(ns prf.pro-rata.request
  "Pure validation and canonical request normalization for pro-rata allocation."
  (:require [prf.canonical.codec :as canonical]
            [prf.pro-rata.rows :as rows]))

(def ^:const mechanism-version 1)

(defn- invalid! [reason data]
  (throw (ex-info "Invalid pro-rata allocation request"
                  (assoc data :reason reason))))

(defn- non-negative-integer? [value]
  (and (integer? value) (not (neg? value))))

(defn normalize
  "Validate a public allocation request and return normalized semantic inputs.

   Operational execution options are intentionally omitted from the returned
   canonical request and remain the responsibility of the runtime adapter."
  [request]
  (let [request (or request {})
        allocation-id (:allocation/id request)
        schema-version (get request :schema-version "pro-rata-allocation-request.v1")
        req-mechanism-version (get request :mechanism/version mechanism-version)
        available (:available request)
        available (when (integer? available) (bigint available))
        rounding-policy (if (nil? (:rounding-policy request))
                          :largest-remainder
                          (:rounding-policy request))
        tie-break-policy (if (nil? (:tie-break-policy request))
                           :canonical-row-id
                           (:tie-break-policy request))
        redistribution-policy (if (nil? (:redistribution-policy request))
                                :unallocated
                                (:redistribution-policy request))]
    (when-not (= "pro-rata-allocation-request.v1" schema-version)
      (invalid! :unsupported-allocation-request-schema {:schema-version schema-version}))
    (when-not (= mechanism-version req-mechanism-version)
      (invalid! :unsupported-allocation-mechanism-version
                {:mechanism/version req-mechanism-version}))
    (when-not allocation-id
      (invalid! :missing-allocation-id {}))
    (try
      (canonical/validate-canonical-value! allocation-id)
      (catch clojure.lang.ExceptionInfo error
        (invalid! :unsupported-allocation-id
                  {:allocation/id allocation-id :cause (ex-data error)})))
    (when-not (non-negative-integer? available)
      (invalid! :invalid-available {:available available}))
    (when-not (= :canonical-row-id tie-break-policy)
      (invalid! :unsupported-tie-break-policy {:tie-break-policy tie-break-policy}))
    (when-not (#{:unallocated :redistribute-cap-excess} redistribution-policy)
      (invalid! :unsupported-redistribution-policy
                {:redistribution-policy redistribution-policy}))
    (let [canonical-rows (rows/canonical-rows (:rows request))
          canonical-request-rows (mapv (fn [row]
                                         {:row/id (:row/id row)
                                          :obligation/id (:obligation/id row)
                                          :requested (:requested row)
                                          :weight (:weight row)
                                          :cap (:declared-cap row)})
                                       canonical-rows)
          canonical-request {:schema-version schema-version
                             :mechanism/version req-mechanism-version
                             :allocation/id allocation-id
                             :available available
                             :rows canonical-request-rows
                             :rounding-policy rounding-policy
                             :tie-break-policy tie-break-policy
                             :redistribution-policy redistribution-policy}]
      {:allocation/id allocation-id
       :schema-version schema-version
       :mechanism/version req-mechanism-version
       :available available
       :rows canonical-rows
       :rounding-policy rounding-policy
       :tie-break-policy tie-break-policy
       :redistribution-policy redistribution-policy
       :canonical-request canonical-request})))
