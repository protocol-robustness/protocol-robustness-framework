;; # Can a Verified Result Be Changed?
;;
;; One question. One change. One consequence.
;;
;; Everything below is computed by the real verifier — this page only narrates
;; and renders. The same computation powers the terminal walkthrough
;; (`bb demo:not-admitted`) and the automated assertion.

^{:nextjournal.clerk/visibility {:code :fold}
  :nextjournal.clerk/dark-mode true}
(ns notebooks.demo-not-admitted
  (:require [nextjournal.clerk :as clerk]
            [resolver-sim.demos.not-admitted.assertions :as assertions]
            [resolver-sim.demos.not-admitted.demo :as demo]
            [resolver-sim.notebook-support.demo-views :as demo-views]))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def result (demo/run))

;; ---

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html (demo-views/demo-surface result))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html (demo-views/technical-proof result))

;; ## Backed, not improvised
;;
;; The verdicts above are not hardcoded. `demo/run` builds the evidence from a
;; canonical ledger, runs the same closed-form verifier before and after the
;; change, and a deterministic assertion (`resolver-sim.demos.not-admitted.
;; assertions/check`) fails the build if the demonstration ever stops telling
;; the truth.

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 (let [{:keys [pass?]} (assertions/check)]
   [:div {:style {:background "#f8fafc" :border "1px solid #cbd5e1"
                  :borderRadius "8px" :padding "12px 16px"
                  :fontFamily "monospace" :fontSize "13px" :maxWidth "760px"}}
    "demonstration assertions: "
    [:strong {:style {:color (if pass? "#16a34a" "#dc2626")}}
     (if pass? "VERIFIED ✓" "VIOLATED ✕")]]))
