(ns resolver-sim.demos.liquidity-shortfall.demo-test
  "Tests for the 'liquidity shortfall' demonstration (Demo C).

   The demonstration's claims are only as good as the allocation engine they
   run, so these tests pin the exact outcome: pool 70 vs requests 100, exact
   pro-rata fills (35/21/14), conservation holds, and the shortfall is exactly
   requested - allocated for every request."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.demos.liquidity-shortfall.assertions :as assertions]
            [resolver-sim.demos.liquidity-shortfall.demo :as demo]
            [resolver-sim.demos.liquidity-shortfall.scenario :as scenario]))

(deftest demo-model-is-complete
  (let [result (demo/run)]
    (is (= :allocation/shortfall (:demo/id result)))
    (is (seq (:demo/question result)))
    (is (seq (:demo/explanation result)))
    (is (= 70 (:available (:demo/pool result))))
    (is (= 100 (:requested (:demo/pool result))))))

(deftest allocation-is-exact-pro-rata
  (let [result (demo/run)
        by-id (into {} (map (juxt :request/id identity)) (:demo/requests result))]
    (is (= 35 (:allocated (get by-id :alice))))
    (is (= 21 (:allocated (get by-id :bob))))
    (is (= 14 (:allocated (get by-id :cara))))
    (is (= 15 (:shortfall (get by-id :alice))))
    (is (= 9  (:shortfall (get by-id :bob))))
    (is (= 6  (:shortfall (get by-id :cara))))))

(deftest conservation-holds
  (let [result (demo/run)
        c (:demo/conservation result)]
    (is (= 100 (:requested c)))
    (is (= 70 (:allocated c)))
    (is (= 30 (:shortfall c)))
    (is (:holds? c))))

(deftest pool-is-fully-allocated
  (let [result (demo/run)]
    (is (:pool-fully-allocated? (:demo/expect result)))
    (is (= 70 (:total-allocated (:demo/allocation result))))
    (is (zero? (:unallocated-residual (:demo/allocation result))))))

(deftest evidence-carries-committed-allocation-hash
  (let [result (demo/run)
        evidence (:demo/evidence result)]
    (is (seq (:committed-hash evidence)))
    (is (seq (:request/hash evidence)))
    (is (= 4 (count (:lines evidence))))
    (is (= 2 (count (:after/checks evidence))))
    (is (every? #(= :pass (:status %)) (:after/checks evidence)))))

(deftest demo-is-deterministic
  (let [r1 (demo/run)
        r2 (demo/run)]
    (is (= (get-in r1 [:demo/evidence :committed-hash])
           (get-in r2 [:demo/evidence :committed-hash])))
    (is (= (:demo/requests r1) (:demo/requests r2)))))

(deftest scenario-uses-the-real-engine
  (testing "the demo runs the domain-neutral allocation engine"
    (let [result (scenario/run-allocation)]
      (is (= "pro-rata-allocation-result.v1" (:schema-version result)))
      (is (seq (:allocation/hash result))))))

(deftest assertions-hold
  (testing "the committed expectations pass"
    (let [{:keys [pass? failures]} (assertions/check)]
      (is pass? failures))))
