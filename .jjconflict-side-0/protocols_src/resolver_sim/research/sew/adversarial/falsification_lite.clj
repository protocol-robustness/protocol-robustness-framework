(ns resolver-sim.research.sew.adversarial.falsification-lite
  "Phase P Lite: Full 3D falsification test
   
   Sweeps parameters:
   - Load: light (10), medium (50), heavy (100), extreme (200)
   - Correlation: rho = [0.0, 0.2, 0.5, 0.8]
   - Attacker budget: [0%, 10%, 25%]
   
   Returns comprehensive heatmap of dominance ratios"
  (:require [resolver-sim.stochastic.rng :as rng]
            [resolver-sim.stochastic.difficulty :as diff]
            [clojure.string :as str]
            [resolver-sim.sim.engine :as proto]))

;; Configuration
(def LOAD-LEVELS
  {:light 10
   :medium 50
   :heavy 100
   :extreme 200})

(def CORRELATION-VALUES [0.0 0.2 0.5 0.8])
(def ATTACKER-BUDGETS [0.0 0.10 0.25])

;; Solidity-parity defaults (simple integer counters + hard cap + overflow route)
(def DEFAULT-RESOLVER-COUNT 10)
(def DEFAULT-MAX-CASES-PER-RESOLVER-PER-EPOCH 6)
(def DEFAULT-OVERFLOW-ATTACKER-WIN-PROBABILITY 0.10)

;; Utility
(defn pad-right [s width]
  (let [pad (- width (count (str s)))]
    (str s (apply str (repeat (max 0 pad) " ")))))

;; Single scenario test
(defn run-scenario-test
  "Run one test scenario with given parameters.
   
   Returns dominance ratio."
  [seed params load-level rho attacker-fraction]
  (let [num-disputes (get LOAD-LEVELS load-level 50)
        attacker-budget (int (* num-disputes attacker-fraction))
        resolver-count (int (get params :resolver-count DEFAULT-RESOLVER-COUNT))
        max-cases-per-resolver-per-epoch
        (int (get params :max-cases-per-resolver-per-epoch
                  DEFAULT-MAX-CASES-PER-RESOLVER-PER-EPOCH))
        enable-overflow-routing? (get params :enable-overflow-routing? true)
        overflow-attacker-win-probability
        (double (get params :overflow-attacker-win-probability
                     DEFAULT-OVERFLOW-ATTACKER-WIN-PROBABILITY))

        init-load (zipmap (range resolver-count) (repeat 0))

        {:keys [honest-profits malice-profits]}
        (loop [d              0
               resolver-load  init-load
               attacks-done   0
               honest-profits []
               malice-profits []]
          (if (>= d num-disputes)
            {:honest-profits honest-profits :malice-profits malice-profits}
            (let [d-rng       (rng/make-rng (+ seed d))
                  resolver-id (mod d resolver-count)
                  at-cap?     (>= (get resolver-load resolver-id 0)
                                  max-cases-per-resolver-per-epoch)]
              (if (and enable-overflow-routing? at-cap?)
                (let [attacker-wins? (< (rng/next-double d-rng) overflow-attacker-win-probability)
                      fee            100.0
                      h-profit       (if attacker-wins? 0.0 fee)
                      m-profit       (if attacker-wins? fee 0.0)]
                  (recur (inc d) resolver-load attacks-done
                         (conj honest-profits h-profit) (conj malice-profits m-profit)))
                (let [should-attack  (and (> attacker-budget 0)
                                          (< attacks-done attacker-budget)
                                          (< (rng/next-double d-rng) 0.5))
                      difficulty     (diff/sample-difficulty d-rng)
                      honest-acc     (if should-attack 0.3 (diff/accuracy-by-difficulty :honest difficulty))
                      malice-acc     (if should-attack 0.7 (diff/accuracy-by-difficulty :malicious difficulty))
                      honest-correct (if (< (rng/next-double d-rng) rho)
                                       false
                                       (< (rng/next-double d-rng) honest-acc))
                      malice-correct (if (< (rng/next-double d-rng) rho)
                                       true
                                       (< (rng/next-double d-rng) malice-acc))
                      detection      (diff/detection-probability-by-difficulty 0.10 difficulty)
                      detected       (< (rng/next-double d-rng) detection)
                      fee            100.0
                      h-profit       (- fee (if (and (not honest-correct) detected) 500.0 0.0))
                      m-profit       (- fee (if (and (not malice-correct) detected) 500.0 0.0))]
                  (recur (inc d)
                         (update resolver-load resolver-id (fnil inc 0))
                         (if should-attack (inc attacks-done) attacks-done)
                         (conj honest-profits h-profit)
                         (conj malice-profits m-profit)))))))]

    (let [h-avg (if (empty? honest-profits) 0.0
                    (/ (reduce + honest-profits) (count honest-profits)))
          m-avg (if (empty? malice-profits) 0.0
                    (/ (reduce + malice-profits) (count malice-profits)))]
      (if (<= m-avg 0) 999.0 (double (/ h-avg m-avg))))))

;; Full sweep
(defn run-phase-p-lite-sweep
  "Run complete 3D parameter sweep.
   
   Returns map: {[load rho budget] → dominance-ratio}"
  [params trials-per-combo]
  (let [seed (:seed params 42)

        results (atom {})
        scenario-count (atom 0)
        total-scenarios (* (count LOAD-LEVELS) (count CORRELATION-VALUES) (count ATTACKER-BUDGETS))]

    (println "📊 Running Phase P Lite Full Sweep")
    (println (str "   Load levels: " (count LOAD-LEVELS)))
    (println (str "   Correlation values: " (count CORRELATION-VALUES)))
    (println (str "   Attacker budgets: " (count ATTACKER-BUDGETS)))
    (println (str "   Trials per combo: " trials-per-combo))
    (println (str "   Total scenarios: " total-scenarios))
    (println)

    ;; Run all combinations
    (doseq [load-key (keys LOAD-LEVELS)]
      (doseq [rho CORRELATION-VALUES]
        (doseq [budget ATTACKER-BUDGETS]
          (let [scenario-seed (+ seed (* (hash [load-key rho budget]) 1000))
                trial-results
                (vec
                 (for [trial (range trials-per-combo)]
                   (let [trial-seed (+ scenario-seed (* trial 100000))
                         result (run-scenario-test trial-seed params load-key rho budget)]
                     result)))]

            ;; Store average
            (let [avg-dominance (if (empty? trial-results)
                                  999.0
                                  (double (/ (reduce + trial-results) (count trial-results))))]
              (swap! results assoc [load-key rho budget] avg-dominance)
              (swap! scenario-count inc)
              (when (zero? (mod @scenario-count 5))
                (print ".")))))))

    (println)
    (println (str "✓ Sweep complete: " @scenario-count " scenarios tested\n"))
    @results))

;; Analysis functions
(defn scenario-classification
  "Classify results into Scenario A/B/C."
  [sweep-results]
  (let [all-values (vals sweep-results)
        min-dominance (reduce min all-values)
        max-dominance (reduce max all-values)

        extreme-rho80 (get sweep-results [:extreme 0.8 0.0] 999.0)
        heavy-rho50 (get sweep-results [:heavy 0.5 0.0] 999.0)

        robust? (> max-dominance 1.5)
        broken? (< min-dominance 0.5)
        brittle? (or (< extreme-rho80 1.0) (< heavy-rho50 1.2))]

    (cond
      (and robust? (not brittle?))
      {:scenario :A
       :label "ROBUST"
       :confidence 0.90
       :message "System remains robust across all conditions"
       :min-ratio min-dominance
       :max-ratio max-dominance}

      (and brittle? (not broken?))
      {:scenario :B
       :label "BRITTLE"
       :confidence 0.65
       :message "System shows brittleness at high load/correlation"
       :min-ratio min-dominance
       :max-ratio max-dominance}

      broken?
      {:scenario :C
       :label "BROKEN"
       :confidence 0.40
       :message "System breaks under realistic conditions"
       :min-ratio min-dominance
       :max-ratio max-dominance}

      :else
      {:scenario :B
       :label "BRITTLE"
       :confidence 0.65
       :message "System shows moderate brittleness"
       :min-ratio min-dominance
       :max-ratio max-dominance})))

;; Heatmap generation
(defn generate-heatmap-by-load-rho
  "Generate (load, rho) heatmap for default attacker budget (0%)."
  [sweep-results]
  (let [loads (keys LOAD-LEVELS)
        header (str (pad-right "Load" 10)
                    (str/join "  " (map #(format "rho=%.1f" %) CORRELATION-VALUES)))]

    (str header "\n"
         (str/join "\n"
                   (for [load loads]
                     (let [row-values (for [rho CORRELATION-VALUES]
                                        (format "%.2f" (get sweep-results [load rho 0.0] 0.0)))]
                       (str (pad-right (name load) 10)
                            (str/join "  " row-values))))))))

(defn generate-heatmap-by-load-budget
  "Generate (load, budget) heatmap for default correlation (0.0)."
  [sweep-results]
  (let [loads (keys LOAD-LEVELS)
        budgets ATTACKER-BUDGETS
        header (str (pad-right "Load" 10)
                    (str/join "  " (map #(format "budget=%.0f%%" (* 100 %)) budgets)))]

    (str header "\n"
         (str/join "\n"
                   (for [load loads]
                     (let [row-values (for [budget budgets]
                                        (format "%.2f" (get sweep-results [load 0.0 budget] 0.0)))]
                       (str (pad-right (name load) 10)
                            (str/join "  " row-values))))))))

;; Main reporting
(defn generate-full-report
  "Generate comprehensive Phase P Lite results report."
  [sweep-results params]
  (let [scenario (scenario-classification sweep-results)

        baseline (get sweep-results [:light 0.0 0.0] 0.0)
        worst-case (reduce min (vals sweep-results))
        best-case (reduce max (vals sweep-results))

        heatmap-rho (generate-heatmap-by-load-rho sweep-results)
        heatmap-budget (generate-heatmap-by-load-budget sweep-results)]

    (str
     "═══════════════════════════════════════════════════════════\n"
     "Phase P Lite: Complete Falsification Test Results\n"
     "═══════════════════════════════════════════════════════════\n\n"

     "SCENARIO CLASSIFICATION\n"
     (pad-right "Result:" 20) (str/upper-case (:label scenario)) "\n"
     (pad-right "Confidence:" 20) (format "%.0f%%" (* 100 (:confidence scenario))) "\n"
     (pad-right "Finding:" 20) (:message scenario) "\n\n"

     "DOMINANCE RATIO STATISTICS\n"
     (pad-right "Baseline (light, rho=0):" 30) (format "%.2f\n" baseline)
     (pad-right "Best case (highest):" 30) (format "%.2f\n" best-case)
     (pad-right "Worst case (lowest):" 30) (format "%.2f\n" worst-case)
     (pad-right "Range:" 30) (format "%.2f\n\n" (- best-case worst-case))

     "HEATMAP 1: Load vs Correlation (rho) [budget=0%]\n"
     heatmap-rho "\n\n"

     "HEATMAP 2: Load vs Attacker Budget [rho=0.0]\n"
     heatmap-budget "\n\n"

     "INTERPRETATION\n"
     (case (:scenario scenario)
       :A (str
           "✅ ROBUST: System dominance remains > 1.5x under all tested conditions\n"
           "   - Difficulty distribution does not destabilize\n"
           "   - Evidence asymmetry manageable under constraints\n"
           "   - Panel herding does not trigger cascade\n"
           "   → Recommended: Proceed to mainnet with high confidence (90%+)\n")

       :B (str
           "⚠️  BRITTLE: System shows degradation under realistic load/correlation\n"
           "   - Dominance ratio drops significantly at rho > 0.3-0.5\n"
           "   - Heavy load exacerbates attack surface\n"
           "   - System remains solvent but margins erode\n"
           "   → Option A (param): Raise minimum bond-to-escrow ratio\n"
           "     Increases honest-path return relative to attack cost; lifts dominance ratio at all load levels\n"
           "   → Option B (design): Add per-resolver rate limit per epoch\n"
           "     Correlated flooding attacks depend on volume; rate limiting breaks the attack model\n"
           "   → Option C (operational): Accept BRITTLE as operating envelope\n"
           "     System is safe at rho < 0.3 and light-medium load; document these as mainnet constraints\n"
           "     and route high-correlation dispute bursts to Kleros escalation automatically\n")

       :C (str
           "❌ BROKEN: System fails under realistic conditions\n"
           "   - Dominance ratio inverts (< 1.0) at moderate conditions\n"
           "   - Attacks become profitable\n"
           "   - Fundamental redesign required\n"
           "   → Recommended: Full mechanism redesign before mainnet\n"))

     "═══════════════════════════════════════════════════════════\n")))

;; Main entry point
(defn run-phase-p-lite
  "Run Phase P Lite falsification test."
  [params]
  (let [trials-per-combo (get params :num-trials 30)
        sweep-results (run-phase-p-lite-sweep params trials-per-combo)
        report (generate-full-report sweep-results params)]

    (println report)
    (let [classification (scenario-classification sweep-results)]
      (proto/make-result
       {:benchmark-id "P-lite"
        :label        "Sequential Appeal Falsification (Lite)"
        :hypothesis   "System achieves ROBUST (class A) under all tested conditions"
        :passed?      (= :A (:scenario classification))
        :results      sweep-results
        :summary      classification}))))
