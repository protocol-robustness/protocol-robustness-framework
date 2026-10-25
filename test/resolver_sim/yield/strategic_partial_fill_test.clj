(ns resolver-sim.yield.strategic-partial-fill-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.yield.strategic-partial-fill :as strategic]))

(def recovery-game-fixture-catalog
  (edn/read-string
   (slurp (io/resource "yield/partial-fill-recovery-game-fixtures.edn"))))

(defn- prepared-recovery-game-fixtures []
  (mapv (fn [case]
          [case (strategic/prepare-recovery-game-model
                 (merge (:defaults recovery-game-fixture-catalog) case))])
        (:cases recovery-game-fixture-catalog)))

(deftest integer-rounding-is-not-exactly-merge-invariant
  (is (= [{:claims [1 2]
           :individual-sum 0
           :merged-allocation 1
           :error 1}]
         (strategic/check-merge-invariance
          [1 1 1] 1 {:mode :pro-rata
                     :rounding-policy :largest-remainder})))
  (is (some #(= {:claims [1 2]
                 :individual-sum 0
                 :merged-allocation 1
                 :error 1}
                %)
            (strategic/check-merge-invariance
             [1 1 1] 2 {:mode :pro-rata
                        :rounding-policy :floor}))))

(deftest rounding-policies-have-distinct-accounting-semantics
  (let [input [1 1 1]
        floor-result (strategic/allocation-report
                      input 1 {:rounding-policy :floor})
        remainder-result (strategic/allocation-report
                          input 1 {:rounding-policy :largest-remainder})]
    (testing "floor keeps its rounding residual visible"
      (is (= [0 0 0] (:allocations floor-result)))
      (is (= 0 (:distributed floor-result)))
      (is (= 1 (:undistributed floor-result))))
    (testing "largest remainder distributes the residual"
      (is (= [1 0 0] (:allocations remainder-result)))
      (is (= 1 (:distributed remainder-result)))
      (is (zero? (:undistributed remainder-result))))))

(deftest validation-artifact-reports-the-known-merge-counterexample
  (let [artifact (strategic/validate-strategic-properties
                  :deviations [:merge]
                  :policies [{:mode :pro-rata
                              :rounding-policy :largest-remainder}]
                  :max-states 1)
        property (first (:properties artifact))]
    (is (= :allocation/exact-merge-invariance (:property property)))
    (is (= :violated (:verdict property)))
    (is (= 1 (:violation-count property)))
    (is (= {:claims [1 1 1]
            :liquidity 1
            :merged-indices [1 2]
            :merged-claims [1 2]
            :individual-sum 0
            :merged-allocation 1
            :error 1}
           (:counterexample property)))))

(deftest split-invariance-preserves-total-allocation
  (testing "splitting a claim into equal parts preserves total allocation"
    (let [violations (strategic/check-split-invariance
                      [2 2] 2 {:mode :pro-rata :rounding-policy :largest-remainder})]
      (is (empty? violations)))))

(deftest permutation-invariance-is-order-independent
  (testing "reordering claims does not change total allocations"
    (let [violations (strategic/check-permutation-invariance
                      [3 1] 4 {:mode :pro-rata :rounding-policy :largest-remainder})]
      (is (empty? violations)))))

(deftest sybil-invariance-prevents-total-allocation-gain
  (testing "splitting a claim into sybil identities does not increase total allocation"
    (let [violations (strategic/check-sybil-invariance
                      [4] 4 {:mode :pro-rata :rounding-policy :largest-remainder})]
      (is (empty? violations)))))

(deftest request-monotonicity-preserves-allocations
  (testing "inflating a claim does not decrease its allocation or increase others"
    (let [violations (strategic/check-request-monotonicity
                      [3 1] 4 {:mode :pro-rata :rounding-policy :largest-remainder})]
      (is (empty? violations)))))

(deftest validate-strategic-properties-returns-complete-artifact
  (testing "validation artifact includes expected top-level keys"
    (let [artifact (strategic/validate-strategic-properties
                    :deviations [:split :merge :permute :sybil :inflate]
                    :policies [{:mode :pro-rata :rounding-policy :largest-remainder}]
                    :max-states 10)
          summary (:summary artifact)]
      (is (= :strategic-closed-form-validation (:artifact/kind artifact)))
      (is (= :yield/partial-fill (:mechanism artifact)))
      (is (map? (:validation-scope artifact)))
      (is (vector? (:properties artifact)))
      (is (map? summary))
      (is (number? (:total-checks summary)))
      (is (number? (:verified summary)))
      (is (number? (:violated summary))))))

(deftest artifact-epistemic-scope-is-bounded-exhaustive
  (testing "epistemic-scope declares bounded exhaustive search"
    (let [artifact (strategic/validate-strategic-properties
                    :deviations [:split]
                    :max-states 5)
          scope (:epistemic-scope artifact)]
      (is (map? scope))
      (is (= :bounded-exhaustive (:scope/kind scope)))
      (is (false? (:scope/universal-claim? scope)))
      (is (true? (:scope/falsification? scope)))
      (is (= :validation-scope (:scope/coverage scope)))
      (is (contains? (set (:scope/limitations scope)) :bounded-domain))
      (is (contains? (set (:scope/limitations scope)) :no-equilibrium-proof))
      (is (contains? (set (:scope/limitations scope)) :no-concurrency-assurance)))))

(deftest artifact-strategic-model-describes-the-evaluation
  (testing "strategic-model captures mechanism, payoff, scope-kind, and actions"
    (let [artifact (strategic/validate-strategic-properties
                    :deviations [:split :merge :permute :sybil :inflate]
                    :policies [{:mode :pro-rata :rounding-policy :largest-remainder}]
                    :max-states 5)
          model (:strategic-model artifact)]
      (is (map? model))
      (is (= :yield/partial-fill (:mechanism model)))
      (is (= :pro-rata (:allocation-mode model)))
      (is (= :allocated-amount-only (:payoff-model model)))
      (is (= :bounded-enumeration (:scope-kind model)))
      (is (vector? (:rounding-policies model)))
      (is (vector? (:actions model)))
      (is (vector? (:claims-unmodeled model)))
      (is (set (set (:actions model)))
          "actions is a vector of keywords"))))

(deftest recovery-game-model-is-ready-for-later-parameterization
  (let [artifact (strategic/validate-strategic-properties
                  :deviations [:split]
                  :max-states 2)
        support (get-in artifact [:strategic-model :recovery-game-support])]
    (is (= "partial-fill-recovery-game.v2" (:schema-version support)))
    (is (= :sequential-recovery-rounds-with-concurrent-arrivals
           (:temporal-model support)))
    (is (contains? (set (:execution-parameter-dimensions support))
                   :transaction-ordering))
    (is (contains? (set (:action-dimensions support)) :liquidity-availability))
    (is (contains? (set (:payoff-dimensions support)) :recovery-risk))
    (is (= :schema-ready-payoffs-unparameterized (:readiness support)))
    (is (= :inconclusive (:analysis-status support)))
    (is (= :none (:equilibrium-claim support)))
    (is (contains? (set (get-in artifact [:epistemic-scope :scope/limitations]))
                   :recovery-payoffs-unparameterized))))

(deftest recovery-game-fixture-catalog-is-parameterized-but-not-analyzed
  (let [prepared (prepared-recovery-game-fixtures)]
    (is (= 16 (count prepared)))
    (doseq [[case result] prepared]
      (is (= (:game/id case) (:game/id result)))
      (is (= :complete (:parameterization/status result))
          (str (:game/id case) " parameterization violations: " (:violations result)))
      (is (= :not-run-inconclusive (:analysis-status result)))
      (is (= :none (:equilibrium-claim result)))
      (is (false? (:candidate-actions-are-protocol-permissions? result))))))

(deftest recovery-game-fixtures-cover-partial-recovery-and-parallel-claims
  (let [cases (into {} (map (fn [[case result]] [(:game/id case) (:model result)])
                            (prepared-recovery-game-fixtures)))
        partial (get cases "partial-recovery-concurrent-claims")
        expected (:expected partial)
        initial-round (first (:rounds partial))
        later-round (second (:rounds partial))]
    (is (= 10000 (:initial-shortfall expected)))
    (is (= 5000 (:recovered-amount expected)))
    (is (= [:alice-right :bob-right :carol-right] (:incoming initial-round)))
    (is (= (:incoming initial-round) (:incoming later-round)))
    (is (= 30000 (reduce + (vals (:requested-by-entitlement initial-round)))))
    (is (= 10000 (- (reduce + (vals (:requested-by-entitlement initial-round)))
                    (:available-liquidity initial-round))))
    (is (= [:bob-right :carol-right :alice-right]
           (:arrival-sequence (get-in cases ["arrival-order-permutation" :rounds 1]))))
    (is (= [10000 5000] (:outstanding-after-round expected)))
    (is (= 5000 (reduce + (map :amount (:recovery-events later-round)))))
    (is (= ["same-block-parallel-submissions"
            "duplicate-entitlement-competing-callers"
            "incoming-entitlement-during-open-shortfall"
            "staggered-multi-block-submissions"
            "no-recovery" "full-recovery" "early-recovery" "late-recovery"
            "arrival-order-permutation" "claim-splitting-candidate"
            "request-and-cap-manipulation-candidates"
            "liquidity-provider-withholding-candidate"
            "high-delay-cost-sensitivity" "recovery-risk-sensitivity"
            "recovery-probability-assumption-sensitivity"]
           (mapv :game/id (rest (:cases recovery-game-fixture-catalog)))))))

(deftest same-block-execution-parameters-require-independent-transaction-identities
  (let [prepared (into {} (map (fn [[case result]] [(:game/id case) result])
                               (prepared-recovery-game-fixtures)))
        parallel (get prepared "same-block-parallel-submissions")
        duplicate (get prepared "duplicate-entitlement-competing-callers")
        staggered (get prepared "staggered-multi-block-submissions")
        model (:model parallel)
        incoming (get prepared "incoming-entitlement-during-open-shortfall")
        incoming-model (:model incoming)
        invalid (assoc-in model [:execution-model :parallelism :submissions 0]
                          {:transaction/id "tx-no-index"
                           :block/id "block-2"
                           :caller/id :alice-caller
                           :entitlement/id :alice-right})
        unknown-entitlement (assoc-in model
                                      [:execution-model :parallelism :submissions 0
                                       :entitlement/id]
                                      :unregistered-right)
        negative-index (assoc-in model
                                 [:execution-model :parallelism :submissions 0
                                  :transaction/index]
                                 -1)
        empty-submissions (assoc-in model [:execution-model :parallelism :submissions] [])
        split-blocks (assoc-in model
                               [:execution-model :parallelism :submissions 0 :block/id]
                               "different-block")
        wrong-submission-round (assoc-in incoming-model
                                         [:execution-model :parallelism :submissions 3 :round/index]
                                         0)
        invalid-results (mapv strategic/prepare-recovery-game-model
                              [invalid unknown-entitlement negative-index
                               empty-submissions split-blocks wrong-submission-round])]
    (is (= :complete (:parameterization/status parallel)))
    (is (= :complete (:parameterization/status duplicate)))
    (is (= :complete (:parameterization/status staggered)))
    (is (= [2 0 1] (mapv :transaction/index
                         (get-in model [:execution-model :parallelism :submissions]))))
    (is (= [:alice-right :alice-right]
           (mapv :entitlement/id
                 (get-in (:model duplicate)
                         [:execution-model :parallelism :submissions]))))
    (is (= #{:alice-caller :relayer-caller}
           (set (map :caller/id
                     (get-in (:model duplicate)
                             [:execution-model :parallelism :submissions])))))
    (is (= [105 100 110]
           (mapv :arrival-time
                 (get-in (:model staggered)
                         [:execution-model :parallelism :submissions]))))
    (is (= :complete (:parameterization/status incoming)))
    (is (= [:alice-right :bob-right :carol-right]
           (get-in incoming-model [:rounds 0 :incoming])))
    (is (= [:alice-right :bob-right :carol-right :dave-right]
           (get-in incoming-model [:rounds 1 :incoming])))
    (is (= [0 1 2 3]
           (mapv :transaction/index
                 (get-in incoming-model [:execution-model :parallelism :submissions]))))
    (is (every? #(= 1 (:round/index %))
                (get-in incoming-model [:execution-model :parallelism :submissions])))
    (is (= [10000 15000] (get-in incoming-model [:expected :outstanding-after-round])))
    (is (= #{"block-4" "block-5"}
           (set (map :block/id
                     (get-in (:model staggered)
                             [:execution-model :parallelism :submissions])))))
    (doseq [result invalid-results]
      (is (= :incomplete (:parameterization/status result)))
      (is (some #(= :parallel-execution-parameters-incomplete (:reason %))
                (:violations result))))))

(deftest shortfall-fixture-rounds-reconcile-to-deferred-liability
  (doseq [[case result] (prepared-recovery-game-fixtures)
          :let [model (:model result)
                expected (:expected case)]
          :when (contains? expected :initial-shortfall)]
    (let [rounds (:rounds model)
          initial-request (reduce + 0 (vals (:requested-by-entitlement (first rounds))))
          recovery-before-first-claim (reduce + 0 (map :amount
                                                       (:recovery-events (first rounds))))
          initial-shortfall (max 0 (- initial-request
                                      (- (:available-liquidity (first rounds))
                                         recovery-before-first-claim)))
          outstanding-by-round
          (mapv (fn [round]
                  (let [requested (reduce + 0 (vals (:requested-by-entitlement round)))]
                    (- requested (min requested (:available-liquidity round)))))
                rounds)
          recovered (reduce + 0 (mapcat (fn [round]
                                          (map :amount (:recovery-events round)))
                                        rounds))]
      (is (= (:initial-shortfall expected) initial-shortfall) (:game/id case))
      (is (= (:outstanding-after-round expected) outstanding-by-round) (:game/id case))
      (is (= (:recovered-amount expected) recovered) (:game/id case)))))

(deftest recovery-game-preparation-fails-closed-on-incomplete-utilities
  (let [[case _] (first (prepared-recovery-game-fixtures))
        model (merge (:defaults recovery-game-fixture-catalog) case)
        incomplete (update-in model [:payoff-parameters :by-player :alice]
                              dissoc :discount-factor-bps)
        result (strategic/prepare-recovery-game-model incomplete)]
    (is (= :incomplete (:parameterization/status result)))
    (is (= :not-run-inconclusive (:analysis-status result)))
    (is (some #(= :utility-parameterization-incomplete (:reason %))
              (:violations result)))))

(deftest withholding-candidate-requires-an-explicit-provider-player
  (let [model (merge (:defaults recovery-game-fixture-catalog)
                     (first (filter #(= "liquidity-provider-withholding-candidate"
                                        (:game/id %))
                                    (:cases recovery-game-fixture-catalog))))
        invalid (assoc model :players (subvec (vec (:players model)) 0 3))
        result (strategic/prepare-recovery-game-model invalid)]
    (is (= :incomplete (:parameterization/status result)))
    (is (some #(= :liquidity-provider-required (:reason %))
              (:violations result)))))

(deftest declared-payoff-parameters-change-outcome-payoffs
  (let [prepared (into {} (map (fn [[case result]] [(:game/id case) result])
                               (prepared-recovery-game-fixtures)))
        outcome {:by-player
                 (into {}
                       (for [player [:alice :bob :carol]]
                         [player {:payments [{:amount 5000 :round/index 1}]
                                  :delay-rounds 1 :fees 0
                                  :expected-unrecovered 5000
                                  :priority-externality 0 :penalty-count 0}]))}
        base (strategic/evaluate-recovery-game-outcome
              (get prepared "partial-recovery-concurrent-claims") outcome)
        high-delay (strategic/evaluate-recovery-game-outcome
                    (get prepared "high-delay-cost-sensitivity") outcome)
        high-risk (strategic/evaluate-recovery-game-outcome
                   (get prepared "recovery-risk-sensitivity") outcome)]
    (is (= :payoffs-evaluated (:status base)))
    (is (= 4990 (get-in base [:by-player :alice :utility])))
    (is (= 3500 (get-in high-delay [:by-player :alice :utility])))
    (is (= -120010 (get-in high-risk [:by-player :alice :utility])))
    (is (< (get-in high-delay [:by-player :alice :utility])
           (get-in base [:by-player :alice :utility])))
    (is (< (get-in high-risk [:by-player :alice :utility])
           (get-in base [:by-player :alice :utility])))
    (is (= :none (:equilibrium-claim high-delay)))
    (is (= :inconclusive
           (:status (strategic/evaluate-recovery-game-outcome
                     (get prepared "partial-recovery-concurrent-claims") {}))))))

(deftest candidate-action-profiles-are-typed-and-do-not-authorize-actions
  (let [prepared (into {} (map (fn [[case result]] [(:game/id case) result])
                               (prepared-recovery-game-fixtures)))
        split (get prepared "claim-splitting-candidate")
        inflated (get prepared "request-and-cap-manipulation-candidates")
        withholding (get prepared "liquidity-provider-withholding-candidate")
        invalid-split (update-in (:model split) [:candidate-action-profiles 0 :parts]
                                 assoc 0 4000)
        invalid-result (strategic/prepare-recovery-game-model invalid-split)]
    (is (= :complete (:parameterization/status split)))
    (is (= :complete (:parameterization/status inflated)))
    (is (= :complete (:parameterization/status withholding)))
    (is (= false (:candidate-actions-are-protocol-permissions? withholding)))
    (is (= :incomplete (:parameterization/status invalid-result)))
    (is (some #(= :candidate-action-profile-invalid (:reason %))
              (:violations invalid-result)))))

(deftest explicit-recovery-probabilities-require-a-stated-basis-and-sum-to-one
  (let [case (first (filter #(= "recovery-probability-assumption-sensitivity"
                                (:game/id %))
                            (:cases recovery-game-fixture-catalog)))
        model (merge (:defaults recovery-game-fixture-catalog) case)
        process (:recovery-process model)
        prepared (strategic/prepare-recovery-game-model model)
        bad (assoc-in model [:recovery-process :outcomes 0 :probability-bps] 4500)
        invalid (strategic/prepare-recovery-game-model bad)]
    (is (= :complete (:parameterization/status prepared)))
    (is (= 10000 (reduce + (map :probability-bps (:outcomes process)))))
    (is (= :explicit-scenario-assumption
           (get-in process [:probability-basis :source])))
    (is (= :incomplete (:parameterization/status invalid)))
    (is (some #(= :recovery-process-assumptions-required (:reason %))
              (:violations invalid)))))

(deftest probability-parameterization-produces-exact-expected-payoffs
  (let [case (first (filter #(= "recovery-probability-assumption-sensitivity"
                                (:game/id %))
                            (:cases recovery-game-fixture-catalog)))
        prepared (strategic/prepare-recovery-game-model
                  (merge (:defaults recovery-game-fixture-catalog) case))
        outcomes (into {}
                       (for [[id amount round delay outstanding]
                             [[:no-recovery 0 0 2 10000]
                              [:partial-recovery 5000 1 1 5000]
                              [:full-recovery 10000 1 0 0]]]
                         [id {:by-player
                              (into {}
                                    (for [player [:alice :bob :carol]]
                                      [player {:payments (if (pos? amount)
                                                           [{:amount amount
                                                             :round/index round}]
                                                           [])
                                               :delay-rounds delay
                                               :fees 0
                                               :expected-unrecovered outstanding
                                               :priority-externality 0
                                               :penalty-count 0}]))}]))
        result (strategic/evaluate-recovery-game-distribution prepared outcomes)]
    (is (= :expected-payoffs-evaluated (:status result)))
    (is (= 10000 (:probability-total-bps result)))
    (is (= 3487 (get-in result [:by-player :alice :expected-utility])))
    (is (= :none (:equilibrium-claim result)))
    (is (= :inconclusive
           (:status (strategic/evaluate-recovery-game-distribution prepared {}))))))

(deftest diagnostic-transform-is-attached-to-each-property
  (testing "every property carries a diagnostic-transform with role and semantic-purpose"
    (let [artifact (strategic/validate-strategic-properties
                    :deviations [:split :merge :permute :sybil :inflate]
                    :max-states 10)
          by-prop (into {} (map (juxt :property identity)) (:properties artifact))]
      (doseq [prop (:properties artifact)]
        (is (map? (:diagnostic-transform prop))
            (str (:property prop) " must have diagnostic-transform"))
        (is (= :diagnostic-transform
               (get-in prop [:diagnostic-transform :role]))
            (str (:property prop) " role must be :diagnostic-transform"))
        (is (= :bounded-counterexample-search
               (get-in prop [:diagnostic-transform :semantic-purpose]))
            (str (:property prop) " semantic-purpose must be :bounded-counterexample-search")))
      (is (= :split (get-in by-prop [:strategy/split-invariance :diagnostic-transform :id])))
      (is (= :merge (get-in by-prop [:allocation/exact-merge-invariance :diagnostic-transform :id])))
      (is (= :permute (get-in by-prop [:strategy/permutation-invariance :diagnostic-transform :id])))
      (is (= :sybil (get-in by-prop [:strategy/sybil-invariance :diagnostic-transform :id])))
      (is (= :inflate (get-in by-prop [:strategy/request-monotonicity :diagnostic-transform :id]))))))

(deftest diagnostic-transformations-listed-in-validation-scope
  (testing ":diagnostic-transformations in validation-scope is sorted vector of deviation keywords"
    (let [artifact (strategic/validate-strategic-properties
                    :deviations [:split :merge :permute :sybil :inflate]
                    :max-states 5)
          dt (get-in artifact [:validation-scope :diagnostic-transformations])]
      (is (vector? dt))
      (is (= (vec (sort dt)) dt))
      (is (= [:inflate :merge :permute :split :sybil] dt)))))

(deftest empty-claims-vector-produces-zero-allocations
  (testing "zero claims yields empty allocations with no violations"
    (let [report (strategic/allocation-report [] 100 {:rounding-policy :largest-remainder})]
      (is (empty? (:allocations report)))
      (is (zero? (:distributed report))))))

(deftest protocol-supplied-generator-runs-through-standard-harness
  (testing "a protocol can register a new deviation family via :extra-generators
            without editing the dispatcher"
    (let [artifact (strategic/validate-strategic-properties
                    :deviations [:split :delay-request]
                    :extra-generators {:delay-request
                                       {:id :delay-request
                                        :property :strategy/delay-invariance
                                        :evaluate (fn [claims liquidity policy]
                                                    {:property :strategy/delay-invariance
                                                     :verdict :verified
                                                     :state {:claims claims :liquidity liquidity
                                                             :policy (select-keys policy [:mode :rounding-policy])}})}}
                    :max-states 3)]
      (is (some #(= :strategy/delay-invariance (:property %))
                (:properties artifact)))
      (is (some #(= :strategy/split-invariance (:property %))
                (:properties artifact))))))

(deftest protocol-supplied-generator-attaches-diagnostic-transform
  (testing "generators supplied via :extra-generators carry a diagnostic-transform"
    (let [artifact (strategic/validate-strategic-properties
                    :deviations [:delay-request]
                    :extra-generators {:delay-request
                                       {:id :delay-request
                                        :property :strategy/delay-invariance
                                        :evaluate (fn [_ _ _]
                                                    {:property :strategy/delay-invariance
                                                     :verdict :verified
                                                     :state {}})}}
                    :max-states 1)
          prop (first (:properties artifact))]
      (is (= :delay-request (get-in prop [:diagnostic-transform :id])))
      (is (= :diagnostic-transform (get-in prop [:diagnostic-transform :role]))))))

(deftest unresolved-deviation-generator-fails-closed
  (testing "an unknown deviation keyword throws rather than silently dropping scope"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Unresolved deviation generator"
         (strategic/validate-strategic-properties
          :deviations [:not-a-real-deviation]
          :max-states 1)))))

(deftest contract-id-path-resolves-via-default-registry
  (testing "contract-id resolves deviations from the builtin deviation-contract registry"
    (let [artifact (strategic/validate-strategic-properties
                    :contract-id :partial-fill/claimant-monotonicity
                    :max-states 3)]
      (is (some #(= :strategy/request-monotonicity (:property %))
                (:properties artifact))))))

(deftest registered-generators-cover-all-builtin-deviation-families
  (testing "every builtin deviation keyword resolves to a registered generator"
    (doseq [dev [:split :merge :permute :sybil :inflate]]
      (is (contains? strategic/registered-deviation-generators dev)
          (str dev " must be registered"))
      (is (keyword? (get-in strategic/registered-deviation-generators [dev :property]))
          (str dev " generator must declare a property")))))
