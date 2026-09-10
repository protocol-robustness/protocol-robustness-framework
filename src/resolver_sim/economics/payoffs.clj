(ns resolver-sim.economics.payoffs
  "Protocol-agnostic generic economic allocation and accounting helpers.

   Layering rule:
   - resolver-sim.economics/* is protocol-agnostic.
   - resolver-sim.protocols.<protocol>/* adapts protocol-specific state and policy
     into generic economics functions.
   - Generic economics must never depend on protocol namespaces.

   Pro-rata allocation semantics are NOT owned here. They live in the
   resolver-sim.pro-rata semantic closure (resolver-sim.pro-rata.allocation,
   resolver-sim.pro-rata.redistribution, resolver-sim.pro-rata.evaluation);
   this namespace must never require a resolver-sim.pro-rata.* namespace.")

;; Basis point denominator used by generic integer accounting helpers.
;; Also defined as resolver-sim.yield.exact-math/scaling-factor (same value).
(def basis-point-denominator 10000)

(defn calculate-bps-amount
  "Return `amount * bps / 10000` using integer division.
   Nil-safe: nil amount or bps is treated as 0."
  [amount bps]
  (quot (* (or amount 0) (or bps 0)) basis-point-denominator))

(defn calculate-net-after-bps-fee
  "Return {:fee ... :net ...} for a basis-point fee deducted from `amount`."
  [amount fee-bps]
  (let [fee (calculate-bps-amount amount fee-bps)]
    {:fee fee
     :net (- amount fee)}))

(defn calculate-capacity-limit
  "Return a generic capacity limit from a base amount and scalar multiplier."
  ([base-amount] (calculate-capacity-limit base-amount 1.0))
  ([base-amount multiplier]
   (* base-amount (or multiplier 1.0))))