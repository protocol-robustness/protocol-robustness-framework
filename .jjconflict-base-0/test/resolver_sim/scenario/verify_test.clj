(ns resolver-sim.scenario.verify-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
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

(defn- write-integrity! [root integrity-map]
  (let [integrity-file (io/file root "manifest/canonical-integrity.json")]
    (io/make-parents integrity-file)
    (spit integrity-file (json/write-str integrity-map))))

(defn- read-integrity [root]
  (json/read-str (slurp (io/file root "manifest/canonical-integrity.json"))
                 :key-fn keyword))

(defn- run-scenario! [root]
  (scenario/run-argv
   ["scenarios/edn/S-DR-084-evidence-after-settlement-rejected.edn"
    "--run-root" (.getPath root)]))

(defn- provenance-check [root field]
  (get-in (verify/verify! root)
          ["canonical-integrity-checks" field]))

(defn- overall-integrity [root]
  (get-in (verify/verify! root) ["checks" "canonical-integrity"]))

(deftest partial-fill-replay-witness-reconciles-with-its-projection
  (let [root (temp-root)]
    (try
      (let [run-result (scenario/run-argv
                        ["scenarios/edn/Y06_multi-party-pro-rata-shortfall.edn"
                         "--run-root" (.getPath root)])
            verified (verify/verify! root)]
        (is (= :completed (:command/status run-result)))
        (is (= "passed" (get verified "status")))
        (is (true? (get-in verified ["checks" "partial-fill-artifacts"]))))
      (finally (delete-tree! root)))))

(deftest verifies-and-rejects-tampering-in-a-canonical-scenario-bundle
  (let [root (temp-root)]
    (try
      (let [run-result (scenario/run-argv
                        ["scenarios/edn/S-DR-084-evidence-after-settlement-rejected.edn"
                         "--run-root" (.getPath root)])]
        (is (= :completed (:command/status run-result)))
        (let [verified (verify/verify! root)
              integrity (io/file root "manifest/canonical-integrity.json")
              deferred (io/file root "manifest/forensic-claims-status.json")
              verdict-policy (io/file root "manifest/verdict-policy.json")]
          (is (= "passed" (get verified "status")))
          (is (.isFile integrity))
          (is (.isFile deferred))
          (is (.isFile verdict-policy))
          (is (true? (get-in verified ["checks" "canonical-integrity"])))
          (is (true? (get-in verified ["checks" "verdict-policy"])))
          (is (true? (get-in verified ["checks" "assurance-artifacts-registered"])))
            ;; The policy self-commitment is an independent verifier gate; a
            ;; changed policy cannot be relabelled as the policy that ran.
          (spit verdict-policy "{}")
          (let [tampered (verify/verify! root)]
            (is (= "failed" (get tampered "status")))
            (is (false? (get-in tampered ["checks" "verdict-policy"])))))
        (spit (first-event-evidence root) "{}")
        (let [result (verify/verify! root)]
          (is (= "failed" (get result "status")))
          (is (false? (get-in result ["checks" "terminal-artifacts-readable"])))))
      (finally
        (delete-tree! root)))))

(defn- run-positive-negative-mutations [root]
  (is (= :completed (:command/status (run-scenario! root))))
  (testing "positive: fresh scenario passes provenance checks"
    (is (true? (provenance-check root "canonical-integrity-creation-provenance"))
        "creation-provenance should be valid on a fresh run")
    (is (true? (provenance-check root "canonical-integrity-source-creation"))
        "source-creation should be valid on a fresh run")
    (is (true? (overall-integrity root))
        "canonical-integrity overall should pass"))
  (testing "negative: delete creation_provenance_hash"
    (let [ci (read-integrity root)]
      (write-integrity! root (dissoc ci :creation_provenance_hash))
      (is (false? (provenance-check root "canonical-integrity-creation-provenance"))
          "deletion of commitment hash must fail provenance check")
      (is (false? (overall-integrity root))
          "overall canonical-integrity must fail")))
  (testing "negative: replace provenance without updating hash"
    (let [ci (read-integrity root)]
      (write-integrity! root (assoc ci :creation_provenance "out-of-band"))
      (is (false? (provenance-check root "canonical-integrity-creation-provenance"))
          "provenance changed without hash update must fail")
      (is (false? (overall-integrity root))
          "overall canonical-integrity must fail")))
  (testing "negative: unsupported provenance value"
    (let [ci (read-integrity root)]
      (write-integrity! root (assoc ci :creation_provenance "unknown"))
      (is (false? (provenance-check root "canonical-integrity-creation-provenance"))
          "unsupported provenance enum must fail")
      (is (false? (overall-integrity root))
          "overall canonical-integrity must fail")))
  (testing "negative: delete source_creation_hash"
    (let [ci (read-integrity root)]
      (write-integrity! root (dissoc ci :source_creation_hash))
      (is (false? (provenance-check root "canonical-integrity-source-creation"))
          "deletion of source_creation_hash must fail source-creation check")
      (is (false? (overall-integrity root))
          "overall canonical-integrity must fail")))
  (testing "negative: replace source_creation without updating hash"
    (let [ci (read-integrity root)]
      (write-integrity! root (assoc ci :source_creation "out-of-band"))
      (is (false? (provenance-check root "canonical-integrity-source-creation"))
          "source_creation changed without hash update must fail")
      (is (false? (overall-integrity root))
          "overall canonical-integrity must fail"))))

(deftest scenario-creation-provenance-commitment-mutations
  (let [root (temp-root)]
    (try
      (run-positive-negative-mutations root)
      (finally
        (delete-tree! root)))))

(deftest scenario-out-of-band-provenance-is-rejected-when-evidence-is-in-band
  (let [root (temp-root)]
    (try
      (let [run-result (run-scenario! root)]
        (is (= :completed (:command/status run-result)))
        (let [ci (read-integrity root)]
          (write-integrity! root (assoc ci
                                        :creation_provenance "out-of-band"
                                        :source_creation "out-of-band"))
          (let [verified (verify/verify! root)]
            (is (false? (get-in verified ["canonical-integrity-checks"
                                          "canonical-integrity-creation-provenance"]))
                "evidence is in-band but integrity claims out-of-band")
            (is (false? (get-in verified ["canonical-integrity-checks"
                                          "canonical-integrity-source-creation"]))
                "source-creation evidence is in-band but integrity claims out-of-band"))))
      (finally
        (delete-tree! root)))))
