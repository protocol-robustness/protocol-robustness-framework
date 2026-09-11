^{:nextjournal.clerk/toc true
  :nextjournal.clerk/visibility {:code :fold :result :show}
  :nextjournal.clerk/dark-mode true}
(ns notebooks.invariant-failures
  (:require [nextjournal.clerk :as clerk]
            [clojure.string :as str]
            [resolver-sim.notebook-support.ui :as ui]
            [resolver-sim.notebook-support.db :as ndb]))

^{:nextjournal.clerk/sync true}
(defonce !ui-state
  (atom {:selected-invariant ""
         :selected-run-id ""
         :selected-step-index nil
         :filters {:severity ""}
         :pagination {:limit 100}}))

(defn exec! [ds sqlvec]
  (ndb/query-on ds sqlvec))

(defn render-table [headers rows]
  [:table {:style {:borderCollapse "collapse" :fontSize "0.83em" :width "100%"}}
   [:thead
    (into [:tr {:style {:backgroundColor "#f1f5f9"}}]
          (for [h headers]
            [:th {:style {:padding "6px 10px" :textAlign "left"}} h]))]
   (into [:tbody] rows)])

(defn as-int [x]
  (try (Long/parseLong (str x)) (catch Exception _ nil)))

(defn invariant-summary [ds severity]
  (let [base "SELECT invariant, severity, COUNT(*) AS failures, COUNT(DISTINCT run_id) AS runs FROM sim_temporal_invariants WHERE holds = FALSE"
        [sql params] (if (str/blank? severity)
                       [(str base " GROUP BY invariant, severity ORDER BY failures DESC LIMIT 200") []]
                       [(str base " AND severity = ? GROUP BY invariant, severity ORDER BY failures DESC LIMIT 200") [severity]])]
    (exec! ds (into [sql] params))))

(defn failing-rows [ds {:keys [severity invariant run-id limit]}]
  (let [preds  (cond-> []
                 (not (str/blank? severity)) (conj ["severity = ?" severity])
                 (not (str/blank? invariant)) (conj ["invariant = ?" invariant])
                 (not (str/blank? run-id)) (conj ["run_id = ?" run-id]))
        where  (if (seq preds)
                 (str "holds = FALSE AND " (str/join " AND " (map first preds)))
                 "holds = FALSE")
        params (mapv second preds)
        sql    (str "SELECT _id, run_id, step_index, invariant, severity, violations_edn, _valid_from "
                    "FROM sim_temporal_invariants WHERE " where
                    " ORDER BY _valid_from DESC LIMIT " (max 1 (min 500 (long limit))))]
    (exec! ds (into [sql] params))))

(defn run-context [ds run-id]
  (if (str/blank? run-id)
    {:ok? true :rows []}
    (exec! ds ["SELECT _id, scenario_id, suite_id, protocol_id, outcome, seed, git_sha, _valid_from FROM sim_temporal_runs WHERE _id = ?" run-id])))

(defn step-context [ds run-id step-index]
  (if (or (str/blank? run-id) (nil? step-index))
    {:ok? true :rows []}
    (exec! ds ["SELECT _id, step_index, action, result, projection_hash, time_before_edn, time_advance_edn, time_after_edn FROM sim_temporal_steps WHERE run_id = ? AND step_index = ?" run-id step-index])))

^{:nextjournal.clerk/no-cache true}
(let [{:keys [ok? ds error]} (ndb/ds-result)
      {:keys [selected-invariant selected-run-id selected-step-index filters pagination]} @!ui-state
      severity (or (:severity filters) "")
      limit (or (:limit pagination) 100)
      summary-r (when ok? (invariant-summary ds severity))
      failing-r (when ok? (failing-rows ds {:severity severity
                                            :invariant selected-invariant
                                            :run-id selected-run-id
                                            :limit limit}))
      run-r (when ok? (run-context ds selected-run-id))
      step-r (when ok? (step-context ds selected-run-id selected-step-index))]
  (clerk/html
   [:div
    [:h1 "Invariant Failure Triage Workbench"]
    [:p {:style {:fontSize "0.9em" :color "#475569"}}
     "Phase 2: synchronized selectors + drilldown for invariant/run/step context."]

    (ui/notebook-navigation "Invariant Failure Triage")

    (if ok?
      (ui/callout :green [:div "XTDB connected"])
      (ui/callout :amber [:div [:strong "XTDB unavailable"] " " [:code (or error "unknown")]]))

    [:div {:style {:display "grid" :gridTemplateColumns "1fr 1fr 140px" :gap "8px" :marginBottom "12px"}}
     [:input {:type "text"
              :placeholder "Severity filter (e.g. time/info/error)"
              :value severity
              :on-change #(swap! !ui-state assoc-in [:filters :severity] (.. % -target -value))}]
     [:input {:type "text"
              :placeholder "Selected run id"
              :value selected-run-id
              :on-change #(swap! !ui-state assoc :selected-run-id (.. % -target -value))}]
     [:input {:type "number" :min "1" :max "500"
              :value limit
              :on-change #(let [v (or (as-int (.. % -target -value)) 100)]
                            (swap! !ui-state assoc-in [:pagination :limit] (-> v (max 1) (min 500))))}]]

    [:div {:style {:fontSize "0.82em" :color "#64748b" :marginBottom "10px"}}
     (str "selected-invariant=" (pr-str selected-invariant)
          " | selected-run-id=" (pr-str selected-run-id)
          " | selected-step-index=" (pr-str selected-step-index))]

    [:h2 "Top failing invariants"]
    (if (and ok? (:ok? summary-r))
      (render-table
       ["Invariant" "Severity" "Failures" "Runs"]
       (for [{:keys [invariant severity failures runs]} (:rows summary-r)]
         [:tr {:style {:borderBottom "1px solid #e2e8f0"}}
          [:td {:style {:padding "5px 10px" :cursor "pointer" :color "#1d4ed8"}
                :title "Click to select invariant"
                :on-click #(swap! !ui-state assoc :selected-invariant (str invariant))}
           (str invariant)]
          [:td {:style {:padding "5px 10px"}} (str severity)]
          [:td {:style {:padding "5px 10px" :fontFamily "monospace"}} (str failures)]
          [:td {:style {:padding "5px 10px" :fontFamily "monospace"}} (str runs)]]))
      (ui/callout :amber [:div "Could not query invariant summary."]))

    [:h2 "Failing rows"]
    (if (and ok? (:ok? failing-r))
      (if (seq (:rows failing-r))
        (render-table
         ["Run ID" "Step" "Invariant" "Severity" "Valid Time"]
         (for [{:keys [run_id step_index invariant severity _valid_from]} (:rows failing-r)]
           [:tr {:style {:borderBottom "1px solid #e2e8f0"}}
            [:td {:style {:padding "5px 10px" :fontFamily "monospace" :cursor "pointer" :color "#1d4ed8"}
                  :title "Click to select run"
                  :on-click #(swap! !ui-state assoc :selected-run-id (str run_id))}
             (str run_id)]
            [:td {:style {:padding "5px 10px" :fontFamily "monospace" :cursor "pointer" :color "#1d4ed8"}
                  :title "Click to select step"
                  :on-click #(swap! !ui-state assoc :selected-step-index step_index)}
             (str step_index)]
            [:td {:style {:padding "5px 10px"}} (str invariant)]
            [:td {:style {:padding "5px 10px"}} (str severity)]
            [:td {:style {:padding "5px 10px" :fontFamily "monospace"}} (str _valid_from)]]))
        (ui/callout :amber [:div "No failing rows for current filters."]))
      (ui/callout :amber [:div "Could not query failing rows."]))

    [:h2 "Selected run context"]
    (if (and ok? (:ok? run-r) (seq (:rows run-r)))
      (let [{:keys [_id scenario_id suite_id protocol_id outcome seed git_sha _valid_from]} (first (:rows run-r))]
        [:div {:style {:border "1px solid #cbd5e1" :padding "10px" :borderRadius "4px" :fontSize "0.84em" :marginBottom "12px"}}
         [:div [:strong "Run ID: "] [:code (str _id)]]
         [:div [:strong "Scenario: "] (str scenario_id)]
         [:div [:strong "Suite: "] (str suite_id)]
         [:div [:strong "Protocol: "] (str protocol_id)]
         [:div [:strong "Outcome: "] (str outcome)]
         [:div [:strong "Seed: "] (str seed)]
         [:div [:strong "Git SHA: "] [:code (str git_sha)]]
         [:div [:strong "Valid From: "] (str _valid_from)]
         [:div {:style {:marginTop "8px" :fontSize "0.82em"}}
          [:a {:href "/notebooks/telemetry" :style {:color "#1d4ed8"}}
           "Open Telemetry notebook to inspect trial/entity timeline"]]])
      (ui/callout :amber [:div "Select a run to view context."]))

    [:h2 "Selected step context"]
    (if (and ok? (:ok? step-r) (seq (:rows step-r)))
      (let [{:keys [step_index action result projection_hash time_before_edn time_advance_edn time_after_edn]} (first (:rows step-r))]
        [:div {:style {:border "1px solid #cbd5e1" :padding "10px" :borderRadius "4px" :fontSize "0.84em"}}
         [:div [:strong "Step: "] (str step_index)]
         [:div [:strong "Action: "] (str action)]
         [:div [:strong "Result: "] (str result)]
         [:div [:strong "Projection Hash: "] [:code (str projection_hash)]]
         [:details {:style {:marginTop "6px"}}
          [:summary "time_before_edn"]
          [:pre {:style {:fontSize "0.8em"}} (str time_before_edn)]]
         [:details {:style {:marginTop "6px"}}
          [:summary "time_advance_edn"]
          [:pre {:style {:fontSize "0.8em"}} (str time_advance_edn)]]
         [:details {:style {:marginTop "6px"}}
          [:summary "time_after_edn"]
          [:pre {:style {:fontSize "0.8em"}} (str time_after_edn)]]])
      (ui/callout :amber [:div "Select a step to view temporal payload details."]))]))
