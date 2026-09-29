(ns resolver-sim.research.observation-work
  "Research-only fine-grained observation work stealing. Cell execution and
   semantic research-analysis identities remain unchanged; assignment and
   observation history is a separate forensic consequence."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.research.analysis-compiler :as compiler]
            [resolver-sim.research.execution-cell-runner :as cell-runner]
            [resolver-sim.research.execution-manifest :as manifest]
            [resolver-sim.research.result-store :as store]
            [resolver-sim.research.runner-participation :as participation]))

(def ^:const plan-schema "research-observation-work-plan.v1")
(def ^:const unit-schema "research-observation-work-unit.v1")
(def ^:const assignment-schema "research-observation-assignment.v1")
(def ^:const observation-schema "research-coordinate-observation.v1")
(def ^:const reconciliation-schema "research-observation-reconciliation.v1")

(defn- root [domain body]
  (hash-ref/sha256-ref (hc/domain-hash domain body)))
(defn- rooted [domain key body] (assoc body key (root domain body)))
(defn- repeat-key [repeat]
  (select-keys repeat [:execution/repeat-id :execution/fixture-id :execution/seed]))
(defn- axis-index [axis]
  (into {} (map (juxt :axis/id :axis/key) (:axis/members axis))))

(defn build-plan [frozen execution-manifest policy-subject-root repeat]
  (let [repeat (repeat-key repeat)
        definition (:research-definition frozen)
        cases (:research/cases definition)
        measures (:research/measures definition)
        case-index (axis-index (get-in frozen [:derived :case-axis]))
        measure-index (axis-index (get-in frozen [:derived :measure-axis]))
        selected (filter #(and (= policy-subject-root (:policy-subject/root %))
                               (= repeat (repeat-key %)))
                         (:dispatch/cells execution-manifest))
        by-case (into {} (map (juxt :case/id identity) selected))]
    (when-not (and (:valid? (manifest/validate-manifest execution-manifest))
                   (= (:research-definition/root execution-manifest)
                      (:research-definition/root frozen))
                   (= (:case-axis/root execution-manifest)
                      (get-in frozen [:derived :case-axis/root]))
                   (= (:measure-axis/root execution-manifest)
                      (get-in frozen [:derived :measure-axis/root]))
                   (contains? (set (:dispatch/policy-subject-roots execution-manifest))
                              policy-subject-root)
                   (contains? (set (map repeat-key (:dispatch/repeats execution-manifest)))
                              repeat)
                   (= (count cases) (count selected) (count by-case))
                   (seq measures))
      (throw (ex-info "Invalid observation-work plan scope" {})))
    (let [units (vec
                 (for [research-case cases measure measures]
                   (rooted
                    :research-observation-work-unit-v1
                    :research-observation-work-unit/root
                    {:artifact/schema unit-schema
                     :research-execution-cell/root
                     (:research-execution-cell/root (by-case (:case/id research-case)))
                     :case/id (:case/id research-case)
                     :measure/id (:measure/id measure)
                     :measure/metric-key
                     (keyword (name (get-in measure [:measure/observation :observation/id])))
                     :analysis/coordinate
                     [(case-index (:case/id research-case))
                      (measure-index (:measure/id measure))]})))
          body {:artifact/schema plan-schema
                :research-definition/root (:research-definition/root frozen)
                :research-execution-manifest/root
                (:research-execution-manifest/root execution-manifest)
                :case-axis/root (get-in frozen [:derived :case-axis/root])
                :measure-axis/root (get-in frozen [:derived :measure-axis/root])
                :policy-subject/root policy-subject-root
                :analysis/repeat repeat
                :observation/work-units units
                :observation/coverage :every-case-measure-exactly-once}]
      (rooted :research-observation-work-plan-v1
              :research-observation-work-plan/root body))))

(defn valid-plan? [frozen execution-manifest plan]
  (= plan (try (build-plan frozen execution-manifest
                          (:policy-subject/root plan) (:analysis/repeat plan))
               (catch Exception _ nil))))

(defn- eligible? [runner]
  (and (:valid? (participation/validate-participation runner))
       (:participation/opted-in? runner)
       (:participation/available? runner)
       (contains? (set (:runner/capabilities runner)) :capability/research-observation)
       (contains? (set (:participation/job-classes runner))
                  :job/research-observation)
       (contains? (set (:runner/environment-kinds runner))
                  :execution-environment/local)))

(defn- assignment-root [assignment]
  (root :research-observation-assignment-v1
        (dissoc assignment :research-observation-assignment/root)))

(defn steal-next
  "Deterministic lowest-coordinate pending selection. This is a pure research
   selector; transport and worker concurrency remain implementation concerns."
  [frozen execution-manifest plan runner assignments]
  (let [units (:observation/work-units plan)
        allowed (set (map :research-observation-work-unit/root units))
        claimed (map :research-observation-work-unit/root assignments)
        claimed-set (set claimed)]
    (when-not (and (valid-plan? frozen execution-manifest plan)
                   (= (count claimed) (count claimed-set))
                   (every? allowed claimed)
                   (every? #(and (= assignment-schema (:artifact/schema %))
                                 (= (:research-observation-work-plan/root plan)
                                    (:research-observation-work-plan/root %))
                                 (= (:research-observation-assignment/root %)
                                    (assignment-root %)))
                           assignments))
      (throw (ex-info "Invalid observation steal state" {})))
    (when (eligible? runner)
      (when-let [unit (first (remove #(contains? claimed-set
                                                (:research-observation-work-unit/root %))
                                     units))]
        (rooted :research-observation-assignment-v1
                :research-observation-assignment/root
                {:artifact/schema assignment-schema
                 :research-observation-work-plan/root
                 (:research-observation-work-plan/root plan)
                 :research-observation-work-unit/root
                 (:research-observation-work-unit/root unit)
                 :runner-participation/root (:runner-participation/root runner)
                 :runner/reference (:runner/reference runner)
                 :runner/site-id (:runner/site-id runner)})))))

(defn- cell-status [result-store execution-report cell-root]
  (let [stored (store/cell-status result-store cell-root)
        failed? (some #(and (= cell-root (:research-execution-cell/root %))
                            (= :failed (:execution/status %)))
                      (:execution/entries execution-report))]
    (if (and (= :missing stored) failed?) :failed stored)))

(defn observe
  [frozen execution-manifest plan assignment runner result-store execution-report]
  (let [unit (some #(when (= (:research-observation-work-unit/root %)
                            (:research-observation-work-unit/root assignment)) %)
                   (:observation/work-units plan))]
    (when-not (and (valid-plan? frozen execution-manifest plan)
                   unit (eligible? runner)
                   (= assignment-schema (:artifact/schema assignment))
                   (= (:research-observation-work-plan/root plan)
                      (:research-observation-work-plan/root assignment))
                   (= (:research-observation-assignment/root assignment)
                      (assignment-root assignment))
                   (= (:runner-participation/root runner)
                      (:runner-participation/root assignment))
                   (= (:runner/reference runner) (:runner/reference assignment))
                   (= (:runner/site-id runner) (:runner/site-id assignment)))
      (throw (ex-info "Invalid observation assignment or runner" {})))
    (let [cell-root (:research-execution-cell/root unit)
          cell (some #(when (= cell-root (:research-execution-cell/root %)) %)
                     (:dispatch/cells execution-manifest))
          status (cell-status result-store execution-report cell-root)
          result (when (= :complete status) (store/result-by-cell result-store cell-root))
          value (case status
                  :complete (get-in result [:metrics (:measure/metric-key unit)] :missing)
                  :conflicting :not-comparable
                  :failed :missing
                  :missing :missing)
          body {:artifact/schema observation-schema
                :research-observation-work-plan/root
                (:research-observation-work-plan/root plan)
                :research-observation-work-unit/root
                (:research-observation-work-unit/root unit)
                :research-observation-assignment/root
                (:research-observation-assignment/root assignment)
                :runner-participation/root (:runner-participation/root runner)
                :research-execution-cell/root cell-root
                :research-execution-cell-result/root
                (:research-execution-cell-result/root result)
                :case/id (:case/id unit)
                :measure/id (:measure/id unit)
                :analysis/coordinate (:analysis/coordinate unit)
                :observation/cell-status status
                :observation/value value}]
      (when-not (and cell
                     (or (not= :complete status)
                         (cell-runner/result-matches-cell? cell result))
                     (or (integer? value)
                         (contains? #{:missing :not-comparable} value)))
        (throw (ex-info "Invalid research observation source or value" {})))
      (rooted :research-coordinate-observation-v1
              :research-coordinate-observation/root body))))

(defn ingest [ingestion observation]
  (let [unit-root (:research-observation-work-unit/root observation)
        prior (get ingestion unit-root)]
    (cond
      (nil? prior) (assoc ingestion unit-root observation)
      (= prior observation) ingestion
      :else (throw (ex-info "Conflicting coordinate observation"
                            {:research-observation-work-unit/root unit-root})))))

(defn reconcile
  "Require exact coordinate coverage and rederive each observation from the
   frozen cell result/report before assembling the existing analysis identity."
  [frozen execution-manifest plan assignments participants ingestion
   result-store execution-report]
  (when-not (valid-plan? frozen execution-manifest plan)
    (throw (ex-info "Invalid observation reconciliation plan" {})))
  (let [units (:observation/work-units plan)
        required (set (map :research-observation-work-unit/root units))
        by-unit (into {} (map (juxt :research-observation-work-unit/root identity)
                              assignments))
        by-participant (into {} (map (juxt :runner-participation/root identity)
                                     participants))]
    (when-not (and (= required (set (keys ingestion)) (set (keys by-unit)))
                   (= (count units) (count assignments))
                   (= (count participants) (count by-participant))
                   (every? (fn [[unit-root observation]]
                             (let [assignment (by-unit unit-root)
                                   runner (by-participant
                                           (:runner-participation/root assignment))]
                               (and (= unit-root
                                       (:research-observation-work-unit/root observation))
                                    runner
                                    (= observation
                                       (try (observe frozen execution-manifest plan
                                                     assignment runner result-store
                                                     execution-report)
                                            (catch Exception _ nil))))))
                           ingestion))
      (throw (ex-info "Incomplete or substituted coordinate observations" {})))
    (let [ordered (mapv #(ingestion (:research-observation-work-unit/root %)) units)
          statuses (into (sorted-map)
                         (map (juxt :case/id :observation/cell-status) ordered))
          coordinates (into {} (map (juxt :analysis/coordinate :observation/value)
                                    ordered))
          analysis (compiler/assemble-policy-analysis
                    frozen execution-manifest (:policy-subject/root plan)
                    (:analysis/repeat plan) statuses coordinates)
          body {:artifact/schema reconciliation-schema
                :research-observation-work-plan/root
                (:research-observation-work-plan/root plan)
                :research-coordinate-observation/roots
                (vec (sort (map :research-coordinate-observation/root ordered)))
                :research-analysis/root (:research-analysis/root analysis)
                :reconciliation/coverage :every-case-measure-exactly-once}]
      {:analysis analysis
       :forensic-reconciliation
       (rooted :research-observation-reconciliation-v1
               :research-observation-reconciliation/root body)})))
