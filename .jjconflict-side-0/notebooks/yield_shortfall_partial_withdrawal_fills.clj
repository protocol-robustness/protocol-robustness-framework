^{:nextjournal.clerk/toc true
  :nextjournal.clerk/visibility {:code :fold :result :show}
  :nextjournal.clerk/width :full
  :nextjournal.clerk/dark-mode true}
(ns notebooks.yield-shortfall-partial-withdrawal-fills
  "Yield Shortfall with Partial Withdrawal Fills — a step-by-step demonstration.

   Illustrative model using the real partial-fill engine
   (resolver-sim.yield.partial-fill/calculate-fulfillment).

   Targeted at technically curious readers who want to understand
   how a yield module behaves when liquidity drops."
  (:require [nextjournal.clerk :as clerk]
             [notebooks.util.yield-demo :as demo]
             [resolver-sim.notebook-support.common :as common]))

(defn- kv-table
  "Convert a flat map (string keys) into a Clerk-compatible key-value table."
  [m]
  {:head [:key :value]
   :rows (mapv (fn [[k v]] [k (str v)]) m)
   :row-keys [:key :value]})

;; # Yield Module Liquidity Shortfall
;;
;; ## What happens when a yield module cannot return all deposited funds?
;;
;; A **liquidity shortfall** occurs when a yield-bearing module holds less
;; available liquidity than the total amount users have requested to withdraw.
;; This can happen because of market stress, delayed redemptions from
;; underlying protocols, or risk controls that freeze a portion of assets.
;;
;; In this demonstration, a yield module holds USDC deposits from three users.
;; The module enters a shortfall state where only 40% of its total value
;; remains liquid. Users then request withdrawals — but the module can only
;; partially fulfill them.
;;
;; **Key concepts:**
;; - **Fulfilled:** amount paid immediately from available liquidity
;; - **Deferred:** amount not paid now but tracked for later recovery
;; - **Shortfall-affected:** any withdrawal that cannot be fully fulfilled
;;
;; ---
;; **Label:** *Illustrative model using real partial-fill engine*
;; This notebook uses `resolver-sim.yield.partial-fill/calculate-fulfillment`
;; (real framework code) for per-user settlement math, but the pool
;; allocation logic is a custom proportional model for demonstration.

;; ## 1. Scenario Setup
;;
;; Three users deposit USDC into the same yield module.
;; After deposits, the module's liquidity drops to 40% of total value.

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(def scenario-events
  [{:event :deposit :user :alice :amount 1000}
   {:event :deposit :user :bob   :amount 750}
   {:event :deposit :user :cara  :amount 500}
   {:event :set-liquidity-shortfall :available-ratio 0.40}
   {:event :withdraw-request :user :alice :requested 1000}
   {:event :withdraw-request :user :bob   :requested 500}
   {:event :withdraw-request :user :cara  :requested 500}])

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(def result (demo/run-scenario scenario-events))

(defn- first-outcome [user]
  (first (filter #(= (:user %) user) (:outcomes result))))

;; ### Scenario summary

(clerk/table
  {:head [:field :value]
   :rows [["Token" "USDC"]
          ["Yield module" "gen-yield (liquid-lending profile)"]
          ["Initial depositors" "alice, bob, cara"]
          ["Total deposited" "2,250 USDC"]
           ["Available ratio (after shortfall)" "40%"]
           ["Withdrawal policy" "proportional share of pool (principal-only; this demo does not model yield)"]
           ["Shortfall model" "liquidity-only, recoverable, defer unfilled"]
          ["Model type" "illustrative (uses real calculate-fulfillment)"]]
   :row-keys [:field :value]})

;; ## 2. Event Timeline

(clerk/table
  (demo/event-timeline scenario-events))

;; ## 3. Walkthrough: What Happens at Each Step

;; ### T0–T2: Normal Starting State

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(let [s (first (:snapshots result))]
  (clerk/html
    [:div {:style {:background "#0f172a" :border "1px solid #004D59"
                   :borderRadius "8px" :padding "16px" :marginTop "12px"}}
     [:h3 {:style {:color "#f8fafc" :marginTop 0}} "Module state before shortfall"]
     [:p {:style {:color "#cbd5e1" :fontSize "14px"}}
      "The module holds deposits from three users. All liquidity is available. "
      "Total principal: " [:strong {:style {:color "#7ADDDC"}} "2,250 USDC"] ". "
      "Available liquidity: " [:strong {:style {:color "#7ADDDC"}} "2,250 USDC"] ". "
      "No shortfall. No deferred amounts. No reserved funds."]
     [:div {:style {:color "white" :backgroundColor "#020617" :padding "10px"
                    :borderRadius "4px"}}
      (clerk/table
        {:head [:user :position :status]
         :rows [["alice" "1,000 USDC" "active"]
                ["bob" "750 USDC" "active"]
                ["cara" "500 USDC" "active"]
                ["Total" "2,250 USDC" "active"]]
         :row-keys [:user :position :status]})]]))

;; ### T3: Liquidity Shortfall Applied

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
  [:div {:style {:background "#0f172a" :border "1px solid #ef4444"
                 :borderRadius "8px" :padding "16px" :marginTop "16px"}}
   [:h3 {:style {:color "#f8fafc" :marginTop 0}} "🔻 Liquidity shortfall hits"]
   [:p {:style {:color "#cbd5e1" :fontSize "14px"}}
    "The module's available liquidity drops to " [:strong {:style {:color "#f87171"}} "40%"]
    " of total value. This simulates a market stress event where "
    [:strong "1,350 USDC"] " becomes reserved/unavailable."]
   [:div {:style {:display "flex" :gap "20px" :marginTop "12px"}}
    [:div {:style {:flex 1 :background "#111827" :border "1px solid #004D59"
                   :borderRadius "6px" :padding "12px"}}
     [:div {:style {:fontSize "11px" :color "#94a3b8" :textTransform "uppercase"}} "Before"]
     [:div {:style {:fontSize "24px" :fontWeight 800 :color "#22c55e"}} "2,250 USDC"]
     [:div {:style {:fontSize "12px" :color "#cbd5e1"}} "fully liquid"]]
    [:div {:style {:flex 1 :background "#111827" :border "1px solid #ef4444"
                   :borderRadius "6px" :padding "12px"}}
     [:div {:style {:fontSize "11px" :color "#94a3b8" :textTransform "uppercase"}} "After"]
     [:div {:style {:fontSize "24px" :fontWeight 800 :color "#f87171"}} "900 USDC"]
     [:div {:style {:fontSize "12px" :color "#cbd5e1"}} "liquid · 1,350 USDC reserved"]]]
   [:div {:style {:marginTop "12px" :padding "8px 12px"
                  :background "#1f2937" :borderLeft "3px solid #f59e0b"
                  :borderRadius "4px" :fontSize "13px" :color "#fbbf24"}}
    "What changed? The module went from fully liquid to 40% liquid. "
    "1,350 USDC that was previously withdrawable is now reserved. "
    "The module marks this as a shortfall event."]])

;; ### T4: Alice Withdraws

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(let [before (nth (:snapshots result) 4)
      after (nth (:snapshots result) 5)
      outcome (first-outcome :alice)
      settlement (get-in result [:settlements :alice])]
  (clerk/html
    [:div {:style {:background "#0f172a" :border "1px solid #f59e0b"
                   :borderRadius "8px" :padding "16px" :marginTop "16px"}}
     [:h3 {:style {:color "#f8fafc" :marginTop 0}} "Alice requests 1,000 USDC"]
     [:p {:style {:color "#cbd5e1" :fontSize "14px"}}
      "Alice is first in line. The pool has "
      [:strong {:style {:color "#7ADDDC"}} "900 USDC"] " available. "
      "Alice holds 1,000 of 2,250 total principal (44.4%), so her proportional share of the 900 pool is 400. Alice receives "
      [:strong {:style {:color "#22c55e"}} (:filled outcome) " USDC"]
      " now, with "
      [:strong {:style {:color "#f59e0b"}} (:deferred outcome) " USDC"]
      " deferred."]
      [:div {:style {:background "#1f2937" :padding "10px" :borderRadius "4px" :marginTop "8px"}}
       (clerk/table
         (kv-table
           {"Requested" (str (:requested outcome) " USDC")
            "Filled (principal)" (str (get-in settlement [:filled :principal] 0) " USDC")
            "Deferred (principal)" (str (get-in settlement [:deferred :principal] 0) " USDC")
            "Settlement mode" (name (:mode settlement))
            "Fill %" (str (format "%.0f" (* 100 (:fill-pct outcome))) "%")}))]
     [:div {:style {:marginTop "12px" :padding "8px 12px"
                    :background "#1f2937" :borderLeft "3px solid #f59e0b"
                    :borderRadius "4px" :fontSize "13px" :color "#fbbf24"}}
      "What changed? Pool drops from "
      (:available-liquidity before) " to "
      (:available-liquidity after) " USDC. "
      "Alice gets a partial fill ("
      (str (format "%.0f" (* 100 (:fill-pct outcome))) "%")
      " of request). The unfilled portion is tracked as deferred."
      " Alice's position enters unwinding state."]]))

;; ### T5: Bob Withdraws

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(let [before (nth (:snapshots result) 5)
      after (nth (:snapshots result) 6)
      outcome (first-outcome :bob)
      settlement (get-in result [:settlements :bob])]
  (clerk/html
    [:div {:style {:background "#0f172a" :border "1px solid #f59e0b"
                   :borderRadius "8px" :padding "16px" :marginTop "16px"}}
     [:h3 {:style {:color "#f8fafc" :marginTop 0}} "Bob requests 500 USDC"]
     [:p {:style {:color "#cbd5e1" :fontSize "14px"}}
      "Bob is next. The pool has "
      [:strong {:style {:color "#7ADDDC"}} (:available-liquidity before) " USDC"]
      " remaining. Bob holds 750 of 1,250 remaining principal (60%), so his proportional share is 300. Bob receives "
      [:strong {:style {:color "#22c55e"}} (:filled outcome) " USDC"]
      " now, with "
      [:strong {:style {:color "#f59e0b"}} (:deferred outcome) " USDC"]
      " deferred."]
     [:div {:style {:background "#1f2937" :padding "10px" :borderRadius "4px" :marginTop "8px"}}
      (clerk/table
        (kv-table
          {"Requested" (str (:requested outcome) " USDC")
           "Filled (principal)" (str (get-in settlement [:filled :principal] 0) " USDC")
           "Deferred (principal)" (str (get-in settlement [:deferred :principal] 0) " USDC")
           "Settlement mode" (name (:mode settlement))
           "Fill %" (str (format "%.0f" (* 100 (:fill-pct outcome))) "%")}))]
    [:div {:style {:marginTop "12px" :padding "8px 12px"
                   :background "#1f2937" :borderLeft "3px solid #f59e0b"
                   :borderRadius "4px" :fontSize "13px" :color "#fbbf24"}}
     "What changed? Pool drops from "
     (:available-liquidity before) " to "
     (:available-liquidity after) " USDC. "
     "Total fulfilled so far: " (:fulfilled-total after) " USDC. "
     "Total deferred so far: " (:deferred-total after) " USDC. "
     "Two users affected by shortfall."]]))

;; ### T6: Cara Withdraws

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(let [before (nth (:snapshots result) 6)
      after (last (:snapshots result))
      outcome (first-outcome :cara)
      settlement (get-in result [:settlements :cara])]
  (clerk/html
    [:div {:style {:background "#0f172a" :border "1px solid #ef4444"
                   :borderRadius "8px" :padding "16px" :marginTop "16px"}}
     [:h3 {:style {:color "#f8fafc" :marginTop 0}} "Cara requests 500 USDC"]
     [:p {:style {:color "#cbd5e1" :fontSize "14px"}}
      "Cara is last. By now, the pool has been largely consumed. "
      "Cara holds all 500 of the remaining principal (100%), so her proportional share is the entire remaining pool of 200 (but she requested 500). She receives "
      [:strong {:style {:color "#22c55e"}} (:filled outcome) " USDC"]
      " and "
      [:strong {:style {:color "#f59e0b"}} (:deferred outcome) " USDC"]
      " is deferred."]
     [:div {:style {:background "#1f2937" :padding "10px" :borderRadius "4px" :marginTop "8px"}}
      (clerk/table
        (kv-table
          {"Requested" (str (:requested outcome) " USDC")
           "Filled (principal)" (str (get-in settlement [:filled :principal] 0) " USDC")
           "Deferred (principal)" (str (get-in settlement [:deferred :principal] 0) " USDC")
           "Settlement mode" (name (:mode settlement))
           "Fill %" (str (format "%.0f" (* 100 (:fill-pct outcome))) "%")}))]
    [:div {:style {:marginTop "12px" :padding "8px 12px"
                   :background "#1f2937" :borderLeft "3px solid #f59e0b"
                   :borderRadius "4px" :fontSize "13px" :color "#fbbf24"}}
     "What changed? Pool drops from "
     (:available-liquidity before) " to "
     (:available-liquidity after) " USDC — fully depleted. "
     "All three users are shortfall-affected. "
     "Total fulfilled: " (:fulfilled-total after) " USDC. "
     "Total deferred: " (:deferred-total after) " USDC. "
     "The module is in unwind state."]]))

;; ## 4. Module State Over Time

(clerk/table
  (demo/module-snapshots (:snapshots result)))

;; ## 5. Per-User Withdrawal Outcomes

(clerk/table
  (demo/withdrawal-outcomes (:outcomes result)))

;; ## 6. Final Shortfall Summary

(clerk/table
  (let [s (demo/shortfall-summary result)]
    {:head [:key :value]
     :rows (mapv (fn [[k v]] [k v]) s)
     :row-keys [:key :value]}))

;; ### Who is affected?
;;
;; - **Alice:** 1,000 USDC requested → 400 fulfilled, 600 deferred. Partial fill.
;;   As the first withdrawer, Alice receives 40% of her request from the still-full pool.
;;
;; - **Bob:** 500 USDC requested → 300 fulfilled, 200 deferred. Partial fill.
;;   Bob arrives second and receives 60% of his request — a higher fill rate
;;   because his share of the remaining pool was proportionally larger.
;;
;; - **Cara:** 500 USDC requested → 200 fulfilled, 300 deferred. Partial fill.
;;   Cara arrives last and receives 40% of her request from the diminished pool.
;;
;; **Key insight:** Each user receives a proportion of the available pool equal
;; to their share of the remaining principal at withdrawal time:
;;   `user-pool-share = pool × user-principal / remaining-total-principal`
;; Liquidity shortfall does not mean total loss — deferred amounts are tracked
;; on unwinding positions and can be recovered if liquidity returns.

;; ## 7. Raw EDN Appendix

;; ### What each settlement decision looks like

(clerk/html
  [:details {:style {:background "#0f172a" :padding "12px" :borderRadius "6px" :marginTop "10px"
                     :border "1px solid #004D59"}}
   [:summary {:style {:cursor "pointer" :fontWeight "bold" :color "#7ADDDC"}}
    "Per-user settlement decisions (from partial-fill engine)"]
   [:div {:style {:marginTop "12px"}}
    (clerk/code
       (common/safe-sorted-map
             (map (fn [[user dec]]
                   [user {:settlement-mode (:mode dec)
                          :requested (:requested dec)
                          :filled (:filled dec)
                          :deferred (:deferred dec)}]))
            (:settlements result)))]])

(clerk/html
  [:details {:style {:background "#0f172a" :padding "12px" :borderRadius "6px" :marginTop "10px"
                     :border "1px solid #004D59"}}
   [:summary {:style {:cursor "pointer" :fontWeight "bold" :color "#7ADDDC"}}
    "Full event log and state snapshots"]
   [:div {:style {:marginTop "12px"}}
    (clerk/code
      {:events scenario-events
       :snapshots (mapv (fn [s]
                          (update-keys s name))
                        (:snapshots result))
       :final-module-state (let [f (:final-state result)]
                             {:total-principal (:total-principal f)
                              :available-liquidity (:available-liquidity f)
                              :fulfilled-total (:fulfilled-total f)
                              :deferred-total (:deferred-total f)
                              :shortfall? (:shortfall? f)})})]])

(clerk/html
  [:details {:style {:background "#0f172a" :padding "12px" :borderRadius "6px" :marginTop "10px"
                     :border "1px solid #004D59"}}
   [:summary {:style {:cursor "pointer" :fontWeight "bold" :color "#7ADDDC"}}
    "Raw final world state"]
   [:div {:style {:marginTop "12px"}}
    (clerk/code
      (dissoc (:final-state result) :positions :events :snapshots))]])


