(ns resolver-sim.sim.multi-epoch
  "Multi-epoch reputation simulation (Phase J).
   Runs 10+ epochs with per-resolver tracking to address critical gaps:
   - Gap #1: Sybil resistance (reputation accumulation)
   - Gap #2: Governance failure (detection decay scenarios)
   - Gap #3: Multi-year dynamics (proof of stability)

   Output: Per-resolver history + aggregated statistics + equity trajectories.

   The :equity-trajectories key in the return value is a map
   {resolver-id → [profit@epoch1 profit@epoch2 ...]} built by
   resolver-sim.sim.trajectory/build-equity-trajectories.

   Attribution model: each epoch, batch trials are routed to individual resolvers
   via a TrialRouter (default: :uniform-random). Conservation invariants are
   checked inside run-single-epoch — any routing bug that changes the economics
   throws before it can corrupt trajectory data.

   Phase 3 extensions:
   - :use-shared-world? true  — share a single dispute pool across all resolvers
     instead of running independent honest/malicious batches. Models genuine
     competition: n-trials disputes total, split between resolver groups.
   - :kernel-validation-sample-size N — validate N scenarios per epoch through
     the Sew replay kernel to check protocol-param self-consistency."
  (:require [resolver-sim.sim.batch         :as batch]
            [resolver-sim.sim.shared-batch         :as shared-batch]
            [resolver-sim.sim.kernel-bridge        :as kernel-bridge]
            [resolver-sim.sim.reputation           :as rep]
            [resolver-sim.sim.trial-router         :as router]
            [resolver-sim.sim.trajectory           :as trajectory]
            [resolver-sim.sim.defection            :as defection]
            [resolver-sim.sim.stochastic-equilibrium :as stoch-eq]
            [resolver-sim.stochastic.rng           :as rng]
            [resolver-sim.stochastic.economics     :as econ]
            [clojure.set]))

;; ---------------------------------------------------------------------------
;; Multi-Epoch Known Metrics Registry
;; ---------------------------------------------------------------------------
;;
;; Metrics in this registry are computed from stochastic population models,
;; NOT from deterministic replay. They describe emergent properties of
;; multi-epoch simulations: reputation trajectories, coordination effects,
;; and strategy dominance ratios.
;;
;; These metrics **must NOT** be referenced in deterministic scenario
;; expectations or theory.falsifies-if clauses; they only exist in Phase J
;; output and population-level aggregates.
;;
;; For metrics from deterministic replay, see
;; resolver-sim.contract-model.replay/known-metrics.

(def known-metrics
  "Stochastic population metrics computed by Phase J (multi-epoch simulation).
   These describe reputation trajectories and strategy dynamics.
   Not available in deterministic replay; must not be used in :theory.falsifies-if
   unless :theory {:metric-scope :population}. Keep in sync with
   `resolver-sim.contract-model.replay/population-metrics`."
  #{:coalition/net-profit        ;; aggregate profit of all resolvers of a strategy
    :malice-mean-profit         ;; mean profit per malicious resolver
    :dominance-ratio            ;; ratio of dominant strategy profit to mean
    :mean-profit                ;; mean profit across all resolvers in epoch
    :reputation-concentration}) ;; Herfindahl index of profit concentration

(defn apply-detection-decay
  [params epoch]
  (rep/apply-detection-decay params epoch))

;; ---------------------------------------------------------------------------
;; Shared-world trial pool helpers
;; ---------------------------------------------------------------------------

(defn- split-paired-trials
  "Split a shared paired-trial pool between honest and strategic resolver groups.

   In shared-world mode, there are n-trials total disputes (not 2n). The pool
   is split proportionally by resolver mix: honest fraction → honest-slice,
   remainder → strategic-slice. Each resolver handles disputes from their slice.

   Both slices contain the full paired trial data (:profit-honest and
   :profit-malice). route-epoch uses the correct field for each group:
     honest resolvers   → :profit-honest from honest-slice
     strategic resolvers → :profit-malice from strategic-slice"
  [paired-trials honest-count strategic-count]
  (let [total       (+ honest-count strategic-count)
        n           (count paired-trials)
        honest-n    (if (pos? total)
                      (Math/round (double (* n (/ honest-count total))))
                      (quot n 2))
        honest-n    (max 0 (min n honest-n))
        strat-n     (- n honest-n)]
    {:trials-honest    (vec (take honest-n paired-trials))
     :trials-malicious (vec (take strat-n (drop honest-n paired-trials)))}))

;; ---------------------------------------------------------------------------
;; Single-epoch runner
;; ---------------------------------------------------------------------------

(defn run-single-epoch
  "Run one epoch (N trials) and return batch stats + per-resolver histories.

   Uses run-batch-with-attribution to get per-trial results, then routes them
   to individual resolvers via the trial router. Conservation invariants are
   checked inside route-epoch — any attribution bug throws immediately.

   Args:
     rng               — seeded SplittableRandom (mutated in place)
     epoch             — epoch number (1-based)
     resolver-histories — {resolver-id → resolver-state}
     n-trials          — trials to run this epoch
     params            — simulation parameters
     trial-router      — TrialRouter implementation (default: uniform-random)

   Params flags:
     :use-shared-world?            — when true, use a single paired dispute pool
                                     so honest and malicious resolvers compete over
                                     the same n-trials disputes (not independent 2n).
      :kernel-validation-sample-size — when set to N>0, validate N scenarios per
                                       epoch through the Sew replay kernel; results
                                       added to :kernel-validation in epoch-summary.
      :kernel-validation-min-pass-rate — when set >0 with kv-sample-size, marks the
                                         epoch as below-threshold if pass-rate falls
                                         below this value (default: 0.0 = no gate).

   Returns: {:epoch-summary {...} :updated-histories {...}}"
  ([rng epoch resolver-histories n-trials params]
   (run-single-epoch rng epoch resolver-histories n-trials params router/uniform-random))
  ([rng epoch resolver-histories n-trials params trial-router]
   (let [decayed-params (apply-detection-decay params epoch)
         use-shared?    (:use-shared-world? params false)
         kv-samples     (:kernel-validation-sample-size params 0)

         ;; Split RNG into independent streams for each use
         [rng-ab rng-cd]    (rng/split-rng rng)
         [rng-h  rng-m]     (rng/split-rng rng-ab)
         [rng-route rng-cd2] (rng/split-rng rng-cd)
         [rng-decay rng-def] (rng/split-rng rng-cd2)
         rng-kv   (when (pos? kv-samples) (first (rng/split-rng rng-h)))

         honest-ids    (vec (keep (fn [[id r]] (when (= :honest (:strategy r)) id))
                                  resolver-histories))
         strategic-ids (vec (keep (fn [[id r]] (when (not= :honest (:strategy r)) id))
                                  resolver-histories))

         ;; ── Batch generation ─────────────────────────────────────────────
         ;; Independent mode: two separate batches (original behaviour).
         ;; Shared-world mode: one paired pool split by resolver mix.
         {:keys [aggregate aggregate-malice trials-honest trials-malicious]}
         (if use-shared?
           (let [{:keys [paired-trials aggregate aggregate-malice]}
                 (shared-batch/run-shared-batch rng-h n-trials decayed-params)
                 {:keys [trials-honest trials-malicious]}
                 (split-paired-trials paired-trials
                                      (count honest-ids)
                                      (count strategic-ids))]
             {:aggregate        aggregate
              :aggregate-malice aggregate-malice
              :trials-honest    trials-honest
              :trials-malicious trials-malicious})
           ;; Default: two independent batches (preserves backward compat)
           (let [r-h (batch/run-batch-with-attribution
                      rng-h n-trials (assoc decayed-params :strategy :honest))
                 r-m (batch/run-batch-with-attribution
                      rng-m n-trials (assoc decayed-params :strategy :malicious))]
             {:aggregate        (:aggregate r-h)
              :aggregate-malice (:aggregate r-m)
              :trials-honest    (:trials r-h)
              :trials-malicious (:trials r-m)}))

         honest-mean (:honest-mean aggregate)
         malice-mean (:malice-mean aggregate-malice)
         dom-ratio   (cond
                       (and malice-mean (pos? malice-mean)) (double (/ honest-mean malice-mean))
                       (pos? honest-mean)                   Double/POSITIVE_INFINITY
                       :else                                1.0)

          ;; ── Financial flow tracking for budget-balance ───────────────────
         all-trials          (concat trials-honest trials-malicious)
         fee-per-trial       (econ/calculate-fee (:escrow-size decayed-params 10000)
                                                 (:resolver-fee-bps decayed-params 150))
         epoch-fees-collected (long (* (if use-shared? n-trials (* 2 n-trials)) fee-per-trial))
          effective-bond-loss-fn
          (fn [t]
            (let [slashed? (:slashed? t false)
                  frozen?  (:frozen? t false)
                  escaped? (:escaped? t false)
                  pending? (:slashing-pending? t false)]
              ;; Mirrors resolve-dispute: escaped trials lose nothing, frozen
              ;; trials owe the loss immediately (even if pending), and
              ;; unfrozen pending losses are deferred.
              (cond
                (and slashed? frozen? escaped?) 0
                frozen? (get t :bond-loss 0)
                pending? 0
                :else (get t :bond-loss 0))))
         epoch-bond-loss (reduce + 0 (map effective-bond-loss-fn all-trials))
         epoch-fraud-upside  (reduce + 0 (map #(get % :fraud-upside 0) all-trials))

          ;; ── Optional kernel validation ────────────────────────────────────
         kv-min-pass-rate (:kernel-validation-min-pass-rate params 0.0)
         kernel-validation
         (when (pos? kv-samples)
           (try
             (let [kv-result (kernel-bridge/run-kernel-validation decayed-params kv-samples rng-kv)
                   pass-rate (:pass-rate kv-result 0.0)]
               (if (and (pos? kv-min-pass-rate) (< pass-rate kv-min-pass-rate))
                 (assoc kv-result
                        :below-threshold? true
                        :min-pass-rate kv-min-pass-rate
                        :violations (conj (:violations kv-result [])
                                          {:type :kernel-validation-below-threshold
                                           :pass-rate pass-rate
                                           :min-pass-rate kv-min-pass-rate}))
                 (assoc kv-result :below-threshold? false :min-pass-rate kv-min-pass-rate)))
             (catch Exception e
               {:pass-count 0 :fail-count kv-samples :pass-rate 0.0
                :below-threshold? (pos? kv-min-pass-rate)
                :min-pass-rate kv-min-pass-rate
                :violations [{:scenario-id "error" :halt-reason :exception
                              :message     (.getMessage e)}]})))

         epoch-summary
         (cond-> {:epoch                  epoch
                  :n-trials               n-trials
                  :batch-mode             (if use-shared? :shared-world :independent)
                  :honest-mean-profit     honest-mean
                  :malice-mean-profit     malice-mean
                  :dominance-ratio        dom-ratio
                  :appeal-rate            (:appeal-rate aggregate)
                  :slash-rate             (:slash-rate aggregate-malice)
                  :fraud-slash-rate       (:fraud-slash-rate aggregate-malice 0.0)
                  :reversal-slash-rate    (:reversal-slash-rate aggregate-malice 0.0)
                  :timeout-slash-rate     (:timeout-slash-rate aggregate-malice 0.0)
                  :l2-detection-rate      (:l2-detection-rate aggregate-malice 0.0)
                  :detection-rate         (:detection-rate aggregate-malice)
                  :l1-reversal-rate       (:p-l1-reversal decayed-params)
                  :l2-reversal-rate       (:p-l2-reversal decayed-params)
                  :routing-mode           (router/routing-mode trial-router)
                  :epoch-fees-collected  epoch-fees-collected
                  :epoch-bond-loss       epoch-bond-loss
                  :epoch-fraud-upside    epoch-fraud-upside}
           kernel-validation
           (assoc :kernel-validation kernel-validation))

         {:keys [honest-attribution strategic-attribution]}
         (router/route-epoch honest-ids strategic-ids
                             trials-honest trials-malicious
                             trial-router rng-route)

         ;; Merge both attribution maps and update histories
         all-attribution (merge honest-attribution strategic-attribution)

         updated-histories
         (reduce-kv
          (fn [acc id resolver]
            (let [attr       (get all-attribution id
                                  {:trials 0 :profit 0.0 :slashed 0
                                   :verdicts 0 :correct 0 :appealed 0 :escalated 0})
                  is-honest? (= :honest (:strategy resolver))]
              (assoc acc id
                     (rep/update-resolver-history
                      resolver
                      (:profit attr 0.0)
                      (:verdicts attr 0)
                      (if is-honest? (:verdicts attr 0) 0)
                      (pos? (:slashed attr 0))
                      epoch
                      :trials    (:trials attr 0)
                      :appealed  (:appealed attr 0)
                      :escalated (:escalated attr 0)))))
          {}
          resolver-histories)]

    ;; ── Optional strategy adaptation ─────────────────────────────────────
    ;; Supports binary legacy defection and load-optimal adaptation modes.
     (let [{:keys [updated-histories defection-events diagnostics resolved-config]}
           (defection/apply-strategy-defection
            rng-def updated-histories epoch (assoc params :_epoch-trials n-trials))

           epoch-summary
           (cond-> epoch-summary
             (or (seq defection-events) (seq diagnostics))
             (assoc :defection
                    (defection/defection-summary
                      defection-events
                      resolver-histories
                      updated-histories
                      diagnostics
                      resolved-config)))]

       ;; Apply population decay with seeded RNG; thread next-id to avoid ID collisions
       (let [{:keys [histories next-id slashed-exits natural-exits]}
             (rep/apply-epoch-decay updated-histories epoch params rng-decay
                                    (:_next-resolver-id params 10000))

             epoch-summary
             (cond-> epoch-summary
               (pos? (+ slashed-exits natural-exits))
               (assoc :slashed-exits slashed-exits
                      :natural-exits natural-exits))]

         {:epoch-summary     epoch-summary
          :updated-histories histories
          :next-resolver-id  next-id})))))

(defn run-multi-epoch
  "Run N epochs with reputation tracking.

   Args:
     rng               — seeded SplittableRandom
     n-epochs          — number of epochs (default: :n-epochs in params, or 10)
     n-trials-per-epoch — trials per epoch (default: :n-trials-per-epoch in params, or 500)
     params            — simulation parameters
     on-epoch-complete — optional 1-arity fn called with [epoch-n epoch-summary]
                         after each epoch completes; used for incremental output.
                         Default: no-op. Does not affect simulation state.

   Returns: {:epoch-results [...] :resolver-histories {...}
             :aggregated-stats {...} :equity-trajectories {...}
             :full-trajectories {...} :strategy-spread-trajectories [...]}"
  ([rng n-epochs n-trials-per-epoch params]
   (run-multi-epoch rng n-epochs n-trials-per-epoch params (fn [_ _] nil)))
  ([rng n-epochs n-trials-per-epoch params on-epoch-complete]
   (let [n-resolvers (get params :n-resolvers 100)
         strategy-mix (or (:strategy-mix params)
                          {:honest 0.50 :lazy 0.15 :malicious 0.25 :collusive 0.10})]

     (println (format "\n🔁 Running Phase J: Multi-Epoch Reputation Simulation"))
     (println (format "   Epochs: %d" n-epochs))
     (println (format "   Trials per epoch: %d" n-trials-per-epoch))
     (println (format "   Initial resolvers: %d" n-resolvers))
     (println (format "   Strategy mix: %s" strategy-mix))
     (println "")

     (let [initial-histories (rep/initialize-resolvers n-resolvers strategy-mix)

           result-accumulator
           (reduce
            (fn [acc epoch-num]
              (let [[rng-1 rng-2]  (rng/split-rng (:rng acc))
                    prev-histories (:histories acc initial-histories)
                    {:keys [epoch-summary updated-histories next-resolver-id]}
                    (run-single-epoch rng-1 epoch-num prev-histories n-trials-per-epoch
                                      (assoc params :_next-resolver-id (:next-id acc n-resolvers)))

                   ;; Rich per-resolver snapshot: everything needed for full trajectories.
                   ;; :profit is the cumulative total so equity trajectories are monotone.
                    epoch-snapshot
                    (reduce-kv
                     (fn [m id r]
                       (let [eh (get (:epoch-history r) (keyword (str "epoch-" epoch-num)) {})]
                         (assoc m id
                                {:profit     (rep/cumulative-profit r)
                                 :reputation (rep/win-rate r)
                                 :trials     (:total-trials r 0)
                                 :verdicts   (:total-verdicts r 0)
                                 :slashed    (:total-slashed r 0)
                                 :appealed   (:total-appealed r 0)
                                 :escalated  (:total-escalated r 0)
                                 :strategy   (:strategy r)})))
                     {} updated-histories)]

                (on-epoch-complete epoch-num epoch-summary)
                (println (format "   Epoch %d: honest=%.0f, malice=%.0f, dominance=%.1f×"
                                 epoch-num
                                 (:honest-mean-profit epoch-summary)
                                 (:malice-mean-profit epoch-summary)
                                 (:dominance-ratio epoch-summary)))
                (assoc acc
                       :rng             rng-2
                       :epochs          (cons epoch-summary (:epochs acc []))
                       :histories       updated-histories
                       :epoch-snapshots (conj (:epoch-snapshots acc []) epoch-snapshot)
                       :next-id         (or next-resolver-id (:next-id acc n-resolvers))
                       :total-fees-collected (+ (:total-fees-collected acc 0)
                                                (:epoch-fees-collected epoch-summary 0))
                       :total-bond-loss      (+ (:total-bond-loss acc 0)
                                                (:epoch-bond-loss epoch-summary 0))
                       :total-fraud-upside   (+ (:total-fraud-upside acc 0)
                                                (:epoch-fraud-upside epoch-summary 0)))))
            {:rng rng :epochs [] :histories initial-histories :epoch-snapshots []
             :next-id n-resolvers}
            (range 1 (inc n-epochs)))

           epoch-results   (reverse (:epochs result-accumulator []))
           epoch-snapshots (:epoch-snapshots result-accumulator [])
           final-histories (:histories result-accumulator initial-histories)
           resolver-ids    (keys final-histories)

           final-stats
           (let [honest-rs (filter #(= :honest     (:strategy (val %))) final-histories)
                 malice-rs (filter #(not= :honest  (:strategy (val %))) final-histories)
                 h-profits (map #(rep/cumulative-profit (val %)) honest-rs)
                 m-profits (map #(rep/cumulative-profit (val %)) malice-rs)
                 h-wr      (map #(rep/win-rate (val %)) honest-rs)
                 m-wr      (map #(rep/win-rate (val %)) malice-rs)
                 exited-ids (clojure.set/difference
                             (set (keys initial-histories))
                             (set (keys final-histories)))
                 exits     (count exited-ids)
                 exited-init-strats (keep #(-> initial-histories (get %) :strategy) exited-ids)
                 honest-exits    (count (filter #(= :honest %) exited-init-strats))
                 lazy-exits      (count (filter #(= :lazy %) exited-init-strats))
                 malicious-exits (count (filter #(= :malicious %) exited-init-strats))
                 collusive-exits (count (filter #(= :collusive %) exited-init-strats))
                 ;; Sum slashed-exits and natural-exits across all epoch-results
                 total-slashed-exits (reduce #(+ %1 (:slashed-exits %2 0)) 0 epoch-results)
                 total-natural-exits (reduce #(+ %1 (:natural-exits %2 0)) 0 epoch-results)]
             {:final-resolver-count       (count final-histories)
              :total-resolver-exits       exits
              :total-slashed-exits        total-slashed-exits
              :total-natural-exits        total-natural-exits
              :honest-exit-count          honest-exits
              :lazy-exit-count            lazy-exits
              :malicious-exit-count       malicious-exits
              :collusive-exit-count       collusive-exits
              :honest-final-count         (count honest-rs)
              :malice-final-count         (count malice-rs)
              :honest-cumulative-profit   (if (seq h-profits) (double (apply + h-profits)) 0.0)
              :malice-cumulative-profit   (if (seq m-profits) (double (apply + m-profits)) 0.0)
              :honest-avg-win-rate        (if (seq h-wr) (double (/ (apply + h-wr) (count h-wr))) 0.0)
              :malice-avg-win-rate        (if (seq m-wr) (double (/ (apply + m-wr) (count m-wr))) 0.0)
              :honest-exit-rate           (double (/ exits (max 1 n-resolvers)))
              :malice-survival-rate       (double (/ (count malice-rs)
                                                     (max 1 (count (filter #(not= :honest (:strategy (val %)))
                                                                           initial-histories)))))
              :flow-total-fees-collected  (get result-accumulator :total-fees-collected 0)
              :flow-total-bond-loss       (get result-accumulator :total-bond-loss 0)
              :flow-total-fraud-upside    (get result-accumulator :total-fraud-upside 0)
              :coalition-net-profit       (let [hp (if (seq h-profits) (double (apply + h-profits)) 0.0)
                                                mp (if (seq m-profits) (double (apply + m-profits)) 0.0)]
                                            (- mp hp))})

;; Legacy equity-only trajectories (backward compat)
           profit-snapshots (mapv (fn [s] (reduce-kv (fn [m id v] (assoc m id (:profit v))) {} s))
                                  epoch-snapshots)
           equity-trajectories          (trajectory/build-equity-trajectories profit-snapshots resolver-ids)
           strategy-spread-trajectories (trajectory/strategy-spread-trajectory final-histories profit-snapshots)

          ;; Full multi-dimensional trajectories (Step 3)
           full-trajectories (trajectory/build-full-trajectories epoch-snapshots resolver-ids final-histories)

           result
           {:scenario-id            (:scenario-id params "phase-j-unnamed")
            :n-epochs               n-epochs
            :n-trials-per-epoch     n-trials-per-epoch
            :initial-resolver-count n-resolvers
            :initial-strategy-mix   strategy-mix
            :initial-composition
            (let [h-count (count (filter #(= :honest     (:strategy (val %))) initial-histories))
                  l-count (count (filter #(= :lazy      (:strategy (val %))) initial-histories))
                  mx-count (count (filter #(= :malicious (:strategy (val %))) initial-histories))
                  c-count (count (filter #(= :collusive (:strategy (val %))) initial-histories))
                  m-count (+ l-count mx-count c-count)
                  t-count (count initial-histories)
                  n       (double (max 1 t-count))]
              {:honest-count    h-count
               :lazy-count      l-count
               :malicious-count mx-count
               :collusive-count c-count
               :malice-count    m-count
               :total-count     t-count
               :honest-share    (/ h-count n)
               :malice-share    (/ m-count n)})
            :epoch-results          (or epoch-results [])
            :resolver-histories     final-histories
            :aggregated-stats       final-stats
            :equity-trajectories          equity-trajectories
            :full-trajectories            full-trajectories
            :strategy-spread-trajectories strategy-spread-trajectories
            :trajectory/meta              {:type        :trajectory/equity
                                           :epoch-count n-epochs
                                           :unit        :profit}}

          ;; Phase 6: stochastic equilibrium evaluation wired into every Phase J run.
          ;; The result is a map of {:claim-results [...] :overall-status :pass/:fail/:inconclusive}
          ;; keyed under :equilibrium-report. Does not affect existing callers — opt-out by
          ;; ignoring the key.
           eq-report (stoch-eq/evaluate-stochastic-equilibrium result)]

       (println (format "\n✓ Phase J complete. Final state:"))
       (println (format "   Resolvers exited: %d (%d slashed, %d natural)"
                        (:total-resolver-exits final-stats)
                        (:total-slashed-exits final-stats 0)
                        (:total-natural-exits final-stats 0)))
       (println (format "   Honest cumulative: %.0f" (:honest-cumulative-profit final-stats)))
       (println (format "   Malice cumulative: %.0f" (:malice-cumulative-profit final-stats)))
       (println (format "   Win rate - honest: %.1f%%" (* 100 (:honest-avg-win-rate final-stats))))
       (println (format "   Win rate - malice: %.1f%%" (* 100 (:malice-avg-win-rate final-stats))))
       (println "")
       (stoch-eq/print-equilibrium-report eq-report)

       (assoc result :equilibrium-report eq-report)))))
