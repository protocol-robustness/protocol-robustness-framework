(ns resolver-sim.notebook-support.speds.var
  "P4: Explicit distribution + VaR projection.

   TWO canonical artifacts, both strictly downstream of risk-projection.v1:

     scenario-distribution.v1
       An explicit probability/weighting artifact over ONE declared outcome
       variable. It declares its model, per-scenario weights, a normalization
       root, and the basis for those weights. This is the first-class boundary
       the user drew: a p95/p99 has NO VaR semantics without it.

     var-projection.v1
       Consumes a risk-projection.v1 and exactly one scenario-distribution.v1,
       and only then emits :var/p95, :var/p99, expected shortfall, and tail
       attribution. VaR claims exist nowhere else.

   Boundaries (do not cross):

   1. CORPUS ≠ PROBABILITY. The distribution model is empirical — uniform
      weights over the measured scenario corpus. The resulting quantiles are
      corpus-relative exposure/loss quantiles, NOT a probabilistic forecast of
      market outcomes. This is stated in :interpretation and rendered on the
      card.

   2. VaR CLAIMS LIVE ONLY IN var-projection.v1. risk-projection.v1 keeps its
      :distribution-policy/status :not-measured; it never emits VaR numbers.

   3. NOT-MEASURED SCENARIOS ARE EXCLUDED FROM THE DISTRIBUTION. Only measured
      scenarios (those with an outcome value) receive weight. Exclusion counts
      are reported so a VaR number can never hide unmeasured corpus mass.

   4. EXACT ARITHMETIC. Quantiles and expected shortfall are computed with
      integer weights; expected shortfall is stored as an exact
      {:numerator N :denominator D} pair (canonical-safe), never a float.

   Both namespaces are pure and deterministic: identical inputs yield identical
   output."
  (:require [resolver-sim.hash.canonical :as canonical]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.notebook-support.speds.risk :as risk]))

(def distribution-schema "scenario-distribution.v1")
(def var-schema "var-projection.v1")

(def distribution-domain-tag "SCENARIO_DISTRIBUTION_V1")
(def var-domain-tag "VAR_PROJECTION_V1")

(def outcome-fields
  "The outcome variable keys accepted by a distribution, mapped to the
   per-scenario metric field they are read from."
  {:per-scenario-peak-exposure  :peak-observed-exposure
   :per-scenario-max-event-loss :max-observed-event-loss})

(def empirical-model "empirical-scenario-distribution.v1")

;; ──────────────────────────────────────────────────────────────────────────
;; Scenario distribution (scenario-distribution.v1)
;; ──────────────────────────────────────────────────────────────────────────

(defn- commit [domain-tag content]
  {:canonical/bytes (canonical/canonical-bytes-hex content)
   :canonical/hash  (hash-ref/sha256-ref
                     (canonical/domain-hash domain-tag content))})

(defn distribution-content
  "The semantic body committed by a scenario-distribution.v1 root.
   Reads only the artifact's own stored fields so verify re-derives the root."
  [{:keys [model outcome scenario-weights normalization-root basis coverage source]}]
  {:schema distribution-schema
   :model model
   :outcome (name outcome)
   :quantity risk/quantity-label
   :scenario-weights scenario-weights
   :normalization-root normalization-root
   :basis basis
   :coverage coverage
   :source source})

(defn build-distribution
  "Build scenario-distribution.v1 from a risk-projection over one outcome.

   v1 supports the empirical model only: uniform weights (weight 1) over the
   measured scenarios that carry the outcome value. Not-measured scenarios are
   excluded and counted."
  [proj outcome]
  (let [field       (get outcome-fields outcome)
        per-scenario (:per-scenario (:metrics proj))
        weighted    (filterv (fn [s] (some? (get s field))) per-scenario)
        n           (count weighted)
        weighted-ids (set (map :scenario/id weighted))
        weights     (mapv (fn [s] {:scenario/id (:scenario/id s) :weight 1})
                          weighted)
        coverage    {:scenario-count (count per-scenario)
                     :weighted-scenario-count n
                     :excluded-scenario-count (- (count per-scenario) n)
                     :excluded-scenarios (vec (map :scenario/id
                                                   (remove (fn [s] (contains? weighted-ids
                                                                              (:scenario/id s)))
                                                           per-scenario)))}
        basis       (str "uniform empirical weights over the measured scenarios "
                         "of risk-projection " (:projection-id proj))
        source      {:risk-projection-id (:projection-id proj)
                     :risk-projection-root (get-in proj [:risk-projection/root :canonical/hash])}
        normalization-root {:scenario-count n
                            :sum-weights n
                            :normalization "uniform (weight 1/N)"}
        model       empirical-model
        content     (distribution-content {:model model
                                           :outcome outcome
                                           :scenario-weights weights
                                           :normalization-root normalization-root
                                           :basis basis
                                           :coverage coverage
                                           :source source})]
    {:schema distribution-schema
     :distribution-id (subs (canonical/domain-hash distribution-domain-tag content) 0 16)
     :context {:risk-projection-id (:projection-id proj)}
     :model model
     :outcome outcome
     :quantity risk/quantity-label
     :scenario-weights weights
     :normalization-root normalization-root
     :basis basis
     :coverage coverage
     :source source
     :distribution/root (commit distribution-domain-tag content)}))

;; ──────────────────────────────────────────────────────────────────────────
;; VaR projection (var-projection.v1)
;; ──────────────────────────────────────────────────────────────────────────

(defn- weighted-outcomes
  "Join a distribution's weights with the outcome values from a risk-projection,
   sorted by (value, scenario-id). Scenarios in the distribution that the
   projection no longer carries are dropped (reported via coverage mismatch)."
  [proj distribution]
  (let [field  (get outcome-fields (:outcome distribution))
        by-id  (into {} (map (juxt :scenario/id identity))
                     (:per-scenario (:metrics proj)))
        joined (for [{:keys [scenario/id weight]} (:scenario-weights distribution)
                     :let [s (get by-id id)]
                     :when (and s (some? (get s field)))]
                 {:scenario/id id
                  :value (get s field)
                  :weight weight})]
    (sort-by (juxt :value :scenario/id) joined)))

(defn weighted-quantile
  "Weighted empirical alpha-quantile.

   Definition (documented on the artifact): the smallest outcome value whose
   cumulative weight reaches `alpha * W_total` (weighted empirical inverse CDF).
   Returns {:value v :cumulative-weight acc}, or nil-valued when empty."
  [alpha weighted]
  (let [w-total (reduce + (map :weight weighted))]
    (loop [acc 0, [h & t] (seq weighted)]
      (if h
        (let [acc (+ acc (:weight h))]
          (if (>= acc (* alpha w-total))
            {:value (:value h) :cumulative-weight acc}
            (recur acc t)))
        {:value nil :cumulative-weight acc}))))

(defn- expected-shortfall
  "Weighted mean of outcomes strictly above var-value. Exact integer pair.
   Returns :not-measured when the tail is empty."
  [var-value weighted]
  (let [tail (filterv #(> (:value %) var-value) weighted)]
    (if (empty? tail)
      :not-measured
      {:numerator  (reduce + (map (fn [x] (* (:value x) (:weight x))) tail))
       :denominator (reduce + (map :weight tail))})))

(defn- tail-attribution [var-value weighted]
  (->> weighted
       (filter #(> (:value %) var-value))
       (mapv (fn [x] (select-keys x [:scenario/id :value :weight])))))

(defn var-content
  "The semantic body committed by a var-projection.v1 root.
   Reads only the artifact's own stored fields so verify re-derives the root."
  [{:keys [outcome distribution source coverage method interpretation metrics]}]
  {:schema var-schema
   :outcome (name outcome)
   :quantity risk/quantity-label
   :distribution {:model (:model distribution)
                  :distribution-id (:distribution-id distribution)
                  :distribution-root (:distribution-root distribution)}
   :source source
   :coverage coverage
   :method method
   :interpretation interpretation
   :metrics metrics})

(defn build-var-projection
  "Build var-projection.v1 from a risk-projection and one scenario-distribution.
   Emits :var/p95, :var/p99, expected shortfall, and tail attribution over the
   DECLARED weighted distribution only."
  [proj distribution]
  (let [weighted (weighted-outcomes proj distribution)
        n        (count weighted)
        q95      (weighted-quantile 0.95 weighted)
        q99      (weighted-quantile 0.99 weighted)
        v95      (:value q95)
        v99      (:value q99)
        es95     (if (nil? v95) :not-measured (expected-shortfall v95 weighted))
        es99     (if (nil? v99) :not-measured (expected-shortfall v99 weighted))
        metrics  {:var/p95 {:value v95 :basis (if (nil? v95) :not-measured :derived)}
                  :var/p99 {:value v99 :basis (if (nil? v99) :not-measured :derived)}
                  :expected-shortfall/p95
                  (if (= :not-measured es95)
                    {:basis :not-measured}
                    (assoc es95 :basis :derived))
                  :expected-shortfall/p99
                  (if (= :not-measured es99)
                    {:basis :not-measured}
                    (assoc es99 :basis :derived))
                  :tail-attribution/p99
                  {:basis (if (and (some? v99) (seq (tail-attribution v99 weighted)))
                            :derived :not-measured)
                   :scenarios (tail-attribution v99 weighted)}}
        source   {:risk-projection-id (:projection-id proj)
                  :risk-projection-root (get-in proj [:risk-projection/root :canonical/hash])
                  :distribution-id (:distribution-id distribution)
                  :distribution-root (get-in distribution [:distribution/root :canonical/hash])}
        dist-ref {:model (:model distribution)
                  :distribution-id (:distribution-id distribution)
                  :distribution-root (get-in distribution [:distribution/root :canonical/hash])}
        coverage {:weighted-scenario-count n
                  :distribution-scenario-count (count (:scenario-weights distribution))
                  :dropped-scenario-count (- (count (:scenario-weights distribution)) n)}
        method   {:quantile "weighted-empirical-inverse-cdf"
                  :var-definition "VaR_c = the smallest outcome value whose cumulative weight reaches c * W_total"
                  :expected-shortfall-definition "weighted mean of outcomes strictly above VaR_c"}
        interpretation
        (str "VaR is over the empirical scenario corpus (" empirical-model
             ", uniform weights). These are corpus-relative "
             (if (= :per-scenario-max-event-loss (:outcome distribution))
               "loss" "exposure")
             " quantiles, NOT a probabilistic forecast of market outcomes; "
             "absent a market probability model they must not be read as "
             "probability-weighted Value-at-Risk.")
        content (var-content {:outcome (:outcome distribution)
                              :distribution dist-ref
                              :source source
                              :coverage coverage
                              :method method
                              :interpretation interpretation
                              :metrics metrics})]
    {:schema var-schema
     :var/id (subs (canonical/domain-hash var-domain-tag content) 0 16)
     :context {:risk-projection-id (:projection-id proj)
               :distribution-id (:distribution-id distribution)}
     :outcome (:outcome distribution)
     :quantity risk/quantity-label
     :distribution dist-ref
     :source source
     :coverage coverage
     :method method
     :interpretation interpretation
     :metrics metrics
     :var/root (commit var-domain-tag content)}))

;; ──────────────────────────────────────────────────────────────────────────
;; Verification
;; ──────────────────────────────────────────────────────────────────────────

(defn verify-distribution-root
  "Recompute a scenario-distribution.v1 root from its own semantic fields."
  [d]
  (commit distribution-domain-tag
          (distribution-content d)))

(defn verify-var-root
  "Recompute a var-projection.v1 root from its own semantic fields."
  [v]
  (commit var-domain-tag
          (var-content v)))
