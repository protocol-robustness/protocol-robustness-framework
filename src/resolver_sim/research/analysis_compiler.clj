(ns resolver-sim.research.analysis-compiler
  "Compile dense, repeat-scoped policy analyses from stored execution cells.

   This layer neither ranks policies nor aggregates repeats. A repeat fixture
   is selected explicitly, preventing a hidden average or consensus claim."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.research.execution-manifest :as manifest]
            [resolver-sim.research.result-store :as store]))

(def ^:const schema "research-execution-policy-analysis.v1")

(defn- root [domain value]
  (hash-ref/sha256-ref (hc/domain-hash domain value)))

(defn- repeat-key [repeat]
  (select-keys repeat [:execution/repeat-id :execution/fixture-id :execution/seed]))

(defn- metric-key [measure]
  (keyword (name (get-in measure [:measure/observation :observation/id]))))

(defn- axis-index [axis]
  (into {} (map (juxt :axis/id :axis/key) (:axis/members axis))))

(defn- analysis-root [analysis]
  (root :research-execution-policy-analysis-v1
        (dissoc analysis :research-analysis/root)))

(defn compile-policy-analysis
  "Compile one policy subject and one declared repeat fixture.

   Complete cells contribute exact integer observations. Missing cells remain
   `:missing`, executor-failed cells remain `:failed`, and conflicting cells
   remain `:not-comparable`. Neither state is erased or coerced to a numeric
   measure.

   The optional execution report supplies failure receipts. They deliberately
   stay outside the result store, which only accepts valid committed results."
  ([frozen execution-manifest result-store policy-subject-root repeat]
   (compile-policy-analysis frozen execution-manifest result-store
                            policy-subject-root repeat nil))
  ([frozen execution-manifest result-store policy-subject-root repeat execution-report]
   (let [manifest-validation (manifest/validate-manifest execution-manifest)
        repeat (repeat-key repeat)
        declared-repeats (set (map repeat-key (:dispatch/repeats execution-manifest)))]
    (when-not (:valid? manifest-validation)
      (throw (ex-info "Cannot compile an invalid execution manifest" manifest-validation)))
    (when-not (contains? (set (:dispatch/policy-subject-roots execution-manifest))
                        policy-subject-root)
      (throw (ex-info "Policy subject is outside the manifest" {:policy-subject/root policy-subject-root})))
    (when-not (contains? declared-repeats repeat)
      (throw (ex-info "Repeat fixture is outside the manifest" {:repeat repeat})))
    (let [definition (:research-definition frozen)
          cases (:research/cases definition)
          measures (:research/measures definition)
          case-index (axis-index (get-in frozen [:derived :case-axis]))
          measure-index (axis-index (get-in frozen [:derived :measure-axis]))
          selected (filter #(and (= policy-subject-root (:policy-subject/root %))
                                 (= repeat (repeat-key %)))
                           (:dispatch/cells execution-manifest))
          by-case (into {} (map (juxt :case/id identity) selected))
          failed-cell-roots
          (into #{}
                (for [entry (:execution/entries execution-report)
                      :when (= :failed (:execution/status entry))]
                  (:research-execution-cell/root entry)))
          statuses (into (sorted-map)
                         (for [research-case cases
                               :let [execution-cell (get by-case (:case/id research-case))]]
                           [(:case/id research-case)
                            (if execution-cell
                              (let [cell-root (:research-execution-cell/root execution-cell)
                                    stored-status (store/cell-status result-store cell-root)]
                                (if (and (= :missing stored-status)
                                         (contains? failed-cell-roots cell-root))
                                  :failed
                                  stored-status))
                              :missing)]))
          coordinates
          (into {}
                (for [research-case cases
                      measure measures
                      :let [case-id (:case/id research-case)
                            status (statuses case-id)
                            result (when (= :complete status)
                                     (store/result-by-cell result-store
                                                           (:research-execution-cell/root (by-case case-id))))
                            raw (case status
                                  :complete (get-in result [:metrics (metric-key measure)] :missing)
                                  :conflicting :not-comparable
                                  :failed :missing
                                  :missing :missing)
                            ]]
                  [[(case-index case-id) (measure-index (:measure/id measure))] raw]))
          researcher-axis-root
          (root :research-execution-researcher-axis-v1
                {:policy-subject-roots (:dispatch/policy-subject-roots execution-manifest)
                 :execution/repeat repeat})
          complete? (every? #{:complete} (vals statuses))
          body {:artifact/schema schema
                :research-definition/root (:research-definition/root frozen)
                :subject-roots [(:research-execution-manifest/root execution-manifest)]
                :case-axis/root (get-in frozen [:derived :case-axis/root])
                :measure-axis/root (get-in frozen [:derived :measure-axis/root])
                :researcher-axis/root researcher-axis-root
                :researcher {:researcher/id (str "policy-subject/" policy-subject-root)}
                :analysis/policy-subject-root policy-subject-root
                :analysis/repeat repeat
                :analysis/status (if complete? :complete :incomplete)
                :analysis/cell-statuses statuses
                :analysis/coordinates coordinates
                :proof-of-possession nil}]
      (assoc body :research-analysis/root (analysis-root body))))))
