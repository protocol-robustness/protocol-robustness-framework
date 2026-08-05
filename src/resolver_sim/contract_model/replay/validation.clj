(ns resolver-sim.contract-model.replay.validation
  "Scenario validation logic for replay engine.

   Decomposed from contract-model/replay to improve kernel modularity."
  (:require [clojure.string                :as str]
            [resolver-sim.scenario.schema-profile :as schema-profile]
            [resolver-sim.contract-model.replay.metrics :as metrics]))

(def ^:private supported-versions (:supported-versions schema-profile/default-profile))

(defn- to-epoch-second
  "Normalize a validated event time to Unix epoch seconds for ordering checks.

   Mirrors the replay clock's second-precision semantics (replay.temporal/epoch-second):
   numbers are truncated via long, Instants via getEpochSecond. Only reached after
   the type check below, so t is a number or a java.time.Instant."
  [t]
  (if (number? t) (long t) (.getEpochSecond ^java.time.Instant t)))

(defn validate-agents
  "Validate a list of agent maps {:id :address :role :strategy ...} for structural correctness.
   Returns {:ok true} or {:ok false :error kw :detail {...}}.

   Checks: non-empty, unique :id values, unique :address values."
  [agents]
  (let [id-counts   (frequencies (map :id agents))
        addr-counts (frequencies (map :address agents))
        dup-ids     (keys (filter (fn [[_ n]] (> n 1)) id-counts))
        dup-addrs   (keys (filter (fn [[_ n]] (> n 1)) addr-counts))]
    (cond
      (empty? agents)   {:ok false :error :no-agents}
      (seq dup-ids)     {:ok false :error :duplicate-agent-ids
                         :detail {:duplicates (vec dup-ids)}}
      (seq dup-addrs)   {:ok false :error :duplicate-agent-addresses
                         :detail {:duplicates (vec dup-addrs)}}
      :else             {:ok true})))

(defn validate-scenario
  "Validate a scenario map for structural correctness before replay.
   Accepts an optional effective-metrics set used to validate metric references
   in :expectations and :theory. Defaults to base-metrics (universal counters).

   When `opts` includes `:strict-validation? false`, purpose/theory requirements
   for enriched schemas are skipped (library-style scenarios)."
  ([scenario] (validate-scenario scenario metrics/base-metrics {}))
  ([scenario effective-metrics] (validate-scenario scenario effective-metrics {}))
  ([scenario effective-metrics opts]
   (let [strict?   (if (contains? opts :strict-validation?)
                     (:strict-validation? opts)
                     true)
         version     (str (:schema-version scenario))
         agents      (:agents scenario)
         events      (sort-by :seq (:events scenario))
         known-ids   (set (concat (map :id agents)
                                  (keep :save-agent-as events)
                                  (keep :save-id-as events)))
         init-time   (get scenario :initial-block-time 1000)
         agent-check (validate-agents agents)
         ;; Events whose :time is not a supported clock value (number or Instant).
         ;; Computed up-front so the type check below can reference it in both the
         ;; cond test and the failure detail, and so the ordering checks never see
         ;; a non-comparable / mixed-type :time.
         bad-times   (vec (remove #(or (number? (:time %))
                                       (instance? java.time.Instant (:time %)))
                                  events))]
     (cond
       (not (contains? supported-versions version))
       {:ok false :error :unsupported-schema-version
        :detail {:expected supported-versions :got version}}

       (and (contains? (set (schema-profile/required-fields version)) :id)
            (not (:id scenario)))
       {:ok false :error :missing-id :detail "v1.1 scenarios must have a unique :id"}

       (and (contains? (set (schema-profile/required-fields version)) :title)
            (not (:title scenario)))
       {:ok false :error :missing-title :detail "v1.1 scenarios must have a human-readable :title"}

       (and (contains? (set (schema-profile/required-fields version)) :purpose)
            (not (:purpose scenario)))
       {:ok false :error :missing-purpose :detail "v1.1 scenarios must declare a :purpose"}

       (and (contains? (set (schema-profile/required-fields version)) :scenario-author)
            (not (string? (:scenario-author scenario))))
       {:ok false :error :missing-scenario-author
        :detail "v1.1 scenarios must include :scenario-author as a non-empty string"}

       (and (contains? (set (schema-profile/required-fields version)) :scenario-author)
            (str/blank? (:scenario-author scenario)))
       {:ok false :error :blank-scenario-author
        :detail ":scenario-author must not be blank"}

      ;; Purpose-based requirements (enriched schemas only; skippable via flags).
       (and strict?
            (contains? (set (schema-profile/required-fields version)) :purpose)
            (schema-profile/requires-theory? (:purpose scenario))
            (not (:theory scenario)))
       {:ok false :error :theory-required
        :detail "purpose :theory-falsification requires a :theory block"}

       (and strict?
            (contains? (set (schema-profile/required-fields version)) :purpose)
            (schema-profile/requires-theory-or-expectations? (:purpose scenario))
            (not (:theory scenario))
            (empty? (get-in scenario [:expectations :metrics]))
            (empty? (get-in scenario [:expectations :terminal]))
            (empty? (get-in scenario [:expectations :invariants])))
       {:ok false :error :adversarial-requires-analysis
        :detail "purpose :adversarial-robustness requires :theory or non-trivial :expectations"}

       (and strict?
            (:theory scenario) (not (get-in scenario [:theory :claim-id])))
       {:ok false :error :theory-missing-claim-id
        :detail ":theory must include a :claim-id"}

       (and strict?
            (:theory scenario) (nil? (get-in scenario [:theory :assumptions])))
       {:ok false :error :theory-missing-assumptions
        :detail ":theory must include an :assumptions vector (may be empty)"}

       (and strict?
            (:theory scenario)
            (contains? (set (schema-profile/required-fields version)) :purpose)
            (schema-profile/requires-metric-falsifies-if? (:purpose scenario))
            (not (seq (get-in scenario [:theory :falsifies-if])))
            (not (true? (get-in scenario [:theory :mechanism-only-negative-test?]))))
       {:ok false :error :theory-falsification-requires-falsifies-if
        :detail ":purpose :theory-falsification requires a non-empty :falsifies-if (metric disconfirmer), or :mechanism-only-negative-test? true with mechanism/equilibrium proxies"}

       (and strict?
            (:theory scenario)
            (not (seq (get-in scenario [:theory :falsifies-if])))
            (empty? (get-in scenario [:theory :mechanism-properties]))
            (empty? (get-in scenario [:theory :equilibrium-concept])))
       {:ok false :error :theory-missing-falsifies-if
        :detail ":theory must include a non-empty :falsifies-if vector, or declare :mechanism-properties / :equilibrium-concept"}

       (not (:ok agent-check))
       agent-check

       (empty? events)
       {:ok false :error :no-events}

       (not= (mapv :seq events) (vec (range (count events))))
       {:ok false :error :non-contiguous-event-seq :detail {:got (mapv :seq events)}}

       ;; Type-check event times BEFORE any ordering comparison, so a
       ;; non-numeric / mixed-type :time (e.g. a String or java.util.Date among
       ;; numbers/Instants) fails as a structured :invalid instead of throwing a
       ;; ClassCastException inside the >/< ordering checks below.
       (seq bad-times)
       {:ok false :error :invalid-event-time
        :detail {:hint "each event :time must be a number (Unix epoch seconds) or java.time.Instant"
                 :violations (mapv (fn [e] {:seq (:seq e) :time (:time e) :type (str (class (:time e)))}) bad-times)}}

       (some (fn [[a b]] (> (to-epoch-second (:time a)) (to-epoch-second (:time b)))) (partition 2 1 events))
       {:ok false :error :non-monotonic-event-time
        :detail {:violations (vec (filter (fn [[a b]] (> (to-epoch-second (:time a)) (to-epoch-second (:time b)))) (partition 2 1 events)))}}

       (some #(< (to-epoch-second (:time %)) init-time) events)
       {:ok false :error :event-time-before-initial
        :detail {:initial-block-time init-time
                 :violations (mapv :time (filter #(< (to-epoch-second (:time %)) init-time) events))}}

       (some #(not (contains? known-ids (:agent %))) events)
       {:ok false :error :unknown-agent-in-event
        :detail {:bad-refs (vec (filter #(not (contains? known-ids (:agent %))) events))}}

;; Population metrics in single-trace theory → hard error (silent inconclusive otherwise).
       (and (:theory scenario)
            (not= :population (metrics/theory-metric-scope scenario))
            (seq (let [refs (metrics/falsifies-if-metric-refs (get-in scenario [:theory :falsifies-if]))
                       bad  (vec (filter metrics/population-metrics refs))]
                   bad)))
       {:ok false :error :population-metric-in-trace-theory
        :detail {:metrics (vec (filter metrics/population-metrics
                                       (metrics/falsifies-if-metric-refs (get-in scenario [:theory :falsifies-if]))))
                 :metric-scope (metrics/theory-metric-scope scenario)
                 :hint "Single-trace replay cannot compute population metrics. Use trace metrics (e.g. :coalition-net-profit), or set :theory {:metric-scope :population} for multi-epoch scenarios."}}

      ;; Theory :falsifies-if metrics must be in the active trace metric registry
      ;; (population-scoped metrics are validated by the multi-epoch runner, not here).
       (let [scope          (metrics/theory-metric-scope scenario)
             theory-metrics (metrics/falsifies-if-metric-refs (get-in scenario [:theory :falsifies-if]))
             trace-metrics  (if (= scope :population)
                              (vec (remove metrics/population-metrics theory-metrics))
                              theory-metrics)
             unknown-theory (vec (remove effective-metrics trace-metrics))]
         (seq unknown-theory))
       {:ok false :error :unknown-theory-metric
        :detail {:unknown (vec (remove effective-metrics
                                       (metrics/falsifies-if-metric-refs (get-in scenario [:theory :falsifies-if]))))
                 :known   effective-metrics
                 :hint    "Declare the metric in the protocol metric-vocabulary or fix the name."}}

      ;; Expectations metrics — separate error code for clearer diagnostics.
       (let [exp-metrics (map #(metrics/metric-key (:name %)) (get-in scenario [:expectations :metrics] []))
             unknown-exp (vec (remove effective-metrics exp-metrics))]
         (seq unknown-exp))
       {:ok false :error :unknown-expectation-metric
        :detail {:unknown (vec (remove effective-metrics
                                       (map #(metrics/metric-key (:name %)) (get-in scenario [:expectations :metrics] []))))
                 :known effective-metrics}}

       :else {:ok true}))))
