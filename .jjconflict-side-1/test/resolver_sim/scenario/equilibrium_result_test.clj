(ns resolver-sim.scenario.equilibrium-result-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.scenario.equilibrium :as eq]
            [resolver-sim.scenario.equilibrium-result :as eq-result]))

(deftest structured-reason-fields
  (testing "untested incentive compatibility"
    (let [r (-> (eq/evaluate-mechanism-properties [:incentive-compatibility]
                                                  {:metrics {:attack-attempts 0}})
                :incentive-compatibility)]
      (is (= :inconclusive (:status r)))
      (is (= :untested-no-adversary (:reason r)))
      (is (seq (:required r)))))

  (testing "missing deviation bundle"
    (let [r (-> (eq/evaluate-equilibrium-concepts [:nash-equilibrium]
                                                  {:metrics {:attack-attempts 1 :attack-successes 0}}
                                                  {}
                                                  {:claim-tier :deviation-tested})
                :nash-equilibrium)]
      (is (= :inconclusive (:status r)))
      (is (= :missing-deviation-bundles (:reason r)))))

  (testing "summarize-domain-results"
    (let [results (eq/evaluate-mechanism-properties [:incentive-compatibility]
                                                    {:metrics {:attack-attempts 1 :attack-successes 0}})
          summary (eq-result/summarize-domain-results results)]
      (is (= :pass (get-in summary [:incentive-compatibility :status]))))))
