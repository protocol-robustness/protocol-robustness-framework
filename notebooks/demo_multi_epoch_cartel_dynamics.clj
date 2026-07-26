;; # Demo 3 — Multi-Epoch Cartel Dynamics
;; Long-Horizon Incentive Stability (screenshot-first)

^{:nextjournal.clerk/visibility {:code :hide :result :show}
  :nextjournal.clerk/dark-mode true}
(ns notebooks.demo-multi-epoch-cartel-dynamics
  (:require [nextjournal.clerk :as clerk]))

(def epochs
  [{:epoch 1 :collusion-profit 12 :detection-prob 0.18 :slashes 0 :rep 1.00}
   {:epoch 2 :collusion-profit 19 :detection-prob 0.19 :slashes 0 :rep 0.97}
   {:epoch 3 :collusion-profit 23 :detection-prob 0.20 :slashes 1 :rep 0.93}
   {:epoch 4 :collusion-profit 27 :detection-prob 0.20 :slashes 1 :rep 0.89}
   {:epoch 5 :collusion-profit 31 :detection-prob 0.21 :slashes 1 :rep 0.84}
   {:epoch 6 :collusion-profit 35 :detection-prob 0.22 :slashes 2 :rep 0.79}])

(def counterfactual
  [{:epoch 1 :collusion-profit 11 :detection-prob 0.35 :slashes 1}
   {:epoch 2 :collusion-profit 6  :detection-prob 0.38 :slashes 2}
   {:epoch 3 :collusion-profit 2  :detection-prob 0.40 :slashes 2}
   {:epoch 4 :collusion-profit -4 :detection-prob 0.42 :slashes 3}
   {:epoch 5 :collusion-profit -11 :detection-prob 0.44 :slashes 4}
   {:epoch 6 :collusion-profit -18 :detection-prob 0.46 :slashes 4}])

(defn metric-card [label value sub]
  [:div {:style {:background "#0f172a" :border "1px solid #004D59" :borderRadius "8px" :padding "12px"}}
   [:div {:style {:fontSize "11px" :textTransform "uppercase" :color "#7ADDDC" :fontWeight 700}} label]
   [:div {:style {:fontSize "26px" :fontWeight 800 :color "#ffffff"}} value]
   [:div {:style {:fontSize "11px" :color "#cbd5e1"}} sub]])

(defn bar [v maxv color]
  [:div {:style {:height "10px" :background "#0b1220" :border "1px solid #134e4a" :borderRadius "999px" :overflow "hidden"}}
   [:div {:style {:height "100%" :width (str (int (* 100 (/ (max 0 v) maxv))) "%") :background color}}]])

(clerk/html
 [:div {:style {:background "#020617" :color "#e2e8f0" :padding "28px" :fontFamily "Inter, JetBrains Mono, sans-serif"}}
  [:h1 {:style {:marginTop 0 :color "#ffffff" :fontSize "40px"}} "Demo 3 — Multi-Epoch Cartel Dynamics"]
  [:p {:style {:color "#7ADDDC" :fontWeight 800 :fontSize "18px"}}
   "Long-horizon incentive stability: repeated strategic interaction, not isolated events."]

  [:div {:style {:display "grid" :gridTemplateColumns "repeat(4,1fr)" :gap "10px" :marginTop "12px"}}
   (metric-card "Audience" "Research" "mechanism design · EF · governance")
   (metric-card "Horizon" "6 epochs" "repeated strategic game")
   (metric-card "Model" "Cartel + slashing" "profit vs detection pressure")
   (metric-card "Core claim" "Stability is dynamic" "incentives over time")]

  [:h2 {:style {:color "#03DAC6" :fontSize "28px" :marginTop "26px"}} "Scene 1 — Setup"]
  [:div {:style {:background "#0f172a" :border "1px solid #004D59" :borderRadius "8px" :padding "14px"}}
   [:ul
    [:li "Resolvers operate under repeated-epoch incentives."]
    [:li "Collusion profitability competes with detection and slashing risk."]
    [:li "Reputation decay compounds long-horizon fragility."]]]

  [:h2 {:style {:color "#03DAC6" :fontSize "28px" :marginTop "26px"}} "Scene 2 — Epoch progression"]
  [:div {:style {:background "#0f172a" :border "1px solid #004D59" :borderRadius "8px" :padding "14px"}}
   [:div {:style {:display "grid" :gridTemplateColumns "70px 1fr 120px 120px 120px" :gap "10px" :fontSize "12px" :color "#94a3b8" :marginBottom "8px"}}
    [:div "Epoch"] [:div "Collusion profitability"] [:div "Detect. prob"] [:div "Slashes"] [:div "Reputation"]]
   (let [mx (apply max 1 (map :collusion-profit epochs))]
     (for [{:keys [epoch collusion-profit detection-prob slashes rep]} epochs]
       [:div {:style {:display "grid" :gridTemplateColumns "70px 1fr 120px 120px 120px" :gap "10px" :alignItems "center" :marginBottom "7px"}}
        [:div {:style {:color "#7ADDDC" :fontWeight 700}} (str "E" epoch)]
        (bar collusion-profit mx "#22d3ee")
        [:div {:style {:color "#e2e8f0"}} (format "%.2f" detection-prob)]
        [:div {:style {:color "#fbbf24"}} (str slashes)]
        [:div {:style {:color "#e2e8f0"}} (format "%.2f" rep)]]))]

  [:h2 {:style {:color "#03DAC6" :fontSize "28px" :marginTop "26px"}} "Scene 3 — Counterfactual branch"]
  [:p {:style {:color "#cbd5e1"}} "Increase fraud detection probability, replay, observe profitability collapse."]
  [:div {:style {:display "grid" :gridTemplateColumns "1fr 1fr" :gap "12px"}}
   [:div {:style {:background "#0f172a" :border "1px solid #004D59" :borderRadius "8px" :padding "12px"}}
    [:h3 {:style {:marginTop 0 :color "#f8fafc"}} "Baseline"]
    [:div {:style {:fontSize "13px" :color "#cbd5e1"}} "Detection drifts slowly (0.18 → 0.22), cartel remains profitable."]]
   [:div {:style {:background "#0f172a" :border "1px solid #03DAC6" :borderRadius "8px" :padding "12px"}}
    [:h3 {:style {:marginTop 0 :color "#f8fafc"}} "Counterfactual: higher detection"]
    [:div {:style {:fontSize "13px" :color "#cbd5e1"}} "Detection rises (0.35 → 0.46), profitability crosses below zero by epoch 4."]]]

  [:div {:style {:background "#0f172a" :border "1px solid #004D59" :borderRadius "8px" :padding "14px" :marginTop "10px"}}
   [:div {:style {:display "grid" :gridTemplateColumns "70px 1fr 120px 120px 120px" :gap "10px" :fontSize "12px" :color "#94a3b8" :marginBottom "8px"}}
    [:div "Epoch"] [:div "Counterfactual profitability"] [:div "Detect. prob"] [:div "Slashes"]]
   (let [mx (apply max 1 (map :collusion-profit counterfactual))]
     (for [{:keys [epoch collusion-profit detection-prob slashes]} counterfactual]
       [:div {:style {:display "grid" :gridTemplateColumns "70px 1fr 120px 120px 120px" :gap "10px" :alignItems "center" :marginBottom "7px"}}
        [:div {:style {:color "#7ADDDC" :fontWeight 700}} (str "E" epoch)]
        (bar collusion-profit mx (if (neg? collusion-profit) "#ef4444" "#22c55e"))
        [:div {:style {:color "#e2e8f0"}} (format "%.2f" detection-prob)]
        [:div {:style {:color "#fbbf24"}} (str slashes)]]))]

  [:h2 {:style {:color "#03DAC6" :fontSize "28px" :marginTop "26px"}} "Final insight"]
  [:div {:style {:background "#0f172a" :border "1px solid #03DAC6" :borderRadius "8px" :padding "14px"}}
   [:p {:style {:margin 0 :fontSize "16px" :fontWeight 700 :color "#f8fafc"}}
    "Systems should be evaluated as repeated strategic games. Small detection changes can flip long-horizon cartel incentives from stable profit to structural collapse."]]])
