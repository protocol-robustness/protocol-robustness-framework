(ns resolver-sim.io.scenario-run-root-test
  "End-to-end containment tests for the structured bb scenario runner.

   These intentionally invoke bb rather than calling the runner directly: path
   ownership is an orchestration contract."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.commands.scenario-run :as scenario-command]
            [resolver-sim.hash.reference :as hash-ref]))

(def ^:private legacy-roots [hash-ref/results-runs-dir hash-ref/test-artifacts-dir "results/evidence" "prf-runs" "prf-artifacts" "target/run"])
(def ^:private settlement-scenario "scenarios/edn/S-DR-084-evidence-after-settlement-rejected.edn")
(def ^:private pro-rata-scenario "scenarios/edn/Y06_multi-party-pro-rata-shortfall.edn")
(def ^:private settlement-slug "S-DR-084-evidence-after-settlement-rejected")
(def ^:private settlement-directory-slug (scenario-command/scenario-slug settlement-scenario))

(defn- delete-tree! [path]
  (let [file (io/file path)]
    (when (.exists file)
      (doseq [f (reverse (file-seq file))]
        (io/delete-file f true)))))

(defn- directory-snapshot []
  (into {}
        (for [root legacy-roots
              :let [dir (io/file root)]]
          [root (if (.exists dir)
                  (set (map #(.getCanonicalPath %) (file-seq dir)))
                  #{})])))

(defn- run-structured! [scenario run-root]
  (shell/sh "bb" "run:scenario" scenario "-a" "--run-root" (.getCanonicalPath (io/file run-root))))

(defn- generated-run-roots [slug]
  (let [runs-dir (io/file hash-ref/results-runs-dir)
        prefix (str slug "-")]
    (if (.exists runs-dir)
      (->> (.listFiles runs-dir)
           (filter #(.isDirectory %))
           (filter #(str/starts-with? (.getName %) prefix))
           (set))
      #{})))

(defn- registry [run-root]
  (json/read-str (slurp (io/file run-root "manifest/artifacts.json")) :key-fn keyword))

(defn- run-files [run-root]
  (set (map #(.getCanonicalPath %) (file-seq (io/file run-root)))))

(defn- root-relative? [run-root path]
  (let [p (java.nio.file.Paths/get path (make-array String 0))]
    (and (not (.isAbsolute p))
         (not (some #{".."} (iterator-seq (.iterator p))))
         (.isFile (io/file run-root path)))))

(defn- with-temp-root [f]
  (let [root (.toFile (java.nio.file.Files/createTempDirectory "structured-run-" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try (f root) (finally (delete-tree! root)))))

(defn- assert-contained-registry! [root]
  (let [r (registry root)
        entries (:artifacts r)
        ids (set (map :id entries))]
    (is (= "." (:root_dir r)))
    (is (seq entries))
    (is (every? #(root-relative? root (:path %)) entries))
    (is (every? #(not (re-find #"(^|/)(results|prf-runs|prf-artifacts|target/run)(/|$)" (:path %))) entries))
    (is (every? #(or (str/starts-with? % "forensic.")
                     (str/starts-with? % "evidence.")
                     (str/starts-with? % "input.")
                     (contains? #{"execution.replay-output"
                                  "execution.dag"
                                  "execution.pre-run-commitment"
                                  "manifest.run-enrichment"
                                  "manifest.run"
                                  "manifest.summary"
                                  "manifest.claimable-classification"
                                  "manifest.sensitivity-report"
                                  "manifest.diagnostic-summary"
                                  "summaries.trace"
                                  "summaries.metrics"
                                  "summaries.claimable"
                                  "summaries.mechanisms"
                                  "summaries.schema-map"
                                  "summaries.trace-plain"
                                  "state.world-final"}
                                %))
                ids))))

(deftest default-run-creates-a-contained-generated-root
  (let [before (generated-run-roots settlement-slug)
        result (shell/sh "bb" "run:scenario" settlement-scenario "-a")
        generated (vec (remove before (generated-run-roots settlement-slug)))]
    (try
      (is (zero? (:exit result)) (:err result))
      (is (= 1 (count generated)) "default invocation must create one run root")
      (when-let [root (first generated)]
        (assert-contained-registry! root)
        (is (.isFile (io/file root "scenarios" settlement-directory-slug "execution/replay-output.json"))))
      (finally
        (doseq [root generated]
          (delete-tree! root))))))

(deftest structured-run-is-contained-and-complete
  (with-temp-root
    (fn [root]
      (let [before (directory-snapshot)
            result (run-structured! settlement-scenario root)
            slug-root (io/file root "scenarios" settlement-directory-slug)
            execution (io/file slug-root "execution")
            enrichment (json/read-str (slurp (io/file root "manifest/run-enrichment.json")) :key-fn keyword)]
        (is (zero? (:exit result)) (:err result))
        (is (= before (directory-snapshot)) "structured runs must not contaminate legacy roots")
        (assert-contained-registry! root)
        (is (.isFile (io/file execution "execution-dag.json")))
        (is (.isFile (io/file execution "pre-run-commitment.json")))
        (is (.isFile (io/file slug-root "forensic/chain-cursor-final.json")))
        (is (.isFile (io/file slug-root "forensic/evidence-registry.json")))
        (let [finalization-path (io/file slug-root "forensic/finalizations/scenarios"
                                         settlement-directory-slug
                                         "evidence-finalization.json")
              finalization (json/read-str (slurp finalization-path) :key-fn keyword)]
          (is (.isFile finalization-path))
          (is (= "evidence-finalization.v2" (:schema-version finalization)))
          (is (= "scenario-chain-finalization" (:finalization-kind finalization))))
        (let [run-finalization-path (io/file root "evidence/finalizations/run/evidence-finalization.json")
              run-finalization (json/read-str (slurp run-finalization-path) :key-fn keyword)]
          (is (.isFile run-finalization-path))
          (is (= "run-evidence-finalization" (:finalization-kind run-finalization)))
          (is (= "exact" (get-in run-finalization [:verification :reconciliation :status])))
          (is (= 1 (get-in run-finalization [:execution :scenario-count])))
          (is (= 1 (get-in run-finalization [:execution :completed-scenario-count])))
          (is (= "verified" (get-in run-finalization [:bindings :evidence-dag :status])))
          (is (re-find #"^sha256:[0-9a-f]{64}$"
                       (get-in run-finalization [:bindings :evidence-dag :root]))))
        (is (re-find #"^evidence-chain:sha256:[0-9a-f]{64}$"
                     (get-in enrichment [:execution :chain-root-ref])))
        (is (= (str "scenarios/" settlement-directory-slug "/execution/execution-dag.json")
               (get-in enrichment [:execution :dag-path])))
        (is (= (str "scenarios/" settlement-directory-slug "/execution/pre-run-commitment.json")
               (get-in enrichment [:execution :pre-run-commitment-path])))
        (is (not (.exists (io/file slug-root "raw/replay-output.json"))))
        (is (not (.exists (io/file slug-root "test-run.json"))))
        (is (not (.exists (io/file slug-root "test-summary.json"))))))))

(deftest sequential-structured-runs-do-not-cross-contaminate
  (with-temp-root
    (fn [first-root]
      (with-temp-root
        (fn [second-root]
          (let [first-result (run-structured! pro-rata-scenario first-root)
                second-result (run-structured! settlement-scenario second-root)
                second-registry (registry second-root)
                second-paths (map :path (:artifacts second-registry))]
            (is (zero? (:exit first-result)) (:err first-result))
            (is (zero? (:exit second-result)) (:err second-result))
            (is (empty? (filter #(re-find #"allocation-result" %) second-paths)))
            (is (every? #(not (str/includes? % (.getCanonicalPath (io/file first-root)))) second-paths))))))))

(deftest reverse-sequential-structured-runs-do-not-cross-contaminate
  (with-temp-root
    (fn [first-root]
      (with-temp-root
        (fn [second-root]
          (let [first-result (run-structured! settlement-scenario first-root)
                second-result (run-structured! pro-rata-scenario second-root)
                first-paths (map :path (:artifacts (registry first-root)))]
            (is (zero? (:exit first-result)) (:err first-result))
            (is (zero? (:exit second-result)) (:err second-result))
            (is (empty? (filter #(re-find #"allocation-result" %) first-paths)))
            (is (every? #(not (str/includes? % (.getCanonicalPath (io/file second-root)))) first-paths))))))))

(deftest concurrent-structured-runs-do-not-cross-reference
  (with-temp-root
    (fn [left-root]
      (with-temp-root
        (fn [right-root]
          (let [left (future (run-structured! settlement-scenario left-root))
                right (future (run-structured! pro-rata-scenario right-root))
                left-result @left
                right-result @right
                left-paths (map :path (:artifacts (registry left-root)))
                right-paths (map :path (:artifacts (registry right-root)))]
            (is (zero? (:exit left-result)) (:err left-result))
            (is (zero? (:exit right-result)) (:err right-result))
            (is (every? #(not (str/includes? % (.getCanonicalPath (io/file right-root)))) left-paths))
            (is (every? #(not (str/includes? % (.getCanonicalPath (io/file left-root)))) right-paths))))))))
