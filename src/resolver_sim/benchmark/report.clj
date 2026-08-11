(ns resolver-sim.benchmark.report
  "Build a report-ready data structure from a benchmark evidence bundle,
   benchmark concepts, and scoring definition.

   Produces a plain map suitable for Clerk rendering — no view logic.
   Supports both explicit-path and auto-resolved (from evidence bundle)
   report construction."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [resolver-sim.concepts.benchmark :as benchmark-concepts]
            [resolver-sim.use-cases.registry :as use-case-registry]
            [resolver-sim.benchmark.claims :refer [normalize-claim-refs]]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.io.resource-path :as rp]))

;; ── Path resolution ────────────────────────────────────────────────────────────
;; Maps from scoring IDs to paths for auto-resolution.

(def benchmark-scoring-paths
  "Scoring rule ID → scoring file path."
  {:scoring/robustness-dimensions-v0 hash-ref/scoring-robustness-dimensions-path
   :scoring/binary-claims-v1 hash-ref/scoring-binary-claims-path
   :scoring/severity-weighted-robustness-v1 hash-ref/scoring-severity-weighted-path
   :scoring/severity-weighted-v1 hash-ref/scoring-severity-weighted-path
   :scoring/shortfall-allocation-v0 hash-ref/scoring-shortfall-allocation-path})

;; ── Data loading ──────────────────────────────────────────────────────────────

(defn load-edn
  [path]
  (rp/edn-read path))

(defn load-evidence
  [path]
  (load-edn path))

(defn load-benchmark-concepts
  [path]
  (when (and path (rp/path-exists? path))
    (benchmark-concepts/load-benchmark-local-concepts [path])))

(defn load-scoring
  [path]
  (load-edn path))

(defn- benchmark-domain-index
  []
  (try
    (into {}
          (map (juxt :domain/id identity))
          (:domains (rp/edn-read hash-ref/benchmark-registry-path)))
    (catch Exception _
      {})))

(defn- benchmark-domain-entry
  [domain-id]
  (get (benchmark-domain-index) domain-id))

(defn- reference-validation-path-by-id
  [scenario-id]
  (when-let [manifest (try (rp/edn-read hash-ref/reference-validation-suite-manifest)
                           (catch Exception _ nil))]
    (some (fn [scenario]
            (when (= scenario-id (:id scenario))
              (:simulator/scenario-path scenario)))
          (:scenarios manifest))))

;; ── Concept lookup ───────────────────────────────────────────────────────────

(defn concept-index
  "Build a dimension-ID → concept map from benchmark concepts."
  [concepts]
  (into {} (map (fn [c] [(:concept/id c) c]) concepts)))

(defn dimension->concept
  "Look up the concept definition for a robustness dimension keyword."
  [concept-idx dim-id]
  (get concept-idx dim-id))

;; ── Dimension results ────────────────────────────────────────────────────────

(defn scenario->outcome
  "Look up a scenario's outcome in the evidence results list.
   Prefer explicit :scenario/id, then a resolved reference-validation path,
   then fall back to file-path matching."
  [scenario-id results]
  (let [scenario-path (reference-validation-path-by-id scenario-id)
        match (or (first (filter #(= scenario-id (:scenario/id %)) results))
                  (when scenario-path
                    (first (filter #(= scenario-path (:simulator/scenario-path %)) results)))
                  (when scenario-path
                    (first (filter #(= scenario-path (:file %)) results)))
                  (first (filter #(str/includes? (or (:file %) "") scenario-id) results)))]
    (when match
      (cond-> {:outcome (:outcome match)
               :halt-reason (:halt-reason match)
               :file (:file match)
               :scenario/evidence-root (:scenario/evidence-root match)}
        (:simulator/scenario-path match)
        (assoc :simulator/scenario-path (:simulator/scenario-path match))
        (:invariant-results match)
        (assoc :invariant-results (:invariant-results match))))))

(defn- scenario-claim-results
  [scenario-id outcome-data claim-results]
  (let [scenario-file (:file outcome-data)
        scenario-path (:simulator/scenario-path outcome-data)]
    (filterv (fn [claim-result]
               (and (= :scenario (:claim/scope claim-result))
                    (or (= scenario-id (:scenario/id claim-result))
                        (and scenario-file (= scenario-file (:scenario/file claim-result)))
                        (and scenario-path (= scenario-path (:simulator/scenario-path claim-result))))))
             claim-results)))

(defn- pass-state
  [xs pred]
  (when (seq xs)
    (boolean (every? pred xs))))

(defn- invariant-pass-state
  [invariant-results]
  (pass-state invariant-results #(= :pass (:result %))))

(defn- dimension-pass?
  [scenario-pass? claims-pass? invariants-pass?]
  (and scenario-pass?
       (not= false claims-pass?)
       (not= false invariants-pass?)))

(defn build-dimension-results
  "For each dimension/scenario entry in the benchmark manifest, find the
   actual scenario outcome from the evidence results and merge concept data.

   evidence-results — vector of scenario result maps from the evidence bundle
   claim-results    — vector of evaluated claim result maps from the evidence bundle
   manifest         — the benchmark manifest map
   concepts         — vector of benchmark concept definitions
   scoring          — the scoring definition map

   Returns a vector of dimension result maps."
  [evidence-results claim-results manifest concepts scoring]
  (let [scenario-entries (:benchmark/scenarios manifest)
        concept-idx (concept-index concepts)
        dimension-rules (->> scoring :scoring/rules :dimensions
                             (map (fn [d] [(:dimension/id d) d]))
                             (into {}))]
    (mapv (fn [entry]
            (let [dim-id (:dimension entry)
                  scenario-id (:scenario/id entry)
                  declared-claim (:claim entry)
                  outcome-data (scenario->outcome scenario-id evidence-results)
                  scenario-pass? (= :pass (:outcome outcome-data))
                  dimension-claim-results (scenario-claim-results scenario-id outcome-data claim-results)
                  claims-pass? (pass-state dimension-claim-results #(= :pass (:claim/outcome %)))
                  invariants-pass? (invariant-pass-state (:invariant-results outcome-data))
                  dimension-ok? (dimension-pass? scenario-pass? claims-pass? invariants-pass?)
                  concept (dimension->concept concept-idx dim-id)
                  scoring-rule (get dimension-rules dim-id)]
              {:dimension dim-id
               :scenario/id scenario-id
               :declared-claim declared-claim
               :outcome (:outcome outcome-data)
               :halt-reason (:halt-reason outcome-data)
               :scenario/pass? scenario-pass?
               :claims/pass? claims-pass?
               :invariants/pass? invariants-pass?
               :dimension/pass? dimension-ok?
               :pass-condition (:pass-condition scoring-rule)
               :pass-condition-met? dimension-ok?
               :claim-results dimension-claim-results
               :invariant-results (:invariant-results outcome-data)
               :concept/title (:concept/title concept)
               :concept/summary (:concept/summary concept)
               :concept/stakeholder-language (:concept/stakeholder-language concept)
               :concept/why-it-matters (:concept/why-it-matters concept)
               :concept/maps-to (:concept/maps-to concept)
               :scenario/evidence-root (:scenario/evidence-root outcome-data)}))
          scenario-entries)))

;; ── Scoring classification ───────────────────────────────────────────────────

(def ^:private claim-maturity-levels
  {:level-1 {:label "Level 1 — mechanical"
             :description "Required artifacts, hashes, evidence roots, and result fields exist and are internally consistent."}
   :level-2 {:label "Level 2 — invariant-backed"
             :description "Named post-hoc invariants passed for each scenario; claim is linked to invariant results."}
   :level-3 {:label "Level 3 — semantic"
             :description "Domain-specific reasoning over scenario results, world state, or evidence nodes. Currently deferred — not evaluated."}})

(defn- classify-per-scenario
  "Classify based on scenario outcome counts.
   Uses explicit labels: 'scenario replay passed' instead of broad 'pass'."
  [total passed rule]
  (let [{:keys [score-fn]} rule]
    (cond
      (zero? total)
      {:classification-label "No scenarios executed"
       :scoring/summary (or score-fn "N/A")}

      (nil? total)
      {:classification-label "Unclassified — no scenario metrics"
       :scoring/summary (or score-fn "N/A")}

      (= total passed)
      {:classification-label "Scenario replay passed"
       :scoring/summary (or score-fn "N/A")}

      (zero? passed)
      {:classification-label "Scenario replay failed"
       :scoring/summary (or score-fn "N/A")}

      :else
      {:classification-label "Partial scenario replay"
       :scoring/summary (or score-fn "N/A")})))

(defn- classify-per-claim
  "Classify based on claim results. Returns nil when claim-results are absent
   or empty — caller should fall back to per-scenario classification.
   Uses 'mechanical claims passed' / 'semantic claims deferred' labels."
  [claim-results rule]
  (when (seq claim-results)
    (let [{:keys [score-fn]} rule
          all-pass? (every? #(= :pass (:claim/outcome %)) claim-results)
          any-fail? (some #(= :fail (:claim/outcome %)) claim-results)
          any-inconclusive? (some #(= :inconclusive (:claim/outcome %)) claim-results)
          any-not-exercised? (some #(= :not-exercised (:claim/outcome %)) claim-results)
          any-not-implemented? (some #(= :not-implemented (:claim/outcome %)) claim-results)]
      {:classification-label (cond
                               all-pass? "Mechanical claims passed"
                               any-fail? "Mechanical claims failed"
                               any-not-exercised? "Required claim not exercised"
                               any-not-implemented? "Claim evaluator not implemented"
                               any-inconclusive? "Semantic claims deferred"
                               :else "Partial mechanical verification")
       :scoring/summary (or score-fn "N/A")})))

(defn- classify-critical
  "Classify based on severity-weighted claim results.
   Requires :critical-severity set in the scoring rule and non-nil claim-results.
   Returns nil when either is missing.
   Uses 'critical claim failed' / 'non-critical failures only' labels."
  [claim-results rule]
  (when (and (seq claim-results) (:critical-severity rule))
    (let [{:keys [score-fn critical-severity]} rule
          all-pass? (every? #(= :pass (:claim/outcome %)) claim-results)
          critical-fail? (some (fn [cr]
                                 (and (= :fail (:claim/outcome cr))
                                      (contains? critical-severity
                                                 (get-in cr [:claim/severity] :low))))
                               claim-results)
          any-inconclusive? (some #(= :inconclusive (:claim/outcome %)) claim-results)]
      {:classification-label (cond
                               all-pass?    "All claims pass — mechanical and invariant verification passed"
                               critical-fail? "Critical claim failed — semantic claim violation detected"
                               any-inconclusive? "Semantic claims deferred — not evaluated"
                               :else        "Non-critical failures only")
       :scoring/summary (or score-fn "N/A")})))

(defn- claim-maturity-level
  "Determine the highest claim maturity level present in the evidence.
   Returns a keyword :level-1, :level-2, :level-3, or nil."
  [claim-results manifest]
  (let [registered-claim-ids (set (keys (:claim-registry manifest)))
        scenario-claims (set (map :claim (:benchmark/scenarios manifest)))
        manifest-claim-ids (set (map :claim/id (:benchmark/claims manifest)))
        evaluated-ids (set (map :claim/id claim-results))]
    (cond
      (and (seq evaluated-ids)
           (some #(contains? registered-claim-ids %) evaluated-ids))
      :level-1
      (some #(contains? #{:claim/no-unauthorized-release :claim/funds-conserved
                          :claim/dispute-liveness :claim/slashing-conservation
                          :claim/governance-non-interference :claim/bounded-resolution-time
                          :claim/yield-preserved-during-shortfall :claim/partial-fill-fairness
                          :claim/partial-fill-decision-integrity
                          :claim/no-leakage-beyond-shortfall :claim/waterfall-coverage-correct
                          :claim/no-over-slashing :claim/appeal-bond-adequacy}
                        %) (concat evaluated-ids manifest-claim-ids))
      :level-2
      (or (seq scenario-claims) (some #(not (contains? registered-claim-ids %)) manifest-claim-ids))
      :level-3)))

(defn classify-result
  "Apply the scoring rule's classifier to available evidence.
   Dispatches on (:classifier scoring-rules):
     :pass-fail-per-scenario  — uses scenario outcome counts
     :pass-fail-per-claim    — uses per-claim outcomes from claim-results
     :pass-fail-critical     — uses severity-weighted claim results
   Returns {:classification-label <string> :scoring/summary <string>
            :claim-maturity <map-or-nil>}
   or {:classification-label \"Unclassified\" :reason <string>}."
  [total passed scoring-rules claim-results manifest]
  (let [maturity (when (and manifest (seq claim-results))
                   (claim-maturity-level claim-results manifest))
        maturity-info (when maturity
                        (assoc (get claim-maturity-levels maturity)
                               :maturity/key maturity))]
    (if-not (map? scoring-rules)
      {:classification-label "Unclassified" :reason "No scoring rules defined"}
      (let [{:keys [classifier] :as rule} scoring-rules
            base (case classifier
                   :pass-fail-per-scenario
                   (classify-per-scenario total passed rule)

                   :pass-fail-per-claim
                   (or (classify-per-claim claim-results rule)
                       (classify-per-scenario total passed rule))

                   :pass-fail-critical
                   (or (classify-critical claim-results rule)
                       {:classification-label "Unclassified"
                        :reason (str "Cannot classify " (pr-str classifier)
                                     " — claim-results or :critical-severity is missing")})

                   {:classification-label "Unclassified"
                    :reason (str "Unknown classifier: " (pr-str classifier))})]
        (if maturity-info
          (assoc base :claim-maturity maturity-info)
          base)))))

;; ── Report assembly ──────────────────────────────────────────────────────────

(defn- resolve-report-concepts
  [manifest concepts-path registry-path]
  (let [local-concepts (when concepts-path
                         (let [base-paths (benchmark-concepts/benchmark-concept-files)
                               merged-paths (if (and (.exists (io/file concepts-path))
                                                     (not (some #(= concepts-path %) base-paths)))
                                              (conj base-paths concepts-path)
                                              base-paths)]
                           (benchmark-concepts/load-benchmark-local-concepts merged-paths)))]
    (let [loaded-use-cases (when registry-path
                             (use-case-registry/load-use-case-registry registry-path))]
      (assoc (benchmark-concepts/resolve-benchmark-concepts
              (:benchmark/concepts manifest)
              (cond-> {}
                local-concepts (assoc :local-concepts local-concepts)
                loaded-use-cases (assoc :use-case-registry loaded-use-cases)))
             :use-case-registry loaded-use-cases))))

(defn- benchmark-framework-concepts
  [concepts]
  (->> concepts
       (mapcat (fn [concept]
                 (or (:concept/framework-concepts concept)
                     (get-in concept [:concept/maps-to :framework-concepts])
                     [])))
       distinct
       vec))

(defn- benchmark-concept-provenance
  [resolved-entries]
  (mapv (fn [{:keys [concept/id concept/source concept]}]
          (cond-> {:concept/id id
                   :concept/source source}
            concept
            (assoc :concept/title (or (:concept/title concept) (:concept/name concept))
                   :concept/maps-to (:concept/maps-to concept))))
        resolved-entries))

(defn- benchmark-concept-summary
  [resolved-entries]
  (mapv (fn [{:keys [concept/id concept/source concept]}]
          (cond-> {:concept/id id
                   :concept/source source}
            concept
            (assoc :concept/title (or (:concept/title concept) (:concept/name concept))
                   :concept/framework-concepts
                   (vec (or (:concept/framework-concepts concept)
                            (get-in concept [:concept/maps-to :framework-concepts])
                            [])))))
        resolved-entries))

(defn build-conclusion
  "Return an explicit, scope-bounded conclusion for a benchmark evidence bundle.
   A scenario pass alone is insufficient: every evaluated claim must pass."
  [metrics claim-results evidence-hash benchmark-run-hash]
  (let [scenarios-pass? (and (pos? (or (:total metrics) 0))
                             (= (:total metrics) (:passed metrics)))
        claims-present? (seq claim-results)
        claims-pass? (and claims-present?
                          (every? #(= :pass (:claim/outcome %)) claim-results))
        claim-failed? (some #(= :fail (:claim/outcome %)) claim-results)
        outcome (cond
                  (or (not scenarios-pass?) claim-failed?) :fail
                  claims-pass? :pass
                  :else :incomplete)
        statement (case outcome
                    :pass "All executed scenarios and evaluated claims passed within the declared benchmark scope."
                    :fail "The benchmark did not establish its declared conclusion because a scenario or evaluated claim failed."
                    "The benchmark conclusion is incomplete because required claim evaluation is absent or not fully passing.")]
    {:outcome outcome
     :statement statement
     :limits ["Applies only to the declared benchmark scenarios, protocol model, runner, and policies."
              "Does not establish production equivalence or comprehensive protocol assurance."]
     :evidence/hash evidence-hash
     :benchmark-run/hash benchmark-run-hash}))

(defn build-report
  "Build a complete report data structure from:
     evidence-path    — EDN evidence bundle produced by bb benchmark:run
     concepts-path    — optional benchmark-local concepts EDN override
     scoring-path     — scoring definition EDN (e.g. benchmarks/scoring/robustness-dimensions-v0.edn)
   
   Returns a plain map suitable for Clerk rendering."
  ([evidence-path concepts-path scoring-path]
   (build-report evidence-path concepts-path scoring-path nil))
  ([evidence-path concepts-path scoring-path use-case-registry-path]
  (let [evidence (load-evidence evidence-path)
        manifest (:benchmark evidence)
        domain-entry (benchmark-domain-entry (:benchmark/domain manifest))
        concept-resolution (resolve-report-concepts manifest concepts-path use-case-registry-path)
        concepts (:report-concepts concept-resolution)
        scoring (load-scoring scoring-path)
        metrics (:metrics evidence)
        results (:results evidence)
        inv-summary (:invariant-summary evidence)
        dimensions (build-dimension-results results (:claim-results evidence) manifest concepts scoring)]
    {:benchmark/id (:benchmark/id manifest)
     :benchmark/domain (:benchmark/domain manifest)
     :benchmark/domain-description (:domain/description domain-entry)
     :benchmark/concepts (benchmark-concept-provenance (:resolved-entries concept-resolution))
     :benchmark/concept-summary (benchmark-concept-summary (:resolved-entries concept-resolution))
     :benchmark/framework-concepts (benchmark-framework-concepts concepts)
     :purpose (:benchmark/purpose manifest)
     :scenario/suite (:benchmark/scenario-suite manifest)
     :scenario/suite-description (:benchmark/scenario-suite-description manifest)
     :evidence/path evidence-path
     :evidence/hash (:evidence/hash evidence)
     :environment (:environment evidence)
     :reproduce (:reproduce evidence)
     :total-scenarios (:total metrics)
     :passed-scenarios (:passed metrics)
     :all-pass? (and (pos? (:total metrics))
                     (= (:total metrics) (:passed metrics)))
     :score (if (pos? (:total metrics))
              (float (/ (:passed metrics) (:total metrics)))
              0.0)
     :scoring/classification (classify-result (:total metrics) (:passed metrics)
                                              (:scoring/rules scoring)
                                              (:claim-results evidence)
                                              (:benchmark evidence))
     :claim/status (let [cr (:claim-results evidence)
                         claim-refs (normalize-claim-refs (:benchmark/claims manifest))]
                     (cond
                       (seq cr)
                       (if (every? #(= :pass (:claim/outcome %)) cr)
                         :verified
                         :partial)
                       (seq claim-refs)
                       :declared-not-verified
                       :else :none))
     :claim/maturity (let [cr (:claim-results evidence)
                           manifest (:benchmark evidence)]
                       (when (seq cr)
                         (claim-maturity-level cr manifest)))
     :claim-results (:claim-results evidence)
     :conclusion (build-conclusion metrics
                                   (:claim-results evidence)
                                   (:evidence/hash evidence)
                                   (:benchmark-run/hash evidence))
     :dimensions dimensions
     :invariant-summary inv-summary
     :concept/section (:concept/section evidence)
     :use-case-registry (when-let [loaded (:use-case-registry concept-resolution)]
                          (select-keys loaded
                                       [:use-case-registry/id :use-case-registry/version
                                        :use-case-registry/schema :use-case-registry/source
                                        :use-case-registry/root :use-case-registry/count]))})))

;; ── Auto-resolution ───────────────────────────────────────────────────────────

(defn resolve-scoring-path
  "Resolve the scoring file path for a scoring rule ID, or nil if unknown."
  [scoring-id]
  (get benchmark-scoring-paths scoring-id))

(defn resolve-report
  "Build a complete report from an evidence bundle path alone.
   Auto-resolves concept and scoring file paths from the bundle's
   benchmark manifest.

   Concept resolution uses the shared benchmark concept resolver.

   Scoring resolution is required — throws if the scoring rule ID
   is not in the registered map."
  [evidence-path]
  (let [evidence (load-evidence evidence-path)
        manifest (:benchmark evidence)
        scoring-id (:benchmark/scoring-rule manifest)
        scoring-path (resolve-scoring-path scoring-id)]
    (if scoring-path
      (build-report evidence-path nil scoring-path)
      (throw (ex-info "Cannot resolve report paths from evidence bundle"
                      {:evidence-path evidence-path
                       :benchmark-id (:benchmark/id manifest)
                       :scoring-id scoring-id
                       :resolved-scoring (boolean scoring-path)
                       :reason "Unknown scoring rule — add to benchmark-scoring-paths in report.clj"})))))
