;; # Sew Protocol Research — Navigation Hub
;;
;; **Central landing page for all protocol research notebooks.**
;;
;; Navigate by: security area · threat category · audience role · status
;;
;; Audience: Auditors, Protocol Researchers, Grant Reviewers, Governance Participants

^{:nextjournal.clerk/dark-mode true}
(ns notebooks.index
  (:require [nextjournal.clerk :as clerk]
            [resolver-sim.notebook-support.nav :as nav]))

;; ---

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 [:div
  ;; page header
  [:div {:style {:background "#0f172a" :color "#f8fafc"
                 :padding "24px 28px" :borderRadius "8px" :marginBottom "24px"}}
   [:h1 {:style {:margin "0 0 6px" :fontSize "1.5em" :fontWeight "800"
                  :fontFamily "monospace" :letterSpacing "0.04em"}}
    "Sew Protocol — Research Navigation Hub"]
   [:p {:style {:margin "0" :color "#94a3b8" :fontSize "0.83em" :lineHeight "1.6"}}
    "Structured research workspace for protocol security, adversarial simulation, "
    "economic analysis, and evidence provenance. "
    [:strong {:style {:color "#e2e8f0"}} (str (nav/notebook-count) " notebooks")] " across 12 research domains. "
    "Navigate by category, threat, or audience role."]
   [:div {:style {:display "flex" :gap "16px" :marginTop "14px" :flexWrap "wrap"}}
    (for [[label href icon]
           [["Evidence Workbook"       "/notebooks/report"                          "🛡️"]
            ["Protocol Provenance"     "/notebooks/protocol_provenance"             "📋"]
            ["Invariant Failures"      "/notebooks/invariant_failures"              "🔍"]
            ["Workbench v2"            "/notebooks/workbench_v2"                    "🔧"]
            ["Yield scenarios"         "/notebooks/yield_scenarios_workbench"       "📈"]
            ["Benchmark Report"        "/notebooks/benchmark_protocol_robustness"   "📊"]
            ["Atlas of Robustness"     "/notebooks/atlas_artifact"                  "📊"]]]
      [:a {:key   label :href href
           :style {:background "#1e293b" :border "1px solid #334155"
                   :borderRadius "5px" :padding "5px 12px"
                   :fontSize "0.78em" :color "#e2e8f0" :textDecoration "none"
                   :fontFamily "monospace"}}
       (str icon " " label)])]]

  ;; hub grid
  (nav/notebook-hub)])
