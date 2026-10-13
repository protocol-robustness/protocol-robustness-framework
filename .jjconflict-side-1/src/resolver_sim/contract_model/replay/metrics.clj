(ns resolver-sim.contract-model.replay.metrics
  "Metric registry and accumulation logic for replay engine.

   Decomposed from contract-model/replay to improve kernel modularity."
  (:require [clojure.string :as str]
            [resolver-sim.protocols.protocol :as proto]
            [resolver-sim.scenario.theory :as theory]))

(def population-metrics
  "Metrics that require stochastic / multi-epoch population simulation.
   Must not appear in single-trace `:theory :falsifies-if` unless
   `:theory :metric-scope` is `:population` (multi-epoch runner).
   Keep in sync with `resolver-sim.sim.multi-epoch/known-metrics`."
  #{:coalition/net-profit
    :detection-rate
    :malice-mean-profit
    :dominance-ratio
    :mean-profit
    :reputation-concentration})

(def base-metrics
  "Universal metrics incremented by the replay engine for every protocol."
  #{:attack-attempts
    :attack-successes
    :rejected-attacks
    :reverts
    :invariant-violations
    :batch-buckets
    :batch-events
    :batch-conflicts})

(defn metric-key
  "Coerce a metric name to a keyword; preserve namespaced keys like :coalition/net-profit."
  [x]
  (cond
    (keyword? x) x
    (string? x)
    (let [s (if (.startsWith ^String x ":") (subs x 1) x)]
      (if (.contains s "/")
        (let [[ns n] (str/split s #"/" 2)]
          (keyword ns n))
        (keyword s)))
    :else (keyword (str x))))

(defn falsifies-if-metric-refs
  "All metric keywords referenced by a `:falsifies-if` vector or predicate tree."
  [falsifies-if]
  (cond
    (nil? falsifies-if) []
    (sequential? falsifies-if)
    (vec (distinct (keep #(when-let [m (:metric %)] (metric-key m)) falsifies-if)))

    (map? falsifies-if)
    (vec (distinct
          (concat
           (when-let [m (:metric falsifies-if)] [(metric-key m)])
           (mapcat falsifies-if-metric-refs (:and falsifies-if))
           (mapcat falsifies-if-metric-refs (:or falsifies-if))
           (mapcat falsifies-if-metric-refs (list (:not falsifies-if)))
           (mapcat falsifies-if-metric-refs (:always falsifies-if))
           (mapcat falsifies-if-metric-refs (:eventually falsifies-if))
           (when-let [p (:after falsifies-if)] (falsifies-if-metric-refs (:predicate p)))
           (when-let [p (:before falsifies-if)] (falsifies-if-metric-refs (:predicate p)))
           (when-let [p (:implies falsifies-if)]
             (concat (falsifies-if-metric-refs (:if p))
                     (falsifies-if-metric-refs (:then p)))))))

    :else []))

(defn theory-metric-scope [scenario]
  (metric-key (or (get-in scenario [:theory :metric-scope]) :trace)))

(defn zero-metrics
  "Initialise the metrics accumulator for one replay run.
   Optional second arg `metrics-profile` (:sew-integrated by default):
     - :sew-integrated / :yield-provider — include protocol vocab metrics
     - :base — only base metrics (no protocol vocab)"
  ([protocol] (zero-metrics protocol :sew-integrated))
  ([protocol metrics-profile]
   (let [base {:attack-attempts      0
               :attack-successes     0
               :rejected-attacks     0
               :reverts              0
               :invariant-violations 0
               :batch-buckets        0
               :batch-events         0
               :batch-conflicts      0
               :invariant-results    {}}
         vocab (if (and (satisfies? proto/EconomicModel protocol)
                        (not= :base metrics-profile))
                 (proto/metric-vocabulary protocol)
                 #{})]
     (into base (map #(vector % 0) vocab)))))

(defn accum-metrics
  "Accumulate metrics after processing one event.
   Optional last arg `metrics-profile` (:sew-integrated by default):
     - :sew-integrated / :yield-provider — call protocol accum
     - :base — skip protocol accum (base metrics only)"
  ([protocol metrics event trace-entry agent-index world-before]
   (accum-metrics protocol metrics event trace-entry agent-index world-before :sew-integrated))
  ([protocol metrics event trace-entry agent-index world-before metrics-profile]
   (let [result-kw (:result trace-entry)
         accepted? (= result-kw :ok)
         agent     (get agent-index (:agent event))
         attack?   (if (satisfies? proto/EconomicModel protocol)
                     (proto/adversarial-event? protocol event agent)
                     false)
         tags      (:event-tags trace-entry)
         world-after (:world trace-entry)
         base (cond-> metrics
                (and attack? accepted?)
                (update :attack-successes inc)

                attack?
                (update :attack-attempts inc)

                (and attack? (not accepted?))
                (update :rejected-attacks inc)

                (not accepted?)
                (update :reverts inc)

                (= :batch-conflict (:error trace-entry))
                (update :batch-conflicts inc)

                (:violations trace-entry)
                (-> (update :invariant-violations inc)
                    (update :invariant-results
                            (fn [acc]
                              (reduce (fn [m [kw r]]
                                        (if (:holds? r) m (assoc m kw :fail)))
                                      acc
                                      (:violations trace-entry))))))]
     (if (and (satisfies? proto/EconomicModel protocol)
              (not= :base metrics-profile))
       (proto/accum-protocol-metrics protocol base tags event accepted? attack? world-before world-after)
       base))))

(defn expectation-metric-keys [scenario]
  (when-let [metrics (get-in scenario [:expectations :metrics])]
    (into #{} (map #(theory/metric-key (:name %)) metrics))))
