(ns resolver-sim.benchmark.dag
  "Builds a compact evidence DAG from a benchmark evidence bundle."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [resolver-sim.graph.export :as graph]))

(defn artifact-directory [evidence-path]
  (str evidence-path ".graph"))

(defn- node [id label status data]
  {:node/id id :node/label label :node/short-id (subs id 0 (min 12 (count id)))
   :node/status status :node/data data})

(defn bundle->graph [bundle]
  (let [root-id (str "bundle:" (:evidence/hash bundle))
        benchmark-id (str "benchmark:" (get-in bundle [:benchmark :benchmark/id]))
        repo-id (str "repo:" (get-in bundle [:repo :repo :commit]))
        scenarios (->> (:results bundle)
                       (map #(select-keys % [:file :scenario/id :scenario/evidence-root :outcome]))
                       (group-by #(or (:scenario/evidence-root %) (:file %)))
                       (map (fn [[_ runs]]
                              (assoc (first runs) :scenario/run-count (count runs))))
                       (sort-by #(str (:file %))))
        claims (sort-by #(str (:claim/id %)) (:claim-results bundle))
        scenario-nodes (mapv (fn [s]
                               (let [id (str "scenario:" (or (:scenario/evidence-root s) (:file s)))]
                                 (node id (str "scenario " (or (:scenario/id s) (:file s)))
                                       (or (:outcome s) :unknown) s))) scenarios)
        claim-nodes (mapv (fn [c]
                            (let [id (str "claim:" (:claim/id c))]
                              (node id (str "claim " (:claim/id c))
                                    (or (:claim/outcome c) :unknown) c))) claims)
        nodes (vec (concat [(node root-id "evidence bundle" :pass
                                  {:evidence/hash (:evidence/hash bundle)})
                            (node benchmark-id "benchmark" :pass (:benchmark bundle))
                            (node repo-id "repository revision" :pass (:repo bundle))]
                           scenario-nodes claim-nodes))
        edges (vec (concat [{:edge/from benchmark-id :edge/to root-id :edge/label "defines"}
                            {:edge/from repo-id :edge/to root-id :edge/label "executed"}]
                           (map #(hash-map :edge/from (:node/id %) :edge/to root-id :edge/label "scenario result") scenario-nodes)
                           (map #(hash-map :edge/from (:node/id %) :edge/to root-id :edge/label "claim result") claim-nodes)))]
    {:nodes nodes :edges edges
     :summary {:node-count (count nodes) :edge-count (count edges)}}))

(defn- claim-coverage-graph [bundle]
  (let [claims (filter #(seq (:claim/evidence %)) (:claim-results bundle))
        claim-nodes (mapv (fn [claim]
                            (node (str "claim:" (:claim/id claim))
                                  (str "claim " (:claim/id claim))
                                  (or (:claim/outcome claim) :unknown)
                                  (select-keys claim [:claim/id :claim/outcome]))) claims)
        evidence-refs (->> claims (mapcat :claim/evidence) distinct)
        evidence-nodes (mapv (fn [e]
                               (let [id (str "evidence:" (if (map? e) (or (:scenario/evidence-root e) (:scenario/id e)) e))]
                                 (node id (str "evidence " (if (map? e) (or (:scenario/id e) (:scenario/evidence-root e)) e)) :pass e))) evidence-refs)
        edges (vec (mapcat (fn [claim]
                             (map (fn [e]
                                    {:edge/from (str "evidence:" (if (map? e) (or (:scenario/evidence-root e) (:scenario/id e)) e))
                                     :edge/to (str "claim:" (:claim/id claim))
                                     :edge/label "supports"})
                                  (:claim/evidence claim))) claims))]
    {:nodes (vec (concat claim-nodes evidence-nodes)) :edges edges
     :summary {:node-count (+ (count claim-nodes) (count evidence-nodes)) :edge-count (count edges)}}))

(defn- export-claim-coverage! [bundle out-dir]
  (let [projection (claim-coverage-graph bundle)]
    (when (seq (:edges projection))
      (graph/write-graph-artifacts! projection
                                    {:title "Explicit claim evidence coverage"
                                     :task/ref (str "claim-coverage/" (:evidence/hash bundle))
                                     :generated-at (str "evidence-root:" (:evidence/hash bundle))}
                                    (str out-dir "/claim-coverage")))))

(defn- optional-source-artifacts [evidence-path out-dir]
  (let [parent (.getParentFile (io/file evidence-path))
        execution-dag (io/file parent "execution-dag.json")
        evidence-nodes (io/file parent "evidence-nodes")]
    (cond-> {}
      (.isFile execution-dag)
      (assoc :execution-dag (graph/render-execution-dag! (.getPath execution-dag)
                                                         (str out-dir "/execution-dag")))
      (.isDirectory evidence-nodes)
      (assoc :evidence-nodes (graph/render-evidence-node-dag! (.getPath evidence-nodes)
                                                              (str out-dir "/evidence-nodes"))))))

(defn- sha256-file [file]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")]
    (with-open [in (java.io.FileInputStream. file)]
      (let [buffer (byte-array 8192)]
        (loop [n (.read in buffer)]
          (when (pos? n)
            (.update digest buffer 0 n)
            (recur (.read in buffer))))))
    (format "%064x" (java.math.BigInteger. 1 (.digest digest)))))

(defn- write-index! [out-dir evidence-hash result graphml-path optional]
  (let [out (io/file out-dir)
        base (.toPath out)
        files (->> (file-seq out)
                   (filter #(.isFile %))
                   (remove #(= "index.json" (.getName %)))
                   (sort-by #(.getPath %)))
        index {:index/version "benchmark-graph-index.v1"
               :source/evidence-hash evidence-hash
               :graphs (cond-> [{:id "benchmark-overview"
                                 :artifact-hash (get-in result [:artifact :artifact/hash])
                                 :files ["evidence-graph.svg" "evidence-graph.json" "graph-evidence.json"
                                         "evidence-graph.html" "evidence-graph-rf.html"]
                                 :links-to (vec (concat (when (:execution-dag optional) ["execution-dag"])
                                                        (when (:evidence-nodes optional) ["evidence-nodes"])
                                                        (when (:claim-coverage optional) ["claim-coverage"])))}]
                         graphml-path (update-in [0 :files] conj "evidence-graph.graphml"))
               :files (mapv (fn [file]
                              {:path (str (.relativize base (.toPath file)))
                               :sha256 (sha256-file file)}) files)}
        path (io/file out "index.json")]
    (spit path (json/write-str index {:key-fn name :indent true}))
    (.getPath path)))

(defn export! [evidence-path]
  (let [bundle (edn/read-string (slurp evidence-path))
        out-dir (artifact-directory evidence-path)
        projection (bundle->graph bundle)
        metadata {:title (str "Benchmark evidence DAG: " (get-in bundle [:benchmark :benchmark/id]))
                  :task/ref (str "benchmark/" (:evidence/hash bundle))
                  :generated-at (str "evidence-root:" (:evidence/hash bundle))}
        result (graph/write-graph-artifacts! projection metadata out-dir)
        graphml-path (try
                       (graph/write-graphml projection out-dir)
                       (catch Exception e
                         (println "GraphML export skipped:" (.getMessage e))
                         nil))
        optional (optional-source-artifacts evidence-path out-dir)
        claim-coverage (export-claim-coverage! bundle out-dir)
        optional (cond-> optional claim-coverage (assoc :claim-coverage claim-coverage))
        index-path (write-index! out-dir (:evidence/hash bundle) result graphml-path optional)
        readme (io/file out-dir "README.md")]
    (spit readme (str "# Benchmark evidence graph artifacts\n\n"
                      "- `evidence-graph.svg`: offline, static visualisation.\n"
                      "- `evidence-graph.graphml`: offline graph exchange file for yEd and similar tools.\n"
                      "- `index.json`: cross-graph registry with source evidence hash and SHA-256 hashes for generated files.\n"
                      "- `evidence-graph.json` and `graph-evidence.json`: machine-readable graph data and its hash-bound artifact.\n"
                      "- `evidence-graph.html` and `evidence-graph-rf.html`: interactive viewers that load browser dependencies from CDNs; use the SVG or GraphML for offline review.\n"))
    (println "Evidence DAG artifacts written to:" out-dir)
    (assoc result :graphml-path graphml-path :index-path index-path :readme-path (.getPath readme) :optional optional)))
