(ns resolver-sim.commands.benchmark-atomic-write-test
  "Tests that every benchmark artifact write uses an atomic write pattern
   (write to .tmp then move) and cleans up its temporary file."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [resolver-sim.commands.run-lifecycle :as lifecycle]
            [resolver-sim.commands.benchmark-inventory :as inventory]
            [resolver-sim.commands.benchmark-conclusion :as conclusion]
            [resolver-sim.evidence.chain :as chain])
  (:import [java.nio.file Files StandardCopyOption]))

(defn- temp-dir []
  (.toFile (Files/createTempDirectory "benchmark-atomic-"
                                      (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- delete-tree! [path]
  (when (.exists (io/file path))
    (doseq [file (reverse (file-seq (io/file path)))]
      (io/delete-file file true))))

(defn- tmp-files [root]
  (->> (file-seq (io/file root))
       (filter #(.isFile %))
       (filter #(str/ends-with? (.getName %) ".tmp"))
       (set)))

(defn- no-tmp-ancestor? [root]
  (->> (file-seq (io/file root))
       (filter #(.isDirectory %))
       (every? #(not (str/ends-with? (.getName %) ".tmp")))))

(defn- assert-no-orphan-tmp-files [root]
  (let [tmp-files (tmp-files root)]
    (is (empty? tmp-files)
        (str "Orphan .tmp files found: " (mapv str tmp-files)))
    (is (no-tmp-ancestor? root)
        "No .tmp directories should remain")))

(defn- assert-valid-json [file expected-schema]
  (is (.exists file) (str "File exists: " file))
  (let [value (json/read-str (slurp file) :key-fn keyword)]
    (is (= expected-schema (:schema_version value))
        (str "schema_version in " file))
    value))

(defn- minimal-evidence []
  {:exit-code 0
   :evidence
   {:run/manifest {:benchmark/manifest-source "resource:benchmarks/packs/sew/manifests/force-authorisation-custody.edn"}
    :benchmark {:benchmark/id "benchmark/sew-force-authorisation-custody-v1"
                :benchmark/status :experimental
                :benchmark/claims #{:claim/funds-conserved}
                :benchmark/required-claims #{:claim/funds-conserved}}
    :results [{:execution/id "exec-001"
               :invariant-results [{:id :conservation-of-funds :result :pass}]
               :scenario/artifacts {:scenario/summary nil :scenario/input-path nil}}]
    :metrics {:total 1 :passed 1}
    :invariant-summary {:total-checks 1 :passed-checks 1}
    :claim-results [{:claim/id :claim/funds-conserved :claim/outcome :pass}]}})

(defn- minimal-context [root]
  (let [r (io/file root)]
    (.mkdirs r)
    (io/make-parents (io/file r "benchmark/conclusion.json"))
    (io/make-parents (io/file r "benchmark/assertions/conservation.json"))
    (io/make-parents (io/file r "benchmark/evidence/evidence.edn"))
    (io/make-parents (io/file r "manifest/run.json"))
    (spit (io/file r "benchmark/evidence/evidence.edn")
          (json/write-str {:evidence "stub"}))
    {:run/root (.toPath r)
     :run/id "test-benchmark-atomic"
     :benchmark/id "benchmark/sew-force-authorisation-custody-v1"
     :benchmark/definition-file (io/file r "benchmark/definition.edn")
     :benchmark/plan-file (io/file r "benchmark/execution-plan.edn")
     :benchmark/evidence-file (io/file r "benchmark/evidence/evidence.edn")
     :benchmark/conclusion-file (io/file r "benchmark/conclusion.json")
     :benchmark/summary-file (io/file r "benchmark/summary.json")
     :benchmark/index-file (io/file r "benchmark/index.edn")
     :benchmark/executions-dir (io/file r "benchmark/executions")
     :manifest/dir (.toPath (io/file r "manifest"))
     :sensitivity/profile :public}))

(deftest lifecycle-atomic-json-cleans-up-tmp
  (let [root (temp-dir)]
    (try
      (let [target (io/file root "benchmark/assertions/conservation.json")]
        (.mkdirs (.getParentFile target))
        (lifecycle/atomic-json! target {:schema_version "benchmark-conservation.v1"})
        (is (.exists target))
        (assert-no-orphan-tmp-files root)
        (is (= "benchmark-conservation.v1"
               (:schema_version (json/read-str (slurp target) :key-fn keyword)))))
      (finally (delete-tree! root)))))

(deftest lifecycle-atomic-json-overwrites-atomically
  (let [root (temp-dir)
        target (io/file root "benchmark/conclusion.json")]
    (try
      (.mkdirs (.getParentFile target))
      (spit target "not-valid-json")
      (lifecycle/atomic-json! target {:schema_version "benchmark-conclusion.v1"})
      (is (= "benchmark-conclusion.v1"
             (:schema_version (json/read-str (slurp target) :key-fn keyword)))
          "atomic-json! must overwrite cleanly even if target contains garbage")
      (assert-no-orphan-tmp-files root)
      (finally (delete-tree! root)))))

(deftest inline-atomic-move-cleans-up-tmp
  (let [root (temp-dir)
        target (io/file root "benchmark/summary.json")]
    (try
      (.mkdirs (.getParentFile target))
      (let [temp (io/file (str (.getPath target) ".tmp"))]
        (spit temp (json/write-str {:schema_version "benchmark-summary.v1"}))
        (Files/move (.toPath temp) (.toPath target)
                    (into-array StandardCopyOption
                                [StandardCopyOption/REPLACE_EXISTING StandardCopyOption/ATOMIC_MOVE])))
      (is (.exists target))
      (assert-no-orphan-tmp-files root)
      (is (= "benchmark-summary.v1"
             (:schema_version (json/read-str (slurp target) :key-fn keyword))))
      (finally (delete-tree! root)))))

(deftest inline-atomic-move-replaces-existing-file
  (let [root (temp-dir)
        target (io/file root "benchmark/conclusion.json")]
    (try
      (.mkdirs (.getParentFile target))
      (spit target "garbage")
      (let [temp (io/file (str (.getPath target) ".tmp"))]
        (spit temp (json/write-str {:schema_version "benchmark-conclusion.v1"}))
        (Files/move (.toPath temp) (.toPath target)
                    (into-array StandardCopyOption
                                [StandardCopyOption/REPLACE_EXISTING StandardCopyOption/ATOMIC_MOVE])))
      (is (= "benchmark-conclusion.v1"
             (:schema_version (json/read-str (slurp target) :key-fn keyword))))
      (assert-no-orphan-tmp-files root)
      (finally (delete-tree! root)))))

(deftest lifecycle-atomic-json-tmp-filename-convention
  (let [root (temp-dir)]
    (try
      (let [paths ["benchmark/conclusion.json"
                   "benchmark/summary.json"
                   "benchmark/assertions/conservation.json"
                   "benchmark/assertions/benchmark-assurance.json"
                   "benchmark/finalization.json"
                   "manifest/artifacts.json"
                   "manifest/artifacts-validation.json"
                   "completion.json"]]
        (doseq [rel-path paths]
          (let [target (io/file root rel-path)]
            (.mkdirs (.getParentFile target))
            (lifecycle/atomic-json! target {:schema_version "test" :path rel-path})
            (is (not (.exists (io/file (str (.getPath target) ".tmp"))))
                (str "No .tmp orphan for " rel-path))
            (is (.exists target) (str "Target created for " rel-path)))))
      (finally (delete-tree! root)))))

(deftest inventory-build-writes-atomically
  (let [root (temp-dir)]
    (try
      (let [r (io/file root)
            context (minimal-context root)]
        (spit (io/file r "benchmark/definition.edn") "{:benchmark/id :test}")
        (spit (io/file r "benchmark/execution-plan.edn") "{:executions []}")
        (spit (io/file r "benchmark/conclusion.json")
              (json/write-str {:schema_version "benchmark-conclusion.v1"}))
        (spit (io/file r "benchmark/summary.json")
              (json/write-str {:schema_version "benchmark-summary.v1"}))
        (spit (io/file r "benchmark/assertions/conservation.json")
              (json/write-str {:schema_version "benchmark-conservation.v1"}))
        (spit (io/file r "benchmark/assertions/benchmark-assurance.json")
              (json/write-str {:schema_version "benchmark-assurance.v1"}))
        (spit (io/file r "benchmark/index.edn") "{:index []}")
        (inventory/build! context))
      (assert-no-orphan-tmp-files root)
      (assert-valid-json (io/file root "manifest/artifacts.json") "benchmark-artifacts.v1")
      (finally (delete-tree! root)))))

(deftest conclusion-write-writes-atomically
  (let [root (temp-dir)]
    (try
      (let [context (minimal-context root)]
        (spit (io/file root "benchmark/evidence/evidence.edn")
              (json/write-str {:test "evidence"}))
        (conclusion/write! context (minimal-evidence)))
      (assert-no-orphan-tmp-files root)
      (assert-valid-json (io/file root "benchmark/conclusion.json") "benchmark-conclusion.v1")
      (finally (delete-tree! root)))))

(deftest benchmark-lifecycle-complete-writes-atomically
  (let [root (temp-dir)]
    (try
      (let [completion {:schema_version "benchmark-completion.v1"
                        :benchmark_id "benchmark/test"
                        :run_id "test-complete"
                        :run_type "benchmark"
                        :lifecycle_status "completed"
                        :semantic_status "pass"}]
        (lifecycle/mark-running! root "test-complete" :benchmark)
        (lifecycle/complete! root completion))
      (assert-no-orphan-tmp-files root)
      (assert-valid-json (io/file root "completion.json") "benchmark-completion.v1")
      (is (not (.exists (io/file root ".run-state")))
          ".run-state must be deleted on completion")
      (finally (delete-tree! root)))))

(deftest benchmark-lifecycle-atomic-json-survives-concurrent-overwrite
  (let [root (temp-dir)
        target (io/file root "manifest/artifacts.json")]
    (try
      (.mkdirs (.getParentFile target))
      (spit target "{}")
      (let [f1 (future
                 (lifecycle/atomic-json! target {:version 1})
                 :done)
            f2 (future
                 (lifecycle/atomic-json! target {:version 2})
                 :done)]
        @f1 @f2)
      (is (.exists target))
      (assert-no-orphan-tmp-files root)
      (let [v (some-> (slurp target) (json/read-str :key-fn keyword))]
        (is (contains? v :version)))
      (finally (delete-tree! root)))))
