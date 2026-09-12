(ns resolver-sim.protocols.sew.registry
  "Resolver registration and staking registry for the Sew protocol.
   Supports Phase K (Tiered Authority) by tracking resolver 'Skin in the Game'.

   Key concepts:
     - Resolver Stake: Amount of capital a resolver has locked in the protocol.
     - Escrow Cap: The maximum escrow amount a resolver is permitted to handle.
     - Slash Capacity: The amount a resolver can be slashed before being disabled.

   Constraints:
     - MAX_SLASH_PER_OFFENSE = 50%
     - RESOLVER_SLASH_CAP_BPS = 20% (per epoch/period)"
  (:require [resolver-sim.protocols.sew.types      :as t]
            [resolver-sim.protocols.sew.accounting :as acct]
            [resolver-sim.protocols.sew.economics  :as sew-econ]
            [resolver-sim.util.attribution         :as attr]
            [resolver-sim.evidence.capture         :as cap]))

(declare get-stake)

(defn reject-evidence!
  "Capture evidence for a rejected guard in withdraw-stake."
  [world resolver-addr amount error-kw]
  (attr/with-attribution {:subject/type :resolver
                          :subject/id resolver-addr
                          :action/type :stake/withdraw-rejected
                          :evidence/reason error-kw}
    (cap/capture-event-evidence!
     :stake-withdraw-rejected
     {:stake/before (get-stake world resolver-addr)}
     {:stake/after (get-stake world resolver-addr)}
     {:stake/resolver resolver-addr
      :stake/amount amount
      :stake/error error-kw}
     nil
     {:world-before world
      :world-after world})))

;; ---------------------------------------------------------------------------
;; Stake management
;; ---------------------------------------------------------------------------

(defn register-stake
  "Deposit stake for a resolver address.
   Returns updated world.
   Updates total-resolvers count for circuit breaker tracking.

   If yield-profile-id is provided, the stake will be managed by that yield module.

   Guards:
     - amount must be positive"
  ([world resolver-addr amount] (register-stake world resolver-addr amount nil))
  ([world resolver-addr amount yield-profile-id]
   (when (or (nil? amount) (not (number? amount)) (<= amount 0))
     (throw (ex-info "register-stake amount must be positive"
                     {:type :invalid-stake-amount
                      :amount amount
                      :resolver resolver-addr})))
   (let [prev-stake (get-stake world resolver-addr)
         was-zero?  (zero? prev-stake)
         world' (-> world
                    (update-in [:resolver-stakes resolver-addr] (fnil + 0) amount)
                    (cond-> (and was-zero? (pos? amount))
                      (update-in [:unavailability-stats :total-resolvers] (fnil inc 0)))
                    (cond-> yield-profile-id
                      (assoc-in [:resolver-yield-profiles resolver-addr] yield-profile-id)))]
     (attr/with-attribution {:subject/type :resolver
                             :subject/id resolver-addr
                             :action/type :stake/register
                             :evidence/reason :stake-registered}
       (cap/capture-event-evidence!
        :stake-registered
        {:stake/before (get-stake world resolver-addr)}
        {:stake/after  (get-stake world' resolver-addr)}
        {:stake/resolver resolver-addr
         :stake/amount amount
         :stake/yield-profile-id yield-profile-id}
        nil
        {:world-before world
         :world-after world'}))
     world')))

(defn get-resolver-yield-profile
  "Returns the yield profile ID assigned to a resolver."
  [world resolver-addr]
  (get-in world [:resolver-yield-profiles resolver-addr]))

(defn get-stake
  "Get the current stake for a resolver address."
  [world resolver-addr]
  (get-in world [:resolver-stakes resolver-addr] 0))

(defn withdraw-stake
  "Withdraw stake for a resolver address.

   Guards:
     - amount must be positive
     - resolver must have enough stake

   Returns {:ok bool :world world' :error kw}."
  [world resolver-addr amount]
  (let [current (get-stake world resolver-addr)]
    (cond
      (or (nil? amount) (not (number? amount)) (<= amount 0))
      (do (reject-evidence! world resolver-addr amount :invalid-amount)
          (t/fail :invalid-amount))

      (> amount current)
      (do (reject-evidence! world resolver-addr amount :insufficient-stake)
          (t/fail :insufficient-stake))

      :else
       (let [new-stake (- current amount)
             world' (-> world
                        (update-in [:resolver-stakes resolver-addr] (fnil - 0) amount)
                        (cond-> (and (pos? current) (zero? new-stake))
                          (update-in [:unavailability-stats :total-resolvers] (fnil dec 0))))]
        (attr/with-attribution {:subject/type :resolver
                                :subject/id resolver-addr
                                :action/type :stake/withdraw
                                :evidence/reason :stake-withdrawn}
          (cap/capture-event-evidence!
           :stake-withdrawn
           {:stake/before current}
           {:stake/after  (get-stake world' resolver-addr)}
           {:stake/resolver resolver-addr
            :stake/amount amount}
           nil
           {:world-before world
            :world-after world'}))
        (t/ok world')))))

(defn can-handle-escrow?
  "True if the resolver's stake is sufficient for the given escrow amount."
  [world resolver-addr escrow-amount]
  (let [stake (get-stake world resolver-addr)
        multiplier (get-in world [:params :capacity-multiplier] 1.0)
        cap   (sew-econ/calculate-escrow-cap stake multiplier)]
    (>= cap escrow-amount)))

(defn get-max-escrow-per-case
  "Mirrors IStakingModule.getMaxEscrowPerCase(resolver).
   Returns the maximum escrow value a resolver can be assigned,
   computed as stake * capacity-multiplier.
   When staking is not configured (no resolver-bond-bps), returns
   most-positive-fixnum (no limit), mirroring StakingModuleNoOp."
  [world resolver-addr]
  (let [bond-bps (get-in world [:params :resolver-bond-bps] 0)]
    (if (zero? bond-bps)
      ;; No staking module configured — backward compatible, no limit
      Long/MAX_VALUE
      (let [stake (get-stake world resolver-addr)
            multiplier (get-in world [:params :capacity-multiplier] 4.0)
            max-per-case (get-in world [:params :max-escrow-per-l0-case] 2000e18)]
        (min (* stake multiplier) max-per-case)))))

;; ---------------------------------------------------------------------------
;; Slashing
;; ---------------------------------------------------------------------------

(defn slash-resolver-stake
  "Slash a portion of a resolver's stake.
   Returns updated world with the slashed amount removed from registry
   and distributed according to protocol rules.

   No guards here — the per-offense cap (50%) and epoch cap (20%) are
   enforced at the governance slash pipeline level (propose-fraud-slash,
   execute-fraud-slash).  Automatic Track 1 reversal slashes and timeout
   slashes (auto-cancel) are not subject to these caps — they are immediate
   and deterministic, not governance-proposed.

   Matches DR3 slashing distribution (50/30/20).
   Supports optional challenger bounty for Phase L."
  ([world resolver-addr amount] (slash-resolver-stake world resolver-addr amount nil 0 nil))
  ([world resolver-addr amount challenger bounty-bps]
   (slash-resolver-stake world resolver-addr amount challenger bounty-bps nil))
  ([world resolver-addr amount challenger bounty-bps workflow-id]
   (slash-resolver-stake world resolver-addr amount challenger bounty-bps workflow-id false))
  ([world resolver-addr amount challenger bounty-bps workflow-id skip-sub-held?]
   (let [current (get-stake world resolver-addr)
           actual  (bigint (min (double current) (double amount)))
           token   (if workflow-id
                   (keyword (or (:token (t/get-transfer world workflow-id)) "USDC"))
                   :USDC)
          ;; Check position-level held balance for this resolver's slash-custody
          ;; (not token-level total-held, which includes other positions like escrow principal).
          ;; The position is keyed by [:held/position token :resolver-slash-custody resolver-addr workflow-id].
          slash-custody-pos-id [:held/position token :resolver-slash-custody resolver-addr (or workflow-id 0)]
          position-held (or (get-in world [:held-ledger/index :by-position slash-custody-pos-id])
                            (get-in world [:held/positions slash-custody-pos-id])
                            0)
           ;; Consume from senior coverage first (if resolver has delegated to a senior).
          senior-addr (get-in world [:resolver-senior resolver-addr])
          coverage-remaining (when senior-addr
                               (get-in world [:senior-bonds senior-addr :reserved-coverage] 0))
          from-coverage (if coverage-remaining
                          (min actual (long coverage-remaining))
                          0)
           from-stake (- actual from-coverage)
           ;; Reduce held only when the specific resolver-slash-custody position has balance
           ;; (avoids position-level underflow).  Reversal slashes (skip-sub-held?=true)
           ;; bypass sub-held because the penalty comes from resolver stakes, not held custody.
          sub-held?      (and (not skip-sub-held?)
                              (pos? actual)
                              (>= position-held actual))
         world'  (-> world
                     (cond-> (and senior-addr (pos? from-coverage))
                       (update-in [:senior-bonds senior-addr :reserved-coverage]
                                  (fnil - 0) from-coverage))
                     (update-in [:resolver-stakes resolver-addr] (fnil - 0) from-stake)
                     (acct/distribute-slashed-funds actual challenger bounty-bps workflow-id)
                     (update-in [:resolver-slash-total resolver-addr] (fnil + 0) actual)
                       (cond-> sub-held?
                         (acct/sub-held token
                                        actual
                                        {:action "slash-resolver-stake"
                                         :reason :resolver-slash-custody-debited
                                         :extra {:held/action "slash-resolver-stake"
                                                 :owner/address resolver-addr
                                                 :held/resolver resolver-addr
                                                 :held/workflow-id workflow-id
                                                 :held/challenger challenger}})))]
      ;; Phase 6: Capture Slashing Evidence — returns evidence map with :evidence/hash
     (let [stake-evidence
           (attr/with-attribution
             {:subject/type :resolver
              :subject/id   resolver-addr
              :action/type  :slash
              :evidence/reason :slashing}
             (cap/capture-event-evidence! :slashing
                                          {:resolver-stake current}
                                          {:resolver-stake (get-stake world' resolver-addr)}
                                          {:requested-amount amount :actual-amount actual}
                                          nil
                                          {:world-before world
                                           :world-after world'}))]
       (assoc (t/ok world') :slashed-from-stake from-stake
              :slashed-from-coverage from-coverage
              :total-slashed actual
              :stake-evidence-hash (:evidence/hash stake-evidence))))))
