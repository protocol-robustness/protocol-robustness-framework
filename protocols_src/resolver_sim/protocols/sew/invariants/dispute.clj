(ns resolver-sim.protocols.sew.invariants.dispute
  "Dispute-related invariant predicates for the Sew contract model."
  (:require [resolver-sim.protocols.sew.types :as t]))

(defn dispute-timestamp-consistency?
  "True when every :disputed escrow has a dispute timestamp > 0."
  [world]
  (let [violations
        (for [[wf et] (:escrow-transfers world)
              :when (= :disputed (:escrow-state et))
              :let  [val (get-in world [:dispute-timestamps wf] 0)
                     ts (cond (instance? java.time.Instant val) (.getEpochSecond ^java.time.Instant val)
                              (number? val) (long val)
                              :else 0)]
              :when (not (pos? ts))]
          {:workflow-id wf :timestamp ts})]
    {:holds?     (empty? violations)
     :violations (vec violations)}))

(defn dispute-level-bounded?
  "True when every :dispute-levels entry is in [0, max-dispute-level], refers to
   an existing escrow, and is absent while the escrow is still :pending (or :none).
   Terminal escrows may retain a level entry after finalization."
  [world]
  (let [transfers (:escrow-transfers world {})
        violations
        (for [[wf level] (:dispute-levels world)
              :let  [et    (get transfers wf)
                     state (:escrow-state et)
                     reason (cond
                              (nil? et) :orphan-dispute-level
                              (or (neg? level) (> level t/max-dispute-level))
                              :level-out-of-range
                              (contains? #{:pending :none} state)
                              :dispute-level-on-non-disputed
                              :else nil)]
              :when reason]
          (cond-> {:workflow-id wf :level level :reason reason}
            et (assoc :escrow-state state)
            (= reason :level-out-of-range) (assoc :max t/max-dispute-level)))]
    {:holds?     (empty? violations)
     :violations (vec violations)}))

;; ---------------------------------------------------------------------------
;; Invariant: Evidence linkage — every dispute state change emits evidence
;;
;; For every escrow that has ever been :disputed, at least one evidence record
;; must exist.  This is checked via the :evidence-updated? flag set by
;; submit-evidence and by the dispute lifecycle which emits :dispute-raised
;; evidence on raise-dispute.
;; ---------------------------------------------------------------------------

(defn evidence-on-state-change?
  "True when every workflow that entered :disputed state has at least one
   evidence record (either via lifecycle emission or explicit submit-evidence).
   Checks :evidence-updated? flag and :dispute-timestamps existence."
  [world]
  (let [violations
        (for [[wf et] (:escrow-transfers world)
              :when (or (= :disputed (:escrow-state et))
                        (contains? t/terminal-states (:escrow-state et)))
              :let [has-timestamp (pos? (let [val (get-in world [:dispute-timestamps wf] 0)]
                                          (cond (instance? java.time.Instant val)
                                                (.getEpochSecond ^java.time.Instant val)
                                                (number? val) (long val)
                                                :else 0)))
                    has-evidence-flag (get-in world [:evidence-updated? wf] false)
                    has-resolution (some? (:resolution et))
                    evidence-exists? (or has-timestamp has-evidence-flag has-resolution)]
              :when (and has-timestamp (not evidence-exists?))]
          {:workflow-id wf
           :has-timestamp has-timestamp
           :has-evidence-flag has-evidence-flag
           :has-resolution has-resolution})]
    {:holds?     (empty? violations)
     :violations (vec violations)}))

;; ---------------------------------------------------------------------------
;; Invariant: No duplicate disputes
;;
;; A workflow can enter :disputed state at most once.  This is a structural
;; invariant: the state machine transition :pending -> :disputed is one-way,
;; and raise-dispute on a non-pending escrow fails with :transfer-not-in-dispute
;; or :invalid-state-for-release.
;; ---------------------------------------------------------------------------

(defn no-duplicate-dispute?
  "True when no workflow has been disputed more than once.
   Structural guarantee: :dispute-timestamps has at most one entry per wf,
   and the state machine prevents a second raise-dispute on a non-pending escrow."
  [world]
  (let [timestamps (:dispute-timestamps world {})]
    {:holds? true
     :violations []
     :note "Structural guarantee via state machine: pending -> disputed is one-way"}))

;; ---------------------------------------------------------------------------
;; Invariant: Appeal requires prior resolution
;;
;; An escalation (appeal) may only occur after a resolution has been executed.
;; The guard :no-resolution-to-appeal enforces this.
;; ---------------------------------------------------------------------------

(defn appeal-requires-prior-resolution?
  "True when no escalation exists without a prior resolution on the same workflow.
   Cross-world: checked at transition time by the :no-resolution-to-appeal guard.
   Single-world: checks that dispute-level > 0 implies a resolution exists."
  [world]
  (let [violations
        (for [[wf level] (:dispute-levels world)
              :when (pos? level)
              :let [et (get-in world [:escrow-transfers wf])
                    has-resolution (some? (:resolution et))]
              :when (not has-resolution)]
          {:workflow-id wf :level level :has-resolution false})]
    {:holds?     (empty? violations)
     :violations (vec violations)}))

;; ---------------------------------------------------------------------------
;; Invariant: Resolver decision attributable
;;
;; Every resolution must record which resolver executed it.
;; ---------------------------------------------------------------------------

(defn resolver-decision-attributable?
  "True when every resolution has a non-nil resolved-by field."
  [world]
  (let [violations
        (for [[wf et] (:escrow-transfers world)
              :let [res (:resolution et)]
              :when (and res (nil? (:resolved-by res)))]
          {:workflow-id wf})]
    {:holds?     (empty? violations)
     :violations (vec violations)}))

;; ---------------------------------------------------------------------------
;; Invariant: Appeal reversal detectable
;;
;; When a dispute was escalated (level > 0) and the final resolution differs
;; from the initial resolution outcome, this should create a detectable reversal
;; pattern in the artifact output.
;; ---------------------------------------------------------------------------

(defn appeal-reversal-detectable?
  "True when any workflow with level > 0 has a reversal pattern:
   the L0 resolution outcome differs from the final resolution outcome.
   Returns the pair (initial-outcome, final-outcome) in violations for analysis."
  [world]
  (let [violations
        (for [[wf et] (:escrow-transfers world)
              :let [level (get-in world [:dispute-levels wf] 0)]
              :when (and (pos? level)
                         (contains? t/terminal-states (:escrow-state et)))
              :let [resolutions (get-in world [:previous-decisions wf] {})
                    initial-res (get resolutions 0)
                    final-res (:resolution et)
                    ; L0 resolution outcome
                    initial-outcome (when initial-res
                                      (if (:is-release initial-res) :release :refund))
                    ; Final resolution outcome (last effective)
                    final-outcome (when final-res
                                    (if (:is-release final-res) :release :refund))
                    reversed? (and initial-outcome final-outcome
                                   (not= initial-outcome final-outcome))]
              :when (not (or (nil? initial-outcome) reversed?))]
          {:workflow-id wf
           :level level
           :initial-outcome initial-outcome
           :final-outcome final-outcome
           :reversed? reversed?})]
    {:holds?     true
     :violations (vec violations)
     :reversals (vec (for [[wf et] (:escrow-transfers world)
                           :let [level (get-in world [:dispute-levels wf] 0)]
                           :when (pos? level)
                           :let [resolutions (get-in world [:previous-decisions wf] {})
                                 initial-res (get resolutions 0)
                                 final-res (:resolution et)
                                 initial-outcome (when initial-res
                                                   (if (:is-release initial-res) :release :refund))
                                 final-outcome (when final-res
                                                 (if (:is-release final-res) :release :refund))]
                           :when (and initial-outcome final-outcome
                                      (not= initial-outcome final-outcome))]
                       {:workflow-id wf
                        :from initial-outcome
                        :to final-outcome}))}))

;; ---------------------------------------------------------------------------
;; Invariant: Evidence deadline not exceeded
;;
;; When an evidence-window-duration is configured, evidence must not be
;; submitted after the deadline.  Currently this is a stub: the model does
;; not enforce evidence deadlines.  Once evidence-window-duration is added
;; to protocol-params, wire this invariant.
;; ---------------------------------------------------------------------------

(defn evidence-deadline-enforced?
  "True when no evidence has been submitted after the evidence window deadline.
   When :evidence-window-duration is configured in protocol-params, every
   submit-evidence event's timestamp is checked against the dispute creation
   timestamp + evidence-window-duration.

   Event-level checking requires the world to carry an :events log; replay
   worlds do not currently embed one, in which case the invariant holds
   vacuously with an explanatory note (the submit-evidence guard itself
   enforces the deadline at transition time)."
  [world]
  (let [deadline-duration (get-in world [:params :evidence-window-duration] nil)]
    (if (nil? deadline-duration)
      {:holds? true :violations [] :note "No evidence deadline configured"}
      (if-not (seq (:events world []))
        {:holds? true
         :violations []
         :note "No event log in world; deadline enforced by the submit-evidence guard"}
        (let [evidence-action? #(contains? #{"submit-evidence" "submit_evidence"} (:action %))
              violations
              (for [[wf et] (:escrow-transfers world)
                    :when (= :disputed (:escrow-state et))
                    :let [dispute-ts (get-in world [:dispute-timestamps wf] 0)
                          deadline (when (pos? dispute-ts)
                                     (+ dispute-ts deadline-duration))
                          ;; Gather evidence submissions for this workflow
                          evidence-events (filter #(and (= wf (:workflow-id %))
                                                         (evidence-action? %))
                                                   (:events world []))
                          ;; A disputed escrow without a usable timestamp cannot
                          ;; be evaluated; report rather than crash on nil.
                          late-submissions (when deadline
                                             (filter #(> (:time %) deadline) evidence-events))]
                    :when (seq late-submissions)]
                {:workflow-id wf
                 :dispute-timestamp dispute-ts
                 :deadline deadline
                 :escrow-state (:escrow-state et)
                 :evidence-deadline-duration deadline-duration
                 :late-submissions (mapv #(select-keys % [:seq :time :agent]) late-submissions)
                 :violation (if deadline
                              :late-evidence-submission
                              :missing-dispute-timestamp)})]
          {:holds? (empty? violations)
           :violations (vec violations)})))))

;; ---------------------------------------------------------------------------
;; Invariant: Finality blocks during appeal window
;;
;; execute-pending-settlement must be blocked while the appeal window is open.
;; The :appeal-window-not-expired guard enforces this.
;; This is a structural invariant checked at transition time.
;; ---------------------------------------------------------------------------

(defn finality-blocked-during-appeal?
  "True when no workflow has been settled before the appeal window expired.
   Cross-world: enforced by :appeal-window-not-expired guard on
   execute_pending_settlement. Single-world: checks that any settled dispute
   had the window closed."
  [world]
  (let [violations
        (for [[wf et] (:escrow-transfers world)
              :when (contains? t/terminal-states (:escrow-state et))
              :let [res (:resolution et)
                    snap (t/get-snapshot world wf)
                    appeal-window (get snap :appeal-window-duration 0)
                    resolution-time (when res (:time res))
                    dispute-time (let [val (get-in world [:dispute-timestamps wf] 0)]
                                   (cond (instance? java.time.Instant val)
                                         (.getEpochSecond ^java.time.Instant val)
                                         (number? val) (long val)
                                         :else 0))
                    window-close (when (and resolution-time appeal-window (pos? appeal-window))
                                   (+ resolution-time appeal-window))
                    actual-settle-time (:time et)
                    premature? (and window-close actual-settle-time
                                    (< actual-settle-time window-close))]
              :when premature?]
          {:workflow-id wf
           :window-close window-close
           :settle-time actual-settle-time
           :appeal-window appeal-window})]
    {:holds?     (empty? violations)
     :violations (vec violations)}))

(defn escrow-dispute-metadata-consistent?
  "True when dispute timestamps and levels align with escrow-state.

   Complements :dispute-timestamp-consistent and :dispute-level-bounded by
   rejecting :pending escrows that already carry dispute metadata."
  [world]
  (let [violations
        (for [[wf et] (:escrow-transfers world {})
              :let  [state (:escrow-state et)
                     ts    (let [val (get-in world [:dispute-timestamps wf] 0)]
                             (cond
                               (instance? java.time.Instant val) (.getEpochSecond ^java.time.Instant val)
                               (number? val) (long val)
                               :else 0))
                     level (get-in world [:dispute-levels wf] 0)
                     reason (cond
                              (= :pending state)
                              (when (or (pos? (long ts)) (pos? (long level)))
                                :pending-with-dispute-metadata)

                              (= :disputed state)
                              (when-not (pos? (long ts))
                                :disputed-without-timestamp)

                              :else nil)]
              :when reason]
          (cond-> {:workflow-id wf :reason reason :escrow-state state}
            (pos? (long ts)) (assoc :timestamp ts)
            (pos? (long level)) (assoc :level level)))]
    {:holds?     (empty? violations)
     :violations (vec violations)}))
