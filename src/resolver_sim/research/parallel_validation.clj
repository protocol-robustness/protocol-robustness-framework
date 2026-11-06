(ns resolver-sim.research.parallel-validation
  "Runtime validation and file reporting for parallel research execution.

   This composes existing rooted executor results with the generic runtime
   report envelope. It does not change executor ordering or cell semantics.
   A rejected completed result is critical; an execution with no result is a
   warning; a revalidated successful result is informational. These describe
   report validation outcomes, not economic risk severity."
  (:require [resolver-sim.research.execution-cell-runner :as cell-runner]
            [resolver-sim.research.parallel-executor :as executor]
            [resolver-sim.research.execution-manifest :as manifest]
            [resolver-sim.validation.runtime-report :as runtime-report]))

(defn- execution-check [cells-by-root entry]
  (let [cell-root (:research-execution-cell/root entry)
        cell (get cells-by-root cell-root)
        result (:result entry)
        result-valid? (and (= :complete (:execution/status entry))
                           cell result
                           (cell-runner/result-matches-cell? cell result))
        declared-complete? (= :complete (:execution/status entry))
        status (if (and declared-complete? result-valid?) :passed :failed)
        severity (if result-valid? :info (if declared-complete? :critical :warning))
        roots (cond-> [cell-root]
                (and result
                     (string? (:research-execution-cell-result/root result))
                     (re-matches #"sha256:[0-9a-f]{64}"
                                 (:research-execution-cell-result/root result)))
                (conj (:research-execution-cell-result/root result)))]
    {:check/id (keyword "research-cell" (str "root-" (subs cell-root 7)))
     :status status
     :severity severity
     :message (cond
                result-valid? "Execution result validates against its frozen cell"
                declared-complete? "Completed execution result failed cell-bound validation"
                :else (str "Execution did not complete: " (name (or (:execution/status entry) :unknown))))
     :execution/status (:execution/status entry)
     :failure/kind (:execution/failure-kind entry)
     :failure/class (get-in entry [:execution/error :class])
     :evidence-roots (vec (distinct roots))}))

(defn build-execution-report
  "Validate every executor entry against its manifest cell and build a report.

   Required opts: :run/id and full :source/revision. The validator id defaults
   to this profile's stable id. The execution report is the direct output of
   parallel-executor/execute-manifest."
  [execution-manifest execution-report opts]
  (let [manifest-validation (manifest/validate-manifest execution-manifest)
        cells (:dispatch/cells execution-manifest)
        entries (:execution/entries execution-report)
        cell-roots (mapv :research-execution-cell/root cells)
        entry-roots (mapv :research-execution-cell/root entries)]
    (when-not (:valid? manifest-validation)
      (throw (ex-info "Cannot validate execution for an invalid manifest"
                      {:reason :invalid-execution-manifest
                       :validation manifest-validation})))
    (when-not (and (= (count cell-roots) (count entry-roots))
                   (= (set cell-roots) (set entry-roots)))
      (throw (ex-info "Execution entries do not cover the manifest cells exactly"
                      {:reason :execution-cell-coverage-mismatch
                       :expected-roots (vec (sort cell-roots))
                       :actual-roots (vec (sort entry-roots))})))
    (let [cells-by-root (into {} (map (juxt :research-execution-cell/root identity) cells))
          checks (->> entries
                      (map #(execution-check cells-by-root %))
                      (sort-by (comp str :check/id))
                      vec)
          evidence-roots (->> checks (mapcat :evidence-roots) distinct sort vec)
          execution-roots (vec (sort (distinct (conj evidence-roots
                                                     (:research-execution-manifest/root execution-manifest)))))]
      (runtime-report/build-report
       (merge opts
              {:validator/id (or (:validator/id opts) "parallel-research-cell-validation.v1")
               :execution/roots execution-roots
               :checks checks})))))

(defn execute-and-report!
  "Execute a frozen manifest, validate each result, and persist a report.

   This is opt-in; callers choose both executor settings and report path. The
   report path must be new. Returns the execution report, validation report,
   and persisted path. Required options: :run/id, :source/revision,
   :report/path."
  [scope execution-manifest executor-kind executor-options opts]
  (when-not (and (string? (:report/path opts)) (seq (:report/path opts)))
    (throw (ex-info "Parallel validation requires an explicit report path"
                    {:reason :missing-report-path})))
  (let [execution-report (executor/execute-manifest scope execution-manifest
                                                    executor-kind executor-options)
        validation-report (build-execution-report execution-manifest execution-report opts)
        path (runtime-report/write-report! (:report/path opts) validation-report)]
    {:execution/report execution-report
     :validation/report validation-report
     :validation/report-path path}))
