(ns resolver-sim.notebook-support.ui
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [resolver-sim.notebook-support.theme :refer [notebook-theme]]
            [resolver-sim.config.paths :as paths]))

(def default-ui-state
  {:selected-trial-id nil
   :selected-entity-id nil
   :filters {:outcome ""
             :invariants ""}
   :pagination {:limit 100}})

(defn rag-badge [rag text]
  (let [[bg border fg emoji]
        (case rag
          :green [(:status/passed-bg notebook-theme) (:status/pass-color notebook-theme) (:status/passed-text notebook-theme) "🟢"]
          :red   [(:status/failed-bg notebook-theme) (:status/fail-color notebook-theme) (:status/failed-text notebook-theme) "🔴"]
          [(:status/warning-bg notebook-theme) (:status/atk-color notebook-theme) (:status/warning-text notebook-theme) "🟠"])]
    [:span {:style {:display "inline-block" :padding "2px 8px"
                    :borderRadius "999px" :border (str "1px solid " border)
                    :backgroundColor bg :color fg :fontSize "0.75em" :fontWeight "600"}}
     (str emoji " " text)]))

(defn callout [rag body]
  ;; Inverted callout: dark background with light text for visual emphasis.
  ;; Theme tokens are light-bg/dark-text; we swap bg/fg for the inverted style.
  (let [[bg border fg]
        (case rag
          :green [(:alert/green-text notebook-theme) (:alert/green-border notebook-theme) (:alert/green-bg notebook-theme)]
          :red   [(:tone/red-text notebook-theme) (:alert/red-border notebook-theme) (:alert/red-bg notebook-theme)]
          [(:alert/amber-text notebook-theme) (:alert/amber-border notebook-theme) (:alert/amber-bg notebook-theme)])]
    [:div {:style {:padding "12px 16px" :backgroundColor bg
                   :border (str "1px solid " border) :borderRadius "4px"
                   :marginBottom "12px" :color fg}}
     body]))

(defn query-error-callout
  ([title {:keys [kind error sql]}]
   (callout :red
            [:div
             [:strong (or title "Query error")]
             (when kind
               [:div {:style {:marginTop "6px" :fontSize "0.86em"}} (str "kind=" kind)])
             (when error
               [:div {:style {:marginTop "4px" :fontFamily "monospace" :fontSize "0.8em"}} error])
             (when sql
               [:details {:style {:marginTop "6px"}}
                [:summary {:style {:cursor "pointer"}} "SQL"]
                [:pre {:style {:fontSize "0.78em" :marginTop "6px"}} (pr-str sql)]])]))
  ([m]
   (query-error-callout "Query error" m)))

(defn metric-card [{:keys [label value rag note]}]
  [:div {:style {:border (str "1px solid " (:tone/neutral-border notebook-theme)) :borderRadius "6px" :padding "10px" :minWidth "140px"}}
   [:div {:style {:display "flex" :gap "6px" :alignItems "center"}}
    (rag-badge rag label)]
   [:div {:style {:fontFamily "monospace" :fontSize "1.1em" :marginTop "4px"}} (str value)]
   (when note [:div {:style {:fontSize "0.75em" :color (:text/muted notebook-theme) :marginTop "4px"}} note])])

(defn filter-controls [!ui-state]
  (let [{:keys [filters pagination]} @!ui-state]
    [:div {:style {:backgroundColor "#172554" :padding "12px" :borderRadius "4px"
                   :border "1px solid #1e40af" :marginBottom "12px" :color "#eff6ff"}}
     [:h3 {:style {:margin "0 0 8px 0" :color "#ffffff"}} "Filters"]
     [:div {:style {:display "grid" :gridTemplateColumns "1fr 1fr 140px" :gap "8px"}}
      [:input {:type "text" :placeholder "Outcome (released/slashed/disputed)"
               :value (or (:outcome filters) "")
               :style {:backgroundColor "#1e293b" :color "#ffffff" :border "1px solid #475569" :padding "6px 10px" :borderRadius "4px"}
               :on-change #(swap! !ui-state assoc-in [:filters :outcome] (.. % -target -value))}]
      [:input {:type "text" :placeholder "Invariants: pass/fail"
               :value (or (:invariants filters) "")
               :style {:backgroundColor "#1e293b" :color "#ffffff" :border "1px solid #475569" :padding "6px 10px" :borderRadius "4px"}
               :on-change #(swap! !ui-state assoc-in [:filters :invariants] (.. % -target -value))}]
      [:input {:type "number" :min "1" :max "1000"
               :value (or (get pagination :limit) 100)
               :style {:backgroundColor "#1e293b" :color "#ffffff" :border "1px solid #475569" :padding "6px 10px" :borderRadius "4px"}
               :on-change #(let [raw (.. % -target -value)
                                 v   (try (Long/parseLong (str raw)) (catch Exception _ 100))
                                 v*  (-> v (max 1) (min 1000))]
                             (swap! !ui-state assoc-in [:pagination :limit] v*))}]]]))

(defn trial-selection-controls [!ui-state]
  (let [selected (:selected-trial-id @!ui-state)]
    [:div {:style {:backgroundColor "#431407" :padding "12px" :borderRadius "4px"
                   :border "1px solid #9a3412" :marginBottom "12px" :color "#ffedd5"}}
     [:h3 {:style {:margin "0 0 8px 0" :color "#ffffff"}} "Selected Trial"]
     [:p {:style {:fontSize "0.82em" :margin "0 0 8px 0" :color "#fdba74"}}
      "Paste a trial UUID directly (works even if row-click interaction is unavailable)."]
     [:div {:style {:display "flex" :gap "8px"}}
      [:input {:type "text"
               :placeholder "Paste full trial UUID"
               :value (or selected "")
               :style {:width "100%" :padding "6px 8px" :fontFamily "monospace" :backgroundColor "#1e293b" :color "#ffffff" :border "1px solid #475569" :borderRadius "4px"}
               :on-change #(let [v (.. % -target -value)
                                 v* (when-not (str/blank? v) v)]
                             (swap! !ui-state assoc :selected-trial-id v*))}]
      [:button {:on-click #(swap! !ui-state assoc :selected-trial-id nil)
                :style {:padding "6px 10px" :cursor "pointer" :backgroundColor "#2e1007" :color "#ffffff" :border "1px solid #ea580c" :borderRadius "4px"}}
       "Clear"]]
     [:div {:style {:fontSize "0.78em" :marginTop "6px" :color "#fdba74"}}
      (str "Current selected-trial-id=" (pr-str selected))]]))

(defn select-trial-view [!ui-state rows]
  [:div {:style {:overflowX "auto"}}
   [:table {:style {:borderCollapse "collapse" :fontSize "0.83em" :width "100%" :backgroundColor "#1e293b" :color "#f8fafc" :borderRadius "6px" :overflow "hidden"}}
    [:thead
     [:tr {:style {:backgroundColor "#0f172a"}}
      [:th {:style {:padding "8px 12px" :textAlign "left" :color "#ffffff"}} "Trial ID"]
      [:th {:style {:padding "8px 12px" :textAlign "left" :color "#ffffff"}} "Batch"]
      [:th {:style {:padding "8px 12px" :textAlign "left" :color "#ffffff"}} "Protocol"]
      [:th {:style {:padding "8px 12px" :textAlign "left" :color "#ffffff"}} "Outcome"]
      [:th {:style {:padding "8px 12px" :textAlign "center" :color "#ffffff"}} "invariants_ok"]
      [:th {:style {:padding "8px 12px" :textAlign "center" :color "#ffffff"}} "divergence"]]]
    (into [:tbody]
          (for [{:keys [trial-id batch-id protocol-id outcome invariants-ok divergence]} rows]
            [:tr {:style {:borderBottom "1px solid #334155"}}
             [:td {:style {:padding "6px 12px" :fontFamily "monospace" :fontSize "0.82em"
                           :cursor "pointer" :color "#38bdf8" :fontWeight "600"
                           :whiteSpace "nowrap" :minWidth "320px" :userSelect "text"}
                   :title (str trial-id)
                   :on-click #(swap! !ui-state assoc :selected-trial-id trial-id)}
              (str trial-id)]
             [:td {:style {:padding "6px 12px" :fontFamily "monospace" :color "#e2e8f0"}} (str batch-id)]
             [:td {:style {:padding "6px 12px" :fontFamily "monospace" :color "#e2e8f0"}} (str protocol-id)]
             [:td {:style {:padding "6px 12px" :color "#e2e8f0"}} (str outcome)]
             [:td {:style {:padding "6px 12px" :textAlign "center"}}
              (if invariants-ok (rag-badge :green "pass") (rag-badge :red "fail"))]
             [:td {:style {:padding "6px 12px" :textAlign "center"}}
              (if divergence (rag-badge :amber "yes") (rag-badge :green "no"))]]))]])

(defn event-timeline-view [events]
  (if (seq events)
    [:table {:style {:borderCollapse "collapse" :fontSize "0.8em" :width "100%" :backgroundColor "#1e293b" :color "#f8fafc" :borderRadius "6px" :overflow "hidden"}}
     [:thead
      [:tr {:style {:backgroundColor "#0f172a"}}
       [:th {:style {:padding "8px 12px" :textAlign "left" :color "#ffffff"}} "#"]
       [:th {:style {:padding "8px 12px" :textAlign "left" :color "#ffffff"}} "time"]
       [:th {:style {:padding "8px 12px" :textAlign "left" :color "#ffffff"}} "entity_id"]
       [:th {:style {:padding "8px 12px" :textAlign "left" :color "#ffffff"}} "event_type"]
       [:th {:style {:padding "8px 12px" :textAlign "left" :color "#ffffff"}} "entity_state"]]]
     (into [:tbody]
           (map-indexed
            (fn [i {:keys [block-time entity-id event-type entity-state]}]
              [:tr {:style {:borderBottom "1px solid #334155"}}
               [:td {:style {:padding "5px 12px" :color "#e2e8f0"}} (inc i)]
               [:td {:style {:padding "5px 12px" :fontFamily "monospace" :color "#e2e8f0"}} (str block-time)]
               [:td {:style {:padding "5px 12px" :fontFamily "monospace" :color "#e2e8f0"}} (str entity-id)]
               [:td {:style {:padding "5px 12px" :color "#e2e8f0"}} (str event-type)]
               [:td {:style {:padding "5px 12px" :fontFamily "monospace" :color "#e2e8f0"}} (str entity-state)]])
            events))]
    (callout :amber [:div "No events recorded for this trial."])))

(defn entity-history-view [events entity-id]
  (let [rows (filter #(= (str entity-id) (str (:entity-id %))) events)]
    (event-timeline-view rows)))

(defn notebook-navigation
  ([] (notebook-navigation nil nil))
  ([current-label] (notebook-navigation current-label nil))
  ([current-label custom-links]
   (let [default-links [{:label "Dispute Resolution Workbench" :href "/notebooks/dispute_resolution"}
                        {:label "Report Notebook" :href "/notebooks/report"}
                        {:label "Invariant Failure Triage" :href "/notebooks/invariant_failures"}
                        {:label "Telemetry Trial Timeline" :href "/notebooks/telemetry"}
                        {:label "XTDB Overview" :href "/notebooks/xtdb_overview"}]
         links (or custom-links default-links)]
     [:div {:style {:backgroundColor "#1e1b4b" :border "1px solid #4338ca" :borderRadius "6px"
                    :padding "10px 12px" :marginBottom "12px" :color "#e0e7ff"}}
      [:div {:style {:fontWeight "600" :marginBottom "6px" :color "#ffffff"}} "Notebook navigation"]
      [:div {:style {:display "flex" :gap "10px" :flexWrap "wrap" :fontSize "0.9em"}}
       (for [{:keys [label href]} links]
         [:a {:href href :style {:color "#818cf8" :fontWeight "600"}} label])
       (when current-label
         [:span {:style {:color "#94a3b8"}} (str "• current: " current-label)])]])))

(defn reference-validation-status []
  (let [suite (paths/reference-validation-suite-dir)
        required [(str suite "/expected/summary.json")
                  (str suite "/actual/summary.json")
                  (str suite "/expected/summary.sha256")
                  (str suite "/actual/summary.sha256")
                  (str suite "/reports/reference-validation-v1.md")]
        present? (fn [p] (.exists (io/file p)))
        present (filter present? required)
        missing (remove present? required)
        rag (cond
              (empty? present) :red
              (seq missing) :amber
              :else :green)]
    (callout rag
             [:div
              [:strong {:style {:color "#ffffff"}} "Reference validation status"]
              [:div {:style {:marginTop "6px" :fontSize "0.86em"}}
               (str (count present) "/" (count required) " core artifacts present")]
              (when (seq missing)
                [:details {:style {:marginTop "6px"}}
                 [:summary {:style {:cursor "pointer"}} "Missing artifacts"]
                 [:ul {:style {:margin "6px 0 0 18px"}}
                  (for [m missing] [:li m])]])
              [:div {:style {:marginTop "8px" :fontSize "0.86em"}}
               [:a {:href "/notebooks/report" :style {:color "#60a5fa" :fontWeight "600"}} "Open Report notebook"]
               " · "
               [:a {:href (str (paths/reference-validation-suite-dir) "/reports/reference-validation-v1.md") :style {:color "#60a5fa" :fontWeight "600"}}
                "Open reference validation report"]]])))

(defn provenance-footer [run]
  (let [manifest (:manifest run)
        registry (:registry run)
        run-id   (get manifest :run_id "—")
        registry-sha (or (get-in manifest [:framework :registry_sha256]) "—")
        git-commit   (get-in manifest [:framework :git_commit] "—")
        git-message  (get-in manifest [:framework :git_message] "—")]
    [:div {:style {:marginTop "40px" :padding "20px" :borderTop (str "1px solid " (:tone/neutral-border notebook-theme)) :fontSize "0.75em" :color (:text/muted notebook-theme)}}
     [:div "Artifact provenance:"]
     [:div {:style {:fontFamily "monospace"}}
      "Run ID: " run-id [:br]
      "Registry Hash: " registry-sha [:br]
      "Commit: " git-commit [:br]
      "Message: " git-message]]))

