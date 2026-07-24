(ns resolver-sim.commands.scenario-manifest-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [resolver-sim.commands.scenario-manifest :as manifest]))

(defn- delete-tree! [path] (when (.exists (io/file path)) (doseq [f (reverse (file-seq (io/file path)))] (io/delete-file f true))))
(deftest writes-enriched-manifest-with-propagated-run-id
  (let [root (.toFile (java.nio.file.Files/createTempDirectory "manifest-" (make-array java.nio.file.attribute.FileAttribute 0)))
        dir (io/file root "manifest")]
    (try
      (.mkdirs dir)
      (spit (io/file dir "run-enrichment.json") "{\"execution\":{\"dag-path\":\"scenarios/s/execution/execution-dag.json\"}}")
      (manifest/write! {:manifest/dir (.getPath dir) :run/id "run-1" :scenario/ref "scenarios/S01.edn"}
                       {:exit-code 0 :duration-ms 12})
      (let [run (json/read-str (slurp (io/file dir "run.json")))
            summary (json/read-str (slurp (io/file dir "summary.json")))]
        (is (= "run-1" (get-in run ["run" "id"])))
        (is (= "complete" (get-in run ["run" "status"])))
        (is (= "scenarios/s/execution/execution-dag.json" (get-in run ["execution" "dag-path"])))
        (is (= "pass" (get-in summary ["run" "overall_status"])))
        (is (= "not-declared" (get-in summary ["value_at_risk" "status"])))
        (is (= "input-snapshot-unavailable" (get-in summary ["value_at_risk_overview" "status"])))
        (is (.isFile (io/file dir "claimable-classification.json"))))
      (finally (delete-tree! root)))))

(deftest value-at-risk-is-derived-from-snapshot-and-terminal-world-only
  (let [root (.toFile (java.nio.file.Files/createTempDirectory "value-at-risk-" (make-array java.nio.file.attribute.FileAttribute 0)))
        snapshot (io/file root "scenario.edn")]
    (try
      (spit snapshot
            "{:events [{:action \"create_escrow\" :params {:token \"USDC\" :amount 100}}\n           {:action :create_escrow :params {:token \"USDC\" :amount 250}}\n           {:action \"create_escrow\" :params {:token \"DAI\" :amount 40}}\n           {:action \"raise_dispute\" :params {:amount 999}}]}")
      (let [execution {:input/provenance {:input/snapshot (.getPath snapshot)
                                          :input/snapshot-relative "inputs/scenarios/scenario.edn"}
                       :run-result {:results [{:world {:total-held {"USDC" 75 "DAI" 40}}}]}}
            projection (manifest/value-at-risk-summary execution)]
        (is (= "available" (get projection "status")))
        (is (= [{"unit" "DAI" "amount" 40}
                {"unit" "USDC" "amount" 350}]
               (get-in projection ["declared_protected_amount" "by_unit"])))
        (is (= [{"unit" "DAI" "amount" 40}
                {"unit" "USDC" "amount" 75}]
               (get-in projection ["custody" "after" "by_unit"])))
        (is (= "not-declared-by-scenario" (get-in projection ["exposure" "expected_loss" "status"])))
        (is (= "not-derived" (get-in projection ["exposure" "observed_loss" "status"])))
        (is (= "inputs/scenarios/scenario.edn"
               (get-in projection ["source_artifacts" 0 "ref"]))))
      (finally (delete-tree! root)))))
