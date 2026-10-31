(ns resolver-sim.pro-rata.evm-conformance-test
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.test :refer [deftest is]]
            [resolver-sim.pro-rata.evm-conformance :as sut]))

(def vector-path "vectors/all-active-60-40.edn")
(def aggregate-vector-path "vectors/aggregate-held-credit-60-40.edn")

(deftest all-active-vector-derives-without-expectations
  (let [vector (sut/read-vector vector-path)
        result (sut/verify-all-active-vector vector)]
    (is (:valid? result))
    (is (= (:expected result) (:expected vector)))
    (is (= (:normalized-effects (:derived result))
           (:normalized-effects (:expected vector))))
    (is (= (:state-after (:derived result))
           (:state-after (:expected vector))))))

(deftest aggregate-held-credit-vector-reconstructs-stage-a
  (let [vector (sut/read-vector aggregate-vector-path)
        result (sut/verify-aggregate-vector vector)]
    (is (:valid? result))
    (is (= (:normalized-effects (:derived result))
           (:normalized-effects (:expected vector))))
    (is (= (:state-after (:derived result))
           (:state-after (:expected vector))))))

(deftest aggregate-vector-declared-starting-state-controls-reconstruction
  (let [vector (sut/read-vector aggregate-vector-path)
        baseline (sut/derive-aggregate-held-credit vector)
        changed (assoc-in vector [:inputs :state-before :quantity/root] 999N)
        changed-result (sut/derive-aggregate-held-credit changed)
        missing-placeholder (update-in vector [:inputs :state-before] dissoc :quantity/root)]
    (is (= [1099N] (vals (:state-after changed-result)))
        "the declared starting balance plus the reconstructed credit determines the post-state")
    (is (not= (:state-before/root baseline) (:state-before/root changed-result)))
    (is (not= (:state-after/root baseline) (:state-after/root changed-result)))
    (is (false? (:valid? (sut/verify-aggregate-vector changed)))
        "a vector retaining stale expected roots fails verification")
    (is (try
          (sut/derive-aggregate-held-credit missing-placeholder)
          false
          (catch clojure.lang.ExceptionInfo _ true)))))

(deftest all-active-context-projection-is-observationally-neutral
  (let [vector (sut/read-vector vector-path)
        context (sut/build-all-active-context vector)]
    (is (= (sut/derive-all-active vector)
           (sut/project-derived context)))))

(deftest aggregate-context-replays-without-vector
  (let [vector (sut/read-vector aggregate-vector-path)
        context (sut/build-aggregate-context vector)
        replayed (sut/replay-from-context context)]
    (is (= (sut/project-derived context)
           (sut/project-derived replayed)))))

(deftest all-active-context-replays-without-vector
  (let [context (sut/build-all-active-context (sut/read-vector vector-path))]
    (is (= (sut/project-derived context)
           (sut/project-derived (sut/replay-from-context context))))))

(deftest reconstructed-descendants-are-not-input-bodies
  (let [context (sut/build-aggregate-context (sut/read-vector aggregate-vector-path))
        input-roots (set (keys (:retained-input-bodies context)))
        retained-roots (set (keys (:retained-bodies context)))]
    (is (not (contains? input-roots
                        (:pro-rata-evm-v2/root (:expected (sut/read-vector aggregate-vector-path))))))
    (is (contains? retained-roots
                   (:effects/root (get-in context [:artifacts :compilation]))))
    (is (set/subset? input-roots retained-roots))))

(deftest replay-ignores-retained-descendant-observations
  (let [context (sut/build-aggregate-context (sut/read-vector aggregate-vector-path))
        poisoned (assoc-in context [:retained-bodies :poison] ::not-an-input)]
    (is (= (sut/project-derived context)
           (sut/project-derived (sut/replay-from-context poisoned))))))

(deftest aggregate-context-expected-and-source-are-not-semantic-inputs
  (let [vector (sut/read-vector aggregate-vector-path)
        baseline (sut/build-aggregate-context vector)
        poisoned (assoc vector :expected ::garbage :metadata ::garbage)]
    (is (= (sut/project-derived baseline)
           (sut/derive-aggregate-held-credit poisoned)))
    (is (= (sut/project-derived baseline)
           (sut/project-derived (assoc baseline :source ::garbage :metadata ::garbage))))))

(deftest native-before-mutation-reconstructs-new-descendants
  (let [context (sut/build-aggregate-context (sut/read-vector aggregate-vector-path))
        quantity-root (-> context :reconstruction :state-before keys first)
        result (sut/run-context-mutation
                context
                {:mutation/id :native-before/increase
                 :mutation/class :reconstruction
                 :mutation/target [:state-before quantity-root]
                 :mutation/apply #(+ % 1N)})]
    (is (= :accepted (:result result)))
    (is (= :same (get-in result [:root-delta :allocation/root])))
    (is (= :same (get-in result [:root-delta :normalized-effects/root])))
    (is (= :changed (get-in result [:root-delta :state-before/root])))
    (is (= :changed (get-in result [:root-delta :state-after/root])))
    (is (= :changed (get-in result [:root-delta :canonical-transition/root])))
    (is (= :changed (get-in result [:root-delta :pro-rata-evm-v2/root])))
    (is (= 201N (get-in result [:mutated :reconstructed-roots :state-after quantity-root])))))

(deftest retained-allocation-substitution-is-rejected
  (let [context (sut/build-aggregate-context (sut/read-vector aggregate-vector-path))
        allocation-root (get-in context [:artifacts :allocation :allocation/hash])
        result (sut/run-context-mutation
                context
                {:mutation/id :allocation/substitute-amount
                 :mutation/class :adversarial-substitution
                 :mutation/target [:retained-input-bodies allocation-root :rows 0 :allocated]
                 :mutation/apply inc})]
    (is (= :rejected (:result result)))
    (is (= :semantic-verification-rejected
           (get-in result [:rejection :classification])))))

(deftest effect-substitution-is-rejected-after-permitted-rehash
  (let [context (sut/build-all-active-context (sut/read-vector vector-path))
        rejection (sut/read-vector "rejection-vectors/effect-substitution.edn")
        result (sut/run-effect-substitution context rejection)]
    (is (= :rejected (:result result)))
    (is (= :semantic-verification-rejected
           (get-in result [:rejection :classification])))
    (is (not= (get-in context [:artifacts :compilation :effect-compilation/root])
              (get-in result [:mutated :claimed-compilation :effect-compilation/root])))
    (is (not= (get-in context [:artifacts :statement :pro-rata-evm-v2/root])
              (get-in result [:mutated :claimed-statement :pro-rata-evm-v2/root])))))

(deftest forged-post-state-is-rejected-at-statement-boundary
  (let [context (sut/build-all-active-context (sut/read-vector vector-path))
        rejection (sut/read-vector "rejection-vectors/forged-post-state.edn")
        result (sut/run-descendant-substitution context rejection)]
    (is (= :rejected (:result result)))
    (is (= :semantic-verification-rejected
           (get-in result [:rejection :classification])))
    (is (not= (get-in result [:mutated :claimed-statement :pro-rata-evm-v2/root])
              (get-in context [:artifacts :statement :pro-rata-evm-v2/root])))))

(deftest aggregate-path-uses-shared-reconstruction
  (let [source (slurp (io/file "src/resolver_sim/pro_rata/evm_conformance.clj"))]
    (is (.contains source "evm/reconstruct-v2"))
    (is (not (.contains source "effects/transition")))
    (is (not (.contains source "binding/build")))
    (is (not (.contains source "evm/build-statement-v2")))))

(deftest package-membership-and-positive-vectors-verify
  (let [result (sut/verify-package)]
    (is (:valid? result))
    (is (:manifest-integrity? result))
    (is (= 3 (:rejection-count result)))
    (is (every? :valid? (:positive-results result)))
    (is (= 3 (count (:rejection-results result))))
    (is (every? #(= :rejected (:result %)) (:rejection-results result)))
    (is (every? :valid? (:rejection-results result)))))

(deftest package-rejection-verification-applies-declared-mutation
  (let [rejection (sut/read-vector "rejection-vectors/forged-post-state.edn")
        baseline (sut/read-vector vector-path)
        no-op-rejection (assoc-in rejection [:mutation :replacement]
                                  (get-in baseline [:expected :state-after/root]))
        result (sut/verify-rejection-vector no-op-rejection)]
    (is (= :accepted (:result result))
        "a declared mutation equal to the real post-state must be observed as accepted")
    (is (false? (:valid? result))
        "the package verifier must reject a negative vector whose mutation no longer rejects")))

(deftest unsupported-package-rejection-declarations-fail-closed
  (let [rejection (sut/read-vector "rejection-vectors/forged-post-state.edn")
        unsupported (assoc-in rejection [:mutation :path] [:unknown :identity])
        result (sut/verify-rejection-vector unsupported)]
    (is (false? (:valid? result)))
    (is (= :invalid-declaration (:result result)))))

(deftest rejection-vector-content-controls-executed-mutation-and-class
  (let [effect-vector (sut/read-vector "rejection-vectors/effect-substitution.edn")
        changed-effect (assoc-in effect-vector [:mutation :semantic-effect :delta] -98N)
        effect-result (sut/verify-rejection-vector changed-effect)
        wrong-class (assoc-in effect-vector [:expected :failure-class]
                              :derived-transition-mismatch)
        wrong-class-result (sut/verify-rejection-vector wrong-class)
        retained-vector (sut/read-vector "rejection-vectors/retained-body-substitution.edn")
        retained-result (sut/verify-rejection-vector retained-vector)]
    (is (= -98N (get-in effect-result [:mutated :claimed-compilation :effects 0 :delta]))
        "the semantic effect declared by the vector is passed into the verifier attempt")
    (is (:valid? effect-result))
    (is (false? (:valid? wrong-class-result))
        "the declared broad failure class must match the exercised refusal path")
    (is (= (get-in retained-vector [:mutation :replacement :asset/root])
           (get-in retained-result [:mutated :claimed-retained-body :asset/root]))
        "the retained-body replacement declared by the vector is actually supplied")
    (is (:valid? retained-result))))

(deftest expected-values-cannot-influence-derivation
  (let [vector (sut/read-vector vector-path)
        derived (sut/derive-all-active vector)
        altered (assoc-in vector [:expected :pro-rata-evm-v2/root]
                          "sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff")]
    (is (= derived (sut/derive-all-active altered)))
    (is (not (sut/compare-derived derived (:expected altered)))
        "Changing expected outputs affects comparison, never derivation")))
