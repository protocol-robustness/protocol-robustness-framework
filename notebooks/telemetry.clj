;; Settings: default-code-visibility = :hide (hidden) or :show (visible)
^{:nextjournal.clerk/visibility {:code :hide :result :show}
  :nextjournal.clerk/dark-mode true}
(ns notebooks.telemetry
  (:require [nextjournal.clerk :as clerk]
            [clojure.set :as set]
            [clojure.string :as str]
            [resolver-sim.notebook-support.ui :as ui]))

;; Deterministic synchronized workbench state.
^{:nextjournal.clerk/sync true}
(defonce !ui-state (atom (assoc ui/default-ui-state :valid-time "")))

(defn render-ui-controls [state-atom]
  [:div {:style {:display "flex" :gap "10px" :marginBottom "10px"}}
   [:div
    [:label {:style {:display "block" :fontSize "0.8em"}} "Valid Time (ISO)"]
    [:input {:type "text"
             :placeholder "YYYY-MM-DDTHH:MM:SSZ"
             :value (:valid-time @state-atom)
             :onChange #(swap! state-atom assoc :valid-time (.. % -target -value))}]]])

(defn ds-result []
  (try
    (require '[resolver-sim.db.xtdb])
    {:ok? true
     :ds ((resolve 'resolver-sim.db.xtdb/->datasource))
     :source "resolver-sim.db.xtdb/->datasource"}
    (catch Throwable e
      {:ok? false
       :error (or (.getMessage e) (str e))
       :source "resolver-sim.db.xtdb/->datasource"})))

(defn query-result [sqlvec]
  (let [{:keys [ok? ds] :as dsr} (ds-result)]
    (if-not ok?
      (merge dsr {:rows [] :sql sqlvec})
      (try
        (require '[next.jdbc])
        ;; Using next.jdbc/execute! directly.
        ;; For queries, we need to handle valid-time.
        {:ok? true :rows ((resolve 'next.jdbc/execute!) ds sqlvec) :sql sqlvec}
        (catch Throwable e
          {:ok? false :rows [] :error (or (.getMessage e) (str e)) :sql sqlvec})))))

(defn get-bitemporal-sql [base-sql valid-at]
  (if (str/blank? valid-at)
    base-sql
    (str base-sql " FOR VALID_TIME AS OF TIMESTAMP '" valid-at "'")))

(defn to-uuid [x]
  (try
    (when (and x (not (str/blank? (str x))))
      (java.util.UUID/fromString (str x)))
    (catch Exception _ nil)))

(defn render-query-error [{:keys [kind error sql]}]
  (ui/query-error-callout "Telemetry query error" {:kind kind :error error :sql sql}))

(def ^:private telemetry-contract
  {:datasource "resolver-sim.db.xtdb/->datasource"
   :schemas {:test-run "test-run.v1"
             :test-artifacts "test-artifacts.v1.2"
             :trace-end-projection "trace-end-projection.v1"
             :claimable-classification "claimable-classification.v2"}})

(def ^:private required-trial-columns
  #{"_id" "batch_id" "protocol_id" "outcome" "invariants_ok" "divergence"})

(def ^:private required-event-columns
  #{"trial_id" "block_time" "entity_id" "event_type" "entity_state"})

(defn table-columns-result [table-name]
  (query-result [(str "SELECT column_name FROM information_schema.columns "
                      "WHERE table_name = ?")
                 table-name]))

(defn missing-required-columns [rows required-cols]
  (let [present (into #{} (map (comp str :column_name) rows))]
    (sort (set/difference required-cols present))))

(defn trial-row->view [r]
  {:trial-id (str (:_id r))
   :batch-id (str (:batch_id r))
   :protocol-id (str (:protocol_id r))
   :outcome (str (:outcome r))
   :invariants-ok (true? (:invariants_ok r))
   :divergence (true? (:divergence r))})

(defn event-row->view [e]
  {:block-time (:block_time e)
   :entity-id (:entity_id e)
   :event-type (:event_type e)
   :entity-state (:entity_state e)})

(defn apply-filters [rows {:keys [outcome invariants]}]
  (cond-> rows
    (not (str/blank? (or outcome "")))
    (->> (filter #(= (str (:outcome %)) outcome)))
    (= invariants "pass")
    (->> (filter #(true? (:invariants_ok %))))
    (= invariants "fail")
    (->> (filter #(false? (:invariants_ok %))))))

^{:nextjournal.clerk/no-cache true}
(let [ui-state @!ui-state
      {:keys [filters pagination selected-trial-id valid-time]} ui-state
      limit (-> (get pagination :limit 100) (max 1) (min 1000))
      dsr (ds-result)
      trial-cols-r (table-columns-result "sim_trial_results")
      event-cols-r (table-columns-result "sim_entity_events")
      trial-cols-missing (when (:ok? trial-cols-r)
                           (missing-required-columns (:rows trial-cols-r) required-trial-columns))
      event-cols-missing (when (:ok? event-cols-r)
                           (missing-required-columns (:rows event-cols-r) required-event-columns))
      preflight-ok?
      (and (:ok? trial-cols-r)
           (:ok? event-cols-r)
           (empty? trial-cols-missing)
           (empty? event-cols-missing))
      trials-r (query-result [(get-bitemporal-sql "SELECT COUNT(*) AS count FROM sim_trial_results" valid-time)])
      events-r (query-result [(get-bitemporal-sql "SELECT COUNT(*) AS count FROM sim_entity_events" valid-time)])
      protocols-r (query-result [(get-bitemporal-sql "SELECT COUNT(DISTINCT protocol_id) AS count FROM sim_trial_results" valid-time)])
      qres (query-result [(get-bitemporal-sql "SELECT _id, batch_id, protocol_id, outcome, invariants_ok, divergence FROM sim_trial_results ORDER BY _id DESC" valid-time)
                          (str " LIMIT " limit)])
      base-rows (:rows qres)
      filtered-rows (apply-filters base-rows filters)
      table-rows (mapv trial-row->view filtered-rows)
      selected-id (or selected-trial-id (:trial-id (first table-rows)))
      tid (to-uuid selected-id)
      t-res (if tid (query-result [(get-bitemporal-sql "SELECT * FROM sim_trial_results WHERE _id = ?" valid-time) tid]) {:ok? true :rows []})
      e-res (if tid (query-result [(get-bitemporal-sql "SELECT * FROM sim_entity_events WHERE trial_id = ?" valid-time) tid]) {:ok? true :rows []})
      trial (first (:rows t-res))
      events (mapv event-row->view (:rows e-res))]
  (clerk/html
   [:div
    [:h1 "Simulation Telemetry Workbench"]
    (render-ui-controls !ui-state)
    (ui/notebook-navigation "Telemetry Trial Timeline")

    [:div {:style {:marginBottom "10px" :padding "10px" :background "#f8fafc" :border "1px solid #cbd5e1" :borderRadius "4px"}}
     [:div [:strong "Telemetry grounding contract"]]
     [:div {:style {:fontSize "0.84em" :marginTop "4px"}}
      [:span [:strong "Datasource: "] [:code (:datasource telemetry-contract)]]]
     [:div {:style {:fontSize "0.84em" :marginTop "4px"}}
      [:span [:strong "Expected schemas: "]
       (->> (:schemas telemetry-contract)
            (map (fn [[k v]] (str (name k) "=" v)))
            (str/join ", "))]]]

    (if (:ok? dsr)
      (ui/callout :green [:div [:strong "Database connection available"] [:code (:source dsr)]])
      (ui/callout :amber [:div [:strong "Database unavailable"] [:code (:error dsr)]]))

    (when-not (:ok? trial-cols-r)
      (render-query-error trial-cols-r))
    (when-not (:ok? event-cols-r)
      (render-query-error event-cols-r))

    (when (and (:ok? trial-cols-r) (seq trial-cols-missing))
      (ui/callout :red
                  [:div
                   [:strong "Schema drift: sim_trial_results missing required columns"]
                   [:code (str/join ", " trial-cols-missing)]]))
    (when (and (:ok? event-cols-r) (seq event-cols-missing))
      (ui/callout :red
                  [:div
                   [:strong "Schema drift: sim_entity_events missing required columns"]
                   [:code (str/join ", " event-cols-missing)]]))

    [:div {:style {:display "flex" :gap "10px" :flexWrap "wrap" :marginBottom "12px"}}
     (ui/metric-card {:label "Trials" :value (if (:ok? trials-r) (or (:count (first (:rows trials-r))) 0) "—") :rag (if (:ok? trials-r) :green :red)})
     (ui/metric-card {:label "Events" :value (if (:ok? events-r) (or (:count (first (:rows events-r))) 0) "—") :rag (if (:ok? events-r) :green :red)})
     (ui/metric-card {:label "Protocols" :value (if (:ok? protocols-r) (or (:count (first (:rows protocols-r))) 0) "—") :rag (if (:ok? protocols-r) :green :red)})]

    (ui/filter-controls !ui-state)
    (ui/trial-selection-controls !ui-state)

    (if-not preflight-ok?
      (ui/callout :red
                  [:div
                   [:strong "Telemetry preflight failed"]
                   [:div {:style {:fontSize "0.86em" :marginTop "6px"}}
                    "Notebook rendering is intentionally blocked to prevent ungrounded telemetry interpretation. Resolve schema drift or datasource errors shown above."]])
      [:div
       [:h2 "Trial Results"]
       [:p {:style {:fontSize "0.84em" :color "#475569" :marginTop "-6px"}}
        "You can select a trial in two ways: "
        "(1) click Trial ID in the table, or (2) paste full trial UUID in the Selected Trial input above."]
       (if-not (:ok? qres)
         (render-query-error qres)
         (if (seq table-rows)
           (ui/select-trial-view !ui-state table-rows)
           (ui/callout :amber [:div "No rows matched current filters."])))

       [:h2 "Event Trace Viewer"]
       (cond
         (nil? selected-id)
         (ui/callout :amber
                     [:div
                      [:div "No trial selected yet."]
                      [:ol {:style {:margin "8px 0 0 18px" :fontSize "0.86em"}}
                       [:li "Paste a full UUID in Selected Trial input, or click a Trial ID row."]
                       [:li "The Event Trace Viewer will refresh automatically."]]])

         (nil? tid)
         (ui/callout :red [:div "Selected trial id is not a valid UUID string."])

         (not (:ok? t-res))
         (render-query-error t-res)

         (not (:ok? e-res))
         (render-query-error e-res)

         (nil? trial)
         (ui/callout :amber [:div "No trial found for selected trial id."])

         :else
         [:div
          [:div {:style {:backgroundColor "#f8fafc" :border "1px solid #cbd5e1" :borderRadius "4px"
                         :padding "10px" :marginBottom "10px" :fontSize "0.84em"}}
           [:div [:strong "Trial ID: "] [:code (str (:_id trial))]]
           [:div [:strong "Protocol: "] (str (:protocol_id trial))]
           [:div [:strong "Outcome: "] (str (:outcome trial))]
           [:div [:strong "invariants_ok: "] (if (:invariants_ok trial) "true" "false")]
           [:div [:strong "divergence: "] (if (:divergence trial) "true" "false")]
           [:div [:strong "Events: "] (count events)]]
           (ui/event-timeline-view events)])])]))
