(ns resolver-sim.research.runner-transport.execution
  "Runner-side execution lifecycle for the supported research work artifacts.

   State mutation and transport delivery are supplied by the owning facade;
   work validation and deterministic execution remain with each work package."
  (:require [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.research.accounting-execution-work :as accounting-work]
            [resolver-sim.research.native-accounting-work :as native-accounting]
            [resolver-sim.research.observed-execution-work :as observed-work]
            [resolver-sim.research.reproduction-execution-work :as reproduction-work]
            [resolver-sim.research.runner-forensics :as runner-forensics]))

(def ^:dynamic *runtime* nil)

(def ^:private runtime-keys
  #{:work-root :runner-state
    :update-runtime-state! :append-work-event :record-forensic-event
    :computed-execution-root :acknowledge-result? :deliver-computation-result!
    :deliver-observed-result! :observed-work-for})

(defn with-runtime
  "Run work execution using the owning runner's state and delivery services."
  [runtime f]
  (when-not (and (map? runtime) (= runtime-keys (set (keys runtime))) (fn? f))
    (throw (ex-info "Invalid runner transport execution runtime" {})))
  (binding [*runtime* runtime] (f)))

(defn- runtime-fn [key]
  (or (get *runtime* key)
      (throw (ex-info "Runner execution called outside its transport runtime"
                      {:missing-runtime-key key}))))

(defn- work-root [work] ((runtime-fn :work-root) work))
(defn- runner-state [& args] (apply (runtime-fn :runner-state) args))
(defn- update-runtime-state! [& args]
  (apply (runtime-fn :update-runtime-state!) args))
(defn- append-work-event [& args] (apply (runtime-fn :append-work-event) args))
(defn- record-forensic-event [& args]
  (apply (runtime-fn :record-forensic-event) args))
(defn- computed-execution-root [& args]
  (apply (runtime-fn :computed-execution-root) args))
(defn- acknowledge-result? [& args]
  (apply (runtime-fn :acknowledge-result?) args))
(defn- deliver-computation-result! [& args]
  (apply (runtime-fn :deliver-computation-result!) args))
(defn- deliver-observed-result! [& args]
  (apply (runtime-fn :deliver-observed-result!) args))
(defn- observed-work-for [& args]
  (apply (runtime-fn :observed-work-for) args))

(defn- execute-reproduction-delivered-work! [runner work logical-cutpoint]
  (let [root (work-root work)
        native? (native-accounting/valid-work? work)
        job (:work/job work)
        assignment (:work/assignment work)
        job-root (:research-execution-job/root job)
        _ (when-let [hook (:transport/before-safe-cutpoint runner)]
            (hook runner work logical-cutpoint))]
    (when (contains? (:cancellations (runner-state runner)) job-root)
      (update-runtime-state! runner #(append-work-event % root :cancelled logical-cutpoint
                                                        :cancellation-observed))
      (throw (ex-info "Cancellation observed before reproduction execution"
                      {:cause :cancelled-at-safe-cutpoint})))
    (update-runtime-state!
     runner
     (fn [state]
       (let [state (append-work-event state root :executing logical-cutpoint :execution-started)
             delivery-node (runner-forensics/latest-node-hash
                            state :runner.forensic/delivery-accepted
                            (:research-run-assignment/root assignment))]
         (record-forensic-event
          state runner
          {:event-key :runner.forensic/execution-started :subject-root root
           :subject-kind (if native? :research-runner/native-accounting-work :research-runner/reproduction-work)
           :causal-parent-roots [job-root (:research-run-assignment/root assignment) root]
           :parent-nodes (cond-> [] delivery-node (conj delivery-node))
           :observed-root (if native? (:accounting-work-unit/root (:accounting/work-unit work))
                              (:reproduction-input/root (:work/reproduction-input work)))}))))
    ;; Runtime-only qualification hook at the durable executing boundary.
    ;; A process death here is recovered as outcome-unknown, never complete.
    (when-let [hook (:transport/before-reproduction-compute runner)]
      (hook runner work logical-cutpoint))
    (let [{:keys [execution forensic-evidence-nodes]}
          (if native?
            (native-accounting/execute-work-with-evidence! work)
            {:execution (reproduction-work/execute-work! work)})
          ;; Persist the completed deterministic computation before attempting
          ;; network delivery. Restart can only replay this exact value.
          initial {:job job :assignment assignment
                   (if native? :native-accounting-execution :reproduction-execution) execution
                   :result-delivery {:delivery/status :pending}
                   :result-deliveries []}]
      (update-runtime-state!
       runner
       (fn [state]
         (let [complete-node (runner-forensics/build-event-node
                              {:event-key :runner.forensic/execution-completed
                               :subject-root root :subject-kind (if native? :research-runner/native-accounting-work :research-runner/reproduction-work)
                               :actor-root (:runner/reference (:runner-participation runner))
                               :causal-parent-roots [job-root
                                                     (:research-run-assignment/root assignment)
                                                     (computed-execution-root execution)]
                               :parent-nodes (cond-> []
                                               (runner-forensics/latest-node-hash
                                                state :runner.forensic/execution-started root)
                                               (conj (runner-forensics/latest-node-hash
                                                      state :runner.forensic/execution-started root)))
                               :observed-root (computed-execution-root execution)
                               :consequence? true})]
           (let [state (-> state
                           (assoc-in [:completed-work root] initial)
                           (append-work-event root :completed logical-cutpoint :execution-completed)
                           (runner-forensics/record-node complete-node))]
             (if native?
               (reduce runner-forensics/record-node state forensic-evidence-nodes)
               state)))))
      (when-let [hook (:transport/before-result-delivery runner)]
        (hook runner work logical-cutpoint))
      (let [cancelled? (contains? (:cancellations (runner-state runner)) job-root)
            delivered (if cancelled?
                        {:delivery/status :cancelled-before-delivery}
                        (deliver-computation-result!
                         {:coordinator-root (:transport/coordinator-root runner)
                          :endpoint (:transport/coordinator-endpoint runner)
                          :job job :assignment assignment
                          (if native? :native-accounting-execution :reproduction-execution) execution
                          :logical-cutpoint logical-cutpoint
                          :test-event (:transport/test-event runner)}))]
        (update-runtime-state!
         runner
         (fn [state]
           (let [state (-> state
                           (assoc-in [:completed-work root :result-delivery] delivered)
                           (update-in [:completed-work root :result-deliveries] conj delivered)
                           (append-work-event root (if (acknowledge-result? delivered)
                                                     :result-acknowledged :completed)
                                              logical-cutpoint
                                              (if (= :cancelled-before-delivery
                                                     (:delivery/status delivered))
                                                :result-delivery-suppressed
                                                :result-posted)))
                 posted (when (not= :cancelled-before-delivery (:delivery/status delivered))
                          (record-forensic-event
                           state runner
                           {:event-key :runner.forensic/result-posted
                            :subject-root (computed-execution-root execution)
                            :subject-kind (if native? :research-runner/native-accounting-result-delivery
                                              :research-runner/reproduction-result-delivery)
                            :causal-parent-roots (cond-> [(:research-run-assignment/root assignment)
                                                          (computed-execution-root execution)]
                                                   (get-in delivered
                                                           [:reachability
                                                            :research-realized-reachability/root])
                                                   (conj (get-in delivered
                                                                 [:reachability
                                                                  :research-realized-reachability/root])))
                            :parent-nodes [(runner-forensics/latest-node-hash
                                            state :runner.forensic/execution-completed root)]
                            :observed-root (computed-execution-root execution)}))]
             (if (and posted (acknowledge-result? delivered))
               (record-forensic-event
                posted runner
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
                                 posted :runner.forensic/result-posted
                                 (computed-execution-root execution))]
                 :observed-root (computed-execution-root execution)
                 :consequence? true})
               (or posted state)))))
        {:execution execution :result-delivery delivered}))))

(defn- execute-delivered-work-existing!
  "Execute an already accepted rooted observed or accounting-partition bundle and return its
   existing observed evidence to the configured coordinator. This is a
   cooperative safe cutpoint: callers invoke it only before starting another
   work unit; no asynchronous process interruption is attempted."
  [runner work logical-cutpoint]
  (when-not (or (observed-work/valid-work? work) (accounting-work/valid-work? work))
    (throw (ex-info "Invalid dispatched execution work" {})))
  (let [root (work-root work)
        observed-work (observed-work-for work)
        assignment (:work/assignment observed-work)
        job (:work/job observed-work)
        job-root (:research-execution-job/root job)
        ;; Runtime-only test/adapter hook. It models a cooperative observation
        ;; point; it is intentionally absent from every rooted work artifact.
        before-safe-cutpoint (:transport/before-safe-cutpoint runner)
        _ (when before-safe-cutpoint (before-safe-cutpoint runner work logical-cutpoint))
        cancelled? (contains? (:cancellations (runner-state runner)) job-root)
        _ (when cancelled?
            (update-runtime-state! runner
                                   #(append-work-event % root :cancelled logical-cutpoint
                                                       :cancellation-observed)))
        _ (when cancelled?
            (throw (ex-info "Cancellation observed at cooperative safe cutpoint"
                            {:cause :cancelled-at-safe-cutpoint
                             :research-execution-job/root job-root})))
        _ (update-runtime-state!
           runner
           (fn [state]
             (let [state (append-work-event state root :executing logical-cutpoint
                                            :execution-started)
                   delivery-node (runner-forensics/latest-node-hash
                                  state :runner.forensic/delivery-accepted
                                  (:research-run-assignment/root assignment))]
               (record-forensic-event
                state runner
                {:event-key :runner.forensic/execution-started
                 :subject-root root :subject-kind :research-runner/work
                 :causal-parent-roots [(:research-execution-job/root job)
                                       (:research-run-assignment/root assignment) root]
                 :parent-nodes (cond-> [] delivery-node (conj delivery-node))
                 :observed-root root}))))
        coordinator-root (:transport/coordinator-root runner)
        endpoint (:transport/coordinator-endpoint runner)]
    (when-not (and (hash-ref/valid-sha256-ref? coordinator-root)
                   (map? endpoint))
      (throw (ex-info "Runner lacks runtime coordinator return endpoint" {})))
    (let [accounting? (accounting-work/valid-work? work)
          executed (if accounting?
                     (accounting-work/execute-work! work)
                     (observed-work/execute-work! work))
          observed-run-evidence (:observed-run-evidence executed)
          run-assurance-evidence (:run-assurance-evidence executed)
          accounting-context (when accounting?
                               {:request (:accounting/request work)
                                :partition-plan (:accounting/partition-plan work)
                                :work-unit (:accounting/work-unit work)})
          accounting-partial (:accounting-partial executed)
          ;; An Anvil action is atomic in this adapter. A cancellation observed
          ;; after it completes cannot erase the observed result, but it may
          ;; stop the next stage (coordinator delivery).
          _ (when-let [hook (:transport/before-result-delivery runner)]
              (hook runner work logical-cutpoint))
          cancelled-after-execution? (contains? (:cancellations (runner-state runner)) job-root)
          returned (if cancelled-after-execution?
                     {:delivery/status :cancelled-before-delivery
                      :delivery/reason :cancellation-observed-after-atomic-execution}
                     (deliver-observed-result!
                      {:coordinator-root coordinator-root :endpoint endpoint
                       :assignment assignment
                       :observed-run-evidence observed-run-evidence
                       :run-assurance-evidence run-assurance-evidence
                       :accounting-context accounting-context
                       :accounting-partial accounting-partial
                       :logical-cutpoint logical-cutpoint}))]
      (update-runtime-state!
       runner
       (fn [state]
         (let [status (if (acknowledge-result? returned)
                        :result-acknowledged :completed)
               state (assoc-in state [:completed-work root]
                               {:observed-run-evidence observed-run-evidence
                                :run-assurance-evidence run-assurance-evidence
                                :accounting-context accounting-context
                                :accounting-partial accounting-partial
                                :result-delivery returned
                                :result-deliveries [returned]})]
           (let [state (append-work-event state root status logical-cutpoint :execution-completed)
                 start-node (runner-forensics/latest-node-hash
                             state :runner.forensic/execution-started root)
                 complete-node (runner-forensics/build-event-node
                                {:event-key :runner.forensic/execution-completed
                                 :subject-root (:research-observed-run-evidence/root observed-run-evidence)
                                 :subject-kind :research-runner/observed-run
                                 :actor-root (:runner/reference (:runner-participation runner))
                                 :causal-parent-roots [root
                                                       (:research-observed-run-evidence/root observed-run-evidence)
                                                       (:research-run-assurance-evidence/root run-assurance-evidence)]
                                 :parent-nodes (cond-> [] start-node (conj start-node))
                                 :observed-root (:semantic-result/root observed-run-evidence)})]
             (let [state (runner-forensics/record-node state complete-node)
                   post-node (when-let [reachability-root
                                        (get-in returned [:reachability
                                                          :research-realized-reachability/root])]
                               (runner-forensics/build-event-node
                                {:event-key :runner.forensic/result-posted
                                 :subject-root (:research-observed-run-evidence/root observed-run-evidence)
                                 :subject-kind :research-runner/result-delivery
                                 :actor-root (:runner/reference (:runner-participation runner))
                                 :causal-parent-roots [(:research-run-assignment/root assignment)
                                                       (:research-run-assurance-evidence/root run-assurance-evidence)
                                                       reachability-root]
                                 :parent-nodes [(:node-hash complete-node)]
                                 :observed-root (:research-run-assurance-evidence/root run-assurance-evidence)}))
                   state (cond-> state post-node (runner-forensics/record-node post-node))]
               (if (and post-node (acknowledge-result? returned))
                 (runner-forensics/record-node
                  state
                  (runner-forensics/build-event-node
                   {:event-key :runner.forensic/coordinator-acknowledged
                    :subject-root (:research-observed-run-evidence/root observed-run-evidence)
                    :subject-kind :research-runner/coordinator-acknowledgement
                    :actor-root (:runner/reference (:runner-participation runner))
                    :causal-parent-roots [(:research-run-assurance-evidence/root run-assurance-evidence)
                                          (get-in returned [:reachability
                                                            :research-realized-reachability/root])]
                    :parent-nodes [(:node-hash post-node)]
                    :observed-root (:research-run-assurance-evidence/root run-assurance-evidence)
                    :consequence? true}))
                 state))))))
      {:execution executed :accounting-partial accounting-partial
       :result-delivery returned})))

(defn execute-delivered-work! [runner work logical-cutpoint]
  (if (or (reproduction-work/valid-work? work) (native-accounting/valid-work? work))
    (execute-reproduction-delivered-work! runner work logical-cutpoint)
    (execute-delivered-work-existing! runner work logical-cutpoint)))

