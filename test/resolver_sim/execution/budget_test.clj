(ns resolver-sim.execution.budget-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.execution.budget :as budget]
            [resolver-sim.economics.payoffs :as payoffs]))

(defn- bubble-max
  [track max-in-flight]
  (fn [x]
    (let [n (swap! track inc)]
      (swap! max-in-flight max n)
      (Thread/sleep 20)
      (swap! track dec)
      x)))

(deftest unbounded-budget-passes-requested-parallelism-through
  (testing "without a budget bound, borrowing reports the requested parallelism"
    (is (= 6 (budget/borrowed-parallelism 6))))
  (testing "and a supplied vector maps in stable order with full parallelism"
    (let [cur (atom 0) mx (atom 0)
          values (vec (range 12))]
      (is (= values (#'payoffs/ordered-detached-mapv 8 identity values)))
      (is (<= @mx 8)))))

(deftest budget-acquire-many-never-exceeds-available-permits
  (budget/with-execution-budget 4
    (testing "a borrower cannot exceed the total budget even when requesting more"
      (let [outer (budget/acquire-permit!)]
        ;; outer consumes 1 -> 3 spare
        (is (= 3 (budget/acquire-many! 8)))
        (is (zero? (budget/available)) "no spare remains after the borrow")
        (budget/release-many! 3)
        (budget/release-permit! outer)
        (is (= 4 (budget/available)))))))

(deftest borrowed-parallelism-reflects-spare-capacity
  (budget/with-execution-budget 4
    (let [outer (budget/acquire-permit!)]
      (testing "borrows the available spare capacity"
        (is (= 3 (budget/borrowed-parallelism 8))))
      (testing "collapses to serially-zero parallelism when no spare remains"
        (budget/acquire-many! 3)
        (is (zero? (budget/available)))
        (is (zero? (budget/borrowed-parallelism 4))))
      (budget/release-many! 3)
      (budget/release-permit! outer))))

(deftest claimant-borrows-spare-capacity-but-remains-bounded-and-ordered
  (budget/with-execution-budget 2
    (testing "inner claimant work borrows at most the spare capacity and preserves order"
      (let [cur (atom 0) mx (atom 0)
            values (vec (range 40))
            f (bubble-max cur mx)
            out (#'payoffs/ordered-detached-mapv 8 f values)]
        (is (= values out) "source order is preserved regardless of borrowing")
        (is (<= @mx 2) "claimant concurrency never exceeded the shared budget")
        (is (= 2 (budget/available)) "borrowed permits are released after the batch")))))

(deftest claimant-serializes-when-shared-budget-is-saturated
  (budget/with-execution-budget 2
    (let [held (budget/acquire-many! 2) ; two outer workers hold both permits
          cur (atom 0) mx (atom 0)
          f (bubble-max cur mx)]
      (try
        (testing "with no spare capacity the claimant runs serially"
          (is (= (vec (range 30))
                 (#'payoffs/ordered-detached-mapv 8 f (vec (range 30)))))
          (is (= 1 @mx) "claimant ran serially when the budget had no spare capacity"))
        (finally
          (budget/release-many! held)))
      (is (= 2 (budget/available)) "released permits restock the budget"))))

(deftest found-tenancy-excludes-permits-from-canonical-allocation
  (testing "the budget is never consulted by the serial reference allocation path"
    (budget/with-execution-budget 2
      (let [cur (atom 0) mx (atom 0)
            f (bubble-max cur mx)
            values (vec (range 6))]
        (is (= values (#'payoffs/ordered-detached-mapv 1 f values)))
        (is (= 1 @mx) "parallelism 1 is always serial, budget or not")))))