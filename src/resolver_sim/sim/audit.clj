(ns resolver-sim.sim.audit
  "Multi-epoch stability audit: falsifiable hypothesis checks and canonical output.

   The core question is not 'does honest beat malicious on average?' —
   the epoch-level batch results already show that. The question is:

     Can a malicious resolver survive, compound, or become dominant over time?

   analyze-multi-epoch answers this with a composite of absolute, distributional,
   and trajectory-slope checks.

   Step 6a — calibration: run known-pass and known-fail fixtures to prove the
   audit detects both outcomes before running the canonical baseline.

   Step 6b — canonical run: run-phase-j-audit executes the full 1000-epoch
   baseline and writes all output files.

   Layering: sim/* only. File I/O via resolver-sim.io.audit-outputs."
  (:require [resolver-sim.vcs            :as vcs])
  (:require [clojure.string               :as str])
  (:require [resolver-sim.evidence.config :as evcfg])
  (:require [resolver-sim.hash.canonical :as hc])
  (:require [resolver-sim.logging :as log]))
;; ---------------------------------------------------------------------------
;; Utility
;; ---------------------------------------------------------------------------

(defn- safe-div [n d] (if (zero? d) 0.0 (double (/ n d))))

(defn- mean-of [coll]
  (if (empty? coll) 0.0 (double (/ (reduce + 0.0 coll) (count coll)))))

(defn- percentile
  "p in [0,1]. Returns value at p-th percentile of coll."
  [coll p]
  (if (empty? coll)
    0.0
    (let [sorted (sort coll)
          idx    (min (dec (count sorted))
                      (int (Math/floor (* p (count sorted)))))]
      (double (nth sorted idx)))))

(defn- linear-slope
  "Least-squares slope of a numeric series (x = index, y = value).
   Returns positive value when y is trending upward."
  [ys]
  (let [n (count ys)]
    (if (< n 2)
      0.0
      (let [xs    (range n)
            xmean (mean-of xs)
            ymean (mean-of ys)
            numer (reduce + 0.0 (map (fn [x y] (* (- x xmean) (- y ymean))) xs ys))
            denom (reduce + 0.0 (map (fn [x] (Math/pow (- x xmean) 2)) xs))]
        (if (zero? denom) 0.0 (double (/ numer denom)))))))

;; ---------------------------------------------------------------------------
;; Per-resolver equity helpers
;; ---------------------------------------------------------------------------

(defn- resolver-final-equity [full-trajectories resolver-id]
  (-> (get full-trajectories resolver-id)
      :equity
      last
      (or 0.0)))

(defn- stratify
  "Partition resolver-ids by strategy from resolver-histories."
  [resolver-histories]
  (reduce-kv (fn [acc id r]
               (let [s (:strategy r :unknown)]
                 (update acc s (fnil conj []) id)))
             {} resolver-histories))

;; ---------------------------------------------------------------------------
;; Dominance-ratio trajectory check
;; ---------------------------------------------------------------------------

(defn- dominance-ratio-trajectory
  "Return vector of dominance ratios from epoch-results."
  [epoch-results]
  (mapv :dominance-ratio epoch-results))

(defn- malice-slope-improving?
  "True when the dominance ratio is trending downward (malice gaining ground)
   in the final tail-fraction of epochs. A positive slope on dominance means
   honest is pulling ahead; negative means malice is closing the gap.

   Infinite dominance ratios (malice profit <= 0) are capped at 1000.0 before
   regression to prevent NaN from ∞ - ∞ arithmetic.

   tail-fraction — fraction of epochs to use for the regression (default 0.2)."
  [epoch-results tail-fraction]
  (let [cap    1000.0
        ratios (mapv #(min cap (double %)) (dominance-ratio-trajectory epoch-results))
        n      (count ratios)
        tail-n (max 2 (int (* tail-fraction n)))
        tail   (subvec ratios (- n tail-n))]
    (< (linear-slope tail) 0.0)))

;; ---------------------------------------------------------------------------
;; Main audit function
;; ---------------------------------------------------------------------------

(defn analyze-multi-epoch
  "Run the composite multi-epoch stability hypothesis against simulation output.

   result        — return value of run-multi-epoch
   opts          — optional overrides:
      :dominance-threshold     minimum honest/malice ratio (default 1.2)
      :max-malice-equity-share maximum fraction of total equity malice may hold (default 0.3)
      :tail-fraction           fraction of epochs used for slope regression (default 0.2)
      :min-detection-rate      minimum per-epoch detection probability (default 0.0)

   The hypothesis being tested:
     H: A malicious resolver cannot survive, compound, or dominate over time.

   Returns:
   {:hypothesis     :malice-cannot-dominate-over-time
    :result         :pass | :fail
    :epochs-checked N
    :checks         {:honest-mean-positive?            bool
                     :malice-mean-nonpositive?          bool
                     :dominance-above-threshold?        bool
                     :honest-p10-above-malice-p90?      bool
                     :malice-equity-share-below-limit?  bool
                     :malice-slope-not-improving?       bool
                     :malice-survival-rate-low?         bool}
    :summary        {:min-dominance-ratio    N
                     :final-honest-p10       N
                     :final-malice-p90       N
                     :malice-equity-share    N
                     :malice-survival-rate   N
                     :dominance-slope        N (positive = honest pulling ahead)}
    :violations     [{:check kw :epoch N :value V}]
    :epoch-results  [...]}"
  ([result] (analyze-multi-epoch result {}))
  ([result opts]
   (let [{:keys [epoch-results aggregated-stats full-trajectories resolver-histories]} result
         n-epochs          (count epoch-results)
         dom-threshold     (get opts :dominance-threshold 1.2)
         max-equity-share  (get opts :max-malice-equity-share 0.3)
         tail-fraction     (get opts :tail-fraction 0.2)
         min-detection     (get opts :min-detection-rate 0.0)

         strata          (stratify resolver-histories)
         honest-ids      (get strata :honest [])
         malice-ids      (concat (get strata :malicious [])
                                 (get strata :lazy [])
                                 (get strata :collusive []))

         honest-equities (mapv #(resolver-final-equity full-trajectories %) honest-ids)
         malice-equities (mapv #(resolver-final-equity full-trajectories %) malice-ids)

         total-equity    (+ (apply + 0.0 honest-equities) (apply + 0.0 malice-equities))
         malice-equity   (apply + 0.0 malice-equities)

         dom-ratios      (dominance-ratio-trajectory epoch-results)
         min-dominance   (if (seq dom-ratios) (apply min dom-ratios) 0.0)
         dom-slope       (linear-slope dom-ratios)

         honest-p10      (percentile honest-equities 0.10)
         malice-p90      (percentile malice-equities 0.90)

         equity-share    (safe-div malice-equity (max 1.0 total-equity))
         survival-rate   (:malice-survival-rate aggregated-stats 0.0)

          ;; Epoch-level check: any epoch where dominance fell below threshold
         dominance-violations
         (for [[e r] (map-indexed vector epoch-results)
               :when (< (:dominance-ratio r 0.0) dom-threshold)]
           {:check :dominance-above-threshold?
            :epoch (inc e)
            :value (:dominance-ratio r)})

         detection-violations
         (when (pos? min-detection)
           (let [missing (filter (fn [[_ r]] (nil? (:detection-rate r))) epoch-results)]
             (concat
              (for [[e _] missing]
                {:check :detection-rate-no-data
                 :epoch (inc e)
                 :detail "detection-rate not present in epoch result (may be stochastic-only metric)"
                 :threshold min-detection})
              (for [[e r] (map-indexed vector epoch-results)
                    :let [dr (:detection-rate r)]
                    :when (and dr (< dr min-detection))]
                {:check :detection-rate-above-minimum
                 :epoch (inc e)
                 :value dr :threshold min-detection}))))

         survival-threshold (get opts :malice-survival-threshold 0.5)

         checks
         {:honest-mean-positive?           (pos? (:honest-cumulative-profit aggregated-stats 0.0))
          :malice-mean-nonpositive?         (<= (:malice-cumulative-profit aggregated-stats 0.0) 0.0)
          :dominance-above-threshold?       (>= min-dominance dom-threshold)
          :honest-p10-above-malice-p90?     (> honest-p10 malice-p90)
          :malice-equity-share-below-limit? (< equity-share max-equity-share)
          :malice-slope-not-improving?      (not (malice-slope-improving? epoch-results tail-fraction))
          :malice-survival-rate-low?        (< survival-rate survival-threshold)
          :detection-rate-above-minimum?    (if (pos? min-detection)
                                              (empty? detection-violations)
                                              true)}

         all-violations
         (concat dominance-violations
                 detection-violations
                 (for [[check passed?] checks
                       :when (not passed?)]
                   {:check check :value (get checks check)}))

         passed? (empty? all-violations)]

     {:hypothesis     :malice-cannot-dominate-over-time
      :result         (if passed? :pass :fail)
      :epochs-checked n-epochs
      :checks         checks
      :summary        {:min-dominance-ratio  min-dominance
                       :dominance-slope      dom-slope
                       :final-honest-p10     honest-p10
                       :final-malice-p90     malice-p90
                       :malice-equity-share  equity-share
                       :malice-survival-rate survival-rate}
      :violations     (vec all-violations)
      :epoch-results  epoch-results})))

;; ---------------------------------------------------------------------------
;; Reproducibility manifest
;; ---------------------------------------------------------------------------

(defn- vcs-head-sha
  "Return current VCS HEAD SHA, or \"unknown\" if jj/git is unavailable."
  []
  (try
    (or (vcs/commit-sha) "unknown")
    (catch Exception e
      (log/warn! :git-sha-resolution-failed {:error (.getMessage e)})
      "unknown")))

(defn params-hash
  "Return a SHA-256 hex digest of params using canonical hashing.
   Stable across runs with the same params map, regardless of key insertion order.
   Uses hash-with-intent with :params-manifest intent to separate from evidence hashes."
  [params]
  ;; Floating-point params (probabilities, multipliers) are not directly
  ;; encodable by the canonical encoder, which rejects lossy numeric types to
  ;; prevent aliasing. Project through the canonical structure view first so
  ;; doubles/floats become tagged float64 leaves while integers, strings, and
  ;; keywords pass through unchanged (preserving integer-only param hashes).
  (hc/hash-with-intent {:hash/intent :params-manifest}
                       (:structure (hc/project-world-to-structure-view params :params-manifest))))

(defn make-manifest
  "Build a reproducibility manifest for a completed multi-epoch run.

   Required fields (every canonical run must include all of them):
     result      — return value of run-multi-epoch
     params      — the params map used (NOT the file path — the actual map)
     params-file — path to the params EDN file used
     seed        — the RNG seed used

   Optional overrides:
     :git-commit   — override auto-detected git SHA
     :sim-version  — simulator version string (default from evidence config)

   Returns a map suitable for writing to EDN alongside results."
  [result params params-file seed & {:keys [git-commit sim-version]}]
  (let [default-version (get (evcfg/framework) "version" "0.1.0")]
    {:params-file       params-file
     :params-hash       (params-hash params)
     :seed              seed
     :epochs            (:n-epochs result)
     :trials-per-epoch  (:n-trials-per-epoch result)
     :initial-resolvers (:initial-resolver-count result)
     :routing-mode      (-> result :epoch-results first :routing-mode)
     :git-commit        (or git-commit (vcs-head-sha))
     :sim-version       (or sim-version default-version)
     :completed-at      (str (java.time.Instant/now))}))

;; ---------------------------------------------------------------------------
;; Canonical output writing
;; ---------------------------------------------------------------------------

(defn trajectory-csv-rows
  "Build seq of per-resolver-per-epoch rows for the trajectory CSV.
   Each row: resolver-id, strategy, epoch (1-based), equity, reputation,
              trial-count, verdict-count, slash-count"
  [full-trajectories resolver-histories n-epochs]
  (for [[id traj] full-trajectories
        epoch-idx (range n-epochs)]
    (let [strategy (get-in resolver-histories [id :strategy] :unknown)]
      {:resolver_id  id
       :strategy     (name strategy)
       :epoch        (inc epoch-idx)
       :equity       (get-in traj [:equity epoch-idx] 0.0)
       :reputation   (get-in traj [:reputation epoch-idx] 0.0)
       :trial_count  (long (get-in traj [:trial-count epoch-idx] 0))
       :verdict_count (long (get-in traj [:verdict-count epoch-idx] 0))
       :slash_count  (long (get-in traj [:slash-count epoch-idx] 0))})))

(defn audit-markdown
  "Generate a human-readable PHASE_J_STABILITY_AUDIT.md from the audit result and manifest."
  [audit-result manifest]
  (let [{:keys [result epochs-checked checks summary violations]} audit-result
        pass? (= :pass result)]
    (str/join "\n"
              ["# Phase J Multi-Epoch Stability Audit"
               ""
               (str "**Result: " (if pass? "✅ PASS" "❌ FAIL") "**")
               ""
               "## Run metadata"
               ""
               (format "| Field | Value |")
               (format "|---|---|")
               (format "| Params file | `%s` |" (:params-file manifest))
               (format "| Params hash | `%s` |" (subs (:params-hash manifest) 0 16))
               (format "| Seed | %d |" (:seed manifest))
               (format "| Epochs | %d |" (:epochs manifest))
               (format "| Trials/epoch | %d |" (:trials-per-epoch manifest))
               (format "| Routing | %s |" (name (or (:routing-mode manifest) :unknown)))
               (format "| Git commit | `%s` |" (subs (or (:git-commit manifest) "unknown") 0 (min 8 (count (str (:git-commit manifest))))))
               (format "| Completed | %s |" (:completed-at manifest))
               ""
               "## Hypothesis"
               ""
               "> A malicious resolver cannot survive, compound, or become dominant over time."
               ""
               (format "Epochs checked: **%d**" epochs-checked)
               ""
               "## Checks"
               ""
               "| Check | Result |"
               "|---|---|"
               (format "| honest-mean-positive? | %s |" (if (:honest-mean-positive? checks) "✅" "❌"))
               (format "| malice-mean-nonpositive? | %s |" (if (:malice-mean-nonpositive? checks) "✅" "❌"))
               (format "| dominance-above-threshold? | %s |" (if (:dominance-above-threshold? checks) "✅" "❌"))
               (format "| honest-p10-above-malice-p90? | %s |" (if (:honest-p10-above-malice-p90? checks) "✅" "❌"))
               (format "| malice-equity-share-below-limit? | %s |" (if (:malice-equity-share-below-limit? checks) "✅" "❌"))
               (format "| malice-slope-not-improving? | %s |" (if (:malice-slope-not-improving? checks) "✅" "❌"))
               (format "| malice-survival-rate-low? | %s |" (if (:malice-survival-rate-low? checks) "✅" "❌"))
               ""
               "## Summary metrics"
               ""
               "| Metric | Value |"
               "|---|---|"
               (format "| Min dominance ratio | %.3f |" (double (:min-dominance-ratio summary 0.0)))
               (let [ds (double (:dominance-slope summary 0.0))]
                 (format "| Dominance slope | %s |" (if (Double/isNaN ds) "n/a" (format "%.6f" ds))))
               (format "| Honest p10 equity | %.1f |" (double (:final-honest-p10 summary 0.0)))
               (format "| Malice p90 equity | %.1f |" (double (:final-malice-p90 summary 0.0)))
               (format "| Malice equity share | %.1f%% |" (* 100 (double (:malice-equity-share summary 0.0))))
               (format "| Malice survival rate | %.1f%% |" (* 100 (double (:malice-survival-rate summary 0.0))))
               ""
               (if (empty? violations)
                 "## No violations — all checks passed."
                 (str/join "\n"
                           (concat
                            ["## Violations"
                             ""
                             "| Check | Epoch | Value |"
                             "|---|---|---|"]
                            (map (fn [v]
                                   (format "| %s | %s | %s |"
                                           (name (:check v))
                                           (or (:epoch v) "-")
                                           (or (:value v) "-")))
                                 violations))))
               ""])))

;; ---------------------------------------------------------------------------
;; Step 6b — canonical run (pure; file writes via io.audit-outputs)
;; ---------------------------------------------------------------------------

(defn run-phase-j-audit
  "Run the canonical multi-epoch stability audit.

   params      — simulation parameters map
   params-file — path to the params file (for manifest)

   Optional keyword args:
     :seed             — RNG seed (default: :rng-seed in params, or 42)
     :n-epochs         — epoch count override (default: :n-epochs in params)
     :n-trials         — trials/epoch override (default: :n-trials-per-epoch in params)
     :audit-opts       — map of options for analyze-multi-epoch
     :epoch-callback   — (fn [epoch-n summary]) invoked after each epoch

   Returns {:result run-result :audit audit-result :manifest manifest}."
  [params params-file & {:keys [seed n-epochs n-trials audit-opts epoch-callback]}]
  (let [seed'         (or seed (:rng-seed params 42))
        n-epochs'     (or n-epochs (:n-epochs params 10))
        n-trials'     (or n-trials (:n-trials-per-epoch params 500))
        rng-inst      (@(requiring-resolve 'resolver-sim.stochastic.rng/make-rng) seed')
        run-fn        @(requiring-resolve 'resolver-sim.sim.multi-epoch/run-multi-epoch)
        result        (run-fn rng-inst n-epochs' n-trials' params (or epoch-callback (constantly nil)))
        params-audit  (:audit-thresholds params {})
        merged-opts   (merge params-audit (or audit-opts {}))
        audit-res     (analyze-multi-epoch result merged-opts)
        mfst      (make-manifest result params params-file seed')]
    {:result  result
     :audit   audit-res
     :manifest mfst}))