(ns resolver-sim.validation.adapters.artifact-registry-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.validation.adapters.artifact-registry :as adapter]
            [clojure.java.io :as io]))

;; ── no direct state-monad dependency ─────────────────────────────────────────

(deftest test-no-direct-state-monad-import
  (testing "adapter ns uses resolver-sim.validation.state, not state-monad directly"
    (let [src       (slurp (io/file "src/resolver_sim/validation/adapters/artifact_registry.clj"))
          has-state (re-find #"resolver-sim\.validation\.state" src)
          has-util  (re-find #"resolver-sim\.util\.state-monad" src)]
      (is has-state "adapter must require resolver-sim.validation.state")
      (is (not has-util) "adapter must not require resolver-sim.util.state-monad"))))

;; ── passed ───────────────────────────────────────────────────────────────────

(deftest test-clean-registry-passes
  (testing "clean registry result produces :passed root"
    (let [result {:checks [{:check/id :registry/required-files-exist :status :passed}
                           {:check/id :registry/hash-verify :status :passed}
                           {:check/id :registry/schema-version-match :status :passed}]
                  :metadata {:run-id "20260614-172333"
                             :artifact-count 5
                             :registry-level "CORE"}}
          root   (adapter/registry-result->validation-root result)]
      (is (= :passed (:status root)))
      (is (= "validation-root.v1" (:validation/root-version root)))
      (is (= :artifact-registry (:suite/id root)))
      (is (= :evidence (:suite/type root)))
      ;; metadata under :extra
      (is (= "20260614-172333" (get-in root [:extra :run-id])))
      (is (= 5 (get-in root [:extra :artifact-count])))
      ;; metrics
      (is (= 3 (get-in root [:metrics :passed])))
      (is (= 3 (get-in root [:metrics :checks])))
      (is (zero? (get-in root [:metrics :failed])))
      (is (zero? (get-in root [:metrics :warnings]))))))

;; ── failed ───────────────────────────────────────────────────────────────────

(deftest test-dangling-dependency-fails
  (testing "dangling dependency check produces :failed and error-key"
    (let [result {:checks [{:check/id :registry/dangling-dependency
                            :status :failed
                            :error-key :registry/dangling-dependency
                            :severity :critical
                            :message "artifact 'test-summary' depends on 'projection.v1' which is not in registry"}]
                  :metadata {:run-id "20260614" :artifact-count 3}}
          root   (adapter/registry-result->validation-root result)]
      (is (= :failed (:status root)))
      (is (contains? (:error-keys root) :registry/dangling-dependency))
      (is (= 1 (count (:errors root))))
      (is (= :registry/dangling-dependency (:key (first (:errors root)))))
      (is (= 1 (get-in root [:metrics :failed])))
      (is (= 1 (get-in root [:metrics :checks]))))))

(deftest test-missing-artifact-fails
  (testing "missing artifact produces :failed"
    (let [result {:checks [{:check/id :registry/required-files-exist :status :passed}
                           {:check/id :registry/missing-artifact
                            :status :failed
                            :error-key :artifact/hash-mismatch
                            :severity :critical
                            :message "artifact 'coverage' file not found at results/test-artifacts/coverage.json"}]
                  :metadata {:run-id "20260614" :artifact-count 4}}
          root   (adapter/registry-result->validation-root result)]
      (is (= :failed (:status root)))
      (is (contains? (:error-keys root) :artifact/hash-mismatch))
      (is (= 1 (get-in root [:metrics :passed])))
      (is (= 1 (get-in root [:metrics :failed])))
      (is (= 2 (get-in root [:metrics :checks]))))))

;; ── warning ──────────────────────────────────────────────────────────────────

(deftest test-diagnostic-warning-produces-warning
  (testing "non-fatal diagnostic warning produces :warning"
    (let [result {:checks   [{:check/id :registry/orphan-file
                              :status :warning
                              :warning-key :registry/orphan-file
                              :message "unregistered file found: results/test-artifacts/temp.json"}]
                  :metadata {:run-id "20260614" :artifact-count 5}}
          root   (adapter/registry-result->validation-root result)]
      (is (= :warning (:status root)))
      (is (contains? (:warning-keys root) :registry/orphan-file))
      (is (= 1 (count (:warnings root))))
      (is (= 1 (get-in root [:metrics :warnings])))
      (is (= 1 (get-in root [:metrics :checks]))))))

;; ── errors dominate warnings ─────────────────────────────────────────────────

(deftest test-errors-dominate-warnings
  (testing "when both errors and warnings are present, status is :failed"
    (let [result {:checks   [{:check/id :registry/hash-verify :status :passed}
                             {:check/id :registry/dangling-dependency
                              :status :failed
                              :error-key :registry/dangling-dependency
                              :severity :critical
                              :message "missing dependency"}
                             {:check/id :registry/orphan-file
                              :status :warning
                              :warning-key :registry/orphan-file
                              :message "orphan file"}]
                  :metadata {:run-id "20260614" :artifact-count 5}}
          root   (adapter/registry-result->validation-root result)]
      (is (= :failed (:status root)))
      (is (contains? (:error-keys root) :registry/dangling-dependency))
      (is (contains? (:warning-keys root) :registry/orphan-file))
      (is (= 1 (get-in root [:metrics :passed])))
      (is (= 1 (get-in root [:metrics :failed])))
      (is (= 1 (get-in root [:metrics :warnings])))
      (is (= 3 (get-in root [:metrics :checks]))))))

;; ── metrics consistency across mixed operations ──────────────────────────────

(deftest test-metrics-consistent
  (testing "metrics are updated consistently across passed/failed/warning checks"
    (let [result {:checks   [(merge {:status :passed} {:check/id :c1})
                             (merge {:status :passed} {:check/id :c2})
                             (merge {:status :failed  :error-key :e1
                                     :severity :critical :message "err1"} {:check/id :c3})
                             (merge {:status :warning :warning-key :w1
                                     :message "warn1"} {:check/id :c4})
                             (merge {:status :passed} {:check/id :c5})]
                  :metadata {:run-id "r1" :artifact-count 10}}
          root   (adapter/registry-result->validation-root result)]
      (is (= 3 (get-in root [:metrics :passed])))
      (is (= 1 (get-in root [:metrics :failed])))
      (is (= 1 (get-in root [:metrics :warnings])))
      (is (= 5 (get-in root [:metrics :checks]))))))

;; ── check maps with all fields preserved ─────────────────────────────────────

;; ── extra errors/warnings beyond checks ───────────────────────────────────────

(deftest test-extra-errors-and-warnings-accepted
  (testing "adapter accepts :errors and :warnings beyond those generated by checks"
    (let [result {:checks   [{:check/id :registry/required-files-exist :status :passed}]
                  :errors   [{:key :registry/structural-issue
                              :severity :critical
                              :message "registry file is malformed"}]
                  :warnings [{:key :registry/deprecated-field
                              :message "deprecated field 'worlds_tracked' used"}]
                  :metadata {:run-id "r1"}}
          root   (adapter/registry-result->validation-root result)]
      (is (= :failed (:status root)) "error from extra errors list")
      (is (contains? (:error-keys root) :registry/structural-issue))
      (is (contains? (:warning-keys root) :registry/deprecated-field))
      (is (= 1 (get-in root [:metrics :passed])))
      (is (= 1 (get-in root [:metrics :failed])))
      (is (= 1 (get-in root [:metrics :warnings])))
      (is (= 3 (get-in root [:metrics :checks]))))))

;; ── explicit diagnostic key preserved ────────────────────────────────────────

(deftest test-explicit-diagnostic-key-preserved
  (testing "explicit :error-key differs from :check/id and survives in :error-keys"
    (let [result {:checks [{:check/id :registry/missing-artifact
                            :status :failed
                            :error-key :artifact/hash-mismatch
                            :severity :critical
                            :message "hash mismatch on artifact"}]}
          root   (adapter/registry-result->validation-root result)]
      (is (= :failed (:status root)))
      ;; :error-key appears in error-keys, not :check/id
      (is (contains? (:error-keys root) :artifact/hash-mismatch))
      (is (not (contains? (:error-keys root) :registry/missing-artifact))
          ":check/id should not leak into error-keys when :error-key is set")
      ;; error entry uses the explicit key
      (is (= :artifact/hash-mismatch (:key (first (:errors root))))))))

(deftest test-explicit-warning-key-preserved
  (testing "explicit :warning-key differs from :check/id and survives in :warning-keys"
    (let [result {:checks [{:check/id :registry/orphan-file-detected
                            :status :warning
                            :warning-key :registry/orphan-file
                            :message "orphan file found"}]}
          root   (adapter/registry-result->validation-root result)]
      (is (= :warning (:status root)))
      (is (contains? (:warning-keys root) :registry/orphan-file))
      (is (not (contains? (:warning-keys root) :registry/orphan-file-detected))
          ":check/id should not leak into warning-keys when :warning-key is set")
      (is (= :registry/orphan-file (:key (first (:warnings root))))))))

;; ── unknown status → warning ─────────────────────────────────────────────────

(deftest test-unknown-status-produces-warning
  (testing "unknown check status produces :warning with adapter key"
    (let [result {:checks [{:check/id :registry/some-check
                            :status :skipped
                            :message "check was skipped"}]}
          root   (adapter/registry-result->validation-root result)]
      (is (= :warning (:status root)) "unknown status should produce :warning")
      (is (contains? (:warning-keys root) :adapter/unknown-check-status))
      (is (= 1 (count (:warnings root))))
      (is (re-find #"Unknown check status" (:message (first (:warnings root))))
          "warning message should describe the unknown status")
      (is (= 1 (get-in root [:metrics :warnings])))
      (is (= 1 (get-in root [:metrics :checks]))))))

(deftest test-unknown-status-with-nil
  (testing "nil status produces :warning, not silent pass"
    (let [result {:checks [{:check/id :registry/some-check
                            :status nil
                            :message "missing status"}]}
          root   (adapter/registry-result->validation-root result)]
      (is (= :warning (:status root)))
      (is (contains? (:warning-keys root) :adapter/unknown-check-status)))))

;; ── mixed pass + warning → warning ──────────────────────────────────────────

(deftest test-mixed-pass-warning-produces-warning
  (testing "mixed pass + warning with no errors produces :warning"
    (let [result {:checks [{:check/id :c1 :status :passed}
                           {:check/id :c2 :status :passed}
                           {:check/id :registry/orphan-file
                            :status :warning
                            :warning-key :registry/orphan-file
                            :message "orphan file"}]
                  :metadata {:run-id "r1" :artifact-count 10}}
          root   (adapter/registry-result->validation-root result)]
      (is (= :warning (:status root)))
      (is (contains? (:warning-keys root) :registry/orphan-file))
      (is (= 2 (get-in root [:metrics :passed])))
      (is (= 1 (get-in root [:metrics :warnings])))
      (is (= 3 (get-in root [:metrics :checks]))))))

;; ── artifact identity survives into :checks or :extra ────────────────────────

(deftest test-artifact-identity-in-check-maps
  (testing "extra check fields (artifact-id, artifact-path) survive in :checks"
    (let [result {:checks [{:check/id :registry/missing-artifact
                            :status :failed
                            :error-key :artifact/hash-mismatch
                            :severity :critical
                            :message "artifact 'coverage' not found"
                            :artifact-id "coverage"
                            :expected-path "results/test-artifacts/coverage.json"
                            :actual-path "not-found"}
                           {:check/id :registry/required-files-exist
                            :status :passed
                            :artifact-id "test-summary"
                            :path "results/test-artifacts/test-summary.json"}]
                  :metadata {:run-id "r1"
                             :artifact-count 5
                             :checked-artifacts [{:id "test-summary" :kind "summary"}
                                                 {:id "coverage" :kind "coverage"}]}}
          root   (adapter/registry-result->validation-root result)]
      ;; status reflects the failed check
      (is (= :failed (:status root)))
      ;; extra fields on check maps survive into :checks
      (let [failed-check (first (filter #(= :registry/missing-artifact (:check/id %))
                                        (:checks root)))]
        (is failed-check "failed check should exist in :checks")
        (is (= "coverage" (:artifact-id failed-check)))
        (is (= "results/test-artifacts/coverage.json" (:expected-path failed-check))))
      ;; passed check also preserves artifact-id
      (let [passed-check (first (filter #(= :registry/required-files-exist (:check/id %))
                                        (:checks root)))]
        (is passed-check "passed check should exist in :checks")
        (is (= "test-summary" (:artifact-id passed-check))))
      ;; metadata survives under :extra
      (is (= 2 (count (get-in root [:extra :checked-artifacts]))))
      (is (= "coverage" (get-in root [:extra :checked-artifacts 1 :id]))))))

;; ── check maps with all fields preserved ─────────────────────────────────────

(deftest test-check-fields-preserved
  (testing "check maps stored in :checks retain their fields"
    (let [check {:check/id :registry/schema-version-match
                 :status :passed
                 :message "schema version matches"
                 :severity :info
                 :expected "test-artifacts.v1.2"
                 :actual "test-artifacts.v1.2"
                 :check-group :schema}
          result {:checks [check]
                  :metadata {:run-id "r1" :artifact-count 5}}
          root   (adapter/registry-result->validation-root result)]
      (is (= 1 (count (:checks root))))
      (is (= check (first (:checks root)))))))
