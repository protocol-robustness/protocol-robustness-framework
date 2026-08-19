(ns resolver-sim.benchmark.all-manifest-smoke-test
  "All-manifest benchmark smoke matrix: plan/load every benchmark from
   the canonical registry, resolve every suite and scenario input, and
   execute a bounded representative matrix to validate the end-to-end
   pipeline without running the full corpus."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.runner :as runner]
            [resolver-sim.evidence.chain :as chain]
            [resolver-sim.io.input-source :as input-source]
            [resolver-sim.io.resource-path :as rp]
            [resolver-sim.scenario.suites :as suites]))

(defn- enumerate-manifests
  "Walk every pack in the canonical registry and return a sequence of
   {:pack <kw> :id <kw> :manifest-path <resource:str> :suite-key <kw> :manifest <map>}
   or collect error records."
  []
  (let [errors (atom [])
        manifests (atom [])
        registry (rp/edn-read rp/canonical-registry-path)]
    (doseq [pack (:packs registry)]
      (let [pack-reg-path (rp/pack-registry-path (:pack/registry pack))
            pack-registry (try (rp/edn-read pack-reg-path)
                               (catch Throwable e
                                 (swap! errors conj {:type :pack-registry-read
                                                     :pack (:pack/id pack)
                                                     :path pack-reg-path
                                                     :error (.getMessage e)})
                                 nil))]
        (when pack-registry
          (doseq [ref (:benchmarks pack-registry)]
            (let [manifest-path (rp/relative-to pack-reg-path (:benchmark/file ref))
                  manifest (try (rp/edn-read manifest-path)
                                (catch Throwable e
                                  (swap! errors conj {:type :manifest-read
                                                      :benchmark (:benchmark/id ref)
                                                      :path manifest-path
                                                      :error (.getMessage e)})
                                  nil))]
              (if manifest
                (swap! manifests conj
                       {:pack (:pack/id pack-registry)
                        :id (:benchmark/id ref)
                        :status (:benchmark/status ref)
                        :manifest-path manifest-path
                        :suite-key (:benchmark/scenario-suite manifest)
                        :manifest manifest})
                (swap! errors conj {:type :manifest-not-found
                                    :benchmark (:benchmark/id ref)
                                    :path manifest-path})))))))
    {:manifests @manifests :errors @errors}))

(deftest all-manifests-plan-load
  "Enumerate every benchmark in the canonical registry, load every manifest,
   resolve every suite key, and verify every scenario input path resolves
   through input-source.  Fail only after collecting all errors."
  (let [{:keys [manifests errors]} (enumerate-manifests)]
    (testing "all pack registries and manifests load without error"
      (is (empty? errors)
          (str "Registry/manifest load errors: " (pr-str errors))))
    (let [suite-errors (atom [])
          id-errors (atom [])
          ids (atom [])]
      (doseq [m manifests]
        (swap! ids conj (:id m))
        (let [suite-key (:suite-key m)]
          (if suite-key
            (if-let [paths (suites/suite-paths suite-key)]
              (doseq [path paths]
                (try
                  (input-source/source path)
                  (catch Throwable e
                    (swap! suite-errors conj
                           {:benchmark (:id m) :suite suite-key
                            :path path :error (.getMessage e)}))))
              (swap! suite-errors conj
                     {:benchmark (:id m) :suite suite-key
                      :error "suite-key not found in suites or pack-suites"}))
            (swap! suite-errors conj
                   {:benchmark (:id m)
                    :error "manifest has no :benchmark/scenario-suite key"}))))
      (let [duplicates (->> @ids frequencies
                            (keep (fn [[id n]] (when (> n 1) id)))
                            vec)]
        (when (seq duplicates)
          (swap! id-errors conj {:type :duplicate-ids :ids duplicates})))
      (testing "all suite keys resolve to scenario paths"
        (is (empty? @suite-errors)
            (str "Suite/input resolution errors: " (pr-str @suite-errors))))
      (testing "no duplicate benchmark IDs"
        (is (empty? @id-errors)
            (str "Duplicate IDs: " (pr-str @id-errors))))
      (testing "minimum corpus size"
        (is (>= (count manifests) 10)
            (str "Expected at least 10 benchmarks in corpus, got " (count manifests))))
      (testing "both packs represented"
        (let [packs (set (map :pack manifests))]
          (is (contains? packs :pack/sew) "Sew pack must be present")
          (is (contains? packs :pack/prf-core) "PRF core pack must be present"))))))

(def bounded-candidates
  "Benchmarks with small suites selected for bounded end-to-end execution.
   Selected from the Sew pack because the runner's default SewAdapter handles
   :protocol/sew benchmarks.  PRF-core benchmarks require a separate adapter
   or protocol-agnostic lifecycle and are not exercised here.
   Each entry is [manifest-path expected-id expected-executions]."
  [["benchmarks/packs/prf-core/force-authorisation-custody-v1.edn"
    :benchmark/force-authorisation-custody-v1
    2]])

(deftest bounded-representative-execution
  "Execute a bounded representative subset of benchmarks to validate the
   end-to-end run pipeline.  Selects only benchmarks with minimal suites
   (2-3 scenarios each) to control runtime."
  (binding [chain/*allow-dirty* true]
    (doseq [[manifest-path expected-id expected-count] bounded-candidates]
      (testing (str "bounded execution of " manifest-path)
        (let [out-dir (str (System/getProperty "java.io.tmpdir")
                           "/benchmark-smoke-" (name expected-id) "-"
                           (System/currentTimeMillis))
              evidence (runner/run-benchmark manifest-path
                                             (runner/->SewAdapter out-dir 1 1) {})]
          (is (contains? evidence :benchmark) ":benchmark key present")
          (is (contains? evidence :results) ":results key present")
          (is (contains? evidence :metrics) ":metrics key present")
          (is (contains? evidence :evidence/hash) ":evidence/hash key present")
          (is (contains? evidence :benchmark-certification) ":benchmark-certification key present")
          (is (= expected-id (get-in evidence [:benchmark :benchmark/id]))
              (str "benchmark ID matches " expected-id))
          (is (= expected-count (count (:results evidence)))
              (str "expected " expected-count " executions"))
          (is (every? #(contains? % :outcome) (:results evidence))
              "every result has an :outcome")
          (is (every? #(contains? % :execution/id) (:results evidence))
              "every result has an :execution/id")
          (let [metrics (:metrics evidence)]
            (is (contains? metrics :total) "metrics has :total")
            (is (contains? metrics :passed) "metrics has :passed")
            (is (pos? (:total metrics)) "at least one execution")
            (is (= (:total metrics) (:passed metrics))
                (str "all executions must pass, got total=" (:total metrics)
                     " passed=" (:passed metrics)))))))))
