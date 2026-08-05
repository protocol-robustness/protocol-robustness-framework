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
