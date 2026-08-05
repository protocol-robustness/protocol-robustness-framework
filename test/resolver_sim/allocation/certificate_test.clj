(ns resolver-sim.allocation.certificate-test
  "Tests for allocation-assurance-certificate.v1 composition."
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.allocation.certificate :as cert]
            [resolver-sim.allocation.kernel :as kernel]
            [resolver-sim.allocation.test-fixtures :as fixtures]))

(deftest certificate-schema-and-subjects
  (let [result (fixtures/kernel-result)
        c (cert/compose-certificate result)]
    (is (= "allocation-assurance-certificate.v1" (:schema-version c)))
    (is (= (:allocation-context-hash result)
           (get-in c [:subject-roots :allocation-context-hash])))
    (is (= (:result-root result) (:result-root c)))
    (is (= (:selected-outcome-id result)
           (get-in c [:selected-outcome :selected-outcome-id])))
    (is (= 14 (count (:assertions c))))))

(deftest certificate-assertion-assurance-classifications
  (let [c (cert/compose-certificate (fixtures/kernel-result))]
    (doseq [assertion (:assertions c)]
      (is (= :independent-replay (:assurance assertion)))
      (is (contains? #{:zk-proof :independent-replay :economic-assumption :not-yet-evaluated}
                     (:assurance assertion))))))

(deftest certificate-never-claims-zk-proof
  (let [c (cert/compose-certificate (fixtures/kernel-result))]
    (is (not-any? #(= :zk-proof (:assurance %)) (:assertions c)))
    (is (not= :zk-proof (get-in c [:proof :status])))
    (is (= :not-yet-evaluated (get-in c [:proof :status])))
    (is (= :mock-native (get-in c [:proof :proof-mode])))))

(deftest certificate-records-economic-assumption
  (let [c (cert/compose-certificate (fixtures/kernel-result))]
    (is (= :economic-assumption (get-in c [:assume-punishment-credible :assurance])))
    (is (= :declared-supported (get-in c [:assume-punishment-credible :status])))))

(deftest certificate-rejected-result-records-classification
  (let [input (assoc (fixtures/happy-input)
                     "outcomes"
                     [{"outcome-id" "O1"
                       "allocations" [{"claim-id" "A" "allocated" "60"}]}])
        result (kernel/run-kernel input)
        c (cert/compose-certificate result)]
    (is (= :rejected (:result/status c)))
    (is (some? (:rejection/classification c)))))
