(ns resolver-sim.research.runner-transport-test
  (:require [clojure.edn]
            [clojure.test :refer [deftest is]]
            [resolver-sim.research.accelerated-accounting :as accounting]
            [resolver-sim.research.accounting-execution-work :as accounting-work]
            [resolver-sim.research.native-accounting-work :as native-accounting]
            [resolver-sim.research.native-accounting-work-test :as native-accounting-fixture]
            [resolver-sim.research.execution-assurance :as assurance]
            [resolver-sim.research.observed-execution-work :as observed-work]
            [resolver-sim.research.execution-control :as control]
            [resolver-sim.research.anvil-executor-test :as anvil-fixture]
            [resolver-sim.research.remote-execution :as remote]
            [resolver-sim.research.remote-execution-test :as fixture]
            [resolver-sim.research.runner-transport :as transport]))

(def coordinator-root (fixture/fake-root \c))
(def transport-secret "research-lab-test-secret")
(defn- root [n] (str "sha256:" (format "%064x" n)))

(deftest runtime-delta-journal-round-trips-checkpoints-and-ignores-torn-tail
  (let [work (:observed-execution-work (anvil-fixture/observed-run-proof-input))
        participant (:work/runner-participation work)
        state-path (java.io.File/createTempFile "runner-delta-journal-" ".edn")
        initial {:jobs {} :assignments {} :work-items {} :work-states {}
                 :cancellations {} :completed-work {} :forensic-evidence-nodes {}}
        runner {:transport/state-path (.getAbsolutePath state-path)
                :transport/journal (atom {:sequence 0 :format :new})
                :runner-participation participant :transport/coordinator-root coordinator-root}
        write-state (var-get (ns-resolve 'resolver-sim.research.runner-transport
                                         'write-runtime-state!))
        load-state (var-get (ns-resolve 'resolver-sim.research.runner-transport
                                        'read-runtime-journal))
        first-state (assoc-in initial [:completed-work (root 1)]
                              {:observed-run-evidence {:root (root 2)}
                               :history [{:event :completed}]})
        second-state (update-in first-state [:completed-work (root 1) :history]
                                conj {:event :delivery-pending})
        third-state (assoc-in second-state [:completed-work (root 1) :result-deliveries]
                              [{:status :unreachable}])]
    (try
      (write-state runner initial initial)
      (with-redefs-fn {(ns-resolve 'resolver-sim.research.runner-transport
                                   'journal-checkpoint-interval) 2}
        (fn []
          (write-state runner initial first-state)
          (write-state runner first-state second-state)
          (write-state runner second-state third-state)))
      (let [decoded (transport/read-runtime-journal-file (.getAbsolutePath state-path))]
        (is (= "research-runner-runtime-journal.v2" (:artifact/schema decoded)))
        (is (= third-state (:runner-runtime/state decoded)))
        (is (= 3 (:journal/sequence decoded))))
      (java.nio.file.Files/writeString
       (.toPath state-path) "{:journal/record :delta :journal/sequence 4"
       java.nio.charset.StandardCharsets/UTF_8
       (into-array java.nio.file.OpenOption [java.nio.file.StandardOpenOption/APPEND]))
      (is (= third-state
             (:runner-runtime/state
              (transport/read-runtime-journal-file (.getAbsolutePath state-path)))))
      (is (= third-state (:state (load-state (.getAbsolutePath state-path) runner))))
      (is (.endsWith (slurp state-path) "\n"))
      (finally (.delete state-path)))))

(defn- launch-runner-process [config]
  (let [configuration-file (java.io.File/createTempFile "prf-runner-process-config-" ".edn")
        ready-file (java.io.File/createTempFile "prf-runner-process-ready-" ".edn")
        log-file (java.io.File/createTempFile "prf-runner-process-log-" ".txt")
        _ready-deleted (.delete ready-file)
        _configuration-written (spit configuration-file (pr-str config))
        builder (doto (ProcessBuilder.
                       ^java.util.List
                       ["clojure" "-M:test" "-m"
                        "resolver-sim.research.runner-process-fixture"
                        (.getAbsolutePath configuration-file)
                        (.getAbsolutePath ready-file)])
                  (.directory (java.io.File. (System/getProperty "user.dir")))
                  (.redirectErrorStream true)
                  (.redirectOutput log-file))
        process (.start builder)]
    {:process process :configuration-file configuration-file
     :ready-file ready-file :log-file log-file}))

(defn- await-file [file log-file process timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [ready (when (.exists file)
                    (try (clojure.edn/read-string (slurp file))
                         (catch Exception _ nil)))]
        (cond
          (and (map? ready) (:runner/ready? ready)) ready
          (not (.isAlive process))
          (throw (ex-info "Runner child process exited before readiness"
                          {:exit-code (.exitValue process) :marker ready
                           :log (slurp log-file)}))
          (< deadline (System/currentTimeMillis))
          (throw (ex-info "Timed out waiting for runner child process"
                          {:marker ready :log (slurp log-file)}))
          :else (do (Thread/sleep 50) (recur)))))))

(defn- await-journal [state-file predicate timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [journal (when (.exists state-file)
                      (try (transport/read-runtime-journal-file (.getPath state-file))
                           (catch Exception _ nil)))]
        (cond
          (and journal (predicate journal)) journal
          (< deadline (System/currentTimeMillis))
          (throw (ex-info "Timed out waiting for runner journal state"
                          {:journal journal}))
          :else (do (Thread/sleep 50) (recur)))))))

(defn- stop-process! [process]
  (when (.isAlive process)
    (.destroyForcibly process)
    (.waitFor process)))

(defn- delete-process-fixtures! [{:keys [configuration-file ready-file log-file]}]
  (doseq [file [configuration-file ready-file log-file]]
    (.delete ^java.io.File file)))

(defn- accounting-request-and-plan [base]
  (let [contract (accounting/build-reduction-contract)
        source (fn [n category value]
                 {:source/root (root n)
                  :accounting/category category :accounting/value value
                  :accounting/subject-root (root 700)
                  :claim-to/root (root 701)})
        request (accounting/build-request
                 {:accounting-domain :accounting-domain/transport-test
                  :cutpoint-root (root 702)
                  :accounting-definition-root (root 703)
                  :accounting-policy-root (root 704)
                  :reduction-contract contract
                  :sources [(source 710 :accounting/a 3)
                            (source 711 :accounting/b 5)]})
        embedded (:observed-execution-work base)
        plan (accounting/build-partition-plan
              request (mapv (comp vector :source/root) (:accounting/sources request))
              {:dependency-set-root
               (get-in embedded [:work/resolved-dependencies
                                 :resolved-research-dependency-set/root])
               :execution-environment-root
               (get-in embedded [:work/execution-environment
                                 :research-execution-environment/root])
               :allowed-implementation-roots
               [(get-in embedded [:work/implementation
                                  :research-implementation-identity/root])]
               :required-capabilities [:capability/anvil]
               :privacy-requirement :public})]
    {:contract contract :request request :plan plan}))

(defn- accounting-dispatched-work [base request plan unit participant]
  (let [old (:observed-execution-work base)
        env (:work/execution-environment old)
        execution-policy (fixture/execution-policy
                          {:required-runs 1 :required-matching-runs 1
                           :distinct-by [] :repeat-runner-policy :permitted
                           :qualification-references []})
        job (remote/build-job
             {:job-class :job/research-calculation
              :workload-root (:accounting-work-unit/root unit)
              :distributed-profile-root
              (get-in old [:work/job :distributed-experiment-profile/root])
              :distributed-policy-root
              (get-in old [:work/job :distributed-scheduler-policy/root])
              :execution-environment-root (:research-execution-environment/root env)
              :execution-policy-root (:research-execution-policy/root execution-policy)})
        assignment (remote/steal-next job env execution-policy participant [])
        resolved (:work/resolved-dependencies old)
        implementation (:work/implementation old)
        assurance-policy (assurance/build-assurance-policy
                          {:execution-policy-root (:research-execution-policy/root execution-policy)
                           :dependency-policy-root (:research-dependency-policy/root resolved)
                           :required-runs 1 :minimum-distinct-runners 1
                           :minimum-distinct-sites 0 :minimum-distinct-implementations 1
                           :additional-validation-roots [] :proof-generation-required? false
                           :verifier-registry-root nil})
        assured-job (assurance/build-assured-job
                     {:job-root (:research-execution-job/root job)
                      :dependency-manifest-root
                      (get-in old [:work/dependency-manifest :research-dependency-manifest/root])
                      :dependency-policy-root (:research-dependency-policy/root resolved)
                      :resolved-dependency-set-root
                      (:resolved-research-dependency-set/root resolved)
                      :implementation-roots
                      [(:research-implementation-identity/root implementation)]
                      :assurance-policy-root
                      (:research-execution-assurance-policy/root assurance-policy)})
        observed (assoc old :work/job job :work/assignment assignment
                        :work/assured-job assured-job
                        :work/runner-participation participant)
        observed (assoc observed :research-observed-execution-work/root
                        (observed-work/work-root observed))]
    (accounting-work/build-work {:observed-execution-work observed
                                 :request request :partition-plan plan :work-unit unit})))

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

(deftest proof-backed-transport-rejects-unbound-execution-before-posting
  (is (try
        (transport/deliver-native-accounting-proof!
         {:runner {:transport/state (atom {:completed-work {} :work-items {}})}
          :endpoint {:transport/base-url "not-a-valid-endpoint"
                     :transport/auth-secret transport-secret}
          :work-root (root 901) :proof-file "/does/not/exist"
          :metadata {} :generation-receipt {} :logical-cutpoint 1})
        false
        (catch Exception _ true))))

(deftest proof-delivery-artifact-persists-before-journal-reference
  (let [directory (java.nio.file.Files/createTempDirectory
                   "prf-proof-delivery-journal-test-"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        source (.resolve directory "source-proof.bin")
        state-path (.resolve directory "runner-state.edn")
        bytes (.getBytes "real-proof-artifact-bytes" java.nio.charset.StandardCharsets/UTF_8)
        runner {:transport/state-path (str state-path)
                :transport/journal (atom {:sequence 0 :format :new})
                :transport/state
                (atom {:jobs {} :assignments {} :work-items {} :work-states {}
                       :cancellations {} :completed-work {}
                       :forensic-evidence-nodes {}})}
        persist-proof (ns-resolve 'resolver-sim.research.runner-transport
                                  'persist-proof-file!)
        update-state (ns-resolve 'resolver-sim.research.runner-transport
                                 'update-runtime-state!)]
    (try
      (java.nio.file.Files/write source bytes (make-array java.nio.file.OpenOption 0))
      (let [{:keys [proof/content-root proof/file] :as stored}
            (persist-proof runner source)]
        (update-state runner assoc-in
                      [:completed-work (root 902) :proof-delivery]
                      (assoc stored :delivery {:delivery/status :pending}))
        (let [journal (transport/read-runtime-journal-file (str state-path))
              persisted (get-in journal [:runner-runtime/state :completed-work
                                         (root 902) :proof-delivery])]
          (is (= content-root (:proof/content-root persisted)))
          (is (= :pending (get-in persisted [:delivery :delivery/status])))
          (is (= (seq bytes)
                 (seq (java.nio.file.Files/readAllBytes
                       (java.nio.file.Paths/get file (make-array String 0))))))))
      (finally
        (doseq [path [source state-path]]
          (java.nio.file.Files/deleteIfExists path))
        (let [artifact-dir (.resolveSibling state-path "runner-state.edn.proof-artifacts")]
          (when (java.nio.file.Files/exists artifact-dir
                                           (make-array java.nio.file.LinkOption 0))
            (with-open [paths (java.nio.file.Files/list artifact-dir)]
              (doseq [path (iterator-seq (.iterator paths))]
                (java.nio.file.Files/deleteIfExists path)))
            (java.nio.file.Files/deleteIfExists artifact-dir)))
        (java.nio.file.Files/deleteIfExists directory)))))

(deftest persisted-proof-delivery-retries-same-bytes-without-reexecution
  (let [{:keys [work participant]} (native-accounting-fixture/work-fixture 100 301)
        execution (native-accounting/execute-work! work)
        work-root (:research-native-accounting-work/root work)
        directory (java.nio.file.Files/createTempDirectory
                   "prf-proof-redelivery-test-"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        source (.resolve directory "source-proof.bin")
        state-path (.resolve directory "runner-state.edn")
        bytes (.getBytes "persisted-proof-for-redelivery"
                         java.nio.charset.StandardCharsets/UTF_8)
        state {:jobs {} :assignments {} :work-items {work-root work}
               :work-states {} :cancellations {}
               :completed-work {work-root {:job (:work/job work)
                                            :assignment (:work/assignment work)
                                            :native-accounting-execution execution}}
               :forensic-evidence-nodes {}}
        runner {:transport/state-path (str state-path)
                :transport/journal (atom {:sequence 0 :format :new})
                :transport/coordinator-root coordinator-root
                :runner-participation participant
                :transport/state (atom state)}
        persist-proof (ns-resolve 'resolver-sim.research.runner-transport
                                  'persist-proof-file!)
        update-state (ns-resolve 'resolver-sim.research.runner-transport
                                 'update-runtime-state!)
        post-var (ns-resolve 'resolver-sim.research.runner-transport 'post-edn!)
        posted (atom nil)]
    (try
      (java.nio.file.Files/write source bytes (make-array java.nio.file.OpenOption 0))
      (let [stored (merge (persist-proof runner source)
                          {:proof/metadata {"fixture" "test"}
                           :research-proof-generation-receipt {:fixture/root (root 903)}
                           :delivery {:delivery/status :unreachable}
                           :proof-deliveries [{:delivery/status :unreachable}]})]
        (update-state runner assoc-in
                      [:completed-work work-root :proof-delivery] stored)
        (with-redefs-fn
          {post-var (fn [_ _ message]
                      (reset! posted message)
                      {:http/status 200
                       :transport/response
                       {:transport/status :already-admitted
                        :accounting-proof-backed-admission/root (root 904)}})}
          #(let [result (transport/retry-undelivered-accounting-proofs!
                         runner {:transport/base-url "http://fixture.invalid"
                                 :transport/auth-secret transport-secret} 8)
                 persisted (get-in (transport/runner-state runner)
                                   [:completed-work work-root :proof-delivery])
                 decoded (.decode (java.util.Base64/getDecoder)
                                  ^String (:proof/base64 @posted))]
             (is (= :already-admitted
                    (get-in (first result) [:delivery :delivery/status])))
             (is (= (seq bytes) (seq decoded)))
             (is (= 2 (count (:proof-deliveries persisted))))
             (is (= (:research-native-accounting-work/root work)
                    (get-in @posted [:native-accounting-execution :run-evidence
                                     :research-native-accounting-work/root]))))))
      (finally
        (java.nio.file.Files/deleteIfExists source)
        (java.nio.file.Files/deleteIfExists state-path)
        (let [artifact-dir (.resolveSibling state-path "runner-state.edn.proof-artifacts")]
          (when (java.nio.file.Files/exists artifact-dir
                                           (make-array java.nio.file.LinkOption 0))
            (with-open [paths (java.nio.file.Files/list artifact-dir)]
              (doseq [path (iterator-seq (.iterator paths))]
                (java.nio.file.Files/deleteIfExists path)))
            (java.nio.file.Files/deleteIfExists artifact-dir)))
        (java.nio.file.Files/deleteIfExists directory)))))

(deftest proof-delivery-retains-coordinator-rejection-cause
  (let [{:keys [work participant]} (native-accounting-fixture/work-fixture 100 301)
        execution (native-accounting/execute-work! work)
        work-root (:research-native-accounting-work/root work)
        directory (java.nio.file.Files/createTempDirectory
                   "prf-proof-rejection-test-"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        source (.resolve directory "proof.bin")
        state-path (.resolve directory "runner-state.edn")
        state {:jobs {} :assignments {} :work-items {work-root work}
               :work-states {} :cancellations {}
               :completed-work {work-root {:job (:work/job work)
                                            :assignment (:work/assignment work)
                                            :native-accounting-execution execution}}
               :forensic-evidence-nodes {}}
        runner {:transport/state-path (str state-path)
                :transport/journal (atom {:sequence 0 :format :new})
                :transport/coordinator-root coordinator-root
                :runner-participation participant
                :transport/state (atom state)}
        post-var (ns-resolve 'resolver-sim.research.runner-transport 'post-edn!)]
    (try
      (java.nio.file.Files/write source (.getBytes "proof" java.nio.charset.StandardCharsets/UTF_8)
                                 (make-array java.nio.file.OpenOption 0))
      (with-redefs-fn
        {post-var (fn [_ _ _]
                    {:http/status 422
                     :transport/response {:transport/status :proof-admission-rejected
                                          :cause :accounting-proof-context-mismatch
                                          :diagnostic "verifier fixture detail"}})}
        #(let [delivery (transport/deliver-native-accounting-proof!
                         {:runner runner
                          :endpoint {:transport/base-url "http://fixture.invalid"
                                     :transport/auth-secret transport-secret}
                          :work-root work-root :proof-file source :metadata {:test true}
                          :generation-receipt {:test/root (root 905)}
                          :logical-cutpoint 9})]
           (is (= :rejected (:delivery/status delivery)))
           (is (= :proof-admission-rejected (:transport/status delivery)))
           (is (= :accounting-proof-context-mismatch (:transport/cause delivery)))
           (is (= "verifier fixture detail" (:transport/diagnostic delivery)))
           (is (= 422 (:transport/http-status delivery)))))
      (finally
        (java.nio.file.Files/deleteIfExists source)
        (java.nio.file.Files/deleteIfExists state-path)
        (let [artifact-dir (.resolveSibling state-path "runner-state.edn.proof-artifacts")]
          (when (java.nio.file.Files/exists artifact-dir
                                           (make-array java.nio.file.LinkOption 0))
            (with-open [paths (java.nio.file.Files/list artifact-dir)]
              (doseq [path (iterator-seq (.iterator paths))]
                (java.nio.file.Files/deleteIfExists path)))
            (java.nio.file.Files/deleteIfExists artifact-dir)))
        (java.nio.file.Files/deleteIfExists directory)))))

(deftest slow-proof-verification-timeout-retries-identical-persisted-proof
  (let [{:keys [work participant]} (native-accounting-fixture/work-fixture 100 301)
        execution (native-accounting/execute-work! work)
        work-root (:research-native-accounting-work/root work)
        directory (java.nio.file.Files/createTempDirectory
                   "prf-slow-proof-delivery-test-"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        source (.resolve directory "proof.bin")
        state-path (.resolve directory "runner-state.edn")
        bytes (.getBytes "same-proof-bytes-on-retry" java.nio.charset.StandardCharsets/UTF_8)
        state {:jobs {} :assignments {} :work-items {work-root work}
               :work-states {} :cancellations {}
               :completed-work {work-root {:job (:work/job work)
                                            :assignment (:work/assignment work)
                                            :native-accounting-execution execution}}
               :forensic-evidence-nodes {}}
        runner {:transport/state-path (str state-path)
                :transport/journal (atom {:sequence 0 :format :new})
                :transport/coordinator-root coordinator-root
                :runner-participation participant
                :transport/state (atom state)}
        server (com.sun.net.httpserver.HttpServer/create
                (java.net.InetSocketAddress. "127.0.0.1" 0) 0)
        arrivals (atom [])
        auth transport-secret
        _ (.createContext
           server "/v1/accounting-proof"
           (reify com.sun.net.httpserver.HttpHandler
             (handle [_ exchange]
               (let [body (.readAllBytes (.getRequestBody exchange))
                     message (clojure.edn/read-string
                              (String. body java.nio.charset.StandardCharsets/UTF_8))
                     attempt (swap! arrivals conj (:proof/base64 message))]
                 (when (= 1 (count attempt)) (Thread/sleep 150))
                 (let [response (.getBytes
                                 (pr-str {:transport/status :accepted
                                          :accounting-proof-backed-admission/root (root 904)})
                                 java.nio.charset.StandardCharsets/UTF_8)]
                   (try
                     (.sendResponseHeaders exchange 200 (long (alength response)))
                     (with-open [out (.getResponseBody exchange)]
                       (.write out response))
                     (catch java.io.IOException _)))))))
        _ (.setExecutor server nil)]
    (try
      (java.nio.file.Files/write source bytes (make-array java.nio.file.OpenOption 0))
      (.start server)
      (let [endpoint {:transport/base-url (str "http://127.0.0.1:"
                                               (.getPort (.getAddress server)))
                      :transport/auth-secret auth :transport/request-timeout-ms 20}
            first-attempt
            (transport/deliver-native-accounting-proof!
             {:runner runner :endpoint endpoint :work-root work-root :proof-file source
              :metadata {"test" "slow-verifier"} :generation-receipt {:test/root (root 903)}
              :logical-cutpoint 1})]
        (is (= :unreachable (:delivery/status first-attempt)))
        (is (= :unreachable (:transport/error first-attempt)))
        ;; Let the first handler finish after the sender's bounded timeout.
        (Thread/sleep 200)
        (let [retry (transport/retry-undelivered-accounting-proofs!
                     runner (assoc endpoint :transport/request-timeout-ms 2000) 2)
              persisted (get-in (transport/runner-state runner)
                                [:completed-work work-root :proof-delivery])]
          (is (= :accepted (get-in (first retry) [:delivery :delivery/status])))
          (is (= 2 (count @arrivals)))
          (is (= (first @arrivals) (second @arrivals)))
          (is (= 2 (count (:proof-deliveries persisted))))
          (is (= (seq bytes)
                 (seq (java.nio.file.Files/readAllBytes
                       (java.nio.file.Paths/get (:proof/file persisted)
                                                (make-array String 0))))))))
      (finally
        (.stop server 0)
        (java.nio.file.Files/deleteIfExists source)
        (java.nio.file.Files/deleteIfExists state-path)
        (let [artifact-dir (.resolveSibling state-path "runner-state.edn.proof-artifacts")]
          (when (java.nio.file.Files/exists artifact-dir
                                           (make-array java.nio.file.LinkOption 0))
            (with-open [paths (java.nio.file.Files/list artifact-dir)]
              (doseq [path (iterator-seq (.iterator paths))]
                (java.nio.file.Files/deleteIfExists path)))
            (java.nio.file.Files/deleteIfExists artifact-dir)))
        (java.nio.file.Files/deleteIfExists directory)))))

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
      (is (:valid? (transport/forensic-reconstruction runner coordinator)))
      (finally
        (transport/stop-runner! runner)
        (transport/stop-coordinator! coordinator)))))

(deftest dispatched-accounting-partitions-reconcile-from-returned-forensic-partials
  (let [base (anvil-fixture/observed-run-proof-input)
        {:keys [contract request plan]} (accounting-request-and-plan base)
        [left right] (:partition/work-units plan)
        participant-a (get-in base [:observed-execution-work :work/runner-participation])
        participant-b (fixture/runner \b :pseudonymous :site/b :qualifications [])
        left-work (accounting-dispatched-work base request plan left participant-a)
        right-work (accounting-dispatched-work base request plan right participant-b)
        assignments [(get-in left-work [:accounting/observed-execution-work :work/assignment])
                     (get-in right-work [:accounting/observed-execution-work :work/assignment])]
        coordinator (transport/start-coordinator!
                     {:coordinator-root coordinator-root :assignments assignments
                      :auth-secret transport-secret})
        runner-a (transport/start-runner!
                  {:runner-participation participant-a :auth-secret transport-secret
                   :coordinator-root coordinator-root
                   :coordinator-endpoint (transport/coordinator-endpoint coordinator transport-secret)})
        runner-b (transport/start-runner!
                  {:runner-participation participant-b :auth-secret transport-secret
                   :coordinator-root coordinator-root
                   :coordinator-endpoint (transport/coordinator-endpoint coordinator transport-secret)})]
    (try
      (is (accounting-work/valid-work? left-work))
      (is (accounting-work/valid-work? right-work))
      (doseq [[work runner cutpoint] [[left-work runner-a 10] [right-work runner-b 11]]]
        (let [embedded (:accounting/observed-execution-work work)
              delivery (transport/deliver-job!
                        {:coordinator-root coordinator-root
                         :endpoint (transport/coordinator-endpoint runner transport-secret 30000)
                         :job (:work/job embedded) :assignment (:work/assignment embedded)
                         :execution-work work :logical-cutpoint cutpoint})]
          (is (= :accepted (:delivery/status delivery)))))
      (let [state (transport/coordinator-state coordinator)
            partials (get-in state [:accounting-partials
                                    (:accounting-partition-plan/root plan)])
            result (accounting/reconcile request contract plan partials)]
        (is (= 2 (count partials)))
        (is (= :complete (:accounting/completeness
                          (accounting/accounting-completeness request plan partials))))
        (is (= (accounting/serial-values request) (:accounting/values result)))
        (is (= 2 (count (:accounting-partial-result/roots result))))
        (is (every? (set (map #(get-in % [:run-assurance-evidence
                                          :research-run-assurance-evidence/root])
                              (vals (:observed-results state))))
                    (map :research-run-assurance-evidence/root (vals partials)))))
      (finally
        (transport/stop-runner! runner-a)
        (transport/stop-runner! runner-b)
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
            (is (= [:unreachable :accepted]
                   (mapv :delivery/status
                         (get-in (transport/runner-state runner)
                                 [:completed-work (:research-observed-execution-work/root work)
                                  :result-deliveries]))))
            (is (not= (get-in (transport/runner-state runner)
                              [:completed-work (:research-observed-execution-work/root work)
                               :result-deliveries 0 :reachability
                               :research-realized-reachability/root])
                      (get-in (transport/runner-state runner)
                              [:completed-work (:research-observed-execution-work/root work)
                               :result-deliveries 1 :reachability
                               :research-realized-reachability/root])))
            (is (= :accepted
                   (get-in (transport/runner-state runner)
                           [:completed-work (:research-observed-execution-work/root work)
                            :result-delivery :delivery/status]))))
          (finally
            (transport/stop-coordinator! coordinator))))
      (finally
        (transport/stop-runner! runner)))))

(deftest persisted-completed-work-redelivers-after-runner-restart-without-reexecution
  (let [fixture (anvil-fixture/observed-run-proof-input)
        work (:observed-execution-work fixture)
        assignment (:work/assignment work)
        state-file (java.io.File/createTempFile "prf-runner-state-" ".edn")]
    (.delete state-file)
    (let [first-runner (transport/start-runner!
                        {:runner-participation (:work/runner-participation work)
                         :auth-secret transport-secret :state-path (.getPath state-file)
                         :coordinator-root coordinator-root
                         :coordinator-endpoint {:transport/base-url "http://127.0.0.1:1"
                                                :transport/auth-secret transport-secret
                                                :transport/request-timeout-ms 100}})]
      (try
        (transport/deliver-job!
         {:coordinator-root coordinator-root
          :endpoint (transport/coordinator-endpoint first-runner transport-secret 30000)
          :job (:work/job work) :assignment assignment :execution-work work
          :logical-cutpoint 4})
        (let [completed (get-in (transport/runner-state first-runner)
                                [:completed-work (:research-observed-execution-work/root work)])]
          (is (= :completed (get-in (transport/runner-state first-runner)
                                    [:work-states (:research-observed-execution-work/root work)
                                     :work/status])))
          (is (= :unreachable (get-in completed [:result-delivery :delivery/status])))
          (is (.exists state-file)))
        (finally
          (transport/stop-runner! first-runner))))
    (let [coordinator (transport/start-coordinator!
                       {:coordinator-root coordinator-root :assignments [assignment]
                        :auth-secret transport-secret})
          restarted (transport/start-runner!
                     {:runner-participation (:work/runner-participation work)
                      :auth-secret transport-secret :state-path (.getPath state-file)
                      :coordinator-root coordinator-root
                      :coordinator-endpoint (transport/coordinator-endpoint coordinator transport-secret)
                      :recovery-logical-cutpoint 9})]
      (try
        (let [work-root (:research-observed-execution-work/root work)
              completed (get-in (transport/runner-state restarted) [:completed-work work-root])
              observed (:observed-run-evidence completed)
              replay (transport/deliver-job!
                      {:coordinator-root coordinator-root
                       :endpoint (transport/coordinator-endpoint restarted transport-secret 30000)
                       :job (:work/job work) :assignment assignment :execution-work work
                       :logical-cutpoint 10})
              journal (transport/read-runtime-journal-file (.getPath state-file))]
          (is (= :result-acknowledged
                 (get-in (transport/runner-state restarted)
                         [:work-states work-root :work/status])))
          (is (= [:unreachable :accepted]
                 (mapv :delivery/status (:result-deliveries completed))))
          (is (= observed
                 (get-in (transport/coordinator-state coordinator)
                         [:observed-results (:research-observed-run-evidence/root observed)
                          :observed-run-evidence])))
          (is (= :already-completed (:delivery/status replay)))
          (is (= 1 (count (filter #(= :execution-started (:work/event %))
                                  (get-in (transport/runner-state restarted)
                                          [:work-states work-root :work/history])))))
          (is (= "research-runner-runtime-journal.v2" (:artifact/schema journal)))
          (is (= (:runner-participation/root (:work/runner-participation work))
                 (:runner-participation/root journal))))
        (finally
          (transport/stop-runner! restarted)
          (transport/stop-coordinator! coordinator)
          (.delete state-file))))))

(deftest child-jvm-crash-after-completion-redelivers-without-reexecution
  (let [fixture (anvil-fixture/observed-run-proof-input)
        work (:observed-execution-work fixture)
        job (:work/job work)
        assignment (:work/assignment work)
        participant (:work/runner-participation work)
        work-root (:research-observed-execution-work/root work)
        state-file (java.io.File/createTempFile "prf-runner-subprocess-state-" ".edn")
        first-runner (atom nil) restarted (atom nil)
        first-info (atom nil)
        second-info (atom nil)
        completed-evidence (atom nil)
        endpoint {:transport/base-url "http://127.0.0.1:1"
                  :transport/auth-secret transport-secret
                  :transport/request-timeout-ms 100}]
    (.delete state-file)
    (try
      (reset! first-info
              (launch-runner-process
               {:runner-participation participant :auth-secret transport-secret
                :state-path (.getAbsolutePath state-file)
                :coordinator-root coordinator-root :coordinator-endpoint endpoint
                :recovery-logical-cutpoint 5}))
      (reset! first-runner @first-info)
      (let [ready (await-file (:ready-file @first-info) (:log-file @first-info)
                              (:process @first-info) 60000)]
        (let [delivery (transport/deliver-job!
                        {:coordinator-root coordinator-root
                         :endpoint {:transport/base-url
                                    (:runner/base-url ready)
                                    :transport/auth-secret transport-secret
                                    :transport/request-timeout-ms 30000}
                         :job job :assignment assignment :execution-work work
                         :logical-cutpoint 6})
              completed (await-journal
                         state-file #(= :unreachable
                                        (get-in % [:runner-runtime/state :completed-work
                                                   work-root :result-delivery :delivery/status]))
                         60000)]
          (is (= :accepted (:delivery/status delivery)))
          (is (= :completed
                 (get-in completed [:runner-runtime/state :work-states work-root :work/status])))
          (is (= 1 (count (filter #(= :execution-started (:work/event %))
                                  (get-in completed [:runner-runtime/state :work-states
                                                     work-root :work/history])))))
          (reset! completed-evidence
                  (select-keys (get-in completed [:runner-runtime/state :completed-work work-root])
                               [:observed-run-evidence :run-assurance-evidence
                                :accounting-context :accounting-partial]))
          (stop-process! (:process @first-runner))))
      (let [coordinator (transport/start-coordinator!
                         {:coordinator-root coordinator-root :assignments [assignment]
                          :auth-secret transport-secret})]
        (try
          (reset! second-info
                  (launch-runner-process
                   {:runner-participation participant :auth-secret transport-secret
                    :state-path (.getAbsolutePath state-file)
                    :coordinator-root coordinator-root
                    :coordinator-endpoint
                    (transport/coordinator-endpoint coordinator transport-secret 30000)
                    :recovery-logical-cutpoint 10}))
          (reset! restarted @second-info)
          (is (:runner/ready?
               (await-file (:ready-file @second-info) (:log-file @second-info)
                           (:process @second-info) 60000)))
          (let [journal (await-journal
                         state-file #(= :result-acknowledged
                                        (get-in % [:runner-runtime/state :work-states
                                                   work-root :work/status]))
                         30000)
                completed (get-in journal [:runner-runtime/state :completed-work work-root])
                evidence (:observed-run-evidence completed)
                coordinator-state (transport/coordinator-state coordinator)]
            (is (= [:unreachable :accepted]
                   (mapv :delivery/status (:result-deliveries completed))))
            (is (= (pr-str @completed-evidence)
                   (pr-str (select-keys completed
                                        [:observed-run-evidence :run-assurance-evidence
                                         :accounting-context :accounting-partial]))))
            (is (= 1 (count (:observed-results coordinator-state))))
            (is (= evidence
                   (get-in coordinator-state
                           [:observed-results
                            (:research-observed-run-evidence/root evidence)
                            :observed-run-evidence])))
            (is (= 1 (count (filter #(= :execution-started (:work/event %))
                                    (get-in journal [:runner-runtime/state :work-states
                                                     work-root :work/history]))))))
          (finally (stop-process! (:process @restarted))
                   (transport/stop-coordinator! coordinator))))
      (finally
        (when-let [process (:process @first-runner)] (stop-process! process))
        (when-let [process (:process @restarted)] (stop-process! process))
        (.delete ^java.io.File state-file)
        (when @first-info (delete-process-fixtures! @first-info))
        (when @second-info (delete-process-fixtures! @second-info))))))

(deftest persisted-interruption-and-cancellation-follow-safe-recovery-rules
  (let [fixture (anvil-fixture/observed-run-proof-input)
        work (:observed-execution-work fixture)
        job (:work/job work)
        assignment (:work/assignment work)
        participant (:work/runner-participation work)
        work-root (:research-observed-execution-work/root work)
        interrupted-file (java.io.File/createTempFile "prf-runner-interrupted-" ".edn")
        cancelled-file (java.io.File/createTempFile "prf-runner-cancelled-" ".edn")
        cancellation (control/build-cancellation
                      {:job-root (:research-execution-job/root job)
                       :control-epoch 1 :reason :user-requested
                       :requested-cutpoint 2 :scope :entire-job :target-roots []})
        base-state {:jobs {(:research-execution-job/root job) job}
                    :assignments {(:research-run-assignment/root assignment) assignment}
                    :work-items {work-root work}
                    :work-states {work-root {:work/status :executing
                                             :work/attempt-sequence 1
                                             :work/history [{:work/event :execution-started
                                                             :work/status :executing
                                                             :work/logical-cutpoint 1
                                                             :work/attempt-sequence 1}]}}
                    :cancellations {} :completed-work {}
                    :forensic-evidence-nodes {}}
        journal (fn [state]
                  {:artifact/schema "research-runner-runtime-journal.v1"
                   :runner-participation/root (:runner-participation/root participant)
                   :coordinator/root nil :runner-runtime/state state})]
    (try
      (spit interrupted-file (pr-str (journal base-state)))
      (let [runner (transport/start-runner!
                    {:runner-participation participant :auth-secret transport-secret
                     :state-path (.getPath interrupted-file) :recovery-logical-cutpoint 7})]
        (try
          (is (= :interrupted (get-in (transport/runner-state runner)
                                      [:work-states work-root :work/status])))
          (is (= :fresh-isolated-retry-on-redelivery
                 (get-in (transport/runner-state runner)
                         [:work-states work-root :work/history 1 :work/recovery-rule])))
          (is (empty? (:completed-work (transport/runner-state runner))))
          (finally (transport/stop-runner! runner))))
      (let [cancelled-state (-> base-state
                                (assoc :cancellations {(:research-execution-job/root job) cancellation})
                                (assoc-in [:work-states work-root :work/status] :cancelled))]
        (spit cancelled-file (pr-str (journal cancelled-state)))
        (let [runner (transport/start-runner!
                      {:runner-participation participant :auth-secret transport-secret
                       :state-path (.getPath cancelled-file)})]
          (try
            (let [delivery (transport/deliver-job!
                            {:coordinator-root coordinator-root
                             :endpoint (transport/coordinator-endpoint runner transport-secret)
                             :job job :assignment assignment :execution-work work
                             :logical-cutpoint 8})]
              (is (= :cancelled-before-start (:delivery/status delivery)))
              (is (= :cancelled (get-in (transport/runner-state runner)
                                        [:work-states work-root :work/status])))
              (is (empty? (:completed-work (transport/runner-state runner)))))
            (finally (transport/stop-runner! runner)))))
      (finally
        (.delete interrupted-file)
        (.delete cancelled-file)))))

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

(deftest coordinator-persists-dispatch-while-runner-is-unavailable-and-retries-on-return
  (let [fixture (anvil-fixture/observed-run-proof-input)
        work (:observed-execution-work fixture)
        assignment (:work/assignment work)
        state-file (java.io.File/createTempFile "prf-coordinator-state-" ".edn")]
    (.delete state-file)
    (let [coordinator (transport/start-coordinator!
                       {:coordinator-root coordinator-root :assignments [assignment]
                        :auth-secret transport-secret :state-path (.getPath state-file)})]
      (try
        (let [outcome (transport/dispatch-job!
                       coordinator
                       {:endpoint {:transport/base-url "http://127.0.0.1:1"
                                   :transport/auth-secret transport-secret
                                   :transport/request-timeout-ms 100}
                        :job (:work/job work) :assignment assignment
                        :execution-work work :logical-cutpoint 40})]
          (is (= :unreachable (:delivery/status outcome)))
          (is (= :pending (get-in (transport/coordinator-state coordinator)
                                  [:dispatch-intents
                                   (:research-run-assignment/root assignment)
                                   :dispatch/status])))
          (is (.exists state-file)))
        (finally (transport/stop-coordinator! coordinator))))
    (let [coordinator (transport/start-coordinator!
                       {:coordinator-root coordinator-root :assignments []
                        :auth-secret transport-secret :state-path (.getPath state-file)})
          runner (transport/start-runner!
                  {:runner-participation (:work/runner-participation work)
                   :auth-secret transport-secret :coordinator-root coordinator-root
                   :coordinator-endpoint (transport/coordinator-endpoint coordinator transport-secret)})]
      (try
        (let [outcomes (transport/retry-pending-dispatches!
                        coordinator
                        (fn [_] (transport/coordinator-endpoint runner transport-secret 30000))
                        41)
              work-root (:research-observed-execution-work/root work)]
          (is (= [:accepted] (mapv :delivery/status outcomes)))
          (is (= :result-acknowledged
                 (get-in (transport/runner-state runner)
                         [:work-states work-root :work/status])))
          (is (= :delivered (get-in (transport/coordinator-state coordinator)
                                    [:dispatch-intents
                                     (:research-run-assignment/root assignment)
                                     :dispatch/status])))
          (is (= 1 (count (filter #(= :execution-started (:work/event %))
                                  (get-in (transport/runner-state runner)
                                          [:work-states work-root :work/history]))))))
        (finally
          (transport/stop-runner! runner)
          (transport/stop-coordinator! coordinator)
          (.delete state-file))))))

(deftest cancellation-after-acceptance-before-execution-prevents-semantic-work
  (let [fixture (anvil-fixture/observed-run-proof-input)
        work (:observed-execution-work fixture)
        assignment (:work/assignment work)
        participant (:work/runner-participation work)
        runner (transport/start-runner!
                {:runner-participation participant :auth-secret transport-secret
                 :coordinator-root coordinator-root
                 :coordinator-endpoint {:transport/base-url "http://127.0.0.1:1"
                                        :transport/auth-secret transport-secret
                                        :transport/request-timeout-ms 100}})]
    (try
      (let [accepted (transport/deliver-job!
                      {:coordinator-root coordinator-root
                       :endpoint (transport/coordinator-endpoint runner transport-secret)
                       :job (:work/job work) :assignment assignment :execution-work work
                       :execute? false :logical-cutpoint 50})
            cancellation (control/build-cancellation
                          {:job-root (:research-execution-job/root (:work/job work))
                           :control-epoch 1 :reason :user-requested
                           :requested-cutpoint 51 :scope :entire-job :target-roots []})
            observed (transport/deliver-cancellation!
                      {:coordinator-root coordinator-root
                       :endpoint (transport/coordinator-endpoint runner transport-secret)
                       :cancellation cancellation :runner-participation participant
                       :assignment assignment :logical-cutpoint 51})
            replay (transport/deliver-job!
                    {:coordinator-root coordinator-root
                     :endpoint (transport/coordinator-endpoint runner transport-secret)
                     :job (:work/job work) :assignment assignment :execution-work work
                     :logical-cutpoint 52})
            root (:research-observed-execution-work/root work)]
        (is (= :accepted (:delivery/status accepted)))
        (is (= :observed (:delivery/status observed)))
        (is (= :cancelled-before-start (:delivery/status replay)))
        (is (= :cancelled (get-in (transport/runner-state runner)
                                  [:work-states root :work/status])))
        (is (empty? (:completed-work (transport/runner-state runner)))))
      (finally (transport/stop-runner! runner)))))

(deftest cancellation-after-atomic-execution-retains-evidence-but-stops-result-delivery
  (let [fixture (anvil-fixture/observed-run-proof-input)
        work (:observed-execution-work fixture)
        assignment (:work/assignment work)
        participant (:work/runner-participation work)
        cancellation (control/build-cancellation
                      {:job-root (:research-execution-job/root (:work/job work))
                       :control-epoch 1 :reason :user-requested
                       :requested-cutpoint 61 :scope :entire-job :target-roots []})
        runner* (atom nil)
        runner (transport/start-runner!
                {:runner-participation participant :auth-secret transport-secret
                 :coordinator-root coordinator-root
                 :coordinator-endpoint {:transport/base-url "http://127.0.0.1:1"
                                        :transport/auth-secret transport-secret
                                        :transport/request-timeout-ms 100}
                 ;; Runtime-only hook: the Anvil action has already completed;
                 ;; this publishes cancellation at the next safe boundary.
                 :before-result-delivery
                 (fn [_ _ cutpoint]
                   (transport/deliver-cancellation!
                    {:coordinator-root coordinator-root
                     :endpoint (transport/coordinator-endpoint @runner* transport-secret)
                     :cancellation cancellation :runner-participation participant
                     :assignment assignment :logical-cutpoint cutpoint}))})]
    (reset! runner* runner)
    (try
      (transport/deliver-job!
       {:coordinator-root coordinator-root
        :endpoint (transport/coordinator-endpoint runner transport-secret)
        :job (:work/job work) :assignment assignment :execution-work work
        :execute? false :logical-cutpoint 60})
      (let [executed (transport/execute-delivered-work! runner work 61)
            root (:research-observed-execution-work/root work)
            completed (get-in (transport/runner-state runner) [:completed-work root])]
        (is (:observed-run-evidence completed))
        (is (= :cancelled-before-delivery
               (get-in completed [:result-delivery :delivery/status])))
        (is (= :completed (get-in (transport/runner-state runner)
                                  [:work-states root :work/status])))
        (is (= :cancelled-before-delivery
               (get-in executed [:result-delivery :delivery/status])))
        (is (nil? (get-in completed [:result-delivery :reachability])))
        (is (:valid? (transport/forensic-reconstruction runner))))
      (finally (transport/stop-runner! runner)))))
