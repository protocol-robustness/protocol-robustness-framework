(ns resolver-sim.forensic.corpus-hash
  "Scenario corpus hashing.
   Given a suite keyword, resolves all scenario file paths and produces
   a corpus root hash.  Used by bb hash:corpus and pre-run commitment."
  (:import [java.security MessageDigest]))

(defn- sha256-file
  "Compute SHA-256 hex of a file's content."
  [path]
  (let [f (java.io.File. path)]
    (when (.isFile f)
      (let [digest (MessageDigest/getInstance "SHA-256")]
        (.update digest (java.nio.file.Files/readAllBytes (.toPath f)))
        (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest digest)))))))

(defn- resolve-suite-paths
  "Resolve scenario file paths for a suite keyword.
   Tries the suites registry; falls back to fixture suite files."
  [suite-key]
  (try
    (let [suites-ns (requiring-resolve 'resolver-sim.scenario.suites/suite-paths)
          paths (suites-ns suite-key)]
      (when (seq paths) (mapv str paths)))
    (catch Exception _ nil)))

(defn corpus-hash
  "Compute the scenario corpus hash for a suite keyword or file path list.
   Returns {:corpus/suite-key, :corpus/scenario-count, :corpus/scenarios, :corpus/root-hash}."
  [suite-key-or-paths]
  (let [paths (if (keyword? suite-key-or-paths)
                (resolve-suite-paths suite-key-or-paths)
                suite-key-or-paths)
        _ (when-not (seq paths)
            (throw (ex-info "No scenarios resolved" {:input suite-key-or-paths})))
        scenarios (mapv (fn [p]
                          (let [f (java.io.File. p)]
                            {:path (.getPath f)
                             :name (.getName f)
                             :size (.length f)
                             :sha256 (sha256-file (.getPath f))}))
                        (take 2000 paths))
        root-str (pr-str (sort-by :name scenarios))
        root-hash (let [digest (MessageDigest/getInstance "SHA-256")]
                    (.update digest (.getBytes root-str "UTF-8"))
                    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest digest))))]
    {:corpus/suite-key suite-key-or-paths
     :corpus/scenario-count (count scenarios)
     :corpus/scenarios scenarios
     :corpus/root-hash root-hash}))

(defn hash-corpus
  "CLI entry point: print corpus hash for a suite keyword.  Returns exit code."
  [suite-key-str]
  (try
    (let [suite-key (keyword suite-key-str)
          result (corpus-hash suite-key)]
      (println (pr-str (dissoc result :corpus/scenarios)))  ; omit detailed list
      0)
    (catch Exception e
      (println "ERROR:" (.getMessage e))
      1)))
