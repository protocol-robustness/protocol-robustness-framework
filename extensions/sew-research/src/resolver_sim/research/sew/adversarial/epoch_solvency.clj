(ns resolver-sim.research.sew.adversarial.epoch-solvency
  "Phase AF: Slashing Epoch Solvency (BM-04).

   Tests whether the insurance pool remains solvent across all realistic
   resolver-population and bond configurations when a worst-case epoch
   occurs — i.e. every resolver is slashed to the epoch cap simultaneously.

   Hypothesis:
     For any (n-resolvers, avg-bond-usd, pool-seed-usd) in the design
     envelope, insurance pool solvency-ratio ≥ 1.0 after a worst-case epoch.

   Parameters swept:
     :n-resolvers     — [5 10 20 50]
     :avg-bond-usd    — [250 500 1000 2500]  (bounded by MIN_RESOLVER_BOND=$250)
     :pool-seed-usd   — [0 500 1000 5000 10000]

   Protocol constants (from ResolverSlashingModuleV1 + ResolverStakingModuleV1):
     EPOCH_CAP_BPS          = 2000  (20% resolver epoch cap)
     SENIOR_EPOCH_CAP_BPS   = 1000  (10% senior epoch cap)
     INSURANCE_CUT_BPS      = 2000  (20% of each slash goes to insurance)

   Pass threshold: solvency-ratio ≥ 1.0 for all configurations in the
   design envelope (n-resolvers ≤ 20, avg-bond ≥ 500)."
  (:require [resolver-sim.stochastic.rng :as rng]
            [resolver-sim.sim.engine :as proto]))

;; ---------------------------------------------------------------------------
;; Protocol constants (mirrors ResolverSlashingModuleV1.sol + ResolverStakingModuleV1.sol)
;; ---------------------------------------------------------------------------

(def EPOCH_CAP_BPS        2000)   ; RESOLVER_SLASH_CAP_BPS  — 20% per resolver per epoch
(def SENIOR_EPOCH_CAP_BPS 1000)   ; SENIOR_SLASH_CAP_BPS    — 10% per senior per epoch
(def INSURANCE_CUT_BPS    2000)   ; 20% of each slash routes to InsurancePoolVault
(def RESOLVER_SLASH_BPS    200)   ; PENALTY_MISSED_RESOLVE  — 2% per-slash rate
(def MAX_ESCROW_PER_CASE  2000)   ; MAX_ESCROW_PER_L0_CASE  — $2,000 hard cap
(def ESCROW_CAP_MULTIPLIER   4)   ; maxEscrowPerL0Case = min($2000, 4× resolverBond)

;; ---------------------------------------------------------------------------
;; Core solvency calculation (analytical, no RNG needed)
;; ---------------------------------------------------------------------------

(defn max-epoch-drawdown
  "Compute the maximum insurance pool drawdown in a single worst-case epoch.

   Worst case: every resolver is slashed to the epoch cap (20% of bond).
   Of each slash, 20% goes to the insurance pool as incoming funding.
   The pool's net obligation is: claims it must pay out - incoming slash cuts.

   In DR v3, the insurance pool covers user losses when a resolver bond is
   depleted and cannot fully compensate a harmed party. In the worst case,
   every resolver is both slashed AND has an open user claim equal to the
   max escrow per case.

   Optional param overrides:
     :epoch-cap-bps        — default EPOCH_CAP_BPS
     :insurance-cut-bps    — default INSURANCE_CUT_BPS
     :max-escrow-per-case  — default MAX_ESCROW_PER_CASE
     :escrow-cap-multiplier — default ESCROW_CAP_MULTIPLIER

   Returns a map with the analytical drawdown components."
  ([n-resolvers avg-bond-usd] (max-epoch-drawdown n-resolvers avg-bond-usd {}))
  ([n-resolvers avg-bond-usd {:keys [epoch-cap-bps insurance-cut-bps max-escrow-per-case escrow-cap-multiplier]
                              :or   {epoch-cap-bps        EPOCH_CAP_BPS
                                     insurance-cut-bps    INSURANCE_CUT_BPS
                                     max-escrow-per-case  MAX_ESCROW_PER_CASE
                                     escrow-cap-multiplier ESCROW_CAP_MULTIPLIER}}]
   (let [epoch-cap-rate   (/ epoch-cap-bps 10000.0)
         insurance-rate   (/ insurance-cut-bps 10000.0)
         total-slashed    (* n-resolvers avg-bond-usd epoch-cap-rate)
         incoming-to-pool (* total-slashed insurance-rate)
         effective-max-escrow (min max-escrow-per-case (* avg-bond-usd escrow-cap-multiplier))
         per-resolver-gap (max 0 (- effective-max-escrow avg-bond-usd))
         total-user-gaps  (* n-resolvers per-resolver-gap)
         net-obligation   (max 0 (- total-user-gaps incoming-to-pool))]
     {:n-resolvers        n-resolvers
      :avg-bond-usd       avg-bond-usd
      :epoch-cap-rate     epoch-cap-rate
      :total-slashed      total-slashed
      :incoming-to-pool   incoming-to-pool
      :per-resolver-gap   per-resolver-gap
      :total-user-gaps    total-user-gaps
      :net-obligation     net-obligation})))

(defn solvency-ratio
  "pool-seed-usd / net-obligation. Infinity when obligation is zero."
  [pool-seed-usd net-obligation]
  (if (zero? net-obligation)
    ##Inf
    (/ (double pool-seed-usd) (double net-obligation))))

;; ---------------------------------------------------------------------------
;; Monte Carlo: add variance from lognormal bond distribution
;; ---------------------------------------------------------------------------

(defn simulate-epoch-solvency
  "Simulate one epoch with N resolvers drawn from a lognormal bond distribution.
   Returns {:pool-after float :solvency-ratio float :pass? bool}"
  [{:keys [n-resolvers avg-bond-usd pool-seed-usd min-bond-usd
           epoch-cap-bps insurance-cut-bps max-escrow-per-case]
    :or   {min-bond-usd 250
           epoch-cap-bps        EPOCH_CAP_BPS
           insurance-cut-bps    INSURANCE_CUT_BPS
           max-escrow-per-case  MAX_ESCROW_PER_CASE}} d-rng]
  (let [epoch-cap-rate (/ epoch-cap-bps 10000.0)
        insurance-rate (/ insurance-cut-bps 10000.0)
        bonds (for [_ (range n-resolvers)]
                (max min-bond-usd
                     (* avg-bond-usd
                        (Math/exp (* 0.5 (- (rng/next-double d-rng) 0.5) 2)))))
        slash-events (for [bond bonds]
                       (let [slashed      (* bond epoch-cap-rate (rng/next-double d-rng))
                             ins-cut      (* slashed insurance-rate)
                             user-claim   (min max-escrow-per-case bond)
                             resolver-gap (max 0.0 (- user-claim (- bond slashed)))]
                         {:ins-cut ins-cut :resolver-gap resolver-gap}))
        pool-after (- (+ pool-seed-usd (reduce + (map :ins-cut slash-events)))
                      (reduce + (map :resolver-gap slash-events)))]
    {:pool-after     pool-after
     :solvency-ratio (solvency-ratio (+ pool-seed-usd (reduce + (map :ins-cut slash-events)))
                                     (reduce + (map :resolver-gap slash-events)))
     :pass?          (>= pool-after 0)}))

;; ---------------------------------------------------------------------------
;; Trial function (engine interface)
;; ---------------------------------------------------------------------------

(defn run-scenario
  "Run one (n-resolvers × avg-bond × pool-seed) trial: analytical + MC.
   Accepts a param map as produced by build-param-grid.
   Protocol constant overrides (:epoch-cap-bps, :insurance-cut-bps,
   :max-escrow-per-case, :escrow-cap-multiplier) are passed through to
   analytical and MC calculations.
   Returns a result map suitable for proto/run-parameter-sweep."
  [{:keys [n-resolvers avg-bond-usd pool-seed-usd n-trials seed
           epoch-cap-bps insurance-cut-bps max-escrow-per-case escrow-cap-multiplier]
    :or   {n-trials 500}}]
  (let [overrides (cond-> {}
                   epoch-cap-bps        (assoc :epoch-cap-bps epoch-cap-bps)
                   insurance-cut-bps    (assoc :insurance-cut-bps insurance-cut-bps)
                   max-escrow-per-case  (assoc :max-escrow-per-case max-escrow-per-case)
                   escrow-cap-multiplier (assoc :escrow-cap-multiplier escrow-cap-multiplier))
        d-rng      (rng/make-rng seed)
        analytical (max-epoch-drawdown n-resolvers avg-bond-usd overrides)
        mc-results (doall (repeatedly n-trials
                                      #(simulate-epoch-solvency
                                        (merge {:n-resolvers   n-resolvers
                                                :avg-bond-usd  avg-bond-usd
                                                :pool-seed-usd pool-seed-usd}
                                               overrides)
                                        d-rng)))
        pass-rate  (double (/ (count (filter :pass? mc-results)) n-trials))
        worst-pool (apply min (map :pool-after mc-results))
        analytic-sr (solvency-ratio pool-seed-usd (:net-obligation analytical))]
    {:params             {:n-resolvers   n-resolvers
                          :avg-bond-usd  avg-bond-usd
                          :pool-seed-usd pool-seed-usd}
     :analytical-solvency analytic-sr
     :mc-pass-rate        pass-rate
     :worst-pool-after    worst-pool
     :pass?               (and (>= analytic-sr 1.0) (>= pass-rate 0.95))
     :class               (cond
                            (and (>= analytic-sr 1.0) (>= pass-rate 0.99)) "A"
                            (and (>= analytic-sr 1.0) (>= pass-rate 0.95)) "B"
                            :else "C")}))

;; ---------------------------------------------------------------------------
;; Parameter grid
;; ---------------------------------------------------------------------------

(defn- build-param-grid
  [{:keys [n-trials base-seed
           epoch-cap-bps insurance-cut-bps max-escrow-per-case escrow-cap-multiplier]
    :or   {n-trials 500 base-seed 42}}]
  (let [overrides (cond-> {}
                    epoch-cap-bps        (assoc :epoch-cap-bps epoch-cap-bps)
                    insurance-cut-bps    (assoc :insurance-cut-bps insurance-cut-bps)
                    max-escrow-per-case  (assoc :max-escrow-per-case max-escrow-per-case)
                    escrow-cap-multiplier (assoc :escrow-cap-multiplier escrow-cap-multiplier))]
    (for [n    [5 10 20 50]
          bond [250 500 1000 2500]
          pool [0 500 1000 5000 10000]
          :let [seed (+ base-seed (* n 7) (* bond 3) pool)]]
      (merge {:n-resolvers n :avg-bond-usd bond :pool-seed-usd pool
              :n-trials n-trials :seed seed}
             overrides))))

;; ---------------------------------------------------------------------------
;; Summary
;; ---------------------------------------------------------------------------

(defn- summarize [results params]
  (let [total        (count results)
        passing      (count (filter :pass? results))
        by-class     (frequencies (map :class results))
        max-n       (double (or (:envelope-max-resolvers params) 20))
        min-bond    (double (or (:envelope-min-bond params) 500))
        envelope     (filter #(and (<= (get-in % [:params :n-resolvers]) max-n)
                                   (>= (get-in % [:params :avg-bond-usd]) min-bond))
                             results)
        env-pass     (count (filter :pass? envelope))
        finite       (remove #(= ##Inf (:analytical-solvency %)) results)
        worst        (when (seq finite) (apply min-key :analytical-solvency finite))
        best         (when (seq finite) (apply max-key :analytical-solvency finite))]
    {:total-scenarios       total
     :passing-scenarios     passing
     :class-a               (get by-class "A" 0)
     :class-b               (get by-class "B" 0)
     :class-c               (get by-class "C" 0)
     :design-envelope-total (count envelope)
     :design-envelope-pass  env-pass
     :envelope-max-resolvers max-n
     :envelope-min-bond     min-bond
     :hypothesis-holds?     (= env-pass (count envelope))
     :worst-solvency-ratio  (if worst (:analytical-solvency worst) ##Inf)
     :worst-params          (when worst (:params worst))
     :best-solvency-ratio   (if best (:analytical-solvency best) ##Inf)
     :best-params           (when best (:params best))}))

;; ---------------------------------------------------------------------------
;; Entry point
;; ---------------------------------------------------------------------------

(defn run-phase-af
  "BM-04: Slashing epoch solvency sweep.

   Tests whether the insurance pool stays solvent across the full
   (n-resolvers × avg-bond × pool-seed) grid under worst-case epoch slashing.

   Pass threshold: solvency-ratio ≥ 1.0 for all design-envelope configs
   (n-resolvers ≤ 20, avg-bond ≥ $500).

   Protocol constant overrides read from params:
     :epoch-cap-bps, :insurance-cut-bps, :max-escrow-per-case, :escrow-cap-multiplier"
  ([] (run-phase-af {}))
  ([params]
   (let [epoch-cap-bps       (or (:epoch-cap-bps params) EPOCH_CAP_BPS)
         insurance-cut-bps   (or (:insurance-cut-bps params) INSURANCE_CUT_BPS)
         max-escrow-per-case (or (:max-escrow-per-case params) MAX_ESCROW_PER_CASE)]
     (proto/print-phase-header
      {:benchmark-id "BM-04"
       :label        "Slashing Epoch Solvency"
       :hypothesis   "Insurance pool solvency-ratio ≥ 1.0 for all design-envelope configs"
       :details      [(format "Constants: epoch-cap=%d bps, insurance-cut=%d bps, max-escrow=$%d"
                              epoch-cap-bps insurance-cut-bps max-escrow-per-case)]})

     (let [grid    (build-param-grid params)
           results (proto/run-parameter-sweep grid run-scenario)
           summary (summarize results params)]

       (println "   Sample results (n-resolvers=10, avg-bond=$500):")
       (doseq [r (filter #(and (= 10 (get-in % [:params :n-resolvers]))
                               (= 500 (get-in % [:params :avg-bond-usd])))
                         results)]
         (println (format "     pool-seed=$%-6d  analytic-SR=%.2fx  mc-pass=%.0f%%  %s"
                          (get-in r [:params :pool-seed-usd])
                          (:analytical-solvency r)
                          (* 100 (:mc-pass-rate r))
                          (case (:class r) "A" "✅ A" "B" "✅ B" "❌ C"))))

       (proto/print-phase-footer
        {:benchmark-id  "BM-04"
         :passed?       (:hypothesis-holds? summary)
         :summary-lines [(format "Total configs:    %d" (:total-scenarios summary))
                         (format "Passing (A+B):    %d  (%.0f%%)"
                                 (:passing-scenarios summary)
                                 (* 100.0 (/ (:passing-scenarios summary)
                                             (:total-scenarios summary))))
                         (format "Class A:  %d   Class B: %d   Class C: %d"
                                 (:class-a summary) (:class-b summary) (:class-c summary))
                         (format "Design-envelope (%d configs, n≤20 bond≥$500):"
                                 (:design-envelope-total summary))
                         (format "  Passing: %d / %d"
                                 (:design-envelope-pass summary)
                                 (:design-envelope-total summary))
                         (when (:worst-params summary)
                           (format "Worst solvency ratio: %.2fx  params: %s"
                                   (:worst-solvency-ratio summary)
                                   (:worst-params summary)))]})

       (proto/make-result
        {:benchmark-id "BM-04"
         :label        "Slashing Epoch Solvency"
         :hypothesis   "Insurance pool solvency-ratio ≥ 1.0 for all design-envelope configs"
         :passed?      (:hypothesis-holds? summary)
         :results      results
         :summary      summary})))))
