(ns resolver-sim.notebook-support.speds.story-data
  "Data contract for SPEDS story engine — plain map, no record needed."
  (:require [resolver-sim.notebook-support.speds.data]))

(defn build-story-data
  "Builds a story context map with keys :trace-id, :git-sha, :hash,
   :title, :replay-match-label, :scenario, and optional :finding,
   :outcome, :invariant-results, :baseline-comparison."
  ([artifacts scenario-id] (build-story-data artifacts scenario-id nil))
  ([artifacts scenario-id finding]
   (let [ctx (resolver-sim.notebook-support.speds.data/generate-proof-context artifacts scenario-id)
         scenario (resolver-sim.notebook-support.speds.data/find-scenario-by-id (:coverage artifacts) scenario-id)
         {:keys [replay-match-label]} (resolver-sim.notebook-support.speds.data/narrative-metrics artifacts)]
     (merge ctx {:scenario scenario
                 :replay-match-label replay-match-label
                 :finding finding
                 :outcome (:outcome finding)
                 :invariant-results (:invariant-results scenario)
                 :baseline-comparison (get-in finding [:story_artifact_spec :baseline_comparison])}))))
