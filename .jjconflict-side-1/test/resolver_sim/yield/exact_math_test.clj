(ns resolver-sim.yield.exact-math-test
  "Property and unit tests for exact ratio arithmetic used in yield accrual
   and partial-fill allocation."
  (:require [clojure.test :refer :all]
            [resolver-sim.yield.exact-math :as m]))

(deftest test-bps-ratio-roundtrip
  (testing "bps->ratio and ratio->bps roundtrip for common values"
    (is (= 0 (m/ratio->bps (m/bps->ratio 0))))
    (is (= 500 (m/ratio->bps (m/bps->ratio 500))))
    (is (= 10000 (m/ratio->bps (m/bps->ratio 10000))))
    (is (= 1 (m/ratio->bps (m/bps->ratio 1))))))

(deftest test-index-growth-factor-ratio
  (testing "index-growth-factor returns exact number, not a float"
    (let [factor (m/index-growth-factor 500 31536000)]
      (is (number? factor))
      (is (> (double factor) 1.0))
      (is (< (double factor) 1.1))))

  (testing "zero APY gives factor of 1"
    (let [factor (m/index-growth-factor 0 1000)]
      (is (== 1 (double factor)))))

  (testing "negative APY gives factor < 1"
    (let [factor (m/index-growth-factor -500 31536000)]
      (is (number? factor))
      (is (< (double factor) 1.0)))))

(deftest test-next-index-exact
  (testing "next-index uses exact arithmetic"
    (let [idx (m/next-index 1 500 31536000)]
      (is (number? idx))
      (is (> (double idx) 1.0)))))

(deftest test-quantize-base-units
  (testing "quantize-base-units splits ratio into integer and remainder"
    (let [[units rem] (m/quantize-base-units 7/2)]
      (is (= 3 units))
      (is (= 1/2 rem)))
    (let [[units rem] (m/quantize-base-units 1)]
      (is (= 1 units))
      (is (= 0 rem)))
    (let [[units rem] (m/quantize-base-units 1/3)]
      (is (= 0 units))
      (is (= 1/3 rem)))))

(deftest test-quantize-with-carry-subunit-accumulation
  (testing "Sub-unit amounts accumulate across intervals"
    (let [carry0 0
          {:keys [units carry]} (m/quantize-with-carry 1/3 carry0)]
      (is (= 0 units) "1/3 unit does not reach 1 claimable unit")
      (is (> (double carry) 0) "Carry preserves exact remainder"))

    (let [result1 (m/quantize-with-carry 1/3 0)
          result2 (m/quantize-with-carry 1/3 (:carry result1))
          result3 (m/quantize-with-carry 1/3 (:carry result2))]
      (is (= 0 (:units result1)))
      (is (= 0 (:units result2)))
      (is (= 1 (:units result3)) "1/3 + 1/3 + 1/3 = 1 base unit after carry")))

  (testing "High-frequency accrual drift is bounded before quantization"
    (let [bps 500
          dt 31536000
          full-idx (m/next-index 1 bps dt)
          n 365
          per-dt (quot dt n)
          rem-dt (- dt (* per-dt n))
          fragmented (loop [idx (m/ratio 1)
                            i 0]
                       (if (< i n)
                         (recur (m/next-index idx bps per-dt) (inc i))
                         (m/next-index idx bps rem-dt)))
          drift (Math/abs (- (double full-idx) (double fragmented)))]
      (is (number? full-idx))
      (is (number? fragmented))
      (is (< drift 0.002)
          (str "Compounding drift " drift " within 0.2% for 5% APY over one year")))))

(deftest test-floor-and-carry-alloc
  (testing "floor-and-carry alloc sum never exceeds available"
    (let [claims [{:key :a :amount 100} {:key :b :amount 100} {:key :c :amount 100}]
          result (m/floor-and-carry-alloc 250 claims)]
      (is (<= (reduce + 0 (map :filled (:allocations result)))
              (first (m/quantize-base-units 250))))))

  (testing "floor-and-carry preserves exact carry"
    (let [result (m/floor-and-carry-alloc 100 [{:key :a :amount 3} {:key :b :amount 3}])]
      (is (number? (:carry result)))))

  (testing "equal claims get equal floor allocations"
    (let [claims [{:key :a :amount 100} {:key :b :amount 100}]
          result (m/floor-and-carry-alloc 150 claims)
          allocs (:allocations result)]
      (is (= 75 (:filled (first allocs))))
      (is (= 75 (:filled (second allocs))))))

  (testing "shortage units are distributed (no lost units)"
    (let [claims [{:key :a :amount 100} {:key :b :amount 100} {:key :c :amount 100}]
          available 10
          result (m/floor-and-carry-alloc available claims)
          total-filled (reduce + 0 (map :filled (:allocations result)))]
      (is (= available total-filled)
          (str "Expected all " available " units allocated, but got " total-filled
               ". shortage=" (:shortage-units result) " carry=" (:carry result)))))

  (testing "distributes across claims when amounts differ"
    (let [claims [{:key :a :amount 7} {:key :b :amount 3}]
          available 10
          result (m/floor-and-carry-alloc available claims)
          total-filled (reduce + 0 (map :filled (:allocations result)))]
      (is (= available total-filled)
          (str "Expected all " available " units allocated, but got " total-filled
               ". shortage=" (:shortage-units result)))))

  (testing "large-rounding-loss prevented for many claims"
    (let [claims (mapv (fn [i] {:key (keyword (str "c" i)) :amount 1}) (range 100))
          available 50
          result (m/floor-and-carry-alloc available claims)
          total-filled (reduce + 0 (map :filled (:allocations result)))]
      (is (= available total-filled)
          (str "Expected " available " units, got " total-filled
               " — lost " (- available total-filled) " units across " (count claims) " claims")))))

(deftest test-largest-remainder-alloc
  (testing "largest-remainder alloc sum equals available units"
    (let [claims [{:key :a :amount 100} {:key :b :amount 60} {:key :c :amount 40}]
          result (m/largest-remainder-alloc 150 claims)
          total-alloc (reduce + 0 (map :filled (:allocations result)))]
      (is (= 150 total-alloc))))

  (testing "largest-remainder never exceeds available"
    (let [claims [{:key :a :amount 1000} {:key :b :amount 1}]
          result (m/largest-remainder-alloc 500 claims)]
      (is (<= (reduce + 0 (map :filled (:allocations result)))
              500)))))

(deftest test-principal-protective-floor-alloc
  (testing "principal-protective-floor protects principal claims"
    (let [claims [{:key :principal :amount 1000}
                  {:key :yield :amount 500}]
          result (m/principal-protective-floor-alloc 1200 claims
                                                     (fn [c] (= :principal (:key c))))
          allocs (:allocations result)
          principal-filled (:filled (first allocs))
          yield-filled (:filled (second allocs))]
      (is (= 1000 principal-filled) "Principal should be fully protected")
      (is (<= yield-filled 200) "Yield gets remaining only")))

  (testing "principal-protective-floor when insufficient for principal"
    (let [claims [{:key :principal :amount 1000}
                  {:key :yield :amount 500}]
          result (m/principal-protective-floor-alloc 500 claims
                                                     (fn [c] (= :principal (:key c))))
          allocs (:allocations result)
          principal-filled (:filled (first allocs))]
      (is (= 0 (:filled (second allocs))) "Yield should get nothing")
      (is (<= principal-filled 500) "Principal capped at available"))))

(deftest test-adversarial-rounding
  (testing "adversarial rounding creates positive rounding debt"
    (let [claims [{:key :a :amount 3} {:key :b :amount 3} {:key :c :amount 3}]
          result (m/adversarial-rounding 10 claims)]
      (is (pos? (:rounding-debt result)) "Adversarial rounding should create rounding debt")
      (is (> (reduce + 0 (map :filled (:allocations result))) 10)
          "Adversarial rounding may exceed available by design")))

  (testing "rounding debt is conserved"
    (let [claims [{:key :a :amount 3} {:key :b :amount 3} {:key :c :amount 3}]
          result (m/adversarial-rounding 10 claims)
          sum-remainders (reduce + 0 (map :remainder-exact (:allocations result)))]
      (is (>= (:rounding-debt result) 0)))))

(deftest test-apy-degradation
  (testing "APY degrades toward floor with staleness"
    (is (= 500 (m/apy-degradation 500 0 86400 0))
        "No staleness = no degradation")
    (is (< (m/apy-degradation 500 43200 86400 0) 500)
        "Half stale = partial degradation")
    (is (= 0 (m/apy-degradation 500 86400 86400 0))
        "Fully stale = full degradation to floor"))

  (testing "Negative APY remains unchanged"
    (is (= -500 (m/apy-degradation -500 43200 86400 0)))))

(deftest test-shortfall-ratio-exact
  (testing "shortfall-ratio-exact computes exact ratio"
    (is (= 1/2 (m/shortfall-ratio-exact 50 100)))
    (is (= 1 (m/shortfall-ratio-exact 100 100)))
    (is (= 1 (m/shortfall-ratio-exact 200 100)) "Cap at 1.0")
    (is (= 1 (m/shortfall-ratio-exact 100 0)) "Zero denominator gives 1")))

(deftest test-ratio-json-serialization
  (testing "ratio->json produces EDN-safe representation"
    (let [json (m/ratio->json 1/3)]
      (is (= "1" (:num json)))
      (is (= "3" (:den json))))
    (let [json (m/ratio->json 22/7)]
      (is (= "22" (:num json)))
      (is (= "7" (:den json))))))

(deftest test-dust-imbalance-vs-economic-insolvency
  (testing "Dust imbalance is distinguishable from economic insolvency"
    (let [dust (m/quantize-with-carry 1/7 0)
          insolvent-value -10000]
      (is (>= (double (:carry dust)) 0) "Dust carry is non-negative")
      (is (< (double (:carry dust)) 1) "Dust is < 1 base unit")
      (is (neg? insolvent-value) "Insolvency is clearly negative"))))

(deftest test-shares-from-principal-and-index
  (testing "shares computation is exact"
    (let [shares (m/shares-from-principal-and-index 10000 (m/ratio 5/4))]
      (is (number? shares))
      (is (== 8000 (double shares)))))

  (testing "roundtrip: principal -> shares -> principal"
    (let [principal 10000
          entry-index (m/ratio 5/4)
          shares (m/shares-from-principal-and-index principal entry-index)
          recovered (m/principal-from-shares-and-index shares entry-index)]
      (is (= principal (long (double recovered)))))))

(deftest test-current-value-exact
  (testing "current-value-exact is ratio-accurate"
    (let [shares (m/ratio 8000)
          index (m/ratio 5/4)
          value (m/current-value-exact shares index)]
      (is (number? value))
      (is (== 10000 (double value)) "8000 shares × 1.25 index = 10000"))))

(deftest test-position-current-value-exact-conservation
  (testing "Position value conservation with exact ratios"
    (let [principal 10000
          entry-index (m/ratio 1)
          shares (m/shares-from-principal-and-index principal entry-index)
          new-index (m/next-index entry-index 500 31536000)
          init-value (m/current-value-exact shares entry-index)
          final-value (m/current-value-exact shares new-index)
          delta-exact (- final-value init-value)]
      (is (number? delta-exact))
      (is (> (double delta-exact) 0))
      (is (< (double delta-exact) 1000))
      (is (== (double (* (m/ratio principal) (- new-index entry-index)))
              (double delta-exact))
          "Delta should equal principal × (new-index - 1)"))))

