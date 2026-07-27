(ns resolver-sim.protocols.sew.state-machine
  "Pure Clojure port of StateManagementLibrary.sol.

   Each function mirrors one Solidity state transition, including the
   caller-site precondition guards that appear in BaseEscrow.sol.

   All functions return {:ok bool :world world' :error keyword}.
   No side effects. World state is immutable; transitions return a new map.

   ## Declarative transition graph

   `allowed-transitions` is the single authoritative source of which
   EscrowState → EscrowState edges are legal.  Every function that changes
   :escrow-state goes through `apply-transition!`, which consults the graph
   and throws a programming-error exception if an illegal edge is attempted.
   The caller-visible result maps use `valid-transition?` in their guards to
   return typed {:ok false :error kw} results before the edge is attempted.

   ## Note on :resolved

   transitionToResolved() is called by BaseEscrow.acceptSplit for
   mutual-agreement split settlements.  The transition is available from
   both :pending and :disputed states.  :resolved is also retained
   in the graph for enum completeness and Foundry/halmos compatibility."
  (:require [resolver-sim.protocols.sew.types :as t]
            [resolver-sim.time.deadlines      :as dl]
            [resolver-sim.time.context        :as time-ctx]))

;; ---------------------------------------------------------------------------
;; Declarative transition graph
;; ---------------------------------------------------------------------------

(def allowed-transitions
  "Alias to t/allowed-transitions for backward compatibility."
  t/allowed-transitions)

(defn valid-transition?
  "True when :from → :to is an edge in `allowed-transitions`."
  [from to]
  (contains? (get allowed-transitions from #{}) to))

(defn- graph-nodes
  [transitions]
  (set (concat (keys transitions) (mapcat val transitions))))

(defn transition-graph-acyclic?
  "True when `allowed-transitions` has no directed cycles.

   Escrow FSM must be acyclic: terminal states have no outgoing edges and live
   states cannot return to earlier phases (no circular invalid_states)."
  [transitions]
  (let [nodes (graph-nodes transitions)
        color (volatile! (into {} (map (fn [n] [n :white]) nodes)))]
    (letfn [(dfs [n]
              (case (@color n)
                :gray true
                :black false
                (do
                  (vswap! color assoc n :gray)
                  (if (some dfs (get transitions n #{}))
                    true
                    (do (vswap! color assoc n :black) false)))))]
      (not (some #(when (= :white (@color %)) (dfs %)) nodes)))))

(def transition-graph-acyclic
  "Authoritative graph is acyclic — enforced in `state-machine-test`."
  (transition-graph-acyclic? allowed-transitions))

;; ---------------------------------------------------------------------------
;; Internal helpers
;; ---------------------------------------------------------------------------

(defn- update-transfer
  "Apply f to the EscrowTransfer map for workflow-id."
  [world workflow-id f & args]
  (let [path [:escrow-transfers workflow-id]]
    (if-some [cur (get-in world path)]
      (apply update-in world path f args)
      (throw (ex-info "No escrow-transfer found; cannot apply transition"
                      {:workflow-id workflow-id
                       :f           (pr-str f)
                       :args        args})))))

(defn- set-escrow-state
  "Set :escrow-state for workflow-id.  Asserts via the transition graph —
   throws ex-info on an illegal edge (programming error, not a protocol revert)."
  [world workflow-id new-state]
  (let [old-state (t/escrow-state world workflow-id)]
    (when-not (valid-transition? old-state new-state)
      (throw (ex-info "Illegal state transition"
                      {:from old-state :to new-state :workflow-id workflow-id})))
    (update-transfer world workflow-id assoc :escrow-state new-state)))

;; ---------------------------------------------------------------------------
;; Guard: workflow must exist
;; ---------------------------------------------------------------------------

(defn- assert-workflow
  "Return {:ok false :error :invalid-workflow-id} when workflow-id is absent."
  [world workflow-id]
  (when-not (t/valid-workflow-id? world workflow-id)
    (t/fail :invalid-workflow-id)))

;; ---------------------------------------------------------------------------
;; Guard helper: require-live-state
;;
;; Shared precondition check for domain functions that operate on escrows.
;; Combines the workflow-exists check, the terminal-state rejection, and the
;; expected-state check into one call.
;;
;; Returns nil when all checks pass (nil is falsy in `or` guard chains).
;; Returns {:ok false :error kw} on failure.
;; ---------------------------------------------------------------------------

(defn require-live-state
  "Guard helper: verify workflow-id exists, is not terminal, and (optionally)
   is in the expected state.

   Usage:
     (or (require-live-state world wf :pending :transfer-not-pending)
         (do-the-thing world wf ...))

   Returns nil when all checks pass.  Returns {:ok false :error kw} otherwise.
   Throws on :dispatch-exception only (internal programming error)."
  [world workflow-id expected-state state-error]
  (or (assert-workflow world workflow-id)
      (let [state (t/escrow-state world workflow-id)]
        (cond
          (nil? state)
          nil  ;; workflow exists in workflow-id map but transfer key is missing — let caller handle

          (t/terminal-state? world workflow-id)
          (t/fail :transfer-not-finalized)

          (and (some? expected-state) (not= state expected-state))
          (t/fail (or state-error :invalid-state))

          :else nil))))

;; ---------------------------------------------------------------------------
;; Public: apply-transition!
;;
;; Lower-level helper for finalization functions (lifecycle, resolution) that
;; have already validated their own guards and just need to commit a state
;; change through the graph.  Returns the updated world directly (not a result
;; map) so it can be used inside -> threads.
;; Throws ex-info on an illegal edge — same as set-escrow-state.
;; ---------------------------------------------------------------------------

(defn apply-transition!
  "Commit a checked state change for workflow-id → new-state.
   Returns the new world map.  Throws on an illegal graph edge."
  [world workflow-id new-state]
  (set-escrow-state world workflow-id new-state))

;; ---------------------------------------------------------------------------
;; Transition Registry (Declarative Logic)
;; ---------------------------------------------------------------------------

(def transitions
  "Declarative registry of protocol transitions.
   Each entry defines:
     :from         — starting state(s)
     :to           — destination state
     :guards       — list of registry keys
     :effects      — list of registry keys
     :state-error  — legacy error keyword for invalid initial state
     :guard-error  — map of {guard-kw legacy-error-kw}"
  {:to-disputed
   {:from         #{:pending}
    :to           :disputed
    :guards       [:participant?]
    :effects      [:set-raise-dispute-status :record-dispute-timestamp]
    :state-error  :transfer-not-pending
    :guard-error  {:participant? :not-participant}}

   :to-released
   {:from         #{:pending :disputed}
    :to           :released
    :guards       []
    :effects      []
    :state-error  :invalid-state-for-release}

   :to-refunded
   {:from         #{:pending :disputed}
    :to           :refunded
    :guards       []
    :effects      []
    :state-error  :invalid-state-for-refund}

   :to-resolved
   {:from         #{:disputed}
    :to           :resolved
    :guards       [:terminal-transfer-done?]
    :effects      []
    :state-error  :transfer-not-in-dispute
    :guard-error  {:terminal-transfer-done? :resolution-without-settlement}}})

;; ---------------------------------------------------------------------------
;; Guard & Effect Implementations
;; ---------------------------------------------------------------------------

(defn- terminal-transfer-done?
  "True when the escrow's amount-after-fee is no longer reflected in total-held
   for its token, i.e. the release/refund transfer accounting has already run."
  [world workflow-id]
  (let [et    (t/get-transfer world workflow-id)
        token (:token et)
        held  (get-in world [:total-held token] 0)
        other-live  (reduce (fn [acc [wf e]]
                              (if (and (not= wf workflow-id)
                                       (= (:token e) token)
                                       (contains? t/live-states (:escrow-state e)))
                                (+ acc (:amount-after-fee e))
                                acc))
                            0
                            (:escrow-transfers world))]
    (= held other-live)))

(def registry
  {:guards
   {:participant?
    (fn [world workflow-id caller]
      (let [et (t/get-transfer world workflow-id)]
        (or (= caller (:from et)) (= caller (:to et)))))

    :terminal-transfer-done?
    (fn [world workflow-id _]
      (terminal-transfer-done? world workflow-id))}

   :effects
   {:set-raise-dispute-status
    (fn [world workflow-id caller]
      (let [et (t/get-transfer world workflow-id)
            is-sender? (= caller (:from et))]
        (update-transfer world workflow-id
                         (if is-sender?
                           #(assoc % :sender-status    :raise-dispute
                                   :recipient-status  :none)
                           #(assoc % :recipient-status :raise-dispute
                                   :sender-status     :none)))))

    :record-dispute-timestamp
    (fn [world workflow-id _]
      (assoc-in world [:dispute-timestamps workflow-id] (time-ctx/block-ts world)))}})

(defn- run-guards [world workflow-id caller txn]
  (reduce (fn [_ g-kw]
            (if-let [g-fn (get-in registry [:guards g-kw])]
              (if (g-fn world workflow-id caller)
                {:ok true}
                (reduced (t/fail (get-in txn [:guard-error g-kw] :guard-failed))))
              (throw (ex-info "Unknown guard" {:guard g-kw}))))
          {:ok true}
          (:guards txn)))

(defn- apply-effects [world workflow-id caller effects]
  (reduce (fn [w e-kw]
            (if-let [e-fn (get-in registry [:effects e-kw])]
              (e-fn w workflow-id caller)
              (throw (ex-info "Unknown effect" {:effect e-kw}))))
          world
          effects))

(defn execute-transition
  "Generic engine to execute a declarative transition."
  [world workflow-id caller transition-kw]
  (if-let [txn (get transitions transition-kw)]
    (or (assert-workflow world workflow-id)
        (let [et    (t/get-transfer world workflow-id)
              state (:escrow-state et)]
          (cond
            (not (contains? (:from txn) state))
            (t/fail (:state-error txn :invalid-state))

            :else
            (let [g-res (run-guards world workflow-id caller txn)]
              (if-not (:ok g-res)
                g-res
                (let [world' (-> world
                                 (set-escrow-state workflow-id (:to txn))
                                 (apply-effects workflow-id caller (:effects txn)))]
                  (t/ok world')))))))
    (throw (ex-info "Unknown transition" {:transition transition-kw}))))

;; ---------------------------------------------------------------------------
;; Public Transitions (Refactored to use execute-transition)
;; ---------------------------------------------------------------------------

(defn transition-to-disputed
  "Transition a :pending escrow to :disputed."
  [world workflow-id caller]
  (execute-transition world workflow-id caller :to-disputed))

(defn transition-to-released
  "Transition a :pending or :disputed escrow to :released."
  [world workflow-id]
  (execute-transition world workflow-id nil :to-released))

(defn transition-to-refunded
  "Transition a :pending or :disputed escrow to :refunded."
  [world workflow-id]
  (execute-transition world workflow-id nil :to-refunded))

(defn transition-to-resolved
  "Transition a :disputed escrow to :resolved."
  [world workflow-id]
  (execute-transition world workflow-id nil :to-resolved))

;; ---------------------------------------------------------------------------
;; Valid status-combination predicate
;;
;; Mirrors the protocol constraints on (EscrowState × SenderStatus × RecipientStatus).
;; ---------------------------------------------------------------------------

(defn valid-status-combination?
  "True when {:escrow-state :sender-status :recipient-status} is a valid
   combination according to the Sew protocol."
  [{:keys [escrow-state sender-status recipient-status]}]
  (cond
    ;; Terminal states: party statuses are cleared at finalization (cancellation-mutex)
    (contains? t/terminal-states escrow-state)
    (and (= :none sender-status) (= :none recipient-status))

    ;; :none — pre-creation or uninitialized; both statuses must be :none
    (= :none escrow-state)
    (and (= :none sender-status) (= :none recipient-status))

    ;; :pending — AGREE_TO_CANCEL is valid; RAISE_DISPUTE is not
    (= :pending escrow-state)
    (and (contains? (disj t/sender-statuses :raise-dispute) sender-status)
         (contains? (disj t/sender-statuses :raise-dispute) recipient-status))

    ;; :disputed — exactly one party has RAISE_DISPUTE; neither has AGREE_TO_CANCEL
    (= :disputed escrow-state)
    (and (contains? (disj t/sender-statuses :agree-to-cancel) sender-status)
         (contains? (disj t/sender-statuses :agree-to-cancel) recipient-status)
         (not= :agree-to-cancel sender-status)
         (not= :agree-to-cancel recipient-status)
         ;; At least one party must have raised the dispute
         (or (= :raise-dispute sender-status)
             (= :raise-dispute recipient-status))
         ;; Both cannot have raised (only one raiseDispute call is accepted)
         (not (and (= :raise-dispute sender-status)
                   (= :raise-dispute recipient-status))))

    :else false))

;; ---------------------------------------------------------------------------
;; Mutual-consent cancel state setters
;;
;; These are not transitions to terminal states — they record agreement
;; so the next check can decide whether to finalise.
;; ---------------------------------------------------------------------------

(defn set-sender-agree-to-cancel
  "Record senderStatus = :agree-to-cancel.
   Guard: state must be :pending, caller must be :from."
  [world workflow-id caller]
  (or (assert-workflow world workflow-id)
      (let [et (t/get-transfer world workflow-id)]
        (cond
          (not= :pending (:escrow-state et))
          (t/fail :transfer-not-pending)

          (not= caller (:from et))
          (t/fail :not-sender)

          :else
          (t/ok (update-transfer world workflow-id assoc :sender-status :agree-to-cancel))))))

(defn set-recipient-agree-to-cancel
  "Record recipientStatus = :agree-to-cancel.
   Guard: state must be :pending, caller must be :to."
  [world workflow-id caller]
  (or (assert-workflow world workflow-id)
      (let [et (t/get-transfer world workflow-id)]
        (cond
          (not= :pending (:escrow-state et))
          (t/fail :transfer-not-pending)

          (not= caller (:to et))
          (t/fail :not-recipient)

          :else
          (t/ok (update-transfer world workflow-id assoc :recipient-status :agree-to-cancel))))))

;; ---------------------------------------------------------------------------
;; Compound: mutual-cancel check
;; ---------------------------------------------------------------------------

(defn both-agreed-to-cancel?
  "True if both sender and recipient have set AGREE_TO_CANCEL."
  [world workflow-id]
  (let [et (t/get-transfer world workflow-id)]
    (and (= :agree-to-cancel (:sender-status et))
         (= :agree-to-cancel (:recipient-status et)))))

;; ---------------------------------------------------------------------------
;; Timed-action predicates
;; ---------------------------------------------------------------------------

(defn- deadline-due?
  "Shared helper for auto-release and auto-cancel deadlines.
   Returns true when the escrow is :pending with a positive deadline timestamp
   that has expired.  nil-safe: returns false for invalid workflow-ids (where
   `t/get-transfer` returns nil) because (= nil :pending) is false.

   `field` — the escrow key holding the deadline timestamp (:auto-release-time
          or :auto-cancel-time)."
  [world workflow-id field]
  (let [et  (t/get-transfer world workflow-id)
        ts  (get et field)]
    ;; Order matters: the (= :pending ...) check MUST come before (pos? ...).
    ;; If the escrow-transfer is nil (invalid wf-id) or the state is wrong,
    ;; short-circuit prevents (pos? nil) from crashing.
    (and (= :pending (:escrow-state et))
         (pos? ts)
         (dl/deadline-expired? (time-ctx/block-ts world) ts))))

(defn auto-release-due?
  "True when auto-release-time has passed and escrow is still :pending."
  [world workflow-id]
  (deadline-due? world workflow-id :auto-release-time))

(defn auto-cancel-due?
  "True when auto-cancel-time has passed and escrow is still :pending."
  [world workflow-id]
  (deadline-due? world workflow-id :auto-cancel-time))

(defn auto-cancel-due-on-disputed?
  "Solidity shadow: ACTION_AUTO_CANCEL_DISPUTED (computeTimedActions, SettlementOps.sol).

   True when a DISPUTED escrow has auto-cancel-time set and the
   deadline has passed, with no pending settlement blocking cleanup.

   Without this check, a frivolous dispute raised before auto-cancel-time
   orphans the deadline (auto-cancel-due? only fires for :pending state),
   forcing the escrow into the longer max-dispute-duration path.

   This is a known griefing vector: adversary raises dispute just before
   auto-cancel-time to block automated cancel, locking the escrow until
   max-dispute-duration elapses."
  [world workflow-id]
  (let [state  (t/escrow-state world workflow-id)
        et     (t/get-transfer world workflow-id)
        ts     (:auto-cancel-time et)]
    (and (= :disputed state)
         (pos? ts)
         (dl/deadline-expired? (time-ctx/block-ts world) ts)
         ;; Must not have a pending settlement (don't override resolver)
         (not (:exists (t/get-pending world workflow-id))))))

(defn dispute-timeout-exceeded?
  "True when max-dispute-duration has elapsed since raiseDispute and
   no pending-settlement exists."
  [world workflow-id]
  (let [state    (t/escrow-state world workflow-id)
        ts       (get-in world [:dispute-timestamps workflow-id] 0)
        snap     (t/get-snapshot world workflow-id)
        max-dur  (get snap :max-dispute-duration 0)
        pending  (t/get-pending world workflow-id)]
    (and (= :disputed state)
         (not (:exists pending))
         (pos? ts)
         (pos? max-dur)
         (dl/deadline-expired? (time-ctx/block-ts world) (dl/deadline ts max-dur)))))

(defn pending-settlement-executable?
  "True when a pending-settlement exists and its appeal-deadline has passed
   (or eligible superseded pending exists when no active pending exists),
   and state is :disputed.

   Only superseded pendings at the current dispute level are considered:
   a superseded pending from a lower level was archived by an escalation
   and should not be executed until the higher-level resolver acts."
  [world workflow-id]
  (let [pending       (t/get-pending world workflow-id)
        state         (t/escrow-state world workflow-id)
        now-ts        (time-ctx/block-ts world)
        current-level (t/dispute-level world workflow-id)]
    (and (= :disputed state)
         (if (:exists pending)
           (dl/deadline-expired? now-ts (:appeal-deadline pending))
           (some #(and (= (:level %) current-level)
                       (dl/deadline-expired? now-ts (:appeal-deadline (:pending %))))
                 (get-in world [:superseded-pending-settlements workflow-id] []))))))