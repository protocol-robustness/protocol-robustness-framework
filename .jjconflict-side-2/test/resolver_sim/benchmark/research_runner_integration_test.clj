(ns resolver-sim.benchmark.research-runner-integration-test
  "End-to-end: a frozen research definition drives a genuine `run-benchmark`
  through the optional :research-context, and the resulting C × M matrix is
  derived from the real execution results carrying research lineage."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.repo :as repo]
            [resolver-sim.benchmark.research-definition :as research]
            [resolver-sim.benchmark.research-execution-projection :as proj]
            [resolver-sim.benchmark.runner :as runner]
            [resolver-sim.vcs :as vcs])
  (:import [java.util UUID]))

(def clean-source-provenance
  {:git-commit-sha "sha256:test-commit"
   :source/hash "sha256:test-source-hash"
   :source/hash-algorithm "source-tree-hash-v1"
   :source/hash-roots []
   :code-hash "sha256:test-code-hash"
   :deps-hash "sha256:test-deps-hash"
   :input-hash "sha256:test-input-hash"
   :dirty? false})

(def coverage-measure
  {:measure/id :measure/coverage-margin
   :measure/kind :requirement-margin.v1
   :measure/domain {:kind :integer :unit :usdc}
   :measure/observation {:observation/id :coverage/covered-capacity}
   :measure/requirement {:requirement/kind :constant :value 800N :unit :usdc}
   :measure/satisfying-direction :at-least})

(def source
  ;; 2 x 2 grid = 4 frozen research cases, matching the 4 executions of
  ;; benchmark/prf-shortfall-allocation-v0 (run-count 1).
  {:artifact/schema "research-source.v1"
   :research/id :study/shortfall-allocation-v0
   :research/title "Shortfall allocation coverage"
   :research/question "What coverage margin remains across shortfall conditions?"
   :research/vary {:parameter/condition-a [1N 2N]
                   :parameter/condition-b [10N 20N]}
   :research/measures [coverage-measure]
   :research/hypotheses []})

(defn- temp-dir! []
  (doto (java.io.File. (System/getProperty "java.io.tmpdir")
                       (str "research-runner-" (UUID/randomUUID)))
    (.mkdirs)))

(defn- observed-invariants-pass [result]
  (count (filter #(= :pass (:result %)) (:invariant-results result))))

(deftest run-benchmark-carries-research-lineage-and-derives-matrix
  (let [root (temp-dir!)
        output (io/file root "out")]
    (try
      (let [frozen (research/freeze-research source)
            evidence (with-redefs [repo/metadata (fn [] {:repo {:commit "test-commit" :dirty? false}})
                                   vcs/source-provenance (constantly clean-source-provenance)]
                       (runner/run-benchmark
                        "benchmarks/packs/prf-core/shortfall-allocation-v0.edn"
                        runner/default-adapter
                        {:scenario-output-dir (.getPath output)
                         :research-context frozen}))
            results (:results evidence)]
        (is (= 4 (count results)))
        (testing "results carry the rooted research lineage"
          (is (every? #(contains? % :research-definition/root) results))
          (is (every? #(contains? % :research-case-axis/root) results))
          (is (every? #(contains? % :research-case/key) results))
          (is (every? #(contains? % :research-execution-projection/root) results))
          (is (every? #(= (:research-definition/root frozen) (:research-definition/root %)) results))
          (is (= (set (range 4)) (into #{} (map :research-case/key) results))))
        (testing "C × M matrix is derived from genuine results and binds D + C + M (not E)"
          (let [compiled (proj/compile-research-matrix-from-results
                          frozen results observed-invariants-pass
                          "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                matrix (:matrix compiled)]
            (is (= 4 (count (:matrix/values matrix))))
            (is (= (:research-definition/root frozen) (:research-definition/root matrix)))
            (is (= (proj/research-case-axis-root frozen) (:case-axis/root matrix)))
            (is (not= (:execution-case-set/root compiled) (:case-axis/root matrix)))
            (is (= (proj/verify-research-run-projection
                    frozen (proj/prepare-research-plan frozen (proj/results->execution-plan results)))
                   (proj/verify-research-run-projection
                    frozen (proj/prepare-research-plan frozen (proj/results->execution-plan results))))))))
      (finally
        (doseq [file (reverse (file-seq root))]
          (.delete file))))))