^{:nextjournal.clerk/dark-mode true}
(ns notebooks.workbench-production
  (:require [nextjournal.clerk :as clerk]
            [resolver-sim.notebook-support.speds.data :as data]
            [resolver-sim.notebook-support.speds.story :as story]
            [resolver-sim.notebook-support.speds.config :as config]
            [clojure.string :as str]))

;; # Sew Protocol — Production Evidence Workbench
;; ## Mission Control (4-tile status only)

^{:nextjournal.clerk/visibility {:code :hide :result :show}
  :nextjournal.clerk/width :full}
(clerk/html
 (let [artifacts (data/load-run-artifacts)
       {:keys [summary coverage]} artifacts
        scenarios (sort-by :id (:scenarios coverage))
        {:keys [replay-match-label scenario-count]} (data/narrative-metrics artifacts)]
   [:div.workbench-container
    [:style "
      /* Force full width on all possible Clerk containers */
      .clerk-view, .viewer-notebook, .prose, .max-w-prose, .max-w-5xl, .mx-auto { 
        max-width: none !important; 
        width: 100% !important; 
        margin-left: 0 !important; 
        margin-right: 0 !important; 
        padding: 0 !important; 
      }
      .workbench-container { 
        font-family: 'Inter', sans-serif;
        background: #020617; 
        color: #7ADDDC; 
        padding: 60px;
        min-height: 100vh;
        width: 100%;
      }
      .hero-strip {
        display: grid;
        grid-template-columns: repeat(4, 1fr);
        gap: 24px;
        margin-bottom: 60px;
      }
      .metric-panel {
        background: #0f172a;
        border: 1px solid #004D59;
        border-radius: 4px;
        padding: 24px;
        box-shadow: inset 0 0 20px rgba(0, 77, 89, 0.2);
      }
      .metric-label { font-size: 10px; text-transform: uppercase; color: #004D59; font-weight: 800; letter-spacing: 0.1em; margin-bottom: 10px; }
      .metric-value { font-family: 'JetBrains Mono'; font-size: 1.5rem; font-weight: 900; color: #fff; }
      .metric-caption { margin-top: 8px; font-size: 12px; color: #cbd5e1; line-height: 1.3; }
    "]

    ;; 1. Mission Control Hero
    [:div.hero-strip
     [:div.metric-panel
      [:div.metric-label "Validation Gate"]
      [:div.metric-value (str/upper-case (or (:overall_status summary) "FAIL"))]
      [:div.metric-caption "Canonical gate status from latest test artifacts."]]
     [:div.metric-panel
      [:div.metric-label "Scenario Corpus"]
      [:div.metric-value scenario-count]
      [:div.metric-caption "Scenarios loaded in latest coverage artifact."]]
     [:div.metric-panel
      [:div.metric-label "Replay Match"]
      [:div.metric-value replay-match-label]
      [:div.metric-caption "Deterministic replay alignment across runs."]]
     [:div.metric-panel
      [:div.metric-label "Git Lineage"]
      [:div.metric-value (let [sha (or (:git_sha summary) (:git-sha config/protocol-defaults))]
                     (if sha (subs sha 0 (min 7 (count sha))) "—"))]
      [:div.metric-caption "Commit fingerprint for this evidence bundle."]]]
    
    ;; 2. Footer
    [:div {:style {:marginTop "80px" :paddingTop "40px" :borderTop "1px solid #004D59" :display "flex" :justifyContent "space-between" :fontSize "11px" :color "#004D59" :fontWeight 800}}
     [:div "© 2026 SEW PROTOCOL FOUNDATION"]
     [:div (str "EVIDENCE_BUNDLE: " (or (:run_id summary) (:run-id config/protocol-defaults)))]]]))
