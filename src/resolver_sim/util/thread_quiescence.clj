(ns resolver-sim.util.thread-quiescence
  "Worker-thread quiescence management for canonical root executors.

  Provides a shared utility that shuts down an ExecutorService, waits
  for all worker threads to terminate, and returns a status keyword.
  When termination cannot be confirmed within the timeout, the caller
  MUST treat the root as :incomplete / :quiescence-unknown rather than
  releasing the root lock — otherwise a subsequent invocation could
  reuse a root whose detached workers are still writing.

  Both the scenario worker pool (benchmark/runner.clj) and the claimant
  executor pool (economics/payoffs.clj) use this utility."
  (:import [java.util.concurrent ExecutorService TimeUnit]))

(def ^:private default-quiescence-timeout-seconds 30)

(defn quiesce-executor!
  "Cancel all running tasks on `executor` and wait for termination.

  Returns a map with :status one of:
    :terminated              — all worker threads terminated within the timeout
    :termination-timeout     — timeout expired; threads did not terminate
    :termination-interrupted — the current thread was interrupted while waiting

  The :remaining-tasks key (present only on timeout) carries diagnostic
  information about queued-but-unstarted tasks."
  ([executor]
   (quiesce-executor! executor default-quiescence-timeout-seconds))
  ([^ExecutorService executor timeout-seconds]
   (let [interrupted? (atom false)
         terminated?
         (try
           (.shutdownNow executor)
           (try
             (.awaitTermination executor (long timeout-seconds) TimeUnit/SECONDS)
             (catch InterruptedException e
               (reset! interrupted? true)
               false))
           (catch InterruptedException e
             (reset! interrupted? true)
             nil))]
     (cond
       @interrupted? {:status :termination-interrupted}
       (true? terminated?) {:status :terminated}
       (nil? terminated?) {:status :termination-interrupted}
       :else {:status :termination-timeout
              :remaining-tasks (.getQueue executor)}))))

(defn quiescence-failed?
  "Predicate: true if `error` is a quiescence failure exception."
  [error]
  (and (instance? clojure.lang.ExceptionInfo error)
       (get (ex-data error) :quiescence/failed?)))

(defn quiescence-failed-exception
  "Build an exception signalling that worker threads did not terminate
  within the quiescence timeout. The exception carries :quiescence/failed?
  so callers can distinguish it from ordinary execution errors."
  [msg context]
  (ex-info msg
           (merge {:quiescence/failed? true}
                  (when context {:quiescence/context context}))
           nil))
