(ns resolver-sim.db.telemetry
  "Adapter between the contract-model runner and the eval-engine XTDB store.

   Converts run-trial / run-with-divergence-check output into the generic record
   format expected by resolver-sim.db.store and writes it to XTDB.

   Records are stored in the protocol-agnostic sim_trial_results table.
   Protocol-specific fields are serialised as a metrics_edn blob, populated via
   AnalysisModule/io-projection with the :telemetry-record target.

   All write functions accept a datasource as their first argument.
   Passing nil is safe: writes become no-ops, enabling offline simulation
   runs and unit tests without a live XTDB instance."
  (:require [resolver-sim.db.store               :as ss]
            [resolver-sim.protocols.protocol      :as proto])
  (:import [java.util Date UUID]))

;; ---------------------------------------------------------------------------
;; Conversion helpers (pure — no I/O)
;; ---------------------------------------------------------------------------

(defn- sim-date
  "Convert a simulated block-time long (unix seconds) to a java.util.Date.
   If block-time is nil, returns the current wall-clock time."
  [block-time]
  (if block-time
    (Date. (* ^long block-time 1000))
    (Date.)))

(defn trial->outcome-record
  "Convert a run-trial result (plus contextual metadata) into the generic map
   expected by resolver-sim.db.store/insert-trial-result!.

   Arguments:
     protocol   — a CoreProtocol instance (used for protocol-id)
     trial-id   — unique string identifier for this trial
     batch-id   — string or keyword identifying the simulation batch
     params     — the params map passed to run-trial
     result     — the map returned by run-trial or run-with-divergence-check

   The returned map has:
     Top-level generic fields: :id, :batch-id, :protocol-id, :outcome,
       :invariants-ok?, :divergence?, :params, :violations, :valid-from
     :metrics blob: protocol-specific fields from AnalysisModule/io-projection."
  [protocol trial-id batch-id params result]
  (let [cm    (if (contains? result :contract) (:contract result) result)
        div   (get result :divergence {})
        btime (get params :block-time 1000)]
    {:id             trial-id
     :batch-id       batch-id
     :protocol-id    (proto/protocol-id protocol)
     :outcome        (get cm :cm/final-state)
     :invariants-ok? (boolean (get cm :cm/invariants-ok? true))
     :divergence?    (boolean (get div :divergence?))
     :params         params
     :metrics        (when (satisfies? proto/AnalysisModule protocol)
                       (proto/io-projection protocol result :telemetry-record))
     :violations     (get cm :cm/inv-violations)
     :valid-from     (sim-date btime)}))

(defn trial->event-records
  "Derive a sequence of sim_entity_events records from a run-trial result.

   Delegates to (AnalysisModule/io-projection protocol data :event-records).
   Returns [] when the protocol does not support event record projection."
  [protocol trial-id params result]
  (if (satisfies? proto/AnalysisModule protocol)
    (or (proto/io-projection protocol {:trial-id trial-id :params params :result result} :event-records)
        [])
    []))

;; ---------------------------------------------------------------------------
;; Write functions (side-effecting — require XTDB datasource)
;; ---------------------------------------------------------------------------

(defn record-trial!
  "Write one trial outcome and its entity event sequence to XTDB.

   All writes are skipped when ds is nil.
   Returns the outcome record map (useful for chaining / inspection)."
  [ds protocol batch-id trial-id params result]
  (let [outcome (trial->outcome-record protocol trial-id batch-id params result)
        events  (trial->event-records  protocol trial-id params result)]
    (ss/insert-trial-result! ds outcome)
    (doseq [ev events]
      (ss/insert-entity-event! ds ev))
    outcome))

(defn record-batch!
  "Write a collection of trial results to XTDB.

   trials — sequence of maps, each with keys:
     :trial-id  — unique string (auto-generated if absent)
     :params    — the params map for this trial
     :result    — the map returned by run-trial or run-with-divergence-check

   Returns a vector of outcome record maps.
   All writes are skipped when ds is nil."
  [ds protocol batch-id trials]
  (mapv (fn [{:keys [trial-id params result]}]
          (let [tid (or trial-id (str (UUID/randomUUID)))]
            (record-trial! ds protocol batch-id tid params result)))
        trials))

;; ---------------------------------------------------------------------------
;; Query helpers (protocol-agnostic)
;; ---------------------------------------------------------------------------

(defn- results->trial-outcomes
  "Convert generic :result/* rows to the :trial/* shape expected by
   EconomicModel/summarise-batch."
  [results]
  (mapv (fn [r]
          (let [m (:result/metrics r)]
            (cond-> {:trial/id             (:result/id r)
                     :trial/batch-id       (:result/batch-id r)
                     :trial/outcome        (:result/outcome r)
                     :trial/invariants-ok? (:result/invariants-ok? r)
                     :trial/divergence?    (:result/divergence? r)
                     :trial/params         (:result/params r)
                     :trial/violations     (:result/violations r)}
              (map? m) (merge (into {}
                                    (for [[k v] m]
                                      [(keyword "trial" (name k)) v]))))))
        results))

(defn- add-temporal-metadata
  [summary {:keys [query-mode explicit-valid-time?]}]
  (assoc summary
         :temporal-confidence {:queries-using-explicit-valid-time (if explicit-valid-time? 1.0 0.0)
                               :temporal-consistency-status       :snapshot-consistent
                               :time-basis                        (if explicit-valid-time? :valid-time :mixed)}
         :temporal-query-mode query-mode))

(defn batch-summary
  "Return summary statistics for a stored batch.

   Fetches all trial outcomes for batch-id from XTDB and computes
   aggregate statistics using the protocol's EconomicModel/summarise-batch method.

   Returns {} when ds is nil or protocol does not implement EconomicModel."
  [ds protocol batch-id]
  (if (or (nil? ds) (not (satisfies? proto/EconomicModel protocol)))
    {}
    (let [results (ss/trial-results ds {:batch-id batch-id
                                        :protocol-id (proto/protocol-id protocol)})
          outcomes (results->trial-outcomes results)]
      (-> (proto/summarise-batch protocol outcomes)
          (add-temporal-metadata {:query-mode :latest
                                  :explicit-valid-time? false})))))

(defn batch-summary-at
  "Return summary statistics for a stored batch AS OF a valid-time.

   Like batch-summary, but uses bitemporal read semantics via
   resolver-sim.db.store/trial-results-at.

   Returns {} when ds is nil or protocol does not implement EconomicModel."
  [ds protocol batch-id valid-at]
  (if (or (nil? ds) (not (satisfies? proto/EconomicModel protocol)))
    {}
    (let [results (ss/trial-results-at ds valid-at {:protocol-id (proto/protocol-id protocol)})
          filtered (filterv #(= (keyword batch-id) (:result/batch-id %)) results)
          outcomes (results->trial-outcomes filtered)]
      (-> (proto/summarise-batch protocol outcomes)
          (add-temporal-metadata {:query-mode :as-of
                                  :explicit-valid-time? true})))))
