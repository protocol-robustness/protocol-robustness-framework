(ns resolver-sim.research.parallel-executor
  "Research-only execution orchestration for frozen cell manifests.

   Executor configuration affects wall-clock experiment throughput only. It is
   intentionally not part of execution-cell or cell-result identity."
  (:require [resolver-sim.research.execution-cell-runner :as runner]
            [resolver-sim.research.execution-manifest :as manifest])
  (:import [java.util.concurrent Callable Executors TimeUnit]))

(def supported-executors #{:sequential :thread-pool :distributed})

(defn- validate-options! [executor options]
  (when-not (contains? supported-executors executor)
    (throw (ex-info "Unsupported research executor" {:executor executor})))
  (when-not (map? options)
    (throw (ex-info "Executor options must be a map" {:options options})))
  (let [allowed #{:max-concurrency}
        extra (seq (remove allowed (keys options)))
        concurrency (get options :max-concurrency)]
    (when extra
      (throw (ex-info "Executor options contain unsupported fields"
                      {:extra-fields (vec (sort-by str extra))})))
    (when (and concurrency (not (pos-int? concurrency)))
      (throw (ex-info "Executor max concurrency must be positive"
                      {:max-concurrency concurrency})))
    (when (and (= executor :sequential) concurrency (not= 1 concurrency))
      (throw (ex-info "Sequential executor has exactly one worker"
                      {:max-concurrency concurrency})))
    (when (and (= executor :distributed) concurrency)
      (throw (ex-info "Distributed simulation has canonical single-driver execution"
                      {:max-concurrency concurrency})))
    {:max-concurrency (or concurrency (if (#{:sequential :distributed} executor) 1 2))}))

(defn- run-one [scope execution-cell]
  (try
    (let [result (runner/run-cell scope execution-cell)]
      {:research-execution-cell/root (:research-execution-cell/root execution-cell)
       :execution/status :complete
       :research-execution-cell-result/root
       (:research-execution-cell-result/root result)
       :result result})
    (catch Throwable error
      ;; Error data is diagnostic runtime evidence only; it is deliberately
      ;; absent from the rooted cell result because no result was produced.
      {:research-execution-cell/root (:research-execution-cell/root execution-cell)
       :execution/status :failed
       :execution/error {:class (.getName (class error))
                         :message (or (.getMessage error) "")}})))

(defn- run-one-distributed [scope execution-manifest-root execution-cell]
  (try
    (let [{:keys [result failure-receipts]} (runner/run-distributed-cell
                                             scope execution-manifest-root execution-cell)]
      (if result
        {:research-execution-cell/root (:research-execution-cell/root execution-cell)
         :execution/status :complete
         :research-execution-cell-result/root
         (:research-execution-cell-result/root result)
         :distributed-execution-failure-receipts failure-receipts
         :result result}
        {:research-execution-cell/root (:research-execution-cell/root execution-cell)
         :execution/status :failed
         :execution/failure-kind :distributed-non-result
         :distributed-execution-failure-receipts failure-receipts}))
    (catch Throwable error
      {:research-execution-cell/root (:research-execution-cell/root execution-cell)
       :execution/status :failed
       :execution/failure-kind :runtime-diagnostic
       :execution/error {:class (.getName (class error))
                         :message (or (.getMessage error) "")}})))

(defn- ordered-entries [entries]
  (vec (sort-by :research-execution-cell/root entries)))

(defn result-set
  "Canonical comparison projection for executor equivalence.

   It omits executor/runtime fields and retains one ordered outcome per cell.
   Failed cells remain explicit rather than disappearing from the set."
  [report]
  (mapv (fn [entry]
          (select-keys entry [:research-execution-cell/root :execution/status
                              :research-execution-cell-result/root]))
        (:execution/entries report)))

(defn- report [executor max-concurrency entries]
  {:executor/kind executor
   :runtime/max-concurrency max-concurrency
   :execution/entries (ordered-entries entries)})

(defn execute-manifest
  "Execute every rooted cell in a validated manifest.

   The thread-pool mode is bounded and gathers futures in manifest order, then
   canonically sorts output by cell root. Completion order therefore cannot
   affect the result set. This function has no shared result accumulator."
  ([scope execution-manifest]
   (execute-manifest scope execution-manifest :sequential {}))
  ([scope execution-manifest executor options]
   (let [validation (manifest/validate-manifest execution-manifest)
         {:keys [max-concurrency]} (validate-options! executor options)
         cells (:dispatch/cells execution-manifest)]
     (when-not (:valid? validation)
       (throw (ex-info "Cannot execute an invalid manifest" validation)))
     (case executor
       :sequential
       (report executor max-concurrency (mapv #(run-one scope %) cells))

       :thread-pool
       (let [pool (Executors/newFixedThreadPool max-concurrency)]
         (try
           (let [futures (mapv (fn [execution-cell]
                                 (.submit pool
                                          ^Callable
                                          (reify Callable
                                            (call [_] (run-one scope execution-cell)))))
                               cells)]
             (report executor max-concurrency (mapv #(.get %) futures)))
           (finally
             (.shutdown pool)
             (when-not (.awaitTermination pool 30 TimeUnit/SECONDS)
               (.shutdownNow pool)))))

       :distributed
       (report executor max-concurrency
               (mapv #(run-one-distributed
                       scope (:research-execution-manifest/root execution-manifest) %)
                     cells))))))
