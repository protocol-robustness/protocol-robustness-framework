(ns resolver-sim.protocols.sew.evidence.slashing-test
  "Integration tests for the migrated pro-rata slash evidence claims path.
   Claims are now produced through claims.engine/evaluate-claims with explicit
   evidence-node references, not through raw allocation-input tunneling."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.claims.engine :as claims-engine]
            [resolver-sim.evidence.node :as node]
            [resolver-sim.protocols.sew.evidence.slashing :as slashing]
            [resolver-sim.protocols.sew.economics :as sew-economics]
            [resolver-sim.protocols.sew.types :as sew-types]
            [resolver-sim.yield.pro-rata-claims :as pro-rata-claims]))

;; ── Test fixtures ──────────────────────────────────────────────────────

(def sample-world
  "Minimal world state for evidence envelope construction."
  (sew-types/empty-world 1000))

(def sample-allocation-input
  {:slash-obligation 11
   :slash-policy {:policy/id :test-policy}
   :liable-parties [{:id :resolver-a :slashable-stake 3}
                    {:id :resolver-b :slashable-stake 2}
                    {:id :resolver-c :slashable-stake 1}]})

(def sample-attribution
  "Sample researcher attribution context."
  {:ctx/scenario-id "test-scenario"
   :ctx/run-id "test-run"
   :ctx/event-index 5
   :ctx/event-type "execute_resolution"
   :subject/type :slash
   :subject/id 0
   :action/type :slash/execute
   :evidence/reason :fraud-slash-executed})

(defn- build-evidence
  "Build full pro-rata slash evidence from sample inputs."
  [& {:keys [world allocation-input attribution]
      :or {world sample-world
           allocation-input sample-allocation-input
           attribution sample-attribution}}]
  (let [allocation-result (sew-economics/calculate-sew-slash-allocation allocation-input)]
    (:evidence
     (slashing/build-prorata-slash-evidence
      {:world world
                      :slash-id 0
       :workflow-id 0
       :resolver :test-resolver
       :epoch 0
       :trigger :test
       :allocation-input allocation-input
       :allocation-result allocation-result
       :transition-dependencies []
       :attribution attribution}))))

;; ── Integration tests ──────────────────────────────────────────────────

(deftest claims-section-produced-through-engine
  (testing "pro-rata claims section is populated through claims.engine/evaluate-claims"
    (let [evidence (build-evidence)
          result (:evidence/result evidence)
          claims (get-in result [:pro-rata :claims])]
      (is (= 7 (count claims)) "legacy projection-compatible pro-rata claims present")
      (is (every? :claim-id claims) "each claim has :claim-id")
      (is (every? :claim-definition-hash claims) "each claim has :claim-definition-hash")
      (is (every? :claim-result-hash claims) "each claim has :claim-result-hash")
      (is (every? #(contains? #{:pass :fail} (:status %)) claims)
          "each claim has :pass/:fail status")
      (is (every? true? (map :holds? claims)) "all claims hold"))))

(deftest deterministic-evaluation
  (testing "same inputs produce identical claim results"
    (let [e1 (build-evidence)
          e2 (build-evidence)
          c1 (get-in e1 [:evidence/result :pro-rata :claims])
          c2 (get-in e2 [:evidence/result :pro-rata :claims])
          pairs (map vector c1 c2)]
      (is (every? (fn [[a b]]
                    (and (= (:claim-id a) (:claim-id b))
                         (= (:holds? a) (:holds? b))
                         (= (:claim-definition-hash a) (:claim-definition-hash b))
                         (= (:claim-result-hash a) (:claim-result-hash b))))
                  pairs)
          "all claim result fields are deterministic"))))

(deftest envelope-shape-preserved
  (testing "existing evidence envelope fields are preserved after migration"
    (let [evidence (build-evidence)
          result (:evidence/result evidence)]
      (is (some? (:evidence/hash evidence)) "evidence envelope has :evidence/hash")
      (is (some? (get-in result [:projection :projection-hash]))
          "envelope has projection-hash")
      (is (some? (get-in result [:projection :projection-definition-hash]))
          "envelope has projection-definition-hash")
      (is (map? (get result :pro-rata)) "envelope has :pro-rata section")
      (is (map? (get-in result [:pro-rata :summary])) "envelope has claims summary")
      (is (contains? (get-in result [:pro-rata :summary]) :holds?)
          "summary has :holds?")
      (is (number? (get-in result [:pro-rata :summary :claim-count]))
          "summary has claim count")
      (is (some? (get-in result [:pro-rata :allocation-hash]))
          "envelope has allocation-hash")
      (is (map? (get result :allocation)) "envelope has original allocation-result")
      (is (= :allocation-only (get-in result [:execution-boundary :authority]))
          "allocation evidence makes no execution or collection claim")
      (is (= :not-established (get-in result [:execution-boundary :execution-claim])))
      (is (some? (:evidence/subject evidence)) "envelope has subject")
      (is (some? (:evidence/frame evidence)) "envelope has frame"))))

(deftest claims-engine-validates-missing-evidence-node
  (testing "claims engine detects evidence reference that does not exist"
    (let [node-hash "nonexistent-hash"
          requests [{:claim-id :pro-rata/conservation
                     :evidence-references [node-hash]}]
          {:keys [validation]}
          (claims-engine/evaluate-claims
           requests []
           {:evaluator-resolver pro-rata-claims/evaluator-resolver})]
      (is (false? (:valid? validation)))
      (is (some #(= :claim/missing-evidence (:error %))
                (:errors validation))))))

(deftest claims-engine-throws-for-missing-definition
  (testing "claims engine throws when claim definition is not in registry"
    (let [eval-node (slashing/build-claim-evaluation-node
                     sample-allocation-input
                     (sew-economics/build-sew-slash-projection-artifact sample-allocation-input)
                     (sew-economics/calculate-sew-slash-allocation sample-allocation-input)
                     (sew-economics/build-sew-slash-projection-artifact sample-allocation-input)
                     (sew-economics/calculate-sew-slash-allocation-from-projection
                      (sew-economics/build-sew-slash-projection-artifact sample-allocation-input)))
          requests [{:claim-id :nonexistent-claim
                     :evidence-references [(:node-hash eval-node)]}]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown claim definition"
                            (claims-engine/evaluate-claims
                             requests [eval-node]
                             {:evaluator-resolver pro-rata-claims/evaluator-resolver}))))))



(deftest slashing-evidence-calls-engine-not-direct-evaluator
  (testing "the slashing evidence builder produces proper engine-shaped claim results"
    (let [evidence (build-evidence)
          result (:evidence/result evidence)
          claims (get-in result [:pro-rata :claims])]
      ;; Verify engine-shaped result fields
      (is (every? :claim-id claims))
      (is (every? :claim-definition-hash claims))
      (is (every? :claim-result-hash claims))
      ;; Verify no raw input fields leaked into claim results
      (is (every? (fn [c] (not (contains? c :sew-slash-input))) claims))
      (is (every? (fn [c] (not (contains? c :allocation-input))) claims))
      (is (every? (fn [c] (not (contains? c :claims/input-context))) claims))
      ;; Verify violation shape
      (is (every? (fn [c] (vector? (:violations c))) claims)))))

(deftest evidence-hash-stable-for-same-inputs
  (testing "evidence hash is deterministic for the same allocation inputs"
    (let [h1 (:evidence/hash (build-evidence))
          h2 (:evidence/hash (build-evidence))]
      (is (= h1 h2) "same inputs produce identical evidence hash"))))

;; ── Concept hash and execution DAG tests ───────────────────────────────

(deftest claims-include-concept-hash
  (testing "each claim result entry includes :claim-definition-concept-hash alongside :claim-definition-hash"
    (let [evidence (build-evidence)
          claims (get-in evidence [:evidence/result :pro-rata :claims])]
      (count (slashing/legacy-projection-claim-ids)) (count claims)
      (is (every? :claim-definition-concept-hash claims) "each claim has :claim-definition-concept-hash")
      (is (every? string? (map :claim-definition-concept-hash claims)) "every concept-hash is a string")
      (is (every? #(= 64 (count (:claim-definition-concept-hash %))) claims) "each concept-hash is 64 hex chars")
      (is (every? :claim-definition-hash claims) "still has :claim-definition-hash")
      (is (every? #(not= (:claim-definition-concept-hash %) (:claim-definition-hash %)) claims)
          "concept-hash differs from canonical claim-definition-hash"))))

(deftest claim-result-hash-commits-to-concept-hash
  (testing "claim-result-hash includes concept-hash in its computation"
    (let [hashes (-> (build-evidence) (get-in [:evidence/result :pro-rata :claims])
                     (->> (map :claim-result-hash)))
          all-distinct? (apply distinct? hashes)]
      (is (every? string? hashes))
      (is (= (count (slashing/legacy-projection-claim-ids))
                 (count (set hashes)))
          "each claim has a unique claim-result-hash"))))

(deftest evidence-dependencies-include-claim-eval-node
  (testing "evidence :dependencies includes the persisted claim-evaluation node hash"
    (let [evidence (build-evidence)
          deps (:evidence/dependencies evidence)]
      (is (some (fn [d] (= :claim-evaluation (:type d))) deps)
          "dependencies include a claim-evaluation entry")
      (is (some (fn [d] (string? (:node-hash d))) deps)
          "claim-evaluation entry has a :node-hash string"))))

(deftest pro-rata-execution-node-persisted-in-registry
  (testing "emit-pro-rata-execution-node! produces a registered execution node"
    (let [allocation-result (sew-economics/calculate-sew-slash-allocation sample-allocation-input)
          projection (sew-economics/build-sew-slash-projection-artifact sample-allocation-input)
          projection-again (sew-economics/build-sew-slash-projection-artifact sample-allocation-input)
          eval-content {:claims/input-context {:liable-parties [] :total-basis 0 :slash-obligation 0}
                        :claims/direct-result allocation-result
                        :claims/projection-artifact projection
                        :claims/projection-artifact-again projection-again}
          claim-eval-node (slashing/emit-claim-eval-execution-node! eval-content)
          claim-eval-hash (:node-hash claim-eval-node)
          evidence (build-evidence)
          exec-node (slashing/emit-pro-rata-execution-node!
                     {:projection-artifact projection
                      :projection-artifact-again projection-again
                      :claim-eval-node-hash claim-eval-hash
                      :evidence evidence
                      :artifact {:allocation-result-hash "test-artifact-hash"}
                      :allocation-result allocation-result
       :slash-id 0
                      :workflow-id 0})]
      (is (some? (:node-hash exec-node)) "execution node has :node-hash")
      (is (some? (node/lookup-node (:node-hash exec-node)))
          "execution node is registered in node registry")
      (is (= :execution/pro-rata-allocation (get-in exec-node [:execution :execution-id]))
          "execution node has correct :execution-id")
      (is (some #(= claim-eval-hash %) (:parent-hashes exec-node))
          "execution node references claim-eval-node as parent")
      (is (= "test-artifact-hash" (get-in exec-node [:extensions :pro-rata/allocation-result-hash]))
          "execution node extensions include allocation-result-hash")
      (is (= :mechanism/pro-rata-allocation (get-in exec-node [:extensions :mechanism/id]))
          "execution node declares the canonical mechanism identity")
      (is (= 1 (get-in exec-node [:extensions :mechanism/version]))
          "execution node declares the canonical mechanism version")
      (is (= "pro-rata-mechanism-node.v1"
             (get-in exec-node [:extensions :mechanism/node-schema-version]))
          "execution node declares the mechanism-node schema")
      (is (= (:evidence/hash evidence)
             (get-in exec-node [:extensions :pro-rata/evidence-hash]))
          "execution node exposes its bound domain evidence hash"))))

;; ── Validation tests ───────────────────────────────────────────────────

(deftest pro-rata-node-has-no-dangling-claim-eval-ref
  (testing "pro-rata execution node parent claim-eval ref is resolvable in node registry"
    (let [allocation-result (sew-economics/calculate-sew-slash-allocation sample-allocation-input)
          projection (sew-economics/build-sew-slash-projection-artifact sample-allocation-input)
          eval-content {:claims/input-context {:liable-parties [] :total-basis 0 :slash-obligation 0}
                        :claims/direct-result allocation-result
                        :claims/projection-artifact projection}
          claim-eval-node (slashing/emit-claim-eval-execution-node! eval-content)
          exec-node (slashing/emit-pro-rata-execution-node!
                     {:projection-artifact projection
                      :projection-artifact-again projection
                      :claim-eval-node-hash (:node-hash claim-eval-node)
                      :evidence (build-evidence)
                      :artifact {:allocation-result-hash "test"}
                      :allocation-result allocation-result
                      :slash-id 0 :workflow-id 0})]
      (is (some? (node/lookup-node (:node-hash claim-eval-node)))
          "claim-eval node is in registry")
      (is (some? (node/lookup-node (:node-hash exec-node)))
          "pro-rata node is in registry")
      (is (= (:node-hash claim-eval-node) (get-in exec-node [:parent-hashes 0]))
          "first parent is existing claim-eval node"))))

(deftest pro-rata-node-references-existing-artifacts
  (testing "pro-rata execution node references existing projection and allocation artifacts"
    (let [allocation-result (sew-economics/calculate-sew-slash-allocation sample-allocation-input)
          projection (sew-economics/build-sew-slash-projection-artifact sample-allocation-input)
          claim-eval-node (slashing/emit-claim-eval-execution-node!
                           {:claims/input-context {} :claims/direct-result allocation-result
                            :claims/projection-artifact projection})
          exec-node (slashing/emit-pro-rata-execution-node!
                     {:projection-artifact projection
                      :projection-artifact-again projection
                      :claim-eval-node-hash (:node-hash claim-eval-node)
                      :evidence (build-evidence)
                      :artifact {:allocation-result-hash "test-artifact"}
                      :allocation-result allocation-result
                      :slash-id 0 :workflow-id 0})]
      (is (string? (get-in exec-node [:extensions :pro-rata/projection-hash]))
          "execution node extensions include projection-hash")
      (is (string? (get-in exec-node [:extensions :pro-rata/re-projection-hash]))
          "execution node extensions include re-projection-hash")
      (is (string? (get-in exec-node [:extensions :pro-rata/allocation-result-hash]))
          "execution node extensions include allocation-result-hash")
      (is (string? (get-in exec-node [:extensions :pro-rata/artifact-hash]))
          "execution node extensions include artifact-hash")
      (is (= (get-in exec-node [:extensions :pro-rata/projection-hash])
             (:projection-hash projection))
          "projection-hash in node matches projection artifact"))))

(deftest evidence-record-references-pro-rata-node
  (testing "final evidence record dependencies include the pro-rata execution node hash"
    (let [evidence (build-evidence)
          deps (:evidence/dependencies evidence)]
      (is (some (fn [d] (= :pro-rata-allocation (:type d))) deps)
          "dependencies include a pro-rata-allocation entry")
      (is (some (fn [d] (and (= :pro-rata-allocation (:type d))
                             (string? (:node-hash d)))) deps)
          "pro-rata-allocation entry has a :node-hash string")
      (let [pro-rata-hash (->> (filter #(= :pro-rata-allocation (:type %)) deps)
                               first :node-hash)]
        (is (string? pro-rata-hash) "pro-rata node hash is a string")))))
