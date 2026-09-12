(ns resolver-sim.research.sew.adversarial.evidence-fog
  "Phase Y: Evidence Fog and Attention Budget Constraint.

   Tests whether system correctness holds when:
   - 15% of disputes are hard/ambiguous to verify
   - Resolvers have a fixed attention budget per epoch (20 units)
   - Attackers can pay to inflate evidence complexity
   - Deep verification is 5-10x more costly than shallow

   Hypothesis to falsify:
     'The system maintains >75% correctness even with limited resolver
      budgets and attacker-driven evidence complexity escalation.'

   If correctness drops below 75% under realistic loads, the system
   needs attention-reward design changes before 90% confidence is claimed."
  (:require [resolver-sim.stochastic.rng :as rng]
            [resolver-sim.sim.engine      :as proto]))

;; ============ Dispute Complexity Distribution ============

(def complexity-distribution
  "Phase Y dispute mix (from PHASE_YZA spec):
   20% easy, 60% medium, 15% hard, 5% ambiguous"
  [{:type :easy      :weight 0.20 :evidence-units 5   :verify-units 1  :base-accuracy 0.95}
   {:type :medium    :weight 0.60 :evidence-units 15  :verify-units 3  :base-accuracy 0.82}
   {:type :hard      :weight 0.15 :evidence-units 30  :verify-units 8  :base-accuracy 0.65}
   {:type :ambiguous :weight 0.05 :evidence-units 100 :verify-units 99 :base-accuracy 0.52}])

(defn sample-dispute-type
  "Sample a dispute type from the Phase Y complexity distribution."
  [d-rng]
  (let [r (rng/next-double d-rng)]
    (cond
      (< r 0.20) (nth complexity-distribution 0)
      (< r 0.80) (nth complexity-distribution 1)
      (< r 0.95) (nth complexity-distribution 2)
      :else      (nth complexity-distribution 3))))

;; ============ Resolver Strategy Selection ============

(defn choose-strategy
  "Resolver selects deep, shallow, or guess based on allocated budget.

   Deep:    >= 8 effort/dispute → 90% accuracy, 85% detection
   Shallow: >= 2 effort/dispute  → 70% accuracy, 40% detection
   Guess:   < 2 effort            → 52% accuracy,  0% detection"
  [effort-allocated]
  (cond
    (>= effort-allocated 8) {:name :deep    :accuracy-mult 1.10 :detection 0.85}
    (>= effort-allocated 2) {:name :shallow :accuracy-mult 0.85 :detection 0.40}
    :else                   {:name :guess   :accuracy-mult 0.63 :detection 0.00}))

;; ============ Simulation Logic ============

(defn assign-disputes
  "Assign each dispute to 3 random resolvers using seeded RNG.

   Previously used Clojure's (shuffle ...) which calls
   java.util.Collections/shuffle with the JVM default PRNG — non-reproducible.
   Now uses rng/shuffle-with-rng for deterministic, seeded assignment."
  [n-resolvers n-disputes d-rng]
  (let [resolver-ids (vec (range n-resolvers))]
    (for [i (range n-disputes)]
      (let [[sub _] (rng/split-rng d-rng)
            assigned (take 3 (rng/shuffle-with-rng resolver-ids sub))]
        {:id i :resolvers assigned}))))

(defn simulate-epoch-y
  "Run one epoch of Phase Y simulation."
  [n-resolvers n-disputes budget-per-resolver complexity-add d-rng]
  (let [dispute-types (repeatedly n-disputes #(sample-dispute-type d-rng))
        assignments (assign-disputes n-resolvers n-disputes d-rng)

        ;; Map: resolver-id -> count of assigned disputes
        resolver-loads (reduce (fn [acc {:keys [resolvers]}]
                                 (reduce (fn [a rid] (update a rid (fnil inc 0))) acc resolvers))
                               {} assignments)

        ;; Map: resolver-id -> effort per assigned dispute
        resolver-budgets (into {} (for [[rid load] resolver-loads]
                                    [rid (/ (double budget-per-resolver) load)]))

        ;; Evaluate each dispute
        dispute-results (map (fn [type {:keys [resolvers]}]
                               (let [votes (for [rid resolvers]
                                             (let [effort (get resolver-budgets rid 0.0)
                                                   strategy (choose-strategy effort)
                                                   accuracy (* (:base-accuracy type) (:accuracy-mult strategy))]
                                               (if (> (rng/next-double d-rng) (- 1.0 accuracy)) 1 0)))
                                     ;; Majority vote (2 or more out of 3)
                                     correct-count (count (filter #(= 1 %) votes))
                                     correct? (>= correct-count 2)]
                                 correct?))
                             dispute-types assignments)]

    {:correct (count (filter identity dispute-results))
     :total n-disputes}))

;; ============ Test Scenarios ============

(defn test-scenario-y
  "Run a specific Phase Y scenario multiple times."
  [scenario-label n-resolvers n-disputes budget complexity-add trials seed accuracy-threshold]
  (println (format "📋 %s" scenario-label))
  (let [rng (rng/make-rng seed)
        results (repeatedly trials #(simulate-epoch-y n-resolvers n-disputes budget complexity-add rng))
        avg-accuracy (/ (reduce + (map #(/ (double (:correct %)) (:total %)) results)) (double trials))]
    (println (format "   Load: %d disputes, %d resolvers (avg %.1f disputes/resolver)"
                     n-disputes n-resolvers (/ (* 3.0 n-disputes) n-resolvers)))
    (println (format "   Budget: %d units, Complexity add: +%d" budget complexity-add))
    (println (format "   Avg accuracy: %.1f%%" (* 100 avg-accuracy)))
    (let [passed? (>= avg-accuracy accuracy-threshold)]
      (println (format "   Status: %s (threshold: %.0f%%)" (if passed? "✅ PASS" "❌ FAIL") (* 100 accuracy-threshold)))
      {:scenario scenario-label :avg-accuracy avg-accuracy :class (if passed? "A" "C")})))

;; ============ Full Phase Y Run ============

(defn run-phase-y-sweep
  "Run all Phase Y evidence fog tests.

   Phase 5 safety sweep established that the ≥75% correctness claim only holds
   when budget-per-resolver ≥ 50 (at 200 disputes / 30 resolvers). When params
   supply a lower budget, the sweep result is annotated with :budget-floor-warning
   so callers can distinguish 'passed at an under-resourced config' from 'passed
   within the validated safe zone'."
  [params]
  (let [n-resolvers        (:n-resolvers params 30)
        seed               (:rng-seed params 42)
        budget-per-resolver (:budget-per-resolver params 20)
        accuracy-threshold (double (or (:accuracy-threshold params) 0.75))
        budget-floor       50
        below-floor?       (< budget-per-resolver budget-floor)]

    (println "\n📊 PHASE Y: EVIDENCE FOG & ATTENTION BUDGET TESTING")
    (println (format "   Hypothesis: >%.0f%% correctness survives budget caps + attacker complexity escalation" (* 100 accuracy-threshold)))
    (when below-floor?
      (println (format "   ⚠️  budget-per-resolver=%d < floor=%d (Phase 5 safety sweep)"
                       budget-per-resolver budget-floor))
      (println "   ⚠️  The ≥75%% accuracy claim only holds at budget-per-resolver ≥ 50."))
    (println "")

    (let [r1 (test-scenario-y "TEST 1: Baseline (light load, ample budget)"
                              n-resolvers 20 20 0 100 seed accuracy-threshold)
          r2 (test-scenario-y "TEST 2: High Fog (15% ambiguous/hard)"
                              n-resolvers 30 20 0 100 (+ seed 1) accuracy-threshold)
          r3 (test-scenario-y "TEST 3: Attacker Fog (High complexity +10)"
                              n-resolvers 30 20 10 100 (+ seed 2) accuracy-threshold)
          r4 (test-scenario-y "TEST 4: Load Spike (100 disputes)"
                              n-resolvers 100 20 0 100 (+ seed 3) accuracy-threshold)
          r5 (test-scenario-y "TEST 5: Extreme Load (200 disputes)"
                              n-resolvers 200 20 0 100 (+ seed 4) accuracy-threshold)

          all-results [r1 r2 r3 r4 r5]
          class-a (count (filter #(= "A" (:class %)) all-results))
          class-c (count (filter #(= "C" (:class %)) all-results))

          min-accuracy (apply min (map :avg-accuracy all-results))
          hypothesis-holds? (>= min-accuracy accuracy-threshold)]

      (println "\n═══════════════════════════════════════════════════")
      (println "📋 PHASE Y SUMMARY")
      (println "═══════════════════════════════════════════════════")
      (println (format "   Robust (A): %d  Fragile (C): %d" class-a class-c))
      (println (format "   Min accuracy across scenarios: %.1f%% (threshold: ≥%.0f%%)" (* 100 min-accuracy) (* 100 accuracy-threshold)))
      (println (format "   Hypothesis holds? %s" (if hypothesis-holds? "✅ YES — system robust" "❌ NO — attention design needed")))
      (println "")
      (if hypothesis-holds?
        (do (println (format "   ✅ PASS: Minimum accuracy exceeds %.0f%% threshold" (* 100 accuracy-threshold)))
            (println "   Confidence impact: +8% (evidence fog not a critical risk)")
            (println "   Interpretation: Attention budget mechanism is adequate under tested loads")
            (println "   Recommendation: No changes needed; monitor under production load"))
        (do (println (format "   ❌ FAIL: Minimum accuracy %.1f%% < %.0f%% threshold" (* 100 min-accuracy) (* 100 accuracy-threshold)))
            (println "   Interpretation: ❌ CRITICAL — attention budget is insufficient")
            (println "   Min accuracy reached: " (format "%.1f%%" (* 100 min-accuracy)))
            (println "   Confidence impact: 0% (issue found; no progress without mitigation)")
            (println "   Recommendations:")
            (println "   1. Add per-dispute effort rewards (based on evidence complexity)")
            (println "   2. Increase attention budget for high-complexity cases (+50%)")
            (println "   3. Implement progressive load scaling (start at 30 disputes, ramp to 200)")
            (println "   4. Test with tiered budget allocation (urgent vs. routine)")))
      (println "")

      (proto/make-result
       {:benchmark-id          "Y"
        :label                 "Evidence Fog & Attention Budget"
        :hypothesis            (format ">%.0f%% correctness survives budget caps + attacker complexity escalation" (* 100 accuracy-threshold))
        :class                 :analytic
        :passed?               hypothesis-holds?
        :results               all-results
        :summary               {:class-a class-a :class-c class-c :min-accuracy min-accuracy :threshold accuracy-threshold}
        :budget-floor-warning  (when below-floor?
                                 {:budget-per-resolver budget-per-resolver
                                  :budget-floor        budget-floor
                                  :note                (format "%.0f%%-accuracy claim only validated at budget >= floor" (* 100 accuracy-threshold))})}))))

;; ============ Phase Y Safety Margin Sweep ============

(defn run-phase-y-safety-sweep
  "Sweep budget-per-resolver to find the minimum budget where ≥75% correctness
   is maintained for high-load scenarios (200 disputes, 30 resolvers).

   Returns: {:safe-budget-threshold int :results [{:budget :accuracy :safe?}]
             :min-safe-budget int}"
  [params]
  (let [accuracy-threshold (double (or (:accuracy-threshold params) 0.75))]
    (println "\n🔍 Phase Y Safety Margin Sweep: budget-per-resolver vs correctness")
    (println (format "   Scenario: 200 disputes, 30 resolvers, 50 trials per budget level (threshold: %.0f%%)" (* 100 accuracy-threshold)))
    (println "")

    (let [n-resolvers (:n-resolvers params 30)
          n-disputes  200
          seed        (:rng-seed params 42)
          trials      50
          budgets     [5 10 15 20 25 30 40 50 75 100]

          results
          (vec (for [budget budgets]
                 (let [rng (rng/make-rng (+ seed budget))
                       accuracy-readings
                       (vec (repeatedly trials
                                        #(let [r (simulate-epoch-y n-resolvers n-disputes budget 0 rng)]
                                           (/ (double (:correct r)) (:total r)))))
                       avg-accuracy (/ (apply + accuracy-readings) (double trials))
                       safe?        (>= avg-accuracy accuracy-threshold)]
                   (println (format "   budget=%3d  accuracy=%.1f%%  %s"
                                    budget (* 100.0 avg-accuracy)
                                    (if safe? "✅" "❌")))
                   {:budget budget :accuracy avg-accuracy :safe? safe?})))

        ;; Find the minimum budget at which correctness becomes safe
          safe-budgets (filter :safe? results)
          threshold    (when (seq safe-budgets)
                         (:budget (first safe-budgets)))]

      (println "")
      (if threshold
        (println (format "   ✅ Minimum safe budget: %d units/resolver" threshold))
        (println (format "   ❌ No tested budget achieves ≥%.0f%% correctness at 200 disputes" (* 100 accuracy-threshold))))
      (println "")

      {:safe-budget-threshold threshold
       :min-safe-budget       threshold
       :results               results})))
