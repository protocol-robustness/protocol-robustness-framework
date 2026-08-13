(ns resolver-sim.benchmark.strategic-claim-validation
  "Deterministic auditable validation for strategic claims.

   This is intentionally narrow for v1:
   - deterministic replay only
   - explicit claim-to-scenario matching
   - level-scoped checks
   - replayable evidence references
   - explicit coverage gaps"
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [resolver-sim.benchmark.runner :as runner]
            [resolver-sim.allocation.proof-admission :as proof-admission]
            [resolver-sim.benchmark.strategic-property-results :as spr]
            [resolver-sim.io.scenarios :as io-sc]
            [resolver-sim.scenario.suites :as suites]
            [resolver-sim.validation.deviation-contract :as dc]
            [resolver-sim.yield.partial-fill :as partial-fill]
            [resolver-sim.yield.strategic-partial-fill :as strategic-partial-fill]
            [resolver-sim.config.paths :as paths]
            [resolver-sim.validation.classes :as classes]
            [resolver-sim.validation.gate :as gate]
            [resolver-sim.io.edn :as ppedn]))

(def strategic-claim-catalog
  "Registered strategic claims.

   Strategic-scope discipline: :deviation-set-ids is declared only when
   bounded deviation-resistance is part of the claim's subject. Only the
   flagship :claim/pro-rata-shortfall-conservation declares it, so the known
   split/merge/permute violations surface only for that claim. Other claims
   must not declare deviation sets just because they share the partial-fill
   mechanism — that would make unrelated known violations invalidate an
   otherwise correctly scoped claim."
  {:claim/pro-rata-shortfall-conservation
   {:claim/id :claim/pro-rata-shortfall-conservation
    :claim/title "Pro-rata shortfall conservation"
    :claim/description
    "Shortfall scenarios should expose a replay-verifiable evidence root and
     preserve shortfall allocation correctness at the matched mechanism level."
    :benchmark/manifest-path (paths/prf-core-shortfall-manifest)
    :mechanism-levels [:allocation/partial-fill
                       :allocation/shortfall]
    :closed-form-check-ids #{:partial-fill/conservation
                             :partial-fill/per-claim-conservation}
    :deviation-set-ids #{:partial-fill/claimant-monotonicity
                         :partial-fill/claimant-split-merge-sybil}
    :required-threat-tags #{"shortfall"}
    :match-dimensions #{:allocation/partial-fill
                        :allocation/shortfall}}

   :claim/waterfall-fill-integrity
   {:claim/id :claim/waterfall-fill-integrity
    :claim/title "Waterfall fill priority integrity"
    :claim/description
    "Partial-fill scenarios using waterfall mode should respect fill-order priority:
     higher-priority buckets are filled to exhaustion before lower-priority buckets
     receive any allocation."
    :benchmark/manifest-path (paths/prf-core-yield-manifest)
    :mechanism-levels [:allocation/partial-fill]
    :closed-form-check-ids #{:partial-fill/waterfall-priority}
    :required-threat-tags #{"shortfall"}
    :match-dimensions #{:allocation/partial-fill}}

   :claim/partial-fill-rounding-integrity
   {:claim/id :claim/partial-fill-rounding-integrity
    :claim/title "Partial-fill rounding residual integrity"
    :claim/description
    "Partial-fill decisions should respect rounding policy bounds under all modes:
     residual amounts must fall within the defined acceptable range for the active
     rounding policy."
    :benchmark/manifest-path (paths/prf-core-yield-manifest)
    :mechanism-levels [:allocation/partial-fill]
    :closed-form-check-ids #{:partial-fill/rounding-residual-bounded}
    :required-threat-tags #{"shortfall"}
    :match-dimensions #{:allocation/partial-fill}}

   :claim/mode-validity
   {:claim/id :claim/mode-validity
    :claim/title "Partial-fill mode validity"
    :claim/description
    "Partial-fill decisions must declare a recognized fill mode: pro-rata,
     principal-first, or waterfall. Unrecognized modes are rejected."
    :benchmark/manifest-path (paths/prf-core-yield-manifest)
    :mechanism-levels [:allocation/partial-fill]
    :closed-form-check-ids #{:partial-fill/mode-valid
                             :partial-fill/settlement-mode-valid}
    :required-threat-tags #{"shortfall"}
    :match-dimensions #{:allocation/partial-fill}}

   :claim/shortfall-detection-validity
   {:claim/id :claim/shortfall-detection-validity
    :claim/title "Shortfall detection validity"
    :claim/description
    "Shortfall scenarios should detect and record shortfall correctly:
     the shortfall evidence root must be verifiable, conservation invariants
     must hold, and deferred/haircut splits must be consistent with the
     declared basis amount."
    :benchmark/manifest-path (paths/prf-core-shortfall-manifest)
    :mechanism-levels [:allocation/shortfall]
    :required-threat-tags #{"shortfall"}
    :match-dimensions #{:allocation/shortfall}}

   :claim/pro-rata-fairness-end-to-end
   {:claim/id :claim/pro-rata-fairness-end-to-end
    :claim/title "Pro-rata fairness end-to-end"
    :claim/description
    "Partial-fill scenarios should produce fair pro-rata allocations:
     every claimant must receive the same fill ratio within rounding
     tolerance. The default assurance is deterministic evidence validation;
     cryptographic-computation assurance is a separately fail-closed graduation
     that additionally requires a supported realized-statement proof profile."
    :claim/assurance-level :assurance/evidence
    :benchmark/manifest-path (paths/prf-core-yield-manifest)
    :mechanism-levels [:allocation/partial-fill]
    :closed-form-check-ids #{:partial-fill/exact-pro-rata
                             :partial-fill/rounding-fairness
                             :partial-fill/fail-action-fairness}
    :closed-form-alternative-sets #{#{:partial-fill/exact-pro-rata
                                      :partial-fill/rounding-fairness}}
    :required-threat-tags #{"shortfall"}
    :match-dimensions #{:allocation/partial-fill}}})

(def ^:private artifact-kind :game-theoretic-validation)

(def ^:private artifact-version "game-theoretic-validation.artifact.v1")

(def ^:private allowed-level-verdicts #{:pass :fail :uncovered})

(defn- sha-256-hex?
  [s]
  (boolean (and (string? s) (re-matches #"[0-9a-f]{64}" s))))

(defn- normalize-scenario-id
  [scenario-id]
  (some-> scenario-id str str/lower-case (str/replace "_" "-")))

(defn- scenario-index-for-suite
  [suite-key]
  (into {}
        (map (fn [path]
               (let [scenario (io-sc/load-scenario-file path)]
                 [path {:scenario/id (:scenario-id scenario)
                        :scenario/public-id (io-sc/scenario-file->id path)
                        :scenario/id-normalized (normalize-scenario-id (:scenario-id scenario))
                        :scenario/public-id-normalized (normalize-scenario-id (io-sc/scenario-file->id path))
                        :scenario/path path
                        :scenario/title (or (:title scenario)
                                            (:scenario-title scenario))
                        :scenario/purpose (:purpose scenario)
                        :scenario/tags (vec (or (:tags scenario) []))
                        :scenario/threat-tags (vec (or (:threat-tags scenario) []))
                        :scenario/events (mapv :action (:events scenario))}])))
        (suites/suite-paths suite-key)))

(defn- benchmark-scenario-declarations
  [manifest]
  (reduce (fn [idx entry]
            (assoc idx
                   (normalize-scenario-id (:scenario/id entry))
                   entry))
          {}
          (:benchmark/scenarios manifest)))

(defn- path-basename
  [path]
  (.getName (io/file (str path))))

(defn- result-by-path
  "Index results by their reported path and basename. Benchmark execution resolves
   resource paths to filesystem paths, whereas suite metadata retains resource-relative
   paths; the basename is the stable identifier across those representations."
  [results]
  (reduce (fn [idx result]
            (let [path (:simulator/scenario-path result)]
              (assoc idx
                     path result
                     (path-basename path) result)))
          {}
          results))

(defn- declaration-for-scenario
  [declaration-by-id scenario-meta]
  (or (get declaration-by-id (:scenario/public-id-normalized scenario-meta))
      (get declaration-by-id (:scenario/id-normalized scenario-meta))))

(defn- scenario-match-reasons
  [claim-spec declaration scenario-meta result]
  (let [dimension (:dimension declaration)
        threat-tags (set (:scenario/threat-tags scenario-meta))
        evidence-root (:scenario/evidence-root result)
        shortfall-tags (sort (filter (:required-threat-tags claim-spec) threat-tags))]
    (cond-> []
      (contains? (:match-dimensions claim-spec) dimension)
      (conj {:reason/id :benchmark/dimension
             :reason/value dimension})

      (seq shortfall-tags)
      (conj {:reason/id :scenario/threat-tags
             :reason/value shortfall-tags})

      (sha-256-hex? evidence-root)
      (conj {:reason/id :scenario/evidence-root
             :reason/value evidence-root}))))

(defn- matched-scenario?
  [claim-spec declaration scenario-meta result]
  (let [reason-ids (set (map :reason/id
                             (scenario-match-reasons claim-spec declaration scenario-meta result)))]
    (and (contains? reason-ids :benchmark/dimension)
         (contains? reason-ids :scenario/threat-tags)
         (contains? reason-ids :scenario/evidence-root))))

(defn- match-entry
  [claim-spec declaration scenario-meta result]
  {:scenario/id (:scenario/public-id scenario-meta)
   :benchmark/declaration {:scenario/id (:scenario/id declaration)
                           :dimension (:dimension declaration)
                           :claim (:claim declaration)}
   :mechanism-level (:dimension declaration)
   :scenario/source-path (:scenario/path scenario-meta)
   :scenario/title (:scenario/title scenario-meta)
   :scenario/purpose (:scenario/purpose scenario-meta)
   :match-reasons (scenario-match-reasons claim-spec declaration scenario-meta result)
   :evidence-references [{:reference/type :scenario-evidence-root
                          :reference/value (:scenario/evidence-root result)}
                         {:reference/type :simulator-scenario-path
                          :reference/value (:file result)}]})

(defn- invariant-failures
  [result]
  (->> (:invariant-results result)
       (filter #(not= :pass (:result %)))
       (mapv :id)))

(defn- closed-form-check-results
  [result check-ids]
  (when (seq check-ids)
    (let [decisions (:partial-fill-decisions result)]
      (if (empty? decisions)
        [{:check/id :partial-fill-decision-present
          :status :not-exercised
          :details {:reason :no-partial-fill-decision-artifacts}}]
        (mapcat (fn [decision]
                  (let [checks (try
                                 (partial-fill/partial-fill-closed-form-checks decision)
                                 (catch clojure.lang.ExceptionInfo e
                                   (:check-results (ex-data e))))]
                    (->> checks
                         (filter #(contains? check-ids (:check/id %)))
                         (map (fn [check]
                                (assoc check :decision/id (:decision/id decision)))))))
                decisions)))))

(defn- scenario-check-results
  [claim-spec mechanism-level result]
  (let [assurance-level (or (:claim/assurance-level claim-spec) :assurance/evidence)
        decisions (:partial-fill-decisions result)
        stmt-root (:scenario/realized-allocation-statements-root result)
        statements (:scenario/realized-allocation-statements-data result)
        statement-by-decision (into {} (keep (fn [s]
                                               (when-let [id (:decision/id s)] [id s])))
                                    statements)
        context (:scenario/allocation-context result)
        lifecycle (:scenario/round-lifecycle result)
        statement-checks
        (mapv (fn [decision]
                (let [statement (get statement-by-decision (:decision/id decision))
                      profile (proof-admission/proof-profile-result decision)
                      recomputed? (proof-admission/statement-match?
                                   {:statement statement
                                    :allocation-context context
                                    :decision decision
                                    :round-lifecycle lifecycle})]
                  {:check/id :realized-statement-recomputed
                   :decision/id (:decision/id decision)
                   :statement-root (:statement/root statement)
                   :status (cond
                             (nil? statement) :not-exercised
                             (= :uncovered (:status profile)) :not-applicable
                             recomputed? :pass
                             :else :fail)
                   :details {:proof-profile (:proof/profile profile)
                             :profile-status (:status profile)
                             :reason (:reason profile)}}))
              (or decisions []))
        binding (:scenario/realized-statement-binding result)
        binding-ok? (proof-admission/valid-scenario-statement-binding? binding)
        ;; Explicit per-statement mapping. A proof for one statement never
        ;; covers the scenario collection unless every committed statement has
        ;; exactly one independently admitted proof tuple.
        proof-admissions (or (:scenario/realized-statement-proof-admissions result) [])
        admission-by-root (group-by (comp :statement/root :artifact) proof-admissions)
        proof-admitted? (fn [statement-root]
                          (let [entries (get admission-by-root statement-root)]
                            (and (= 1 (count entries))
                                 (proof-admission/cryptographic-computation-admitted?
                                  (first entries)))))
        coverage (proof-admission/statement-proof-coverage statements proof-admissions)
        complete-proof-coverage? (and (:complete? coverage)
                                      (every? proof-admitted? (:statement-roots coverage)))
        cryptographic? (= assurance-level :assurance/cryptographic-computation)
        base-checks [{:check/id :scenario-passed
                      :status (if (= :pass (:outcome result)) :pass :fail)
                      :details {:outcome (:outcome result)
                                :halt-reason (:halt-reason result)}}
                     {:check/id :evidence-root-valid
                      :status (if (sha-256-hex? (:scenario/evidence-root result)) :pass :fail)
                      :details {:scenario/evidence-root (:scenario/evidence-root result)}}
                     {:check/id :no-invariant-errors
                      :status (if (empty? (invariant-failures result)) :pass :fail)
                      :details {:failed-invariants (invariant-failures result)}}
                     ;; Evidence assurance stays compatible: scenarios without
                     ;; allocation context have no statement. Cryptographic
                     ;; assurance never treats that absence, or a shaped hash,
                     ;; as proof.
                     {:check/id :realized-statement-root-valid
                      :status (if (some? stmt-root)
                                (if (sha-256-hex? stmt-root) :pass :fail)
                                (if cryptographic? :not-exercised :pass))
                      :details {:realized-allocation-statements-root stmt-root}}
                     {:check/id :scenario-statement-binding-valid
                      :status (if cryptographic?
                                (if binding-ok? :pass :fail)
                                :pass)
                      :details {:binding-root (:binding-root binding)
                                :required? cryptographic?}}
                     {:check/id :sp1-proof-verified
                      :status (if cryptographic?
                                (if complete-proof-coverage? :pass :fail)
                                :pass)
                      :details {:assurance-level assurance-level
                                :covered-roots (:covered-roots coverage)
                                :statement-roots (:statement-roots coverage)}}]
        statement-checks (mapv (fn [check]
                                 (if (= :realized-statement-recomputed (:check/id check))
                                   (assoc check :proof-status
                                          (if (proof-admitted? (:statement-root check)) :pass :fail))
                                   check))
                               statement-checks)
        cf-checks (when (= :allocation/partial-fill mechanism-level)
                    (closed-form-check-results result (:closed-form-check-ids claim-spec)))
        cf-checks (map (fn [check]
                         (assoc check :statement-root
                                (:statement/root (get statement-by-decision
                                                      (:decision/id check)))))
                       cf-checks)
        ;; Extract exercise witnesses from closed-form check results
        witnesses (when cf-checks
                    (let [decisions (:partial-fill-decisions result)]
                      (mapv (fn [i d]
                              {:decision/index i
                               :settlement-mode (:settlement-mode d)
                               :fill-mode (get-in d [:policy :mode])
                               :exercised-fill? (= :partial-fill (:settlement-mode d))})
                            (range) (or decisions []))))]
    {:checks (into (cond-> base-checks
                     cryptographic? (into statement-checks))
                   (or cf-checks []))
     :witnesses (or witnesses [])}))

(defn- level-verdict
  [level matched-scenarios results claim-spec]
  (if (empty? matched-scenarios)
    {:mechanism-level level
     :verdict :uncovered
     :scenario-ids []
     :check-results []
     :evidence-references []}
    (let [scenario-ids (mapv :scenario/id matched-scenarios)
          result-for (fn [source-path]
                       (or (get results source-path)
                           (get results (path-basename source-path))))
          level-checks (mapcat (fn [match]
                                 (let [result (result-for (:scenario/source-path match))
                                       {:keys [checks]} (scenario-check-results claim-spec level result)]
                                   (map (fn [check]
                                          (assoc check :scenario/id (:scenario/id match)))
                                        checks)))
                               matched-scenarios)
          witnesses (mapcat (fn [match]
                              (let [result (result-for (:scenario/source-path match))
                                    {:keys [witnesses]} (scenario-check-results claim-spec level result)
                                    ws witnesses]
                                (map #(assoc % :scenario/id (:scenario/id match)) (or ws []))))
                            matched-scenarios)
          integrity-gate (gate/evaluate-integrity-gate
                          level-checks
                          :witnesses witnesses
                          :required-mechanisms (when (= :allocation/partial-fill level)
                                                 (set (keep :fill-mode witnesses))))
          ;; Alternative-theorem coverage: a :not-applicable check is acceptable
          ;; when it belongs to a declared :closed-form-alternative-set whose
          ;; group is covered by another member passing on the same decision.
          ;; Without this, largest-remainder dust would make the exact-pro-rata
          ;; theorem :not-applicable and incorrectly fail the whole level even
          ;; though the rounding-fairness theorem covers the regime.
          alternative-sets (or (:closed-form-alternative-sets claim-spec) #{})
          covered-alternative-sets
          (set (for [alt-set alternative-sets
                     :let [passing-ids (set (map :check/id
                                                 (filter #(= :pass (:status %)) level-checks)))
                           covered (seq (filter #(contains? passing-ids %) alt-set))]
                     :when covered]
                 alt-set))
          not-applicable-ok?
          (fn [check]
            (boolean (some (fn [alt-set]
                             (and (contains? alt-set (:check/id check))
                                  (contains? covered-alternative-sets alt-set)))
                           alternative-sets)))
          not-exercised? (some #(or (= :not-exercised (:status %))
                                    (and (= :not-applicable (:status %))
                                         (not (not-applicable-ok? %))))
                               level-checks)
          ;; Require at least one exercised partial-fill decision when checking
          ;; partial-fill allocation properties
          partial-fill-exercised? (or (not= :allocation/partial-fill level)
                                      (some :exercised-fill? witnesses))
          all-acceptable? (every? (fn [check]
                                    (or (= :pass (:status check))
                                        (and (= :not-applicable (:status check))
                                             (not-applicable-ok? check))))
                                  level-checks)
          verdict (cond
                    not-exercised? :uncovered
                    (not partial-fill-exercised?) :unexercised
                    all-acceptable? :pass
                    :else :fail)
          uncovered-reason (when (or not-exercised?
                                     (and (= :allocation/partial-fill level)
                                          (not partial-fill-exercised?)))
                             (if not-exercised?
                               (if (some #(and (= :not-applicable (:status %))
                                               (not (not-applicable-ok? %)))
                                         level-checks)
                                 :no-applicable-theorem
                                 :no-partial-fill-decision-artifacts)
                               :no-exercised-partial-fill))]
      {:mechanism-level level
       :verdict verdict
       :integrity-gate integrity-gate
       :uncovered-reason uncovered-reason
       :scenario-ids scenario-ids
       :witnesses (vec witnesses)
       :check-results (vec level-checks)
       :evidence-references (vec (mapcat :evidence-references matched-scenarios))})))

(defn- resolve-deviation-set-ids
  "Resolve every declared :deviation-set-ids entry to a registered contract.

   Fails closed: a declared id that does not resolve to a registered contract
   throws, so the audit trail cannot silently drop part of the requested scope.

   Returns {:deviation-set-ids [...] :contract-ids [...] :contracts [...]
            :deviations #{...}} with deterministic, duplicate-free
   :deviation-set-ids and :contract-ids (sorted by keyword), and the union of
   the resolved contracts' deviation generators."
  [set-ids]
  (let [ids (vec (sort set-ids))
        contracts (mapv (fn [id]
                          (or (dc/get-contract id)
                              (throw (ex-info "Unresolved deviation-set id"
                                              {:deviation-set-id id
                                               :known-ids (vec (sort (keys dc/registered-contracts)))}))))
                        ids)]
    {:deviation-set-ids ids
     :contract-ids (mapv :contract/id contracts)
     :contracts contracts
     :deviations (into #{} (mapcat :deviation-generators) contracts)}))

(defn- strategic-validation-for-claim
  "Run the strategic-property validation for a claim's declared deviation sets.

   Resolves each :deviation-set-ids entry to a registered deviation contract,
   unions the contracts' deviation generators, and runs the exhaustive
   strategic-partial-fill validation. Returns nil when the claim declares no
   deviation sets; otherwise
   {:deviation-set-ids [...] :contract-ids [...] :deviations #{...}
    :artifact {:properties [...] :summary {...}}}.

   The unioned deviations are passed explicitly (no :contract-id) because
   validate-strategic-properties would otherwise derive the deviation set from a
   single contract and silently ignore the others."
  [claim-spec]
  (when (seq (:deviation-set-ids claim-spec))
    (let [resolved (resolve-deviation-set-ids (:deviation-set-ids claim-spec))]
      (assoc resolved
             :artifact (strategic-partial-fill/validate-strategic-properties
                        :deviations (:deviations resolved))))))

(defn- strategic-claim-artifact
  [claim-spec manifest evidence]
  (let [suite-key (:benchmark/scenario-suite manifest)
        scenario-meta-by-path (scenario-index-for-suite suite-key)
        declaration-by-id (benchmark-scenario-declarations manifest)
        results-by-path (result-by-path (:results evidence))
        scenario-entries (->> scenario-meta-by-path
                              vals
                              (keep (fn [scenario-meta]
                                      (let [declaration (declaration-for-scenario declaration-by-id scenario-meta)
                                            source-path (:scenario/path scenario-meta)
                                            result (or (get results-by-path source-path)
                                                       (get results-by-path (path-basename source-path)))]
                                        (when (and declaration result)
                                          {:declaration declaration
                                           :scenario-meta scenario-meta
                                           :result result}))))
                              (sort-by (fn [{:keys [declaration scenario-meta]}]
                                         [(:dimension declaration) (:scenario/id scenario-meta)])))
        matched-scenarios (->> scenario-entries
                               (filter (fn [{:keys [declaration scenario-meta result]}]
                                         (matched-scenario? claim-spec declaration scenario-meta result)))
                               (mapv (fn [{:keys [declaration scenario-meta result]}]
                                       (match-entry claim-spec declaration scenario-meta result))))
        matched-by-level (group-by :mechanism-level matched-scenarios)
        declared-by-level (group-by (fn [{:keys [declaration]}]
                                      (:dimension declaration))
                                    scenario-entries)
        level-verdicts (mapv (fn [level]
                               (level-verdict level
                                              (get matched-by-level level [])
                                              results-by-path
                                              claim-spec))
                             (:mechanism-levels claim-spec))
        strategic-validation (strategic-validation-for-claim claim-spec)
        strategic-artifact (:artifact strategic-validation)
        strategic-properties (or (:properties strategic-artifact) [])
        strategic-property-results (spr/strategic-properties->results strategic-artifact)
        strategic-deviation-results (spr/strategic-properties->deviation-results
                                     strategic-artifact)
        level-verdicts (if (seq strategic-properties)
                         (mapv (fn [entry]
                                 (if (= :allocation/partial-fill (:mechanism-level entry))
                                   (assoc entry :properties strategic-properties)
                                   entry))
                               level-verdicts)
                         level-verdicts)
        coverage-gaps (->> level-verdicts
                           (filter #(= :uncovered (:verdict %)))
                           (mapv (fn [entry]
                                   (let [level (:mechanism-level entry)]
                                     {:mechanism-level level
                                      :reason (or (:uncovered-reason entry)
                                                  (if (seq (get declared-by-level level))
                                                    :declared-scenarios-failed-match-basis
                                                    :no-declared-scenarios-for-level))}))))
        passed-level-count (count (filter #(= :pass (:verdict %)) level-verdicts))
        failed-level-count (count (filter #(= :fail (:verdict %)) level-verdicts))
        uncovered-level-count (count coverage-gaps)
        ;; Collect all check results for gate evaluation
        all-check-results (mapcat :check-results level-verdicts)
        all-witnesses (mapcat :witnesses level-verdicts)
        integrity-verdicts (keep :integrity-gate level-verdicts)
        validation-classes (->> (concat
                                 (keep :validation-class all-check-results)
                                 (keep :validation-class strategic-property-results))
                                distinct
                                (sort-by (fn [c] (.indexOf classes/class-order c)))
                                vec)
        ;; Evaluate economic-model gate using upstream integrity verdicts
        combined-integrity (first integrity-verdicts)
        economic-model-gate (gate/evaluate-economic-model-gate
                             (or combined-integrity {:gate :integrity :verdict :pass})
                             all-check-results
                             :assumptions {:claim-id (:claim/id claim-spec)})
        ;; Strategic gate — deviation-resistance results come from the claim's
        ;; declared deviation sets (split/merge/permute/sybil/inflate) run through
        ;; strategic-partial-fill and routed into the gate as deviation results.
        strategic-gate (gate/evaluate-strategic-gate
                        economic-model-gate
                        strategic-deviation-results
                        []
                        :contract-ids (:contract-ids strategic-validation)
                        :scope {:mechanism-levels (:mechanism-levels claim-spec)
                                :deviation-set-ids (:deviation-set-ids strategic-validation)
                                :deviations (vec (sort (:deviations strategic-validation)))})
        gates-summary (let [integrity-v (get (first integrity-verdicts) :verdict :pass)
                            economic-v (:verdict economic-model-gate)
                            strategic-v (:verdict strategic-gate)]
                        (cond
                          (= :blocked integrity-v) :integrity-blocked
                          (= :blocked economic-v) :economic-model-blocked
                          (= :blocked strategic-v) :strategic-blocked
                          (= :inconclusive economic-v) :economic-model-inconclusive
                          (= :inconclusive strategic-v) :strategic-inconclusive
                          (= :violated strategic-v) :strategic-violated
                          :else :all-pass))]
    {:artifact/kind artifact-kind
     :artifact/version artifact-version
     :claim/id (:claim/id claim-spec)
     :claim/title (:claim/title claim-spec)
     :claim/description (:claim/description claim-spec)
     :claim/interpretation
     (if (:deviation-set-ids claim-spec)
       "Pass means the claim was not falsified by the matched scenarios and the
        declared deviation sets on the evaluated evidence. It is bounded and
        evidence-scoped: it does not prove the claim over the full strategy space,
        unexercised mechanisms, or undeclared deviation sets."
       "Pass means the claim was not falsified by the matched scenarios on the
        evaluated evidence. It is bounded and evidence-scoped: it does not prove
        the claim over the full strategy space or unexercised mechanisms.")
     :claim/validation-classes validation-classes
     :benchmark/id (:benchmark/id manifest)
     :benchmark/scenario-suite suite-key
     :benchmark/manifest-path (:benchmark/manifest-path claim-spec)
     :matched-scenarios matched-scenarios
     :level-verdicts level-verdicts
     :coverage-gaps coverage-gaps
     :strategic-property-results strategic-property-results
     :strategic-deviation-scope (when strategic-validation
                                  {:deviation-set-ids (:deviation-set-ids strategic-validation)
                                   :contract-ids (:contract-ids strategic-validation)
                                   :deviations (vec (sort (:deviations strategic-validation)))})
     :gates {:integrity (first integrity-verdicts)
             :economic-model economic-model-gate
             :strategic strategic-gate}
     :gates-summary gates-summary
     :summary {:matched-scenario-count (count matched-scenarios)
               :passed-level-count passed-level-count
               :failed-level-count failed-level-count
               :uncovered-level-count uncovered-level-count
               :strategic-property-count (count strategic-properties)
               :strategic-property-violations (count (filter #(= :violated (:verdict %))
                                                             strategic-deviation-results))
               :gates-blocked? (some #(= :blocked (:verdict %))
                                     [(first integrity-verdicts)
                                      economic-model-gate
                                      strategic-gate])
               :valid? (and (zero? failed-level-count)
                            (zero? uncovered-level-count)
                            (= :all-pass gates-summary))}}))

(defn- valid-coverage-gap?
  [gap]
  (and (keyword? (:mechanism-level gap))
       (contains? #{:no-declared-scenarios-for-level
                    :declared-scenarios-failed-match-basis
                    :no-partial-fill-decision-artifacts
                    :no-applicable-theorem}
                  (:reason gap))))

(defn- validate-artifact!
  [artifact]
  (when-not (= artifact-kind (:artifact/kind artifact))
    (throw (ex-info "Invalid strategic claim artifact kind"
                    {:expected artifact-kind
                     :actual (:artifact/kind artifact)})))
  (when-not (= artifact-version (:artifact/version artifact))
    (throw (ex-info "Invalid strategic claim artifact version"
                    {:expected artifact-version
                     :actual (:artifact/version artifact)})))
  (doseq [k [:claim/id :benchmark/id :benchmark/scenario-suite
             :matched-scenarios :level-verdicts :coverage-gaps :summary]]
    (when-not (contains? artifact k)
      (throw (ex-info "Strategic claim artifact missing required key"
                      {:missing-key k}))))
  (doseq [entry (:level-verdicts artifact)]
    (when-not (contains? allowed-level-verdicts (:verdict entry))
      (throw (ex-info "Invalid level verdict in strategic claim artifact"
                      {:entry entry}))))
  (doseq [gap (:coverage-gaps artifact)]
    (when-not (valid-coverage-gap? gap)
      (throw (ex-info "Invalid coverage gap in strategic claim artifact"
                      {:gap gap}))))
  artifact)

(defn- sort-maps
  [x]
  (cond
    (map? x) (into (sorted-map-by (fn [a b]
                                    (compare (str a) (str b))))
                   (map (fn [[k v]] [k (sort-maps v)]) x))
    (vector? x) (mapv sort-maps x)
    (seq? x) (doall (map sort-maps x))
    :else x))

(defn run-strategic-claim-validation
  [& {:keys [claim-id out-dir]
      :or {claim-id :claim/pro-rata-shortfall-conservation
           out-dir "./prf-out/game-theory"}}]
  (let [claim-spec (or (get strategic-claim-catalog claim-id)
                       (throw (ex-info "Unknown strategic claim"
                                       {:claim-id claim-id
                                        :known-claims (sort (keys strategic-claim-catalog))})))
        manifest (runner/load-manifest (:benchmark/manifest-path claim-spec))
        evidence (runner/run-benchmark (:benchmark/manifest-path claim-spec))
        artifact (strategic-claim-artifact claim-spec manifest evidence)
        claim-name (name claim-id)
        base-path (str out-dir "/" claim-name)
        edn-path (str base-path "/game-theoretic-validation-artifact.edn")
        json-path (str base-path "/game-theoretic-validation-artifact.json")
        stable-artifact (-> artifact
                            validate-artifact!
                            sort-maps)]
    (io/make-parents edn-path)
    (spit edn-path (ppedn/ppr-str stable-artifact))
    (spit json-path (json/write-str stable-artifact {:key-fn name}))
    {:exit-code (if (get-in stable-artifact [:summary :valid?]) 0 1)
     :artifact stable-artifact
     :output-files [edn-path json-path]}))
