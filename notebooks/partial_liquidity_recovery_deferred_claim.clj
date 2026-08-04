^{:nextjournal.clerk/visibility {:code :show :result :show}
  :nextjournal.clerk/width :full
  :nextjournal.clerk/dark-mode true}
(ns notebooks.partial-liquidity-recovery-deferred-claim
  "Partial Liquidity Recovery and Deferred-Claim Settlement — a step-by-step demonstration.

   Illustrative model using the real partial-fill engine
   (resolver-sim.yield.partial-fill/calculate-fulfillment).

   Targeted at technically curious readers who want to understand
   how a yield module can recover partial liquidity and settle
   previously deferred withdrawal claims."
  (:require [nextjournal.clerk :as clerk]
             [notebooks.util.yield-demo :as demo]
             [resolver-sim.notebook-support.common :as common]))

(defn- kv-table
  [m]
  {:head [:key :value]
   :rows (mapv (fn [[k v]] [k (str v)]) m)
   :row-keys [:key :value]})

;; # Partial Liquidity Recovery and Deferred-Claim Settlement
;;
;; ## What happens when a yield module gets some liquidity back?
;;
;; A **liquidity shortfall** reserves a portion of deposited funds. Users who
;; withdraw during a shortfall receive only partial fills; the rest is deferred.
;;
;; This notebook extends the story: after an initial shortfall and withdrawal
;; wave, the module recovers from 40% to 90% liquidity. Users who were
;; shortfall-affected can now withdraw more of their remaining principal —
;; though still not all, since liquidity is not fully restored.
;;
;; **Key concepts:**
;; - **Partial recovery:** liquidity returns but not to 100%
;; - **Deferred claims:** previously unfilled amounts become claimable again
;; - **Wave 2:** a second round of withdrawals against the expanded pool

;; ## 1. Scenario Setup
;;
;; Same initial deposits and first shortfall as before, then liquidity
;; recovers to 90% and users attempt second withdrawals.

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(def scenario-events
  [{:event :deposit :user :alice :amount 1000}
   {:event :deposit :user :bob   :amount 750}
   {:event :deposit :user :cara  :amount 500}
   ;; Wave 1: shortfall at 40%
   {:event :set-liquidity-shortfall :available-ratio 0.40}
   {:event :withdraw-request :user :alice :requested 1000}
   {:event :withdraw-request :user :bob   :requested 500}
   {:event :withdraw-request :user :cara  :requested 500}
   ;; Liquidity recovers to 90% — previously reserved funds become accessible
   {:event :set-liquidity-shortfall :available-ratio 0.90}
   ;; Wave 2: users claim more of their remaining principal
   {:event :withdraw-request :user :alice :requested 600}
   {:event :withdraw-request :user :bob   :requested 450}
   {:event :withdraw-request :user :cara  :requested 300}])

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(def result (demo/run-scenario scenario-events))

(defn- outcome-by [user wave]
  (first (filter #(and (= (:user %) user) (= wave (:wave %))) (:outcomes result))))

;; ### Scenario summary

(clerk/table
  {:head [:field :value]
   :rows [["Token" "USDC"]
          ["Yield module" "gen-yield (liquid-lending profile)"]
          ["Initial depositors" "alice, bob, cara"]
          ["Total deposited" "2,250 USDC"]
          ["Wave 1 available ratio" "40%"]
          ["Wave 2 available ratio" "90%"]
          ["Withdrawal policy" "waterfall (principal → realized-yield → deferred-yield)"]
          ["Model type" "illustrative (uses real calculate-fulfillment)"]]
   :row-keys [:field :value]})

;; ## 2. Event Timeline

(clerk/table
  (demo/event-timeline scenario-events))

;; ## 3. Walkthrough: What Happens at Each Step

;; ### T0–T2: Normal Starting State

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
  [:div {:style {:background "#0f172a" :border "1px solid #004D59"
                 :borderRadius "8px" :padding "16px" :marginTop "12px"}}
   [:h3 {:style {:color "#f8fafc" :marginTop 0}} "Module state before shortfall"]
   [:p {:style {:color "#cbd5e1" :fontSize "14px"}}
    "The module holds deposits from three users. All liquidity is available. "
    "Total principal: " [:strong {:style {:color "#7ADDDC"}} "2,250 USDC"] ". "
    "Available liquidity: " [:strong {:style {:color "#7ADDDC"}} "2,250 USDC"] ". "
    "No shortfall. No deferred amounts."]
   [:div {:style {:color "white" :backgroundColor "#020617" :padding "10px"
                  :borderRadius "4px"}}
    (clerk/table
      {:head [:user :position :status]
       :rows [["alice" "1,000 USDC" "active"]
              ["bob" "750 USDC" "active"]
              ["cara" "500 USDC" "active"]
              ["Total" "2,250 USDC" "active"]]
       :row-keys [:user :position :status]})]])

;; ### T3: First Shortfall — 40% Liquid

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(let [snap (nth (:snapshots result) 4)]
  (clerk/html
    [:div {:style {:background "#0f172a" :border "1px solid #ef4444"
                   :borderRadius "8px" :padding "16px" :marginTop "16px"}}
     [:h3 {:style {:color "#f8fafc" :marginTop 0}} "🔻 Liquidity drops to 40%"]
     [:p {:style {:color "#cbd5e1" :fontSize "14px"}}
      "The module's available liquidity drops to " [:strong {:style {:color "#f87171"}} "40%"]
      " of total value — 1,350 USDC becomes reserved."]
     [:div {:style {:marginTop "12px" :padding "8px 12px"
                    :background "#1f2937" :borderLeft "3px solid #f59e0b"
                    :borderRadius "4px" :fontSize "13px" :color "#fbbf24"}}
      "What changed? Pool goes from 2,250 to "
      (:available-liquidity snap) " USDC. Shortfall active."]]))

;; ### T4–T6: First Withdrawal Wave

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(let [snap (nth (:snapshots result) 7)]
  (clerk/html
    [:div {:style {:background "#0f172a" :border "1px solid #f59e0b"
                   :borderRadius "8px" :padding "16px" :marginTop "16px"}}
     [:h3 {:style {:color "#f8fafc" :marginTop 0}} "Wave 1 — Alice, Bob, Cara withdraw"]
     [:p {:style {:color "#cbd5e1" :fontSize "14px"}}
      "Three withdrawals consume the 900 USDC pool. "
      "All three receive partial fills; their positions enter unwinding state."]
     (clerk/table (demo/withdrawal-outcomes (:outcomes result) [:alice :bob :cara]))
     [:div {:style {:marginTop "12px" :padding "8px 12px"
                    :background "#1f2937" :borderLeft "3px solid #f59e0b"
                    :borderRadius "4px" :fontSize "13px" :color "#fbbf24"}}
      "What changed? Pool fully consumed. "
      "Fulfilled: " (:fulfilled-total snap) " USDC. Deferred: " (:deferred-total snap) " USDC. "
      "All positions in unwinding state. Module holds only reserved funds."]]))

;; ### T7: Liquidity Recovers to 90%

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(let [before (nth (:snapshots result) 7)
      after (nth (:snapshots result) 8)]
  (clerk/html
    [:div {:style {:background "#0f172a" :border "1px solid #22c55e"
                   :borderRadius "8px" :padding "16px" :marginTop "16px"}}
     [:h3 {:style {:color "#f8fafc" :marginTop 0}} "🔶 Liquidity recovers to 90%"]
     [:p {:style {:color "#cbd5e1" :fontSize "14px"}}
      "The module's liquidity recovers from 40% to "
      [:strong {:style {:color "#22c55e"}} "90%"] ". "
      "Funds that were previously reserved become accessible. "
      "Unwinding positions are reactivated so users can claim more."]
     [:div {:style {:display "flex" :gap "20px" :marginTop "12px"}}
      [:div {:style {:flex 1 :background "#111827" :border "1px solid #f59e0b"
                     :borderRadius "6px" :padding "12px"}}
       [:div {:style {:fontSize "11px" :color "#94a3b8" :textTransform "uppercase"}} "Before recovery"]
       [:div {:style {:fontSize "24px" :fontWeight 800 :color "#f87171"}} (:available-liquidity before) " USDC"]
       [:div {:style {:fontSize "12px" :color "#cbd5e1"}} "pool depleted"]]
      [:div {:style {:flex 1 :background "#111827" :border "1px solid #22c55e"
                     :borderRadius "6px" :padding "12px"}}
       [:div {:style {:fontSize "11px" :color "#94a3b8" :textTransform "uppercase"}} "After recovery"]
       [:div {:style {:fontSize "24px" :fontWeight 800 :color "#22c55e"}} (:available-liquidity after) " USDC"]
       [:div {:style {:fontSize "12px" :color "#cbd5e1"}} "newly available"]]]
     [:div {:style {:marginTop "12px" :padding "8px 12px"
                    :background "#1f2937" :borderLeft "3px solid #22c55e"
                    :borderRadius "4px" :fontSize "13px" :color "#86efac"}}
      "What changed? Available liquidity restored to "
      (:available-liquidity after) " USDC (90% of total, minus already-fulfilled amounts). "
      "Positions re-activated. Deferred claims can now be retried."]]))

;; ### T8–T10: Second Withdrawal Wave

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(let [snap (last (:snapshots result))
      wave2 (filter #(= 2 (:wave %)) (:outcomes result))]
  (clerk/html
    [:div {:style {:background "#0f172a" :border "1px solid #22c55e"
                   :borderRadius "8px" :padding "16px" :marginTop "16px"}}
     [:h3 {:style {:color "#f8fafc" :marginTop 0}} "Wave 2 — Users claim remaining principal"]
     [:p {:style {:color "#cbd5e1" :fontSize "14px"}}
      "With 1,125 USDC newly available, users attempt to withdraw their remaining "
      "principal. They still receive partial fills — the pool is not fully restored to 100%."]
     [:div {:style {:marginTop "8px"}}
      (clerk/table (demo/withdrawal-outcomes wave2))]
     [:div {:style {:marginTop "12px" :padding "8px 12px"
                    :background "#1f2937" :borderLeft "3px solid #22c55e"
                    :borderRadius "4px" :fontSize "13px" :color "#86efac"}}
      "What changed? Total fulfilled rises to "
      (:fulfilled-total snap) " USDC. Remaining deferred: "
      (:deferred-total snap) " USDC. "
      "Some funds are still unresolved — recovery was partial, not full."]]))

;; ## 4. Module State Over Time

(clerk/table
  (demo/module-snapshots (:snapshots result)))

;; ## 5. Per-User Withdrawal Outcomes — Combined Across Both Waves

(clerk/table
  (let [wave1 (filter #(= 1 (:wave %)) (:outcomes result))
        wave2 (filter #(= 2 (:wave %)) (:outcomes result))
        t1 (demo/withdrawal-outcomes wave1)
        t2 (demo/withdrawal-outcomes wave2)]
    {:head [:wave :user :requested :fulfilled :fill-pct :deferred :status :shortfall-affected]
     :rows (into (mapv #(into ["Wave 1"] %) (:rows t1))
                 (mapv #(into ["Wave 2"] %) (:rows t2)))
     :row-keys [:wave :user :requested :fulfilled :fill-pct :deferred :status :shortfall-affected]}))

;; ### Who is affected after both waves?

;;
;; After liquidity recovery to 90%, each user tries again. The totals
;; depend on how much pool remained after their wave-1 share was taken.
;;
;; **Alice:** First wave 400, second wave — remaining depends on pool share.
;;
;; **Bob:** First wave 300, second wave — remaining depends on pool share.
;;
;; **Cara:** First wave 200, second wave — remaining depends on pool share.
;;
;; **Key insight:** Partial liquidity recovery enables partial deferred-claim
;; settlement. Deferred amounts shrink but are not eliminated unless liquidity
;; returns to 100%. The module tracks each user's residual claim.
;;
;; **Key insight:** Partial liquidity recovery enables partial deferred-claim
;; settlement. Deferred amounts shrink but are not eliminated unless liquidity
;; returns to 100%. The module tracks each user's residual claim.

;; ## 6. Final Shortfall Summary

(clerk/table
  (let [s (demo/shortfall-summary result)]
    {:head [:key :value]
     :rows (mapv (fn [[k v]] [k v]) s)
     :row-keys [:key :value]}))

;; ## 7. Raw EDN Appendix

(clerk/html
  [:details {:style {:background "#0f172a" :padding "12px" :borderRadius "6px" :marginTop "10px"
                     :border "1px solid #004D59"}}
   [:summary {:style {:cursor "pointer" :fontWeight "bold" :color "#7ADDDC"}}
    "Per-user settlement decisions (both waves)"]
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
