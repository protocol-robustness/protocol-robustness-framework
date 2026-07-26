^{:nextjournal.clerk/dark-mode true}
(ns notebooks.theory-falsification-artifact
  (:require [nextjournal.clerk :as clerk]
            [resolver-sim.notebook-support.speds.data :as data]
            [resolver-sim.notebook-support.speds.story :as story]))

;; # Sew Protocol — Theory Falsification Visual
;; ## Multi-Frame Evidence Arc for Expected-Negative Scenarios

^{:nextjournal.clerk/visibility {:code :hide :result :show}
  :nextjournal.clerk/width :full}
(clerk/html
 (let [artifacts (data/load-run-artifacts)]
   [:div {:style {:background "#020617" :padding "40px" :minHeight "100vh"}}
    (story/generate-theory-falsification-story artifacts)]))
