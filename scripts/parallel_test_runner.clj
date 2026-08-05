(ns scripts.parallel-test-runner
  "Run Clojure test namespaces in parallel using future, each with isolated
   artifact directory to prevent evidence reconciliation warnings.

   Usage:
     clojure -M:test -m scripts.parallel-test-runner [--noop-capture] ns1 ns2 ns3

   When --noop-capture is the first argument, evidence capture is suppressed
   entirely (no disk I/O).  Use for pure unit tests.

   Environment variables:
     PARALLEL_TEST_JOBS  — max concurrent namespaces (default: min (dec n-cpus,
                           heap-budget), min 1)
     PARALLEL_TEST_MEM_BUDGET_MB — heap budget per concurrent namespace (default 1024)
     PARALLEL_TEST_NS_TIMEOUT_MS — per-namespace timeout before the run is marked
                           failed (default 1200000 = 20 min)
     PARALLEL_TEST_EXCLUDE_NS — comma/whitespace-separated namespace symbols to run
                           in the sequential lane instead of the parallel pool.
                           Defaults to the audit-derived set below; set to an empty
                           string to disable exclusion.
     PARALLEL_TEST_RUN_ID — run id used for the artifact run root and ownership
                           markers (default: timestamp-uuid)
     PARALLEL_TEST_LEAK_CHECK — set to 1 to snapshot process-global registries and
                           the shared artifact dir before dispatch and verify they
                           are left untouched afterwards (gating on diff; mirrors
                           run-sew-tests SEW_TEST_LEAK_CHECK)
     KEEP_PARALLEL_TEST_ARTIFACTS — set to any truthy value to preserve the run
                           root even on success (always kept on failure)

   Parallel-safety lane:
     Namespaces flagged with hard process-global hazards (see
     scripts/audit-parallel-safety.clj hard-patterns) never overlap the parallel
     pool; they run sequentially before the pool dispatches.  The audit is the
     source of truth for the default exclusion set below.

   Frozen run-root register:
     Each namespace regenerates fresh evidence/node/attestation registries and
     publishes under an artifact-scope bound to its namespace root inside the
     run root (<tmp>/parallel-run-<run-id>/<idx>-<ns>).  The scope manifest is
     frozen (finalize-scope!) and the run root carries an ownership marker, so
     artifacts are only ever deleted via artifact-scope/safe-delete!.  This
     mirrors scripts/run-sew-tests.clj.

   Load-time side-effect invariant:
     Namespace loading (require) happens before per-namespace registry/artifact
     isolation, so test namespace require forms must be side-effect-light.
     Evidence writes, registry initialization, and artifact path writes must
     happen during test execution (run-tests), not at load time."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as t]
            [resolver-sim.evidence.capture :as cap]
            [resolver-sim.evidence.chain :as chain]
            [resolver-sim.evidence.node :as node]
            [resolver-sim.evidence.attestation-registry :as ar]
            [resolver-sim.evidence.config :as evcfg]
            [resolver-sim.hash.canonical :as hc]
            [scripts.artifact-scope :as artifact-scope]
            [scripts.test-state :as ts]
            [scripts.test-summary :as summary]))

(def parallel-excluded-namespaces
  "Namespaces with audit-flagged hard process-global hazards that never overlap
   the parallel pool (derived from scripts/audit-parallel-safety.clj hard-pattern
   scan of the framework/unit/evidence lists: with-redefs/alter-var-root on
   shared vars, fixed path writes, port/server binding).  Override with
   PARALLEL_TEST_EXCLUDE_NS (comma/space separated); empty string disables."
  '#{resolver-sim.evidence.chain-test
     resolver-sim.evidence.commitment-root-test
     resolver-sim.evidence.node-test
     resolver-sim.hash.attestor-hash-test
     resolver-sim.hash.canonical-test
     resolver-sim.protocols.sew.dispute-resolution-coverage-test
     resolver-sim.protocols.sew.replay-test
     resolver-sim.validation.scenario-registry-test
     resolver-sim.benchmark.game-theory-validation-test})

(defn- excluded-syms
  "Resolve the exclusion set: env override if set, else the default set.
   An empty PARALLEL_TEST_EXCLUDE_NS string disables exclusion."
  []
  (if-let [env (System/getenv "PARALLEL_TEST_EXCLUDE_NS")]
    (->> (str/split env #"[,\s]+")
         (remove str/blank?)
         (map symbol)
         (into #{}))
    parallel-excluded-namespaces))

(defn- default-jobs
  []
  (max 1 (dec (.availableProcessors (Runtime/getRuntime)))))

(defn- memory-budget-jobs
  "Cap concurrent jobs so each running namespace gets at least ~1 GiB of max
   heap. Avoids OOM on high-core machines where `dec n-cpus` over-subscribes.
   Env override: PARALLEL_TEST_MEM_BUDGET_MB (default 1024)."
  []
  (let [budget-mb (or (some-> (System/getenv "PARALLEL_TEST_MEM_BUDGET_MB") Long/parseLong)
                      1024)
        budget-bytes (* budget-mb 1024 1024)
        max-heap (.maxMemory (Runtime/getRuntime))]
    (max 1 (quot max-heap budget-bytes))))

(defn- parse-job-limit
  "Jobs = user override (if set), else the smaller of the cpu-based default and
   the memory budget, so we never run more namespaces than the heap can hold."
  []
  (if-let [env (System/getenv "PARALLEL_TEST_JOBS")]
    (max 1 (Integer/parseInt env))
    (max 1 (min (default-jobs) (memory-budget-jobs)))))

(defn- ns-timeout-ms
  []
  (or (some-> (System/getenv "PARALLEL_TEST_NS_TIMEOUT_MS") Long/parseLong)
      1200000))

(defn- noop-capture
  "No-op evidence capture — suppresses all disk I/O."
  [& _]
  nil)

;; ── Leak tripwire (mirrors scripts/run-sew-tests.clj) ──────────────────────

(defn- chain-private
  "Read the bound value of a private dynamic var in resolver-sim.evidence.chain."
  [sym]
  (var-get (ns-resolve 'resolver-sim.evidence.chain sym)))

(defn- artifact-file-listing
  [dir]
  (when (and dir (.exists (io/file dir)))
    (->> (file-seq (io/file dir))
         (filter #(.isFile %))
         (sort-by #(.getName %))
         (mapv (fn [f] [(str f) (.length f)])))))

(defn- snapshot-default-state
  "Snapshot process-global state before/after the run.

   Returns {:gating {...} :advisory {...}}:
     :gating  — evidence/node/attestation registries + shared artifact dir;
                these MUST be unchanged by isolated namespaces (hard gate).
     :advisory — system properties, user.dir, locale/timezone, uncaught
                exception handler, and the live thread set; changes are
                reported but do not fail the run (avoid false positives
                from JVM/agent activity)."
  []
  (let [dir (evcfg/artifact-dir)
        threads (->> (.keySet (Thread/getAllStackTraces))
                     (remove #(.isDaemon %))
                     (map #(.getName %))
                     sort vec)]
    {:gating {:node-registry (pr-str @node/*node-registry*)
              :node-lock node/*node-persistence-lock*
              :attestation-registry (pr-str @ar/*attestation-registry*)
              :evidence-registry (pr-str @(chain-private 'evidence-registry-atom))
              :scenario-evidence (pr-str @(chain-private 'scenario-evidence-atom))
              :chain-cursor (pr-str @(chain-private 'chain-cursor))
              :artifact-dir dir
              :artifact-files (artifact-file-listing dir)}
     :advisory {:system-properties (pr-str (into (sorted-map) (System/getProperties)))
                :user.dir (System/getProperty "user.dir")
                :locale (str (java.util.Locale/getDefault))
                :timezone (str (java.util.TimeZone/getDefault))
                :uncaught-handler (str (Thread/getDefaultUncaughtExceptionHandler))
                :threads threads}}))

(defn- leak-diffs
  "Compare two snapshots; return {:gating [...] :advisory [...]} of keys that
   changed.  Lock identity is compared with identical?, everything else with =."
  [before after]
  (letfn [(changed [cat]
            (keep (fn [k]
                    (let [a (get (get before cat) k)
                          b (get (get after cat) k)]
                      (when (if (= k :node-lock)
                              (not (identical? a b))
                              (not= a b))
                        k)))
                  (keys (get before cat))))]
    {:gating (vec (changed :gating))
     :advisory (vec (changed :advisory))}))

(defn- run-one-namespace
  "Execute a single namespace under fresh evidence/node/attestation registries
   (regenerated) with an isolated artifact dir inside the run root, and freeze
   an artifact-scope manifest for it.  Returns a structured result map; never
   touches shared reporting/rerun state itself."
  [run-id tmp-root frozen idx noop-capture?]
  (let [sym (nth frozen idx)
        ns-root (str tmp-root "/" (format "%03d" idx) "-" (munge (str sym)))
        out (java.io.StringWriter.)
        err (java.io.StringWriter.)
        pw (java.io.PrintWriter. out)
        pwe (java.io.PrintWriter. err)
        scope-config {:run-id run-id
                      :namespace sym
                      :namespace-root ns-root
                      :scope-id (str run-id "-" idx)}]
    (artifact-scope/write-owner-marker! ns-root
                                        {:run-id run-id :namespace sym})
    (let [[result scope]
          (artifact-scope/with-scope
           scope-config
           (fn []
             (binding [*out* pw
                       *err* pwe
                       t/*test-out* pw
                       t/*report-counters* (ref t/*initial-report-counters*)
                       t/*testing-vars* (list)
                       t/*testing-contexts* (list)]
               (chain/with-fresh-evidence-context*
                (fn []
                  (node/with-fresh-registry
                    (ar/with-fresh-registry*
                     (fn []
                       (binding [evcfg/*artifact-dir* ns-root
                                 chain/*allow-dirty* true
                                 cap/*capture-event-evidence!* (if noop-capture?
                                                                 noop-capture
                                                                 cap/*capture-event-evidence!*)
                                 hc/*validate-intent-constraints* true]
                         (try
                           (t/run-tests sym)
                           (catch Throwable t
                             (when (instance? InterruptedException t)
                               (.interrupt (Thread/currentThread)))
                             (println "ERROR in" sym ":" (.getMessage t))
                             (.printStackTrace t)
                             {:test 0 :pass 0 :fail 0 :error 1})))))))))))
          [manifest scope-failed?]
          (try
            [(artifact-scope/finalize-scope! scope false) false]
            (catch clojure.lang.ExceptionInfo e
              [(or (:manifest (ex-data e))
                   (artifact-scope/mark-incomplete! scope))
               true]))]
      {:idx idx
       :sym sym
       :result result
       :output (str out)
       :err-output (str err)
       :manifest manifest
       :scope-status (:scope-status manifest)
       :scope-failed? scope-failed?})))

(defn -main
  [& args]
  (let [[noop-capture? namespaces] (if (= "--noop-capture" (first args))
                                     [true (rest args)]
                                     [false args])
        syms (map symbol namespaces)
        frozen (vec syms)
        n (count frozen)
        _ (doseq [s syms] (require s))
        run-id (or (System/getenv "PARALLEL_TEST_RUN_ID")
                   (str (System/currentTimeMillis) "-" (java.util.UUID/randomUUID)))
        tmp-root (str (System/getProperty "java.io.tmpdir") "/parallel-run-" run-id)
        _ (artifact-scope/write-owner-marker! tmp-root {:run-id run-id :namespace :run-root})
        leak? (= "1" (System/getenv "PARALLEL_TEST_LEAK_CHECK"))
        pre (when leak? (snapshot-default-state))
        leak-failed-atom (atom false)
        start (System/currentTimeMillis)
        jobs (parse-job-limit)
        timeout-ms (ns-timeout-ms)
        excluded (excluded-syms)
        excluded-idx (filterv #(contains? excluded (nth frozen %)) (range n))
        eligible-idx (filterv #(not (contains? excluded (nth frozen %))) (range n))
        _ (println (str "Running " n " namespaces (jobs=" jobs
                        ", timeout=" timeout-ms "ms, run-root=" tmp-root ")"))
        _ (when (seq excluded-idx)
            (println (str "  parallel-excluded (sequential lane): " (count excluded-idx)))
            (doseq [i excluded-idx] (println (str "    " (nth frozen i)))))
        task (fn [idx] (run-one-namespace run-id tmp-root frozen idx noop-capture?))
        sem (java.util.concurrent.Semaphore. jobs)
        futures (mapv (fn [idx]
                        [idx (future
                               (.acquire sem)
                               (try
                                 (task idx)
                                 (finally
                                   (.release sem))))])
                      eligible-idx)
        excl-results (mapv task excluded-idx)
        elig-results (mapv (fn [[idx fut]]
                             (let [r (deref fut timeout-ms :timeout)]
                               (if (= r :timeout)
                                 {:idx idx
                                  :sym (nth frozen idx)
                                  :result {:test 0 :pass 0 :fail 0 :error 1}
                                  :output ""
                                  :err-output (format "TIMEOUT: %s did not complete within %dms\n"
                                                      (nth frozen idx) timeout-ms)
                                  :scope-status :incomplete
                                  :scope-failed? true
                                  :manifest {:scope-status :incomplete}}
                                 r)))
                           futures)
        results (sort-by :idx (concat excl-results elig-results))
        elapsed (- (System/currentTimeMillis) start)
        err-extra (mapv (fn [{:keys [result scope-failed?]}]
                          (+ (or (:error result) 0) (if scope-failed? 1 0)))
                        results)
        total {:test (apply + (map (comp :test :result) results))
               :pass (apply + (map (comp :pass :result) results))
               :fail (apply + (map (comp :fail :result) results))
               :error (apply + err-extra)}
        failed? (or (pos? (+ (:fail total) (:error total)))
                    @leak-failed-atom)
        all-complete? (every? #(= :complete (:scope-status %)) results)
        keep? (or failed? (not all-complete?)
                 (some? (System/getenv "KEEP_PARALLEL_TEST_ARTIFACTS")))
        _ (when leak?
            (let [{gating-diffs :gating advisory-diffs :advisory}
                  (leak-diffs pre (snapshot-default-state))]
              (when (seq advisory-diffs)
                (println (str "  leak advisory (reported, not gating): "
                              (str/join ", " advisory-diffs))))
              (if (seq gating-diffs)
                (do
                  (println)
                  (println "LEAK CHECK: FAILED — root registries or artifact dir changed:")
                  (doseq [k gating-diffs] (println "  " k " changed"))
                  (reset! leak-failed-atom true))
                (println "\nLEAK CHECK: clean"))))]
    ;; Serialize per-namespace output (prevent interleaving from concurrent namespaces)
    (println)
    (let [items (mapv (fn [{:keys [sym output err-output]}]
                        {:label (str sym)
                         :failures (summary/first-failing-tests
                                    (str output err-output))})
                      results)]
      (doseq [{:keys [sym result output err-output scope-status scope-failed?]} results]
        (let [label (str sym)]
          (println)
          (println "─────" label "─────")
          (print output)
          (when (not= "" err-output)
            (print err-output))
          (flush)
          (cond
            scope-failed?
            (println (str "  INCOMPLETE  " label "  (scope-status: " scope-status ")"))

            (and (zero? (:fail result)) (zero? (:error result)))
            (println (str "  PASS  " label "  (" (:test result) " tests, scope: " scope-status ")"))

            :else
            (println (str "  FAIL  " label "  " (:fail result) " fail, " (:error result) " errors, "
                          (:test result) " tests")))))
      ;; Summary box
      (println)
      (summary/render-box "Parallel test summary"
                          [(format "%d tests, %d assertions, %d failures, %d errors"
                                   (:test total) (:pass total) (:fail total) (:error total))
                           (format "elapsed: %.2fs  jobs: %d  excluded: %d"
                                   (/ elapsed 1000.0) jobs (count excluded-idx))
                           (if keep?
                             (format "artifacts: %s" tmp-root)
                             "artifacts: cleaned (all passed)")])
      (summary/result-line total elapsed)
      (summary/print-failures items))
    ;; Cleanup — only via ownership-marker-guarded safe-delete!
    (if keep?
      (println "Keeping run root:" tmp-root)
      (try
        (artifact-scope/safe-delete! tmp-root run-id)
        (println "run root cleaned:" tmp-root)
        (catch Throwable e
          (println "WARN: run-root cleanup skipped:" (.getMessage e)))))
    ;; Persist test state for bb test:rerun
    (let [failed-syms (mapv :sym
                            (filter #(or (pos? (:fail (:result %)))
                                         (pos? (:error (:result %)))
                                         (:scope-failed? %))
                                    results))]
      (ts/write-state! {:command *command-line-args*
                        :failed-nses failed-syms}))
    (when failed?
      (System/exit 1))))
