(ns resolver-sim.commands.scenario-inventory
  "Initial root-relative artifact inventory for structured scenario bundles."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [resolver-sim.io.paths :as paths]
            [resolver-sim.config.paths :as cfg-paths])
  (:import [java.security MessageDigest]
           [java.math BigInteger]
           [java.nio.file Files StandardCopyOption Path Paths]))

(def ^:private terminal-outer-artifacts
  "Excluded to keep the registry/package/completion commitment chain non-circular.
   The package index commits to the registry; completion commits to the index."
  #{paths/run-package-index paths/completion})

(def ^:private known
  {"manifest/run.json" {:id "manifest.run" :kind "run-manifest" :schema "run-manifest.v1" :importance "CORE"}
   "manifest/summary.json" {:id "manifest.summary" :kind "summary" :schema "summary.v1" :importance "CORE"}
   "manifest/value-at-risk.json" {:id "manifest.value-at-risk" :kind "scenario.value-at-risk" :schema "scenario-value-at-risk.v1" :importance "CORE"}
   "manifest/claimable-classification.json" {:id "manifest.claimable-classification" :kind "summary" :schema "claimable-classification.v2" :importance "CORE"}
   "manifest/run-enrichment.json" {:id "manifest.run-enrichment" :kind "run-enrichment" :schema "run-enrichment.v1" :importance "CORE"}
   "manifest/canonical-integrity.json" {:id "manifest.canonical-integrity" :kind "canonical-integrity" :schema "canonical-integrity.v1" :importance "CORE"}
   "manifest/forensic-claims-status.json" {:id "manifest.forensic-claims-status" :kind "forensic-claims-status" :schema "forensic-claims-status.v1" :importance "CORE"}
   "manifest/verdict-policy.json" {:id "manifest.verdict-policy" :kind "verdict-policy" :schema "verdict-policy.v1" :importance "CORE"}

   "manifest/diagnostic-summary.json" {:id "manifest.diagnostic-summary" :kind "scenario.diagnostic-summary" :schema "scenario-diagnostic-summary.v1" :importance "CORE"}
   "manifest/pro-rata-mechanism-nodes.json" {:id "manifest.pro-rata-mechanism-nodes" :kind "mechanism.pro-rata-nodes" :schema "pro-rata-mechanism-nodes.v1" :importance "CORE"}
   paths/sensitivity-report {:id "manifest.sensitivity-report" :kind "sensitivity-report" :schema "sensitivity-report.v2" :importance "CORE"}
   "execution/replay-output.json" {:id "execution.replay-output" :kind "raw.replay" :schema "bundle-root.v1" :importance "DIAGNOSTIC"}
   "execution/execution-dag.json" {:id "execution.dag" :kind "execution.dag" :schema "execution-dag.v1" :importance "CORE"}
   "execution/pre-run-commitment.json" {:id "execution.pre-run-commitment" :kind "pre-run-commitment" :schema "pre-run-commitment.v1" :importance "CORE"}
   "execution/runner-finalization.json" {:id "execution.runner-finalization" :kind "runner-finalization" :schema "runner-finalization.v1" :importance "CORE" :dependencies ["execution.replay-output"]}
   "summaries/trace-summary.json" {:id "summaries.trace" :kind "summary.trace" :schema "trace-summary.v1" :importance "CORE" :dependencies ["execution.replay-output"]}
   "summaries/metrics.json" {:id "summaries.metrics" :kind "summary.metrics" :schema "scenario-metrics.v1" :importance "CORE" :dependencies ["execution.replay-output"]}
   "summaries/claimable-classification.json" {:id "summaries.claimable" :kind "summary.claimable" :schema "claimable-classification.v2" :importance "CORE" :dependencies ["execution.replay-output"]}
   "summaries/mechanism-summary.json" {:id "summaries.mechanisms" :kind "summary.mechanisms" :schema "mechanism-summary.v1" :importance "CORE" :dependencies ["execution.replay-output"]}
   "summaries/schema-map.json" {:id "summaries.schema-map" :kind "summary.schema-map" :schema "schema-map.v1" :importance "DIAGNOSTIC" :dependencies ["execution.replay-output"]}
   "summaries/partial-fill-decisions.json" {:id "summaries.partial-fill-decisions" :kind "summary.partial-fill-decisions" :schema "partial-fill-decisions.v1" :importance "CORE" :dependencies ["execution.replay-output"]}
   "summaries/partial-fill-decisions.md" {:id "summaries.partial-fill-decisions-review" :kind "summary.partial-fill-decisions-review" :schema "partial-fill-decisions-review.v1" :importance "DIAGNOSTIC" :dependencies ["summaries.partial-fill-decisions"]}
   "summaries/fraud-group-slash-allocation.json" {:id "summaries.fraud-group-slash-allocation" :kind "summary.fraud-group-slash-allocation" :schema "fraud-group-slash-allocation.v1" :importance "CORE" :dependencies ["execution.replay-output"]}
   "summaries/fraud-group-slash-allocation.md" {:id "summaries.fraud-group-slash-allocation-review" :kind "summary.fraud-group-slash-allocation-review" :schema "fraud-group-slash-allocation-review.v1" :importance "DIAGNOSTIC" :dependencies ["summaries.fraud-group-slash-allocation"]}
   "summaries/trace-plain.md" {:id "summaries.trace-plain" :kind "summary.trace-plain" :schema "trace-plain.v1" :importance "DIAGNOSTIC" :dependencies ["summaries.trace"]}
   "state/world-final.json" {:id "state.world-final" :kind "state.final" :schema "world-final.v1" :importance "CORE" :dependencies ["execution.replay-output"]}})

(defn- hash-file [file]
  (let [d (MessageDigest/getInstance "SHA-256")]
    (with-open [in (io/input-stream file)]
      (let [b (byte-array 8192)]
        (loop [n (.read in b)]
          (when (pos? n) (.update d b 0 n) (recur (.read in b))))))
    (format "%064x" (BigInteger. 1 (.digest d)))))

(defn- relative-path [^Path root file]
  (let [relative (str (.relativize root (.toPath (io/file file))))]
    (when (or (.isAbsolute (Paths/get relative (make-array String 0)))
              (some #{".."} (str/split relative #"[\\/]+")))
      (throw (ex-info "Inventory path escapes run root" {:path relative})))
    relative))

(defn- scenario-finalization-spec [relative]
  (let [suffix (subs relative (count "forensic/finalizations/scenarios/"))]
    {:id (str "evidence.scenario-finalization." (str/replace suffix #"[^A-Za-z0-9]+" "."))
     :kind "evidence.scenario-finalization" :schema "evidence-finalization.v2" :importance "CORE"}))

(defn- forensic-spec [relative]
  (let [suffix (subs relative (count "forensic/"))
        evidence-node? (str/includes? suffix "evidence-nodes/")]
    {:id (str "forensic." (str/replace suffix #"[^A-Za-z0-9]+" "."))
     :kind (if evidence-node? "evidence.node" "forensic.evidence")
     :schema (if evidence-node? "evidence-node.v1" "unknown")
     :importance (if evidence-node? "CORE" "DIAGNOSTIC")}))

(defn- run-evidence-spec [relative]
  {:id (str "evidence." (str/replace relative #"[^A-Za-z0-9]+" "."))
   :kind "evidence.finalization" :schema "evidence-finalization.v2" :importance "CORE"})

(defn- input-spec [file]
  ;; The exact source bytes are part of the replay evidence. Use their complete
  ;; content hash as identity, rather than the human filename or short prefix.
  {:id (str "input.scenario." (hash-file file))
   :kind "input.scenario" :schema "scenario-input.v1" :importance "CORE"})

(defn- entry [root scenario-prefix profile relative]
  (let [file (io/file root relative)
        local (if (.startsWith relative scenario-prefix)
                (subs relative (count scenario-prefix))
                relative)
        spec (or (known local)
                 (when (.startsWith local "forensic/finalizations/scenarios/")
                   (scenario-finalization-spec local))
                 (when (.startsWith local "forensic/") (forensic-spec local))
                 (when (.startsWith relative "evidence/") (run-evidence-spec relative))
                 (when (.startsWith relative "inputs/scenarios/") (input-spec file)))]
    (when (and spec (.isFile file))
      {:id (:id spec) :kind (:kind spec) :path relative :importance (:importance spec)
       :schema_version (:schema spec) :contract_version "evidence-contract.v1"
       :producer "scenario-inventory.clj" :sensitivity_profile (name (or profile :public))
       :export_policy (if (= (or profile :public) :public) "public-scan-required" "internal-retention") :verifies_against []
       :dependencies (mapv (fn [id] {:id id}) (:dependencies spec []))
       :sha256 (hash-file file) :bytes (.length file)})))

(defn- atomic-write! [file content]
  (let [target (io/file file)
        temp (io/file (.getParentFile target)
                      (str "." (.getName target) ".tmp-" (java.util.UUID/randomUUID)))]
    (.mkdirs (.getParentFile target))
    (spit temp content)
    (Files/move (.toPath temp) (.toPath target)
                (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING StandardCopyOption/ATOMIC_MOVE]))))

(defn build! [context]
  (let [root (io/file (str (:run/root context)))
        root-path (.toAbsolutePath (.normalize (.toPath root)))
        scenario-root (io/file (str (:scenario/root context)))
        scenario-prefix (str (cfg-paths/scenarios-root) "/" (:scenario/slug context) "/")
        standard-paths (map #(str scenario-prefix %) (remove #(str/starts-with? % "manifest/") (keys known)))
        manifest-paths (filter #(str/starts-with? % "manifest/") (keys known))
        forensic-paths (for [file (file-seq (io/file scenario-root "forensic"))
                             :when (.isFile file)]
                         (relative-path root-path file))
        run-evidence-paths (for [file (file-seq (io/file root "evidence"))
                                 :when (.isFile file)]
                             (relative-path root-path file))
        input-paths (for [file (file-seq (io/file root "inputs" "scenarios"))
                          :when (.isFile file)]
                      (relative-path root-path file))
        entries (->> (concat standard-paths manifest-paths forensic-paths run-evidence-paths input-paths)
                     (remove terminal-outer-artifacts)
                     distinct
                     (keep #(entry root scenario-prefix (:sensitivity/profile context) %))
                     (sort-by :id)
                     vec)
        ids (map :id entries)
        _ (when-not (= (count ids) (count (set ids)))
            (throw (ex-info "Inventory generated duplicate artifact IDs" {:ids ids})))
        profile (or (:sensitivity/profile context) :public)
        registry {:schema_version "test-artifacts.v1.2" :contract_version "evidence-contract.v1"
                  :run_id (:run/id context) :root_dir "."
                  :sensitivity_profile (name profile)
                  :export_policy (if (= :public profile) "public-scan-required" "internal-retention")
                  :artifacts entries}]
    (atomic-write! (io/file root paths/artifacts-registry) (json/write-str registry :indent true))
    registry))
