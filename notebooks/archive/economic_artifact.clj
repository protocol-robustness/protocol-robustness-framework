^{:nextjournal.clerk/dark-mode true}
(ns notebooks.economic-artifact
  (:require [nextjournal.clerk :as clerk]
            [resolver-sim.notebook-support.speds.data :as data]
            [resolver-sim.notebook-support.speds.story :as story]))

;; # Sew Protocol — Technical Validation Story
;; ## Economic Robustness & Yield Conservation Surface

^{:nextjournal.clerk/visibility {:code :hide :result :show}
  :nextjournal.clerk/width :full}
(clerk/html
 (let [artifacts (data/load-run-artifacts)
       scenario-id "scenarios/s09-multi-escrow-solvency"]
   [:div {:style {:background "#000" :padding "40px" :minHeight "100vh"}}
    (story/generate-story-by-family scenario-id artifacts)]))
