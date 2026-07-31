(ns resolver-sim.scenario.equilibrium
  "Trace-end mechanism-property and equilibrium-concept validation.

   Activates the :mechanism-properties and :equilibrium-concept fields in CDRS
   v1.1 theory blocks. These fields were previously RESERVED; this namespace
   makes them ACTIVE as terminal trace proxy validations.

   ## What this is

   A lightweight falsification layer that checks whether realised terminal
   outcomes are consistent with claimed economic properties. The output always
   says 'trace-consistent with claimed property', never 'equilibrium proven'.

   A Nash equilibrium requires comparing deviations across many traces.
   A single replay can only check that no observed attack succeeded and no
   invariant was violated. All results carry a :basis field that declares
   the strength of the check (see Claim-Strength Taxonomy below).

   ## Claim-Strength Taxonomy (:basis values)

     :single-trace-terminal-proxy   — terminal world state only; no deviation comparison
     :single-trace-metric-proxy     — accumulated metrics from one trace
     :absent-evidence               — required evidence fields not present → inconclusive
     :not-applicable                — property logically cannot apply in this scenario context
     :multi-trace-required          — only meaningful across N traces; single-trace inconclusive
     :multi-epoch-required          — only meaningful across epochs; single-trace inconclusive

   ## Result statuses

     :pass           — trace is consistent with the claimed property
     :fail           — trace violates the property (hard failure when :severity :hard)
     :inconclusive   — required evidence absent or property requires more than one trace
     :not-applicable — property cannot be evaluated in this scenario context

   ## Severity

     :hard — a :fail blocks the suite (same as expectations failure)
     :soft — a :fail is a warning; :inconclusive is always soft

   ## Protocol extension

     Protocol-specific validators are injected via the DisputeProtocol interface:
       (protocol/mechanism-property-validators protocol)  — returns {kw → fn}
       (protocol/equilibrium-concept-validators protocol) — returns {kw → fn}

     These are merged with the built-in generic validators at evaluation time.
     evaluate-mechanism-properties and evaluate-equilibrium-concepts also accept
     an explicit extra-validators map for direct use outside the protocol dispatch.

   This namespace is pure — no I/O, no DB, no side effects."
  (:require [resolver-sim.protocols.protocol :as protocol]
            [resolver-sim.scenario.equilibrium-result :as eq-result]))

(def ^:private evidence-schema-version
  "Semantic version for equilibrium/mechanism evidence payload shape."
  "1.0")

(def ^:private deviation-bundle-gated-concepts
  #{:dominant-strategy-equilibrium :nash-equilibrium})

(defn- build-provenance
  "Extract temporal + local-attestation provenance from run result for
   downstream game-theoretic evidence consumers.

   This is metadata-only in Slice 1: it does not change pass/fail semantics.
   Future strict modes may gate on these fields."
  [result]
  (let [temporal-confidence (:temporal-confidence result)
        temporal-query-mode (:temporal-query-mode result)
        attestation         (:attestation result)
        attestation-status  (or (:status attestation)
                                (:attestation-status result)
                                :unknown)]
    {:temporal {:query-mode          (or temporal-query-mode :unknown)
                :confidence          temporal-confidence
                :explicit-valid-time? (boolean (= :valid-time (get-in temporal-confidence [:time-basis])))}
     :attestation {:status attestation-status
                   :source :local-self-signed
                   :details (or attestation {})}}))

(defn- requires-deviation-bundle?
  [claim-tier concept]
  (and (contains? #{:deviation-tested :population-tested} claim-tier)
       (contains? deviation-bundle-gated-concepts concept)))

(defn- strict-valid-time-required?
  [trust-mode]
  (= :strict-valid-time trust-mode))

(defn- strict-attestation-required?
  [trust-mode]
  (= :strict-attestation trust-mode))

;; ---------------------------------------------------------------------------
;; Result constructors (delegate to equilibrium-result)
;; ---------------------------------------------------------------------------

(def ^:private property-class
  "Maps equilibrium property keywords to their validation class."
  {:incentive-compatibility       :validation.class/payoff-property
   :sybil-resistance              :validation.class/payoff-property
   :dominant-strategy-equilibrium :validation.class/payoff-property
   :empirical-strategy-dominance :validation.class/payoff-property
   :nash-equilibrium              :validation.class/equilibrium
   :bounded-nash-diagnostic       :validation.class/equilibrium
   :bayesian-nash-equilibrium     :validation.class/equilibrium
   :pro-rata-fairness             :validation.class/algebraic-integrity
   :redistribution-fairness       :validation.class/algebraic-integrity
   :individual-rationality        :validation.class/payoff-property
   :collusion-resistance           :validation.class/deviation-resistance
   :stake-flow-conservation        :validation.class/algebraic-integrity
   :budget-balance                 :validation.class/payoff-property
   :force-refund-path-integrity    :validation.class/algebraic-integrity
   :pending-lifecycle-integrity    :validation.class/algebraic-integrity
   :subgame-perfect-equilibrium    :validation.class/equilibrium
   :bounded-public-state-epsilon-spe :validation.class/equilibrium
   :bounded-backward-induction-spe  :validation.class/equilibrium
   :resolver-reputation-spe         :validation.class/equilibrium
   :resolver-reputation-profile-matrix :validation.class/equilibrium
   :cancellation-dominance          :validation.class/deviation-resistance})

(defn- pass [property basis observed expected]
  (eq-result/pass-result property basis observed expected
                         :validation-class (get property-class property)))

(defn- fail [property basis observed expected offending]
  (eq-result/fail-result property basis observed expected offending
                         :validation-class (get property-class property)))

(defn- inconclusive [property basis reason-kw detail & {:keys [required available]}]
  (eq-result/inconclusive-result property basis reason-kw
                                 :detail detail :required required :available available
                                 :validation-class (get property-class property)))

(defn- not-applicable [property reason-kw detail & opts]
  (eq-result/not-applicable-result property reason-kw
                                   (assoc opts :detail detail)))

;; ---------------------------------------------------------------------------
;; Mechanism-property validators (generic — Sew-specific validators are in
;; protocols/sew/equilibrium.clj and injected via mechanism-property-validators)
;; ---------------------------------------------------------------------------

(defn- check-incentive-compatibility
  "No actor obtained higher realised payoff through labelled adversarial action
   than through honest baseline, when adversarial actors are present.

   :inconclusive when no adversarial actors appear in the trace.
   :pass when attack-successes = 0 and funds-lost = 0.
   :fail when any adversarial event succeeded or funds were lost."
  [{:keys [metrics]}]
  (let [attempts  (:attack-attempts metrics 0)
        successes (:attack-successes metrics 0)
        lost      (:funds-lost metrics 0)]
    (cond
      (zero? attempts)
      (inconclusive :incentive-compatibility :single-trace-metric-proxy :untested-no-adversary
                    "no adversarial actors in trace; property vacuously consistent but untested"
                    :required [:attack-attempts :attack-successes]
                    :available [:metrics])

      (or (pos? successes) (pos? lost))
      (fail :incentive-compatibility :single-trace-metric-proxy
            {:attack-successes successes :funds-lost lost}
            {:attack-successes 0 :funds-lost 0}
            (cond-> []
              (pos? successes) (conj {:metric :attack-successes :observed successes})
              (pos? lost)      (conj {:metric :funds-lost :observed lost})))

      :else
      (pass :incentive-compatibility :single-trace-metric-proxy
            {:attack-successes successes :funds-lost lost}
            "no adversarial action succeeded; no funds lost"))))

;; ---------------------------------------------------------------------------
;; Equilibrium-concept validators (generic — Sew-specific SPE validators are
;; in protocols/sew/equilibrium.clj and injected via equilibrium-concept-validators)
;; ---------------------------------------------------------------------------

(defn- check-sybil-resistance
  "Proxy: attack-successes = 0 (blunt; does not distinguish identity attacks).
   :inconclusive when no attacks present."
  [{:keys [metrics]}]
  (let [attempts  (:attack-attempts metrics 0)
        successes (:attack-successes metrics 0)]
    (cond
      (zero? attempts)
      (inconclusive :sybil-resistance :single-trace-metric-proxy :untested-no-adversary
                    "no adversarial actors; sybil resistance untested in this trace"
                    :required [:attack-attempts])

      (pos? successes)
      (fail :sybil-resistance :single-trace-metric-proxy
            {:attack-successes successes}
            {:attack-successes 0}
            [{:metric :attack-successes :observed successes}])

      :else
      (pass :sybil-resistance :single-trace-metric-proxy
            {:attack-successes successes}
            "no unauthorized identity attack succeeded"))))

;; ---------------------------------------------------------------------------
;; Equilibrium-concept validators (generic — Sew-specific SPE validators are
;; in protocols/sew/equilibrium.clj and injected via equilibrium-concept-validators)
;; ---------------------------------------------------------------------------

(defn- check-dominant-strategy-equilibrium
  "Honest behavior was a dominant strategy: the observed outcome is consistent
   with honest play being optimal regardless of others' strategies.

   Single-trace proxy: invariant-violations = 0 AND attack-successes = 0.
   This does NOT verify dominance across all opponent strategies — it only
   checks that no deviation from honest behavior was profitable in this trace.

   :inconclusive when no adversarial actors are present (untested).

   Takes an optional `property` keyword (default :dominant-strategy-equilibrium)
   so alias concepts (e.g. :empirical-strategy-dominance) can reuse this
   predicate while still being labelled with the concept that was requested."
  ([projection] (check-dominant-strategy-equilibrium projection :dominant-strategy-equilibrium))
  ([projection property]
   (let [{:keys [metrics]} projection
         violations (:invariant-violations metrics 0)
         successes  (:attack-successes metrics 0)
         attempts   (:attack-attempts metrics 0)]
     (cond
       (and (zero? attempts) (zero? violations))
       (inconclusive property :single-trace-metric-proxy :untested-no-adversary
                     "no adversarial actors in trace; dominance is consistent but untested"
                     :required [:attack-attempts])

       (or (pos? violations) (pos? successes))
       (fail property :single-trace-metric-proxy
             {:invariant-violations violations :attack-successes successes}
             {:invariant-violations 0 :attack-successes 0}
             (cond-> []
               (pos? violations) (conj {:metric :invariant-violations :observed violations})
               (pos? successes)  (conj {:metric :attack-successes :observed successes})))

       :else
       (pass property :single-trace-metric-proxy
             {:invariant-violations violations :attack-successes successes}
             "no deviation from honest behavior was profitable in this trace (single-trace proxy)")))))

(defn- check-nash-equilibrium
  "No profitable unilateral deviation was observed. Trace is consistent with
   Nash equilibrium.

   Single-trace proxy: attack-successes = 0 AND invariant-violations = 0.
   This does NOT verify that no profitable deviation exists — only that no
   deviation succeeded in this trace.

   :inconclusive when no adversarial actors present.

   Takes an optional `property` keyword (default :nash-equilibrium) so alias
   concepts (e.g. :bounded-nash-diagnostic) can reuse this predicate while still
   being labelled with the concept that was requested."
  ([projection] (check-nash-equilibrium projection :nash-equilibrium))
  ([projection property]
   (let [{:keys [metrics]} projection
         violations (:invariant-violations metrics 0)
         successes  (:attack-successes metrics 0)
         attempts   (:attack-attempts metrics 0)]
     (cond
       (and (zero? attempts) (zero? violations))
       (inconclusive property :single-trace-metric-proxy :untested-no-adversary
                     "no adversarial actors; Nash consistency untested in this trace"
                     :required [:attack-attempts])

       (or (pos? violations) (pos? successes))
       (fail property :single-trace-metric-proxy
             {:invariant-violations violations :attack-successes successes}
             {:invariant-violations 0 :attack-successes 0}
             (cond-> []
               (pos? successes)  (conj {:metric :attack-successes :observed successes})
               (pos? violations) (conj {:metric :invariant-violations :observed violations})))

       :else
       (pass property :single-trace-metric-proxy
             {:invariant-violations violations :attack-successes successes}
             "no unilateral deviation succeeded in this trace (single-trace proxy)")))))

(defn- check-bayesian-nash-equilibrium
  "Requires population/belief distributions across resolvers. Always
   :inconclusive for single-trace replay."
  [_projection]
  (inconclusive :bayesian-nash-equilibrium :multi-epoch-required :multi-epoch-required
                "requires population data across resolvers; single-trace cannot evaluate"
                :required [:population-metrics :belief-distributions]))

;; ---------------------------------------------------------------------------
;; Mechanism-property validator: pro-rata fairness
;; ---------------------------------------------------------------------------

(defn- check-pro-rata-fairness
  "Trace-level proxy for pro-rata allocation fairness.

   Examines shortfall-processing metrics:
     :total-shortfall-basis     — sum of all shortfall basis-amounts
     :total-shortfall-filled    — sum of all fulfilled-amounts
     :total-shortfall-deferred  — sum of all deferred-amounts
     :total-shortfall-haircut   — sum of all haircut-amounts

   When shortfall events occurred, conservation must hold:
     basis = filled + deferred + haircut
   Violation indicates value leaked or was created during shortfall processing.

   This is a single-trace proxy. Per-claimant proportional fairness is
   verified independently by the closed-form partial-fill checks
   (:partial-fill/pro-rata-cross-product in resolver-sim.yield.partial-fill).

   :inconclusive — no shortfall events recorded (untested).
   :pass         — conservation holds; recovery = basis.
   :fail         — conservation violated; imbalance detected."
  [{:keys [metrics]}]
  (let [basis (long (or (:total-shortfall-basis metrics) 0))
        filled (long (or (:total-shortfall-filled metrics) 0))
        deferred (long (or (:total-shortfall-deferred metrics) 0))
        haircut (long (or (:total-shortfall-haircut metrics) 0))
        recovery (+ filled deferred haircut)]
    (cond
      (zero? basis)
      (inconclusive :pro-rata-fairness :single-trace-metric-proxy :untested-no-shortfall
                    "no shortfall events in trace; pro-rata fairness untested"
                    :required [:total-shortfall-basis]
                    :available [:metrics])

      (not= basis recovery)
      (fail :pro-rata-fairness :single-trace-metric-proxy
            {:total-shortfall-basis basis
             :total-shortfall-filled filled
             :total-shortfall-deferred deferred
             :total-shortfall-haircut haircut
             :recovery-sum recovery
             :imbalance (- basis recovery)}
            {:total-shortfall-basis basis
             :recovery-sum basis}
            [{:metric :shortfall-imbalance
              :observed (- basis recovery)
              :expected 0}])

      :else
      (pass :pro-rata-fairness :single-trace-metric-proxy
            {:total-shortfall-basis basis
             :total-shortfall-filled filled
             :total-shortfall-deferred deferred
             :total-shortfall-haircut haircut}
            "shortfall conservation holds; recovery = basis"))))

;; ---------------------------------------------------------------------------
;; Mechanism-property validator: redistribution fairness
;; ---------------------------------------------------------------------------

(defn- check-redistribution-fairness
  "Trace-level proxy for redistribution fairness.

   When a pro-rata allocation requires redistribution (items hit caps),
   the redistribution must complete without hitting the iteration limit
   and must not produce negative per-item allocations.

   Consumes metrics:
     :redistribution-total-passes          — number of redistribution passes (0 = none needed)
     :redistribution-iteration-limit-hit?  — true if max-redistribution-passes was reached
     :redistribution-negative-allocations  — count of items with negative allocated amounts

   :inconclusive — no redistribution occurred (single-pass allocation sufficed).
   :pass         — redistribution completed successfully.
   :fail         — iteration limit was reached, indicating cascading caps prevented
                   full distribution of available liquidity."
  [{:keys [metrics]}]
  (let [total-passes (long (or (:redistribution-total-passes metrics) 0))
        limit-hit? (:redistribution-iteration-limit-hit? metrics)
        negative-allocs (long (or (:redistribution-negative-allocations metrics) 0))]
    (cond
      (zero? total-passes)
      (inconclusive :redistribution-fairness :single-trace-metric-proxy :untested-no-redistribution
                    "no redistribution passes recorded; allocation was single-pass"
                    :required [:redistribution-total-passes]
                    :available [:metrics])

      (or (true? limit-hit?) (pos? negative-allocs))
      (fail :redistribution-fairness :single-trace-metric-proxy
            {:redistribution-total-passes total-passes
             :iteration-limit-hit? limit-hit?
             :negative-allocations negative-allocs}
            {:iteration-limit-hit? false
             :negative-allocations 0}
            (cond-> []
              (true? limit-hit?)
              (conj {:metric :redistribution-iteration-limit-hit? :observed true :expected false})
              (pos? negative-allocs)
              (conj {:metric :redistribution-negative-allocations :observed negative-allocs :expected 0})))

      :else
      (pass :redistribution-fairness :single-trace-metric-proxy
            {:redistribution-total-passes total-passes}
            "redistribution completed without iteration limit or negative allocations"))))

;; ---------------------------------------------------------------------------
;; Dispatcher maps (generic only)
;; Protocol-specific validators are merged in at evaluation time via
;; protocol/mechanism-property-validators and protocol/equilibrium-concept-validators.
;; ---------------------------------------------------------------------------

(def ^:private mechanism-validators
  {:incentive-compatibility     check-incentive-compatibility
   :sybil-resistance            check-sybil-resistance
   :pro-rata-fairness           check-pro-rata-fairness
   :redistribution-fairness     check-redistribution-fairness})

(def ^:private equilibrium-validators
  {:dominant-strategy-equilibrium check-dominant-strategy-equilibrium
   :empirical-strategy-dominance  (fn [p] (check-dominant-strategy-equilibrium p :empirical-strategy-dominance))
   :nash-equilibrium              check-nash-equilibrium
   :bounded-nash-diagnostic       (fn [p] (check-nash-equilibrium p :bounded-nash-diagnostic))
   :bayesian-nash-equilibrium     check-bayesian-nash-equilibrium})

;; ---------------------------------------------------------------------------
;; Status roll-up
;; ---------------------------------------------------------------------------

(defn- roll-up-status
  "Compute aggregate status from a collection of validator results.
   :fail  — any hard :fail present
   :inconclusive — no :fail but some :inconclusive or :not-applicable
   :pass  — all results are :pass
   :not-checked — empty results"
  [results]
  (cond
    (empty? results)               :not-checked
    (some #(and (= :fail (:status %)) (= :hard (:severity %))) results) :fail
    ;; Defensive: if a soft :fail exists (future extension), treat as inconclusive.
    (some #(and (= :fail (:status %)) (not= :hard (:severity %))) results) :inconclusive
    (some #(#{:inconclusive :not-applicable} (:status %)) results) :inconclusive
    :else                          :pass))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn evaluate-mechanism-properties
  "Check all declared :mechanism-properties against the terminal projection.
   Merges built-in generic validators with protocol-specific extra-validators.
   Returns a map of {property-kw → result-map}."
  ([properties projection]
   (evaluate-mechanism-properties properties projection {}))
  ([properties projection extra-validators]
   (let [validators (merge mechanism-validators extra-validators)]
     (into {} (map (fn [prop]
                     (let [kw  (keyword prop)
                           chk (get validators kw)]
                       [kw (if chk
                             (chk projection)
                             (inconclusive kw :absent-evidence :unsupported-concept
                                           (str "no validator implemented for mechanism property: " (name kw))
                                           :required [(keyword (name kw))]))]))
                   properties)))))

(defn evaluate-equilibrium-concepts
  "Check all declared :equilibrium-concept values against the terminal projection.
   Merges built-in generic validators with protocol-specific extra-validators.
   Returns a map of {concept-kw → result-map}."
  ([concepts projection]
   (evaluate-equilibrium-concepts concepts projection {} {}))
  ([concepts projection extra-validators]
   (evaluate-equilibrium-concepts concepts projection extra-validators {}))
  ([concepts projection extra-validators {:keys [claim-tier trust-mode explicit-valid-time? attestation-status]
                                          :or {claim-tier :proxy trust-mode :relaxed explicit-valid-time? false attestation-status :unknown}}]
   (let [validators (merge equilibrium-validators extra-validators)]
     (into {} (map (fn [concept]
                     (let [kw  (keyword concept)
                           chk (get validators kw)
                           bundle-ok? (true? (get-in projection [:deviation-bundle :meets-minimum?]))]
                       [kw (cond
                             (and (strict-valid-time-required? trust-mode)
                                  (not explicit-valid-time?))
                             (inconclusive kw :absent-evidence :missing-valid-time-provenance
                                           (str "trust mode " trust-mode
                                                " requires explicit valid-time provenance; projection/result missing :temporal-confidence.time-basis=:valid-time")
                                           :required [:temporal-confidence.time-basis/valid-time])

                             (and (strict-attestation-required? trust-mode)
                                  (not= :verified attestation-status))
                             (inconclusive kw :absent-evidence :missing-verified-attestation
                                           (str "trust mode " trust-mode
                                                " requires attestation status :verified; observed status=" attestation-status)
                                           :required [:attestation.status/verified]
                                           :available [(keyword "attestation.status" (name attestation-status))])

                             (and (requires-deviation-bundle? claim-tier kw)
                                  (not bundle-ok?))
                             (inconclusive kw :multi-trace-required :missing-deviation-bundles
                                           (str "claim tier " claim-tier
                                                " requires deviation bundle evidence; projection missing :deviation-bundle.meets-minimum? true")
                                           :required [:deviation-bundle.meets-minimum?])

                             chk
                             (chk projection)

                             :else
                             (inconclusive kw :absent-evidence :unsupported-concept
                                           (str "no validator implemented for equilibrium concept: " (name kw))
                                           :required [(keyword (name kw))]))]))
                   concepts)))))

(defn evaluate-equilibrium
  "Top-level entry: build terminal projection, run all declared validators.
   Called by scenario.theory/evaluate-theory when the theory block contains
   :mechanism-properties or :equilibrium-concept.

   Gets projection via the protocol's trace-projection method (when a :protocol
   key is present in result), then merges protocol-supplied extra validators with
   the built-in generic ones.  Falls back gracefully when no protocol is present.

   Returns:
   {:mechanism-results  {property-kw → result-map}
    :mechanism-status   :pass | :fail | :inconclusive | :not-applicable | :not-checked
    :equilibrium-results {concept-kw → result-map}
    :equilibrium-status :pass | :fail | :inconclusive | :not-applicable | :not-checked
    :equilibrium-result-strengths {concept-kw → :pass|:epsilon-pass|:profitable-deviation|:inconclusive|nil}
                              Granular per-concept result strength, surfaced for research use
                              (not collapsed by roll-up).  nil when absent from observed map.}"
  [theory result]
  (let [proto        (:protocol result)
        ;; Get projection from protocol's trace-projection if available; otherwise nil.
        raw-proj     (when proto (protocol/trace-projection proto result))
        ;; Thread spe-config from the theory block into the projection so that
        ;; evaluate-subgame-counterfactual uses the declared thresholds/epsilon
        ;; values rather than its own defaults (regret-threshold=0, epsilon-abs=0.0).
        projection   (cond-> raw-proj
                       (:spe-config theory) (assoc :spe-config (:spe-config theory)))
        mech-props   (seq (:mechanism-properties theory))
        eq-concepts  (seq (:equilibrium-concept theory))
        provenance   (build-provenance result)
        explicit-valid-time? (true? (get-in provenance [:temporal :explicit-valid-time?]))
        attestation-status (or (get-in provenance [:attestation :status]) :unknown)
        trust-mode   (keyword (or (:equilibrium-trust-mode theory) :relaxed))

        extra-mech-validators (when proto (protocol/mechanism-property-validators proto))
        extra-eq-validators   (when proto (protocol/equilibrium-concept-validators proto))

        mech-results (if (and projection mech-props)
                       (evaluate-mechanism-properties mech-props projection
                                                      (or extra-mech-validators {}))
                       {})
        claim-tier  (keyword (or (:equilibrium-claim-tier theory) :proxy))
        eq-results   (if (and projection eq-concepts)
                       (evaluate-equilibrium-concepts eq-concepts projection
                                                      (or extra-eq-validators {})
                                                      {:claim-tier claim-tier
                                                       :trust-mode trust-mode
                                                       :explicit-valid-time? explicit-valid-time?
                                                       :attestation-status attestation-status})
                       {})
        eq-result-strengths (into {}
                                  (map (fn [[kw result]]
                                         [kw (get-in result [:observed :result-strength])])
                                       eq-results))]
    {:evidence-schema-version evidence-schema-version
     :equilibrium-claim-tier claim-tier
     :equilibrium-trust-mode trust-mode
     :provenance          provenance
     :mechanism-results  mech-results
     :mechanism-status   (roll-up-status (vals mech-results))
     :mechanism-reasons  (eq-result/domain-status-reasons mech-results)
     :equilibrium-results eq-results
     :equilibrium-result-strengths eq-result-strengths
     :equilibrium-status  (roll-up-status (vals eq-results))
     :equilibrium-reasons (eq-result/domain-status-reasons eq-results)}))

