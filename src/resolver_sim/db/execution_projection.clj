(ns resolver-sim.db.execution-projection
  "Optional XTDB projection of completion-gated execution package indexes.

   This namespace is downstream of package completion and never participates in
   semantic execution or authoritative publication.

   COMPLETION GATE. `project-run!`/`resolve-projection` derive every row from
   `package-index/resolve-validation-context`, the authoritative terminal-
   completion resolver. That context only yields a trusted `:package-index` when
   the exact completion seal exists (`lifecycle_status = completed`) and binds
   the package-index bytes by hash + length; completeness and integrity reports
   gate `build-projection`. A pre-completion or partially-written package cannot
   satisfy the entry point and returns nil.

   TIME SEMANTICS. `:valid-from` is the terminal completion (execution/completion)
   time read from the completion's `completed_at`, persisted as the XTDB valid
   time `_valid_from`. It is NOT simulated/protocol event time (trace `:time` /
   world `:block-ts`). `_valid_from` must never be reinterpreted as canonical
   protocol or execution event time.

   PERSISTENCE IDENTITY. Rows use a deterministic `_id` (run/execution identity)
   and deterministic `_valid_from`, so re-inserting the same entity at the same
   valid-time overwrites the same XTDB bitemporal version (idempotent); distinct
   identities stay distinct observations.

   BENCHMARK EXECUTIONS. `sim_benchmark_executions` rows are derived from the
   existing canonical `benchmark-artifact-index.v1` file referenced by the
   package index's `:benchmark-index` artifact (its per-case `:executions`),
   NOT from a field on the package-index map. A benchmark package whose index
   carries no `:benchmark-index` artifact yields no execution rows.

   EPOCH VALID-TIME FALLBACK. No completion producer currently writes
   `completed_at`/`valid_from`, so `:valid-from` is nil for real packages and
   `_valid_from` falls back to the epoch instant (2000-01-01T00:00:00Z) so the
   row is visible to any realistic AS-OF query. Epoch is a deterministic,
   non-protocol marker — its degraded meaning is \"valid since 2000-01-01\", so
   an AS-OF query at/before that instant does not see the row. It is not
   simulated/protocol event time, execution time, or a wall-clock completion
   time; no wall-clock nondeterminism is introduced to remove it."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [next.jdbc :as jdbc]
            [resolver-sim.db.xtdb :as xtdb]
            [resolver-sim.run.package-index :as package-index]))

(defn- first-present [m ks]
  (some (fn [k] (when (some? (get m k)) (get m k))) ks))

(defn- completion-value [completion names]
  (first-present completion names))

(defn- index-value [index & ks]
  (first-present index ks))

(defn- artifact-index-executions
  "Read per-case execution entries from the existing canonical
   benchmark-artifact-index.v1 file referenced by the package index's
   `:benchmark-index` artifact. Returns [] when the ref is absent or the file
   is unreadable, so a package without a benchmark artifact index yields no
   execution rows rather than failing the projection."
  [run-root index]
  (let [ref (get-in index [:artifacts :benchmark-index :ref])
        file (when (and run-root (string? ref)) (io/file run-root ref))]
    (if (and file (.isFile file))
      (try
        (vec (:executions (edn/read-string (slurp file))))
        (catch Exception _ []))
      [])))

(defn build-execution-run
  "Purely project a validated completion and package-index context.

   Callers must pass the result of package-index/resolve-completion-context;
   this function does not validate arbitrary caller-supplied roots or statuses."
  [{:keys [completion index package-index]}]
  {:run-id (completion-value completion ["run_id"])
   :run-type (some-> (completion-value completion ["run_type"]) keyword)
   :status (completion-value completion ["lifecycle_status"])
   :semantic-status (completion-value completion ["semantic_status"])
   :benchmark-id (index-value index :benchmark/id)
   :scenario-id (index-value index :scenario/id)
   :execution-id (index-value index :execution/id)
   :package-index-root (index-value index :run-package/hash)
   :bundle-root (index-value index :bundle/root-hash)
   :input-set-root (index-value index :input-set/root)
   :semantic-composition-root (index-value index :semantic-composition/root)
   :completion-sha256 (completion-value completion ["completion_sha256"])
   :package-index-sha256 (or (:sha256 package-index)
                             (completion-value completion ["run_package_index_sha256"]))
   :package-index-bytes (or (:bytes package-index)
                            (completion-value completion ["run_package_index_bytes"]))
   :valid-from (some-> (completion-value completion ["completed_at" "valid_from"]) xtdb/parse-ts)})

(defn build-benchmark-execution
  "Purely project one exact execution-index entry."
  [{:keys [completion index execution]}]
  {:execution-id (:execution/id execution)
   :run-id (completion-value completion ["run_id"])
   :benchmark-id (:benchmark/id index)
   :scenario-id (:scenario/id execution)
   :case-key (:case/key execution)
   :run-index (:benchmark/run-index execution)
   :run-count (:benchmark/run-count execution)
   :status (:execution/status execution)
   :outcome (:outcome execution)
   :halt-reason (:halt-reason execution)
   :use-case (or (:use-case/id execution) (:use-case execution))
   :evidence-root (:scenario/evidence-root execution)
   :input-root (or (:input/root execution) (:execution/input-root execution))
   :replay-output-sha256 (:scenario/replay-output-sha256 execution)
   :semantic-composition-root (:semantic-composition-root execution)
   :valid-from (some-> (completion-value completion ["completed_at" "valid_from"]) xtdb/parse-ts)})

(defn build-projection
  "Return derived rows only for a valid resolved completion context.

   Benchmark execution rows come from the existing canonical
   benchmark-artifact-index.v1 file (`:benchmark-index` artifact), never from a
   field on the package-index map."
  [{:keys [completion-report completeness-report integrity-report completion package-index run-root]}]
  (when (and (:valid? completion-report)
             (or (nil? completeness-report) (:complete? completeness-report))
             (or (nil? integrity-report) (:valid? integrity-report))
             completion (:index package-index))
    (let [index (:index package-index)
          executions (artifact-index-executions run-root index)]
      {:run (build-execution-run {:completion completion :index index :package-index package-index})
       :benchmarks (mapv #(build-benchmark-execution
                           {:completion completion :index index :execution %})
                         executions)})))

(defn resolve-projection
  "Resolve and validate a completed package, returning its pure projection or nil.
   nil is the precise invalid/incomplete-source contract; a valid source never
   returns nil (see `project-run!`)."
  [run-root]
  (build-projection (package-index/resolve-validation-context run-root)))

(defn- insert-run! [ds row]
  (jdbc/execute! ds
                 [(str "INSERT INTO sim_execution_runs"
                       " (_id, run_type, status, semantic_status, benchmark_id, scenario_id, execution_id,"
                       "  package_index_root, bundle_root, input_set_root, semantic_composition_root,"
                       "  completion_sha256, package_index_sha256, package_index_bytes, _valid_from) VALUES ("
                       (xtdb/sql-str (:run-id row)) ", "
                       (xtdb/sql-str (:run-type row)) ", "
                       (xtdb/sql-str (:status row)) ", "
                       (xtdb/sql-str (:semantic-status row)) ", "
                       (xtdb/sql-str (:benchmark-id row)) ", "
                       (xtdb/sql-str (:scenario-id row)) ", "
                       (xtdb/sql-str (:execution-id row)) ", "
                       (xtdb/sql-str (:package-index-root row)) ", "
                       (xtdb/sql-str (:bundle-root row)) ", "
                       (xtdb/sql-str (:input-set-root row)) ", "
                       (xtdb/sql-str (:semantic-composition-root row)) ", "
                       (xtdb/sql-str (:completion-sha256 row)) ", "
                       (xtdb/sql-str (:package-index-sha256 row)) ", "
                       (xtdb/sql-long (:package-index-bytes row)) ", "
                       (xtdb/sql-ts (:valid-from row))
                       ")")]))

(defn- insert-benchmark! [ds row]
  (jdbc/execute! ds
                 [(str "INSERT INTO sim_benchmark_executions"
                       " (_id, run_id, benchmark_id, scenario_id, case_key, run_index, run_count, status, outcome, halt_reason,"
                       "  use_case, evidence_root, input_root, replay_output_sha256, semantic_composition_root, _valid_from) VALUES ("
                       (xtdb/sql-str (:execution-id row)) ", "
                       (xtdb/sql-str (:run-id row)) ", "
                       (xtdb/sql-str (:benchmark-id row)) ", "
                       (xtdb/sql-str (:scenario-id row)) ", "
                       (xtdb/sql-str (:case-key row)) ", "
                       (xtdb/sql-long (:run-index row)) ", "
                       (xtdb/sql-long (:run-count row)) ", "
                       (xtdb/sql-str (:status row)) ", "
                       (xtdb/sql-str (:outcome row)) ", "
                       (xtdb/sql-str (:halt-reason row)) ", "
                       (xtdb/sql-str (:use-case row)) ", "
                       (xtdb/sql-str (:evidence-root row)) ", "
                       (xtdb/sql-str (:input-root row)) ", "
                       (xtdb/sql-str (:replay-output-sha256 row)) ", "
                       (xtdb/sql-str (:semantic-composition-root row)) ", "
                       (xtdb/sql-ts (:valid-from row))
                       ")")]))

(defn project-run!
  "Project a verified completed package. Returns the pure projection even when
   ds is nil; nil ds performs no writes.

   Return contract: nil is reserved for an invalid/incomplete source (the
   completion gate failed). A valid source always returns a non-nil projection,
   whether or not persistence ran — so callers can unambiguously distinguish
   'valid source + persistence disabled' from 'invalid/incomplete source'."
  [ds run-root]
  (when-let [projection (resolve-projection run-root)]
    (when ds
      (insert-run! ds (:run projection))
      (doseq [row (:benchmarks projection)]
        (insert-benchmark! ds row)))
    projection))

(defn- decode-row [row]
  (cond-> (into {} (map (fn [[k v]] [(keyword "execution" (name k)) v]) row))
    (:source_roots_edn row) (assoc :execution/source-roots (xtdb/parse-edn (:source_roots_edn row)))
    (:checksums_edn row) (assoc :execution/checksums (xtdb/parse-edn (:checksums_edn row)))))

(defn- query-rows [ds sql]
  (if ds
    (mapv decode-row (jdbc/execute! ds [sql] xtdb/opts))
    []))

(defn latest
  "Return the latest execution run, or [] when the datasource is unavailable."
  [ds]
  (if ds
    (first (query-rows ds "SELECT * FROM sim_execution_runs ORDER BY _valid_from DESC, _id DESC LIMIT 1"))
    []))

(defn recent
  "Return up to n execution runs, newest valid-time first."
  [ds n]
  (query-rows ds (str "SELECT * FROM sim_execution_runs ORDER BY _valid_from DESC, _id DESC LIMIT " (long n))))

(defn executions-by-scenario [ds scenario-id]
  (query-rows ds (str "SELECT * FROM sim_benchmark_executions WHERE scenario_id = " (xtdb/sql-str scenario-id) " ORDER BY _id ASC")))

(defn executions-by-use-case [ds use-case]
  (query-rows ds (str "SELECT * FROM sim_benchmark_executions WHERE use_case = " (xtdb/sql-str use-case) " ORDER BY _id ASC")))

(defn failures [ds]
  (query-rows ds "SELECT * FROM sim_benchmark_executions WHERE status <> 'completed' OR outcome IN ('failed', 'error') ORDER BY _id ASC"))

(defn benchmark-executions [ds benchmark-id]
  (query-rows ds (str "SELECT * FROM sim_benchmark_executions WHERE benchmark_id = " (xtdb/sql-str benchmark-id) " ORDER BY _id ASC")))

(defn as-of [ds valid-at]
  (query-rows ds (str "SELECT * FROM sim_execution_runs FOR VALID_TIME AS OF TIMESTAMP '"
                      (.format java.time.format.DateTimeFormatter/ISO_INSTANT (.toInstant valid-at))
                      "' ORDER BY _valid_from DESC, _id DESC")))

(defn recent-at [ds valid-at n]
  (query-rows ds (str "SELECT * FROM sim_execution_runs FOR VALID_TIME AS OF TIMESTAMP '"
                      (.format java.time.format.DateTimeFormatter/ISO_INSTANT (.toInstant valid-at))
                      "' ORDER BY _valid_from DESC, _id DESC LIMIT " (long n))))

(defn executions-by-scenario-at [ds scenario-id valid-at]
  (query-rows ds (str "SELECT * FROM sim_benchmark_executions FOR VALID_TIME AS OF TIMESTAMP '"
                      (.format java.time.format.DateTimeFormatter/ISO_INSTANT (.toInstant valid-at))
                      "' WHERE scenario_id = " (xtdb/sql-str scenario-id) " ORDER BY _id ASC")))

(defn benchmark-executions-at [ds benchmark-id valid-at]
  (if-not ds
    []
    (query-rows ds (str "SELECT * FROM sim_benchmark_executions FOR VALID_TIME AS OF TIMESTAMP '"
                        (.format java.time.format.DateTimeFormatter/ISO_INSTANT (.toInstant valid-at))
                        "' WHERE benchmark_id = " (xtdb/sql-str benchmark-id) " ORDER BY _id ASC"))))

(def as-of-benchmark-rows benchmark-executions-at)
(def benchmark-rows benchmark-executions)
(def by-scenario executions-by-scenario)
(def by-use-case executions-by-use-case)

(defn comparison-scope
  "Normalize an explicit caller-supplied comparison scope; no equivalence is inferred."
  [scope]
  (select-keys scope [:benchmark-id :scenario-id :use-case :run-id :input-set-root
                      :semantic-composition-root :as-of]))

(defn compare-completed
  "Compare projected completed rows under an explicit scope. The result groups
   result roots; it does not assert semantic equivalence or verify artifacts."
  [rows scope]
  (let [scope (comparison-scope scope)
        matching (filterv (fn [row]
                            (every? (fn [[k v]] (= v (get row (keyword "execution" (name k)))))
                                    (dissoc scope :as-of))) rows)]
    {:scope scope
     :rows matching
     :result-roots (frequencies (keep :execution/bundle_root matching))
     :comparable? (and (seq matching)
                       (= 1 (count (set (keep :execution/semantic_status matching)))))}))
