(ns resolver-sim.benchmark.cancellation-transition-observation-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [resolver-sim.benchmark.cancellation-transition-observation :as o]
            [resolver-sim.hash.canonical :as hc]))

(def vector-fixture
  (edn/read-string (slurp "data/fixtures/golden/cancellation-transition-vector.v1.edn")))

(deftest frozen-vector-recomputes
  (let [subject (o/transition-subject (:subject-input vector-fixture))
        result (o/transition-result subject
                                    (select-keys (:result vector-fixture)
                                                 [:state-after/root :receipt/root
                                                  :effects/root :invariants/root]))]
    (is (= (:semantic-projection (:subject vector-fixture))
           (dissoc subject :subject/root)))
    (is (= (:semantic-projection (:result vector-fixture))
           (dissoc result :result/root)))
    (is (= (:canonical-value-bytes-hex (:subject vector-fixture))
           (hc/canonical-bytes-hex
            (:semantic-projection (:subject vector-fixture)))))
    (is (= (:canonical-value-bytes-hex (:result vector-fixture))
           (hc/canonical-bytes-hex
            (:semantic-projection (:result vector-fixture)))))
    (is (o/valid-subject? subject))
    (is (o/valid-result? result))))

(deftest ordered-subject-inputs-are-semantic
  (let [input (:subject-input vector-fixture)
        root (:subject/root (o/transition-subject input))]
    (doseq [field [:prior-state/root :authorised-command/root
                   :transition-definition/root]]
      (is (not= root
                (:subject/root
                 (o/transition-subject
                  (assoc input field
                         "sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"))))))))
