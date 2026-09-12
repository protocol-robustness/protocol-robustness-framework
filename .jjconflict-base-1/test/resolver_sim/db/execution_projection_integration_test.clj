(ns resolver-sim.db.execution-projection-integration-test
  "Live XTDB integration coverage for the completion-gated execution projection.

   Proves, against a real XTDB pgwire endpoint (localhost:5432):
     completed package → projection → actual persistence → typed query
   and the persistence identity/upsert semantics:
     • re-projecting the same completed execution is idempotent (no duplicate
       semantic observation rows);
     • two distinct execution-run identities sharing the same result root remain
       two distinct execution observations;
     • a valid-time (FOR VALID_TIME AS OF) query returns the projected rows.

   Persistence is idempotent because each observation uses a deterministic
   `_id` (the run/execution identity) and a deterministic `_valid_from`
   (the completion's completed_at, epoch-fallback), so re-inserting the same
   entity at the same valid-time overwrites the same XTDB bitemporal version
   rather than appending a duplicate.

   Run with:
     make xtdb
     clojure -M:test -e \"(require 'resolver-sim.db.execution-projection-integration-test)
                          (clojure.test/run-tests 'resolver-sim.db.execution-projection-integration-test)\""
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [resolver-sim.commands.run-lifecycle :as lifecycle]
            [resolver-sim.db.execution-projection :as ep]
            [resolver-sim.db.xtdb :as xtdb]
            [resolver-sim.io.paths :as paths]
            [resolver-sim.run.package-index :as package-index])
  (:import [java.util UUID]))

;; ---------------------------------------------------------------------------
;; Fixture — shared datasource + per-suite cleanup
;; ---------------------------------------------------------------------------

(def ^:dynamic *ds* nil)

(def benchmark-artifact-ids
  [:runner-finalization :benchmark-definition :execution-plan :benchmark-index
   :benchmark-evidence :content-registry :benchmark-conclusion :benchmark-conservation
   :benchmark-finalization :benchmark-assurance :canonical-integrity :verdict-policy
   :forensic-status])

(defn- sha-ref [file] (str "sha256:" (lifecycle/sha256-file file)))

(defn- delete-if-present! [ds table]
  (try (jdbc/execute! ds [(str "DELETE FROM " table)])
       (catch Exception _ nil)))

(defn- wait-for-xtdb!
  "Wait (up to timeout-ms) for the XTDB pgwire endpoint to accept a connection.
   XTDB's container can be up before its pgwire listener is ready; the JDBC
   driver then fails the auth handshake with EOF/connection-reset. Retrying a
   trivial query until it succeeds makes the suite robust to that startup race."
  [ds timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) (long timeout-ms))]
    (loop []
      (let [ok (try (jdbc/execute! ds ["SELECT 1"])
                    true
                    (catch Throwable _ false))]
        (cond
          ok true
          (>= (System/currentTimeMillis) deadline)
          (throw (ex-info "XTDB not reachable on localhost:5432 (run `make xtdb` and wait for healthy)."
                          {:error :xtdb-unreachable}))
          :else (do (Thread/sleep 2000) (recur)))))))

(defn xtdb-fixture [f]
  (let [ds (xtdb/->datasource)]
    (wait-for-xtdb! ds 60000)
    (binding [*ds* ds]
      (try
        (f)
        (finally
          (delete-if-present! ds "sim_execution_runs")
          (delete-if-present! ds "sim_benchmark_executions"))))))

(use-fixtures :once xtdb-fixture)

;; ---------------------------------------------------------------------------
;; Completed-package builder
;;
;; Builds a real, completion-sealed benchmark package whose closure validates
;; via the authoritative resolver, so the projection exercises the genuine
;; terminal-completion gate rather than a stubbed context.
;; ---------------------------------------------------------------------------

(defn- build-completed-package!
  "Write a valid benchmark package under a temp root. `executions` (a vector of
   per-case execution maps) is written into the canonical
   benchmark-artifact-index.v1 file that the package index's `:benchmark-index`
   artifact references, so the projection derives `sim_benchmark_executions`
   rows from that existing canonical source. Returns the run-root path string."
  [run-id bundle-root & [executions]]
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "exec-proj-" (make-array java.nio.file.attribute.FileAttribute 0)))
        executions (vec (or executions []))
        artifact-index-path "benchmark/index.edn"
        artifact-index-file (io/file root artifact-index-path)
        _ (.mkdirs (.getParentFile artifact-index-file))
        _ (spit artifact-index-file
                (pr-str {:artifact-index/version "benchmark-artifact-index.v1"
                         :benchmark/id run-id
                         :execution-count (count executions)
                         :executions executions}))
        artifacts (assoc
                   (into {}
                         (map (fn [id]
                                (let [path (str "benchmark/" (name id) ".dat")
                                      file (io/file root path)]
                                  (.mkdirs (.getParentFile file))
                                  (spit file (str "artifact-" (name id)))
                                  [id {:ref path
                                       :sha256 (sha-ref file)
                                       :bytes (.length file)}])))
                         (remove #{:benchmark-index} benchmark-artifact-ids))
                   :benchmark-index {:ref artifact-index-path
                                     :sha256 (sha-ref artifact-index-file)
                                     :bytes (.length artifact-index-file)})
        index-input {:run-id run-id :run-type :benchmark
                     :bundle-root-hash bundle-root :artifacts artifacts}
        idx-path (io/file root paths/run-package-index)]
    (package-index/write! idx-path index-input)
    (let [index-file (io/file root paths/run-package-index)]
      (spit (io/file root paths/completion)
            (json/write-str
             {"schema_version" "benchmark-completion.v1"
              "run_id" run-id
              "run_type" "benchmark"
              "lifecycle_status" "completed"
              "run_package_index_ref" paths/run-package-index
              "run_package_index_sha256" (sha-ref index-file)
              "run_package_index_bytes" (.length index-file)
              "finalization_ref" (get-in artifacts [:benchmark-finalization :ref])
              "finalization_sha256" (get-in artifacts [:benchmark-finalization :sha256])})))
    (.getPath root)))

(defn- as-of-ts [s] (java.util.Date. (.toEpochMilli (java.time.Instant/parse s))))

(defn- insert-execution-run-row!
  "TEST-ONLY direct insert of one sim_execution_runs row, mirroring the private
   projection insert so the system-time correction test can write a
   deliberately-incorrect observation that the real projection later corrects."
  [ds row]
  (jdbc/execute!
   ds
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

(def ^:private sample-executions
  [{:execution/id "exec-1" :case/key "case-1" :scenario/id "scenario-1"
    :benchmark/run-index 0 :benchmark/run-count 2 :execution/status "completed"
    :outcome :pass :halt-reason nil :scenario/evidence-root "sha256:ev-1"
    :scenario/replay-output-sha256 "sha256:replay-1"}
   {:execution/id "exec-2" :case/key "case-2" :scenario/id "scenario-2"
    :benchmark/run-index 1 :benchmark/run-count 2 :execution/status "completed"
    :outcome :fail :halt-reason :invariant-violation :scenario/evidence-root "sha256:ev-2"
    :scenario/replay-output-sha256 "sha256:replay-2"}])

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest completed-package-projects-and-persists-to-xtdb
  (testing "a completion-sealed package validates, projects, persists, and is queryable"
    (let [root (build-completed-package! "run-integ-1" "sha256:bundle-root-1" sample-executions)
          ctx  (package-index/resolve-validation-context root)]
      (is (:valid? (:completion-report ctx)))
      (is (:complete? (:completeness-report ctx)))
      (is (:valid? (:integrity-report ctx)))
      (let [projection (ep/project-run! *ds* root)]
        (is (= "run-integ-1" (get-in projection [:run :run-id])))
        (is (= "sha256:bundle-root-1" (get-in projection [:run :bundle-root])))
        (is (= 2 (count (:benchmarks projection)))
            "per-case executions from the canonical artifact-index.v1 project into benchmark rows")
        (let [stored (ep/recent *ds* 10)
              mine   (filter #(= "run-integ-1" (:execution/_id %)) stored)]
          (is (= 1 (count mine)) "one run observation persisted")
          (is (= "sha256:bundle-root-1" (:execution/bundle_root (first mine)))
              "typed query returns the projected bundle root"))
        (let [exec-rows (ep/executions-by-scenario *ds* "scenario-1")]
          (is (= 1 (count exec-rows))
              "sim_benchmark_executions is populated from the canonical artifact-index.v1")
          (is (= "exec-1" (:execution/_id (first exec-rows)))))))))

(deftest projection-persistence-is-idempotent
  (testing "projecting the same completed execution twice yields no duplicate semantic rows"
    (let [root (build-completed-package! "run-integ-idem" "sha256:bundle-root-idem"
                                         sample-executions)]
      (ep/project-run! *ds* root)
      (ep/project-run! *ds* root)
      (let [mine (filter #(= "run-integ-idem" (:execution/_id %))
                         (ep/recent *ds* 100))]
        (is (= 1 (count mine))
            "deterministic _id + deterministic _valid_from overwrite the same XTDB version"))
      (let [exec-1 (ep/executions-by-scenario *ds* "scenario-1")
            exec-2 (ep/executions-by-scenario *ds* "scenario-2")]
        (is (= 1 (count exec-1)) "benchmark execution rows are idempotent too")
        (is (= 1 (count exec-2)))))))

(deftest same-id-different-content-is-replacement-not-idempotence
  (testing "same run _id + same valid-time + different content is a replacement, not idempotence"
    (let [root-a (build-completed-package! "run-shared-id" "sha256:bundle-v1")
          root-b (build-completed-package! "run-shared-id" "sha256:bundle-v2")
          proj-a (ep/project-run! *ds* root-a)
          proj-b (ep/project-run! *ds* root-b)]
      (is (not= (get-in proj-a [:run :bundle-root])
                (get-in proj-b [:run :bundle-root]))
          "two distinct validated sources project distinct content under the same run identity")
      (let [mine (filter #(= "run-shared-id" (:execution/_id %)) (ep/recent *ds* 100))]
        (is (= 1 (count mine))
            "XTDB collapses to one row at the same valid-time — the later write replaces the earlier")
        (is (= "sha256:bundle-v2" (:execution/bundle_root (first mine)))
            "the surviving row holds the later projection; this is replacement, not idempotence")))))

(deftest distinct-run-identities-remain-distinct-observations
  (testing "two execution-run identities sharing a result root remain two observations"
    (let [root-a (build-completed-package! "run-identity-A" "sha256:shared-bundle")
          root-b (build-completed-package! "run-identity-B" "sha256:shared-bundle")]
      (ep/project-run! *ds* root-a)
      (ep/project-run! *ds* root-b)
      (let [rows (ep/recent *ds* 100)
            shared (filter #(= "sha256:shared-bundle" (:execution/bundle_root %)) rows)]
        (is (= #{["run-identity-A"] ["run-identity-B"]}
               (set (map (juxt :execution/_id) shared)))
            "two distinct run identities produce two execution observations, not one")))))

(deftest valid-time-as-of-query-against-execution-tables
  (testing "FOR VALID_TIME AS OF returns rows from their valid-from onward"
    (let [root (build-completed-package! "run-integ-asof" "sha256:bundle-root-asof")]
      (ep/project-run! *ds* root)
      (is (empty? (ep/as-of *ds* (as-of-ts "1999-01-01T00:00:00Z")))
          "no rows visible before their valid-from (epoch fallback)")
      (let [future (ep/as-of *ds* (as-of-ts "2030-01-01T00:00:00Z"))
            mine   (filter #(= "run-integ-asof" (:execution/_id %)) future)]
        (is (= 1 (count mine)) "the projected row is visible AS OF a later valid time")))))

(deftest system-time-records-a-derived-index-correction
  (testing "an incorrect index observation corrected by the projection appears in system-time history"
    (let [id   (str "run-integ-correction-" (UUID/randomUUID))
          root (build-completed-package! id "sha256:result-correct")
          correct (ep/resolve-projection root)
          incorrect (assoc-in correct [:run :bundle-root] "sha256:INDEX-ERROR-WRONG")]
      ;; t1: the index first showed the wrong derived root. `insert-run!` is
      ;; private, so this writes the deliberately-incorrect observation directly
      ;; (the same demo-only bypass the explorer seed uses).
      (insert-execution-run-row! *ds* (:run incorrect))
      ;; t2: the authoritative projection corrects it (same _id → new system-time version).
      (ep/project-run! *ds* root)
      (let [hist (sort-by :execution/_system_from
                          (filter #(= id (:execution/_id %))
                                  (ep/execution-runs-history *ds*)))]
        (is (= ["sha256:INDEX-ERROR-WRONG" "sha256:result-correct"]
               (mapv :execution/bundle_root hist))
            "history shows the incorrect observation followed by the corrected one")
        (is (= 2 (count hist)) "two system-time versions, not one"))
      (let [current (first (filter #(= id (:execution/_id %))
                                   (ep/execution-runs-valid-at *ds* (java.util.Date.))))]
        (is (= "sha256:result-correct" (:execution/bundle_root current))
            "the current (best-known) row is the corrected projection")))))