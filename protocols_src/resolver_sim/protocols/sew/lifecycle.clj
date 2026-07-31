(ns resolver-sim.protocols.sew.lifecycle
  "Pure Clojure port of BaseEscrow escrow lifecycle operations.

   Covers:
     create-escrow     — createEscrow (fee deduction, snapshot, auto-times)
     release           — release (release strategy consulted via stub)
     sender-cancel     — senderCancel (mutual consent or unilateral)
     recipient-cancel  — recipientCancel (mutual consent or unilateral)
     auto-cancel-disputed-escrow — autoCancelDisputedEscrow (dispute timeout)

   raise-dispute is in state_machine.clj (transition-to-disputed) since it
   delegates entirely to the state transition; the lifecycle wrapper is here.

   All functions return {:ok bool :world world' :error keyword}.
   Arithmetic: uint256 integer division (no rounding)."
  (:require [resolver-sim.protocols.sew.types         :as t]
            [resolver-sim.protocols.sew.state-machine :as sm]
            [resolver-sim.protocols.sew.accounting    :as acct]
            [resolver-sim.protocols.sew.registry      :as reg]
            [resolver-sim.protocols.sew.economics     :as sew-econ]
            [resolver-sim.yield.ops                    :as yield-ops]
            [resolver-sim.yield.partial-fill            :as partial-fill]
            [resolver-sim.yield.module                 :as yield-module]
            [resolver-sim.yield.accounting             :as yield-acct]
            [resolver-sim.yield.expectations           :as yield-exp]
            [resolver-sim.yield.registry               :as yield-reg]
            [resolver-sim.protocols.sew.yield.policy  :as yield-policy]
            [resolver-sim.util.attribution             :as attr]
            [resolver-sim.util.attributed-monad        :as am]
            [resolver-sim.util.state-monad             :as monad]
            [resolver-sim.time.context                 :as time-ctx]
            [resolver-sim.evidence.capture            :as cap]))

;; ---------------------------------------------------------------------------
;; Guard logging helper — returns (t/fail kw) with :guard-context attached
;; so process-step can capture rejection context in trace entries.
;; ---------------------------------------------------------------------------

(defn- guard-fail [error-kw & {:as ctx}]
  (attr/log-with-attr :debug "guard/rejected" (assoc ctx :error error-kw))
  (assoc (t/fail error-kw) :guard-context ctx))

;; ---------------------------------------------------------------------------
;; Internal accounting helpers
;; ---------------------------------------------------------------------------

(defn- token-available? [world token]
  (not (contains? (:token-liquidity-crunch world) token)))

(defn- yield-module-available? [world module-id token]
  (if (nil? module-id)
    true
    (let [mid    (keyword module-id)
          status (get-in world [:yield/module-status mid] :active)
          mode   (get-in world [:yield/risk mid token :liquidity-mode] :available)]
      (and (= status :active)
           (not (contains? yield-acct/liquidity-modes mode))))))

(defn- resolver-available? [world resolver]
  (if (or (nil? resolver) (= resolver t/zero-address))
    true
    (let [capacity-ok? (not (t/resolver-at-capacity? world resolver))
          freeze-expiry (get-in world [:resolver-frozen-until resolver] 0)
          unfrozen?     (<= freeze-expiry (time-ctx/block-ts world))]
      (and capacity-ok? unfrozen?))))

;; ---------------------------------------------------------------------------
;; Internal: _cancelAndRefund + _releaseEscrowTransfer
;;
;; Both clear pending-settlement, subtract total-held, then transition state.
;; The push/fallback transfer distinction is abstracted — the model records
;; either a state change or a claimable balance entry.
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Yield Accrual
;; ---------------------------------------------------------------------------

(declare accrue-yield)

(defn resolver-yield-owner-id
  "Canonical yield position owner id for resolver stake."
  [resolver-addr]
  (str "resolver:" resolver-addr))

(defn init-resolver-yield-accrual-time
  "Anchor resolver yield accrual clock at the current block time."
  [world resolver-addr]
  (assoc-in world [:resolver-yield-accrual-times resolver-addr] (time-ctx/block-ts world)))

(defn accrue-resolver-yield
  "Advance yield for a resolver staking position by elapsed time since last accrual."
  [world resolver-addr token]
  (attr/with-attribution {:yield/target-type :resolver
                          :yield/resolver-addr resolver-addr}
    (let [profile-id (reg/get-resolver-yield-profile world resolver-addr)]
      (if profile-id
        (let [{:keys [module-id]} (yield-reg/resolve-yield-profile profile-id)
              owner-id (resolver-yield-owner-id resolver-addr)
              now      (time-ctx/block-ts world)
              last     (get-in world [:resolver-yield-accrual-times resolver-addr] now)
              dt       (- now last)
              tok      (if (keyword? token) token (keyword token))]
          (if (pos? dt)
            (let [pos-before (get-in world [:yield/positions owner-id])
                  world' (yield-ops/apply-yield-op
                          world {:op/type :yield/accrue
                                 :module/id module-id
                                 :owner/id owner-id
                                 :token tok
                                 :dt dt})
                  pos (get-in world' [:yield/positions owner-id])
                  yield-before (+ (:unrealized-yield pos-before 0) (:realized-yield pos-before 0))
                  yield-after  (+ (:unrealized-yield pos 0) (:realized-yield pos 0))
                  yield-delta (- yield-after yield-before)]
              (-> world'
                  (cond-> (pos? yield-delta)
                    (acct/add-held tok yield-delta {:action "accrue-resolver-yield"
                                                    :reason :resolver-yield-accrued
                                                    :extra {:held/resolver resolver-addr
                                                            :held/owner-id owner-id}})
                    (neg? yield-delta)
                    (acct/sub-held tok (- yield-delta) {:action "accrue-resolver-yield"
                                                        :reason :resolver-yield-loss
                                                        :extra {:held/resolver resolver-addr
                                                                :held/owner-id owner-id}}))
                  (assoc-in [:resolver-yield-accrual-times resolver-addr] now)))
            world))
        world))))

;; ---------------------------------------------------------------------------
;; Internal: finalize helpers (no accounting — see lifecycle for that)
;; ---------------------------------------------------------------------------

(defn- reserve-deferred-yield-custody
  "Reclassify terminal shortfall residue from escrow principal to yield custody.
   This is a zero-net ledger transfer: the deferred liability remains held but
   becomes recoverable through the :deferred-yield-claimed position."
  [world token workflow-id deferred]
  (let [yield-position [:held/position token :yield-custody workflow-id]
        principal-position [:held/position token :escrow-principal workflow-id]
        already-reserved (get-in world [:held/positions yield-position] 0)
        required (max 0 (- (long deferred) (long already-reserved)))
        principal-held (get-in world [:held/positions principal-position] 0)]
    (when (> required principal-held)
      (throw (ex-info "deferred yield exceeds terminal principal residue"
                      {:type :invalid-deferred-yield-custody
                       :workflow-id workflow-id
                       :deferred deferred
                       :already-reserved already-reserved
                       :principal-held principal-held})))
    (if (zero? required)
      world
      (-> world
          (acct/sub-held token required
                         {:action "reserve-deferred-yield"
                          :reason :deferred-yield-reclassified-out
                          :extra {:held/workflow-id workflow-id}})
          (acct/add-held token required
                         {:action "reserve-deferred-yield"
                          :reason :deferred-yield-reserved
                          :extra {:held/workflow-id workflow-id}})))))

(defn- finalize
  "Internal: transition escrow to terminal state, release accounting.
   direction — :released (to recipient) or :refunded (to sender).

   Optional opts:
   - authorization-provenance — when present, the escrow settlement uses
     :force-authorised-release or :force-authorised-refund as the held reason
     and carries the authorization provenance."

  [world workflow-id direction & {:keys [authorization-provenance]}]
  (let [et        (t/get-transfer world workflow-id)
        token     (:token et)
        released-so-far (get-in world [:amount-released workflow-id] 0)
        amt       (- (:amount-after-fee et) released-so-far)
        fot-bps   (get-in world [:token-fot-bps token] 0)
        net-amt   (- amt (t/compute-fee amt fot-bps))
        snap      (t/get-snapshot world workflow-id)
        mid       (:yield-generation-module snap)
        owner-id  (t/escrow-yield-owner-id workflow-id)
        recipient (if (= direction :released) (:to et) (:from et))
        record-fn (if (= direction :released) acct/record-released acct/record-refunded)
        held-reason (if authorization-provenance
                      (if (= direction :released)
                        :force-authorised-release
                        :force-authorised-refund)
                      (if (= direction :released)
                        :escrow-settlement-released
                        :escrow-settlement-refunded))
        ;; Run accrue + withdraw first so we can inspect the shortfall result
        world-after-yield
        (-> world
            (accrue-yield workflow-id)
            (cond-> (and mid (contains? (:yield/modules world) mid))
              (yield-ops/apply-yield-op {:op/type :yield/withdraw
                                         :module/id mid
                                         :owner/id owner-id})))
        ;; Under a liquidity shortfall, only fulfilled-amount is immediately settleable.
        ;; Use net-amt when no yield module is involved or no shortfall occurred.
        pos           (when mid (get-in world-after-yield [:yield/positions owner-id]))
        pos-shortfall (:shortfall pos)
        partial-yield? (and pos pos-shortfall
                            (yield-acct/partial-yield-shortfall? pos pos-shortfall))
        ;; Partial-yield shortfall: principal is immediate; liquid yield is settled in policy.
        principal-immediate (if partial-yield? (:principal pos 0) net-amt)
        world-after-policy
        (yield-policy/apply-yield-policy world-after-yield workflow-id direction)
        ;; Sub-held (computed after policy so accrual-in-held is reconciled):
        ;; - no-shortfall: remove gross afa (amt), capped at available total-held
        ;; - partial-yield shortfall: held after policy minus deferred obligation
        ;; - gross shortfall: fulfilled only (deferred remains in :total-held)
        held-after-policy (get-in world-after-policy [:total-held token] 0)
        raw-settle-amt (if pos-shortfall
                         (if partial-yield?
                           principal-immediate
                           (:fulfilled-amount pos-shortfall 0))
                         net-amt)
        sub-held-amt  (if pos-shortfall
                        (let [fulfilled (:fulfilled-amount pos-shortfall 0)
                              deferred  (:deferred-amount pos-shortfall 0)
                              haircut   (:haircut-amount pos-shortfall 0)]
                          (cond
                            partial-yield?
                            (- held-after-policy deferred)
                            (pos? deferred) fulfilled
                            (>= held-after-policy (+ fulfilled haircut)) (+ fulfilled haircut)
                            :else fulfilled))
                        (min raw-settle-amt held-after-policy))
        settled-amt sub-held-amt
        shortfall-started (:started-at pos-shortfall)
        principal-position [:held/position token :escrow-principal workflow-id]
        principal-position-held (get-in world-after-policy [:held/positions principal-position] 0)
        evidence-reason (if (= direction :released) :escrow-released :escrow-refunded)]
    (if (< principal-position-held sub-held-amt)
      ;; Settlement-boundary guard: the settlement amount must not exceed the
      ;; escrow's own custody position. A negative-yield (mark-to-market)
      ;; write-down can drain the escrow-principal position below the remaining
      ;; principal; that is a supported reachable state, so it fails as a
      ;; structured rejection here — BEFORE sub-held — rather than surfacing the
      ;; accounting-layer :sub-held-position-underflow assertion as an exception.
      ;; The accounting-layer underflow check remains in place as defense-in-depth.
      (guard-fail :insufficient-custody-position
                  :workflow-id workflow-id
                  :token token
                  :direction direction
                  :settlement-amount sub-held-amt
                  :position-id principal-position
                  :position-held principal-position-held)
      (let [result (-> world-after-policy
                       (acct/sub-held token
                                      sub-held-amt
                                      {:action (str "finalize-" (name direction))
                                       :reason held-reason
                                       :authorization-provenance authorization-provenance
                                       :extra (cond-> {:held/action (str "finalize-" (name direction))
                                                       :held/workflow-id workflow-id
                                                       :owner/address recipient
                                                       :held/recipient recipient
                                                       :held/settlement-direction direction
                                                       :held/settled-amount settled-amt}
                                                 shortfall-started
                                                 (assoc :shortfall/started-at shortfall-started))})
                       (record-fn token settled-amt)
                       (cond-> (pos? (long (or (:deferred-amount pos-shortfall) 0)))
                         (reserve-deferred-yield-custody token workflow-id
                                                         (:deferred-amount pos-shortfall)))
                       ;; Track outbound FoT fee
                       (update-in [:total-fot-fees token] (fnil + 0) (- amt net-amt))
                       ;; Principal claimable
                       (acct/record-claimable-v2 workflow-id :settlement/principal recipient settled-amt)
                       (update :pending-settlements dissoc workflow-id)
                       (sm/apply-transition! workflow-id direction)
                       ;; Reset dispute/cancel statuses
                       (update-in [:escrow-transfers workflow-id] assoc :sender-status :none :recipient-status :none)
                       ;; Clean up dispute timestamp on terminal state
                       (update :dispute-timestamps dissoc workflow-id))]
        (attr/with-attribution {:subject/type :escrow
                                :subject/id workflow-id
                                :action/type (keyword "escrow" (name direction))
                                :evidence/reason evidence-reason}
          (cap/capture-event-evidence!
           evidence-reason
            {:finalize/before
             {:workflow-state (t/escrow-state world workflow-id)
              :total-held (get-in world [:total-held token] 0)
              :resolver (:dispute-resolver et)}}
            {:finalize/after
             {:workflow-state (t/escrow-state result workflow-id)
              :total-held (get-in result [:total-held token] 0)}}
            {:finalize/workflow-id workflow-id
             :finalize/direction direction
             :finalize/recipient recipient
             :finalize/settled-amount settled-amt
             :finalize/sub-held-amount sub-held-amt
             :finalize/partial-yield? (boolean partial-yield?)
             :finalize/shortfall? (boolean pos-shortfall)
             :finalize/resolver (:dispute-resolver et)
             :finalize/authorization-id (some-> authorization-provenance :authorization/id)
             :finalize/authorization-type (some-> authorization-provenance :authorization/type)
             :force-auth/auth-id (some-> authorization-provenance :authorization/id)}
            nil
           {:world-before world
            :world-after result}))
        (t/ok result)))))

(defn finalize-escrow-accounting
  "Shared finalize accounting for release/refund and resolution paths.
  Optional opts:
  - authorization-provenance — forwarded to finalize for force-authorised
    escrow settlement."
  [world workflow-id direction & {:keys [authorization-provenance]}]
  (finalize world workflow-id direction
            :authorization-provenance authorization-provenance))

(defn apply-partial-fill-settlement
  "Apply a validated partial-fill decision and its corresponding Sew custody
   movements atomically. The generic yield helper only changes the position;
   this protocol boundary records canonical, partition-bounded held adjustments.

   `opts` must explicitly bind the payout to an escrow workflow and recipient:
   {:workflow-id id :recipient address}. A decision may pay principal and yield;
   those amounts are debited from their respective custody partitions."
  [world position decision {:keys [workflow-id recipient action]
                            :or {action "partial-fill-settlement"}}]
  (when (nil? workflow-id)
    (throw (ex-info "partial-fill settlement requires workflow id"
                    {:type :invalid-partial-fill-settlement
                     :reason :missing-workflow-id})))
  (when (nil? recipient)
    (throw (ex-info "partial-fill settlement requires recipient"
                    {:type :invalid-partial-fill-settlement
                     :reason :missing-recipient
                     :workflow-id workflow-id})))
  (let [owner-id (or (:owner/id position)
                     (-> (resolver-sim.yield.position/position-identity position) second))
        current-position (get-in world [:yield/positions owner-id])
        token (:token position)
        filled (:filled decision {})
        principal (long (get filled :principal 0))
        yield-amount (+ (long (get filled :realized-yield 0))
                        (long (get filled :deferred-yield 0)))]
    (when-not current-position
      (throw (ex-info "partial-fill position not found in world"
                      {:type :invalid-partial-fill-settlement
                       :reason :position-not-found
                       :owner-id owner-id})))
    (when-not (= position current-position)
      (throw (ex-info "partial-fill position is stale"
                      {:type :invalid-partial-fill-settlement
                       :reason :stale-position
                       :owner-id owner-id})))
    (when (not= (partial-fill/filled-total decision) (+ principal yield-amount))
      (throw (ex-info "partial-fill decision contains unsupported payout buckets"
                      {:type :invalid-partial-fill-settlement
                       :reason :unsupported-filled-bucket
                       :filled filled})))
    (partial-fill/validate-partial-fill-application! current-position decision)
    (let [world' (partial-fill/apply-partial-fill world current-position decision)
          settled (cond-> world'
                    (pos? principal)
                    (acct/sub-held token principal
                                   {:action action
                                    :reason :escrow-settlement-released
                                    :extra {:held/workflow-id workflow-id
                                            :owner/address recipient
                                            :held/recipient recipient
                                            :held/partial-fill-owner-id owner-id}})
                    (pos? yield-amount)
                    (acct/sub-held token yield-amount
                                   {:action action
                                    :reason :yield-distributed
                                    :extra {:held/workflow-id workflow-id
                                            :owner/address recipient
                                            :held/recipient recipient
                                            :held/partial-fill-owner-id owner-id}}))]
      (attr/with-attribution {:subject/type :escrow
                              :subject/id workflow-id
                              :action/type :yield/partial-fill-settlement
                              :evidence/reason :partial-fill-settled}
        (cap/capture-event-evidence!
         :partial-fill-settled
         {:settlement/before {:total-held (get-in world [:total-held token] 0)
                              :position current-position}}
         {:settlement/after {:total-held (get-in settled [:total-held token] 0)
                             :position (get-in settled [:yield/positions owner-id])}}
         {:settlement/workflow-id workflow-id
          :settlement/recipient recipient
          :settlement/token token
          :settlement/filled (partial-fill/filled-total decision)
          :settlement/decision decision}
         nil
         {:world-before world :world-after settled}))
      settled)))

(defn apply-deferred-yield-claim-settlement
  "Settle recovered deferred yield for one escrow position.

   Generic yield recovery only updates position state. This Sew boundary binds
   the recovered amount to a terminal workflow, removes it from the canonical
   yield-custody partition, creates the recipient's yield claimable, and emits
   settlement evidence."
  [world workflow-id owner-id recipient reclaimed]
  (when-not (t/valid-workflow-id? world workflow-id)
    (throw (ex-info "deferred yield claim requires an existing workflow"
                    {:type :invalid-deferred-yield-claim
                     :reason :invalid-workflow-id
                     :workflow-id workflow-id})))
  (when-not (and (string? recipient) (not (clojure.string/blank? recipient)))
    (throw (ex-info "deferred yield claim requires recipient"
                    {:type :invalid-deferred-yield-claim
                     :reason :missing-recipient
                     :workflow-id workflow-id})))
  (when-not (and (integer? reclaimed) (pos? reclaimed))
    (throw (ex-info "deferred yield claim requires positive recovered amount"
                    {:type :invalid-deferred-yield-claim
                     :reason :invalid-reclaimed-amount
                     :amount reclaimed})))
  (let [et (t/get-transfer world workflow-id)
        token (:token et)
        position (get-in world [:yield/positions owner-id])]
    (when-not position
      (throw (ex-info "deferred yield position not found"
                      {:type :invalid-deferred-yield-claim
                       :reason :position-not-found
                       :owner-id owner-id})))
    (when-not (= token (:token position))
      (throw (ex-info "deferred yield position token differs from workflow"
                      {:type :invalid-deferred-yield-claim
                       :reason :token-mismatch
                       :workflow-id workflow-id
                       :workflow-token token
                       :position-token (:token position)})))
    (let [settled (-> world
                      (acct/sub-held token reclaimed
                                     {:action "claim-deferred-yield"
                                      :reason :deferred-yield-claimed
                                      :extra {:held/action "claim-deferred-yield"
                                              :held/workflow-id workflow-id
                                              :held/owner-id owner-id
                                              :owner/address recipient
                                              :held/recipient recipient}})
                      (acct/record-claimable-v2 workflow-id :settlement/yield recipient reclaimed))]
      (attr/with-attribution {:subject/type :escrow
                              :subject/id workflow-id
                              :action/type :yield/deferred-claim-settlement
                              :evidence/reason :deferred-yield-claimed}
        (cap/capture-event-evidence!
         :deferred-yield-claimed
         {:claim/before {:total-held (get-in world [:total-held token] 0)
                         :position position}}
         {:claim/after {:total-held (get-in settled [:total-held token] 0)
                        :claimable (get-in settled [:claimable-v2 workflow-id :settlement/yield recipient] 0)}}
         {:claim/workflow-id workflow-id
          :claim/owner-id owner-id
          :claim/recipient recipient
          :claim/token token
          :claim/reclaimed reclaimed}
         nil
         {:world-before world :world-after settled}))
      settled)))

;;
;; Mirrors: BaseEscrow.createEscrow
;;
;; Guards:
;;   1. token must be non-nil
;;   2. to must be non-nil
;;   3. amount must be positive
;;   4. Cannot set both autoReleaseTime and autoCancelTime (CannotSetBothAutoTimes)
;;
;; Accounting:
;;   fee             = amount * escrow-fee-bps / 10000 (integer division)
;;   amount-after-fee = amount - fee
;;   total-held[token] += amount-after-fee
;;   total-fees[token] += fee
;;
;; Auto-times logic (_applyEscrowSettings):
;;   If both settings times are 0 and defaults exist, apply defaults.
;;   Auto-release and auto-cancel are mutually exclusive.
;;
;; Returns {:ok true :world world' :workflow-id id} on success.
;; ---------------------------------------------------------------------------

(defn create-escrow
  "Create a new escrow, assign next workflow-id.

   world       — current world state
   caller      — address of msg.sender (:from)
   token       — ERC20 token address
   to          — recipient address
   amount      — gross amount (uint256)
   settings    — EscrowSettings map (see types/make-escrow-settings)
   snapshot    — ModuleSnapshot map (pre-computed by caller, mirrors _snapshotModulesForEscrow)

   The snapshot is passed in rather than derived internally so the model
   remains pure: callers supply the governance config state they want to test."
  [world caller token to amount settings snapshot]
  (let [token (keyword token)]
    (cond
      (nil? token)
      (t/fail :invalid-token)

      (not (token-available? world token))
      (t/fail :token-liquidity-crunch)

      (nil? to)
      (t/fail :invalid-recipient)

      (<= amount 0)
      (t/fail :amount-zero)

      (let [ymid (:yield-generation-module snapshot)
            yield-enabled? (and ymid (t/yield-preset-yield-enabled? (:yield-preset settings)))]
        (and yield-enabled? (not (yield-module-available? world ymid token))))
      (t/fail :insufficient-module-liquidity)

      (and (pos? (:auto-release-time settings 0))
           (pos? (:auto-cancel-time settings 0)))
      (t/fail :cannot-set-both-auto-times)

      :else
      (let [workflow-id   (get world :next-workflow-id 0)]
        (let [fee-bps       (:escrow-fee-bps snapshot 0)
              fee           (sew-econ/calculate-escrow-fee amount fee-bps)
              afa           (- amount fee)
            ;; _applyEscrowSettings: compute effective auto times
              snap-rel      (:default-auto-release-delay snapshot 0)
              snap-can      (:default-auto-cancel-delay snapshot 0)
              use-defaults? (and (zero? (:auto-release-time settings 0))
                                 (zero? (:auto-cancel-time settings 0)))
              auto-rel      (cond
                              (pos? (:auto-release-time settings 0)) (:auto-release-time settings)
                              (and use-defaults? (pos? snap-rel))    (+ (time-ctx/block-ts world) snap-rel)
                              :else                                   0)
              auto-can      (cond
                              (pos? (:auto-cancel-time settings 0)) (:auto-cancel-time settings)
                              (and use-defaults? (pos? snap-can))   (+ (time-ctx/block-ts world) snap-can)
                              :else                                  0)
            ;; Resolver: custom-resolver takes precedence over snapshot
              resolver      (or (:custom-resolver settings)
                                (:dispute-resolver snapshot))
            ;; Bonding guard: only enforce when resolver-bond-bps is configured
              bond-bps      (:resolver-bond-bps snapshot 0)
              stake         (if resolver (reg/get-stake world resolver) 0)]
          (cond
            (= "" resolver)
            (t/fail :invalid-resolver)

            (and resolver (t/resolver-at-capacity? world resolver))
            (t/fail :resolver-at-capacity)

            (and resolver (> (get-in world [:resolver-frozen-until resolver] 0) (time-ctx/block-ts world)))
            (t/fail :resolver-frozen)

            (and resolver (pos? bond-bps) (pos? stake)
                 (not (reg/can-handle-escrow? world resolver afa)))
            (t/fail :insufficient-resolver-stake)

            :else
            (let [et            (t/make-escrow-transfer
                                 {:token             token
                                  :to                to
                                  :from              caller
                                  :amount-after-fee  afa
                                  :initial-fee       fee
                                  :dispute-resolver  resolver
                                  :auto-release-time auto-rel
                                  :auto-cancel-time  auto-can
                                  :last-accrual-time (time-ctx/block-ts world)
                                  :escrow-state      :pending})
                   ymid          (when-let [m (:yield-generation-module snapshot)]
                                   (yield-module/resolve-module-id world m))
                   world'        (-> world
                                    (assoc :next-workflow-id (inc workflow-id))
                                    (assoc-in [:escrow-transfers workflow-id] et)
                                    (assoc-in [:escrow-settings workflow-id]
                                              (t/make-escrow-settings settings))
                                    (assoc-in [:module-snapshots workflow-id] snapshot)
                                     (update-in [:total-principal-deposited token] (fnil + 0) amount)
                                     (acct/add-held token
                                                    afa
                                                    {:action "create-escrow"
                                                     :reason :escrow-principal-deposited
                                                     :extra {:held/action "create-escrow"
                                                             :held/workflow-id workflow-id
                                                             :owner/address caller
                                                             :held/from caller
                                                             :held/to to}})
                                     (acct/record-fee token fee)
                                    (update-in [:total-fot-fees token] (fnil + 0) (- amount afa fee)))
                 ;; Trigger yield deposit if module is configured
                   world''       (if (and ymid
                                          (t/yield-preset-yield-enabled? (:yield-preset settings))
                                          (contains? (:yield/modules world') ymid))
                                   (yield-ops/apply-yield-op world' {:op/type :yield/deposit
                                                                     :module/id ymid
                                                                     :owner/id (t/escrow-yield-owner-id workflow-id)
                                                                     :amount afa
                                                                     :token token})
                                   world')]
             ;; Evidence capture with canonical attribution + rich domain payload
              (let [yield-deposit-applied? (and ymid
                                                (t/yield-preset-yield-enabled? (:yield-preset settings))
                                                (contains? (:yield/modules world') ymid))
                   ;; Normalize settings to artifact-safe fields only
                    settings-ev {:yield-preset (:yield-preset settings)
                                 :profile-id (:profile-id settings)
                                 :protection-profile-id (:protection-profile-id settings)
                                 :auto-release (:auto-release settings)
                                 :auto-cancel (:auto-cancel settings)
                                 :custom-resolver (:custom-resolver settings)}
                    created-wf (get-in world'' [:escrow-transfers workflow-id])]
                (attr/with-attribution {:subject/type :escrow
                                        :subject/id workflow-id
                                        :action/type :escrow/create
                                        :evidence/reason :escrow-created}
                  (cap/capture-event-evidence!
                   :escrow-created
                   {:escrow/before
                     {:next-workflow-id (:next-workflow-id world)
                      :total-held (get-in world [:total-held token] 0)
                      :resolver-stake (when resolver (reg/get-stake world resolver))}}
                    {:escrow/after
                     {:next-workflow-id (:next-workflow-id world'')
                      :total-held (get-in world'' [:total-held token] 0)
                     :resolver-stake (when resolver (reg/get-stake world'' resolver))
                     :created-workflow (select-keys created-wf
                                                    [:token :to :from :amount-after-fee
                                                     :initial-fee :dispute-resolver
                                                     :auto-release-time :auto-cancel-time
                                                     :escrow-state :last-accrual-time])}}
                   {:escrow/workflow-id workflow-id
                    :escrow/token token
                    :escrow/amount amount
                    :escrow/fee fee
                    :escrow/amount-after-fee afa
                    :escrow/resolver resolver
                    :escrow/auto-release auto-rel
                    :escrow/auto-cancel auto-can
                    :escrow/yield-module ymid
                   :escrow/yield-deposit-applied? yield-deposit-applied?
                   :escrow/settings settings-ev}
                  nil
                  {:world-before world
                   :world-after world''})))
              (assoc (t/ok world'') :workflow-id workflow-id))))))))

;; ---------------------------------------------------------------------------
;; raise-dispute
;;
;; Thin wrapper around transition-to-disputed.
;; ---------------------------------------------------------------------------

(defn raise-dispute
  "Raise a dispute on a :pending escrow.
   Caller must be :from or :to.

   Also checks DRM resolver capacity: if the escrow has a dispute-resolver assigned
   and that resolver is at maxConcurrentDisputes, the call fails with
   :resolver-capacity-exceeded — mirroring DRM.initializeDispute behaviour.
   On success, increments the resolver's current-active counter."
  [world workflow-id caller]
  (let [result (sm/transition-to-disputed world workflow-id caller)]
    (if-not (:ok result)
      result
      (let [resolver (get-in (:world result) [:escrow-transfers workflow-id :dispute-resolver])]
        (if (and resolver (t/resolver-at-capacity? world resolver))
           (t/fail :resolver-capacity-exceeded)
           (if (and resolver (> (get-in world [:resolver-frozen-until resolver] 0) (time-ctx/block-ts world)))
             (t/fail :resolver-frozen)
             (let [et (t/get-transfer world workflow-id)
                afa (:amount-after-fee et)
                max-escrow (reg/get-max-escrow-per-case world resolver)]
            (if (> afa max-escrow)
              (t/fail :insufficient-resolver-stake)
              (let [world' (t/increment-resolver-capacity (:world result) resolver)]
                (attr/with-attribution {:subject/type :dispute
                                        :subject/id workflow-id
                                        :action/type :dispute/raise
                                        :evidence/reason :dispute-raised}
                  (cap/capture-event-evidence!
                   :dispute-raised
                   {:dispute/before {:escrow-state (t/escrow-state world workflow-id)
                                     :resolver resolver}}
                   {:dispute/after  {:escrow-state (t/escrow-state world' workflow-id)
                                     :resolver-capacity (get-in world' [:resolver-capacities resolver :current-active])}}
                   {:dispute/workflow-id workflow-id
                    :dispute/caller caller
                    :dispute/resolver resolver
                    :dispute/level (t/dispute-level world' workflow-id)}
                   nil
                   {:world-before world
                    :world-after world'}))
                (t/ok world'))))))))))

;; ---------------------------------------------------------------------------
;; release
;;
;; Mirrors: BaseEscrow.release
;;
;; The release strategy is modelled as a function:
;;   (release-strategy-fn world workflow-id caller) → {:allowed? bool :reason-code uint8}
;;
;; When strategy-fn is nil (no strategy configured), the call reverts:
;; this matches the contract's ReleaseStrategyNotSet revert.
;;
;; Guards:
;;   1. workflow-id must exist
;;   2. state must be :pending
;;   3. release-strategy-fn must be non-nil
;;   4. strategy must return {:allowed? true}
;; ---------------------------------------------------------------------------

(defn release
  "Release a :pending escrow to :to.

   release-strategy-fn — (fn [world workflow-id caller] → {:allowed? bool :reason-code n})
                         Pass nil to simulate 'no strategy configured'."
  [world workflow-id caller release-strategy-fn]
  (cond
    (not (t/valid-workflow-id? world workflow-id))
    (guard-fail :invalid-workflow-id :workflow-id workflow-id)

    (not= :pending (t/escrow-state world workflow-id))
    (guard-fail :transfer-not-pending
                :escrow-state (t/escrow-state world workflow-id)
                :workflow-id workflow-id)

    (nil? release-strategy-fn)
    (guard-fail :release-strategy-not-set :workflow-id workflow-id)

    :else
    (let [{:keys [allowed? reason-code]} (release-strategy-fn world workflow-id caller)]
      (if-not allowed?
        (guard-fail (if (= 1 reason-code) :not-sender :release-not-allowed)
                    :reason-code reason-code :workflow-id workflow-id)
        (finalize world workflow-id :released))))

;; ---------------------------------------------------------------------------
;; partial-release
;;
;; Mirrors: EscrowVault.partialRelease
;;
;; Guards:
;;   1. workflow-id must exist
;;   2. state must be :pending
;;   3. amount must be positive
;;   4. amount must not exceed remaining (amount-after-fee - already-released)
;;   5. release-strategy-fn must be non-nil
;;   6. strategy must return {:allowed? true}
;;
;; Accounting (matching EscrowVault.partialRelease):
;;   - increments :amount-released[workflow-id]
;;   - sub-held(token, amount)
;;   - record-claimable(workflow-id, :to, amount)
;;   - record-released(token, amount)
;;   - if amount-released == amount-after-fee → transitions to :released
;; ---------------------------------------------------------------------------

(defn partial-release
  "Release a portion of a :pending escrow to :to.

   release-strategy-fn — (fn [world workflow-id caller]
                           → {:allowed? bool :reason-code n})"
  [world workflow-id caller amount release-strategy-fn]
  (let [wf-id (t/normalize-workflow-id workflow-id)]
    (cond
      (not (t/valid-workflow-id? world wf-id))
      (guard-fail :invalid-workflow-id :workflow-id wf-id)

      (not= :pending (t/escrow-state world wf-id))
      (guard-fail :transfer-not-pending
                  :escrow-state (t/escrow-state world wf-id)
                  :workflow-id wf-id)

      (<= amount 0)
      (guard-fail :amount-zero :workflow-id wf-id :amount amount)

      (nil? release-strategy-fn)
      (guard-fail :release-strategy-not-set :workflow-id wf-id)

      :else
      (let [et (t/get-transfer world wf-id)
            afa (:amount-after-fee et)
            released-so-far (get-in world [:amount-released wf-id] 0)
            remaining (- afa released-so-far)]
        (if (> amount remaining)
          (guard-fail :amount-exceeds-balance
                      :requested amount :available remaining
                      :workflow-id wf-id)
          (let [{:keys [allowed? reason-code]} (release-strategy-fn world wf-id caller)]
            (if-not allowed?
              (guard-fail (if (= 1 reason-code) :not-sender :release-not-allowed)
                          :reason-code reason-code :workflow-id wf-id)
              (let [new-released (+ released-so-far amount)]
                (if (>= new-released afa)
                  ;; Fully released — transition to :released
                  (t/ok (-> world
                            (assoc-in [:amount-released wf-id] new-released)
                            (acct/sub-held (:token et) amount
                                           {:action "partial-release"
                                            :reason :escrow-settlement-released
                                            :extra {:held/workflow-id wf-id
                                                    :owner/address (:to et)
                                                    :held/recipient (:to et)}})
                            (acct/record-claimable-v2 wf-id :settlement/principal (:to et) amount)
                            (acct/record-released (:token et) amount)
                            (sm/apply-transition! wf-id :released)
                            (update-in [:escrow-transfers wf-id] assoc
                                       :sender-status :none
                                       :recipient-status :none)))
                  ;; Partial — stay :pending
                  (t/ok (-> world
                            (assoc-in [:amount-released wf-id] new-released)
                            (acct/sub-held (:token et) amount
                                           {:action "partial-release"
                                            :reason :escrow-settlement-released
                                            :extra {:held/workflow-id wf-id
                                                    :owner/address (:to et)
                                                    :held/recipient (:to et)}})
                            (acct/record-claimable-v2 wf-id :settlement/principal (:to et) amount)
                            (acct/record-released (:token et) amount))))))))))))

;; ---------------------------------------------------------------------------
;; sender-cancel
;;
;; Mirrors: BaseEscrow.senderCancel
;;
;; The cancellation strategy is modelled as a map:
;;   {:can-cancel?          bool  — canCancel result
;;    :unilateral-cancel?   bool  — canCancelUnilaterally result}
;; or nil when no strategy is configured (mutual-consent-only path).
;;
;; Logic:
;;   1. Guard: caller = :from, state = :pending
;;   2. If strategy set:
;;      a. If !canCancel → revert :not-authorized-to-cancel-yet
;;      b. If canCancelUnilaterally → immediate refund
;;   3. Else: set senderStatus = :agree-to-cancel; refund if both agreed
;; ---------------------------------------------------------------------------

(defn sender-cancel
  "Attempt to cancel escrow as sender.

   cancel-strategy — {:can-cancel? bool :unilateral-cancel? bool} or nil."
  [world workflow-id caller cancel-strategy]
  (cond
    (not (t/valid-workflow-id? world workflow-id))
    (guard-fail :invalid-workflow-id :workflow-id workflow-id)

    (not= caller (get-in world [:escrow-transfers workflow-id :from]))
    (guard-fail :not-sender :caller caller :workflow-id workflow-id)

    (not= :pending (t/escrow-state world workflow-id))
    (guard-fail :transfer-not-pending
                :escrow-state (t/escrow-state world workflow-id)
                :workflow-id workflow-id)

    ;; Strategy set and blocks the call
    (and (some? cancel-strategy) (not (:can-cancel? cancel-strategy)))
    (guard-fail :not-authorized-to-cancel-yet
                :cancel-strategy cancel-strategy :workflow-id workflow-id)

    ;; Strategy permits unilateral cancel
    (and (some? cancel-strategy) (:unilateral-cancel? cancel-strategy))
    (finalize world workflow-id :refunded)

    :else
    ;; Mutual-consent path: set sender status
    (let [r (sm/set-sender-agree-to-cancel world workflow-id caller)]
      (if-not (:ok r)
        r
        (if (sm/both-agreed-to-cancel? (:world r) workflow-id)
          (finalize (:world r) workflow-id :refunded)
          r)))))

;; ---------------------------------------------------------------------------
;; recipient-cancel
;;
;; Mirrors: BaseEscrow.recipientCancel
;; Same logic as sender-cancel but for :to.
;; ---------------------------------------------------------------------------

(defn recipient-cancel
  "Attempt to cancel escrow as recipient.

   cancel-strategy — {:can-cancel? bool :unilateral-cancel? bool} or nil."
  [world workflow-id caller cancel-strategy]
  (cond
    (not (t/valid-workflow-id? world workflow-id))
    (guard-fail :invalid-workflow-id :workflow-id workflow-id)

    (not= caller (get-in world [:escrow-transfers workflow-id :to]))
    (guard-fail :not-recipient :caller caller :workflow-id workflow-id)

    (not= :pending (t/escrow-state world workflow-id))
    (guard-fail :transfer-not-pending
                :escrow-state (t/escrow-state world workflow-id)
                :workflow-id workflow-id)

    (and (some? cancel-strategy) (not (:can-cancel? cancel-strategy)))
    (guard-fail :not-authorized-to-cancel-yet
                :cancel-strategy cancel-strategy :workflow-id workflow-id)

    (and (some? cancel-strategy) (:unilateral-cancel? cancel-strategy))
    (finalize world workflow-id :refunded)

    :else
    (let [r (sm/set-recipient-agree-to-cancel world workflow-id caller)]
      (if-not (:ok r)
        r
        (if (sm/both-agreed-to-cancel? (:world r) workflow-id)
          (finalize (:world r) workflow-id :refunded)
          r)))))

;; ---------------------------------------------------------------------------
;; Cleanup orphaned reversal slashes for terminal escrows
;; ---------------------------------------------------------------------------

(defn cleanup-orphaned-slashes
  "Archive and remove orphaned pending reversal slashes for a terminal escrow.
   Only expired Track 2 slashes are removed; their complete records are retained in
   :reversal-slash-history with an auditable :expired-cleaned-up status."
  [world workflow-id]
  (let [now-ts (time-ctx/block-ts world)]
    (if (#{:released :refunded} (t/escrow-state world workflow-id))
      (let [expired? (fn [[_slash-id slash]]
                       (and (= :pending (:status slash))
                            (= workflow-id (:slash/workflow-id slash))
                            (= :reversal (:slash/kind slash))
                            (<= (:appeal-deadline slash 0) now-ts)))
            expired  (filter expired? (:pending-fraud-slashes world {}))]
        (-> world
            (update :reversal-slash-history
                    (fnil into {})
                    (map (fn [[slash-id slash]]
                           [slash-id (assoc slash
                                            :status :expired-cleaned-up
                                            :cleanup-at now-ts
                                            :cleanup-reason :appeal-window-expired)])
                         expired))
            (update :pending-fraud-slashes
                    (fn [slashes]
                      (into {} (remove expired? slashes))))))
      world)))

;; ---------------------------------------------------------------------------
;; auto-cancel-disputed-escrow
;;
;; Mirrors: BaseEscrow.autoCancelDisputedEscrow
;;
;; Guards:
;;   1. state must be :disputed
;;   2. no pending-settlement exists  (CRIT-3: don't override resolver decision)
;;   3. dispute-raised-timestamp set + max-dispute-duration elapsed
;; ---------------------------------------------------------------------------

(defn- cancel-disputed-escrow-now
  "Internal: execute disputed-escrow refund + resolver slash unconditionally.
   Caller is responsible for time/state validation.
   Slashes the resolver's stake WITHOUT subtracting from :total-held —
   the resolver's stake lives in :resolver-stakes, not :total-held
   (register-stake never calls add-held), so the slash distribution is
   backed by the stake reduction, not by :total-held."
  [world workflow-id]
  (let [et             (t/get-transfer world workflow-id)
        resolver        (:dispute-resolver et)
        slash-amt       (:amount-after-fee et)
        token           (:token et)
        has-resolver?   (and resolver
                             (not= resolver t/zero-address))
        world-finalized (finalize world workflow-id :refunded)]
    (if-not (:ok world-finalized)
      world-finalized
      (let [world-finalized (:world world-finalized)
            world-slashed   (if has-resolver?
                              (let [current (reg/get-stake world-finalized resolver)
                                    actual  (bigint (min (double current) (double slash-amt)))
                                    world'  (-> world-finalized
                                                (update-in [:resolver-stakes resolver] (fnil - 0) actual)
                                                (acct/distribute-slashed-funds actual nil 0 workflow-id)
                                                (update-in [:resolver-slash-total resolver] (fnil + 0) actual))]
                                (attr/with-attribution
                                 {:subject/type :resolver
                                  :subject/id   resolver
                                  :action/type  :slash
                                  :evidence/reason :slashing}
                                 (cap/capture-event-evidence!
                                  :slashing
                                  {:resolver-stake current}
                                  {:resolver-stake (reg/get-stake world' resolver)}
                                  {:requested-amount slash-amt :actual-amount actual}
                                  nil
                                  {:world-before world-finalized
                                   :world-after world'}))
                                world')
                              world-finalized)
            world-result    (-> world-slashed
                                (t/decrement-resolver-capacity resolver)
                                (acct/return-all-bonds-for-workflow workflow-id)
                                (cleanup-orphaned-slashes workflow-id))]
        (t/ok world-result))))))

(defn auto-cancel-disputed-escrow
  "Cancel a :disputed escrow after max-dispute-duration has elapsed.
   Performs full accounting reconciliation: slashes the resolver (as a timeout)
   and distributes funds."
  [world workflow-id]
  (cond
    (not (t/valid-workflow-id? world workflow-id))
    (t/fail :invalid-workflow-id)

    (not= :disputed (t/escrow-state world workflow-id))
    (t/fail :transfer-not-in-dispute)

    (:exists (t/get-pending world workflow-id))
    (t/fail :has-pending-settlement)

    (not (sm/dispute-timeout-exceeded? world workflow-id))
    (t/fail :dispute-timeout-not-exceeded)

    :else
    (cancel-disputed-escrow-now world workflow-id)))

(defn auto-cancel-disputed-on-auto-time
  "Solidity shadow: ACTION_AUTO_CANCEL_DISPUTED (computeTimedActions, SettlementOps.sol).

   Cancel a DISPUTED escrow whose auto-cancel-time has passed, bypassing
   max-dispute-duration check.  Mirrors auto-cancel-disputed-escrow but
   triggers on auto-cancel-time instead of dispute-timeout.

   Guards:
     1. state must be :disputed
     2. no pending-settlement exists
     3. auto-cancel-time set and passed (check via state-machine predicate)"
  [world workflow-id]
  (cond
    (not (t/valid-workflow-id? world workflow-id))
    (t/fail :invalid-workflow-id)

    (not= :disputed (t/escrow-state world workflow-id))
    (t/fail :transfer-not-in-dispute)

    (:exists (t/get-pending world workflow-id))
    (t/fail :has-pending-settlement)

    (not (sm/auto-cancel-due-on-disputed? world workflow-id))
    (t/fail :auto-cancel-time-not-passed)

    :else
    (cancel-disputed-escrow-now world workflow-id)))

;; ── Monadic Transitions ──────────────────────────────────────────────────────

(defn create-escrow-m
  "Monadic version of create-escrow."
  [caller token to amount settings snapshot]
  (am/update-with-result create-escrow caller token to amount settings snapshot))

(defn raise-dispute-m
  "Monadic version of raise-dispute."
  [workflow-id caller]
  (am/update-with-result raise-dispute workflow-id caller))

(defn release-m
  "Monadic version of release."
  [workflow-id caller release-strategy-fn]
  (am/update-with-result release workflow-id caller release-strategy-fn))

(defn partial-release-m
  "Monadic version of partial-release."
  [workflow-id caller amount release-strategy-fn]
  (am/update-with-result partial-release workflow-id caller amount release-strategy-fn))

(defn sender-cancel-m
  "Monadic version of sender-cancel."
  [workflow-id caller cancel-strategy]
  (am/update-with-result sender-cancel workflow-id caller cancel-strategy))

(defn recipient-cancel-m
  "Monadic version of recipient-cancel."
  [workflow-id caller cancel-strategy]
  (am/update-with-result recipient-cancel workflow-id caller cancel-strategy))

(defn auto-cancel-disputed-escrow-m
  "Monadic version of auto-cancel-disputed-escrow."
  [workflow-id]
  (am/update-with-result auto-cancel-disputed-escrow workflow-id))

(defn auto-cancel-disputed-on-auto-time-m
  "Monadic version of auto-cancel-disputed-on-auto-time."
  [workflow-id]
  (am/update-with-result auto-cancel-disputed-on-auto-time workflow-id))

(defn init-resolver-yield-accrual-time-m
  "Monadic version of init-resolver-yield-accrual-time."
  [resolver-addr]
  (am/update-attributed #(init-resolver-yield-accrual-time % resolver-addr)))

(defn accrue-resolver-yield-m
  "Monadic version of accrue-resolver-yield."
  [resolver-addr token]
  (am/update-attributed #(accrue-resolver-yield % resolver-addr token)))

(defn accrue-yield-monadic
  "Monadic implementation of accrue-yield, threading AttributedState.
   Computes yield delta and records it via Sew accounting so held-adjustments
   and total-yield-generated remain consistent with invariant expectations."
  [workflow-id]
  (monad/update-state
   (fn [attributed-state]
     (let [world (attr/unwrap-state attributed-state)
           snap (t/get-snapshot world workflow-id)
           mid  (:yield-generation-module snap)]
       (if (and mid (contains? (:yield/modules world) mid))
         (let [et    (t/get-transfer world workflow-id)
               now   (time-ctx/block-ts world)
               last  (:last-accrual-time et now)
               dt    (- now last)
               oid    (t/escrow-yield-owner-id workflow-id)
               tok    (:token et)
               pos-before (get-in world [:yield/positions oid])]
           (if (pos? dt)
             (let [unrealized-before (:unrealized-yield pos-before 0)
                   realized-before   (:realized-yield pos-before 0)
                   world' (yield-ops/apply-yield-op world {:op/type :yield/accrue
                                                           :module/id mid
                                                           :owner/id oid
                                                           :token tok
                                                           :dt dt})
                   pos    (get-in world' [:yield/positions oid])
                   unrealized-after (:unrealized-yield pos 0)
                   realized-after   (:realized-yield pos 0)
                   yield-delta (- (+ unrealized-after realized-after)
                                  (+ unrealized-before realized-before))
                   ;; Record yield delta through Sew accounting layer so
                    ;; held-adjustments-cover-total-held-delta? and related
                    ;; invariants see matching held-adjustment entries.
                    ;; NOTE: yield modules (fixed, liquid_lending) already update
                    ;; total-yield-generated internally — do NOT double-count here.
                    world'' (cond-> world'
                              (pos? yield-delta)
                              (acct/add-held tok yield-delta
                                             {:action "yield-accrual"
                                              :reason :yield-accrued
                                              :extra {:held/workflow-id workflow-id}})
                              (neg? yield-delta)
                              ((fn [w]
                                 (let [deduct-amount (- yield-delta)
                                       pos-id [:held/position tok :yield-custody workflow-id]
                                       held-balance (or (get-in w [:held-ledger/index :by-position pos-id])
                                                        0)
                                       yield-deduct (min deduct-amount held-balance)
                                       excess-deduct (- deduct-amount yield-deduct)]
                                   (-> w
                                       (cond-> (pos? yield-deduct)
                                         (acct/sub-held tok yield-deduct
                                                        {:action "yield-accrual"
                                                         :reason :yield-accrued
                                                         :extra {:held/workflow-id workflow-id}}))
                                       (cond-> (pos? excess-deduct)
                                         (acct/sub-held tok excess-deduct
                                                        {:action "yield-accrual"
                                                         :reason :yield-negative-excess
                                                         :extra {:held/workflow-id workflow-id}})))))))
                    world''' (-> world''
                                (assoc-in [:escrow-transfers workflow-id :last-accrual-time] now)
                                (assoc-in [:escrow-transfers workflow-id :accumulated-yield] (+ unrealized-after realized-after)))]
               (attr/wrap-state world''' (attr/get-attribution attributed-state)))
             attributed-state))
         attributed-state)))))

(defn accrue-yield
  "Calculate and update accrued yield for an escrow based on time delta."
  [world workflow-id]
  (let [attributed (attr/wrap-state world (attr/current-attribution))]
    (attr/unwrap-state (monad/exec-state (accrue-yield-monadic workflow-id) attributed))))
