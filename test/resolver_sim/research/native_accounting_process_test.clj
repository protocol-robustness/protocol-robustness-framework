(ns resolver-sim.research.native-accounting-process-test
  "Literal OS process-loss qualification of native accounting payloads."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [resolver-sim.research.native-accounting-work :as native]
            [resolver-sim.research.accelerated-accounting :as accounting]
            [resolver-sim.research.native-accounting-work-test :as fixture]
            [resolver-sim.research.reproduction-runner-test :as process-harness]
            [resolver-sim.research.runner-transport :as transport]
            [resolver-sim.research.runner-forensics :as forensics]
            [resolver-sim.research.execution-control :as control]))

;; Reuse the qualified process/HTTP harness, not a second execution protocol.
(def launch #'process-harness/launch-runner-process)
(def await-ready #'process-harness/await-process-file)
(def await-stage #'process-harness/await-process-stage)
(def await-state #'process-harness/await-runner-state)
(def kill! #'process-harness/kill-runner-process!)
(defn temp-path []
  (let [file (java.io.File/createTempFile "native-accounting-process-" ".edn")]
    (.delete file) file))
(defn endpoint [ready kind]
  {:transport/base-url (get ready kind) :transport/auth-secret fixture/secret
   :transport/request-timeout-ms 10000})
(defn child-config [participant journal coordinator]
  (cond-> {:runner-participation participant :auth-secret fixture/secret
           :state-path (.getAbsolutePath journal)}
    coordinator (assoc :coordinator-root fixture/coordinator-root
                       :coordinator-endpoint (transport/coordinator-endpoint coordinator fixture/secret 10000))))
(defn ready! [child]
  (await-ready (:ready-file child) (:process child) (:log-file child)))
(defn state! [journal predicate]
  (:runner-runtime/state (await-state journal predicate)))
(defn dispatch [coordinator ready work]
  (transport/dispatch-native-accounting!
   coordinator {:endpoint (endpoint ready :runner/base-url) :accounting-work work}))
(defn async-dispatch [coordinator ready work]
  (future (try (dispatch coordinator ready work) (catch Throwable e {:failure e}))))
(defn join! [task]
  (let [result (deref task 15000 ::timeout)]
    (when (= ::timeout result) (future-cancel task)
          (throw (ex-info "Dispatch did not terminate after process loss" {})))
    result))
(defn start-coordinator [assignment & [journal initial-dispatches]]
  (transport/start-coordinator!
   {:coordinator-root fixture/coordinator-root :assignments [assignment]
    :auth-secret fixture/secret :bind-address "127.0.0.1" :port 0
    :state-path (some-> journal .getAbsolutePath) :initial-dispatches initial-dispatches}))
(defn starts [state work-root]
  (count (filter #(= :execution-started (:work/event %))
                 (get-in state [:work-states work-root :work/history]))))

(deftest account-and-canonical-result-identity-do-not-depend-on-partition-count
  (let [whole (fixture/work-fixture)
        first-half (fixture/work-fixture 100 301 {:partitions [[(fixture/root 11)] [(fixture/root 12)]]})
        second-half (fixture/work-fixture 100 302 {:partitions [[(fixture/root 11)] [(fixture/root 12)]] :partition-index 1})
        complete (fn [basis works]
                   (accounting/reconcile
                    (:request basis) (:contract basis) (:plan basis)
                    (reduce accounting/ingest-partial {}
                            (map #(-> % :work native/execute-work! :accounting-partial) works))))
        serial-partition (complete whole [whole])
        split-partition (complete first-half [second-half first-half])]
    (is (= (get-in whole [:request :accounting-request/root])
           (get-in first-half [:request :accounting-request/root])))
    (is (not= (get-in whole [:plan :accounting-partition-plan/root])
              (get-in first-half [:plan :accounting-partition-plan/root])))
    (is (= (:canonical-accounting-result/root serial-partition)
           (:canonical-accounting-result/root split-partition)))
    (is (= (native/account-root (:request whole) (get-in whole [:work :accounting/work-unit]))
           (native/account-root (:request first-half) (get-in first-half [:work :accounting/work-unit]))))
    (is (= (accounting/serial-values (:request whole)) (:accounting/values split-partition)))))

(deftest killed-before-and-during-accounting-never-invents-completion
  (let [{:keys [work assignment participant]} (fixture/work-fixture)
        wr (:research-native-accounting-work/root work)
        coordinator (start-coordinator assignment)]
    (try
      (doseq [[stage before recovered count-starts]
              [[:before-safe-cutpoint :accepted :accepted 1]
               [:during-native-accounting-compute :executing :outcome-unknown 2]]]
        (let [journal (temp-path) entered (temp-path) release (temp-path)
              config (child-config participant journal coordinator)
              child (launch (assoc config :block-at stage :entered-path (.getAbsolutePath entered)
                                   :release-path (.getAbsolutePath release)))
              task (async-dispatch coordinator (ready! child) work)]
          (try
            (await-stage entered (:process child) (:log-file child) stage)
            (let [state (state! journal #(= before (get-in % [:work-states wr :work/status])))]
              (is (empty? (:completed-work state))))
            (kill! child)
            (join! task)
            (let [restarted (launch config)]
              (try
                (let [ready (ready! restarted)
                      state (state! journal #(= recovered (get-in % [:work-states wr :work/status])))]
                  (is (empty? (:completed-work state)))
                  (when (= recovered :outcome-unknown)
                    (is (= :execution-outcome-unknown-after-restart
                           (:work/event (peek (get-in state [:work-states wr :work/history]))))))
                  (dispatch coordinator ready work)
                  (let [complete (state! journal #(= :result-acknowledged (get-in % [:work-states wr :work/status])))
                        execution (get-in complete [:completed-work wr :native-accounting-execution])]
                    (is (= count-starts (starts complete wr)))
                    (is (= execution (native/validate-execution work execution)))))
                (finally (kill! restarted))))
            (finally (kill! child) (future-cancel task)
                     (doseq [file [journal entered release]] (.delete ^java.io.File file))))))
      (finally (transport/stop-coordinator! coordinator)))))

(deftest killed-after-persisted-accounting-redelivers-without-computation
  (let [{:keys [work assignment participant]} (fixture/work-fixture)
        wr (:research-native-accounting-work/root work)
        journal (temp-path) entered (temp-path) release (temp-path)
        coordinator (start-coordinator assignment)
        config (child-config participant journal coordinator)
        child (launch (assoc config :block-at :before-result-delivery
                             :entered-path (.getAbsolutePath entered) :release-path (.getAbsolutePath release)))
        task (async-dispatch coordinator (ready! child) work)]
    (try
      (await-stage entered (:process child) (:log-file child) :before-result-delivery)
      (let [saved (state! journal #(contains? (:completed-work %) wr))
            execution (get-in saved [:completed-work wr :native-accounting-execution])]
        (is (= execution (native/validate-execution work execution)))
        (kill! child) (join! task)
        ;; A recomputation would block at this actual-arithmetic hook and never
        ;; reach readiness; recovery must only deliver persisted evidence.
        (let [restarted (launch (assoc config :block-at :during-native-accounting-compute
                                      :entered-path (.getAbsolutePath entered) :release-path (.getAbsolutePath release)))]
          (try
            (ready! restarted)
            (let [state (state! journal #(= :result-acknowledged (get-in % [:work-states wr :work/status])))
                  entries (:native-accounting-results (transport/coordinator-state coordinator))]
              (is (= 1 (starts state wr)))
              (is (= execution (get-in state [:completed-work wr :native-accounting-execution])))
              (is (= 1 (count entries)))
              (is (:valid? (forensics/validate-runner-reconstruction state (transport/coordinator-state coordinator)))))
            (finally (kill! restarted)))))
      (finally (kill! child) (future-cancel task) (transport/stop-coordinator! coordinator)
               (doseq [file [journal entered release]] (.delete ^java.io.File file))))))

(deftest coordinator-killed-after-accounting-ingest-before-ack
  (let [{:keys [work assignment participant job]} (fixture/work-fixture)
        wr (:research-native-accounting-work/root work)
        runner-journal (temp-path) coordinator-journal (temp-path)
        entered (temp-path) release (temp-path) events (temp-path)
        child (launch {:fixture/main "resolver-sim.research.coordinator-process-fixture"
                       :coordinator-root fixture/coordinator-root :assignments [assignment]
                       :initial-dispatches [{:job job :assignment assignment :execution-work work}]
                       :auth-secret fixture/secret :state-path (.getAbsolutePath coordinator-journal)
                       :fixture/entered-path (.getAbsolutePath entered) :fixture/release-path (.getAbsolutePath release)
                       :fixture/event-path (.getAbsolutePath events)})
        runner (transport/start-runner!
                {:runner-participation participant :auth-secret fixture/secret :coordinator-root fixture/coordinator-root
                 :coordinator-endpoint (endpoint (ready! child) :coordinator/base-url)
                 :state-path (.getAbsolutePath runner-journal) :bind-address "127.0.0.1" :port 0})
        task (future (try (transport/deliver-job!
                           {:coordinator-root fixture/coordinator-root
                            :endpoint (transport/coordinator-endpoint runner fixture/secret 10000)
                            :job job :assignment assignment :execution-work work})
                         (catch Throwable e {:failure e})))]
    (try
      (await-stage entered (:process child) (:log-file child) :after-reproduction-persisted)
      (let [state (:coordinator-runtime/state (edn/read-string (slurp coordinator-journal)))]
        (is (= 1 (count (:native-accounting-results state))))
        (is (= 1 (count (get-in state [:accounting-partials (get-in work [:accounting/partition-plan :accounting-partition-plan/root])])))))
      (kill! child) (join! task)
      (state! runner-journal #(= :unreachable (get-in % [:completed-work wr :result-delivery :delivery/status])))
      (let [coordinator (start-coordinator assignment coordinator-journal)]
        (try
          (transport/retry-undelivered-reproduction-results!
           runner (transport/coordinator-endpoint coordinator fixture/secret 10000) 1)
          (let [state (state! runner-journal #(= :result-acknowledged (get-in % [:work-states wr :work/status])))
                cstate (transport/coordinator-state coordinator)]
            (is (= 1 (count (:native-accounting-results cstate))))
            (is (= 1 (count (get-in cstate [:accounting-partials (get-in work [:accounting/partition-plan :accounting-partition-plan/root])]))))
            (is (= 2 (count (get-in state [:completed-work wr :result-deliveries]))))
            (is (= :already-recorded (get-in state [:completed-work wr :result-delivery :delivery/status])))
            (is (= 1 (starts state wr)))
            (is (:valid? (forensics/validate-runner-reconstruction state cstate))))
          (finally (transport/stop-coordinator! coordinator))))
      (finally (kill! child) (future-cancel task) (transport/stop-runner! runner)
               (doseq [file [runner-journal coordinator-journal entered release events]] (.delete ^java.io.File file))))))

(deftest cancellation-before-accounting-survives-literal-restart
  (let [{:keys [work assignment participant job]} (fixture/work-fixture)
        journal (temp-path) config (child-config participant journal nil)
        child (launch config)
        cancellation (control/build-cancellation
                      {:job-root (:research-execution-job/root job) :control-epoch 0 :reason :user-requested
                       :requested-cutpoint 0 :scope :entire-job :target-roots []})]
    (try
      (is (= :observed (:delivery/status (transport/deliver-cancellation!
                                        {:coordinator-root fixture/coordinator-root :endpoint (endpoint (ready! child) :runner/base-url)
                                         :cancellation cancellation :runner-participation participant :assignment assignment}))))
      (kill! child)
      (let [restarted (launch config)]
        (try
          (let [delivery (transport/deliver-job!
                          {:coordinator-root fixture/coordinator-root :endpoint (endpoint (ready! restarted) :runner/base-url)
                           :job job :assignment assignment :execution-work work})
                state (state! journal #(contains? (:cancellations %) (:research-execution-job/root job)))]
            (is (= :cancelled-before-start (:delivery/status delivery)))
            (is (empty? (:completed-work state)))
            (is (zero? (starts state (:research-native-accounting-work/root work)))))
          (finally (kill! restarted))))
      (finally (kill! child) (.delete journal)))))

(deftest cancellation-after-accounting-retains-completion-across-kill
  (let [{:keys [work assignment participant job]} (fixture/work-fixture)
        wr (:research-native-accounting-work/root work)
        journal (temp-path) entered (temp-path) release (temp-path)
        coordinator (start-coordinator assignment)
        config (child-config participant journal coordinator)
        child (launch (assoc config :block-at :before-result-delivery
                             :entered-path (.getAbsolutePath entered) :release-path (.getAbsolutePath release)))
        ready (ready! child) task (async-dispatch coordinator ready work)
        cancellation (control/build-cancellation
                      {:job-root (:research-execution-job/root job) :control-epoch 0 :reason :user-requested
                       :requested-cutpoint 1 :scope :entire-job :target-roots []})]
    (try
      (await-stage entered (:process child) (:log-file child) :before-result-delivery)
      (let [saved (get-in (state! journal #(contains? (:completed-work %) wr)) [:completed-work wr :native-accounting-execution])]
        (is (= :observed (:delivery/status (transport/deliver-cancellation!
                                          {:coordinator-root fixture/coordinator-root :endpoint (endpoint ready :runner/base-url)
                                           :cancellation cancellation :runner-participation participant :assignment assignment}))))
        ;; Kill without releasing the blocked delivery handler: cancellation
        ;; must already have durably suppressed pending result delivery.
        (kill! child) (join! task)
        (let [restarted (launch config)]
          (try
            (ready! restarted)
            (let [state (state! journal #(= :cancelled-before-delivery (get-in % [:completed-work wr :result-delivery :delivery/status])))]
              (is (= saved (get-in state [:completed-work wr :native-accounting-execution])))
              (is (= saved (native/validate-execution work saved)))
              (is (= 1 (starts state wr)))
              (is (empty? (:native-accounting-results (transport/coordinator-state coordinator)))))
            (finally (kill! restarted)))))
      (finally (kill! child) (future-cancel task) (transport/stop-coordinator! coordinator)
               (doseq [file [journal entered release]] (.delete ^java.io.File file))))))

(deftest live-cross-host-accounting-partition-reconciles-with-local-results
  ;; Uses the same SSH loopback/reverse tunnel as the existing cross-host
  ;; reproduction qualification. The runner URL is runtime-only.
  (if-not (and (seq (System/getenv "PRF_REMOTE_ACCOUNTING_RUNNER_URL"))
               (seq (System/getenv "PRF_REMOTE_ACCOUNTING_COORDINATOR_PORT")))
    (is true "set PRF_REMOTE_ACCOUNTING_RUNNER_URL and PRF_REMOTE_ACCOUNTING_COORDINATOR_PORT for live-host qualification")
    (let [partitions [[(fixture/root 11)] [(fixture/root 12)]]
          local (fixture/work-fixture 100 301 {:partitions partitions :partition-index 0})
          remote-work (:work (fixture/work-fixture 100 302 {:partitions partitions :partition-index 1}))
          plan (:plan local) request (:request local) contract (:contract local)
          local-work (:work local)
          port (Integer/parseInt (System/getenv "PRF_REMOTE_ACCOUNTING_COORDINATOR_PORT"))
          coordinator (transport/start-coordinator!
                       {:coordinator-root fixture/coordinator-root
                        :assignments [(:assignment local) (:work/assignment remote-work)]
                        :auth-secret fixture/secret :bind-address "127.0.0.1" :port port})
          local-runner (transport/start-runner!
                        {:runner-participation (:participant local) :auth-secret fixture/secret
                         :coordinator-root fixture/coordinator-root
                         :coordinator-endpoint (transport/coordinator-endpoint coordinator fixture/secret 30000)
                         :bind-address "127.0.0.1" :port 0})
          remote-endpoint {:transport/base-url (System/getenv "PRF_REMOTE_ACCOUNTING_RUNNER_URL")
                           :transport/auth-secret fixture/secret :transport/request-timeout-ms 30000}]
      (try
        (let [local-dispatch (transport/dispatch-native-accounting!
                              coordinator {:endpoint (transport/coordinator-endpoint local-runner fixture/secret 30000)
                                           :accounting-work local-work :logical-cutpoint 1})
              local-result (:native-accounting-execution local-dispatch)
              remote-dispatch (transport/dispatch-native-accounting!
                               coordinator {:endpoint remote-endpoint :accounting-work remote-work :logical-cutpoint 2})
              _ (is (= :accepted (get-in remote-dispatch [:delivery :delivery/status]))
                    (pr-str (:delivery remote-dispatch)))
              remote-result (:native-accounting-execution remote-dispatch)
              _ (is remote-result "remote runner must deliver an admitted accounting execution")
              local-full (reduce accounting/ingest-partial {}
                                 [(:accounting-partial local-result)
                                  (:accounting-partial (native/execute-work! remote-work))])
              local-reconciliation (accounting/reconcile request contract plan local-full)
              distributed (get-in (transport/coordinator-state coordinator)
                                  [:accounting-partials (:accounting-partition-plan/root plan)])
              remote-reconciliation (when (= 2 (count distributed))
                                      (accounting/reconcile request contract plan distributed))]
          (is (:native-accounting-execution (transport/dispatch-native-accounting!
                                              coordinator {:endpoint remote-endpoint
                                                           :accounting-work remote-work :logical-cutpoint 3})))
          (is (= {:accounting/bond 25} (:accounting/values remote-reconciliation)))
          (is (= (:accounting/values local-reconciliation) (:accounting/values remote-reconciliation)))
          (is (= (:canonical-accounting-result/root local-reconciliation)
                 (:canonical-accounting-result/root remote-reconciliation)))
          (is (= 2 (count distributed)))
          (is (= (accounting/serial-values request) (:accounting/values remote-reconciliation)))
          (is (not= (native/execution-root local-result) (native/execution-root remote-result)))
          (is (= :complete (:accounting/completeness
                            (accounting/accounting-completeness request plan distributed)))))
        (finally (transport/stop-runner! local-runner) (transport/stop-coordinator! coordinator))))))
