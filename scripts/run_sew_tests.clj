(ns scripts.run-sew-tests
  "Run Sew protocol tests in a single JVM.

   Usage:
     clojure -M:test:with-sew -m scripts.run-sew-tests [group]

   Groups (default: unit):
     unit       — fast unit tests, noop evidence capture
     scenario   — scenario/replay/invariants tests with temp-dir evidence
     all        — runs both groups

   Execution modes (SEW_TEST_MODE):
     shared-sequential    — legacy: namespaces run one at a time sharing the
                            default/root evidence context (this is the current
                            default and preserves existing behaviour).
     isolated-sequential  — each namespace gets a fresh, fully isolated
                            evidence + reporting context, run sequentially.
     isolated-parallel    — same isolation, but namespaces run concurrently
                            on a bounded thread pool.

   Legacy flags (temporary compatibility aliases; conflicting values error):
     SEW_TEST_SEQUENTIAL=1  → shared-sequential
     SEW_TEST_PARALLEL=1    → isolated-parallel

   Concurrency / soak controls:
     SEW_TEST_MODE             one of the modes above
     PARALLEL_TEST_JOBS        max concurrent namespaces (default: capped by CPU)
     SEW_TEST_SEED             integer seed; shuffles parallel submission order
     SEW_TEST_NS_TIMEOUT_MS    per-namespace timeout (default: none)
     SEW_TEST_RUN_ID           run identifier used in artifact-root paths
     SEW_TEST_RESULTS_FILE     path to write machine-readable results EDN
     SEW_TEST_LEAK_CHECK=1     verify root registries/artifact dir unchanged
     SEW_TEST_HASH_ARTIFACTS=1 include content hashes in artifact manifests

   Invariants (see scripts/soak_sew_parallel.sh and the audit scripts):
     - Namespace discovery and sequential loading happen before any execution;
       the namespace list is frozen before dispatch.
     - Namespace futures/workers only return structured results; the coordinator
       sorts them into the frozen order, aggregates, writes test:rerun state
       once, emits the summary, and chooses the exit code."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as t]
            [resolver-sim.evidence.attestation-registry :as ar]
            [resolver-sim.evidence.capture :as cap]
            [resolver-sim.evidence.chain :as chain]
            [resolver-sim.evidence.config :as evcfg]
            [resolver-sim.evidence.node :as node]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.test-util :as tu]
            [scripts.artifact-scope :as artifact-scope]
            [scripts.test-state :as ts]
            [scripts.test-summary :as summary]))

;; ── Fast unit test namespaces (pure domain logic, no evidence assertions) ───

(def unit-test-namespaces
  '[resolver-sim.protocols.sew.accounting-test
    resolver-sim.protocols.sew.alias-test
    resolver-sim.protocols.sew.authority-test
    resolver-sim.protocols.sew.claimable-classification-test
    resolver-sim.protocols.sew.diff-test
    resolver-sim.protocols.sew.dispute-capacity-test
    resolver-sim.protocols.sew.economics-test
    resolver-sim.protocols.sew.force-authorisation-test
    resolver-sim.protocols.sew.held-custody-test-env-test
    resolver-sim.protocols.sew.terminal-state-snapshot-test
    resolver-sim.protocols.sew.authorised-effect-correlation-test
    resolver-sim.protocols.sew.terminal-reservation-test
    resolver-sim.protocols.sew.forking-strategist-expectations-test
    resolver-sim.protocols.sew.funds-ledger-projection-test
    resolver-sim.protocols.sew.governance-gates-test
    resolver-sim.protocols.sew.governance-identity-test
    resolver-sim.protocols.sew.governance-authorization-test
    resolver-sim.protocols.sew.governance-test
    resolver-sim.protocols.sew.idempotence-checklist-test
    resolver-sim.protocols.sew.lifecycle-test
    resolver-sim.protocols.sew.phase-k-test
    resolver-sim.protocols.sew.phase-m-test
    resolver-sim.protocols.sew.properties-test
    resolver-sim.protocols.sew.registry-immutability-test
    resolver-sim.protocols.sew.related-claims-test
    resolver-sim.protocols.sew.research-resolution-test
    resolver-sim.protocols.sew.resolution-test
    resolver-sim.protocols.sew.resolver-yield-accrual-test
    resolver-sim.protocols.sew.snapshot-boundary-test
    resolver-sim.protocols.sew.snapshot-test
    resolver-sim.protocols.sew.state-machine-test
    resolver-sim.protocols.sew.temporal-boundary-test
    resolver-sim.protocols.sew.temporal-generator-test
    resolver-sim.protocols.sew.trace-export-idempotency-test
    resolver-sim.protocols.sew.yield.failure-test
    resolver-sim.protocols.sew.yield.finalize-parity-test
    resolver-sim.protocols.sew.yield.policy-test
    resolver-sim.protocols.sew.yield-reorg-race-test
    resolver-sim.protocols.sew.yield-solvency-test
    resolver-sim.assurance.force-authorisation-portability-test
    resolver-sim.assurance.parameter-attribution-test
    resolver-sim.assurance.authorised-effect-correlation-test
    resolver-sim.evidence.staged-capture-test
    resolver-sim.io.content-addressed-store-test
    resolver-sim.benchmark.game-theory-validation-test
    resolver-sim.benchmark.force-authorisation-consumption-v2-test
    resolver-sim.benchmark.force-authorised-execution-evidence-v2-test
    resolver-sim.benchmark.sew-pre-application-test
    resolver-sim.protocols.sew.slashing-test
    resolver-sim.protocols.sew.evidence.slashing-test])

;; ── Slow scenario test namespaces (full replay, evidence chain assertions) ──

(def scenario-test-namespaces
  '[resolver-sim.contract-model.replay-batch-sew-test
    resolver-sim.protocols.sew.adversarial-test
    resolver-sim.protocols.sew.dispute-resolution-coverage-test
    resolver-sim.protocols.sew.evidence.slashing-test
    resolver-sim.protocols.sew.financial.finality-hardening-test
    resolver-sim.protocols.sew.financial.finality-test
    resolver-sim.protocols.sew.financial.loss-test
    resolver-sim.protocols.sew.financial.solvency-test
    resolver-sim.protocols.sew.integration-test
    resolver-sim.protocols.sew.invariant-registry-test
    resolver-sim.protocols.sew.invariant-runner-test
    resolver-sim.protocols.sew.invariants.solvency-test
    resolver-sim.protocols.sew.invariants.temporal-test
    resolver-sim.protocols.sew.replay-bridge-test
    resolver-sim.protocols.sew.replay-dedupe-policy-test
    resolver-sim.protocols.sew.replay-event-id-scenario-test
    resolver-sim.protocols.sew.replay-idempotency-test
    resolver-sim.protocols.sew.replay-test
    resolver-sim.protocols.sew.require-event-id-test
    resolver-sim.protocols.sew.runner-parity-test])

;; ── Namespaces excluded from the parallel lane ──────────────────────────────
;;
;; Derived from scripts/audit_parallel_safety.clj (HARD hazards): these
;; namespaces mutate process-global state in a way that is not safe to overlap
;; with any other namespace (with-redefs on shared non-dynamic vars, fixed
;; /tmp path writes).  They always run in the sequential lane even when the
;; mode is isolated-parallel.  The audit exits non-zero if a HARD-hazard
;; namespace is missing from this set, so the two stay in agreement.

(def parallel-excluded-namespaces
  '#{resolver-sim.protocols.sew.accounting-test
     resolver-sim.protocols.sew.force-authorisation-test
     resolver-sim.protocols.sew.authorised-effect-correlation-test
     resolver-sim.benchmark.game-theory-validation-test
     resolver-sim.benchmark.force-authorised-execution-evidence-v2-test
     resolver-sim.benchmark.sew-pre-application-test})

;; ── Sequential namespace loading (before dispatch) ──────────────────────────

(defn- load-all!
  "Require every namespace sequentially before execution begins.  Concurrent
   require can expose unrelated hazards (init side effects, alter-var-root,
   generated files, class compilation), so this is deliberately sequential and
   happens before the frozen namespace list is dispatched."
  [syms]
  (doseq [sym syms]
    (try
      (require sym)
      (catch Throwable t
        (println "WARN: failed to load" sym ":" (.getMessage t))))))

;; ── Execution modes ─────────────────────────────────────────────────────────

(def execution-modes
  {:shared-sequential   {:label "legacy shared context, sequential"
                         :parallel? false
                         :fresh-context? false}
   :isolated-sequential {:label "per-namespace fresh context, sequential"
                         :parallel? false
                         :fresh-context? true}
   :isolated-parallel   {:label "per-namespace fresh context, parallel"
                         :parallel? true
                         :fresh-context? true}})

(defn parse-mode
  "Resolve the execution mode from SEW_TEST_MODE and the legacy compatibility
   flags.  Conflicting legacy flags are rejected."
  []
  (let [mode-env (System/getenv "SEW_TEST_MODE")
        legacy-seq? (= "1" (System/getenv "SEW_TEST_SEQUENTIAL"))
        legacy-par? (= "1" (System/getenv "SEW_TEST_PARALLEL"))]
    (when (and legacy-seq? legacy-par?)
      (throw (ex-info "SEW_TEST_SEQUENTIAL and SEW_TEST_PARALLEL cannot both be 1"
                      {:sequential true :parallel true})))
    (let [mode (cond mode-env (keyword mode-env)
                     legacy-seq? :shared-sequential
                     legacy-par? :isolated-parallel
                     :else :shared-sequential)]
      (when-not (contains? execution-modes mode)
        (throw (ex-info (str "Unknown SEW_TEST_MODE: " mode-env)
                        {:mode mode-env :expected (vec (keys execution-modes))})))
      mode)))

(defn parse-jobs
  "Number of concurrent namespace workers.  Capped defensively: at least 1,
   never more than the namespace count, and the default is bounded by a fixed
   cap rather than raw CPU count (single-core hosts and memory-constrained CI)."
  [n-ns]
  (let [cpus (.availableProcessors (Runtime/getRuntime))
        default (max 1 (min 8 (max 1 (dec cpus))))
        jobs (or (some-> (System/getenv "PARALLEL_TEST_JOBS") Integer/parseInt)
                 default)]
    (max 1 (min n-ns jobs))))

;; ── Artifact roots ──────────────────────────────────────────────────────────

(defn- temp-root
  "Temporary root for per-namespace artifact dirs.  Overridable so concurrent
   processes never collide (PRF_TEST_TMP_ROOT)."
  []
  (or (System/getenv "PRF_TEST_TMP_ROOT")
      (System/getProperty "java.io.tmpdir")))

(defn make-artifact-dir
  "Create an isolated artifact dir under the temp root, scoped by run id and
   namespace index to avoid collisions between concurrent processes.  Carries
   an ownership marker so cleanup never deletes an unowned path."
  [run-id idx sym]
  (let [dir (str (temp-root) "/sew-run-" run-id "/" (format "%03d" idx) "-" (munge (str sym)))]
    (artifact-scope/write-owner-marker! dir {:run-id run-id :namespace sym})
    dir))

(defn- capture-for
  "Evidence capture strategy per group: unit tests are noop (no disk I/O),
   scenario tests use the default real capture."
  [group]
  (if (= group "unit")
    tu/noop-capture
    cap/*capture-event-evidence!*))

;; ── Default-state leak tripwire ─────────────────────────────────────────────

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

(defn snapshot-default-state
  "Snapshot process-global state before/after an isolated run.

   Returns {:gating {...} :advisory {...}}:
     :gating  — evidence/node/attestation registries + shared artifact dir;
                these MUST be unchanged by isolated runs (hard gate).
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

(defn leak-diffs
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

;; ── Per-namespace execution ─────────────────────────────────────────────────

(defn- apply-isolation
  "Apply the mode's isolation stack around thunk and return
   [result scope artifact-dir out err].

   Wrapper construction order (see review):
     1. reporting + artifact-dir + capture bindings (outermost),
     2. chain fresh evidence context,
     3. node fresh registry,
     4. attestation fresh registry,
     5. artifact scope (records every publication for the per-namespace
        manifest; sees the isolated artifact dir).
   Binding the artifact dir first means any fresh registry that captures its
   persistence directory/lock during construction is already on the isolated
   root, never the shared/default path."
  [mode group run-id idx sym thunk]
  (let [artifact-dir (make-artifact-dir run-id idx sym)
        out (java.io.StringWriter.)
        err (java.io.StringWriter.)
        pw (java.io.PrintWriter. out)
        pwe (java.io.PrintWriter. err)
        scope-config {:run-id run-id
                      :namespace sym
                      :namespace-root artifact-dir
                      :scope-id (str run-id "-" idx)}
        wrap
        (fn [f]
          (if (:fresh-context? (execution-modes mode))
            (chain/with-fresh-evidence-context*
             (fn []
               (node/with-fresh-registry
                (ar/with-fresh-registry*
                 (fn [] (f))))))
            (f)))
        [res scope] (artifact-scope/with-scope
                     scope-config
                     (fn []
                       (binding [*out* pw
                                 *err* pwe
                                 t/*test-out* pw
                                 t/*report-counters* (ref t/*initial-report-counters*)
                                 t/*testing-vars* (list)
                                 t/*testing-contexts* (list)
                                 evcfg/*artifact-dir* artifact-dir
                                 chain/*allow-dirty* true
                                 hc/*validate-intent-constraints* true
                                 cap/*capture-event-evidence!* (capture-for group)]
                         (wrap thunk))))]
    [res scope artifact-dir (str out) (str err)]))

(defn run-one
  "Execute a single namespace under the mode's isolation stack and return a
   structured result including the finalised artifact-scope manifest.  Never
   touches shared reporting/rerun state itself."
  [mode group run-id idx sym]
  (let [start (System/currentTimeMillis)
        strict-artifacts? (= "1" (System/getenv "SEW_TEST_STRICT_ARTIFACTS"))
        execute
        (fn []
          (try
            (t/run-tests sym)
            (catch Throwable ex
              (when-not (instance? InterruptedException ex)
                (println "ERROR in namespace" sym ":" (.getMessage ex))
                (.printStackTrace ex))
              {:test 0 :pass 0 :fail 0 :error 1})))
        [result scope artifact-dir out err] (apply-isolation mode group run-id idx sym execute)
        ;; Close the scope; on worker failure or strict verification failure,
        ;; record an incomplete manifest instead of letting it throw away the
        ;; result (crash/incomplete-run semantics).
        [manifest scope-failed?]
        (try
          [(artifact-scope/finalize-scope! scope strict-artifacts?) false]
          (catch clojure.lang.ExceptionInfo e
            [(or (:manifest (ex-data e))
                 (artifact-scope/mark-incomplete! scope))
             true]))]
    {:idx idx
     :namespace sym
     :tests (:test result)
     :assertions (:pass result)
     :failures (:fail result)
     :errors (+ (:error result) (if scope-failed? 1 0))
     :output out
     :err-output err
     :artifact-dir artifact-dir
     :manifest manifest
     :scope-status (:scope-status manifest)
     :elapsed-ms (- (System/currentTimeMillis) start)}))

(defn context-identities
  "Run the mode's real isolation stack and return the bound identities for one
   namespace context: artifact dir, node registry + persistence lock,
   attestation registry, evidence registry, scenario-evidence atom, and chain
   cursor.  Used by scripts/verify_context_isolation.clj."
  [mode group run-id idx sym]
  (let [id-thunk
        (fn []
          {:artifact-dir (evcfg/artifact-dir)
           :node-registry node/*node-registry*
           :node-lock node/*node-persistence-lock*
           :attestation-registry ar/*attestation-registry*
           :evidence-registry (chain-private 'evidence-registry-atom)
           :scenario-evidence (chain-private 'scenario-evidence-atom)
           :chain-cursor (chain-private 'chain-cursor)})
        [identities _ _ _ _] (apply-isolation mode group run-id idx sym id-thunk)]
    identities))


;; ── Schedulers ──────────────────────────────────────────────────────────────

(defn shuffle-with-seed
  "Deterministic Fisher-Yates shuffle for reproducible soak schedules."
  [coll seed]
  (let [r (java.util.Random. seed)
        v (vec coll)]
    (loop [items v result []]
      (if (empty? items)
        result
        (let [i (.nextInt r (count items))]
          (recur (into (subvec items 0 i) (subvec items (inc i)))
                 (conj result (nth items i))))))))

(defn run-sequential
  [task n]
  (mapv task (range n)))

(defn run-parallel
  "Run tasks on a bounded thread pool.  Returns results (possibly in shuffled
   submission order); the coordinator re-sorts into canonical namespace order."
  [task n jobs timeout-ms seed]
  (let [pool (java.util.concurrent.Executors/newFixedThreadPool jobs)
        order (if seed (shuffle-with-seed (range n) seed) (vec (range n)))
        futures (mapv (fn [idx]
                        [idx (.submit pool ^java.util.concurrent.Callable (fn [] (task idx)))])
                      order)
        _ (.shutdown pool)
        results (mapv (fn [[idx f]]
                        (try
                          (if timeout-ms
                            (.get f timeout-ms java.util.concurrent.TimeUnit/MILLISECONDS)
                            (.get f))
                          (catch java.util.concurrent.TimeoutException _
                            (.cancel f true)
                            {:idx idx :namespace nil :timed-out true
                             :tests 0 :assertions 0 :failures 0 :errors 1
                             :output (str "TIMEOUT after " timeout-ms "ms\n")
                             :err-output "" :artifact-dir nil
                             :scope-status :incomplete
                             :manifest {:scope-status :incomplete
                                        :problems [{:type :scope-timeout}]}
                             :elapsed-ms timeout-ms})
                          (catch java.util.concurrent.ExecutionException e
                            (throw (or (.getCause e) e)))))
                      futures)]
    (when timeout-ms
      (.shutdownNow pool))
    results))

;; ── Group orchestration ─────────────────────────────────────────────────────

(defn- run-group
  [label syms group mode run-id jobs timeout-ms seed]
  (println)
  (println (str "─── " label " " (count syms) " namespaces ───"))
  (let [frozen (vec syms)
        n (count frozen)
        start (System/currentTimeMillis)
        task (fn [idx] (run-one mode group run-id idx (nth frozen idx)))]
    (if (and (:parallel? (execution-modes mode))
             (seq parallel-excluded-namespaces))
      ;; Parallel lane with a sequential safety lane: namespaces that mutate
      ;; process-global state never overlap the pool.
      (let [excluded-idx (filterv #(contains? parallel-excluded-namespaces (nth frozen %)) (range n))
            eligible-idx (filterv #(not (contains? parallel-excluded-namespaces (nth frozen %))) (range n))]
        (when (seq excluded-idx)
          (println (str "  parallel-excluded (sequential lane): " (count excluded-idx)))
          (doseq [i excluded-idx] (println (str "    " (nth frozen i)))))
        (let [excl (mapv (fn [i] (task i)) excluded-idx)
              elig (run-parallel (fn [pos] (task (nth eligible-idx pos)))
                                 (count eligible-idx) jobs timeout-ms seed)
              per-ns (sort-by :idx (concat excl elig))]
          {:label label
           :group group
           :per-ns per-ns
           :elapsed-ms (- (System/currentTimeMillis) start)}))
      (let [per-ns (if (:parallel? (execution-modes mode))
                     (run-parallel task n jobs timeout-ms seed)
                     (run-sequential task n))]
        {:label label
         :group group
         :per-ns (sort-by :idx per-ns)
         :elapsed-ms (- (System/currentTimeMillis) start)}))))

;; ── Coordinator (reporting, rerun state, exit code) ─────────────────────────

(defn- normalize-result
  "Semantic per-namespace fingerprint derived from the scope manifest (the
   authoritative record of what the namespace published), not a directory
   walk.  Only :complete scopes contribute equivalence-bearing artifact data."
  [r]
  (let [output (str (:output r) (:err-output r))
        manifest (or (:manifest r) {})
        artifacts (vec (sort-by :relative-path (:artifacts manifest)))]
    {:namespace (:namespace r)
     :tests (:tests r)
     :assertions (:assertions r)
     :failures (:failures r)
     :errors (:errors r)
     :failing-vars (vec (summary/first-failing-tests output))
     :scope-status (:scope-status manifest)
     :declared-artifacts (mapv (fn [a]
                                 {:logical-id (:logical-id a)
                                  :relative-path (:relative-path a)
                                  :content-hash (:content-hash a)
                                  :size (:size a)})
                               artifacts)
     :artifact-count (count artifacts)
     :undeclared-files (vec (sort (:undeclared-files manifest)))
     :scope-problems (vec (sort-by :type (:problems manifest)))
     :elapsed-ms (:elapsed-ms r)
     :timed-out? (boolean (:timed-out r))
     ;; full output retained for diagnostics on failed soak comparisons
     :output output
     :manifest manifest}))

(defn- write-results-file!
  [path mode run-id seed jobs per-ns]
  (io/make-parents path)
  (spit path
        (pr-str {:schema-version 2
                 :mode (name mode)
                 :run-id run-id
                 :seed seed
                 :jobs jobs
                 :java (System/getProperty "java.version")
                 :cpus (.availableProcessors (Runtime/getRuntime))
                 :namespaces (mapv :namespace per-ns)
                 :results (mapv normalize-result per-ns)})))

(defn- print-per-namespace
  [per-ns]
  (doseq [{:keys [namespace output err-output tests failures errors timed-out?]} per-ns]
    (let [label (str namespace)]
      (println)
      (println "─────" label "─────")
      (print output)
      (when (and err-output (not= "" err-output))
        (print err-output))
      (flush)
      (cond
        timed-out?
        (println (str "  TIMEOUT  " label))

        (and (zero? failures) (zero? errors))
        (println (str "  PASS  " label "  (" tests " tests)"))

        :else
        (println (str "  FAIL  " label "  " failures " fail, " errors " errors, "
                      tests " tests"))))))

(defn- finalize
  "Coordinator-only: leak report, results file, per-namespace output in frozen
   order, shared summary, one test:rerun state write, exit code."
  [group-results mode run-id seed jobs leak? pre leak-failed-atom]
  (let [per-ns (vec (mapcat :per-ns group-results))
        totals {:test  (apply + (map :tests per-ns))
                :pass  (apply + (map :assertions per-ns))
                :fail  (apply + (map :failures per-ns))
                :error (apply + (map :errors per-ns))}
        total-elapsed (apply + (map :elapsed-ms group-results))
        failed-nses (vec (distinct
                          (map :namespace
                               (filter #(pos? (+ (:failures %) (:errors %))) per-ns))))
        items (mapv (fn [r]
                      {:label (str (:namespace r))
                       :failures (summary/first-failing-tests
                                  (str (:output r) (:err-output r)))})
                    per-ns)]
    ;; 1. Leak tripwire — only isolated modes must leave root registries/shared
    ;;    artifact locations untouched; shared-sequential intentionally uses
    ;;    root registries, so differences there are expected and reported
    ;;    without failing.  Gating diffs = evidence/node/attestation state +
    ;;    shared artifact dir.  Advisory diffs (system props, locale, threads)
    ;;    are reported but never fail.
    (when leak?
      (let [{gating-diffs :gating advisory-diffs :advisory} (leak-diffs pre (snapshot-default-state))
            gated? (:fresh-context? (execution-modes mode))]
        (when (seq advisory-diffs)
          (println (str "  leak advisory (reported, not gating): "
                        (str/join ", " advisory-diffs))))
        (if (seq gating-diffs)
          (do
            (println)
            (println (str "LEAK CHECK: " (if gated? "FAILED" "changes (expected in shared mode)")
                          " — root registries or artifact dir:"))
            (doseq [k gating-diffs] (println "  " k "changed"))
            (when gated?
              (reset! leak-failed-atom true))
            (flush))
          (println "\nLEAK CHECK: clean"))))
    ;; 2. Machine-readable results (soak comparison input)
    (when-let [rf (System/getenv "SEW_TEST_RESULTS_FILE")]
      (write-results-file! rf mode run-id seed jobs per-ns))
    ;; 3. Per-namespace output in canonical (frozen) order
    (println)
    (print-per-namespace per-ns)
    ;; 4. Shared summary
    (println)
    (summary/render-box "Sew test batch summary"
                        [(format "%d tests, %d assertions, %d failures, %d errors"
                                 (:test totals) (:pass totals) (:fail totals) (:error totals))
                         (format "elapsed: %.2fs  mode: %s" (/ total-elapsed 1000.0) (name mode))])
    (summary/result-line totals total-elapsed)
    (summary/print-failures items)
    ;; 5. Single rerun-state write (coordinator only)
    (ts/write-state! {:command *command-line-args*
                      :failed-nses failed-nses})
    (flush)
    (when (or (pos? (+ (:fail totals) (:error totals)))
              @leak-failed-atom)
      (System/exit 1))))

;; ── Main ────────────────────────────────────────────────────────────────────

(defn -main
  [& args]
  (let [group (or (first args) "unit")
        mode (parse-mode)
        run-id (or (System/getenv "SEW_TEST_RUN_ID")
                   (str (System/currentTimeMillis) "-" (java.util.UUID/randomUUID)))
        seed (some-> (System/getenv "SEW_TEST_SEED") Long/parseLong)
        timeout-ms (some-> (System/getenv "SEW_TEST_NS_TIMEOUT_MS") Long/parseLong)
        leak? (= "1" (System/getenv "SEW_TEST_LEAK_CHECK"))
        leak-failed-atom (atom false)
        ns-override (some-> (System/getenv "SEW_TEST_NS_LIST") read-string)
        groups (cond
                 ns-override
                 [["custom" (vec ns-override) "unit"]]

                 (= group "unit")
                 [["unit" unit-test-namespaces "unit"]]

                 (= group "scenario")
                 [["scenario" scenario-test-namespaces "scenario"]]

                 (= group "all")
                 [["unit" unit-test-namespaces "unit"]
                  ["scenario" scenario-test-namespaces "scenario"]]

                 :else
                 (do (println "Unknown group:" group ". Use: unit, scenario, or all")
                     (System/exit 1)))
        jobs (parse-jobs (apply + (map (comp count second) groups)))]
    (println (str "SEW test mode: " (name mode)
                  "  group: " group
                  "  run-id: " run-id
                  "  jobs: " jobs
                  (when seed (str "  seed: " seed))))
    ;; Ownership marker for the run root (namespace roots carry their own).
    (artifact-scope/write-owner-marker! (str (temp-root) "/sew-run-" run-id)
                                        {:run-id run-id :namespace :run-root})
    ;; Load namespaces sequentially first (root-level side effects are
    ;; legitimate here), THEN snapshot default state so the leak tripwire only
    ;; sees test-execution-time changes to root registries/artifact locations.
    (doseq [[_ syms _] groups]
      (println "Loading" (count syms) "namespaces...")
      (load-all! syms))
    (let [pre (when leak? (snapshot-default-state))
          group-results (mapv (fn [[label syms g]]
                                (run-group label syms g mode run-id jobs timeout-ms seed))
                              groups)
          _ (when (= "1" (System/getenv "SEW_TEST_CLEANUP_RUN"))
              ;; Guarded cleanup: delete the run root only if every scope is
              ;; complete and the ownership marker matches this run id.
              (let [all-complete? (every? (fn [gr]
                                            (every? #(= :complete (:scope-status %))
                                                    (:per-ns gr)))
                                          group-results)]
                (if all-complete?
                  (try
                    (artifact-scope/safe-delete! (str (temp-root) "/sew-run-" run-id) run-id)
                    (println "\nrun root cleaned (all scopes complete):"
                             (str (temp-root) "/sew-run-" run-id))
                    (catch Throwable e
                      (println "WARN: run-root cleanup skipped:" (.getMessage e))))
                  (println "\nrun root retained (incomplete scopes):"
                           (str (temp-root) "/sew-run-" run-id)))))]
      (finalize group-results mode run-id seed jobs leak? pre leak-failed-atom))))
