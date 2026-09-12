(ns resolver-sim.delegation-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew.research-models.delegation :as delegation]
            [resolver-sim.protocols.sew.research-models.resolver-ring :as ring]))

(deftest test-delegation-registry
  (testing "Create resolver registry with senior and juniors"
    (let [specs {"senior-1" {:bond 10000 :tier :senior}
                 "junior-1" {:bond 1000 :delegated-to "senior-1" :tier :junior}
                 "junior-2" {:bond 1000 :delegated-to "senior-1" :tier :junior}}
          registry (delegation/create-resolver-registry specs)]
      (is (= 3 (count registry)))
      (is (= 10000 (get-in registry ["senior-1" :bond])))
      (is (= "senior-1" (get-in registry ["junior-1" :senior]))))))

(deftest test-coverage-available
  (testing "Calculate senior coverage correctly"
    (let [specs {"senior-1" {:bond 10000 :tier :senior}
                 "junior-1" {:bond 1000 :delegated-to "senior-1" :tier :junior}}
          registry (delegation/create-resolver-registry specs)
          coverage (delegation/coverage-available registry "senior-1")]
      (is (= 15000 (:total-coverage coverage)))  ; 10000 * 3 * 0.5
      (is (= 0 (:reserved coverage)))
      (is (= 15000 (:available coverage))))))

(deftest test-waterfall-slashing-solo
  (testing "Slash solo resolver"
    (let [registry (delegation/create-resolver-registry
                    {"solo-1" {:bond 5000 :tier :solo}})
          registry-after (delegation/waterfall-slash registry "solo-1" 1000)]
      (is (= 4000 (get-in registry-after ["solo-1" :bond])))
      (is (= 1000 (get-in registry-after ["solo-1" :slashed-amount]))))))

(deftest test-waterfall-slashing-junior
  (testing "Slash junior (depletes own bond first)"
    (let [registry (delegation/create-resolver-registry
                    {"senior-1" {:bond 10000 :tier :senior}
                     "junior-1" {:bond 1000 :delegated-to "senior-1" :tier :junior}})
          registry-after (delegation/waterfall-slash registry "junior-1" 500)]
      (is (= 500 (get-in registry-after ["junior-1" :bond])))
      (is (= 500 (get-in registry-after ["junior-1" :slashed-amount]))))))

(deftest test-waterfall-slashing-junior-exceeds-bond
  (testing "Slash junior beyond bond (reduces senior's reserved coverage)"
    (let [registry (delegation/create-resolver-registry
                    {"senior-1" {:bond 10000 :tier :senior}
                     "junior-1" {:bond 1000 :delegated-to "senior-1" :tier :junior}})
          ;; Pre-reserve coverage on the senior (not junior)
          registry-with-coverage (assoc-in registry ["senior-1" :reserved-coverage] 2000)
          ;; Slash 2000: 1000 from junior bond, 1000 from senior's reserved coverage
          registry-after (delegation/waterfall-slash registry-with-coverage "junior-1" 2000)]
      ;; Junior's bond should be fully depleted
      (is (= 0 (get-in registry-after ["junior-1" :bond])))
      ;; Junior should have 1000 slashed from their bond
      (is (= 1000 (get-in registry-after ["junior-1" :slashed-amount])))
      ;; Senior's reserved coverage should be reduced by 1000 (from 2000 to 1000)
      (is (= 1000 (get-in registry-after ["senior-1" :reserved-coverage])))
      ;; Senior should also have 1000 slashed
      (is (= 1000 (get-in registry-after ["senior-1" :slashed-amount]))))))

(deftest test-resolver-ring-creation
  (testing "Create a resolver ring"
    (let [ring-spec {:senior {:bond 10000 :name "senior-1"}
                     :juniors [{:bond 1000 :name "junior-1"}
                               {:bond 1000 :name "junior-2"}
                               {:bond 1000 :name "junior-3"}]}
          my-ring (ring/create-ring ring-spec)]
      (is (= "senior-1" (:senior-id my-ring)))
      (is (= 3 (count (:junior-ids my-ring))))
      (is (= 0 (:total-profit my-ring)))
      (is (= 0 (:disputes-total my-ring))))))

(deftest test-ring-profitability
  (testing "Analyze ring profitability"
    (let [ring-spec {:senior {:bond 10000 :name "senior-1"}
                     :juniors [{:bond 1000 :name "junior-1"}
                               {:bond 1000 :name "junior-2"}
                               {:bond 1000 :name "junior-3"}]}
          my-ring (ring/create-ring ring-spec)
          ;; Simulate some profit
          my-ring-with-profit (assoc my-ring :total-profit 500 :disputes-total 10)]
      (let [analysis (ring/ring-profitability my-ring-with-profit)]
        (is (= 500 (:total-profit analysis)))
        (is (= 50 (:average-profit-per-dispute analysis)))
        (is (= 4 (count (:member-states analysis))))
        (is (:viable? analysis))))))
