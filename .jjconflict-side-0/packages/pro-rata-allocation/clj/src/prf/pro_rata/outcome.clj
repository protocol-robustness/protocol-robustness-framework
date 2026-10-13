(ns prf.pro-rata.outcome
  "Canonical policy outcome derived from a verified pro-rata result."
  (:require [prf.canonical.hash :as canonical-hash]
            [prf.canonical.codec :as canonical]
            [prf.pro-rata.api :as allocation]
            [prf.pro-rata.identity :as identity]))

(def ^:const schema-version "pro-rata-policy-outcome.v1")
(def ^:const outcome-domain "PRO_RATA_POLICY_OUTCOME_V1")

(defn policy-outcome-preimage [outcome]
  (select-keys outcome
               [:schema/version :mechanism/id :mechanism/version
                :request/root :policy/root :allocation-result/root
                :outcome/status :rounding/outcome :violations]))

(defn- rounding-outcome [result]
  (if (= :largest-remainder (:rounding-policy result))
    {:result :exercised :violations []}
    {:result :not-exercised
     :reason :rounding-policy-not-largest-remainder
     :violations []}))

(defn build-policy-outcome
  "Build the frozen policy-outcome.v1 artifact from a verified allocation result."
  [result]
  (allocation/verify-result (:canonical-request result) result)
  (let [outcome {:schema/version schema-version
                 :mechanism/id (get-in result [:mechanism :id])
                 :mechanism/version (get-in result [:mechanism :version])
                 :request/root (:request/hash result)
                 :policy/root (identity/allocation-policy-root result)
                 :allocation-result/root (:allocation/hash result)
                 :outcome/status :passed
                 :rounding/outcome (rounding-outcome result)
                 :violations []}]
    (assoc outcome :outcome/root
           (canonical-hash/hash-with-domain outcome-domain
                                            (policy-outcome-preimage outcome)))))

(defn validate-policy-outcome!
  "Validate the frozen outcome shape and its canonical root."
  [outcome]
  (canonical/validate-canonical-value! outcome)
  (when-not (= schema-version (:schema/version outcome))
    (throw (ex-info "Unsupported pro-rata policy outcome schema"
                    {:reason :binding-invalid
                     :schema/version (:schema/version outcome)})))
  (when-not (and (= :passed (:outcome/status outcome))
                 (vector? (:violations outcome))
                 (empty? (:violations outcome)))
    (throw (ex-info "Invalid pro-rata policy outcome status"
                    {:reason :binding-invalid})))
  (let [expected (canonical-hash/hash-with-domain outcome-domain
                                                  (policy-outcome-preimage outcome))]
    (when-not (= expected (:outcome/root outcome))
      (throw (ex-info "Pro-rata policy outcome root mismatch"
                      {:reason :binding-invalid
                       :expected expected
                       :actual (:outcome/root outcome)}))))
  outcome)
