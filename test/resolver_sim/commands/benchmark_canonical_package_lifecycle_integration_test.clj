(ns resolver-sim.commands.benchmark-canonical-package-lifecycle-integration-test
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.cli :as benchmark-cli]
            [resolver-sim.benchmark.verify :as benchmark-verify]
            [resolver-sim.benchmark.runner :as runner]
            [resolver-sim.benchmark.research-pack :as research-pack]
            [resolver-sim.commands.run-benchmark :as command]
            [resolver-sim.economics.payoffs :as payoffs]
            [resolver-sim.evidence.node :as evidence-node])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

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
  ;; Canonical package artifact bytes: all files below the coordinator-published
  ;; execution tree, EXCEPT the raw evidence-node envelope. Evidence nodes embed
  ;; a wall-clock :timestamp and derived :record-hash that are audit-only; they
  ;; must not make identical semantic runs produce different package identity.
  ;; They are therefore compared by their deterministic canonical projection
  ;; (evidence-node/canonical-node-projection), exactly as artifact-manifest-for-dir
  ;; commits them. This intentionally includes raw/replay-output.edn.
  (let [executions (io/file root "benchmark/executions")]
    (into (sorted-map)
          (for [file (file-seq executions)
                :let [relative (str (.relativize (.toPath executions) (.toPath file)))]
                :when (.isFile file)]
            [relative (vec (if (clojure.string/includes? relative "evidence-nodes/")
                             (evidence-node/canonical-node-projection
                              (edn/read-string (slurp file)))
                             (java.nio.file.Files/readAllBytes (.toPath file))))]))))

(defn- completion-bindings [root]
  (select-keys (json/read-str (slurp (io/file root "completion.json")))
               ["semantic_status" "bundle_root_hash" "artifact_set_root"
                "closure_commitment" "final_ref" "input_set_root"]))

(defn- redistribution-passes [root]
  (mapv #(get-in % [:partial-fill-decisions 0 :evidence :allocation-passes])
        (:results (evidence root))))

(defn- nested-yield-scenario [id]
  (let [owners (mapv #(format "0xOwner%02d" %) (range 18))
        deposits (mapv (fn [seq owner]
                         {:seq seq :time 1000000 :agent owner :action "yield_deposit"
                          :params {:amount 100 :token "USDC" :owner-id owner}})
                       (range 18) owners)
        caps (into {} (map (fn [owner] [owner 20]) (take 6 owners)))]
    {:scenario-id id
     :id id
     :schema-version "1.1"
     :title "Nested claimant concurrency"
     :purpose "integration-test"
     :threat-tags ["shortfall" "pro-rata" "caps" "redistribution"]
     :scenario-author "agent-c"
     :protocol "yield-v1"
     :initial-block-time 1000000
     :protocol-params {:yield-profile "aave-v3" :token "USDC"
                       :focus-owner-id (first owners)}
     :agents (conj (mapv (fn [owner] {:id owner :address owner :role "provider"}) owners)
                   {:id "governance" :address "governance" :role "governance"})
     :events (conj deposits
                   {:seq 18 :time 1100000 :agent "governance" :action "set-yield-risk"
                    :params {:token "USDC" :shortfall {:available-ratio 0.6
                                                       :reason "nested-concurrency-test"}}}
                   {:seq 19 :time 1200000 :agent "governance" :action "yield_withdraw_shared"
                    :params {:token "USDC" :module-id "aave-v3" :owner-ids owners
                             :allocation-mode "pro-rata" :effective-caps caps}})}))

(deftest nested-yield-claimant-concurrency-preserves-canonical-package
  (let [fixture-root (temp-dir)
        scenarios (doto (io/file fixture-root "scenarios") .mkdirs)
        manifest (io/file fixture-root "nested-yield-benchmark.edn")
        serial-root (io/file fixture-root "serial-package")
        candidate-root (io/file fixture-root "candidate-package")
        benchmark-id "benchmark/test-nested-yield-claimant-concurrency"
        outer-ready (CountDownLatch. 2)
        inner-ready (CountDownLatch. 2)
        await-both! (fn [latch layer]
                      (when-not (.await ^CountDownLatch latch 10 TimeUnit/SECONDS)
                        (throw (ex-info "Timed out waiting for nested concurrency layer"
                                        {:layer layer}))))]
    (doseq [id ["nested-yield-alpha" "nested-yield-bravo"]]
      (spit (io/file scenarios (str id ".edn")) (pr-str (nested-yield-scenario id))))
    (spit manifest
          (pr-str {:benchmark/id :benchmark/nested-yield-claimant-concurrency
                   :scenario-suites [(.getPath scenarios)]
                   :benchmark/claims []}))
    (try
      (with-redefs [benchmark-cli/resolve-benchmark-manifest
                    (fn [id]
                      (if (= benchmark-id id)
                        (.getPath manifest)
                        (throw (ex-info "unexpected benchmark ID" {:id id}))))]
        (let [serial (command/run-with-root! benchmark-id (.getPath serial-root) nil :public
                                             {:execution/parallelism 1
                                              :execution/chunk-size 1
                                              :execution/claimant-parallelism 1})
              candidate
              (with-redefs [runner/*outer-scenario-worker-hook*
                            (fn [_]
                              (.countDown outer-ready)
                              (await-both! outer-ready :outer-scenario-workers))
                            payoffs/*redistribution-claimant-hook*
                            (fn [_]
                              ;; This hook runs inside detached claimant fact
                              ;; determination, not at scenario/round entry.
                              ;; Two claimant tasks must be active before either
                              ;; can continue into the serial round reduction.
                              (.countDown inner-ready)
                              (await-both! inner-ready :redistribution-claimants))]
                (command/run-with-root! benchmark-id (.getPath candidate-root) nil :public
                                        {:execution/parallelism 2
                                         :execution/chunk-size 1
                                         :execution/claimant-parallelism 2
                                         :execution/claimant-parallel-threshold 16}))]
          (testing "the 1x1 reference and 2x2 candidate both complete and verify"
            (is (zero? (:exit-code serial)))
            (is (zero? (:exit-code candidate)))
            (is (= "passed" (get (benchmark-verify/verify! (.getPath serial-root)) "status")))
            (is (= "passed" (get (benchmark-verify/verify! (.getPath candidate-root)) "status"))))
          (testing "runtime latches prove simultaneous outer workers and inner active-set claimant determination"
            (is (zero? (.getCount outer-ready)) "both scenario workers reached the outer latch")
            (is (zero? (.getCount inner-ready)) "both 18-claimant redistribution passes reached the inner latch"))
          (testing "nested parallelism does not change redistribution or canonical package output"
            (is (= [[18 12] [18 12]]
                   (mapv #(mapv (comp count :active-ids) %) (redistribution-passes serial-root))))
            (is (= (redistribution-passes serial-root)
                   (redistribution-passes candidate-root)))
            (is (= (package-semantics serial-root)
                   (package-semantics candidate-root)))
            (is (= (execution-artifact-bytes serial-root)
                   (execution-artifact-bytes candidate-root)))
            (is (= (completion-bindings serial-root)
                   (completion-bindings candidate-root))))))
      (finally
        (try
          (let [keep "/tmp/preserved-roots"
                _ (.mkdirs (io/file keep))
                p (doto (ProcessBuilder.
                         ["/bin/sh" "-c" (str "cp -r '" (.getPath serial-root) "' '" (str keep "/serial") "'; cp -r '" (.getPath candidate-root) "' '" (str keep "/candidate") "'")]) .inheritIO)]
            (.waitFor (.start p)))
          (catch Exception e (println :preserve-copy-error (.getMessage e))))
        (delete-tree! fixture-root)))))

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

(deftest frozen-research-pack-is-bound-by-input-set-and-package-closure
  (let [fixture-root (temp-dir)
        run-root (io/file fixture-root "research-pack-package")
        manifest (write-dummy-fixture! fixture-root)
        benchmark-id "benchmark/test-research-pack-closure"
        composition-root "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        plan {:schema-version research-pack/schema-version
              :research-pack/id :research/dummy-pack
              :research-pack/command-root "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
              :research-pack/assignment-root "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
              :research-pack/plan-root "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
              :research-pack/members [{:member/id :member/core
                                       :member/contract "benchmark.v1"
                                       :member/input-root "sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
                                       :member/parameters-root "sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
                                       :member/expected-outputs {}}]
              :research-pack/requested-capabilities []
              :research-pack/reducer-contract research-pack/reducer-contract
              :research-pack/composition-root composition-root
              :research-pack/resolution-root "sha256:1111111111111111111111111111111111111111111111111111111111111111"}
        pack (assoc plan :research-pack/root (research-pack/pack-root plan)
                    :research-pack/composition {:semantic-composition/root composition-root})]
    (try
      (with-redefs [benchmark-cli/resolve-benchmark-manifest (constantly manifest)]
        (let [result (command/run-with-root! benchmark-id (.getPath run-root) nil :public
                                             {:execution/parallelism 1 :execution/chunk-size 1
                                              :research-pack pack})
              pack-file (io/file run-root "benchmark/research-pack.edn")
              assurance (json/read-str (slurp (io/file run-root "benchmark/assertions/benchmark-assurance.json")))]
          (is (zero? (:exit-code result)))
          (is (.isFile pack-file))
          (is (some #(= "research-benchmark-pack" (get % "source_kind"))
                    (get assurance "input_set")))
          (is (= "passed" (get (benchmark-verify/verify! (.getPath run-root)) "status")))
          (spit pack-file (pr-str (assoc (edn/read-string (slurp pack-file))
                                         :research-pack/plan-root "sha256:9999999999999999999999999999999999999999999999999999999999999999")))
          (is (= "failed" (get (benchmark-verify/verify! (.getPath run-root)) "status"))
              "input-set and package closure reject post-publication pack substitution")))
      (finally (delete-tree! fixture-root)))))
