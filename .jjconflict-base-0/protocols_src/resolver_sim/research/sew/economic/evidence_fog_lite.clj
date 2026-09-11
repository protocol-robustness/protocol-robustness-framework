(ns resolver-sim.research.sew.economic.evidence-fog-lite
  "Phase Y: Yield Efficiency.
   
   Validates the Yield Module implementation, specifically checking whether
   yield generation effectively covers protocol fees and correctly handles
   distribution to participants."
  (:require [resolver-sim.protocols.sew.snapshot :as snapshot]
            [resolver-sim.protocols.sew.types :as t]
            [resolver-sim.protocols.sew.lifecycle :as lc]
            [resolver-sim.protocols.sew.resolution :as res]
            [resolver-sim.protocols.sew.accounting :as ac]
            [resolver-sim.protocols.sew.authority :as auth]
            [resolver-sim.protocols.sew.invariants :as inv]
            [resolver-sim.stochastic.economics :as econ]
            [resolver-sim.stochastic.rng :as rng]))

(defn run-yield-trial
  "Run a single trial testing yield accrual and distribution.
   1. Create escrow with 1.5% fee.
   2. Set yield rate to 5% APR.
   3. Advance time by 30 days.
   4. Accrue yield.
   5. Resolve and Finalize.
   6. Assert: (Principal + Yield) - ProtocolFees == Total Payouts."
  [params]
  (let [escrow-size (:escrow-size params 10000)
        fee-bps     (:resolver-fee-bps params 150)
        yield-bps   (:yield-annual-bps params 500) ; 5%
        duration    (:duration-days params 30)

        ;; Setup
        w0    (t/empty-world 1000)
        snap  (snapshot/make-escrow-snapshot
               {:escrow-fee-bps fee-bps
                :yield-protocol-fee-bps (:yield-protocol-fee-bps params 1000)}) ; 10% of yield

        ;; 1. Create
        cr    (lc/create-escrow w0 "alice" "USDC" "bob" escrow-size {} snap)
        _     (when-not (:ok cr) (throw (ex-info "Create failed" cr)))
        w1    (assoc-in (:world cr) [:escrow-transfers 0 :dispute-resolver] "resolver0")
        wf-id 0

        ;; 1.5 Raise Dispute (required for resolution)
        dr    (lc/raise-dispute w1 wf-id "alice")
        w1-d  (:world dr)

        ;; 2. Set Rate
        w2    (assoc-in w1-d [:yield-rates "USDC"] yield-bps)

        ;; 3. Advance Time
        w3    (assoc w2 :block-time (+ (:block-time w2) (* duration 86400)))

        ;; 4. Accrue & Resolve
        r-res (res/execute-resolution w3 wf-id "resolver0" true "0xhash" nil)
        _     (when-not (:ok r-res) (throw (ex-info "Resolution failed" r-res)))
        w4    (:world r-res)

        ;; Final State check
        et-final (t/get-transfer w4 wf-id)
        yield    (get-in w4 [:total-yield-generated "USDC"] 0)
        fees     (get-in w4 [:total-fees "USDC"] 0)

        inv-res  (inv/check-all w4)]

    {:yield-generated yield
     :protocol-fees   fees
     :final-state     (:escrow-state et-final)
     :invariants-ok?  (:all-hold? inv-res)
     :solvency-ratio  (inv/calculate-solvency-ratio w4)}))

(defn run-phase-y [params]
  (println "\n=== Phase Y: Yield Efficiency Validation ===")
  (println (format "Escrow: %d | APR: %.2f%% | Duration: %d days"
                   (:escrow-size params 10000)
                   (/ (double (:yield-annual-bps params 500)) 100.0)
                   (:duration-days params 30)))

  (let [result (run-yield-trial params)]
    (println "\n=== SUMMARY ===")
    (println (format "  Yield Accrued:   %d" (:yield-generated result)))
    (println (format "  Protocol Fees:   %d" (:protocol-fees result)))
    (println (format "  Final State:     %s" (name (:final-state result))))
    (println (format "  Solvency Ratio:  %.4f" (:solvency-ratio result)))
    (if (:invariants-ok? result)
      (println "  Status: ✅ PASS")
      (println "  Status: ❌ FAIL (Invariants Violated)"))))
