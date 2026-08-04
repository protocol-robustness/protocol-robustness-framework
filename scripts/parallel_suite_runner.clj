(ns scripts.parallel-suite-runner
  "Run fixture suites in parallel in a single JVM using future dispatch.
   Each suite gets fresh evidence/attestation registries and an isolated
   artifact subdirectory to prevent evidence reconciliation warnings.
   Suite result JSONs are copied to the shared artifact dir after completion.

   Usage: clojure -M:test:with-sew -m scripts.parallel-suite-runner :suites/all-invariants :suites/baseline-safety ...

   Environment variables:
     PARALLEL_TEST_JOBS  — max concurrent suites (default: min (dec n-cpus, heap-budget), min 1)
     PARALLEL_TEST_MEM_BUDGET_MB — heap budget per concurrent suite (default 1024)
     PARALLEL_TEST_SUITE_TIMEOUT_MS — per-suite timeout (default 3_600_000 = 60 min)
     KEEP_PARALLEL_TEST_ARTIFACTS — set to any truthy value to preserve temp dirs
                                   even on success (they are always kept on failure)

   Load-time side-effect invariant:
     Namespace loading (require) happens before per-suite registry/artifact isolation.
     Evidence writes, registry initialization, and artifact path writes must
     happen during suite execution (run-suite), not at load time."
  (:require [clojure.java.io :as io]
            [resolver-sim.sim.fixtures :as f]
            [resolver-sim.io.fixtures :as io-fix]
            [resolver-sim.evidence.chain :as chain]
            [resolver-sim.evidence.attestation-registry :as ar]
            [resolver-sim.evidence.config :as evcfg]
            [resolver-sim.protocols.registry :as preg]
            [scripts.test-summary :as summary])
  (:gen-class))

(defn- parse-suite-key
  [s]
  (keyword (if (.startsWith s ":") (subs s 1) s)))

(defn- suite-result-file
  [suite-key]
  (str "suite-" (name suite-key) ".json"))

(defn- default-jobs
  []
  (max 1 (dec (.availableProcessors (Runtime/getRuntime)))))

(defn- memory-budget-jobs
  "Cap concurrent suites so each gets at least ~1 GiB of max heap, avoiding OOM
   on high-core machines. Env override: PARALLEL_TEST_MEM_BUDGET_MB (default 1024)."
  []
  (let [budget-mb (or (some-> (System/getenv "PARALLEL_TEST_MEM_BUDGET_MB") Long/parseLong)
                      1024)
        budget-bytes (* budget-mb 1024 1024)
        max-heap (.maxMemory (Runtime/getRuntime))]
    (max 1 (quot max-heap budget-bytes))))

(defn- parse-job-limit
  "Jobs = user override (if set), else the smaller of the cpu-based default and
   the memory budget, so we never run more suites than the heap can hold."
  []
  (if-let [env (System/getenv "PARALLEL_TEST_JOBS")]
    (max 1 (Integer/parseInt env))
    (max 1 (min (default-jobs) (memory-budget-jobs)))))

(defn- suite-timeout-ms
  []
  (or (some-> (System/getenv "PARALLEL_TEST_SUITE_TIMEOUT_MS") Long/parseLong)
      3600000))

(defn- cleanup!
  [root]
  (doseq [f (reverse (doall (file-seq (io/file root))))]
    (.delete f)))

(defn- run-one-suite
  [suite-key artifact-dir]
  (chain/with-fresh-evidence-context*
   (fn []
     (ar/with-fresh-registry*
      (fn []
        (binding [evcfg/*artifact-dir* artifact-dir]
           (let [result (try (io-fix/run-suite-from-key suite-key :save nil {})
                            (catch Throwable t
                              (when (instance? InterruptedException t)
                                (.interrupt (Thread/currentThread)))
                              (println "ERROR in" suite-key ":" (.getMessage t))
                              (.printStackTrace t)
                              {:ok? false :results []}))]
            (try (f/emit-suite-result suite-key result)
                 (catch Throwable t
                   (println "WARN: emit-suite-result failed for" suite-key ":" (.getMessage t))))
            {:suite-key suite-key
             :ok? (:ok? result)
             :result result})))))))

(defn -main
  [& args]
  (let [suite-keys (mapv parse-suite-key args)
        n (count suite-keys)
         default-id preg/default-protocol-id
         _ (println (str "Pre-loading default protocol: " default-id "..."))
         _ (preg/get-protocol default-id)
         _ (println "Pre-loading protocol namespaces...")
         _ (doseq [ns-sym (preg/known-protocol-namespaces)]
           (require ns-sym))
         _ (println "Loaded" (count (preg/known-protocol-namespaces)) "protocol namespaces.")
        _ (println (str "Running " n " canonical fixture suites (single JVM, parallel)..."))
        _ (flush)
        start (System/currentTimeMillis)
        shared-dir (evcfg/artifact-dir)
        tmp-root (str (System/getProperty "java.io.tmpdir")
                      "/parallel-suite-artifacts-" (java.util.UUID/randomUUID))
        jobs (parse-job-limit)
        sem (java.util.concurrent.Semaphore. jobs)
        futures (mapv (fn [i sk]
                        (let [suite-dir (str tmp-root "/" (format "%03d" i) "-" (name sk))]
                          (.mkdirs (io/file suite-dir))
                          (future
                            (.acquire sem)
                            (try
                              (run-one-suite sk suite-dir)
                              (finally
                                (.release sem))))))
                      (range)
                      suite-keys)
        timeout-ms (suite-timeout-ms)
        results (mapv (fn [sk fut]
                        (let [r (deref fut timeout-ms :timeout)]
                          (if (= r :timeout)
                            {:suite-key sk
                             :ok? false
                             :result {:ok? false :results []}}
                            r)))
                      suite-keys futures)
        elapsed (- (System/currentTimeMillis) start)
        failed (remove :ok? results)
        failed? (pos? (count failed))
        keep? (or failed? (some? (System/getenv "KEEP_PARALLEL_TEST_ARTIFACTS")))]
    ;; Copy suite result JSONs back to shared artifact dir
    (let [sk->idx (into {} (map-indexed (fn [i sk] [sk i]) suite-keys))]
      (doseq [{:keys [suite-key]} results]
        (let [idx (get sk->idx suite-key)
              src (str tmp-root "/" (format "%03d" idx) "-" (name suite-key)
                       "/" (suite-result-file suite-key))
              dst (str shared-dir "/" (suite-result-file suite-key))]
          (when (.exists (io/file src))
            (io/copy (io/file src) (io/file dst))))))
    ;; Per-suite results
    (doseq [{:keys [suite-key ok? result]} results]
      (println (str suite-key " → " (if ok? "PASS" "FAIL")))
      (when-not ok?
        (doseq [r (:results result)]
          (when (not= :pass (:outcome r))
            (println (str "  FAIL: " (:trace-id r) " [" (:outcome r) "]"))))))
    (let [items (mapv (fn [{:keys [suite-key result]}]
                        {:label (str suite-key)
                         :failures (into []
                                         (keep (fn [r]
                                                 (when (not= :pass (:outcome r))
                                                   (str (:trace-id r) " [" (:outcome r) "]")))
                                               (:results result)))})
                      results)
          totals {:test n :pass (- n (count failed)) :fail (count failed) :error 0}]
      (println)
      (summary/render-box "Suite run summary"
                          [(format "%d suites, %d passed, %d failed"
                                   n (- n (count failed)) (count failed))
                           (format "elapsed: %.2fs  jobs: %d" (/ elapsed 1000.0) jobs)])
      (summary/result-line totals elapsed)
      (summary/print-failures items))
    ;; Cleanup — keep on failure, delete on success
    (if keep?
      (println "Keeping artifact dirs:" tmp-root)
      (try
        (cleanup! tmp-root)
        (catch Exception e
          (println "WARN: artifact cleanup failed:" (.getMessage e)))))
    (when failed?
      (System/exit 1))))
