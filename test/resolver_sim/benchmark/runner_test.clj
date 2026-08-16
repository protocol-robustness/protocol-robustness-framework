(ns resolver-sim.benchmark.runner-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.runner :as runner]
            [resolver-sim.benchmark.execution-identity :as execution-identity]
            [resolver-sim.io.input-source :as input-source]
            [resolver-sim.benchmark.adapter :as adapter]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.benchmark.repo :as repo]
            [resolver-sim.vcs :as vcs]
            [resolver-sim.benchmark.signing :as signing]
            [resolver-sim.benchmark.sharing :as sharing]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.protocols.sew.invariants :as sew-inv]
            [resolver-sim.protocols.registry :as protocols]
            [resolver-sim.scenario.suites :as suites]
            [resolver-sim.commands.run-benchmark :as command]
            [resolver-sim.benchmark.claims :as claims]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(declare temp-dir!)

(deftest scenario-output-packages-are-isolated-per-execution
  (let [root (doto (java.io.File/createTempFile "benchmark-artifacts-" "")
               (.delete)
               (.mkdirs))
        scenario-file (java.io.File. root "S03_dr3-dispute-refund.edn")
        _ (spit scenario-file "{:scenario-id \"S03\"}")
        source (input-source/source (.getPath scenario-file))
        descriptor (execution-identity/descriptor source {:scenario-id "S03" :protocol "sew-v1"} 0)
        run-1 (#'runner/execution-output-dir (.getPath root) 1 descriptor)
        run-2 (#'runner/execution-output-dir (.getPath root) 2 descriptor)]
    (try
      (testing "each execution receives a stable descriptor-derived directory"
        (is (not= run-1 run-2))
        (is (re-find #"exec-0001-[0-9a-f]{16}$" run-1))
        (is (re-find #"exec-0002-[0-9a-f]{16}$" run-2)))
      (testing "a package retains input, raw result, and execution summary"
        (let [package (#'runner/write-execution-package!
                       run-1 source {:protocol "sew-v1"}
                       {:outcome :pass :events-processed 3})]
          (is (.exists (java.io.File. (:scenario/input-path package))))
          (is (.exists (java.io.File. (:scenario/replay-output package))))
          (is (.exists (java.io.File. (:scenario/summary package))))))
      (finally
        (doseq [file (reverse (file-seq root))]
          (.delete file))))))

(deftest frozen-plan-inputs-are-immutable-worker-sources
  (let [root (temp-dir!)
        original (io/file root "scenario.edn")
        staging (io/file root "staging")]
    (try
      (spit original "{:scenario-id \"frozen\" :protocol \"sew-v1\"}")
      (let [source (input-source/source (.getPath original))
            benchmark {:benchmark/claims []}
            plan (runner/build-execution-plan benchmark [source])
            frozen (#'runner/freeze-plan-inputs! plan benchmark [source] (.getPath staging))
            worker-source (get (:source-by-id frozen) (:execution/id (first (:plan frozen))))]
        (is (= (:input/ref source) (:input/ref worker-source)))
        (is (not= (:input/path source) (:input/path worker-source)))
        (is (= (:input/content-hash (:execution/descriptor (first plan)))
               (:scenario/input-root (first (:plan frozen)))))
        (spit original "{:scenario-id \"mutated\" :protocol \"sew-v1\"}")
        (is (= "frozen" (:scenario-id (#'runner/load-scenario worker-source)))))
      (finally
        (doseq [file (reverse (file-seq root))] (.delete file))))))

(deftest execution-plan-chunking-is-deterministic-and-non-semantic
  (let [plan [{:execution/ordinal 1 :execution/id "sha256:one"}
              {:execution/ordinal 2 :execution/id "sha256:two"}
              {:execution/ordinal 3 :execution/id "sha256:three"}
              {:execution/ordinal 4 :execution/id "sha256:four"}]
        chunks (runner/execution-chunks plan 3)]
    (is (= [["sha256:one" "sha256:two" "sha256:three"]
            ["sha256:four"]]
           (mapv :chunk/work-item-ids chunks)))
    (is (= [[1 2 3] [4]] (mapv :chunk/work-item-ordinals chunks)))
    (is (= chunks (runner/execution-chunks plan 3)))
    (is (thrown? clojure.lang.ExceptionInfo (runner/execution-chunks plan 0)))))

(deftest execution-plan-reconciliation-restores-frozen-order
  (let [plan [{:execution/ordinal 1 :execution/id "sha256:one" :execution/directory "exec-0001-one"
               :execution/descriptor {:id 1}}
              {:execution/ordinal 2 :execution/id "sha256:two" :execution/directory "exec-0002-two"
               :execution/descriptor {:id 2}}]
        result (fn [id ordinal descriptor directory]
                 {:execution/id id :execution/ordinal ordinal :execution/descriptor descriptor
                  :scenario/artifacts {:scenario/artifact-dir directory}})
        reverse-completion [(result "sha256:two" 2 {:id 2} "exec-0002-two")
                            (result "sha256:one" 1 {:id 1} "exec-0001-one")]]
    (is (true? (#'runner/reconcile-execution-plan! plan reverse-completion)))
    (is (= ["sha256:one" "sha256:two"]
           (mapv :execution/id (#'runner/order-reconciled-results plan reverse-completion))))))

(deftest execution-plan-reconciliation-rejects-divergent-results
  (let [plan [{:execution/id "sha256:one" :execution/directory "exec-0001-one"}
              {:execution/id "sha256:two" :execution/directory "exec-0002-two"}]
        result (fn [id directory]
                 {:execution/id id
                  :scenario/artifacts {:scenario/artifact-dir directory}})
        reconcile #(#'runner/reconcile-execution-plan! plan %)]
    (testing "matching plan and results reconcile"
      (is (true? (reconcile [(result "sha256:one" "exec-0001-one")
                             (result "sha256:two" "exec-0002-two")]))))
    (testing "missing planned execution is rejected"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"reconciliation failed"
                            (reconcile [(result "sha256:one" "exec-0001-one")]))))
    (testing "extra execution is rejected"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"reconciliation failed"
                            (reconcile [(result "sha256:one" "exec-0001-one")
                                        (result "sha256:two" "exec-0002-two")
                                        (result "sha256:extra" "exec-0003-extra")]))))
    (testing "duplicate execution is rejected"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"reconciliation failed"
                            (reconcile [(result "sha256:one" "exec-0001-one")
                                        (result "sha256:one" "exec-0001-one")
                                        (result "sha256:two" "exec-0002-two")]))))
    (testing "misplaced execution is rejected"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"reconciliation failed"
                            (reconcile [(result "sha256:one" "wrong-directory")
                                        (result "sha256:two" "exec-0002-two")]))))))

(deftest test-hashing-determinism
  (testing "Identical data produces identical hashes"
    (let [data {:a 1 :b [1 2 3] :c {:d "foo"}}
          h1 (hc/hash-with-intent {:hash/intent :evidence-record} data)
          h2 (hc/hash-with-intent {:hash/intent :evidence-record} data)]
      (is (= h1 h2))))

  (testing "Map key order does not affect hash"
    (let [data1 {:a 1 :b 2}
          data2 {:b 2 :a 1}
          h1 (hc/hash-with-intent {:hash/intent :evidence-record} data1)
          h2 (hc/hash-with-intent {:hash/intent :evidence-record} data2)]
      (is (= h1 h2)))))

(deftest test-suite-resolution
  (testing "Pack suite keywords resolve to scenario paths"
    (is (= 65 (count (suites/suite-paths :suite/sew-dispute-safety-v1)))
        ":suite/sew-dispute-safety-v1 should resolve to 65 dispute-resolution scenarios")
    (is (= 15 (count (suites/suite-paths :suite/sew-yield-safety-v1)))
        ":suite/sew-yield-safety-v1 should resolve to 15 yield scenarios")
    (is (= 65 (count (suites/suite-paths :suite/prf-replay-v1)))
        ":suite/prf-replay-v1 should resolve to 65 core replay scenarios")
    (is (nil? (suites/suite-paths :suite/non-existent))
        "Unknown suite keyword should return nil")))

(deftest test-pack-manifest-loading
  (testing "Canonical pack manifests load and reference registered suites"
    (doseq [[path expected-suite expected-count]
            [["benchmarks/packs/sew/escrow-dispute-v1.edn"
              :suite/sew-dispute-safety-v1 65]
             ["benchmarks/packs/sew/dispute-liveness-v1.edn"
              :suite/sew-dispute-safety-v1 65]
             ["benchmarks/packs/sew/yield-shortfall-v1.edn"
              :suite/sew-yield-safety-v1 15]
             ["benchmarks/packs/sew/resolver-slashing-v1.edn"
              :suite/sew-dispute-safety-v1 65]
             ["benchmarks/packs/prf-core/deterministic-replay-v1.edn"
              :suite/prf-replay-v1 65]]]
      (let [manifest (edn/read-string (slurp path))
            suite-kw (:benchmark/scenario-suite manifest)
            paths (suites/suite-paths suite-kw)]
        (is (= expected-suite suite-kw)
            (str (:benchmark/id manifest) " references " expected-suite))
        (is (= expected-count (count paths))
            (str (:benchmark/id manifest) " resolves to " expected-count " scenarios"))))))

(deftest test-hash-stability
  (testing "Hashing is stable across different instances of same data"
    (let [data {:repo {:commit "abc"}}
          h1 (hc/hash-with-intent {:hash/intent :evidence-record} data)
          h2 (hc/hash-with-intent {:hash/intent :evidence-record} (into {} data))]
      (is (= h1 h2)))))

(deftest test-repo-metadata
  (testing "Can extract git metadata"
    (let [meta (repo/metadata)]
      (is (contains? meta :repo))
      (is (string? (get-in meta [:repo :commit])))
      (is (boolean? (get-in meta [:repo :dirty?]))))))

(defn- clean-source-provenance
  "Deterministic clean-VCS provenance so real-runner tests are hermetic —
   the workspace working copy is dirty during development."
  []
  {:git-commit-sha "sha256:test-commit"
   :source/hash "sha256:test-source-hash"
   :source/hash-algorithm "source-tree-hash-v1"
   :source/hash-roots []
   :code-hash "sha256:test-code-hash"
   :deps-hash "sha256:test-deps-hash"
   :input-hash "sha256:test-input-hash"
   :dirty? false})

(deftest test-benchmark-run-basic
  (testing "Can run a benchmark (old format) and generate evidence"
    (with-redefs [repo/metadata (fn [] {:repo {:commit "test-commit" :dirty? false}})
                  vcs/source-provenance clean-source-provenance]
      (let [manifest-path "benchmarks/packs/sew/dispute-liveness-v1.edn"
            evidence (runner/run-benchmark manifest-path)]
        (is (contains? evidence :benchmark))
        (is (contains? evidence :repo))
        (is (contains? evidence :evidence/hash))
        (is (vector? (:results evidence)))))))

(deftest test-benchmark-run-new-format
  (testing "Can run a benchmark (new pack format) and generate evidence"
    (with-redefs [repo/metadata (fn [] {:repo {:commit "test-commit" :dirty? false}})
                  vcs/source-provenance clean-source-provenance]
      (let [manifest-path "benchmarks/packs/sew/escrow-dispute-v1.edn"
            evidence (runner/run-benchmark manifest-path)]
        (is (contains? evidence :benchmark) "Evidence should contain :benchmark")
        (is (contains? evidence :repo) "Evidence should contain :repo")
        (is (contains? evidence :evidence/hash) "Evidence should contain :evidence/hash")
        (is (contains? evidence :benchmark-certification) "Evidence should contain :benchmark-certification")
        (is (vector? (:results evidence)) "Results should be a vector")
        (is (contains? evidence :metrics) "Evidence should contain :metrics")
        ;; Verify the evidence shape matches BENCHMARK_RESULT_SPEC_V1
        (is (string? (:evidence/hash evidence)) "Hash should be a string")
        (is (pos? (count (:evidence/hash evidence))) "Hash should be non-empty")
        ;; :repo should contain git metadata
        (is (contains? (:repo evidence) :repo) ":repo should contain nested :repo metadata")))))

(deftest test-suite-resolution-in-adapter
  (testing "SewAdapter resolves :benchmark/scenario-suite keyword"
    (let [manifest (edn/read-string (slurp "benchmarks/packs/sew/escrow-dispute-v1.edn"))
          adapter runner/default-adapter
          scenarios (adapter/load-scenarios adapter manifest)]
      (is (= 65 (count scenarios))
          "Adapter should resolve :suite/sew-dispute-safety-v1 to 65 scenarios")
      (is (every? #(= :file (:input/type %)) scenarios)
          "All scenarios should be file-backed input sources")))

  (testing "SewAdapter falls back to :scenario-suites (old format)"
    (let [old-manifest {:scenario-suites ["scenarios"]}
          adapter runner/default-adapter
          scenarios (adapter/load-scenarios adapter old-manifest)]
      (is (pos? (count scenarios))
          "Old format should still resolve scenarios via directory walking"))))

(deftest test-evidence-shape
  (testing "Evidence bundle matches BENCHMARK_RESULT_SPEC_V1 shape"
    (with-redefs [repo/metadata (fn [] {:repo {:commit "test-commit" :dirty? false}})
                  vcs/source-provenance clean-source-provenance]
      (let [evidence (runner/run-benchmark "benchmarks/packs/sew/escrow-dispute-v1.edn")]
      ;; Core shape
        (is (contains? evidence :benchmark) ":benchmark key present")
        (is (contains? evidence :repo) ":repo key present")
        (is (contains? evidence :environment) ":environment key present")
        (is (contains? evidence :results) ":results key present")
        (is (contains? evidence :metrics) ":metrics key present")
        (is (contains? evidence :evidence/hash) ":evidence/hash key present")
        (is (contains? evidence :benchmark-certification) ":benchmark-certification key present")

      ;; Environment shape
        (is (contains? (:environment evidence) :os-name) ":environment :os-name")
        (is (contains? (:environment evidence) :java-version) ":environment :java-version")

      ;; Metrics shape
        (is (contains? (:metrics evidence) :total) ":metrics :total")
        (is (contains? (:metrics evidence) :passed) ":metrics :passed")

      ;; Benchmark shape
        (is (map? (:benchmark evidence)) ":benchmark is a map")

      ;; Results vector
        (is (every? #(contains? % :file) (:results evidence))
            "Each result should have :file")
        (is (every? #(contains? % :outcome) (:results evidence))
            "Each result should have :outcome")))))

(deftest test-deterministic-replay-benchmark-produces-claim-results
  (testing "PRF deterministic replay benchmark executes duplicate runs and resolves replay claims"
    (with-redefs [repo/metadata (fn [] {:repo {:commit "test-commit"
                                               :dirty? false}})
                  sew/replay-with-sew-protocol (fn [_scenario _opts]
                                                 {:events-processed 3
                                                  :outcome :pass
                                                  :halt-reason nil
                                                  :metrics {:invariant-results {}}
                                                  :world {:status :ok}})
                  sew-inv/check-all (fn [_world] {:results {}})]
      (let [evidence (runner/run-benchmark "benchmarks/packs/prf-core/deterministic-replay-v1.edn")
            claim-results (:claim-results evidence)
            claim-outcomes (into {} (map (juxt :claim/id :claim/outcome)) claim-results)]
        (is (= 130 (count (:results evidence)))
            "Deterministic replay benchmark should execute 65 scenarios twice")
        (is (= #{0 1} (into #{} (map :benchmark/run-index) (:results evidence)))
            "run indices are 0-based repetitions (range run-count)")
        (is (= #{2} (into #{} (map :benchmark/run-count) (:results evidence))))
        (is (= 130 (get-in evidence [:metrics :execution-count])))
        (is (= 65 (get-in evidence [:metrics :unique-scenario-count])))
        (is (= 2 (get-in evidence [:metrics :declared-run-count])))
        (is (= 130 (get-in evidence [:run/manifest :execution-count])))
        (is (= 65 (get-in evidence [:run/manifest :unique-scenario-count])))
        (is (= 2 (get-in evidence [:run/manifest :declared-run-count])))
        (is (= :pass (get claim-outcomes :claim/replay-identical-results)))
        (is (= :pass (get claim-outcomes :claim/hash-consistency-across-runs)))
        (is (= :pass (get claim-outcomes :claim/no-nondeterminism)))
        (is (not-any? #(= :inconclusive (:claim/outcome %)) claim-results)
            "Replay claims should now resolve to concrete outcomes")))))

(deftest test-malformed-manifest
  (testing "Throws on missing manifest"
    (is (thrown? Exception (runner/load-manifest "non-existent.edn")))))

(deftest claim-registry-input-returns-nil-when-file-unreadable
  (testing "sha256 is nil, not a magic string, when the registry cannot be read"
    (let [ctx {:claim-registry/path "/nonexistent/registry.edn"}]
      (is (nil? (get-in (command/claim-registry-input ctx) ["sha256"]))))))

(deftest test-hash-stability
  (testing "Hashing is stable across different instances of same data"
    (let [data {:repo {:commit "abc"}}
          h1 (hc/hash-with-intent {:hash/intent :evidence-record} data)
          h2 (hc/hash-with-intent {:hash/intent :evidence-record} (into {} data))]
      (is (= h1 h2)))))

(deftest test-share-summary
  (testing "Share summary generation"
    (let [evidence {:benchmark {:benchmark/id "test-bm"}
                    :repo {:repo {:commit "def"}}
                    :metrics {:passed 5 :total 5}
                    :evidence/hash "hash123"}
          summary (sharing/share-summary evidence)]
      (is (str/includes? summary "test-bm"))
      (is (str/includes? summary "def"))
      (is (str/includes? summary "PASS")))))

(deftest test-share-summary-rejects-scenario-only-active-readiness
  (let [evidence {:benchmark {:benchmark/id :benchmark/test
                              :benchmark/status :active
                              :benchmark/claims [{:claim/id :claim/test}]}
                  :metrics {:passed 1 :total 1}
                  :claim-results [{:claim/id :claim/test
                                   :claim/outcome :not-exercised}]}
        summary (sharing/share-summary evidence)]
    (is (str/includes? summary "REQUIRED CLAIMS INCOMPLETE"))))

(deftest test-attestation
  (testing "Attestation structure"
    (let [evidence {:benchmark {:benchmark/id "test-bm" :commit "abc"}
                    :repo {:repo {:commit "def"}}
                    :evidence/hash "hash123"}
          ;; Mock slurp and signing/sign-hash
          _ (with-redefs [clojure.core/slurp (fn [_] (pr-str evidence))
                          signing/sign-hash (fn [_ _ _] "sig123")]
              (let [att (sharing/attest "evidence.edn" "my-key" "pass")]
                (is (= "test-bm" (:benchmark/id att)))
                (is (= "hash123" (:evidence/hash att)))
                (is (= "sig123" (:signature att)))))])))

;; ── Detached artifact manifests / publication hardening ──────────────────────

(defn- temp-dir!
  "Create a fresh, empty temp directory for hermetic filesystem tests."
  []
  (doto (java.io.File/createTempFile "runner-artifact-" "")
    (.delete)
    (.mkdirs)))

(defn- plan-entry-for
  "A minimal frozen plan entry and staged directory name for publication tests."
  [id dir]
  {:execution/id id :execution/ordinal 1 :execution/directory dir
   :execution/descriptor {:id id}})

(deftest artifact-manifest-collision-semantics
  (testing "identical logical identity + bytes → deterministic idempotent dedupe"
    (let [m1 {:execution/id "e1"
              :artifacts [{:artifact/relative-path "raw/replay-output.edn"
                           :artifact/sha256 "sha-x" :artifact/byte-count 3}]}
          deduped (#'runner/reconcile-artifact-manifests! [m1 m1])]
      (is (= 2 (count deduped)) "two manifests survive")
      (is (= 1 (count (:artifacts (first deduped)))) "first keeps its artifact")
      (is (= 0 (count (:artifacts (second deduped)))) "duplicate artifact deduped")))
  (testing "same logical identity + differing bytes → fail closed"
    (let [m1 {:execution/id "e1"
              :artifacts [{:artifact/relative-path "raw/replay-output.edn"
                           :artifact/sha256 "sha-x" :artifact/byte-count 3}]}
          m2 (assoc-in m1 [:artifacts 0 :artifact/sha256] "sha-different")]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"collision"
                            (#'runner/reconcile-artifact-manifests! [m1 m2]))))))

(deftest publication-rehashes-canonical-destinations
  (let [root (temp-dir!)
        canonical (io/file root "canonical")
        staging (io/file root "staging")
        plan [(plan-entry-for "e1" "exec-0001-abc")]
        staged-dir (io/file staging "exec-0001-abc")
        replay-file (io/file staged-dir "raw" "replay-output.edn")]
    (try
      (.mkdirs (io/file canonical))
      (.mkdirs (.getParentFile replay-file))
      (spit replay-file "canonical payload")
      (let [worker-manifest (#'runner/artifact-manifest-for-dir (.getPath staged-dir) nil)
            result {:execution/id "e1" :execution/ordinal 1
                    :scenario/artifact-manifest worker-manifest
                    :scenario/artifacts {}}
            published (first (#'runner/publish-staged-executions!
                              (.getPath canonical) (.getPath staging) plan [result]))]
        (testing "staged directory is moved into the canonical execution location"
          (is (.exists (io/file canonical "exec-0001-abc" "raw" "replay-output.edn")))
          (is (false? (.exists staged-dir))))
        (testing "canonical destinations are re-hashed after the coordinator move"
          (is (true? (get-in published [:scenario/artifact-manifest :artifact/canonical-verified])))
          (is (= (#'runner/sha256-file (io/file canonical "exec-0001-abc" "raw" "replay-output.edn"))
                 (get-in published [:scenario/artifact-manifest :artifacts 0 :artifact/sha256])))))
      (finally
        (doseq [file (reverse (file-seq root))] (.delete file))))))

(deftest publication-move-failure-is-not-completed
  (let [root (temp-dir!)
        canonical (io/file root "canonical")
        staging (io/file root "staging")
        plan [(plan-entry-for "e1" "exec-0001-abc")]
        staged-dir (io/file staging "exec-0001-abc")
        replay-file (io/file staged-dir "raw" "replay-output.edn")]
    (try
      (.mkdirs (io/file canonical))
      (.mkdirs (.getParentFile replay-file))
      (spit replay-file "payload")
      (let [worker-manifest (#'runner/artifact-manifest-for-dir (.getPath staged-dir) nil)
            result {:execution/id "e1" :execution/ordinal 1
                    :scenario/artifact-manifest worker-manifest
                    :scenario/artifacts {}}]
        (with-redefs [runner/move-staged-to-canonical!
                      (fn [& _] (throw (ex-info "injected move failure" {:reason :test})))]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"injected"
                                (#'runner/publish-staged-executions!
                                 (.getPath canonical) (.getPath staging) plan [result]))))
        (testing "no canonical execution directory makes the run look completed"
          (is (not-any? #(.isDirectory %) (or (.listFiles canonical) (make-array java.io.File 0)))))
        (testing "staged output is not canonically published on failure"
          (is (nil? (get-in result [:scenario/artifacts :scenario/artifact-dir])))))
      (finally
        (doseq [file (reverse (file-seq root))] (.delete file))))))

(deftest staged-divergence-fails-before-publication
  (let [root (temp-dir!)
        canonical (io/file root "canonical")
        staging (io/file root "staging")
        plan [(plan-entry-for "e1" "exec-0001-abc")]
        staged-dir (io/file staging "exec-0001-abc")
        replay-file (io/file staged-dir "raw" "replay-output.edn")]
    (try
      (.mkdirs (io/file canonical))
      (.mkdirs (.getParentFile replay-file))
      (spit replay-file "payload")
      ;; worker manifest claims different bytes than what is actually staged
      (let [result {:execution/id "e1" :execution/ordinal 1
                    :scenario/artifact-manifest
                    {:artifact/manifest-version "benchmark-artifact-manifest.v1"
                     :artifacts [{:artifact/relative-path "raw/replay-output.edn"
                                  :artifact/sha256 "deadbeef" :artifact/byte-count 1}]}
                    :scenario/artifacts {}}]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"diverged"
                              (#'runner/publish-staged-executions!
                               (.getPath canonical) (.getPath staging) plan [result])))
        (testing "nothing was moved to canonical before the failure"
          (is (not-any? #(.isDirectory %) (or (.listFiles canonical) (make-array java.io.File 0))))))
      (finally
        (doseq [file (reverse (file-seq root))] (.delete file))))))

;; ── Controlled executor equivalence ──────────────────────────────────────────

(defn- make-hermetic-plan!
  "Build a frozen execution plan plus its source-by-id map from n temp scenarios."
  [n]
  (let [root (temp-dir!)
        files (mapv (fn [i]
                      (let [f (java.io.File. root (str "S" i ".edn"))]
                        (spit f (pr-str {:scenario-id (str "S" i) :protocol "sew-v1"}))
                        f))
                    (range n))
        sources (mapv #(input-source/source (.getPath %)) files)
        benchmark {:benchmark/claims []}
        plan (runner/build-execution-plan benchmark sources)
        source-by-id (into {} (map (fn [e s] [(:execution/id e) s]) plan sources))]
    {:root root :plan plan :source-by-id source-by-id}))

(deftest executor-parallelism-and-chunking-yield-identical-canonical-results
  (let [{:keys [root plan source-by-id]} (make-hermetic-plan! 6)
        fake-exec (fn [_suite _source entry _run-count _staging]
                    {:execution/id (:execution/id entry)
                     :execution/ordinal (:execution/ordinal entry)
                     :execution/descriptor (:execution/descriptor entry)
                     :value (:execution/ordinal entry)})
        run! (fn [parallelism chunk-size]
               (with-redefs [runner/execute-scenario fake-exec]
                 (let [raw (#'runner/execute-plan-bounded!
                            nil plan source-by-id 1 nil parallelism chunk-size)]
                   (is (true? (#'runner/reconcile-execution-plan! plan raw)))
                   (#'runner/order-reconciled-results plan raw))))]
    (try
      (let [serial-one (run! 1 1)
            one-chunk (run! 1 6)
            uneven (run! 1 2)
            parallel-2 (run! 2 1)
            parallel-2-uneven (run! 2 2)
            parallel-3 (run! 3 1)]
        (testing "canonical plan order is preserved in every configuration"
          (is (= [1 2 3 4 5 6] (mapv :execution/ordinal serial-one))))
        (testing "serial, chunked, and bounded-parallel runs are byte-equivalent"
          (is (= serial-one one-chunk))
          (is (= serial-one uneven))
          (is (= serial-one parallel-2))
          (is (= serial-one parallel-2-uneven))
          (is (= serial-one parallel-3))))
      (finally
        (doseq [file (reverse (file-seq root))] (.delete file))))))

(deftest executor-completion-order-never-becomes-canonical-order
  (let [{:keys [root plan source-by-id]} (make-hermetic-plan! 4)
        started (into {} (map (fn [ordinal] [ordinal (promise)]) (range 1 5)))
        release (into {} (map (fn [ordinal] [ordinal (promise)]) (range 1 5)))
        finished (into {} (map (fn [ordinal] [ordinal (promise)]) (range 1 5)))
        completed (atom [])
        fake-exec (fn [_suite _source entry _run-count _staging]
                    (let [ordinal (:execution/ordinal entry)]
                      (deliver (get started ordinal) true)
                      @(get release ordinal)
                      (swap! completed conj ordinal)
                      (deliver (get finished ordinal) true)
                      {:execution/id (:execution/id entry)
                       :execution/ordinal ordinal
                       :execution/descriptor (:execution/descriptor entry)}))]
    (try
      (with-redefs [runner/execute-scenario fake-exec]
        (let [result (future (#'runner/execute-plan-bounded! nil plan source-by-id 1 nil 4 1))]
          (doseq [ordinal (range 1 5)]
            (is (true? (deref (get started ordinal) 2000 false))))
          ;; Deliberately release in a non-canonical schedule.
          (doseq [ordinal [3 1 4 2]]
            (deliver (get release ordinal) true)
            (is (true? (deref (get finished ordinal) 2000 false))))
          (let [raw @result
                canonical (#'runner/order-reconciled-results plan raw)]
            (is (= [3 1 4 2] @completed))
            (is (= [1 2 3 4] (mapv :execution/ordinal canonical))))))
      (finally
        (doseq [file (reverse (file-seq root))] (.delete file))))))

(deftest executor-bounded-parallelism-with-real-overlap
  (testing "per-execution scheduling overlaps >1 and never exceeds the pool bound"
    (let [{:keys [root plan source-by-id]} (make-hermetic-plan! 8)
          current (atom 0)
          max-in-flight (atom 0)
          gate (promise)
          fake-exec (fn [_suite _source entry _run-count _staging]
                      (let [n (swap! current inc)]
                        (swap! max-in-flight max n)
                        (deref gate 5000 nil)
                        (swap! current dec)
                        {:execution/id (:execution/id entry)
                         :execution/ordinal (:execution/ordinal entry)
                         :execution/descriptor (:execution/descriptor entry)}))]
      (try
        (with-redefs [runner/execute-scenario fake-exec]
          (let [result (future (#'runner/execute-plan-bounded! nil plan source-by-id 1 nil 4 1))]
            (Thread/sleep 300)
            (deliver gate true)
            @result))
        (is (>= @max-in-flight 2) "more than one execution overlapped (real parallelism)")
        (is (<= @max-in-flight 4) "in-flight executions respected the pool bound")
        (finally
          (deliver gate true)
          (doseq [file (reverse (file-seq root))] (.delete file)))))))

(deftest executor-serial-mode-never-exceeds-one-in-flight
  (testing "parallelism 1 never runs more than one execution concurrently"
    (let [{:keys [root plan source-by-id]} (make-hermetic-plan! 8)
          current (atom 0)
          max-in-flight (atom 0)
          fake-exec (fn [_suite _source entry _run-count _staging]
                      (let [n (swap! current inc)]
                        (swap! max-in-flight max n)
                        (Thread/sleep 40)
                        (swap! current dec)
                        {:execution/id (:execution/id entry)
                         :execution/ordinal (:execution/ordinal entry)
                         :execution/descriptor (:execution/descriptor entry)}))]
      (try
        (with-redefs [runner/execute-scenario fake-exec]
          (#'runner/execute-plan-bounded! nil plan source-by-id 1 nil 1 1))
        (is (= 1 @max-in-flight) "serial mode kept in-flight bounded to exactly one")
        (finally
          (doseq [file (reverse (file-seq root))] (.delete file)))))))

(deftest executor-worker-failure-cancels-outstanding-and-quiesces
  (testing "first worker failure cancels outstanding work and quiesces authoritatively"
    (let [{:keys [root plan source-by-id]} (make-hermetic-plan! 6)
          started (atom 0)
          gate (promise)
          fake-exec (fn [_suite _source entry _run-count _staging]
                      (let [ordinal (:execution/ordinal entry)]
                        (swap! started inc)
                        (when (= ordinal 1)
                          (throw (ex-info "boom" {:ordinal ordinal})))
                        (deref gate 10000 nil)
                        {:execution/id (:execution/id entry)
                         :execution/ordinal ordinal
                         :execution/descriptor (:execution/descriptor entry)}))]
      (try
        (with-redefs [runner/execute-scenario fake-exec]
          (let [thrown (try
                         (#'runner/execute-plan-bounded! nil plan source-by-id 1 nil 4 1)
                         nil
                         (catch Exception e e))]
            (is (some? thrown) "a worker failure must propagate")
            (is (= :benchmark-execution-failed (:reason (ex-data thrown))))
            (is (contains? (ex-data thrown) :quiescence)
                "failure must carry an authoritative quiescence result")
            (is (< @started 6) "outstanding work was cancelled, not all executions ran")))
        (finally
          (deliver gate true)
          (doseq [file (reverse (file-seq root))] (.delete file)))))))

(defn- make-lane-plan!
  "Build a frozen plan + source-by-id + protocol-by-id from a list of protocols."
  [protocols]
  (let [root (temp-dir!)
        files (mapv (fn [[i protocol]]
                      (let [f (java.io.File. root (str "S" i ".edn"))]
                        (spit f (pr-str {:scenario-id (str "S" i) :protocol protocol}))
                        f))
                    (map-indexed vector protocols))
        sources (mapv #(input-source/source (.getPath %)) files)
        benchmark {:benchmark/claims []}
        plan (runner/build-execution-plan benchmark sources)
        source-by-id (into {} (map (fn [e s] [(:execution/id e) s]) plan sources))
        protocol-by-id (zipmap (map :execution/id plan) protocols)]
    {:root root :plan plan :source-by-id source-by-id :protocol-by-id protocol-by-id}))

(deftest lane-safe-executions-overlap-despite-exclusive-present
  (testing "safe executions still overlap when an exclusive lane is present"
    (let [{:keys [root plan source-by-id protocol-by-id]}
          (make-lane-plan! (vec (concat (repeat 4 "sew-v1") (repeat 2 "excl-algo-v1"))))
          safe-current (atom 0) safe-max (atom 0)
          excl-current (atom 0) excl-max (atom 0)
          gate (promise)
          fake-exec (fn [_suite _source entry _run-count _staging]
                      (let [protocol (get protocol-by-id (:execution/id entry))]
                        (if (= "excl-algo-v1" protocol)
                          (let [n (swap! excl-current inc)]
                            (swap! excl-max max n)
                            (deref gate 10000 nil)
                            (swap! excl-current dec))
                          (let [n (swap! safe-current inc)]
                            (swap! safe-max max n)
                            (deref gate 10000 nil)
                            (swap! safe-current dec)))
                        {:execution/id (:execution/id entry)
                         :execution/ordinal (:execution/ordinal entry)
                         :execution/descriptor (:execution/descriptor entry)}))]
      (try
        (with-redefs [runner/execute-scenario fake-exec]
          (let [result (future (#'runner/execute-plan-bounded!
                                nil plan source-by-id 1 nil 4 1 nil nil #{"excl-algo-v1"}))]
            (Thread/sleep 400)
            (deliver gate true)
            @result))
        (is (>= @safe-max 2) "safe executions overlapped even with exclusive work present")
        (is (= 1 @excl-max) "exclusive lane serialized its own executions")
        (finally
          (deliver gate true)
          (doseq [file (reverse (file-seq root))] (.delete file)))))))

(deftest lane-incompatible-exclusive-protocols-never-overlap
  (testing "different exclusive-protocol lanes never run concurrently, while safe work still overlaps"
    (let [{:keys [root plan source-by-id protocol-by-id]}
          (make-lane-plan! (vec (concat (repeat 4 "sew-v1") (repeat 2 "excl-a-v1") (repeat 2 "excl-b-v1"))))
          safe-current (atom 0) safe-max (atom 0)
          excl-current (atom 0) excl-max (atom 0)
          gate (promise)
          fake-exec (fn [_suite _source entry _run-count _staging]
                      (let [protocol (get protocol-by-id (:execution/id entry))]
                        (if (#{"excl-a-v1" "excl-b-v1"} protocol)
                          (let [n (swap! excl-current inc)]
                            (swap! excl-max max n)
                            (deref gate 10000 nil)
                            (swap! excl-current dec))
                          (let [n (swap! safe-current inc)]
                            (swap! safe-max max n)
                            (deref gate 10000 nil)
                            (swap! safe-current dec)))
                        {:execution/id (:execution/id entry)
                         :execution/ordinal (:execution/ordinal entry)
                         :execution/descriptor (:execution/descriptor entry)}))]
      (try
        (with-redefs [runner/execute-scenario fake-exec]
          (let [result (future (#'runner/execute-plan-bounded!
                                nil plan source-by-id 1 nil 4 1 nil nil #{"excl-a-v1" "excl-b-v1"}))]
            (Thread/sleep 400)
            (deliver gate true)
            @result))
        (is (>= @safe-max 2) "safe executions still overlapped alongside exclusive lanes")
        (is (= 1 @excl-max) "incompatible exclusive executions never ran concurrently")
        (finally
          (deliver gate true)
          (doseq [file (reverse (file-seq root))] (.delete file)))))))

(deftest canonical-workers-never-fall-back-to-the-mutable-protocol-registry
  (testing "the frozen adapter map is authoritative for coordinator tasks"
    (let [adapter ::frozen-adapter
          calls (atom 0)]
      (with-redefs [protocols/get-protocol (fn [_] (swap! calls inc) ::registry-adapter)]
        (is (= adapter
               (with-bindings {#'runner/*canonical-worker?* true
                               #'runner/*frozen-protocol-adapters* {"dummy" adapter}}
                 (#'runner/resolve-worker-adapter! "dummy"))))
        (is (zero? @calls))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"missing-frozen-protocol-adapter|unavailable"
                              (with-bindings {#'runner/*canonical-worker?* true
                                              #'runner/*frozen-protocol-adapters* {}}
                                (#'runner/resolve-worker-adapter! "dummy"))))
        (is (zero? @calls))))))

;; ── Claim evaluation hardening ───────────────────────────────────────────────

(deftest claim-evaluation-failure-is-structured-fail-closed
  (testing "coordinator-owned claim evaluation surfaces a structured failure"
    (with-redefs [claims/evaluate-manifest-claims
                  (fn [& _] (throw (ex-info "claim evaluator exploded" {:reason :test})))]
      (let [result (#'runner/evaluate-claims-coordinator-owned {} [])]
        (is (= 1 (count result)))
        (is (= :failed (get-in result [0 :claim/evaluation-status])))
        (is (= :error (get-in result [0 :claim/outcome])))
        (is (some? (get-in result [0 :claim/evaluation-error])))))))

(deftest claim-evaluation-success-passthrough
  (testing "coordinator-owned claim evaluation forwards healthy results unchanged"
    (with-redefs [claims/evaluate-manifest-claims
                  (fn [_ _] [{:claim/id :claim/test :claim/outcome :pass}])]
      (is (= [{:claim/id :claim/test :claim/outcome :pass}]
             (#'runner/evaluate-claims-coordinator-owned {} []))))))
