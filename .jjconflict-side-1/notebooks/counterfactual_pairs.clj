;; # Counterfactual Pairs — Simulation Correctness Demo
;;
;; Purpose: show "what if x had been y" with concrete paired scenarios.

^{:nextjournal.clerk/visibility {:code :fold :result :show}
  :nextjournal.clerk/dark-mode true}
(ns notebooks.counterfactual-pairs
  (:require [nextjournal.clerk :as clerk]
            [clojure.string :as str]
            [resolver-sim.notebook-support.speds.data :as speds-data]
            [resolver-sim.notebook-support.common :as common]))

(defn- canonical-sid [sid]
  (-> (str sid)
      str/lower-case
      (str/replace #"\\.json$" "")
      (str/replace #"_" "-")
      (str/replace #"^scenarios/" "scenarios/")))

(defn- sid-candidates [sid]
  (let [c (canonical-sid sid)
        bare (str/replace c #"^scenarios/" "")]
    [c
     (str "scenarios/" bare)
     bare]))

(defn- index-by-id [scenarios]
  (reduce (fn [acc s]
            (reduce (fn [a k] (assoc a k s))
                    acc
                    (sid-candidates (:id s))))
          {}
          scenarios))

(defn- scenario-chip [label sid expected scenarios-by-id]
  (let [s (some #(get scenarios-by-id %) (sid-candidates sid))
        file-path (str (str/replace sid #"^scenarios/" "scenarios/") ".json")
        file-scenario (when-not s (common/read-json file-path))
        title (or (:title s) (:title file-scenario))
        source (cond
                 s "coverage"
                 file-scenario "scenario file"
                 :else "unresolved")]
    [:div {:style {:border "1px solid #134e4a" :background "#0b1220" :borderRadius "6px" :padding "12px"}}
     [:div {:style {:fontSize "11px" :fontWeight 800 :color "#7ADDDC" :textTransform "uppercase" :letterSpacing "0.08em"}}
      label]
     [:div {:style {:marginTop "6px" :fontFamily "JetBrains Mono" :fontSize "12px" :color "#e2e8f0"}} sid]
     [:div {:style {:marginTop "6px" :fontSize "12px" :color "#cbd5e1"}}
      (or title "Scenario not found in loaded coverage artifact or scenarios/edn/*.edn")]
     [:div {:style {:marginTop "4px" :fontSize "11px" :color "#94a3b8"}}
      (str "Metadata source: " source)]
     [:div {:style {:marginTop "8px" :fontSize "12px" :color "#fbbf24"}}
      (str "Expected outcome: " expected)]]))

(def pair-specs
  [{:id :appeal-window
    :title "Appeal inside window vs just outside"
    :focus "Demonstrates deterministic deadline enforcement around appeal boundary"
    :left {:label "Inside window (allowed)"
           :scenario-id "scenarios/S76_sponsored-appeal-third-party-funding"
           :expected "Appeal accepted while window is open"}
    :right {:label "Just outside window (blocked)"
            :scenario-id "scenarios/S74_appeal-deadline-boundary"
            :expected "Late appeal rejected by deadline guard"}}

   {:id :escalation
    :title "Escalation allowed vs blocked"
    :focus "Shows escalation path only proceeds when level/guard conditions hold"
    :left {:label "Allowed"
           :scenario-id "scenarios/S21_dr3-kleros-pending-cleared-on-escalation"
           :expected "Escalation succeeds and pending path is superseded"}
    :right {:label "Blocked"
            :scenario-id "scenarios/S23_preemptive-escalation-blocked"
            :expected "Escalation rejected by precondition guard"}}

   {:id :stake-threshold
    :title "Resolver stake above vs below slash threshold"
    :focus "Shows stake health changes slash/execution outcomes"
    :left {:label "Above threshold"
           :scenario-id "scenarios/S25_profit-maximizer-slash-lifecycle"
           :expected "Slash lifecycle executes without exhausting resolver"}
    :right {:label "Below threshold"
            :scenario-id "scenarios/S24_resolver-stake-depletion-cascade"
            :expected "Stake depletion blocks/changes downstream behavior"}}

   {:id :pending-settlement
    :title "Pending settlement executed now vs delayed"
    :focus "Shows time-gated settlement behavior around expiry/deadline"
    :left {:label "Executed now"
           :scenario-id "scenarios/S05_pending-settlement-execute"
           :expected "Pending settlement executes at valid time"}
    :right {:label "Delayed"
            :scenario-id "scenarios/S57_pending-settlement-expiry-boundary-1s"
            :expected "Delayed/expired path rejected or altered by temporal guard"}}])

(clerk/html
 (let [artifacts (speds-data/load-run-artifacts)
       coverage (:coverage artifacts)
       scenarios (or (:scenarios coverage) [])
       by-id (index-by-id scenarios)]
   [:div {:style {:background "#020617" :color "#7ADDDC" :padding "32px" :fontFamily "Inter, JetBrains Mono, sans-serif"}}
    [:h1 {:style {:color "#fff" :marginTop 0}} "Counterfactual Pairs"]
    [:p {:style {:color "#cbd5e1" :maxWidth "980px"}}
     "Goal: demonstrate the simulator behaves correctly by contrasting closely-related scenarios where one key assumption changes."]

    [:div {:style {:marginTop "18px" :marginBottom "24px" :padding "12px" :border "1px solid #134e4a" :background "#0b1220" :borderRadius "6px"}}
     [:strong {:style {:color "#fbbf24"}} "Start here: "]
     [:span {:style {:color "#e2e8f0"}} "Appeal inside window vs just outside (first card below)."]]

    (for [{:keys [id title focus left right]} pair-specs]
      [:div {:key (name id)
             :style {:marginBottom "20px" :border "1px solid #134e4a" :borderRadius "8px" :padding "14px" :background "#0f172a"}}
       [:div {:style {:fontSize "11px" :textTransform "uppercase" :letterSpacing "0.08em" :color "#94a3b8"}}
        (str "pair: " (name id))]
       [:h3 {:style {:margin "6px 0" :color "#fff"}} title]
       [:p {:style {:marginTop 0 :color "#cbd5e1"}} focus]
       [:div {:style {:display "grid" :gridTemplateColumns "1fr 1fr" :gap "12px"}}
        (scenario-chip (:label left) (:scenario-id left) (:expected left) by-id)
        (scenario-chip (:label right) (:scenario-id right) (:expected right) by-id)]])

    [:div {:style {:marginTop "24px" :fontSize "12px" :color "#94a3b8"}}
     "Tip: run `bb run:family forking-strategist` and `bb artifacts:refresh` before opening this notebook for freshest artifact context."]]))
