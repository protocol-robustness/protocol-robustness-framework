(ns resolver-sim.commands.scenario-cli-parity-test
  "End-to-end parity contract for the public BB and JAR-dispatch scenario paths."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string]
            [clojure.test :refer [deftest is]]))

(def ^:private scenario "scenarios/edn/S-DR-084-evidence-after-settlement-rejected.edn")

(defn- delete-tree! [path]
  (let [file (io/file path)]
    (when (.exists file)
      (doseq [entry (reverse (file-seq file))]
        (io/delete-file entry true)))))

(defn- temp-root [prefix]
  (.getPath (.toFile (java.nio.file.Files/createTempDirectory prefix
                                                              (make-array java.nio.file.attribute.FileAttribute 0)))))

(defn- bundle-contract [root]
  (let [root-file (io/file root)
        run (json/read-str (slurp (io/file root "manifest/run.json")))
        registry (json/read-str (slurp (io/file root "manifest/artifacts.json")) :key-fn keyword)
        completion (json/read-str (slurp (io/file root "completion.json")))
        paths (map :path (:artifacts registry))
        ;; Forensic artifacts are recursively inventory-derived from the
        ;; evidence pipeline. Their IDs are path-derived and may grow as new
        ;; finalization artifacts are persisted; stable scenario contract IDs
        ;; remain the non-forensic registry entries.
        stable-ids (->> (:artifacts registry)
                        (map :id)
                        (remove #(clojure.string/starts-with? % "forensic."))
                        set)
        input (get run "input")
        input-artifacts (filter #(= "input.scenario" (:kind %)) (:artifacts registry))
        scenario-dir (first (filter #(.isDirectory %) (.listFiles (io/file root "scenarios"))))]
    {:completion? (.isFile (io/file root-file "completion.json"))
     :running? (.exists (io/file root-file ".run-state"))
     :completion-status (get completion "status")
     :completion-outcome (get completion "outcome")
     :root-dir (:root_dir registry)
     :artifact-ids stable-ids
     :input input
     :input-artifacts (mapv #(select-keys % [:id :kind :path :sha256 :bytes]) input-artifacts)
     :all-relative? (every? (fn [path]
                               (let [p (java.nio.file.Paths/get path (make-array String 0))]
                                 (and (not (.isAbsolute p))
                                      (not (some #{".."} (iterator-seq (.iterator p)))))))
                             paths)
     :excluded? (boolean (some #{"manifest/artifacts.json"
                                 "manifest/artifact-registry-validation.json"
                                 "completion.json"
                                 ".run-state"}
                              paths))
     :scenario-dir? (boolean scenario-dir)}))

(deftest bb-and-jar-dispatch-produce-equivalent-completed-bundles
  (let [bb-root (temp-root "scenario-bb-")
        jar-root (temp-root "scenario-jar-")
        jar-expression (str "(require (quote resolver-sim.cli.dispatch)) "
                            "(System/exit (resolver-sim.cli.dispatch/run "
                            "[\"run-scenario\" \"" scenario "\" \"--run-root\" \"" jar-root "\"]))")]
    (try
      (let [bb-result (shell/sh "bb" "run:scenario" scenario "--run-root" bb-root)
            jar-result (shell/sh "clojure" "-M:with-sew" "-e" jar-expression)
            bb-contract (bundle-contract bb-root)
            jar-contract (bundle-contract jar-root)]
        (is (zero? (:exit bb-result)) (:err bb-result))
        (is (zero? (:exit jar-result)) (:err jar-result))
        (is (= bb-contract jar-contract))
        (is (= "completed" (:completion-status bb-contract)))
        (is (= "pass" (:completion-outcome bb-contract)))
        (is (true? (:completion? bb-contract)))
        (is (false? (:running? bb-contract)))
        (is (= "." (:root-dir bb-contract)))
        (is (true? (:all-relative? bb-contract)))
        (is (false? (:excluded? bb-contract)))
        (is (true? (:scenario-dir? bb-contract)))
        (is (re-matches #"[0-9a-f]{64}" (get-in bb-contract [:input "sha256"])))
        (is (pos? (get-in bb-contract [:input "bytes"])))
        (is (= (get-in bb-contract [:input "sha256"])
               (some-> bb-contract :input-artifacts first :sha256)))
        (is (= (get-in bb-contract [:input "snapshot"])
               (some-> bb-contract :input-artifacts first :path))))
      (finally
        (delete-tree! bb-root)
        (delete-tree! jar-root)))))
