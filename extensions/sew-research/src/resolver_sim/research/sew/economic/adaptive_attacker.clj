(ns resolver-sim.research.sew.economic.adaptive-attacker
  "Phase U: Adaptive Attacker Learning

   Tests whether attackers can optimize strategies across epochs.

   Key question: Can an attacker learn which strategies work best
   and concentrate attacks on high-ROI methods?

   Scenarios:
   1. Static strategy: Same attack method every epoch
   2. Learning attacker: Switches to best-performing strategy
   3. Adaptive defense: System parameters change mid-simulation
   4. Protocol-backed adaptive attacker: Uses AdaptiveAttacker protocol dispatch

   Measures:
   - Success rate over time (do attackers improve?)
   - Strategy convergence (do they pick one winning strategy?)
   - Governance lag (can updates outpace learning?)
   - Budget efficiency (cost per successful attack)"
  (:require [resolver-sim.stochastic.rng :as rng]
            [resolver-sim.sim.engine      :as engine]
            [resolver-sim.adversaries.strategy :as strategy]))

;; ============ Simple Adaptive Attacker ============

(defn simulate-attack
  "Simulate single attack attempt.
   
   Returns: {:success? bool :cost int :detected? bool}"
  [strategy difficulty rng]
  (let [; Base detection rate by difficulty
        base-detection (case difficulty
                         :easy 0.15
                         :medium 0.30
                         :hard 0.50)

        ; Strategy effectiveness
        effectiveness (case strategy
                        :bribery (- 1.0 (* base-detection 0.6))
                        :evidence-spoof (- 1.0 (* base-detection 0.4))
                        :resolver-targeting (- 1.0 (* base-detection 0.5))
                        0.5)

        success? (< (rng/next-double rng) effectiveness)
        cost (case strategy
               :bribery (case difficulty :easy 2000 :medium 5000 :hard 10000)
               :evidence-spoof (case difficulty :easy 1000 :medium 2000 :hard 5000)
               :resolver-targeting (case difficulty :easy 1500 :medium 3000 :hard 6000)
               2000)

        detected? (and success? (< (rng/next-double rng) (* base-detection 0.3)))]

    {:success? (and success? (not detected?))
     :cost cost
     :detected? detected?}))

(defn run-epoch
  "Run one epoch of attack attempts with optional learning or Adversary dispatch.

   When an `adversary` satisfying the Adversary protocol is provided:
     - should-attack? is called before each attack to decide whether to attack
     - observe-outcome! is called after each attack to update adversary state
     - attack-type is used to select the attack strategy
   This makes AdaptiveAttacker's EMA belief updates actually run.

   Without `adversary` (nil): falls back to inline learning? logic (original behaviour).

   Returns: {:strategy-stats {strategy-name {:attempts :successes :cost}} :budget-remaining int}"
  ([initial-budget num-attacks difficulty learning? strategy-history rng]
   (run-epoch initial-budget num-attacks difficulty learning? strategy-history rng nil))
  ([initial-budget num-attacks difficulty learning? strategy-history rng adversary]
   (let [strategies [:bribery :evidence-spoof :resolver-targeting]
         stats (zipmap strategies (repeat {:attempts 0 :successes 0 :cost 0}))]

     (loop [epoch   0
            budget  initial-budget
            stats   stats
            history []]

       (if (>= epoch num-attacks)
         {:strategy-stats   stats
          :budget-remaining budget
          :total-attempts   (apply + (map :attempts (vals stats)))
          :total-successes  (apply + (map :successes (vals stats)))
          :history          history}

         (let [dispatch-params {:rng rng :difficulty difficulty :attacker-budget budget}

              ;; Decide whether to attack this round
               attack? (if adversary
                         (strategy/should-attack? adversary dispatch-params)
                         true)  ; legacy path: always attack

               chosen-strategy
               (cond
                ;; Adversary protocol dispatch
                 adversary
                 (let [at (strategy/attack-type adversary dispatch-params)]
                   (if (= at :none) :bribery at))
                ;; Inline learning mode
                 learning?
                 (let [win-rates (into {}
                                       (for [s strategies]
                                         (let [st (get stats s)]
                                           [s (if (zero? (:attempts st))
                                                0.0
                                                (/ (double (:successes st))
                                                   (double (:attempts st))))])))
                       best (key (apply max-key val win-rates))]
                   best)
                ;; Random strategy (seeded)
                 :else
                 (nth strategies (mod (long (* (rng/next-double rng) (count strategies)))
                                      (count strategies))))

               result     (if attack?
                            (simulate-attack chosen-strategy difficulty rng)
                            {:success? false :cost 0 :detected? false})
               new-budget (- budget (:cost result))
               old-stat   (get stats chosen-strategy)
               new-stat   (assoc old-stat
                                 :attempts  (inc (:attempts old-stat))
                                 :successes (+ (:successes old-stat) (if (:success? result) 1 0))
                                 :cost      (+ (:cost old-stat) (:cost result)))]

          ;; Notify adversary of outcome (no-op for non-adaptive types)
           (when adversary
             (strategy/observe-outcome! adversary
                                        {:attack-type chosen-strategy
                                         :success?    (:success? result)
                                         :profit      (if (:success? result) (:cost result) 0)
                                         :detected?   (:detected? result)}))

           (recur (inc epoch)
                  (max 0 new-budget)
                  (assoc stats chosen-strategy new-stat)
                  (conj history
                        {:epoch    epoch
                         :strategy chosen-strategy
                         :success? (:success? result)
                         :cost     (:cost result)
                         :budget   new-budget
                         :attacked? attack?}))))))))

;; ============ Phase U Scenarios ============

(defn scenario-learning-vs-static
  "Scenario 1: Does attacker learning improve success rate?
   
   Compares:
   - Static attacker: Random strategy every epoch (baseline)
   - Learning attacker: Converges to best strategy
   
   Expected: Learning attacker should have higher final success rate"
  [{:keys [seed]}]
  (let [rng (rng/make-rng seed)
        budget 100000
        num-epochs 50
        difficulty :medium

        ; Static baseline
        static (run-epoch budget num-epochs difficulty false [] rng)
        static-rate (if (zero? (:total-attempts static))
                      0.0
                      (/ (double (:total-successes static))
                         (double (:total-attempts static))))

        ; Learning attacker
        learning (run-epoch budget num-epochs difficulty true [] rng)
        learning-rate (if (zero? (:total-attempts learning))
                        0.0
                        (/ (double (:total-successes learning))
                           (double (:total-attempts learning))))

        improvement (- learning-rate static-rate)
        vulnerable? (> improvement 0.1)]

    {:scenario "learning-advantage"
     :status (if vulnerable? :vulnerable :safe)
     :confidence improvement
     :metrics {:static-success-rate static-rate
               :learning-success-rate learning-rate
               :improvement improvement
               :static-budget-left (:budget-remaining static)
               :learning-budget-left (:budget-remaining learning)}
     :reason (format "Static: %.1f%%, Learning: %.1f%%, Improvement: %.1f%%"
                     (* 100.0 static-rate)
                     (* 100.0 learning-rate)
                     (* 100.0 improvement))}))

(defn scenario-convergence-speed
  "Scenario 2: How quickly does attacker converge to winning strategy?
   
   Measures: Win rate improvement in early (epochs 0-15) vs late (epochs 35-50)
   
   Expected: Learning should show clear improvement trajectory"
  [{:keys [seed]}]
  (let [rng (rng/make-rng seed)
        result (run-epoch 100000 50 :medium true [] rng)
        history (:history result)

        early (take 15 history)
        late (drop 35 history)

        early-wins (count (filter :success? early))
        early-rate (if (empty? early) 0.0 (/ early-wins (double (count early))))

        late-wins (count (filter :success? late))
        late-rate (if (empty? late) 0.0 (/ late-wins (double (count late))))

        convergence-improvement (- late-rate early-rate)
        vulnerable? (> convergence-improvement 0.15)]

    {:scenario "convergence-speed"
     :status (if vulnerable? :vulnerable :safe)
     :confidence convergence-improvement
     :metrics {:early-success-rate early-rate
               :late-success-rate late-rate
               :improvement convergence-improvement}
     :reason (format "Early: %.1f%%, Late: %.1f%%, Improvement: %.1f%%"
                     (* 100.0 early-rate)
                     (* 100.0 late-rate)
                     (* 100.0 convergence-improvement))}))

(defn scenario-defense-timing
  "Scenario 3: Can governance prevent attacker learning?
   
   Setup:
   - Baseline: No defense changes
   - With defense: Difficulty increases at epoch 25
   
   Expected: Defense should reset attacker's learning and reduce final ROI"
  [{:keys [seed]}]
  (let [rng (rng/make-rng seed)

        ; No defense
        baseline (run-epoch 100000 50 :medium true [] rng)
        baseline-rate (if (zero? (:total-attempts baseline))
                        0.0
                        (/ (double (:total-successes baseline))
                           (double (:total-attempts baseline))))

        ; With defense: increase difficulty mid-game (approximated by lower effectiveness)
        defended (run-epoch 100000 50 :hard true [] rng)
        defended-rate (if (zero? (:total-attempts defended))
                        0.0
                        (/ (double (:total-successes defended))
                           (double (:total-attempts defended))))

        protection (- baseline-rate defended-rate)
        status (if (< protection 0.05) :vulnerable :safe)]

    {:scenario "defense-effectiveness"
     :status status
     :confidence protection
     :metrics {:baseline-rate baseline-rate
               :defended-rate defended-rate
               :protection protection}
     :reason (format "Baseline ROI: %.1f%%, Defended: %.1f%%, Protection: %.1f%%"
                     (* 100.0 baseline-rate)
                     (* 100.0 defended-rate)
                     (* 100.0 protection))}))

(defn scenario-budget-grinding
  "Scenario 4: Can persistent attacks with small budget succeed?
   
   Tests: Multiple budget levels with different attack difficulties
   
   Expected: Even with learning, modest budgets should fail on hard disputes"
  [{:keys [seed]}]
  (let [rng (rng/make-rng seed)
        budgets [25000 50000 100000]

        ; Test each budget on hard disputes
        results (for [budget budgets]
                  (let [sim (run-epoch budget 100 :hard true [] rng)
                        final-budget (:budget-remaining sim)
                        profit (- final-budget budget)]
                    {:budget budget
                     :final-budget final-budget
                     :profit profit
                     :roi (if (zero? budget) 0.0 (/ (double profit) (double budget)))
                     :success-rate (if (zero? (:total-attempts sim))
                                     0.0
                                     (/ (double (:total-successes sim))
                                        (double (:total-attempts sim))))}))

        profitable (count (filter #(pos? (:profit %)) results))
        vulnerable? (> profitable 0)]

    {:scenario "budget-grinding"
     :status (if vulnerable? :vulnerable :safe)
     :confidence (apply max (map :roi results))
     :metrics {:results results
               :profitable-budgets profitable}
     :reason (format "Profitable budgets: %d/3, Best ROI: %.1f%%"
                     profitable
                     (* 100.0 (apply max (map :roi results))))}))

;; ============ Adversary Protocol Scenario ============

(defn scenario-adversary-protocol
  "Scenario 5: Does AdaptiveAttacker via protocol dispatch improve over static?

   This is the *protocol-wired* version of scenario-learning-vs-static.
   AdaptiveAttacker.should-attack? and observe-outcome! are called through
   protocol dispatch, making the EMA belief update actually run.

   Previously: AdaptiveAttacker was never called by any phase (dead code).
   This scenario closes that gap.

   Expected: Protocol-wired adaptive attacker should converge to a better
   strategy than a static (non-learning) baseline."
  [{:keys [seed]}]
  (let [rng        (rng/make-rng seed)
        budget     100000
        num-attacks 50
        difficulty :medium

        ;; Baseline: static (no adversary, no learning)
        static     (run-epoch budget num-attacks difficulty false [] rng nil)
        static-rate (if (zero? (:total-attempts static)) 0.0
                        (/ (double (:total-successes static))
                           (double (:total-attempts static))))

        ;; Protocol-wired: AdaptiveAttacker via protocol dispatch
        [rng2 _]   (rng/split-rng rng)
        adversary  (strategy/make-adaptive-attacker 0.3 0.2)
        adaptive   (run-epoch budget num-attacks difficulty false [] rng2 adversary)
        adapt-rate (if (zero? (:total-attempts adaptive)) 0.0
                       (/ (double (:total-successes adaptive))
                          (double (:total-attempts adaptive))))

        improvement (- adapt-rate static-rate)
        vulnerable? (> improvement 0.1)]

    {:scenario   "adversary-protocol-adaptive"
     :status     (if vulnerable? :vulnerable :safe)
     :confidence improvement
     :metrics    {:static-success-rate  static-rate
                  :adaptive-success-rate adapt-rate
                  :improvement           improvement}
     :reason     (format "Static: %.1f%%, AdaptiveAttacker: %.1f%%, Δ=%.1f%%"
                         (* 100.0 static-rate)
                         (* 100.0 adapt-rate)
                         (* 100.0 improvement))}))

(defn scenario-adversary-multi-epoch
  "Scenario 6: Do AdaptiveAttacker EMA beliefs converge over 100 epochs?

   Phase 5 added protocol-wired dispatch (Scenario 5) but only ran one epoch.
   This scenario runs 100 epochs with the same AdaptiveAttacker instance so
   beliefs accumulate across epochs via the EMA update in observe-outcome!.

   Two hypotheses tested:
     H1 - Convergence: strategy belief variance stabilises (< 0.05) by epoch 30.
          Measured as the variance across the three belief values in @beliefs-atom.
     H2 - Improvement: the adaptive attacker's final-20-epoch success rate is
          meaningfully higher (> 5%) than the first-20-epoch success rate.
          This validates that the EMA actually guides strategy selection.

   The adversary is declared vulnerable when BOTH H1 AND H2 hold — i.e. when
   the learning converges to a genuinely better strategy, not just any strategy.
   Convergence without improvement is uninformative; improvement without convergence
   may be noise."
  [{:keys [seed]}]
  (let [n-epochs     100
        epoch-attacks 30
        budget       500000
        difficulty   :medium
        adversary    (strategy/make-adaptive-attacker 0.3 0.2)
        rng          (rng/make-rng seed)

        ;; Run 100 epochs keeping the same adversary (beliefs persist across epochs)
        epoch-stats
        (loop [epoch 0
               rng   rng
               acc   []]
          (if (>= epoch n-epochs)
            acc
            (let [[sub _]  (rng/split-rng rng)
                  result   (run-epoch budget epoch-attacks difficulty false [] sub adversary)
                  rate     (if (zero? (:total-attempts result)) 0.0
                               (/ (double (:total-successes result))
                                  (double (:total-attempts result))))
                  ;; Snapshot belief variance at this epoch
                  beliefs  @(.beliefs-atom adversary)
                  mean-b   (/ (apply + beliefs) (count beliefs))
                  variance (/ (apply + (map #(* (- % mean-b) (- % mean-b)) beliefs))
                              (count beliefs))]
              (recur (inc epoch)
                     rng
                     (conj acc {:epoch epoch :rate rate :belief-variance variance})))))

        ;; H1: variance < 0.05 by epoch 30
        variance-at-30  (:belief-variance (nth epoch-stats 29))
        converged?      (< variance-at-30 0.05)

        ;; H2: final-20 rate > first-20 rate + 0.05
        first-20-rate   (/ (apply + (map :rate (take 20 epoch-stats))) 20.0)
        last-20-rate    (/ (apply + (map :rate (take-last 20 epoch-stats))) 20.0)
        improved?       (> last-20-rate (+ first-20-rate 0.05))

        vulnerable?     (and converged? improved?)]

    {:scenario   "adversary-multi-epoch-convergence"
     :status     (if vulnerable? :vulnerable :safe)
     :confidence (if improved? (- last-20-rate first-20-rate) 0.0)
     :metrics    {:variance-at-epoch-30  variance-at-30
                  :converged?            converged?
                  :first-20-epoch-rate   first-20-rate
                  :last-20-epoch-rate    last-20-rate
                  :improved?             improved?}
     :reason     (format "Beliefs converged?=%s (var@30=%.3f); rate Δ=%.1f%% (first20=%.1f%% last20=%.1f%%)"
                         (str converged?)
                         variance-at-30
                         (* 100.0 (- last-20-rate first-20-rate))
                         (* 100.0 first-20-rate)
                         (* 100.0 last-20-rate))}))

;; ============ Scenario Runner ============

(defn run-phase-u-sweep
  "Run all Phase U scenarios with multiple seeds"
  []
  (println "\n" (apply str (repeat 70 "=")))
  (println "Phase U: Adaptive Attacker Learning")
  (println (apply str (repeat 70 "=")))

  (let [seeds (range 42 52)  ; 10 trials
        scenario-fns [scenario-learning-vs-static
                      scenario-convergence-speed
                      scenario-defense-timing
                      scenario-budget-grinding
                      scenario-adversary-protocol
                      scenario-adversary-multi-epoch]

        results (flatten
                 (for [scenario-fn scenario-fns
                       seed seeds]
                   (let [result (scenario-fn {:seed seed})]
                     (println (format "%-30s seed=%2d [%s]"
                                      (:scenario result)
                                      seed
                                      (name (:status result))))
                     result)))]

    (println "\n" (apply str (repeat 70 "=")))

    ; Summary stats
    (let [by-scenario (group-by :scenario results)
          vulnerable-count (count (filter #(= :vulnerable (:status %)) results))
          safe-count (count (filter #(= :safe (:status %)) results))]

      (println (format "\n📊 Summary"))
      (println (format "  Total scenarios: %d" (count results)))
      (println (format "  Vulnerable: %d" vulnerable-count))
      (println (format "  Safe: %d" safe-count))

      (doseq [[scenario-name rs] by-scenario]
        (let [vulns (count (filter #(= :vulnerable (:status %)) rs))
              avg-conf (/ (apply + (map :confidence rs)) (count rs))]
          (println (format "  %-30s: %d vulnerable, avg confidence: %.1f%%"
                           scenario-name vulns (* 100.0 avg-conf)))))

      (println "\n" (apply str (repeat 70 "=")))
      (println "Detailed Results")
      (println (apply str (repeat 70 "=")))

      (doseq [result results]
        (println (format "\n%s (seed=%d)" (:scenario result) (:seed result)))
        (println (format "  Status: %s" (name (:status result))))
        (println (format "  Reason: %s" (:reason result))))

      (println "\n\n" (apply str (repeat 70 "=")))
      (println "PHASE U ASSESSMENT")
      (println (apply str (repeat 70 "=")))
      (if (> vulnerable-count 8)
        (println "  🔴 CRITICAL: Adaptive attackers can reliably improve attack success rates")
        (println "  🟢 ACCEPTABLE: Learning provides limited advantage to attackers"))
      (println)
      (engine/make-result
       {:benchmark-id "U"
        :label        "Adaptive Attacker Learning"
        :hypothesis   "Adaptive learning provides limited advantage (<= 8 vulnerable scenarios of 60)"
        :passed?      (<= vulnerable-count 8)
        :results      results
        :summary      {:vulnerable vulnerable-count :safe safe-count}}))))

(defn -main
  [& args]
  (run-phase-u-sweep))
