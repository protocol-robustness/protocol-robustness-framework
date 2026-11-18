(ns resolver-sim.research.sew.adversarial.equity-divergence
  "Phase AH: Honest vs. Strategic Equity Divergence Sweep.

   Tests whether strategic-type resolvers (malicious, lazy, collusive) can achieve
   sustained equity advantage over honest resolvers across multi-epoch simulations.
   Equilibrium drift is detected when the strategic equity trajectory diverges from
   the honest mean by more than the configured threshold.

   Hypothesis:
     Over N epochs, strategic-type resolver equity must not diverge from honest-type
     mean equity by more than drift-threshold (default 2×).

   Two falsifiable metrics:
     1. strategic-mean-equity / honest-mean-equity ≤ drift-threshold-mean (2.0)
     2. strategic-p95-equity  / honest-mean-equity ≤ drift-threshold-p95  (2.0)
        — catches cartel or ring outliers that the mean alone would mask.

   Phase metadata:
     {:phase/id :phase-ah
      :trajectory/classes [:equity-divergence :strategy-spread]}

   Parameters: data/params/phase-ah-trajectory-sweep.edn
   Entry point: (run-phase-ah params)"
  (:require [resolver-sim.sim.multi-epoch :as me]
            [resolver-sim.sim.trajectory :as trajectory]
            [resolver-sim.stochastic.rng :as rng]))

(def phase-metadata
  {:phase/id          :phase-ah
   :trajectory/classes [:equity-divergence :strategy-spread]
   :hypothesis        "Strategic equity must not diverge >2× from honest mean."})

;; ---------------------------------------------------------------------------
;; Single divergence trial
;; ---------------------------------------------------------------------------

(defn- strategic-profits-at-epoch
  "Extract all strategic-resolver profits from equity-trajectories at one epoch."
  [equity-trajectories resolver-histories epoch-idx]
  (keep (fn [[id r]]
          (when (#{:malicious :lazy :collusive :ring} (:strategy r))
            (nth (get equity-trajectories id []) epoch-idx nil)))
        resolver-histories))

(defn- honest-profits-at-epoch
  "Extract all honest-resolver profits from equity-trajectories at one epoch."
  [equity-trajectories resolver-histories epoch-idx]
  (keep (fn [[id r]]
          (when (= :honest (:strategy r))
            (nth (get equity-trajectories id []) epoch-idx nil)))
        resolver-histories))

(defn run-divergence-trial
  "Run one multi-epoch simulation and compute divergence metrics.

   Returns a result map including pass/fail and the per-epoch spread trajectory."
  [n-epochs params]
  (let [seed       (+ (:seed params 42) (* n-epochs 7))
        d-rng      (rng/make-rng seed)
        me-result  (me/run-multi-epoch d-rng n-epochs (:n-trials-per-epoch params 200) params)

        equity-traj  (:equity-trajectories me-result {})
        histories    (:resolver-histories  me-result {})
        spread-traj  (:strategy-spread-trajectories me-result [])

        drift-mean   (:drift-threshold-mean params 2.0)
        drift-p95    (:drift-threshold-p95  params 2.0)

        ; Find worst-case epoch: highest strategic-mean / honest-mean ratio.
        violations
        (keep-indexed
         (fn [epoch-idx _]
           (let [h-profits (honest-profits-at-epoch   equity-traj histories epoch-idx)
                 s-profits (strategic-profits-at-epoch equity-traj histories epoch-idx)
                 h-mean    (if (seq h-profits) (double (/ (reduce + h-profits) (count h-profits))) 0.0)
                 s-mean    (if (seq s-profits) (double (/ (reduce + s-profits) (count s-profits))) 0.0)
                 s-p95     (trajectory/p95 (vec s-profits))
                 ratio-mean (trajectory/divergence-ratio h-mean s-mean)
                 ratio-p95  (trajectory/divergence-ratio h-mean s-p95)]
             (when (or (and ratio-mean (> ratio-mean drift-mean))
                       (and ratio-p95  (> ratio-p95  drift-p95)))
               {:epoch           (inc epoch-idx)
                :ratio-mean      ratio-mean
                :ratio-p95       ratio-p95
                :honest-mean     h-mean
                :strategic-mean  s-mean
                :strategic-p95   s-p95})))
         spread-traj)

        first-violation (first violations)
        max-spread-mean (apply max 0.0 (keep :ratio-mean violations))
        max-spread-p95  (apply max 0.0 (keep :ratio-p95  violations))]

    {:n-epochs               n-epochs
     :pass?                  (nil? first-violation)
     :drift-detected-at-epoch (some-> first-violation :epoch)
     :max-spread-mean        max-spread-mean
     :max-spread-p95         max-spread-p95
     :strategy-spread-trajectory spread-traj
     :aggregated-stats       (:aggregated-stats me-result)}))

;; ---------------------------------------------------------------------------
;; Phase AH sweep
;; ---------------------------------------------------------------------------

(defn run-phase-ah
  "Run Phase AH: sweep over n-epochs-sweep × strategy-mixes.

   params — loaded from data/params/phase-ah-trajectory-sweep.edn.
   Returns a result map with :pass?, per-trial results, and phase metadata."
  [params]
  (println "\n📈 Running Phase AH: Equity Divergence Sweep")
  (println (str "   Hypothesis: " (:hypothesis phase-metadata)))
  (let [epoch-sweep    (:n-epochs-sweep params [100 500 1000])
        strategy-mixes (:strategy-mixes params [{}])
        drift-mean     (:drift-threshold-mean params 2.0)
        drift-p95      (:drift-threshold-p95  params 2.0)]

    (println (format "   n-epochs sweep: %s" (pr-str epoch-sweep)))
    (println (format "   Strategy mixes: %d variants" (count strategy-mixes)))
    (println (format "   Thresholds: mean ≤ %.1f×, p95 ≤ %.1f×\n" drift-mean drift-p95))

    (let [trial-results
          (for [n-epochs       epoch-sweep
                strategy-mix   strategy-mixes]
            (let [label   (:label strategy-mix "unnamed")
                  p       (merge params (dissoc strategy-mix :label))
                  result  (run-divergence-trial n-epochs p)]
              (println (format "   [n=%4d %-14s] %s  max-mean=%.2f×  max-p95=%.2f×"
                               n-epochs label
                               (if (:pass? result) "✓ PASS" "✗ FAIL")
                               (double (:max-spread-mean result))
                               (double (:max-spread-p95  result))))
              (assoc result :n-epochs n-epochs :label label)))

          all-pass?    (every? :pass? trial-results)
          failed-trials (filter (complement :pass?) trial-results)]

      (println (format "\n%s  %d/%d trials passed"
                       (if all-pass? "✓ PASS" "✗ FAIL")
                       (count (filter :pass? trial-results))
                       (count trial-results)))

      {:phase/id          :phase-ah
       :trajectory/classes (:trajectory/classes phase-metadata)
       :hypothesis         (:hypothesis phase-metadata)
       :pass?             all-pass?
       :trials            (vec trial-results)
       :failed-trials     (vec failed-trials)
       :drift-threshold-mean drift-mean
       :drift-threshold-p95  drift-p95})))
