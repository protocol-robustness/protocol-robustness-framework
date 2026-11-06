(ns resolver-sim.yield.accrual-properties
  "Offline accrual properties (partition equivalence, drift budgets).

   Used by tests and future Y08-style scenarios — not evaluated every replay step."
  (:require [resolver-sim.yield.modules.liquid-lending :as liquid]))

(defn- run-fragmented-accrue
  [world module token total-dt steps]
  (let [per-step (quot total-dt steps)
        remainder (- total-dt (* per-step steps))
        world*   (nth (iterate #(liquid/accrue % module {:token token :dt per-step}) world) steps)]
    (if (pos? remainder)
      (liquid/accrue world* module {:token token :dt remainder})
      world*)))

(defn partition-drift
  "Absolute unrealized-yield difference: one-shot vs `steps` fragmented accruals."
  [world module-id token total-dt steps]
  (let [module {:module/id module-id}
        one-shot    (liquid/accrue world module {:token token :dt total-dt})
        fragmented  (run-fragmented-accrue world module token total-dt steps)
        owner       (ffirst (:yield/positions world))]
    (when owner
      (let [pos-key (first owner)]
        (Math/abs (long (- (get-in one-shot [:yield/positions pos-key :unrealized-yield] 0)
                           (get-in fragmented [:yield/positions pos-key :unrealized-yield] 0))))))))

(defn partition-drift-within-budget?
  [world module-id token total-dt steps max-drift]
  (<= (partition-drift world module-id token total-dt steps) max-drift))

(def default-liquid-lending-partition-budget
  "Matches `yield.accounting-test` / 365 daily steps over one year at 10% APY."
  365)
