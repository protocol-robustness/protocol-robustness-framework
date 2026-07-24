(ns resolver-sim.yield.pro-rata-claims-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.claims.engine :as claims-engine]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.protocols.sew.economics :as sew-economics]
            [resolver-sim.yield.pro-rata-claims :as claims]))

(def phase-6-claims
  #{:projection-deterministic
    :projection-canonical-safe
    :pro-rata/allocation-complete
    :pro-rata/non-negative
    :pro-rata/conservation
    :pro-rata/quota-bounded
    :pro-rata/permutation-invariant})

(def extended-claims
  (conj phase-6-claims
        :pro-rata/cap-respecting
        :pro-rata/canonical-remainder-assignment
        :pro-rata/projection-diff))

(defn- make-content
  "Build evidence content with matching direct and projection results."
  [direct-result]
  {:claims/direct-result direct-result
   :claims/projection-result (dissoc direct-result :claims/input-context)
   :claims/projection-artifact {:projection-hash "h1"}
   :claims/projection-artifact-again {:projection-hash "h1"}})

(def representative-fixtures
  [{:slash-obligation 11
    :slash-policy {:policy/id :test-policy}
    :liable-parties [{:id :resolver-a
                      :slashable-stake 3}
                     {:id :resolver-b
                      :slashable-stake 2}
                     {:id :resolver-c
                      :slashable-stake 1}]}
   {:slash-obligation 10
    :liable-parties [{:id :resolver-a
                      :slashable-stake 5}
                     {:id :resolver-b
                      :slashable-stake 3}
                     {:id :resolver-c
                      :slashable-stake 2}]}
   {:slash-obligation 7
    :basis :custom-weight
    :cap-field :custom-cap
    :liable-parties [{:id :resolver-a
                      :custom-weight 4}
                     {:id :resolver-b
                      :custom-weight 2}
                     {:id :resolver-c
                      :custom-weight 1}]}])

(defn- claim-allocation-view
  [result]
  (update result :allocations #(mapv (fn [row] (dissoc row :share)) (or % []))))

(defn- build-claim-evaluation-node
  "Build a claim-evaluation evidence node from a SEW slash allocation input,
   matching the shape produced by evidence/slashing.clj."
  [allocation-input]
  (let [direct-result (sew-economics/calculate-sew-slash-allocation allocation-input)
        projection-artifact (sew-economics/build-sew-slash-projection-artifact allocation-input)
        projection-artifact-again (sew-economics/build-sew-slash-projection-artifact allocation-input)
        projection-result (sew-economics/calculate-sew-slash-allocation-from-projection projection-artifact)
        permuted-input (update allocation-input :liable-parties #(vec (reverse (or % []))))
        direct-result-permuted (sew-economics/calculate-sew-slash-allocation permuted-input)
        projection-artifact-permuted (sew-economics/build-sew-slash-projection-artifact permuted-input)
        projection-result-permuted (sew-economics/calculate-sew-slash-allocation-from-projection projection-artifact-permuted)
        content {:claims/input-context
                 {:liable-parties (:liable-parties allocation-input [])
                  :total-basis (long (:total-basis direct-result 0))
                  :slash-obligation (or (:slash-obligation allocation-input)
                                        (:slash-amount allocation-input)
                                        0)
                  :basis-field (:basis allocation-input :slashable-stake)
                  :cap-field (:cap-field allocation-input :available-slashable)
                  :unmet-policy (:unmet-policy allocation-input :record-only)}
                 :claims/direct-result (claim-allocation-view direct-result)
                 :claims/direct-result-permuted (claim-allocation-view direct-result-permuted)
                 :claims/projection-artifact projection-artifact
                 :claims/projection-artifact-again projection-artifact-again
                 :claims/projection-result (claim-allocation-view projection-result)
                 :claims/projection-result-permuted (claim-allocation-view projection-result-permuted)}
        node-hash (hc/hash-with-intent {:hash/intent :evidence-record} content)]
    {:node-hash node-hash
     :result content
     :claims/evaluation-context true}))

(defn- evaluate-claims-from-input
  "Evaluate Phase 6 pro-rata claims from a raw allocation input, building
   evidence nodes and passing through claims.engine/evaluate-claims."
  [allocation-input]
  (let [node (build-claim-evaluation-node allocation-input)
        requests (mapv (fn [claim-id]
                         {:claim-id claim-id
                          :evidence-references [(:node-hash node)]})
                       phase-6-claims)
        {:keys [claim-results]}
        (claims-engine/evaluate-claims
         requests [node]
         {:evaluator-resolver claims/evaluator-resolver})]
    (into {} (map (juxt :claim-id identity) claim-results))))

(deftest registered-claims-cover-phase-6-contract
  (testing "the evaluator registry exposes the phase 6 claim set plus extensions"
    (is (= extended-claims (set (claims/registered-claim-ids))))))

(deftest all-phase-6-claims-pass-on-representative-fixtures
  (testing "claim evaluators pass on representative fixtures via claims engine"
    (doseq [input representative-fixtures]
      (let [result (evaluate-claims-from-input input)]
        (is (= phase-6-claims (set (keys result))))
        (is (every? true? (map :holds? (vals result))))
        (is (empty? (mapcat :violations (vals result))))))))

(deftest missing-evidence-node-produces-failure
  (testing "evaluator returns :missing-evidence-content when no evidence node provided"
    (let [result (claims/evaluate-claim :pro-rata/conservation {:evidence-nodes []})]
      (is (false? (:holds? result)))
      (is (= [{:type :missing-evidence-content}] (:violations result))))))

(deftest unknown-claim-id-throws
  (testing "evaluating an unknown claim id throws an error"
    (is (thrown? clojure.lang.ExceptionInfo
                 (claims/evaluate-claim :unknown-claim {:evidence-nodes []})))))

(deftest claims-compare-integer-equivalent-rows-by-canonical-identity
  (let [direct {:allocations [{:id :alice :owed 3 :paid 2 :unmet 1}
                              {:id :bob :owed 3 :paid 1 :unmet 2}]
                :total-requested 6 :total-allocated 3 :total-unmet 3 :remainder 0}
        projection {:allocations [{:id :bob :owed 3N :paid 1N :unmet 2N}
                                  {:id :alice :owed 3N :paid 2N :unmet 1N}]
                    :total-requested 6N :total-allocated 3N :total-unmet 3N :remainder 0N}
        content (assoc (make-content direct) :claims/projection-result projection)
        result (claims/evaluate-claim :pro-rata/conservation
                                      {:evidence-nodes [{:result content}]})]
    (is (true? (:holds? result)))
    (is (empty? (:violations result)))))

(deftest duplicate-allocation-identities-fail-closed
  (let [duplicate {:allocations [{:id :alice :owed 3 :paid 2 :unmet 1}
                                 {:id :alice :owed 3 :paid 1 :unmet 2}]
                   :total-requested 6 :total-allocated 3 :total-unmet 3 :remainder 0}
        result (claims/evaluate-claim :pro-rata/conservation
                                      {:evidence-nodes [{:result (make-content duplicate)}]})]
    (is (false? (:holds? result)))
    (is (some #(= :duplicate-allocation-identity (:type %))
              (:violations result)))))

(deftest claims-engine-integrates-with-evaluator-resolver
  (testing "claims.engine/evaluate-claims resolves pro-rata evaluators correctly"
    (let [input (first representative-fixtures)
          node (build-claim-evaluation-node input)
          requests [{:claim-id :pro-rata/conservation
                     :evidence-references [(:node-hash node)]}]
          {:keys [claim-results validation]}
          (claims-engine/evaluate-claims
           requests [node]
           {:evaluator-resolver claims/evaluator-resolver})]
      (is (= 1 (count claim-results)))
      (is (= :pro-rata/conservation (:claim-id (first claim-results))))
      (is (true? (:holds? (first claim-results))))
      (is (:valid? validation)))))

(deftest pro-rata-fairness-passes-on-proportional-allocation
  (testing "pro-rata-fairness passes when all claimants have same fill ratio"
    (let [allocations [{:id :a :paid 20 :owed 40}
                       {:id :b :paid 30 :owed 60}]
          result (claims/evaluate-claim
                  :pro-rata-fairness
                  {:evidence-nodes [{:result {:claims/direct-result {:allocations allocations}
                                              :claims/projection-result {:allocations allocations}
                                              :claims/projection-artifact {:projection-hash "h1"}
                                              :claims/projection-artifact-again {:projection-hash "h1"}}}]})]
      (is (true? (:holds? result)))
      (is (empty? (:violations result))))))

(deftest pro-rata-fairness-fails-on-non-proportional-allocation
  (testing "pro-rata-fairness fails when fill ratios differ"
    (let [allocations [{:id :a :paid 10 :owed 40}
                       {:id :b :paid 40 :owed 60}]
          result (claims/evaluate-claim
                  :pro-rata-fairness
                  {:evidence-nodes [{:result {:claims/direct-result {:allocations allocations}
                                              :claims/projection-result {:allocations allocations}
                                              :claims/projection-artifact {:projection-hash "h1"}
                                              :claims/projection-artifact-again {:projection-hash "h1"}}}]})]
      (is (false? (:holds? result)))
      (is (seq (:violations result))))))

(deftest pro-rata-fairness-passes-with-single-claimant
  (testing "pro-rata-fairness passes trivially with fewer than 2 claimants"
    (let [allocations [{:id :a :paid 20 :owed 40}]
          result (claims/evaluate-claim
                  :pro-rata-fairness
                  {:evidence-nodes [{:result {:claims/direct-result {:allocations allocations}
                                              :claims/projection-result {:allocations allocations}
                                              :claims/projection-artifact {:projection-hash "h1"}
                                              :claims/projection-artifact-again {:projection-hash "h1"}}}]})]
      (is (true? (:holds? result)))
      (is (empty? (:violations result))))))

(deftest pro-rata-fairness-missing-evidence
  (testing "pro-rata-fairness fails when evidence content is missing"
    (let [result (claims/evaluate-claim :pro-rata-fairness {:evidence-nodes []})]
      (is (false? (:holds? result)))
      (is (= [{:type :missing-evidence-content}] (:violations result))))))

;; ── Partial-fill bridge evaluator tests ─────────────────────────────────

(deftest partial-fill-fairness-passes-on-proportional-decision
  (testing "partial-fill-fairness passes when fill ratios are equal"
    (let [decision {:requested {:a 40 :b 60}
                    :filled {:a 20 :b 30}
                    :policy {:mode :pro-rata}}
          result (claims/evaluate-claim
                  :partial-fill-fairness
                  {:evidence-nodes [{:result decision}]})]
      (is (true? (:holds? result)))
      (is (empty? (:violations result))))))

(deftest partial-fill-fairness-fails-on-non-proportional-decision
  (testing "partial-fill-fairness fails when fill ratios differ"
    (let [decision {:requested {:a 40 :b 60}
                    :filled {:a 10 :b 40}
                    :policy {:mode :pro-rata}}
          result (claims/evaluate-claim
                  :partial-fill-fairness
                  {:evidence-nodes [{:result decision}]})]
      (is (false? (:holds? result)))
      (is (seq (:violations result))))))

(deftest partial-fill-fairness-passes-with-single-claim
  (testing "partial-fill-fairness passes with a single claimant"
    (let [decision {:requested {:a 40} :filled {:a 20} :policy {:mode :pro-rata}}
          result (claims/evaluate-claim
                  :partial-fill-fairness
                  {:evidence-nodes [{:result decision}]})]
      (is (true? (:holds? result)))
      (is (empty? (:violations result))))))

(deftest partial-fill-fairness-missing-evidence
  (testing "partial-fill-fairness fails when evidence is missing"
    (let [result (claims/evaluate-claim :partial-fill-fairness {:evidence-nodes []})]
      (is (false? (:holds? result)))
      (is (= [{:type :missing-evidence-content}] (:violations result))))))

;; ── Edge case tests for claims evaluators (B3) ─────────────────────────

(deftest rounding-bounded-passes-on-exact-division
  (testing "rounding-bounded passes when allocation exactly matches ideal"
    (let [allocations [{:id :a :basis-amount 50 :paid 50}
                       {:id :b :basis-amount 50 :paid 50}]
          result (claims/evaluate-claim
                  :rounding-bounded
                  {:evidence-nodes [{:result {:claims/input-context {:total-basis 100 :slash-obligation 100}
                                              :claims/direct-result {:allocations allocations}
                                              :claims/projection-result {:allocations allocations}
                                              :claims/projection-artifact {:projection-hash "h1"}
                                              :claims/projection-artifact-again {:projection-hash "h1"}}}]})]
      (is (true? (:holds? result)))
      (is (empty? (:violations result))))))

(deftest rounding-bounded-fails-on-large-deviation
  (testing "rounding-bounded fails when deviation exceeds 1 unit"
    (let [allocations [{:id :a :basis-amount 50 :paid 100}]
          result (claims/evaluate-claim
                  :rounding-bounded
                  {:evidence-nodes [{:result {:claims/input-context {:total-basis 100 :slash-obligation 100}
                                              :claims/direct-result {:allocations allocations}
                                              :claims/projection-result {:allocations allocations}
                                              :claims/projection-artifact {:projection-hash "h1"}
                                              :claims/projection-artifact-again {:projection-hash "h1"}}}]})]
      (is (false? (:holds? result)))
      (is (seq (:violations result))))))

(deftest rounding-bounded-passes-on-zero-total-basis
  (testing "rounding-bounded passes when total-basis is zero"
    (let [result (claims/evaluate-claim
                  :rounding-bounded
                  {:evidence-nodes [{:result {:claims/input-context {:total-basis 0 :slash-obligation 0}
                                              :claims/direct-result {:allocations []}
                                              :claims/projection-result {:allocations []}
                                              :claims/projection-artifact {:projection-hash "h1"}
                                              :claims/projection-artifact-again {:projection-hash "h1"}}}]})]
      (is (true? (:holds? result)))
      (is (empty? (:violations result))))))
(deftest conservation-passes-on-exact-allocation
  (testing "conservation passes when requested = allocated + unmet + remainder"
    (let [direct {:allocations [{:id :a :owed 50 :paid 25 :unmet 25}]
                  :total-requested 50
                  :total-allocated 25
                  :total-unmet 25
                  :remainder 0}
          result (claims/evaluate-claim
                  :conservation
                  {:evidence-nodes [{:result (make-content direct)}]})]
      (is (true? (:holds? result)))
      (is (empty? (:violations result))))))

(deftest conservation-fails-on-mismatch
  (testing "conservation fails when totals do not sum to requested"
    (let [direct {:allocations [] :total-requested 100 :total-allocated 60 :total-unmet 20 :remainder 0}
          result (claims/evaluate-claim
                  :conservation
                  {:evidence-nodes [{:result (make-content direct)}]})]
      (is (false? (:holds? result)))
      (is (seq (:violations result))))))

(deftest conservation-reports-each-invalid-row
  (testing "one corrupted allocation row fails conservation even when another is valid"
    (let [direct {:allocations [{:id :resolver-a :owed 10 :paid 8 :unmet 2}
                                {:id :resolver-b :owed 10 :paid 7 :unmet 1}]
                  :total-requested 20 :total-allocated 15 :total-unmet 4 :remainder 1}
          result (claims/evaluate-claim
                  :conservation
                  {:evidence-nodes [{:result (make-content direct)}]})]
      (is (false? (:holds? result)))
      (is (some #(= {:type :per-allocation-owed-mismatch
                     :idx 1
                     :id :resolver-b
                     :expected 10
                     :observed 8}
                    %)
                (:violations result))))))

(deftest ordering-independent-uses-a-real-input-permutation
  (testing "canonical tie-breaking preserves ownership under a reversed input order"
    (let [node (build-claim-evaluation-node
                {:slash-obligation 1
                 :liable-parties [{:id :resolver-a :slashable-stake 1}
                                  {:id :resolver-b :slashable-stake 1}]})
          valid (claims/evaluate-claim :ordering-independent
                                       {:evidence-nodes [node]})
          corrupted (update-in node [:result :claims/direct-result-permuted :allocations]
                               #(assoc % 0 (assoc (first %) :paid 0 :unmet 1)))
          invalid (claims/evaluate-claim :ordering-independent
                                         {:evidence-nodes [corrupted]})]
      (is (true? (:holds? valid)))
      (is (false? (:holds? invalid)))
      (is (some #(and (= :ordering-dependent (:type %))
                      (= :direct (:path %)))
                (:violations invalid))))))

(deftest pro-rata-fairness-passes-on-three-claimants
  (testing "pro-rata-fairness passes with three proportional claimants"
    (let [allocations [{:id :a :paid 10 :owed 20}
                       {:id :b :paid 15 :owed 30}
                       {:id :c :paid 25 :owed 50}]
          result (claims/evaluate-claim
                  :pro-rata-fairness
                  {:evidence-nodes [{:result {:claims/direct-result {:allocations allocations}
                                              :claims/projection-result {:allocations allocations}
                                              :claims/projection-artifact {:projection-hash "h1"}
                                              :claims/projection-artifact-again {:projection-hash "h1"}}}]})]
      (is (true? (:holds? result)))
      (is (empty? (:violations result))))))

(deftest pro-rata-fairness-detects-single-unfair-claimant
  (testing "pro-rata-fairness detects one unfair claimant among many"
    (let [allocations [{:id :a :paid 10 :owed 20}
                       {:id :b :paid 20 :owed 30}
                       {:id :c :paid 25 :owed 50}]
          result (claims/evaluate-claim
                  :pro-rata-fairness
                  {:evidence-nodes [{:result {:claims/direct-result {:allocations allocations}
                                              :claims/projection-result {:allocations allocations}
                                              :claims/projection-artifact {:projection-hash "h1"}
                                              :claims/projection-artifact-again {:projection-hash "h1"}}}]})]
      (is (false? (:holds? result)))
      (is (seq (:violations result))))))
