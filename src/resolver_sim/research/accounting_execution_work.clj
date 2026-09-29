(ns resolver-sim.research.accounting-execution-work
  "Research-only adapter which attaches one canonical accounting partition to
   a rooted observed execution. It does not alter accounting reduction: the
   partial's values remain the closed serial projection of its declared source
   roots. The observed execution supplies runner/environment/dependency and
   assurance provenance for the distributed attempt."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.research.accelerated-accounting :as accounting]
            [resolver-sim.research.observed-execution-work :as observed-work]))

(def ^:const schema "research-accounting-execution-work.v1")

(defn- root [x]
  (hash-ref/sha256-ref
   (hc/domain-hash :research-accounting-execution-work-v1 x)))

(defn work-root [work]
  (root (dissoc work :research-accounting-execution-work/root)))

(defn- member-unit? [plan unit]
  (some #(= (:accounting-work-unit/root unit)
            (:accounting-work-unit/root %))
        (:partition/work-units plan)))

(defn valid-work? [work]
  (let [execution-work (:accounting/observed-execution-work work)
        request (:accounting/request work)
        plan (:accounting/partition-plan work)
        unit (:accounting/work-unit work)
        implementation (get-in execution-work [:work/implementation
                                               :research-implementation-identity/root])]
    (and (= schema (:artifact/schema work))
         (observed-work/valid-work? execution-work)
         (accounting/valid-request? request)
         (accounting/valid-partition-plan? request plan)
         (member-unit? plan unit)
         (= (:accounting-request/root request) (:accounting-request/root unit))
         (= (:accounting-work-unit/root unit)
            (try (accounting/work-unit-root unit) (catch Exception _ nil)))
         (= (:accounting-work-unit/root unit)
            (get-in execution-work [:work/job :workload/root]))
         (= (:work-unit/dependency-set-root unit)
            (get-in execution-work [:work/resolved-dependencies
                                    :resolved-research-dependency-set/root]))
         (= (:work-unit/execution-environment-root unit)
            (get-in execution-work [:work/execution-environment
                                    :research-execution-environment/root]))
         (contains? (set (:work-unit/allowed-implementation-roots unit))
                    implementation)
         (= (:research-accounting-execution-work/root work)
            (try (work-root work) (catch Exception _ nil))))))

(defn build-work
  [{:keys [observed-execution-work request partition-plan work-unit]}]
  (let [body {:artifact/schema schema
              :accounting/observed-execution-work observed-execution-work
              :accounting/request request
              :accounting/partition-plan partition-plan
              :accounting/work-unit work-unit}
        work (assoc body :research-accounting-execution-work/root (work-root body))]
    (when-not (valid-work? work)
      (throw (ex-info "Invalid accounting execution work bundle" {})))
    work))

(defn execute-work!
  "Execute the embedded observed work, then derive exactly one canonical
   accounting partial from the frozen request/partition. The accounting result
   is semantic; the observed execution and assurance roots remain forensic
   provenance and do not change its canonical partial consequence."
  [work]
  (when-not (valid-work? work)
    (throw (ex-info "Invalid accounting execution work bundle" {})))
  (let [execution (observed-work/execute-work! (:accounting/observed-execution-work work))
        observed-run-evidence (:observed-run-evidence execution)
        run-assurance-evidence (:run-assurance-evidence execution)
        embedded (:accounting/observed-execution-work work)
        partial (accounting/build-partial-result
                 {:request (:accounting/request work)
                  :work-unit (:accounting/work-unit work)
                  :values (accounting/partial-values (:accounting/request work)
                                                     (:accounting/work-unit work))
                  :run-assurance-root
                  (:research-run-assurance-evidence/root run-assurance-evidence)
                  :implementation-root (get-in embedded [:work/implementation
                                                        :research-implementation-identity/root])
                  :runner-reference (get-in embedded [:work/runner-participation
                                                       :runner/reference])
                  :resolved-dependency-set-root
                  (get-in embedded [:work/resolved-dependencies
                                    :resolved-research-dependency-set/root])
                  :execution-environment-root
                  (get-in embedded [:work/execution-environment
                                    :research-execution-environment/root])})]
    {:execution execution
     :observed-run-evidence observed-run-evidence
     :run-assurance-evidence run-assurance-evidence
     :accounting-partial partial}))
