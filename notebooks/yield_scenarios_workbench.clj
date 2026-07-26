^{:nextjournal.clerk/dark-mode true}
(ns notebooks.yield-scenarios-workbench
  "Demo workbench for yield-provider JSON scenarios (workbench v2 visuals)."
  (:require [nextjournal.clerk :as clerk]
            [resolver-sim.notebook-support.workbench-v2-styles :as wb]
            [resolver-sim.notebook-support.yield-scenarios :as ys]))

;; # Yield Provider Scenarios — Demo Workbench
;;
;; **Purpose:** Present Y01–Y06 at a glance for demos and reviews — no Sew escrow context required.
;;
;; Each card replays its JSON scenario live via `yield-v1` and shows the event spine + headline metrics.

^{:nextjournal.clerk/visibility {:code :hide :result :show}
  :nextjournal.clerk/no-cache true}
(def suite-summary (ys/run-provider-suite!))

^{:nextjournal.clerk/sync true
  :nextjournal.clerk/visibility {:code :hide :result :show}}
(defonce !selected-scenario-id
  (atom (get-in (first (:results suite-summary)) [:scenario :scenario-id])))

(defn- entry-by-id [summary sid]
  (some #(when (= sid (:scenario-id (:scenario %))) %) (:results summary)))

^{:nextjournal.clerk/visibility {:code :hide :result :show}
  :nextjournal.clerk/width :full}
(clerk/html
 (wb/workbench-shell
  [:<>
   (ys/gallery-hiccup suite-summary)

   [:div.grid-layout {:style {:margin-top "32px"}}
    [:div.card {:style {:grid-column "span 12"}}
     [:div.card-title "Step-by-step trace"]
     [:div {:style {:fontSize "12px" :color "#94a3b8" :marginBottom "16px"}}
      "Pick a scenario to show the full replay trace (Clerk-synchronized selection)."]
     [:div.select-row
      [:select {:value (str @!selected-scenario-id)
                :on-change #(reset! !selected-scenario-id (.. % -target -value))}
       (for [e (ys/sort-entries-demo-order (:results suite-summary))
             :let [sid (:scenario-id (:scenario e))
                   demo (:demo e)]]
         [:option {:key sid :value sid}
          (str (:code demo) " — " (:headline demo))])]]
     (ys/detail-panel-hiccup (entry-by-id suite-summary @!selected-scenario-id))]]]))
