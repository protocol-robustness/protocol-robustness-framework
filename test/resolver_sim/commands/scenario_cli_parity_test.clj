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
        registry (json/read-str (slurp (io/file root "manifest/artifacts.json")) :key-fn keyword)
        completion (json/read-str (slurp (io/file root "completion.json")))
        paths (map :path (:artifacts registry))
        stable-ids (->> (:artifacts registry)
                        (map :id)
                        (remove #(or (clojure.string/starts-with? % "forensic.claims.")
                                    (clojure.string/starts-with? % "forensic.attestations.")
                                    (clojure.string/starts-with? % "forensic.evidence-nodes.")))
                        set)
        scenario-dir (first (filter #(.isDirectory %) (.listFiles (io/file root "scenarios"))))]
    {:completion? (.isFile (io/file root-file "completion.json"))
     :running? (.exists (io/file root-file ".run-state"))
     :completion-status (get completion "status")
     :completion-outcome (get completion "outcome")
     :root-dir (:root_dir registry)
     :artifact-ids stable-ids
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
        (is (true? (:scenario-dir? bb-contract))))
      (finally
        (delete-tree! bb-root)
        (delete-tree! jar-root)))))
