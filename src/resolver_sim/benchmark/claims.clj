(ns resolver-sim.benchmark.claims
  "Claim evaluators for benchmark packs at Levels 1 and 2.

   Level 1 (mechanical): checks that required artifacts, hashes, evidence
   roots, or result fields exist and are internally consistent.  These are
   structural assertions about the evidence bundle — no domain reasoning.

   Level 2 (invariant-backed): checks that specific named post-hoc invariants
   passed for a scenario result.  This covers Sew protocol claims where a
   semantic property is proxied by invariant results from check-all.

   See docs/benchmarks/DESIGN_CLAIM_VERIFICATION.md for maturity level definitions."
  (:require [clojure.string :as str]
            [resolver-sim.claims.engine :as evidence-claims]
            [resolver-sim.definitions.passive-registries :as passive-registries]
            [resolver-sim.io.resource-path :as rp]
            [resolver-sim.yield.partial-fill :as partial-fill]))

;; ── Claim provenance bridge ───────────────────────────────────────────────────

(defn claim-provenance
  "Return passive-registry provenance for a benchmark claim when available.

   Benchmark claims without a matching passive definition remain valid benchmark
   catalogue entries, but are explicitly marked so reports do not imply a
   hash-bound evidence-node definition."
  [claim-id]
  (if-let [definition (evidence-claims/claim-definition claim-id)]
    {:claim/definition-source :passive-registry
     :claim/definition-hash (:canonical-hash definition)
     :claim/concept-hash (:concept-hash definition)
     :claim/registry-version (:registry-version passive-registries/claim-definition-registry)
     :claim/evidence-binding :benchmark-result}
    {:claim/definition-source :benchmark-catalogue-only
     :claim/registry-version (:registry-version passive-registries/claim-definition-registry)
     :claim/evidence-binding :benchmark-result}))

(defn- with-provenance
  [claim-result]
  (merge claim-result (claim-provenance (:claim/id claim-result))))

;; ── Claim ref normalization ───────────────────────────────────────────────────
;; Benchmark packs may declare claims as flat keywords or as maps with
;; :claim/id plus optional :claim/role, :claim/rationale, :claim/failure-meaning.
;; normalize-claim-refs converts mixed vectors to uniform map vectors.

(defn normalize-claim-refs
  "Normalize a vector of claim refs: keywords become {:claim/id <keyword>},
   maps are returned as-is. Throws on unexpected types."
  [claims]
  (when (seq claims)
    (mapv (fn [c]
            (cond
              (keyword? c) {:claim/id c}
              (map? c) c
              :else (throw (ex-info "Invalid claim ref" {:claim-ref c}))))
          claims)))

(defn claim-ref->id
  "Extract the claim keyword from a normalized or raw claim ref."
  [claim-ref]
  (or (:claim/id claim-ref)
      (when (keyword? claim-ref) claim-ref)))

;; ── Evaluator registry ────────────────────────────────────────────────────────
;; Each entry: {<claim-kw> {:scope <:scenario|:benchmark>
;;                          :check (fn [ctx]) -> {:holds? bool
;;                                                :violations [<map> ...]}}

(defn- sha-256-hex?
  [s]
  (boolean (and (string? s) (re-matches #"[0-9a-f]{64}" s))))

;; ── Helpers for Level 2 invariant-backed checks ────────────────────────────────

(defn- check-invariants
  "Check that all named invariants passed in the scenario's invariant results.
   Returns {:holds? bool :violations [map]}.
   Missing invariants are :not-exercised, never an implicit pass: an active
   benchmark must show that its required semantic check actually ran."
  [ctx invariant-ids]
  (let [inv-results (get-in ctx [:scenario/result :invariant-results])
        missing    (remove (fn [id]
                             (some #(= (:id %) id) inv-results))
                           invariant-ids)
        failures   (keep (fn [id]
                           (let [entry (some #(when (= (:id %) id) %) inv-results)]
                             (when (and entry (not= :pass (:result entry)))
                               {:type :invariant-failed
                                :invariant-id id
                                :message (str "invariant " id " failed for claim")})))
                         invariant-ids)]
    (cond
      (seq missing)
      {:outcome :not-exercised
       :violations (mapv (fn [id]
                           {:type :invariant-not-exercised
                            :invariant-id id
                            :message (str "invariant " id " was not produced for claim")})
                         missing)}

      :else
      {:holds? (empty? failures)
       :violations (vec failures)})))

(defn- partial-fill-decisions
  [results]
  (mapcat :partial-fill-decisions results))

(defn- check-partial-fill-closed-forms
  "Evaluate selected closed-form checks across every emitted partial-fill decision.
   Returns witness data indicating which settlement modes and fill modes were
   exercised.  A workload with no decision artifact has not exercised this property."
  [results check-ids]
  (let [decisions (vec (partial-fill-decisions results))]
    (if (empty? decisions)
      {:outcome :not-exercised
       :violations [{:type :missing-partial-fill-decision
                     :message "workload produced no partial-fill decision artifact"}]
       :witnesses []}
      (let [failures (->> decisions
                          (mapcat (fn [decision]
                                    (->> (partial-fill/partial-fill-closed-form-checks decision)
                                         (filter #(and (contains? check-ids (:check/id %))
                                                       (= :fail (:status %))))
                                         (map (fn [check]
                                                {:type :closed-form-failure
                                                 :decision-id (:decision/id decision)
                                                 :check-id (:check/id check)
                                                 :details (:details check)})))))
                          vec)
            witnesses (mapv (fn [i decision]
                              {:decision/index i
                               :settlement-mode (:settlement-mode decision)
                               :fill-mode (get-in decision [:policy :mode])
                               :exercised-fill? (= :partial-fill (:settlement-mode decision))})
                            (range)
                            decisions)]
        {:holds? (empty? failures)
         :violations failures
         :witnesses witnesses}))))

(def ^:private reversal-claim-coverage
  {:claim/reversal-reviewer-due-process
   ["DR-N-001-reversal-slash-appeal-lifecycle"
    "DR-N-002-reversal-slash-appeal-rejected"
    "DR-N-003-reversal-slash-appeal-window-expired"
    "DR-N-004-reversal-slash-appeal-wrong-party"]
   :claim/reversal-slash-conservation
   ["DR-N-001-reversal-slash-appeal-lifecycle"
    "DR-R-001-reversal-slash-insufficient-stake"]
   :claim/vindication-stability
   ["DR-N-001-reversal-slash-appeal-lifecycle"
    "DR-O-001-vindication-4-level"
    "DR-O-002-vindication-minimum-stake"
    "DR-O-003-vindication-zero-stake"]
   :claim/challenge-bounty-correctness
   ["DR-Q-001-challenge-bounty-reversal"
    "DR-Q-002-challenge-bounty-no-challenger"]
   :claim/governance-force-reversal-authorized
   ["DR-P-001-force-reversal-slash"
    "DR-P-002-force-reversal-slash-idempotent"]})

(def ^:private reversal-claim-invariants
  {:claim/reversal-reviewer-due-process
   [:slash-distribution-consistent :resolver/balances-conserved :conservation-of-funds]
   :claim/reversal-slash-conservation
   [:slash-distribution-consistent :conservation-of-funds]
   :claim/vindication-stability
   [:slash-distribution-consistent :resolver/balances-conserved :conservation-of-funds]
   :claim/challenge-bounty-correctness
   [:slash-distribution-consistent :resolver/balances-conserved :conservation-of-funds]
   :claim/governance-force-reversal-authorized
   [:slash-distribution-consistent :resolver/balances-conserved :conservation-of-funds]})

(defn- expectation-passed?
  [result]
  (true? (get-in result [:checks :expectations :ok?])))

(defn- reversal-claim-check
  "Verify that every scenario specifically registered for a reversal claim ran,
   passed its replay and declared expectations, and produced passing accounting
   invariants. Returns reviewer-readable assertions for each required scenario."
  [claim-id results]
  (let [required-scenarios (get reversal-claim-coverage claim-id)
        required-invariants (get reversal-claim-invariants claim-id)
        by-id (group-by (comp str/upper-case :scenario/id) results)
        assertions
        (mapv (fn [scenario-id]
                (let [result (first (get by-id (str/upper-case scenario-id)))
                      invariant-results (:invariant-results result)
                      missing-invariants (->> required-invariants
                                              (remove (fn [id]
                                                        (some #(= id (:id %)) invariant-results)))
                                              vec)
                      failed-invariants (->> required-invariants
                                             (keep (fn [id]
                                                     (let [entry (some #(when (= id (:id %)) %) invariant-results)]
                                                       (when (and entry (not= :pass (:result entry))) id))))
                                             vec)]
                  {:scenario/id scenario-id
                   :scenario/present? (boolean result)
                   :replay/outcome (:outcome result)
                   :expectations/passed? (when result (expectation-passed? result))
                   :invariants/missing missing-invariants
                   :invariants/failed failed-invariants
                   :holds? (and result
                                (= :pass (:outcome result))
                                (expectation-passed? result)
                                (empty? missing-invariants)
                                (empty? failed-invariants))}))
              required-scenarios)
        violations (->> assertions
                        (keep (fn [assertion]
                                (when-not (:holds? assertion)
                                  {:type :reversal-claim-scenario-failed
                                   :scenario/id (:scenario/id assertion)
                                   :message "Required reversal-claim scenario did not satisfy replay, expectation, and invariant checks"
                                   :details (dissoc assertion :holds?)})))
                        vec)]
    (if (empty? results)
      {:outcome :not-exercised
       :violations [{:type :missing-reversal-claim-results
                     :message "No scenario results were supplied for reversal claim evaluation"}]
       :assertions []}
      {:holds? (empty? violations)
       :violations violations
       :assertions assertions})))

(defn- scenario-group-key
  [result]
  (or (:scenario/id result)
      (:simulator/scenario-path result)
      (:file result)))

(defn- duplicate-scenario-groups
  [results]
  (->> results
       (group-by scenario-group-key)
       (remove (fn [[scenario-key grouped-results]]
                 (or (nil? scenario-key)
                     (< (count grouped-results) 2))))
       (into {})))

(defn- scenario-groups
  [results]
  (->> results
       (group-by scenario-group-key)
       (remove (comp nil? key))
       (into {})))

(defn- result-fingerprint
  [result]
  (select-keys result [:outcome :halt-reason :invariant-results]))

(defn- nondeterminism-fingerprint
  [result]
  (select-keys result [:outcome :halt-reason :invariant-results :scenario/evidence-root]))

(defn- consistent-fingerprints?
  [results fingerprint-fn]
  (<= (count (distinct (map fingerprint-fn results))) 1))

(defn- missing-scenario-identity-violations
  [results]
  (->> results
       (keep (fn [result]
               (when-not (scenario-group-key result)
                 {:type :missing-scenario-identity
                  :message "scenario result is missing :scenario/id, :simulator/scenario-path, and :file"})))
       vec))

(defn- positive-int?
  [x]
  (and (int? x) (pos? x)))

(defn- run-pairing-violations
  [grouped-results]
  (let [scenario-id (some :scenario/id grouped-results)
        scenario-path (some :simulator/scenario-path grouped-results)
        file (some :file grouped-results)
        run-counts (keep :benchmark/run-count grouped-results)
        run-indices (keep :benchmark/run-index grouped-results)
        distinct-run-counts (distinct run-counts)
        declared-run-count (first distinct-run-counts)]
    (cond
      (not-every? #(contains? % :benchmark/run-count) grouped-results)
      [{:type :missing-run-count
        :scenario-id scenario-id
        :scenario-path scenario-path
        :file file
        :message "scenario result is missing :benchmark/run-count"}]

      (not-every? #(contains? % :benchmark/run-index) grouped-results)
      [{:type :missing-run-index
        :scenario-id scenario-id
        :scenario-path scenario-path
        :file file
        :message "scenario result is missing :benchmark/run-index"}]

      (not= 1 (count distinct-run-counts))
      [{:type :inconsistent-run-count
        :scenario-id scenario-id
        :scenario-path scenario-path
        :file file
        :message "scenario results disagree on :benchmark/run-count"}]

      (not (positive-int? declared-run-count))
      [{:type :invalid-run-count
        :scenario-id scenario-id
        :scenario-path scenario-path
        :file file
        :message "scenario result has invalid :benchmark/run-count"}]

      (not-every? positive-int? run-indices)
      [{:type :invalid-run-index
        :scenario-id scenario-id
        :scenario-path scenario-path
        :file file
        :message "scenario result has invalid :benchmark/run-index"}]

      (not= declared-run-count (count grouped-results))
      [{:type :insufficient-replay-runs
        :scenario-id scenario-id
        :scenario-path scenario-path
        :file file
        :message (str "scenario expected " declared-run-count " replay runs, got " (count grouped-results))}]

      (not= declared-run-count (count (distinct run-indices)))
      [{:type :duplicate-run-index
        :scenario-id scenario-id
        :scenario-path scenario-path
        :file file
        :message "scenario replay results contain duplicate :benchmark/run-index values"}]

      (not= (set run-indices) (set (range 1 (inc declared-run-count))))
      [{:type :incomplete-run-pairing
        :scenario-id scenario-id
        :scenario-path scenario-path
        :file file
        :message "scenario replay results do not cover the full declared run index range"}]

      :else
      [])))

(defn- benchmark-consistency-check
  [results {:keys [required-fields fingerprint-fn violation-type mismatch-message require-run-pairing?]}]
  (let [missing-identity (missing-scenario-identity-violations results)
        grouped-results (scenario-groups results)
        missing-fields (->> results
                            (mapcat (fn [result]
                                      (keep (fn [field]
                                              (when (nil? (get result field))
                                                {:type :missing-required-field
                                                 :field field
                                                 :scenario-id (:scenario/id result)
                                                 :scenario-path (:simulator/scenario-path result)
                                                 :file (:file result)
                                                 :message (str "scenario result missing required field " field)}))
                                            required-fields)))
                            vec)
        run-pairing (if require-run-pairing?
                      (->> grouped-results
                           vals
                           (mapcat run-pairing-violations)
                           vec)
                      [])
        mismatches (->> (duplicate-scenario-groups results)
                        (keep (fn [[scenario-key grouped-results]]
                                (when-not (consistent-fingerprints? grouped-results fingerprint-fn)
                                  {:type violation-type
                                   :scenario-id (some :scenario/id grouped-results)
                                   :scenario-path (some :simulator/scenario-path grouped-results)
                                   :file (some :file grouped-results)
                                   :message (mismatch-message scenario-key)})))
                        vec)
        violations (vec (concat missing-identity missing-fields run-pairing mismatches))]
    {:holds? (empty? violations)
     :violations violations}))

(defn- check-exercised-invariants
  "Run invariant-backed checks only after the scenario emitted the evidence
   required by the bounded claim. An absent mechanism is :not-exercised, never
   a pass for that claim."
  [ctx exercised? missing-type invariant-ids]
  (if-not exercised?
    {:outcome :not-exercised
     :violations [{:type missing-type
                   :message "scenario did not exercise the claimed mechanism"}]}
    (check-invariants ctx invariant-ids)))

(defn- force-authorisation-exercised?
  [ctx]
  (seq (get-in ctx [:scenario/world :force-authorisations])))

(defn- held-custody-exercised?
  [ctx]
  (seq (get-in ctx [:scenario/world :held-adjustments])))

(defn- forensic-linkage-exercised?
  [ctx]
  (and (force-authorisation-exercised? ctx)
       (seq (get-in ctx [:scenario/world :force-authorisations/consumed]))))

(def evaluator-registry
  {:evidence-root-present
   {:scope :scenario
    :check
    (fn [ctx]
      (let [root (get-in ctx [:scenario/result :scenario/evidence-root])]
        {:holds? (boolean root)
         :violations (when-not root
                       [{:type :missing-evidence-root
                         :message "scenario/evidence-root is nil or missing"}])}))}

   :replay-result-present
   {:scope :scenario
    :check
    (fn [ctx]
      (let [outcome (get-in ctx [:scenario/result :outcome])]
        {:holds? (boolean outcome)
         :violations (when-not outcome
                       [{:type :missing-outcome
                         :message "scenario outcome is nil or missing"}])}))}

   :scenario-hash-present
   {:scope :scenario
    :check
    (fn [ctx]
      (let [root (get-in ctx [:scenario/result :scenario/evidence-root])]
        {:holds? (sha-256-hex? root)
         :violations (cond
                       (nil? root) [{:type :missing-evidence-root
                                     :message "scenario/evidence-root is nil"}]
                       (not (string? root)) [{:type :invalid-evidence-root-type
                                              :message (str "expected string, got " (type root))}]
                       (not (re-matches #"[0-9a-f]{64}" root)) [{:type :invalid-evidence-root-format
                                                                 :message (str "expected 64-char hex, got " (count root) " chars")}]
                       :else [])}))}

   :no-invariant-errors
   {:scope :scenario
    :check
    (fn [ctx]
      (let [inv-results (get-in ctx [:scenario/result :invariant-results])
            failures (filter #(= :fail (:result %)) inv-results)]
        {:holds? (empty? failures)
         :violations (mapv (fn [f]
                             {:type :invariant-failure
                              :invariant-id (:id f)
                              :message (str "invariant " (:id f) " failed")})
                           failures)}))}

   :all-scenarios-pass
   {:scope :benchmark
    :check
    (fn [ctx]
      (let [results (:benchmark/results ctx)
            failures (remove #(= :pass (:outcome %)) results)]
        {:holds? (empty? failures)
         :violations (mapv (fn [r]
                             {:type :scenario-not-passed
                              :scenario-id (:scenario/id r)
                              :outcome (:outcome r)
                              :message (str "scenario " (:scenario/id r) " outcome is " (:outcome r))})
                           failures)}))}

   :claim/replay-identical-results
   {:scope :benchmark
    :check (fn [ctx]
             (benchmark-consistency-check
              (:benchmark/results ctx)
              {:required-fields [:outcome]
               :require-run-pairing? true
               :fingerprint-fn result-fingerprint
               :violation-type :replay-results-mismatch
               :mismatch-message (fn [scenario-key]
                                   (str "scenario " scenario-key " produced non-identical replay results"))}))}

   :claim/hash-consistency-across-runs
   {:scope :benchmark
    :check (fn [ctx]
             (benchmark-consistency-check
              (:benchmark/results ctx)
              {:required-fields [:scenario/evidence-root]
               :require-run-pairing? true
               :fingerprint-fn :scenario/evidence-root
               :violation-type :evidence-root-mismatch
               :mismatch-message (fn [scenario-key]
                                   (str "scenario " scenario-key " produced non-identical evidence roots"))}))}

   :claim/no-nondeterminism
   {:scope :benchmark
    :check (fn [ctx]
             (benchmark-consistency-check
              (:benchmark/results ctx)
              {:required-fields [:outcome :scenario/evidence-root]
               :require-run-pairing? true
               :fingerprint-fn nondeterminism-fingerprint
               :violation-type :nondeterministic-replay
               :mismatch-message (fn [scenario-key]
                                   (str "scenario " scenario-key " exhibited nondeterministic replay artifacts"))}))}

   ;; ── Level 2: Sew protocol claims (invariant-backed) ─────────────────────────
   ;; Each maps a Sew semantic claim to one or more post-hoc invariants
   ;; that serve as proxies for the claimed property.

   ;; Bounded force-authorisation / custody claims. These require the mechanism
   ;; to have been exercised; a normal escrow scenario cannot pass them vacuously.
   :force-authorisation-exact-scope-single-use
   {:scope :scenario
    :check (fn [ctx]
             (check-exercised-invariants
              ctx (force-authorisation-exercised? ctx)
              :force-authorisation-not-exercised
              [:force-authorisations-lifecycle-consistent
               :held-adjustments-reconstruct-total-held
               :held-custody-closed-form]))}

   :held-custody-position-isolation
   {:scope :scenario
    :check (fn [ctx]
             (check-exercised-invariants
              ctx (held-custody-exercised? ctx)
              :held-custody-not-exercised
              [:held-partitions-non-negative
               :held-adjustments-reconstruct-total-held
               :terminal-workflow-custody-closed]))}

   :forensic-authorisation-custody-linkage
   {:scope :scenario
    :check (fn [ctx]
             (check-exercised-invariants
              ctx (forensic-linkage-exercised? ctx)
              :forensic-authorisation-linkage-not-exercised
              [:force-authorisations-lifecycle-consistent
               :held-artifacts-derived-from-adjustments
               :held-custody-closed-form]))}

   ;; escrow-dispute-v1 pack
   :claim/no-unauthorized-release
   {:scope :scenario
    :check (fn [ctx]
             (check-invariants ctx [:conservation-of-funds :released-monotonic]))}

   :claim/funds-conserved
   {:scope :scenario
    :check (fn [ctx]
             (check-invariants ctx [:conservation-of-funds]))}

   :claim/dispute-liveness
   {:scope :scenario
    :check (fn [ctx]
             (check-invariants ctx [:dispute-resolution-path :dispute-level-bounded]))}

   :claim/slashing-conservation
   {:scope :scenario
    :check (fn [ctx]
             (check-invariants ctx [:slash-distribution-consistent :conservation-of-funds]))}

   :claim/governance-non-interference
   {:scope :scenario
    :check (fn [ctx]
             (check-invariants ctx [:escrow-state-transition-valid :cancellation-mutex]))}

   ;; dispute-liveness-v1 pack (additional claims)
   :claim/bounded-resolution-time
   {:scope :scenario
    :check (fn [ctx]
             (check-invariants ctx [:time-non-decreasing :temporal-consistency]))}

   ;; yield-shortfall-v1 pack
   :claim/yield-preserved-during-shortfall
   {:scope :scenario
    :check (fn [ctx]
             (check-invariants ctx [:yield-exposure :shortfall-fidelity]))}

   :claim/partial-fill-decision-integrity
   {:scope :benchmark
    :check (fn [ctx]
             (check-partial-fill-closed-forms
              (:benchmark/results ctx)
              #{:partial-fill/conservation
                :partial-fill/capacity-bound
                :partial-fill/per-claim-bound
                :partial-fill/per-claim-conservation
                :partial-fill/rounding-residual-bounded
                :partial-fill/claim-key-consistency
                :partial-fill/non-negative-amounts
                :partial-fill/settlement-mode-consistency
                :partial-fill/settlement-mode-valid
                :partial-fill/mode-valid
                :partial-fill/deferred-haircut-overlap
                :partial-fill/deferred-haircut-sum-bound
                :partial-fill/evidence-self-consistency
                :partial-fill/unrealized-bucket-valid
                :partial-fill/decision-artifact-format
                :partial-fill/pro-rata-cross-product
                :partial-fill/principal-first-priority
                :partial-fill/waterfall-priority}))}

   :claim/cap-adherence
   {:scope :benchmark
    :check (fn [ctx]
             (check-partial-fill-closed-forms
              (:benchmark/results ctx)
              #{:partial-fill/capacity-bound
                :partial-fill/per-claim-bound}))}

   :claim/no-leakage-beyond-shortfall
   {:scope :scenario
    :check (fn [ctx]
             (check-invariants ctx [:shortfall-fidelity :conservation-of-funds]))}

   ;; resolver-slashing-v1 pack (additional claims)
   :claim/waterfall-coverage-correct
   {:scope :scenario
    :check (fn [ctx]
             (check-invariants ctx [:senior-coverage-not-exceeded]))}

   :claim/no-over-slashing
   {:scope :scenario
    :check (fn [ctx]
             (check-invariants ctx [:slash-distribution-consistent :bond-slash-bounded]))}

   :claim/appeal-bond-adequacy
   {:scope :scenario
    :check (fn [ctx]
             (check-invariants ctx [:appeal-bond-conserved :challenge-bond-proportional]))}

   ;; escrow-dispute-v1 pack (solvency)
   :claim/solvency-status
   {:scope :scenario
    :check (fn [ctx]
             (check-invariants ctx [:solvency :conservation-of-funds]))}

   ;; reversal-slashing-v1 pack: benchmark-scoped so each claim verifies its
   ;; own registered scenario coverage rather than treating unrelated scenarios
   ;; as evidence for every reversal property.
   :claim/reversal-reviewer-due-process
   {:scope :benchmark
    :check (fn [ctx]
             (reversal-claim-check :claim/reversal-reviewer-due-process (:benchmark/results ctx)))}

   :claim/reversal-slash-conservation
   {:scope :benchmark
    :check (fn [ctx]
             (reversal-claim-check :claim/reversal-slash-conservation (:benchmark/results ctx)))}

   :claim/vindication-stability
   {:scope :benchmark
    :check (fn [ctx]
             (reversal-claim-check :claim/vindication-stability (:benchmark/results ctx)))}

   :claim/challenge-bounty-correctness
   {:scope :benchmark
    :check (fn [ctx]
             (reversal-claim-check :claim/challenge-bounty-correctness (:benchmark/results ctx)))}

   :claim/governance-force-reversal-authorized
   {:scope :benchmark
    :check (fn [ctx]
             (reversal-claim-check :claim/governance-force-reversal-authorized (:benchmark/results ctx)))}})

(def ^:private scoring-rule-paths
  {:scoring/robustness-dimensions-v0 "resource:benchmarks/scoring/robustness-dimensions-v0.edn"
   :scoring/binary-claims-v1 "resource:benchmarks/scoring/binary-claims-v1.edn"
   :scoring/severity-weighted-robustness-v1 "resource:benchmarks/scoring/severity-weighted-robustness-v1.edn"
   :scoring/severity-weighted-v1 "resource:benchmarks/scoring/severity-weighted-robustness-v1.edn"
   :scoring/shortfall-allocation-v0 "resource:benchmarks/scoring/shortfall-allocation-v0.edn"})

(defn- load-scoring
  [scoring-id]
  (when-let [path (get scoring-rule-paths scoring-id)]
    (try (rp/edn-read path)
         (catch Exception _ nil))))

(defn- severity-claims
  [scoring]
  (->> (:severity/claims scoring)
       (apply merge {})
       (map (fn [[claim-id severity-entry]]
              [claim-id (:severity severity-entry)]))
       (into {})))

(defn- manifest-severity-index
  [manifest]
  (let [scoring-severities (severity-claims (load-scoring (:benchmark/scoring-rule manifest)))
        declared-severities (into {}
                                  (keep (fn [claim-ref]
                                          (let [normalized (if (keyword? claim-ref)
                                                             {:claim/id claim-ref}
                                                             claim-ref)]
                                            (when-let [severity (:claim/severity normalized)]
                                              [(:claim/id normalized) severity]))))
                                  (:benchmark/claims manifest))]
    (merge scoring-severities declared-severities)))

(defn- claim-severity
  [severity-index claim-ref]
  (or (:claim/severity claim-ref)
      (get severity-index (:claim/id claim-ref))
      :low))

(defn evaluator-resolver
  "Look up a claim evaluator by claim keyword.
   Returns {:scope <kw> :check <fn>} or nil."
  [claim-id]
  (get evaluator-registry claim-id))

;; ── Evaluation ────────────────────────────────────────────────────────────────

(defn evaluate-claim
  "Evaluate a single claim against the given context.
   context depends on scope — for :scenario claims it includes
   :scenario/result, for :benchmark claims it includes :benchmark/results.
   Returns {:claim/id <kw> :claim/outcome <kw> :claim/evidence [<coll>] :claim/severity <kw>}."
  ([claim-id context]
   (evaluate-claim claim-id context :low))
  ([claim-id context severity]
   (let [scenario-result (:scenario/result context)
         scenario-fields (select-keys scenario-result
                                      [:scenario/id :simulator/scenario-path :file])]
     (with-provenance
       (if-let [{:keys [scope check]} (evaluator-resolver claim-id)]
         (let [{:keys [holds? violations outcome assertions]} (check context)]
           (merge {:claim/id claim-id
                   :claim/outcome (or outcome (if holds? :pass :fail))
                   :claim/severity severity
                   :claim/evidence (mapv :type violations)}
                  (when (some? assertions)
                    {:claim/assertions assertions})
                  (when (= scope :scenario)
                    {:claim/scope :scenario
                     :scenario/id (:scenario/id scenario-fields)
                     :scenario/file (:file scenario-fields)
                     :simulator/scenario-path (:simulator/scenario-path scenario-fields)})))
         (merge {:claim/id claim-id
                 :claim/outcome :not-implemented
                 :claim/severity severity
                 :claim/evidence []
                 :claim/error (str "No evaluator registered for " claim-id)}
                (when scenario-result
                  {:claim/scope :scenario
                   :scenario/id (:scenario/id scenario-fields)
                   :scenario/file (:file scenario-fields)
                   :simulator/scenario-path (:simulator/scenario-path scenario-fields)})))))))

(defn evaluate-manifest-claims
  "Evaluate all claims declared in a benchmark manifest against scenario results.
   Dispatches per-claim by scope: :scenario claims are evaluated once per result,
   :benchmark claims are evaluated once against the full result set.
   Returns a flat vector of claim result maps."
  [manifest results]
  (let [claim-refs (normalize-claim-refs (:benchmark/claims manifest))
        severity-index (manifest-severity-index manifest)]
    (when (seq claim-refs)
      (vec
       (mapcat (fn [claim-ref]
                 (let [claim-id (:claim/id claim-ref)
                       severity (claim-severity severity-index claim-ref)]
                   (if-let [{:keys [scope]} (evaluator-resolver claim-id)]
                     (case scope
                       :scenario
                       (mapv (fn [result]
                               (evaluate-claim claim-id
                                               {:scenario/result result
                                                :scenario/world (:world result)
                                                :scenario/metrics (:metrics result)}
                                               severity))
                             results)

                       :benchmark
                       [(evaluate-claim claim-id
                                        {:benchmark/results results
                                         :benchmark/manifest manifest}
                                        severity)]

                       [(evaluate-claim claim-id {:error (str "Unknown scope: " scope)} severity)])
                     [(evaluate-claim claim-id {:error (str "Unknown claim: " claim-id)} severity)])))
               claim-refs)))))
