(ns resolver-sim.commands.validate-test
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.cli.dispatch :as dispatch]
            [resolver-sim.commands.validate :as sut])
  (:import [java.nio.file Files Path]
           [java.util UUID]))

(defn- temp-run-dir []
  (Files/createTempDirectory "prf-validate-test-"
                             (make-array java.nio.file.attribute.FileAttribute 0)))

(defn- private-call [symbol & args]
  (apply (var-get (ns-resolve 'resolver-sim.commands.validate symbol)) args))

(defn- with-redefined [symbol replacement f]
  (with-redefs-fn {(ns-resolve 'resolver-sim.commands.validate symbol) replacement} f))

(defn- report [overrides]
  (merge {:status "passed"
          :expectedCount 2
          :discoveredCount 2
          :attempted 2
          :failed 0
          :timedOut 0
          :missing []
          :unexpected []
          :namespaces [{:status "passed"} {:status "passed"}]}
         overrides))

(deftest notebook-validation-requires-complete-success-report
  (testing "a complete report passes"
    (is (sut/notebook-report-valid? (report {}))))
  (testing "a timed-out child cannot be hidden by a zero subprocess status"
    (is (not (sut/notebook-report-valid?
              (report {:status "failed" :failed 1 :timedOut 1
                       :namespaces [{:status "passed"} {:status "timed-out"}]})))))
  (testing "incomplete inventory and missing report cannot pass"
    (is (not (sut/notebook-report-valid? (report {:discoveredCount 1 :attempted 1}))))
    (is (not (sut/notebook-report-valid? nil))))
  (testing "every reported namespace must pass"
    (is (not (sut/notebook-report-valid?
              (report {:namespaces [{:status "passed"} {:status "failed"}]}))))))

(deftest process-runner-preserves-success-and-failure-status
  (let [dir (temp-run-dir)
        stdout (.resolve ^Path dir "out.log")
        stderr (.resolve ^Path dir "err.log")
        success (private-call 'run-process
                              ["sh" "-c" "printf 'hello-out'; printf 'hello-err' >&2"]
                              5000 stdout stderr)
        failure (private-call 'run-process ["sh" "-c" "exit 23"] 5000
                              (.resolve ^Path dir "failure-out.log")
                              (.resolve ^Path dir "failure-err.log"))]
    (is (= :passed (:terminal success)))
    (is (= 0 (:exit-code success)))
    (is (= "hello-out" (slurp (.toFile stdout))))
    (is (= "hello-err" (slurp (.toFile stderr))))
    (is (= :failed (:terminal failure)))
    (is (= 23 (:exit-code failure)))
    (is (= :nonzero-exit (:exit-cause failure)))))

(deftest process-runner-retains-timeout-signal-and-start-failures
  (let [dir (temp-run-dir)
        timeout (private-call 'run-process ["sh" "-c" "sleep 10"] 30
                              (.resolve ^Path dir "timeout-out.log")
                              (.resolve ^Path dir "timeout-err.log"))
        signal (private-call 'run-process ["sh" "-c" "kill -TERM $$"] 5000
                             (.resolve ^Path dir "signal-out.log")
                             (.resolve ^Path dir "signal-err.log"))
        missing (private-call 'run-process
                              [(str "missing-validation-executable-" (UUID/randomUUID))]
                              1000 (.resolve ^Path dir "missing-out.log")
                              (.resolve ^Path dir "missing-err.log"))]
    (is (= :timed-out (:terminal timeout)))
    (is (= :timeout (:exit-cause timeout)))
    (is (nil? (:exit-code timeout)))
    (is (= :failed (:terminal signal)))
    (is (number? (:exit-code signal)))
    (is (>= (:exit-code signal) 128))
    (is (= :nonzero-signal-like-status (:exit-cause signal)))
    (is (= :failed (:terminal missing)))
    (is (= :process-start-or-execution-error (:exit-cause missing)))
    (is (string? (:error missing)))))

(deftest aggregate-validation-retains-failing-phase-and-exits-nonzero
  (let [dir (temp-run-dir)
        phases [{:id :first :argv ["first"] :timeout-ms 1000}
                {:id :second :argv ["second"] :timeout-ms 1000}]
        calls (atom 0)
        runner (fn [argv _timeout-ms stdout stderr]
                 (let [n (swap! calls inc)]
                   (spit (.toFile ^Path stdout) (str "stdout " (first argv)))
                   (spit (.toFile ^Path stderr) (str "stderr " (first argv)))
                   (if (= n 2)
                     (throw (ex-info "injected phase runner failure" {}))
                     {:argv argv :exit-code 0 :terminal :passed :exit-cause :normal-exit})))
        output (with-out-str
                 (reset! calls 0)
                 (let [result (private-call 'run-phases phases (.toString dir) runner)]
                   (is (= 1 (:exit-code result)))
                   (is (= :failed (get-in result [:report :status])))))
        report-file (first (filter #(= "validation-report.edn" (.getName %))
                                   (file-seq (.toFile dir))))
        report (edn/read-string (slurp report-file))]
    (is (= 2 @calls))
    (is (= :failed (:status report)))
    (is (= :runner-exception (get-in report [:phases 1 :exit-cause])))
    (is (string? (get-in report [:phases 1 :stdout])))
    (is (string? (get-in report [:phases 1 :stderr])))
    (is (re-find #"VALIDATION FAILED" output))
    (is (not (re-find #"VALIDATION PASSED" output)))))

(deftest cli-returns-validation-handler-exit-code
  (with-redefs [sut/run (fn [_] {:exit-code 1 :message "injected phase failure"})]
    (is (= 1 (dispatch/run ["validate"])))))

(deftest notebook-phase-rejects-a-stale-pass-report
  (let [dir (temp-run-dir)
        report-path (.resolve ^Path dir "old-pass.json")
        valid-report {:status "passed" :expectedCount 1 :discoveredCount 1
                      :attempted 1 :failed 0 :timedOut 0 :missing [] :unexpected []
                      :namespaces [{:status "passed"}]}
        output-dir (.resolve ^Path dir "run")
        result* (atom nil)]
    (spit (.toFile report-path) (json/write-str valid-report))
    (with-redefined 'latest-notebook-report-file #(.toFile report-path)
      (fn []
          ;; A pre-existing pass report cannot validate a runner that emitted none.
        (with-redefined 'notebook-reports-before-run #(hash-set "old-pass.json")
          (fn []
            (let [output (with-out-str
                           (reset! result*
                                   (private-call 'run-phases
                                                 [{:id :all-notebooks :argv ["notebooks"]
                                                   :timeout-ms 1000}]
                                                 (.toString output-dir)
                                                 (fn [_ _ _ _]
                                                   {:exit-code 0 :terminal :passed
                                                    :exit-cause :normal-exit}))))]
              (is (re-find #"VALIDATION FAILED" output))
              (is (= :failed (get-in @result* [:report :status])))
              (is (= :notebook-report-invalid
                     (get-in @result* [:report :phases 0 :exit-cause]))))))))))
