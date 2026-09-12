(ns resolver-sim.demos.liquidity-shortfall.scenario
  "Demo scenario: legitimate withdrawal requests compete for scarce liquidity.

   One pool, one shortfall, one allocation. The requests are the fixed inputs;
   the allocation is computed by the real domain-neutral pro-rata engine
   (resolver-sim.pro-rata.allocation/allocate), which is the same deterministic,
   hash-committed machinery used across the framework. No demo-specific
   allocation logic is invented."
  (:require [resolver-sim.pro-rata.allocation :as allocation]))

(defn shortfall-request
  "The canonical allocation request: 70 available against 100 requested.

   Rows carry :requested and :weight (equal here — pro-rata by request size).
   No caps are declared, so every request is eligible for its full share."
  []
  {:schema-version "pro-rata-allocation-request.v1"
   :allocation/id :demo/liquidity-shortfall
   :available 70
   :rows [{:row/id :alice :requested 50 :weight 50}
          {:row/id :bob   :requested 30 :weight 30}
          {:row/id :cara  :requested 20 :weight 20}]
   :rounding-policy :largest-remainder
   :tie-break-policy :canonical-row-id
   :redistribution-policy :unallocated})

(defn run-allocation
  "Execute the real allocation engine over the shortfall request.

   Returns the hash-committed allocation result envelope from
   pro-rata.allocation/allocate."
  []
  (allocation/allocate (shortfall-request)))
