(ns resolver-sim.commands.benchmark-inventory
  "Root-relative inventory for canonical benchmark bundles."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.math BigInteger]
           [java.nio.file Files Path StandardCopyOption]
           [java.security MessageDigest]))

(def ^:private excluded #{"manifest/artifacts.json" "manifest/artifacts-validation.json"
                         "completion.json" ".run-state" ".run.lock"})

(def ^:private known
  {"manifest/run.json" ["manifest.run" "run-manifest" "benchmark-run-manifest.v1"]
   "manifest/sensitivity-report.json" ["manifest.sensitivity-report" "sensitivity-report" "sensitivity-report.v1"]
      "benchmark/execution/runner-finalization.json" ["benchmark.runner-finalization" "runner-finalization" "runner-finalization.v1"]
   "benchmark/definition.edn" ["benchmark.definition" "benchmark.definition" "edn.v1"]
   "benchmark/execution-plan.edn" ["benchmark.execution-plan" "benchmark.execution-plan" "benchmark-execution-plan.v1"]
   "benchmark/index.edn" ["benchmark.index" "benchmark.index" "benchmark-artifact-index.v1"]
   "benchmark/evidence/evidence.edn" ["benchmark.evidence" "benchmark.evidence" "benchmark-result.v1"]
   "benchmark/summary.json" ["benchmark.summary" "benchmark.summary" "benchmark-summary.v1"]
   "benchmark/conclusion.json" ["benchmark.conclusion" "benchmark.conclusion" "benchmark-conclusion.v1"]
   "benchmark/assertions/conservation.json" ["benchmark.conservation" "benchmark.conservation" "benchmark-conservation.v1"]
   "benchmark/assertions/benchmark-assurance.json" ["benchmark.assurance" "benchmark.assurance" "benchmark-assurance.v1"]})

(defn- sha256 [file]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (with-open [stream (io/input-stream file)]
      (let [buffer (byte-array 8192)]
        (loop [read (.read stream buffer)]
          (when (pos? read) (.update digest buffer 0 read) (recur (.read stream buffer))))))
    (format "%064x" (BigInteger. 1 (.digest digest)))))

(defn- relative [^Path root file]
  (let [path (str (.relativize root (.toPath (io/file file))))]
    (when (or (.startsWith path "/") (some #{".."} (str/split path #"[\\/]+")))
      (throw (ex-info "Benchmark inventory path escapes run root" {:path path})))
    path))

(defn- spec [relative]
  (or (known relative)
      (when (str/starts-with? relative "benchmark/executions/")
        [(str "execution." (str/replace relative #"[^A-Za-z0-9]+" "."))
         "benchmark.execution-artifact" "unknown"])))

(defn build! [context]
  (let [root (io/file (str (:run/root context)))
        root-path (.toAbsolutePath (.normalize (.toPath root)))
        files (->> (file-seq root) (filter #(.isFile %))
                   (map #(vector (relative root-path %) %))
                   (remove #(excluded (first %)))
                   (sort-by first))
        entries (->> files
                     (keep (fn [[path file]]
                             (when-let [[id kind schema] (spec path)]
                               {:id id :kind kind :path path :importance "CORE"
                                :schema_version schema :contract_version "evidence-contract.v1"
                                :producer "benchmark-inventory.clj"
                                :sensitivity_profile (name (:sensitivity/profile context))
                                :export_policy (if (= :public (:sensitivity/profile context)) "public-scan-required" "internal-retention")
                                :dependencies [] :sha256 (sha256 file) :bytes (.length file)})))
                     vec)
        ids (map :id entries)]
    (when-not (= (count ids) (count (set ids)))
      (throw (ex-info "Benchmark inventory generated duplicate artifact IDs" {:ids ids})))
    (let [target (io/file root "manifest/artifacts.json")
          temp (io/file (str (.getPath target) ".tmp"))
          registry {:schema_version "benchmark-artifacts.v1"
                    :contract_version "evidence-contract.v1"
                    :run_id (:run/id context) :root_dir "."
                    :sensitivity_profile (name (:sensitivity/profile context))
                    :artifacts entries}]
      (.mkdirs (.getParentFile target))
      (spit temp (json/write-str registry))
      (Files/move (.toPath temp) (.toPath target)
                  (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING StandardCopyOption/ATOMIC_MOVE]))
      registry)))
