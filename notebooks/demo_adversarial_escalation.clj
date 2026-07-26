;; # Demo 1 — Adversarial Dispute Escalation
;; Coordination Failure Under Valid Execution

^{:nextjournal.clerk/visibility {:code :hide :result :show}
  :nextjournal.clerk/dark-mode true}
(ns notebooks.demo-adversarial-escalation
  (:require [nextjournal.clerk :as clerk]
            [clojure.string :as str]
            [resolver-sim.notebook-support.common :as common]
            [resolver-sim.notebook-support.speds.data :as data]))

(def base-scenario "scenarios/edn/S83_yield-accrual-reorg-race.edn")
(def cf-scenario "scenarios/edn/S74_appeal-deadline-boundary.edn")

(defn load-events [scenario-path]
  (or (:events (common/read-json scenario-path)) []))

(defn actor-lane [agent]
  (case (str/lower-case (str agent))
    "buyer" "Buyer"
    "seller" "Seller"
    "resolver" "Resolver"
    "keeper" "Keeper"
    "System"))

(defn window-label [{:keys [action time]}]
  (cond
    (= action "raise_dispute") {:label "Attack window opens" :color "#f59e0b" :time time}
    (= action "challenge_resolution") {:label "Appeal boundary reached" :color "#ef4444" :time time}
    (= action "execute_pending_settlement") {:label "Settlement race" :color "#22c55e" :time time}
    :else nil))

(defn timeline-view [events]
  [:div {:style {:display "grid" :gap "8px" :fontSize "12px"}}
   (for [e events
         :let [w (window-label e)]]
     [:div {:style {:display "grid" :gridTemplateColumns "130px 70px 220px 1fr"
                    :gap "10px" :padding "6px"
                    :border "1px solid #134e4a" :borderRadius "6px"
                    :background "#0f172a"}}
      [:div {:style {:color "#7ADDDC" :fontWeight 700}} (actor-lane (:agent e))]
      [:div {:style {:color "#94a3b8"}} (str (:time e) "s")]
      [:div {:style {:color "#f8fafc" :fontWeight 600}} (:action e)]
      [:div
       (when w
         [:span {:style {:padding "2px 8px" :borderRadius "999px" :background (:color w) :color "#020617" :fontWeight 800}}
          (:label w)])]])])

(defn metrics-card [title value subtitle]
  [:div {:style {:background "#0f172a" :border "1px solid #004D59" :padding "12px" :borderRadius "8px"}}
   [:div {:style {:fontSize "11px" :color "#7ADDDC" :textTransform "uppercase" :fontWeight 700}} title]
   [:div {:style {:fontSize "24px" :fontWeight 800 :color "#ffffff"}} value]
   [:div {:style {:fontSize "11px" :color "#cbd5e1"}} subtitle]])

(let [artifacts (data/load-run-artifacts)
      base-events (load-events base-scenario)
      cf-events (load-events cf-scenario)
      findings (or (:findings (common/read-json "results/test-artifacts/findings.json")) [])
      issues (or (:issues (common/read-json "results/test-artifacts/issues.json")) [])
      s83-finding (first (filter #(str/includes? (str/lower-case (str (:scenario_id %))) "s83") findings))]
  (clerk/html
   [:div {:style {:background "#020617" :color "#e2e8f0" :padding "28px" :fontFamily "Inter, JetBrains Mono, sans-serif"}}
    [:h1 {:style {:marginTop 0 :color "#ffffff" :fontSize "40px" :lineHeight 1.1}} "Demo 1 — Adversarial Dispute Escalation"]
    [:p {:style {:color "#7ADDDC" :fontWeight 800 :fontSize "18px"}}
     "This is a valid protocol execution. No invariants are broken. Yet the system is under strategic extraction pressure."]

    [:h2 {:style {:color "#03DAC6" :fontSize "28px" :marginTop "28px"}} "Scene 1 — Scenario setup"]
    [:div {:style {:display "grid" :gridTemplateColumns "repeat(4,1fr)" :gap "10px"}}
     (metrics-card "Actors" "4" "buyer, seller, resolver, keeper")
     (metrics-card "Appeal window" "60s" "from scenario protocol params")
     (metrics-card "Escalation actions" (str (count (filter #(= "execute_resolution" (:action %)) base-events))) "pressure points")
     (metrics-card "Scenario" "S83" "yield accrual reorg race")]

    [:h2 {:style {:color "#03DAC6" :fontSize "28px" :marginTop "28px"}} "Scene 2 — Adversarial behavior emerges"]
    [:p {:style {:color "#cbd5e1"}} "Swimlane replay timeline with highlighted adversarial windows"]
    (timeline-view base-events)

    [:h2 {:style {:color "#03DAC6" :fontSize "28px" :marginTop "28px"}} "Scene 3 — Counterfactual replay"]
    [:div {:style {:display "grid" :gridTemplateColumns "1fr 1fr" :gap "12px"}}
     [:div {:style {:background "#0f172a" :border "1px solid #004D59" :padding "12px" :borderRadius "8px"}}
      [:h3 {:style {:marginTop 0}} "Base: S83"]
      [:div {:style {:fontSize "12px" :color "#cbd5e1"}} (str "Events: " (count base-events))]]
     [:div {:style {:background "#0f172a" :border "1px solid #004D59" :padding "12px" :borderRadius "8px"}}
      [:h3 {:style {:marginTop 0}} "Counterfactual: S74 (appeal boundary)"]
      [:div {:style {:fontSize "12px" :color "#cbd5e1"}} (str "Events: " (count cf-events))]]]

    [:h2 {:style {:color "#03DAC6" :fontSize "28px" :marginTop "28px"}} "Scene 4 — Evidence generation"]
    [:ul
     [:li "results/test-artifacts/coverage.json"]
     [:li "results/test-artifacts/findings.json"]
     [:li "results/test-artifacts/issues.json"]
     [:li "results/test-artifacts/test-summary.json"]]
    [:div {:style {:fontSize "12px" :color "#cbd5e1"}}
     (str "Findings count: " (count findings) " · Issues count: " (count issues))]

    [:h2 {:style {:color "#03DAC6" :fontSize "28px" :marginTop "28px"}} "Scene 5 — Final insight"]
    [:div {:style {:background "#0f172a" :border "1px solid #03DAC6" :padding "14px" :borderRadius "8px"}}
     [:div [:strong "S83 classification: "] (or (:kind s83-finding) "n/a") " / " (or (:severity s83-finding) "n/a")]
     [:p {:style {:marginBottom 0 :color "#f8fafc"}}
      "Robustness is not only about valid state transitions. It is about strategic behavior under adversarial incentives."]]]))
