(ns resolver-sim.research.sew.adversarial.liveness-participation
  "Phase R: Liveness & Participation Failure Testing.
   
   Tests whether system can fail not through attacks or mechanism flaws,
   but through LIVENESS failure: 'The system is solvent but nobody shows up.'
   
   Failure modes tested:
   1. Juror opportunity cost → Dropout when better yields elsewhere
   2. Boredom → Cognitive fatigue from trivial cases
   3. Adverse selection → Only aggressive resolvers remain
   4. Latency sensitivity → Users leave if decisions too slow
   5. Participation spiral → Reflexive death spiral
   
   Key insight: Economic incentives alone don't guarantee liveness.
   You need active participation ecosystem design.

   ARCHITECTURAL BOUNDARY: Phase R is an EXPLORATORY phase — its output is
   display-only (console) and is not persisted, certified, hashed, or included
   in any attestation/bundle-root pipeline.  The saturation fields propagated by
   test-latency-sensitivity (:saturation-queue-days / :saturation-queue /
   :saturation-satisfied / :liveness/wait-capped?) are DIAGNOSTIC: their
   consistency is verified at test time (check-saturation-invariant) and they
   make no evidence-time claim."
  (:require [resolver-sim.stochastic.liveness-failures :as liveness]
            [resolver-sim.stochastic.rng :as rng]
            [resolver-sim.sim.engine      :as proto]))

;; ============ Test 1: Opportunity Cost ============

(defn test-opportunity-cost
  "Test: At what external yield do resolvers give up?
   
   Scenarios: Different base yields, fixed resolution rewards.
   Question: What's the break-even?
   
   Returns: Analysis of exit risk"
  []

  (println "\n🚪 LIVENESS TEST 1: OPPORTUNITY COST")
  (println "   When does 'staking elsewhere' beat dispute resolution?")
  (println "")

  (let [base-yields [0.05 0.10 0.15 0.20]  ; 5%-20% APY elsewhere
        dispute-rewards [1000 2000 5000]    ; Resolution rewards

        results (for [base-yield base-yields
                      reward dispute-rewards]

                  (let [num-disputes 4  ; 4 disputes/week

                        {:keys [net-surplus willing? reason]}
                        (liveness/juror-opportunity-cost base-yield reward 100 num-disputes)

                        class (if willing? "A" "B")]

                    {:base-yield base-yield
                     :reward reward
                     :net-surplus net-surplus
                     :willing? willing?
                     :reason reason
                     :class class}))]

    ; Summary
    (let [willing (count (filter :willing? results))
          total (count results)]
      (println "  Results: " willing "/" total " scenarios have positive incentive")
      (println "")
      (doseq [r (filter (fn [r] (> (:base-yield r) 0.10)) results)]
        (let [status (if (:willing? r) "✅ WILLING" "❌ EXIT")]
          (println (str "    Base yield: " (format "%.0f" (double (* (:base-yield r) 100)))
                        "%, Reward: $" (:reward r) " → " status))))

      results)))

;; ============ Test 2: Boredom & Cognitive Fatigue ============

(defn test-boredom-threshold
  "Test: Can resolvers handle an unending stream of trivial cases?
   
   Scenario: 10 cases/week, mostly boring (simple disputes).
   Question: What's the dropout rate?
   
   Returns: Participation retention by case difficulty"
  []

  (println "\n😴 LIVENESS TEST 2: COGNITIVE FATIGUE")
  (println "   Can resolvers handle weeks of boring cases?")
  (println "")

  (let [case-difficulties [0.2 0.5 0.8]  ; Easy, medium, hard
        cases-per-week 10
        cognitive-limit 50  ; Max cognitive load per week
        d-rng (rng/make-rng 42)

        results (for [difficulty case-difficulties]

                  (let [{:keys [dropout-risk will-participate? verdict]}
                        (liveness/boredom-threshold difficulty cognitive-limit cases-per-week 0.2 d-rng)

                        class (cond
                                (> dropout-risk 0.5) "C"
                                (> dropout-risk 0.2) "B"
                                :else "A")]

                    {:difficulty difficulty
                     :dropout-risk dropout-risk
                     :will-participate? will-participate?
                     :verdict verdict
                     :class class}))]

    ; Summary
    (println "  Dropout risk by case difficulty:")
    (doseq [r results]
      (let [risk-emoji (cond (> (:dropout-risk r) 0.5) "❌"
                             (> (:dropout-risk r) 0.2) "⚠️"
                             :else "✅")]
        (println (str "    Difficulty " (format "%.1f" (double (:difficulty r)))
                      ": " risk-emoji " " (format "%.0f" (double (* (:dropout-risk r) 100))) "% dropout"))))

    results))

;; ============ Test 3: Adverse Selection ============

(defn test-adverse-selection
  "Test: When resolvers leave, does the remaining pool degrade?
   
   Scenario: Start with balanced resolver pool (50% risk-averse, 50% risk-seeking).
   If 30% dropout, what's the composition after?
   
   Returns: Pool degradation and accuracy impact"
  []

  (println "\n⚖️  LIVENESS TEST 3: ADVERSE SELECTION")
  (println "   When resolvers drop out, who leaves and who stays?")
  (println "")

  (let [initial-pool 100
        dropout-rates [0.1 0.3 0.5]
        risk-aversion-dist 0.5  ; 50% risk-averse

        results (for [dropout-rate dropout-rates]

                  (let [{:keys [remaining-total new-seeking-fraction accuracy-degradation risk-verdict]}
                        (liveness/adverse-selection-effect initial-pool dropout-rate risk-aversion-dist)

                        class (cond
                                (< remaining-total 10) "C"
                                (> new-seeking-fraction 0.7) "C"
                                (> accuracy-degradation 0.1) "B"
                                :else "A")]

                    {:dropout-rate dropout-rate
                     :remaining remaining-total
                     :seeking-fraction new-seeking-fraction
                     :accuracy-loss accuracy-degradation
                     :verdict risk-verdict
                     :class class}))]

    ; Summary
    (println "  Pool composition after dropout:")
    (doseq [r results]
      (let [emoji (case (:class r) "A" "✅" "B" "⚠️" "C" "❌")]
        (println (str "    " (format "%.0f" (double (* (:dropout-rate r) 100)))
                      "% dropout → " emoji " " (int (:remaining r)) " remaining, "
                      (format "%.0f" (double (* (:seeking-fraction r) 100))) "% risk-seeking"))))

    results))

;; ============ Test 4: Latency Sensitivity ============

(defn test-latency-sensitivity
  "Test: Do users leave if disputes take too long?
   
   Scenario: System with varying loads.
   Question: At what wait time do users start exiting?
   
   Returns: User retention by latency"
  []

  (println "\n⏱️  LIVENESS TEST 4: LATENCY SENSITIVITY")
  (println "   Do users abandon system if disputes take too long?")
  (println "")

  (let [dispute-volumes [10 20 30 40]  ; Disputes per week
        resolvers 20
        patience-threshold 7  ; Days acceptable

        results (for [volume dispute-volumes]

                  (let [result (liveness/latency-sensitivity volume resolvers 5 patience-threshold)
                        {:keys [queue-wait-days user-retention-rate retained-volume verdict spiral-effect
                                saturation-queue-days saturation-queue saturation-satisfied]} result

                        class (cond
                                (> queue-wait-days 14) "C"
                                (> queue-wait-days 7) "B"
                                :else "A")]

                    {:volume volume
                     :wait-days queue-wait-days
                     :retention-rate user-retention-rate
                     :retained-volume retained-volume
                     :spiral spiral-effect
                     :saturation-queue-days saturation-queue-days
                     :saturation-queue saturation-queue
                     :saturation-satisfied saturation-satisfied
                     :liveness/wait-capped? (:liveness/wait-capped? result)
                     :verdict verdict
                     :class class}))]

    ; Summary
    (println "  User retention by latency:")
    (doseq [r results]
      (let [status (case (:verdict r)
                     "CRITICAL: System broken" "❌ BROKEN"
                     "SEVERE: Users leaving" "❌ SEVERE"
                     "SERIOUS: Latency problem" "⚠️ PROBLEM"
                     "OK: Within tolerance" "✅ OK")]
        (println (str "    " (int (:volume r)) " disputes → "
                      (int (:wait-days r)) "d wait → " status))))

    results))

;; ============ Test 5: Participation Spiral ============

(defn test-participation-spiral
  "Test: Can the system enter a death spiral?
   
   Scenario: Start with healthy system, then hit stress event.
   Can it recover, or does it spiral down?
   
   Returns: Trajectory over 12 weeks"
  []

  (println "\n🌀 LIVENESS TEST 5: PARTICIPATION SPIRAL")
  (println "   Does system enter reflexive death spiral under stress?")
  (println "")

  (let [; Start: 30 resolvers, 40 disputes/week (healthy)
        initial-resolvers 30
        initial-volume 40

        ; Spiral trigger: Utilization > 0.8
        dropout-trigger 0.8
        user-sensitivity 0.1  ; 10% user sensitivity per week of extra latency

        trajectory (liveness/participation-spiral initial-resolvers initial-volume
                                                  dropout-trigger user-sensitivity 12)

        ; Find if spiral (week-over-week decline)
        week-0 (first trajectory)
        week-12 (last trajectory)

        resolver-decline (- (:resolvers week-0) (:resolvers week-12))
        volume-decline (- (:volume week-0) (:volume week-12))

        class (cond
                (< (:resolvers week-12) 5) "C"  ; System broken
                (< (:volume week-12) (/ (:volume week-0) 2)) "C"  ; Volume halved
                :else "B")]  ; Degraded but stable

    (println "  12-week trajectory:")
    (println (str "    Week 0: " (int (:resolvers week-0)) " resolvers, "
                  (int (:volume week-0)) " disputes/week"))
    (println (str "    Week 12: " (int (:resolvers week-12)) " resolvers, "
                  (int (:volume week-12)) " disputes/week"))
    (println (str "    Status: " (last (map :status trajectory))))
    (println "")
    (println "  Decline analysis:")
    (println (str "    Resolvers lost: " (int resolver-decline)))
    (println (str "    Volume lost: " (int volume-decline)))

    {:trajectory trajectory
     :initial-resolvers initial-resolvers
     :initial-volume initial-volume
     :final-resolvers (:resolvers week-12)
     :final-volume (:volume week-12)
     :resolver-decline resolver-decline
     :volume-decline volume-decline
     :class class}))

;; ============ Test 6: Critical Mass ============

(defn test-critical-mass
  "Test: What's the minimum viable participation level?
   
   Scenarios: Different numbers of resolvers and regions.
   Question: How close are we to failure?
   
   Returns: Safety margin assessment"
  []

  (println "\n⚔️  LIVENESS TEST 6: CRITICAL MASS THRESHOLD")
  (println "   How close are we to system failure from low participation?")
  (println "")

  (let [; Assume we need at least 3 resolvers per region (for Kleros appeals)
        min-resolvers-needed 15  ; 3 regions × 5 resolvers/region
        regions 3

        current-resolvers-options [10 15 20 30 50]

        results (for [current current-resolvers-options]

                  (let [{:keys [safety-fraction attrition-tolerance status]}
                        (liveness/critical-mass-threshold min-resolvers-needed regions current)

                        class (cond
                                (< current min-resolvers-needed) "C"
                                (< safety-fraction 0.2) "C"
                                (< safety-fraction 0.5) "B"
                                :else "A")]

                    {:current-resolvers current
                     :safety-fraction safety-fraction
                     :attrition-tolerance attrition-tolerance
                     :status status
                     :class class}))]

    ; Summary
    (println "  Safety margin by resolver count:")
    (doseq [r results]
      (let [emoji (case (:class r) "A" "✅" "B" "⚠️" "C" "❌")]
        (println (str "    " (int (:current-resolvers r)) " resolvers → " emoji " "
                      (format "%.0f" (double (* (:safety-fraction r) 100))) "% safety margin"))))

    results))

;; ============ Full Test Suite ============

(defn run-phase-r-sweep
  "Run all Phase R liveness tests.
   
   Tests whether system can fail through participation collapse
   independent of mechanism security.
   
   Returns: Summary of liveness risks"
  []

  (println "\n📊 PHASE R: LIVENESS & PARTICIPATION TESTING")
  (println "   Testing: Opportunity cost, boredom, adverse selection, latency, spiral")
  (println "")

  (let [opp-cost-results (test-opportunity-cost)
        boredom-results (test-boredom-threshold)
        adverse-sel-results (test-adverse-selection)
        latency-results (test-latency-sensitivity)
        spiral-result (test-participation-spiral)
        critical-results (test-critical-mass)

        ; Classify vulnerability
        opp-cost-vuln (count (filter #(= (:class %) "B") opp-cost-results))
        boredom-vuln (count (filter #(= (:class %) "C") boredom-results))
        adverse-sel-vuln (count (filter #(= (:class %) "C") adverse-sel-results))
        latency-vuln (count (filter #(= (:class %) "C") latency-results))
        spiral-vuln (if (= (:class spiral-result) "C") 1 0)
        critical-vuln (count (filter #(= (:class %) "C") critical-results))

        total-vuln (+ opp-cost-vuln boredom-vuln adverse-sel-vuln latency-vuln spiral-vuln critical-vuln)]

    (println "")
    (println "📋 SUMMARY")
    (println "")
    (println "  Opportunity cost vulnerable: " opp-cost-vuln " scenarios")
    (println "  Boredom dropout risk: " boredom-vuln " scenarios")
    (println "  Adverse selection risk: " adverse-sel-vuln " scenarios")
    (println "  Latency-driven exit: " latency-vuln " scenarios")
    (println "  Participation spiral: " spiral-vuln " (critical)")
    (println "  Below critical mass: " critical-vuln " scenarios")
    (println "")
    (println "  Total vulnerable: " total-vuln " / 20 scenarios")
    (println "  Risk level: " (cond
                                (> total-vuln 15) "CRITICAL"
                                (> total-vuln 10) "HIGH"
                                (> total-vuln 5) "MODERATE"
                                :else "LOW"))
    (println "")

    (proto/make-result
     {:benchmark-id "R"
      :label        "Liveness & Participation Failure"
      :hypothesis   "Total vulnerable scenarios < 5 (LOW risk)"
      :passed?      (< total-vuln 5)
      :results      {:opportunity-cost opp-cost-results :boredom boredom-results
                     :adverse-selection adverse-sel-results :latency latency-results
                     :spiral spiral-result :critical-mass critical-results}
      :summary      {:total-vulnerable total-vuln
                     :risk-level (cond (> total-vuln 15) "CRITICAL"
                                       (> total-vuln 10) "HIGH"
                                       (> total-vuln 5)  "MODERATE"
                                       :else             "LOW")}})))
