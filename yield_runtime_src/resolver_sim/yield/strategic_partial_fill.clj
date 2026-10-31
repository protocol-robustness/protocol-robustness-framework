(ns resolver-sim.yield.strategic-partial-fill
  "Strategic partial-fill validation: invariance checks under
   bounded claim transformations.

   Evaluates whether the partial-fill allocation mechanism is resistant to
   bounded strategic manipulation by comparing allocations before and after
   claim transformations (split, merge, permute, sybil, inflate).

   Checks search bounded integer claim sets. The artifact records whether
   its configured state generator is exhaustive or sampled, and its evaluation
   budget. No search result establishes a universal equilibrium claim."
  (:require

   [resolver-sim.validation.deviation-contract :as dc]
   [resolver-sim.validation.enumeration :as enum]
   [resolver-sim.hash.canonical :as hc]
   [resolver-sim.yield.strategic-execution :as execution]
   [resolver-sim.signed-external-decision :as signed-decision]))

;; ---------------------------------------------------------------------------
;; Allocation helper
;; ---------------------------------------------------------------------------

(defn allocation-report
  "Allocate integer liquidity proportionally and report rounding accounting.

   `:floor` deliberately leaves a rounding residual undistributed.  The
   `:largest-remainder` policy assigns that residual deterministically by
   descending remainder, then ascending claim index."
  [claims available {:keys [rounding-policy]
                     :or {rounding-policy :largest-remainder}}]
  (let [n (count claims)
        liquidity (long available)
        total-claims (reduce + 0 claims)
        distributable (min liquidity total-claims)
        empty-allocations (vec (repeat n 0))]
    (if (or (zero? total-claims) (zero? distributable))
      {:allocations empty-allocations
       :distributed 0
       :undistributed 0
       :excess-liquidity (- liquidity distributable)
       :rounding-policy rounding-policy}
      (let [floor-fills (mapv (fn [claim] (quot (* claim distributable) total-claims)) claims)
            floor-total (reduce + 0 floor-fills)
            residual (- distributable floor-total)
            remainders (mapv (fn [claim] (mod (* claim distributable) total-claims)) claims)
            selected-indices (take residual
                                   (sort-by (fn [i] [(- (nth remainders i)) i])
                                            (range n)))]
        (case rounding-policy
          :floor
          {:allocations floor-fills
           :distributed floor-total
           :undistributed residual
           :excess-liquidity (- liquidity distributable)
           :rounding-policy rounding-policy}

          :largest-remainder
          (let [selected? (set selected-indices)
                allocations (mapv (fn [i fill]
                                    (if (contains? selected? i) (inc fill) fill))
                                  (range n) floor-fills)]
            {:allocations allocations
             :distributed distributable
             :undistributed 0
             :excess-liquidity (- liquidity distributable)
             :rounding-policy rounding-policy})

          (throw (ex-info "Unsupported rounding policy"
                          {:rounding-policy rounding-policy
                           :supported #{:floor :largest-remainder}})))))))

(defn- allocate
  "Return claim-key -> filled amount for the requested rounding policy."
  [claims available policy]
  (into {}
        (map-indexed (fn [i fill] [(str "c" i) fill])
                     (:allocations (allocation-report claims available policy)))))

;; ---------------------------------------------------------------------------
;; State enumeration
;; ---------------------------------------------------------------------------

(def default-scope
  "Default enumeration bounds as an EnumerationScope."
  (enum/make-scope :dimensions {:claim-count [1 5] :request [0 20] :liquidity [0 20]}
                   :sampling :stratified
                   :max-states 500))

(def recovery-game-schema-version "partial-fill-recovery-game.v2")

(def recovery-game-candidate-actions
  "Action candidates the model can represent. Inclusion describes model
   capacity, not protocol permission; protocol adapters must identify which
   actions are actually feasible."
  #{:claim-now :defer :split-claim :merge-claims :delay-arrival
    :reorder-arrival :increase-request :change-cap :withhold-liquidity})

(def recovery-game-utility-parameters
  "Required integer coefficients for an explicit linear utility profile.
   Coefficients are assumptions supplied by the fixture author, not protocol
   facts. Monetary amounts use the fixture's declared base unit."
  [:amount-value
   :delay-cost-per-round
   :fee-cost-per-unit
   :recovery-risk-cost-per-unit
   :priority-externality-cost-per-unit
   :penalty-cost
   :continuation-value
   :discount-factor-bps])

(def recovery-game-assumption-attestation-domain
  "resolver-sim/partial-fill-recovery-game-assumptions.v1")

(def recovery-game-assumption-attestation-role
  :recovery-game-assumptions-authority)

(def recovery-game-action-permission-domain
  "resolver-sim/partial-fill-recovery-game-action-permissions.v1")

(def recovery-game-action-permission-role
  :recovery-game-action-permission-authority)

(defn recovery-game-model-root
  "Content root for all recovery-game inputs, including utilities and declared
   feasible actions. The signature is excluded to avoid a self-reference."
  [model]
  (hc/domain-hash :projection-artifact
                  {:artifact/kind :partial-fill-recovery-game-model.v1
                   :model (dissoc model :assumption-attestation
                                  :action-permission-attestation)}))

(defn sign-recovery-game-assumptions
  "Attach an Ed25519 authority attestation to the complete model. The signing
   key must be trusted by verifiers under the recovery-game assumptions role."
  [model private-key key-id]
  (let [envelope {:schema-version "partial-fill-recovery-game-attestation.v1"
                  :game/id (:game/id model)
                  :model/root (recovery-game-model-root model)}]
    (assoc model :assumption-attestation
           (signed-decision/sign-envelope envelope
                                           recovery-game-assumption-attestation-domain
                                           private-key key-id))))

(defn verify-recovery-game-assumptions
  "Verify the complete model attestation against an explicit trusted-key
   policy. A self-hash alone never establishes authority."
  [model trust-policy]
  (let [attestation (:assumption-attestation model)
        expected-root (recovery-game-model-root model)
        signature-result (if (map? trust-policy)
                           (signed-decision/verify-envelope
                            attestation recovery-game-assumption-attestation-domain
                            trust-policy recovery-game-assumption-attestation-role)
                           {:valid? false :reason :trust-policy-required})
        binding-valid? (and (map? attestation)
                            (= "partial-fill-recovery-game-attestation.v1"
                               (:schema-version attestation))
                            (= (:game/id model) (:game/id attestation))
                            (= expected-root (:model/root attestation)))]
    (if (and binding-valid? (:valid? signature-result))
      {:valid? true
       :key-id (:key-id signature-result)
       :model/root expected-root
       :attestation-role recovery-game-assumption-attestation-role}
      {:valid? false
       :reason (if-not binding-valid? :assumption-model-binding-mismatch
                       (:reason signature-result))
       :signature-result signature-result
       :model/root expected-root})))

(defn- action-permission-claims-root [model]
  (hc/domain-hash :projection-artifact
                  {:artifact/kind :partial-fill-recovery-game-action-permissions.v1
                   :game/id (:game/id model)
                   :claims (:action-permission-claims model)}))

(defn sign-recovery-game-action-permissions
  "Attach a protocol-authority signature to explicit per-player action claims.
   Each claim must include a source root and :permission/status :permitted."
  [model private-key key-id]
  (let [envelope {:schema-version "partial-fill-recovery-game-permissions.v1"
                  :game/id (:game/id model)
                  :claims/root (action-permission-claims-root model)}]
    (assoc model :action-permission-attestation
           (signed-decision/sign-envelope envelope
                                           recovery-game-action-permission-domain
                                           private-key key-id))))

(defn verify-recovery-game-action-permissions
  "Verify signed per-player action claims and return the authorized action sets."
  [model trust-policy]
  (let [claims (:action-permission-claims model)
        players (set (map :player/id (:players model)))
        candidates (set (:candidate-deviations model))
        claim-keys (mapv (juxt :player/id :action) claims)
        claims-valid? (and (vector? claims)
                           (seq claims)
                           (every? (fn [claim]
                                     (let [player-id (:player/id claim)
                                           action (:action claim)
                                           source-root (:source/root claim)]
                                       (and (players player-id)
                                            (candidates action)
                                            (= :permitted (:permission/status claim))
                                            (string? source-root)
                                            (re-matches #"(?:sha256:)?[0-9a-f]{64}" source-root))))
                                   claims)
                           (= (count claim-keys) (count (distinct claim-keys))))
        attestation (:action-permission-attestation model)
        signature-result (if (map? trust-policy)
                           (signed-decision/verify-envelope
                            attestation recovery-game-action-permission-domain
                            trust-policy recovery-game-action-permission-role)
                           {:valid? false :reason :trust-policy-required})
        binding-valid? (and (= "partial-fill-recovery-game-permissions.v1"
                               (:schema-version attestation))
                            (= (:game/id model) (:game/id attestation))
                            (= (action-permission-claims-root model)
                               (:claims/root attestation)))]
    (if (and claims-valid? binding-valid? (:valid? signature-result))
      {:valid? true
       :key-id (:key-id signature-result)
       :claims/root (:claims/root attestation)
       :permitted-actions (reduce (fn [result claim]
                                    (update result (:player/id claim) (fnil conj #{})
                                            (:action claim)))
                                  {} claims)}
      {:valid? false
       :reason (cond
                 (not claims-valid?) :action-permission-claims-invalid
                 (not binding-valid?) :action-permission-binding-mismatch
                 :else (:reason signature-result))
       :signature-result signature-result})))

(defn- valid-candidate-action-profile? [profile model]
  (let [entitlement (some #(when (= (:entitlement/id %) (:entitlement/id profile)) %)
                          (:entitlements model))
        round-index (:round/index profile)
        round (when (and (integer? round-index)
                         (<= 0 round-index (dec (count (:rounds model)))))
                (nth (:rounds model) round-index))]
    (case (:action profile)
      :split-claim
      (and entitlement
           (= (:player/id entitlement) (:player/id profile))
           (vector? (:parts profile))
           (seq (:parts profile))
           (every? #(and (integer? %) (pos? %)) (:parts profile))
           (= (:amount entitlement) (reduce + 0 (:parts profile))))

      :increase-request
      (and entitlement
           (= (:player/id entitlement) (:player/id profile))
           (integer? (:requested-amount profile))
           (> (:requested-amount profile) (:amount entitlement)))

      :change-cap
      (and entitlement
           (= (:player/id entitlement) (:player/id profile))
           (integer? (:requested-cap profile))
           (> (:requested-cap profile) (:amount entitlement)))

      :delay-arrival
      (and (integer? (:delay-rounds profile))
           (pos? (:delay-rounds profile)))

      :reorder-arrival
      (and round
           (= (set (:incoming round)) (set (:arrival-sequence profile)))
           (= (count (:incoming round)) (count (:arrival-sequence profile))))

      :withhold-liquidity
      (and (= :liquidity-provider
              (:role (some #(when (= (:player/id profile) (:player/id %)) %)
                           (:players model))))
           (integer? (:amount profile))
           (pos? (:amount profile)))

      true)))

(defn prepare-recovery-game-model
  "Validate and prepare an explicit finite recovery-game description.

   This validates model completeness and internal references only. It does not
   execute a game tree or establish best responses, equilibrium, incentive
   compatibility, or protocol permission for candidate actions. Missing or
   inconsistent parameters leave the model inconclusive."
  [model]
  (let [players (vec (:players model))
        player-ids (mapv :player/id players)
        player-id-set (set player-ids)
        entitlements (vec (:entitlements model))
        entitlement-ids (mapv :entitlement/id entitlements)
        entitlement-id-set (set entitlement-ids)
        rounds (vec (:rounds model))
        actions (set (:candidate-deviations model))
        action-profiles (vec (:candidate-action-profiles model))
        payoff-by-player (get-in model [:payoff-parameters :by-player])
        violations (vec
                    (concat
                     (when-not (= recovery-game-schema-version (:schema-version model))
                       [{:path [:schema-version] :reason :unsupported-schema}])
                     (when-not (and (string? (:game/id model))
                                    (not-empty (:game/id model)))
                       [{:path [:game/id] :reason :missing-game-id}])
                     (when (or (empty? players)
                               (some nil? player-ids)
                               (not= (count player-ids) (count player-id-set)))
                       [{:path [:players] :reason :player-identities-invalid}])
                     (when (or (empty? entitlements)
                               (some nil? entitlement-ids)
                               (not= (count entitlement-ids) (count entitlement-id-set)))
                       [{:path [:entitlements] :reason :entitlement-identities-invalid}])
                     (for [[i e] (map-indexed vector entitlements)
                           :when (or (not (player-id-set (:player/id e)))
                                     (nil? (:beneficiary e))
                                     (not (and (integer? (:amount e))
                                               (not (neg? (:amount e))))))]
                       {:path [:entitlements i] :reason :entitlement-fields-invalid})
                     (when (empty? rounds)
                       [{:path [:rounds] :reason :recovery-rounds-required}])
                     (for [[i round] (map-indexed vector rounds)
                           :when (or (not= i (:round/index round))
                                     (not (and (integer? (:available-liquidity round))
                                               (not (neg? (:available-liquidity round)))))
                                     (not= (set (:incoming round))
                                           (set (keys (:requested-by-entitlement round))))
                                     (not= (count (:incoming round))
                                           (count (set (:incoming round))))
                                     (some #(not (entitlement-id-set %)) (:incoming round))
                                     (some #(not (and (integer? %)
                                                      (not (neg? %))))
                                           (vals (:requested-by-entitlement round)))
                                     (some #(or (not (integer? (:amount %)))
                                                (neg? (:amount %))
                                                (nil? (:event/id %))
                                                (nil? (:source %))
                                                (not= :modeled-assumption
                                                      (:evidence-class %)))
                                           (:recovery-events round)))]
                       {:path [:rounds i] :reason :round-or-recovery-event-invalid})
                     (when (or (some #(not (recovery-game-candidate-actions %)) actions)
                               (empty? actions))
                       [{:path [:candidate-deviations] :reason :candidate-actions-invalid}])
                     (when (and (contains? actions :withhold-liquidity)
                                (not-any? #(= :liquidity-provider (:role %)) players))
                       [{:path [:candidate-deviations :withhold-liquidity]
                         :reason :liquidity-provider-required}])
                     (for [player player-ids
                           :let [params (get payoff-by-player player)]
                           :when (or (nil? params)
                                     (some #(not (integer? (get params %)))
                                           recovery-game-utility-parameters)
                                     (not (<= 0 (:discount-factor-bps params 0) 10000)))]
                       {:path [:payoff-parameters :by-player player]
                        :reason :utility-parameterization-incomplete})
                     (for [[i profile] (map-indexed vector action-profiles)
                           :when (or (not (player-id-set (:player/id profile)))
                                     (not (actions (:action profile)))
                                     (not (valid-candidate-action-profile? profile model))
                                     (and (= :withhold-liquidity (:action profile))
                                          (not= :liquidity-provider
                                                (:role (first (filter #(= (:player/id profile)
                                                                          (:player/id %))
                                                                      players))))))]
                       {:path [:candidate-action-profiles i]
                        :reason :candidate-action-profile-invalid})
                     (when-not (and (keyword? (get-in model [:action-timing :decision-point]))
                                    (boolean? (get-in model [:action-timing :simultaneous?]))
                                    (boolean? (get-in model [:action-timing :can-defer?])))
                       [{:path [:action-timing] :reason :action-timing-required}])
                     (let [parallelism (get-in model [:execution-model :parallelism])
                           submissions (:submissions parallelism)
                           tx-ids (map :transaction/id submissions)
                           tx-indices (map :transaction/index submissions)
                           block-ids (map :block/id submissions)
                           pool-ids (map :liquidity-pool/id submissions)
                           arrival-times (map :arrival-time submissions)
                           submission-round-valid?
                           (every? (fn [submission]
                                     (let [round-index (:round/index submission)
                                           round (when (and (integer? round-index)
                                                            (<= 0 round-index)
                                                            (< round-index (count rounds)))
                                                   (nth rounds round-index))]
                                       (and round
                                            (some #{(:entitlement/id submission)}
                                                  (:incoming round)))))
                                   submissions)]
                       (when-not (and (contains? #{:independent-transactions
                                                   :single-transaction-batch}
                                                 (:submission-boundary parallelism))
                                      (contains? #{:same-block :multiple-blocks}
                                                 (:inclusion parallelism))
                                      (contains? #{:transaction-index :unspecified}
                                                 (:ordering parallelism))
                                      (contains? #{:caller-selected :sequencer-determined :unspecified}
                                                 (:ordering-control parallelism))
                                      (contains? #{:concurrent-arrival :staggered-arrival}
                                                 (:submission-time-model parallelism))
                                      (= :shared-liquidity
                                         (:contention parallelism))
                                      (= :single-settlement-per-entitlement
                                         (:duplicate-entitlement-policy parallelism))
                                      (= :per-submission (:submission-atomicity parallelism))
                                      (= :no-state-change (:failed-submission-effect parallelism))
                                      (contains? #{:same-entitlement-idempotent :new-transaction}
                                                 (:retry-policy parallelism))
                                      (keyword? (:arrival-time-unit parallelism))
                                      (vector? submissions)
                                      (seq submissions)
                                      submission-round-valid?
                                      (every? #(and (some? (:caller/id %))
                                                    (entitlement-id-set (:entitlement/id %))
                                                    (some? (:liquidity-pool/id %))
                                                    (integer? (:arrival-time %))
                                                    (not (neg? (:arrival-time %)))) submissions)
                                      (case (:submission-time-model parallelism)
                                        :concurrent-arrival
                                        (= 1 (count (set arrival-times)))
                                        :staggered-arrival
                                        (> (count (set arrival-times)) 1)
                                        false)
                                      (= 1 (count (set pool-ids)))
                                      (or (and (= :single-transaction-batch
                                                  (:submission-boundary parallelism))
                                               (every? #(and (string? %) (not-empty %)) tx-ids)
                                               (= 1 (count (set tx-ids))))
                                          (and (= :independent-transactions
                                                  (:submission-boundary parallelism))
                                               (every? #(and (string? %) (not-empty %)) tx-ids)
                                               (= (count tx-ids) (count (set tx-ids)))))
                                      (or (not= :independent-transactions
                                                (:submission-boundary parallelism))
                                          (every? #(and (string? %) (not-empty %)) block-ids))
                                      (or (not= :same-block (:inclusion parallelism))
                                          (and (= :independent-transactions
                                                  (:submission-boundary parallelism))
                                               (= 1 (count (set block-ids)))
                                               (every? #(and (integer? %) (not (neg? %))) tx-indices)
                                               (= (count tx-indices)
                                                  (count (set tx-indices))))))
                         [{:path [:execution-model :parallelism]
                           :reason :parallel-execution-parameters-incomplete}]))
                     (when-not (and (contains? #{:public :private :imperfect}
                                               (get-in model [:information-model :visibility]))
                                    (keyword? (get-in model [:information-model :known-at-round]))
                                    (boolean? (get-in model [:information-model
                                                             :recovery-known-before-action?])))
                       [{:path [:information-model]
                         :reason :information-model-incomplete}])
                     (when-not (and (contains? #{:deterministic-scenario :stochastic-process}
                                               (get-in model [:recovery-process :kind]))
                                    (= :modeled-assumption
                                       (get-in model [:recovery-process :flow-attribution]))
                                    (case (get-in model [:recovery-process :probability-model])
                                      :none (= :deterministic-scenario
                                               (get-in model [:recovery-process :kind]))
                                      :explicit-probabilities
                                      (let [process (:recovery-process model)
                                            outcomes (:outcomes process)
                                            probabilities (map :probability-bps outcomes)]
                                        (and (= :stochastic-process (:kind process))
                                             (seq outcomes)
                                             (string? (get-in process
                                                              [:probability-basis :description]))
                                             (contains? #{:observed-frequency
                                                          :published-calibration
                                                          :explicit-scenario-assumption}
                                                        (get-in process
                                                                [:probability-basis :source]))
                                             (every? #(and (integer? %) (<= 0 % 10000))
                                                     probabilities)
                                             (= 10000 (reduce + 0 probabilities))
                                             (every? #(and (integer? (:recovery-amount %))
                                                           (not (neg? (:recovery-amount %))))
                                                     outcomes)
                                             (= (count outcomes)
                                                (count (set (map :outcome/id outcomes))))
                                             (every? :outcome/id outcomes)))
                                      false))
                       [{:path [:recovery-process]
                         :reason :recovery-process-assumptions-required}])
                     (when-not (and (contains? #{:integer-utility-points}
                                               (get-in model [:payoff-parameters :unit]))
                                    (keyword? (get-in model [:payoff-parameters :amount-unit]))
                                    (= :linear-additive-per-round
                                       (get-in model [:payoff-parameters :aggregation])))
                       [{:path [:payoff-parameters]
                         :reason :payoff-units-and-aggregation-required}])
                     (when-not (and (integer? (get-in model [:terminal :horizon-round]))
                                    (<= 0 (get-in model [:terminal :horizon-round] -1)
                                        (dec (count rounds)))
                                    (= player-id-set
                                       (set (keys (get-in model [:terminal
                                                                 :continuation-value-by-player]))))
                                    (every? integer?
                                            (vals (get-in model [:terminal
                                                                 :continuation-value-by-player])))
                                    (= :fixed-horizon (get-in model [:terminal :condition]))
                                    (every? (fn [round]
                                              (or (nil? (:arrival-sequence round))
                                                  (= (set (:incoming round))
                                                     (set (:arrival-sequence round)))))
                                            rounds))
                       [{:path [:terminal] :reason :terminal-conditions-incomplete}])
                     (when-not (and (= :pro-rata (get-in model [:mechanism :allocation-mode]))
                                    (contains? #{:floor :largest-remainder}
                                               (get-in model [:mechanism :rounding-policy]))
                                    (= :defer (get-in model [:mechanism :residual-treatment]))
                                    (= :entitlement-beneficiary-fixed
                                       (get-in model [:mechanism :identity-binding])))
                       [{:path [:mechanism] :reason :mechanism-policy-must-be-explicit}])))]
    {:schema-version recovery-game-schema-version
     :game/id (:game/id model)
     :parameterization/status (if (empty? violations) :complete :incomplete)
     :analysis-status :not-run-inconclusive
     :equilibrium-claim :none
     :candidate-actions-are-protocol-permissions? false
     :model model
     :violations violations}))

(defn- discount-factor [discount-bps round-index]
  (let [n (long discount-bps)
        d 10000
        exponent (long round-index)]
    (/ (reduce * 1 (repeat exponent n))
       (reduce * 1 (repeat exponent d)))))

(defn evaluate-recovery-game-outcome
  "Apply a prepared fixture's declared linear payoff assumptions to one
   observed or hypothetical terminal outcome. The outcome contains per-player
   payments [{:amount N :round/index N}], :delay-rounds, :fees,
   :expected-unrecovered, :priority-externality, and :penalty-count.

   Returns exact integer/rational utility components. This is payoff
   bookkeeping only; it does not enumerate deviations or infer equilibrium."
  [prepared outcome]
  (let [model (:model prepared)
        validation (prepare-recovery-game-model model)
        horizon (get-in model [:terminal :horizon-round])
        per-player (:by-player (:payoff-parameters model))
        outcome-by-player (:by-player outcome)
        players (map :player/id (:players model))
        missing (vec (for [player players
                           :let [p (get outcome-by-player player)]
                           :when (or (nil? p)
                                     (not (vector? (:payments p)))
                                     (some (fn [k] (not (and (integer? (get p k))
                                                             (not (neg? (get p k))))))
                                           [:delay-rounds :fees :expected-unrecovered
                                            :priority-externality :penalty-count])
                                     (some #(or (not (and (integer? (:amount %))
                                                          (not (neg? (:amount %)))))
                                                (not (and (integer? (:round/index %))
                                                          (<= 0 (:round/index %) horizon))))
                                           (:payments p)))]
                       player))]
    (if (or (not= :complete (:parameterization/status validation)) (seq missing))
      {:status :inconclusive
       :reason (if (not= :complete (:parameterization/status validation))
                 :model-parameterization-incomplete
                 :outcome-incomplete)
       :missing-players missing
       :model-violations (:violations validation)
       :equilibrium-claim :none}
      {:status :payoffs-evaluated
       :equilibrium-claim :none
       :by-player
       (into (sorted-map)
             (map
              (fn [player]
                (let [params (get per-player player)
                      result (get outcome-by-player player)
                      discounted-payments
                      (reduce + 0
                              (map (fn [{:keys [amount round/index]}]
                                     (* amount (:amount-value params)
                                        (discount-factor (:discount-factor-bps params)
                                                         index)))
                                   (:payments result)))
                      delay-cost (* (:delay-cost-per-round params)
                                    (:delay-rounds result))
                      fee-cost (* (:fee-cost-per-unit params) (:fees result))
                      recovery-risk-cost (* (:recovery-risk-cost-per-unit params)
                                            (:expected-unrecovered result))
                      priority-externality-cost (* (:priority-externality-cost-per-unit params)
                                                   (:priority-externality result))
                      penalty-cost (* (:penalty-cost params) (:penalty-count result))
                      continuation-value (* (:continuation-value params)
                                            (get-in model [:terminal
                                                           :continuation-value-by-player
                                                           player])
                                            (discount-factor (:discount-factor-bps params)
                                                             horizon))
                      costs {:delay delay-cost :fees fee-cost
                             :recovery-risk recovery-risk-cost
                             :priority-externality priority-externality-cost
                             :penalties penalty-cost}
                      utility (+ discounted-payments continuation-value
                                 (- (reduce + 0 (vals costs))))]
                  [player {:utility utility
                           :components {:discounted-payments discounted-payments
                                        :delay-cost delay-cost
                                        :fee-cost fee-cost
                                        :recovery-risk-cost recovery-risk-cost
                                        :priority-externality-cost priority-externality-cost
                                        :penalty-cost penalty-cost
                                        :continuation-value continuation-value}}]))
              players))})))

(defn evaluate-recovery-game-distribution
  "Compute exact expected utility over a fixture's explicit recovery outcomes.

   `outcomes-by-id` maps each declared :outcome/id to a terminal outcome accepted
   by evaluate-recovery-game-outcome. Probability weights are basis points and
   are used only when the model validates an explicit probability basis whose
   outcomes sum to 10,000. This computes expected payoffs; it does not solve
   strategies or establish equilibrium."
  [prepared outcomes-by-id]
  (let [model (:model prepared)
        validation (prepare-recovery-game-model model)
        process (:recovery-process model)
        declared-outcomes (:outcomes process)
        evaluations (mapv (fn [scenario]
                            (let [id (:outcome/id scenario)
                                  result (evaluate-recovery-game-outcome
                                          prepared (get outcomes-by-id id))]
                              {:scenario/id id
                               :probability-bps (:probability-bps scenario)
                               :result result}))
                          declared-outcomes)]
    (if (or (not= :complete (:parameterization/status validation))
            (not= :explicit-probabilities (:probability-model process))
            (empty? declared-outcomes)
            (some #(not= :payoffs-evaluated (get-in % [:result :status])) evaluations))
      {:status :inconclusive
       :reason :explicit-probability-outcomes-required
       :scenario-evaluations evaluations
       :equilibrium-claim :none}
      {:status :expected-payoffs-evaluated
       :probability-basis (:probability-basis process)
       :probability-total-bps (reduce + 0 (map :probability-bps declared-outcomes))
       :equilibrium-claim :none
       :by-player
       (into (sorted-map)
             (for [player (map :player/id (:players model))]
               [player {:expected-utility
                        (reduce + 0
                                (map (fn [{:keys [probability-bps result]}]
                                       (* (/ probability-bps 10000)
                                          (get-in result [:by-player player :utility])))
                                     evaluations))
                        :scenario-utilities
                        (into (sorted-map)
                              (map (fn [{:keys [scenario/id probability-bps result]}]
                                     [id {:probability-bps probability-bps
                                          :utility (get-in result
                                                           [:by-player player :utility])}])
                                   evaluations))}]))})))

(defn analyze-finite-normal-form-game
  "Find every pure-strategy Nash equilibrium in an explicit finite normal-form
   game. The payoff table must contain exactly one utility vector per joint
   action profile. Utilities are caller-supplied assumptions; this verifies the
   equilibrium relation to that table, not the truth or authority of its inputs.

   Returns :inconclusive for malformed, incomplete, or over-budget tables."
  ([game] (analyze-finite-normal-form-game game {}))
  ([{:keys [game/id players actions payoffs]}
    {:keys [max-profiles] :or {max-profiles 10000}}]
   (let [players (vec players)
         action-map (if (map? actions) actions {})
         action-vectors (mapv (fn [player]
                                (let [choices (get action-map player)]
                                  (if (vector? choices) choices [])))
                              players)
         payoff-rows (if (vector? payoffs) payoffs [])
         expected-count (reduce *' 1 (map count action-vectors))
         profiles (reduce (fn [profiles [player choices]]
                            (for [profile profiles
                                  choice choices]
                              (assoc profile player choice)))
                          [{}]
                          (map vector players action-vectors))
         profile-key (fn [profile] (into {} (map (fn [player] [player (get profile player)]) players)))
         payoff-by-profile (into {} (map (fn [{:keys [actions utilities]}]
                                           [(profile-key actions) utilities]) payoff-rows))
         valid-players? (and (seq players)
                             (= (count players) (count (distinct players)))
                             (every? some? players))
         valid-actions? (and (map? actions)
                             (= (set players) (set (keys action-map)))
                             (every? (fn [choices]
                                       (and (seq choices)
                                            (= (count choices) (count (distinct choices)))))
                                     action-vectors))
         budget-valid? (and (integer? max-profiles) (pos? max-profiles))
         within-budget? (and budget-valid? (<= expected-count max-profiles))
         profile-shapes-valid? (and (vector? payoffs)
                                    (= (count payoff-rows) (count payoff-by-profile))
                                    (every? (fn [{:keys [actions utilities]}]
                                              (and (map? actions)
                                                   (= (set players) (set (keys actions)))
                                                   (every? (fn [[player choice]]
                                                             (some #{choice} (get action-map player)))
                                                           actions)
                                                   (map? utilities)
                                                   (= (set players) (set (keys utilities)))
                                                   (every? #(or (integer? %) (ratio? %))
                                                           (vals utilities))))
                                            payoff-rows))
         complete? (and within-budget? valid-players? valid-actions? profile-shapes-valid?
                        (= (set profiles) (set (keys payoff-by-profile))))
         best-response-report
         (when complete?
           (mapv (fn [profile]
                   (let [utilities (get payoff-by-profile profile)
                         deviations (vec
                                     (for [player players
                                           choice (get action-map player)
                                           :when (not= choice (get profile player))
                                           :let [deviation (assoc profile player choice)
                                                 utility (get-in payoff-by-profile
                                                                 [deviation player])]
                                           :when (> utility (get utilities player))]
                                       {:player player
                                        :from (get profile player)
                                        :to choice
                                        :current-utility (get utilities player)
                                        :deviation-utility utility
                                        :gain (- utility (get utilities player))}))]
                     {:actions profile
                      :utilities utilities
                      :profitable-deviations deviations
                      :pure-nash? (empty? deviations)}))
                 profiles))]
     (cond
       (not (and (integer? max-profiles) (pos? max-profiles)))
       {:status :inconclusive :reason :invalid-profile-budget :game/id id
        :equilibrium-concept :pure-nash}

       (> expected-count max-profiles)
       {:status :inconclusive :reason :profile-budget-exceeded :game/id id
        :expected-profile-count expected-count :max-profiles max-profiles
        :equilibrium-concept :pure-nash}

       (not complete?)
       {:status :inconclusive :reason :invalid-or-incomplete-payoff-table :game/id id
        :expected-profile-count expected-count :observed-profile-count (count payoff-rows)
        :equilibrium-concept :pure-nash}

       :else
       (let [equilibria (mapv :actions (filter :pure-nash? best-response-report))]
         {:status :analyzed
          :game/id id
          :equilibrium-concept :pure-nash
          :profile-count expected-count
          :equilibria equilibria
          :pure-equilibrium-exists? (boolean (seq equilibria))
          :profile-analysis best-response-report
          :evidence {:complete-payoff-table? true
                     :utilities-authenticated? false
                     :profile-enumeration :exhaustive}})))))

(defn- nonempty-subsets [items]
  (->> (reduce (fn [subsets item]
                 (into subsets (map #(conj % item) subsets)))
               [[]] items)
       (remove empty?)
       vec))

(defn- solve-exact-linear-system [equations variable-count]
  (let [matrix (mapv vec equations)
        row-count (count matrix)
        rref (loop [column 0
                    pivot-row 0
                    matrix matrix
                    pivots {}]
               (if (or (= column variable-count) (= pivot-row row-count))
                 {:matrix matrix :pivots pivots}
                 (if-let [selected (first (filter #(not (zero? (nth (nth matrix %) column)))
                                                  (range pivot-row row-count)))]
                   (let [matrix (assoc matrix pivot-row (nth matrix selected)
                                       selected (nth matrix pivot-row))
                         pivot (nth (nth matrix pivot-row) column)
                         normalized (mapv #(/ % pivot) (nth matrix pivot-row))
                         matrix (assoc matrix pivot-row normalized)
                         matrix (mapv (fn [index row]
                                        (if (= index pivot-row)
                                          row
                                          (let [factor (nth row column)]
                                            (if (zero? factor)
                                              row
                                              (mapv - row (mapv #(* factor %) normalized))))))
                                      (range row-count) matrix)]
                     (recur (inc column) (inc pivot-row) matrix
                            (assoc pivots column pivot-row)))
                   (recur (inc column) pivot-row matrix pivots))))
        inconsistent? (some (fn [row]
                              (and (every? zero? (take variable-count row))
                                   (not (zero? (nth row variable-count)))))
                            (:matrix rref))]
    (cond
      inconsistent? {:kind :inconsistent}
      (< (count (:pivots rref)) variable-count) {:kind :underdetermined}
      :else {:kind :unique
             :solution (mapv (fn [column]
                               (nth (nth (:matrix rref) (get (:pivots rref) column))
                                    variable-count))
                             (range variable-count))})))

(defn analyze-two-player-mixed-equilibria
  "Enumerate exact mixed-strategy Nash equilibria in a finite two-player game.

   Support enumeration solves indifference systems over integer/rational
   utilities. Unique exact support solutions are checked against every
   out-of-support deviation. Degenerate supports with non-unique feasible
   solutions return :inconclusive rather than asserting completeness."
  ([game] (analyze-two-player-mixed-equilibria game {}))
  ([{:keys [players actions payoffs] :as game}
    {:keys [max-profiles max-support-pairs]
     :or {max-profiles 10000 max-support-pairs 10000}}]
   (let [base (analyze-finite-normal-form-game game {:max-profiles max-profiles})
         players (vec players)
         action-map (if (map? actions) actions {})
         action-vectors (mapv #(get action-map %) players)
         support-pair-count (when (= 2 (count players))
                              (*' (dec (reduce *' 1 (repeat (count (first action-vectors)) 2)))
                                  (dec (reduce *' 1 (repeat (count (second action-vectors)) 2)))))]
     (cond
       (not= :analyzed (:status base))
       (assoc base :equilibrium-concept :mixed-nash :pure-equilibria (:equilibria base))

       (not= 2 (count players))
       {:status :inconclusive :reason :two-player-game-required
        :game/id (:game/id game) :equilibrium-concept :mixed-nash}

       (not (and (integer? max-support-pairs) (pos? max-support-pairs)))
       {:status :inconclusive :reason :invalid-support-budget
        :game/id (:game/id game) :equilibrium-concept :mixed-nash}

       (> support-pair-count max-support-pairs)
       {:status :inconclusive :reason :support-budget-exceeded
        :game/id (:game/id game) :support-pair-count support-pair-count
        :max-support-pairs max-support-pairs :equilibrium-concept :mixed-nash}

       :else
       (let [[player-a player-b] players
             actions-a (first action-vectors)
             actions-b (second action-vectors)
             payoff-by-profile (into {} (map (fn [{:keys [actions utilities]}]
                                               [actions utilities]) payoffs))
             supports-a (nonempty-subsets actions-a)
             supports-b (nonempty-subsets actions-b)
             candidates
             (for [support-a supports-a
                   support-b supports-b
                   :let [reference-a (first support-a)
                         reference-b (first support-b)
                         equations-b (conj
                                      (mapv (fn [action-a]
                                              (conj (mapv (fn [action-b]
                                                            (- (get-in payoff-by-profile
                                                                       [{player-a action-a player-b action-b}
                                                                        player-a])
                                                               (get-in payoff-by-profile
                                                                       [{player-a reference-a player-b action-b}
                                                                        player-a])))
                                                          support-b)
                                                    0))
                                            (rest support-a))
                                      (conj (vec (repeat (count support-b) 1)) 1))
                         equations-a (conj
                                      (mapv (fn [action-b]
                                              (conj (mapv (fn [action-a]
                                                            (- (get-in payoff-by-profile
                                                                       [{player-a action-a player-b action-b}
                                                                        player-b])
                                                               (get-in payoff-by-profile
                                                                       [{player-a action-a player-b reference-b}
                                                                        player-b])))
                                                          support-a)
                                                    0))
                                            (rest support-b))
                                      (conj (vec (repeat (count support-a) 1)) 1))
                         solve-b (solve-exact-linear-system equations-b (count support-b))
                         solve-a (solve-exact-linear-system equations-a (count support-a))]]
               (cond
                 (or (= :inconsistent (:kind solve-a))
                     (= :inconsistent (:kind solve-b)))
                 {:kind :none}

                 (or (= :underdetermined (:kind solve-a))
                     (= :underdetermined (:kind solve-b)))
                 {:kind :underdetermined :support-a support-a :support-b support-b}

                 :else
                 (let [prob-a (:solution solve-a)
                       prob-b (:solution solve-b)
                       positive? (fn [probabilities]
                                   (and (every? pos? probabilities)
                                        (= 1 (reduce + 0 probabilities))))
                       utility-a (fn [action-a]
                                   (reduce + 0
                                           (map (fn [action-b probability]
                                                  (* probability
                                                     (get-in payoff-by-profile
                                                             [{player-a action-a player-b action-b}
                                                              player-a])))
                                                actions-b
                                                (map #(or (get (zipmap support-b prob-b) %) 0)
                                                     actions-b))))
                       utility-b (fn [action-b]
                                   (reduce + 0
                                           (map (fn [action-a probability]
                                                  (* probability
                                                     (get-in payoff-by-profile
                                                             [{player-a action-a player-b action-b}
                                                              player-b])))
                                                actions-a
                                                (map #(or (get (zipmap support-a prob-a) %) 0)
                                                     actions-a))))
                       support-utility-a (utility-a reference-a)
                       support-utility-b (utility-b reference-b)
                       best-response? (and (positive? prob-a) (positive? prob-b)
                                           (every? #(<= (utility-a %) support-utility-a) actions-a)
                                           (every? #(<= (utility-b %) support-utility-b) actions-b))]
                   (if best-response?
                     {:kind :equilibrium
                      :strategies {player-a (zipmap support-a prob-a)
                                   player-b (zipmap support-b prob-b)}
                      :expected-utilities {player-a support-utility-a
                                           player-b support-utility-b}}
                     {:kind :none}))))
             unresolved? (some #(= :underdetermined (:kind %)) candidates)
             equilibria (->> candidates
                             (filter #(= :equilibrium (:kind %)))
                             (map #(select-keys % [:strategies :expected-utilities]))
                             distinct vec)]
         {:status (if unresolved? :inconclusive :analyzed)
          :reason (when unresolved? :degenerate-support-needs-polytope)
          :game/id (:game/id game)
          :equilibrium-concept :mixed-nash
          :pure-equilibria (:equilibria base)
          :equilibria equilibria
          :equilibrium-exists? (boolean (seq equilibria))
          :support-pair-count support-pair-count
          :support-enumeration :exhaustive
          :utilities-authenticated? false})))))

(defn analyze-recovery-game-profiles
  "Evaluate explicit terminal outcomes for each joint action profile, then
   solve the resulting finite pure-strategy game. Deterministic models provide
   :outcome per profile; stochastic models provide :outcomes-by-id, keyed by
   the model's declared outcome IDs. All outcomes remain caller-supplied.
   `opts` is passed to the bounded normal-form solver."
  ([prepared game] (analyze-recovery-game-profiles prepared game {}))
  ([prepared {:keys [actions profiles]} opts]
   (let [model (:model prepared)
         players (mapv :player/id (:players model))
         max-profiles (get opts :max-profiles 10000)
         trust-policy (:assumption-trust-policy opts)
         permission-trust-policy (:action-permission-trust-policy opts)
         require-authenticated? (true? (:require-authenticated-assumptions? opts))
         attestation-check (when trust-policy
                             (verify-recovery-game-assumptions model trust-policy))
         permission-check (when permission-trust-policy
                            (verify-recovery-game-action-permissions
                             model permission-trust-policy))
         action-map (if (map? actions) actions {})
         action-vectors (mapv #(get action-map %) players)
         action-domain-valid? (and (map? actions)
                                   (= (set players) (set (keys actions)))
                                   (every? (fn [choices]
                                             (and (vector? choices) (seq choices)
                                                  (= (count choices) (count (distinct choices)))))
                                           action-vectors))
         expected-profiles (when action-domain-valid?
                             (reduce *' 1 (map count action-vectors)))
         supplied-profiles (when (vector? profiles) (count profiles))
         profile-actions (when (vector? profiles) (mapv :actions profiles))
         profile-table-valid? (and expected-profiles supplied-profiles
                                   (= expected-profiles supplied-profiles)
                                   (every? (fn [profile]
                                             (and (map? profile)
                                                  (= (set players) (set (keys (:actions profile))))
                                                  (every? (fn [[player choice]]
                                                            (some #{choice} (get action-map player)))
                                                          (:actions profile))))
                                           profiles)
                                   (= supplied-profiles
                                      (count (distinct profile-actions))))
         action-domain-authorized? (and (:valid? permission-check)
                                        (every? (fn [[player choices]]
                                                  (every? (get-in permission-check
                                                                  [:permitted-actions player] #{})
                                                          choices))
                                                action-map))
         preflight-reason
         (cond
           (not= :complete (:parameterization/status prepared)) :recovery-model-incomplete
           (:require-validated-execution? opts) :validated-execution-unavailable
           (not (and (integer? max-profiles) (pos? max-profiles))) :invalid-profile-budget
           (or (and expected-profiles (> expected-profiles max-profiles))
               (and supplied-profiles (> supplied-profiles max-profiles)))
           :profile-budget-exceeded
           (not profile-table-valid?) :invalid-or-incomplete-payoff-table
           (and trust-policy (not (:valid? attestation-check)))
           :assumption-attestation-invalid
           (and require-authenticated? (nil? trust-policy))
           :assumption-trust-policy-required
           (and permission-trust-policy (not (:valid? permission-check)))
           :action-permission-attestation-invalid
           (and permission-trust-policy (not action-domain-authorized?))
           :action-permission-not-authorized
           (and require-authenticated? (nil? permission-trust-policy))
           :action-permission-trust-policy-required
           :else nil)]
     (if preflight-reason
       {:status :inconclusive
        :reason preflight-reason
        :game/id (:game/id prepared)
        :expected-profile-count expected-profiles
        :observed-profile-count supplied-profiles
        :max-profiles max-profiles
        :equilibrium-concept :pure-nash}
       (let [stochastic? (= :explicit-probabilities
                            (get-in model [:recovery-process :probability-model]))
             evaluated (mapv (fn [{:keys [actions] :as profile}]
                               (let [result (if stochastic?
                                              (evaluate-recovery-game-distribution
                                               prepared (:outcomes-by-id profile))
                                              (evaluate-recovery-game-outcome
                                               prepared (:outcome profile)))
                                     utility-key (if (= :expected-payoffs-evaluated (:status result))
                                                   :expected-utility :utility)
                                     utilities (when (#{:payoffs-evaluated
                                                        :expected-payoffs-evaluated}
                                                      (:status result))
                                                 (into {} (map (fn [player]
                                                                 [player (get-in result
                                                                                 [:by-player player utility-key])])
                                                               players)))]
                                 {:actions actions
                                  :utilities utilities
                                  :evaluation result})) profiles)
             failed (filterv #(nil? (:utilities %)) evaluated)]
         (if (seq failed)
           {:status :inconclusive
            :reason :profile-outcome-incomplete
            :game/id (:game/id prepared)
            :profile-evaluations evaluated
            :equilibrium-concept :pure-nash}
           (let [analysis (analyze-finite-normal-form-game
                           {:game/id (:game/id prepared)
                            :players players :actions action-map
                            :payoffs (mapv #(select-keys % [:actions :utilities]) evaluated)}
                           opts)]
             (assoc analysis
                    :source-model :recovery-game
                    :execution-assurance {:outcome-source :caller-supplied
                                          :settlement-replayed? false
                                          :outcomes-authenticated? false
                                          :state-dependent-permissions-verified? false}
                    :utility-assumptions-authenticated? (boolean (:valid? attestation-check))
                    :action-permissions-authenticated? (boolean action-domain-authorized?)
                    :assumption-attestation attestation-check
                    :action-permission-attestation permission-check
                    :outcome-evaluations (mapv (fn [{:keys [actions evaluation]}]
                                                 {:actions actions :result evaluation})
                                               evaluated)))))))))

(defn analyze-two-player-recovery-game-profiles
  "Evaluate a complete recovery-game profile table and solve its exact
   two-player mixed Nash equilibria. All budget, outcome, and trust checks from
   `analyze-recovery-game-profiles` apply before mixed-support enumeration."
  ([prepared game] (analyze-two-player-recovery-game-profiles prepared game {}))
  ([prepared game opts]
   (if (not= 2 (count (get-in prepared [:model :players])))
     {:status :inconclusive
      :reason :two-player-game-required
      :game/id (:game/id prepared)
      :equilibrium-concept :mixed-nash}
     (let [pure-analysis (analyze-recovery-game-profiles prepared game opts)]
       (if (not= :analyzed (:status pure-analysis))
         (assoc pure-analysis :equilibrium-concept :mixed-nash)
         (let [players (mapv :player/id (get-in prepared [:model :players]))
               normal-form {:game/id (:game/id prepared)
                            :players players
                            :actions (:actions game)
                            :payoffs (mapv #(select-keys % [:actions :utilities])
                                           (:profile-analysis pure-analysis))}
               mixed-analysis (analyze-two-player-mixed-equilibria normal-form opts)]
           (assoc mixed-analysis
                  :source-model :recovery-game
                  :execution-assurance (:execution-assurance pure-analysis)
                  :utility-assumptions-authenticated?
                  (:utility-assumptions-authenticated? pure-analysis)
                  :action-permissions-authenticated?
                  (:action-permissions-authenticated? pure-analysis)
                  :assumption-attestation (:assumption-attestation pure-analysis)
                  :action-permission-attestation (:action-permission-attestation pure-analysis)
                  :outcome-evaluations (:outcome-evaluations pure-analysis))))))))

(defn analyze-replayed-recovery-game-profiles
  "Replay every profile and branch before solving an exact finite game.
   Game profiles contain :actions and :traces-by-id. Execution models supply
   fixed public plans, explicit credits and zero costs; other semantics fail closed."
  [prepared execution-model {:keys [actions profiles] :as game} opts]
  (let [prepared (prepare-recovery-game-model (:model prepared))
        model (execution/prepare-execution-model prepared execution-model)
        players (mapv :player/id (get-in prepared [:model :players]))
        budget (get opts :max-profiles 10000)
        branch-budget (get opts :max-replays 10000)
        branches (:branches execution-model)
        trust-policy (:assumption-trust-policy opts)
        attestation (when trust-policy (verify-recovery-game-assumptions (:model prepared) trust-policy))
        domain (if (= :verified (:status model))
                 (into {} (map (fn [[player plans]] [player (vec (keys plans))]) (:plans execution-model))) {})
        count-expected (reduce *' 1 (map #(count (get domain %)) players))
        valid-table? (and (= :verified (:status model)) (pos-int? budget) (pos-int? branch-budget)
                          (vector? profiles) (<= (count profiles) budget)
                          (<= count-expected budget)
                          (<= (*' count-expected (count branches)) branch-budget)
                          (map? actions) (= (set players) (set (keys actions)))
                          (= (into {} (map (fn [[k v]] [k (set v)]) actions))
                             (into {} (map (fn [[k v]] [k (set v)]) domain)))
                          (every? #(and (vector? %) (seq %) (= (count %) (count (distinct %)))) (vals actions))
                          (vector? profiles) (= count-expected (count profiles))
                          (= (count profiles) (count (distinct (map :actions profiles))))
                          (every? (fn [profile]
                                    (and (map? profile) (map? (:actions profile)) (map? (:traces-by-id profile))
                                         (= (set players) (set (keys (:actions profile))))
                                         (every? (fn [[p a]] (contains? (set (get domain p)) a)) (:actions profile))
                                         (= (set (map :branch/id branches)) (set (keys (:traces-by-id profile)))))) profiles))]
    (cond
      (not= :verified (:status model)) model
      (not (and (pos-int? budget) (pos-int? branch-budget)))
      {:status :inconclusive :reason :invalid-profile-budget}
      (or (> count-expected budget) (and (vector? profiles) (> (count profiles) budget))
          (> (*' count-expected (count branches)) branch-budget))
      {:status :inconclusive :reason :profile-budget-exceeded}
      (not valid-table?) {:status :inconclusive :reason :invalid-or-incomplete-payoff-table}
      (and (:require-authenticated-assumptions? opts) (nil? trust-policy))
      {:status :inconclusive :reason :assumption-trust-policy-required}
      (and trust-policy (not (:valid? attestation)))
      {:status :inconclusive :reason :assumption-attestation-invalid}
      :else
      (let [replays (mapv (fn [profile]
                            {:actions (:actions profile)
                             :branches (mapv (fn [branch]
                                               {:branch/id (:branch/id branch)
                                                :result (execution/replay-profile
                                                         model (:actions profile) (:branch/id branch)
                                                         (get-in profile [:traces-by-id (:branch/id branch)]) opts)}) branches)}) profiles)
            failures (vec (for [profile replays branch (:branches profile)
                                :when (not= :verified (get-in branch [:result :status]))]
                            {:actions (:actions profile) :branch/id (:branch/id branch)
                             :reason (get-in branch [:result :reason])}))]
        (if (seq failures)
          {:status :inconclusive :reason :profile-execution-invalid :failures failures}
          (let [stochastic? (= :explicit-probabilities (get-in prepared [:model :recovery-process :probability-model]))
                derived (assoc game :profiles
                               (mapv (fn [profile]
                                       (let [outcomes (into {} (map (fn [b] [(:branch/id b) (get-in b [:result :outcome])]) (:branches profile)))]
                                         (if stochastic?
                                           {:actions (:actions profile) :outcomes-by-id outcomes}
                                           {:actions (:actions profile) :outcome (get outcomes :deterministic)}))) replays))
                analyzer (if (:mixed? opts) analyze-two-player-recovery-game-profiles analyze-recovery-game-profiles)
                analysis (analyzer prepared derived (dissoc opts :require-validated-execution?
                                                            :require-authenticated-assumptions?
                                                            :action-permission-trust-policy))]
            (assoc analysis
                   :source-model :replayed-recovery-game
                   :execution-assurance {:outcome-source :verified-replay
                                         :settlement-replayed? true
                                         :outcomes-authenticated? false
                                         :state-dependent-permissions-verified? true}
                   :execution-evidence/root
                   (execution/content-root :partial-fill-game-execution-evidence.v1
                                            {:model/root (recovery-game-model-root (:model prepared))
                                             :execution/root (:execution/root model)
                                             :initial-state/root (execution/state-root (execution/initial-state model))
                                             :actions actions :replays replays}))))))))

(defn- enumerate-policies
  "Generate the policy variants to test."
  []
  [{:mode :pro-rata :rounding-policy :floor}
   {:mode :pro-rata :rounding-policy :largest-remainder}])

;; ---------------------------------------------------------------------------
;; Transformation functions
;; ---------------------------------------------------------------------------

(defn- split-claim
  "Split claim at index `idx` into `parts` equal parts.
   Returns a new claim vector with more entries."
  [claims idx parts]
  (let [n (long (nth claims idx))
        base (quot n parts)
        rem (- n (* base parts))
        splits (concat (repeat rem (inc base))
                       (repeat (- parts rem) base))
        before (take idx claims)
        after (drop (inc idx) claims)]
    (vec (concat before splits after))))

(defn- merge-claims
  "Merge claims at indices `idxs` into a single claim (sum of values).
   Returns a new claim vector with fewer entries."
  [claims idxs]
  (let [idxs-set (set idxs)
        merged-sum (reduce + 0 (map (fn [i] (nth claims i)) idxs))
        remaining (keep-indexed (fn [i v] (when (not (idxs-set i)) v)) claims)]
    (vec (cons merged-sum remaining))))

(defn- permute-claims
  "Apply a permutation to claim order.
   Returns a new claim vector."
  [claims perm]
  (vec (map (fn [i] (nth claims i)) perm)))

(defn- inflate-claim
  "Increase claim at index `idx` by `delta`."
  [claims idx delta]
  (let [v (vec claims)]
    (assoc v idx (+ (nth v idx) delta))))

;; ---------------------------------------------------------------------------
;; Invariance checks
;; ---------------------------------------------------------------------------

(defn check-split-invariance
  "Verify that splitting a claim into N equal parts produces the same
   total allocation as the original single claim.
   
   For each claim in each state, tries splitting into 2, 3, or 4 parts.
   Returns vector of violation maps."
  [claims available policy]
  (let [filled (allocate claims available policy)
        violations (atom [])]
    (doseq [idx (range (count claims))
            :let [n (nth claims idx)]
            :when (>= n 2)
            parts [2 3 4]
            :when (>= n parts)]
      (let [split-claims (split-claim claims idx parts)
            split-filled (allocate split-claims available policy)
            original-total (get filled (str "c" idx) 0)
            split-total (reduce + 0 (map #(get split-filled (str "c" %) 0)
                                         (range idx (+ idx parts))))
            error (- split-total original-total)]
        (when (not= split-total original-total)
          (swap! violations conj
                 {:claim idx :original n :parts parts
                  :original-allocation original-total
                  :split-allocation split-total
                  :error error}))))
    @violations))

(defn check-merge-invariance
  "Return exact merge-invariance violations for every pair of claims.

   Integer per-claim rounding is identity-sensitive, so this property is not
   expected to hold for either supported rounding policy."
  [claims available policy]
  (let [filled (allocate claims available policy)
        violations (atom [])]
    (doseq [i (range (dec (count claims)))
            j (range (inc i) (count claims))]
      (let [merged-claims (merge-claims claims [i j])
            merged-filled (allocate merged-claims available policy)
            individual-sum (+ (get filled (str "c" i) 0)
                              (get filled (str "c" j) 0))
            merged-allocation (get merged-filled (str "c0") 0)
            error (- merged-allocation individual-sum)]
        (when (not= merged-allocation individual-sum)
          (swap! violations conj
                 {:claims [i j] :individual-sum individual-sum
                  :merged-allocation merged-allocation
                  :error error}))))
    @violations))

(defn- permutations
  "Generate all permutations of a vector (iterative algorithm).
   Returns a lazy seq of vectors."
  [v]
  (let [n (count v)]
    (if (<= n 1)
      [v]
      (mapcat (fn [i]
                (let [elem (nth v i)
                      rest-v (vec (concat (take i v) (drop (inc i) v)))]
                  (map (fn [p] (vec (cons elem p)))
                       (permutations rest-v))))
              (range n)))))

(defn check-permutation-invariance
  "Compare each original claimant's allocation after mapping permuted positions
   back to the same claimant. Supports up to five claims."
  [claims available policy]
  (when (> (count claims) 5)
    (throw (ex-info "Permutation scope exceeds five claims" {:claim-count (count claims)})))
  (let [baseline (:allocations (allocation-report claims available policy))]
    (vec (for [perm (permutations (range (count claims)))
               :let [changed (:allocations (allocation-report (permute-claims claims perm) available policy))]
               [new-index original-index] (map-indexed vector perm)
               :let [before (nth baseline original-index)
                     after (nth changed new-index)]
               :when (not= before after)]
           {:claim original-index :permutation (vec perm)
            :original-allocation before :permuted-allocation after
            :error (- after before)}))))

(defn check-sybil-invariance
  "Search for profitable unilateral splits, preserving the owner's identity
   across its child claims while every other claimant stays unchanged."
  [claims available policy]
  (vec (for [violation (check-split-invariance claims available policy)
             :when (pos? (:error violation))]
         (assoc violation :gain (:error violation)))))

(defn check-request-monotonicity
  "Verify that increasing a claim's requested amount does not decrease
   its allocation (monotonicity property)."
  [claims available policy]
  (let [baseline-filled (allocate claims available policy)
        violations (atom [])]
    (doseq [idx (range (count claims))
            delta [1 2 5]]
      (let [inflated (inflate-claim claims idx delta)
            inflated-filled (allocate inflated available policy)]
        (doseq [j (range (count claims))]
          (let [original-alloc (get baseline-filled (str "c" j) 0)
                new-alloc (get inflated-filled (str "c" j) 0)]
            ;; For the inflated claim, allocation should not decrease
            (when (and (= j idx) (< new-alloc original-alloc))
              (swap! violations conj
                     {:claim idx :delta delta
                      :original-alloc original-alloc
                      :new-alloc new-alloc
                      :kind :inflated-claim-lost-allocation}))
            ;; For non-inflated claims, allocation should not increase
            (when (and (not= j idx) (> new-alloc original-alloc))
              (swap! violations conj
                     {:claim j :delta delta :inflated-claim idx
                      :original-alloc original-alloc
                      :new-alloc new-alloc
                      :kind :non-inflated-claim-gained-allocation}))))))
    @violations))

;; ---------------------------------------------------------------------------
;; Registered deviation generators
;; ---------------------------------------------------------------------------

(def registered-deviation-generators
  "Registered deviation generators.  Each generator declares:

     {:id        kw     ;; generator keyword, e.g. :split
      :property  kw     ;; strategic property it evaluates
      :evaluate  (fn [claims liquidity policy] -> check-map)}

   The check-map returned by :evaluate is a single property check result:

     {:property kw
      :verdict  :verified | :violated
      :counterexamples [...]          ;; optional, violation witnesses
      :state    {:claims [...] :liquidity N :policy {...}}  ;; evaluated state
      ...}

   Adding a NEW deviation family is a registration act, not a dispatcher edit:
   a protocol supplies extra generators via the :generators option to
   validate-strategic-properties, and the harness runs them generically."
  {:split
   {:id :split
    :property :strategy/split-invariance
    :evaluate (fn [claims liquidity policy]
                (let [v (check-split-invariance claims liquidity policy)]
                  {:property :strategy/split-invariance
                   :verdict (if (empty? v) :verified :violated)
                   :counterexamples (when (seq v) (take 3 v))
                   :state {:claims claims :liquidity liquidity
                           :policy (select-keys policy [:mode :rounding-policy])}}))}

   :merge
   {:id :merge
    :property :allocation/exact-merge-invariance
    :evaluate (fn [claims liquidity policy]
                (let [sample-violations (check-merge-invariance claims liquidity policy)
                      ;; Deterministic witnesses prevent a sampled run from claiming a
                      ;; universal algebraic property it cannot establish.  Flooring
                      ;; needs two units to expose its own non-additivity.
                      regression-state (if (= :floor (:rounding-policy policy))
                                         {:claims [1 1 1] :liquidity 2}
                                         {:claims [1 1 1] :liquidity 1})
                      regression-violations (check-merge-invariance
                                             (:claims regression-state)
                                             (:liquidity regression-state)
                                             policy)
                      violations (vec (concat sample-violations regression-violations))]
                  {:property :allocation/exact-merge-invariance
                   :status :violated
                   :verdict :violated
                   :rounding-policy (:rounding-policy policy)
                   :counterexamples (take 3 violations)
                   :regression-counterexample (assoc regression-state
                                                     :merged-indices [1 2]
                                                     :merged-claims [1 2]
                                                     :individual-sum 0
                                                     :merged-allocation 1
                                                     :error 1)
                   :state {:claims claims :liquidity liquidity
                           :policy (select-keys policy [:mode :rounding-policy])}}))}

   :permute
   {:id :permute
    :property :strategy/permutation-invariance
    :evaluate (fn [claims liquidity policy]
                (let [v (check-permutation-invariance claims liquidity policy)]
                  {:property :strategy/permutation-invariance
                   :verdict (if (empty? v) :verified :violated)
                   :counterexamples (when (seq v) (take 3 v))
                   :state {:claims claims :liquidity liquidity
                           :policy (select-keys policy [:mode :rounding-policy])}}))}

   :sybil
   {:id :sybil
    :property :strategy/sybil-invariance
    :evaluate (fn [claims liquidity policy]
                (let [v (check-sybil-invariance claims liquidity policy)]
                  {:property :strategy/sybil-invariance
                   :verdict (if (empty? v) :verified :violated)
                   :counterexamples (when (seq v) (take 3 v))
                   :state {:claims claims :liquidity liquidity
                           :policy (select-keys policy [:mode :rounding-policy])}}))}

   :inflate
   {:id :inflate
    :property :strategy/request-monotonicity
    :evaluate (fn [claims liquidity policy]
                (let [v (check-request-monotonicity claims liquidity policy)]
                  {:property :strategy/request-monotonicity
                   :verdict (if (empty? v) :verified :violated)
                   :counterexamples (when (seq v) (take 3 v))
                   :state {:claims claims :liquidity liquidity
                           :policy (select-keys policy [:mode :rounding-policy])}}))}})

(defn generator-for-property
  "Resolve the registered generator that produces the given property.
   Returns the generator map, or nil."
  [property]
  (some (fn [g] (when (= property (:property g)) g))
        (vals registered-deviation-generators)))

(defn resolve-deviation-generators
  "Resolve a set of deviation keywords to their generator maps.
   Unknown deviations (not in the registered or supplied generator maps)
   throw, so a claim can never silently drop part of its declared scope."
  [deviations & {:keys [extra-generators]
                 :or {extra-generators {}}}]
  (let [all (merge registered-deviation-generators extra-generators)]
    (mapv (fn [dev]
            (or (get all dev)
                (throw (ex-info "Unresolved deviation generator"
                                {:deviation dev
                                 :known (vec (sort (keys all)))}))))
          deviations)))

(defn diagnostic-transform-for-property
  "Return the diagnostic-transform metadata for a property, deriving from the
   registered generator (or a supplied resolved generator map).  Returns nil
   for unknown properties."
  ([property]
   (when-let [g (generator-for-property property)]
     {:id (:id g)
      :role :diagnostic-transform
      :semantic-purpose :bounded-counterexample-search}))
  ([property generator-by-property]
   (when-let [g (or (get generator-by-property property)
                    (generator-for-property property))]
     {:id (:id g)
      :role :diagnostic-transform
      :semantic-purpose :bounded-counterexample-search})))

(defn validate-strategic-properties
  "Run all strategic invariance checks across enumerated states.
   Returns {:properties [...] :summary {...}}.

   The evaluation harness is generator-driven: deviation keywords resolve to
   registered generators (registered-deviation-generators) merged with any
   protocol/application-supplied generators passed via :extra-generators.
   Adding a new deviation family is a registration act, not a dispatcher edit.

   Options:
   - :scope — an EnumerationScope (:dimensions map of dimension-kw -> [lo hi],
     :sampling, :max-states); defaults to default-scope
   - :policies — vector of policy maps to test
   - :deviations — vector of deviation keywords to test
   - :extra-generators — map of {deviation-kw generator-map} supplied by a
     protocol/application.  Each generator must have :id, :property, and
     :evaluate (fn [claims liquidity policy] -> check-map); see
     registered-deviation-generators for the canonical shape.
   - :max-states — the configured evaluation cap.  Kept as the compatibility
     input name, but it caps state × policy executions, not distinct states:
     with P policies the enumeration examines at most (max-states / P) distinct
     states.  The artifact reports this precisely via :state-policy-evaluations,
     :distinct-states-examined, :policies, and :max-state-policy-evaluations.
   - :contract-id — deviation contract id; when set, deviations are derived
     from the contract and :deviations option is ignored
   - :contract-registry — explicit deviation-contract registry to resolve
     :contract-id against (defaults to the framework-builtin registry)
   - :declared-property-ids — strategic properties the caller explicitly makes
     gate-relevant; all other transformation results are diagnostic observations"
  [& {:keys [scope policies deviations max-states contract-id contract-registry
             extra-generators declared-property-ids]
      :or {scope default-scope
           policies (enumerate-policies)
           max-states 500}}]
  (let [contract-registry (or contract-registry dc/default-deviation-contract-registry)
        contract (when contract-id (dc/get-contract contract-registry contract-id))
        deviations (or (when contract (dc/deviations-in-contract contract-registry contract-id))
                       (when (some? deviations) (vec deviations))
                       (vec (sort (keys registered-deviation-generators))))
        _ (when (or (empty? deviations) (empty? policies) (not (pos-int? max-states)))
            (throw (ex-info "Strategic validation requires deviations, policies and a positive evaluation budget"
                            {:reason :empty-strategic-validation})))
        generators (resolve-deviation-generators deviations
                                                 :extra-generators extra-generators)
        generator-by-property (into {} (map (fn [g] [(:property g) g])) generators)
        declared-property-ids (set declared-property-ids)
        results (atom [])
        state-policy-count (atom 0)
        distinct-states (atom #{})
        policies (vec policies)
        enum-states (enum/generate-states scope)]
    (doseq [state enum-states
            policy policies
            :while (< @state-policy-count max-states)]
      (let [request-vec (:claims state)
            liquidity (:liquidity state)
            _ (swap! state-policy-count inc)
            _ (swap! distinct-states conj [request-vec liquidity])
            checks (mapv (fn [g]
                           ((:evaluate g) request-vec liquidity policy))
                         generators)]
        (swap! results conj {:state-policy-evaluations @state-policy-count
                             :claims request-vec
                             :liquidity liquidity
                             :policy policy
                             :checks checks})))
    (let [all-verdicts (mapcat (fn [r] (map :verdict (:checks r))) @results)
          total-checks (count all-verdicts)
          verified (count (filter #{:verified} all-verdicts))
          violated (count (filter #{:violated} all-verdicts))
          policy-identifiers (mapv (fn [p] (select-keys p [:mode :rounding-policy]))
                                   policies)
          state-policy-evaluations @state-policy-count
          distinct-states-examined (count @distinct-states)
          [cc-lo cc-hi] (get-in scope [:dimensions :claim-count])
          [rq-lo rq-hi] (get-in scope [:dimensions :request])
          [lq-lo lq-hi] (get-in scope [:dimensions :liquidity])
          request-width (inc (- rq-hi rq-lo))
          domain-cardinality (*' (inc (- lq-hi lq-lo))
                                 (reduce +' 0 (map (fn [n]
                                                     (reduce *' 1 (repeat n request-width)))
                                                   (range cc-lo (inc cc-hi)))))
          complete? (and (= :exhaustive (:sampling scope))
                         (= state-policy-evaluations
                            (*' domain-cardinality (count policies))))]
      {:artifact/kind :strategic-closed-form-validation
       :mechanism :yield/partial-fill
       :contract-id contract-id
       :strategic-model {:mechanism :yield/partial-fill
                         :allocator :amount-only-ordinal-reference
                         :production-allocator-validated? false
                         :allocation-mode :pro-rata
                         :rounding-policies policy-identifiers
                         :actions [:honest-request
                                   :split
                                   :merge
                                   :permute
                                   :sybil-split
                                   :inflate-request
                                   :claim-now-or-defer
                                   :delay-arrival
                                   :reorder-arrival
                                   :manipulate-priority
                                   :manipulate-cap
                                   :withhold-liquidity-across-recovery-rounds]
                         :payoff-model :allocated-amount-only
                         :recovery-game-support
                         {:schema-version "partial-fill-recovery-game.v2"
                          :temporal-model :sequential-recovery-rounds-with-concurrent-arrivals
                          :execution-parameter-dimensions
                          [:submission-boundary :same-block-or-multi-block-inclusion
                           :transaction-ordering :ordering-control :arrival-timing
                           :shared-liquidity-contention :per-submission-atomicity
                           :duplicate-entitlement-policy :retry-policy]
                          :action-dimensions [:claim-timing :claim-splitting-and-merging
                                              :arrival-order :priority :request-size
                                              :claim-cap :liquidity-availability]
                          :payoff-dimensions [:amount :timing-and-time-value
                                              :recovery-risk :fees-and-costs
                                              :priority-externalities :penalties
                                              :continuation-value]
                          :required-parameterization [:utility-functions
                                                      :discounting
                                                      :information-available-to-actors
                                                      :action-timing
                                                      :parallel-execution-model
                                                      :recovery-process]
                          :readiness :schema-ready-payoffs-unparameterized
                          :analysis-status :inconclusive
                          :equilibrium-claim :none}
                         :scope-kind :bounded-enumeration
                         :claims-unmodeled [:fees
                                            :timing
                                            :recovery-risk
                                            :side-payments
                                            :continuation-values]}
       :validation-scope {:dimensions (:dimensions scope)
                          :domain-cardinality domain-cardinality
                          :complete? complete?
                          :incompleteness-reason (when-not complete?
                                                   (if (= :exhaustive (:sampling scope))
                                                     :enumeration-or-evaluation-budget
                                                     :sampling))
                          :sampling (:sampling scope)
                          :policies policy-identifiers
                          :max-state-policy-evaluations max-states
                          :state-policy-evaluations state-policy-evaluations
                          :distinct-states-examined distinct-states-examined
                          :states-examined state-policy-evaluations
                          :diagnostic-transformations (vec (sort deviations))
                          :declared-property-ids (vec (sort declared-property-ids))}
       :epistemic-scope {:scope/kind (if (= :exhaustive (:sampling scope))
                                       :bounded-exhaustive :bounded-sampled)
                         :scope/universal-claim? false
                         :scope/falsification? true
                         :scope/coverage :validation-scope
                         :scope/limitations [:bounded-domain
                                             :bounded-policy-set
                                             :bounded-transformation-set
                                             :no-equilibrium-proof
                                             :no-concurrency-assurance
                                             :recovery-payoffs-unparameterized]}
       :properties (->> (mapcat :checks @results)
                        (group-by :property)
                        (mapv (fn [[prop results]]
                                (let [verdict (if (every? #(= :verified (:verdict %)) results)
                                                :verified :violated)
                                      state-keys (map (fn [r] [(get-in r [:state :claims])
                                                               (get-in r [:state :liquidity])])
                                                      results)]
                                  {:property prop
                                   :property-role (if (contains? declared-property-ids prop)
                                                    :declared-property
                                                    :diagnostic-observation)
                                   :diagnostic-transform (diagnostic-transform-for-property prop generator-by-property)
                                   :evaluation/status (if (= :verified verdict)
                                                        :no-counterexample-found
                                                        :counterexample-found)
                                   :status verdict
                                   :verdict verdict
                                   :violation-count (count (filter #(= :violated (:verdict %)) results))
                                   :state-count (count (distinct state-keys))
                                   :state-policy-evaluations (count results)
                                   :counterexample (some :regression-counterexample results)
                                   :sample-counterexamples (->> results
                                                                (filter #(= :violated (:verdict %)))
                                                                (take 2)
                                                                (mapcat :counterexamples)
                                                                (take 3))}))))
       :summary {:states-examined state-policy-evaluations
                 :state-policy-evaluations state-policy-evaluations
                 :distinct-states-examined distinct-states-examined
                 :policies policy-identifiers
                 :max-state-policy-evaluations max-states
                 :properties-examined (count (distinct (mapcat (fn [r] (map :property (:checks r))) @results)))
                 :total-checks total-checks
                 :verified verified
                 :violated violated
                 :valid? (and (pos? total-checks) (zero? violated))}})))
