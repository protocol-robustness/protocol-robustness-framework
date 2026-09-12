(ns resolver-sim.resubmission.receipt-daemon
  "Process-local P1A scheduling only; the store and worker own issuance semantics."
  (:require [resolver-sim.resubmission.receipt-worker :as worker]
            [resolver-sim.resubmission.store :as store]))

(defn- positive-long [label value]
  (when-not (and (integer? value) (pos? value) (<= value Long/MAX_VALUE))
    (throw (IllegalArgumentException. (str label " must be a positive long"))))
  (long value))

(defn- outcome [status]
  (cond
    (#{:issued :idempotent} status) [status :success]
    (= :unavailable status) [:unavailable :retryable]
    (#{:invalid :invalid-obligation :invalid-state :not-found
       :receipt-obligation/conflict} status) [status :semantic]
    :else [:unknown :semantic]))

(defn- scan! [s private-key batch-size after-id stopped]
  (let [summary (atom {:attempted 0 :counts {} :outcomes [] :next-after-id after-id})]
    (try
      (let [entries (store/pending-receipt-obligations s)
            id-of #(get-in % [:receipt-obligation :receipt-obligation/id])
            ;; Resume by identity rather than offset: successful entries disappear.
            [before after] (if after-id
                             (split-with #(not (pos? (compare (id-of %) after-id))) entries)
                             [[] entries])]
        (doseq [entry (take batch-size (concat after before))
                :while (not @stopped)]
          (let [id (id-of entry)
                [status classification]
                (try
                  (outcome (:status (worker/reconstruct s id private-key)))
                  (catch InterruptedException _
                    (reset! stopped true)
                    [:interrupted :shutdown])
                  (catch Exception _ [:exception :retryable]))]
            (swap! summary
                   (fn [m]
                     (-> m
                         (update :attempted inc)
                         (assoc :next-after-id id)
                         (update-in [:counts classification] (fnil inc 0))
                         (update :outcomes conj {:status status :classification classification})))))))
      (catch InterruptedException _
        (reset! stopped true)
        (swap! summary assoc :scan-error :interrupted))
      (catch Exception _
        (swap! summary assoc :scan-error :retryable)))
    @summary))

(defn run-once!
  "Attempt at most :batch-size (default 64) pending entries, sequentially.
   Pass the returned :next-after-id as :after-id on the next call for fairness.
   Outcomes contain only fixed diagnostic keywords, never worker data/errors.
   Discovery reads the store's full snapshot; only issuance work is bounded."
  ([s private-key] (run-once! s private-key {}))
  ([s private-key {:keys [batch-size after-id] :or {batch-size 64}}]
   (scan! s private-key (positive-long "batch-size" batch-size) after-id (atom false))))

(defn start!
  "Start an immediate scan followed by fixed-delay polling, without signals.
   Options: :batch-size 64, :poll-interval-ms 1000, :stop-timeout-ms 1000.
   Returns an explicit handle with :summary (latest sanitized scan atom).
   A handle owns one sequential thread and a fair cursor; no global state."
  ([s private-key] (start! s private-key {}))
  ([s private-key {:keys [batch-size poll-interval-ms stop-timeout-ms]
                   :or {batch-size 64 poll-interval-ms 1000 stop-timeout-ms 1000}}]
   (let [batch-size (positive-long "batch-size" batch-size)
         interval (positive-long "poll-interval-ms" poll-interval-ms)
         timeout (positive-long "stop-timeout-ms" stop-timeout-ms)
         stopped (atom false)
         wake (Object.)
         summary (atom nil)
         thread (Thread.
                 ^Runnable
                 (fn []
                   (try
                     (loop [cursor nil]
                       (when-not @stopped
                         (let [result (scan! s private-key batch-size cursor stopped)]
                           (reset! summary result)
                           (locking wake
                             (when-not @stopped (.wait wake interval)))
                           (recur (:next-after-id result)))))
                     (catch InterruptedException _ (reset! stopped true))))
                 "p1a-receipt-daemon")
         handle {:thread thread :stopped stopped :wake wake
                 :summary summary :stop-timeout-ms timeout}]
     (.setDaemon thread true)
     (.start thread)
     handle)))

(defn stop!
  "Request shutdown and wake polling, without interrupting in-flight issuance.
   Wait at most the configured timeout (or explicit positive timeout-ms).
   Returns :stopped or :stopping; a timed-out signing call may still complete.
   Repeat safely to await completion. Caller interruption is preserved."
  ([handle] (stop! handle (:stop-timeout-ms handle)))
  ([{:keys [thread stopped wake]} timeout-ms]
   (let [timeout (positive-long "timeout-ms" timeout-ms)]
     (reset! stopped true)
     (locking wake (.notifyAll ^Object wake))
     (when-not (identical? thread (Thread/currentThread))
       (try
         (.join ^Thread thread timeout)
         (catch InterruptedException _ (.interrupt (Thread/currentThread)))))
     (if (.isAlive ^Thread thread) :stopping :stopped))))
