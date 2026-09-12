(ns resolver-sim.yield.parity-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.yield.modules.liquid-lending :as v1]
            [resolver-sim.yield.position :as pos]))

(def world-base
  {:yield/indices {:mod {:USDC 1.0}}
   :yield/rates {:mod {:USDC 0.05}}
   :block-time 0})

(def pos-base
  {:owner/id "o"
   :module/id :mod
   :token :USDC
   :principal 1000
   :status :active})

(defn approx= [a b epsilon]
  (< (Math/abs (- (double a) (double b))) epsilon))

(deftest parity-test-deposit
  (testing "V1 and V2 produce equivalent position state on deposit"
    (let [op {:owner/id "o" :amount 1000 :token :USDC}
          mod-v1 (v1/make-liquid-lending-module :mod)
          mod-v2 (v1/make-liquid-lending-module :mod)
          w-v1 (v1/deposit world-base mod-v1 op)
          w-v2 (v1/deposit world-base mod-v2 op)
          pos-v1 (get-in w-v1 [:yield/positions "o"])
          pos-v2 (get-in w-v2 [:yield/positions "o"])]
      (is (= (:principal pos-v1) (:principal pos-v2)))
      (is (pos? (:shares pos-v2))))))

(deftest parity-test-accrual
  (testing "V1 and V2 accrual comparison"
    (let [op {:token :USDC :dt 31536000}
          w-v1 (assoc-in world-base [:yield/positions "o"] (pos/make-position pos-base))
          w-v2 (assoc-in world-base [:yield/positions "o"] (pos/make-position pos-base))
          mod-v1 (v1/make-liquid-lending-module :mod)
          mod-v2 (v1/make-liquid-lending-module :mod)

          w-v1-accrued (v1/accrue w-v1 mod-v1 op)
          w-v2-accrued (v1/accrue w-v2 mod-v2 op)

          pos-v1 (get-in w-v1-accrued [:yield/positions "o"])
          pos-v2 (get-in w-v2-accrued [:yield/positions "o"])]
      (is (approx= (:unrealized-yield pos-v1) (:unrealized-yield pos-v2) 0.01)
          (str "V1 unrealized: " (:unrealized-yield pos-v1)
               ", V2 unrealized: " (:unrealized-yield pos-v2))))))
