(ns resolver-sim.benchmark.distributed.chunk-execution
  "Closed execution authority for one claimed fixed benchmark chunk."
  (:require [resolver-sim.benchmark.distributed.chunk-result :as result]
            [resolver-sim.benchmark.distributed.executable-distribution :as distribution]
            [resolver-sim.benchmark.distributed.fixed-chunks :as fixed]
            [resolver-sim.hash.reference :as hash-ref]))

(defn- fail! [reason data]
  (throw (ex-info "Claimed chunk execution is invalid" (assoc data :reason reason))))

(defn- exact-members? [claimed entries]
  (= (:chunk/execution-ids claimed) (mapv :execution/id entries)))

(defn authorize-chunk!
  "Validate closed observed material before its entries may be scheduled.

   The returned handle contains only the claimed descriptor and closed resolved
   entries; operational lease identity remains outside semantic result identity."
  [{:keys [claimed-chunk resolved-executions sensitivity-root
           expected-executable-distribution observed-executable-distribution] :as request}]
  (when (or (contains? request :expected-executable-distribution)
            (contains? request :observed-executable-distribution))
    (let [verification (distribution/verify-expected-observed
                        {:expected expected-executable-distribution
                         :observed observed-executable-distribution})]
      (when-not (= :verified (:distribution-preflight/status verification))
        (fail! (:reason verification) verification))))
  (let [entries (vec resolved-executions)
        input-root (fixed/input-root entries)
        work-root (fixed/work-root entries)]
    (when-not (exact-members? claimed-chunk entries)
      (fail! :claimed-membership-mismatch
             {:expected (:chunk/execution-ids claimed-chunk)
              :actual (mapv :execution/id entries)}))
    (when-not (= (:chunk/expected-input-root claimed-chunk) input-root)
      (fail! :observed-input-root-mismatch {}))
    (when-not (= (:chunk/expected-work-root claimed-chunk) work-root)
      (fail! :observed-work-root-mismatch {}))
    (when-not (= (:chunk/expected-sensitivity-root claimed-chunk) sensitivity-root)
      (fail! :observed-sensitivity-root-mismatch {}))
    {:claimed-chunk claimed-chunk
     :resolved-executions entries
     :sensitivity-root sensitivity-root
     :executable-distribution/root (:chunk/expected-executable-distribution-root claimed-chunk)
     :observed-executable-distribution/root
     (:executable-distribution/root observed-executable-distribution)
     :chunk/input-root input-root
     :chunk/work-root work-root}))

(defn finalize-chunk!
  "Build the detached result from exact ordered rows returned by scheduled work."
  [{:keys [claimed-chunk resolved-executions sensitivity-root chunk/input-root chunk/work-root]
    :as handle}
   rows]
  (let [rows (vec rows)
        expected-ids (mapv :execution/id resolved-executions)
        actual-ids (mapv :execution/id rows)]
    (when-not (and (= (count expected-ids) (count actual-ids))
                   (= (set expected-ids) (set actual-ids)))
      (fail! :worker-execution-id-mismatch {:expected expected-ids :actual actual-ids}))
    (let [row-by-id (into {} (map (juxt :execution/id identity) rows))
          rows (mapv row-by-id expected-ids)]
      (when (some #(not (hash-ref/valid-sha256-ref? (:staged-artifact-manifest/root %))) rows)
        (fail! :invalid-staged-artifact-manifest-root {}))
      (result/build-manifest {:chunk/id (:chunk/id claimed-chunk)
                              :run-plan/root (:run-plan/root claimed-chunk)
                              :execution-plan/root (:execution-plan/root claimed-chunk)
                              :chunk/input-root input-root
                              :chunk/work-root work-root
                              :executable-distribution/root
                              (:observed-executable-distribution/root handle)
                              :chunk/execution-ids (mapv :execution/id rows)
                              :chunk/staged-artifact-manifest-roots (mapv :staged-artifact-manifest/root rows)
                              :sensitivity/root sensitivity-root}))))

(defn execute-chunk!
  "Compatibility synchronous execution through authorize/finalize authority."
  [{:keys [execute-entry!] :as request}]
  (when-not (fn? execute-entry!) (fail! :missing-execute-entry {}))
  (let [handle (authorize-chunk! request)
        rows (mapv execute-entry! (:resolved-executions handle))]
    (finalize-chunk! handle rows)))
