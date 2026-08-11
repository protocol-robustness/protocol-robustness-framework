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
     PARALLEL_TEST_RUN_ROOT — stage per-namespace artifact roots under this
                           directory (instead of /tmp/parallel-run-<run-id>) for
                           later consolidation by
                           scripts/evidence/consolidate_test_artifacts.py.  When
                           set, the run root is left in place (no auto-cleanup)
                           and is excluded from the leak tripwire's shared
                           artifact-dir listing.
     PARALLEL_TEST_LEAK_CHECK — set to 1 to snapshot process-global registries and
                           the shared artifact dir before dispatch and verify they
                           are left untouched afterwards (gating on diff; mirrors
                           run-sew-tests SEW_TEST_LEAK_CHECK)
     KEEP_TEST_ARTIFACTS — set to any truthy value to preserve the run
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
  "Namespaces that never overlap the parallel pool; they always run first in a
   sequential lane.  Two classes:
     - audit-flagged hard process-global hazards (with-redefs/alter-var-root on
       shared vars, fixed path writes, port/server binding) from the
       scripts/audit-parallel-safety.clj hard-pattern scan.  The audit gate
       (scripts.audit-parallel-safety parallel-test-runner|all) fails if a
       hard-hazard namespace in parallel-runner-namespaces is missing here.
     - scenario-group members (scripts.run-sew-tests scenario-test-namespaces),
       which are validated sequential-only (see run-sew-tests GROUP POLICY) and
       therefore must not run in a parallel pool.
   `resolver-sim.community.result-test` is a static-scan false positive (the
   fixed /tmp/ paths are fixture literal strings, not writes) but is kept in
   the lane so the audit gate stays deterministic.
   Override with PARALLEL_TEST_EXCLUDE_NS (comma/space separated); empty string
   disables."
  '#{resolver-sim.evidence.chain-test
     resolver-sim.evidence.commitment-root-test
     resolver-sim.evidence.node-test
     resolver-sim.hash.attestor-hash-test
     resolver-sim.hash.canonical-test
      resolver-sim.protocols.sew.dispute-resolution-coverage-test
      resolver-sim.protocols.sew.replay-test
      resolver-sim.contract-model.replay-batch-sew-test
      resolver-sim.validation.scenario-registry-test
     resolver-sim.benchmark.game-theory-validation-test
     resolver-sim.community.result-test
     ;; with-redefs on selection/candidate-digest-hex (shared static var); both
     ;; redef the same var so they must never overlap the pool.
     resolver-sim.allocation.kernel-test
     resolver-sim.allocation.selection-test})

(def parallel-runner-namespaces
  "Union of every namespace handed to scripts.parallel-test-runner by the
   canonical invocations: test.sh run_unit + run_generators; bb.edn
   test:framework, test:framework:parallel, test:evidence,
   test:evidence-known-gaps, test:quick-sew, test:quick-sew:concurrent,
   test:community.  This is the single source of truth for the
   scripts.audit-parallel-safety parallel-test-runner mode: any namespace in
   this set carrying an audit hard hazard, or any scenario-group member here,
   must be in parallel-excluded-namespaces or the audit gate fails.
   Keep in sync with those invocation lists."
  '[    resolver-sim.assurance.cancellation-gates-test
    resolver-sim.assurance.consumer-enforcement-test
    resolver-sim.assurance.custody-summary-test
    resolver-sim.assurance.deterministic-evidence-test
    resolver-sim.assurance.three-member-authority-test
    resolver-sim.allocation.certificate-test
    resolver-sim.allocation.claim-consumption-receipt-test
    resolver-sim.allocation.cli-test
    resolver-sim.allocation.context-test
    resolver-sim.allocation.native-evidence-test
    resolver-sim.allocation.proposal-test
    resolver-sim.allocation.reconciliation-test
    resolver-sim.allocation.roots-test
    resolver-sim.allocation.round-state-test
    resolver-sim.allocation.vectors-test
    resolver-sim.pro-rata.allocation-test
    resolver-sim.pro-rata.dependency-boundary-test
    resolver-sim.test-vectors.pro-rata-test
    resolver-sim.benchmark.packs.partial-fill.pro-rata-evidence-test
    resolver-sim.yield.partial-fill-test
    resolver-sim.yield.pro-rata-accounting-test
    resolver-sim.yield.pro-rata-claims-test
    resolver-sim.yield.pro-rata-propagation-properties-test
    resolver-sim.yield.strategic-partial-fill-test
    resolver-sim.benchmark.decision-subject-test
    resolver-sim.benchmark.game-theory-validation-test
    resolver-sim.benchmark.packs.partial-fill.evidence-test
    resolver-sim.benchmark.researcher-decision-v2-test
    resolver-sim.claim-outcome-test
    resolver-sim.community.core-test
    resolver-sim.community.design-scrutiny-test
    resolver-sim.community.end-to-end-test
    resolver-sim.community.result-test
    resolver-sim.contract-model.replay-batch-appeal-test
    resolver-sim.contract-model.replay-batch-sew-test
    resolver-sim.contract-model.replay-batch-slash-domain-test
    resolver-sim.contract-model.replay-batch-test
    resolver-sim.core-tests
    resolver-sim.deferral-test
    resolver-sim.economics.payoffs-test
    resolver-sim.economics.with-bounty.application-plan-test
    resolver-sim.economics.with-bounty.replay-test
    resolver-sim.economics.with-bounty.stage-a-test
    resolver-sim.economics.with-bounty.verification-test
    resolver-sim.evidence.attestation-adversarial-test
    resolver-sim.evidence.attestation-dag-test
    resolver-sim.evidence.attestation-known-gaps-test
    resolver-sim.evidence.attestation-node-test
    resolver-sim.evidence.attestation-policy-test
    resolver-sim.evidence.attestation-registry-test
    resolver-sim.evidence.attestation-test
    resolver-sim.evidence.chain-test
    resolver-sim.evidence.commitment-root-test
    resolver-sim.evidence.finalization-test
    resolver-sim.evidence.integrity-test
    resolver-sim.evidence.node-test
    resolver-sim.evidence.qol-test
    resolver-sim.evidence.registry-test
    resolver-sim.evidence.revocation-test
    resolver-sim.evidence.timestamping-test
    resolver-sim.financial.pro-rata-characterization-test
    resolver-sim.generators.equilibrium-test
    resolver-sim.generators.fixtures-test
    resolver-sim.grounded-amount-test
    resolver-sim.hash.algorithm-test
    resolver-sim.hash.attestor-hash-test
    resolver-sim.hash.canonical-test
    resolver-sim.hash.concat-properties-test
    resolver-sim.hash.framing-view-test
    resolver-sim.hash.sequence-test
    resolver-sim.hash.admission-profile-test
    resolver-sim.io.scenario-fixture-parity-test
    resolver-sim.io.scenario-runner-test
    resolver-sim.ordering.priority-composition-test
    resolver-sim.ordering.priority-test
    resolver-sim.properties.invariants-test
    resolver-sim.protocol-alignment-test
    resolver-sim.protocols.sew.dispute-resolution-coverage-test
    resolver-sim.protocols.sew.forking-strategist-expectations-test
    resolver-sim.protocols.sew.phase-k-test
    resolver-sim.protocols.sew.phase-m-test
    resolver-sim.protocols.sew.replay-test
    resolver-sim.protocols.sew.resolution-test
    resolver-sim.protocols.sew.slashing-test
    resolver-sim.protocols.sew.trace-export-idempotency-test
    resolver-sim.protocols.sew.with-bounty-test
    resolver-sim.run.overview-test
    resolver-sim.scenario.equilibrium-test
    resolver-sim.scenario.expectations-test
    resolver-sim.scenario.suites-test
    resolver-sim.sim.defection-test
    resolver-sim.sim.multi-epoch-test
    resolver-sim.sim.strategy-adaptation-test
    resolver-sim.sim.waterfall-test
    resolver-sim.time.context-test
    resolver-sim.time.model-test
    resolver-sim.validation.scenario-registry-test
    resolver-sim.workflow-group-test])

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
  (if (and dir (.exists (io/file dir)))
    (let [staged (some-> (System/getenv "PARALLEL_TEST_RUN_ROOT")
                         io/file .getCanonicalFile .toPath)]
      (->> (file-seq (io/file dir))
           (filter #(.isFile %))
           ;; Roots staged under PARALLEL_TEST_RUN_ROOT are owned by this run
           ;; for later collection; exclude them so the leak tripwire only
           ;; gates on the canonical/shared artifact location.
           (remove (fn [f]
                     (and staged
                          (.startsWith (.toPath (.getCanonicalFile f)) staged))))
           (sort-by #(.getName %))
           (mapv (fn [f] [(str f) (.length f)]))))
    []))

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
        staged-root (System/getenv "PARALLEL_TEST_RUN_ROOT")
        tmp-root (or staged-root
                     (str (System/getProperty "java.io.tmpdir") "/parallel-run-" run-id))
        _ (when-not staged-root
            (artifact-scope/write-owner-marker! tmp-root {:run-id run-id :namespace :run-root}))
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
        ;; Sequential safety lane runs FIRST and never overlaps the pool.  The
        ;; pool futures are only submitted after every excluded namespace
        ;; completes: submitting them earlier let the pool overlap the lane
        ;; (clojure.core/future dispatches immediately), recreating the exact
        ;; non-deterministic with-redefs race class that failed the
        ;; run-sew-tests 8-run soak for accounting-test.
        excl-results (mapv task excluded-idx)
        sem (java.util.concurrent.Semaphore. jobs)
        futures (mapv (fn [idx]
                        [idx (future
                               (.acquire sem)
                               (try
                                 (task idx)
                                 (finally
                                   (.release sem))))])
                      eligible-idx)
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
                 (some? (System/getenv "KEEP_TEST_ARTIFACTS")))
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
    ;; Cleanup — staged roots are left in place for the collector; otherwise
    ;; only via ownership-marker-guarded safe-delete!
    (if (or staged-root keep?)
      (println (if staged-root
                 (str "Artifacts staged for collection (PARALLEL_TEST_RUN_ROOT): " tmp-root)
                 (str "Keeping run root: " tmp-root)))
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
