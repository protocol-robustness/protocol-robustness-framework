(ns resolver-sim.research.reproduction-runner-test
  (:require [clojure.edn]
            [clojure.test :refer [deftest is]]
            [resolver-sim.research.execution-control :as control]
            [resolver-sim.research.interaction-reproduction-test :as replay-fixture]
            [resolver-sim.research.remote-execution :as remote]
            [resolver-sim.research.remote-execution-test :as remote-fixture]
            [resolver-sim.research.interaction-reproduction :as reproduction]
            [resolver-sim.research.reproduction-execution-work :as work]
            [resolver-sim.research.runner-transport :as transport]))

(def secret "durable-reproduction-test-secret")
(def coordinator-root (remote-fixture/fake-root \c))

(defn- launch-runner-process [config]
  (let [fixture-main (or (:fixture/main config)
                         "resolver-sim.research.runner-process-fixture")
        config (dissoc config :fixture/main)
        config-file (java.io.File/createTempFile "reproduction-runner-config-" ".edn")
        ready-file (java.io.File/createTempFile "reproduction-runner-ready-" ".edn")
        log-file (java.io.File/createTempFile "reproduction-runner-log-" ".txt")]
    (.delete ready-file)
    (spit config-file (pr-str config))
    (let [builder (doto (ProcessBuilder.
                         ^java.util.List
                         ["clojure" "-M:test" "-m"
                          fixture-main
                          (.getAbsolutePath config-file) (.getAbsolutePath ready-file)])
                    (.directory (java.io.File. (System/getProperty "user.dir")))
                    (.redirectErrorStream true)
                    (.redirectOutput log-file))]
      {:process (.start builder) :config-file config-file
       :ready-file ready-file :log-file log-file})))

(defn- await-process-file [file process log-file]
  (let [deadline (+ (System/currentTimeMillis) 60000)]
    (loop []
      (cond
        (.exists ^java.io.File file)
        (let [ready (try (clojure.edn/read-string (slurp file))
                         (catch Exception _ nil))]
          (if (or (and (map? ready) (:runner/ready? ready)
                       (string? (:runner/base-url ready)))
                  (and (map? ready) (:coordinator/ready? ready)
                       (string? (:coordinator/base-url ready))))
            ready
            (do (Thread/sleep 20) (recur))))
        (not (.isAlive ^Process process))
        (throw (ex-info "Runner process exited before fixture marker"
                        {:exit (.exitValue ^Process process) :log (slurp log-file)}))
        (> (System/currentTimeMillis) deadline)
        (throw (ex-info "Timed out waiting for runner process fixture" {}))
        :else (do (Thread/sleep 20) (recur))))))

(defn- await-process-stage [file process log-file stage]
  (let [deadline (+ (System/currentTimeMillis) 60000)]
    (loop []
      (let [marker (when (.exists ^java.io.File file)
                     (try (clojure.edn/read-string (slurp file))
                          (catch Exception _ nil)))]
        (cond
          (= stage (or (:fixture/stage marker) (:stage marker))) marker
          (not (.isAlive ^Process process))
          (throw (ex-info "Coordinator process exited before fixture stage"
                          {:stage stage :exit (.exitValue ^Process process)
                           :log (slurp log-file) :marker marker}))
          (> (System/currentTimeMillis) deadline)
          (throw (ex-info "Timed out waiting for coordinator fixture stage"
                          {:stage stage :marker marker :log (slurp log-file)}))
          :else (do (Thread/sleep 20) (recur)))))))

(defn- await-runner-state [file pred]
  (let [deadline (+ (System/currentTimeMillis) 60000)]
    (loop []
      (let [journal (when (.exists ^java.io.File file)
                      (try (transport/read-runtime-journal-file (.getPath file))
                           (catch Exception _ nil)))]
        (cond
          (and journal (pred (:runner-runtime/state journal))) journal
          (> (System/currentTimeMillis) deadline)
          (throw (ex-info "Timed out waiting for runner state" {:journal journal}))
          :else (do (Thread/sleep 20) (recur)))))))

(defn- await-fixture-event [file process log-file event]
  (let [deadline (+ (System/currentTimeMillis) 60000)]
    (loop []
      (let [events (when (.exists ^java.io.File file)
                     (try (clojure.edn/read-string (slurp file))
                          (catch Exception _ nil)))]
        (cond
          (and (vector? events) (some #{event} events)) events
          (not (.isAlive ^Process process))
          (throw (ex-info "Coordinator process exited before fixture event"
                          {:event event :exit (.exitValue ^Process process)
                           :log (slurp log-file) :events events}))
          (> (System/currentTimeMillis) deadline)
          (throw (ex-info "Timed out waiting for coordinator fixture event"
                          {:event event :events events :log (slurp log-file)}))
          :else (do (Thread/sleep 20) (recur)))))))

(defn- kill-runner-process! [info]
  (when-let [^Process process (:process info)]
    (when (.isAlive process)
      (.destroyForcibly process)
      (.waitFor process)))
  (doseq [file [(:config-file info) (:ready-file info) (:log-file info)]]
    (when file (.delete ^java.io.File file))))

(defn setup-artifacts []
  (let [{:keys [input implementation original]} (replay-fixture/fixture)
        env (remote-fixture/anvil-environment)
        profile (remote-fixture/local-profile)
        policy (remote-fixture/execution-policy
                {:required-runs 1 :required-matching-runs 1
                 :distinct-by [] :repeat-runner-policy :permitted
                 :qualification-references []})
        job (remote/build-job
             {:job-class :job/research-calculation
              :workload-root (:reproduction-input/root input)
              :distributed-profile-root (:distributed-experiment-profile/root profile)
              :distributed-policy-root
              (:distributed-scheduler-policy/root remote-fixture/distributed-policy-artifact)
              :execution-environment-root (:research-execution-environment/root env)
              :execution-policy-root (:research-execution-policy/root policy)})
        participant (remote-fixture/runner \d :identified :site/a)
        assignment (remote/steal-next job env policy participant [])
        bundle (work/build-work {:job job :assignment assignment
                                 :runner-participation participant
                                 :reproduction-input input :implementation implementation})]
    {:input input :implementation implementation :original original :env env
     :profile profile :policy policy :job job :participant participant
     :assignment assignment :bundle bundle}))

(defn start-coordinator
  ([assignment state-path] (start-coordinator assignment state-path nil))
  ([assignment state-path test-event]
   (transport/start-coordinator!
    {:coordinator-root coordinator-root :assignments [assignment]
     :auth-secret secret :bind-address "127.0.0.1" :port 0 :state-path state-path
     :test-event test-event})))

(defn start-runner [artifacts endpoint state-path
                    & [{:keys [before-safe-cutpoint before-result-delivery test-event]}]]
  (transport/start-runner!
   {:runner-participation (:participant artifacts) :auth-secret secret
    :coordinator-root (when endpoint coordinator-root) :coordinator-endpoint endpoint
    :state-path state-path :before-safe-cutpoint before-safe-cutpoint
    :before-result-delivery before-result-delivery
    :test-event test-event
    :bind-address "127.0.0.1" :port 0}))

(deftest durable-remote-replay-and-duplicate-admission
  (let [{:keys [job assignment bundle original] :as a} (setup-artifacts)
        coordinator (start-coordinator assignment nil)
        coordinator-endpoint (transport/coordinator-endpoint coordinator secret)
        runner (start-runner a coordinator-endpoint nil)]
    (try
      (let [endpoint (transport/coordinator-endpoint runner secret 30000)
            local-execution (reproduction/execute (:input a) (:implementation a))
            result (transport/dispatch-reproduction!
                    coordinator {:endpoint endpoint :job job :assignment assignment
                                 :reproduction-work bundle :authoritative-result original})
            duplicate (transport/dispatch-job!
                       coordinator {:endpoint endpoint :job job :assignment assignment
                                    :execution-work bundle :logical-cutpoint 2})
            duplicate-result (transport/deliver-reproduction-result!
                              {:coordinator-root coordinator-root :endpoint coordinator-endpoint
                               :job job :assignment assignment
                               :reproduction-execution (:reproduction-execution result)
                               :logical-cutpoint 3})
            admitted (vals (:reproduction-results (transport/coordinator-state coordinator)))]
        (is (= :reproduced (:reproduction/status (:reproduction-evidence result))))
        (is (= (:reproduction-input/root (:work/reproduction-input bundle))
               (:reproduction-input/root (:reproduction-execution result))))
        (is (= (:reproduction-execution/root local-execution)
               (:reproduction-execution/root (:reproduction-execution result))))
        (is (= (reproduction/compare-execution local-execution original)
               (:reproduction-evidence result)))
        (is (= 1 (count admitted)))
        (is (= :already-completed (:delivery/status duplicate)))
        (is (= :already-recorded (:delivery/status duplicate-result)))
        (is (= 1 (count (filter #(= :runner.forensic/execution-started
                                    (get-in % [:extensions :forensic/observation-key]))
                                (vals (:forensic-evidence-nodes (transport/runner-state runner)))))))
        (is (= assignment
               (:assignment (first admitted))))
        (is (not (contains? (:execution-work
                             (get-in (transport/coordinator-state coordinator)
                                     [:dispatch-intents
                                      (:research-run-assignment/root assignment)]))
                            :authoritative-result))))
      (finally
        (transport/stop-runner! runner)
        (transport/stop-coordinator! coordinator)))))

(deftest accepted-work-retries-after-pre-execution-runner-interruption
  (let [{:keys [job assignment bundle original] :as a} (setup-artifacts)
        coordinator (start-coordinator assignment nil)
        coordinator-endpoint (transport/coordinator-endpoint coordinator secret)
        journal (java.io.File/createTempFile "reproduction-prestart-state-" ".edn")
        _journal-deleted (.delete journal)
        path (.getAbsolutePath journal)
        failing-runner (start-runner a coordinator-endpoint path
                                     {:before-safe-cutpoint
                                      (fn [_ _ _]
                                        (throw (ex-info "simulated loss before safe cutpoint" {})))})]
    (try
      (let [failing-endpoint (transport/coordinator-endpoint failing-runner secret 30000)]
        (is (thrown? clojure.lang.ExceptionInfo
                     (transport/dispatch-job!
                      coordinator {:endpoint failing-endpoint :job job :assignment assignment
                                   :execution-work bundle})))
        (is (= :accepted
               (get-in (transport/runner-state failing-runner)
                       [:work-states (:research-reproduction-execution-work/root bundle)
                        :work/status])))
        (transport/stop-runner! failing-runner)
        (let [restarted (start-runner a coordinator-endpoint path)
              endpoint (transport/coordinator-endpoint restarted secret 30000)]
          (try
            (transport/retry-pending-dispatches!
             coordinator (constantly endpoint) 2)
            (let [entries (vals (:reproduction-results (transport/coordinator-state coordinator)))
                  execution (:reproduction-execution (first entries))]
              (is (= 1 (count entries)))
              (is (= :reproduced
                     (:reproduction/status (reproduction/compare-execution execution original))))
              (is (= 1 (count (filter #(= :runner.forensic/execution-started
                                          (get-in % [:extensions :forensic/observation-key]))
                                      (vals (:forensic-evidence-nodes
                                             (transport/runner-state restarted))))))))
            (finally (transport/stop-runner! restarted)))))
      (finally
        (when (:transport/server failing-runner) (transport/stop-runner! failing-runner))
        (transport/stop-coordinator! coordinator)
        (.delete journal)))))

(deftest completed-work-is-redelivered-after-runner-restart
  (let [{:keys [job assignment bundle original] :as a} (setup-artifacts)
        coordinator-journal (java.io.File/createTempFile "reproduction-coordinator-state-" ".edn")
        _coordinator-journal-deleted (.delete coordinator-journal)
        coordinator-path (.getAbsolutePath coordinator-journal)
        coordinator (start-coordinator assignment coordinator-path)
        good-endpoint (transport/coordinator-endpoint coordinator secret)
        journal (java.io.File/createTempFile "reproduction-runner-state-" ".edn")
        _journal-deleted (.delete journal)
        path (.getAbsolutePath journal)
        runner (start-runner a good-endpoint path
                             {:before-result-delivery
                              (fn [& _] (transport/stop-coordinator! coordinator))})]
    (try
      (let [endpoint (transport/coordinator-endpoint runner secret 30000)
            first-delivery (transport/dispatch-job!
                            coordinator {:endpoint endpoint :job job :assignment assignment
                                         :execution-work bundle})
            state-after-execution (transport/runner-state runner)]
        (is (= :accepted (:delivery/status first-delivery)))
        (is (= :unreachable
               (get-in state-after-execution
                       [:completed-work (:research-reproduction-execution-work/root bundle)
                        :result-delivery :delivery/status])))
        (transport/stop-runner! runner)
        (transport/stop-coordinator! coordinator)
        (let [recovered-coordinator (start-coordinator assignment coordinator-path)
              recovered-endpoint (transport/coordinator-endpoint recovered-coordinator secret)
              restarted (start-runner a recovered-endpoint path)
              state-after-restart (transport/runner-state restarted)
              delivered (vals (:reproduction-results
                               (transport/coordinator-state recovered-coordinator)))]
          (try
            (is (= 1 (count delivered)))
            (is (= 1 (count (filter #(= :runner.forensic/execution-started
                                        (get-in % [:extensions :forensic/observation-key]))
                                    (vals (:forensic-evidence-nodes state-after-restart))))))
            (is (= :result-acknowledged
                   (get-in state-after-restart
                           [:work-states (:research-reproduction-execution-work/root bundle)
                            :work/status])))
            (is (= (:reproduction-execution/root
                    (:reproduction-execution
                     (first delivered)))
                   (:reproduction-execution/root
                    (get-in state-after-restart
                            [:completed-work (:research-reproduction-execution-work/root bundle)
                             :reproduction-execution]))))
            (is (= :reproduced
                   (:reproduction/status
                    (reproduction/compare-execution
                     (:reproduction-execution
                      (get-in state-after-restart
                              [:completed-work (:research-reproduction-execution-work/root bundle)]))
                     original))))
            (is (:valid? (transport/forensic-reconstruction restarted recovered-coordinator)))
            (finally
              (transport/stop-runner! restarted)
              (transport/stop-coordinator! recovered-coordinator)))))
      (finally
        (when (:transport/server runner) (transport/stop-runner! runner))
        (transport/stop-coordinator! coordinator)
        (.delete journal)
        (.delete coordinator-journal)))))

(deftest cancellation-before-execution-prevents-work
  (let [{:keys [job assignment bundle] :as a} (setup-artifacts)
        runner (start-runner a nil nil)
        cancellation (control/build-cancellation
                      {:job-root (:research-execution-job/root job)
                       :control-epoch 0 :reason :user-requested
                       :requested-cutpoint 1 :scope :entire-job :target-roots []})]
    (try
      (let [runner-endpoint (transport/coordinator-endpoint runner secret)
            control-delivery (transport/deliver-cancellation!
                              {:coordinator-root coordinator-root :endpoint runner-endpoint
                               :cancellation cancellation
                               :runner-participation (:participant a)
                               :assignment assignment :logical-cutpoint 1})
            delivery (transport/deliver-job!
                      {:coordinator-root coordinator-root :endpoint runner-endpoint
                       :job job :assignment assignment :execution-work bundle})]
        (is (= :observed (:delivery/status control-delivery)))
        (is (= :cancelled-before-start (:delivery/status delivery)))
        (is (empty? (:completed-work (transport/runner-state runner)))))
      (finally (transport/stop-runner! runner)))))

(deftest cancellation-after-atomic-reproduction-retains-execution
  (let [{:keys [job assignment bundle] :as a} (setup-artifacts)
        coordinator (start-coordinator assignment nil)
        coordinator-endpoint (transport/coordinator-endpoint coordinator secret)
        cancellation (control/build-cancellation
                      {:job-root (:research-execution-job/root job)
                       :control-epoch 0 :reason :user-requested
                       :requested-cutpoint 2 :scope :entire-job :target-roots []})
        execution-finished (promise)
        continue-delivery (promise)
        runner (start-runner
                a coordinator-endpoint nil
                {:before-result-delivery
                 (fn [& _]
                   (deliver execution-finished true)
                   @continue-delivery)})]
    (try
      (let [endpoint (transport/coordinator-endpoint runner secret 30000)
            dispatch-result (promise)
            _dispatch-thread (doto
                              (Thread.
                               (fn []
                                 (deliver dispatch-result
                                          (try
                                            {:value
                                             (transport/dispatch-job!
                                              coordinator {:endpoint endpoint :job job
                                                           :assignment assignment
                                                           :execution-work bundle})}
                                            (catch Throwable failure
                                              {:failure failure})))))
                               (.start))
            _ @execution-finished
            cancellation-delivery
            (transport/deliver-cancellation!
             {:coordinator-root coordinator-root
              :endpoint endpoint :cancellation cancellation
              :runner-participation (:participant a) :assignment assignment
              :logical-cutpoint 3})
            _ (deliver continue-delivery true)
            dispatch-outcome @dispatch-result
            completed (get-in (transport/runner-state runner)
                              [:completed-work (:research-reproduction-execution-work/root bundle)])]
        (is (nil? (:failure dispatch-outcome)))
        (is (= :observed (:delivery/status cancellation-delivery)))
        (is (reproduction/valid-execution? (:reproduction-execution completed)))
        (is (= :cancelled-before-delivery
               (get-in completed [:result-delivery :delivery/status])))
        (is (= (:research-job-cancellation/root cancellation)
               (get-in (transport/runner-state runner)
                       [:cancellations (:research-execution-job/root job)
                        :research-job-cancellation/root])))
        (is (empty? (:reproduction-results (transport/coordinator-state coordinator)))))
      (finally
        (transport/stop-runner! runner)
        (transport/stop-coordinator! coordinator)))))

(deftest identical-reproductions-keep-per-assignment-forensic-records
  (let [{:keys [input implementation original env profile participant]} (setup-artifacts)
        policy (remote-fixture/execution-policy
                {:required-runs 2 :required-matching-runs 2
                 :distinct-by [] :repeat-runner-policy :permitted
                 :qualification-references []})
        job (remote/build-job
             {:job-class :job/research-calculation
              :workload-root (:reproduction-input/root input)
              :distributed-profile-root (:distributed-experiment-profile/root profile)
              :distributed-policy-root
              (:distributed-scheduler-policy/root remote-fixture/distributed-policy-artifact)
              :execution-environment-root (:research-execution-environment/root env)
              :execution-policy-root (:research-execution-policy/root policy)})
        assignment-a (remote/steal-next job env policy participant [])
        assignment-b (remote/steal-next job env policy participant [assignment-a])
        bundle-a (work/build-work {:job job :assignment assignment-a
                                   :runner-participation participant
                                   :reproduction-input input :implementation implementation})
        bundle-b (work/build-work {:job job :assignment assignment-b
                                   :runner-participation participant
                                   :reproduction-input input :implementation implementation})
        coordinator (transport/start-coordinator!
                     {:coordinator-root coordinator-root
                      :assignments [assignment-a assignment-b]
                      :auth-secret secret :bind-address "127.0.0.1" :port 0})
        runner (start-runner
                {:participant participant}
                (transport/coordinator-endpoint coordinator secret) nil)]
    (try
      (let [endpoint (transport/coordinator-endpoint runner secret 30000)
            first-run (transport/dispatch-reproduction!
                       coordinator {:endpoint endpoint :job job :assignment assignment-a
                                    :reproduction-work bundle-a :authoritative-result original})
            second-run (transport/dispatch-reproduction!
                        coordinator {:endpoint endpoint :job job :assignment assignment-b
                                     :reproduction-work bundle-b :authoritative-result original})
            results (vals (:reproduction-results (transport/coordinator-state coordinator)))]
        (is (= (:reproduction-execution/root (:reproduction-execution first-run))
               (:reproduction-execution/root (:reproduction-execution second-run))))
        (is (= 2 (count results)))
        (is (= 2 (count (set (map #(get-in % [:assignment :research-run-assignment/root])
                                  results)))))
        (is (= :reproduced
               (:reproduction/status (:reproduction-evidence second-run)))))
      (finally
        (transport/stop-runner! runner)
        (transport/stop-coordinator! coordinator)))))

(deftest process-kill-reproduction-cutpoints-recover-conservatively
  (let [{:keys [job assignment bundle participant]} (setup-artifacts)
        work-root (:research-reproduction-execution-work/root bundle)
        coordinator (transport/start-coordinator!
                     {:coordinator-root coordinator-root :assignments [assignment]
                      :auth-secret secret :bind-address "127.0.0.1" :port 0})
        coordinator-endpoint (transport/coordinator-endpoint coordinator secret 30000)
        cases [{:stage :before-safe-cutpoint :expected :accepted}
               {:stage :before-reproduction-compute :expected :outcome-unknown}]]
    (try
      (doseq [{:keys [stage expected]} cases]
        (let [journal (java.io.File/createTempFile "reproduction-kill-state-" ".edn")
              entered (java.io.File/createTempFile "reproduction-kill-entered-" ".edn")
              release (java.io.File/createTempFile "reproduction-kill-release-" ".edn")
              _ (do (.delete journal) (.delete entered) (.delete release))
              child (launch-runner-process
                     {:runner-participation participant :auth-secret secret
                      :state-path (.getAbsolutePath journal)
                      :coordinator-root coordinator-root
                      :coordinator-endpoint coordinator-endpoint
                      :block-at stage :entered-path (.getAbsolutePath entered)
                      :release-path (.getAbsolutePath release)})
              ready (await-process-file (:ready-file child) (:process child) (:log-file child))
              endpoint {:transport/base-url (:runner/base-url ready)
                        :transport/auth-secret secret :transport/request-timeout-ms 30000}
              dispatch (future
                         (try
                           (transport/dispatch-job!
                            coordinator {:endpoint endpoint :job job :assignment assignment
                                         :execution-work bundle})
                           (catch Throwable failure {:failure failure})))]
          (try
            (await-process-stage entered (:process child) (:log-file child) stage)
            (let [pre-kill-status (if (= :outcome-unknown expected) :executing expected)
                  before (await-runner-state journal
                                             #(= pre-kill-status
                                                 (get-in % [:work-states work-root :work/status])))]
              (is (= pre-kill-status (get-in before [:runner-runtime/state :work-states
                                                     work-root :work/status]))))
            (kill-runner-process! child)
            @dispatch
            (let [restarted (launch-runner-process
                             {:runner-participation participant :auth-secret secret
                              :state-path (.getAbsolutePath journal)
                              :coordinator-root coordinator-root
                              :coordinator-endpoint coordinator-endpoint})]
              (try
                (let [restarted-ready
                      (await-process-file (:ready-file restarted) (:process restarted)
                                          (:log-file restarted))]
                  (when (= :outcome-unknown expected)
                    (let [recovered (await-runner-state
                                     journal #(= :outcome-unknown
                                                 (get-in % [:work-states work-root :work/status])))]
                      (is (empty? (get-in recovered [:runner-runtime/state
                                                     :completed-work work-root])))
                      (is (= :execution-outcome-unknown-after-restart
                             (:work/event
                              (peek (get-in recovered [:runner-runtime/state :work-states
                                                       work-root :work/history])))))))
                ;; Resend the exact rooted bundle. Accepted work starts once;
                ;; outcome-unknown work starts a new attempt, never fabricates
                ;; the lost attempt's result.
                  (let [runner-endpoint {:transport/base-url (:runner/base-url restarted-ready)
                                         :transport/auth-secret secret
                                         :transport/request-timeout-ms 30000}]
                    (transport/dispatch-job!
                     coordinator {:endpoint runner-endpoint :job job :assignment assignment
                                  :execution-work bundle})
                    (let [terminal (await-runner-state
                                    journal #(= :result-acknowledged
                                                (get-in % [:work-states work-root :work/status])))
                          state (:runner-runtime/state terminal)
                          starts (filter #(= :execution-started (:work/event %))
                                         (get-in state [:work-states work-root :work/history]))]
                      (is (= (if (= :outcome-unknown expected) 2 1) (count starts)))
                      (is (= :result-acknowledged
                             (get-in state [:work-states work-root :work/status]))))))
                (finally (kill-runner-process! restarted))))
            (finally
              (kill-runner-process! child)
              (doseq [file [journal entered release]] (.delete ^java.io.File file))))))
      (finally (transport/stop-coordinator! coordinator)))))

(deftest coordinator-process-kill-after-persisted-result-redelivers-idempotently
  (let [{:keys [job assignment bundle] :as artifacts} (setup-artifacts)
        work-root (:research-reproduction-execution-work/root bundle)
        runner-journal (java.io.File/createTempFile "reproduction-coordinator-loss-runner-" ".edn")
        coordinator-journal (java.io.File/createTempFile "reproduction-coordinator-loss-coordinator-" ".edn")
        entered (java.io.File/createTempFile "reproduction-coordinator-loss-entered-" ".edn")
        release (java.io.File/createTempFile "reproduction-coordinator-loss-release-" ".edn")
        events (java.io.File/createTempFile "reproduction-coordinator-loss-events-" ".edn")
        trace-file (java.io.File/createTempFile "reproduction-coordinator-loss-trace-" ".edn")
        trace (atom [])
        trace! (fn [event]
                 (locking trace
                   (swap! trace conj event)
                   (spit trace-file (pr-str @trace))))
        _ (do (.delete runner-journal) (.delete coordinator-journal)
              (.delete entered) (.delete release) (.delete events) (.delete trace-file))
        child (launch-runner-process
               {:fixture/main "resolver-sim.research.coordinator-process-fixture"
                :coordinator-root coordinator-root :assignments [assignment]
                :initial-dispatches [{:job job :assignment assignment
                                      :execution-work bundle}]
                :auth-secret secret :state-path (.getAbsolutePath coordinator-journal)
                :fixture/entered-path (.getAbsolutePath entered)
                :fixture/event-path (.getAbsolutePath events)
                :fixture/release-path (.getAbsolutePath release)})
        first-runner (atom nil)]
    (try
      (let [ready (await-process-file (:ready-file child) (:process child) (:log-file child))
            blocked-endpoint {:transport/base-url (:coordinator/base-url ready)
                              :transport/auth-secret secret :transport/request-timeout-ms 30000}]
        (is (:coordinator/ready? ready))
        (is (re-find #"^http://127\.0\.0\.1:[0-9]+$"
                     (:transport/base-url blocked-endpoint)))
        (let [journal (transport/read-runtime-journal-file (.getPath coordinator-journal))
              intent (get-in journal [:coordinator-runtime/state :dispatch-intents
                                      (:research-run-assignment/root assignment)])]
          (is (= job (:job intent)))
          (is (= assignment (:assignment intent)))
          (is (= bundle (:execution-work intent))))
        (is (some #{:listener-accepting}
                  (clojure.edn/read-string (slurp events))))
        (reset! first-runner (start-runner artifacts blocked-endpoint
                                           (.getAbsolutePath runner-journal)
                                           {:test-event trace!}))
        (let [delivery (future
                         (transport/deliver-job!
                          {:coordinator-root coordinator-root
                           :endpoint (transport/coordinator-endpoint @first-runner secret 30000)
                           :job job :assignment assignment :execution-work bundle}))]
          (await-fixture-event events (:process child) (:log-file child)
                               :handler-entered)
          (await-fixture-event events (:process child) (:log-file child)
                               :request-body-received)
          ;; The coordinator has already atomically persisted the entry but has
          ;; deliberately not returned its acknowledgement.
          (await-process-stage entered (:process child) (:log-file child)
                               :after-reproduction-persisted)
          (trace! :parent-observed-persisted-result)
          (is (= [:listener-accepting :handler-entered :request-body-received
                  :new-result-recognized :semantic-result-persisted]
                 (clojure.edn/read-string (slurp events))))
          (let [persisted (transport/read-runtime-journal-file (.getPath coordinator-journal))]
            (is (= 1 (count (get-in persisted [:coordinator-runtime/state
                                               :reproduction-results])))))
          (trace! :sigkill-issued)
          (kill-runner-process! child)
          (is (not (.isAlive ^Process (:process child))))
          (trace! :coordinator-exit-observed)
          (let [lost (await-runner-state
                      runner-journal #(= :unreachable
                                         (get-in % [:completed-work work-root
                                                    :result-delivery :delivery/status])))]
            (is (= :completed (get-in lost [:runner-runtime/state :work-states
                                            work-root :work/status])))
            ;; The outer job-delivery response is incidental. The persisted
            ;; failed result post is the meaningful crash cutpoint.
            (trace! :runner-post-failed-and-pending)
            (future-cancel delivery))
          (let [coordinator (start-coordinator
                             assignment (.getAbsolutePath coordinator-journal) trace!)]
            (try
              (let [address (java.net.URI/create (:transport/base-url coordinator))]
                (with-open [socket (java.net.Socket.)]
                  (.connect socket (java.net.InetSocketAddress. (.getHost address)
                                                                (.getPort address)) 5000)))
              (trace! :replacement-listener-accepting)
              (trace! :retry-initiated)
              (transport/retry-undelivered-reproduction-results!
               @first-runner (transport/coordinator-endpoint coordinator secret 5000) 1)
              (trace! :runner-retry-returned)
              (let [recovered (await-runner-state
                               runner-journal #(= :result-acknowledged
                                                  (get-in % [:work-states work-root :work/status])))]
                (is (= 1 (count (:reproduction-results (transport/coordinator-state coordinator)))))
                (is (= 1 (count (:reproduction-results
                                 (get-in (transport/read-runtime-journal-file
                                          (.getPath coordinator-journal))
                                         [:coordinator-runtime/state])))))
                (is (= 2 (count (get-in recovered [:runner-runtime/state :completed-work
                                                   work-root :result-deliveries]))))
                (is (= :already-recorded
                       (get-in recovered [:runner-runtime/state :completed-work work-root
                                          :result-delivery :delivery/status])))
                (is (= 1 (count (filter #(= :execution-started (:work/event %))
                                        (get-in recovered [:runner-runtime/state :work-states
                                                           work-root :work/history])))))
                (let [observed @trace
                      index-of (fn [event occurrence]
                                 (nth (keep-indexed
                                       (fn [index candidate]
                                         (when (= event candidate) index))
                                       observed)
                                      occurrence nil))]
                  (is (every? (set observed)
                              [:result-post-initiated :result-post-transport-failed
                               :parent-observed-persisted-result :sigkill-issued
                               :coordinator-exit-observed :runner-post-failed-and-pending
                               :replacement-listener-accepting :retry-initiated
                               :runner-retry-initiated :handler-entered
                               :request-body-received :duplicate-result-recognized
                               :acknowledgement-emitted :result-post-response-received
                               :runner-acknowledgement-recorded :runner-retry-returned]))
                  ;; Independent process and client threads can observe adjacent
                  ;; events in either order; assert only causal partial order.
                  (is (< (index-of :result-post-initiated 0)
                         (index-of :parent-observed-persisted-result 0)
                         (index-of :sigkill-issued 0)))
                  (is (< (index-of :sigkill-issued 0)
                         (index-of :result-post-transport-failed 0)
                         (index-of :runner-post-failed-and-pending 0)))
                  (is (< (index-of :replacement-listener-accepting 0)
                         (index-of :retry-initiated 0)
                         (index-of :runner-retry-initiated 0)
                         (index-of :result-post-initiated 1)
                         (index-of :handler-entered 0)
                         (index-of :request-body-received 0)
                         (index-of :duplicate-result-recognized 0)
                         (index-of :runner-acknowledgement-recorded 0)
                         (index-of :runner-retry-returned 0)))))
              (finally (transport/stop-coordinator! coordinator))))))
      (finally
        (kill-runner-process! child)
        (when @first-runner (transport/stop-runner! @first-runner))
        (doseq [file [runner-journal coordinator-journal entered release events trace-file]]
          (.delete ^java.io.File file))))))

(deftest process-kill-cancellation-before-execution-survives-restart
  (let [{:keys [job assignment bundle participant]} (setup-artifacts)
        work-root (:research-reproduction-execution-work/root bundle)
        journal (java.io.File/createTempFile "reproduction-cancel-before-state-" ".edn")
        _ (.delete journal)
        cancellation (control/build-cancellation
                      {:job-root (:research-execution-job/root job)
                       :control-epoch 0 :reason :user-requested
                       :requested-cutpoint 1 :scope :entire-job :target-roots []})
        first (launch-runner-process
               {:runner-participation participant :auth-secret secret
                :state-path (.getAbsolutePath journal)})]
    (try
      (let [ready (await-process-file (:ready-file first) (:process first) (:log-file first))
            endpoint {:transport/base-url (:runner/base-url ready)
                      :transport/auth-secret secret :transport/request-timeout-ms 30000}]
        (is (= :observed
               (:delivery/status
                (transport/deliver-cancellation!
                 {:coordinator-root coordinator-root :endpoint endpoint
                  :cancellation cancellation :runner-participation participant
                  :assignment assignment :logical-cutpoint 1}))))
        (kill-runner-process! first)
        (let [restarted (launch-runner-process
                         {:runner-participation participant :auth-secret secret
                          :state-path (.getAbsolutePath journal)})]
          (try
            (let [restarted-ready (await-process-file (:ready-file restarted) (:process restarted)
                                                      (:log-file restarted))
                  delivery (transport/deliver-job!
                            {:coordinator-root coordinator-root
                             :endpoint {:transport/base-url (:runner/base-url restarted-ready)
                                        :transport/auth-secret secret
                                        :transport/request-timeout-ms 30000}
                             :job job :assignment assignment :execution-work bundle})
                  state (await-runner-state journal
                                            #(contains? (:cancellations %)
                                                        (:research-execution-job/root job)))]
              (is (= :cancelled-before-start (:delivery/status delivery)))
              (is (empty? (get-in state [:runner-runtime/state :completed-work])))
              (is (nil? (get-in state [:runner-runtime/state :work-states work-root]))))
            (finally (kill-runner-process! restarted)))))
      (finally
        (kill-runner-process! first)
        (.delete journal)))))

(deftest process-kill-after-atomic-reproduction-retains-result-and-suppresses-delivery
  (let [{:keys [job assignment bundle participant]} (setup-artifacts)
        work-root (:research-reproduction-execution-work/root bundle)
        journal (java.io.File/createTempFile "reproduction-cancel-after-state-" ".edn")
        entered (java.io.File/createTempFile "reproduction-cancel-after-entered-" ".edn")
        release (java.io.File/createTempFile "reproduction-cancel-after-release-" ".edn")
        _ (do (.delete journal) (.delete entered) (.delete release))
        coordinator (transport/start-coordinator!
                     {:coordinator-root coordinator-root :assignments [assignment]
                      :auth-secret secret :bind-address "127.0.0.1" :port 0})
        cancellation (control/build-cancellation
                      {:job-root (:research-execution-job/root job)
                       :control-epoch 0 :reason :user-requested
                       :requested-cutpoint 2 :scope :entire-job :target-roots []})
        child (launch-runner-process
               {:runner-participation participant :auth-secret secret
                :state-path (.getAbsolutePath journal)
                :coordinator-root coordinator-root
                :coordinator-endpoint (transport/coordinator-endpoint coordinator secret 30000)
                :block-at :before-result-delivery
                :entered-path (.getAbsolutePath entered)
                :release-path (.getAbsolutePath release)})]
    (try
      (let [ready (await-process-file (:ready-file child) (:process child) (:log-file child))
            endpoint {:transport/base-url (:runner/base-url ready)
                      :transport/auth-secret secret :transport/request-timeout-ms 30000}
            execution (future
                        (try (transport/deliver-job!
                              {:coordinator-root coordinator-root :endpoint endpoint
                               :job job :assignment assignment :execution-work bundle})
                             (catch Throwable failure {:failure failure})))]
        (await-process-stage entered (:process child) (:log-file child)
                             :before-result-delivery)
        (is (= :observed
               (:delivery/status
                (transport/deliver-cancellation!
                 {:coordinator-root coordinator-root :endpoint endpoint
                  :cancellation cancellation :runner-participation participant
                  :assignment assignment :logical-cutpoint 2}))))
        (spit release "release")
        @execution
        (let [cancelled (await-runner-state
                         journal #(= :cancelled-before-delivery
                                     (get-in % [:completed-work work-root
                                                :result-delivery :delivery/status])))]
          (is (reproduction/valid-execution?
               (get-in cancelled [:runner-runtime/state :completed-work work-root
                                  :reproduction-execution])))
          (is (empty? (:reproduction-results (transport/coordinator-state coordinator))))
          (kill-runner-process! child)
          (let [restarted (launch-runner-process
                           {:runner-participation participant :auth-secret secret
                            :state-path (.getAbsolutePath journal)
                            :coordinator-root coordinator-root
                            :coordinator-endpoint
                            (transport/coordinator-endpoint coordinator secret 30000)})]
            (try
              (await-process-file (:ready-file restarted) (:process restarted)
                                  (:log-file restarted))
              (let [after-restart (await-runner-state
                                   journal #(= :cancelled-before-delivery
                                               (get-in % [:completed-work work-root
                                                          :result-delivery :delivery/status])))]
                (is (= 1 (count (filter #(= :execution-started (:work/event %))
                                        (get-in after-restart [:runner-runtime/state :work-states
                                                               work-root :work/history])))))
                (is (empty? (:reproduction-results (transport/coordinator-state coordinator)))))
              (finally (kill-runner-process! restarted))))))
      (finally
        (kill-runner-process! child)
        (doseq [file [journal entered release]] (.delete ^java.io.File file))
        (transport/stop-coordinator! coordinator)))))

(deftest multi-host-reproduction-preserves-semantic-roots
  ;; Opt-in integration test. A runner process on a second host is reached
  ;; through runtime-only SSH forwarding; the rooted bundle/protocol is the
  ;; same one used by local dispatch.
  (if-not (and (seq (System/getenv "PRF_REMOTE_RUNNER_URL"))
               (seq (System/getenv "PRF_REMOTE_COORDINATOR_PORT")))
    (is true "set PRF_REMOTE_RUNNER_URL and PRF_REMOTE_COORDINATOR_PORT for live-host qualification")
    (let [{:keys [job assignment bundle original] :as artifacts} (setup-artifacts)
          coordinator-port (Integer/parseInt (System/getenv "PRF_REMOTE_COORDINATOR_PORT"))
          runner-url (System/getenv "PRF_REMOTE_RUNNER_URL")
          coordinator (transport/start-coordinator!
                       {:coordinator-root coordinator-root :assignments [assignment]
                        :auth-secret secret :bind-address "127.0.0.1"
                        :port coordinator-port})
          remote-endpoint {:transport/base-url runner-url
                           :transport/auth-secret secret
                           :transport/request-timeout-ms 30000}
          local-execution (reproduction/execute (:input artifacts)
                                                (:implementation artifacts))]
      (try
        (let [dispatched (transport/dispatch-reproduction!
                          coordinator {:endpoint remote-endpoint :job job
                                       :assignment assignment :reproduction-work bundle
                                       :authoritative-result original
                                       :logical-cutpoint 1})
              execution (:reproduction-execution dispatched)
              admitted (vals (:reproduction-results
                              (transport/coordinator-state coordinator)))]
          (is (= :accepted (get-in dispatched [:delivery :delivery/status]))
              (pr-str (:delivery dispatched)))
          (is (= (:reproduction-input/root (:work/reproduction-input bundle))
                 (:reproduction-input/root execution)))
          (is (= (:research-implementation-identity/root (:work/implementation bundle))
                 (:research-implementation-identity/root
                  execution)))
          (is (= (:reproduction-execution/root local-execution)
                 (:reproduction-execution/root execution)))
          (is (= :reproduced (:reproduction/status (:reproduction-evidence dispatched))))
          (is (= 1 (count admitted)))
          (is (= (:reproduction-execution/root execution)
                 (get-in (first admitted)
                         [:reproduction-execution :reproduction-execution/root])))
          (is (= assignment (:assignment (first admitted)))))
        (finally (transport/stop-coordinator! coordinator))))))
