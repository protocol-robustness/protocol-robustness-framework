(ns resolver-sim.pro-rata.policy-outcome
  "Derived policy-outcome consequence for a validated domain-neutral pro-rata result.

   This namespace never allocates, consumes authority, or validates application
   context. It reuses the primitive witness validator and records the policy
   facts established for one exact allocation result."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.pro-rata.allocation :as allocation]
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
  (let [outcome {:schema/version schema-version
                 :mechanism/id (get-in result [:mechanism :id])
                 :mechanism/version (get-in result [:mechanism :version])
                 :request/root (:request/hash result)
                 :policy/root (allocation/allocation-policy-root result)
                 :allocation-result/root (:allocation/hash result)
                 :outcome/status :passed
                 :rounding/outcome (invariants/remainder-assignment-status result)
                 :violations []}]
    (assoc outcome :outcome/root
           (hc/hash-with-intent {:hash/intent :pro-rata-policy-outcome}
                                (policy-outcome-preimage outcome)))))

(defn validate-pro-rata-policy-outcome-v1!
  "Validate the canonical root and internal shape of a policy-outcome consequence.
   Primitive result validation remains the responsibility of construction."
  [outcome]
  (hc/validate-canonical-value! outcome)
  (when-not (= schema-version (:schema/version outcome))
    (throw (ex-info "Unsupported pro-rata policy outcome schema"
                    {:reason :binding-invalid
                     :schema/version (:schema/version outcome)})))
  (when-not (and (= :passed (:outcome/status outcome))
                 (vector? (:violations outcome))
                 (empty? (:violations outcome)))
    (throw (ex-info "Invalid pro-rata policy outcome status"
                    {:reason :binding-invalid})))
  (let [expected (hc/hash-with-intent {:hash/intent :pro-rata-policy-outcome}
                                      (policy-outcome-preimage outcome))]
    (when-not (= expected (:outcome/root outcome))
      (throw (ex-info "Pro-rata policy outcome root mismatch"
                      {:reason :binding-invalid
                       :expected expected
                       :actual (:outcome/root outcome)}))))
  outcome)
