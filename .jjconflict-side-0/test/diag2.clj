(ns diag2
  (:require [resolver-sim.yield.modules.liquid-lending :as ll]
            [resolver-sim.yield.invariants :as inv]))

(def test-world
  {:yield/indices {:test-mod {"USDC" 1}}
   :yield/rates   {:test-mod {"USDC" 0.05}}
   :yield/risk    {:test-mod {"USDC" {:liquidity-mode :available :loss-mode :none}}}
   :yield/held-balances {"USDC" 1000000}
   :yield/module-status {:test-mod :active}
   :block-time 1000 :run/id "test-run" :execution/id "test-execution"
   :params {:scenario-id "test-scenario"}})

(def test-mod (ll/make-liquid-lending-module :test-mod))

(defn shared-withdrawal-world [owners available]
  (-> (reduce (fn [w owner]
                (ll/deposit w test-mod {:owner/id owner :amount 100 :token "USDC"}))
              test-world
              (sort owners))
      (assoc-in [:total-held :USDC] available)))

(println "=== Test 2 ===")
(def world2 (shared-withdrawal-world ["alice" "bob"] 150))
(def result2 (ll/withdraw-shared world2 test-mod {:owner-ids ["alice" "bob"] :token "USDC" :allocation-mode :pro-rata :effective-caps {"alice" 20 "bob" 100}}))
(println "total-held:" (pr-str (:total-held result2)))
(def p2 (->> (:yield/pro-rata-propagations result2) vals first))
(println "source-token:" (pr-str (get-in p2 [:source-account :token])))
(println "application-token:" (pr-str (get-in (->> (:yield/applied-pro-rata-propagations result2) vals first) [:source-account :token])))
(def r2 (inv/check-pro-rata-accounting-reconciles result2))
(println "holds?:" (:holds? r2))
(run! #(println %) (:violations r2))

(println "=== Test 3 ===")
(def world3 (-> (reduce (fn [w owner]
                          (ll/deposit w test-mod {:owner/id owner :amount 10 :token "USDC"}))
                        (assoc test-world :yield/held-balances {"USDC" 10})
                        ["alice" "bob" "carol"])
                (assoc :total-held {:USDC 10})))
(def result3 (ll/withdraw-shared world3 test-mod {:owner-ids ["carol" "bob" "alice"] :token "USDC" :allocation-mode :pro-rata}))
(println "total-held:" (pr-str (:total-held result3)))
(def r3 (inv/check-pro-rata-accounting-reconciles result3))
(println "holds?:" (:holds? r3))
(run! #(println %) (:violations r3))
