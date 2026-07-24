(ns resolver-sim.commands.scenario-extraction
  "Pure scenario-bundle extraction projections."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.nio.file Files StandardCopyOption]))

(defn normalize-replay [bundle]
  (if (= "bundle-root.v1" (:bundle/schema-version bundle))
    (let [results (get-in bundle [:overview :results])
          raw-results (:run/scenario-results bundle)
          _ (when (not= 1 (count results))
              (throw (ex-info "Structured scenario extraction requires exactly one scenario result" {:count (count results)})))
          _ (when (not= 1 (count raw-results))
              (throw (ex-info "Structured scenario extraction requires exactly one raw scenario result" {:count (count raw-results)})))
          overview (first results)
          raw (first raw-results)]
      (let [scenario-id (or (:scenario-id overview)
                            (:scenario-id raw)
                            (get-in raw [:world :params :scenario-id]))]
        (assoc bundle :scenario-id scenario-id :outcome (:outcome overview)
               :events-processed (count (:trace raw)) :source {:scenario-id scenario-id}
               :trace (:trace raw) :metrics (:metrics raw) :world (:world raw))))
    bundle))

(defn- action [event] (or (:action event) (:event-type event) "?"))
(defn- action-name [event] (let [value (action event)] (if (keyword? value) (name value) (str value))))
(defn- actor [event] (or (:caller event) (:actor event) (:agent event)))
(defn trace-summary [replay provenance]
  (let [trace (vec (:trace replay))]
    {"schema_version" "trace-summary.v1" "scenario_id" (:scenario-id replay)
     "scenario_title" (or (get-in replay [:source :description]) (get-in replay [:source :scenario-id]))
     "outcome" (or (:outcome replay) "unknown") "events_processed" (or (:events-processed replay) (count trace))
     "steps" (mapv (fn [index event] (let [seq (or (:seq event) index) action (action-name event)]
                                       {"seq" seq "time" (or (:time event) (:block-time event))
                                        "actor" (actor event) "action" action "result" (or (:result event) (:outcome event) "?")
                                        "evidence_refs" [(format "evidence/events/%03d-%s.json" seq action)]}))
                   (range) trace)
     "derived_from" provenance}))

(defn- metric [metrics name default] (get metrics (keyword name) default))
(defn- available-ratio [world]
  (some (fn [module-data]
          (some (fn [token-data] (get-in token-data [:shortfall :available-ratio]))
                (vals (or module-data {}))))
        (vals (or (:yield/risk world) {}))))

(defn metrics-summary [replay provenance]
  (let [metrics (:metrics replay {})]
    {"schema_version" "scenario-metrics.v1" "scenario_id" (get-in replay [:source :scenario-id])
     "outcome" (or (:outcome replay) "unknown") "events_processed" (or (:events-processed replay) 0)
     "metrics" {"escrow_unrealized" (metric metrics "escrow-unrealized" 0)
                "escrow_realized" (metric metrics "escrow-realized" 0)
                "invariant_violations" (metric metrics "invariant-violations" 0)
                "attack_attempts" (metric metrics "attack-attempts" 0)
                "claimable" (metric metrics "claimable" 0)
                "batch_conflicts" (metric metrics "batch-conflicts" 0)
                "available_ratio" (or (metric metrics "available-ratio" nil) (available-ratio (:world replay)))}
     "derived_from" provenance}))

(def ^:private sensitive-key? #"(?i)(api[_-]?key|password|secret|private[_-]?key|access[_-]?token|authorization)")
(def ^:private sensitive-value? #"(?i)(-----BEGIN (?:RSA |EC |OPENSSH |)?PRIVATE KEY-----|authorization:\s*bearer\s+)")

(defn- public-world [value]
  (cond
    (map? value) (into {} (keep (fn [[key item]]
                                  (when-not (re-find sensitive-key? (name key))
                                    [key (public-world item)])) value))
    (vector? value) (mapv public-world value)
    (seq? value) (mapv public-world value)
    (and (string? value) (re-find sensitive-value? value)) "[REDACTED]"
    :else value))

(defn world-final
  ([replay provenance profile] (world-final replay provenance profile (:world replay {})))
  ([replay provenance profile world]
   {"schema_version" "world-final.v1" "scenario_id" (get-in replay [:source :scenario-id])
    "outcome" (or (:outcome replay) "unknown") "events_processed" (or (:events-processed replay) 0)
    "sensitivity_profile" (name profile)
    "world" (if (= profile :public) (public-world world) world) "derived_from" provenance}))

(def ^:private schema-map
  {"mechanism-summary.v1" {"description" "High-level summary of simulation mechanism outcomes (escrow, disputes, slashing)."
                           "fields" {"scenario_id" "Unique identifier of the scenario." "outcome" "Final simulation outcome (pass/fail)."
                                     "escrow" "Aggregated stats on escrow lifecycles." "dispute" "Aggregated stats on dispute resolutions."
                                     "slashing" "Aggregated stats on slashing actions." "claimable" "Summary of claimable fund classifications."
                                     "temporal" "Temporal statistics (steps, consistency)."}}
   "scenario-metrics.v1" {"description" "Raw numeric metrics collected during simulation."
                          "fields" {"scenario_id" "Unique identifier of the scenario." "metrics" "Detailed numeric metrics."}}})

(defn mechanism-summary [replay provenance]
  (let [trace (:trace replay []) metrics (:metrics replay {})
        count-action (fn [target] (count (filter #(= target (action-name %)) trace)))]
    {"schema_version" "mechanism-summary.v1" "scenario_id" (get-in replay [:source :scenario-id])
     "outcome" (or (:outcome replay) "unknown")
     "escrow" {"created" (count-action "create_escrow") "released" (count-action "release_escrow")
               "disputed" (count-action "raise_dispute")
               "finalized" (+ (count-action "release_escrow") (count-action "release_claimable") (count-action "finalize_claimable"))}
     "dispute" {"raised" (count-action "raise_dispute") "resolved" (count-action "execute_resolution")
                "appealed" (count-action "escalate_dispute") "outcome" (:outcome replay)}
     "slashing" {"attempts" (count-action "propose_fraud_slash") "executed" (count-action "execute_fraud_slash")
                 "unmet_obligations" (metric metrics "unrealized" 0)}
     "claimable" {"total_claimable" (metric metrics "claimable" 0) "stale_claimables" 0}
     "temporal" {"steps" (count trace) "clock_mode" "discrete-step"
                 "consistent" (every? #(or (:time %) (:block-time %)) trace)}
     "derived_from" provenance}))

(defn extraction-schema-map [provenance]
  {"schema_version" "schema-map.v1" "map" schema-map "derived_from" provenance})

(defn- json-safe-value
  "Preserve exact persisted allocation witnesses when projecting to JSON.
   Clojure ratios are not JSON values; encode them explicitly rather than
   coercing to floating point or failing the canonical review run."
  [value]
  (cond
    (ratio? value) {:ratio/numerator (numerator value)
                    :ratio/denominator (denominator value)}
    (map? value) (into {} (map (fn [[k v]]
                                 [(if (or (string? k) (keyword? k)) k (pr-str k))
                                  (json-safe-value v)])) value)
    ;; Set iteration is not a JSON contract. Canonical textual ordering keeps
    ;; reviewer projections deterministic without changing the source witness.
    (set? value) (->> value (sort-by pr-str) (mapv json-safe-value))
    (vector? value) (mapv json-safe-value value)
    (seq? value) (mapv json-safe-value value)
    ;; The raw authoritative world can contain runtime-only leaves (for example
    ;; path objects). They are outside the allocation witness contract and must
    ;; not prevent a reviewer projection from being emitted.
    (or (nil? value) (string? value) (boolean? value) (number? value) (keyword? value)) value
    :else (str value)))

(defn partial-fill-decisions
  "Project replay-produced partial-fill decisions without recalculating them.
   This is conditional: scenarios with no decision artifacts do not receive it."
  [replay provenance]
  (let [decisions (->> (get-in replay [:world :yield/partial-fill-decisions] {})
                       vals
                       (sort-by :decision/id)
                       vec)
        project (fn [decision]
                  (let [requested (reduce + 0 (vals (:requested decision {})))
                        filled (reduce + 0 (vals (:filled decision {})))
                        deferred (reduce + 0 (vals (:deferred decision {})))
                        available (get-in decision [:evidence :available-liquidity] 0)]
                    {"decision_id" (:decision/id decision)
                     "decision_sha256" (:decision/hash decision)
                     "decision_source" (some-> (:decision/source decision) name)
                     "participants" (json-safe-value (:participants decision))
                     "allocation_scope" (some-> (:allocation/scope decision) name)
                     "allocation_ordering" (some-> (:allocation/ordering decision) name)
                     "rounding_tie_break" (some-> (:allocation/rounding-tie-break decision) name)
                     "allocation_domain" (:allocation/domain decision)
                     "module_id" (some-> (:module/id decision) name)
                     "token" (some-> (:token decision) name)
                     "settlement_mode" (some-> (:settlement-mode decision) name)
                     "policy" {"mode" (some-> (get-in decision [:policy :mode]) name)
                               "rounding_policy" (some-> (get-in decision [:policy :rounding-policy]) name)
                               "allocation_ordering" (some-> (get-in decision [:policy :allocation-ordering]) name)
                               "rounding_tie_break" (some-> (get-in decision [:policy :rounding-tie-break]) name)}
                     "available_liquidity" available
                     "total_requested" requested
                     "total_filled" filled
                     "total_deferred" deferred
                     "shortage" (max 0 (- requested available))
                     "allocation_rows" (json-safe-value (get-in decision [:evidence :allocation-rows] []))
                     "allocation_detail" (json-safe-value (get-in decision [:evidence :allocation-detail]))
                     "allocation_mechanism" (json-safe-value (get-in decision [:evidence :allocation-mechanism]))
                     "mechanism_evidence" (json-safe-value (get-in decision [:evidence :allocation-mechanism-evidence]))
                     "redistribution" (json-safe-value (get-in decision [:evidence :redistribution]))
                     "allocation_passes" (json-safe-value (get-in decision [:evidence :allocation-passes] []))
                     "unallocated_residual" (get-in decision [:evidence :unallocated-residual] 0)
                     "residual_reason" (some-> (get-in decision [:evidence :residual-reason]) name)
                     "conservation" {"holds" (and (= requested (+ filled deferred))
                                                  (<= filled available))
                                     "requested_equals_filled_plus_deferred" (= requested (+ filled deferred))
                                     "filled_not_above_available" (<= filled available)
                                     "residual" (- available filled)}}))]
    {"schema_version" "partial-fill-decisions.v1"
     "scenario_id" (get-in replay [:source :scenario-id])
     "decision_count" (count decisions)
     "decisions" (mapv project decisions)
     "derived_from" provenance}))

(defn partial-fill-decisions-markdown
  "Human-readable companion to `partial-fill-decisions.v1`. It renders the
   persisted projection and does not perform a second allocation calculation."
  [projection]
  (let [render-row (fn [row]
                     (let [ratio (get row :fill-ratio)]
                       (format "| %s | %s | %s | %s | %s/%s |\n"
                               (get row :key) (get row :owed) (get row :filled)
                               (get row :deferred) (get ratio :numerator)
                               (get ratio :denominator))))
        render-decision (fn [decision]
                          (str "\n## Decision `" (get decision "decision_id") "`\n\n"
                               "- **Source:** `" (get decision "decision_source") "`\n"
                               "- **Scope:** `" (get decision "allocation_scope") "`\n"
                               "- **Pool:** `" (get decision "module_id") "/" (get decision "token") "`\n"
                               "- **Policy:** `" (get-in decision ["policy" "mode"]) "`, rounding `"
                               (get-in decision ["policy" "rounding_policy"]) "`\n"
                               "- **Tie-break:** `" (or (get decision "rounding_tie_break")
                                                        (get-in decision ["policy" "rounding_tie_break"])) "`\n"
                               "- **Liquidity:** " (get decision "available_liquidity") "; requested "
                               (get decision "total_requested") "; filled " (get decision "total_filled")
                               "; deferred " (get decision "total_deferred") "; shortage " (get decision "shortage") ".\n"
                               "- **Conservation:** " (if (get-in decision ["conservation" "holds"]) "holds" "FAILED")
                               "; residual " (get-in decision ["conservation" "residual"]) ".\n\n"
                               "| Participant | Requested | Filled | Deferred | Exact fill ratio |\n"
                               "|---|---:|---:|---:|---:|\n"
                               (apply str (map render-row (get decision "allocation_rows" [])))))]
    (str "# Partial-Fill Allocation Summary\n\n"
         "This summary is derived from `partial-fill-decisions.json`; the JSON projection and its replay decision hash are authoritative.\n"
         (apply str (map render-decision (get projection "decisions" []))))))

(defn fraud-group-slash-allocation
  "Project executed incident-scoped group fraud slashes from the persisted final
   world. This is a presentation projection only: it never recalculates the
   proposal allocation or execution rows."
  [replay provenance]
  (let [slashes (->> (get-in replay [:world :pending-fraud-slashes] {})
                     vals
                     (filter #(= "fraud-group" (some-> (:slash/kind %) name)))
                     (sort-by :slash/id)
                     vec)
        project (fn [slash]
                  (let [proposal (or (:proposal-allocation slash) (:allocation slash) {})
                        rows (get-in slash [:allocation :allocations] [])
                        sum (fn [f] (reduce + 0 (map #(or (f %) 0) rows)))
                        allocated (reduce + 0 (map #(or (:paid %) 0) rows))
                        paid (sum :actual-paid)
                        stayed (reduce + 0 (map #(if (= "stayed" (some-> (:execution-status %) name))
                                                   (or (:paid %) 0) 0) rows))
                        unpaid (reduce + 0 (map #(if (= "unpaid" (some-> (:execution-status %) name))
                                                   (or (:paid %) 0) 0) rows))
                        allocation-unmet (max 0 (- (or (:amount slash) 0) allocated))
                        uncollected (- allocated paid)]
                    {"slash_id" (:slash/id slash)
                     "liable_group_id" (:liable-group/id slash)
                     "member_snapshot_hash" (:liable-group/member-snapshot-hash slash)
                     "member_ordering" (some-> (:liable-group/ordering slash) name)
                     "workflow_id" (:workflow-id slash)
                     "fraud_incident_ref" (:fraud-incident-ref slash)
                     "status" (some-> (:status slash) name)
                     "obligation" (:amount slash)
                     "member_snapshot" (:members slash)
                     "proposal_allocation" (:allocations proposal)
                     "execution_rows" rows
                     "appeals" (:appeals slash)
                     "totals" {"allocated" allocated "paid" paid "stayed" stayed
                               "unpaid" unpaid
                               "allocation_unmet" allocation-unmet
                               "uncollected" uncollected}
                     "reconciles" {"allocation_not_above_obligation" (<= allocated (:amount slash))
                                   "allocated_plus_allocation_unmet_equals_obligation"
                                   (= (:amount slash) (+ allocated allocation-unmet))
                                   "paid_plus_stayed_plus_unpaid_equals_allocated"
                                   (= allocated (+ paid stayed unpaid))
                                   "stayed_plus_unpaid_equals_uncollected"
                                   (= uncollected (+ stayed unpaid))}}))]
    {"schema_version" "fraud-group-slash-allocation.v1"
     "scenario_id" (get-in replay [:source :scenario-id])
     "slash_count" (count slashes)
     "slashes" (mapv project slashes)
     "derived_from" provenance}))

(defn fraud-group-slash-allocation-markdown [projection]
  (let [render-row (fn [row]
                     (format "| %s | %s | %s | %s | %s |\n"
                             (:id row) (:paid row) (:actual-paid row)
                             (some-> (:execution-status row) name)
                             (some-> (:appeal-status row) name)))
        render (fn [slash]
                 (str "\n## Fraud group slash `" (get slash "slash_id") "`\n\n"
                      "- **Incident-scoped liable group:** `" (get slash "liable_group_id") "`\n"
                      "- **Immutable member snapshot:** `" (get slash "member_snapshot_hash") "`\n"
                      "- **Ordering:** `" (get slash "member_ordering") "`\n"
                      "- **Workflow:** `" (get slash "workflow_id") "`; status `" (get slash "status") "`\n"
                      "- **Obligation:** " (get slash "obligation") "; allocated " (get-in slash ["totals" "allocated"])
                      "; collected " (get-in slash ["totals" "paid"])
                      "; stayed " (get-in slash ["totals" "stayed"])
                      "; uncollected " (get-in slash ["totals" "uncollected"])
                      "; allocation-unmet " (get-in slash ["totals" "allocation_unmet"]) ".\n\n"
                      "The proposal allocation is immutable. Stayed rows were upheld on appeal and were not redistributed.\n\n"
                      "| Member | Proposal debit | Collected debit | Execution | Appeal |\n|---|---:|---:|---|---|\n"
                      (apply str (map render-row (get slash "execution_rows" [])))))]
    (str "# Fraud-Group Pro-Rata Slash Summary\n\n"
         "This review projection is derived from the persisted executed slash record; its JSON companion is authoritative.\n"
         (apply str (map render (get projection "slashes" []))))))

(defn claimable-classification [replay run-id]
  (let [build-document (requiring-resolve 'resolver-sim.protocols.sew.claimable-classification/build-document)
        world (:world replay {})
        scenario-id (get-in replay [:source :scenario-id])]
    (build-document
     :worlds [world]
     :contexts [{:world world :scenario-id scenario-id :outcome (:outcome replay)}]
     :scope (str "scenario/" scenario-id)
     :scenarios-passed (if (= :pass (:outcome replay)) 1 0)
     :observations-status "single-scenario"
     :aggregation "single-terminal-world"
     :provenance {:run_id run-id :producer "scenario-extraction.clj"})))

(defn plain-trace [replay]
  (str "# Plain Language Trace Summary\n\n"
       (str/join "\n" (map-indexed (fn [i event] (str (inc i) ". **" (action event) "**")) (:trace replay)))))

(defn- atomic-write! [file content]
  (let [target (io/file file) temp (io/file (str (.getPath target) ".tmp"))]
    (.mkdirs (.getParentFile target))
    (spit temp content)
    (Files/move (.toPath temp) (.toPath target)
                (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING StandardCopyOption/ATOMIC_MOVE]))))

(defn write-basic-projections!
  ([scenario-root replay provenance profile run-id]
   (write-basic-projections! scenario-root replay provenance profile run-id (:world replay {})))
  ([scenario-root replay provenance profile run-id raw-world]
   (let [root (io/file (str scenario-root))
         classification (claimable-classification replay run-id)
         write-json (fn [relative value]
                      (atomic-write! (io/file root relative)
                                     (json/write-str (json-safe-value value))))]
     (write-json "summaries/trace-summary.json" (trace-summary replay provenance))
     (write-json "summaries/metrics.json" (metrics-summary replay provenance))
     (write-json "summaries/claimable-classification.json" classification)
     (write-json "summaries/mechanism-summary.json" (mechanism-summary replay provenance))
     (write-json "summaries/schema-map.json" (extraction-schema-map provenance))
     (let [partial-fill (partial-fill-decisions replay provenance)
           partial-fill-path "summaries/partial-fill-decisions.json"]
       (when (pos? (get partial-fill "decision_count" 0))
         (write-json partial-fill-path partial-fill)
         (atomic-write! (io/file root "summaries/partial-fill-decisions.md")
                        (partial-fill-decisions-markdown partial-fill)))
       (let [fraud-group (fraud-group-slash-allocation replay provenance)
             fraud-group-path "summaries/fraud-group-slash-allocation.json"]
         (when (pos? (get fraud-group "slash_count" 0))
           (write-json fraud-group-path fraud-group)
           (atomic-write! (io/file root "summaries/fraud-group-slash-allocation.md")
                          (fraud-group-slash-allocation-markdown fraud-group))))
       (write-json "state/world-final.json" (world-final replay provenance profile raw-world))
       (atomic-write! (io/file root "summaries/trace-plain.md") (plain-trace replay))
       {:classification classification
        :written (cond-> ["summaries/trace-summary.json" "summaries/metrics.json"
                          "summaries/claimable-classification.json" "summaries/mechanism-summary.json"
                          "summaries/schema-map.json" "state/world-final.json" "summaries/trace-plain.md"]
                   (pos? (get partial-fill "decision_count" 0)) (conj partial-fill-path)
                   (pos? (get partial-fill "decision_count" 0)) (conj "summaries/partial-fill-decisions.md"))}))))

(defn extract!
  "Write scenario projections. The two-argument canonical form consumes the
   transient execution result returned by run-and-report, so raw worlds/traces
   need not be embedded in the immutable replay bundle root. The one-argument
   form remains for legacy persisted replay inputs."
  ([context] (extract! context nil))
  ([context execution]
   (let [replay-file (io/file (str (:replay/file context)))
         source (when-not execution (slurp replay-file))
         raw (when source (json/read-str source))
         bundle (if execution
                  (assoc (:bundle-root execution)
                         :run/scenario-results (get-in execution [:run-result :results]))
                  (json/read-str source :key-fn keyword))
         replay (normalize-replay bundle)
         raw-world (if execution
                     (get-in execution [:run-result :results 0 :world] {})
                     (if (= "bundle-root.v1" (get raw "bundle/schema-version"))
                       (get-in raw ["run/scenario-results" 0 "world"] {})
                       (get raw "world" {})))
         provenance {"path" (str (:replay/file context))}
         result (write-basic-projections! (:scenario/root context) replay provenance
                                          (:sensitivity/profile context) (:run/id context) raw-world)]
     result)))
