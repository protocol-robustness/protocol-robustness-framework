(ns examples.speds.component-gallery
  (:require [nextjournal.clerk :as clerk]
            [resolver-sim.notebook-support.speds.core :as speds]
            [resolver-sim.notebook-support.speds.tokens :as tokens]))

;; # SPEDS v1.1 — Component Gallery
;; ### A living visual regression suite for the Protocol Evidence Design System.
;;
;; This is an example gallery, not a production notebook.
;; Move to notebooks/ if you need to render it in Clerk.

^{:nextjournal.clerk/visibility {:code :hide :result :show}
  :nextjournal.clerk/width :full}
(clerk/html
 [:div {:style {:background "#020617" :padding "40px" :color "#7ADDDC" :fontFamily "'Inter', sans-serif"}}
  
  [:h2 "1. Core Design Tokens"]
  [:div {:style {:display "flex" :gap "20px" :marginBottom "40px"}}
   (for [[k v] tokens/palette]
     [:div {:style {:textAlign "center"}}
      [:div {:style {:width "60px" :height "60px" :background v :border "1px solid #004D59" :borderRadius "4px" :marginBottom "8px"}}]
      [:div {:style {:fontSize "10px" :fontFamily "JetBrains Mono"}} (name k)]])]

  [:h2 "2. Actor Nodes (V-ACT)"]
  [:div {:style {:display "flex" :gap "40px" :marginBottom "40px"}}
   [:div {:style {:textAlign "center"}} (speds/v-act "byr" :honest) [:p "Honest"]]
   [:div {:style {:textAlign "center"}} (speds/v-act "atk" :adversarial) [:p "Adversarial"]]
   [:div {:style {:textAlign "center"}} (speds/v-act "klr" :backstop) [:p "Backstop"]]]

  [:h2 "3. Flow Lines (V-FLO)"]
  [:div {:style {:display "flex" :flexDirection "column" :gap "20px" :maxWidth "400px" :marginBottom "40px"}}
   [:div "Principal:" (speds/v-flo :principal)]
   [:div "Yield:" (speds/v-flo :yield)]
   [:div "Adversarial:" (speds/v-flo :adversarial)]]

  [:h2 "4. Invariant Badges (V-INV)"]
  [:div {:style {:display "flex" :gap "20px" :marginBottom "40px"}}
   (speds/v-inv :solvency :ok)
   (speds/v-inv :budget :fail)]

  [:h2 "5. Logic Gates (V-RES)"]
  [:div {:style {:marginBottom "40px"}}
   (speds/v-res "Invariant G04")]

  [:h2 "6. Evidence Containers (V-FRAME)"]
  [:div {:style {:display "grid" :gridTemplateColumns "repeat(auto-fill, minmax(500px, 1fr))" :gap "40px"}}
   
   ;; Example 1: The Hook
   (speds/v-frame 
    {:header "[SEW_PROT] ADVERSARIAL_RUN: S26 | STATUS: ALERT"
     :footer-left "TRACE_ID: 8f2a...1b9c"
     :footer-right "BLOCK_H: 14,200,000"}
    [:div {:style {:padding "20px"}}
     [:div {:style {:display "inline-block" :padding "4px 12px" :background "rgba(255, 152, 0, 0.1)" :border "1px solid #FF9800" :color "#FF9800" :fontSize "12px" :fontWeight "800" :marginBottom "20px"}} "THREAT_DETECTED"]
     [:h1 {:style {:fontSize "48px" :fontWeight "900" :lineHeight "0.9" :textTransform "uppercase" :color "#fff" :textShadow "0 0 30px rgba(122, 221, 220, 0.4)"}} "100M LIQUID" [:br] "REORG ATTACK"]
     [:p {:style {:fontSize "16px" :marginTop "24px" :color "#7ADDDC" :fontWeight "700"}} 
      "Attacker attempts to force a fraudulent settlement by manipulating L1 block-finality."]])

   ;; Example 2: The Intercept
   (speds/v-frame
    {:header "[SEW_PROT] ADVERSARIAL_RUN: S26 | STATUS: INTERCEPTED"
     :footer-left "LATENCY: 0.1ms"
     :footer-right "OUTCOME: TERMINAL_REJECT"}
    [:div {:style {:position "relative" :height "100%" :display "flex" :flexDirection "column" :justifyContent "center"}}
     (speds/v-res "Invariant G04")
     [:div {:style {:marginTop "20px"}}
      (speds/v-inv :solvency :ok)]
     [:h2 {:style {:fontSize "52px" :fontWeight "900" :lineHeight "0.9" :textTransform "uppercase" :color "#03DAC6" :marginTop "20px" :textShadow speds/teal-shadow}} "ATTACK" [:br] "DEFLECTED"]])
   ]])
