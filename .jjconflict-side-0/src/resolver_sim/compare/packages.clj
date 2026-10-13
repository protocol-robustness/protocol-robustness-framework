(ns resolver-sim.compare.packages
  "Inspection of completed run packages for the comparison and inspection
   CLI commands: root hashes, result roots, declared dependencies, and
   semantic equivalence.

   These helpers are deliberately best-effort and tolerant: they report the
   structure that is present rather than requiring a fully verified package.
   Full package verification remains the job of verify-scenario /
   verify-benchmark and run/comparison."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [resolver-sim.community.result :as community-result]
            [resolver-sim.io.paths :as paths]
            [resolver-sim.run.package-index :as package-index]))

(def ^:const schema-version "package-inspection.v1")

(defn- slurp-json
  "Read a JSON file with keyword keys; nil when missing or unparseable."
  [file]
  (when (and file (.isFile (io/file file)))
    (try (json/read-str (slurp file) :key-fn keyword) (catch Exception _ nil))))

(defn- slurp-json-raw
  "Read a JSON file with string keys (completion.json convention); nil when
   missing or unparseable."
  [file]
  (when (and file (.isFile (io/file file)))
    (try (json/read-str (slurp file)) (catch Exception _ nil))))

(defn- slurp-json-or-edn
  "Read an artifact as EDN (.edn) or JSON; nil when missing or unparseable."
  [file]
  (when (and file (.isFile (io/file file)))
    (try
      (if (str/ends-with? (.getName (io/file file)) ".edn")
        (edn/read-string (slurp file))
        (json/read-str (slurp file) :key-fn keyword))
      (catch Exception _ nil))))

(defn read-completion
  "Parse completion.json (string keys) or nil."
  [run-root]
  (slurp-json-raw (io/file run-root paths/completion)))

(defn read-run-index
  "Resolve the run package index. Returns {:index <map> :path <file>} or
   {:reason <map>}."
  [run-root]
  (package-index/read-index run-root))

(defn package-roots
  "Structural root hashes that seal a completed run package: the package
   index hash, the bundle root hash, and the completion seal binding.

   Returns {:valid? true ...} on success, or {:valid? false :reason <map>}."
  [run-root]
  (let [index-result (package-index/read-index run-root)
        completion   (read-completion run-root)]
    (cond
      (:reason index-result)
      (assoc index-result :run-root run-root :valid? false)

      :else
      (let [index (:index index-result)]
        {:schema-version       schema-version
         :run-root             run-root
         :valid?               true
         :run-type             (:run/type index)
         :run-id               (:run/id index)
         :scenario-id          (:scenario/id index)
         :package-index-path   (str paths/run-package-index)
         :package-index-hash   (:run-package/hash index)
         :bundle-root-hash     (:bundle/root-hash index)
         :completion-path      (str paths/completion)
         :completion-seal      (when completion
                                 {:sha256 (get completion "run_package_index_sha256")
                                  :bytes  (get completion "run_package_index_bytes")})}))))

(defn scenario-results
  "Collect :run/scenario-results entries from every scenarios/*/replay-output.json
   beneath the run root, in deterministic path order."
  [run-root]
  (let [scenarios-dir (io/file run-root "scenarios")]
    (if-not (.isDirectory scenarios-dir)
      []
      (->> (file-seq scenarios-dir)
           (filter #(.isFile %))
           (filter #(= "replay-output.json" (.getName %)))
           (sort-by #(.getPath %))
           (mapcat (fn [f]
                     (let [data    (try (json/read-str (slurp f) :key-fn keyword)
                                        (catch Exception _ nil))
                           results (when data (:run/scenario-results data))]
                       (if (sequential? results) results []))))
           vec))))

(defn stable-result
  "Project a run's realized scenario results through the community stable
   projection. Returns the community-result report {:stable/hash ...}."
  [run-root]
  (community-result/project-stable-result {:results (scenario-results run-root)}))

(defn result-roots
  "Roots that identify a run's realized result: the bundle root hash, the
   stable-result hash, and the per-scenario evidence roots.

   Returns {:valid? true ...} on success, or {:valid? false :reason <map>}."
  [run-root]
  (let [index-result (package-index/read-index run-root)
        results      (scenario-results run-root)
        stable       (stable-result run-root)]
    (cond
      (:reason index-result)
      (assoc index-result :run-root run-root :valid? false)

      :else
      (let [index (:index index-result)]
        {:schema-version           schema-version
         :run-root                 run-root
         :valid?                   true
         :run-id                   (:run/id index)
         :bundle-root-hash         (:bundle/root-hash index)
         :stable-result-hash       (:stable/hash stable)
         :stable-comparison-policy (get-in stable [:stable/projection :comparison-policy])
         :scenario-count           (count results)
         :scenario-evidence-roots  (vec (keep :scenario/evidence-root results))}))))

(defn- artifact-ref
  "Resolve an artifact's :ref from a package index artifacts map."
  [artifacts artifact-id]
  (get-in artifacts [artifact-id :ref]))

(defn declared-dependencies
  "Best-effort declared dependency surface of a run package: package index
   artifacts, declared evidence hashes, scenario finalizations, execution
   DAG edges, and benchmark bindings.

   Returns {:valid? true ...} on success, or {:valid? false :reason <map>}."
  [run-root]
  (let [index-result (package-index/read-index run-root)]
    (cond
      (:reason index-result)
      (assoc index-result :run-root run-root :valid? false)

      :else
      (let [index       (:index index-result)
            artifacts   (:artifacts index)
            run-final   (slurp-json (when-let [ref (artifact-ref artifacts :run-finalization)]
                                      (io/file run-root ref)))
            dag         (slurp-json (when-let [ref (artifact-ref artifacts :execution-dag)]
                                      (io/file run-root ref)))
            benchmark   (slurp-json-or-edn (when-let [ref (artifact-ref artifacts :benchmark-definition)]
                                             (io/file run-root ref)))
            scenario-fs (get-in run-final [:evidence :scenario-finalizations])]
        {:schema-version                    schema-version
         :run-root                          run-root
         :valid?                            true
         :run-type                          (:run/type index)
         :package/artifacts                 (vec (sort (keys artifacts)))
         :package/artifact-paths            (vec (sort (keep :ref (vals artifacts))))
         :package/index-hash                (:run-package/hash index)
         :evidence/declared-evidence-hashes (vec (sort (get-in run-final [:evidence :declared-evidence-hashes])))
         :evidence/scenario-finalization-hashes (vec (sort (keep #(get-in % [:finalization :sha256]) scenario-fs)))
         :evidence/scenario-ids             (vec (sort (keep :scenario-id scenario-fs)))
         :dag/root-hash                     (:dag/root-hash dag)
         :dag/edges                         (vec (sort-by (juxt :edge/from :edge/to) (:edges dag)))
         :dag/node-source-hashes            (vec (sort (keep #(get-in % [:node/input-hashes :scenario/source-hash]) (:nodes dag))))
         :benchmark/scenario-suite          (when benchmark (:benchmark/scenario-suite benchmark))
         :benchmark/claims                  (vec (sort (keep :claim/id (:benchmark/claims benchmark))))
         :benchmark/concepts                (vec (sort (:benchmark/concepts benchmark)))
         :benchmark/property-types          (vec (sort (:benchmark/property-types benchmark)))}))))

(defn- semantic-outcome
  "Verdict-policy semantic outcome of a run root, or nil."
  [run-root]
  (let [policy (slurp-json (io/file run-root "manifest/verdict-policy.json"))]
    (get-in policy [:verdict :semantic_outcome])))

(defn semantic-equivalent
  "Compare two run roots at the result level using the community stable
   projection. Two runs are semantically equivalent when both realized a
   non-empty result set with identical stable-result hashes and, where both
   declare a verdict-policy semantic outcome, that outcome matches.

   Returns a report with :equivalent? and the contributing hashes."
  [root-a root-b]
  (let [stable-a    (stable-result root-a)
        stable-b    (stable-result root-b)
        outcome-a   (semantic-outcome root-a)
        outcome-b   (semantic-outcome root-b)
        left-count  (count (scenario-results root-a))
        right-count (count (scenario-results root-b))
        stable-match?  (= (:stable/hash stable-a) (:stable/hash stable-b))
        outcome-match? (or (nil? outcome-a) (nil? outcome-b)
                           (= outcome-a outcome-b))]
    {:schema-version       "semantic-equivalence.v1"
     :left-stable-hash     (:stable/hash stable-a)
     :right-stable-hash    (:stable/hash stable-b)
     :left-semantic-outcome  outcome-a
     :right-semantic-outcome outcome-b
     :left-scenario-count    left-count
     :right-scenario-count   right-count
     :stable-equivalent?     stable-match?
     :outcome-equivalent?    outcome-match?
     :equivalent?            (and (pos? left-count)
                                  (pos? right-count)
                                  stable-match?
                                  outcome-match?)}))
