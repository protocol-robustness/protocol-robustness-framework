(ns resolver-sim.research.native-accounting-performance
  "Opt-in wall-clock performance study for the frozen native accounting path.

   Timings are runtime observations, never canonical research inputs or result
   identities. The benchmark changes no scheduler or accounting semantics."
  (:refer-clojure :exclude [ints])
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [resolver-sim.research.accelerated-accounting :as accounting]
            [resolver-sim.research.native-accounting-batch :as batch]
            [resolver-sim.research.native-accounting-profile :as profile]
            [resolver-sim.research.native-accounting-work :as native]
            [resolver-sim.research.native-accounting-work-test :as fixture]
            [resolver-sim.research.runner-transport :as transport])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- scratch-directory []
  (when (= "1" (System/getenv "PRF_ACCOUNTING_PERFORMANCE_DURABLE"))
    (.toFile (Files/createTempDirectory "prf-accounting-performance-" (make-array FileAttribute 0)))))

(defn- journal-path [directory name]
  (when directory (.getAbsolutePath (io/file directory name))))

(defn- cleanup-scratch! [directory]
  (when directory
    (doseq [file (reverse (file-seq directory))]
      (when-not (.delete ^java.io.File file)
        (throw (ex-info "Performance scratch cleanup failed" {:runtime/path (str file)}))))))

(defn- registered! [registry runtime]
  (swap! registry conj runtime)
  runtime)

(defn- with-runtimes [f]
  (let [directory (scratch-directory) coordinators (atom []) runners (atom [])]
    (try
      (f directory coordinators runners)
      (finally
        (doseq [runner @runners] (transport/stop-runner! runner))
        (doseq [coordinator @coordinators] (transport/stop-coordinator! coordinator))
        (cleanup-scratch! directory)))))

(defn- elapsed [f]
  (let [started (System/nanoTime)
        value (f)]
    {:value value :elapsed-ns (- (System/nanoTime) started)}))

(defn- fixture-matrix [account-count source-count partitions cutpoint mode workers]
  (let [basis (fixture/basis account-count source-count cutpoint)]
    (vec
   (for [account-index (range account-count)
         partition-index (range partitions)
         :let [slot (+ (* account-index partitions) partition-index)
               runner-id (case mode
                           :serial 301
                           :local (+ 301 (mod slot workers))
                           :cross-host (+ 301 (mod slot 2)))]]
     (fixture/work-fixture (+ 100 account-index) runner-id
                           {:account-basis basis :partition-count partitions
                            :partition-index partition-index})))))

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
  (let [construction (elapsed #(fixture-matrix account-count source-count 1 cutpoint :serial 1))
        fixtures (:value construction)
        raw (elapsed #(mapv (fn [f] (accounting/serial-values (:request f))) fixtures))
        compute (elapsed
                 #(mapv (fn [{:keys [work]}]
                          (:accounting-partial (native/execute-work! work))) fixtures))
        reconcile (elapsed #(reconcile-fixtures fixtures (:value compute)))]
    {:mode :serial :accounts account-count :sources-per-account source-count
     :partitions-per-account 1 :compute-ns (:elapsed-ns compute)
     :actual-worker-count 1 :work-construction-ns (:elapsed-ns construction)
     :raw-accounting-ns (:elapsed-ns raw)
     :end-to-end-ns (+ (:elapsed-ns construction) (:elapsed-ns compute) (:elapsed-ns reconcile))
     :reconciliation-ns (:elapsed-ns reconcile)
     :canonical-result-roots (vec (sort (map :canonical-accounting-result/root (:value reconcile))))}))

(defn- start-local-runners [coordinator fixtures directory registry]
  (let [worker-fixtures (->> fixtures
                             (reduce (fn [m f]
                                       (assoc m (get-in f [:participant :runner/reference]) f)) {})
                             vals)]
    (mapv (fn [f]
            (registered! registry (transport/start-runner!
             {:runner-participation (:participant f)
              :auth-secret fixture/secret
              :coordinator-root fixture/coordinator-root
              :coordinator-endpoint (transport/coordinator-endpoint coordinator fixture/secret 60000)
              :state-path (journal-path directory (str "runner-" (get-in f [:participant :runner/reference]) ".edn"))
              :bind-address "127.0.0.1" :port 0})))
          worker-fixtures)))

(defn- dispatch-accounts [coordinator fixtures endpoint-for workers]
  (let [first-fixture (first fixtures)
        requests (->> fixtures (map :request) (distinct))
        ;; Rebuild the authoritative parent from these exact declared account
        ;; sources; no expected result is included in work construction.
        request (first requests)
        parent (accounting/build-request
                {:accounting-domain (:accounting/domain request)
                 :cutpoint-root (:accounting/cutpoint-root request)
                 :accounting-definition-root (:accounting-definition/root request)
                 :accounting-policy-root (:accounting-policy/root request)
                 :reduction-contract (:contract first-fixture)
                 :sources (vec (mapcat :accounting/sources requests))})
        result (batch/dispatch-accounts-and-reconcile!
                coordinator parent (:contract first-fixture)
                (mapv (fn [{:keys [work]}]
                        {:work work :endpoint (endpoint-for (get-in work [:work/assignment :runner/reference]))
                         :logical-cutpoint 1}) fixtures)
                {:max-concurrent-dispatches workers :max-outstanding-per-runner 1})]
    (when-not (= :complete (:accounting/batch-status result))
      (throw (ex-info "Performance sample has incomplete account coverage" result)))
    result))

(defn- batch-roots [result]
  (vec (sort (map #(get-in % [:accounting/reconciliation :canonical-accounting-result/root])
                 (vals (:accounting/account-results result))))))

(defn- coordinator-for [fixtures directory]
  (transport/start-coordinator!
   {:coordinator-root fixture/coordinator-root
    :assignments (mapv :assignment fixtures)
    :state-path (journal-path directory "coordinator.edn")
    :auth-secret fixture/secret :bind-address "127.0.0.1" :port 0}))

(defn- local-sample [account-count source-count partitions cutpoint workers]
  (with-runtimes
   (fn [directory coordinator-registry runner-registry]
    (let [construction (elapsed #(fixture-matrix account-count source-count partitions cutpoint :local workers))
        fixtures (:value construction)
        coordinator-start (elapsed #(registered! coordinator-registry (coordinator-for fixtures directory)))
        coordinator (:value coordinator-start)
        runner-start (elapsed #(start-local-runners coordinator fixtures directory runner-registry))
        runners (:value runner-start)]
      (let [dispatch (elapsed
                      #(dispatch-accounts
                        coordinator fixtures
                        (fn [runner-root]
                          (let [runner (some (fn [r]
                                               (when (= runner-root
                                                        (get-in r [:runner-participation :runner/reference])) r))
                                             runners)]
                            (transport/coordinator-endpoint runner fixture/secret 60000))) workers))
            roots (batch-roots (:value dispatch))]
        {:mode :partitioned-local :accounts account-count :sources-per-account source-count
         :partitions-per-account partitions
         :actual-worker-count (count runners)
         :durable-journals-enabled? (some? directory)
         :max-concurrent-dispatches workers
         :work-construction-ns (:elapsed-ns construction)
         :coordinator-startup-ns (:elapsed-ns coordinator-start)
         :runner-startup-ns (:elapsed-ns runner-start)
         :dispatch-admission-reconciliation-ns (:elapsed-ns dispatch)
         :end-to-end-ns (+ (:elapsed-ns construction) (:elapsed-ns coordinator-start)
                           (:elapsed-ns runner-start) (:elapsed-ns dispatch))
         :canonical-result-roots roots})))))

(defn- cross-host-sample [account-count source-count partitions cutpoint]
  (when (< (* account-count partitions) 2)
    (throw (ex-info "Cross-host comparison needs at least two independent work units" {})))
  (with-runtimes
   (fn [directory coordinator-registry runner-registry]
    (let [runner-url (System/getenv "PRF_REMOTE_ACCOUNTING_RUNNER_URL")
        coordinator-port (some-> (System/getenv "PRF_REMOTE_ACCOUNTING_COORDINATOR_PORT") Integer/parseInt)
        _ (when-not (and (seq runner-url) coordinator-port)
            (throw (ex-info "Cross-host measurement requires the SSH-tunnel runner URL and coordinator port" {})))
        construction (elapsed #(fixture-matrix account-count source-count partitions cutpoint :cross-host 2))
        fixtures (:value construction)
        coordinator-start (elapsed
                           #(registered! coordinator-registry (transport/start-coordinator!
                             {:coordinator-root fixture/coordinator-root
                              :assignments (mapv :assignment fixtures)
                              :state-path (journal-path directory "coordinator.edn")
                              :auth-secret fixture/secret :bind-address "127.0.0.1"
                              :port coordinator-port})))
        coordinator (:value coordinator-start)
        local-fixtures (filterv #(= (fixture/root 301)
                                    (get-in % [:participant :runner/reference])) fixtures)
        ;; Independent account/partition units alternate across two runners.
        local-runner-start (elapsed
                            #(registered! runner-registry (transport/start-runner!
                              {:runner-participation (:participant (first local-fixtures))
                               :auth-secret fixture/secret :coordinator-root fixture/coordinator-root
                               :coordinator-endpoint (transport/coordinator-endpoint coordinator fixture/secret 60000)
                               :state-path (journal-path directory "local-runner.edn")
                               :bind-address "127.0.0.1" :port 0})))
        local-runner (:value local-runner-start)
        remote-endpoint {:transport/base-url runner-url :transport/auth-secret fixture/secret
                         :transport/request-timeout-ms 60000}]
      (let [dispatch (elapsed
                      #(dispatch-accounts
                        coordinator fixtures
                        (fn [runner-root]
                          (if (= runner-root (get-in local-runner [:runner-participation :runner/reference]))
                            (transport/coordinator-endpoint local-runner fixture/secret 60000)
                            remote-endpoint)) 2))]
        {:mode :cross-host-ssh-tunnel :accounts account-count :sources-per-account source-count
         :partitions-per-account partitions
         :actual-worker-count 2 :max-concurrent-dispatches 2
         :local-durable-journals-enabled? (some? directory)
         :remote-journal-profile :not-observed
         :work-construction-ns (:elapsed-ns construction)
         :coordinator-startup-ns (:elapsed-ns coordinator-start)
         :local-runner-startup-ns (:elapsed-ns local-runner-start)
         :remote-runner-startup-included? false
         :dispatch-admission-reconciliation-ns (:elapsed-ns dispatch)
         :end-to-end-ns (+ (:elapsed-ns construction) (:elapsed-ns coordinator-start)
                           (:elapsed-ns local-runner-start) (:elapsed-ns dispatch))
         :canonical-result-roots (batch-roots (:value dispatch))})))))

(defn- ints [s default]
  (if (str/blank? s) default (mapv parse-long (str/split s #","))))

(defn- measured-sample [f]
  (if (= "1" (System/getenv "PRF_ACCOUNTING_PERFORMANCE_PROFILE"))
    (let [{:keys [value] timings :runtime/profile} (profile/profile-call f)]
      (assoc value :runtime/profile timings))
    (assoc (f) :runtime/profile {:status :not-collected})))

(defn -main [& [account-counts sources-per-account partition-counts repetitions worker-counts]]
  (let [account-counts (ints account-counts [1 4])
        sources-per-account (ints sources-per-account [10 100])
        partition-counts (ints partition-counts [1])
        worker-counts (ints worker-counts [1 2 4])
        repetitions (or (some-> repetitions parse-long) 3)
        _ (when-not (and (pos-int? repetitions)
                          (every? #(and (seq %) (every? pos-int? %))
                                  [account-counts sources-per-account partition-counts worker-counts]))
            (throw (ex-info "Measurement axes and repetitions must be positive integers" {})))
        cross-host? (boolean (and (seq (System/getenv "PRF_REMOTE_ACCOUNTING_RUNNER_URL"))
                                  (seq (System/getenv "PRF_REMOTE_ACCOUNTING_COORDINATOR_PORT"))))
        warmups (vec
                 (for [accounts account-counts
                       source-count sources-per-account
                       partitions partition-counts
                       workers worker-counts
                       :when (<= partitions source-count)
                       :let [cutpoint (+ 900000000 (* accounts 100) source-count)]]
                   (do
                     (serial-sample accounts source-count cutpoint)
                     (local-sample accounts source-count partitions cutpoint workers)
                     (when (and cross-host? (= workers 2) (> (* accounts partitions) 1))
                       (cross-host-sample accounts source-count partitions cutpoint))
                     {:accounts accounts :sources-per-account source-count
                      :partitions-per-account partitions :worker-capacity workers})))
        samples
        (vec
         (for [rep (range repetitions)
               accounts account-counts
               source-count sources-per-account
               partitions partition-counts
               workers worker-counts
               :when (<= partitions source-count)
               :let [cutpoint (+ 800000 (* rep 10000) (* accounts 100) source-count)
                     serial (measured-sample #(serial-sample accounts source-count cutpoint))
                     local (measured-sample #(local-sample accounts source-count partitions cutpoint workers))
                     cross-host (when (and cross-host? (= workers 2) (> (* accounts partitions) 1))
                                  (measured-sample #(cross-host-sample accounts source-count partitions cutpoint)))
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
                     :measurement/worker-capacities worker-counts
                     :measurement/local-durable-journals-enabled?
                     (= "1" (System/getenv "PRF_ACCOUNTING_PERFORMANCE_DURABLE"))
                     :measurement/instrumented?
                     (= "1" (System/getenv "PRF_ACCOUNTING_PERFORMANCE_PROFILE"))
                     :measurement/stage-profile :optional-inclusive-current-jvm-only
                     :measurement/repetitions repetitions
                     :measurement/warmups-per-coordinate 1
                     :measurement/warmup-coordinates warmups
                     :measurement/cross-host-runner-prestarted? cross-host?
                     :measurement/samples samples}]
    (try
      (prn measurement)
      (finally
        (shutdown-agents)))))
