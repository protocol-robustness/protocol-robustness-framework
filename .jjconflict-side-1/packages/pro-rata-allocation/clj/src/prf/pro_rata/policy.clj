(ns prf.pro-rata.policy
  "Canonical pro-rata policy identity projection.")

(def schema-version "pro-rata-allocation-policy.v1")

(defn identity-basis
  "Project an authoritative allocation result to the fields committed by the
  canonical pro-rata policy identity."
  [result]
  (when-not (and (= "pro-rata-allocation-result.v1" (:schema-version result))
                 (map? (:mechanism result))
                 (contains? result :rounding-policy)
                 (contains? result :tie-break-policy)
                 (contains? result :redistribution-policy))
    (throw (ex-info "Cannot derive pro-rata policy root from allocation result"
                    {:reason :primitive-result-invalid})))
  {:schema/version schema-version
   :mechanism/id (get-in result [:mechanism :id])
   :mechanism/version (get-in result [:mechanism :version])
   :rounding-policy (:rounding-policy result)
   :tie-break-policy (:tie-break-policy result)
   :redistribution-policy (:redistribution-policy result)})
