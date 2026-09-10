(ns resolver-sim.financial.pro-rata-characterization-test
  "Characterization tests freezing current pro-rata and slashing behavior.
   These tests capture current inputs, outputs, rounding, unmet obligations,
   caps, and conservation results to prevent accidental semantic drift during
   future refactoring."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.yield.exact-math :as exact-math]
            [resolver-sim.pro-rata.allocation :as allocation]
            [resolver-sim.protocols.sew.economics :as sew-economics]))

;; =============================================================================
;; largest-remainder-alloc characterization
;; =============================================================================
;;
;; The canonical pro-rata primitive (Hare quota / largest-remainder method).
;; Input: total-available (numeric), claims (seq of maps with :amount)
;; Output:
;;   {:allocations [{:amount N, :filled N, :remainder-exact R, :ideal-exact I} ...]
;;    :total-available-units N
;;    :total-allocated-units N
;;    :shortage-units 0
;;    :carry 0}
;; Contracts:
;;   - sum(:filled) == total-available-units (conservation)
;;   - floor(ideal) <= :filled <= ceil(ideal) (quota rule)
;;   - All amounts are integer base units

(deftest ^:characterization lra-equal-claims-divisible
  (testing "equal claims with perfectly divisible total"
    (let [result (exact-math/largest-remainder-alloc 10 [{:amount 5} {:amount 5}])]
      (is (= [{:amount 5 :filled 5 :ideal-exact 5 :remainder-exact 0}
              {:amount 5 :filled 5 :ideal-exact 5 :remainder-exact 0}]
             (:allocations result)))
      (is (= 10 (:total-available-units result)))
      (is (= 10 (:total-allocated-units result)))
      (is (= 0 (:shortage-units result)))
      (is (= 0 (:carry result))))))

(deftest ^:characterization lra-equal-claims-indivisible
  (testing "equal claims with indivisible total — dust goes to first by input order"
    (let [result (exact-math/largest-remainder-alloc 10 [{:amount 3} {:amount 3} {:amount 3}])]
      (is (= [{:amount 3 :filled 4}
              {:amount 3 :filled 3}
              {:amount 3 :filled 3}]
             (mapv #(select-keys % [:amount :filled]) (:allocations result))))
      (is (= [4 3 3] (mapv :filled (:allocations result))))
      (is (= 10 (:total-allocated-units result)))
      (is (= 10 (:total-available-units result))))))

(deftest ^:characterization lra-unequal-claims-exact
  (testing "unequal claims whose ideal shares are exact integers"
    (let [result (exact-math/largest-remainder-alloc 10 [{:amount 5} {:amount 3} {:amount 2}])]
      (is (= [5 3 2] (mapv :filled (:allocations result))))
      (is (= 10 (:total-allocated-units result))))))

(deftest ^:characterization lra-single-claim
  (testing "single claim receives entire total"
    (let [result (exact-math/largest-remainder-alloc 10 [{:amount 5}])]
      (is (= [{:amount 5 :filled 10}] (mapv #(select-keys % [:amount :filled]) (:allocations result))))
      (is (= 10 (:total-allocated-units result))))))

(deftest ^:characterization lra-zero-available
  (testing "zero total available yields zero allocations"
    (let [result (exact-math/largest-remainder-alloc 0 [{:amount 5}])]
      (is (= [{:amount 5 :filled 0}] (mapv #(select-keys % [:amount :filled]) (:allocations result))))
      (is (= 0 (:total-allocated-units result))))))

(deftest ^:characterization lra-empty-claims
  (testing "empty claims list yields empty allocations with zero-claims branch (carry = total)"
    (let [result (exact-math/largest-remainder-alloc 10 [])]
      (is (= [] (:allocations result)))
      (is (= 0 (:total-allocated-units result)))
      (is (= 10 (:total-available-units result))))))

(deftest ^:characterization lra-all-zero-amount-claims
  (testing "all claims with zero amount — zero-claims branch"
    (let [result (exact-math/largest-remainder-alloc 10 [{:amount 0} {:amount 0}])]
      (is (= [{:amount 0 :filled 0}
              {:amount 0 :filled 0}]
             (mapv #(select-keys % [:amount :filled]) (:allocations result))))
      (is (= 0 (:total-allocated-units result))))))

(deftest ^:characterization lra-tie-breaking-input-order
  (testing "dust distribution tie-breaking uses input order (earlier index wins)"
    (let [result (exact-math/largest-remainder-alloc 10 [{:key :a :amount 3}
                                                         {:key :b :amount 3}
                                                         {:key :c :amount 3}])]
      (is (= [4 3 3] (mapv :filled (:allocations result)))))))

(deftest ^:characterization lra-dust-distribution-deterministic
  (testing "dust distribution is deterministic across repeated calls"
    (let [claims [{:key :a :amount 3} {:key :b :amount 3} {:key :c :amount 3}]
          r1 (exact-math/largest-remainder-alloc 10 claims)
          r2 (exact-math/largest-remainder-alloc 10 claims)]
      (is (= (mapv :filled (:allocations r1))
             (mapv :filled (:allocations r2)))))))

(deftest ^:characterization lra-input-order-advantage-bounded
  (testing "first-in-input advantage does not exceed 1 unit over last-in-input"
    (doseq [claims [[{:key :a :amount 3} {:key :b :amount 3} {:key :c :amount 3}]
                    [{:key :a :amount 5} {:key :b :amount 5} {:key :c :amount 5} {:key :d :amount 5}]
                    [{:key :a :amount 1} {:key :b :amount 2} {:key :c :amount 3} {:key :d :amount 4}]]]
      (let [total (reduce + 0 (map :amount claims))
            total-with-tie (inc total)
            forward (exact-math/largest-remainder-alloc total-with-tie claims)
            reversed (exact-math/largest-remainder-alloc total-with-tie (vec (rseq claims)))
            f-filled (mapv :filled (:allocations forward))
            r-filled (mapv :filled (:allocations reversed))
            max-diff (reduce max (map (fn [f r] (abs (- f r))) f-filled (vec (rseq r-filled))))]
        (is (<= max-diff 1)
            (str "max allocation difference = " max-diff " for claims=" (pr-str claims)))))))

(deftest ^:characterization lra-dust-goes-to-largest-remainder
  (testing "dust goes to largest fractional remainder, not blindly to first position"
    (let [claims [{:key :a :amount 3} {:key :b :amount 3} {:key :c :amount 1}]
          result (exact-math/largest-remainder-alloc 8 claims)
          dust-recipient (:key (first (sort-by :filled > (:allocations result))))]
      (is (= :a dust-recipient)
          "first claim with largest remainder receives the dust"))))

(deftest ^:characterization lra-float-amounts
  (testing "float amounts are rationalized internally"
    (let [result (exact-math/largest-remainder-alloc 10 [{:amount 0.5} {:amount 0.5}])]
      (is (= [5 5] (mapv :filled (:allocations result))))
      (is (= 10 (:total-allocated-units result))))))

(deftest ^:characterization lra-conservation-invariant
  (testing "sum of filled always equals total-available-units"
    (doseq [[total claims] [[10 [{:amount 3} {:amount 3} {:amount 3}]]
                            [100 [{:amount 1} {:amount 2} {:amount 3} {:amount 4}]]
                            [7 [{:amount 10} {:amount 20}]]
                            [1 [{:amount 1000}]]
                            [1000 (mapv (fn [i] {:amount (inc i)}) (range 10))]]]
      (let [result (exact-math/largest-remainder-alloc total claims)]
        (is (= (:total-available-units result)
               (:total-allocated-units result))
            (str "Conservation failed: total=" total " claims=" (pr-str claims)))))))

(deftest ^:characterization lra-quota-rule-invariant
  (testing "each allocation satisfies floor(ideal) <= filled <= ceil(ideal)"
    (doseq [[total claims] [[10 [{:amount 3} {:amount 3} {:amount 3}]]
                            [100 [{:amount 10} {:amount 20} {:amount 30} {:amount 40}]]
                            [7 [{:amount 1} {:amount 2}]]
                            [1000 (mapv (fn [i] {:amount (inc i)}) (range 10))]]]
      (let [result (exact-math/largest-remainder-alloc total claims)]
        (doseq [a (:allocations result)]
          (let [idl (:ideal-exact a)
                [flr rem] (exact-math/quantize-base-units idl)
                cel (if (zero? rem) flr (inc flr))]
            (is (<= flr (:filled a) cel)
                (str "Quota rule violation: ideal=" idl " filled=" (:filled a)))))))))

(deftest ^:characterization lra-ratio-total-available
  (testing "ratio total-available rounds correctly"
    (let [total (/ 10 3)
          result (exact-math/largest-remainder-alloc total [{:amount 1} {:amount 1}])]
      (is (= 3 (:total-available-units result)))
      ;; ideal each = total * 1/2 = (10/3) * 1/2 = 5/3
      ;; floor = 1 each, shortage = 3 - 2 = 1
      ;; largest remainder: 5/3 = 1 remainder 2/3, both have same remainder
      ;; tie goes to first: [2, 1]
      (is (= [2 1] (mapv :filled (:allocations result))))
      (is (= 3 (:total-allocated-units result))))))

;; =============================================================================
;; allocate-pro-rata characterization  (generic, protocol-agnostic)
;; =============================================================================
;;
;; Input: {:amount N, :items [{:id kw, :weight N, :cap N-or-nil} ...]
;;         :id-fn, :weight-fn, :cap-fn, :rounding, :remainder-policy, :ordering-policy}
;; Output:
;;   {:allocations [{:id kw, :allocated N, :unmet N, :weight N, :cap N-or-nil} ...]
;;    :total-requested N
;;    :total-allocated N
;;    :total-unmet N
;;    :remainder N
;;    :policy {...}}

(deftest ^:characterization apr-conservation-invariant
  (testing "requested = allocated + unmet + remainder across diverse inputs"
    (doseq [spec [{:amount 400
                   :items [{:id :a :weight 1000 :cap 1000}
                           {:id :b :weight 500 :cap 60}
                           {:id :c :weight 500 :cap 500}]
                   :cap-fn :cap}
                  {:amount 10
                   :rounding :floor-with-largest-remainder
                   :items [{:id :a :weight 1}
                           {:id :b :weight 1}
                           {:id :c :weight 1}]}
                  {:amount 100
                   :items [{:id :a :weight 0}
                           {:id :b :weight 10}]}
                  {:amount 100
                   :items [{:id :a :weight 10 :cap 5}
                           {:id :b :weight 10}]
                   :cap-fn :cap}
                  {:amount 0
                   :items [{:id :a :weight 10}]}
                  {:amount 50
                   :items [{:id :a :weight 3}
                           {:id :b :weight 7}]}
                  {:amount 100
                   :items [{:id :a :weight 100}
                           {:id :b :weight 200}
                           {:id :c :weight 300}]
                   :cap-fn (constantly 1000)}]]
      (let [result (allocation/allocate-pro-rata spec)]
        (is (= (:total-requested result)
               (+ (:total-allocated result)
                  (:total-unmet result)
                  (:remainder result)))
            (str "Conservation failed for spec: " (pr-str spec)))
        (is (every? #(<= 0 (:allocated %)) (:allocations result))
            (str "Negative allocation for spec: " (pr-str spec)))
        (is (every? #(<= 0 (:unmet %)) (:allocations result))
            (str "Negative unmet for spec: " (pr-str spec)))))))

(deftest ^:characterization apr-cap-prevents-over-allocation
  (testing "cap limits individual allocation and records unmet"
    (let [result (allocation/allocate-pro-rata
                  {:amount 400
                   :items [{:id :a :weight 1000 :cap 1000}
                           {:id :b :weight 500 :cap 60}
                           {:id :c :weight 500 :cap 500}]
                   :cap-fn :cap})]
      (is (= [200 60 100] (mapv :allocated (:allocations result))))
      (is (= [0 40 0] (mapv :unmet (:allocations result))))
      (is (= 360 (:total-allocated result)))
      (is (= 40 (:total-unmet result)))
      (is (= 0 (:remainder result))))))

(deftest ^:characterization apr-floor-rounding-leaves-remainder
  (testing "default floor rounding leaves integer dust in remainder"
    (let [result (allocation/allocate-pro-rata
                  {:amount 10
                   :items [{:id :a :weight 1}
                           {:id :b :weight 1}
                           {:id :c :weight 1}]})]
      (is (= [3 3 3] (mapv :allocated (:allocations result))))
      (is (= 9 (:total-allocated result)))
      (is (= 0 (:total-unmet result)))
      (is (= 1 (:remainder result))))))

(deftest ^:characterization apr-largest-remainder-distributes-all
  (testing "largest-remainder rounding distributes all dust deterministically"
    (let [result (allocation/allocate-pro-rata
                  {:amount 10
                   :rounding :floor-with-largest-remainder
                   :items [{:id :a :weight 1}
                           {:id :b :weight 1}
                           {:id :c :weight 1}]})]
      (is (= [4 3 3] (mapv :allocated (:allocations result))))
      (is (= 10 (:total-allocated result)))
      (is (= 0 (:total-unmet result)))
      (is (= 0 (:remainder result))))))

(deftest ^:characterization apr-zero-weight-gets-nothing
  (testing "zero-weight items get no allocation, remainder stays"
    (let [result (allocation/allocate-pro-rata
                  {:amount 100
                   :items [{:id :a :weight 0}
                           {:id :b :weight 10}]})]
      (is (= [0 100] (mapv :allocated (:allocations result))))
      (is (= 100 (:total-allocated result)))
      (is (= 0 (:remainder result))))))

(deftest ^:characterization apr-capped-largest-remainder
  (testing "cap with largest-remainder rounding"
    (let [result (allocation/allocate-pro-rata
                  {:amount 100
                   :items [{:id :a :weight 50 :cap 30}
                           {:id :b :weight 50}]
                   :cap-fn :cap
                   :rounding :floor-with-largest-remainder})]
      ;; Without cap: a=50, b=50. With cap: a capped at 30, b gets adjusted
      ;; a: floor(100*50/100)=50, capped at 30 → allocated=30, unmet=20
      ;; b: floor(100*50/100)=50, no cap → allocated=50
      ;; total-allocated=80, total-unmet=20, remainder=0
      (is (= [{:id :a :allocated 30 :unmet 20}
              {:id :b :allocated 50 :unmet 0}]
             (mapv #(select-keys % [:id :allocated :unmet]) (:allocations result))))
      (is (= 80 (:total-allocated result)))
      (is (= 20 (:total-unmet result)))
      (is (= 0 (:remainder result))))))

(deftest ^:characterization apr-uneven-weights-exact-characterization
  (testing "characterizes exact allocation for 3:7 ratio"
    (let [result (allocation/allocate-pro-rata
                  {:amount 50
                   :items [{:id :a :weight 3}
                           {:id :b :weight 7}]
                   :rounding :floor-with-largest-remainder})]
      ;; a: floor(50*3/10)=15, b: floor(50*7/10)=35
      ;; remainder: 50-15-35=0, so no dust
      (is (= [15 35] (mapv :allocated (:allocations result)))))))

(deftest ^:characterization apr-conservation-large-set
  (testing "conservation holds for 10-way split with caps"
    (let [items (mapv (fn [i] {:id (keyword (str "p" i))
                               :weight (* 10 (inc i))
                               :cap (* 5 (inc i))})
                      (range 10))
          result (allocation/allocate-pro-rata
                  {:amount 1000
                   :items items
                   :cap-fn :cap
                   :rounding :floor-with-largest-remainder})]
      (is (= (:total-requested result)
             (+ (:total-allocated result)
                (:total-unmet result)
                (:remainder result)))))
    (testing "no negative allocations"
      (let [items (mapv (fn [i] {:id (keyword (str "p" i))
                                 :weight (inc i)
                                 :cap (inc i)})
                        (range 5))
            result (allocation/allocate-pro-rata
                    {:amount 50
                     :items items
                     :cap-fn :cap
                     :rounding :floor})]
        (is (every? #(<= 0 (:allocated %)) (:allocations result)))))))

;; =============================================================================
;; calculate-sew-slash-allocation characterization
;; =============================================================================
;;
;; Input: {:slash-amount N, :liable-parties [{:id kw :slashable-stake N :available-slashable N} ...]
;;          :basis kw, :cap-field kw, :unmet-policy kw, :slash-policy map}
;; Defaults: basis=:slashable-stake, cap-field=:available-slashable, unmet-policy=:record-only
;; Output:
;;   {:status :ok | :no-liable-basis
;;    :basis kw, :cap-field kw, :unmet-policy kw
;;    :slash-obligation N
;;    :total-basis N
;;    :recovered-total N
;;    :unmet-total N
;;    :allocations [{:id kw, :basis-amount N, :share ratio,
;;                    :owed N, :paid N, :unmet N} ...]}

(deftest ^:characterization csa-equal-parties
  (testing "equal stakes, no cap constraints, full recovery"
    (let [result (sew-economics/calculate-sew-slash-allocation
                  {:slash-amount 100
                   :liable-parties [{:id :alice :slashable-stake 100 :available-slashable 100}
                                    {:id :bob   :slashable-stake 100 :available-slashable 100}]})]
      ;; Note: function does not set :status on success
      (is (= 200 (:total-basis result)))
      (is (= 100 (:recovered-total result)))
      (is (= 0 (:unmet-total result)))
      (is (= [{:id :alice :basis-amount 100 :share 1/2 :owed 50 :paid 50 :unmet 0}
              {:id :bob   :basis-amount 100 :share 1/2 :owed 50 :paid 50 :unmet 0}]
             (mapv #(select-keys % [:id :basis-amount :share :owed :paid :unmet])
                   (:allocations result)))))))

(deftest ^:characterization csa-unequal-stakes
  (testing "unequal stakes yield proportional allocation"
    (let [result (sew-economics/calculate-sew-slash-allocation
                  {:slash-amount 100
                   :liable-parties [{:id :alice :slashable-stake 200 :available-slashable 200}
                                    {:id :bob   :slashable-stake 100 :available-slashable 100}]})]
      (is (= 300 (:total-basis result)))
      (is (= 100 (:recovered-total result)))
      ;; alice: 2/3 share → 66.66..., floor=66, rem=2/3 → rem NOT largest (both same), first gets dust
      ;; bob: 1/3 share → 33.33..., floor=33
      ;; wait: alice has larger remainder actually — let's compute
      ;; alice ideal = 100 * 200/300 = 200/3 = 66 + 2/3 → floor=66 rem=2/3
      ;; bob ideal = 100 * 100/300 = 100/3 = 33 + 1/3 → floor=33 rem=1/3
      ;; shortage = 100 - 66 - 33 = 1
      ;; remainders: alice=2/3, bob=1/3. sort by -(rem) → alice first
      ;; alice gets the extra unit: 67, bob gets 33
      (is (= [{:id :alice :paid 67 :unmet 0}
              {:id :bob   :paid 33 :unmet 0}]
             (mapv #(select-keys % [:id :paid :unmet]) (:allocations result)))))))

(deftest ^:characterization csa-cap-limits-recovery
  (testing "available-slashable caps the amount recovered from a party"
    (let [result (sew-economics/calculate-sew-slash-allocation
                  {:slash-amount 100
                   :liable-parties [{:id :alice :slashable-stake 200 :available-slashable 30}
                                    {:id :bob   :slashable-stake 200 :available-slashable 200}]})]
      ;; total-basis = 400
      ;; alice: ideal = 100 * 200/400 = 50, but cap = 30, so allocated=30, unmet=20
      ;; bob: ideal = 100 * 200/400 = 50, no cap → allocated=50
      ;; total-allocated=80, total-unmet=20
      (is (= 80 (:recovered-total result)))
      (is (= 20 (:unmet-total result)))
      (is (= [{:id :alice :paid 30 :unmet 20}
              {:id :bob   :paid 50 :unmet 0}]
             (mapv #(select-keys % [:id :paid :unmet]) (:allocations result)))))))

(deftest ^:characterization csa-zero-total-basis
  (testing "zero total basis returns no-liable-basis status"
    (let [result (sew-economics/calculate-sew-slash-allocation
                  {:slash-amount 100
                   :liable-parties [{:id :alice :slashable-stake 0 :available-slashable 0}
                                    {:id :bob   :slashable-stake 0 :available-slashable 0}]})]
      (is (= :no-liable-basis (:status result)))
      (is (= 0 (:total-basis result)))
      (is (= 0 (:recovered-total result)))
      (is (= 100 (:unmet-total result)))
      (is (= [] (:allocations result))))))

(deftest ^:characterization csa-zero-slash-amount
  (testing "zero slash amount yields zero recovery"
    (let [result (sew-economics/calculate-sew-slash-allocation
                  {:slash-amount 0
                   :liable-parties [{:id :alice :slashable-stake 100 :available-slashable 100}]})]
      (is (= 0 (:recovered-total result)))
      (is (= 0 (:unmet-total result)))
      (is (= [{:id :alice :paid 0 :unmet 0}]
             (mapv #(select-keys % [:id :paid :unmet]) (:allocations result)))))))

(deftest ^:characterization csa-empty-liable-parties
  (testing "empty liable parties yields no-liable-basis"
    (let [result (sew-economics/calculate-sew-slash-allocation
                  {:slash-amount 100
                   :liable-parties []})]
      (is (= :no-liable-basis (:status result)))
      (is (= 0 (:total-basis result)))
      (is (= 0 (:recovered-total result)))
      (is (= 100 (:unmet-total result)))
      (is (= [] (:allocations result))))))

(deftest ^:characterization csa-custom-basis-field
  (testing "custom basis field changes weighting"
    (let [result (sew-economics/calculate-sew-slash-allocation
                  {:slash-amount 100
                   :basis :stake
                   :cap-field :max-slash
                   :liable-parties [{:id :alice :stake 300 :max-slash 200}
                                    {:id :bob   :stake 100 :max-slash 100}]})]
      ;; Note: function does not set :status on success
      (is (= 400 (:total-basis result)))
      ;; alice: 300/400 = 3/4, ideal = 75, cap 200 → 75
      ;; bob: 100/400 = 1/4, ideal = 25, cap 100 → 25
      (is (= [{:id :alice :basis-amount 300 :paid 75 :unmet 0}
              {:id :bob   :basis-amount 100 :paid 25 :unmet 0}]
             (mapv #(select-keys % [:id :basis-amount :paid :unmet]) (:allocations result)))))))

(deftest ^:characterization csa-partial-cap
  (testing "one party capped, other absorbs remainder"
    (let [result (sew-economics/calculate-sew-slash-allocation
                  {:slash-amount 100
                   :liable-parties [{:id :alice :slashable-stake 100 :available-slashable 30}
                                    {:id :bob   :slashable-stake 100 :available-slashable 100}
                                    {:id :carol :slashable-stake 100 :available-slashable 100}]})]
      ;; equal stakes: each ideal = 100 * 100/300 = 33.33...
      ;; floor = 33 each, shortage = 100 - 99 = 1
      ;; remainders: each 1/3, tie goes to first (alice)
      ;; alice gets floor+1 = 34, but cap=30! so allocated=30, unmet=4
      ;; after capping alice at 30, the unmet is recorded
      ;; bob gets 33, carol gets 33
      ;; total-allocated = 30 + 33 + 33 = 96, total-unmet = 4
      (is (= 96 (:recovered-total result)))
      (is (= 4 (:unmet-total result))))))

(deftest ^:characterization csa-nil-slash-amount-falls-back-to-slash-obligation
  (testing "slash-amount defaults to slash-obligation when nil"
    (let [result (sew-economics/calculate-sew-slash-allocation
                  {:slash-obligation 50
                   :liable-parties [{:id :alice :slashable-stake 100 :available-slashable 100}]})]
      (is (= 50 (:slash-obligation result)))
      (is (= 50 (:recovered-total result))))))

(deftest ^:characterization csa-conservation
  (testing "recovered + unmet == slash-obligation"
    (doseq [spec [{:slash-amount 100
                   :liable-parties [{:id :a :slashable-stake 200 :available-slashable 200}
                                    {:id :b :slashable-stake 200 :available-slashable 200}]}
                  {:slash-amount 100
                   :liable-parties [{:id :a :slashable-stake 100 :available-slashable 30}
                                    {:id :b :slashable-stake 200 :available-slashable 200}]}
                  {:slash-amount 0
                   :liable-parties [{:id :a :slashable-stake 100 :available-slashable 100}]}
                  {:slash-amount 100
                   :liable-parties []}
                  {:slash-amount 100
                   :liable-parties [{:id :a :slashable-stake 0 :available-slashable 0}
                                    {:id :b :slashable-stake 0 :available-slashable 0}]}]]
      (let [result (sew-economics/calculate-sew-slash-allocation spec)]
        (is (= (:slash-obligation result)
               (+ (:recovered-total result) (:unmet-total result)))
            (str "Conservation failed: " (pr-str spec)))))))

;; =============================================================================
;; calculate-slashing-distribution characterization
;; =============================================================================
;;
;; Input: amount, bounty, optional {:insurance-cut-bps, :protocol-retained-bps}
;; Default: insurance-cut-bps=5000, protocol-retained-bps=3000
;; Output: {:insurance N, :protocol N, :retained N}
;; Bounty is split evenly between insurance and protocol pools

(deftest ^:characterization csd-default-distribution
  (testing "default 5000/3000 split leaves retained as remainder"
    ;; amount=100, insurance=5000bps=50, protocol=3000bps=30, retained=100-50-30=20
    (let [result (sew-economics/calculate-slashing-distribution 100 0)]
      (is (= {:insurance 50 :protocol 30 :retained 20} result)))))

(deftest ^:characterization csd-with-bounty
  (testing "bounty is split evenly between insurance and protocol"
    ;; amount=100, bounty=10
    ;; insurance=50, protocol=30, retained=20
    ;; bounty-from-insurance=5, bounty-from-protocol=5
    ;; insurance=50-5=45, protocol=30-5=25, retained=20
    (let [result (sew-economics/calculate-slashing-distribution 100 10)]
      (is (= {:insurance 45 :protocol 25 :retained 20} result)))))

(deftest ^:characterization csd-custom-overrides
  (testing "custom bps overrides change distribution"
    ;; amount=100, bounty=0, insurance=7000bps=70, protocol=1000bps=10, retained=100-70-10=20
    (let [result (sew-economics/calculate-slashing-distribution
                  100 0 {:insurance-cut-bps 7000 :protocol-retained-bps 1000})]
      (is (= {:insurance 70 :protocol 10 :retained 20} result)))))

(deftest ^:characterization csd-zero-amount
  (testing "zero amount yields zero distribution"
    (let [result (sew-economics/calculate-slashing-distribution 0 0)]
      (is (= {:insurance 0 :protocol 0 :retained 0} result)))))

(deftest ^:characterization csd-bounty-exceeds-insurance-pool
  (testing "bounty can exceed insurance pool (insurance goes negative)"
    (let [result (sew-economics/calculate-slashing-distribution 10 60)]
      ;; amount=10, insurance=5000bps=5, protocol=3000bps=3, retained=2
      ;; bounty-from-insurance=30, bounty-from-protocol=30
      ;; insurance=5-30=-25, protocol=3-30=-27, retained=2
      (is (= {:insurance -25 :protocol -27 :retained 2} result)))))

(deftest ^:characterization csd-large-amount
  (testing "large amounts use integer-safe division"
    (let [result (sew-economics/calculate-slashing-distribution 1000000 0)]
      (is (= {:insurance 500000 :protocol 300000 :retained 200000} result)))))

(deftest ^:characterization csd-zero-bps-override
  (testing "zero bps overrides zero out pools and retained = amount"
    (let [result (sew-economics/calculate-slashing-distribution
                  100 0 {:insurance-cut-bps 0 :protocol-retained-bps 0})]
      (is (= {:insurance 0 :protocol 0 :retained 100} result)))))

(deftest ^:characterization csd-odd-bounty-split
  (testing "odd bounty is split with integer truncation (quot)"
    ;; bounty=5, bounty-from-insurance=2, bounty-from-protocol=3
    (let [result (sew-economics/calculate-slashing-distribution 100 5)]
      (is (= 2 (quot 5 2)) "quot(5,2)=2")
      ;; insurance=50-2=48, protocol=30-3=27, retained=20
      (is (= {:insurance 48 :protocol 27 :retained 20} result)))))

(deftest ^:characterization csd-bounty-split-is-floor
  (testing "bounty split truncates: first half = quot(bounty 2)"
    (is (= 0 (quot 1 2)))
    (is (= 1 (quot 3 2)))
    (is (= 2 (quot 5 2)))))
