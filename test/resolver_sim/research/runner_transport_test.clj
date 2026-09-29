(ns resolver-sim.research.runner-transport-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.research.execution-control :as control]
            [resolver-sim.research.anvil-executor-test :as anvil-fixture]
            [resolver-sim.research.remote-execution :as remote]
            [resolver-sim.research.remote-execution-test :as fixture]
            [resolver-sim.research.runner-transport :as transport]))

(def coordinator-root (fixture/fake-root \c))
(def transport-secret "research-lab-test-secret")

(defn- setup []
  (let [environment (fixture/anvil-environment)
        policy (fixture/execution-policy {:required-runs 2
                                          :required-matching-runs 1
                                          :distinct-by [:runner-identity]
                                          :qualification-references []})
        profile (fixture/local-profile)
        job (fixture/job environment policy profile)
        runner-a (fixture/runner \a :pseudonymous :site/a :qualifications [])
        runner-b (fixture/runner \b :pseudonymous :site/b :qualifications [])
        assignment-a (remote/steal-next job environment policy runner-a [])
        assignment-b (remote/steal-next job environment policy runner-b [assignment-a])]
    {:environment environment :policy policy :job job
     :runner-a runner-a :runner-b runner-b
     :assignment-a assignment-a :assignment-b assignment-b}))

(defn- with-runners! [setup-data f]
  (let [runner-a (transport/start-runner! {:runner-participation (:runner-a setup-data)
                                           :auth-secret transport-secret})
        runner-b (transport/start-runner! {:runner-participation (:runner-b setup-data)
                                           :auth-secret transport-secret})]
    (try
      (f runner-a runner-b)
      (finally
        (transport/stop-runner! runner-a)
        (transport/stop-runner! runner-b)))))

(deftest real-http-job-delivery-respects-distinct-runner-assignments
  (let [setup-data (setup)]
    (with-runners! setup-data (fn [runner-a runner-b]
      (let [a (transport/deliver-job! {:coordinator-root coordinator-root
                                       :endpoint (transport/coordinator-endpoint runner-a transport-secret)
                                       :job (:job setup-data)
                                       :assignment (:assignment-a setup-data)
                                       :logical-cutpoint 1})
            b (transport/deliver-job! {:coordinator-root coordinator-root
                                       :endpoint (transport/coordinator-endpoint runner-b transport-secret)
                                       :job (:job setup-data)
                                       :assignment (:assignment-b setup-data)
                                       :logical-cutpoint 2})]
        (is (= :accepted (:delivery/status a)))
        (is (= :accepted (:delivery/status b)))
        (is (:reachability/realized? (:reachability a)))
        (is (:reachability/realized? (:reachability b)))
        (is (not= (:runner/reference (:assignment-a setup-data))
                  (:runner/reference (:assignment-b setup-data))))
        (is (not= (:transport/state runner-a) (:transport/state runner-b)))
        (is (= (:assignment-a setup-data)
               (get-in (transport/runner-state runner-a)
                       [:assignments (:research-run-assignment/root
                                      (:assignment-a setup-data))])))
        (is (= (:assignment-b setup-data)
               (get-in (transport/runner-state runner-b)
                       [:assignments (:research-run-assignment/root
                                      (:assignment-b setup-data))]))))))))

(deftest authentication-failure-is-not-mislabeled-as-unreachable
  (let [setup-data (setup)]
    (with-runners! setup-data (fn [runner-a _runner-b]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"authentication"
                            (transport/deliver-job!
                             {:coordinator-root coordinator-root
                              :endpoint (transport/coordinator-endpoint runner-a "wrong-secret")
                              :job (:job setup-data)
                              :assignment (:assignment-a setup-data)})))
      (is (empty? (:assignments (transport/runner-state runner-a))))))))

(deftest runner-cannot-accept-an-assignment-rooted-to-another-runner
  (let [setup-data (setup)]
    (with-runners! setup-data (fn [runner-a runner-b]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"rejected job delivery"
                            (transport/deliver-job!
                             {:coordinator-root coordinator-root
                              :endpoint (transport/coordinator-endpoint runner-b transport-secret)
                              :job (:job setup-data)
                              :assignment (:assignment-a setup-data)})))
      (is (empty? (:assignments (transport/runner-state runner-a))))
      (is (empty? (:assignments (transport/runner-state runner-b))))))))

(deftest observed-result-route-rejects-unvalidated-or-unbound-evidence
  (let [setup-data (setup)]
    (let [coordinator (transport/start-coordinator!
                       {:coordinator-root coordinator-root
                        :assignments [(:assignment-a setup-data)]
                        :auth-secret transport-secret})]
      (try
        (let [response ((deref #'resolver-sim.research.runner-transport/post-edn!)
                        (transport/coordinator-endpoint coordinator transport-secret)
                        "/v1/results"
                        {:transport/kind :research/observed-result-delivery
                         :assignment (:assignment-a setup-data)
                         :observed-run-evidence {:artifact/schema "not-observed"}
                         :run-assurance-evidence {:artifact/schema "not-assured"}
                         :logical-cutpoint 1})]
          (is (= 422 (:http/status response)))
          (is (= :invalid-observed-result
                 (get-in response [:transport/response :transport/status])))
          (is (empty? (:observed-results (transport/coordinator-state coordinator)))))
        (finally
          (transport/stop-coordinator! coordinator))))))

(deftest dispatched-observed-anvil-work-returns-assured-evidence-to-coordinator
  (let [fixture (anvil-fixture/observed-run-proof-input)
        work (:observed-execution-work fixture)
        assignment (:work/assignment work)
        participant (:work/runner-participation work)
        coordinator (transport/start-coordinator!
                     {:coordinator-root coordinator-root :assignments [assignment]
                      :auth-secret transport-secret})
        runner (transport/start-runner!
                {:runner-participation participant :auth-secret transport-secret
                 :coordinator-root coordinator-root
                 :coordinator-endpoint (transport/coordinator-endpoint coordinator transport-secret)})]
    (try
      (let [delivered (transport/deliver-job!
                       {:coordinator-root coordinator-root
                        :endpoint (transport/coordinator-endpoint runner transport-secret 30000)
                        :job (:work/job work) :assignment assignment
                        :execution-work work :logical-cutpoint 7})
            completed (get-in (transport/runner-state runner)
                              [:completed-work (:research-observed-execution-work/root work)])
            observed (:observed-run-evidence completed)
            returned (:result-delivery completed)]
        (is (= :accepted (:delivery/status delivered)))
        (is (= :accepted (:delivery/status returned)))
        (is (= (get-in fixture [:observed-run-evidence :semantic-result/root])
               (:semantic-result/root observed)))
        (is (:reachability/realized? (:reachability returned)))
        (is (= observed
               (get-in (transport/coordinator-state coordinator)
                       [:observed-results
                        (:research-observed-run-evidence/root observed)
                        :observed-run-evidence]))))
      (finally
        (transport/stop-runner! runner)
        (transport/stop-coordinator! coordinator)))))

(deftest substituted-work-is-rejected-before-network-delivery
  (let [fixture (anvil-fixture/observed-run-proof-input)
        work (:observed-execution-work fixture)
        runner (transport/start-runner!
                {:runner-participation (:work/runner-participation work)
                 :auth-secret transport-secret})]
    (try
      (doseq [bad-work [(assoc-in work [:work/action :value-wei] 2)
                        (assoc-in work [:work/assignment :runner/reference]
                                  (fixture/fake-root \f))]]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Invalid job delivery lineage"
                              (transport/deliver-job!
                               {:coordinator-root coordinator-root
                                :endpoint (transport/coordinator-endpoint runner transport-secret)
                                :job (:work/job work)
                                :assignment (:work/assignment work)
                                :execution-work bad-work}))))
      (is (empty? (:assignments (transport/runner-state runner))))
      (finally
        (transport/stop-runner! runner)))))

(deftest cancellation-before-dispatched-work-prevents-execution
  (let [fixture (anvil-fixture/observed-run-proof-input)
        work (:observed-execution-work fixture)
        assignment (:work/assignment work)
        participant (:work/runner-participation work)
        runner (transport/start-runner! {:runner-participation participant
                                         :auth-secret transport-secret})]
    (try
      (let [cancellation (control/build-cancellation
                          {:job-root (:research-execution-job/root (:work/job work))
                           :control-epoch 1 :reason :user-requested
                           :requested-cutpoint 0 :scope :entire-job :target-roots []})
            observed (transport/deliver-cancellation!
                      {:coordinator-root coordinator-root
                       :endpoint (transport/coordinator-endpoint runner transport-secret)
                       :cancellation cancellation :runner-participation participant
                       :assignment assignment :logical-cutpoint 1})
            delivery (transport/deliver-job!
                      {:coordinator-root coordinator-root
                       :endpoint (transport/coordinator-endpoint runner transport-secret)
                       :job (:work/job work) :assignment assignment
                       :execution-work work :logical-cutpoint 2})]
        (is (= :observed (:delivery/status observed)))
        (is (= :cancelled-before-start (:delivery/status delivery)))
        (is (empty? (:completed-work (transport/runner-state runner)))))
      (finally
        (transport/stop-runner! runner)))))

(deftest completed-work-is-retained-when-coordinator-result-delivery-is-unreachable
  (let [fixture (anvil-fixture/observed-run-proof-input)
        work (:observed-execution-work fixture)
        runner (transport/start-runner!
                {:runner-participation (:work/runner-participation work)
                 :auth-secret transport-secret
                 :coordinator-root coordinator-root
                 :coordinator-endpoint {:transport/base-url "http://127.0.0.1:1"
                                        :transport/auth-secret transport-secret
                                        :transport/request-timeout-ms 100}})]
    (try
      (let [delivery (transport/deliver-job!
                      {:coordinator-root coordinator-root
                       :endpoint (transport/coordinator-endpoint runner transport-secret 30000)
                       :job (:work/job work) :assignment (:work/assignment work)
                       :execution-work work :logical-cutpoint 4})
            completed (get-in (transport/runner-state runner)
                              [:completed-work (:research-observed-execution-work/root work)])]
        (is (= :accepted (:delivery/status delivery)))
        (is (= :unreachable (get-in completed [:result-delivery :delivery/status])))
        (is (false? (get-in completed [:result-delivery :reachability :reachability/realized?])))
        (is (:observed-run-evidence completed))
        (is (:run-assurance-evidence completed)))
      (finally
        (transport/stop-runner! runner)))))

(deftest retained-result-can-be-retried-after-coordinator-recovery
  (let [fixture (anvil-fixture/observed-run-proof-input)
        work (:observed-execution-work fixture)
        assignment (:work/assignment work)
        runner (transport/start-runner!
                {:runner-participation (:work/runner-participation work)
                 :auth-secret transport-secret
                 :coordinator-root coordinator-root
                 :coordinator-endpoint {:transport/base-url "http://127.0.0.1:1"
                                        :transport/auth-secret transport-secret
                                        :transport/request-timeout-ms 100}})]
    (try
      (transport/deliver-job!
       {:coordinator-root coordinator-root
        :endpoint (transport/coordinator-endpoint runner transport-secret 30000)
        :job (:work/job work) :assignment assignment
        :execution-work work :logical-cutpoint 4})
      (let [completed (get-in (transport/runner-state runner)
                              [:completed-work (:research-observed-execution-work/root work)])
            coordinator (transport/start-coordinator!
                         {:coordinator-root coordinator-root :assignments [assignment]
                          :auth-secret transport-secret})]
        (try
          (let [retries (transport/retry-undelivered-results!
                         runner (transport/coordinator-endpoint coordinator transport-secret) 8)
                observed (:observed-run-evidence completed)
                accepted (get-in (transport/coordinator-state coordinator)
                                 [:observed-results
                                  (:research-observed-run-evidence/root observed)])]
            (is (= 1 (count retries)))
            (is (= :accepted (get-in retries [0 :delivery :delivery/status])))
            (is (= observed (:observed-run-evidence accepted)))
            (is (= :accepted
                   (get-in (transport/runner-state runner)
                           [:completed-work (:research-observed-execution-work/root work)
                            :result-delivery :delivery/status]))))
          (finally
            (transport/stop-coordinator! coordinator))))
      (finally
        (transport/stop-runner! runner)))))

(deftest cancellation-delivery-is-realized-separately-from-unreachable-runner
  (let [setup-data (setup)]
    (with-runners! setup-data (fn [runner-a runner-b]
      (transport/deliver-job! {:coordinator-root coordinator-root
                               :endpoint (transport/coordinator-endpoint runner-a transport-secret)
                               :job (:job setup-data) :assignment (:assignment-a setup-data)})
      (transport/deliver-job! {:coordinator-root coordinator-root
                               :endpoint (transport/coordinator-endpoint runner-b transport-secret)
                               :job (:job setup-data) :assignment (:assignment-b setup-data)})
      (let [cancel (control/build-cancellation
                    {:job-root (:research-execution-job/root (:job setup-data))
                     :control-epoch 1 :reason :user-requested
                     :requested-cutpoint 3 :scope :entire-job :target-roots []})
            observed (transport/deliver-cancellation!
                      {:coordinator-root coordinator-root
                       :endpoint (transport/coordinator-endpoint runner-a transport-secret)
                       :cancellation cancel :runner-participation (:runner-a setup-data)
                       :assignment (:assignment-a setup-data) :logical-cutpoint 4})]
        (is (= :observed (:delivery/status observed)))
        (is (control/cancellation-observed? cancel (:observation observed)))
        (transport/stop-runner! runner-b)
        (let [unreachable (transport/deliver-cancellation!
                           {:coordinator-root coordinator-root
                            :endpoint (transport/coordinator-endpoint runner-b transport-secret)
                            :cancellation cancel :runner-participation (:runner-b setup-data)
                            :assignment (:assignment-b setup-data) :logical-cutpoint 5})]
          (is (= :unreachable (:delivery/status unreachable)))
          (is (false? (:reachability/realized? (:reachability unreachable))))
          (is (nil? (:observation unreachable)))))))))

(deftest pre-delivered-cancellation-prevents-later-work-delivery
  (let [setup-data (setup)]
    (with-runners! setup-data (fn [runner-a _runner-b]
      (let [cancel (control/build-cancellation
                    {:job-root (:research-execution-job/root (:job setup-data))
                     :control-epoch 1 :reason :user-requested
                     :requested-cutpoint 1 :scope :entire-job :target-roots []})
            observed (transport/deliver-cancellation!
                      {:coordinator-root coordinator-root
                       :endpoint (transport/coordinator-endpoint runner-a transport-secret)
                       :cancellation cancel :runner-participation (:runner-a setup-data)
                       :assignment (:assignment-a setup-data) :logical-cutpoint 2})
            delivery (transport/deliver-job!
                      {:coordinator-root coordinator-root
                       :endpoint (transport/coordinator-endpoint runner-a transport-secret)
                       :job (:job setup-data) :assignment (:assignment-a setup-data)
                       :logical-cutpoint 3})]
        (is (= :observed (:delivery/status observed)))
        (is (= :cancelled-before-start (:delivery/status delivery)))
        (is (empty? (:assignments (transport/runner-state runner-a)))))))))
