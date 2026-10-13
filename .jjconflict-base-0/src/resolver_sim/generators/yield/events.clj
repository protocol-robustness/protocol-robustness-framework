(ns resolver-sim.generators.yield.events
  (:require [clojure.test.check.generators :as gen]
            [resolver-sim.generators.yield.core :as ycore]))

(def gen-yield-op-type
  (gen/elements [:yield/deposit :yield/accrue :yield/withdraw
                 :set-yield-risk :yield/claim-deferred]))

(def gen-dt-seconds
  (gen/elements [86400 604800 2592000 31536000]))

(def gen-deposit-amount
  (gen/large-integer* {:min 1000 :max 500000}))

(defn- op->action [op-type]
  (case op-type
    :yield/deposit       "yield_deposit"
    :yield/accrue        "yield_accrue"
    :yield/withdraw      "yield_withdraw"
    :set-yield-risk      "set-yield-risk"
    :yield/claim-deferred "yield_claim_deferred"))

(defn yield-event
  "Build one scenario event map."
  [seq time agent op-type & [{:keys [amount token module-id dt risk-params]
                              :or {token "USDC"
                                   module-id "aave-v3"}}]]
  (let [base {:seq seq :time time :agent agent :action (op->action op-type)}]
    (case op-type
      :yield/deposit
      (assoc base :params {:token token :amount (or amount 10000)})

      :yield/accrue
      (assoc base :params {:token token :dt (or dt 31536000)})

      :yield/withdraw
      (assoc base :params {:token token :module-id module-id})

      :set-yield-risk
      (assoc base :params (or risk-params
                              {:module-id module-id :token token
                               :liquidity-mode "available"}))

      :yield/claim-deferred
      (assoc base :params {:token token :module-id module-id}))))

(defn deposit-accrue-withdraw-seq
  "Canonical event spine: fund → accrue → withdraw."
  [& {:keys [owner initial-time deposit-amount accrue-dt module-id token]
      :or {owner "vault"
           initial-time 1000
           deposit-amount 10000
           accrue-dt 31536000
           module-id "aave-v3"
           token "USDC"}}]
  [(yield-event 0 initial-time owner :yield/deposit
                {:amount deposit-amount :token token})
   (yield-event 1 (+ initial-time accrue-dt) owner :yield/accrue
                {:dt accrue-dt :token token})
   (yield-event 2 (+ initial-time accrue-dt 1000) owner :yield/withdraw
                {:token token :module-id module-id})])

(defn shortfall-affected-seq
  "Partial-liquidity withdraw (yield leg stressed), optional liquidity recovery + claim."
  [& {:keys [owner initial-time recover?]
      :or {owner "vault"
           initial-time 1000
           recover? false}}]
  (let [base (deposit-accrue-withdraw-seq
              :owner owner
              :initial-time initial-time
              :deposit-amount 10000
              :accrue-dt 31536000)]
    (if recover?
      (let [t-after (+ initial-time 31536000 2000)]
        (into base
              [(yield-event 3 t-after owner :set-yield-risk
                            {:risk-params {:module-id "aave-v3" :token "USDC"
                                           :liquidity-mode "available"}})
               (yield-event 4 (+ t-after 1000) owner :yield/claim-deferred
                            {:token "USDC"})]))
      base)))

(defn liquidity-shortage-seq
  "Deposit attempt while pool is in :shortfall liquidity mode (expect reject)."
  [& {:keys [owner initial-time]
      :or {owner "vault" initial-time 1000}}]
  [(yield-event 0 initial-time owner :yield/deposit
                {:token "USDC" :amount 5000})])

(def gen-yield-op
  (gen/bind gen-yield-op-type
            (fn [op]
              (gen/bind (gen/tuple gen-deposit-amount gen-dt-seconds ycore/gen-yield-token)
                        (fn [[amt dt token]]
                          (gen/return {:op op :amount amt :dt dt :token token}))))))

(def gen-yield-event-sequence
  (gen/elements [(deposit-accrue-withdraw-seq)
                 (shortfall-affected-seq)
                 (shortfall-affected-seq :recover? true)
                 (liquidity-shortage-seq)]))
