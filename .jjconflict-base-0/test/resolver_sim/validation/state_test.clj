(ns resolver-sim.validation.state-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.validation.state :as vs]
            [resolver-sim.validation.root :as root]))

(def init root/empty-validation-root)

;; ── re-exports ───────────────────────────────────────────────────────────────

(deftest test-re-exports-safe
  (testing "safe monadic combinators are re-exported"
    (is (fn? vs/return))
    (is (fn? vs/bind))
    (is (fn? vs/run-state))
    (is (fn? vs/eval-state))
    (is (fn? vs/exec-state))
    (is (fn? vs/sequence-state))
    (is (fn? vs/traverse-state))))

(deftest test-put-state-not-re-exported
  (testing "put-state is NOT re-exported (cannot replace state)"
    (is (nil? (resolve 'vs/put-state)))))

(deftest test-update-state-not-re-exported
  (testing "raw update-state is NOT re-exported"
    (is (nil? (resolve 'vs/update-state)))))

;; ── semantic writes ──────────────────────────────────────────────────────────

(deftest test-semantic-operations
  (testing "semantic operations compose via bind"
    (let [m (vs/bind (vs/set-suite-id :yield)
                     (fn [_]
                       (vs/bind (vs/record-error {:key :yield/deferred-mismatch :message "fail"})
                                (fn [_]
                                  (vs/bind (vs/record-warning {:key :yield/drift :message "drift"})
                                           (fn [_]
                                             (vs/bind (vs/add-status-key :yield/checked)
                                                      (fn [_]
                                                        (vs/record-pass)))))))))
          final (vs/exec-state m init)]
      (is (= :yield (:suite/id final)))
      (is (contains? (:error-keys final) :yield/deferred-mismatch))
      (is (contains? (:warning-keys final) :yield/drift))
      (is (contains? (:status-keys final) :yield/checked))
      (is (= 1 (get-in final [:metrics :passed])))
      (is (= 1 (get-in final [:metrics :failed])))
      (is (= 1 (get-in final [:metrics :warnings])))
      (is (= 1 (count (:errors final))))
      (is (= 1 (count (:warnings final)))))))

(deftest test-record-pass
  (testing "record-pass increments :passed and :checks"
    (let [final (vs/exec-state (vs/record-pass) init)]
      (is (= 1 (get-in final [:metrics :passed])))
      (is (= 1 (get-in final [:metrics :checks]))))))

(deftest test-record-evidence
  (testing "record-evidence appends to :evidence"
    (let [final (vs/exec-state
                 (vs/record-evidence {:check/id :yield/solvency :status :passed})
                 init)]
      (is (= 1 (count (:evidence final))))
      (is (= :yield/solvency (:check/id (first (:evidence final))))))))

(deftest test-record-error-derives-keys-and-metrics
  (testing "record-error updates :errors, :error-keys, and metrics"
    (let [final (vs/exec-state
                 (vs/record-error {:key :yield/deferred-mismatch
                                   :severity :critical
                                   :message "deferred yield mismatch"})
                 init)]
      (is (contains? (:error-keys final) :yield/deferred-mismatch))
      (is (= 1 (count (:errors final))))
      (is (= :yield/deferred-mismatch (:key (first (:errors final)))))
      (is (= 1 (get-in final [:metrics :failed])))
      (is (= 1 (get-in final [:metrics :checks]))))))

(deftest test-record-warning-derives-keys-and-metrics
  (testing "record-warning updates :warnings, :warning-keys, and metrics"
    (let [final (vs/exec-state
                 (vs/record-warning {:key :yield/drift
                                     :severity :warning
                                     :message "yield drift detected"})
                 init)]
      (is (contains? (:warning-keys final) :yield/drift))
      (is (= 1 (count (:warnings final))))
      (is (= :yield/drift (:key (first (:warnings final)))))
      (is (= 1 (get-in final [:metrics :warnings])))
      (is (= 1 (get-in final [:metrics :checks]))))))

(deftest test-record-check
  (testing "record-check appends a check map"
    (let [final (vs/exec-state
                 (vs/record-check {:check/id :yield/solvency :status :passed})
                 init)]
      (is (= 1 (count (:checks final)))))))

(deftest test-add-error-key
  (testing "add-error-key adds to error-keys without creating an error entry"
    (let [final (vs/exec-state (vs/add-error-key :yield/mismatch) init)]
      (is (contains? (:error-keys final) :yield/mismatch))
      (is (empty? (:errors final))))))

(deftest test-add-warning-key
  (testing "add-warning-key adds to warning-keys"
    (let [final (vs/exec-state (vs/add-warning-key :yield/drift) init)]
      (is (contains? (:warning-keys final) :yield/drift)))))

(deftest test-increment-metric
  (testing "increment-metric increments a named metric"
    (let [final (vs/exec-state (vs/increment-metric :checks) init)]
      (is (= 1 (get-in final [:metrics :checks]))))))

(deftest test-set-suite-type
  (testing "set-suite-type sets the suite/type"
    (let [final (vs/exec-state (vs/set-suite-type :protocol) init)]
      (is (= :protocol (:suite/type final))))))

(deftest test-merge-extra-uses-extra-key
  (testing "merge-extra writes under :extra, not top-level"
    (let [final (vs/exec-state (vs/merge-extra {:run-id "abc"}) init)]
      (is (= "abc" (get-in final [:extra :run-id])))
      (is (not (contains? final :run-id))
          ":run-id should not be at top-level"))))

(deftest test-merge-extra-cannot-overwrite-validation-keys
  (testing "merge-extra cannot overwrite :error-keys, :status-keys, or :metrics"
    (let [with-data (assoc init :extra {:scenario "s1"})
          final (vs/exec-state
                 (vs/merge-extra {:error-keys #{:injected}
                                  :status-keys #{:injected}
                                  :metrics {:checks 999}
                                  :some-key "val"})
                 with-data)]
      ;; validation keys remain unchanged
      (is (= (:error-keys init) (:error-keys final)))
      (is (= (:status-keys init) (:status-keys final)))
      (is (= (:metrics init) (:metrics final)))
      ;; :extra accumulates payload under :extra
      (is (= "s1" (get-in final [:extra :scenario])))
      (is (= "val" (get-in final [:extra :some-key]))))))

;; ── semantic reads ───────────────────────────────────────────────────────────

(deftest test-snapshot
  (testing "snapshot returns the full state as the computation value"
    (let [[v s] (vs/run-state (vs/snapshot) {:test true})]
      (is (= {:test true} v))
      (is (= {:test true} s)))))

(deftest test-get-status-keys
  (testing "get-status-keys returns :status-keys"
    (let [[v _] (vs/run-state (vs/get-status-keys) {:status-keys #{:a}})]
      (is (= #{:a} v)))))

(deftest test-get-error-keys
  (testing "get-error-keys returns :error-keys"
    (let [[v _] (vs/run-state (vs/get-error-keys) {:error-keys #{:e1}})]
      (is (= #{:e1} v)))))

(deftest test-get-warning-keys
  (testing "get-warning-keys returns :warning-keys"
    (let [[v _] (vs/run-state (vs/get-warning-keys) {:warning-keys #{:w1}})]
      (is (= #{:w1} v)))))

(deftest test-get-evidence
  (testing "get-evidence returns :evidence"
    (let [[v _] (vs/run-state (vs/get-evidence) {:evidence [:e1]})]
      (is (= [:e1] v)))))

(deftest test-get-checks
  (testing "get-checks returns :checks"
    (let [[v _] (vs/run-state (vs/get-checks) {:checks [:c1]})]
      (is (= [:c1] v)))))

(deftest test-get-metrics
  (testing "get-metrics returns :metrics"
    (let [[v _] (vs/run-state (vs/get-metrics) {:metrics {:passed 1}})]
      (is (= {:passed 1} v)))))

;; ── integration: full flow ───────────────────────────────────────────────────

(deftest test-full-validation-flow
  (testing "validation accumulation flow using semantic operations"
    (let [m (vs/bind (vs/set-suite-id :yield)
                     (fn [_]
                       (vs/bind (vs/set-suite-type :protocol)
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
                                                                   (vs/bind (vs/record-pass)
                                                                            (fn [_]
                                                                              (vs/bind (vs/merge-extra {:run-id "run-1"})
                                                                                       (fn [_]
                                                                                         (vs/snapshot)))))))))))))))
          state (vs/exec-state m init)]
      (is (= :yield (:suite/id state)))
      (is (= :protocol (:suite/type state)))
      (is (= 1 (count (:errors state))))
      (is (= 1 (count (:warnings state))))
      (is (= 1 (count (:evidence state))))
      (is (= 1 (get-in state [:metrics :passed])))
      (is (= 1 (get-in state [:metrics :failed])))
      (is (= 1 (get-in state [:metrics :warnings])))
      (is (contains? (:error-keys state) :yield/deferred-mismatch))
      (is (contains? (:warning-keys state) :yield/drift))
      (is (= "run-1" (get-in state [:extra :run-id]))))))
