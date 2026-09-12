(ns resolver-sim.allocation.test-fixtures
  "Shared fixtures for the allocation kernel test suite."
  (:require [resolver-sim.allocation.kernel :as kernel]
            [resolver-sim.allocation.vectors :as vectors]))

(def policy-hash (str "0x" (apply str (repeat 32 "ab"))))

(defn happy-input
  "The fixed a-vs-b-plus-c scenario as a transport JSON input document."
  []
  {"allocation-id" "a-vs-b-plus-c"
   "kernel-version" "allocation-kernel.v1"
   "selection-algorithm" "domain-hash-rejection-v1"
   "policy" {"policy-id" "policy-a-vs-b-plus-c"
             "policy-hash" policy-hash
             "forbid-duplicate-owners" false}
   "claimants"
   [{"claim-id" "A" "economic-owner-id" "owner-A" "amount" "50" "weight" "50"}
    {"claim-id" "B" "economic-owner-id" "owner-B" "amount" "30" "weight" "30"}
    {"claim-id" "C" "economic-owner-id" "owner-C" "amount" "20" "weight" "20"}]
   "outcomes"
   [{"outcome-id" "O1"
     "allocations" [{"claim-id" "A" "allocated" "50"}
                    {"claim-id" "B" "allocated" "0"}
                    {"claim-id" "C" "allocated" "0"}]}
    {"outcome-id" "O2"
     "allocations" [{"claim-id" "A" "allocated" "0"}
                    {"claim-id" "B" "allocated" "30"}
                    {"claim-id" "C" "allocated" "20"}]}]
   "proposed-rates"
   [{"outcome-id" "O1" "numerator" "1" "denominator" "2"}
    {"outcome-id" "O2" "numerator" "1" "denominator" "2"}]
   "capacity" "50"
   "total-eligible-weight" "100"
   "exact-pro-rata-denominator" "100"
   "authoritative-randomness" "0x0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20"})

(defn happy-committed
  "Committed-roots block for the happy path."
  []
  (vectors/base-committed (happy-input)))

(defn happy-with-committed
  "Happy-path input with the committed-roots block attached."
  []
  (assoc (happy-input) "committed" (happy-committed)))

(defn kernel-result
  "Run the kernel over the happy input with committed roots."
  []
  (kernel/run-kernel (happy-with-committed)))
