(ns resolver-sim.yield.invariants-hardening-test
  "Targeted unit tests for yield invariants that lack dedicated coverage.
   These tests construct minimal world states and verify invariant pass/fail
   behavior, avoiding the namespace issues in invariants_test.clj."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.yield.invariants :as inv]
            [resolver-sim.yield.risk :as risk]))

;; ── partial-liquidity-principal ─────────────────────────────────────────

(deftest partial-liquidity-principal-passes-on-no-haircut
  (testing "partial-liquidity-principal passes when haircut is 0 during partial liquidity"
    (let [world {:yield/positions {"u1" {:module/id :m :token :t :status :unwinding
                                         :principal 1000 :realized-yield 0 :unrealized-yield 0
                                         :shortfall {:fulfilled-amount 800 :deferred-amount 200
                                                     :haircut-amount 0 :basis-amount 1000}}}
                 :yield/risk {:m {:t {:liquidity-mode :shortfall
                                      :failure-modes [:partial-liquidity]}}}}
          risk (get-in world [:yield/risk :m :t])]
      (is (contains? (risk/normalize-failure-modes (:failure-modes risk)) :partial-liquidity))
      (is (inv/holds? :yield/partial-liquidity-principal world)))))

(deftest partial-liquidity-principal-fails-on-haircut
  (testing "partial-liquidity-principal fails when haircut > 0 during partial liquidity"
    (let [world {:yield/positions {"u1" {:module/id :m :token :t :status :unwinding
                                         :principal 1000 :realized-yield 0 :unrealized-yield 0
                                         :shortfall {:fulfilled-amount 800 :deferred-amount 100
                                                     :haircut-amount 100 :basis-amount 1000}}}
                 :yield/risk {:m {:t {:liquidity-mode :shortfall
                                      :failure-modes [:partial-liquidity]}}}}
          risk (get-in world [:yield/risk :m :t])]
      (is (contains? (risk/normalize-failure-modes (:failure-modes risk)) :partial-liquidity))
      (is (not (inv/holds? :yield/partial-liquidity-principal world))))))

(deftest partial-liquidity-principal-passes-when-not-partial
  (testing "partial-liquidity-principal passes when not in partial-liquidity mode"
    (let [world {:yield/positions {"u1" {:module/id :m :token :t :status :active
                                         :principal 1000}}}
          risk (get-in world [:yield/risk :m :t] {})]
      (is (not (contains? (risk/normalize-failure-modes (:failure-modes risk)) :partial-liquidity)))
      (is (inv/holds? :yield/partial-liquidity-principal world)))))

;; ── value-conservation ─────────────────────────────────────────────────

(deftest value-conservation-passes-on-valid-shortfall
  (testing "value-conservation passes when deferred + haircut <= principal + unrealized"
    (let [world {:yield/positions {"u1" {:module/id :m :token :t :status :unwinding
                                         :principal 1000 :realized-yield 0 :unrealized-yield 0
                                         :shortfall {:deferred-amount 300 :haircut-amount 100
                                                     :fulfilled-amount 600 :basis-amount 1000}}}}]
      (is (inv/holds? :yield/value-conservation world)))))

(deftest value-conservation-fails-on-excessive-shortfall
  (testing "value-conservation fails when deferred + haircut exceeds principal + unrealized"
    (let [world {:yield/positions {"u1" {:module/id :m :token :t :status :unwinding
                                         :principal 100 :realized-yield 0 :unrealized-yield 0
                                         :shortfall {:deferred-amount 300 :haircut-amount 100
                                                     :fulfilled-amount 600 :basis-amount 1000}}}}]
      (is (not (inv/holds? :yield/value-conservation world))))))

(deftest value-conservation-passes-on-negative-unrealized
  (testing "value-conservation handles negative unrealized (mark-to-market)"
    (let [world {:yield/positions {"u1" {:module/id :m :token :t :status :unwinding
                                         :principal 1000 :realized-yield 0 :unrealized-yield -100
                                         :shortfall {:deferred-amount 300 :haircut-amount 100
                                                     :fulfilled-amount 600 :basis-amount 1000}}}}]
      (is (inv/holds? :yield/value-conservation world)))))

(deftest value-conservation-passes-when-no-shortfall
  (testing "value-conservation passes trivially when no shortfall exists"
    (let [world {:yield/positions {"u1" {:module/id :m :token :t :status :active
                                         :principal 1000}}}]
      (is (inv/holds? :yield/value-conservation world)))))

;; ── deferred-reclaim ───────────────────────────────────────────────────

(deftest deferred-reclaim-passes-on-clean-withdrawn
  (testing "deferred-reclaim passes on withdrawn position with no shortfall"
    (let [world {:yield/positions {"u1" {:module/id :m :token :t :status :withdrawn
                                         :principal 0 :realized-yield 0 :unrealized-yield 0
                                         :reclaimed-amount 100}}}]
      (is (inv/holds? :yield/deferred-reclaim world)))))

(deftest deferred-reclaim-fails-on-withdrawn-with-shortfall
  (testing "deferred-reclaim fails when withdrawn position still has shortfall"
    (let [world {:yield/positions {"u1" {:module/id :m :token :t :status :withdrawn
                                         :principal 0 :realized-yield 0 :unrealized-yield 0
                                         :reclaimed-amount 100
                                         :shortfall {:basis-amount 1000 :fulfilled-amount 600
                                                     :deferred-amount 400 :haircut-amount 0}}}}]
      (is (not (inv/holds? :yield/deferred-reclaim world))))))

(deftest deferred-reclaim-passes-on-active-position
  (testing "deferred-reclaim passes for non-withdrawn positions"
    (let [world {:yield/positions {"u1" {:module/id :m :token :t :status :active
                                         :principal 1000}}}]
      (is (inv/holds? :yield/deferred-reclaim world)))))

(deftest deferred-reclaim-fails-on-negative-reclaimed
  (testing "deferred-reclaim fails when reclaimed-amount is negative"
    (let [world {:yield/positions {"u1" {:module/id :m :token :t :status :withdrawn
                                         :principal 0 :realized-yield 0 :unrealized-yield 0
                                         :reclaimed-amount -50}}}]
      (is (not (inv/holds? :yield/deferred-reclaim world))))))

;; ── aggregate-shortfall-cap ─────────────────────────────────────────────

(deftest aggregate-shortfall-cap-passes-on-valid
  (testing "aggregate shortfall cap passes when basis <= value"
    (let [world {:yield/positions {"u1" {:module/id :m :token :t :status :unwinding
                                         :principal 1000 :realized-yield 0 :unrealized-yield 0
                                         :shortfall {:basis-amount 600 :fulfilled-amount 400
                                                     :deferred-amount 200 :haircut-amount 0}}
                                   "u2" {:module/id :m :token :t :status :unwinding
                                         :principal 2000 :realized-yield 0 :unrealized-yield 0
                                         :shortfall {:basis-amount 800 :fulfilled-amount 600
                                                     :deferred-amount 200 :haircut-amount 0}}}}]
      (is (inv/holds? :yield/aggregate-shortfall-cap world)))))

(deftest aggregate-shortfall-cap-fails-on-overage
  (testing "aggregate shortfall cap fails when basis exceeds value"
    (let [world {:yield/positions {"u1" {:module/id :m :token :t :status :unwinding
                                         :principal 100 :realized-yield 0 :unrealized-yield 0
                                         :shortfall {:basis-amount 600 :fulfilled-amount 400
                                                     :deferred-amount 200 :haircut-amount 0}}
                                   "u2" {:module/id :m :token :t :status :unwinding
                                         :principal 200 :realized-yield 0 :unrealized-yield 0
                                         :shortfall {:basis-amount 800 :fulfilled-amount 600
                                                     :deferred-amount 200 :haircut-amount 0}}}}]
      (is (not (inv/holds? :yield/aggregate-shortfall-cap world))))))

(deftest aggregate-shortfall-cap-passes-on-empty-positions
  (testing "aggregate shortfall cap passes with no positions"
    (let [world {:yield/positions {}}]
      (is (inv/holds? :yield/aggregate-shortfall-cap world)))))

(deftest aggregate-shortfall-cap-separates-modules
  (testing "aggregate shortfall cap separates different module/token pairs"
    (let [world {:yield/positions {"u1" {:module/id :m1 :token :t1 :status :unwinding
                                         :principal 1000 :realized-yield 0 :unrealized-yield 0
                                         :shortfall {:basis-amount 600 :fulfilled-amount 400
                                                     :deferred-amount 200 :haircut-amount 0}}
                                   "u2" {:module/id :m2 :token :t2 :status :unwinding
                                         :principal 2000 :realized-yield 0 :unrealized-yield 0
                                         :shortfall {:basis-amount 500 :fulfilled-amount 400
                                                     :deferred-amount 100 :haircut-amount 0}}}}]
      (is (inv/holds? :yield/aggregate-shortfall-cap world)))))

;; ── aggregate-shortfall ────────────────────────────────────────────────────

(deftest aggregate-shortfall-passes-on-valid
  (testing "aggregate shortfall passes when total basis <= total value"
    (let [world {:yield/positions {"u1" {:module/id :m :token :t :status :unwinding
                                         :principal 1000 :realized-yield 0 :unrealized-yield 0
                                         :shortfall {:basis-amount 600 :fulfilled-amount 400
                                                     :deferred-amount 200 :haircut-amount 0}}
                                   "u2" {:module/id :m :token :t :status :unwinding
                                         :principal 2000 :realized-yield 0 :unrealized-yield 0
                                         :shortfall {:basis-amount 800 :fulfilled-amount 600
                                                     :deferred-amount 200 :haircut-amount 0}}}}]
      (is (inv/holds? :yield/aggregate-shortfall world)))))

(deftest aggregate-shortfall-fails-on-overage
  (testing "aggregate shortfall fails when total basis exceeds total value"
    (let [world {:yield/positions {"u1" {:module/id :m :token :t :status :unwinding
                                         :principal 100 :realized-yield 0 :unrealized-yield 0
                                         :shortfall {:basis-amount 600 :fulfilled-amount 400
                                                     :deferred-amount 200 :haircut-amount 0}}
                                   "u2" {:module/id :m :token :t :status :unwinding
                                         :principal 200 :realized-yield 0 :unrealized-yield 0
                                         :shortfall {:basis-amount 800 :fulfilled-amount 600
                                                     :deferred-amount 200 :haircut-amount 0}}}}]
      (is (not (inv/holds? :yield/aggregate-shortfall world))))))

(deftest aggregate-shortfall-passes-on-empty-positions
  (testing "aggregate shortfall passes with no positions"
    (let [world {:yield/positions {}}]
      (is (inv/holds? :yield/aggregate-shortfall world)))))

(deftest aggregate-shortfall-separates-modules
  (testing "aggregate shortfall separates different module/token pairs"
    (let [world {:yield/positions {"u1" {:module/id :m1 :token :t1 :status :unwinding
                                         :principal 1000 :realized-yield 0 :unrealized-yield 0
                                         :shortfall {:basis-amount 600 :fulfilled-amount 400
                                                     :deferred-amount 200 :haircut-amount 0}}
                                   "u2" {:module/id :m2 :token :t2 :status :unwinding
                                         :principal 2000 :realized-yield 0 :unrealized-yield 0
                                         :shortfall {:basis-amount 500 :fulfilled-amount 400
                                                     :deferred-amount 100 :haircut-amount 0}}}}]
      (is (inv/holds? :yield/aggregate-shortfall world)))))

;; ── aggregate ──────────────────────────────────────────────────────────────

(deftest aggregate-passes-on-valid
  (testing "aggregate consistency passes when splits reconcile and basis <= value"
    (let [world {:yield/positions {"u1" {:module/id :m :token :t :status :unwinding
                                         :principal 1000 :realized-yield 0 :unrealized-yield 0
                                         :shortfall {:basis-amount 600 :fulfilled-amount 400
                                                     :deferred-amount 200 :haircut-amount 0}}}}]
      (is (inv/holds? :yield/aggregate world)))))

(deftest aggregate-fails-on-unbalanced-splits
  (testing "aggregate consistency fails when shortfall splits do not reconcile to basis"
    (let [world {:yield/positions {"u1" {:module/id :m :token :t :status :unwinding
                                         :principal 1000 :realized-yield 0 :unrealized-yield 0
                                         :shortfall {:basis-amount 600 :fulfilled-amount 400
                                                     :deferred-amount 100 :haircut-amount 0}}}}]
      (is (not (inv/holds? :yield/aggregate world))))))

(deftest aggregate-fails-on-shortfall-over-value
  (testing "aggregate consistency fails when shortfall exceeds available value"
    (let [world {:yield/positions {"u1" {:module/id :m :token :t :status :unwinding
                                         :principal 100 :realized-yield 0 :unrealized-yield 0
                                         :shortfall {:basis-amount 600 :fulfilled-amount 400
                                                     :deferred-amount 200 :haircut-amount 0}}}}]
      (is (not (inv/holds? :yield/aggregate world))))))

(deftest aggregate-passes-on-empty-positions
  (testing "aggregate consistency passes with no positions"
    (let [world {:yield/positions {}}]
      (is (inv/holds? :yield/aggregate world)))))

(deftest aggregate-separates-modules
  (testing "aggregate consistency separates different module/token pairs"
    (let [world {:yield/positions {"u1" {:module/id :m1 :token :t1 :status :unwinding
                                         :principal 1000 :realized-yield 0 :unrealized-yield 0
                                         :shortfall {:basis-amount 600 :fulfilled-amount 400
                                                     :deferred-amount 200 :haircut-amount 0}}
                                   "u2" {:module/id :m2 :token :t2 :status :unwinding
                                         :principal 2000 :realized-yield 0 :unrealized-yield 0
                                         :shortfall {:basis-amount 500 :fulfilled-amount 400
                                                     :deferred-amount 100 :haircut-amount 0}}}}]
      (is (inv/holds? :yield/aggregate world)))))
