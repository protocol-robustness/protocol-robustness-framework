(ns resolver-sim.core.phases
  "Phase registry and per-mode runner helpers.

   Each run-* function orchestrates one simulation mode: it calls the relevant
   sim namespace, prints a summary, writes output files, and returns the result
   map.  All I/O (print + file write) lives here; the phase namespaces are pure.

   `phase-runners` is the dispatch table used by -main to route CLI flags to
   the correct runner.  To add a new phase:
     1. Add a require above.
     2. Add an entry to phase-runners.
     3. Add a cli-option entry in core.cli."
  (:require [clojure.string :as str]
            [resolver-sim.evidence.node :as ev-node]
            [resolver-sim.io.results  :as results]
            [resolver-sim.sim.batch   :as batch]
            [resolver-sim.sim.sweep   :as sweep]
            [resolver-sim.sim.multi-epoch       :as multi-epoch]
            [resolver-sim.sim.waterfall         :as waterfall]
            [resolver-sim.sim.governance-impact :as gov-impact]
            [resolver-sim.research.sew.economic.market-exit             :as market-exit]
            [resolver-sim.validation.scenario-id :as sid]
            [resolver-sim.research.sew.adversarial.falsification-lite   :as falsification-lite]
            [resolver-sim.research.sew.adversarial.evidence-fog         :as evidence-fog]
            [resolver-sim.research.sew.adversarial.legitimacy-loop      :as legitimacy-loop]
            [resolver-sim.research.sew.governance.adversary             :as adversary]
            [resolver-sim.research.sew.adversarial.trust-floor          :as trust-floor]
            [resolver-sim.research.sew.governance.bandwidth-floor       :as bandwidth-floor]
            [resolver-sim.research.sew.adversarial.fair-slashing        :as fair-slashing]
            [resolver-sim.research.sew.adversarial.epoch-solvency       :as epoch-solvency]
            [resolver-sim.research.sew.adversarial.ema-convergence      :as ema-convergence]
            [resolver-sim.research.sew.adversarial.equity-divergence    :as equity-divergence]
            [resolver-sim.research.sew.adversarial.escalation-trap      :as escalation-trap]
            [resolver-sim.research.sew.adversarial.collusion-ring       :as collusion-ring]
            [resolver-sim.research.sew.governance.capture-drift         :as capture-drift]
            [resolver-sim.research.sew.adversarial.falsification-revised :as falsification-revised]
            [resolver-sim.research.sew.adversarial.advanced-vulnerability :as advanced-vulnerability]
            [resolver-sim.research.sew.adversarial.liveness-participation :as liveness-participation]
            [resolver-sim.research.sew.economic.adaptive-attacker       :as adaptive-attacker]
            [resolver-sim.research.sew.economic.belief-cascades         :as belief-cascades]
            [resolver-sim.research.sew.economic.dispute-clustering       :as dispute-clustering]
            [resolver-sim.research.sew.economic.burst-concurrency       :as burst-concurrency]
            [resolver-sim.sim.adversarial               :as adversarial]
            [resolver-sim.research.sew.analytic.phase-f-economic-parameters :as phase-f]
            [resolver-sim.research.sew.analytic.phase-c-corruption-economics :as phase-c]
            [resolver-sim.research.sew.analytic.phase-e-evidence-integrity :as phase-e]
            [resolver-sim.research.sew.analytic.phase-m-fairness-analysis :as phase-m]
            [resolver-sim.stochastic.rng                :as rng]))

;; ── Phase evidence tiers ─────────────────────────────────────────────────────
;; Each phase is classified by what kind of evidence it produces.
;;   :protocol-kernel-evidence  — exercises resolve-dispute / replay-with-protocol
;;   :analytic                  — closed-form algebraic check (no protocol kernel)
;;   :exploratory               — narrative / qualitative simulation; not falsifiable
;;
;; The CI gate (scripts/test.sh monte-carlo) runs 5 phases; all others are
;; exploratory unless explicitly CI-gated.  This map is the single source of truth
;; for which phases are protocol-kernel vs analytic vs exploratory.

(def phase-evidence-tiers
  "Phase keyword → evidence tier.

   :protocol-kernel-evidence — calls resolve-dispute or replay-with-protocol
   :analytic                 — algebraic/closed-form, no protocol calls
   :exploratory              — narrative, qualitative, or pre-prototype"
  {:phase-o          :analytic
   :phase-p          :analytic
   :phase-aa         :analytic
   :phase-ad         :analytic
   :phase-f          :analytic

   :phase-ac         :analytic
   :phase-ac-sweep   :analytic
   :phase-ac-cap     :analytic
   :phase-ad-sweep   :analytic
   :phase-ae         :analytic
   :phase-af         :analytic
   :phase-ag         :analytic
   :phase-ah         :analytic
   :phase-ai         :analytic
   :phase-t          :analytic
   :phase-y          :analytic
   :phase-z          :analytic
   :market-exit      :analytic
   :phase-p-revised  :analytic
   :phase-q          :exploratory
   :phase-r          :exploratory
   :phase-u          :exploratory
   :phase-v          :exploratory
   :phase-w          :exploratory
   :phase-x          :exploratory
   :phase-f-dr       :analytic
   :phase-c-dr       :analytic
   :phase-e-dr       :analytic
   :phase-m-dr       :analytic})

(defn tier-for-phase
  "Return the evidence tier keyword for a phase keyword, or :unknown."
  [phase-key]
  (get phase-evidence-tiers phase-key :unknown))

;; ── CI-gated phases ─────────────────────────────────────────────────────────
;; Phases that run in scripts/test.sh monte-carlo.  This is the canonical list
;; consumed by CI; changes here should be reflected in test.sh.

(def ci-monte-carlo-phases
  "Phase keywords that run in the CI Monte Carlo gate (test.sh monte-carlo)."
  #{:phase-o :phase-p :phase-aa :phase-ad :phase-f})

(defn ci-gated?
  "True if a phase runs in the CI Monte Carlo gate."
  [phase-key]
  (contains? ci-monte-carlo-phases phase-key))

;; ── Phase runner helpers ─────────────────────────────────────────────────────
;; ---------------------------------------------------------------------------

(defn run-simulation [params output-dir]
  (ev-node/with-execution-node
    {:execution-id :execution/simulation
     :inputs {:params params
              :output-dir output-dir}
     :outputs-fn (fn [batch-result]
                   {:scenario-id (:scenario-id params "unnamed")
                    :output-dir output-dir
                    :result batch-result})}
    (fn []
      (let [scenario-id (sid/validate-scenario-id! (:scenario-id params "unnamed"))
            run-dir (results/create-run-directory output-dir scenario-id)
            rng (rng/make-rng (:rng-seed params))

            _ (println (format "\n📊 Running simulation: %s" scenario-id))
            _ (println (format "   Seed: %d" (:rng-seed params)))
            _ (println (format "   Trials: %d" (:n-trials params)))

            batch-result (batch/run-batch rng (:n-trials params) params)

            _ (println "\n✓ Simulation complete. Results:")
            _ (println (format "   Honest avg profit: %.2f" (:honest-mean batch-result)))
            _ (println (format "   Malice avg profit: %.2f" (:malice-mean batch-result)))
            _ (println (format "   Dominance ratio: %.2f" (:dominance-ratio batch-result)))]

        (results/write-edn (format "%s/summary.edn" run-dir) batch-result)
        (results/write-csv (format "%s/results.csv" run-dir) [batch-result])
        (results/write-run-metadata (format "%s/metadata.edn" run-dir)
                                    {:scenario-id scenario-id
                                     :params params
                                     :batch-result batch-result})

        (println (format "\n💾 Results saved to: %s" run-dir))
        batch-result))))

(defn run-sweep [params output-dir]
  (ev-node/with-execution-node
    {:execution-id :execution/batch
     :inputs {:params params
              :output-dir output-dir}
     :outputs-fn (fn [results-list]
                   {:scenario-id (:scenario-id params "unnamed")
                    :output-dir output-dir
                    :result-count (count results-list)
                    :results results-list})}
    (fn []
      (let [scenario-id       (:scenario-id params "unnamed")
            custom-sweep-params (:sweep-params params)
            run-dir (results/create-run-directory output-dir (str scenario-id "-sweep"))

            _ (if custom-sweep-params
                (println (format "\n📊 Running parameter sweep: %s" scenario-id))
                (println (format "\n📊 Running strategy sweep: %s" scenario-id)))
            _ (println (format "   Seed: %d" (:rng-seed params)))
            _ (println (format "   Trials per combo: %d" (:n-trials params)))

            results-list (if custom-sweep-params
                           (sweep/run-parameter-sweep params (:rng-seed params) custom-sweep-params)
                           (sweep/run-strategy-sweep  params (:rng-seed params)))]

        (println (format "\n✓ Sweep complete. %d results:" (count results-list)))
        (if custom-sweep-params
          (doseq [result results-list]
            (let [param-str (->> custom-sweep-params
                                 keys
                                 (map #(format "%s=%s" (name %) (get result %)))
                                 (str/join " "))]
              (println (format "   %s: honest=%.2f, malice=%.2f, ratio=%.2f"
                               param-str (:honest-mean result) (:malice-mean result)
                               (:dominance-ratio result)))))
          (doseq [result results-list]
            (println (format "   %s: honest=%.2f, malice=%.2f, ratio=%.2f"
                             (:strategy result) (:honest-mean result) (:malice-mean result)
                             (:dominance-ratio result)))))

        (results/write-edn (format "%s/summary.edn" run-dir) results-list)
        (results/write-csv (format "%s/results.csv" run-dir) results-list)
        (results/write-run-metadata (format "%s/metadata.edn" run-dir)
                                    {:scenario-id scenario-id
                                     :sweep-type (if custom-sweep-params :parameter :strategy)
                                     :params params
                                     :results results-list})

        (println (format "\n💾 Sweep results saved to: %s" run-dir))
        results-list))))

(defn run-ring-simulation [params output-dir]
  (let [scenario-id (:scenario-id params "unnamed")
        run-dir (results/create-run-directory output-dir (str scenario-id "-ring"))
        rng      (rng/make-rng (:rng-seed params))
        ring-spec (:ring-spec params)

        _ (when-not ring-spec (throw (Exception. "ring-spec not found in params")))
        _ (println (format "\n📊 Running ring simulation: %s" scenario-id))
        _ (println (format "   Seed: %d" (:rng-seed params)))
        _ (println (format "   Trials: %d" (:n-trials params)))
        _ (let [{:keys [senior juniors]} ring-spec]
            (println (format "   Ring: 1 senior ($%d bond) + %d juniors"
                             (:bond senior) (count juniors))))

        ring-result (batch/run-ring-batch rng (:n-trials params) params ring-spec)

        _ (println "\n✓ Ring simulation complete. Results:")
        _ (println (format "   Total ring profit: %.2f" (:ring-total-profit ring-result)))
        _ (println (format "   Avg profit/dispute: %.2f" (:ring-avg-profit-per-dispute ring-result)))
        _ (println (format "   Catch rate: %.4f" (:ring-catch-rate ring-result)))
        _ (println (format "   Ring viable: %s" (:ring-viable? ring-result)))
        _ (println (format "   Senior exhausted: %s" (:ring-senior-exhausted? ring-result)))]

    (results/write-edn (format "%s/summary.edn" run-dir) ring-result)
    (results/write-run-metadata (format "%s/metadata.edn" run-dir)
                                {:scenario-id scenario-id
                                 :type :ring
                                 :params params
                                 :ring-result ring-result})

    (println (format "\n💾 Ring results saved to: %s" run-dir))
    ring-result))

(defn run-multi-epoch-simulation [params output-dir]
  (let [scenario-id         (:scenario-id params "unnamed")
        run-dir             (results/create-run-directory output-dir (str scenario-id "-multi-epoch"))
        rng                 (rng/make-rng (:rng-seed params))
        n-epochs            (get params :n-epochs 10)
        n-trials-per-epoch  (get params :n-trials-per-epoch 500)

        result (multi-epoch/run-multi-epoch rng n-epochs n-trials-per-epoch params)]

    (results/write-edn (format "%s/summary.edn" run-dir) result)
    (results/write-run-metadata (format "%s/metadata.edn" run-dir)
                                {:scenario-id scenario-id
                                 :type :multi-epoch
                                 :n-epochs n-epochs
                                 :n-trials-per-epoch n-trials-per-epoch
                                 :params params
                                 :result result})

    (println (format "\n💾 Multi-epoch results saved to: %s" run-dir))
    result))

(defn run-probabilistic-waterfall-simulation
  "Run probabilistic waterfall with MC dispute economics.

   Each dispute trial calls resolve-dispute with the full stochastic model,
   so slash amount, frequency, and reason reflect probabilistic detection,
   variable escrow sizes, and strategy-dependent behavior.

   Per-epoch caps (20% junior / 10% senior) are enforced per the contract invariant."
  [params output-dir]
  (let [scenario-id   (:scenario-id params "unnamed")
        scenario-name (:scenario-name params scenario-id)
        run-dir       (results/create-run-directory output-dir (str scenario-name "-probabilistic-waterfall"))
        rng-inst      (rng/make-rng (:rng-seed params 42))
        pool          (waterfall/initialize-waterfall-pool params)

        _ (println (format "\n🌊 Running probabilistic waterfall: %s" scenario-name))
        _ (println (format "   Mode: Monte Carlo dispute resolution"))
        _ (println (format "   Threshold: Coverage adequacy must be ≥80%% to pass"))
        _ (println "")
        _ (println (format "   Seniors: %d | Juniors: %d"
                           (:n-seniors params 5)
                           (* (:n-seniors params 5) (:n-juniors-per-senior params 10))))
        _ (println (format "   Trials: %d | Detection prob: %.1f%%"
                           (:n-trials params 1000)
                           (* 100 (:slashing-detection-probability params 0.10))))

        n-trials (get params :n-trials 1000)

        result (waterfall/probabilistic-process-slash-pool
                rng-inst pool params n-trials)

        metrics (:metrics result)
        ;; Bounded fraction-covered metric (see waterfall/aggregate-waterfall-metrics).
        ;; The historical :coverage-adequacy-score is preserved in the metrics map
        ;; but is a deficit-margin proxy that goes negative past exhaustion and is
        ;; no longer the pass/fail gate.
        adequacy (:coverage-adequacy-pct metrics)

        ;; Fixture-health: flag oracle exhaustion so stale calibrations
        ;; don't produce misleading results.  Exhaustion means the fixed
        ;; roll sequence was too short for at least one trial; repeat-last
        ;; masks this silently.
        exhausted-events (filter :oracle-exhausted? (:events result))
        fixture-exhausted? (seq exhausted-events)
        fixture-warnings (distinct (mapcat :oracle-warnings exhausted-events))
        pass?    (>= adequacy 80.0)]

    (println (format "\n   Slashed disputes: %d / %d (%.1f%%)"
                     (:total-slashes metrics) n-trials
                     (if (zero? n-trials) 0.0 (* 100.0 (/ (:total-slashes metrics) n-trials)))))
    (println (format "   Juniors exhausted: %.1f%%" (:juniors-exhausted-pct metrics)))
    (println (format "   Coverage used: %.1f%%" (:seniors-coverage-used-avg-pct metrics)))
    (println (format "   Adequacy score: %.1f%% (scale: 0–100)" adequacy))
    (println "")
    (when fixture-exhausted?
      (println (format "   ⚠️  ORACLE FIXTURE EXHAUSTED — %d/%d trials affected"
                       (count exhausted-events) (count (:events result))))
      (doseq [w (take 3 fixture-warnings)]
        (println (str "       " (:message w))))
      (println "   Results are INVALID for evidence runs. Increase fixture roll count."))
    (println "")
    (if fixture-exhausted?
      (println (format "   Status: ⚠️  INVALID (fixture exhausted — results unreliable)"))
      (if pass?
        (println (format "   Status: ✅ PASS (%.1f%% ≥ 80%% threshold)" adequacy))
        (println (format "   Status: ❌ FAIL (%.1f%% < 80%% threshold)" adequacy))))
    (println "")
    (when (and (not fixture-exhausted?) (< adequacy 80.0))
      (println "   Recommendations:")
      (println "   • Increase senior bond amounts or utilization-factor")
      (println "   • Reduce detection probability sensitivity")
      (println "   • Validate assumptions with higher fraud rates"))

    (results/write-edn (format "%s/summary.edn" run-dir) (select-keys result [:events :metrics]))
    (results/write-run-metadata (format "%s/metadata.edn" run-dir)
                                {:scenario-id scenario-id
                                 :type :probabilistic-waterfall
                                 :params params
                                 :result metrics})
    (println (format "\n💾 Results saved to: %s" run-dir))
    metrics))

(defn run-waterfall-simulation [params output-dir]
  (let [scenario-id   (:scenario-id params "unnamed")
        scenario-name (:scenario-name params scenario-id)
        run-dir       (results/create-run-directory output-dir (str scenario-name "-waterfall"))
        pool          (waterfall/initialize-waterfall-pool params)

        _ (println (format "\n🌊 Running waterfall stress test: %s" scenario-name))
        _ (println (format "   Hypothesis: Waterfall maintains >80%% coverage under fraud rate threshold"))
        _ (println (format "   Purpose: Verify senior/junior tier adequacy and pool solvency"))
        _ (println (format "   Threshold: Coverage adequacy must be ≥80%% to pass"))
        _ (println "")
        _ (println (format "   Seniors: %d | Juniors: %d"
                           (:n-seniors params 5)
                           (* (:n-seniors params 5) (:n-juniors-per-senior params 10))))
        _ (println (format "   Fraud rate: %.1f%% | Coverage multiplier: %.1f×"
                           (* 100 (:fraud-rate params 0.10))
                           (:coverage-multiplier params 3.0)))

        n-trials       (get params :n-trials 1000)
        n-fraud-events (int (* n-trials (:fraud-rate params 0.10)))

        slash-events (mapv (fn [i]
                             (let [n-juniors  (* (:n-seniors params 5) (:n-juniors-per-senior params 10))
                                   junior-idx (mod i n-juniors)
                                   senior-idx (int (/ junior-idx (:n-juniors-per-senior params 10)))]
                               {:resolver-id  (str "j" senior-idx "_" (mod junior-idx (:n-juniors-per-senior params 10)))
                                :senior-id    (str "s" senior-idx)
                                :slash-amount (waterfall/calculate-slash-amount
                                               (:junior-bond-amount params 500)
                                               (:fraud-slash-bps params 50))
                                :reason  :fraud
                                :epoch   (int (/ i 10))}))
                           (range n-fraud-events))

        result  (reduce (fn [state event]
                          (let [{:keys [resolvers seniors event-result]}
                                (waterfall/process-slash-event event
                                                               (:resolvers state)
                                                               (:seniors state))]
                            {:resolvers resolvers
                             :seniors seniors
                             :events (conj (:events state) event-result)}))
                        {:resolvers (:juniors pool) :seniors (:seniors pool) :events []}
                        slash-events)

        metrics (waterfall/aggregate-waterfall-metrics
                 (:resolvers result)
                 (:seniors result)
                 (:events result))

        summary {:scenario-id scenario-id
                 :scenario-name scenario-name
                 :type :waterfall
                 :params (select-keys params [:fraud-rate :coverage-multiplier :utilization-factor
                                              :n-seniors :n-juniors-per-senior
                                              :senior-bond-amount :junior-bond-amount])
                 :results metrics}]

    (let [adequacy (:coverage-adequacy-pct metrics)
          pass?    (>= adequacy 80.0)]
      (println (format "   Juniors exhausted: %.1f%%" (:juniors-exhausted-pct metrics)))
      (println (format "   Coverage used: %.1f%%" (:seniors-coverage-used-avg-pct metrics)))
      (println (format "   Adequacy score: %.1f%% (scale: 0–100)" adequacy))
      (println "")
      (if pass?
        (println (format "   Status: ✅ PASS (%.1f%% ≥ 80%% threshold)" adequacy))
        (println (format "   Status: ❌ FAIL (%.1f%% < 80%% threshold)" adequacy)))
      (println "")
      (if pass?
        (println "   Interpretation: Pool is well-provisioned for stated fraud rate.")
        (println "   Interpretation: ❌ CRITICAL — pool inadequate. Senior coverage insufficient."))
      (println "")
      (when (< adequacy 80.0)
        (println "   Recommendations:")
        (println "   • Increase senior bond amounts (currently tied to utilization-factor)")
        (println "   • Increase utilization-factor from current setting")
        (println "   • Reduce fraud-slash-bps to test lower thresholds")
        (println "   • Validate assumptions with higher fraud rates (target: 25%)")))

    (results/write-edn (format "%s/summary.edn" run-dir) summary)
    (results/write-run-metadata (format "%s/metadata.edn" run-dir)
                                {:scenario-id scenario-id
                                 :type :waterfall
                                 :params params
                                 :results metrics})

    (println (format "\n💾 Waterfall results saved to: %s" run-dir))
    summary))

(defn run-governance-impact-simulation [params output-dir]
  (let [scenario-id   (:scenario-id params "unnamed")
        scenario-name (:scenario-name params scenario-id)
        run-dir       (results/create-run-directory output-dir (str scenario-name "-governance-impact"))
        rng           (rng/make-rng (:seed params 42))
        n-epochs      (get params :n-epochs 10)
        n-trials      (get params :n-trials-per-epoch 500)

        _ (println (format "\n🏛️  Running governance impact test: %s" scenario-name))
        _ (println (format "   Governance response: %d days" (:governance-response-days params 3)))

        result (gov-impact/run-multi-epoch-governance-impact rng n-epochs n-trials params)

        summary {:scenario-id scenario-id
                 :scenario-name scenario-name
                 :type :governance-impact
                 :governance-response-days (:governance-response-days params 3)
                 :params (select-keys params [:governance-response-days :n-epochs :n-trials-per-epoch
                                              :slashing-detection-probability :strategy-mix])
                 :results (select-keys result [:epoch-results :governance-metrics :aggregated-stats])}]

    (println (format "   Final honest resolvers: %d" (get-in result [:aggregated-stats :honest-final-count])))
    (println (format "   Slashes executed: %d" (get-in result [:governance-metrics :total-pending-slashes-resolved])))
    (println (format "   Still pending: %d" (get-in result [:governance-metrics :pending-slashes-still-waiting])))
    (println (format "   Frozen resolvers: %d" (get-in result [:governance-metrics :frozen-resolvers])))

    (results/write-edn (format "%s/summary.edn" run-dir) summary)
    (results/write-run-metadata (format "%s/metadata.edn" run-dir)
                                {:scenario-id scenario-id
                                 :type :governance-impact
                                 :params params
                                 :results result})

    (println (format "\n💾 Governance impact results saved to: %s" run-dir))
    summary))

;; ---------------------------------------------------------------------------
;; Phase registry
;;
;; Maps CLI option key → [header-string run-fn].
;; run-fn signature: (fn [params output-dir])
;; header-string: printed before run-fn is called; nil means run-fn prints its own header.
;;
;; To add a new phase: add require above + entry here + cli-option in core.cli.
;; ---------------------------------------------------------------------------

(def phase-runners
  {:phase-p-lite    ["\n📊 Running Phase P Lite Falsification Test"
                     (fn [p _] (falsification-lite/run-phase-p-lite p))]
   :market-exit     ["\n🔄 Running Phase O Market Exit Cascade"
                     (fn [p _] (market-exit/run-phase-o-complete p))]
   :phase-y         ["\n🔬 Running Phase Y: Evidence Fog & Attention Budgets"
                     (fn [p _] (evidence-fog/run-phase-y-sweep p))]
   :phase-z         ["\n🔄 Running Phase Z: Legitimacy & Reflexive Participation"
                     (fn [p _] (legitimacy-loop/run-phase-z-sweep p))]
   :phase-aa        ["\n🏛️  Running Phase AA: Governance as Adversary"
                     (fn [p _] (adversary/run-phase-aa-sweep p))]
   :phase-ac        ["\n🔄 Running Phase AC: Trust Floor & Emergency Onboarding"
                     (fn [p _] (trust-floor/run-phase-ac-sweep p))]
   :phase-ad        ["\n🏛️  Running Phase AD: Governance Bandwidth Floor"
                     (fn [p _] (bandwidth-floor/run-phase-ad-sweep p))]
   :phase-ae        [nil (fn [p _] (fair-slashing/run-phase-ae p))]
   :phase-af        [nil (fn [p _] (epoch-solvency/run-phase-af p))]
   :phase-ag        [nil (fn [p _] (ema-convergence/run-phase-ag p))]
   :phase-ah        [nil (fn [p _] (equity-divergence/run-phase-ah p))]
   :phase-ai        [nil (fn [p _] (escalation-trap/run-phase-ai p))]
   :phase-f         [nil (fn [p _] (collusion-ring/run-phase-f p))]
   :phase-ac-sweep  ["\n🔬 Running Phase AC Threshold Search"
                     (fn [p _] (trust-floor/run-phase-ac-threshold-sweep p))]
   :phase-ad-sweep  ["\n🔬 Running Phase AD Threshold Search"
                     (fn [p _] (bandwidth-floor/run-phase-ad-threshold-sweep p))]
   :phase-ac-cap    ["\n🔬 Running Phase AC Capacity Expansion"
                     (fn [p _] (trust-floor/run-phase-ac-capacity-expansion p))]
   :phase-t         ["\n🏛️  Running Phase T: Governance Capture via Rule Drift"
                     (fn [p _] (capture-drift/run-phase-t-sweep p))]
   :phase-p-revised ["\n📊 Running Phase P Revised: Sequential Appeal Falsification"
                     (fn [_ _] (falsification-revised/run-phase-p-revised-sweep))]
   :phase-q         ["\n🔬 Running Phase Q: Advanced Vulnerability"
                     (fn [_ _] (advanced-vulnerability/run-phase-q-sweep))]
   :phase-r         ["\n🔬 Running Phase R: Liveness & Participation Failure"
                     (fn [_ _] (liveness-participation/run-phase-r-sweep))]
   :phase-u         ["\n🎯 Running Phase U: Adaptive Attacker Learning"
                     (fn [_ _] (adaptive-attacker/run-phase-u-sweep))]
   :phase-v         ["\n🌊 Running Phase V: Correlated Belief Cascades"
                     (fn [_ _] (belief-cascades/run-phase-v-sweep))]
   :phase-w         ["\n🎯 Running Phase W: Dispute Type Clustering"
                     (fn [_ _] (dispute-clustering/run-phase-w-sweep))]
   :phase-x         ["\n💥 Running Phase X: Burst Concurrency Exploit"
                     (fn [_ _] (burst-concurrency/run-phase-x-sweep))]
   :governance-impact [nil run-governance-impact-simulation]
   :waterfall              [nil run-waterfall-simulation]
   :probabilistic-waterfall [nil run-probabilistic-waterfall-simulation]
   :multi-epoch       [nil run-multi-epoch-simulation]
   :sweep             [nil run-sweep]
   :adversarial       [nil (fn [p _] (adversarial/run-adversarial-search p))]
   :phase-f-dr        ["\n💰 Running Phase F: Dispute Resolution Economic Parameters"
                       (fn [p _] (phase-f/run-phase-f-sweep))]
   :phase-c-dr        ["\n🔐 Running Phase C: Dispute Resolution Corruption Economics"
                       (fn [p _] (phase-c/run-phase-c-sweep))]
   :phase-e-dr        ["\n📋 Running Phase E: Dispute Resolution Evidence Integrity"
                       (fn [p _] (phase-e/run-phase-e-sweep))]
   :phase-m-dr        ["\n⚖️  Running Phase M: Dispute Resolution Fairness Analysis"
                       (fn [p _] (phase-m/run-phase-m-sweep))]})
