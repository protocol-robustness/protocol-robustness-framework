(ns resolver-sim.research.native-accounting-batch-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.edn :as edn]
            [resolver-sim.research.accelerated-accounting :as accounting]
            [resolver-sim.research.native-accounting-batch :as batch]
            [resolver-sim.research.native-accounting-work :as native]
            [resolver-sim.research.native-accounting-work-test :as fixture]
            [resolver-sim.research.native-accounting-profile :as profile]
            [resolver-sim.research.native-accounting-performance :as performance]
            [resolver-sim.research.execution-control :as control]
            [resolver-sim.research.runner-transport :as transport])
  (:import [java.util.concurrent CountDownLatch TimeUnit]
           [java.io StringWriter]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- account-fixtures []
  (let [basis (fixture/basis 4 10 987)]
    {:basis basis
     :fixtures (mapv #(fixture/work-fixture (+ 100 %) (+ 301 (mod % 2))
                                          {:account-basis basis}) (range 4))}))

(defn- semantic-results [result]
  (into (sorted-map)
        (map (fn [[account r]] [account (get-in r [:accounting/reconciliation
                                                   :canonical-accounting-result/root])]))
        (:accounting/account-results result)))

(deftest performance-progress-is-separate-edn-and-keeps-result-roots
  (let [writer (StringWriter.)
        root (fixture/root 991)
        sample {:repetition 2
                :serial {:end-to-end-ns 120 :canonical-result-roots [root]}
                :partitioned-local {:accounts 3 :sources-per-account 20
                                    :partitions-per-account 4 :actual-worker-count 2
                                    :end-to-end-ns 450 :canonical-result-roots [root]}
                :cross-host nil}
        event (performance/emit-progress! writer 7 sample)
        decoded (edn/read-string (.toString writer))]
    (is (= event decoded))
    (is (= :native-accounting-sample-complete (:research/measurement-event decoded)))
    (is (= 7 (:measurement/progress-index decoded)))
    (is (= 450 (:partitioned-local/end-to-end-ns decoded)))
    (is (= [root] (:canonical-result-roots decoded)))
    (is (not (contains? decoded :runner/reference)))
    (is (nil? (:cross-host/end-to-end-ns decoded)))))

(deftest actual-account-dispatch-preserves-parent-coverage-and-semantic-roots
  (let [{:keys [basis fixtures]} (account-fixtures)
        coordinator (transport/start-coordinator!
                     {:coordinator-root fixture/coordinator-root :auth-secret fixture/secret
                      :assignments (mapv :assignment fixtures)})
        runners (mapv #(transport/start-runner!
                        {:runner-participation (:participant %) :auth-secret fixture/secret
                         :coordinator-root fixture/coordinator-root
                         :coordinator-endpoint (transport/coordinator-endpoint coordinator fixture/secret 30000)})
                      (take 2 fixtures))
        dispatches (mapv (fn [i f] {:work (:work f)
                                   :endpoint (transport/coordinator-endpoint (nth runners (mod i 2))
                                                                            fixture/secret 30000)})
                         (range) fixtures)]
    (try
      (let [{result :value timings :runtime/profile}
            (profile/profile-call
             #(batch/dispatch-accounts-and-reconcile!
               coordinator (:request basis) (:contract basis) dispatches
               {:max-concurrent-dispatches 2 :max-outstanding-per-runner 1}))
            replay (batch/dispatch-accounts-and-reconcile!
                    coordinator (:request basis) (:contract basis) (vec (reverse dispatches))
                    {:max-concurrent-dispatches 1})]
        (is (= :complete (:accounting/batch-status result)))
        (is (= 4 (count (:accounting/account-results result))))
        (is (empty? (:accounting/incomplete-account-roots result)))
        (is (= (:accounting-request/root (:request basis)) (:accounting/parent-request-root result)))
        (is (= (semantic-results result) (semantic-results replay)))
        (is (= 2 (get-in result [:runtime/dispatch-profile :dispatch/capacity])))
        (is (pos? (get-in result [:runtime/dispatch-profile :dispatch/wall-ns])))
        (is (= 2 (count (get-in result [:runtime/dispatch-profile :dispatch/per-runner-call-time-ns]))))
        (doseq [[account request] (:accounts basis)]
          (is (= (accounting/serial-values request)
                 (get-in result [:accounting/account-results account :accounting/reconciliation :accounting/values]))))
        (is (pos? (get-in timings [:stages [:coordinator :partition-arithmetic-recomputation] :calls] 0)))
        (is (= 4 (get-in timings [:stages [:coordinator :work-binding-validation] :calls])))
        (is (= 0 (get-in timings [:stages [:executor-evidence :work-binding-validation] :calls] 0)))
        (is (= 0 (get-in timings [:stages [:executor-evidence :execution-validation] :calls] 0)))
        (is (= 4 (get-in timings [:stages [:executor-delivery :result-delivery-round-trip] :calls])))
        (is (= 4 (get-in timings [:stages [:reconciliation :full-serial-fold] :calls])))
        (is (= :not-observed (:remote-stages timings))))
      (finally
        (doseq [runner runners] (transport/stop-runner! runner))
        (transport/stop-coordinator! coordinator)))))

(deftest scheduling-diagnostic-design-is-explicit-and-counterbalanced
  (is (= [10 10 10 10] (performance/scheduling-layout :homogeneous 2 4 10)))
  (is (= [40 10 40 10] (performance/scheduling-layout :skewed 2 4 10)))
  (is (= [80 10 10 10] (performance/scheduling-layout :long-tail 2 4 10)))
  (is (thrown? clojure.lang.ExceptionInfo (performance/scheduling-layout :unknown 2 4 10)))
  (is (thrown? clojure.lang.ExceptionInfo (performance/scheduling-layout :skewed 5 4 10)))
  (let [orders (map performance/scheduling-order (range 6))]
    (is (= 6 (count (set orders))))
    (doseq [position (range 3)]
      (is (= {:serial 2 :fixed 2 :pull 2}
             (frequencies (map #(nth % position) orders))))))
  (let [p (#'batch/dispatch-profile
            [{:runner-root :a :started-ns 0 :finished-ns 10 :work-unit-count 1}
             {:runner-root :b :started-ns 0 :finished-ns 30 :work-unit-count 1}] 2)]
    (is (= {:a 20 :b 0} (:dispatch/per-runner-finish-tail-ns p)))
    (is (= 20 (:dispatch/idle-capacity-ns p)))
    (is (= 2 (:dispatch/max-concurrent-groups p)))))

(deftest pull-dispatch-reassigns-only-pending-rooted-work-and-preserves-accounting-roots
  (let [{:keys [basis fixtures]} (account-fixtures)
        participants (mapv #(-> (fixture/work-fixture 100 % {:account-basis basis}) :participant)
                           (range 301 305))
        coordinator (transport/start-coordinator!
                     {:coordinator-root fixture/coordinator-root :auth-secret fixture/secret
                      :assignments (mapv :assignment fixtures)})
        runners (mapv #(transport/start-runner!
                        {:runner-participation % :auth-secret fixture/secret
                         :coordinator-root fixture/coordinator-root
                         :coordinator-endpoint (transport/coordinator-endpoint coordinator fixture/secret 30000)})
                      participants)
        routes (mapv (fn [participant runner]
                       {:participation participant
                        :endpoint (transport/coordinator-endpoint runner fixture/secret 30000)})
                     participants runners)
        dispatches (mapv (fn [{:keys [work]}]
                           {:work work :eligible-runners routes :logical-cutpoint 1}) fixtures)
        expected-results
        (into (sorted-map)
              (map (fn [{:keys [work request plan contract]}]
                     (let [partial (:accounting-partial (native/execute-work! work))
                           reconciliation (accounting/reconcile request contract plan
                                                               (accounting/ingest-partial {} partial))]
                       [(native/account-root request (:accounting/work-unit work))
                        (:canonical-accounting-result/root reconciliation)])))
              fixtures)]
    (try
      (let [result (batch/dispatch-accounts-and-reconcile!
                    coordinator (:request basis) (:contract basis) dispatches
                    {:max-concurrent-dispatches 4 :max-outstanding-per-runner 1
                     :assignment-allowed? (constantly true)})
            state (transport/coordinator-state coordinator)
            admitted (vals (:native-accounting-results state))
            assigned-runner-roots (set (map :runner/reference
                                            (map :assignment admitted)))
            expected-runner-roots (set (map :runner/reference participants))]
        (is (= :complete (:accounting/batch-status result)))
        (is (= expected-runner-roots assigned-runner-roots))
        (is (= assigned-runner-roots
               (set (keys (get-in result [:runtime/dispatch-profile :dispatch/per-runner-call-time-ns]))))
            "pull occupancy is attributed to actual execution runners, not frozen initial assignments")
        (is (= assigned-runner-roots
               (set (keys (get-in result [:runtime/dispatch-profile :dispatch/per-runner-finish-tail-ns])))))
        (is (= 4 (count admitted)) "each rooted account unit is admitted once")
        (is (= expected-results (semantic-results result)))
        (is (= 4 (get-in result [:runtime/dispatch-profile :dispatch/work-unit-count])))
        (is (= (set (keys (:accounts basis))) (set (keys (:accounting/account-results result))))))
      (finally
        (doseq [runner runners] (transport/stop-runner! runner))
        (transport/stop-coordinator! coordinator)))))

(deftest pull-dispatch-does-not-create-assignments-after-control-denies-work
  (let [{:keys [basis fixtures]} (account-fixtures)
        participants (mapv #(-> (fixture/work-fixture 100 % {:account-basis basis}) :participant)
                           (range 301 305))
        assignments (mapv :assignment fixtures)
        coordinator (transport/start-coordinator!
                     {:coordinator-root fixture/coordinator-root :auth-secret fixture/secret
                      :assignments assignments})
        routes (mapv (fn [participant]
                       {:participation participant
                        :endpoint {:transport/base-url "http://127.0.0.1:1"}}) participants)
        dispatches (mapv (fn [{:keys [work]}]
                           {:work work :eligible-runners routes}) fixtures)]
    (try
      (let [result (batch/dispatch-accounts-and-reconcile!
                    coordinator (:request basis) (:contract basis) dispatches
                    {:assignment-allowed? (fn [_ _] false)})
            state (transport/coordinator-state coordinator)]
        (is (= :incomplete (:accounting/batch-status result)))
        (is (= 4 (count (filter #(= :cancelled-before-assignment (:dispatch/status %))
                                (:accounting/dispatch-outcomes result)))))
        (is (empty? (:dispatch-intents state)))
        (is (= (set (map :research-run-assignment/root assignments))
               (set (keys (:assignments state)))))
        (is (empty? (:accounting-partials state))))
      (finally (transport/stop-coordinator! coordinator)))))

(deftest native-dispatch-validates-sender-work-once-before-persisting-intent
  (let [basis (fixture/basis 1 4 986)
        work (:work (fixture/work-fixture 100 301 {:account-basis basis}))
        coordinator (transport/start-coordinator!
                     {:coordinator-root fixture/coordinator-root
                      :auth-secret fixture/secret
                      :assignments [(:work/assignment work)]})
        deliver-var (ns-resolve 'resolver-sim.research.runner-transport
                                'deliver-job-after-validation!)]
    (try
      (let [validation-count (atom 0)
            validate native/valid-work?
            result (with-redefs-fn
                    {#'native/valid-work?
                     (fn [candidate]
                       (swap! validation-count inc)
                       (validate candidate))
                     deliver-var (fn [_] {:delivery/status :unreachable})}
                    #(transport/dispatch-native-accounting!
                      coordinator {:endpoint {:transport/base-url "http://127.0.0.1:1"}
                                   :accounting-work work}))]
        (is (= :unreachable (get-in result [:delivery :delivery/status])))
        (is (= 1 @validation-count)
            "dispatch intent validates before persistence; sender does not repeat it")
        (is (= 1 (count (:dispatch-intents (transport/coordinator-state coordinator)))))
        (is (nil? (:native-accounting-execution result))))
      (finally
        (transport/stop-coordinator! coordinator)))))

(deftest invalid-native-dispatch-does-not-persist-an-intent
  (let [basis (fixture/basis 1 4 985)
        work (:work (fixture/work-fixture 100 301 {:account-basis basis}))
        coordinator (transport/start-coordinator!
                     {:coordinator-root fixture/coordinator-root
                      :auth-secret fixture/secret
                      :assignments [(:work/assignment work)]})
        invalid (assoc work :accounting/request
                       {:accounting-request/root (fixture/root 999)})]
    (try
      (is (thrown? clojure.lang.ExceptionInfo
                   (transport/dispatch-native-accounting!
                    coordinator {:endpoint {} :accounting-work invalid})))
      (is (empty? (:dispatch-intents (transport/coordinator-state coordinator))))
      (finally (transport/stop-coordinator! coordinator)))))

(deftest batched-account-delivery-keeps-independent-results-and-idempotent-replay
  (let [basis (fixture/basis 3 6 988)
        fixtures (mapv #(fixture/work-fixture (+ 100 %) 301 {:account-basis basis}) (range 3))
        coordinator (transport/start-coordinator!
                     {:coordinator-root fixture/coordinator-root :auth-secret fixture/secret
                      :assignments (mapv :assignment fixtures)})
        runner (transport/start-runner!
                {:runner-participation (:participant (first fixtures))
                 :auth-secret fixture/secret :coordinator-root fixture/coordinator-root
                 :coordinator-endpoint (transport/coordinator-endpoint coordinator fixture/secret 30000)})
        endpoint (transport/coordinator-endpoint runner fixture/secret 30000)
        dispatches (mapv #(hash-map :work (:work %) :endpoint endpoint) fixtures)]
    (try
      (let [options {:max-concurrent-dispatches 1 :max-transport-batch-size 3}
            first-result (batch/dispatch-accounts-and-reconcile!
                          coordinator (:request basis) (:contract basis) dispatches options)
            replay (batch/dispatch-accounts-and-reconcile!
                    coordinator (:request basis) (:contract basis) (vec (reverse dispatches)) options)]
        (is (= :complete (:accounting/batch-status first-result)))
        (is (= (semantic-results first-result) (semantic-results replay)))
        (is (= 3 (count (:native-accounting-results (transport/coordinator-state coordinator)))))
        (is (every? #(= 1 (get-in % [:work/attempt-sequence]))
                    (vals (:work-states (transport/runner-state runner)))))
        (is (every? #(= :full-batch (get-in % [:dispatch :delivery :delivery/cache-status]))
                    (:accounting/dispatch-outcomes first-result)))
        (is (= 3 (count (:dispatch-intents (transport/coordinator-state coordinator)))))
        (let [a (:work (first fixtures))
              b (:work (second fixtures))
              prior (transport/runner-state runner)
              substituted (assoc b :accounting/request
                                 {:accounting-request/root
                                  (get-in a [:accounting/request :accounting-request/root])})
              [status _] ((ns-resolve 'resolver-sim.research.runner-transport
                                      'apply-job-message!)
                          runner {:transport/kind :research/job-delivery
                                  :job (:work/job b) :assignment (:work/assignment b)
                                  :execution-work substituted :execute? true :logical-cutpoint 1})]
          (is (= 422 status))
          (is (= prior (transport/runner-state runner)))))
      (finally
        (transport/stop-runner! runner)
        (transport/stop-coordinator! coordinator)))))

(deftest rooted-accounting-input-cache-survives-runner-restart
  (let [basis (fixture/basis 1 8 989)
        fixtures (mapv #(fixture/work-fixture 100 301
                                              {:account-basis basis :partition-count 2
                                               :partition-index %}) (range 2))
        directory (Files/createTempDirectory "accounting-input-cache-" (make-array FileAttribute 0))
        path (str (.resolve directory "runner.edn"))
        coordinator (transport/start-coordinator!
                     {:coordinator-root fixture/coordinator-root :auth-secret fixture/secret
                      :assignments (mapv :assignment fixtures)})
        config {:runner-participation (:participant (first fixtures))
                :auth-secret fixture/secret :coordinator-root fixture/coordinator-root
                :coordinator-endpoint (transport/coordinator-endpoint coordinator fixture/secret 30000)
                :state-path path}
        runner (transport/start-runner! config)]
    (try
      (let [first-delivery (transport/dispatch-native-accounting!
                            coordinator {:endpoint (transport/coordinator-endpoint runner fixture/secret 30000)
                                         :accounting-work (:work (first fixtures))})]
        (is (= :accepted (get-in first-delivery [:delivery :delivery/status])))
        (transport/stop-runner! runner)
        (let [restarted (transport/start-runner! config)
              second-work (:work (second fixtures))]
          (try
            (let [preview (transport/deliver-job!
                           {:coordinator-root fixture/coordinator-root
                            :endpoint (transport/coordinator-endpoint restarted fixture/secret 30000)
                            :job (:work/job second-work)
                            :assignment (:work/assignment second-work)
                            :execution-work second-work :execute? false
                            :use-cached-accounting-input? true})
                  second-delivery (transport/dispatch-native-accounting!
                                   coordinator {:endpoint (transport/coordinator-endpoint restarted fixture/secret 30000)
                                                :accounting-work second-work})
                  work-root (native/work-root second-work)]
              (is (= :referenced (:delivery/cache-status preview)))
              (is (= :accepted (:delivery/status preview)))
              (is (native/valid-work? (get-in (transport/runner-state restarted) [:work-items work-root])))
              (is (= :accepted (get-in second-delivery [:delivery :delivery/status])))
              (is (= 2 (count (:native-accounting-results (transport/coordinator-state coordinator))))))
            (finally (transport/stop-runner! restarted)))))
      (finally
        (transport/stop-runner! runner)
        (transport/stop-coordinator! coordinator)
        (Files/deleteIfExists (.resolve directory "runner.edn"))
        (Files/deleteIfExists directory)))))

(deftest coordinator-reuses-request-and-plan-for-later-partition
  (let [basis (fixture/basis 1 8 990)
        fixtures (mapv #(fixture/work-fixture 100 301
                                              {:account-basis basis :partition-count 2
                                               :partition-index %}) (range 2))
        coordinator (transport/start-coordinator!
                     {:coordinator-root fixture/coordinator-root :auth-secret fixture/secret
                      :assignments (mapv :assignment fixtures)})
        runner (transport/start-runner!
                {:runner-participation (:participant (first fixtures))
                 :auth-secret fixture/secret :coordinator-root fixture/coordinator-root
                 :coordinator-endpoint (transport/coordinator-endpoint coordinator fixture/secret 30000)})
        endpoint (transport/coordinator-endpoint runner fixture/secret 30000)
        dispatches (mapv #(hash-map :work (:work %) :endpoint endpoint) fixtures)]
    (try
      (let [result (batch/dispatch-accounts-and-reconcile!
                    coordinator (:request basis) (:contract basis) dispatches
                    {:max-concurrent-dispatches 1 :max-outstanding-per-runner 1})
            statuses (frequencies (map #(get-in % [:dispatch :delivery :delivery/cache-status])
                                     (:accounting/dispatch-outcomes result)))]
        (is (= :complete (:accounting/batch-status result)))
        (is (= {:full 1 :referenced 1} statuses))
        (is (= (accounting/serial-values (first (vals (:accounts basis))))
               (get-in result [:accounting/account-results (fixture/root 100)
                               :accounting/reconciliation :accounting/values]))))
      (finally
        (transport/stop-runner! runner)
        (transport/stop-coordinator! coordinator)))))

(deftest batch-reuses-rooted-account-inputs-within-one-envelope
  (let [basis (fixture/basis 1 12 991)
        fixtures (mapv #(fixture/work-fixture 100 301
                                              {:account-basis basis :partition-count 3
                                               :partition-index %}) (range 3))
        coordinator (transport/start-coordinator!
                     {:coordinator-root fixture/coordinator-root :auth-secret fixture/secret
                      :assignments (mapv :assignment fixtures)})
        runner (transport/start-runner!
                {:runner-participation (:participant (first fixtures))
                 :auth-secret fixture/secret :coordinator-root fixture/coordinator-root
                 :coordinator-endpoint (transport/coordinator-endpoint coordinator fixture/secret 30000)})
        endpoint (transport/coordinator-endpoint runner fixture/secret 30000)]
    (try
      (let [result (batch/dispatch-accounts-and-reconcile!
                    coordinator (:request basis) (:contract basis)
                    (mapv #(hash-map :work (:work %) :endpoint endpoint) fixtures)
                    {:max-concurrent-dispatches 1 :max-transport-batch-size 3})
            statuses (frequencies (map #(get-in % [:dispatch :delivery :delivery/cache-status])
                                     (:accounting/dispatch-outcomes result)))]
        (is (= :complete (:accounting/batch-status result)))
        (is (= {:full-batch 1 :referenced-batch 2} statuses))
        (is (= 3 (count (:native-accounting-results (transport/coordinator-state coordinator)))))
        (doseq [{:keys [work]} fixtures]
          (is (= work (get-in (transport/runner-state runner)
                              [:work-items (native/work-root work)]))))
        (is (= (accounting/serial-values (first (vals (:accounts basis))))
               (get-in result [:accounting/account-results (fixture/root 100)
                               :accounting/reconciliation :accounting/values]))))
      (finally
        (transport/stop-runner! runner)
        (transport/stop-coordinator! coordinator)))))

(deftest repeated-account-batch-payload-has-one-full-input-copy
  (let [basis (fixture/basis 1 200 991)
        fixtures (mapv #(fixture/work-fixture 100 301
                                              {:account-basis basis :partition-count 4
                                               :partition-index %}) (range 4))
        items (mapv #(hash-map :accounting-work (:work %) :logical-cutpoint 0) fixtures)
        messages ((ns-resolve 'resolver-sim.research.runner-transport
                              'accounting-batch-messages) items)
        full-messages (mapv (fn [message item]
                              (assoc message :execution-work (:accounting-work item)))
                            messages items)]
    (is (= 4 (count messages)))
    (is (= (:work (first fixtures)) (:execution-work (first messages))))
    (doseq [[message fixture] (map vector (rest messages) (rest fixtures))]
      (is (= {:accounting-request/root
              (get-in fixture [:work :accounting/request :accounting-request/root])}
             (get-in message [:execution-work :accounting/request])))
      (is (= {:accounting-partition-plan/root
              (get-in fixture [:work :accounting/partition-plan
                               :accounting-partition-plan/root])}
             (get-in message [:execution-work :accounting/partition-plan]))))
    (is (< (count (pr-str messages)) (count (pr-str full-messages))))))

(deftest batch-reference-miss-after-cancelled-first-item-resends-full-work
  (let [basis (fixture/basis 1 8 991)
        fixtures (mapv #(fixture/work-fixture 100 301
                                              {:account-basis basis :partition-count 2
                                               :partition-index %}) (range 2))
        coordinator (transport/start-coordinator!
                     {:coordinator-root fixture/coordinator-root :auth-secret fixture/secret
                      :assignments (mapv :assignment fixtures)})
        runner (transport/start-runner!
                {:runner-participation (:participant (first fixtures))
                 :auth-secret fixture/secret :coordinator-root fixture/coordinator-root
                 :coordinator-endpoint (transport/coordinator-endpoint coordinator fixture/secret 30000)})
        endpoint (transport/coordinator-endpoint runner fixture/secret 30000)
        first-work (:work (first fixtures))
        cancellation (control/build-cancellation
                      {:job-root (get-in first-work [:work/job :research-execution-job/root])
                       :control-epoch 1 :reason :user-requested
                       :requested-cutpoint 1 :scope :entire-job :target-roots []})]
    (try
      (is (= :observed
             (:delivery/status
              (transport/deliver-cancellation!
               {:coordinator-root fixture/coordinator-root :endpoint endpoint
                :cancellation cancellation
                :runner-participation (:participant (first fixtures))
                :assignment (:assignment (first fixtures)) :logical-cutpoint 1}))))
      (let [result (batch/dispatch-accounts-and-reconcile!
                    coordinator (:request basis) (:contract basis)
                    (mapv #(hash-map :work (:work %) :endpoint endpoint) fixtures)
                    {:max-concurrent-dispatches 1 :max-transport-batch-size 2})
            outcomes (into {} (map (juxt :accounting-work-unit/root identity)
                                   (:accounting/dispatch-outcomes result)))
            first-outcome (get outcomes (get-in first-work
                                                [:accounting/work-unit :accounting-work-unit/root]))
            second-outcome (get outcomes (get-in (second fixtures)
                                                 [:work :accounting/work-unit
                                                  :accounting-work-unit/root]))]
        (is (= :incomplete (:accounting/batch-status result)))
        (is (= :cancelled-before-start
               (get-in first-outcome [:dispatch :delivery :delivery/status])))
        (is (= :miss-resent-full-batch
               (get-in second-outcome [:dispatch :delivery :delivery/cache-status])))
        (is (= :accepted (get-in second-outcome [:dispatch :delivery :delivery/status])))
        (is (= 1 (count (:native-accounting-results (transport/coordinator-state coordinator)))))
        (is (nil? (get-in result [:accounting/account-results (fixture/root 100)
                                 :accounting/reconciliation]))))
      (finally
        (transport/stop-runner! runner)
        (transport/stop-coordinator! coordinator)))))

(deftest empty-cache-resends-full-work-without-changing-identity
  (let [{:keys [work participant]} (fixture/work-fixture)
        runner (transport/start-runner! {:runner-participation participant
                                         :auth-secret fixture/secret})]
    (try
      (let [delivered (transport/deliver-job!
                       {:coordinator-root fixture/coordinator-root
                        :endpoint (transport/coordinator-endpoint runner fixture/secret 30000)
                        :job (:work/job work) :assignment (:work/assignment work)
                        :execution-work work :execute? false
                        :use-cached-accounting-input? true})]
        (is (= :accepted (:delivery/status delivered)))
        (is (= :miss-resent-full (:delivery/cache-status delivered)))
        (is (= work (get-in (transport/runner-state runner)
                            [:work-items (native/work-root work)])))
        (is (empty? (:completed-work (transport/runner-state runner)))))
      (finally (transport/stop-runner! runner)))))

(deftest batched-cancellation-preserves-other-account-and-missing-coverage
  (let [basis (fixture/basis 2 6 992)
        fixtures (mapv #(fixture/work-fixture (+ 100 %) 301 {:account-basis basis}) (range 2))
        coordinator (transport/start-coordinator!
                     {:coordinator-root fixture/coordinator-root :auth-secret fixture/secret
                      :assignments (mapv :assignment fixtures)})
        runner (transport/start-runner!
                {:runner-participation (:participant (first fixtures))
                 :auth-secret fixture/secret :coordinator-root fixture/coordinator-root
                 :coordinator-endpoint (transport/coordinator-endpoint coordinator fixture/secret 30000)})
        endpoint (transport/coordinator-endpoint runner fixture/secret 30000)
        first-work (:work (first fixtures))
        cancellation (control/build-cancellation
                      {:job-root (get-in first-work [:work/job :research-execution-job/root])
                       :control-epoch 1 :reason :user-requested
                       :requested-cutpoint 1 :scope :entire-job :target-roots []})]
    (try
      (is (= :observed
             (:delivery/status
              (transport/deliver-cancellation!
               {:coordinator-root fixture/coordinator-root :endpoint endpoint
                :cancellation cancellation
                :runner-participation (:participant (first fixtures))
                :assignment (:assignment (first fixtures)) :logical-cutpoint 1}))))
      (let [result (batch/dispatch-accounts-and-reconcile!
                    coordinator (:request basis) (:contract basis)
                    (mapv #(hash-map :work (:work %) :endpoint endpoint) fixtures)
                    {:max-concurrent-dispatches 1 :max-transport-batch-size 2})
            statuses (set (map #(get-in % [:dispatch :delivery :delivery/status])
                               (:accounting/dispatch-outcomes result)))]
        (is (= :incomplete (:accounting/batch-status result)))
        (is (= #{:cancelled-before-start :accepted} statuses))
        (is (= [(fixture/root 100)] (:accounting/incomplete-account-roots result)))
        (is (nil? (get-in result [:accounting/account-results (fixture/root 100)
                                 :accounting/reconciliation])))
        (is (= :complete (get-in result [:accounting/account-results (fixture/root 101)
                                       :accounting/status])))
        (is (= 1 (count (:native-accounting-results (transport/coordinator-state coordinator))))))
      (finally
        (transport/stop-runner! runner)
        (transport/stop-coordinator! coordinator)))))

(deftest malformed-parent-batches-fail-before-dispatch
  (let [{:keys [basis fixtures]} (account-fixtures)
        items (mapv #(hash-map :work (:work %) :endpoint {}) fixtures)
        calls (atom 0)]
    (with-redefs [transport/dispatch-native-accounting! (fn [& _] (swap! calls inc))]
      (doseq [bad [(subvec items 0 3) (conj items (first items))
                   (assoc-in items [0 :work :accounting/request :accounting/cutpoint-root] (fixture/root 99))]]
        (is (thrown? clojure.lang.ExceptionInfo
                     (batch/dispatch-accounts-and-reconcile! nil (:request basis) (:contract basis) bad {}))))
      (is (thrown? clojure.lang.ExceptionInfo
                   (batch/dispatch-accounts-and-reconcile! nil (:request (fixture/basis 4 11 987))
                                                         (:contract basis) items {})))
      (doseq [limits [{:max-concurrent-dispatches 0} {:max-outstanding-per-runner 0}]]
        (is (thrown? clojure.lang.ExceptionInfo
                     (batch/dispatch-accounts-and-reconcile! nil (:request basis) (:contract basis) items limits))))
      (is (zero? @calls)))))

(deftest bounded-capacity-scans-past-busy-runners-and-preserves-completed-accounts
  (let [{:keys [basis fixtures]} (account-fixtures)
        ;; Group the queue by runner deliberately: B must start despite A's
        ;; second pending account occupying an earlier queue position.
        fixtures (sort-by #(get-in % [:participant :runner/reference]) fixtures)
        items (mapv #(hash-map :work (:work %) :endpoint {}) fixtures)
        executions (into {} (map #(vector (native/work-root (:work %)) (native/execute-work! (:work %))) fixtures))
        state (atom {}) active (atom {}) peak (atom 0) per-runner-peak (atom {})
        entered (CountDownLatch. 2) release (CountDownLatch. 1)]
    (with-redefs [transport/coordinator-state (fn [_] @state)
                  transport/dispatch-native-accounting!
                  (fn [_ {:keys [accounting-work]}]
                    (let [runner (get-in accounting-work [:work/assignment :runner/reference])
                          counts (swap! active update runner (fnil inc 0))]
                      (swap! peak max (reduce + (vals counts)))
                      (swap! per-runner-peak update runner (fnil max 0) (get counts runner))
                      (.countDown entered)
                      (try
                        (when-not (.await release 10 TimeUnit/SECONDS)
                          (throw (ex-info "Test dispatch barrier timed out" {})))
                        (if (= (fixture/root 103) (native/account-root (:accounting/request accounting-work)
                                                                     (:accounting/work-unit accounting-work)))
                          (throw (ex-info "Unavailable runner for final account" {}))
                          (let [execution (get executions (native/work-root accounting-work))
                                plan (get-in accounting-work [:accounting/partition-plan :accounting-partition-plan/root])]
                            (swap! state update-in [:accounting-partials plan] (fnil accounting/ingest-partial {})
                                   (:accounting-partial execution))
                            {:delivery {:delivery/status :accepted} :native-accounting-execution execution}))
                        (finally (swap! active update runner dec))))) ]
      (let [task (future (batch/dispatch-accounts-and-reconcile! nil (:request basis) (:contract basis) items
                                                              {:max-concurrent-dispatches 2
                                                               :max-outstanding-per-runner 1}))]
        (try
          (is (.await entered 10 TimeUnit/SECONDS))
          (.countDown release)
          (let [result (deref task 20000 ::timeout)]
            (is (not= ::timeout result))
            (is (= 2 @peak))
            (is (every? #(= 1 %) (vals @per-runner-peak)))
            (is (= :incomplete (:accounting/batch-status result)))
            (is (= [(fixture/root 103)] (:accounting/incomplete-account-roots result)))
            (is (= 3 (count (filter #(= :complete (:accounting/status %))
                                   (vals (:accounting/account-results result))))))
            (is (nil? (get-in result [:accounting/account-results (fixture/root 103) :accounting/reconciliation]))))
          (finally (.countDown release) (future-cancel task)))))))

(deftest profiling-keeps-execution-identities-and-restores-functions
  (let [work (:work (fixture/work-fixture))
        expected (native/execute-work! work)
        original native/execute-work!
        measured (profile/profile-call #(native/execute-work! work))]
    (is (= expected (:value measured)))
    (is (identical? original native/execute-work!))
    (is (= 1 (get-in measured [:runtime/profile :stages [:executor :execution] :calls])))
    (is (= 1 (get-in measured [:runtime/profile :stages [:executor :work-binding-validation] :calls])))
    (is (= 1 (get-in measured [:runtime/profile :stages [:executor :accounting-request-validation] :calls]))
        "partition-plan validation already checks its exact request")
    (is (= 1 (get-in measured [:runtime/profile :stages [:executor :dependency-manifest-validation] :calls]))
        "dependency resolution validates the manifest")
    (is (= 1 (get-in measured [:runtime/profile :stages [:executor :dependency-policy-validation] :calls]))
        "dependency resolution validates the policy")
    (is (= 1 (get-in measured [:runtime/profile :stages [:executor :execution-validation] :calls])))
    (is (thrown? clojure.lang.ExceptionInfo
                 (profile/profile-call #(throw (ex-info "Expected failure" {})))))
    (is (identical? original native/execute-work!))))

(deftest execute-with-forensic-evidence-preserves-public-artifacts
  (let [work (:work (fixture/work-fixture))
        execution (native/execute-work! work)
        nodes (native/evidence-nodes work execution)
        measured (profile/profile-call #(native/execute-work-with-evidence! work))
        combined (:value measured)]
    (is (= execution (:execution combined)))
    (is (= (mapv :extensions nodes)
           (mapv :extensions (:forensic-evidence-nodes combined))))
    (is (= 1 (get-in measured [:runtime/profile :stages [:executor :work-binding-validation] :calls])))
    (is (= 1 (get-in measured [:runtime/profile :stages [:executor :execution-validation] :calls])))
    (is (= 1 (get-in measured [:runtime/profile :stages [:executor-evidence :forensic-evidence] :calls])))
    (is (= 0 (get-in measured [:runtime/profile :stages [:executor-evidence :work-binding-validation] :calls] 0)))
    (is (= 0 (get-in measured [:runtime/profile :stages [:executor-evidence :selected-implementation-recomputation] :calls] 0)))
    (is (= 0 (get-in measured [:runtime/profile :stages [:executor-evidence :partition-arithmetic-recomputation] :calls] 0)))) )

(deftest cancelled-account-cannot-become-zero-or-complete
  (let [{:keys [basis fixtures]} (account-fixtures)
        partials (into {} (map (fn [f]
                                [(get-in f [:plan :accounting-partition-plan/root])
                                 (accounting/ingest-partial {} (:accounting-partial (native/execute-work! (:work f))))])
                              (take 3 fixtures)))
        items (mapv #(hash-map :work (:work %) :endpoint {}) fixtures)]
    (with-redefs [transport/coordinator-state (fn [_] {:accounting-partials partials})
                  transport/dispatch-native-accounting!
                  (fn [_ {:keys [accounting-work]}]
                    {:delivery {:delivery/status
                                (if (= (fixture/root 103)
                                       (native/account-root (:accounting/request accounting-work)
                                                            (:accounting/work-unit accounting-work)))
                                  :cancelled :already-completed)}})]
      (let [result (batch/dispatch-accounts-and-reconcile! nil (:request basis) (:contract basis) items {})]
        (is (= :incomplete (:accounting/batch-status result)))
        (is (= [(fixture/root 103)] (:accounting/incomplete-account-roots result)))
        (is (= 3 (count (remove nil? (vals (semantic-results result))))))
        (is (nil? (get-in result [:accounting/account-results (fixture/root 103) :accounting/reconciliation])))
        (is (= 3 (count partials)))))))

(deftest performance-startup-failure-removes-owned-journal-directory
  (let [directory (.toFile (Files/createTempDirectory "accounting-startup-failure-" (make-array FileAttribute 0)))
        scratch-var (ns-resolve 'resolver-sim.research.native-accounting-performance 'scratch-directory)]
    (with-redefs-fn {scratch-var (constantly directory)
                    #'transport/start-coordinator! (fn [& _] (throw (ex-info "Socket unavailable" {})))}
      #(is (thrown-with-msg? clojure.lang.ExceptionInfo #"Socket unavailable"
                            (performance/-main "1" "10" "1" "1" "1"))))
    (is (not (.exists directory)))))
