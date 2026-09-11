(ns resolver-sim.tools.participation-stability
  "Three-layer participation stability check: passthrough, classified, fallback.

   Evaluation order:
   1. Always extract aggregate passthrough diagnostics.
   2. Validate aggregate evidence — if incomplete or inconsistent, return
      :inconclusive with :evaluation-mode :invalid-evidence.
   3. If per-strategy classified data is available, run the classified check
      (honest exit rate ≤ 10%, productive exit rate ≤ 20%).
   4. Otherwise, run the aggregate fallback check (40% threshold).
   5. Record :evaluation-mode as :classified, :fallback, or :invalid-evidence."
  (:require [clojure.string :as str]))

;; ── Configurable assumptions ──────────────────────────────────────────────────

(def ^:dynamic *aggregate-fallback-threshold*
  "Aggregate exit rate threshold for the fallback check.
   Set to 40% — corresponds to a minimum retention floor of 60%
   of the initial resolver population.
   Not calibrated to any single observed result. A 35% threshold
   would overfit to a current 30% result."
  0.40)

(def aggregate-fallback-threshold-source
  {:threshold-name :aggregate-fallback-threshold
   :threshold-value 0.40
   :threshold-description "Aggregate exit rate threshold for fallback check"
   :retention-floor 0.60
   :retention-floor-description "Minimum 60% of initial resolver population must remain"
   :rationale "Not calibrated to any single observed result. 35% would overfit to current 30% result."
   :rejected-alternatives [{:value 0.35
                            :reason "calibrated only to current 30% result; not a general-purpose threshold"}]})

(def ^:dynamic *classified-honest-exit-threshold*
  "Maximum allowable honest exit rate in the classified check.
   Honest resolvers are the protocol's most productive participants;
   their exit rate must remain low for participation stability."
  0.10)

(def ^:dynamic *classified-productive-exit-threshold*
  "Maximum allowable productive-participant exit rate in the classified check.
   Productive participants (honest + lazy) must be retained at or above
   this level."
  0.20)

;; ── Generic helpers ──────────────────────────────────────────────────────────

(defn evaluation-result
  "Construct a standardised participation-stability result.
   Every field uses a failsafe default so callers never see nil status
   or missing :evaluation-mode."
  [{:keys [status evaluation-mode reason evidence]}]
  {:status (or status :inconclusive)
   :evaluation-mode (or evaluation-mode :invalid-evidence)
   :reason reason
   :evidence (or evidence {})})

(defn complete-finite-numbers?
  "Returns true if every key in required-keys is present in m and has a
   finite numeric value (not nil, not NaN, not Infinity).
   Does NOT require non-negative — use separate consistency checks for that."
  [m required-keys]
  (and (map? m)
       (every? (fn [k]
                 (let [v (get m k)]
                   (and (number? v)
                        (not (Double/isNaN (double v)))
                        (not (Double/isInfinite (double v))))))
               required-keys)))

;; ── Passthrough diagnostics ──────────────────────────────────────────────────

(def passthrough-keys
  "Keys always extracted from :aggregated-stats for passthrough diagnostics."
  [:total-resolver-exits :final-resolver-count
   :honest-exit-count :lazy-exit-count :malicious-exit-count :collusive-exit-count])

(def initial-composition-keys
  "Keys from :initial-composition used for per-strategy breakdowns."
  [:honest-count :lazy-count :malicious-count :collusive-count])

(defn extract-passthrough-diagnostics
  "Extract aggregate and per-strategy diagnostics from a multi-epoch result.
   Always returns a map; missing stats default to zero or nil so downstream
   consumers (reports, notebooks, artifacts) remain compatible."
  [result]
  (let [initial   (:initial-resolver-count result)
        stats     (:aggregated-stats result {})
        exits     (:total-resolver-exits stats 0)
        final     (:final-resolver-count stats)
        h-exits   (:honest-exit-count stats 0)
        l-exits   (:lazy-exit-count stats 0)
        mx-exits  (:malicious-exit-count stats 0)
        c-exits   (:collusive-exit-count stats 0)
        agg-rate  (if (and initial (pos? initial))
                    (/ (double exits) (double initial))
                    nil)
        init-comp (:initial-composition result)
        h-init    (:honest-count init-comp 0)
        l-init    (:lazy-count init-comp 0)
        mx-init   (:malicious-count init-comp 0)
        c-init    (:collusive-count init-comp 0)
        prod-init (+ h-init l-init)
        prod-exits (+ h-exits l-exits)
        prod-rate  (if (pos? prod-init)
                     (/ (double prod-exits) (double prod-init))
                     nil)]
    {:total-exits          exits
     :initial-count        initial
     :final-count          final
     :aggregate-exit-rate  agg-rate
     :honest-exits         h-exits
     :lazy-exits           l-exits
     :malicious-exits      mx-exits
     :collusive-exits      c-exits
     :productive-exits     prod-exits
     :productive-init      prod-init
     :productive-exit-rate prod-rate
     :honest-init          h-init
     :lazy-init            l-init
     :malicious-init       mx-init
     :collusive-init       c-init
     :retention-count      (when (and initial exits) (- initial exits))
     :retention-rate       (when (and initial (pos? initial))
                             (/ (- (double initial) (double exits)) (double initial)))}))

;; ── Consistency validation ───────────────────────────────────────────────────

(defn validate-participation-consistency
  "Validate internal consistency of participation data.
   Returns nil if consistent, or a reason keyword describing the first
   inconsistency found."
  [result passthrough]
  (let [initial   (:initial-resolver-count result)
        stats     (:aggregated-stats result {})
        exits     (:total-resolver-exits stats 0)
        final     (:final-resolver-count stats)
        h-exits   (:honest-exit-count stats 0)
        l-exits   (:lazy-exit-count stats 0)
        mx-exits  (:malicious-exit-count stats 0)
        c-exits   (:collusive-exit-count stats 0)
        init-comp (:initial-composition result)
        h-init    (:honest-count init-comp 0)
        l-init    (:lazy-count init-comp 0)
        mx-init   (:malicious-count init-comp 0)
        c-init    (:collusive-count init-comp 0)]
    (cond
      (neg? (double initial))
      :negative-initial-count
      (neg? (double exits))
      :negative-total-exits
      (and final (neg? (double final)))
      :negative-final-count
      (neg? (double h-exits))
      :negative-honest-exits
      (neg? (double l-exits))
      :negative-lazy-exits
      (neg? (double mx-exits))
      :negative-malicious-exits
      (neg? (double c-exits))
      :negative-collusive-exits
      (> exits initial)
      :exits-exceed-initial
      (and final (not= final (- initial exits)))
      :final-count-mismatch
      ;; Only check per-strategy sums when :initial-composition is present,
      ;; otherwise per-strategy defaults are meaningless placeholders.
      (and (:initial-composition result)
           (not= exits (+ h-exits l-exits mx-exits c-exits)))
      :per-strategy-exit-sum-mismatch
      (and (:initial-composition result) (> h-exits h-init))
      :honest-exits-exceed-init
      (and (:initial-composition result) (> l-exits l-init))
      :lazy-exits-exceed-init
      (and (:initial-composition result) (> mx-exits mx-init))
      :malicious-exits-exceed-init
      (and (:initial-composition result) (> c-exits c-init))
      :collusive-exits-exceed-init
      :else nil)))

;; ── Aggregate evidence completeness ──────────────────────────────────────────

(def aggregate-required-keys
  "Required keys for the aggregate fallback check."
  [:initial-resolver-count :total-resolver-exits :final-resolver-count])

(defn aggregate-evidence-complete?
  "Returns true if all aggregate-level required keys are present and finite."
  [result]
  (let [stats (:aggregated-stats result {})]
    (and (complete-finite-numbers? result [:initial-resolver-count])
         (complete-finite-numbers? stats [:total-resolver-exits :final-resolver-count]))))

;; ── Classified evidence availability ─────────────────────────────────────────

(def classified-required-keys
  "Required :aggregated-stats keys for the classified check."
  [:honest-exit-count :lazy-exit-count
   :malicious-exit-count :collusive-exit-count])

(def classified-composition-keys
  "Required :initial-composition keys for the classified check."
  [:honest-count :lazy-count :malicious-count :collusive-count])

(defn classified-evidence-available?
  "Returns true if per-strategy exit and initial-composition data are available
   AND aggregate evidence is also complete."
  [result]
  (let [stats     (:aggregated-stats result {})
        init-comp (:initial-composition result)]
    (and (aggregate-evidence-complete? result)
         (complete-finite-numbers? stats classified-required-keys)
         (complete-finite-numbers? init-comp classified-composition-keys))))

;; ── Classified check ─────────────────────────────────────────────────────────

(defn run-classified-check
  "Run the classified participation check.
   Requires per-strategy exit and initial composition data.
   Honest exit rate ≤ 10% AND productive exit rate ≤ 20%.
   Malicious and collusive exits are excluded from the instability numerator
   because their departure is expected under functioning slashing."
  [result passthrough]
  (let [initial       (:initial-resolver-count result)
        stats         (:aggregated-stats result {})
        h-exits       (:honest-exit-count stats 0)
        l-exits       (:lazy-exit-count stats 0)
        init-comp     (:initial-composition result)
        h-init        (:honest-count init-comp 0)
        l-init        (:lazy-count init-comp 0)
        h-rate        (if (pos? h-init) (/ (double h-exits) (double h-init)) nil)
        prod-init     (+ h-init l-init)
        prod-exits    (+ h-exits l-exits)
        prod-rate     (if (pos? prod-init) (/ (double prod-exits) (double prod-init)) nil)
        h-threshold   *classified-honest-exit-threshold*
        p-threshold   *classified-productive-exit-threshold*
        h-ok?         (and h-rate (<= h-rate h-threshold))
        p-ok?         (and prod-rate (<= prod-rate p-threshold))
        thresholds    {:classified-honest-exit-threshold        h-threshold
                       :classified-productive-exit-threshold    p-threshold
                       :aggregate-fallback-threshold            *aggregate-fallback-threshold*}
        observed      {:honest-exit-rate        h-rate
                       :productive-exit-rate    prod-rate
                       :aggregate-exit-rate     (:aggregate-exit-rate passthrough)
                       :total-exits             (:total-exits passthrough)
                       :initial-count           initial}]
    (if (and h-ok? p-ok?)
      (evaluation-result
       {:status          :pass
        :evaluation-mode :classified
        :reason          (format "classified check: honest-exit-rate=%.1f%% (≤%.0f%%), productive-exit-rate=%.1f%% (≤%.0f%%)"
                                 (* 100 (or h-rate 0)) (* 100 h-threshold)
                                 (* 100 (or prod-rate 0)) (* 100 p-threshold))
        :evidence        (merge passthrough
                                {:honest-exit-rate             h-rate
                                 :productive-exit-rate         prod-rate
                                 :honest-exit-threshold        h-threshold
                                 :productive-exit-threshold    p-threshold
                                 :malicious-collusive-excluded true
                                 :evaluation-mode              :classified
                                 :thresholds                   thresholds
                                 :threshold-source             aggregate-fallback-threshold-source
                                 :observed-values              observed
                                 :classified-check             {:honest-exit-rate          h-rate
                                                                :productive-exit-rate      prod-rate
                                                                :honest-exit-threshold     h-threshold
                                                                :productive-exit-threshold p-threshold
                                                                :honest-exit-ok?           h-ok?
                                                                :productive-exit-ok?       p-ok?}})})
      (evaluation-result
       {:status          :fail
        :evaluation-mode :classified
        :reason          (format "classified check: honest-exit-rate=%.1f%% (limit %.0f%%), productive-exit-rate=%.1f%% (limit %.0f%%)"
                                 (* 100 (or h-rate 0)) (* 100 h-threshold)
                                 (* 100 (or prod-rate 0)) (* 100 p-threshold))
        :evidence        (merge passthrough
                                {:honest-exit-rate             h-rate
                                 :productive-exit-rate         prod-rate
                                 :honest-exit-threshold        h-threshold
                                 :productive-exit-threshold    p-threshold
                                 :malicious-collusive-excluded true
                                 :evaluation-mode              :classified
                                 :thresholds                   thresholds
                                 :threshold-source             aggregate-fallback-threshold-source
                                 :observed-values              observed
                                 :classified-check             {:honest-exit-rate          h-rate
                                                                :productive-exit-rate      prod-rate
                                                                :honest-exit-threshold     h-threshold
                                                                :productive-exit-threshold p-threshold
                                                                :honest-exit-ok?           h-ok?
                                                                :productive-exit-ok?       p-ok?}})}))))

;; ── Fallback check ───────────────────────────────────────────────────────────

(defn run-fallback-check
  "Run the aggregate fallback participation check.
   Uses *aggregate-fallback-threshold* (40%) as the maximum allowable
   aggregate exit rate. Includes threshold metadata in the result."
  [result passthrough]
  (let [initial   (:initial-resolver-count result)
        stats     (:aggregated-stats result {})
        exits     (:total-resolver-exits stats 0)
        agg-rate  (if (pos? initial) (/ (double exits) (double initial)) nil)
        threshold *aggregate-fallback-threshold*
        retention-floor (- 1.0 threshold)
        ok?       (and agg-rate (< agg-rate threshold))
        thresholds {:aggregate-fallback-threshold threshold}
        observed  {:aggregate-exit-rate agg-rate
                   :total-exits         exits
                   :initial-count       initial
                   :final-count         (:final-resolver-count stats)
                   :retention-floor     (- 1.0 (or agg-rate 0))}]
    (if ok?
      (evaluation-result
       {:status          :pass
        :evaluation-mode :fallback
        :reason          (format "classified data unavailable; fallback: aggregate-exit-rate=%.1f%% < %.0f%% (retention floor %.0f%%)"
                                 (* 100 agg-rate) (* 100 threshold) (* 100 retention-floor))
        :evidence        (merge passthrough
                                {:aggregate-fallback-threshold threshold
                                 :retention-floor              retention-floor
                                 :evaluation-mode              :fallback
                                 :thresholds                   thresholds
                                 :threshold-source             aggregate-fallback-threshold-source
                                 :observed-values              observed
                                 :fallback-check               {:aggregate-exit-rate       agg-rate
                                                                :aggregate-fallback-threshold threshold
                                                                :retention-floor           retention-floor
                                                                :aggregate-exit-ok?        ok?}
                                 :fallback-reason              "classified exit data unavailable for per-strategy decomposition"})})
      (evaluation-result
       {:status          :fail
        :evaluation-mode :fallback
        :reason          (format "classified data unavailable; fallback: aggregate-exit-rate=%.1f%% ≥ %.0f%% (retention floor %.0f%%)"
                                 (* 100 agg-rate) (* 100 threshold) (* 100 retention-floor))
        :evidence        (merge passthrough
                                {:aggregate-fallback-threshold threshold
                                 :retention-floor              retention-floor
                                 :evaluation-mode              :fallback
                                 :thresholds                   thresholds
                                 :threshold-source             aggregate-fallback-threshold-source
                                 :observed-values              observed
                                 :fallback-check               {:aggregate-exit-rate       agg-rate
                                                                :aggregate-fallback-threshold threshold
                                                                :retention-floor           retention-floor
                                                                :aggregate-exit-ok?        ok?}
                                 :fallback-reason              "classified exit data unavailable for per-strategy decomposition"})}))))

;; ── Orchestrator ─────────────────────────────────────────────────────────────

(defn check-participation-stability
  "Three-layer participation stability check.
   Evaluation order:
   1. Always extract aggregate passthrough diagnostics.
   2. Validate aggregate evidence completeness and consistency.
      If incomplete or inconsistent → :inconclusive :invalid-evidence.
   3. If per-strategy classified data is available → run classified check
      with honest ≤ 10% and productive ≤ 20% thresholds.
   4. Otherwise → run aggregate fallback check with 40% threshold.
   5. Record :evaluation-mode as :classified, :fallback, or :invalid-evidence.

   Returns a map with :status, :evaluation-mode, :reason, :required-fields,
   :missing-fields, :thresholds, :threshold-source, :observed-values, :evidence."
  [result]
  (let [passthrough (extract-passthrough-diagnostics result)]
    (if-not (aggregate-evidence-complete? result)
      (let [stats    (:aggregated-stats result {})
            required aggregate-required-keys
            missing  (vec (for [k required
                                :let [v (if (= k :initial-resolver-count)
                                          (get result k)
                                          (get stats k))]
                                :when (or (nil? v)
                                          (not (number? v))
                                          (Double/isNaN (double v))
                                          (Double/isInfinite (double v)))]
                            k))]
        (evaluation-result
         {:status          :inconclusive
          :evaluation-mode :invalid-evidence
          :reason          (str "aggregate evidence incomplete: missing "
                                (pr-str missing))
          :required-fields required
          :missing-fields  missing
          :evidence        (assoc passthrough
                                  :evaluation-mode :invalid-evidence
                                  :required-fields required
                                  :missing-fields  missing)}))
      (let [inconsistency (validate-participation-consistency result passthrough)]
        (if inconsistency
          (evaluation-result
           {:status          :inconclusive
            :evaluation-mode :invalid-evidence
            :reason          (str "inconsistent participation data: " (name inconsistency))
            :required-fields aggregate-required-keys
            :missing-fields  []
            :evidence        (assoc passthrough
                                    :evaluation-mode :invalid-evidence
                                    :inconsistency   inconsistency
                                    :required-fields aggregate-required-keys
                                    :missing-fields  [])})
          (if (classified-evidence-available? result)
            (run-classified-check result passthrough)
            (run-fallback-check result passthrough)))))))
