(ns resolver-sim.commands.scenario-inventory-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.commands.scenario-inventory :as inventory]))

(defn- temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
            "scenario-inventory-test-"
            (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- delete-tree! [path]
  (when (.exists (io/file path))
    (doseq [file (reverse (file-seq (io/file path)))]
      (io/delete-file file true))))

(defn- write! [root relative content]
  (let [file (io/file root relative)]
    (.mkdirs (.getParentFile file))
    (spit file content)
    file))

(defn- context [root]
  (let [slug "fixture-abc123"
        scenario-root (io/file root "scenarios" slug)]
    {:run/root (.getPath root)
     :run/id "run-inventory-test"
     :scenario/slug slug
     :scenario/root (.getPath scenario-root)}))

(deftest build-registers-nested-forensic-artifacts-and-dependencies
  (let [root (temp-dir)
        c (context root)
        prefix "scenarios/fixture-abc123/"]
    (try
      (write! root (str prefix "execution/replay-output.json") "{\"bundle/schema-version\":\"bundle-root.v1\"}")
      (write! root (str prefix "summaries/trace-summary.json") "{}")
      (write! root (str prefix "summaries/trace-plain.md") "# trace")
      (write! root (str prefix "summaries/schema-map.json") "{}")
      (write! root (str prefix "forensic/claims/result.json") "{}")
      (write! root (str prefix "forensic/attestations/result.json") "{}")
      (write! root (str prefix "forensic/evidence-nodes/node-abc.edn") "{:node-hash \"abc\"}")
      (let [registry (inventory/build! c)
            by-id (into {} (map (juxt :id identity) (:artifacts registry)))
            persisted (json/read-str (slurp (io/file root "manifest/artifacts.json")) :key-fn keyword)]
        (testing "all entries are root-relative and persisted atomically"
          (is (= (:artifacts registry) (:artifacts persisted)))
          (is (every? #(and (not (.startsWith ^String (:path %) "/"))
                            (not (re-find #"(?:^|/)\.\.(?:/|$)" (:path %))))
                      (:artifacts registry))))
        (testing "known artifact metadata and dependency edges are retained"
          (is (= "DIAGNOSTIC" (:importance (by-id "execution.replay-output"))))
          (is (= "CORE" (:importance (by-id "summaries.trace"))))
          (is (= [{:id "execution.replay-output"}]
                 (:dependencies (by-id "summaries.trace"))))
          (is (= [{:id "summaries.trace"}]
                 (:dependencies (by-id "summaries.trace-plain")))))
        (testing "nested forensic files retain their complete relative path and unique ID"
          (is (= "scenarios/fixture-abc123/forensic/claims/result.json"
                 (:path (by-id "forensic.claims.result.json"))))
          (is (= "scenarios/fixture-abc123/forensic/attestations/result.json"
                 (:path (by-id "forensic.attestations.result.json")))))
        (testing "persisted evidence nodes are core package artifacts"
          (let [node-entry (by-id "forensic.evidence.nodes.node.abc.edn")]
            (is (= "evidence.node" (:kind node-entry)))
            (is (= "evidence-node.v1" (:schema_version node-entry)))
            (is (= "CORE" (:importance node-entry))))))
      (finally (delete-tree! root)))))

(deftest build-registers-snapshotted-scenario-input-by-content-hash
  (let [root (temp-dir)
        c (context root)
        content "{:scenario/id :snapshot-fixture}"
        expected-hash "381ef2fc98be033a1c6ba3b67302b2889e8627732dc30f72209998430e0f023a"]
    (try
      (write! root "inputs/scenarios/381ef2fc98be-fixture.edn" content)
      (let [registry (inventory/build! c)
            entry (first (filter #(= (str "input.scenario." expected-hash)
                                      (:id %))
                                 (:artifacts registry)))]
        (is (= (str "input.scenario." expected-hash) (:id entry)))
        (is (= "input.scenario" (:kind entry)))
        (is (= "CORE" (:importance entry)))
        (is (= "inputs/scenarios/381ef2fc98be-fixture.edn" (:path entry)))
        (is (= expected-hash (:sha256 entry)))
        (is (= (count (.getBytes content "UTF-8")) (:bytes entry))))
      (finally (delete-tree! root)))))

(deftest build-rejects-forensic-id-collisions-without-writing-a-registry
  (let [root (temp-dir)
        c (context root)
        prefix "scenarios/fixture-abc123/forensic/"]
    (try
      ;; Both paths normalize to forensic.a.b.json. Reject rather than silently
      ;; overwriting or producing an ambiguous artifact identity.
      (write! root (str prefix "a/b.json") "{}")
      (write! root (str prefix "a-b.json") "{}")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"duplicate artifact IDs"
                            (inventory/build! c)))
      (is (not (.exists (io/file root "manifest/artifacts.json"))))
      (finally (delete-tree! root)))))
