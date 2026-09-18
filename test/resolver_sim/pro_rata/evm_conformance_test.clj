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
    (is (every? :valid? (:positive-results result)))))

(deftest expected-values-cannot-influence-derivation
  (let [vector (sut/read-vector vector-path)
        derived (sut/derive-all-active vector)
        altered (assoc-in vector [:expected :pro-rata-evm-v2/root]
                          "sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff")]
    (is (= derived (sut/derive-all-active altered)))
    (is (not (sut/compare-derived derived (:expected altered)))
        "Changing expected outputs affects comparison, never derivation")))
