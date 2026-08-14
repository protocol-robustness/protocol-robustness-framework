(ns resolver-sim.commands.benchmark-canonical-package-lifecycle-integration-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.cli :as benchmark-cli]
            [resolver-sim.benchmark.verify :as benchmark-verify]
            [resolver-sim.benchmark.runner :as runner]
            [resolver-sim.commands.run-benchmark :as command]))

(defn- temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
            "canonical-benchmark-package-lifecycle-"
            (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- delete-tree! [path]
  (when (.exists (io/file path))
    (doseq [file (reverse (file-seq (io/file path)))]
      (io/delete-file file true))))

(defn- write-dummy-fixture! [root]
  (let [scenarios (doto (io/file root "scenarios") .mkdirs)
        manifest (io/file root "dummy-benchmark.edn")
        scenario (fn [id]
                   {:scenario-id id
                    :schema-version "1.0"
                    :initial-block-time 1000
                    :protocol "dummy"
                    :agents [{:id "agent" :address "0xA"}]
                    :events [{:seq 0 :time 1000 :agent "agent"
                              :action "noop" :params {}}]})]
    (doseq [id ["dummy-alpha" "dummy-bravo" "dummy-charlie"]]
      (spit (io/file scenarios (str id ".edn")) (pr-str (scenario id))))
    (spit manifest
          (pr-str {:benchmark/id :benchmark/canonical-package-lifecycle-dummy
                   :scenario-suites [(.getPath scenarios)]
                   :benchmark/claims []}))
    (.getPath manifest)))

(defn- evidence [root]
  (edn/read-string (slurp (io/file root "benchmark/evidence/evidence.edn"))))

(defn- semantic-result-roots [root]
  (mapv #(select-keys % [:execution/id :execution/ordinal :scenario/id :outcome :pass?
                         :scenario/evidence-root])
        (:results (evidence root))))

(defn- package-semantics [root]
  (let [bundle (evidence root)]
    {:benchmark-root (:evidence/hash bundle)
     :scenario-roots (semantic-result-roots root)
     :claim-roots (mapv #(select-keys % [:claim/id :claim/root :claim/evidence-root])
                        (:claim-results bundle))
     :artifact-manifests
     (mapv (fn [result]
             {:execution/id (:execution/id result)
              :artifacts (mapv #(select-keys % [:artifact/relative-path
                                                :artifact/sha256
                                                :artifact/byte-count
                                                :artifact/semantic-root])
                               (get-in result [:scenario/artifact-manifest :artifacts]))})
           (:results bundle))
     :closure (:benchmark/execution-closure bundle)}))

(defn- execution-artifact-bytes [root]
  ;; All files below the coordinator-published execution tree are canonical
  ;; scenario artifacts. Runtime adapters are projected out before raw replay
  ;; output is written, so this intentionally includes raw/replay-output.edn.
  (let [executions (io/file root "benchmark/executions")]
    (into (sorted-map)
          (for [file (file-seq executions)
                :let [relative (str (.relativize (.toPath executions) (.toPath file)))]
                :when (.isFile file)]
            [relative (vec (java.nio.file.Files/readAllBytes (.toPath file)))]))))

(deftest real-canonical-package-lifecycle-is-stable-across-serial-and-parallel-roots
  (let [fixture-root (temp-dir)
        serial-root (io/file fixture-root "serial-package")
        parallel-root (io/file fixture-root "parallel-package")
        one-chunk-root (io/file fixture-root "one-chunk-package")
        uneven-chunks-root (io/file fixture-root "uneven-chunks-package")
        failed-root (io/file fixture-root "failed-package")
        commit-failure-root (io/file fixture-root "commit-failure-package")
        copied-completion-root (io/file fixture-root "copied-completion-package")
        staged-worker-failure-root (io/file fixture-root "staged-worker-failure-package")
        manifest (write-dummy-fixture! fixture-root)
        fake-id "benchmark/test-canonical-package-lifecycle"]
    (do
      (with-redefs [benchmark-cli/resolve-benchmark-manifest
                    (fn [id]
                      (if (= fake-id id)
                        manifest
                        (throw (ex-info "unexpected benchmark ID" {:id id}))))]
        (testing "serial and parallel roots complete, verify, and preserve canonical execution output"
          (let [serial (command/run-with-root! fake-id (.getPath serial-root) nil :public
                                               {:execution/parallelism 1 :execution/chunk-size 1})
                parallel (command/run-with-root! fake-id (.getPath parallel-root) nil :public
                                                 {:execution/parallelism 2 :execution/chunk-size 1})
                one-chunk (command/run-with-root! fake-id (.getPath one-chunk-root) nil :public
                                                  {:execution/parallelism 2 :execution/chunk-size 3})
                uneven (command/run-with-root! fake-id (.getPath uneven-chunks-root) nil :public
                                               {:execution/parallelism 2 :execution/chunk-size 2})
                roots [serial-root parallel-root one-chunk-root uneven-chunks-root]]
            (is (every? zero? (map :exit-code [serial parallel one-chunk uneven])))
            (doseq [root roots]
              (is (.isFile (io/file root "completion.json")))
              (is (= "passed" (get (benchmark-verify/verify! (.getPath root)) "status")))
              (is (= 3 (count (semantic-result-roots root))))
              (is (true? (get-in (evidence root) [:benchmark/execution-closure :closed?]))))
            (doseq [root (rest roots)]
              (is (= (package-semantics serial-root)
                     (package-semantics root))
                  "Chunk decomposition is operational; package semantics are invariant")
              (is (= (execution-artifact-bytes serial-root)
                     (execution-artifact-bytes root))))))
        (testing "completion.json is bound to the exact semantic package it seals"
          (let [completion-file (io/file serial-root "completion.json")
                finalization-file (io/file serial-root "benchmark/finalization.json")
                evidence-file (io/file serial-root "benchmark/evidence/evidence.edn")
                artifact-file (first (filter #(.isFile %) (file-seq (io/file serial-root "benchmark/executions"))))
                completion-bytes (slurp completion-file)
                finalization-bytes (slurp finalization-file)
                evidence-bytes (slurp evidence-file)
                artifact-bytes (java.nio.file.Files/readAllBytes (.toPath artifact-file))]
            (.mkdirs copied-completion-root)
            (spit (io/file copied-completion-root "completion.json") completion-bytes)
            (is (= "failed" (get (benchmark-verify/verify! (.getPath copied-completion-root)) "status"))
                "A completion marker copied to an incomplete root cannot be accepted")
            (spit finalization-file "{\"tampered\":true}")
            (is (= "failed" (get (benchmark-verify/verify! (.getPath serial-root)) "status"))
                "Completion seals exact finalization bytes")
            (spit finalization-file finalization-bytes)
            (.delete artifact-file)
            (is (= "failed" (get (benchmark-verify/verify! (.getPath serial-root)) "status"))
                "Completion/package registry closure rejects a missing scenario artifact")
            (java.nio.file.Files/write (.toPath artifact-file) artifact-bytes
                                       (make-array java.nio.file.OpenOption 0))
            (let [changed (assoc-in (edn/read-string evidence-bytes)
                                    [:benchmark/execution-closure :closed?] false)]
              (spit evidence-file (pr-str changed)))
            (is (= "failed" (get (benchmark-verify/verify! (.getPath serial-root)) "status"))
                "Completion's closure commitment rejects altered closure evidence")
            (spit evidence-file evidence-bytes)))
        (testing "a post-publication phase failure cannot complete or verify"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"injected post-execution failure"
                                (command/run-with-root!
                                 fake-id (.getPath failed-root) nil :public
                                 {:execution/parallelism 2
                                  :execution/chunk-size 1
                                  :finalize-runner (fn [& _]
                                                     (throw (ex-info "injected post-execution failure" {})))})))
          (is (seq (filter #(.isDirectory %) (.listFiles (io/file failed-root "benchmark/executions")))))
          (is (not (.exists (io/file failed-root "completion.json"))))
          (is (= "failed" (get (benchmark-verify/verify! (.getPath failed-root)) "status")))
          (is (not (.exists (io/file failed-root ".run.lock")))))
        (testing "final artifacts without completion.json are not a committed package"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"injected terminal commit failure"
                                (command/run-with-root!
                                 fake-id (.getPath commit-failure-root) nil :public
                                 {:execution/parallelism 2
                                  :execution/chunk-size 1
                                  :complete (fn [& _]
                                              (throw (ex-info "injected terminal commit failure" {})))})))
          ;; Every phase before :complete has materialized its final files, but
          ;; completion.json is the sole terminal commit marker.
          (is (.isFile (io/file commit-failure-root "benchmark/finalization.json")))
          (is (.isFile (io/file commit-failure-root "manifest/run-package-index.json")))
          (is (not (.exists (io/file commit-failure-root "completion.json"))))
          (is (= "failed" (get (benchmark-verify/verify! (.getPath commit-failure-root)) "status")))
          (is (not (.exists (io/file commit-failure-root ".run.lock")))))
        (testing "a worker failure leaves diagnostic staging but no canonical execution artifacts"
          (let [real-execute @#'runner/execute-scenario
                calls (atom 0)]
            (with-redefs [runner/execute-scenario
                          (fn [& args]
                            (if (= 2 (swap! calls inc))
                              (throw (ex-info "injected staged worker failure" {:reason :test}))
                              (apply real-execute args)))]
              (is (thrown-with-msg? clojure.lang.ExceptionInfo #"finalization aborted"
                                    (command/run-with-root!
                                     fake-id (.getPath staged-worker-failure-root) nil :public
                                     {:execution/parallelism 1 :execution/chunk-size 1}))))
            (let [staging (io/file staged-worker-failure-root "benchmark/.staging/benchmark-executions")
                  canonical (io/file staged-worker-failure-root "benchmark/executions")]
              (is (seq (filter #(.isDirectory %) (or (.listFiles staging) (make-array java.io.File 0)))))
              (is (empty? (filter #(.isDirectory %) (or (.listFiles canonical) (make-array java.io.File 0)))))
              (is (not (.exists (io/file staged-worker-failure-root "completion.json"))))
              (is (= "failed"
                     (get (benchmark-verify/verify! (.getPath staged-worker-failure-root)) "status"))))))
        (delete-tree! fixture-root)))))
