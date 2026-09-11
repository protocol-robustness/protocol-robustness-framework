(ns resolver-sim.stochastic.evidence-costs-prorata-test
  "Unit tests for ec/prorata-dispute-load.

   ## What is under test
   `prorata-dispute-load` distributes a total epoch dispute count across
   resolvers proportionally by effort-budget (capacity weight) using the
   Hare-quota / largest-remainder method.

   ## Evidence mapping
   Each test maps to one or more of the formal guarantees documented in
   pro_rata_proportional_math_spec.md and in the function docstring:
     G1 — Conservation:  Σ alloc_i = total-disputes
     G2 — Quota Rule:    floor(q_i) ≤ alloc_i ≤ ceil(q_i)
             where q_i = total × (budget_i / Σ budgets)
     G3 — Non-negative: alloc_i ≥ 0 for all i
     G4 — Completeness: every resolver-id appears in the result map
     G5 — Determinism:  same inputs → same output (no hidden RNG)"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [resolver-sim.properties.harness :as pbh]
            [resolver-sim.stochastic.evidence-costs :as ec]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- quota
  "Ideal (exact) share for resolver i given budgets and total disputes."
  [budget-i total-budget total-disputes]
  (* total-disputes (/ (double budget-i) (double total-budget))))

(defn- check-quota-rule
  "Returns true iff every allocation satisfies floor(q_i) ≤ alloc_i ≤ ceil(q_i)."
  [alloc-map resolver-budgets total-budget total-disputes]
  (every? (fn [[id alloc]]
            (let [q  (quota (get resolver-budgets id) total-budget total-disputes)
                  lo (long (Math/floor q))
                  hi (long (Math/ceil  q))]
              (<= lo alloc hi)))
          alloc-map))

;; ---------------------------------------------------------------------------
;; G1: Conservation
;; ---------------------------------------------------------------------------

(deftest conservation-uniform-budgets
  "G1 — sum of allocations must equal total-disputes (uniform budget case)."
  (testing "10 resolvers, equal budgets, 100 disputes"
    (let [ids    (map #(str "r-" %) (range 10))
          budgets (zipmap ids (repeat 100.0))
          result  (ec/prorata-dispute-load ids budgets 100)]
      (is (= 100 (reduce + 0 (vals result))) "Conservation: Σ alloc = 100")))

  (testing "7 resolvers, equal budgets, 100 disputes — non-divisible remainder test"
    (let [ids    (map #(str "r-" %) (range 7))
          budgets (zipmap ids (repeat 100.0))
          result  (ec/prorata-dispute-load ids budgets 100)]
      (is (= 100 (reduce + 0 (vals result))) "Conservation holds with non-divisible total"))))

(deftest conservation-heterogeneous-budgets
  "G1 — conservation must hold even with very different budgets."
  (let [ids    ["heavy" "light" "medium"]
        budgets {"heavy" 200.0 "light" 50.0 "medium" 100.0}
        result  (ec/prorata-dispute-load ids budgets 300)]
    (is (= 300 (reduce + 0 (vals result))) "Conservation with heterogeneous budgets")))

(deftest conservation-prime-total
  "G1 — conservation with a prime total forces non-trivial remainders."
  (let [ids    ["a" "b" "c"]
        budgets {"a" 1.0 "b" 1.0 "c" 1.0}
        result  (ec/prorata-dispute-load ids budgets 97)]
    (is (= 97 (reduce + 0 (vals result))))))

;; ---------------------------------------------------------------------------
;; G2: Quota Rule
;; ---------------------------------------------------------------------------

(deftest quota-rule-uniform
  "G2 — each alloc must be floor(q_i) or ceil(q_i), uniform budgets."
  (let [ids    (map #(str "r-" %) (range 5))
        budgets (zipmap ids (repeat 100.0))
        total   13
        result  (ec/prorata-dispute-load ids budgets total)
        total-b 500.0]
    (is (check-quota-rule result budgets total-b total)
        "Quota rule satisfied for all 5 resolvers")))

(deftest quota-rule-heterogeneous
  "G2 — quota rule satisfied under heterogeneous budgets."
  (let [ids     ["a" "b" "c" "d"]
        budgets {"a" 300.0 "b" 100.0 "c" 50.0 "d" 50.0}
        total   100
        total-b (reduce + (vals budgets))
        result  (ec/prorata-dispute-load ids budgets total)]
    (is (check-quota-rule result budgets total-b total))))

;; ---------------------------------------------------------------------------
;; G3: Non-negativity
;; ---------------------------------------------------------------------------

(deftest non-negative-allocations
  "G3 — no resolver receives a negative dispute count, even with a 0-budget entry."
  (let [ids    ["x" "y" "z"]
        budgets {"x" 0.0 "y" 100.0 "z" 50.0}
        result  (ec/prorata-dispute-load ids budgets 50)]
    (is (every? #(>= % 0) (vals result)))))

;; ---------------------------------------------------------------------------
;; G4: Completeness
;; ---------------------------------------------------------------------------

(deftest all-ids-present
  "G4 — every resolver-id must appear in the result, including zero-budget ones."
  (let [ids    ["r1" "r2" "r3"]
        budgets {"r1" 100.0 "r2" 0.0 "r3" 50.0}
        result  (ec/prorata-dispute-load ids budgets 30)]
    (is (= (set ids) (set (keys result))) "All IDs present in result")))

;; ---------------------------------------------------------------------------
;; G5: Determinism
;; ---------------------------------------------------------------------------

(deftest determinism
  "G5 — same inputs always produce the same output (no hidden RNG)."
  (let [ids    ["a" "b" "c"]
        budgets {"a" 100.0 "b" 200.0 "c" 150.0}
        r1 (ec/prorata-dispute-load ids budgets 77)
        r2 (ec/prorata-dispute-load ids budgets 77)]
    (is (= r1 r2) "Deterministic across repeated calls")))

;; ---------------------------------------------------------------------------
;; Proportionality
;; ---------------------------------------------------------------------------

(deftest proportional-allocation-exact
  "When total-disputes is exactly divisible by equal budgets,
   every resolver gets exactly total/n — no remainder allocation needed."
  (let [ids    ["a" "b" "c" "d"]
        budgets (zipmap ids (repeat 100.0))
        result  (ec/prorata-dispute-load ids budgets 100)]
    (is (= {"a" 25 "b" 25 "c" 25 "d" 25} result)
        "Equal budgets, divisible total → equal integer shares")))

(deftest proportional-allocation-2x-budget
  "A resolver with twice the budget should receive ~twice the disputes,
   subject to quota-rule rounding tolerance (at most 1 off ideal)."
  (let [ids    ["big" "small"]
        budgets {"big" 200.0 "small" 100.0}
        total   90
        result  (ec/prorata-dispute-load ids budgets total)
        big-d   (get result "big")
        small-d (get result "small")]
    ;; Ideal: big=60, small=30
    (is (= 90 (+ big-d small-d)) "Conservation")
    (is (<= 60 big-d 61)     "Big resolver gets ~2× disputes: [60,61]")
    (is (<= 29 small-d 30)   "Small resolver gets ~1× disputes: [29,30]")))

(deftest proportional-3-way-exact
  "Three resolvers with budgets 3:2:1 and a divisible total → exact ratio."
  (let [ids    ["a" "b" "c"]
        budgets {"a" 300.0 "b" 200.0 "c" 100.0}
        total   60
        result  (ec/prorata-dispute-load ids budgets total)]
    ;; Ideal: a=30, b=20, c=10 (total-budget=600, exact)
    (is (= {"a" 30 "b" 20 "c" 10} result) "Exact 3:2:1 ratio")))

;; ---------------------------------------------------------------------------
;; Edge cases
;; ---------------------------------------------------------------------------

(deftest zero-total-disputes
  "When total-disputes=0, all resolvers receive 0 (trivial conservation)."
  (let [ids    ["r1" "r2"]
        budgets {"r1" 100.0 "r2" 200.0}
        result  (ec/prorata-dispute-load ids budgets 0)]
    (is (= {"r1" 0 "r2" 0} result))
    (is (= 0 (reduce + 0 (vals result))))))

(deftest single-resolver-receives-all
  "Single resolver receives all disputes."
  (let [ids    ["solo"]
        budgets {"solo" 100.0}
        result  (ec/prorata-dispute-load ids budgets 47)]
    (is (= {"solo" 47} result))))

(deftest empty-resolver-list
  "Empty resolver list returns empty map."
  (let [result (ec/prorata-dispute-load [] {} 100)]
    (is (= {} result))))

(deftest fallback-uniform-when-all-weights-zero
  "When all budgets are 0 (no capacity info), falls back to uniform.
   Conservation must still hold and each alloc ∈ {floor, ceil} of total/n."
  (let [ids    ["a" "b" "c"]
        budgets {"a" 0.0 "b" 0.0 "c" 0.0}
        total   10
        result  (ec/prorata-dispute-load ids budgets total (ec/epoch-effort-budget))]
    (is (= total (reduce + 0 (vals result))) "Conservation in fallback-uniform mode")
    (is (every? #(<= 3 % 4) (vals result))  "Uniform: each gets floor(10/3)=3 or ceil=4")))

(deftest missing-budgets-use-default
  "Resolvers absent from the budget map use the default-budget.
   Two resolvers with identical defaults → equal allocation."
  (let [ids    ["x" "y"]
        budgets {}   ;; all missing → fall back to default
        total   100
        result  (ec/prorata-dispute-load ids budgets total (ec/epoch-effort-budget))]
    (is (= 100 (reduce + 0 (vals result))) "Conservation")
    (is (= {"x" 50 "y" 50} result)         "Identical defaults → equal shares")))

;; ---------------------------------------------------------------------------
;; Rational Resolver Threshold: integration smoke test
;; ---------------------------------------------------------------------------

(deftest rational-threshold-effort-equalises-across-resolvers
  "When two resolvers have different budgets, they experience *different*
   dispute counts but nearly *equal* effort-per-dispute — the key property
   the rational threshold model needs.

   Scenario: 100 total disputes, resolver A budget=200, B budget=100.
   A gets ~67 disputes → effort-per-dispute ≈ 200/67 ≈ 2.99
   B gets ~33 disputes → effort-per-dispute ≈ 100/33 ≈ 3.03

   Both classify as :heavy load (between partial-verification and minimal-check
   thresholds), giving consistent threshold analysis across the pool."
  (let [ids    ["big" "small"]
        budgets {"big" 200.0 "small" 100.0}
        total   100
        result  (ec/prorata-dispute-load ids budgets total)
        big-d   (get result "big")
        small-d (get result "small")]
    (is (> big-d small-d) "Higher-capacity resolver handles more disputes")
    (let [effort-a (/ 200.0 big-d)
          effort-b (/ 100.0 small-d)]
      (is (< (Math/abs (- effort-a effort-b)) 0.2)
          "effort-per-dispute is nearly equal — market equilibrium preserved"))))

;; ---------------------------------------------------------------------------
;; Generative G1–G5 coverage
;; ---------------------------------------------------------------------------

(defn- valid-prorata-input-gen
  "Generate {:resolver-ids ids :budgets budget-map :dispute-count n}
   with unique IDs, budgets in [0, 500], and disputes in [0, 1000].
   Uses integer budgets converted to doubles to avoid NaN edges in
   test.check gen/double*."
  []
  (gen/bind
   (gen/tuple
    (gen/choose 1 20)        ;; resolver count
    (gen/choose 0 1000))     ;; dispute count
   (fn [[n dispute-count]]
     (let [ids (mapv #(keyword (str "r-" %)) (range n))]
       (gen/fmap
        (fn [budget-vals]
          {:resolver-ids ids
           :budgets (zipmap ids (map double budget-vals))
           :dispute-count dispute-count})
        (gen/vector (gen/choose 0 500) n))))))

(deftest prorata-satisfies-all-guarantees
  "Generative verification of formal guarantees G1–G5 across random valid inputs.
   Covers edge cases (zero disputes, zero budgets, all-zero budgets, overload)
   through generator ranges rather than individual examples."
  (let [prop (prop/for-all [{:keys [resolver-ids budgets dispute-count]}
                            (valid-prorata-input-gen)]
                           (let [alloc (ec/prorata-dispute-load resolver-ids budgets dispute-count)
                                 total-budget (reduce + (vals budgets))
                                 same (ec/prorata-dispute-load resolver-ids budgets dispute-count)]
                             (and
                    ;; G1 — Conservation
                              (= dispute-count (reduce + 0 (vals alloc)))
                    ;; G2 — Quota rule (skip when total-budget = 0 — fallback uniform)
                              (or (zero? total-budget)
                                  (check-quota-rule alloc budgets total-budget dispute-count))
                    ;; G3 — Non-negative
                              (every? #(>= % 0) (vals alloc))
                    ;; G4 — Completeness: every resolver-id appears in the result
                              (= (set resolver-ids) (set (keys alloc)))
                    ;; G5 — Determinism
                              (= alloc same))))
        res (tc/quick-check (pbh/trial-count pbh/review-trials) prop)]
    (is (:pass? res) (pbh/report-failure res))))
