(ns resolver-sim.grounded-amount-test
  "Tests for the cross-artifact grounded-amount projection contract."
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [resolver-sim.grounded-amount :as ga]))

(defn -main [& _]
  (run-tests 'resolver-sim.grounded-amount-test))

(deftest grounded-amount-projection
  (testing "produces the canonical projection shape"
    (is (= {:amount/value 100 :amount/token :usdc :amount/basis :deferred
            :amount/source-root "root"}
           (ga/grounded-amount 100 :usdc :deferred "root")))
    (is (= {:amount/value 100 :amount/token :usdc :amount/basis :deferred
            :amount/source-root "root" :amount/as-of-root "state"}
           (ga/grounded-amount 100 :usdc :deferred "root" :as-of-root "state")))))

(deftest grounded-amount-predicate
  (testing "a bare number or partial projection is not grounded"
    (is (false? (ga/grounded-amount? 100)))
    (is (false? (ga/grounded-amount? {:amount/value 100})))
    (is (false? (ga/grounded-amount? {:amount/value 100 :amount/token :usdc})))
    (is (true? (ga/grounded-amount?
                (ga/grounded-amount 100 :usdc :deferred "root"))))
    (is (true? (ga/grounded-amount?
                (ga/grounded-amount 100 :usdc :deferred "root" :as-of-root "s"))))))

(deftest grounded-amount-validation
  (testing "validate-grounded-amount! accepts a grounded projection"
    (is (= {:amount/value 100 :amount/token :usdc :amount/basis :deferred
            :amount/source-root "root"}
           (ga/validate-grounded-amount! (ga/grounded-amount 100 :usdc :deferred "root")))))
  (testing "rejects a bare number rather than silently accepting it"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"not a grounded amount projection"
                          (ga/validate-grounded-amount! 100)))))
