(ns resolver-sim.pro-rata.policy-outcome
  "Derived policy-outcome consequence for a validated domain-neutral pro-rata result.

   This namespace never allocates, consumes authority, or validates application
   context. It reuses the primitive witness validator and records the policy
   facts established for one exact allocation result."
  (:require [prf.pro-rata.outcome :as package-outcome]
            [resolver-sim.pro-rata.invariants :as invariants]))

(def ^:const schema-version "pro-rata-policy-outcome.v1")

(defn- primitive-result! [result]
  (let [violations (invariants/result-violations result)]
    (when (seq violations)
      (throw (ex-info "Pro-rata primitive result is invalid"
                      {:reason :primitive-result-invalid
                       :violations violations})))
    result))

(defn policy-outcome-preimage [outcome]
  (select-keys outcome
               [:schema/version :mechanism/id :mechanism/version
                :request/root :policy/root :allocation-result/root
                :outcome/status :rounding/outcome :violations]))

(defn build-pro-rata-policy-outcome-v1
  "Construct a canonical consequence from one validated primitive result.

   Constituents are read-only: the allocation result and its policy are not
   consumed or granted present-tense application authority."
  [result]
  (primitive-result! result)
  (package-outcome/build-policy-outcome result))

(defn validate-pro-rata-policy-outcome-v1!
  "Validate the canonical root and internal shape of a policy-outcome consequence.
   Primitive result validation remains the responsibility of construction."
  [outcome]
  (package-outcome/validate-policy-outcome! outcome))
