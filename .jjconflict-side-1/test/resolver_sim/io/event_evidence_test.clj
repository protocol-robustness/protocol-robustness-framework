(ns resolver-sim.io.event-evidence-test
  (:require [clojure.test :refer :all]
            [resolver-sim.io.event-evidence :as event-evidence]))

(deftest evidence-filename-uses-unique-chain-sequence
  (testing "targeted records produced during one replay event do not overwrite each other"
    (let [base {:evidence/type :slashing
                :scenario/id "DR-PR-002"
                :event/seq 7}
          first-name (event-evidence/evidence-filename
                      (assoc base :evidence/chain-seq 6))
          second-name (event-evidence/evidence-filename
                       (assoc base :evidence/chain-seq 7))]
      (is (not= first-name second-name))
      (is (= "slashing-DR-PR-002-6.json" first-name))
      (is (= "slashing-DR-PR-002-7.json" second-name)))))

(deftest evidence-filename-retains-event-sequence-fallback
  (is (= "slashing-S01-4.json"
         (event-evidence/evidence-filename
          {:evidence/type :slashing :scenario/id "S01" :event/seq 4}))))
