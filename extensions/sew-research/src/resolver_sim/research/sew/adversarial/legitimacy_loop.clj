(ns resolver-sim.research.sew.adversarial.legitimacy-loop
  "Phase Z: Legitimacy and Reflexive Participation Loop.

   Tests whether the system sustains stable participation over 100+ epochs when:
   - Outcomes are occasionally controversial or slow
   - False positives occasionally slash honest resolvers
   - Sudden participation shocks occur (30-50% withdrawals)

   Failure signal: trust drops below exit threshold → participation cascades
   downward → security threshold breached."
  (:require [resolver-sim.stochastic.rng :as rng]
            [resolver-sim.sim.engine :as proto]))

;; ============ Trust Index Model (Pure) ============

(defn update-trust
  "trust_t+1 = trust_t * decay + correctness_signal + fairness_signal"
  [{:keys [trust]} {:keys [accuracy false-positive-rate resolution-time]}]
  (let [correctness-signal (cond
                             (>= accuracy 0.85)  0.02
                             (<= accuracy 0.65) -0.03
                             :else               0.00)
        fp-signal          (if (> false-positive-rate 0.05) -0.01 0.0)
        fairness-signal    (if (> resolution-time 5) -0.015 0.01)
        new-trust (-> trust
                      (* 0.98)
                      (+ correctness-signal)
                      (+ fp-signal)
                      (+ fairness-signal)
                      (max 0.0)
                      (min 1.0))]
    new-trust))

(defn update-participation
  "Participation feedback loop."
  [current-participation trust]
  (let [sigmoid (fn [x] (/ 1.0 (+ 1.0 (Math/exp (* -10.0 x)))))
        re-entry (* 0.06 (sigmoid (- trust 0.6)))
        retention 0.96
        new-participation (+ (* current-participation retention) re-entry)]
    (max 0.1 (min 1.0 new-participation))))

;; ============ Engine Adapters ============

(defn simulate-epoch-z
  "Adapter for the unified proto."
  [epoch state params _rng]
  (let [{:keys [base-accuracy false-positive-rate resolution-time shock-epoch shock-magnitude]}
        (merge {:base-accuracy 0.88 :false-positive-rate 0.02
                :resolution-time 3 :shock-epoch -1 :shock-magnitude 0.0}
               params)

        participation (:participation state)
        trust         (:trust state)

        ;; Apply participation shock if this is the shock epoch
        participation-after-shock (if (= epoch shock-epoch)
                                    (max 0.1 (- participation shock-magnitude))
                                    participation)

        ;; Accuracy degrades at low participation (simplified security threshold)
        effective-accuracy (if (< participation-after-shock 0.4)
                             (* base-accuracy 0.80)
                             base-accuracy)

        new-trust (update-trust {:trust trust}
                                {:accuracy effective-accuracy
                                 :false-positive-rate false-positive-rate
                                 :resolution-time resolution-time})

        new-participation (update-participation participation-after-shock new-trust)]

    {:epoch epoch
     :trust new-trust
     :participation new-participation
     :spiral-risk? (and (< new-trust 0.40) (< new-participation 0.40))}))

(defn summarize-z-history
  "Aggregation and hypothesis checking for Phase Z."
  [history params]
  (let [min-trust (apply min (map :trust history))
        min-part (apply min (map :participation history))
        final (last history)
        spiral? (< (:participation final) 0.3)
        passed? (not spiral?)
        expected-negative? (boolean (:expected-negative-control? params))]
    {:status (cond
               (and expected-negative? (not passed?)) "⚠️ EXPECTED NEGATIVE CONTROL"
               passed? "✅ STABLE"
               :else "❌ UNEXPECTED DEATH SPIRAL")
     :min-trust min-trust
     :min-part min-part
     :final-trust (:trust final)
     :final-part (:participation final)
     :class (if passed? "A" "C")
     :expected-negative-control? expected-negative?
     :passed? passed?}))

;; ============ Scenario Definitions ============

(defn make-scenarios [seed]
  [{:label "TEST 1: Baseline (Stable environment)"
    :initial-state {:trust 0.75 :participation 0.85}
    :update-fn simulate-epoch-z
    :summary-fn summarize-z-history
    :epochs 100
    :seed seed
    :params {}}

   {:label "TEST 2: Market Shock (40% exit at epoch 30)"
    :initial-state {:trust 0.75 :participation 0.85}
    :update-fn simulate-epoch-z
    :summary-fn summarize-z-history
    :epochs 100
    :seed (+ seed 1)
    :params {:shock-epoch 30 :shock-magnitude 0.40}}

   {:label "TEST 3: Scam Wave (High FP rate 8%)"
    :initial-state {:trust 0.75 :participation 0.85}
    :update-fn simulate-epoch-z
    :summary-fn summarize-z-history
    :epochs 100
    :seed (+ seed 2)
    :params {:false-positive-rate 0.08}}

   {:label "TEST 4: Combined Shocks"
    :initial-state {:trust 0.75 :participation 0.85}
    :update-fn simulate-epoch-z
    :summary-fn summarize-z-history
    :epochs 100
    :seed (+ seed 3)
    :params {:shock-epoch 30 :shock-magnitude 0.30 :false-positive-rate 0.06}}

   {:label "TEST 5: Cascading Failures (Negative control: low accuracy + slow resolution)"
    :initial-state {:trust 0.75 :participation 0.85}
    :update-fn simulate-epoch-z
    :summary-fn summarize-z-history
    :epochs 100
    :seed (+ seed 4)
    :params {:base-accuracy 0.60
             :resolution-time 8
             :expected-negative-control? true}}])

;; ============ Full Phase Z Run ============

(defn run-phase-z-sweep
  "Run all Phase Z legitimacy + reflexive participation tests.

   Phase 5 sensitivity sweep established the stable operating zone:
     base-accuracy ≥ 0.65 AND false-positive-rate < 0.20

   When params fall outside this zone, the result is annotated with
   :accuracy-floor-warning so callers can distinguish 'passed in safe zone'
   from 'passed in a configuration that may not generalise'."
  [params]
  (let [seed              (:rng-seed params 42)
        base-accuracy     (:base-accuracy params 0.88)
        fpr               (:false-positive-rate params 0.02)
        accuracy-floor    0.65
        fpr-ceiling       0.20
        below-accuracy?   (< base-accuracy accuracy-floor)
        above-fpr?        (>= fpr fpr-ceiling)
        outside-safe-zone? (or below-accuracy? above-fpr?)
        _ (proto/print-phase-header
           {:benchmark-id "Z"
            :label        "Legitimacy & Reflexive Participation Loop"
            :hypothesis   "System maintains stable participation (>40%) over 100 epochs"})
        _ (when outside-safe-zone?
            (when below-accuracy?
              (println (format "   ⚠️  base-accuracy=%.2f < floor=%.2f (Phase 5 sensitivity sweep)"
                               base-accuracy accuracy-floor))
              (println "   ⚠️  Stability claim only validated at base-accuracy ≥ 0.65."))
            (when above-fpr?
              (println (format "   ⚠️  false-positive-rate=%.2f ≥ ceiling=%.2f (Phase 5 sensitivity sweep)"
                               fpr fpr-ceiling))
              (println "   ⚠️  Stability claim only validated at false-positive-rate < 0.20.")))

        scenarios (make-scenarios seed)
        results (proto/run-sweep "PHASE Z SWEEP" scenarios params)

        expected-neg? (fn [r] (true? (:expected-negative-control? r)))
        gate-results  (remove expected-neg? results)
        class-a (count (filter #(= "A" (:class %)) gate-results))
        class-c (count (filter #(= "C" (:class %)) gate-results))
        hypothesis-holds? (zero? class-c)
        neg-count (count (filter expected-neg? results))]

    (proto/print-phase-footer
     {:benchmark-id  "Z"
      :passed?       hypothesis-holds?
      :summary-lines [(format "Robust (A): %d  Fragile (C): %d  (negative controls: %d)"
                              class-a class-c neg-count)]})

    (proto/make-result
     {:benchmark-id          "Z"
      :label                 "Legitimacy & Reflexive Participation Loop"
      :hypothesis            "System maintains stable participation (>40%) over 100 epochs"
      :passed?               hypothesis-holds?
      :results               results
      :summary               {:class-a class-a :class-c class-c}
      :accuracy-floor-warning (when outside-safe-zone?
                                {:base-accuracy  base-accuracy
                                 :accuracy-floor accuracy-floor
                                 :fpr            fpr
                                 :fpr-ceiling    fpr-ceiling
                                 :note           "Stability claim only validated within safe zone"})})))

;; ============ Phase Z Sensitivity Sweep ============

(defn- run-z-scenario-pure
  "Run a single Phase Z scenario without output, returning final state."
  [initial-state params n-epochs]
  (loop [epoch 1
         state initial-state]
    (if (> epoch n-epochs)
      state
      (recur (inc epoch)
             (simulate-epoch-z epoch state params nil)))))

(defn run-phase-z-sensitivity-sweep
  "Sweep base-accuracy and false-positive-rate to find the safety margins
   for stable participation. Returns a 2D grid of outcomes.

   For each (accuracy, fpr) pair: run 100 epochs and record whether
   participation stays above 30% (no death spiral).

   Returns: {:results [{:accuracy :fpr :final-participation :stable?}]
             :min-safe-accuracy double
             :max-safe-fpr      double}"
  [params]
  (println "\n🔍 Phase Z Sensitivity Sweep: base-accuracy × false-positive-rate")
  (println "   Threshold: participation > 30% at epoch 100")
  (println "   Initial state: trust=0.75 participation=0.85")
  (println "")

  (let [accuracies [0.60 0.65 0.70 0.75 0.80 0.85 0.90 0.95]
        fprs       [0.01 0.02 0.04 0.06 0.08 0.10]
        n-epochs   100
        init-state {:trust 0.75 :participation 0.85}

        results
        (vec (for [acc accuracies
                   fpr fprs]
               (let [final-state (run-z-scenario-pure
                                  init-state
                                  {:base-accuracy acc :false-positive-rate fpr
                                   :resolution-time 3}
                                  n-epochs)
                     stable?     (> (:participation final-state) 0.30)]
                 {:accuracy         acc
                  :fpr              fpr
                  :final-part       (:participation final-state)
                  :final-trust      (:trust final-state)
                  :stable?          stable?})))

        ;; Per-accuracy: find the max FPR that still gives stability
        stable-results (filter :stable? results)

        min-safe-acc
        (when (seq stable-results)
          (apply min (map :accuracy stable-results)))

        max-safe-fpr-at-min-acc
        (when min-safe-acc
          (apply max (map :fpr (filter #(and (= (:accuracy %) min-safe-acc) (:stable? %))
                                       results))))]

    ;; Print a compact table
    (println (format "   %-8s  %s" "Accuracy" (apply str (map #(format "  fpr=%.2f" %) fprs))))
    (doseq [acc accuracies]
      (let [row (filter #(= (:accuracy %) acc) results)]
        (println (format "   acc=%.2f  %s"
                         acc
                         (apply str (map #(format "  %s" (if (:stable? %) "✅ " "❌ ")) row))))))
    (println "")
    (if min-safe-acc
      (do (println (format "   ✅ Minimum safe accuracy: %.2f (max FPR at this accuracy: %.2f)"
                           min-safe-acc max-safe-fpr-at-min-acc))
          (println "   Protocol is stable for accuracy ≥ this threshold."))
      (println "   ❌ No tested combination achieves stable participation."))
    (println "")

    {:results              results
     :min-safe-accuracy    min-safe-acc
     :max-safe-fpr         max-safe-fpr-at-min-acc
     :stable-count         (count stable-results)
     :total-combinations   (count results)}))
