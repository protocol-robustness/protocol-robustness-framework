(ns resolver-sim.commands.validate-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [resolver-sim.cli.dispatch :as dispatch]
            [resolver-sim.commands.validate :as sut])
  (:import [java.nio.file Files Path]
           [java.util UUID]))

(defn- temp-run-dir []
  (Files/createTempDirectory "prf-validate-test-"
                             (make-array java.nio.file.attribute.FileAttribute 0)))

(defn- log-path [^Path dir name]
  (.resolve dir name))

(defn- run-process [& args]
  (apply (var-get (ns-resolve 'resolver-sim.commands.validate 'run-process)) args))

(defn- with-process-runner [runner f]
  (with-redefs-fn {(ns-resolve 'resolver-sim.commands.validate 'run-process) runner} f))

(deftest process-runner-preserves-success-and-failure-status
  (let [dir (temp-run-dir)
        stdout (log-path dir "out.log")
        stderr (log-path dir "err.log")
        success (run-process ["sh" "-c" "printf 'hello-out'; printf 'hello-err' >&2"]
                             5000 stdout stderr)
        success-out (slurp (.toFile stdout))
        success-err (slurp (.toFile stderr))
        failure (run-process ["sh" "-c" "exit 23"] 5000
                             (log-path dir "failure-out.log")
                             (log-path dir "failure-err.log"))]
    (is (= :passed (:terminal success)))
    (is (= 0 (:exit-code success)))
    (is (= "hello-out" success-out))
    (is (= "hello-err" success-err))
    (is (= :failed (:terminal failure)))
    (is (= 23 (:exit-code failure)))
    (is (= :nonzero-exit (:exit-cause failure)))))

(deftest process-runner-retains-signal-like-status
  (let [dir (temp-run-dir)
        result (run-process ["sh" "-c" "kill -TERM $$"] 5000
                            (log-path dir "out.log")
                            (log-path dir "err.log"))]
    (is (= :failed (:terminal result)))
    (is (number? (:exit-code result)))
    (is (>= (:exit-code result) 128))
    (is (= :nonzero-signal-like-status (:exit-cause result)))))

(deftest process-runner-terminates-timeouts
  (let [dir (temp-run-dir)
        result (run-process ["sh" "-c" "sleep 10"] 30
                            (log-path dir "out.log")
                            (log-path dir "err.log"))]
    (is (= :timed-out (:terminal result)))
    (is (= :timeout (:exit-cause result)))
    (is (nil? (:exit-code result)))))

(deftest process-runner-reports-missing-executable
  (let [dir (temp-run-dir)
        result (run-process [(str "missing-validation-executable-" (UUID/randomUUID))]
                            1000 (log-path dir "out.log") (log-path dir "err.log"))]
    (is (= :failed (:terminal result)))
    (is (= :process-start-or-execution-error (:exit-cause result)))
    (is (string? (:error result)))))

(deftest aggregate-validation-persists-partial-failure-and-exits-nonzero
  (let [dir (temp-run-dir)
        phase-results (atom [])
        phase-calls (atom 0)
        fake-process (fn [argv _timeout-ms stdout stderr]
                       (let [index (swap! phase-calls inc)]
                         (if (= 3 index)
                           (throw (ex-info "injected phase runner failure" {}))
                           (do
                             (spit (.toFile stdout) (str "stdout " (first argv)))
                             (spit (.toFile stderr) (str "stderr " (first argv)))
                             (let [result {:argv argv :exit-code 0 :terminal :passed
                                           :exit-cause :normal-exit}]
                               (swap! phase-results conj result)
                               result)))))]
    (with-process-runner fake-process
      (fn []
        (let [run-result (atom nil)
              stderr (java.io.StringWriter.)
              output (with-out-str
                       (binding [*err* stderr]
                         (reset! run-result
                                 (sut/run {:validate/output-dir (.toString dir)}))))
              report-file (last (filter #(= "validation-report.edn" (.getName %))
                                        (file-seq (.toFile dir))))
              report (edn/read-string (slurp report-file))]
          (is (= 1 (:exit-code @run-result)))
          (is (= :failed (get-in @run-result [:report :status])))
          (is (= 4 @phase-calls))
          (is (= 3 (count @phase-results)))
          (is (= :failed (:status report)))
          (is (= :failed (get-in report [:phases 2 :terminal])))
          (is (= :runner-exception (get-in report [:phases 2 :exit-cause])))
          (is (string? (get-in report [:phases 2 :stdout])))
          (is (string? (get-in report [:phases 2 :stderr])))
          (is (re-find #"VALIDATION FAILED" output))
          (is (not (re-find #"VALIDATION PASSED" output))))))))

(deftest cli-returns-the-validation-handler-exit-code
  (with-redefs [sut/run (fn [_] {:exit-code 1 :message "injected phase failure"})]
    (is (= 1 (dispatch/run ["validate"])))))

(deftest aggregate-validation-passes-only-after-all-phases-pass
  (let [dir (temp-run-dir)]
    (with-process-runner (fn [argv _timeout-ms stdout stderr]
                           (spit (.toFile stdout) "ok")
                           (spit (.toFile stderr) "")
                           {:argv argv :exit-code 0 :terminal :passed
                            :exit-cause :normal-exit})
      (fn []
        (let [result (with-out-str (sut/run {:validate/output-dir (.toString dir)}))]
          (is (re-find #"VALIDATION PASSED" result))
          (is (not (re-find #"VALIDATION FAILED" result))))))))
