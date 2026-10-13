(ns resolver-sim.pro-rata.policy-outcome-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [resolver-sim.pro-rata.allocation :as allocation]
            [resolver-sim.pro-rata.policy-outcome :as outcome]))

(defn result-for [rounding]
  (allocation/allocate {:allocation/id :policy-outcome
                        :available 3
                        :rounding-policy rounding
                        :rows [{:row/id :a :obligation/id :a :requested 3 :weight 1}
                               {:row/id :b :obligation/id :b :requested 3 :weight 1}]}))

(deftest builds-deterministic-derived-policy-outcome
  (let [result (result-for :largest-remainder)
        first-outcome (outcome/build-pro-rata-policy-outcome-v1 result)
        second-outcome (outcome/build-pro-rata-policy-outcome-v1 result)]
    (is (= first-outcome second-outcome))
    (is (= :passed (:outcome/status first-outcome)))
    (is (= (:allocation/hash result) (:allocation-result/root first-outcome)))
    (is (= (:request/hash result) (:request/root first-outcome)))
    (is (= (allocation/allocation-policy-root result) (:policy/root first-outcome)))
    (is (= first-outcome (outcome/validate-pro-rata-policy-outcome-v1! first-outcome)))))

(deftest outcome-root-is-policy-sensitive-and-rejects-tampering
  (let [largest (outcome/build-pro-rata-policy-outcome-v1 (result-for :largest-remainder))
        floor (outcome/build-pro-rata-policy-outcome-v1 (result-for :floor))]
    (is (not= (:policy/root largest) (:policy/root floor)))
    (is (not= (:outcome/root largest) (:outcome/root floor)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (outcome/validate-pro-rata-policy-outcome-v1!
                  (assoc largest :policy/root "tampered"))))))

(deftest policy-outcome-conformance-fixtures
  (let [fixture (edn/read-string
                 (slurp "resources/test-vectors/pro-rata/policy-outcome-conformance-v1.edn"))]
    (is (= "pro-rata-policy-outcome-conformance.v1" (:schema/version fixture)))
    (doseq [{:keys [id request expected]} (:cases fixture)]
      (let [result (allocation/allocate request)
            policy-outcome (outcome/build-pro-rata-policy-outcome-v1 result)]
        (is (= (:allocated-total expected) (:allocated-total result)) (str id " allocated total"))
        (is (= (:unallocated-residual expected) (:unallocated-residual result)) (str id " residual"))
        (is (= (:rounding-result expected)
               (get-in policy-outcome [:rounding/outcome :result]))
            (str id " rounding outcome"))
        (is (= policy-outcome
               (outcome/validate-pro-rata-policy-outcome-v1! policy-outcome))
            (str id " validates"))))))

(deftest primitive-lineage-and-witness-tampering-rejects-construction
  (let [result (result-for :largest-remainder)]
    (doseq [[label tampered]
            [[:request-root (assoc result :request/hash "tampered")]
             [:allocation-root (assoc result :allocation/hash "tampered")]
             [:allocation-row (assoc-in result [:rows 0 :allocated] 99)]
             [:rounding-witness (assoc-in result [:rows 0 :remainder-rank] 99)]
             [:residual (assoc result :unallocated-residual 99)]]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (outcome/build-pro-rata-policy-outcome-v1 tampered))
          (str label " rejects invalid primitive result")))))

(deftest outcome-lineage-tampering-rejects-validation
  (let [policy-outcome (outcome/build-pro-rata-policy-outcome-v1
                        (result-for :largest-remainder))]
    (doseq [field [:request/root :policy/root :allocation-result/root
                   :mechanism/id :mechanism/version :rounding/outcome
                   :schema/version]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (outcome/validate-pro-rata-policy-outcome-v1!
                    (assoc policy-outcome field :tampered)))
          (str field " changes the canonical outcome")))))

(deftest deterministic-replay-preserves-policy-outcome-root
  (let [request {:allocation/id :policy-outcome/replay
                 :available 7
                 :rows [{:row/id :a :obligation/id :a :requested 7 :weight 2}
                        {:row/id :b :obligation/id :b :requested 7 :weight 1}]}
        first-outcome (outcome/build-pro-rata-policy-outcome-v1 (allocation/allocate request))
        second-outcome (outcome/build-pro-rata-policy-outcome-v1 (allocation/allocate request))]
    (is (= (:outcome/root first-outcome) (:outcome/root second-outcome)))
    (is (= first-outcome second-outcome))))
