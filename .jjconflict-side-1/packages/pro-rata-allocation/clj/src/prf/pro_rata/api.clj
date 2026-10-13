(ns prf.pro-rata.api
  "Package-owned allocation flow with runtime and hashing supplied by adapters."
  (:require [prf.pro-rata.request :as request]
            [prf.pro-rata.result :as result]
            [prf.pro-rata.runtime :as runtime]
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
  "Run allocation with package defaults or explicit host runtime adapters.

   One argument uses the package serial runtime and frozen PRF-compatible hash
   identities. It accepts callback progress observers; a progress atom requires
   the explicit host adapter arity. The two-argument form accepts
   `:execute-allocation`, `:hash-request`, and `:hash-result` adapters."
  ([public-request]
   (allocate public-request (runtime/serial-adapters public-request)))
  ([public-request {:keys [execute-allocation hash-request hash-result] :as adapters}]
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
         result-base (result/assemble normalized request-hash allocation allocation-witness)]
     (assoc result-base :allocation/hash (hash-result (result/root-input result-base))))))

(defn verify-result
  "Validate a supplied result against the deterministic allocation for request.

   Returns true for an exact semantic result, including its root and witness.
   Throws `ExceptionInfo` with `:portable-contract/projection-mismatch` for any
   result that differs from the derived result. The diagnostic fractional
   remainder total is excluded from verification, as specified by V1."
  [public-request supplied-result]
  (let [expected-result (allocate public-request)
        root-excluded #{:allocation/fractional-remainder-total}]
    (when-not (= (apply dissoc expected-result root-excluded)
                 (apply dissoc supplied-result root-excluded))
      (throw (ex-info "Invalid portable pro-rata allocation result"
                      {:reason :portable-contract/projection-mismatch})))
    true))
