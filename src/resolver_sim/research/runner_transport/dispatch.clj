(ns resolver-sim.research.runner-transport.dispatch
  "Coordinator-to-runner dispatch and result-delivery protocol operations.

   Runtime services are supplied explicitly for each call; this namespace does
   not own transport credentials, runner state, or journal persistence."
  (:require [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.research.execution-control :as control]
            [resolver-sim.research.execution-assurance :as assurance]
            [resolver-sim.research.native-accounting-work :as native-accounting]
            [resolver-sim.research.observed-assurance :as observed]
            [resolver-sim.research.remote-execution :as remote]
            [resolver-sim.research.interaction-reproduction :as reproduction]
            [resolver-sim.research.runner-participation :as participation]
            [resolver-sim.research.runner-transport.work-types :as work-types]))

(def ^:dynamic *runtime* nil)

(def ^:private runtime-keys
  #{:post-edn! :persist-dispatch-intent! :update-coordinator-state!
    :coordinator-state :native-accounting-work-shape? :observed-work-for
    :claim-observation-from-delivery :valid-accounting-result-delivery?})

(defn with-runtime
  "Run one dispatch operation using the owning transport's internal services."
  [runtime f]
  (when-not (and (map? runtime) (= runtime-keys (set (keys runtime))) (fn? f))
    (throw (ex-info "Invalid runner transport dispatch runtime" {})))
  (binding [*runtime* runtime] (f)))

(defn- runtime-fn [key]
  (or (get *runtime* key)
      (throw (ex-info "Runner dispatch called outside its transport runtime"
                      {:missing-runtime-key key}))))

(defn- post-edn! [& args] (apply (runtime-fn :post-edn!) args))
(defn- persist-dispatch-intent! [& args]
  (apply (runtime-fn :persist-dispatch-intent!) args))
(defn- update-coordinator-state! [& args]
  (apply (runtime-fn :update-coordinator-state!) args))
(defn- coordinator-state [& args] (apply (runtime-fn :coordinator-state) args))
(defn- native-accounting-work-shape? [work]
  ((runtime-fn :native-accounting-work-shape?) work))
(defn- observed-work-for [work] ((runtime-fn :observed-work-for) work))
(defn- claim-observation-from-delivery [& args]
  (apply (runtime-fn :claim-observation-from-delivery) args))
(defn- valid-accounting-result-delivery? [& args]
  (apply (runtime-fn :valid-accounting-result-delivery?) args))
(defn- accounting-reference? [value root-key]
  (work-types/accounting-reference? value root-key))

(defn- accounting-input-refs [work]
  (assoc work
         :accounting/request {:accounting-request/root
                              (get-in work [:accounting/request :accounting-request/root])}
         :accounting/partition-plan {:accounting-partition-plan/root
                                     (get-in work [:accounting/partition-plan
                                                   :accounting-partition-plan/root])}))

(defn- interpret-job-delivery
  [coordinator-root job assignment logical-cutpoint response cache-status]
  (let [body (:transport/response response)
        status (:transport/status body)]
    (cond
      (= :authentication-rejected status)
      (throw (ex-info "Runner rejected transport authentication"
                      {:transport/error :authentication-rejected}))
      (nil? (:http/status response))
      {:delivery/status :unreachable :delivery/cache-status cache-status
       :reachability (control/build-reachability
                      {:job-root (:research-execution-job/root job)
                       :route-kind :job-delivery
                       :subject-root (:research-run-assignment/root assignment)
                       :source-root coordinator-root
                       :destination-root (:runner/reference assignment)
                       :declared-reachable? true :outcome :unreachable
                       :attempt-cutpoint logical-cutpoint})}
      (and (= 200 (:http/status response))
           (contains? #{:accepted :cancelled-before-start :already-completed} status)
           (= (:research-execution-job/root job)
              (:research-execution-job/root body))
           (= (:research-run-assignment/root assignment)
              (:research-run-assignment/root body))
           (= (:runner/reference assignment) (:runner/reference body)))
      {:delivery/status status :delivery/cache-status cache-status
       :reachability (control/build-reachability
                      {:job-root (:research-execution-job/root job)
                       :route-kind :job-delivery
                       :subject-root (:research-run-assignment/root assignment)
                       :source-root coordinator-root
                       :destination-root (:runner/reference assignment)
                       :declared-reachable? true :outcome :realized
                       :attempt-cutpoint logical-cutpoint})}
      :else (throw (ex-info "Runner rejected job delivery" {:response response})))))

(defn- deliver-job-after-validation!
  [{:keys [coordinator-root endpoint job assignment execution-work logical-cutpoint execute?
           use-cached-accounting-input?]
    :or {logical-cutpoint 0 execute? true}}]
  (let [message {:transport/kind :research/job-delivery
                 :job job :assignment assignment :execution-work execution-work
                 :execute? execute? :logical-cutpoint logical-cutpoint}
        referenced? (and use-cached-accounting-input?
                         (native-accounting-work-shape? execution-work))
        first-response (post-edn! endpoint "/v1/jobs"
                                  (if referenced?
                                    (assoc message :execution-work (accounting-input-refs execution-work))
                                    message))
        cache-miss? (and referenced?
                         (= :accounting-input-cache-miss
                            (get-in first-response [:transport/response :transport/status])))
        response (if cache-miss? (post-edn! endpoint "/v1/jobs" message) first-response)
        cache-status (cond cache-miss? :miss-resent-full
                           referenced? :referenced
                           (native-accounting-work-shape? execution-work) :full
                           :else :not-applicable)]
    (interpret-job-delivery coordinator-root job assignment logical-cutpoint
                            response cache-status)))

(defn deliver-job!
  "Deliver a rooted job/assignment over HTTP and produce existing canonical
   realized-reachability evidence. Authentication rejection is a transport
   boundary failure, not a false claim that the route was unreachable."
  [{:keys [job assignment execution-work] :as dispatch}]
  (when-not (and (remote/valid-job? job) (remote/valid-assignment? assignment)
                 (= (:research-execution-job/root job)
                    (:research-execution-job/root assignment))
                 (or (nil? execution-work)
                     (let [work (observed-work-for execution-work)]
                       (and work (= job (:work/job work))
                            (= assignment (:work/assignment work))))))
    (throw (ex-info "Invalid job delivery lineage" {})))
  (deliver-job-after-validation! dispatch))

(defn deliver-claim-evidence-coordinate!
  "Transport one existing rooted claim-evidence coordinate to an opted-in
   runner. The plan, source artifacts, coordinate assignment, and resulting
   observation retain their existing identities; this HTTP envelope adds no
   research-analysis schema or semantics."
  [{:keys [coordinator-root endpoint job assignment claim-evidence-work
           logical-cutpoint]
    :or {logical-cutpoint 0}}]
  (let [participant (:runner-participation claim-evidence-work)
        message {:job job :assignment assignment
                 :claim-evidence-work (dissoc claim-evidence-work
                                              :runner-participation)}
        observation (claim-observation-from-delivery
                     {:runner-participation participant} message)]
    (when-not (and (hash-ref/valid-sha256-ref? coordinator-root)
                   observation
                   (integer? logical-cutpoint) (not (neg? logical-cutpoint)))
      (throw (ex-info "Invalid claim-evidence coordinate dispatch" {})))
    (let [response (post-edn! endpoint "/v1/claim-evidence"
                              (assoc message :logical-cutpoint logical-cutpoint))
          body (:transport/response response)
          returned (:research-claim-evidence-observation body)
          status (:transport/status body)]
      (cond
        (= :authentication-rejected status)
        (throw (ex-info "Runner rejected transport authentication"
                        {:transport/error :authentication-rejected}))

        (nil? (:http/status response))
        {:delivery/status :unreachable
         :reachability
         (control/build-reachability
          {:job-root (:research-execution-job/root job)
           :route-kind :job-delivery
           :subject-root (:research-claim-evidence-coordinate/root
                          (:coordinate-assignment claim-evidence-work))
           :source-root coordinator-root
           :destination-root (:runner/reference assignment)
           :declared-reachable? true :outcome :unreachable
           :attempt-cutpoint logical-cutpoint})}

        (and (= 200 (:http/status response)) (= :accepted status)
             (= (:research-execution-job/root job)
                (:research-execution-job/root body))
             (= (:research-run-assignment/root assignment)
                (:research-run-assignment/root body))
             (= (:runner/reference assignment) (:runner/reference body))
             (= observation returned))
        {:delivery/status :accepted
         :research-claim-evidence-observation returned
         :reachability
         (control/build-reachability
          {:job-root (:research-execution-job/root job)
           :route-kind :job-delivery
           :subject-root (:research-claim-evidence-coordinate/root
                          (:coordinate-assignment claim-evidence-work))
           :source-root coordinator-root
           :destination-root (:runner/reference assignment)
           :declared-reachable? true :outcome :realized
           :attempt-cutpoint logical-cutpoint})}

        :else
        (throw (ex-info "Runner rejected claim-evidence coordinate delivery"
                        {:response response}))))))

(defn- note-dispatch-delivery!
  [coordinator endpoint assignment execution-work logical-cutpoint delivery]
  (let [assignment-root (:research-run-assignment/root assignment)
        cache-key (when (native-accounting-work-shape? execution-work)
                    [(:transport/base-url endpoint) (:runner/reference assignment)
                     (get-in execution-work [:accounting/request :accounting-request/root])
                     (get-in execution-work [:accounting/partition-plan
                                             :accounting-partition-plan/root])])]
    (when (and cache-key (contains? #{:accepted :already-completed}
                                    (:delivery/status delivery)))
      (swap! (:transport/accounting-input-known coordinator) conj cache-key))
    (update-coordinator-state!
     coordinator
     (fn [state]
       (-> state
           (assoc-in [:dispatch-intents assignment-root :dispatch/status]
                     (if (= :unreachable (:delivery/status delivery)) :pending :delivered))
           (update-in [:dispatch-intents assignment-root :dispatch/history]
                      (fnil conj []) {:dispatch/logical-cutpoint logical-cutpoint
                                      :dispatch/delivery-status (:delivery/status delivery)
                                      :dispatch/cache-status (:delivery/cache-status delivery)
                                      :dispatch/reachability-root
                                      (get-in delivery [:reachability
                                                        :research-realized-reachability/root])}))))))

(defn dispatch-job!
  "Persist coordinator dispatch intent before attempting delivery. Repeated
   transport attempts are at-least-once; the immutable assignment/work bundle
   makes later runner application exactly-once semantically. This is the
   qualification seam for coordinator restart/recovery, not a job queue."
  [coordinator {:keys [endpoint job assignment execution-work logical-cutpoint execute?]
                :or {logical-cutpoint 0 execute? true} :as dispatch}]
  (when-not (and (remote/valid-job? job) (remote/valid-assignment? assignment))
    (throw (ex-info "Invalid coordinator dispatch" {})))
  (let [_ (persist-dispatch-intent! coordinator dispatch)
        cache-key (when (native-accounting-work-shape? execution-work)
                    [(:transport/base-url endpoint) (:runner/reference assignment)
                     (get-in execution-work [:accounting/request :accounting-request/root])
                     (get-in execution-work [:accounting/partition-plan
                                             :accounting-partition-plan/root])])]
    (let [delivery (deliver-job-after-validation!
                    {:coordinator-root (:coordinator/root coordinator)
                     :endpoint endpoint :job job :assignment assignment
                     :execution-work execution-work :logical-cutpoint logical-cutpoint
                     :execute? execute?
                     :use-cached-accounting-input?
                     (and cache-key
                          (contains? @(:transport/accounting-input-known coordinator)
                                     cache-key))})]
      (note-dispatch-delivery! coordinator endpoint assignment execution-work
                               logical-cutpoint delivery)
      delivery)))

(defn dispatch-reproduction!
  "Dispatch canonical input plus implementation to a runner. The expected
   result is used only after execution and is never in dispatch intent, HTTP
   job delivery, or runner state."
  [coordinator {:keys [endpoint job assignment reproduction-work authoritative-result
                       logical-cutpoint]
                :or {logical-cutpoint 0}}]
  (let [delivery (dispatch-job! coordinator
                                {:endpoint endpoint :job job :assignment assignment
                                 :execution-work reproduction-work
                                 :logical-cutpoint logical-cutpoint})
        entry (some (fn [[_ result]]
                      (when (= (:research-run-assignment/root assignment)
                               (:research-run-assignment/root (:assignment result))) result))
                    (:reproduction-results (coordinator-state coordinator)))
        execution (:reproduction-execution entry)]
    (cond-> {:delivery delivery :reproduction-execution execution}
      (and execution authoritative-result)
      (assoc :reproduction-evidence
             (reproduction/compare-execution execution authoritative-result)))))

(defn dispatch-native-accounting!
  "Dispatch the rooted account partition and admit its directly assured partial.
   Job and assignment are derived from the bundle; expected values are absent."
  [coordinator {:keys [endpoint accounting-work logical-cutpoint] :or {logical-cutpoint 0}}]
  (let [job (:work/job accounting-work) assignment (:work/assignment accounting-work)
        delivery (dispatch-job! coordinator {:endpoint endpoint :job job :assignment assignment
                                             :execution-work accounting-work :logical-cutpoint logical-cutpoint})
        entry (some (fn [[_ result]]
                      (when (= (:research-run-assignment/root assignment)
                               (:research-run-assignment/root (:assignment result))) result))
                    (:native-accounting-results (coordinator-state coordinator)))]
    {:delivery delivery :native-accounting-execution (:native-accounting-execution entry)}))

(defn- accounting-batch-messages [items]
  (second
   (reduce
    (fn [[seen messages] item]
      (let [work (:accounting-work item)
            input-key [(get-in work [:accounting/request :accounting-request/root])
                       (get-in work [:accounting/partition-plan
                                     :accounting-partition-plan/root])]
            repeated? (contains? seen input-key)]
        [(conj seen input-key)
         (conj messages
               {:transport/kind :research/job-delivery
                :job (:work/job work) :assignment (:work/assignment work)
                :execution-work (if repeated? (accounting-input-refs work) work)
                :execute? true :logical-cutpoint (:logical-cutpoint item 0)})]))
    [#{} []] items)))

(defn dispatch-native-accounting-batch!
  "Send several already-rooted native work units to one runner in one HTTP
   envelope. Repeated account inputs within that envelope are sent by root
   after their first full occurrence. Every assignment keeps its own intent,
   result admission and retry history. A lost batch response leaves its
   assignments pending; redelivery uses the ordinary idempotent path."
  [coordinator endpoint items]
  (let [works (mapv :accounting-work items)
        assignments (mapv :work/assignment works)
        runner-roots (set (map :runner/reference assignments))]
    (when-not (and (vector? items) (<= 1 (count items) 32)
                   (map? endpoint) (= 1 (count runner-roots))
                   (= (count assignments)
                      (count (set (map :research-run-assignment/root assignments))))
                   (every? native-accounting/valid-work? works)
                   (every? #(and (integer? (:logical-cutpoint % 0))
                                 (not (neg? (:logical-cutpoint % 0)))) items))
      (throw (ex-info "Invalid native accounting transport batch" {})))
    (doseq [work works]
      (persist-dispatch-intent! coordinator
                                {:job (:work/job work)
                                 :assignment (:work/assignment work)
                                 :execution-work work :execute? true}))
    (let [messages (accounting-batch-messages items)
          response (post-edn! endpoint "/v1/jobs"
                              {:transport/kind :research/job-delivery-batch
                               :transport/items messages})
          replies (get-in response [:transport/response :transport/results])]
      (when (and (:http/status response)
                 (not (and (= 200 (:http/status response))
                           (= :batch-processed
                              (get-in response [:transport/response :transport/status]))
                           (vector? replies) (= (count replies) (count items)))))
        (throw (ex-info "Runner rejected native accounting batch" {:response response})))
      (mapv
       (fn [index item reply]
         (let [work (:accounting-work item)
               assignment (:work/assignment work)
               cutpoint (:logical-cutpoint item 0)
               unit-root (get-in work [:accounting/work-unit :accounting-work-unit/root])]
           (try
             (let [referenced? (accounting-reference?
                                (get-in (nth messages index)
                                        [:execution-work :accounting/request])
                                :accounting-request/root)
                   cache-miss? (and referenced?
                                    (= :accounting-input-cache-miss
                                       (get-in reply [:transport/response :transport/status])))
                   delivery (if cache-miss?
                              (deliver-job! {:coordinator-root (:coordinator/root coordinator)
                                             :endpoint endpoint :job (:work/job work)
                                             :assignment assignment :execution-work work
                                             :logical-cutpoint cutpoint})
                              (interpret-job-delivery
                               (:coordinator/root coordinator) (:work/job work)
                               assignment cutpoint (or reply {:http/status nil})
                               (if referenced? :referenced-batch :full-batch)))
                   delivery (if cache-miss?
                              (assoc delivery :delivery/cache-status :miss-resent-full-batch)
                              delivery)
                   _ (note-dispatch-delivery! coordinator endpoint assignment work
                                              cutpoint delivery)
                   entry (some (fn [[_ result]]
                                 (when (= (:research-run-assignment/root assignment)
                                          (:research-run-assignment/root (:assignment result)))
                                   result))
                               (:native-accounting-results (coordinator-state coordinator)))]
               {:accounting-work-unit/root unit-root
                :dispatch {:delivery delivery
                           :native-accounting-execution (:native-accounting-execution entry)}})
             (catch Exception e
               {:accounting-work-unit/root unit-root
                :dispatch/status :failed :failure/class (.getName (class e))}))))
       (range (count items)) items (or replies (repeat nil))))))

(defn retry-pending-dispatches!
  "Re-attempt persisted dispatch intents after a runner returns. It never
   changes the assignment or work bundle, and terminal runner state makes a
   replay `:already-completed` rather than a second semantic execution."
  [coordinator endpoint-for-assignment logical-cutpoint]
  (mapv (fn [[_ intent]]
          (dispatch-job! coordinator
                         (assoc intent :endpoint (endpoint-for-assignment (:assignment intent))
                                :logical-cutpoint logical-cutpoint)))
        (->> (:dispatch-intents (coordinator-state coordinator))
             (filter (fn [[_ intent]] (= :pending (:dispatch/status intent))))
             (sort-by key))))

(defn deliver-observed-result!
  "Return an already validated observed run plus its V1 assurance consequence.
   This does not accept a primitive, authorize consumption, or choose among
   competing results. It records a real transport delivery for the exact
   assignment and keeps repeated identical evidence idempotent."
  [{:keys [coordinator-root endpoint assignment observed-run-evidence
           run-assurance-evidence accounting-context accounting-partial logical-cutpoint]
    :or {logical-cutpoint 0}}]
  (when-not (and (remote/valid-assignment? assignment)
                 (observed/valid-observed-run-evidence? observed-run-evidence)
                 (assurance/valid-run-assurance-evidence? run-assurance-evidence)
                 (= (:research-execution-job/root assignment)
                    (:research-execution-job/root observed-run-evidence))
                 (= (:research-run-assignment/root assignment)
                    (:research-run-assignment/root observed-run-evidence))
                 (= (:research-observed-run-evidence/root observed-run-evidence)
                    (:research-run-evidence/root run-assurance-evidence))
                 (if (or accounting-context accounting-partial)
                   (and accounting-context accounting-partial
                        (valid-accounting-result-delivery? accounting-context accounting-partial
                                                           run-assurance-evidence))
                   true))
    (throw (ex-info "Invalid observed result delivery lineage" {})))
  (let [response (post-edn! endpoint "/v1/results"
                            {:transport/kind :research/observed-result-delivery
                             :assignment assignment
                             :observed-run-evidence observed-run-evidence
                             :run-assurance-evidence run-assurance-evidence
                             :accounting-context accounting-context
                             :accounting-partial accounting-partial
                             :logical-cutpoint logical-cutpoint})
        body (:transport/response response)
        status (:transport/status body)]
    (cond
      (= :authentication-rejected status)
      (throw (ex-info "Coordinator rejected transport authentication"
                      {:transport/error :authentication-rejected}))

      (nil? (:http/status response))
      {:delivery/status :unreachable
       :reachability (control/build-reachability
                      {:job-root (:research-execution-job/root assignment)
                       :route-kind :result-delivery
                       :subject-root (:research-observed-run-evidence/root observed-run-evidence)
                       :source-root (:runner/reference observed-run-evidence)
                       :destination-root coordinator-root
                       :declared-reachable? true :outcome :unreachable
                       :attempt-cutpoint logical-cutpoint})}

      (and (= 200 (:http/status response))
           (contains? #{:accepted :already-recorded} status)
           (= (:research-execution-job/root assignment)
              (:research-execution-job/root body))
           (= (:research-run-assignment/root assignment)
              (:research-run-assignment/root body))
           (= (:research-observed-run-evidence/root observed-run-evidence)
              (:research-observed-run-evidence/root body))
           (= (:research-run-assurance-evidence/root run-assurance-evidence)
              (:research-run-assurance-evidence/root body))
           (= coordinator-root (:coordinator/root body)))
      {:delivery/status status
       :reachability (control/build-reachability
                      {:job-root (:research-execution-job/root assignment)
                       :route-kind :result-delivery
                       :subject-root (:research-observed-run-evidence/root observed-run-evidence)
                       :source-root (:runner/reference observed-run-evidence)
                       :destination-root coordinator-root
                       :declared-reachable? true :outcome :realized
                       :attempt-cutpoint logical-cutpoint})}

      :else
      (throw (ex-info "Coordinator rejected observed result delivery"
                      {:response response
                       :cause (get-in response [:transport/response :transport/failure-cause])})))))

(defn deliver-cancellation!
  "Publish a canonical cancellation to a runner and create separate delivery
   and runner-observation artifacts only after the runner actually responds."
  [{:keys [coordinator-root endpoint cancellation runner-participation
           assignment logical-cutpoint last-work-unit-started-root
           last-work-unit-completed-root status]
    :or {logical-cutpoint 0 status :observed}}]
  (when-not (and (:valid? (participation/validate-participation runner-participation))
                 (= (:runner-participation/root runner-participation)
                    (:runner-participation/root assignment))
                 (= (:research-execution-job/root cancellation)
                    (:research-execution-job/root assignment)))
    (throw (ex-info "Invalid cancellation delivery lineage" {})))
  (let [response (post-edn! endpoint "/v1/cancellations"
                            {:transport/kind :research/cancellation-delivery
                             :cancellation cancellation
                             :logical-cutpoint logical-cutpoint})]
    (if (nil? (:http/status response))
      {:delivery/status :unreachable
       :reachability (control/build-reachability
                      {:job-root (:research-execution-job/root cancellation)
                       :route-kind :cancellation-delivery
                       :subject-root (:research-job-cancellation/root cancellation)
                       :source-root coordinator-root
                       :destination-root (:runner/reference runner-participation)
                       :declared-reachable? true :outcome :unreachable
                       :attempt-cutpoint logical-cutpoint})}
      (let [body (:transport/response response)]
        (when (= :authentication-rejected (:transport/status body))
          (throw (ex-info "Runner rejected transport authentication"
                          {:transport/error :authentication-rejected})))
        (when-not (and (= 200 (:http/status response))
                       (= :observed (:transport/status body))
                       (= (:research-execution-job/root cancellation)
                          (:research-execution-job/root body))
                       (= (:research-job-cancellation/root cancellation)
                          (:research-job-cancellation/root body))
                       (= (:runner/reference runner-participation)
                          (:runner/reference body)))
          (throw (ex-info "Runner rejected cancellation delivery" {:response response})))
        (let [reachability (control/build-reachability
                            {:job-root (:research-execution-job/root cancellation)
                             :route-kind :cancellation-delivery
                             :subject-root (:research-job-cancellation/root cancellation)
                             :source-root coordinator-root
                             :destination-root (:runner/reference runner-participation)
                             :declared-reachable? true :outcome :realized
                             :attempt-cutpoint logical-cutpoint})]
          {:delivery/status :observed
           :reachability reachability
           :observation (control/build-control-observation
                         {:job-root (:research-execution-job/root cancellation)
                          :assignment-root (:research-run-assignment/root assignment)
                          :runner-participation-root
                          (:runner-participation/root runner-participation)
                          :runner-reference (:runner/reference runner-participation)
                          :highest-observed-epoch (:control/epoch cancellation)
                          :cancellation-root (:research-job-cancellation/root cancellation)
                          :observation-cutpoint logical-cutpoint
                          :last-work-unit-started-root last-work-unit-started-root
                          :last-work-unit-completed-root last-work-unit-completed-root
                          :status status
                          :reachability reachability})})))))
