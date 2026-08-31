(ns resolver-sim.db.explorer-seed
  "Demonstrative XTDB explorer seed.

   DESTRUCTIVE / REBUILDABLE. This truncates the explorer sim_* tables and
   repopulates a small, pedagogical dataset for the XTDB Temporal Explorer
   notebook. It is NOT production code, is not part of protocol semantics, and
   never modifies an authoritative/completed PRF artifact.

   Seed cases:
     A. clean successful execution + temporal replay
     B. invariant-failure execution + temporal replay (Failure Archaeology)
     C. expected-error execution + temporal replay
     D. two comparable runs -> same result root (CONVERGENT)
     E. two comparable runs -> divergent result roots (DIVERGENT)
     F. one synthetic non-authoritative index correction (system-time history)

   Run: bb explorer:seed        (reset + seed)
        bb explorer:reset-seed  (truncate only)"
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [next.jdbc :as jdbc]
            [resolver-sim.commands.run-lifecycle :as lifecycle]
            [resolver-sim.db.execution-projection :as ep]
            [resolver-sim.db.temporal :as temporal]
            [resolver-sim.db.xtdb :as xtdb]
            [resolver-sim.io.paths :as paths]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.run.package-index :as pi])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def benchmark-artifact-ids
  [:runner-finalization :benchmark-definition :execution-plan :benchmark-index
   :benchmark-evidence :content-registry :benchmark-conclusion :benchmark-conservation
   :benchmark-finalization :benchmark-assurance :canonical-integrity :verdict-policy
   :forensic-status])

(defn- sha-ref [file] (str "sha256:" (lifecycle/sha256-file file)))

(defn- temp-root []
  (.toFile (Files/createTempDirectory "explorer-seed-" (make-array FileAttribute 0))))

(defn- delete-if-present! [ds table]
  (try (jdbc/execute! ds [(str "DELETE FROM " table)])
       (catch Exception _ nil)))

(defn reset-seed!
  "Truncate the explorer sim_* tables (execution + temporal). Idempotent."
  [ds]
  (doseq [tbl ["sim_execution_runs" "sim_benchmark_executions"
               "sim_temporal_runs" "sim_temporal_steps"
               "sim_temporal_invariants" "sim_temporal_coverage"]]
    (delete-if-present! ds tbl)))

;; ---------------------------------------------------------------------------
;; Completed-package builder (benchmark profile, so closure validation is
;; structural and the artifact-index.v1 supplies per-case executions).
;; ---------------------------------------------------------------------------

(defn- build-completed-package!
  "Write a valid, completion-sealed benchmark package under a temp root and
   return its run-root path string. `executions` are written into the canonical
   benchmark-artifact-index.v1 file the package index references."
  [run-id bundle-root scenario-id executions]
  (let [root (temp-root)
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
        index-input {:run-id run-id :scenario-id scenario-id :run-type :benchmark
                     :bundle-root-hash bundle-root :artifacts artifacts}
        idx-path (io/file root paths/run-package-index)]
    (pi/write! idx-path index-input)
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

(defn- exec [execution-id case-key scenario-id outcome status]
  {:execution/id execution-id :case/key case-key :scenario/id scenario-id
   :benchmark/run-index 0 :benchmark/run-count 1 :execution/status status
   :outcome outcome :halt-reason (when (= :fail outcome) :invariant-violation)
   :scenario/evidence-root (str "sha256:ev-" execution-id)
   :scenario/replay-output-sha256 (str "sha256:replay-" execution-id)})

;; ---------------------------------------------------------------------------
;; Execution runs
;; ---------------------------------------------------------------------------

(defn seed-execution-runs! [ds]
  (let [;; A: success
        root-a (build-completed-package! "run-success" "sha256:result-success"
                                         "scenario-success"
                                         [(exec "exec-success-1" "case-success-1" "scenario-success" :pass "completed")
                                          (exec "exec-success-2" "case-success-2" "scenario-success" :pass "completed")])
        ;; B: failure
        root-b (build-completed-package! "run-failure" "sha256:result-failure"
                                         "scenario-failure"
                                         [(exec "exec-failure-1" "case-failure-1" "scenario-failure" :fail "completed")])
        ;; C: expected-error
        root-c (build-completed-package! "run-expected-error" "sha256:result-expected"
                                         "scenario-expected-error"
                                         [(exec "exec-expected-1" "case-expected-1" "scenario-expected-error" :pass "completed")])
        ;; D: convergent (same scenario, same result root, distinct run identities)
        root-d1 (build-completed-package! "run-conv-A" "sha256:result-convergent" "scenario-convergent" [])
        root-d2 (build-completed-package! "run-conv-B" "sha256:result-convergent" "scenario-convergent" [])
        ;; E: divergent (same scenario, distinct result roots)
        root-e1 (build-completed-package! "run-div-A" "sha256:result-div-A" "scenario-divergent" [])
        root-e2 (build-completed-package! "run-div-B" "sha256:result-div-B" "scenario-divergent" [])]
    (doseq [root [root-a root-b root-c root-d1 root-d2 root-e1 root-e2]]
      (ep/project-run! ds root))))

;; ---------------------------------------------------------------------------
;; Temporal replays (constructed records, as the replay recorder would write)
;; ---------------------------------------------------------------------------

(defn- seed-temporal-run!
  [ds run-id scenario-id outcome block-time steps invariants]
  (temporal/record-temporal-run!
   ds
   {:run {:run-id run-id :batch-id :explorer-seed :protocol sew/protocol
          :suite-id :explorer :scenario-id scenario-id :seed 1 :git-sha "explorer-seed"
          :outcome outcome :block-time block-time}
    :steps steps
    :invariants invariants
    :coverage {:coverage {:offsets [-1 0 1]} :block-time block-time}}))

(defn seed-temporal-runs! [ds]
  ;; A: clean successful replay
  (seed-temporal-run!
   ds "temporal-success" "scenario-success" :pass 1000
   [{:step-index 0 :action :initialize :result :ok :block-time 1000}
    {:step-index 1 :action :deposit :result :ok :block-time 1100}
    {:step-index 2 :action :settle :result :ok :block-time 1200}]
   [{:step-index 2 :invariant :conservation :holds? true :severity :time :violations [] :block-time 1200}])
  ;; B: invariant failure (Failure Archaeology hero)
  (seed-temporal-run!
   ds "temporal-failure" "scenario-failure" :fail 2000
   [{:step-index 0 :action :initialize :result :ok :block-time 2000}
    {:step-index 1 :action :deposit :result :ok :block-time 2100}
    {:step-index 2 :action :cancel :result :ok :block-time 2200}
    {:step-index 3 :action :settle :result :ok :block-time 2300}]
   [{:step-index 2 :invariant :disposition :holds? true :severity :time :violations [] :block-time 2200}
    {:step-index 3 :invariant :held-credit :holds? false :severity :time
     :violations [{:reason :credit-mismatch :expected 100 :actual 75}] :block-time 2300}])
  ;; C: expected-error replay (rejected entry, kernel outcome :fail)
  (seed-temporal-run!
   ds "temporal-expected-error" "scenario-expected-error" :fail 3000
   [{:step-index 0 :action :initialize :result :ok :block-time 3000}
    {:step-index 1 :action :execute_pending_settlement :result :rejected :block-time 3100}]
   [{:step-index 1 :invariant :conservation :holds? true :severity :time :violations [] :block-time 3100}]))

;; ---------------------------------------------------------------------------
;; Synthetic non-authoritative index correction
;;
;; This is the ONE deliberate bypass of the normal
;;   validated artifact → deterministic projection → persistence
;; path, and it is dev-only. It writes an INCORRECT derived-index observation
;; directly (mirroring the private insert SQL) so XTDB system time can record
;; its later correction. Keeping the direct insert here — rather than exposing a
;; public production insert on `db.execution-projection` — makes the unsafe
;; boundary obvious and local to the demo seed.
;; ---------------------------------------------------------------------------

(defn- insert-execution-run-row!
  "DEMO-ONLY direct insert of one sim_execution_runs row. Mirrors the private
   projection insert so the seed can write an intentionally-incorrect index
   observation that the real projection later corrects. Never call from src/."
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

(defn seed-synthetic-index-correction!
  "Demonstrate XTDB system time.

   The completed/rooted PRF artifact is UNCHANGED. This deliberately writes an
   INCORRECT derived-index observation for a run (via the demo-only direct
   insert above), then projects the correct observation for the same run
   identity. XTDB system time records both; the correct projection becomes
   current. This models correction of the derived index, never mutation of the
   authoritative artifact."
  [ds]
  (let [run-id "run-synthetic-correction"
        root   (build-completed-package! run-id "sha256:result-correct"
                                         "scenario-correction"
                                         [(exec "exec-correct-1" "case-correct-1" "scenario-correction" :pass "completed")])
        correct-projection (ep/resolve-projection root)
        ;; Incorrect index observation: same run identity, wrong derived root.
        incorrect-row (assoc-in correct-projection [:run :bundle-root] "sha256:INDEX-ERROR-WRONG-ROOT")]
    ;; t1: the index first showed the wrong derived root (demo-only bypass).
    (insert-execution-run-row! ds (:run incorrect-row))
    ;; t2: the authoritative projection corrects it (same _id → new system-time version).
    (ep/project-run! ds root)))

;; ---------------------------------------------------------------------------
;; Entry point
;; ---------------------------------------------------------------------------

(defn -main [& args]
  (let [ds (xtdb/->datasource)]
    (reset-seed! ds)
    (when-not (= "reset" (first args))
      (seed-execution-runs! ds)
      (seed-temporal-runs! ds)
      (seed-synthetic-index-correction! ds)
      (println "explorer seed persisted:"))
    (println "xtdb temporal explorer dataset ready.")))