;; # Demo 2 — Liquidity Shortfall & Partial Settlement
;; Yield-Enabled Settlement Under Liquidity Stress (screenshot-first)

^{:nextjournal.clerk/visibility {:code :fold :result :show}
  :nextjournal.clerk/dark-mode true}
(ns notebooks.demo-liquidity-shortfall-partial-settlement
  (:require [nextjournal.clerk :as clerk]))

(def stress-epochs
  [{:t "T0" :liq 100 :queue 8  :claim-now 100 :deferred 0}
   {:t "T1" :liq 72  :queue 22 :claim-now 88  :deferred 12}
   {:t "T2" :liq 49  :queue 41 :claim-now 67  :deferred 33}
   {:t "T3" :liq 31  :queue 58 :claim-now 42  :deferred 58}
   {:t "T4" :liq 36  :queue 51 :claim-now 46  :deferred 54}
   {:t "T5" :liq 54  :queue 36 :claim-now 61  :deferred 39}
   {:t "T6" :liq 73  :queue 19 :claim-now 79  :deferred 21}])

(def counterfactual
  [{:t "T0" :liq 100 :queue 8  :claim-now 100}
   {:t "T1" :liq 81  :queue 16 :claim-now 91}
   {:t "T2" :liq 67  :queue 27 :claim-now 84}
   {:t "T3" :liq 58  :queue 33 :claim-now 76}
   {:t "T4" :liq 64  :queue 29 :claim-now 82}
   {:t "T5" :liq 76  :queue 20 :claim-now 89}
   {:t "T6" :liq 88  :queue 12 :claim-now 94}])

(defn card [label value sub]
  [:div {:style {:background "#0f172a" :border "1px solid #004D59" :borderRadius "8px" :padding "12px"}}
   [:div {:style {:fontSize "11px" :textTransform "uppercase" :color "#7ADDDC" :fontWeight 700}} label]
   [:div {:style {:fontSize "26px" :fontWeight 800 :color "#ffffff"}} value]
   [:div {:style {:fontSize "11px" :color "#cbd5e1"}} sub]])

(defn bar [v maxv color]
  [:div {:style {:height "10px" :background "#0b1220" :border "1px solid #134e4a" :borderRadius "999px" :overflow "hidden"}}
   [:div {:style {:height "100%" :width (str (int (* 100 (/ (max 0 v) maxv))) "%") :background color}}]])

(defn waterfall-segment [label amount color text-color]
  [:div {:style {:background color :color text-color :padding "10px" :borderRadius "6px" :fontWeight 700 :fontSize "12px"}}
   [:div label]
   [:div {:style {:fontSize "18px"}} (str amount "%")]])

(clerk/html
 [:div {:style {:background "#020617" :color "#e2e8f0" :padding "28px" :fontFamily "Inter, JetBrains Mono, sans-serif"}}
  [:h1 {:style {:marginTop 0 :color "#ffffff" :fontSize "40px"}} "Demo 2 — Liquidity Shortfall & Partial Settlement"]
  [:p {:style {:color "#7ADDDC" :fontWeight 800 :fontSize "18px"}}
   "This settlement system is operating correctly. But the underlying liquidity environment has changed."]

  [:div {:style {:display "grid" :gridTemplateColumns "repeat(4,1fr)" :gap "10px" :marginTop "12px"}}
   (card "Audience" "Aave / Yield" "settlement + liquidity researchers")
   (card "Mode" "Liquidity stress" "queue active, redemption constrained")
   (card "Critical state" "42% / 58%" "immediate claimable / deferred")
   (card "Core claim" "Market-coupled" "settlement inherits liquidity behavior")]

  [:h2 {:style {:color "#03DAC6" :fontSize "28px" :marginTop "26px"}} "Scene 1 — System setup"]
  [:div {:style {:display "grid" :gridTemplateColumns "1fr 1fr" :gap "12px"}}
   [:div {:style {:background "#0f172a" :border "1px solid #004D59" :borderRadius "8px" :padding "14px"}}
    [:h3 {:style {:marginTop 0 :color "#f8fafc"}} "Capital flow"]
    [:div {:style {:fontSize "13px" :color "#cbd5e1"}}
     "Escrow principal → yield venue → withdrawal queue → partial settlement engine → claimability timeline."]]
   [:div {:style {:background "#0f172a" :border "1px solid #004D59" :borderRadius "8px" :padding "14px"}}
    [:h3 {:style {:marginTop 0 :color "#f8fafc"}} "Liquidity waterfall"]
    [:div {:style {:fontSize "13px" :color "#cbd5e1"}}
     "Available liquidity → immediate fulfillment → deferred queue → recovery sweep."]]]

  [:h2 {:style {:color "#03DAC6" :fontSize "28px" :marginTop "26px"}} "Scene 2 — Coordinated withdrawals"]
  [:div {:style {:background "#0f172a" :border "1px solid #004D59" :borderRadius "8px" :padding "14px"}}
   [:div {:style {:display "grid" :gridTemplateColumns "80px 1fr 1fr 140px 140px" :gap "10px" :fontSize "12px" :color "#94a3b8" :marginBottom "8px"}}
    [:div "Time"] [:div "Liquidity"] [:div "Queue growth"] [:div "Claim now %"] [:div "Deferred %"]]
   (let [mx-liq (apply max 1 (map :liq stress-epochs))
         mx-q (apply max 1 (map :queue stress-epochs))]
     (for [{:keys [t liq queue claim-now deferred]} stress-epochs]
       [:div {:style {:display "grid" :gridTemplateColumns "80px 1fr 1fr 140px 140px" :gap "10px" :alignItems "center" :marginBottom "7px"}}
        [:div {:style {:color "#7ADDDC" :fontWeight 700}} t]
        (bar liq mx-liq "#22d3ee")
        (bar queue mx-q "#f59e0b")
        [:div {:style {:color "#22c55e" :fontWeight 700}} (str claim-now "%")]
        [:div {:style {:color "#ef4444" :fontWeight 700}} (str deferred "%")]]))
   [:div {:style {:marginTop "10px" :padding "8px 10px" :background "#111827" :border "1px solid #ef4444" :borderRadius "6px" :fontWeight 800 :color "#f8fafc"}}
    "Partial settlement state at stress peak: 42% immediately claimable · 58% deferred"]]

  [:h2 {:style {:color "#03DAC6" :fontSize "28px" :marginTop "26px"}} "Scene 3 — Queue propagation"]
  [:div {:style {:background "#0f172a" :border "1px solid #004D59" :borderRadius "8px" :padding "14px"}}
   [:div {:style {:fontSize "13px" :color "#cbd5e1" :marginBottom "8px"}}
    "Cascading delays as queued withdrawals absorb settlement capacity; latency grows until liquidity recovers."]
   [:div {:style {:display "grid" :gridTemplateColumns "repeat(7,1fr)" :gap "8px"}}
    (for [{:keys [t queue]} stress-epochs]
      [:div {:style {:background "#111827" :border "1px solid #334155" :borderRadius "6px" :padding "8px" :textAlign "center"}}
       [:div {:style {:fontSize "11px" :color "#94a3b8"}} t]
       [:div {:style {:fontSize "18px" :fontWeight 800 :color "#f59e0b"}} queue]
       [:div {:style {:fontSize "11px" :color "#cbd5e1"}} "queued"]])]]

  [:h2 {:style {:color "#03DAC6" :fontSize "28px" :marginTop "26px"}} "Explicit shortfall state"]
  [:div {:style {:background "#0f172a" :border "1px solid #ef4444" :borderRadius "8px" :padding "14px"}}
   [:div {:style {:fontSize "14px" :fontWeight 800 :color "#f8fafc" :marginBottom "10px"}}
    "Shortfall at peak stress (T3)"]
   [:div {:style {:display "grid" :gridTemplateColumns "repeat(4,1fr)" :gap "10px" :marginBottom "12px"}}
    (card "Requested settlement" "100%" "total withdrawal demand")
    (card "Immediately claimable" "42%" "fulfilled now")
    (card "Deferred" "58%" "queued shortfall")
    (card "Explicit shortfall" "58%" "unavailable at execution time")]
   [:div {:style {:display "grid" :gridTemplateColumns "1fr 1fr 1fr" :gap "8px"}}
    (waterfall-segment "Requested" 100 "#1f2937" "#f8fafc")
    (waterfall-segment "Available now" 42 "#22c55e" "#020617")
    (waterfall-segment "Shortfall (deferred)" 58 "#ef4444" "#ffffff")]
   [:div {:style {:marginTop "10px" :fontSize "13px" :color "#fecaca" :fontWeight 700}}
    "Shortfall is explicit: 58% of requested settlement cannot be redeemed immediately and is forced into deferred queue state."]]

  [:h2 {:style {:color "#03DAC6" :fontSize "28px" :marginTop "26px"}} "Scene 4 — Counterfactual replay"]
  [:p {:style {:color "#cbd5e1"}} "Adjusted parameters: higher reserve ratio, smoother withdrawal cadence, faster liquidity recovery."]
  [:div {:style {:display "grid" :gridTemplateColumns "1fr 1fr" :gap "12px"}}
   [:div {:style {:background "#0f172a" :border "1px solid #004D59" :borderRadius "8px" :padding "12px"}}
    [:h3 {:style {:marginTop 0 :color "#f8fafc"}} "Baseline stress"]
    [:div {:style {:fontSize "13px" :color "#cbd5e1"}} "Queue peak: 58 · Claim-now floor: 42%"]]
   [:div {:style {:background "#0f172a" :border "1px solid #03DAC6" :borderRadius "8px" :padding "12px"}}
    [:h3 {:style {:marginTop 0 :color "#f8fafc"}} "Counterfactual tuned"]
    [:div {:style {:fontSize "13px" :color "#cbd5e1"}} "Queue peak: 33 · Claim-now floor: 76%"]]]

  [:div {:style {:background "#0f172a" :border "1px solid #004D59" :borderRadius "8px" :padding "14px" :marginTop "10px"}}
   [:div {:style {:display "grid" :gridTemplateColumns "80px 1fr 140px" :gap "10px" :fontSize "12px" :color "#94a3b8" :marginBottom "8px"}}
    [:div "Time"] [:div "Counterfactual liquidity"] [:div "Claim-now %"]]
   (let [mx-liq (apply max 1 (map :liq counterfactual))]
     (for [{:keys [t liq claim-now]} counterfactual]
       [:div {:style {:display "grid" :gridTemplateColumns "80px 1fr 140px" :gap "10px" :alignItems "center" :marginBottom "7px"}}
        [:div {:style {:color "#7ADDDC" :fontWeight 700}} t]
        (bar liq mx-liq "#22c55e")
        [:div {:style {:color "#22c55e" :fontWeight 700}} (str claim-now "%")]]))]

  [:h2 {:style {:color "#03DAC6" :fontSize "28px" :marginTop "26px"}} "Scene 5 — Evidence output"]
  [:div {:style {:background "#0f172a" :border "1px solid #004D59" :borderRadius "8px" :padding "14px"}}
   [:ul
    [:li "Liquidity stress artifact"]
    [:li "Settlement degradation metrics"]
    [:li "Claimability timeline"]
    [:li "Latency and recovery curve snapshots"]]]

  [:h2 {:style {:color "#03DAC6" :fontSize "28px" :marginTop "26px"}} "Final insight"]
  [:div {:style {:background "#0f172a" :border "1px solid #03DAC6" :borderRadius "8px" :padding "14px"}}
   [:p {:style {:margin 0 :fontSize "16px" :fontWeight 700 :color "#f8fafc"}}
    "Yield-enabled settlement systems inherit liquidity behavior from underlying capital markets."]]])
