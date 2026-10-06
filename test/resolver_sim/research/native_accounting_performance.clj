(ns resolver-sim.research.native-accounting-performance
  "Opt-in wall-clock performance study for the frozen native accounting path.

   Timings are runtime observations, never canonical research inputs or result
   identities. The benchmark changes no scheduler or accounting semantics."
  (:refer-clojure :exclude [ints])
  (:require [clojure.string :as str]
            [resolver-sim.research.accelerated-accounting :as accounting]
            [resolver-sim.research.native-accounting-work :as native]
            [resolver-sim.research.native-accounting-work-test :as fixture]
            [resolver-sim.research.runner-transport :as transport]))

(defn- elapsed [f]
  (let [started (System/nanoTime)
        value (f)]
    {:value value :elapsed-ns (- (System/nanoTime) started)}))

(defn- fixture-for [account runner-id account-count source-count partitions partition-index cutpoint]
  (fixture/work-fixture account runner-id
                        {:account-count account-count
                         :sources-per-account source-count
                         :partition-count partitions
                         :partition-index partition-index
                         :cutpoint-id cutpoint}))

(defn- account-ids [account-count]
  (mapv #(+ 100 %) (range account-count)))

(defn- fixture-matrix [account-count source-count partitions cutpoint mode]
  (vec
   (for [account (account-ids account-count)
         partition-index (range partitions)
         :let [runner-id (case mode
                           :serial 301
                           :local (+ 301 partition-index)
                           :cross-host (if (zero? partition-index) 301 302))]]
     (fixture-for account runner-id account-count source-count partitions partition-index cutpoint))))

(defn- reconcile-fixtures [fixtures partials]
  (let [groups (group-by #(get-in % [:plan :accounting-partition-plan/root]) fixtures)
        partials (mapv #(or (:accounting-partial %) %) partials)
        partial-by-unit (into {} (map (juxt :accounting-work-unit/root identity) partials))]
    (mapv
     (fn [[_ members]]
       (let [{:keys [request plan contract]} (first members)
             ingestion (into {}
                             (map (fn [member]
                                    (let [unit (get-in member [:work :accounting/work-unit])]
                                      [(:accounting-work-unit/root unit)
                                       (partial-by-unit (:accounting-work-unit/root unit))])))
                             members)]
         (accounting/reconcile request contract plan ingestion)))
     (sort-by key groups))))

(defn- serial-sample [account-count source-count cutpoint]
  (let [fixtures (mapv #(fixture-for % 301 account-count source-count 1 0 cutpoint)
                       (account-ids account-count))
        compute (elapsed
                 #(mapv (fn [{:keys [work]}]
                          (:accounting-partial (native/execute-work! work))) fixtures))
        reconcile (elapsed #(reconcile-fixtures fixtures (:value compute)))]
    {:mode :serial :accounts account-count :sources-per-account source-count
     :partitions-per-account 1 :compute-ns (:elapsed-ns compute)
     :end-to-end-ns (+ (:elapsed-ns compute) (:elapsed-ns reconcile))
     :reconciliation-ns (:elapsed-ns reconcile)
     :canonical-result-roots (mapv :canonical-accounting-result/root (:value reconcile))}))

(defn- start-local-runners [coordinator fixtures]
  (let [worker-fixtures (->> fixtures
                             (reduce (fn [m f]
                                       (assoc m (get-in f [:participant :runner/reference]) f)) {})
                             vals)]
    (mapv (fn [f]
            (transport/start-runner!
             {:runner-participation (:participant f)
              :auth-secret fixture/secret
              :coordinator-root fixture/coordinator-root
              :coordinator-endpoint (transport/coordinator-endpoint coordinator fixture/secret 60000)
              :bind-address "127.0.0.1" :port 0}))
          worker-fixtures)))

(defn- dispatch-groups [coordinator fixtures endpoint-for]
  (let [groups (vals (group-by #(get-in % [:participant :runner/reference]) fixtures))
        workers (mapv (fn [group]
                        (future
                          (mapv (fn [{:keys [work]}]
                                  (:native-accounting-execution
                                   (transport/dispatch-native-accounting!
                                    coordinator
                                    {:endpoint (endpoint-for (get-in work [:work/runner-participation
                                                                         :runner/reference]))
                                     :accounting-work work :logical-cutpoint 1})))
                                group)))
                      groups)]
    (vec (mapcat deref workers))))

(defn- coordinator-for [fixtures]
  (transport/start-coordinator!
   {:coordinator-root fixture/coordinator-root
    :assignments (mapv :assignment fixtures)
    :auth-secret fixture/secret :bind-address "127.0.0.1" :port 0}))

(defn- local-sample [account-count source-count partitions cutpoint]
  (let [fixtures (fixture-matrix account-count source-count partitions cutpoint :local)
        coordinator-start (elapsed #(coordinator-for fixtures))
        coordinator (:value coordinator-start)
        runner-start (elapsed #(start-local-runners coordinator fixtures))
        runners (:value runner-start)]
    (try
      (let [dispatch (elapsed
                      #(dispatch-groups
                        coordinator fixtures
                        (fn [runner-root]
                          (let [runner (some (fn [r]
                                               (when (= runner-root
                                                        (get-in r [:runner-participation :runner/reference])) r))
                                             runners)]
                            (transport/coordinator-endpoint runner fixture/secret 60000)))))
            reconcile (elapsed #(reconcile-fixtures fixtures (:value dispatch)))
            roots (mapv :canonical-accounting-result/root (:value reconcile))]
        {:mode :partitioned-local :accounts account-count :sources-per-account source-count
         :partitions-per-account partitions
         :coordinator-startup-ns (:elapsed-ns coordinator-start)
         :runner-startup-ns (:elapsed-ns runner-start)
         :dispatch-execution-ns (:elapsed-ns dispatch)
         :end-to-end-ns (+ (:elapsed-ns coordinator-start) (:elapsed-ns runner-start)
                           (:elapsed-ns dispatch) (:elapsed-ns reconcile))
         :reconciliation-ns (:elapsed-ns reconcile)
         :canonical-result-roots roots})
      (finally
        (doseq [runner runners] (transport/stop-runner! runner))
        (transport/stop-coordinator! coordinator)))))

(defn- cross-host-sample [account-count source-count partitions cutpoint]
  (when (< partitions 2)
    (throw (ex-info "Cross-host partition comparison needs at least two partitions" {})))
  (let [runner-url (System/getenv "PRF_REMOTE_ACCOUNTING_RUNNER_URL")
        coordinator-port (some-> (System/getenv "PRF_REMOTE_ACCOUNTING_COORDINATOR_PORT") Integer/parseInt)
        _ (when-not (and (seq runner-url) coordinator-port)
            (throw (ex-info "Cross-host measurement requires the SSH-tunnel runner URL and coordinator port" {})))
        fixtures (fixture-matrix account-count source-count partitions cutpoint :cross-host)
        coordinator-start (elapsed
                           #(transport/start-coordinator!
                             {:coordinator-root fixture/coordinator-root
                              :assignments (mapv :assignment fixtures)
                              :auth-secret fixture/secret :bind-address "127.0.0.1"
                              :port coordinator-port}))
        coordinator (:value coordinator-start)
        local-fixtures (filterv #(= (fixture/root 301)
                                    (get-in % [:participant :runner/reference])) fixtures)
        ;; Partition zero belongs to the local runner; all remaining slots are
        ;; routed to the already-running remote participant identity.
        local-runner-start (elapsed
                            #(transport/start-runner!
                              {:runner-participation (:participant (first local-fixtures))
                               :auth-secret fixture/secret :coordinator-root fixture/coordinator-root
                               :coordinator-endpoint (transport/coordinator-endpoint coordinator fixture/secret 60000)
                               :bind-address "127.0.0.1" :port 0}))
        local-runner (:value local-runner-start)
        remote-endpoint {:transport/base-url runner-url :transport/auth-secret fixture/secret
                         :transport/request-timeout-ms 60000}]
    (try
      (let [dispatch (elapsed
                      #(dispatch-groups
                        coordinator fixtures
                        (fn [runner-root]
                          (if (= runner-root (get-in local-runner [:runner-participation :runner/reference]))
                            (transport/coordinator-endpoint local-runner fixture/secret 60000)
                            remote-endpoint))))
            reconcile (elapsed #(reconcile-fixtures fixtures (:value dispatch)))]
        {:mode :cross-host-ssh-tunnel :accounts account-count :sources-per-account source-count
         :partitions-per-account partitions
         :coordinator-startup-ns (:elapsed-ns coordinator-start)
         :local-runner-startup-ns (:elapsed-ns local-runner-start)
         :remote-runner-startup-included? false
         :dispatch-execution-ns (:elapsed-ns dispatch)
         :end-to-end-ns (+ (:elapsed-ns coordinator-start) (:elapsed-ns local-runner-start)
                           (:elapsed-ns dispatch) (:elapsed-ns reconcile))
         :reconciliation-ns (:elapsed-ns reconcile)
         :canonical-result-roots (mapv :canonical-accounting-result/root (:value reconcile))})
      (finally
        (transport/stop-runner! local-runner)
        (transport/stop-coordinator! coordinator)))))

(defn- ints [s default]
  (if (str/blank? s) default (mapv parse-long (str/split s #","))))

(defn -main [& [account-counts sources-per-account partition-counts repetitions]]
  (let [account-counts (ints account-counts [1 2])
        sources-per-account (ints sources-per-account [16 64])
        partition-counts (ints partition-counts [1 2 4 8])
        repetitions (or (some-> repetitions parse-long) 3)
        cross-host? (boolean (and (seq (System/getenv "PRF_REMOTE_ACCOUNTING_RUNNER_URL"))
                                  (seq (System/getenv "PRF_REMOTE_ACCOUNTING_COORDINATOR_PORT"))))
        warmups (vec
                 (for [accounts account-counts
                       source-count sources-per-account
                       partitions partition-counts
                       :when (<= partitions source-count)
                       :let [cutpoint (+ 900000000 (* accounts 100) source-count)]]
                   (do
                     (serial-sample accounts source-count cutpoint)
                     (local-sample accounts source-count partitions cutpoint)
                     (when (and cross-host? (> partitions 1))
                       (cross-host-sample accounts source-count partitions cutpoint))
                     {:accounts accounts :sources-per-account source-count
                      :partitions-per-account partitions})))
        samples
        (vec
         (for [rep (range repetitions)
               accounts account-counts
               source-count sources-per-account
               partitions partition-counts
               :when (<= partitions source-count)
               :let [cutpoint (+ 800000 (* rep 10000) (* accounts 100) source-count)
                     serial (serial-sample accounts source-count cutpoint)
                     local (local-sample accounts source-count partitions cutpoint)
                     cross-host (when (and cross-host? (> partitions 1))
                                  (cross-host-sample accounts source-count partitions cutpoint))
                     expected (:canonical-result-roots serial)]]
           (do
             (when-not (and (= expected (:canonical-result-roots local))
                            (or (nil? cross-host)
                                (= expected (:canonical-result-roots cross-host))))
               (throw (ex-info "Performance modes changed canonical accounting results"
                               {:serial expected :local (:canonical-result-roots local)
                                :cross-host (:canonical-result-roots cross-host)})))
             {:repetition rep :serial serial :partitioned-local local
              :cross-host cross-host})))
        measurement {:research/measurement :native-accounting-performance.v1
                     :measurement/canonical? false
                     :measurement/clock :jvm-system-nano-time
                     :measurement/account-counts account-counts
                     :measurement/source-counts-per-account sources-per-account
                     :measurement/partition-counts partition-counts
                     :measurement/repetitions repetitions
                     :measurement/warmups-per-coordinate 1
                     :measurement/warmup-coordinates warmups
                     :measurement/cross-host-runner-prestarted? cross-host?
                     :measurement/samples samples}]
    (try
      (prn measurement)
      (finally
        (shutdown-agents)))))
