(ns resolver-sim.assurance.canonical-force-authorisation
  "Reconciliation for the two force-authorisation worlds (ADR-0007 D2), plus
  the D3 boundary/context classification, D4 profile pinning, and D5 schema
  versioning compatibility test.

  The two worlds:

    :canonical-research
      resolver-sim.benchmark.researcher-force-authorisation plus the run-layer
      force-authorisation-policy artifact: strict policy/instance split, frozen
      exactly-three-member signed decisions, Ed25519, whole-outcome consensus,
      decision statuses, consumption receipts, and a frozen review-round binding.

    :legacy-evidence
      resolver-sim.assurance.force-authorisation base helpers plus the frozen
      historical held-custody evidence artifacts (force-auth-add-held.v1/.v2,
      force-auth-lifecycle.v1, force-auth-lifecycle-summary.v1/.v2,
      force-auth-add-held-summary.v1/.v2): single-identity grant/consume
      lifecycle, authorization/status in
      #{:active :consumed :revoked :expired :failed-after-consumption :rolled-back}.
      Historical verification of these artifacts is extension-owned
      (prf.extensions.held-custody legacy-validate); the former core legacy
      evidence namespace was removed in Phase 3B. This namespace classifies
      schema-version strings only — it re-implements neither world.

  This namespace is protocol-independent (no Sew import) and provides pure data
  classification only; it does not re-implement either world."
  (:require [clojure.set]
            [clojure.string :as str]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

;; ═══════════════════════════════════════════════════════════════════════════
;; Canonical profile (D1 / D4)
;; ═══════════════════════════════════════════════════════════════════════════

(def canonical-member-count
  "Canonical member count of the three-member decision standard, pinned to 3."
  3)

(def canonical-threshold
  "Canonical threshold of the three-member decision standard: two concurring
   positions over the same whole outcome."
  2)

(def canonical-profile
  {:member-count canonical-member-count
   :threshold canonical-threshold})

(def canonical-schema-version
  "Version of the canonical reconciliation vocabulary."
  "force-authorisation-canonical-reconciliation.v1")

(def canonical-decision-statuses
  "Canonical immutable decision statuses (shared with the research world)."
  #{:approved :approved-with-dissent :declined})

(def canonical-decision-vocabulary
  "Individual position vocabulary for a canonical decision."
  #{:approve :dissent})

(def legacy-lifecycle-statuses
  "Lifecycle statuses recognized from the legacy evidence world."
  #{:active :consumed :revoked :expired :failed-after-consumption :rolled-back})

;; ═══════════════════════════════════════════════════════════════════════════
;; Profile classification (D1 / D4)
;; ═══════════════════════════════════════════════════════════════════════════

(defn classify-profile
  "Classify a (member-count, threshold) pair against the canonical profile.

    :canonical                  exactly 3 members, 2-of-3 (conforming)
    :canonical-unanimous        exactly 3 members, 3-of-3 (conforming, stronger)
    :nonconforming-one-approval exactly 3 members, 1-of-3 (never conforming)
    :noncanonical-quorum        member count not equal to 3 (separate primitive)
    :invalid                    missing or non-integer values"
  [member-count threshold]
  (cond
    (or (nil? member-count) (nil? threshold)
        (not (integer? member-count)) (not (integer? threshold)))
    :invalid

    (and (= member-count canonical-member-count)
         (= threshold canonical-threshold))
    :canonical

    (and (= member-count canonical-member-count)
         (= threshold canonical-member-count))
    :canonical-unanimous

    (and (= member-count canonical-member-count)
         (= threshold 1))
    :nonconforming-one-approval

    (not= member-count canonical-member-count)
    :noncanonical-quorum

    :else
    :nonconforming))

(def conforming-standard-classifications
  #{:canonical :canonical-unanimous})

(defn declare-profile
  [opts]
  (let [{:keys [member-count threshold named-policy? profile-id]} opts
        declared? (or (some? profile-id) (true? named-policy?))]
    {:member-count member-count
     :threshold threshold
     :declared? declared?
     :profile/id (when (some? profile-id) (str profile-id))
     :named-policy? (boolean named-policy?)
     :class (classify-profile member-count threshold)}))

(defn three-member-standard-conforming?
  [profile]
  (and (true? (:declared? profile))
       (contains? #{:canonical :canonical-unanimous} (:class profile))))

(defn canonical-profile-conforming?
  [profile]
  (and (true? (:declared? profile))
       (= :canonical (:class profile))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Policy reconciliation
;; ═══════════════════════════════════════════════════════════════════════════

(defn- field
  "Read a value by any of the given keys (string or keyword spellings)."
  [m & ks]
  (some #(get m %) ks))

(defn reconcile-policy
  "Normalize a force-authorisation policy artifact into the canonical policy
   shape and classify it against the canonical profile.

   Accepts a policy map with either string keys (the run-layer
   force-authorisation-policy artifact shape) or keyword keys.

   Returns
     {:policy/id ... :schema/version ... :member-count n :threshold m
      :profile <classification> :conforming? bool}."
  [policy]
  (let [member-count (field policy "member_count" :member-count :member_count)
        threshold    (field policy "threshold" :threshold)
        profile-id   (field policy "policy_id" :policy-id :policy_id)
        declared (declare-profile
                  {:member-count member-count
                   :threshold threshold
                   :profile-id profile-id})]
    {:policy/id profile-id
     :schema/version (field policy "schema_version" :schema-version :schema_version)
     :member-count member-count
     :threshold threshold
     :profile (:class declared)
     :conforming? (three-member-standard-conforming? declared)}))

;; ═══════════════════════════════════════════════════════════════════════════
;; Representation-family classification (D2)
;; ═══════════════════════════════════════════════════════════════════════════

(def canonical-research-prefixes
  "Schema-version prefixes of the canonical research force-authorisation world."
  ["researcher-force-authorisation." "researcher-decision."])

(def canonical-policy-prefixes
  "Schema-version prefixes of the canonical policy artifact."
  ["force-authorisation-policy."])

(def legacy-evidence-prefixes
  "Schema-version prefixes of the legacy evidence force-authorisation world."
  ["force-auth-add-held"
   "force-auth-lifecycle"
   "force-auth-add-held-summary"])

(defn classify-representation
  "Classify an artifact (or its schema-version string) into a representation
   family:
     :canonical-research  canonical instance/decision world
     :canonical-policy    canonical policy artifact
     :legacy-evidence     legacy scope-hash evidence world
     :unknown"
  [artifact]
  (let [v (if (map? artifact) (str (:schema-version artifact)) (str artifact))]
    (cond
      (str/blank? v) :unknown
      (some #(str/starts-with? v %) canonical-research-prefixes) :canonical-research
      (some #(str/starts-with? v %) canonical-policy-prefixes) :canonical-policy
      (some #(str/starts-with? v %) legacy-evidence-prefixes) :legacy-evidence
      :else :unknown)))

(def eligible-emission-families
  "Representation families allowed to emit new canonical decisions (D2)."
  #{:canonical-research})

(defn representation-normative?
  "True only for the canonical research world (and its policy artifact)."
  [family]
  (contains? #{:canonical-research :canonical-policy} family))

(defn representation-emission-eligible?
  "True only when the family is the single designated canonical new-emission
   path. Legacy evidence and unknown families are never emission-eligible."
  [family]
  (contains? eligible-emission-families family))

(defn- canonical-claims-absent
  "Claims a representation lacks relative to the canonical three-member model."
  [family]
  (case family
    :canonical-research nil
    :canonical-policy [:member-positions :consensus :signatures]
    :legacy-evidence [:member-count :threshold :researcher-signatures
                      :whole-outcome-agreement :role-independence
                      :policy-binding]
    :unknown [:member-count :threshold :membership :signatures
              :whole-outcome-agreement :role-independence :policy-binding]
    [:member-count :threshold :whole-outcome-agreement]))

(defn assess-representation
  "Canonical differential assessment of a force-authorisation artifact or its
   schema-version string. Ties `classify-representation` to the canonical
   model (D2) and reports what a migration would need.

   Returns
     {:representation/class <family>
      :canonical-model-compatible? bool
      :canonical-emission-eligible? bool
      :representation-normative? bool
      :missing-claims [...]
      :schema-change :no-change | :legacy-projection | :fail-closed
      :migration-action ...}"
  [artifact-or-schema]
  (let [family (classify-representation artifact-or-schema)]
    (case family
      :canonical-research
      {:representation/class family
       :canonical-model-compatible? true
       :canonical-emission-eligible? true
       :representation-normative? true
       :missing-claims []
       :schema-change :none
       :migration-action :none}

      :canonical-policy
      {:representation/class family
       :canonical-model-compatible? true
       :canonical-emission-eligible? false
       :representation-normative? true
       :missing-claims (vec (canonical-claims-absent family))
       :schema-change :config-only
       :migration-action :validate-policy-through-profile}

      :legacy-evidence
      {:representation/class family
       :canonical-model-compatible? false
       :canonical-emission-eligible? false
       :representation-normative? false
       :missing-claims (vec (canonical-claims-absent family))
       :schema-change :legacy-projection
       :migration-action :read-and-verify-only}

      :unknown
      {:representation/class family
       :canonical-model-compatible? false
       :canonical-emission-eligible? false
       :representation-normative? false
       :missing-claims (vec (canonical-claims-absent family))
       :schema-change :fail-closed
       :migration-action :reject/ignore}

      {:representation/class :unknown
       :canonical-model-compatible? false
       :canonical-emission-eligible? false
       :representation-normative? false
       :missing-claims []
       :schema-change :fail-closed
       :migration-action :reject/ignore})))

(defn projection-normative?
  "False for every legacy projection; only canonical decisions are normative."
  [projection]
  (true? (:projection/normative? projection)))

(defn legacy-as-canonical-projection
  "Present a legacy evidence authorization artifact as a canonical-model
   projection.

   A legacy grant is a single-identity grant/consume record, not a three-member
   decision. Under D2/D3 it is retained for historical verification only:

     - ONE-WAY: the projection is not usable as input to canonical certificate
       construction, canonical policy issuance, a conformance success based on
       projected fields, role-independence or whole-outcome-agreement claims,
       or new force-authorisation emission.

   The negative capability is machine-visible:  :projection/normative? and
   :canonical-emission-eligible? are both false."
  [legacy-artifact status]
  (let [auth-id (or (:authorization/id legacy-artifact) (:id legacy-artifact))
        st (or status (:authorization/status legacy-artifact) :unknown)]
    {:representation/class :legacy-evidence
     :projection/normative? false
     :canonical-emission-eligible? false
     :reconciliation/schema canonical-schema-version
     :projection/source :legacy-evidence
     :authorization/id auth-id
     :legacy/status st
     :canonical/decision? false
     :canonical/conforming? false
     :canonical/role-independent false
     :canonical/whole-outcome-consistent false
     :projection/notes
     "single-identity legacy grant projected onto the canonical model; historical verification only, never normative, never emission-eligible, never consensus-bearing"}))

(defn reconcile-force-authorisation-worlds
  "D2 summary: one canonical model; legacy representations are read-only
   projections. Pure metadata, no state."
  []
  {:reconciliation/schema canonical-schema-version
   :canonical-model "resolver-sim.benchmark.researcher-force-authorisation"
   :canonical-policy "resolver-sim.run.force-authorisation-policy"
   :legacy-representation "prf.extensions.held-custody.legacy-validate"
   :one-canonical-model? true
   :new-decisions-emitted-from-legacy? false})

;; ═══════════════════════════════════════════════════════════════════════════
;; D3 boundary-context classification
;; ═══════════════════════════════════════════════════════════════════════════

(def decision-boundary-kinds
  #{:contested-adjudication :initiation :routing
    :deterministic-execution :evidence-submission})

(def boundary-context-table
  {:canonical-contested {:members 3 :threshold 2 :mode :mandatory}
   :historical-artifact-verification {:legacy-permitted true}
   :experimental-simulation {:alternative-panel-permitted true}
   :deterministic-probabilistic {:not-applies true}
   :post-certification-execution {:single-actor-permitted true}})

(defn declared-boundary-kind
  "Declared :decision-boundary/kind, failing closed to :deterministic-execution
   for unknown values. The kind is authoritative; the command name is not."
  [cmd]
  (if (contains? decision-boundary-kinds (:decision-boundary/kind cmd))
    (:decision-boundary/kind cmd)
    :deterministic-execution))

(defn boundary-decision?
  "True when cmd is a canonical contested-decision boundary. A command is a
   boundary only when its DECLARED :decision-boundary/kind is
   :contested-adjudication AND it selects among materially incompatible
   outcomes using discretionary or evaluative judgement. Initiation, routing,
   evidence submission, and deterministic execution are never boundaries on
   their own, regardless of command name."
  [cmd]
  (and (= :contested-adjudication (declared-boundary-kind cmd))
       (true? (:selects-materially-incompatible-outcomes? cmd))
       (true? (:uses-discretionary-judgement? cmd))))

(defn decision-context
  "Map a decision/action descriptor to its D3 context.

   opts may carry :decision-boundary/kind (authoritative), or legacy booleans
   :contested? :produces-certificate? :historical-artifact? :experimental?
   :deterministic? :probabilistic? :post-certification-execution?.

   Returns a keyword from `boundary-context-table`, or
   :not-a-contested-boundary."
  [opts]
  (let [kind (declared-boundary-kind opts)]
    (cond
      (or (:deterministic? opts) (:probabilistic? opts)
          (and (contains? opts :decision-boundary/kind)
               (= :deterministic-execution kind)))
      :deterministic-probabilistic
      (:historical-artifact? opts) :historical-artifact-verification
      (:experimental? opts) :experimental-simulation
      (:post-certification-execution? opts) :post-certification-execution
      (or (:contested? opts) (:produces-certificate? opts)
          (= :contested-adjudication kind))
      :canonical-contested
      :else :not-a-contested-boundary)))

;; ═══════════════════════════════════════════════════════════════════════════
;; D5 schema-versioning compatibility test
;; ═══════════════════════════════════════════════════════════════════════════

(def bump-flags
  "Change descriptors that each force a certificate schema version bump (D5).

   A change is safe without a bump only when none of these is true."
  {:certificate-controls-membership
   "member count or threshold becomes certificate-controlled"
   :commits-policy-profile-id
   "certificate commits a policy/profile identifier that was not previously committed"
   :role-semantics-changed
   "role semantics change"
   :whole-outcome-meaning-changed
   "the meaning of consensus / whole-outcome agreement changes"
   :new-membership-claims-committed
   "new membership or independence claims enter the hashed projection"
   :reader-interpretation-changed
   "old readers could accept the artifact while interpreting it differently"})

(defn schema-change-compatibility
  "D5: classify a change descriptor map into a versioning outcome.

   opts carries a subset of the `bump-flags` keys set to true, and optionally
   :affected-schema to name the schema under test.

   Returns
     {:schema-stable? bool
      :schema-bump :stable | :bump-required
      :required-action :preserve-version | :new-version
      :reasons #{<fired bump-flags keys>}
      :affected-schema ...}"
  ([opts] (schema-change-compatibility {} opts))
  ([base opts]
   (let [opts (merge base opts)
         reasons (set (filter #(true? (get opts %)) (keys bump-flags)))
         bump? (seq reasons)]
     {:schema-stable? (not bump?)
      :schema-bump (if bump? :bump-required :stable)
      :required-action (if bump? :new-version :preserve-version)
      :reasons reasons
      :affected-schema (:affected-schema opts)})))

(defn schema-stable?
  "True when opts does not require a certificate schema version bump (D5)."
  ([opts] (= :stable (:schema-bump (schema-change-compatibility opts))))
  ([base opts]
   (= :stable (:schema-bump (schema-change-compatibility base opts)))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Cancellation window (cancellation-window.v1)
;; ═══════════════════════════════════════════════════════════════════════════
;;
;; A generic, domain-neutral primitive. It decides four things and nothing
;; else: which target states are valid (pre-cutpoint), which are irreversible,
;; how a state maps to :open / :closed / :invalid, and how to fail closed.
;; It implies no 2-of-3 semantics of its own — the 2-of-3 conformance lives in
;; the decision profile, not here. A workflow supplies a lifecycle profile;
;; force authorisation and probabilistic allocation each supply one below.

(def cancellation-window-v1-schema
  "Schema version of the generic cancellation-window.v1 primitive."
  "cancellation-window.v1")

(defn lifecycle-window-profile
  "Build a cancellation-window.v1 lifecycle profile.

   Contract 4: open states are explicit (`:open-states`), not inferred as
   'valid minus irreversible'. A profile is a domain projection of the generic
   primitive and never embeds 2-of-3 semantics.

   opts:
     :profile/id            — qualified identifier of the domain lifecycle
     :profile/version       — integer profile version (committed into decisions)
     :valid-states          — complete declared state vocabulary (open ∪ irreversible)
     :open-states           — explicit pre-cutpoint (cancellable) states
     :irreversible-states   — the cutpoint state plus everything after it
     :blocking-reason-by-state — reason keyword per irreversible state (required)
     :transitions           — optional map state -> #{reachable next states}
                              (used by the monotonic-window rule)"
  [{:keys [valid-states open-states irreversible-states
           blocking-reason-by-state transitions] :as opts}]
  {:window/schema cancellation-window-v1-schema
   :profile/id (:profile/id opts)
   :profile/version (:profile/version opts)
   :valid-states (set valid-states)
   :open-states (set open-states)
   :irreversible-states (set irreversible-states)
   :blocking-reason-by-state (or blocking-reason-by-state {})
   :transitions (or transitions {})})

(defn classify-lifecycle-window
  "Generic cancellation-window.v1 classification of a target state.

   Owns only the window mechanics; it never judges a decision profile. Fail
   closed: a missing, unrecognised, or undeclared state is :invalid and is
   never :open.

   Returns
     {:window/schema ...
      :window/state :open|:closed|:invalid
      :window/possible? bool
      :window/blocking-reasons [kw]}."
  [window state]
  (let [open (:open-states window)
        irreversible (:irreversible-states window)
        reason-fn (fn [st] (get (:blocking-reason-by-state window) st st))
        kw (cond
             (nil? state) :invalid
             (contains? irreversible state) :closed
             (contains? open state) :open
             :else :invalid)
        blocking (cond-> []
                   (= :closed kw) (conj (reason-fn state))
                   (= :invalid kw) (conj :unknown-target-state))]
    {:window/schema (:window/schema window)
     :window/state kw
     :window/possible? (= :open kw)
     :window/blocking-reasons (vec blocking)}))

(defn validate-lifecycle-profile
  "Contract 4 structural validation of a lifecycle profile. Returns errors as
   keyword reasons:

     - missing :profile/id or :profile/version
     - empty :valid-states
     - :open-states / :irreversible-states overlap
     - open or irreversible states not declared in :valid-states
     - :valid-states not the complete union of open U irreversible
     - an irreversible state with no blocking reason in
       :blocking-reason-by-state
     - an open state that classifies non-open, or an irreversible state that
       classifies non-closed (the cutpoint state must be :closed)."
  [window]
  (let [id (:profile/id window)
        version (:profile/version window)
        valid (:valid-states window)
        open (:open-states window)
        irreversible (:irreversible-states window)
        reasons (:blocking-reason-by-state window)
        errors (cond-> []
                 (nil? id) (conj :missing-profile-id)
                 (not (integer? version)) (conj :missing-profile-version)
                 (empty? valid) (conj :empty-valid-states)
                 (seq (clojure.set/intersection open irreversible))
                 (conj :open-irreversible-overlap)
                 (seq (clojure.set/difference open valid))
                 (conj :open-state-not-valid)
                 (seq (clojure.set/difference irreversible valid))
                 (conj :irreversible-state-not-valid)
                 (not= valid (clojure.set/union open irreversible))
                 (conj :incomplete-valid-state-contract)
                 (some #(nil? (get reasons %)) irreversible)
                 (conj :missing-blocking-reason)
                 (some #(not= :open (:window/state (classify-lifecycle-window window %))) open)
                 (conj :open-state-classifies-not-open)
                 (some #(not= :closed (:window/state (classify-lifecycle-window window %))) irreversible)
                 (conj :irreversible-state-classifies-not-closed))]
    {:valid? (empty? errors) :errors (vec errors)}))

(defn validate-lifecycle-monotonicity
  "Contract 5: the window is monotonic - for a single target instance the
   lifecycle must never move :closed -> :open. The primitive classifies
   individual states; this checks the profile's optional :transitions
   (state -> allowed next states): every edge from a closed state must lead to
   a closed or terminal state (never a reopening). Failure, timeout, restart,
   coordinator recovery, fallback, and partial rollback must continue on the
   same committed basis rather than move an instance back into :open."
  [window]
  (let [open-state? (fn [s] (contains? (:open-states window) s))
        closed-state? (fn [s] (contains? (:irreversible-states window) s))
        violations (for [[from tos] (:transitions window)
                         to tos
                         :when (and (closed-state? from) (open-state? to))]
                     [from to])]
    {:valid? (empty? violations) :violations (vec violations)}))

(def cancellation-lifecycle-cutpoint
  "Human description of the cutpoint rule (contract 3): the first state that
   creates an irreversible effect belongs to the :closed side of the boundary
   and is never classified :open. For probabilistic allocation this is the
   authoritative randomness request, earlier than :consumed."
  "the first irreversible state (the state that commits the effect) classifies
  :closed, never :open; for probabilistic allocation this is the authoritative
  randomness request, earlier than :consumed")

(defn cancellation-conflict-key
  "Contract 6: the canonical key that cancellation and the irreversible
   transition must contend on atomically. The coordinator must serialise
   cancellation and the cutpoint transition over exactly one of these so that
   exactly one may establish the winning transition -
   (compare-and-transition! {:target-id ... :expected-state ...
   :expected-version ... :certificate cancellation-certificate} :cancelled).
   Classification alone cannot guard the race."
  [target-id lifecycle-window]
  {:target/id target-id
   :lifecycle/profile-id (:profile/id lifecycle-window)
   :lifecycle/profile-version (:profile/version lifecycle-window)})

(def cancellation-effects
  "Effects of an atomic pre-consumption cancellation (contract 2, Option A)."
  #{:invalidate-authorisation :release-reservation :prevent-consumption
    :emit-terminal-cancellation-receipt :preserve-cancellation-evidence})

(def cancellation-binding-fields
  "Contract 7: the signed/certified cancellation decision must bind this whole
   outcome over the exact target snapshot - otherwise members could agree to
   cancel the same logical target while relying on different target versions or
   lifecycle snapshots."
  #{:target/id :target/hash :lifecycle/profile-id :lifecycle/profile-version
    :target/state-evidence-root :cancellation/action :cancellation/effects
    :cancellation/reason :decision/profile-id :policy/instance
    :decision/validity-window :conflict/consumption-key})

(defn- cancellation-binding-field-present?
  "True when the binding carries a populated value for `field` - i.e. the key is
   present AND its value is not nil or a whitespace-only string.  Contract 7
   binds a whole outcome over the exact target snapshot, so a nil blank
   placeholder must not count as the binding being present.  Empty collections
   (e.g. an empty :cancellation/effects set) ARE present - they project to an
   empty canonical vector and are legitimately committable."
  [binding field]
  (let [v (get binding field ::missing)]
    (and (not (identical? v ::missing))
         (not (or (nil? v)
                  (and (string? v) (str/blank? v)))))))

(defn cancellation-binding-complete?
  "True when the commit map carries a populated value for every
   `cancellation-binding-fields` key."
  [binding]
  (every? #(cancellation-binding-field-present? binding %)
          cancellation-binding-fields))

(defn missing-cancellation-binding-fields
  "The binding fields absent from - or blank/nil within - a committed
   cancellation decision map."
  [binding]
  (vec (sort (filter #(not (cancellation-binding-field-present? binding %))
                     cancellation-binding-fields))))

(defn- project-cancellation-binding
  "Project a committed cancellation binding into canonical-safe form.  Sets
   (e.g. the :cancellation/effects vocabulary) become sorted vectors so the
   binding can be content-addressed by the strict encoder, which rejects sets."
  [binding]
  (letfn [(walk [v]
            (cond
              (set? v) (vec (sort-by pr-str (map walk v)))
              (map? v) (into {} (map (fn [[k val]] [k (walk val)]) v))
              (vector? v) (mapv walk v)
              :else v))]
    (walk binding)))

(defn cancellation-binding-hash
  "Content hash of a committed cancellation binding (contract 7 whole-outcome
   binding).  Requires every `cancellation-binding-fields` key to be present,
   projects set values (e.g. :cancellation/effects) to sorted vectors, and
   commits the exact binding fields via canonical-bytes under the
   :cancellation-binding domain.  Returns \"sha256:<hex>\"."
  [binding]
  (when-not (cancellation-binding-complete? binding)
    (throw (ex-info "cannot content-hash an incomplete cancellation binding"
                    {:missing (missing-cancellation-binding-fields binding)})))
  (hash-ref/sha256-ref (hc/domain-hash :cancellation-binding
                                       (project-cancellation-binding
                                        (select-keys binding cancellation-binding-fields)))))

(defn cancellation-binding-hash-valid?
  "True when the committed :cancellation/binding-hash recomputes from the
   binding's exact binding-fields projection."
  [binding]
  (and (cancellation-binding-complete? binding)
       (= (:cancellation/binding-hash binding)
          (cancellation-binding-hash (dissoc binding :cancellation/binding-hash)))))

(def cancellation-operations
  "Contract 1 taxonomy of cancellation-adjacent operations. Only an EXPLICIT
   cancellation that revokes an otherwise valid authorisation is a canonical
   contested decision. Deterministic expiry, invalidation, routing, rejection,
   and execution are not cancellation decisions (preserves D3 and D7):
     submit cancellation request                        no
     decide to cancel a still-valid authorisation     yes
     expire at a committed deadline                    no
     deterministic invalidation                       no
     reject a post-cutpoint cancellation              no
     execute a certified cancellation                  no
     automatic abandonment/fallback under a
       precommitted failure policy                     no, provided precommitted"
  {:submit-cancel-request :deterministic
   :decide-cancel-valid-authorisation :canonical-decision
   :expire-at-deadline :deterministic
   :deterministic-invalidation :deterministic
   :reject-post-cutpoint-cancellation :deterministic
   :execute-certified-cancellation :deterministic
   :apply-deterministic-fallback :deterministic})

(defn cancellation-decision-required?
  "Contract 1: true only for an explicit decision to cancel an otherwise valid
   authorisation - the single canonical-contested case in the taxonomy."
  [operation]
  (= :canonical-decision (get cancellation-operations operation)))

;; ═══════════════════════════════════════════════════════════════════════════
;; Deterministic-operation evidence contract
;; ═══════════════════════════════════════════════════════════════════════════
;;
;; INVARIANT: no researcher panel is required because no researcher discretion
;; is exercised; verification remains mandatory. A deterministic operation is
;; not a canonical decision, but it is never unverified — every required
;; evidence category must be present and consistent.

(def deterministic-operation-evidence
  "Data-driven evidence checklist for every deterministic cancellation-adjacent
   operation (contract 1 taxonomy). `:decide-cancel-valid-authorisation` is
   intentionally absent: it is the single canonical contested decision.

   Evidence categories (subset as required per operation):
     :lifecycle/profile-id / :lifecycle/profile-version — which lifecycle;
     :lifecycle/state        — current committed lifecycle state;
     :cutpoint               — the irreversible boundary rule;
     :applicable-time        — deadline/time at which the rule applies;
     :target/id :target/hash — the exact target and its committed snapshot;
     :domain-projection      — evidence -> lifecycle-state projection;
     :conflict-key           — the contention key (contract 6);
     :conflict-key-result    — the transition race result;
     :certificate/hash       — the certified decision being executed;
   :rule/id                — the deterministic rule identifier;
      :operation/provenance   — who/what performed the operation and when.
   :lifecycle/profile is REQUIRED for the post-cutpoint operations (rejection and
   certified execution): the declared :lifecycle/state must classify :closed
   (never :open) relative to it. Without the profile the open-window
   contradiction cannot be checked, so those operations fail closed."
  {:expire-at-deadline
   {:evidence #{:lifecycle/profile-id :lifecycle/profile-version
                :lifecycle/state :cutpoint :applicable-time
                :target/id :target/hash :rule/id :operation/provenance}
    :reason "expiry at a committed deadline"}

   :deterministic-invalidation
   {:evidence #{:lifecycle/profile-id :lifecycle/profile-version
                :lifecycle/state :cutpoint
                :target/id :target/hash :rule/id :operation/provenance}
    :reason "deterministic invalidation"}

   :reject-post-cutpoint-cancellation
   {:evidence #{:lifecycle/profile-id :lifecycle/profile-version
                :lifecycle/profile :lifecycle/state :cutpoint :target/id :target/hash
                :domain-projection :rule/id :operation/provenance}
    :reason "post-cutpoint rejection"}

   :execute-certified-cancellation
   {:evidence #{:lifecycle/profile-id :lifecycle/profile-version
                :lifecycle/profile :lifecycle/state :cutpoint :target/id :target/hash
                :certificate/hash :conflict-key :conflict-key-result
                :operation/provenance}
    :reason "execution of a certified decision"}

   :apply-deterministic-fallback
   {:evidence #{:lifecycle/profile-id :lifecycle/profile-version
                :lifecycle/state :cutpoint :target/id :target/hash
                :domain-projection :rule/id :operation/provenance}
    :reason "fallback under a precommitted failure policy"}

   :submit-cancel-request
   {:evidence #{:lifecycle/profile-id :lifecycle/profile-version
                :target/id :operation/provenance}
    :reason "submission of a cancellation request"}})

(defn- deterministic-evidence-consistent?
  "Cross-field consistency for deterministic-operation evidence. A post-cutpoint
   operation (rejection or certified execution) whose declared :lifecycle/state
   is still :open under the supplied :lifecycle/profile contradicts the
   declared cutpoint and is inconsistent. The :lifecycle/profile is a REQUIRED
   evidence category for those two operations, so the open-window contradiction
   is always checked rather than silently passing."
  [operation evidence]
  (let [profile (:lifecycle/profile evidence)
        state (:lifecycle/state evidence)]
    (if (and (contains? #{:reject-post-cutpoint-cancellation
                          :execute-certified-cancellation} operation)
             (some? profile)
             (some? state))
      (not= :open (:window/state (classify-lifecycle-window profile state)))
      true)))

(defn deterministic-operation-evidence-valid?
  "Check a deterministic operation's supplied evidence against its checklist.

   Fails closed: every required evidence category must be present and non-nil,
   and cross-field consistency must hold (post-cutpoint operations must not be
   executed while the lifecycle window is still :open); an unknown operation is
   invalid.

   Returns
     {:valid? bool
      :operation <kw>
      :missing-evidence [kw]
      :reason <contract reason>}."
  [operation evidence]
  (let [contract (get deterministic-operation-evidence operation)]
    (if-not contract
      {:valid? false :operation operation :missing-evidence []
       :reason :unknown-operation}
      (let [missing (vec (sort (remove #(some? (get evidence %))
                                       (:evidence contract))))
            consistent? (deterministic-evidence-consistent? operation evidence)]
        {:valid? (and (empty? missing) consistent?)
         :operation operation
         :missing-evidence missing
         :consistent? consistent?
         :reason (:reason contract)}))))

(defn deterministic-operation-verified?
  "True only when a deterministic operation carries all required evidence.
   A certificate may exist but never turns a deterministic operation into a
   canonical cancellation decision."
  [operation evidence]
  (:valid? (deterministic-operation-evidence-valid? operation evidence)))

;; ═══════════════════════════════════════════════════════════════════════════
;; Lifecycle profiles (cancellation-window.v1 instantiations)
;; ═══════════════════════════════════════════════════════════════════════════

(def force-authorisation-window
  "Force-authorisation lifecycle profile (contract 2, Option A). Pre-consumption
   states, INCLUDING :reservation-issued, are open (cancellable). A cancellation
   in this window must atomically invalidate the authorisation, release the
   reservation, prevent subsequent consumption, emit a terminal cancellation
   receipt, and preserve evidence linking the released reservation to the
   cancellation certificate. Consumption/effect states are the cutpoint and
   irreversible."
  (lifecycle-window-profile
   {:profile/id :resolution.lifecycle-window/force-authorisation
    :profile/version 1
    :valid-states #{:proposed :signed-eligible :authorisation-built
                    :reservation-issued :consumed :outcome-released
                    :rolled-back-after-consumption
                    :consumption-receipt-terminal}
    :open-states #{:proposed :signed-eligible :authorisation-built
                   :reservation-issued}
    :irreversible-states #{:consumed :outcome-released
                           :rolled-back-after-consumption
                           :consumption-receipt-terminal}
    :blocking-reason-by-state {:consumed :consumed
                               :outcome-released :outcome-released
                               :rolled-back-after-consumption
                               :rolled-back-after-consumption
                               :consumption-receipt-terminal
                               :consumption-receipt-terminal}}))

(def probabilistic-allocation-window
  "Probabilistic-allocation lifecycle profile (contract 3). The cutpoint is the
   AUTHORITATIVE RANDOMNESS REQUEST - :randomness-requested is the FIRST closed
   state, earlier than :consumed. After randomness is requested the round may
   fail, time out, reuse the same seed, or enter a declared fallback, but it
   must not be cancelled and rerolled:
     AllocationCommitted      -> :allocation-committed   (open)
     RandomnessRequested      -> :randomness-requested   (CLOSED - cutpoint)
     RandomnessFulfilled      -> :randomness-fulfilled    (closed)
     ResultProposed           -> :result-proposed         (closed)
     ResultAccepted           -> :result-accepted         (closed)
     ClaimConsumptionStarted  -> :claim-consumption-started (closed)"
  (lifecycle-window-profile
   {:profile/id :prf.lifecycle-window/probabilistic-allocation
    :profile/version 1
    :valid-states #{:allocation-committed :randomness-requested
                    :randomness-fulfilled :result-proposed :result-accepted
                    :claim-consumption-started}
    :open-states #{:allocation-committed}
    :irreversible-states #{:randomness-requested :randomness-fulfilled
                           :result-proposed :result-accepted
                           :claim-consumption-started}
    :blocking-reason-by-state
    {:randomness-requested :authoritative-randomness-requested
     :randomness-fulfilled :randomness-fulfilled
     :result-proposed :result-proposed
     :result-accepted :result-accepted
     :claim-consumption-started :claim-consumption-started}
    :transitions {:allocation-committed      #{:randomness-requested}
                  :randomness-requested      #{:randomness-fulfilled}
                  :randomness-fulfilled      #{:result-proposed}
                  :result-proposed           #{:result-accepted}
                  :result-accepted           #{:claim-consumption-started}
                  :claim-consumption-started #{}}}))

;; Compatibility helpers (force-authorisation profile).

(defn cancellation-window
  "Classify a force-authorisation target state via cancellation-window.v1
   (see `force-authorisation-window`). Returns :open / :closed / :invalid."
  [target-state]
  (:window/state (classify-lifecycle-window force-authorisation-window target-state)))

(defn cancellation-possible?
  "True only when the target is still within its cancellable window. Fails
   closed: unknown or missing states are not cancellable."
  [target-state]
  (= :open (cancellation-window target-state)))

;; ═══════════════════════════════════════════════════════════════════════════
;; Cancellation decision (cancellation-decision.v1)
;; ═══════════════════════════════════════════════════════════════════════════
;;
;; Normative sentence (contract 1): an EXPLICIT cancellation that revokes an
;; otherwise valid authorisation is a canonical contested decision. Deterministic
;; expiry, invalidation, routing, rejection, and execution are not cancellation
;; decisions. This decision composes the generic cancellation-window.v1 primitive
;; under the authority of the canonical three-member profile (D1); the 2-of-3
;; conformance lives HERE, never in the generic window primitive.

(def cancellation-decision-schema
  "Schema version of the canonical cancellation-decision.v1 vocabulary."
  "cancellation-decision.v1")

(defn classify-cancellation
  "Reconcile a cancellation against the canonical three-member profile (D1) and
   the target's lifecycle window (cancellation-window.v1).

   Cancellation authority and cancellation possibility are independent gates:
   the target is changeable only when BOTH the decision profile conforms AND
   the window is :open. A valid 2-of-3 certificate cannot override a closed
   window; an open window does not itself authorise cancellation.

   The window primitive is supplied by a lifecycle profile (`:window` in opts,
   defaulting to `force-authorisation-window`). The lifecycle profile is a
   domain projection of the generic primitive - a force-authorisation state is
   never treated as an allocation state directly.

   A cancellation is assessed against the canonical pinned profile unless
   member/threshold are supplied; per D4 conformance always requires an explicit
   declaration (:profile-id or :named-policy? true).

   opts may carry the decision profile (:member-count :threshold :profile-id
   :named-policy?) and :window (a lifecycle profile; defaults to
   force-authorisation-window).
   target-state is the declared :cancellation/target-state of the target.

   Returns a generic reconciliation result that feeds the cancellation
   certificate:
     {:cancellation/schema-version \"cancellation-decision.v1\"
      :cancellation/profile <class>
      :cancellation/profile-conforming? bool
      :cancellation/lifecycle-profile-id <kw>
      :cancellation/lifecycle-profile-version <int>
      :cancellation/target-state <kw>
      :cancellation/window :open|:closed|:invalid
      :cancellation/window-possible? bool
      :cancellation/possible? bool   ;; DEPRECATED derived view of window-possible?
      :cancellation/blocking-reasons [kw]}.

   This classification owns the WINDOW gate only. It does not assess a
   certificate: `:cancellation/window-possible?` is NOT complete cancellation
   authority. Use `classify-cancellation-gates` / `cancellation-authorised?`
   for the authority, executability, and committability gates."
  [opts target-state]
  (let [{:keys [member-count threshold profile-id named-policy? window]} opts
        lifecycle (or window force-authorisation-window)
        declared (declare-profile
                  {:member-count (or member-count canonical-member-count)
                   :threshold    (or threshold canonical-threshold)
                   :profile-id   profile-id
                   :named-policy? named-policy?})
        profile-conforming? (three-member-standard-conforming? declared)
        win (classify-lifecycle-window lifecycle target-state)
        window-open? (= :open (:window/state win))
        window-possible? (and profile-conforming? window-open?)
        reasons (cond-> (:window/blocking-reasons win)
                  (not profile-conforming?)
                  (conj :non-conforming-decision-profile))]
    {:cancellation/schema-version cancellation-decision-schema
     :cancellation/profile (:class declared)
     :cancellation/profile-conforming? profile-conforming?
     :cancellation/lifecycle-profile-id (:profile/id lifecycle)
     :cancellation/lifecycle-profile-version (:profile/version lifecycle)
     :cancellation/target-state target-state
     :cancellation/window (:window/state win)
     :cancellation/window-possible? window-possible?
     :cancellation/possible? window-possible?
     :cancellation/blocking-reasons (vec reasons)}))

;; ═══════════════════════════════════════════════════════════════════════════
;; Cancellation gates: window / authority / executability / committability
;; ═══════════════════════════════════════════════════════════════════════════
;;
;; The four cancellation predicates are owned by four different layers. They
;; must not be collapsed into a single combined flag:
;;
;;   :cancellation/window-possible?   lifecycle reconciliation
;;     (and profile-conforming? window-open?)
;;   :cancellation/authorised?        three-member certificate
;;     (three-member-standard-conforming? certificate)
;;   :cancellation/executable?        composition
;;     (and window-possible? authorised? current-snapshot-binding-valid?)
;;   :cancellation/committable?       transition race
;;     (and executable? conflict-key-transition-won?)

(defn cancellation-authorised?
  "Authority gate (three-member certificate layer): the certificate conforms to
   the canonical profile. Never reopens or reinterprets lifecycle state.

   certificate — a declared profile map from `declare-profile` (or nil when no
   certificate is available)."
  [certificate]
  (and (some? certificate)
       (three-member-standard-conforming? certificate)))

(defn current-snapshot-binding-valid?
  "Whole-snapshot binding (contract 7): true when a certificate's committed
   cancellation binding still matches the COMPLETE current snapshot. A
   certificate for an earlier open snapshot must not execute after a relevant
   lifecycle change.

   certificate-binding — the committed cancellation decision/binding map.
   current-snapshot    — the current committed snapshot, keyed by the same
                         `cancellation-binding-fields`."
  [certificate-binding current-snapshot]
  (and (cancellation-binding-complete? certificate-binding)
       (= (select-keys certificate-binding cancellation-binding-fields)
          (select-keys current-snapshot cancellation-binding-fields))))

(defn cancellation-executable?
  "Executability gate: window-possible AND authorised AND the certificate still
   binds the complete current cancellation snapshot."
  [window-possible? authorised? snapshot-binding-valid?]
  (and window-possible? authorised? snapshot-binding-valid?))

(defn cancellation-committable?
  "Committability gate: executable AND the authoritative transition race over
   `cancellation-conflict-key` was won.

   This layer consumes a supplied, verified transition result. It does NOT
   claim durable cross-process atomicity: JVM-local compare-and-transition is
   not durable coordination."
  [executable? transition-won?]
  (and executable? transition-won?))

(defn classify-cancellation-gates
  "Compose the four cancellation predicates from independently owned gates.

   inputs:
     :decision-opts           profile opts exactly as `classify-cancellation`
                              (:member-count :threshold :profile-id :named-policy?)
     :target-state            lifecycle target state
     :certificate             declared three-member certificate profile (or nil)
     :snapshot-binding-valid? bool — contract 7 whole-snapshot binding, verified
                              by the caller against the current snapshot
     :transition-won?         bool — verified conflict-key race result
     :window                  optional lifecycle profile

   Ownership:
     window-possible?  lifecycle reconciliation (recomputed here);
     authorised?       three-member certificate;
     executable?       both gates + current-snapshot binding;
     committable?      executable + authoritative transition race result.

   Returns
     {:cancellation/window-possible? bool
      :cancellation/authorised? bool
      :cancellation/executable? bool
      :cancellation/committable? bool
      :cancellation/snapshot-binding-valid? bool
      :cancellation/transition-won? bool
      :cancellation/window ...
      :cancellation/profile-conforming? bool
      :cancellation/blocking-reasons [kw]}."
  [{:keys [decision-opts target-state certificate snapshot-binding-valid?
           transition-won? window]}]
  (let [classification (classify-cancellation
                        (assoc decision-opts :window window)
                        target-state)
        window-possible? (:cancellation/window-possible? classification)
        authorised? (cancellation-authorised? certificate)
        binding-valid? (boolean snapshot-binding-valid?)
        executable? (cancellation-executable? window-possible? authorised?
                                              binding-valid?)
        race-won? (boolean transition-won?)
        committable? (cancellation-committable? executable? race-won?)
        reasons (cond-> (:cancellation/blocking-reasons classification)
                  (not authorised?) (conj :certificate-not-authorised)
                  (not binding-valid?) (conj :snapshot-binding-stale)
                  (and executable? (not race-won?)) (conj :transition-race-lost))]
    {:cancellation/window-possible? window-possible?
     :cancellation/authorised? authorised?
     :cancellation/executable? executable?
     :cancellation/committable? committable?
     :cancellation/snapshot-binding-valid? binding-valid?
     :cancellation/transition-won? race-won?
     :cancellation/window (:cancellation/window classification)
     :cancellation/profile-conforming? (:cancellation/profile-conforming? classification)
     :cancellation/blocking-reasons (vec reasons)}))

(defn- window-assertion-fields
  [schema window possible? blocking]
  {:assertion/id :cancellation/window-respected
   :decision-schema schema
   :cancellation/window window
   :cancellation/possible? possible?
   :blocking-reasons blocking})

(defn cancellation-window-assertion
  "Build the certificate-ready assertion.

   Contract 8: only recomputation from committed evidence may claim
   `:assurance :independent-replay`. Passing a precomputed classification only
   yields a structural check (`:assurance :structural-check`), not replay.

   `:independent-replay` means RECOMPUTATION independence: the classification
   is recomputed from committed evidence. It establishes exactly one of the
   five process properties — recomputable replay. It does NOT establish process
   separation, implementation independence, state independence, or transition
   atomicity. Do not read 'independent' as any of those stronger claims.

   - With a committed-evidence map (identified by a :target-evidence key), the
     domain state, window classification, possibility, and blocking reasons are
     recomputed from evidence (:assurance :independent-replay). Evidence keys:
       :target-evidence committed-target-evidence
       :lifecycle-profile committed-profile
       :domain-projection projection       ;; state-evidence -> lifecycle-state
       :decision-opts {...}                ;; decision profile for conformance
   - With a `classify-cancellation` result (has :cancellation/window), the
     assertion is a structural check only (:assurance :structural-check); status
     passes when the window was respected (:open/:closed), fails on :invalid.

   Returns
     {:assertion/id :cancellation/window-respected
      :status :passing | :failing
      :assurance :independent-replay | :structural-check
      :decision-schema \"cancellation-decision.v1\"
      :cancellation/window ...
      :cancellation/possible? ...
      :blocking-reasons [kw]
      :evidence/derived-state ...   (replay path only)}"
  [input]
  (let [{:keys [target-evidence lifecycle-profile domain-projection decision-opts]} input
        replay? (contains? input :target-evidence)
        result (if replay?
                 (let [target-state (domain-projection target-evidence)
                       r (classify-cancellation
                          (assoc decision-opts :window lifecycle-profile)
                          target-state)]
                   (assoc r :evidence/derived-state target-state))
                 input)
        window (:cancellation/window result)]
    (merge (window-assertion-fields (:cancellation/schema-version result)
                                    window (:cancellation/possible? result)
                                    (:cancellation/blocking-reasons result))
           (if replay?
             {:status (if (= :invalid window) :failing :passing)
              :assurance :independent-replay}
             {:status (if (and (some? input) (= :invalid window)) :failing :passing)
              :assurance :structural-check})
           (when replay?
             {:evidence/derived-state (:evidence/derived-state result)}))))
