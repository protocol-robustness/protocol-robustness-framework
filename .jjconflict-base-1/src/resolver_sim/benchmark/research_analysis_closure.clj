(ns resolver-sim.benchmark.research-analysis-closure
  "Pure verification of the researcher incentive-analysis evidence boundary.

   This module deliberately does not execute a benchmark. It validates the
   immutable inputs and existing outcome-manifest closure which a future shared
   run lifecycle must publish atomically."
  (:require [resolver-sim.benchmark.incentive-model :as model]
            [resolver-sim.benchmark.incentive-deviation-domain :as domain]
            [resolver-sim.benchmark.outcome-manifest :as outcome]
            [resolver-sim.benchmark.research-assignment :as assignment]
            [resolver-sim.benchmark.research-command :as command]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

(def ^:const schema-version "research-analysis-closure.v1")

(defn closure-root [closure]
  (hash-ref/sha256-ref
   (hc/domain-hash :research-analysis-closure
                   (dissoc closure :research-analysis/root :errors))))

(defn- output-roots [manifest]
  (select-keys manifest [:outcomes/incentive-root
                         :outcomes/incentive-compatibility-root
                         :outcomes/incentives-strategies-root
                         :outcomes/incentives-coalitions-root]))

(defn verify-closure
  "Derive a fail-closed closure report from concrete artifacts.

   `:evidence-class` is derived exclusively from the declared domain. In
   particular, :evidence/observed-single-trace never satisfies
   :claim/general-incentive-compatibility."
  [{:keys [research-command incentive-model deviation-domain research-assignment outcome-manifest]}]
  (let [errors (atom [])
        command-check (command/validate-command research-command)
        model-check (model/validate-model incentive-model)
        domain-check (domain/validate-domain deviation-domain)
        assignment-check (assignment/validate-assignment research-assignment)
        outcome-check (outcome/outcome-complete-for-command? research-command outcome-manifest)
        evidence-class (domain/evidence-class deviation-domain)]
    (when-not (:valid? command-check) (swap! errors into (map #(str "command: " %) (:errors command-check))))
    (when-not (:valid? model-check) (swap! errors into (map #(str "model: " %) (:errors model-check))))
    (when-not (:valid? domain-check) (swap! errors into (map #(str "domain: " %) (:errors domain-check))))
    (when-not (:valid? assignment-check) (swap! errors into (map #(str "assignment: " %) (:errors assignment-check))))
    (when-not (:complete? outcome-check) (swap! errors into (map #(str "outcome: " %) (:errors outcome-check))))
    (when-not (= (:incentive-model/root incentive-model)
                 (:deviation-domain/incentive-model-root deviation-domain))
      (swap! errors conj "domain does not bind the supplied incentive model"))
    (when-not (= (:incentive-model/subject-root incentive-model)
                 (:deviation-domain/subject-root deviation-domain))
      (swap! errors conj "domain subject does not bind the incentive model subject"))
    (when-not (= (:command/hash research-command)
                 (:research-assignment/command-root research-assignment))
      (swap! errors conj "assignment does not bind the research command"))
    (let [base {:schema-version schema-version
                :research-analysis/command-root (:command/hash research-command)
                :research-analysis/model-root (:incentive-model/root incentive-model)
                :research-analysis/deviation-domain-root (:deviation-domain/root deviation-domain)
                :research-analysis/assignment-root (:research-assignment/hash research-assignment)
                :research-analysis/outcome-root (:benchmark-outcome/hash outcome-manifest)
                :research-analysis/output-roots (output-roots outcome-manifest)
                :research-analysis/evidence-class evidence-class
                :research-analysis/general-ic-proven? false
                :research-analysis/status (if (empty? @errors) :valid :invalid)}]
      (assoc base :research-analysis/root (closure-root base)
             :errors (vec @errors)))))

(defn closure-valid? [closure]
  (and (= :valid (:research-analysis/status closure))
       (= (:research-analysis/root closure) (closure-root closure))))
