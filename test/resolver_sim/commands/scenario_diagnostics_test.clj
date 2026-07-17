(ns resolver-sim.commands.scenario-diagnostics-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [resolver-sim.commands.scenario-diagnostics :as diagnostics]))

(defn- temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
            "scenario-diagnostics-"
            (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- write-trace! [root steps]
  (let [summary-dir (io/file root "summaries")]
    (.mkdirs summary-dir)
    (spit (io/file summary-dir "trace-summary.json")
          (json/write-str {"scenario_id" "diagnostic-test"
                           "steps" steps}))))

(defn- diagnostic-for [steps]
  (let [root (temp-dir)
        manifest-dir (io/file root "manifest")]
    (.mkdirs manifest-dir)
    (write-trace! root steps)
    (diagnostics/write! {:scenario/root (.getPath root)
                         :manifest/dir (.getPath manifest-dir)}
                        {:exit-code 1})))

(deftest semantic-failure-outranks-earlier-rejection
  (let [diagnostic (diagnostic-for
                    [{"seq" 6 "time" 1150 "actor" "resolver-b"
                      "action" "execute_resolution" "result" "rejected"}
                     {"seq" 8 "time" 1250 "actor" "resolver-b"
                      "action" "submit_evidence" "result" "invariant-violated"}])]
    (is (= "invariant-violated" (get-in diagnostic ["primary_diagnostic" "classification"])))
    (is (= 8 (get-in diagnostic ["primary_diagnostic" "event" "seq"])))
    (is (= "completed" (get diagnostic "execution_status")))
    (is (= "fail" (get diagnostic "semantic_outcome")))))

(deftest rejection-remains-the-diagnostic-fallback
  (let [diagnostic (diagnostic-for
                    [{"seq" 2 "time" 1100 "actor" "attacker"
                      "action" "execute_resolution" "result" "rejected"}])]
    (is (= "rejected" (get-in diagnostic ["primary_diagnostic" "classification"])))
    (is (= 2 (get-in diagnostic ["primary_diagnostic" "event" "seq"])))) )
