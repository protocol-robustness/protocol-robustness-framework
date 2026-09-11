(ns resolver-sim.lab.validation-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.lab.validation :as validation]))

(def withdrawal
  {:experiment "withdrawal-constrained-liquidity.v1"
   :parameters {:available-liquidity 1000
                :alice-requested 500
                :bob-requested 500
                :carol-requested 400
                :mechanism "pro-rata"
                :rounding-policy "largest-remainder"}})

(deftest valid-inputs-accepted
  (is (:ok? (validation/validate-request withdrawal)))
  (is (= 1000 (get-in (validation/validate-request withdrawal)
                      [:parameters :available-liquidity]))))

(deftest numeric-strings-coerced
  (is (:ok? (validation/validate-request
             (assoc-in withdrawal [:parameters :available-liquidity] "1000")))))

(deftest missing-required-parameter-rejected
  ;; insolvency-after-loss declares :custody and :recognized-loss without
  ;; defaults, so omitting them must be rejected.
  (is (not (:ok? (validation/validate-request
                  {:experiment "insolvency-after-loss.v1"
                   :parameters {:recognized-loss 0}}))))
  (is (not (:ok? (validation/validate-request
                  {:experiment "insolvency-after-loss.v1"
                   :parameters {:custody 1000}})))))

(deftest unknown-parameter-rejected
  (is (not (:ok? (validation/validate-request
                  (assoc-in withdrawal [:parameters :not-a-parameter] 5)))))
  (is (not (:ok? (validation/validate-request
                  (assoc-in withdrawal [:parameters :command] "rm -rf /"))))))

(deftest wrong-type-rejected
  (is (not (:ok? (validation/validate-request
                  (assoc-in withdrawal [:parameters :available-liquidity] "many")))))
  (is (not (:ok? (validation/validate-request
                  (assoc-in withdrawal [:parameters :mechanism] 7))))))

(deftest out-of-bounds-rejected
  (is (not (:ok? (validation/validate-request
                  (assoc-in withdrawal [:parameters :available-liquidity] -1)))))
  (is (not (:ok? (validation/validate-request
                  (assoc-in withdrawal [:parameters :available-liquidity]
                            100000000000)))))
  (is (not (:ok? (validation/validate-request
                  (assoc-in withdrawal [:parameters :mechanism] "instant-payout"))))))

(deftest oversized-request-rejected
  (is (not (:ok? (validation/validate-request
                  {:experiment "withdrawal-constrained-liquidity.v1"
                   :parameters (into {}
                                     (for [i (range 100)]
                                       [(keyword (str "p" i)) i]))}))))
  (is (not (:ok? (validation/validate-request {:experiment 42 :parameters {}}))))
  (is (not (:ok? (validation/validate-request "not-a-map")))))

(deftest missing-experiment-rejected
  (is (not (:ok? (validation/validate-request {:parameters {}}))))
  (is (not (:ok? (validation/validate-request {:experiment "does-not-exist.v1"
                                               :parameters {}})))))

(deftest optional-parameters-allow-nil
  (is (:ok? (validation/validate-request
             {:experiment "insolvency-after-loss.v1"
              :parameters {:custody 1000 :recognized-loss 0}}))))

(deftest defaults-applied
  (let [{:keys [ok? parameters]} (validation/validate-request withdrawal)]
    (is ok?)
    (is (= "pro-rata" (:mechanism parameters)))
    (is (= "largest-remainder" (:rounding-policy parameters)))))
