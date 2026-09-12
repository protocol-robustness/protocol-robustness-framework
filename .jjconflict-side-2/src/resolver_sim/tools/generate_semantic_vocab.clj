(ns resolver-sim.tools.generate-semantic-vocab
  (:require [clojure.string :as str]
            [resolver-sim.definitions.registry :as defs]
            [resolver-sim.config.paths :as paths]))

(def vocab-path (str (paths/docs-dir) "/overview/SEMANTIC_VOCAB.md"))
(def registry-edn-path (str (paths/docs-dir) "/overview/SEMANTIC_REGISTRY.edn"))

(defn- ensure-out-dir! []
  (.mkdirs (java.io.File. (str (paths/docs-dir) "/overview"))))

(defn- generate! []
  (ensure-out-dir!)
  (spit vocab-path (defs/vocab-markdown))
  (spit registry-edn-path (defs/definitions-canonical-edn))
  {:vocab-path vocab-path
   :registry-edn-path registry-edn-path})

(defn- current-file [path]
  (when (.exists (java.io.File. path))
    (slurp path)))

(defn- drift-report []
  (let [expected-vocab (defs/vocab-markdown)
        expected-edn (defs/definitions-canonical-edn)
        actual-vocab (current-file vocab-path)
        actual-edn (current-file registry-edn-path)]
    {:vocab-drift? (not= expected-vocab actual-vocab)
     :edn-drift? (not= expected-edn actual-edn)}))

(defn -main [& _]
  (let [args (set (map str/lower-case *command-line-args*))]
    (if (contains? args "--check")
      (let [{:keys [vocab-drift? edn-drift?]} (drift-report)]
        (if (or vocab-drift? edn-drift?)
          (do
            (println "Semantic artifact drift detected.")
            (println "Run: clojure -M:test -m resolver-sim.tools.generate-semantic-vocab")
            (System/exit 1))
          (println "Semantic artifacts are up to date.")))
      (let [{:keys [vocab-path registry-edn-path]} (generate!)]
        (println "Wrote semantic vocab:" vocab-path)
        (println "Wrote semantic registry snapshot:" registry-edn-path)))))
