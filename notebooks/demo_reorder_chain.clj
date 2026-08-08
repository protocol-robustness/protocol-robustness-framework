;; # Does the Same Evidence in a Different Order Mean the Same Thing?
;;
;; One question. One change. One consequence.
;;
;; The same three evidence items are verified in one order, then presented in
;; another order. The real chain verifier runs both times.
;;
;; The computation below is shared with the terminal walkthrough
;; (`bb demo:reorder-chain`) and the automated assertion.

^{:nextjournal.clerk/dark-mode true}
(ns notebooks.demo-reorder-chain
  (:require [nextjournal.clerk :as clerk]
            [resolver-sim.demos.reorder-chain.assertions :as assertions]
            [resolver-sim.demos.reorder-chain.demo :as demo]
            [resolver-sim.notebook-support.demo-views :as demo-views]))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def result (demo/run))

;; ---

(clerk/html (demo-views/demo-surface result))

(clerk/html (demo-views/technical-proof result))

;; ## Backed, not improvised
;;
;; The verdicts above come from the real chain verifier (`resolver-sim.evidence.
;; chain/verify-scenario-chain`), run identically before and after the reorder.
;; A deterministic assertion fails the build if the demonstration ever stops
;; telling the truth.

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 (let [{:keys [pass?]} (assertions/check)]
   [:div {:style {:background "#f8fafc" :border "1px solid #cbd5e1"
                  :borderRadius "8px" :padding "12px 16px"
                  :fontFamily "monospace" :fontSize "13px" :maxWidth "760px"}}
    "demonstration assertions: "
    [:strong {:style {:color (if pass? "#16a34a" "#dc2626")}}
     (if pass? "VERIFIED ✓" "VIOLATED ✕")]]))
