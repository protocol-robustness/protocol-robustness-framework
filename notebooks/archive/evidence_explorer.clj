^{:nextjournal.clerk/dark-mode true}
(ns notebooks.archive.evidence-explorer
  (:require [nextjournal.clerk :as clerk]
            [clojure.string :as str]
            [resolver-sim.notebook-support.common :as common]
            [resolver-sim.notebook-support.speds.data :as speds-data]))

;; # Evidence Explorer — Technical Audit Surface
;; ## Deep-Dive Trace Inspection for Verified Scenarios

^{:nextjournal.clerk/visibility {:code :hide :result :show}
  :nextjournal.clerk/width :full}
(clerk/html
 (let [all-scenarios (get (speds-data/load-coverage) :scenarios)
       selected-id "scenarios/s08-state-machine-attack-gauntlet"] ;; Default to attack gauntlet
   [:div.explorer-engine
    [:style "
      .explorer-engine { background: #020617; color: #7ADDDC; padding: 40px; font-family: 'JetBrains Mono', monospace; }
      .explorer-grid { display: grid; grid-template-columns: 300px 1fr; gap: 32px; }
      .nav-panel { border-right: 1px solid #004D59; padding-right: 20px; }
      .trace-pane { background: #0f172a; border: 1px solid #004D59; padding: 24px; border-radius: 4px; }
      .event-log { font-size: 11px; color: #cbd5e1; }
      .log-row { display: flex; gap: 15px; padding: 4px 0; border-bottom: 1px solid #020617; }
      .ts { color: #004D59; width: 60px; }
      .action { color: #FF9800; width: 140px; text-transform: uppercase; }
      .nav-item { cursor: pointer; padding: 8px; border-radius: 2px; margin-bottom: 4px; }
      .nav-item:hover { background: #004D59; }
      .active-nav { background: #004D59; color: #fff; }
    "]

    [:h2 {:style {:marginTop 0}} "Evidence Explorer v1.1"]
    
    [:div.explorer-grid
     [:div.nav-panel
      [:h4 "Verified Fixtures"]
      (for [s (take 20 all-scenarios)]
        [:div {:class (str "nav-item " (when (= (:id s) selected-id) "active-nav"))}
         (or (:title s) (:id s))])]

     [:div.trace-pane
      [:h3 {:style {:marginTop 0}} "Raw Deterministic Trace"]
      [:div.event-log
       (let [trace-path (str "data/fixtures/traces/" (or (:file (first (filter #(= (:id %) selected-id) all-scenarios))) "s08-state-machine-attack-gauntlet.trace.json"))
             trace (common/read-json trace-path)]
         (for [e (:events trace)]
           [:div.log-row
            [:span.ts (str (:time e) "ms")]
            [:span.action (:action e)]
            [:span (str (:params e))]]))]]]]))
