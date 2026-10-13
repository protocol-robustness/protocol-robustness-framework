(ns resolver-sim.db.sew
  "Sew-specific persistence helpers — query wrappers that unpack the generic
   sim_trial_results / sim_entity_events tables into Sew-shaped :trial/* maps.

   These functions belong in db/* (XTDB shell layer) rather than in the generic
   resolver-sim.db.store because the generic store has no knowledge of the Sew
   metrics blob schema or the Sew entity lifecycle.

   Callers:
     resolver-sim.db.telemetry  — batch-summary uses sew-trial-outcomes
     integration tests          — telemetry_integration_test.clj"
  (:require [next.jdbc              :as jdbc]
            [resolver-sim.db.xtdb   :as xtdb]
            [resolver-sim.db.store  :as store]))

;; ---------------------------------------------------------------------------
;; Row mapping helpers
;; ---------------------------------------------------------------------------

(defn- row->sew-trial-outcome
  "Convert a sim_trial_results row into a :trial/* namespaced map.
   Unpacks the metrics_edn blob to restore individual Sew fields."
  [row]
  (let [metrics (some-> (:metrics_edn row) xtdb/parse-edn)]
    (cond-> {:trial/id                (:_id row)
             :trial/batch-id          (some-> (:batch_id row) keyword)
             :trial/outcome           (some-> (:outcome row) keyword)
             :trial/invariants-ok?    (boolean (:invariants_ok row))
             :trial/divergence?       (boolean (:divergence row))
             :trial/strategy          (some-> (:strategy metrics) keyword)
             :trial/dispute-correct?  (boolean (:dispute-correct? metrics))
             :trial/appeal-triggered? (boolean (:appeal-triggered? metrics))
             :trial/slashed?          (boolean (:slashed? metrics))
             :trial/profit-honest     (:profit-honest metrics)
             :trial/profit-malice     (:profit-malice metrics)
             :trial/cm-fee            (:cm-fee metrics)
             :trial/cm-afa            (:cm-afa metrics)}
      (:params_edn row)     (assoc :trial/params     (xtdb/parse-edn (:params_edn row)))
      (:violations_edn row) (assoc :trial/violations (xtdb/parse-edn (:violations_edn row)))
      metrics               (assoc :trial/diffs      (:diffs metrics)))))

;; ---------------------------------------------------------------------------
;; Query functions
;; ---------------------------------------------------------------------------

(defn sew-trial-outcomes
  "Query sim_trial_results for Sew trials (protocol_id = 'sew-v1').
   Returns :trial/* namespaced maps compatible with db.store/summarise-batch.

   Options:
     :batch-id  — string/keyword filter
     :strategy  — keyword filter (applied in Clojure after fetch)
     :limit     — max rows

   Returns [] when ds is nil."
  ([ds] (sew-trial-outcomes ds {}))
  ([ds {:keys [batch-id strategy limit]}]
   (if (nil? ds)
     []
     (let [clauses (cond-> ["protocol_id = 'sew-v1'"]
                     batch-id (conj (str "batch_id = '" (xtdb/kw->str batch-id) "'")))
           where   (clojure.string/join " AND " clauses)
           sql     (cond-> (str "SELECT * FROM sim_trial_results WHERE " where)
                     limit (str " LIMIT " limit))
           all     (mapv row->sew-trial-outcome
                         (jdbc/execute! ds [sql] xtdb/opts))]
       (if strategy
         (filterv #(= strategy (:trial/strategy %)) all)
         all)))))

(defn- inst->iso ^String [^java.util.Date d]
  (.format java.time.format.DateTimeFormatter/ISO_INSTANT
           (.toInstant d)))

(defn sew-trial-outcomes-at
  "Bitemporal query for Sew trial outcomes AS OF a valid-time.
   Returns :trial/* namespaced maps, or [] when ds is nil."
  [ds valid-at]
  (if (nil? ds)
    []
    (mapv row->sew-trial-outcome
          (jdbc/execute! ds
                         [(str "SELECT * FROM sim_trial_results "
                               "FOR VALID_TIME AS OF TIMESTAMP '"
                               (inst->iso valid-at)
                               "' WHERE protocol_id = 'sew-v1'")]
                         xtdb/opts))))

(defn sew-escrow-events-for-trial
  "Return entity events for a trial in Sew-shaped maps (remaps generic
   entity-id → workflow-id and entity-state → escrow-state).
   Returns [] when ds is nil."
  [ds trial-id]
  (mapv (fn [ev]
          {:event/id           (:event/id ev)
           :event/trial-id     (:event/trial-id ev)
           :event/workflow-id  (some-> (:event/entity-id ev) parse-long)
           :event/type         (:event/type ev)
           :event/escrow-state (:event/entity-state ev)
           :event/block-time   (:event/block-time ev)})
        (store/entity-events-for-trial ds trial-id)))

(defn sew-escrow-events-for-trial-at
  "Return entity events for a trial AS OF a specific valid-time in Sew-shaped
   maps (remaps generic entity-id → workflow-id and entity-state → escrow-state).

   Returns [] when ds is nil."
  [ds trial-id valid-at]
  (mapv (fn [ev]
          {:event/id           (:event/id ev)
           :event/trial-id     (:event/trial-id ev)
           :event/workflow-id  (some-> (:event/entity-id ev) parse-long)
           :event/type         (:event/type ev)
           :event/escrow-state (:event/entity-state ev)
           :event/block-time   (:event/block-time ev)})
        (store/entity-events-for-trial-at ds trial-id valid-at)))

;; ---------------------------------------------------------------------------
;; Aggregate helpers (Sew-specific — pure, no database required)
;; ---------------------------------------------------------------------------

(defn sew-summarise-batch
  "Compute Sew-specific summary statistics over a vector of :trial/* outcome maps.

   Extends the generic store/summarise-outcomes with Sew financial metrics
   (:trial/slashed?, :trial/profit-honest, :trial/profit-malice).

   Returns:
     {:n              — total trials
      :by-strategy    — {strategy {:n :slashed :divergent :invariant-failures}}
      :by-outcome     — {outcome count}
       :profit-honest  {:min :max :mean}
       :profit-malice  {:min :max :mean}}"
  [outcomes]
  (let [base  (store/summarise-outcomes outcomes)
        by-s  (group-by :trial/strategy outcomes)
        mean  (fn [xs k] (if (seq xs) (double (/ (reduce + (map k xs)) (count xs))) 0.0))
        min*  (fn [xs k] (when (seq xs) (apply min (map k xs))))
        max*  (fn [xs k] (when (seq xs) (apply max (map k xs))))]
    (-> base
        (update :by-strategy
                (fn [by-s-base]
                  (into {}
                        (map (fn [[s rows]]
                               [s (assoc (get by-s-base s {:n (count rows)})
                                         :slashed (count (filter :trial/slashed? rows)))])
                             by-s))))
        (assoc :profit-honest {:min  (min* outcomes :trial/profit-honest)
                               :max  (max* outcomes :trial/profit-honest)
                               :mean (mean outcomes :trial/profit-honest)}
               :profit-malice {:min  (min* outcomes :trial/profit-malice)
                               :max  (max* outcomes :trial/profit-malice)
                               :mean (mean outcomes :trial/profit-malice)}))))
