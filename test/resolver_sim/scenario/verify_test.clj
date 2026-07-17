(ns resolver-sim.scenario.verify-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [resolver-sim.commands.scenario :as scenario]
            [resolver-sim.scenario.verify :as verify]))

(defn- temp-root []
  (.toFile (java.nio.file.Files/createTempDirectory
            "scenario-verify-"
            (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- delete-tree! [root]
  (doseq [file (reverse (file-seq root))]
    (io/delete-file file true)))

(defn- first-event-evidence [root]
  (first (filter #(and (.isFile %)
                       (= "event-evidence" (.getName (.getParentFile %)))
                       (.endsWith (.getName %) ".json"))
                 (file-seq root))))

(deftest verifies-and-rejects-tampering-in-a-canonical-scenario-bundle
  (let [root (temp-root)]
    (try
      (let [run-result (scenario/run-argv
                        ["scenarios/edn/S-DR-084-evidence-after-settlement-rejected.edn"
                         "--run-root" (.getPath root)])]
        (is (= :completed (:command/status run-result)))
        (let [verified (verify/verify! root)
              integrity (io/file root "manifest/canonical-integrity.json")
              deferred (io/file root "manifest/forensic-claims-status.json")]
          (is (= "passed" (get verified "status")))
          (is (.isFile integrity))
          (is (.isFile deferred))
          (is (true? (get-in verified ["checks" "canonical-integrity"])))
          (is (true? (get-in verified ["checks" "assurance-artifacts-registered"]))))
        (spit (first-event-evidence root) "{}")
        (let [result (verify/verify! root)]
          (is (= "failed" (get result "status")))
          (is (false? (get-in result ["checks" "terminal-artifacts-readable"]))))
        )
      (finally
        (delete-tree! root)))))
