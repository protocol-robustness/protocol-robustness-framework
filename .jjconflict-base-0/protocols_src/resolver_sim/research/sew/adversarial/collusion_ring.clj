(ns resolver-sim.research.sew.adversarial.collusion-ring
  "Phase F: Multi-Resolver Collusion Ring Deterrence.

   Tests whether the waterfall-slashing mechanism deters coordinated collusion
   rings. A 'ring' is a senior resolver and 1-N junior delegators that all lie
   on every dispute; when caught the waterfall drains junior bonds first, then
   exhausts senior coverage.

   Hypothesis:
     At slashing-detection-probability ≥ deterrence-threshold (default 0.10),
     a multi-resolver collusion ring cannot sustain net positive profit:
     the fraction of (ring-size × detection-prob) configurations that are NOT
     ring-profitable must be ≥ 80%.

   Sweep axes:
     - junior-counts    — number of junior delegators (1, 2, 3)
     - detection-probs  — slashing detection probability (0.05, 0.10, 0.15, 0.20)
     - senior-bond-sizes — senior bond in same units as escrow-size

   Phase metadata:
     {:phase/id :phase-f
      :trajectory/classes [:ring-collusion :waterfall-slashing]}

   Parameters: data/params/phase-f-baseline.edn
   Entry point: (run-phase-f params)"
  (:require [resolver-sim.sim.batch :as batch]
            [resolver-sim.stochastic.rng :as rng]))

(def phase-metadata
  {:phase/id          :phase-f
   :trajectory/classes [:ring-collusion :waterfall-slashing]
   :hypothesis        "Waterfall slashing deters ring collusion: ≥80% of configurations at detection ≥ threshold are not ring-profitable."})

;; ---------------------------------------------------------------------------
;; Ring-spec builders
;; ---------------------------------------------------------------------------

(defn- build-ring-spec
  "Construct a ring-spec map for run-ring-batch.
   senior-bond is the senior resolver's posted bond.
   junior-count is how many junior delegators to include (each posts junior-bond)."
  [senior-bond junior-bond junior-count]
  {:senior {:name "senior-1" :bond senior-bond}
   :juniors (mapv (fn [i] {:name (str "junior-" (inc i)) :bond junior-bond})
                  (range junior-count))})

;; ---------------------------------------------------------------------------
;; Single scenario trial
;; ---------------------------------------------------------------------------

(defn run-ring-scenario
  "Run one (ring-size × detection-prob × senior-bond) scenario.

   Returns a result map with :ring-profitable?, :ring-viable?, :ring-catch-rate,
   :ring-total-profit, and the configuration used."
  [params junior-count detection-prob senior-bond]
  (let [seed       (+ (:rng-seed params 42)
                      (* junior-count 1000)
                      (* (int (* detection-prob 100)) 17)
                      (* (int (/ senior-bond 1000)) 31))
        run-rng    (rng/make-rng seed)
        junior-bond (:junior-bond params 1000)
        ring-spec  (build-ring-spec senior-bond junior-bond junior-count)
        run-params (assoc params :slashing-detection-probability detection-prob)
        result     (batch/run-ring-batch run-rng (:n-trials params 1000) run-params ring-spec)]
    (assoc result
           :junior-count      junior-count
           :detection-prob    detection-prob
           :senior-bond       senior-bond
           :ring-type-label   (format "%d-junior / det=%.2f / bond=%d"
                                      junior-count detection-prob senior-bond))))

;; ---------------------------------------------------------------------------
;; Phase F sweep
;; ---------------------------------------------------------------------------

(defn run-phase-f
  "Run Phase F: sweep ring-size × detection-prob × senior-bond-size.

   params — loaded from data/params/phase-f-baseline.edn.
   Returns a result map with :pass?, per-scenario results, and phase metadata."
  [params]
  (println "\n🔗 Running Phase F: Multi-Resolver Collusion Ring Deterrence")
  (println (str "   Hypothesis: " (:hypothesis phase-metadata)))

  (let [junior-counts    (:junior-counts params [1 2 3])
        detection-probs  (:detection-probs params [0.05 0.10 0.15 0.20])
        senior-bonds     (:senior-bond-sizes params [5000 10000])
        det-threshold    (:deterrence-threshold params 0.10)
        pass-fraction    (:pass-fraction params 0.80)]

    (println (format "   Junior counts:      %s" (pr-str junior-counts)))
    (println (format "   Detection probs:    %s" (pr-str detection-probs)))
    (println (format "   Senior bonds:       %s" (pr-str senior-bonds)))
    (println (format "   Deterrence ≥:       %.2f" det-threshold))
    (println (format "   Pass threshold:     %.0f%%" (* pass-fraction 100)))
    (println "")

    (let [scenarios
          (for [jc junior-counts
                dp detection-probs
                sb senior-bonds]
            (let [result (run-ring-scenario params jc dp sb)
                  deterred? (not (:ring-profitable? result))]
              (println (format "   [%-42s] %s  profit=%,.0f  catch=%.2f"
                               (:ring-type-label result)
                               (if deterred? "✓ deterred" "✗ profitable")
                               (double (:ring-total-profit result))
                               (double (:ring-catch-rate result))))
              (assoc result :deterred? deterred?)))

          above-threshold   (filter #(>= (:detection-prob %) det-threshold) scenarios)
          deterred-above    (filter :deterred? above-threshold)
          deterrence-rate   (if (seq above-threshold)
                              (double (/ (count deterred-above) (count above-threshold)))
                              0.0)
          pass?             (>= deterrence-rate pass-fraction)

          all-count         (count scenarios)
          deterred-all      (count (filter :deterred? scenarios))]

      (println "")
      (println (format "%s  Deterrence rate at detection ≥ %.2f: %.1f%%  (%d/%d scenarios deterred)"
                       (if pass? "✓ PASS" "✗ FAIL")
                       det-threshold
                       (* deterrence-rate 100)
                       (count deterred-above)
                       (count above-threshold)))
      (println (format "   Overall: %d/%d scenarios deterred (all detection levels)"
                       deterred-all all-count))

      {:phase/id           :phase-f
       :trajectory/classes (:trajectory/classes phase-metadata)
       :hypothesis         (:hypothesis phase-metadata)
       :pass?              pass?
       :deterrence-rate    deterrence-rate
       :deterrence-threshold det-threshold
       :pass-fraction      pass-fraction
       :scenarios          (vec scenarios)
       :deterred-above-threshold (count deterred-above)
       :total-above-threshold    (count above-threshold)
       :deterred-all       deterred-all
       :total-scenarios    all-count})))
