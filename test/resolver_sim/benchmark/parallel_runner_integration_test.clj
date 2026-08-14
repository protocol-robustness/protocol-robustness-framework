(ns resolver-sim.benchmark.parallel-runner-integration-test
  "Hermetic coverage of the runner's bounded parallel execution path. Replay is
  replaced with a deterministic, file-producing worker so the test exercises
  runner orchestration without requiring a protocol registry or real suite."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.repo :as repo]
            [resolver-sim.benchmark.runner :as runner]
            [resolver-sim.vcs :as vcs])
  (:import [java.util.concurrent CountDownLatch]))

(defn- temp-dir! []
  (doto (java.io.File/createTempFile "parallel-runner-" "")
    (.delete)
    (.mkdirs)))

(defn- delete-tree! [file]
  (doseq [entry (reverse (file-seq file))]
    (.delete entry)))

(defn- artifact-bytes [root]
  (into (sorted-map)
        (for [file (file-seq (io/file root))
              :when (and (.isFile file)
                         (not= "benchmark-index.edn" (.getName file)))]
          [(str (.relativize (.toPath (io/file root)) (.toPath file)))
           (vec (java.nio.file.Files/readAllBytes (.toPath file)))])))

(defn- clean-source-provenance []
  {:git-commit-sha "sha256:parallel-runner-test-commit"
   :source/hash "sha256:parallel-runner-test-source"
   :source/hash-algorithm "source-tree-hash-v1"
   :source/hash-roots []
   :code-hash "sha256:parallel-runner-test-code"
   :deps-hash "sha256:parallel-runner-test-deps"
   :input-hash "sha256:parallel-runner-test-input"
   :dirty? false})

(defn- deterministic-worker
  "Small stand-in for protocol replay. It deliberately uses the runner's real
  package writer and manifest builder, so staging and coordinator publication
  still validate byte-level artifact integrity."
  [worker-threads start-barrier _suite source plan-entry _run-count staging-root]
  (swap! worker-threads conj (.getName (Thread/currentThread)))
  ;; The two parallel chunks must both enter before either can complete. This
  ;; proves concurrency without relying on timing-sensitive sleeps.
  (when start-barrier
    (.countDown ^CountDownLatch start-barrier)
    (.await ^CountDownLatch start-barrier))
  (let [scenario-id (-> (:input/display-name source)
                        (clojure.string/replace #"\.edn$" ""))
        semantic-root (str "sha256:semantic-" scenario-id)
        replay-result {:outcome :pass
                       :pass? true
                       :events-processed 1
                       :scenario-id scenario-id
                       :semantic-root semantic-root}
        artifact-dir (#'runner/staging-execution-dir staging-root plan-entry)
        artifacts (#'runner/write-execution-package! artifact-dir source
                                                     {:scenario-id scenario-id
                                                      :protocol "test-v1"}
                                                     replay-result)]
    {:execution/id (:execution/id plan-entry)
     :execution/ordinal (:execution/ordinal plan-entry)
     :execution/descriptor (:execution/descriptor plan-entry)
     :scenario/id scenario-id
     :benchmark/run-index 0
     :benchmark/run-count 1
     :outcome :pass
     :pass? true
     :events-processed 1
     :invariant-results []
     :scenario/evidence-root semantic-root
     :scenario/artifacts artifacts
     :scenario/artifact-manifest
     (#'runner/artifact-manifest-for-dir artifact-dir semantic-root)}))

(deftest run-benchmark-is-plan-ordered-across-bounded-parallel-configurations
  (let [root (temp-dir!)
        scenarios-dir (doto (io/file root "scenarios") .mkdirs)
        manifest-file (io/file root "benchmark.edn")
        _ (doseq [id ["alpha" "bravo" "charlie" "delta"]]
            (spit (io/file scenarios-dir (str id ".edn"))
                  (str "{:scenario-id \"" id "\" :protocol \"test-v1\"}")))
        _ (spit manifest-file
                (pr-str {:benchmark/id :benchmark/hermetic-parallel-runner
                         :scenario-suites [(.getPath scenarios-dir)]
                         :benchmark/claims []}))
        serial-output (.getPath (io/file root "serial-output"))
        parallel-output (.getPath (io/file root "parallel-output"))
        serial-threads (atom #{})
        parallel-threads (atom #{})
        run! (fn [output parallelism chunk-size threads]
               (let [start-barrier (when (> parallelism 1) (CountDownLatch. 2))]
                 (with-redefs-fn {#'repo/metadata (fn [] {:repo {:commit "test" :dirty? false}})
                                  #'vcs/source-provenance clean-source-provenance
                                  #'resolver-sim.benchmark.runner/validate-and-freeze-global-prerequisites!
                                  (fn [_] true)
                                  #'resolver-sim.benchmark.runner/execute-scenario
                                  (partial deterministic-worker threads start-barrier)}
                   #(runner/run-benchmark (.getPath manifest-file) runner/default-adapter
                                          {:scenario-output-dir output
                                           :parallelism parallelism
                                           :chunk-size chunk-size}))))]
    (try
      (let [serial (run! serial-output 1 1 serial-threads)
            parallel (run! parallel-output 2 2 parallel-threads)
            result-projection (fn [evidence]
                                (mapv #(select-keys % [:execution/id :execution/ordinal
                                                       :scenario/id :outcome :pass?
                                                       :scenario/evidence-root])
                                      (:results evidence)))]
        (testing "parallel execution restores the frozen plan order and semantic roots"
          (is (= (result-projection serial) (result-projection parallel)))
          (is (= [1 2 3 4] (mapv :execution/ordinal (:results parallel))))
          (is (= (mapv (comp #(str "sha256:semantic-" %) :scenario/id)
                       (:results parallel))
                 (mapv :scenario/evidence-root (:results parallel)))))
        (testing "the bounded executor uses more than one worker for two chunks"
          (is (= 1 (count @serial-threads)))
          (is (> (count @parallel-threads) 1)
              (str "Expected multiple worker threads, got " @parallel-threads)))
        (testing "published scenario artifacts are byte-identical despite parallelism and chunk size"
          (is (= (artifact-bytes serial-output) (artifact-bytes parallel-output)))
          (is (every? #(true? (get-in % [:scenario/artifact-manifest
                                         :artifact/canonical-verified]))
                      (:results parallel)))))
      (finally
        (delete-tree! root)))))

(deftest canonical-artifact-assembly-ignores-forced-reverse-producer-completion
  (let [root (temp-dir!)
        scenarios-dir (doto (io/file root "scenarios") .mkdirs)
        manifest-file (io/file root "benchmark.edn")
        ids ["alpha" "bravo" "charlie" "delta"]
        _ (doseq [id ids]
            (spit (io/file scenarios-dir (str id ".edn"))
                  (str "{:scenario-id \"" id "\" :protocol \"test-v1\"}")))
        _ (spit manifest-file
                (pr-str {:benchmark/id :benchmark/forced-completion-order
                         :scenario-suites [(.getPath scenarios-dir)]
                         :benchmark/claims []}))
        serial-output (.getPath (io/file root "serial-output"))
        reverse-output (.getPath (io/file root "reverse-output"))
        worker-threads (atom #{})
        started (CountDownLatch. (count ids))
        release-gates (zipmap ids (repeatedly (count ids) promise))
        completed-gates (zipmap ids (repeatedly (count ids) promise))
        completion-order (atom [])
        controlled-worker
        (fn [suite source plan-entry run-count staging-root]
          (let [scenario-id (-> (:input/display-name source)
                                (clojure.string/replace #"\\.edn$" ""))]
            (.countDown started)
            (.await started)
            @(get release-gates scenario-id)
            (let [result (deterministic-worker worker-threads nil suite source plan-entry run-count staging-root)]
              (swap! completion-order conj scenario-id)
              (deliver (get completed-gates scenario-id) true)
              result)))
        run-serial! (fn []
                      (with-redefs-fn {#'repo/metadata (fn [] {:repo {:commit "test" :dirty? false}})
                                       #'vcs/source-provenance clean-source-provenance
                                       #'resolver-sim.benchmark.runner/validate-and-freeze-global-prerequisites! (fn [_] true)
                                       #'resolver-sim.benchmark.runner/execute-scenario
                                       (partial deterministic-worker (atom #{}) nil)}
                        #(runner/run-benchmark (.getPath manifest-file) runner/default-adapter
                                               {:scenario-output-dir serial-output
                                                :parallelism 1 :chunk-size 1})))
        run-reverse! (fn []
                       (with-redefs-fn {#'repo/metadata (fn [] {:repo {:commit "test" :dirty? false}})
                                        #'vcs/source-provenance clean-source-provenance
                                        #'resolver-sim.benchmark.runner/validate-and-freeze-global-prerequisites! (fn [_] true)
                                        #'resolver-sim.benchmark.runner/execute-scenario controlled-worker}
                         #(runner/run-benchmark (.getPath manifest-file) runner/default-adapter
                                                {:scenario-output-dir reverse-output
                                                 :parallelism 4 :chunk-size 1})))]
    (try
      (let [serial (run-serial!)
            reverse-outcome (promise)
            coordinator (doto (Thread.
                               (fn []
                                 (try
                                   (deliver reverse-outcome (run-reverse!))
                                   (catch Throwable t
                                     (deliver reverse-outcome t)))))
                          (.setName "forced-completion-test-coordinator")
                          (.start))]
        (when-not (.await started 10 java.util.concurrent.TimeUnit/SECONDS)
          (throw (ex-info "Timed out waiting for all detached producers to start" {})))
        ;; Deliberately complete detached producers in an order different from
        ;; the frozen alphabetical plan order: C, A, D, B.
        (doseq [id ["charlie" "alpha" "delta" "bravo"]]
          (deliver (get release-gates id) true)
          (is (true? @(get completed-gates id)) (str id " completed after release")))
        (.join coordinator 10000)
        (let [parallel @reverse-outcome
              _ (when (instance? Throwable parallel) (throw parallel))
              projection (fn [evidence]
                           (mapv #(select-keys % [:execution/id :execution/ordinal
                                                  :scenario/id :scenario/evidence-root])
                                 (:results evidence)))]
          (testing "forced producer completion order is genuinely noncanonical"
            (is (= ["charlie" "alpha" "delta" "bravo"] @completion-order))
            (is (> (count @worker-threads) 1)))
          (testing "coordinator restores canonical concatenation/assembly order"
            (is (= ["alpha" "bravo" "charlie" "delta"]
                   (mapv :scenario/id (:results parallel))))
            (is (= [1 2 3 4] (mapv :execution/ordinal (:results parallel))))
            (is (= (projection serial) (projection parallel)))
            (is (= (:evidence/hash serial) (:evidence/hash parallel)))
            (is (= (artifact-bytes serial-output) (artifact-bytes reverse-output)))
            (is (every? #(true? (get-in % [:scenario/artifact-manifest
                                            :artifact/canonical-verified]))
                        (:results parallel))))))
      (finally
        ;; Do not leave a worker blocked if an assertion or timeout aborts this
        ;; controlled-interleaving test before all releases are issued.
        (doseq [[_ gate] release-gates]
          (deliver gate true))
        (delete-tree! root)))))

(deftest run-benchmark-replays-dummy-protocol-scenarios-across-parallelism
  (let [root (temp-dir!)
        scenarios-dir (doto (io/file root "scenarios") .mkdirs)
        manifest-file (io/file root "dummy-benchmark.edn")
        scenario (fn [id]
                   {:scenario-id id
                    :schema-version "1.0"
                    :initial-block-time 1000
                    :protocol "dummy"
                    :agents [{:id "agent" :address "0xA"}]
                    :events [{:seq 0 :time 1000 :agent "agent"
                              :action "noop" :params {}}]})
        _ (doseq [id ["dummy-alpha" "dummy-bravo"]]
            (spit (io/file scenarios-dir (str id ".edn")) (pr-str (scenario id))))
        _ (spit manifest-file
                (pr-str {:benchmark/id :benchmark/hermetic-dummy-parallel-runner
                         :scenario-suites [(.getPath scenarios-dir)]
                         :benchmark/claims []}))
        serial-output (.getPath (io/file root "serial-output"))
        parallel-output (.getPath (io/file root "parallel-output"))
        run! (fn [output parallelism]
               (runner/run-benchmark (.getPath manifest-file) runner/default-adapter
                                     {:scenario-output-dir output
                                      :parallelism parallelism
                                      :chunk-size 1}))]
    (try
      (let [serial (run! serial-output 1)
            parallel (run! parallel-output 2)
            projection (fn [evidence]
                         (mapv #(select-keys % [:execution/id :execution/ordinal
                                                :scenario/id :outcome :pass?
                                                :scenario/evidence-root])
                               (:results evidence)))]
        (testing "real dummy-protocol replay is plan ordered and semantically stable"
          (is (= (projection serial) (projection parallel)))
          (is (= (:evidence/hash serial) (:evidence/hash parallel))
              "Operational output paths and scheduling do not affect the bundle root")
          (is (= [1 2] (mapv :execution/ordinal (:results parallel))))
          (is (every? #(= :pass (:outcome %)) (:results parallel))))
        (testing "canonical evidence retains protocol identity, not the JVM adapter"
          (is (= {:protocol/id "dummy" :protocol/version 1}
                 (:scenario/protocol (first (:results serial)))))
          (is (not (re-find #"DummyProtocol|#object" (pr-str serial)))))
        (testing "coordinator publishes and verifies each canonical execution package"
          (doseq [[evidence output] [[serial serial-output] [parallel parallel-output]]]
            (is (= 2 (count (filter #(.isDirectory %) (.listFiles (io/file output))))))
            (is (every? #(and (.isDirectory (io/file (get-in % [:scenario/artifacts :scenario/artifact-dir])))
                              (true? (get-in % [:scenario/artifact-manifest
                                                :artifact/canonical-verified])))
                        (:results evidence))))))
      (finally
        (delete-tree! root)))))
