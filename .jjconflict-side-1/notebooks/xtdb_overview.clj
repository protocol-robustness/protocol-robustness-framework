^{:nextjournal.clerk/toc true
  :nextjournal.clerk/visibility {:code :fold :result :show}
  :nextjournal.clerk/dark-mode true}
(ns notebooks.xtdb-overview
  (:require [nextjournal.clerk :as clerk]
            [resolver-sim.notebook-support.ui :as ui]
            [resolver-sim.notebook-support.db :as ndb]))

(defn count* [ds table]
  (let [r (ndb/query-on ds [(str "SELECT COUNT(*) AS count FROM " table)])]
    (if (:ok? r)
      {:ok? true :n (or (:count (first (:rows r))) 0)}
      {:ok? false :n 0 :error (:error r)})))

(defn outcome-breakdown [ds]
  (ndb/query-on ds ["SELECT outcome, COUNT(*) AS count FROM sim_trial_results GROUP BY outcome ORDER BY count DESC"]))

(defn recent-batches [ds]
  (ndb/query-on ds ["SELECT batch_id, protocol_id, COUNT(*) AS trial_count, MAX(_valid_from) AS latest_valid_from FROM sim_trial_results GROUP BY batch_id, protocol_id ORDER BY latest_valid_from DESC LIMIT 20"]))

(defn recent-runs [ds]
  (ndb/query-on ds ["SELECT _id, scenario_id, suite_id, protocol_id, outcome, seed, git_sha, _valid_from FROM sim_temporal_runs ORDER BY _valid_from DESC LIMIT 20"]))

^{:nextjournal.clerk/no-cache true}
(let [{:keys [ok? ds source error]} (ndb/ds-result)]
  (clerk/html
   [:div
    [:h1 "XTDB Facts Overview Workbench"]
    [:p {:style {:color "#475569" :fontSize "0.9em"}}
     "Front door for stored simulation facts. Use this page first, then drill into telemetry/invariant notebooks."]
    (ui/notebook-navigation "XTDB Overview")
    (ui/reference-validation-status)

    (if ok?
      (ui/callout :green [:div [:strong "XTDB connected"] [:code source]])
      (ui/callout :amber [:div [:strong "XTDB unavailable"] [:code (or error "unknown")]]))

    (when ok?
      (let [trial-c (count* ds "sim_trial_results")
            event-c (count* ds "sim_entity_events")
            run-c   (count* ds "sim_temporal_runs")
            step-c  (count* ds "sim_temporal_steps")
            inv-c   (count* ds "sim_temporal_invariants")
            cov-c   (count* ds "sim_temporal_coverage")
            out-r   (outcome-breakdown ds)
            bat-r   (recent-batches ds)
            run-r   (recent-runs ds)]
        [:div
         [:div {:style {:display "flex" :gap "10px" :flexWrap "wrap" :marginBottom "12px"}}
          (ui/metric-card {:label "Trials" :value (:n trial-c) :rag :green})
          (ui/metric-card {:label "Entity Events" :value (:n event-c) :rag :green})
          (ui/metric-card {:label "Temporal Runs" :value (:n run-c) :rag :green})
          (ui/metric-card {:label "Temporal Steps" :value (:n step-c) :rag :green})
          (ui/metric-card {:label "Invariant Rows" :value (:n inv-c) :rag :green})
          (ui/metric-card {:label "Coverage Rows" :value (:n cov-c) :rag :green})]

         [:h2 "Outcome Breakdown (trial facts)"]
         (if (:ok? out-r)
           [:table {:style {:borderCollapse "collapse" :fontSize "0.85em" :width "100%" :backgroundColor "#1e293b" :color "#f8fafc" :borderRadius "6px" :overflow "hidden"}}
            [:thead [:tr {:style {:backgroundColor "#0f172a"}}
                     [:th {:style {:padding "8px 12px" :textAlign "left" :color "#ffffff"}} "Outcome"]
                     [:th {:style {:padding "8px 12px" :textAlign "right" :color "#ffffff"}} "Count"]]]
            (into [:tbody]
                  (for [{:keys [outcome count]} (:rows out-r)]
                    [:tr {:style {:borderBottom "1px solid #334155"}}
                     [:td {:style {:padding "6px 12px" :color "#e2e8f0"}} (str outcome)]
                     [:td {:style {:padding "6px 12px" :textAlign "right" :fontFamily "monospace" :color "#e2e8f0"}} (str count)]]))]
           (ui/callout :amber [:div "Could not query outcome breakdown."]))

         [:h2 "Recent Batches"]
         (if (:ok? bat-r)
           [:table {:style {:borderCollapse "collapse" :fontSize "0.83em" :width "100%" :backgroundColor "#1e293b" :color "#f8fafc" :borderRadius "6px" :overflow "hidden"}}
            [:thead [:tr {:style {:backgroundColor "#0f172a"}}
                     [:th {:style {:padding "8px 12px" :textAlign "left" :color "#ffffff"}} "Batch ID"]
                     [:th {:style {:padding "8px 12px" :textAlign "left" :color "#ffffff"}} "Protocol"]
                     [:th {:style {:padding "8px 12px" :textAlign "right" :color "#ffffff"}} "Trials"]
                     [:th {:style {:padding "8px 12px" :textAlign "left" :color "#ffffff"}} "Latest Valid Time"]]]
            (into [:tbody]
                  (for [{:keys [batch_id protocol_id trial_count latest_valid_from]} (:rows bat-r)]
                    [:tr {:style {:borderBottom "1px solid #334155"}}
                     [:td {:style {:padding "6px 12px" :fontFamily "monospace" :color "#e2e8f0"}} (str batch_id)]
                     [:td {:style {:padding "6px 12px" :color "#e2e8f0"}} (str protocol_id)]
                     [:td {:style {:padding "6px 12px" :textAlign "right" :fontFamily "monospace" :color "#e2e8f0"}} (str trial_count)]
                     [:td {:style {:padding "6px 12px" :fontFamily "monospace" :color "#e2e8f0"}} (str latest_valid_from)]]))]
           (ui/callout :amber [:div "Could not query recent batches."]))

         [:h2 "Recent Temporal Runs"]
         (if (:ok? run-r)
           [:table {:style {:borderCollapse "collapse" :fontSize "0.82em" :width "100%" :backgroundColor "#1e293b" :color "#f8fafc" :borderRadius "6px" :overflow "hidden"}}
            [:thead [:tr {:style {:backgroundColor "#0f172a"}}
                     [:th {:style {:padding "8px 12px" :textAlign "left" :color "#ffffff"}} "Run ID"]
                     [:th {:style {:padding "8px 12px" :textAlign "left" :color "#ffffff"}} "Scenario"]
                     [:th {:style {:padding "8px 12px" :textAlign "left" :color "#ffffff"}} "Outcome"]
                     [:th {:style {:padding "8px 12px" :textAlign "right" :color "#ffffff"}} "Seed"]
                     [:th {:style {:padding "8px 12px" :textAlign "left" :color "#ffffff"}} "Git SHA"]]]
            (into [:tbody]
                  (for [{:keys [_id scenario_id outcome seed git_sha]} (:rows run-r)]
                    [:tr {:style {:borderBottom "1px solid #334155"}}
                     [:td {:style {:padding "6px 12px" :fontFamily "monospace" :color "#e2e8f0"}} (str _id)]
                     [:td {:style {:padding "6px 12px" :color "#e2e8f0"}} (str scenario_id)]
                     [:td {:style {:padding "6px 12px" :color "#e2e8f0"}} (str outcome)]
                     [:td {:style {:padding "6px 12px" :textAlign "right" :fontFamily "monospace" :color "#e2e8f0"}} (str seed)]
                     [:td {:style {:padding "6px 12px" :fontFamily "monospace" :color "#e2e8f0"}} (str git_sha)]]))]
           (ui/callout :amber [:div "Could not query temporal runs."]))

         (ui/callout
          :amber
          [:div
           [:strong "Next drilldown"]
           [:ul {:style {:margin "6px 0 0 18px"}}
            [:li [:a {:href "/notebooks/invariant_failures"} "Open Invariant Failure Triage"]]
            [:li [:a {:href "/notebooks/telemetry"} "Open Telemetry Trial Timeline"]]
            [:li [:a {:href "/notebooks/report"} "Open Report Notebook"]]]])]))]))
