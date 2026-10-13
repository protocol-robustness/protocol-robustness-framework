(ns resolver-sim.validation.root-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.validation.root :as sut]
            [resolver-sim.validation.state :as vs]))

;; ── empty-root ───────────────────────────────────────────────────────────────

(deftest test-empty-root-passes
  (testing "empty-root has :passed status after finalize"
    (let [root (sut/finalize-root sut/empty-root)]
      (is (= :passed (:status root)))
      (is (= "validation-root.v1" (:validation/root-version root)))
      (is (zero? (get-in root [:metrics :checks]))))))

(deftest test-empty-root-has-expected-keys
  (testing "empty-root contains all required keys"
    (let [root sut/empty-root]
      (is (contains? root :status))
      (is (contains? root :status-keys))
      (is (contains? root :error-keys))
      (is (contains? root :warning-keys))
      (is (contains? root :checks))
      (is (contains? root :errors))
      (is (contains? root :warnings))
      (is (contains? root :evidence))
      (is (contains? root :metrics))
      (is (contains? root :extra))
      (is (contains? root :suite/id))
      (is (contains? root :suite/type)))))

;; ── derive-root-status ───────────────────────────────────────────────────────

(deftest test-derive-root-status-passed
  (testing "no error or warning keys  → :passed"
    (is (= :passed (sut/derive-root-status sut/empty-root)))))

(deftest test-derive-root-status-warning
  (testing "warning keys, no error keys → :warning"
    (let [root (assoc sut/empty-root :warning-keys #{:yield/drift})]
      (is (= :warning (sut/derive-root-status root))))))

(deftest test-derive-root-status-failed
  (testing "error keys → :failed"
    (let [root (assoc sut/empty-root :error-keys #{:yield/mismatch})]
      (is (= :failed (sut/derive-root-status root))))))

(deftest test-derive-root-status-errors-dominate
  (testing "errors dominate warnings → :failed"
    (let [root (assoc sut/empty-root
                      :error-keys #{:yield/mismatch}
                      :warning-keys #{:yield/drift})]
      (is (= :failed (sut/derive-root-status root))))))

;; ── finalize-root ────────────────────────────────────────────────────────────

(deftest test-finalize-root-adds-version
  (testing "finalize-root adds :validation/root-version"
    (let [root (sut/finalize-root sut/empty-root)]
      (is (= "validation-root.v1" (:validation/root-version root))))))

(deftest test-finalize-root-derives-status
  (testing "finalize-root derives :status from keys"
    (let [warn (sut/finalize-root (assoc sut/empty-root :warning-keys #{:x}))
          err  (sut/finalize-root (assoc sut/empty-root :error-keys #{:y}))]
      (is (= :warning (:status warn)))
      (is (= :failed (:status err))))))

;; ── status-precedence ────────────────────────────────────────────────────────

(deftest test-status-precedence-order
  (testing "status-precedence is ordered most to least severe"
    (is (= [:failed :warning :passed] sut/status-precedence))
    (is (= :failed (first sut/status-precedence)) ":failed is most severe")
    (is (= :passed (last sut/status-precedence)) ":passed is least severe")))

;; ── build-root ───────────────────────────────────────────────────────────────

(deftest test-build-root-empty
  (testing "build-root with no computation returns passed root"
    (let [root (sut/build-root (vs/return nil))]
      (is (= :passed (:status root)))
      (is (= "validation-root.v1" (:validation/root-version root))))))

(deftest test-build-root-composes-all-operations
  (testing "build-root composes all semantic operations correctly"
    (let [root (sut/build-root
                (vs/bind (vs/record-pass)
                         (fn [_]
                           (vs/bind (vs/record-pass)
                                    (fn [_]
                                      (vs/bind (vs/record-error {:key :yield/deferred-mismatch
                                                                 :severity :critical
                                                                 :message "deferred mismatch"})
                                               (fn [_]
                                                 (vs/bind (vs/record-warning {:key :yield/drift
                                                                              :severity :warning
                                                                              :message "small drift"})
                                                          (fn [_]
                                                            (vs/bind (vs/record-evidence {:check/id :yield/solvency
                                                                                          :status :passed})
                                                                     (fn [_]
                                                                       (vs/bind (vs/set-suite-id :yield)
                                                                                (fn [_]
                                                                                  (vs/bind (vs/set-suite-type :protocol)
                                                                                           (fn [_]
                                                                                             (vs/bind (vs/merge-extra {:run-id "run-1"})
                                                                                                      (fn [_]
                                                                                                        (vs/return nil))))))))))))))))))]
      ;; status derived from error keys
      (is (= :failed (:status root)))
      ;; version
      (is (= "validation-root.v1" (:validation/root-version root)))
      ;; suite metadata
      (is (= :yield (:suite/id root)))
      (is (= :protocol (:suite/type root)))
      ;; keys
      (is (contains? (:error-keys root) :yield/deferred-mismatch))
      (is (contains? (:warning-keys root) :yield/drift))
      ;; counts
      (is (= 2 (get-in root [:metrics :passed])))
      (is (= 1 (get-in root [:metrics :failed])))
      (is (= 1 (get-in root [:metrics :warnings])))
      (is (= 4 (get-in root [:metrics :checks])))
      (is (= 1 (count (:errors root))))
      (is (= 1 (count (:warnings root))))
      (is (= 1 (count (:evidence root))))
      ;; extra metadata survives under :extra
      (is (= "run-1" (get-in root [:extra :run-id]))))))

;; ── merge-suite-result ───────────────────────────────────────────────────────

(deftest test-merge-suite-result-adds-keys
  (testing "merge-suite-result absorbs suite result keys"
    (let [suite {:status-keys #{:s1}
                 :error-keys #{:e1}
                 :warning-keys #{:w1}
                 :errors [{:key :e1}]
                 :warnings [{:key :w1}]
                 :evidence [{:check/id :c1}]
                 :checks [{:check/id :c1}]
                 :metrics {:checks 5 :passed 3 :failed 1 :warnings 1}
                 :suite/id :yield
                 :suite/type :protocol}
          root (sut/finalize-root
                (vs/exec-state (sut/merge-suite-result suite) sut/empty-root))]
      (is (= :failed (:status root)))
      (is (contains? (:status-keys root) :s1))
      (is (contains? (:error-keys root) :e1))
      (is (contains? (:warning-keys root) :w1))
      (is (= 1 (count (:errors root))))
      (is (= 1 (count (:warnings root))))
      (is (= 1 (count (:evidence root))))
      (is (= 1 (count (:checks root))))
      (is (= 5 (get-in root [:metrics :checks])))
      (is (= 3 (get-in root [:metrics :passed])))
      (is (= 1 (get-in root [:metrics :failed])))
      (is (= 1 (get-in root [:metrics :warnings])))
      (is (= :yield (:suite/id root)))
      (is (= :protocol (:suite/type root))))))

(deftest test-merge-suite-result-accumulates
  (testing "merge-suite-result accumulates across multiple calls"
    (let [s1 {:error-keys #{:e1} :errors [{:key :e1}] :metrics {:checks 1 :failed 1}}
          s2 {:warning-keys #{:w1} :warnings [{:key :w1}] :metrics {:checks 1 :warnings 1}}
          m  (vs/bind (sut/merge-suite-result s1)
                      (fn [_]
                        (sut/merge-suite-result s2)))
          root (sut/finalize-root (vs/exec-state m sut/empty-root))]
      (is (= :failed (:status root)) "errors dominate")
      (is (= 2 (get-in root [:metrics :checks])))
      (is (= 1 (get-in root [:metrics :failed])))
      (is (= 1 (get-in root [:metrics :warnings]))))))

;; ── integration: build-root is the single entry point ────────────────────────

(deftest test-build-root-is-entry-point
  (testing "build-root runs computation and finalizes"
    (let [root (sut/build-root
                (vs/bind (vs/record-pass)
                         (fn [_]
                           (vs/bind (vs/record-pass)
                                    (fn [_]
                                      (vs/record-pass))))))]
      (is (= :passed (:status root)))
      (is (= 3 (get-in root [:metrics :passed])))
      (is (= 3 (get-in root [:metrics :checks]))))))

(deftest test-build-root-metrics-derived-correctly
  (testing "metrics tally correctly across mixed operations"
    (let [root (sut/build-root
                (vs/bind (vs/record-pass)
                         (fn [_]
                           (vs/bind (vs/record-pass)
                                    (fn [_]
                                      (vs/bind (vs/record-error {:key :e1 :message "err"})
                                               (fn [_]
                                                 (vs/bind (vs/record-warning {:key :w1 :message "warn"})
                                                          (fn [_]
                                                            (vs/record-pass))))))))))]
      (is (= 3 (get-in root [:metrics :passed])))
      (is (= 1 (get-in root [:metrics :failed])))
      (is (= 1 (get-in root [:metrics :warnings])))
      (is (= 5 (get-in root [:metrics :checks]))))))
