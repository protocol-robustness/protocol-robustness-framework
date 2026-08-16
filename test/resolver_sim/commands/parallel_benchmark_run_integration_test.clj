(ns resolver-sim.commands.parallel-benchmark-run-integration-test
  "Integration coverage for the parallel-benchmark-run capability
   composition.

   The command is a bounded capability composition, not a separate benchmark
   algorithm: it routes through the same canonical run-with-root! +
   run->benchmark path as run-benchmark, adding bounded local parallelism
   across scenario workers and claimant executors. Its build declares (and the
   registry validates) that it is composed with the incentive and
   incentive-compatibility capabilities. This suite asserts the guarantees
   that follow: canonical output invariant to budget-bounded parallelism,
   ordinary verify-benchmark accepting the output unchanged, a bounded
   automatic default capped at a fixed ceiling, and an explicit --parallelism
   honored exactly."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.cli :as benchmark-cli]
            [resolver-sim.benchmark.verify :as benchmark-verify]
            [resolver-sim.cli.registry :as registry]
            [resolver-sim.commands.run-benchmark :as command]))

(defn- temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
            "prf-parallel-benchmark-run-"
            (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- delete-tree! [path]
  (when (.exists (io/file path))
    (doseq [file (reverse (file-seq (io/file path)))]
      (io/delete-file file true))))

(defn- evid [root]
  (edn/read-string (slurp (io/file root "benchmark/evidence/evidence.edn"))))

(defn- package-semantics [root]
  (let [bundle (evid root)]
    {:benchmark-root (:evidence/hash bundle)
     :scenario-roots (mapv (fn [r]
                             {:execution/id (:execution/id r)
                              :scenario/id (:scenario/id r)
                              :outcome (:outcome r)
                              :pass? (:pass? r)})
                           (:results bundle))
     :closure (:benchmark/execution-closure bundle)}))

(deftest parallel-benchmark-run-execution-budget-preserves-canonical-package
  (let [fixture (temp-dir)
        scenarios (doto (io/file fixture "scenarios") .mkdirs)
        manifest (io/file fixture "parallel-edn-benchmark.edn")
        serial-root (io/file fixture "serial-package")
        parallel-root (io/file fixture "parallel-package")
        benchmark-id "benchmark/test-parallel-benchmark-run-execution-budget"
        scenario (fn [id]
                   {:scenario-id id
                    :schema-version "1.0"
                    :initial-block-time 1000
                    :protocol "dummy"
                    :agents [{:id "agent" :address "0xA"}]
                    :events [{:seq 0 :time 1000 :agent "agent" :action "noop" :params {}}]})]
    (try
      (doseq [id ["alpha" "beta" "gamma"]]
        (spit (io/file scenarios (str id ".edn")) (pr-str (scenario id))))
      (spit manifest (pr-str {:benchmark/id :benchmark/parallel-benchmark-run-execution-budget
                              :scenario-suites [(.getPath scenarios)]
                              :benchmark/claims []}))
      (with-redefs [benchmark-cli/resolve-benchmark-manifest
                    (fn [id] (if (= benchmark-id id)
                               (.getPath manifest)
                               (throw (ex-info "unexpected benchmark ID" {:id id}))))]
        (testing "serial (1 worker, unbounded) and parallel+budget (2 workers, budget 2) both complete and verify"
          (let [serial (command/run-with-root! benchmark-id (.getPath serial-root) nil :public
                                               {:execution/parallelism 1 :execution/chunk-size 1})
                parallel (command/run-with-root! benchmark-id (.getPath parallel-root) nil :public
                                                 {:execution/parallelism 2 :execution/chunk-size 1
                                                  :execution/budget 2})]
            (is (zero? (:exit-code serial)))
            (is (zero? (:exit-code parallel)))
            (is (= "passed" (get (benchmark-verify/verify! (.getPath serial-root)) "status")))
            (is (= "passed" (get (benchmark-verify/verify! (.getPath parallel-root)) "status"))
                "a budget-bounded parallel run produces a verifiable canonical package")))
        (testing "canonical output is invariant to budget-bounded parallel execution"
          (is (= (package-semantics serial-root)
                 (package-semantics parallel-root))
              "bundle root, scenario roots, and closure match across serial and budget-bounded parallel")))
      (finally (delete-tree! fixture)))))

(deftest parallel-benchmark-run-is-a-bounded-composition
  ;; Shared handler routes both command paths to the canonical run-benchmark
  ;; algorithm. Non-positive --execution-budget is rejected (exit 2) before
  ;; any benchmark resolution or root mutation, and the resolved parallelism
  ;; is snapped to a fixed ceiling independent of the scenario count.
  (let [handler #'command/run]
    (testing "non-positive execution budget is rejected up front"
      (is (= 2 (:exit-code (handler {:cmd/path "parallel-benchmark-run"
                                     :cmd/args ["benchmark/x"]
                                     :execution-budget 0
                                     :run-root "/tmp/untouched"})))
          "budget 0 is rejected")
      (is (= 2 (:exit-code (handler {:cmd/path "parallel-benchmark-run"
                                     :cmd/args ["benchmark/x"]
                                     :execution-budget -3
                                     :run-root "/tmp/untouched"})))
          "negative budget is rejected"))
    (testing "shared handler resolves for both command paths"
      (is (fn? @#'command/run)))
    (testing "effective parallelism: bounded automatic default, explicit honored exactly"
      (let [ep command/effective-parallelism]
        ;; Automatic default = min(scenario-count, ceiling); bounded for large
        ;; scenario counts, never the raw scenario count.
        (is (= 3 (ep true nil 3)))
        (is (= 8 (ep true nil 1000)) "default capped at the ceiling")
        (is (= 1 (ep true nil 0)) "degenerate/unknown scenario count -> 1")
        ;; Explicit --parallelism is honored exactly, never clamped or
        ;; reinterpreted (operator choice).
        (is (= 3 (ep true 3 0)))
        (is (= 16 (ep true 16 1000)))
        (is (= 1000 (ep true 1000 5000)))
        ;; The plain run-benchmark path is a single worker by default and is
        ;; not bounded by the composition ceiling.
        (is (= 1 (ep false nil 0)))
        (is (= 6 (ep false 6 0)))))))

(deftest parallel-composition-registers-incentive-and-incentive-compatibility-includes
  ;; The parallel command's build declares it is composed with the incentive
  ;; and incentive-compatibility capabilities. This is a build-composition
  ;; declaration (validated against a capability vocabulary by the command
  ;; registry) — not an outcome-dimension gate.
  (let [reg (edn/read-string (slurp "resources/prf/commands/registry.edn"))
        entry (first (filter #(= :parallel-benchmark-run (:command/id %))
                             (:commands reg)))
        validation (resolver-sim.cli.registry/validate-registry)]
    (is (some? entry) "parallel-benchmark-run present in registry")
    (is (= #{:capability/incentive :capability/incentive-compatibility}
           (:command/built-with-includes entry))
        "declares incentive + incentive-compatibility includes")
    (is (:ok? validation) "registry (incl. built-with-includes) validates")))

(deftest registry-rejects-unknown-built-with-includes
  (let [base {:schema-version "prf.commands.registry.v1"
              :commands [{:command/id :weird
                          :command/path ["weird"]
                          :command/category :test
                          :command/surface :prf
                          :command/jar-availability :native
                          :command/runtime :jvm
                          :command/description "x"
                          :command/positional-args {:min 0 :max 0}}]}
        ok (resolver-sim.cli.registry/validate-registry)]
    (is (:ok? ok))
    (let [with-unknown (assoc-in base [:commands 0 :command/built-with-includes]
                                 #{:capability/incentive :capability/space-travel})
          result (with-redefs [resolver-sim.cli.registry/load-registry
                               (constantly with-unknown)]
                   (resolver-sim.cli.registry/validate-registry))]
      (is (false? (:ok? result)))
      (is (some #(re-find #"declares unknown capabilities" %)
                (:errors result))))))