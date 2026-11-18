(ns resolver-sim.research.sew.adversarial.falsification-revised
  "Phase P Revised: Falsification test for sequential appeal system.
   
   Tests the CORRECT model:
   - Single-resolver sequential appeals (not parallel panel)
   - Information cascades (not herding coordination)
   - Escalation economics (not panel voting)
   
   This replaces Phase P Lite which modeled wrong system architecture."
  (:require [resolver-sim.stochastic.rng :as rng]
            [resolver-sim.stochastic.decision-quality :as dq]
            [resolver-sim.protocols.sew.research-models.information-cascade :as ic]
            [resolver-sim.protocols.sew.research-models.escalation-economics :as ee]
            [resolver-sim.protocols.sew.config :as config]
            [resolver-sim.sim.engine :as proto]
            [resolver-sim.util.evidence :as ev]))

;; ============ Test Parameters ============

(def TIME_PRESSURE_LEVELS
  "How much time pressure resolvers face.
   1.0 = standard (24h R0, 48h R1, unlimited R2)"
  [0.5    ; Abundant time
   1.0    ; Standard time
   1.5])  ; High pressure

(def REPUTATION_WEIGHTS
  "How much reputation pressure affects decisions.
   1.0 = strong reputation incentive (avoid dissent)"
  [0.0    ; No reputation effect
   0.3    ; Low
   0.6])  ; High

(def EVIDENCE_QUALITY_LEVELS
  "How clear is the ground truth in this case.
   1.0 = obvious truth, 0.0 = totally ambiguous"
  [0.3    ; Hard case (evidence ambiguous)
   0.6    ; Medium case
   1.0])  ; Easy case (clear ground truth)

(def APPEAL_PROBABILITIES
  "What fraction of decisions get appealed?
   Affects escalation cascade risk."
  [0.1    ; Few appeals (most accept initial decision)
   0.3    ; Moderate appeals
   0.5])  ; High appeals (everything gets challenged)

;; ============ Single Test Scenario ============

(defn run-single-test
  "Run one complete test scenario.
   
   Tests: Sequential appeal under specific conditions
   
   Args:
   - seed: RNG seed
   - time-pressure: [0.5 - 2.0]
   - reputation-weight: [0.0 - 1.0]
   - evidence-quality: [0.0 - 1.0]
   - appeal-probability: [0.0 - 1.0]
   - num-trials: Number of disputes to simulate
   
   Returns: {:honest-win-rate, :cascade-risk, :findings}"
  [seed time-pressure reputation-weight evidence-quality appeal-probability num-trials]

  (let [rng (rng/make-rng seed)
        config config/DEFAULT_ESCALATION_CONFIG

        ; Run multiple disputes
        results (for [trial-idx (range num-trials)]
                  (let [; Randomize parameters per trial
                        ground-truth (< (rng/next-double rng) 0.5)
                        difficulty (nth ["easy" "medium" "hard"]
                                        (mod (long (* (rng/next-double rng) 3)) 3))

                        ; Simulate full appeal
                        appeal (dq/simulate-full-appeal
                                rng ground-truth
                                [true false false]  ; Honest R0, others honest too
                                [time-pressure time-pressure time-pressure]
                                difficulty evidence-quality
                                :appeal-threshold appeal-probability)

                        ; Analyze cascade risk
                        cascade-analysis (ic/analyze-cascade-trajectory
                                          ground-truth
                                          [(:decision (:round-0 appeal))
                                           (if (:round-1 appeal)
                                             (:decision (:round-1 appeal))
                                             nil)]
                                          reputation-weight
                                          evidence-quality)]

                    {:correct (:final-decision appeal)
                     :ground-truth ground-truth
                     :error (:was-error appeal)
                     :escalations (:escalation-count appeal)
                     :cascade-risk (:cascade-risk cascade-analysis)
                     :difficulty difficulty}))

        ; Aggregate results
        correct-count (count (filter #(= (:correct %) (:ground-truth %)) results))
        error-rate (/ (count (filter :error results)) num-trials)
        avg-escalations (/ (apply + (map :escalations results))
                           num-trials)
        avg-cascade-risk (/ (apply + (map :cascade-risk results))
                            num-trials)

        ; Cascade analysis
        hard-cases (filter #(= (:difficulty %) "hard") results)
        hard-error-rate (if (seq hard-cases)
                          (/ (count (filter :error hard-cases))
                             (count hard-cases))
                          0.0)

        ; Scenario classification
        scenario-class (cond
                         (< error-rate 0.15) "A"  ; Robust
                         (< error-rate 0.25) "B"  ; Acceptable
                         :else "C")]  ; Fragile

    {:scenario-class scenario-class
     :correct-rate (/ correct-count num-trials)
     :error-rate error-rate
     :hard-case-error-rate hard-error-rate
     :avg-escalations avg-escalations
     :avg-cascade-risk avg-cascade-risk
     :reputation-weight reputation-weight
     :evidence-quality evidence-quality
     :time-pressure time-pressure
     :appeal-probability appeal-probability
     :num-trials num-trials
     :findings {:system-type "Sequential Appeal"
                :vulnerability-type "Information Cascades"
                :mitigation (if (> avg-cascade-risk 0.4)
                              "High cascade risk - use reputation weighting or evidence oracles"
                              "Cascade risk acceptable")}}))

;; ============ Full Test Suite ============

(defn run-phase-p-revised-sweep
  "Run full 3D parameter sweep for Phase P Revised.
   
   Tests all combinations of:
   - Time pressure
   - Reputation weight  
   - Evidence quality
   - Appeal probability
   
   Returns: Results matrix and analysis"
  []

  (println "\n📊 Running Phase P Revised: Sequential Appeal System")
  (println "   Testing: Information cascades, escalation economics, sequential corruption")
  (println "")

  (let [; Generate all combinations
        scenarios (for [tp TIME_PRESSURE_LEVELS
                        rw REPUTATION_WEIGHTS
                        eq EVIDENCE_QUALITY_LEVELS
                        ap APPEAL_PROBABILITIES]
                    {:time-pressure tp
                     :reputation-weight rw
                     :evidence-quality eq
                     :appeal-probability ap})

        ; Run all scenarios
        results (ev/contextual-pmap (fn [s]
                                      (run-single-test 42
                                                       (:time-pressure s)
                                                       (:reputation-weight s)
                                                       (:evidence-quality s)
                                                       (:appeal-probability s)
                                                       100))  ; 100 trials per scenario
                                    scenarios)

        ; Classify scenarios
        class-a (count (filter #(= (:scenario-class %) "A") results))
        class-b (count (filter #(= (:scenario-class %) "B") results))
        class-c (count (filter #(= (:scenario-class %) "C") results))

        ; Summarize findings
        summary {:total-scenarios (count results)
                 :scenario-a-count class-a
                 :scenario-b-count class-b
                 :scenario-c-count class-c
                 :avg-cascade-risk (/ (apply + (map :avg-cascade-risk results))
                                      (count results))
                 :worst-error-rate (apply max (map :error-rate results))
                 :best-error-rate (apply min (map :error-rate results))}]

    (println "✓ Sweep complete. Results by scenario:")
    (println "  Scenario A (Robust):     " class-a " scenarios")
    (println "  Scenario B (Acceptable): " class-b " scenarios")
    (println "  Scenario C (Fragile):    " class-c " scenarios")
    (println "")
    (println "Key metrics:")
    (println "  Avg cascade risk: " (format "%.2f" (double (:avg-cascade-risk summary))))
    (let [best (* (:best-error-rate summary) 100.0)
          worst (* (:worst-error-rate summary) 100.0)]
      (println "  Error range: " (format "%.1f" best) "% - " (format "%.1f" worst) "%"))
    (println "")

    {:summary summary
     :results results}))

;; ============ Focused Test: Cascade Risk ============

(defn analyze-cascade-vulnerability
  "Deep dive: How vulnerable is the system to information cascades?
   
   Tests specific cascade scenarios:
   - Wrong Round 0 decision
   - Does Round 1 detect it?
   - Does Round 2 correct it?
   
   Returns: Cascade analysis"
  []

  (println "\n🔍 Analyzing Information Cascade Vulnerability")
  (println "")

  (let [rng (rng/make-rng 42)

        ; Test case: Hard case with ambiguous evidence
        ground-truth true
        difficulty "hard"
        evidence-quality 0.3  ; Ambiguous

        ; Simulate wrong R0 decision but plausible
        wrong-decision false  ; Wrong, but seems reasonable

        ; Analyze cascade risk across different conditions
        cascade-tests (for [rw [0.0 0.3 0.6 1.0]
                            ap [0.1 0.3 0.5]]
                        (let [cascade-risk (ic/information-cascade-risk
                                            3 rw evidence-quality)

                            ; Can it break?
                              break-prob (ic/detect-cascade-error
                                          2  ; Two rounds in cascade
                                          evidence-quality
                                          1.0)]  ; Normal time

                          {:reputation-weight rw
                           :appeal-probability ap
                           :cascade-risk cascade-risk
                           :break-probability break-prob
                           :vulnerable? (> cascade-risk 0.4)}))]

    (println "Cascade analysis (hard case, ambiguous evidence):")
    (println "")
    (doseq [test cascade-tests]
      (println (format "  Reputation=%.1f Appeal=%.1f → Risk=%.2f Break=%.2f %s"
                       (:reputation-weight test)
                       (:appeal-probability test)
                       (:cascade-risk test)
                       (:break-probability test)
                       (if (:vulnerable? test) "⚠️ VULNERABLE" "✓ OK"))))
    (println "")

    (proto/make-result
     {:benchmark-id "P-revised"
      :label        "Sequential Appeal Falsification (Revised)"
      :hypothesis   "Information cascades occur with friction; system not broken at moderate correlation"
      :passed?      (not-any? :vulnerable? cascade-tests)
      :results      cascade-tests
      :summary      {:vulnerable-count (count (filter :vulnerable? cascade-tests))
                     :total-count (count cascade-tests)}})))

;; ============ Comparative Analysis ============

(defn compare-to-phase-p-lite
  "Compare Phase P Revised findings to invalid Phase P Lite findings.
   
   Shows why model mismatch matters."
  []

  (println "\n📊 Phase P Revised vs Phase P Lite: Model Comparison")
  (println "")
  (println "Architecture:")
  (println "  Phase P Lite: 3-resolver PARALLEL panel voting")
  (println "  Phase P Revised: 1-resolver SEQUENTIAL appeals")
  (println "")
  (println "Vulnerability Type:")
  (println "  Phase P Lite: Herding cascade (impossible in sequential)")
  (println "  Phase P Revised: Information cascade (realistic in sequential)")
  (println "")
  (println "Mechanism:")
  (println "  Phase P Lite: Attacker corrupts 2/3 to break majority")
  (println "  Phase P Revised: Attacker corrupts each level independently")
  (println "")
  (println "Result:")
  (println "  Phase P Lite: System breaks at rho ≥ 0.5")
  (println "  Phase P Revised: System has cascade risk but with friction")
  (println "")
  (println "Confidence Impact:")
  (println "  Phase P Lite: 99% → 40% (INVALID - wrong model)")
  (println "  Phase P Revised: 99% → ? (actual findings TBD)")
  (println "")

  {})
