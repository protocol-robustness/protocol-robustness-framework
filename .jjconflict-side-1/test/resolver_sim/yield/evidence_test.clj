(ns resolver-sim.yield.evidence-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.yield.evidence :as ev]))

(deftest extract-per-claim-from-allocation-rows
  (testing "extracts per-claim data from allocation-rows when present"
    (let [decision {:requested {:a 40 :b 60}
                    :filled {:a 20 :b 30}
                    :deferred {:a 20 :b 30}
                    :evidence {:allocation-rows [{:key :a :owed 40 :filled 20 :deferred 20 :fill-ratio 0.5 :cap-hit? false}
                                                 {:key :b :owed 60 :filled 30 :deferred 30 :fill-ratio 0.5 :cap-hit? false}]}}
          result (ev/extract-per-claim-allocation decision)]
      (is (= 2 (count result)))
      (is (= {:claim/key :a :requested 40 :filled 20 :deferred 20 :haircut 0 :fill-ratio 0.5 :cap-hit? false}
             (first result)))
      (is (= {:claim/key :b :requested 60 :filled 30 :deferred 30 :haircut 0 :fill-ratio 0.5 :cap-hit? false}
             (second result))))))

(deftest extract-per-claim-falls-back-to-maps
  (testing "computes per-claim data from flat maps when allocation-rows absent"
    (let [decision {:requested {:a 40 :b 60}
                    :filled {:a 20 :b 30}
                    :deferred {:a 20 :b 30}}
          result (ev/extract-per-claim-allocation decision)]
      (is (= 2 (count result)))
      (is (= 0.5 (:fill-ratio (first result))))
      (is (= 0.5 (:fill-ratio (second result)))))))

(deftest extract-per-claim-handles-haircut
  (testing "includes haircut amounts in per-claim data"
    (let [decision {:requested {:a 100}
                    :filled {:a 50}
                    :deferred {:a 30}
                    :haircut {:a 20}}
          result (ev/extract-per-claim-allocation decision)]
      (is (= 1 (count result)))
      (is (= {:claim/key :a :requested 100 :filled 50 :deferred 30 :haircut 20
              :fill-ratio 0.5 :cap-hit? false}
             (first result))))))

(deftest extract-per-claim-empty-decision
  (testing "returns empty vector for empty decision"
    (let [result (ev/extract-per-claim-allocation {})]
      (is (= [] result)))))
