(ns scripts.parallel-suite-runner
  "Run fixture suites in parallel in a single JVM using future dispatch.
   Each suite gets fresh evidence/attestation registries and an isolated
   artifact subdirectory to prevent evidence reconciliation warnings.
   Suite result JSONs are copied to the shared artifact dir after completion.

   Usage: clojure -M:test:with-sew -m scripts.parallel-suite-runner :suites/all-invariants :suites/baseline-safety ...

   Environment variables:
     PARALLEL_TEST_JOBS  — max concurrent suites (default: (dec n-cpus), min 1)
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
            [resolver-sim.protocols.registry :as preg])
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

(defn- parse-job-limit
  []
  (or (some-> (System/getenv "PARALLEL_TEST_JOBS") Integer/parseInt)
      (default-jobs)))

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
        results (mapv deref futures)
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
    (println (str "\n=== Suite Run Complete ==="))
    (println (str "  suites: " n "  failed: " (count failed) "  elapsed: " (format "%.2fs" (/ elapsed 1000.0))
                  "  jobs: " jobs))
    ;; Cleanup — keep on failure, delete on success
    (if keep?
      (println "Keeping artifact dirs:" tmp-root)
      (try
        (cleanup! tmp-root)
        (catch Exception e
          (println "WARN: artifact cleanup failed:" (.getMessage e)))))
    (when failed?
      (System/exit 1))))
