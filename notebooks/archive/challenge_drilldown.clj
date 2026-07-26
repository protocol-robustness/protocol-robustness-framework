^{:nextjournal.clerk/dark-mode true}
(ns notebooks.archive.challenge-drilldown
  (:require [nextjournal.clerk :as clerk]
            [clojure.string :as str]
            [resolver-sim.notebook-support.common :as common]
            [resolver-sim.notebook-support.speds.data :as speds-data]))

;; # Evidence Drilldown — Challenge Window Mechanics
;; ## Scenario S74: Deterministic Deadline Enforcement (t ± 1ms)

^{:nextjournal.clerk/sync true}
(defonce !time-cursor (atom 1180))

;; ---
;; Data Loading

(def scenario (speds-data/load-coverage)) ;; Simplification for drilldown focus
(def events (get-in (common/read-json "scenarios/edn/S74_appeal-deadline-boundary.edn") [:events]))

;; ---
;; Drilldown Interface

^{:nextjournal.clerk/visibility {:code :hide :result :show}
  :nextjournal.clerk/width :full}
(clerk/html
 [:div.drilldown-engine
  [:style "
    .drilldown-engine { background: #020617; color: #7ADDDC; padding: 40px; font-family: 'JetBrains Mono', monospace; }
    .timeline-scrubber { width: 100%; margin: 40px 0; }
    .event-card { 
      background: #0f172a; 
      border: 1px solid #004D59; 
      padding: 20px; 
      border-radius: 4px; 
      margin-bottom: 10px;
    }
    .highlight { color: #FF9800; font-weight: bold; }
    .status-ok { color: #03DAC6; }
  "]

  [:h2 "Temporal State Interceptor (S74)"]
  [:p {:style {:color "#94a3b8"}} "Scrub the timeline to observe state enforcement at the T+1ms boundary."]

  [:div.timeline-scrubber
   [:div {:style {:textAlign "center" :fontSize "1.2rem" :color "#94a3b8"}} 
    "Static view of deadline boundary (T+1180ms)"]]

  [:div.event-log
   (for [e (sort-by :time events)]
     (let [is-boundary? (and (>= (:time e) 1180) (<= (:time e) 1181))]
       [:div.event-card
        [:span {:style {:color "#004D59" :marginRight "20px"}} (str (:time e) "ms")]
        [:span {:style {:fontWeight 800 :marginRight "20px"}} (str/upper-case (:action e))]
        (when (and is-boundary? (> (:time e) 1180))
          [:span.highlight " [GUARD TRIGGERED: ERR_WINDOW_CLOSED]"])
        (when (and is-boundary? (<= (:time e) 1180))
          [:span.status-ok " [ACCEPTED]"])]))]])

;; ---
;; ## Temporal Logic Guard
^{:nextjournal.clerk/visibility {:code :show :result :show}}
(defn check-deadline [time-ms]
  (if (<= time-ms 1180)
    {:status :accepted :msg "Within appeal window"}
    {:status :rejected :msg "Err: window closed (Guard: sm/final-round?)"}))

(check-deadline @!time-cursor)
