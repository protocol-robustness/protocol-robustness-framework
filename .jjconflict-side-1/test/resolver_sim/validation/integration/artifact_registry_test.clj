(ns resolver-sim.validation.integration.artifact-registry-test
  (:require [clojure.data.json :as json]
            [clojure.java.shell :as sh]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.validation.integration.artifact-registry :as integ]))

;; ── fixtures ─────────────────────────────────────────────────────────────────

(def all-pass-registry
  "A minimal registry where every artifact's dependencies are satisfied."
  {:run-id "test-run-1"
   :artifacts [{:id "test-run"
                :schema_version "test-run.v1"
                :verifies_against []}
               {:id "test-summary"
                :schema_version "test-summary.v2"
                :verifies_against ["test-run.v1"]}
               {:id "claimable-classification"
                :schema_version "claimable-classification.v2"
                :verifies_against ["test-run.v1"]}]})

(def broken-registry
  "A registry with a dangling dependency: missing-schema.v1 is required but not provided."
  {:run-id "test-run-2"
   :artifacts [{:id "test-run"
                :schema_version "test-run.v1"
                :verifies_against []}
               {:id "test-summary"
                :schema_version "test-summary.v2"
                :verifies_against ["test-run.v1" "missing-schema.v1"]}]})

(def realistic-registry
  "A fixture matching the actual emitted test-artifacts.json shape with full
   artifact fields: id, kind, path, schema_version, contract_version, producer,
   verifies_against, dependencies, input_versions, sha256, bytes, mtime_utc."
  {:schema_version "test-artifacts.v1.2"
   :contract_version "evidence-contract.v1"
   :run_id "20260614-172333"
   :generated_at "2026-06-14T16:26:22.707809+00:00"
   :generator {:name "artifact-registry-emitter" :version "v1.2"}
   :root_dir "results/test-artifacts"
   :run_manifest {:path "results/test-artifacts/test-run.json"
                  :schema_version "test-run.v1"
                  :sha256 "ce747ce13bc217af7f1953104b4f0a828f815f8a3fc91bab42e6f0ff27fd0b44"
                  :bytes 1873
                  :mtime_utc "2026-06-14T16:26:22.706375+00:00"}
   :artifacts [{:id "test-run"
                :kind "run-manifest"
                :path "results/test-artifacts/test-run.json"
                :schema_version "test-run.v1"
                :contract_version "evidence-contract.v1"
                :producer "test-run-emitter.v1"
                :verifies_against []
                :dependencies []
                :input_versions {}
                :sha256 "ce747ce13bc217af7f1953104b4f0a828f815f8a3fc91bab42e6f0ff27fd0b44"
                :bytes 1873
                :mtime_utc "2026-06-14T16:26:22.706375+00:00"}
               {:id "test-summary"
                :kind "summary"
                :path "results/test-artifacts/test-summary.json"
                :schema_version "test-summary.v2"
                :contract_version "evidence-contract.v1"
                :producer "summary-emitter.v1"
                :verifies_against ["test-run.v1"]
                :dependencies [{:id "test-run" :sha256 "ce747ce13bc217af7f1953104b4f0a828f815f8a3fc91bab42e6f0ff27fd0b44"}]
                :input_versions {"test_run" "test-run.v1"}
                :sha256 "13d785b5cb2ca9394307b5ae6b436990345bba634b4dbb02ae1b8483e24249a2"
                :bytes 3852
                :mtime_utc "2026-06-14T16:26:22.705374+00:00"}
               {:id "claimable-classification"
                :kind "classification"
                :path "results/test-artifacts/claimable-classification.json"
                :schema_version "claimable-classification.v2"
                :contract_version "evidence-contract.v1"
                :producer "claimable-classification-emitter.v2"
                :verifies_against ["test-run.v1"]
                :dependencies [{:id "test-run" :sha256 "ce747ce13bc217af7f1953104b4f0a828f815f8a3fc91bab42e6f0ff27fd0b44"}]
                :input_versions {"test_run" "test-run.v1"}
                :sha256 "d50c81fa1842b6a581d347b9b039246a0798ab467c8ac6e8a725a32abba48bf1"
                :bytes 50298
                :mtime_utc "2026-06-14T16:25:22.992806+00:00"}
               {:id "validation-root"
                :kind "validation-root"
                :path "results/test-artifacts/validation-root.json"
                :schema_version "validation-root.v1"
                :contract_version "evidence-contract.v1"
                :producer "validation-root-emitter.v1"
                :verifies_against ["test-summary.v2"]
                :dependencies [{:id "test-summary" :sha256 "13d785b5cb2ca9394307b5ae6b436990345bba634b4dbb02ae1b8483e24249a2"}]
                :input_versions {"test_summary" "test-summary.v2"}
                :sha256 "f7d7e967d2b07f203af5b61c5d668da35f59d2657ae86efae87b5db1b2b4ac7a"
                :bytes 233
                :mtime_utc "2026-06-13T10:55:15.135878+00:00"}
               {:id "coverage"
                :kind "coverage"
                :path "results/test-artifacts/coverage.json"
                :schema_version "coverage.v1"
                :contract_version "evidence-contract.v1"
                :producer "coverage-emitter.v1"
                :verifies_against ["scenario.v1"]
                :dependencies []
                :input_versions {"test_summary" "test-summary.v2"}
                :sha256 "04e5a4fa26899914762fff27a1480490089402bb5897508b853c89a260756e98"
                :bytes 131108
                :mtime_utc "2026-06-14T16:24:16.139930+00:00"}]})

(def ambiguous-registry
  "A registry where two artifacts share the same schema_version, making
   dependency resolution ambiguous for any consumer depending on that version."
  {:run-id "test-run-3"
   :artifacts [{:id "module-a"
                :schema_version "shared-protocol.v1"
                :verifies_against []}
               {:id "module-b"
                :schema_version "shared-protocol.v1"
                :verifies_against []}
               {:id "consumer"
                :schema_version "consumer.v1"
                :verifies_against ["shared-protocol.v1"]}]})

;; ── tests ────────────────────────────────────────────────────────────────────

(deftest test-validate-artifact-registry-public-entry-point
  (testing "validate-artifact-registry is the named public entry point"
    (let [root (integ/validate-artifact-registry all-pass-registry)]
      (is (= :passed (:status root)))
      (is (= "validation-root.v1" (:validation/root-version root)))))
  (testing "deprecated validate-registry still works"
    (let [root (integ/validate-registry all-pass-registry)]
      (is (= :passed (:status root))))))

(deftest test-dependency-resolution-mode
  (testing "validation root records :dependency-resolution :dependencies-field when :dependencies used"
    (let [root (integ/validate-artifact-registry realistic-registry)]
      (is (= :dependencies-field (get-in root [:extra :dependency-resolution])))))
  (testing "validation root records :verifies-against-field when only legacy field used"
    (let [root (integ/validate-artifact-registry all-pass-registry)]
      (is (= :verifies-against-field (get-in root [:extra :dependency-resolution]))))))

(deftest test-all-pass-registry-remains-passed
  (testing "existing all-pass registry produces :passed validation root"
    (let [root (integ/validate-artifact-registry all-pass-registry)]
      (is (= :passed (:status root)))
      (is (= "validation-root.v1" (:validation/root-version root)))
      (is (= :artifact-registry (:suite/id root)))
      (is (= :evidence (:suite/type root)))
      (is (= "test-run-1" (get-in root [:extra :run-id])))
      (is (= 3 (get-in root [:extra :artifact-count])))
      (is (= 1 (get-in root [:metrics :checks])) "artifacts-present check runs")
      (is (= 1 (get-in root [:metrics :passed])))
      (is (zero? (get-in root [:metrics :failed])))
      (is (zero? (get-in root [:metrics :warnings]))))))

(deftest test-realistic-registry-passes
  (testing "a registry matching the emitted test-artifacts.json shape passes"
    (let [root (integ/validate-artifact-registry realistic-registry)]
      (is (= :passed (:status root)))
      (is (= "validation-root.v1" (:validation/root-version root)))
      (is (= 5 (get-in root [:extra :artifact-count])))
      ;; all dependencies are satisfied or known non-artifact schemas
      (is (= 1 (get-in root [:metrics :passed])))
      (is (zero? (get-in root [:metrics :failed])))
      (is (zero? (get-in root [:metrics :warnings]))))))

(deftest test-broken-dependency-produces-failed
  (testing "broken registry dependency produces :failed with explicit diagnostic key"
    (let [root (integ/validate-artifact-registry broken-registry)]
      (is (= :failed (:status root)))
      ;; explicit diagnostic key in error-keys
      (is (contains? (:error-keys root) :registry/dangling-dependency))
      ;; the check id should NOT leak into error-keys
      (is (not (contains? (:error-keys root) :registry/dangling-dep))
          "check-id should not appear in error-keys when explicit error-key is set")
      ;; metrics
      (is (= 1 (get-in root [:metrics :passed])))
      (is (= 1 (get-in root [:metrics :failed])))
      (is (= 2 (get-in root [:metrics :checks])))
      (is (= 1 (count (:errors root))))
      (is (= :registry/dangling-dependency (:key (first (:errors root))))))))

(deftest test-ambiguous-schema-version-warning
  (testing "ambiguous schema version produces :warning with explicit key"
    (let [root (integ/validate-artifact-registry ambiguous-registry)]
      (is (= :warning (:status root)))
      (is (contains? (:warning-keys root) :registry/ambiguous-schema-version-dependency))
      ;; artifacts-present passes
      (is (= 1 (get-in root [:metrics :passed])))
      ;; one ambiguous dependency warning
      (is (= 1 (get-in root [:metrics :warnings])))
      (is (= 2 (get-in root [:metrics :checks])))
      (is (= 1 (count (:warnings root))))
      (is (= :registry/ambiguous-schema-version-dependency (:key (first (:warnings root))))))))

(deftest test-known-non-artifact-schema-excluded-from-dangling
  (testing "evidence-contract.v1 is excluded from dangling dependency checks"
    (let [root (integ/validate-artifact-registry
                {:run-id "r1"
                 :artifacts [{:id "test"
                              :schema_version "test.v1"
                              :verifies_against ["evidence-contract.v1"]}]})]
      (is (= :passed (:status root))
          "evidence-contract.v1 should not trigger dangling dependency"))))

(deftest test-unknown-check-status-via-extra-checks
  (testing "extra check with unknown status produces :warning and adapter key"
    (let [root (integ/validate-artifact-registry
                all-pass-registry
                {:extra-checks [{:check/id :registry/custom-check
                                 :status :skipped
                                 :message "not run in this context"}]})]
      (is (= :warning (:status root)))
      (is (contains? (:warning-keys root) :adapter/unknown-check-status))
      (is (= 1 (get-in root [:metrics :warnings]))))))

(deftest test-explicit-diagnostic-key-in-extra-checks
  (testing "explicit diagnostic key from extra check survives in error-keys"
    (let [root (integ/validate-artifact-registry
                all-pass-registry
                {:extra-checks [{:check/id :custom/schema-check
                                 :status :failed
                                 :error-key :custom/schema-mismatch
                                 :severity :critical
                                 :message "schema version mismatch"}]})]
      (is (= :failed (:status root)))
      (is (contains? (:error-keys root) :custom/schema-mismatch))
      (is (not (contains? (:error-keys root) :custom/schema-check))
          ":check/id should not leak when :error-key is explicit")
      (is (= :custom/schema-mismatch (:key (first (:errors root))))))))

;; ── export integration ───────────────────────────────────────────────────────

(deftest test-realistic-registry-produces-exportable-validation-root
  (testing "a realistic emitted registry (matching test-artifacts.json) produces a
           validation-root.v1 with :validation/artifact-registry metadata"
    (let [root   (integ/validate-artifact-registry realistic-registry)
          vkey   :validation/artifact-registry]
      (is (= :passed (:status root)))
      (is (= 5 (get-in root [:extra :artifact-count])))
      ;; the root is a validation-root.v1 map — store-able as metadata
      (is (map? root))
      (is (contains? root :status))
      (is (contains? root :validation/root-version))
      ;; simulation of storing under a well-known metadata key
      (let [export-meta {vkey {:status (:status root)
                               :checks (get-in root [:metrics :checks])
                               :passed (get-in root [:metrics :passed])
                               :failed (get-in root [:metrics :failed])
                               :warnings-count (get-in root [:metrics :warnings])
                               :error-keys (:error-keys root)
                               :warning-keys (:warning-keys root)}}]
        (is (= :passed (get-in export-meta [vkey :status])))
        (is (= 1 (get-in export-meta [vkey :passed])))
        (is (zero? (get-in export-meta [vkey :failed])))
        (is (zero? (get-in export-meta [vkey :warnings-count])))))))

(deftest test-broken-registry-produces-exportable-validation-root
  (testing "a broken registry produces a validation-root with :failed visible in export metadata"
    (let [root (integ/validate-artifact-registry broken-registry)]
      (is (= :failed (:status root)))
      (is (contains? (:error-keys root) :registry/dangling-dependency))
      ;; export metadata shape
      (let [vkey    :validation/artifact-registry
            export-meta {vkey {:status   (:status root)
                               :failed   (get-in root [:metrics :failed])
                               :errors   (count (:errors root))
                               :error-keys (:error-keys root)}}]
        (is (= :failed (get-in export-meta [vkey :status])))
        (is (= 1 (get-in export-meta [vkey :failed])))
        (is (contains? (get-in export-meta [vkey :error-keys]) :registry/dangling-dependency))))))

(deftest test-ambiguous-registry-produces-exportable-warning
  (testing "ambiguous registry produces a warning visible in export metadata"
    (let [root (integ/validate-artifact-registry ambiguous-registry)]
      (is (= :warning (:status root)))
      (is (contains? (:warning-keys root) :registry/ambiguous-schema-version-dependency))
      (let [vkey    :validation/artifact-registry
            export-meta {vkey {:status        (:status root)
                               :warnings      (count (:warnings root))
                               :warning-keys  (:warning-keys root)}}]
        (is (= :warning (get-in export-meta [vkey :status])))
        (is (= 1 (get-in export-meta [vkey :warnings])))))))

(deftest test-validate-artifact-registry-from-file
  (testing "validate-artifact-registry-from-file reads JSON, produces validation root"
    (let [tmpfile (java.io.File/createTempFile "test-registry" ".json")
          _       (spit tmpfile (json/write-str realistic-registry {:escape-slash false}))
          root    (integ/validate-artifact-registry-from-file (.getPath tmpfile))]
      (is (= :passed (:status root)))
      (is (= 5 (get-in root [:extra :artifact-count])))
      (.delete tmpfile))))

;; ── -main smoke test ─────────────────────────────────────────────────────────

(deftest test-main-smoke
  (testing "-main writes artifact-registry-validation.json and prints status line"
    (let [tmpfile  (doto (java.io.File/createTempFile "registry-test-" ".json") .deleteOnExit)
          reg-file (java.io.File. (.getParent tmpfile) "test-artifacts.json")
          _        (spit reg-file (json/write-str realistic-registry {:escape-slash false}))
          result   (sh/sh "clojure" "-M" "-m"
                          "resolver-sim.validation.integration.artifact-registry"
                          (.getPath reg-file))
          out      (:out result)
          exit     (:exit result)
          out-file (java.io.File. (.getParent tmpfile) "artifact-registry-validation.json")]
      (is (zero? exit) (str "exit code 0, got " exit ": " (:err result)))
      (is (.exists out-file) "artifact-registry-validation.json written beside registry")
      (is (re-find #"status=passed" out) "stdout contains status")
      (is (re-find #"test-artifacts.json" out) "stdout contains input path")
      (is (re-find #"artifact-registry-validation.json" out) "stdout contains output path")
      (is (= "passed" (get-in (json/read-str (slurp out-file) :key-fn keyword) [:status]))
          "written validation root has :passed status")
      (.delete reg-file)
      (.delete out-file)
      (.delete tmpfile))))

;; ── -main error paths ─────────────────────────────────────────────────────────

(deftest test-main-nonexistent-file
  (testing "-main with nonexistent file exits 1 and prints File not found"
    (let [result (sh/sh "clojure" "-M" "-m"
                        "resolver-sim.validation.integration.artifact-registry"
                        "/tmp/nonexistent-registry-test-abc123.json")]
      (is (= 1 (:exit result)))
      (is (re-find #"(?i)File not found" (:out result)))
      (is (re-find #"nonexistent-registry-test" (:out result)) "path appears in message"))))

(deftest test-main-malformed-json
  (testing "-main with malformed JSON exits 1 and prints Operational error"
    (let [tmpfile (doto (java.io.File/createTempFile "malformed-" ".json") .deleteOnExit)
          _       (spit tmpfile "{invalid json}")
          result  (sh/sh "clojure" "-M" "-m"
                         "resolver-sim.validation.integration.artifact-registry"
                         (.getPath tmpfile))]
      (is (= 1 (:exit result)))
      (is (re-find #"(?i)Operational error" (:out result)))
      (.delete tmpfile))))

(deftest test-main-broken-registry
  (testing "-main with broken registry exits 0, writes :failed validation root"
    (let [tmpfile  (doto (java.io.File/createTempFile "broken-" ".json") .deleteOnExit)
          reg-file (java.io.File. (.getParent tmpfile) "test-artifacts.json")
          _        (spit reg-file (json/write-str broken-registry {:escape-slash false}))
          result   (sh/sh "clojure" "-M" "-m"
                          "resolver-sim.validation.integration.artifact-registry"
                          (.getPath reg-file))
          out      (:out result)
          exit     (:exit result)
          out-file (java.io.File. (.getParent tmpfile) "artifact-registry-validation.json")]
      (is (zero? exit) (str "validation failures exit 0, got " exit ": " (:err result)))
      (is (.exists out-file) "validation root written")
      (is (re-find #"status=failed" out) "stdout shows :failed status")
      (is (= "failed" (get-in (json/read-str (slurp out-file) :key-fn keyword) [:status]))
          "written root has :failed status")
      (is (some #(= "dangling-dependency" %) (get-in (json/read-str (slurp out-file) :key-fn keyword) [:error-keys]))
          "error-keys contains dangling-dependency")
      (.delete reg-file)
      (.delete out-file)
      (.delete tmpfile))))

;; ── edge cases: empty / nil artifacts ─────────────────────────────────────────

(deftest test-empty-artifacts-fails
  (testing "empty :artifacts [] produces :failed with :registry/no-artifacts"
    (let [root (integ/validate-artifact-registry {:artifacts [] :run-id "r1"})]
      (is (= :failed (:status root)))
      (is (contains? (:error-keys root) :registry/no-artifacts))
      (is (= 1 (get-in root [:metrics :failed])))
      (is (= 0 (get-in root [:extra :artifact-count]))))))

(deftest test-nil-artifacts-fails
  (testing "nil :artifacts produces :failed with :registry/no-artifacts"
    (let [root (integ/validate-artifact-registry {:run-id "r1"})]
      (is (= :failed (:status root)))
      (is (contains? (:error-keys root) :registry/no-artifacts))
      (is (= 1 (get-in root [:metrics :failed])))
      (is (= 0 (get-in root [:extra :artifact-count]))))))
