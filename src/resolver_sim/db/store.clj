(ns resolver-sim.db.store
  "XTDB persistence for simulation outcomes (protocol-agnostic).

   Two generic tables (auto-created by XTDB on first INSERT):

     sim_trial_results — one row per simulation trial
       Columns: _id, batch_id, protocol_id, outcome, invariants_ok, divergence,
                params_edn, metrics_edn, violations_edn, _valid_from
       protocol_id discriminates between protocol implementations (e.g. \"<protocol-id>\").
       metrics_edn is a protocol-specific EDN blob of all per-trial metrics.

     sim_entity_events — one row per entity state-transition event within a trial
       Columns: _id, trial_id, entity_id, event_type, entity_state,
                block_time, _valid_from

   Valid-time semantics:
     _valid_from = simulated block timestamp (as java.util.Date).
     Queries with FOR VALID_TIME AS OF reproduce state at any point in the
     simulated chain timeline.

   Datasource:
     Use resolver-sim.db.xtdb/->datasource to obtain a connection to the
     XTDB pgwire endpoint.  Pass nil as the datasource to skip all writes
     (useful in tests and offline simulation runs)."
  (:require [clojure.string :as str]
            [next.jdbc              :as jdbc]
            [resolver-sim.db.xtdb   :as xtdb]
            [resolver-sim.logging   :as log]))

;; ---------------------------------------------------------------------------
;; Schema helpers
;; ---------------------------------------------------------------------------

(defn truncate!
  "Delete all simulation rows. Ignores errors for tables that have
   never been written to (XTDB raises an error on DELETE from a non-existent
   table)."
  [ds]
  (doseq [tbl ["sim_trial_results"
               "sim_entity_events"
               "sim_temporal_runs"
               "sim_temporal_steps"
               "sim_temporal_invariants"
               "sim_temporal_coverage"]]
    (try
      (jdbc/execute! ds [(str "DELETE FROM " tbl)])
      (catch Exception e
        (log/warn! :db-truncate-failed {:table tbl :error (.getMessage e)})))))

;; ---------------------------------------------------------------------------
;; sim_trial_results — writes
;; ---------------------------------------------------------------------------

(defn insert-trial-result!
  "Insert one generic trial result row into sim_trial_results.

   Required keys:
     :id          — unique trial id string (UUID recommended)
     :batch-id    — identifies the simulation batch (string or keyword)
     :protocol-id — stable protocol identifier (e.g. \"sew-v1\")
     :outcome     — terminal outcome keyword (e.g. :released, :refunded, :resolved)
     :valid-from  — java.util.Date corresponding to simulated block time

   Optional keys (all default to nil/false):
     :invariants-ok? — boolean (were all invariants satisfied?)
     :divergence?    — boolean (outcome diverged from idealised model)
     :params         — map (full trial params; stored as EDN)
     :metrics        — map (protocol-specific metrics blob; stored as EDN)
     :violations     — map (invariant violations; stored as EDN)

   No-op when ds is nil."
  [ds {:keys [id batch-id protocol-id outcome
              invariants-ok? divergence?
              params metrics violations
              valid-from]}]
  (when ds
    (jdbc/execute! ds
                   [(str "INSERT INTO sim_trial_results"
                         " (_id, batch_id, protocol_id, outcome,"
                         "  invariants_ok, divergence,"
                         "  params_edn, metrics_edn, violations_edn, _valid_from)"
                         " VALUES ("
                         (xtdb/sql-str id)                              ", "
                         (xtdb/sql-str (xtdb/kw->str batch-id))        ", "
                         (xtdb/sql-str protocol-id)                    ", "
                         (xtdb/sql-str (xtdb/kw->str outcome))         ", "
                         (xtdb/sql-bool invariants-ok?)                 ", "
                         (xtdb/sql-bool divergence?)                    ", "
                         (xtdb/sql-str (xtdb/->edn params))            ", "
                         (xtdb/sql-str (xtdb/->edn metrics))           ", "
                         (xtdb/sql-str (xtdb/->edn violations))        ", "
                         (xtdb/sql-ts valid-from)
                         ")")])))

;; ---------------------------------------------------------------------------
;; sim_entity_events — writes
;; ---------------------------------------------------------------------------

(defn insert-entity-event!
  "Insert one entity state-transition event row into sim_entity_events.

   Keys:
     :id           — unique event id string
     :trial-id     — parent trial id
     :entity-id    — entity identifier (any string; e.g. \"0\" for Sew workflow 0)
     :event-type   — keyword, e.g. :sew/escrow-created
     :entity-state — keyword representing current entity state
     :block-time   — long (simulated unix timestamp)
     :valid-from   — java.util.Date (same as block-time converted to Date)

   No-op when ds is nil."
  [ds {:keys [id trial-id entity-id event-type entity-state block-time valid-from]}]
  (when ds
    (jdbc/execute! ds
                   [(str "INSERT INTO sim_entity_events"
                         " (_id, trial_id, entity_id, event_type, entity_state,"
                         "  block_time, _valid_from)"
                         " VALUES ("
                         (xtdb/sql-str id)                              ", "
                         (xtdb/sql-str trial-id)                        ", "
                         (xtdb/sql-str (str entity-id))                 ", "
                         (xtdb/sql-str (xtdb/kw->str event-type))      ", "
                         (xtdb/sql-str (xtdb/kw->str entity-state))    ", "
                         (xtdb/sql-long block-time)                     ", "
                         (xtdb/sql-ts valid-from)
                         ")")])))

;; ---------------------------------------------------------------------------
;; sim_trial_results — reads (generic)
;; ---------------------------------------------------------------------------

(defn- row->trial-result [row]
  (cond-> {:result/id           (:_id row)
           :result/batch-id     (some-> (:batch_id row) keyword)
           :result/protocol-id  (:protocol_id row)
           :result/outcome      (some-> (:outcome row) keyword)
           :result/invariants-ok? (boolean (:invariants_ok row))
           :result/divergence?    (boolean (:divergence row))}
    (:params_edn row)     (assoc :result/params     (xtdb/parse-edn (:params_edn row)))
    (:metrics_edn row)    (assoc :result/metrics    (xtdb/parse-edn (:metrics_edn row)))
    (:violations_edn row) (assoc :result/violations (xtdb/parse-edn (:violations_edn row)))))

(defn trial-results
  "Return trial result rows, ordered by insertion.

   Options:
     :batch-id    — string/keyword filter
     :protocol-id — string filter (e.g. \"sew-v1\")
     :limit       — max rows (default unbounded)

   Returns a vector of :result/* namespaced maps.
   No-op (returns []) when ds is nil."
  ([ds] (trial-results ds {}))
  ([ds {:keys [batch-id protocol-id limit]}]
   (if (nil? ds)
     []
     (let [clauses (cond-> ["1=1"]
                     batch-id    (conj (str "batch_id = "    (xtdb/sql-str (xtdb/kw->str batch-id))))
                     protocol-id (conj (str "protocol_id = " (xtdb/sql-str protocol-id))))]
       (mapv row->trial-result
             (jdbc/execute!
              ds
              [(cond-> (str "SELECT * FROM sim_trial_results WHERE "
                            (str/join " AND " clauses))
                 limit (str " LIMIT " limit))]
              xtdb/opts))))))

(defn- inst->iso ^String [^java.util.Date d]
  (.format java.time.format.DateTimeFormatter/ISO_INSTANT
           (.toInstant d)))

(defn trial-results-at
  "Return trial results AS OF a specific valid time (bitemporal query).
   Returns a vector of :result/* namespaced maps, or [] when ds is nil."
  ([ds valid-at] (trial-results-at ds valid-at {}))
  ([ds valid-at {:keys [protocol-id]}]
   (if (nil? ds)
     []
     (let [base-sql (str "SELECT * FROM sim_trial_results "
                         "FOR VALID_TIME AS OF TIMESTAMP '"
                         (inst->iso valid-at) "'")
           sql      (if protocol-id
                      (str base-sql " WHERE protocol_id = '" protocol-id "'")
                      base-sql)]
       (mapv row->trial-result (jdbc/execute! ds [sql] xtdb/opts))))))

;; ---------------------------------------------------------------------------
;; sim_entity_events — reads (generic)
;; ---------------------------------------------------------------------------

(defn entity-events-for-trial
  "Return all entity state-transition events for a trial, ordered by block_time.
   Returns a vector of :event/* namespaced maps, or [] when ds is nil."
  [ds trial-id]
  (if (nil? ds)
    []
    (mapv (fn [row]
            {:event/id           (:_id row)
             :event/trial-id     (:trial_id row)
             :event/entity-id    (:entity_id row)
             :event/type         (some-> (:event_type row) keyword)
             :event/entity-state (some-> (:entity_state row) keyword)
             :event/block-time   (:block_time row)})
          (jdbc/execute! ds
                         ["SELECT * FROM sim_entity_events WHERE trial_id = ? ORDER BY block_time ASC"
                          trial-id]
                         xtdb/opts))))

(defn entity-events-for-trial-at
  "Return entity state-transition events for a trial AS OF a specific valid time
   (bitemporal query), ordered by block_time.

   Returns a vector of :event/* namespaced maps, or [] when ds is nil."
  [ds trial-id valid-at]
  (if (nil? ds)
    []
    (let [sql (str "SELECT * FROM sim_entity_events "
                   "FOR VALID_TIME AS OF TIMESTAMP '" (inst->iso valid-at) "' "
                   "WHERE trial_id = ? ORDER BY block_time ASC")]
      (mapv (fn [row]
              {:event/id           (:_id row)
               :event/trial-id     (:trial_id row)
               :event/entity-id    (:entity_id row)
               :event/type         (some-> (:event_type row) keyword)
               :event/entity-state (some-> (:entity_state row) keyword)
               :event/block-time   (:block_time row)})
            (jdbc/execute! ds [sql trial-id] xtdb/opts)))))

;; ---------------------------------------------------------------------------
;; Temporal evidence tables — writes
;; ---------------------------------------------------------------------------

(defn insert-temporal-run!
  "Insert one temporal run record.
   No-op when ds is nil."
  [ds {:keys [id batch-id protocol-id suite-id scenario-id seed git-sha
              outcome metrics valid-from]}]
  (when ds
    (jdbc/execute! ds
                   [(str "INSERT INTO sim_temporal_runs"
                         " (_id, batch_id, protocol_id, suite_id, scenario_id, seed, git_sha,"
                         "  outcome, metrics_edn, _valid_from)"
                         " VALUES ("
                         (xtdb/sql-str id) ", "
                         (xtdb/sql-str (xtdb/kw->str batch-id)) ", "
                         (xtdb/sql-str protocol-id) ", "
                         (xtdb/sql-str (xtdb/kw->str suite-id)) ", "
                         (xtdb/sql-str (xtdb/kw->str scenario-id)) ", "
                         (xtdb/sql-long seed) ", "
                         (xtdb/sql-str git-sha) ", "
                         (xtdb/sql-str (xtdb/kw->str outcome)) ", "
                         (xtdb/sql-str (xtdb/->edn metrics)) ", "
                         (xtdb/sql-ts valid-from)
                         ")")]
                   xtdb/opts)))

(defn insert-temporal-step!
  "Insert one temporal step record.
   No-op when ds is nil."
  [ds {:keys [id run-id step-index action result
              time-before time-advance time-after
              projection-hash valid-from]}]
  (when ds
    (jdbc/execute! ds
                   [(str "INSERT INTO sim_temporal_steps"
                         " (_id, run_id, step_index, action, result,"
                         "  time_before_edn, time_advance_edn, time_after_edn, projection_hash, _valid_from)"
                         " VALUES ("
                         (xtdb/sql-str id) ", "
                         (xtdb/sql-str run-id) ", "
                         (xtdb/sql-long step-index) ", "
                         (xtdb/sql-str (xtdb/kw->str action)) ", "
                         (xtdb/sql-str (xtdb/kw->str result)) ", "
                         (xtdb/sql-str (xtdb/->edn time-before)) ", "
                         (xtdb/sql-str (xtdb/->edn time-advance)) ", "
                         (xtdb/sql-str (xtdb/->edn time-after)) ", "
                         (xtdb/sql-str projection-hash) ", "
                         (xtdb/sql-ts valid-from)
                         ")")]
                   xtdb/opts)))

(defn insert-temporal-invariant!
  "Insert one temporal invariant evaluation record.
   No-op when ds is nil."
  [ds {:keys [id run-id step-index invariant holds? severity violations valid-from]}]
  (when ds
    (jdbc/execute! ds
                   [(str "INSERT INTO sim_temporal_invariants"
                         " (_id, run_id, step_index, invariant, holds, severity, violations_edn, _valid_from)"
                         " VALUES ("
                         (xtdb/sql-str id) ", "
                         (xtdb/sql-str run-id) ", "
                         (xtdb/sql-long step-index) ", "
                         (xtdb/sql-str (xtdb/kw->str invariant)) ", "
                         (xtdb/sql-bool holds?) ", "
                         (xtdb/sql-str (xtdb/kw->str severity)) ", "
                         (xtdb/sql-str (xtdb/->edn violations)) ", "
                         (xtdb/sql-ts valid-from)
                         ")")]
                   xtdb/opts)))

(defn insert-temporal-coverage!
  "Insert one temporal coverage summary record.
   No-op when ds is nil."
  [ds {:keys [id run-id coverage valid-from]}]
  (when ds
    (jdbc/execute! ds
                   [(str "INSERT INTO sim_temporal_coverage"
                         " (_id, run_id, coverage_edn, _valid_from)"
                         " VALUES ("
                         (xtdb/sql-str id) ", "
                         (xtdb/sql-str run-id) ", "
                         (xtdb/sql-str (xtdb/->edn coverage)) ", "
                         (xtdb/sql-ts valid-from)
                         ")")]
                   xtdb/opts)))

;; ---------------------------------------------------------------------------
;; Temporal evidence tables — reads
;; ---------------------------------------------------------------------------

(defn- decode-ts [value]
  (cond
    (nil? value) nil
    (instance? java.util.Date value) value
    :else (xtdb/parse-ts (str value))))

(defn- temporal-row [row]
  {:temporal/id         (:_id row)
   :temporal/run-id     (:run_id row)
   :temporal/valid-from (decode-ts (:_valid_from row))})

(defn- temporal-run-row [row]
  (merge (temporal-row row)
         {:temporal/batch-id    (some-> (:batch_id row) keyword)
          :temporal/protocol-id (:protocol_id row)
          :temporal/suite-id    (some-> (:suite_id row) keyword)
          :temporal/scenario-id (some-> (:scenario_id row) keyword)
          :temporal/seed        (:seed row)
          :temporal/git-sha     (:git_sha row)
          :temporal/outcome     (some-> (:outcome row) keyword)
          :temporal/metrics     (xtdb/parse-edn (:metrics_edn row))}))

(defn- temporal-step-row [row]
  (merge (temporal-row row)
         {:temporal/step-index     (:step_index row)
          :temporal/action         (some-> (:action row) keyword)
          :temporal/result         (some-> (:result row) keyword)
          :temporal/time-before    (xtdb/parse-edn (:time_before_edn row))
          :temporal/time-advance   (xtdb/parse-edn (:time_advance_edn row))
          :temporal/time-after     (xtdb/parse-edn (:time_after_edn row))
          :temporal/projection-hash (:projection_hash row)}))

(defn- temporal-invariant-row [row]
  (merge (temporal-row row)
         {:temporal/step-index (:step_index row)
          :temporal/invariant  (some-> (:invariant row) keyword)
          :temporal/holds?     (boolean (:holds row))
          :temporal/severity   (some-> (:severity row) keyword)
          :temporal/violations (xtdb/parse-edn (:violations_edn row))}))

(defn- temporal-coverage-row [row]
  (merge (temporal-row row)
         {:temporal/coverage (xtdb/parse-edn (:coverage_edn row))}))

(defn- temporal-query [ds table decoder order-by valid-at]
  (if (nil? ds)
    []
    (let [as-of (if valid-at
                  (str " FOR VALID_TIME AS OF TIMESTAMP '" (inst->iso valid-at) "'")
                  "")
          sql (str "SELECT * FROM " table as-of " ORDER BY " order-by " ASC")]
      (mapv decoder (jdbc/execute! ds [sql] xtdb/opts)))))

(defn temporal-runs
  "Return current temporal runs, ordered by valid time then id."
  [ds]
  (temporal-query ds "sim_temporal_runs" temporal-run-row "_valid_from, _id" nil))

(defn temporal-runs-at
  "Return temporal runs visible at valid-at, ordered by valid time then id."
  [ds valid-at]
  (temporal-query ds "sim_temporal_runs" temporal-run-row "_valid_from, _id" valid-at))

(defn temporal-steps-for-run
  "Return current steps for run-id, ordered by step index then id."
  [ds run-id]
  (if (nil? ds) []
      (let [rows (jdbc/execute! ds ["SELECT * FROM sim_temporal_steps WHERE run_id = ? ORDER BY step_index ASC, _id ASC" run-id] xtdb/opts)]
        (mapv temporal-step-row rows))))

(defn temporal-steps-for-run-at
  "Return steps for run-id visible at valid-at."
  [ds run-id valid-at]
  (if (nil? ds) []
      (let [sql (str "SELECT * FROM sim_temporal_steps FOR VALID_TIME AS OF TIMESTAMP '" (inst->iso valid-at) "' WHERE run_id = ? ORDER BY step_index ASC, _id ASC")]
        (mapv temporal-step-row (jdbc/execute! ds [sql run-id] xtdb/opts)))))

(defn temporal-invariants-for-run
  "Return current invariant evaluations for run-id, ordered by step index then id."
  [ds run-id]
  (if (nil? ds) []
      (mapv temporal-invariant-row
            (jdbc/execute! ds ["SELECT * FROM sim_temporal_invariants WHERE run_id = ? ORDER BY step_index ASC, _id ASC" run-id] xtdb/opts))))

(defn temporal-invariants-for-run-at
  "Return invariant evaluations for run-id visible at valid-at."
  [ds run-id valid-at]
  (if (nil? ds) []
      (let [sql (str "SELECT * FROM sim_temporal_invariants FOR VALID_TIME AS OF TIMESTAMP '" (inst->iso valid-at) "' WHERE run_id = ? ORDER BY step_index ASC, _id ASC")]
        (mapv temporal-invariant-row (jdbc/execute! ds [sql run-id] xtdb/opts)))))

(defn temporal-coverage-for-run
  "Return current coverage rows for run-id, ordered by valid time then id."
  [ds run-id]
  (if (nil? ds) []
      (mapv temporal-coverage-row
            (jdbc/execute! ds ["SELECT * FROM sim_temporal_coverage WHERE run_id = ? ORDER BY _valid_from ASC, _id ASC" run-id] xtdb/opts))))

(defn temporal-coverage-for-run-at
  "Return coverage rows for run-id visible at valid-at."
  [ds run-id valid-at]
  (if (nil? ds) []
      (let [sql (str "SELECT * FROM sim_temporal_coverage FOR VALID_TIME AS OF TIMESTAMP '" (inst->iso valid-at) "' WHERE run_id = ? ORDER BY _valid_from ASC, _id ASC")]
        (mapv temporal-coverage-row (jdbc/execute! ds [sql run-id] xtdb/opts)))))

(defn temporal-run-detail
  "Return a current run and its temporal evidence, or {} for nil ds/missing run."
  [ds run-id]
  (if-let [run (first (filter #(= run-id (:temporal/id %)) (temporal-runs ds)))]
    {:run run
     :steps (temporal-steps-for-run ds run-id)
     :invariants (temporal-invariants-for-run ds run-id)
     :coverage (temporal-coverage-for-run ds run-id)}
    {}))

(defn temporal-run-detail-at
  "Return a run and its evidence visible at valid-at, or {} when absent."
  [ds run-id valid-at]
  (if-let [run (first (filter #(= run-id (:temporal/id %)) (temporal-runs-at ds valid-at)))]
    {:run run
     :steps (temporal-steps-for-run-at ds run-id valid-at)
     :invariants (temporal-invariants-for-run-at ds run-id valid-at)
     :coverage (temporal-coverage-for-run-at ds run-id valid-at)}
    {}))

;; ---------------------------------------------------------------------------
;; Aggregate helpers (pure — no database required)
;; ---------------------------------------------------------------------------

(defn summarise-outcomes
  "Compute generic summary statistics over a vector of :trial/* outcome maps.

   Uses only protocol-agnostic keys present on every trial outcome record.

   Returns:
      {:n              — total trials
       :by-strategy    — {strategy {:n :divergent :invariant-failures}}
       :by-outcome     — {outcome count}}
   "
  [outcomes]
  (let [by-s (group-by :trial/strategy outcomes)
        by-o (group-by :trial/outcome outcomes)]
    {:n              (count outcomes)
     :by-strategy    (into {}
                           (map (fn [[s rows]]
                                  [s {:n                  (count rows)
                                      :divergent          (count (filter :trial/divergence? rows))
                                      :invariant-failures (count (remove :trial/invariants-ok? rows))}])
                                by-s))
     :by-outcome     (into {} (map (fn [[o rows]] [o (count rows)]) by-o))}))
