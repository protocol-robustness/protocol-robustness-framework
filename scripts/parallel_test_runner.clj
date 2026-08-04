(ns scripts.parallel-test-runner
  "Run Clojure test namespaces in parallel using future, each with isolated
   artifact directory to prevent evidence reconciliation warnings.

   Usage:
     clojure -M:test -m scripts.parallel-test-runner [--noop-capture] ns1 ns2 ns3

   When --noop-capture is the first argument, evidence capture is suppressed
   entirely (no disk I/O).  Use for pure unit tests.

   Environment variables:
     PARALLEL_TEST_JOBS  — max concurrent namespaces (default: (dec n-cpus), min 1)
     KEEP_PARALLEL_TEST_ARTIFACTS — set to any truthy value to preserve temp dirs
                                    even on success (they are always kept on failure)

   Load-time side-effect invariant:
     Namespace loading (require) happens before per-namespace registry/artifact
     isolation, so test namespace require forms must be side-effect-light.
     Evidence writes, registry initialization, and artifact path writes must
     happen during test execution (run-tests), not at load time."
  (:require [clojure.java.io :as io]
            [clojure.test :as t]
            [resolver-sim.evidence.capture :as cap]
            [resolver-sim.evidence.chain :as chain]
            [resolver-sim.evidence.node :as node]
            [resolver-sim.evidence.attestation-registry :as ar]
            [resolver-sim.evidence.config :as evcfg]
            [resolver-sim.hash.canonical :as hc]
            [scripts.test-state :as ts]
            [scripts.test-summary :as summary]))

(defn- default-jobs
  []
  (max 1 (dec (.availableProcessors (Runtime/getRuntime)))))

(defn- parse-job-limit
  []
  (or (some-> (System/getenv "PARALLEL_TEST_JOBS") Integer/parseInt)
      (default-jobs)))

(defn- cleanup!
  [root]
  (doseq [f (reverse (doall (file-seq (io/file root))))]
    (.delete f)))

(defn- noop-capture
  "No-op evidence capture — suppresses all disk I/O."
  [& _]
  nil)

(defn -main
  [& args]
  (let [[noop-capture? namespaces] (if (= "--noop-capture" (first args))
                                     [true (rest args)]
                                     [false args])
        syms (map symbol namespaces)
        _ (doseq [s syms] (require s))
        start (System/currentTimeMillis)
        tmp-root (str (System/getProperty "java.io.tmpdir")
                      "/parallel-test-artifacts-" (java.util.UUID/randomUUID))
        jobs (parse-job-limit)
        sem (java.util.concurrent.Semaphore. jobs)
        futures (mapv (fn [i sym]
                        (let [ns-artifact-dir (str tmp-root "/" (format "%03d" i) "-" (munge (str sym)))
                              out-writer (java.io.StringWriter.)
                              err-writer (java.io.StringWriter.)]
                          (.mkdirs (io/file ns-artifact-dir))
                          (future
                            (.acquire sem)
                            (try
                              (binding [*out* (java.io.PrintWriter. out-writer)
                                        *err* (java.io.PrintWriter. err-writer)
                                        t/*test-out* (java.io.PrintWriter. out-writer)]
                                (chain/with-fresh-evidence-context*
                                 (fn []
                                   (node/with-fresh-registry
                                     (ar/with-fresh-registry*
                                      (fn []
                                         (binding [evcfg/*artifact-dir* ns-artifact-dir
                                                   chain/*allow-dirty* true
                                                   cap/*capture-event-evidence!* (if noop-capture?
                                                                                   noop-capture
                                                                                   cap/*capture-event-evidence!*)
                                                   hc/*validate-intent-constraints* true]
                                          (let [r (try (t/run-tests sym)
                                                       (catch Throwable t
                                                         (when (instance? InterruptedException t)
                                                           (.interrupt (Thread/currentThread)))
                                                         (println "ERROR in" sym ":" (.getMessage t))
                                                         (.printStackTrace t)
                                                         {:test 0 :pass 0 :fail 0 :error 1}))]
                                            {:sym sym :result r :output (str out-writer) :err-output (str err-writer)}))))))))
                              (finally
                                (.release sem))))))
                        (range)
                        syms)
        results (mapv deref futures)
        elapsed (- (System/currentTimeMillis) start)
        total {:test (apply + (map (comp :test :result) results))
               :pass (apply + (map (comp :pass :result) results))
               :fail (apply + (map (comp :fail :result) results))
               :error (apply + (map (comp :error :result) results))}
        failed? (pos? (+ (:fail total) (:error total)))
        keep? (or failed? (some? (System/getenv "KEEP_PARALLEL_TEST_ARTIFACTS")))]
    ;; Serialize per-namespace output (prevent interleaving from concurrent namespaces)
    (println)
    (let [items (mapv (fn [{:keys [sym output err-output]}]
                        {:label (str sym)
                         :failures (summary/first-failing-tests
                                    (str output err-output))})
                      results)]
      (doseq [{:keys [sym result output err-output]} results]
        (let [label (str sym)]
          (println)
          (println "─────" label "─────")
          (print output)
          (when (not= "" err-output)
            (print err-output))
          (flush)
          (if (and (zero? (:fail result)) (zero? (:error result)))
            (println (str "  PASS  " label "  (" (:test result) " tests)"))
            (println (str "  FAIL  " label "  " (:fail result) " fail, " (:error result) " errors, "
                          (:test result) " tests")))))
      ;; Summary box
      (println)
      (summary/render-box "Parallel test summary"
                          [(format "%d tests, %d assertions, %d failures, %d errors"
                                   (:test total) (:pass total) (:fail total) (:error total))
                           (format "elapsed: %.2fs  jobs: %d" (/ elapsed 1000.0) jobs)
                           (if keep?
                             (format "artifacts: %s" tmp-root)
                             "artifacts: cleaned (all passed)")])
      (summary/result-line total elapsed)
      (summary/print-failures items))
    ;; Cleanup — keep on failure, delete on success
    (if keep?
      (println "Keeping artifact dirs:" tmp-root)
      (try
        (cleanup! tmp-root)
        (catch Exception e
          (println "WARN: artifact cleanup failed:" (.getMessage e)))))
    ;; Persist test state for bb test:rerun
    (let [failed-syms (mapv :sym (filter #(or (pos? (:fail (:result %)))
                                              (pos? (:error (:result %))))
                                        results))]
      (ts/write-state! {:command *command-line-args*
                        :failed-nses failed-syms}))
    (when failed?
      (System/exit 1))))
