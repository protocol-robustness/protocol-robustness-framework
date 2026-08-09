;; # <Scenario / Analysis Title>
;;
;; **Audience:** <protocol reviewers, security researchers, contributors>
;;
;; **Purpose:** <what this notebook demonstrates — one line>
;;
;; **Data contract:**
;; - `<artifact-path>` — <what it provides>
;; - `<artifact-path>` — <what it provides>

^{:nextjournal.clerk/toc true
  :nextjournal.clerk/dark-mode true
  :nextjournal.clerk/visibility {:code :fold}}
(ns notebooks._template
  (:require [nextjournal.clerk :as clerk]
            [resolver-sim.notebook-support.views :as views]
            [resolver-sim.notebook-support.checks :as checks]
            [resolver-sim.notebook-support.speds.data :as speds-data]))

;; ## Scenario and temporal context
;;
;; <inputs, assumptions, parameters>

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def run
  (-> (speds-data/load-summary)
      checks/assert-test-summary!))

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(views/render-card
 {:label "Scenario"
  :rag   :green
  :value (:run_id run)
  :note  "Run ID from test-summary artifact"})

;; ## Evidence-backed result
;;
;; <key result — small table, chart, or card>

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(defn conservation-holds?
  "Invariant: requested = debited + unmet + waived"
  [{:keys [requested debited unmet waived]}]
  (= requested (+ debited unmet waived)))

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/table
 {:head ["Metric" "Value"]
  :rows [["Status" (:overall_status run)]
         ["Run ID" (:run_id run)]]})

;; ## Invariant checks
;;
;; <pass/fail table>

;; ## Evidence path
;;
;; <trace rows, artifact hashes, registry links>

;; ## Failure / edge cases
;;
;; <what would invalidate the result>

;; ## Reproduce
;;
;; ```shell
;; <command to reproduce>
;; ```
;; - Run ID: <run-id>
;; - Input artifacts: <paths>
;; - Expected output: <summary>
