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
            [resolver-sim.scenario.equilibrium-result :as eq-result]
            [resolver-sim.validation.validator-descriptor :as vd]
            [resolver-sim.validation.strategic-registry :as sr]))

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
   :incentive-margin              :validation.class/payoff-property
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
        lost      (:funds-lost metrics 0)
        margin    (:incentive-margin metrics)]
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
            (if (some? margin)
              {:attack-successes successes :funds-lost lost :incentive-margin margin}
              {:attack-successes successes :funds-lost lost})
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
   (:partial-fill/exact-pro-rata and :partial-fill/rounding-fairness in
   resolver-sim.yield.partial-fill).

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
;; Builtin generic validators + registry-driven dispatch
;;
;; The generic mechanism-property and equilibrium-concept validators are
;; registered in a rooted validator registry.  A descriptor (prf/
;; game-theoretic-validator.v1) is the canonical identity — it identifies a
;; stable evaluator id/version and its declared execution horizon.  The
;; executable fn is resolved separately from the registry at evaluation time.
;; Adding a validator means registering a descriptor + executable in a
;; registry source; the dispatch below never switches on a fixed validator id.
;; ---------------------------------------------------------------------------

(def ^:private generic-default-epistemic-contract
  "Common epistemic contract for builtin generic single-trace validators."
  {:claim-strength :single-trace-proxy
   :universal-claim? false
   :falsification? true
   :limitations [:single-trace
                 :no-universal-claim
                 :bounded-counterfactual-search]})

(defn- generic-descriptor
  "Build a prf/game-theoretic-validator.v1 descriptor for a generic validator."
  [{:keys [id kind validation-class horizon epistemic-contract]}]
  (merge
   {:validator/schema vd/validator-schema
    :validator/id id
    :validator/version 1
    :validator/kind kind
    :validator/validation-class validation-class
    :validator/epistemic-contract (or epistemic-contract generic-default-epistemic-contract)
    :validator/origin :framework}
   (when horizon
     {:validator/execution-model {:horizon horizon
                                  :state-model :deterministic
                                  :history-required? false}})))

(def builtin-mechanism-validator-descriptors
  "Descriptors for the builtin generic mechanism-property validators."
  (mapv generic-descriptor
        [{:id :incentive-compatibility
          :kind :mechanism-property
          :validation-class :validation.class/payoff-property
          :horizon :single-trace}
         {:id :sybil-resistance
          :kind :mechanism-property
          :validation-class :validation.class/payoff-property
          :horizon :single-trace}
         {:id :pro-rata-fairness
          :kind :mechanism-property
          :validation-class :validation.class/algebraic-integrity
          :horizon :single-trace}
         {:id :redistribution-fairness
          :kind :mechanism-property
          :validation-class :validation.class/algebraic-integrity
          :horizon :single-trace}]))

(def builtin-equilibrium-validator-descriptors
  "Descriptors for the builtin generic equilibrium-concept validators."
  (mapv generic-descriptor
        [{:id :dominant-strategy-equilibrium
          :kind :equilibrium-concept
          :validation-class :validation.class/payoff-property
          :horizon :single-trace}
         {:id :empirical-strategy-dominance
          :kind :equilibrium-concept
          :validation-class :validation.class/payoff-property
          :horizon :single-trace}
         {:id :nash-equilibrium
          :kind :equilibrium-concept
          :validation-class :validation.class/equilibrium
          :horizon :single-trace}
         {:id :bounded-nash-diagnostic
          :kind :equilibrium-concept
          :validation-class :validation.class/equilibrium
          :horizon :single-trace}
         {:id :bayesian-nash-equilibrium
          :kind :equilibrium-concept
          :validation-class :validation.class/equilibrium
          :horizon :multi-epoch
          :epistemic-contract {:claim-strength :multi-epoch-required
                               :universal-claim? false
                               :falsification? true
                               :limitations [:multi-epoch
                                             :no-universal-claim]}}]))

(def ^:private generic-mechanism-validators
  {:incentive-compatibility     check-incentive-compatibility
   :sybil-resistance            check-sybil-resistance
   :pro-rata-fairness           check-pro-rata-fairness
   :redistribution-fairness     check-redistribution-fairness})

(def ^:private generic-equilibrium-validators
  {:dominant-strategy-equilibrium check-dominant-strategy-equilibrium
   :empirical-strategy-dominance  (fn [p] (check-dominant-strategy-equilibrium p :empirical-strategy-dominance))
   :nash-equilibrium              check-nash-equilibrium
   :bounded-nash-diagnostic       (fn [p] (check-nash-equilibrium p :bounded-nash-diagnostic))
   :bayesian-nash-equilibrium     check-bayesian-nash-equilibrium})

(def builtin-validator-registry
  "Rooted registry of builtin generic mechanism-property and
   equilibrium-concept validators.  Descriptors are the committed identity;
   executables are the local runtime association, kept separate so the root
   never commits behaviour.  Protocol/application validators compose on top
   via build-validator-registry."
  (sr/build-validator-registry
   :sources [{:origin :framework
              :entries (concat builtin-mechanism-validator-descriptors
                               builtin-equilibrium-validator-descriptors)}]
   :executables (merge generic-mechanism-validators
                       generic-equilibrium-validators)))

(defn compose-validator-registry
  "Compose a validator registry from the builtin generic validators plus
   protocol/application-supplied sources.  Each extra source is either
     {:origin kw :entries [descriptor ...]} or a plain vector of descriptors;
   extra-executables is {validator-id fn}.

   Returns a registry map like builtin-validator-registry."
  [& {:keys [sources executables]
      :or {sources [] executables {}}}]
  (sr/build-validator-registry
   :sources (concat [{:origin :framework
                      :entries (concat builtin-mechanism-validator-descriptors
                                       builtin-equilibrium-validator-descriptors)}]
                    sources)
   :executables (merge generic-mechanism-validators
                       generic-equilibrium-validators
                       executables)))

(defn resolve-validator-executable
  "Resolve the executable fn for a validator id from a registry.
   Returns nil when the id has no executable registered."
  [registry validator-id]
  (sr/resolve-validator-executable registry validator-id))

(defn resolve-validator-descriptor
  "Resolve the descriptor for a validator id from a registry.
   Returns nil when the id has no descriptor registered."
  [registry validator-id]
  (sr/resolve-validator registry validator-id))

(defn registered-validator-ids
  "Sorted vector of validator ids registered (by descriptor) in a registry."
  [registry]
  (sr/validator-ids registry))

;; ---------------------------------------------------------------------------
;; Horizon gating (execution-model is first-class, from the descriptor)
;; ---------------------------------------------------------------------------

(defn- horizon-gate
  "Return [basis reason-kw detail] when the descriptor's declared horizon is
   incompatible with single-trace evaluation, else nil."
  [desc]
  (when desc
    (cond
      (vd/multi-epoch-required? desc)
      [:multi-epoch-required :multi-epoch-required
       "declared execution horizon requires multi-epoch evidence; single-trace projection cannot evaluate"]
      (vd/multi-trace-required? desc)
      [:multi-trace-required :multi-trace-required
       "declared execution horizon requires multiple independent traces; single-trace projection cannot evaluate"])))

(defn- resolve-validator!
  "Resolve the descriptor and executable for a validator id from a registry.
   Fails closed:
     - descriptor registered but executable missing → throws;
     - executable present but no descriptor → treated as single-trace
       (backward compatible: legacy protocol validators carried no descriptor).
   Returns {:descriptor desc-or-nil :executable fn-or-nil}."
  [registry validator-id]
  (let [desc (resolve-validator-descriptor registry validator-id)
        exec (resolve-validator-executable registry validator-id)]
    (when (and desc (nil? exec))
      (throw (ex-info "Validator descriptor registered without executable"
                      {:validator/id validator-id
                       :registry/root (:root registry)})))
    {:descriptor desc :executable exec}))

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
   Dispatch is registry-driven: each property resolves its descriptor +
   executable from a validator registry (default builtin-validator-registry).
   Adding a validator = registering a descriptor + executable; this function
   never switches on a fixed validator id.

   Optional:
     :registry    — a validator registry (from compose-validator-registry /
                    build-validator-registry). Defaults to the builtin.
     :extra-executables — {property-kw → fn} merged into the registry
                    (backward-compatible with the legacy extra-validators map).
     :descriptors — {property-kw → descriptor} merged into the registry.

   A descriptor declaring a :multi-epoch or :multi-trace horizon is reported
   :inconclusive :multi-epoch-required when only single-trace evidence is
   available.  A descriptor registered without an executable fails closed
   (throws).  Returns a map of {property-kw → result-map}."
  ([properties projection]
   (evaluate-mechanism-properties properties projection {} {}))
  ([properties projection extra-validators]
   (evaluate-mechanism-properties properties projection extra-validators {}))
  ([properties projection extra-validators {:keys [registry descriptors]
                                            :or {descriptors {}}}]
   (let [reg (or registry
                 (compose-validator-registry
                  :sources [{:origin :protocol :entries (vec (vals descriptors))}]
                  :executables extra-validators))]
     (into {} (map (fn [prop]
                     (let [kw  (keyword prop)
                           {:keys [descriptor executable]} (resolve-validator! reg kw)
                           hg (horizon-gate descriptor)]
                       [kw (cond
                             hg
                             (let [[basis reason-kw detail] hg]
                               (inconclusive kw basis reason-kw detail))

                             executable
                             (executable projection)

                             :else
                             (inconclusive kw :absent-evidence :unsupported-concept
                                           (str "no validator implemented for mechanism property: " (name kw))
                                           :required [(keyword (name kw))]))]))
                   properties)))))

(defn evaluate-equilibrium-concepts
  "Check all declared :equilibrium-concept values against the terminal projection.
   Dispatch is registry-driven: each concept resolves its descriptor + executable
   from a validator registry (default builtin-validator-registry).  Adding a
   validator = registering a descriptor + executable; this function never
   switches on a fixed validator id.

   Optional:
     :registry    — a validator registry (default builtin).
     :extra-executables — {concept-kw → fn} merged into the registry
                    (backward-compatible with the legacy extra-validators map).
     :descriptors — {concept-kw → descriptor} merged into the registry.

   A concept whose descriptor declares a :multi-epoch or :multi-trace horizon
   is reported :inconclusive :multi-epoch-required when only single-trace
   evidence is available.  A descriptor registered without an executable fails
   closed (throws).  Returns a map of {concept-kw → result-map}."
  ([concepts projection]
   (evaluate-equilibrium-concepts concepts projection {} {}))
  ([concepts projection extra-validators]
   (evaluate-equilibrium-concepts concepts projection extra-validators {}))
  ([concepts projection extra-validators {:keys [claim-tier trust-mode explicit-valid-time? attestation-status
                                                 registry descriptors]
                                          :or {claim-tier :proxy trust-mode :relaxed explicit-valid-time? false attestation-status :unknown descriptors {}}}]
   (let [reg (or registry
                 (compose-validator-registry
                  :sources [{:origin :protocol :entries (vec (vals descriptors))}]
                  :executables extra-validators))]
     (into {} (map (fn [concept]
                     (let [kw  (keyword concept)
                           {:keys [descriptor executable]} (resolve-validator! reg kw)
                           bundle-ok? (true? (get-in projection [:deviation-bundle :meets-minimum?]))
                           hg (horizon-gate descriptor)]
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

                             hg
                             (let [[basis reason-kw detail] hg]
                               (inconclusive kw basis reason-kw detail))

                             executable
                             (executable projection)

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

        extra-mech-validators (or (when proto (protocol/mechanism-property-validators proto)) {})
        extra-eq-validators   (or (when proto (protocol/equilibrium-concept-validators proto)) {})
        ;; First-class descriptors: when the protocol exposes a
        ;; ValidatorDescriptorCatalog, index descriptors by concept/kind so
        ;; horizon-aware dispatch can gate multi-epoch/multi-trace concepts.
        proto-descriptors (when (and proto
                                     (satisfies? protocol/ValidatorDescriptorCatalog proto))
                            (let [descs (protocol/validator-descriptors proto)]
                              {:equilibrium (filter #(= :equilibrium-concept (:validator/kind %)) descs)
                               :mechanism   (filter #(= :mechanism-property (:validator/kind %)) descs)}))
        ;; Compose per-kind registries: builtin + protocol descriptors +
        ;; protocol executables.  Dispatch resolves ids from these registries;
        ;; no fixed validator-id switching anywhere in this path.
        mech-reg (compose-validator-registry
                  :sources [{:origin :protocol :entries (get proto-descriptors :mechanism [])}]
                  :executables extra-mech-validators)
        eq-reg   (compose-validator-registry
                  :sources [{:origin :protocol :entries (get proto-descriptors :equilibrium [])}]
                  :executables extra-eq-validators)

        mech-results (if (and projection mech-props)
                       (evaluate-mechanism-properties mech-props projection {} {:registry mech-reg})
                       {})
        claim-tier  (keyword (or (:equilibrium-claim-tier theory) :proxy))
        eq-results   (if (and projection eq-concepts)
                       (evaluate-equilibrium-concepts eq-concepts projection {}
                                                      {:claim-tier claim-tier
                                                       :trust-mode trust-mode
                                                       :explicit-valid-time? explicit-valid-time?
                                                       :attestation-status attestation-status
                                                       :registry eq-reg})
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

