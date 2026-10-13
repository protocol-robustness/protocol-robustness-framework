(ns resolver-sim.research.sew.economic.market-exit
  "Phase O: Market Exit Cascade Modeling
  
  Tests system resilience when honest resolvers exit due to low profitability.
  
  Model:
  - Each epoch: run disputes, measure profits
  - Exit probability = max(0, (expected_profit - actual_profit) / 1000)
  - Track pool composition over 10 epochs
  - Test 4 scenarios: baseline, governance failure, fraud spike, recovery"
  (:require [resolver-sim.sim.batch :as batch]
            [resolver-sim.stochastic.rng :as rng]
            [resolver-sim.stochastic.economics :as econ]
            [resolver-sim.sim.engine      :as engine]))

;; ============================================================================
;; EXIT PROBABILITY & SIMULATION
;; ============================================================================

(def ^:private exit-prob-denominator
  "Normalizes profit shortfall to a per-epoch exit probability [0,1].
   Derived from the model calibration: a shortfall of this many units
   makes exit a near-certainty."
  1000.0)

(defn calculate-exit-probability
  "Calculate per-epoch exit probability based on profit shortfall"
  [expected-profit actual-profit]
  (let [gap (- expected-profit actual-profit)
        prob (/ gap exit-prob-denominator)]
    (double (max 0.0 (min 1.0 prob)))))

(defn apply-resolver-exits
  "Remove resolvers based on exit probability

  Returns: {:remaining, :exited-count, :exit-rate, :honest-remaining}"
  [rng resolvers exit-prob-fn]
  (let [initial (count resolvers)
        remaining (filter (fn [r] (> (rng/next-double rng) (exit-prob-fn r))) resolvers)
        exited (- initial (count remaining))
        exit-rate (if (zero? initial) 0.0 (double (/ exited initial)))
        honest-remaining (count (filter #(= :honest (:strategy %)) remaining))]

    {:remaining remaining
     :exited-count exited
     :exit-rate exit-rate
     :honest-remaining honest-remaining}))

(defn run-one-epoch
  "Run one epoch with exits
  
  Returns: {:epoch, :honest-remaining, :pool-size, :honest-ratio, :remaining}"
  [rng epoch resolvers params expected-profit]

  (let [[rng-exit rng-batch] (rng/split-rng rng)
        ;; Run disputes
        batch-result (batch/run-batch rng-batch (:n-trials-per-epoch params 500) params)
        avg-profit (:avg-profit batch-result 150.0)

        ;; Calculate exit probs
        exit-prob-fn (fn [r]
                       (calculate-exit-probability expected-profit avg-profit))

        ;; Apply exits
        {:keys [remaining exited-count exit-rate honest-remaining]}
        (apply-resolver-exits rng-exit resolvers exit-prob-fn)

        pool-size (count remaining)
        honest-ratio (if (zero? pool-size) 0.0 (double (/ honest-remaining pool-size)))]

    {:epoch epoch
     :pool-size pool-size
     :honest-remaining honest-remaining
     :exit-rate exit-rate
     :honest-ratio honest-ratio
     :remaining remaining}))

;; ============================================================================
;; SCENARIO TESTING
;; ============================================================================

(defn run-market-exit-cascade-scenario
  "Run one scenario over 10 epochs"
  [rng params scenario]

  (let [scenario-name (:name scenario)
        gov-eff (:governance-effectiveness scenario)
        fraud-mult (:fraud-rate-multiplier scenario)
        baseline-profit 150.0
        expected-profit (* baseline-profit gov-eff)

        ;; Initial pool: 50 resolvers, 90% honest
        initial-honest 45
        initial-fraud 5
        initial-resolvers (concat
                           (mapv (fn [i] {:id (str "h" i) :strategy :honest}) (range initial-honest))
                           (mapv (fn [i] {:id (str "f" i) :strategy :fraud}) (range initial-fraud)))

        ;; Adjust params
        adjusted-params (assoc params
                               :fraud-rate (* (:fraud-rate params 0.1) fraud-mult))

        ;; Run 10 epochs — split RNG for each epoch
        results (loop [epoch 1, rng rng, resolvers initial-resolvers, epochs []]
                  (if (> epoch 10)
                    epochs
                    (let [[rng-epoch rng-rest] (rng/split-rng rng)
                          result (run-one-epoch rng-epoch epoch resolvers adjusted-params expected-profit)]
                      (recur (inc epoch) rng-rest
                             (:remaining result)
                             (conj epochs result)))))]

    {:scenario-name scenario-name
     :gov-eff gov-eff
     :fraud-mult fraud-mult
     :epoch-results results
     :final-honest-ratio (:honest-ratio (last results))
     :final-pool-size (:pool-size (last results))}))

;; ============================================================================
;; PHASE O COMPLETE
;; ============================================================================

(defn run-phase-o-complete
  "Run Phase O: Market Exit Cascade Analysis
  
  Test 4 scenarios:
  1. Baseline: gov=100%, fraud=10%
  2. Governance Failure: gov=0%, fraud=10%
  3. Fraud Spike: gov=0%, fraud=30%
  4. Recovery: gov=100%, fraud=30%"
  [params]

  (let [scenarios [{:name "Baseline (Normal)" :governance-effectiveness 1.0 :fraud-rate-multiplier 1.0}
                   {:name "Governance Failure" :governance-effectiveness 0.0 :fraud-rate-multiplier 1.0}
                   {:name "Fraud Spike" :governance-effectiveness 0.0 :fraud-rate-multiplier 3.0}
                   {:name "Recovery" :governance-effectiveness 1.0 :fraud-rate-multiplier 3.0}]

        _ (println "\n📊 Running Phase O: Market Exit Cascade Analysis")
        _ (println "   Testing 4 cascading failure modes over 10 epochs\n")

        rng-seed  (or (:rng-seed params) 42)
        rng-scenarios (rng/make-rng rng-seed)
        results (mapv (fn [scenario]
                        (let [[rng-scenario rng-scenarios] (rng/split-rng rng-scenarios)
                              _ (println (format "   Testing: %s..." (:name scenario)))
                              result (run-market-exit-cascade-scenario rng-scenario params scenario)
                              _ (println (format "     → Final honest ratio: %.1f%%"
                                                 (* 100 (:final-honest-ratio result))))]
                          result))
                      scenarios)

        ;; Summary
        baseline-ratio (:final-honest-ratio (first results))
        spike-ratio (:final-honest-ratio (nth results 2))

        status (if (< spike-ratio 0.5)
                 "🔴 CRITICAL (honest ratio < 50%)"
                 (if (< spike-ratio 0.7)
                   "🟠 DEGRADED (honest ratio < 70%)"
                   (if (< spike-ratio 0.85)
                     "🟡 CAUTION (honest ratio < 85%)"
                     "✅ STABLE (honest ratio maintained)")))

        finding (format "Baseline: %.0f%% → Spike: %.0f%% → %s"
                        (* 100 baseline-ratio)
                        (* 100 spike-ratio)
                        status)]

    (println "\n📈 Exit Cascade Results:")
    (println "Scenario                 | Gov'nce | Fraud | Final Honest | Status")
    (println "-------------------------|--------|-------|--------------|--------")
    (doseq [result results]
      (println (format "%-25s | %6.0f%% | %5.0f%% | %11.1f%% | %s"
                       (:scenario-name result)
                       (* 100 (:gov-eff result))
                       (* 100 (:fraud-mult result))
                       (* 100 (:final-honest-ratio result))
                       (cond (< (:final-honest-ratio result) 0.5) "🔴"
                             (< (:final-honest-ratio result) 0.7) "🟠"
                             (< (:final-honest-ratio result) 0.85) "🟡"
                             :else "✅"))))

    (println (format "\n   Key Finding: %s\n" finding))

    ;; MC-B: Print breakeven detection rate so the economic security gap is visible
    (let [escrow     (:escrow-size params 10000)
          fee        (econ/calculate-fee escrow (:resolver-fee-bps params 150))
          bond       (+ (econ/calculate-bond escrow (:appeal-bond-bps params 700))
                        (econ/calculate-bond escrow (:resolver-bond-bps params 1000)))
          bond-loss  (* bond (:slash-multiplier params 2.5))
          breakeven  (econ/breakeven-detection escrow fee bond-loss)
          current-d  (:slashing-detection-probability params 0.10)
          fsr        (:fraud-success-rate params 0.0)]
      (println "   ── Economic Deterrence Analysis ──────────────────────────────────────")
      (println (format "   fraud-success-rate:    %.2f (set in params; 0.0 = protocol-income model only)" fsr))
      (println (format "   current detection:     %.0f%%" (* 100 current-d)))
      (println (format "   breakeven detection:   %.0f%% (needed to deter if every undetected fraud diverts escrow)" (* 100 breakeven)))
      (if (>= current-d breakeven)
        (println "   ✅ Detection is sufficient to deter fraud-as-escrow-diversion at current parameters")
        (println (format "   ⚠️  Detection is %.0fx below breakeven — economic security relies on invariant suite"
                         (/ breakeven current-d))))
      (println "   ─────────────────────────────────────────────────────────────────────\n"))

    (let [spike-threshold (double (or (:spike-ratio-threshold params) 0.70))]
      (engine/make-result
       {:benchmark-id "O"
        :label        "Market Exit Cascade"
        :hypothesis   (format "Honest resolver ratio stays >= %.0f%% under governance failure + fraud spike" (* 100 spike-threshold))
        :class        :analytic
        :passed?      (>= spike-ratio spike-threshold)
        :results      results
        :summary      {:finding finding :spike-ratio spike-ratio :threshold spike-threshold}}))))
