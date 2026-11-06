(ns resolver-sim.research.runner-transport.results
  "Delivery and retry of completed research execution evidence."
  (:require [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.research.execution-control :as control]
            [resolver-sim.research.native-accounting-work :as native-accounting]
            [resolver-sim.research.remote-execution :as remote]
            [resolver-sim.research.interaction-reproduction :as reproduction]
            [resolver-sim.research.runner-forensics :as runner-forensics]))

(def ^:dynamic *runtime* nil)
(def ^:private runtime-keys
  #{:post-edn! :computed-execution-root :runner-state :update-runtime-state!
    :append-work-event :acknowledge-result? :deliver-observed-result!
    :record-forensic-event})

(defn with-runtime [runtime f]
  (when-not (and (map? runtime) (= runtime-keys (set (keys runtime))) (fn? f))
    (throw (ex-info "Invalid runner result-delivery runtime" {})))
  (binding [*runtime* runtime] (f)))

(defn- runtime-fn [key]
  (or (get *runtime* key)
      (throw (ex-info "Result delivery called without runtime services"
                      {:missing-runtime-key key}))))
(defn- post-edn! [& args] (apply (runtime-fn :post-edn!) args))
(defn- computed-execution-root [& args]
  (apply (runtime-fn :computed-execution-root) args))
(defn- runner-state [& args] (apply (runtime-fn :runner-state) args))
(defn- update-runtime-state! [& args]
  (apply (runtime-fn :update-runtime-state!) args))
(defn- append-work-event [& args]
  (apply (runtime-fn :append-work-event) args))
(defn- acknowledge-result? [& args]
  (apply (runtime-fn :acknowledge-result?) args))
(defn- deliver-observed-result! [& args]
  (apply (runtime-fn :deliver-observed-result!) args))
(defn- record-forensic-event [& args]
  (apply (runtime-fn :record-forensic-event) args))

(defn deliver-computation-result!
  "At-least-once delivery of a persisted fresh execution artifact. The expected
   result is deliberately absent; comparison remains a later consumer step."
  [{:keys [coordinator-root endpoint job assignment reproduction-execution native-accounting-execution logical-cutpoint
           test-event]}]
  (when-not (and (remote/valid-job? job) (remote/valid-assignment? assignment)
                 (if native-accounting-execution
                   (and (hash-ref/valid-sha256-ref? (native-accounting/execution-root native-accounting-execution))
                        (= (:research-execution-job/root job)
                           (get-in native-accounting-execution [:run-evidence :research-execution-job/root]))
                        (= (:research-run-assignment/root assignment)
                           (get-in native-accounting-execution [:run-evidence :research-run-assignment/root])))
                   (reproduction/valid-execution? reproduction-execution))
                 (= (:research-execution-job/root job)
                    (:research-execution-job/root assignment))
                 (or native-accounting-execution
                     (= (:reproduction-input/root reproduction-execution) (:workload/root job))))
    (throw (ex-info "Invalid reproduction result delivery" {})))
  (when test-event (test-event :result-post-initiated))
  (let [execution (or native-accounting-execution reproduction-execution)
        result-root (computed-execution-root execution)
        response (post-edn! endpoint (if native-accounting-execution "/v1/accounting" "/v1/reproductions")
                            {:transport/kind (if native-accounting-execution :research/native-accounting-result-delivery
                                                 :research/reproduction-result-delivery)
                             :job job :assignment assignment
                             (if native-accounting-execution :native-accounting-execution :reproduction-execution) execution
                             :logical-cutpoint logical-cutpoint})
        body (:transport/response response) status (:transport/status body)]
    (when test-event
      (test-event (if (:http/status response) :result-post-response-received
                      :result-post-transport-failed)))
    (cond
      (= :authentication-rejected status)
      (throw (ex-info "Coordinator rejected reproduction result authentication" {}))
      (nil? (:http/status response))
      {:delivery/status :unreachable
       :reachability (control/build-reachability
                      {:job-root (:research-execution-job/root job)
                       :route-kind :result-delivery
                       :subject-root result-root
                       :source-root (:runner/reference assignment)
                       :destination-root coordinator-root
                       :declared-reachable? true :outcome :unreachable
                       :attempt-cutpoint logical-cutpoint})}
      (and (= 200 (:http/status response))
           (contains? #{:accepted :already-recorded} status)
           (= result-root (get body (if native-accounting-execution :research-native-accounting-run/root :reproduction-execution/root)))
           (= (:research-run-assignment/root assignment)
              (:research-run-assignment/root body)))
      {:delivery/status status
       :reachability (control/build-reachability
                      {:job-root (:research-execution-job/root job)
                       :route-kind :result-delivery
                       :subject-root result-root
                       :source-root (:runner/reference assignment)
                       :destination-root coordinator-root
                       :declared-reachable? true :outcome :realized
                       :attempt-cutpoint logical-cutpoint})}
      :else (throw (ex-info "Coordinator rejected reproduction result" {:response response})))))

(defn deliver-reproduction-result! [message]
  (when (or (nil? (:reproduction-execution message)) (:native-accounting-execution message))
    (throw (ex-info "Expected reproduction result" {})))
  (deliver-computation-result! message))

(defn deliver-native-accounting-result! [message]
  (when (or (nil? (:native-accounting-execution message)) (:reproduction-execution message))
    (throw (ex-info "Expected native accounting result" {})))
  (deliver-computation-result! message))

(defn retry-undelivered-results!
  "Retry coordinator delivery for completed work whose earlier result post was
   unreachable. The original semantic and forensic artifacts are reused; the
   coordinator's content-addressed ingestion is idempotent. Every delivery
   attempt is retained in order, while `:result-delivery` remains the latest
   attempt for compatibility with existing callers."
  ([runner logical-cutpoint]
   (retry-undelivered-results! runner (:transport/coordinator-endpoint runner)
                               logical-cutpoint))
  ([runner endpoint logical-cutpoint]
   (when-not (and (map? endpoint)
                  (integer? logical-cutpoint) (not (neg? logical-cutpoint)))
     (throw (ex-info "Invalid result retry parameters" {})))
   (let [pending (->> (:completed-work (runner-state runner))
                      (filter (fn [[_ completed]]
                                (and (not (:reproduction-execution completed)) (not (:native-accounting-execution completed))
                                     (= :unreachable
                                        (get-in completed [:result-delivery :delivery/status])))))
                      (sort-by key))]
     (mapv
      (fn [[work-root completed]]
        (let [assignment (get-in completed [:observed-run-evidence
                                            :proof/source-artifacts :assignment])
              delivered (deliver-observed-result!
                         {:coordinator-root (:transport/coordinator-root runner)
                          :endpoint endpoint :assignment assignment
                          :observed-run-evidence (:observed-run-evidence completed)
                          :run-assurance-evidence (:run-assurance-evidence completed)
                          :accounting-context (:accounting-context completed)
                          :accounting-partial (:accounting-partial completed)
                          :logical-cutpoint logical-cutpoint})]
          (update-runtime-state!
           runner
           (fn [state]
             (let [status (if (acknowledge-result? delivered)
                            :result-acknowledged :completed)
                   state (-> state
                             (assoc-in [:completed-work work-root :result-delivery] delivered)
                             (update-in [:completed-work work-root :result-deliveries]
                                        (fnil conj []) delivered))
                   state (append-work-event state work-root status logical-cutpoint
                                            :result-redelivery)
                   observed-root (:research-observed-run-evidence/root
                                  (:observed-run-evidence completed))
                   assurance-root (:research-run-assurance-evidence/root
                                   (:run-assurance-evidence completed))
                   previous-post (or (runner-forensics/latest-node-hash
                                      state :runner.forensic/result-redelivered observed-root)
                                     (runner-forensics/latest-node-hash
                                      state :runner.forensic/result-posted observed-root))
                   completion-node (runner-forensics/latest-node-hash
                                    state :runner.forensic/execution-completed observed-root)
                   post-node (runner-forensics/build-event-node
                              {:event-key :runner.forensic/result-redelivered
                               :subject-root observed-root
                               :subject-kind :research-runner/result-delivery
                               :actor-root (:runner/reference (:runner-participation runner))
                               :causal-parent-roots [(:research-run-assignment/root assignment)
                                                     assurance-root
                                                     (get-in delivered [:reachability
                                                                        :research-realized-reachability/root])]
                               :parent-nodes (cond-> [] completion-node (conj completion-node)
                                                     previous-post (conj previous-post))
                               :observed-root assurance-root})
                   state (runner-forensics/record-node state post-node)]
               (if (acknowledge-result? delivered)
                 (runner-forensics/record-node
                  state
                  (runner-forensics/build-event-node
                   {:event-key :runner.forensic/coordinator-acknowledged
                    :subject-root observed-root
                    :subject-kind :research-runner/coordinator-acknowledgement
                    :actor-root (:runner/reference (:runner-participation runner))
                    :causal-parent-roots [assurance-root
                                          (get-in delivered [:reachability
                                                             :research-realized-reachability/root])]
                    :parent-nodes [(:node-hash post-node)]
                    :observed-root assurance-root :consequence? true}))
                 state))))
          {:research-observed-execution-work/root work-root :delivery delivered}))
      pending))))

(defn retry-undelivered-reproduction-results! [runner endpoint logical-cutpoint]
  (let [pending (->> (:completed-work (runner-state runner))
                     (filter (fn [[_ entry]]
                               (and (or (:reproduction-execution entry) (:native-accounting-execution entry))
                                    (contains? #{:pending :unreachable}
                                               (get-in entry [:result-delivery :delivery/status])))))
                     (sort-by key))]
    (mapv
     (fn [[root completed]]
       (let [native? (some? (:native-accounting-execution completed))
             execution (or (:native-accounting-execution completed) (:reproduction-execution completed))
             assignment (:assignment completed)
             job (:job completed)
             test-event (:transport/test-event runner)
             _ (when test-event (test-event :runner-retry-initiated))
             delivered (deliver-computation-result!
                        {:coordinator-root (:transport/coordinator-root runner)
                         :endpoint endpoint :job job :assignment assignment
                         (if native? :native-accounting-execution :reproduction-execution) execution :logical-cutpoint logical-cutpoint
                         :test-event test-event})]
         (update-runtime-state!
          runner
          (fn [state]
            (let [state (-> state
                            (assoc-in [:completed-work root :result-delivery] delivered)
                            (update-in [:completed-work root :result-deliveries] conj delivered)
                            (append-work-event root (if (acknowledge-result? delivered)
                                                      :result-acknowledged :completed)
                                               logical-cutpoint :result-redelivery))
                  previous (runner-forensics/latest-node-hash
                            state :runner.forensic/result-posted
                            (computed-execution-root execution))
                  redelivery (record-forensic-event
                              state runner
                              {:event-key (if previous
                                            :runner.forensic/result-redelivered
                                            :runner.forensic/result-posted)
                               :subject-root (computed-execution-root execution)
                               :subject-kind (if native? :research-runner/native-accounting-result-delivery
                                                 :research-runner/reproduction-result-delivery)
                               :causal-parent-roots
                               (cond-> [(:research-run-assignment/root assignment)
                                        (computed-execution-root execution)]
                                 (get-in delivered
                                         [:reachability :research-realized-reachability/root])
                                 (conj (get-in delivered
                                               [:reachability
                                                :research-realized-reachability/root])))
                               :parent-nodes (cond-> [(runner-forensics/latest-node-hash
                                                       state :runner.forensic/execution-completed root)]
                                               previous (conj previous))
                               :observed-root (computed-execution-root execution)})]
              (if (and redelivery (acknowledge-result? delivered))
                (record-forensic-event
                 redelivery runner
                 {:event-key :runner.forensic/coordinator-acknowledged
                  :subject-root (computed-execution-root execution)
                  :subject-kind (if native? :research-runner/native-accounting-result-acknowledgement
                                    :research-runner/reproduction-result-acknowledgement)
                  :causal-parent-roots
                  (cond-> [(computed-execution-root execution)]
                    (get-in delivered
                            [:reachability :research-realized-reachability/root])
                    (conj (get-in delivered
                                  [:reachability :research-realized-reachability/root])))
                  :parent-nodes [(runner-forensics/latest-node-hash
                                  redelivery (if previous
                                               :runner.forensic/result-redelivered
                                               :runner.forensic/result-posted)
                                  (computed-execution-root execution))]
                  :observed-root (computed-execution-root execution)
                  :consequence? true})
                (or redelivery state)))))
         (when test-event
           (test-event (if (acknowledge-result? delivered)
                         :runner-acknowledgement-recorded
                         :runner-redelivery-not-acknowledged)))
         {(if native? :research-native-accounting-run/root :reproduction-execution/root) (computed-execution-root execution)
          :delivery delivered}))
     pending)))

