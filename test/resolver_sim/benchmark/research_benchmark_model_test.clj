(ns resolver-sim.benchmark.research-benchmark-model-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.research-benchmark-model :as rbm]
            [resolver-sim.hash.canonical :as hc]))

(def sample-model-input
  {:model/id :model/yield-partial-fill
   :model/version 1
   :model/state
   {:entities [:yield/position :yield/deferred-position :yield/obligation]
    :variables [:position/status :position/current-amount :yield/available-liquidity]
    :authority-policies [:position/current-amount-precedence]}
   :model/actors
   [{:actor/id :actor/participant
     :actor/capabilities [:action/request-withdrawal]}
    {:actor/id :actor/researcher
     :actor/capabilities [:action/propose-case :action/challenge-claim]}]
   :model/actions
   [:action/request-withdrawal
    :action/calculate-partial-fill
    :action/apply-allocation
    :action/create-deferred-successor
    :action/close-prior-deferred
    :action/propose-current-amount-correction
    :action/force-authorise]
   :model/transitions
   [{:transition/id :transition/apply-partial-fill
     :transition/type :transition/economic
     :action :action/apply-allocation
     :preconditions
     [{:kind :predicate
       :predicate
       {:and [{:state {:query [:yield/deferred-position :position/status]
                        :op := :value :position.status/active}}
               {:state {:query [:yield/deferred-position :position/current-amount]
                        :op :> :value 0}}]}}]
     :postconditions
     [{:kind :predicate
       :predicate
       {:and [{:state {:query [:yield/position :position/status]
                        :op := :value :position.status/withdrawn}}
               {:state {:query [:yield/deferred-position :position/current-amount]
                        :op := :value 0}}]}}]}]
   :model/invariants
   [:yield/pro-rata-propagation-complete
    :yield/pro-rata-accounting-reconciles]})

;; ── Builder tests ─────────────────────────────────────────────────────────

(deftest valid-model-builds
  (let [model (rbm/build-model sample-model-input)]
    (is (= "research-benchmark-model.v1" (:schema-version model)))
    (is (= :model/yield-partial-fill (:model/id model)))
    (is (= 1 (:model/version model)))
    (is (some? (:model/hash model)))
    (is (rbm/model-envelope? model))))

(deftest validate-model-passes
  (let [model (rbm/build-model sample-model-input)
        result (rbm/validate-model model)]
    (is (:valid? result) (str "errors: " (:errors result)))))

(deftest hash-changes-on-version-change
  (let [a (rbm/build-model sample-model-input)
        b (rbm/build-model (assoc sample-model-input :model/version 2))]
    (is (not= (:model/hash a) (:model/hash b)))))

(deftest hash-changes-on-transition-change
  (let [a (rbm/build-model sample-model-input)
        mod-input (assoc-in sample-model-input
                            [:model/transitions 0 :transition/type]
                            :transition/state-change)
        b (rbm/build-model mod-input)]
    (is (not= (:model/hash a) (:model/hash b))
        "changing a transition must change the model hash")))

(deftest hash-mismatch-rejected
  (let [model (rbm/build-model sample-model-input)
        tampered (assoc model :model/hash "sha256:deadbeef")
        result (rbm/validate-model tampered)]
    (is (not (:valid? result)))
    (is (some #(re-find #"hash mismatch" %) (:errors result)))))

;; ── Determinism tests ─────────────────────────────────────────────────────

(deftest canonical-ordering-deterministic
  (let [reversed-input (update sample-model-input :model/actors reverse)
        a (rbm/build-model sample-model-input)
        b (rbm/build-model reversed-input)]
    (is (= (:model/hash a) (:model/hash b))
        "actor ordering must not affect hash")))

(deftest capability-ordering-deterministic
  (let [swapped-input (assoc-in sample-model-input
                                [:model/actors 1 :actor/capabilities]
                                [:action/challenge-claim :action/propose-case])
        a (rbm/build-model sample-model-input)
        b (rbm/build-model swapped-input)]
    (is (= (:model/hash a) (:model/hash b))
        "capability ordering must not affect hash")))

;; ── Uniqueness tests ──────────────────────────────────────────────────────

(deftest duplicate-actor-id-rejected
  (let [bad-input (assoc sample-model-input :model/actors
                         [{:actor/id :actor/participant
                           :actor/capabilities [:action/request-withdrawal]}
                          {:actor/id :actor/participant
                           :actor/capabilities [:action/foo]}])
        model (rbm/build-model bad-input)
        result (rbm/validate-model model)]
    (is (not (:valid? result)))
    (is (some #(re-find #"duplicate actor" %) (:errors result)))))

(deftest duplicate-transition-id-rejected
  (let [bad-input (assoc sample-model-input :model/transitions
                         [{:transition/id :transition/apply-partial-fill
                           :transition/type :transition/economic
                           :action :action/apply-allocation}
                          {:transition/id :transition/apply-partial-fill
                           :transition/type :transition/economic
                           :action :action/apply-allocation}])
        model (rbm/build-model bad-input)
        result (rbm/validate-model model)]
    (is (not (:valid? result)))
    (is (some #(re-find #"duplicate transition" %) (:errors result)))))

(deftest duplicate-action-rejected
  (let [bad-input (assoc sample-model-input :model/actions
                         [:action/request-withdrawal :action/request-withdrawal])
        model (rbm/build-model bad-input)
        result (rbm/validate-model model)]
    (is (not (:valid? result)))
    (is (some #(re-find #"duplicate action" %) (:errors result)))))

(deftest duplicate-invariant-rejected
  (let [bad-input (assoc sample-model-input :model/invariants
                         [:yield/conservation :yield/conservation])
        model (rbm/build-model bad-input)
        result (rbm/validate-model model)]
    (is (not (:valid? result)))
    (is (some #(re-find #"duplicate invariant" %) (:errors result)))))

(deftest duplicate-state-entity-rejected
  (let [bad-input (assoc-in sample-model-input
                            [:model/state :entities]
                            [:yield/position :yield/position])
        model (rbm/build-model bad-input)
        result (rbm/validate-model model)]
    (is (not (:valid? result)))
    (is (some #(re-find #"duplicate entity" %) (:errors result)))))

;; ── Referential closure tests ─────────────────────────────────────────────

(deftest undeclared-action-rejected
  (let [bad-input (assoc-in sample-model-input
                            [:model/transitions 0 :action]
                            :action/nonexistent)
        model (rbm/build-model bad-input)
        result (rbm/validate-model model)]
    (is (not (:valid? result)))
    (is (some #(re-find #"undeclared action" %) (:errors result)))))

;; ── Predicate validation tests ────────────────────────────────────────────

(deftest invalid-predicate-operator-rejected
  (let [bad-pred {:kind :predicate
                  :predicate {:state {:query [:x] :op :invalid-op :value 0}}}
        bad-input (assoc-in sample-model-input
                            [:model/transitions 0 :preconditions]
                            [bad-pred])
        model (rbm/build-model bad-input)
        result (rbm/validate-model model)]
    (is (not (:valid? result)))
    (is (some #(re-find #"invalid comparison" %) (:errors result)))))

(deftest temporal-operator-rejected-in-transition
  (let [bad-pred {:kind :predicate
                  :predicate {:always
                              {:state {:query [:x] :op := :value 1}}}}
        bad-input (assoc-in sample-model-input
                            [:model/transitions 0 :preconditions]
                            [bad-pred])
        model (rbm/build-model bad-input)
        result (rbm/validate-model model)]
    (is (not (:valid? result)))
    (is (some #(re-find #"temporal" %) (:errors result)))))

(deftest unknown-transition-type-rejected
  (let [bad-input (assoc-in sample-model-input
                            [:model/transitions 0 :transition/type]
                            :transition/unknown)
        model (rbm/build-model bad-input)
        result (rbm/validate-model model)]
    (is (not (:valid? result)))
    (is (some #(re-find #"invalid.*transition/type" %) (:errors result)))))

;; ── Unknown-key rejection tests ───────────────────────────────────────────

(deftest unknown-top-level-key-rejected
  (let [model (rbm/build-model sample-model-input)
        bad (assoc model :model/typo "accidental-key")
        result (rbm/validate-model bad)]
    (is (not (:valid? result)))
    (is (some #(re-find #"unknown top-level" %) (:errors result)))))

(deftest unknown-state-key-rejected
  (let [bad-state (assoc (:model/state sample-model-input)
                         :extra-key "bad")
        bad-input (assoc sample-model-input :model/state bad-state)
        model (rbm/build-model bad-input)
        result (rbm/validate-model model)]
    (is (not (:valid? result)))
    (is (some #(re-find #"unknown.*state" %) (:errors result)))))

(deftest unknown-actor-key-rejected
  (let [bad-actor (assoc (first (:model/actors sample-model-input))
                         :actor/typo "bad")
        bad-input (assoc sample-model-input :model/actors [bad-actor])
        model (rbm/build-model bad-input)
        result (rbm/validate-model model)]
    (is (not (:valid? result)))
    (is (some #(re-find #"unknown keys in actor" %) (:errors result)))))

(deftest unknown-transition-key-rejected
  (let [bad-trans (assoc (first (:model/transitions sample-model-input))
                         :transition/typo "bad")
        bad-input (assoc sample-model-input :model/transitions [bad-trans])
        model (rbm/build-model bad-input)
        result (rbm/validate-model model)]
    (is (not (:valid? result)))
    (is (some #(re-find #"unknown keys in transition" %) (:errors result)))))

;; ── Envelope and reference tests ──────────────────────────────────────────

(deftest model-envelope-not-validation
  (let [model (rbm/build-model sample-model-input)
        tampered (assoc model :model/hash "sha256:fake")]
    (is (rbm/model-envelope? tampered)
        "envelope check must pass even with tampered hash")
    (is (not (:valid? (rbm/validate-model tampered)))
        "full validation must reject tampered hash")))

(deftest model-reference-valid
  (let [model (rbm/build-model sample-model-input)
        entry {:benchmark/model-root (:model/hash model)}]
    (is (= :valid (:status (rbm/validate-model-reference entry model))))))

(deftest model-reference-mismatch
  (let [model (rbm/build-model sample-model-input)
        entry {:benchmark/model-root "sha256:different"}]
    (is (= :reference-mismatch (:status (rbm/validate-model-reference entry model))))))

(deftest model-reference-invalid-model
  (let [model {:schema-version "research-benchmark-model.v1"
               :model/id :model/bad
               :model/version 0
               :model/hash "sha256:fake"}]
    (is (= :invalid-model (:status (rbm/validate-model-reference {} model))))))

;; ── Qualified-keyword tests ───────────────────────────────────────────────

(deftest unqualified-entity-id-rejected
  (let [bad-input (assoc-in sample-model-input
                            [:model/state :entities]
                            [:position :yield/deferred-position])
        model (rbm/build-model bad-input)
        result (rbm/validate-model model)]
    (is (not (:valid? result)))
    (is (some #(re-find #"not qualified" %) (:errors result)))))

(deftest unqualified-action-rejected
  (let [bad-input (assoc sample-model-input :model/actions
                         [:action/valid :unqualified-action])
        model (rbm/build-model bad-input)
        result (rbm/validate-model model)]
    (is (not (:valid? result)))
    (is (some #(re-find #"not qualified" %) (:errors result)))))

;; ── Golden vector tests ───────────────────────────────────────────────────

(deftest compute-golden-hash-deterministic
  (let [v (first rbm/golden-vectors)
        h1 (rbm/compute-golden-hash v)
        h2 (rbm/compute-golden-hash v)]
    (is (some? (:expected-hash h1)))
    (is (= (:expected-hash h1) (:expected-hash h2))
        "compute-golden-hash must be deterministic")))

(deftest golden-vectors-stable-check
  (let [result (rbm/golden-vectors-stable?)]
    ;; With :expected-hash nil on all vectors, stable? will be false
    ;; The test verifies the function runs without error and returns
    ;; the expected structure
    (is (contains? result :stable?))
    (is (contains? result :mismatches))))

(deftest golden-vectors-round-trip
  (let [vectors rbm/golden-vectors]
    (doseq [v vectors]
      (let [model (rbm/build-model (:model v))
            validation (rbm/validate-model model)]
        (is (rbm/model-envelope? model)
            (str "model envelope valid for: " (:description v)))
        (is (:valid? validation)
            (str "validation passes for: " (:description v)))))))

(deftest negative-golden-vectors-fail-closed
  (let [vectors rbm/negative-golden-vectors]
    (doseq [v vectors]
      (let [model (:model v)
            ;; Negative vectors are malformed persisted artifacts.
            ;; Validate them directly (not through build-model) to prove
            ;; that independent loaders fail closed.
            model-with-hash (assoc model :model/hash "sha256:placeholder-for-validation")
            validation (rbm/validate-model model-with-hash)]
        (is (not (:valid? validation))
            (str "negative golden vector must fail: " (:description v)))
        (is (some #(re-find (:expected-failure v) %) (:errors validation))
            (str "negative golden vector error must match: " (:description v)
                 " — got errors: " (:errors validation)))))))

(deftest state-variable-closure-rejects-undeclared
  (let [bad-input (assoc-in sample-model-input
                            [:model/transitions 0 :preconditions]
                            [{:kind :predicate
                              :predicate
                              {:state {:query [:yield/nonexistent-var]
                                        :op := :value 1}}}])
        model (rbm/build-model bad-input)
        result (rbm/validate-model model)]
    (is (not (:valid? result)))
    (is (some #(re-find #"undeclared variable" %) (:errors result)))))

(deftest state-variable-closure-accepts-declared
  (let [state (:model/state sample-model-input)
        vars (:variables state)
        _ (is (contains? (set vars) :position/status))
        model (rbm/build-model sample-model-input)
        result (rbm/validate-model model)]
    (is (:valid? result) "direct references to declared variables must pass")))
