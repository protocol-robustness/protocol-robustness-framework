(ns prf.pro-rata.api
  "Package-owned allocation flow with runtime and hashing supplied by adapters."
  (:require [prf.pro-rata.request :as request]
            [prf.pro-rata.result :as result]
            [prf.pro-rata.witness :as witness]))

(defn- rounding-mode [policy]
  (case policy
    :largest-remainder :floor-with-largest-remainder
    :floor :floor
    (throw (ex-info "Invalid pro-rata allocation request"
                    {:reason :unsupported-rounding-policy
                     :rounding-policy policy}))))

(defn- require-adapter-function! [adapters key]
  (when-not (fn? (get adapters key))
    (throw (ex-info "Missing pro-rata runtime adapter function"
                    {:reason :missing-allocation-adapter :adapter key}))))

(defn allocate
  "Run the package-owned allocation flow through explicit runtime adapters.

   Adapters supply `:execute-allocation` (runtime execution), `:hash-request`
   (canonical request identity), and `:hash-result` (result identity). The
   semantic request, witness, and result shapes are owned here."
  [public-request {:keys [execute-allocation hash-request hash-result] :as adapters}]
  (doseq [key [:execute-allocation :hash-request :hash-result]]
    (require-adapter-function! adapters key))
  (let [normalized (request/normalize public-request)
        {:keys [available rows rounding-policy redistribution-policy canonical-request]}
        normalized
        request-hash (hash-request canonical-request)
        execution-request {:amount available
                           :items rows
                           :id-fn :row/id
                           :weight-fn :weight
                           :cap-fn :effective-cap
                           :rounding (rounding-mode rounding-policy)
                           :remainder-policy :unallocated
                           :ordering-policy :canonical-id
                           :on-progress (:on-progress public-request)
                           :progress-atom (:progress-atom public-request)
                           ;; Operational inputs are omitted from canonical-request.
                           :parallelism (:parallelism public-request)
                           :execution/quiescence-timeout-seconds
                           (:execution/quiescence-timeout-seconds public-request)}
        allocation (execute-allocation execution-request redistribution-policy)
        allocation-witness (witness/witness-rows available rows rounding-policy redistribution-policy)
        result-base (result/assemble normalized request-hash allocation allocation-witness)
        root-input (dissoc result-base :allocation/fractional-remainder-total)]
    (assoc result-base :allocation/hash (hash-result root-input))))
