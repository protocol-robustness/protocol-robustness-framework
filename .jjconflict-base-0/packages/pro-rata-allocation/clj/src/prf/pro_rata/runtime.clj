(ns prf.pro-rata.runtime
  "Deterministic serial runtime adapters for direct package consumers."
  (:require [prf.pro-rata.identity :as allocation-identity]
            [prf.pro-rata.redistribution :as redistribution]
            [prf.pro-rata.single-pass :as single-pass]))

(defn serial-adapters
  "Build package-local adapters for a public allocation request.

   This runtime executes claimants serially. A progress callback is supported;
   adapting a progress atom requires a host reducer adapter and is rejected."
  [public-request]
  (when (and (:progress-atom public-request)
             (not (fn? (:on-progress public-request))))
    (throw (ex-info "A progress atom requires a host progress adapter"
                    {:reason :progress-atom-requires-runtime-adapter})))
  (let [runtime
        {:effective-claimant-parallelism
         (fn [requested _item-count _observer]
           (let [requested (long (or requested 1))]
             (when-not (pos? requested)
               (throw (ex-info "Pro-rata claimant parallelism must be positive"
                               {:parallelism requested})))
             1))
         :ordered-detached-mapv
         (fn [_parallelism _timeout-seconds f values]
           (mapv f values))
         :report-progress!
         (fn [observer event]
           (when (fn? observer)
             (try (observer event)
                  (catch Exception _ nil))))}]
    (assoc runtime
           :execute-allocation
           (fn [request policy]
             (case policy
               :unallocated (single-pass/allocate request runtime)
               :redistribute-cap-excess (redistribution/allocate request runtime)))
           :hash-request allocation-identity/request-root
           :hash-result allocation-identity/allocation-result-root)))
