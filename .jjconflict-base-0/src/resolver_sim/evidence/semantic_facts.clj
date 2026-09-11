(ns resolver-sim.evidence.semantic-facts
  "Normalized semantic-facts export layer for future coverage/search/scoring use.
   Data-first and additive: emits facts from existing artifacts without changing
   current artifact contracts."
  (:require [clojure.data.json :as json]
            [resolver-sim.definitions.registry :as defs]
            [resolver-sim.evidence.config :as evcfg]
            [resolver-sim.notebook-support.common :as common]
            [resolver-sim.scenario.outcome-semantics :as ose]))

(def semantic-facts-path (evcfg/artifact-path :semantic-facts))

(defn- ->purpose-id [purpose]
  (keyword "scenario.purpose" (name (ose/normalize-purpose purpose))))

(defn- ->failure-id [tag]
  (keyword "scenario.failure" (name tag)))

(defn- ->transition-id [tr]
  (if (defs/transition-def tr)
    (keyword "scenario.transition" (name tr))
    (keyword "scenario.transition" "unknown")))

(defn scenario-facts
  "Emit scenario-level normalized facts from a single scenario map."
  [scenario]
  (let [sid (:id scenario)
        purpose (:purpose scenario)
        tags (or (:threat-tags scenario) [])
        transitions (or (:transitions scenario) [])]
    (vec
     (concat
      [{:fact/type :scenario/uses-purpose
        :scenario/id sid
        :purpose/id (->purpose-id purpose)}]
      (for [tag tags]
        {:fact/type :scenario/exercises-failure
         :scenario/id sid
         :failure/id (->failure-id tag)})
      (for [tr transitions]
        {:fact/type :scenario/exercises-transition
         :scenario/id sid
         :transition/id (->transition-id tr)})))))

(defn trial-facts
  "Emit trial-level normalized facts from findings bundle and trial id.
   Uses low-risk derivations available in current findings artifacts."
  [trial-id findings]
  (vec
   (mapcat
    (fn [f]
      (let [scenario-id (:scenario_id f)
            severity (keyword "severity" (name (keyword (str (:severity f)))))]
        [{:fact/type :trial/covers-scenario
          :trial/id trial-id
          :scenario/id scenario-id}
         {:fact/type :trial/classifies-scenario
          :trial/id trial-id
          :scenario/id scenario-id
          :status/id (keyword "scenario.status" (str (:status_kind f)))}
         {:fact/type :trial/assigns-severity
          :trial/id trial-id
          :scenario/id scenario-id
          :severity/id severity}]))
    (or findings []))))

(defn- checked-invariants [scenario]
  (or (get-in scenario [:expectations :invariants])
      (keys (or (:invariant-results scenario) {}))
      []))

(defn- invariant-result-facts [trial-id scenario]
  (let [sid (or (:id scenario) "unknown-scenario")
        expected (checked-invariants scenario)
        observed (or (:invariant-results scenario) {})]
    (vec
     (concat
      (for [inv expected
            :let [invk (if (keyword? inv) inv (keyword (str inv)))]]
        {:fact/type :trial/checks-invariant
         :trial/id trial-id
         :scenario/id sid
         :invariant/id invk})
      (for [[inv status] observed]
        {:fact/type :trial/invariant-result
         :trial/id trial-id
         :scenario/id sid
         :invariant/id inv
         :result/id (keyword "invariant.result" (name status))
         :severity/id (or (get-in (defs/invariant-def inv) [:default-severity]) :low)})))))

(defn artifacts->semantic-facts
  "Build full semantic-facts bundle from run artifacts and findings bundle.
   Expected inputs:
   - artifacts with [:coverage :scenarios]
   - findings-bundle with [:run :run_id] and [:findings]"
  [artifacts findings-bundle]
  (let [scenarios (or (get-in artifacts [:coverage :scenarios]) [])
        run-id (get-in findings-bundle [:run :run_id] "unknown-run")
        findings (or (:findings findings-bundle) [])
        facts (vec (concat (mapcat scenario-facts scenarios)
                           (trial-facts run-id findings)
                           (mapcat #(invariant-result-facts run-id %) scenarios)))]
    {:schema/version (evcfg/schema :semantic-facts)
     :run/id run-id
     :definitions/hash (defs/definitions-hash)
     :fact-count (count facts)
     :facts facts}))

(defn save-semantic-facts!
  [facts-bundle]
  (.mkdirs (java.io.File. (evcfg/artifact-dir)))
  (spit semantic-facts-path (json/write-str facts-bundle))
  facts-bundle)

(defn load-semantic-facts []
  (common/read-json semantic-facts-path))
