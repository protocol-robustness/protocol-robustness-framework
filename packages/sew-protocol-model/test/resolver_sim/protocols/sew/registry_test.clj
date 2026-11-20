(ns resolver-sim.protocols.sew.registry-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.evidence.capture :as capture]
            [resolver-sim.protocols.sew.registry :as registry]))

(deftest slash-resolver-stake-consumes-delegated-senior-coverage
  (testing "a delegated slash consumes senior coverage and records insurance"
    (let [world {:resolver-stakes {"junior" 1000}
                 :resolver-senior {"junior" "senior"}
                 :senior-bonds {"senior" {:coverage-max 1000
                                          :reserved-coverage 800}}
                 :bond-distribution {}
                 :params {:insurance-cut-bps 5000
                          :protocol-retained-bps 3000}}
          world'
          (with-redefs [capture/capture-event-evidence!
                        (fn [& _] {:evidence/hash "sha256:test-slash"})]
            (:world (registry/slash-resolver-stake world "junior" 600)))]
      (is (= 200 (get-in world' [:senior-bonds "senior" :reserved-coverage]))
          "the slash must debit the delegated senior coverage pool")
      (is (= 1000 (get-in world' [:resolver-stakes "junior"]))
          "coverage absorbs the slash before resolver stake")
      (is (= 300 (get-in world' [:bond-distribution :insurance]))
          "the configured insurance share must be recorded")
      (is (= 180 (get-in world' [:bond-distribution :protocol]))
          "the configured protocol share must be recorded")
      (is (= 600 (get-in world' [:resolver-slash-total "junior"]))
          "the full slash remains accounted for"))))
