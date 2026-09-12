(ns resolver-sim.pro-rata.evm-conformance-test
  (:require [clojure.test :refer [deftest is testing]]
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
