(ns resolver-sim.protocols.sew.related-claims
  "Related claims registry: explicit immutable relationship groups for
   audit, batch force-authorisation, and evidence linkage.

   A related-claims group is created once with an immutable member set.
   Membership cannot be mutated — versioned relationships must create a
   new relationship-id for any membership change.

   v1 semantics: #{:audit-only} — no settlement coupling.
   Future semantics may add :batch-force-authorisation, :shared-evidence.

   Relationship creation is a governance/control-plane operation: it is
   intentionally not exposed as a SewProtocol state-machine action. Protocol
   execution may consume relationships (via related-claims force-authorisation),
   but scenario actions do not create them.

   See docs/architecture/HELD_CUSTODY_ACCOUNTING_AND_FORCE_AUTHORISATION.md
   (\"Related claims\") and docs/architecture/FRAUD_INCIDENT_LIABILITY_VERSIONING.md
   for the full design rationale."
  (:require [resolver-sim.protocols.sew.types :as t]
            [resolver-sim.related-claims :as related-claims]
            [resolver-sim.workflow-group :as wg]
            [resolver-sim.evidence.capture :as cap]
            [resolver-sim.util.attribution :as attr]))

(def related-claims-domain related-claims/related-claims-domain)
(def related-claims-domain-v2 related-claims/related-claims-domain-v2)
(def related-claims-domain-v3 related-claims/related-claims-domain-v3)
(def related-claims-domain-v4 related-claims/related-claims-domain-v4)
(def related-claims-version related-claims/related-claims-version)
(def related-claims-version-v2 related-claims/related-claims-version-v2)
(def related-claims-version-v3 related-claims/related-claims-version-v3)
(def related-claims-version-v4 related-claims/related-claims-version-v4)
(def default-semantics related-claims/default-semantics)
(def shared-resolution-semantics related-claims/shared-resolution-semantics)

(def ^:const allowed-relationship-types
  "Controlled vocabulary of relationship types."
  #{:same-incident
    :same-counterparty
    :same-evidence
    :governance-batch
    :force-authorisation-batch
    :resolver-batch
    :appeal-batch})

(def ^:const allowed-claim-kinds
  "Claim kinds that can appear in a relationship membership."
  #{:sew/workflow})

(def related-claims-hash-v1 related-claims/related-claims-hash-v1)
(def related-claims-hash-v2 related-claims/related-claims-hash-v2)
(def related-claims-hash-v3 related-claims/related-claims-hash-v3)
(def related-claims-hash-v4 related-claims/related-claims-hash-v4)
(def related-claims-hash related-claims/related-claims-hash)
(def verify-related-claims-hash related-claims/verify-related-claims-hash)

;; ---------------------------------------------------------------------------
;; Accessors
;; ---------------------------------------------------------------------------

(declare verify-related-claims-hash)

(defn get-related-claims
  "Lookup a relationship record by id. Returns nil if not found."
  [world relationship-id]
  (get-in world [:related-claims relationship-id]))

(defn related-claims-active?
  "True when the relationship exists and has :active status."
  [world relationship-id]
  (let [rel (get-related-claims world relationship-id)]
    (and rel (= :active (:relationship/status rel)))))

(def related-claims-member-hash related-claims/related-claims-member-hash)

(def relationship-member? related-claims/relationship-member?)

(def related-claim-member? related-claims/related-claim-member?)

(def related-claims-required-members related-claims/related-claims-required-members)

(def related-claims-consistency related-claims/related-claims-consistency)

(def shared-evidence-valid? related-claims/shared-evidence-valid?)

(def claim-acceptance related-claims/claim-acceptance)

;; ---------------------------------------------------------------------------
;; Validation
;; ---------------------------------------------------------------------------

(defn- validate-relationship-type!
  [type]
  (when-not (contains? allowed-relationship-types type)
    (throw (ex-info "invalid relationship type"
                    {:type :invalid-related-claims
                     :relationship/type type
                     :allowed allowed-relationship-types}))))

(defn- validate-members-nonempty!
  [members]
  (when-not (seq members)
    (throw (ex-info "related-claims relationship requires at least one member"
                    {:type :invalid-related-claims
                     :error :empty-members
                     :members members}))))

(defn- validate-semantics!
  [semantics incident-root shared-evidence-root resolution-policy-root]
  (cond
    (= default-semantics semantics) nil
    (= shared-resolution-semantics semantics)
    (when-not (every? string? [incident-root shared-evidence-root resolution-policy-root])
      (throw (ex-info "shared-resolution requires incident, evidence, and policy roots"
                      {:type :invalid-related-claims
                       :error :shared-resolution-basis-invalid})))
    :else
    (throw (ex-info "unsupported relationship semantics"
                    {:type :invalid-related-claims
                     :error :unsupported-semantics
                     :semantics semantics
                     :allowed #{default-semantics shared-resolution-semantics}}))))

(defn- validate-claim-kinds!
  [members]
  (doseq [m members]
    (when-not (contains? allowed-claim-kinds (:claim/kind m))
      (throw (ex-info "invalid claim kind in relationship member"
                      {:type :invalid-related-claims
                       :member m
                       :allowed allowed-claim-kinds})))))

(defn- validate-members-exist!
  [world members]
  (doseq [m members]
    (case (:claim/kind m)
      :sew/workflow
      (when-not (t/valid-workflow-id? world (:workflow/id m))
        (throw (ex-info "relationship member workflow does not exist"
                        {:type :invalid-related-claims
                         :member m})))
      nil)))

(defn- validate-no-duplicate-members!
  [world members]
  ;; Intra-group duplicate rule: no duplicate member identity within the group
  ;; (canonical workflow-group structural check).
  (when-not (wg/valid-workflow-group-members?
             (map (fn [m] (wg/workflow-group-member (:claim/kind m) (:workflow/id m)))
                  members))
    (throw (ex-info "duplicate workflow member in relationship"
                    {:type :invalid-related-claims
                     :members members})))
  ;; Cross-relationship global rule (consumer-specific): no workflow-id already
  ;; in another active relationship, regardless of type.
  ;; Terminal members in the new relationship are excluded: a finalized workflow
  ;; is functionally complete and does not benefit from a new relationship, but
  ;; it should not be blocked by a stale active relationship that has not yet
  ;; been archived.
  (let [wf-ids (set (for [m members
                          :when (= :sew/workflow (:claim/kind m))
                          :when (not (t/terminal-state? world (:workflow/id m)))]
                      (:workflow/id m)))]
    (doseq [[rel-id rel] (:related-claims world {})]
      (when (= :active (:relationship/status rel))
        (let [existing-wf-ids (set (for [m (:relationship/members rel)
                                         :when (= :sew/workflow (:claim/kind m))]
                                     (:workflow/id m)))]
          (doseq [wf-id wf-ids]
            (when (contains? existing-wf-ids wf-id)
              (throw (ex-info "workflow-id already in an active relationship"
                              {:type :invalid-related-claims
                               :workflow/id wf-id
                               :existing-relationship-id rel-id})))))))))

;; ---------------------------------------------------------------------------
;; Creator provenance / authentication
;; ---------------------------------------------------------------------------

(defn- validate-creator-provenance!
  "Require an explicit, well-formed creator provenance on every related-claims
   record. There is no hardcoded default creator. Note this validates WELL-FORMED
   provenance, not authenticity — only the governance-gated action sets
   :relationship/authenticated? true."
  [created-by]
  (when-not (and (map? created-by)
                 (contains? created-by :actor/type)
                 (string? (:actor/address created-by))
                 (not= "" (:actor/address created-by)))
    (throw (ex-info "related-claims requires explicit, well-formed creator provenance"
                    {:type :invalid-related-claims
                     :error :missing-creator-provenance
                     :created-by created-by}))))

(defn- build-creator-provenance
  "Canonical projection of the creator provenance committed by the V2 and V3 hashes.
   Includes :authorization/assurance so the assurance classification is hash-bound."
  [created-by]
  (select-keys created-by
               [:actor/type :actor/address
                :authorization/type :authorization/check :authorization/source
                :authorization/governance-mode :authorization/authentication-mode
                :authorization/assurance
                :authorization/address-bound? :authorization/registry-verified?
                :authorization/provenance]))

(def related-claims-assurances
  "Assurance classification for a related-claims relationship.
     :address-bound  — governance action in restricted mode: role + configured-address match.
     :role-declared  — governance action in explicit legacy mode (scenario-declared role only).
     :open           — governance action in open/full mode (any resolved actor).
     :unauthenticated— direct builder construction (never authenticated)."
  #{:address-bound :role-declared :open :unauthenticated})

(defn- configured-governance-address
  "Return the configured governance address from an execution context, or nil
   for absent/malformed configuration. Authentication must fail closed in either
   case; action dispatch performs the user-facing configuration validation."
  [context]
  (let [identity (:governance-identity context)]
    (cond
      (string? identity) (when (seq identity) identity)
      (map? identity) (let [address (:governance/address identity)]
                        (when (and (string? address) (seq address)) address))
      :else nil)))

(defn verify-authenticated-related-claims
  "Verify an address-bound V3 relationship against its active execution context.
   V1 and V2 records are still hash-verifiable through
   `verify-related-claims-hash`, but cannot authenticate because their contracts
   do not commit the full current relationship semantics."
  [context relationship]
  (let [creator-provenance (:relationship/creator-provenance relationship)
        nested-provenance (:authorization/provenance creator-provenance)
        configured-address (configured-governance-address context)
        hash-verification (try
                            (verify-related-claims-hash relationship)
                            (catch Exception _ {:valid? false
                                                :reasons #{:relationship-hash-mismatch}}))
        reasons (cond-> (set (:reasons hash-verification))
                  (not (map? relationship)) (conj :invalid-relationship)
                  (not (contains? #{related-claims-version-v3 related-claims-version-v4}
                                  (:related-claims/version relationship)))
                  (conj :unsupported-relationship-version)
                  (not= :restricted (keyword (or (:governance-mode context) :restricted)))
                  (conj :governance-mode-not-restricted)
                  (nil? configured-address) (conj :governance-identity-not-configured)
                  (not= :address-bound (:relationship/assurance relationship))
                  (conj :relationship-assurance-not-address-bound)
                  (not (true? (:relationship/authenticated? relationship)))
                  (conj :relationship-not-authenticated)
                  (not (map? creator-provenance)) (conj :missing-creator-provenance)
                  (and (map? creator-provenance)
                       (not= creator-provenance (build-creator-provenance creator-provenance)))
                  (conj :noncanonical-creator-provenance)
                  (not= :governance (:actor/type creator-provenance))
                  (conj :invalid-creator-type)
                  (not= :governance (:authorization/type creator-provenance))
                  (conj :invalid-authorization-type)
                  (not= :with-governance-actor (:authorization/check creator-provenance))
                  (conj :invalid-authorization-check)
                  (not= :governance (:authorization/source creator-provenance))
                  (conj :invalid-authorization-source)
                  (not= :restricted (:authorization/governance-mode creator-provenance))
                  (conj :creator-governance-mode-not-restricted)
                  (not= :address-bound (:authorization/authentication-mode creator-provenance))
                  (conj :creator-authentication-mode-not-address-bound)
                  (not= :address-bound (:authorization/assurance creator-provenance))
                  (conj :creator-assurance-not-address-bound)
                  (not (true? (:authorization/address-bound? creator-provenance)))
                  (conj :creator-address-not-bound)
                  (not= configured-address (:actor/address creator-provenance))
                  (conj :creator-address-mismatch)
                  (not (map? nested-provenance)) (conj :missing-authorization-provenance)
                  (not= (:actor/address creator-provenance)
                        (:authorization/actor-address nested-provenance))
                  (conj :provenance-actor-address-mismatch)
                  (not= configured-address
                        (:authorization/configured-governance-address nested-provenance))
                  (conj :provenance-configured-address-mismatch)
                  (not= :restricted (:authorization/governance-mode nested-provenance))
                  (conj :provenance-governance-mode-not-restricted)
                  (not= :address-bound (:authorization/authentication-mode nested-provenance))
                  (conj :provenance-authentication-mode-not-address-bound)
                  (not= :address-bound (:authorization/assurance nested-provenance))
                  (conj :provenance-assurance-not-address-bound)
                  (not (true? (:authorization/address-bound? nested-provenance)))
                  (conj :provenance-address-not-bound))]
    {:valid? (empty? reasons)
     :reasons reasons}))

(defn authenticated-related-claims?
  "True only when `relationship` verifies against a supplied execution context.
   The one-argument compatibility form fails closed because it has no configured
   governance identity with which to validate the creator-address binding."
  ([relationship]
   (authenticated-related-claims? nil relationship))
  ([context relationship]
   (:valid? (verify-authenticated-related-claims context relationship))))

;; ---------------------------------------------------------------------------
;; Builder
;; ---------------------------------------------------------------------------

(defn- build-related-claims-record
  "Construct a V3 related-claims record map without storing it.
   `assurance` is the derived assurance classification (:address-bound,
   :role-declared, :open, or :unauthenticated). Authenticated is true ONLY for
   :address-bound."
  [world type members semantics reason creator-provenance created-by created-at-step assurance
   incident-root shared-evidence-root resolution-policy-root]
  (let [wf-members (for [m members]
                     (let [scope-hash (or (:claim/scope-hash m)
                                          (related-claims-member-hash m))]
                       (-> m
                           (assoc :claim/scope-hash scope-hash)
                           (update :workflow/id t/normalize-workflow-id))))
        relationship-id (get world :next-related-claim-id 0)
        semantics (or semantics default-semantics)
        v4? (= shared-resolution-semantics semantics)
        rel-hash (if v4?
                   (related-claims-hash-v4 wf-members creator-provenance semantics
                                           incident-root shared-evidence-root resolution-policy-root)
                   (related-claims-hash-v3 wf-members creator-provenance semantics))]
    (cond-> {:related-claims/version (if v4? related-claims-version-v4 related-claims-version-v3)
             :relationship/id relationship-id
             :relationship/type type
             :relationship/status :active
             :relationship/members wf-members
             :relationship/semantics (or semantics default-semantics)
             :relationship/reason reason
             :relationship/creator-provenance creator-provenance
             :relationship/assurance (or assurance :unauthenticated)
             :relationship/authenticated? (= :address-bound (or assurance :unauthenticated))
             :created-by created-by
             :created-at-step created-at-step
             :relationship/hash rel-hash}
      v4? (assoc :incident/root incident-root
                 :shared-evidence/root shared-evidence-root
                 :resolution-policy/root resolution-policy-root))))

(defn- validate-no-auth-override!
  "Direct construction may never claim authentication. Reject any caller-supplied
   authentication/assurance override."
  [opts]
  (when (or (some? (:authenticated? opts))
            (some? (:assurance opts)))
    (throw (ex-info "caller-supplied authentication/assurance override rejected on direct construction"
                    {:type :invalid-related-claims
                     :error :related-claims-auth-override-rejected
                     :opts (select-keys opts [:authenticated? :assurance])}))))

(defn- create-related-claims-with-assurance!
  "Authenticated-path V3 builder, intended to be invoked ONLY by the governance-gated
   grant-related-claims action. `assurance` is derived from governance-check
   (:address-bound restricted, :role-declared legacy, :open open). This is the
   only path that produces an :address-bound (authenticated) record. External
   callers should use create-related-claims!, which rejects authentication
   overrides and always emits :unauthenticated."
  [world {:keys [type members semantics reason created-by created-at-step incident-root
                 shared-evidence-root resolution-policy-root] :as opts}
   assurance]
  (try
    (validate-relationship-type! type)
    (validate-members-nonempty! members)
    (validate-semantics! semantics incident-root shared-evidence-root resolution-policy-root)
    (validate-claim-kinds! members)
    (validate-members-exist! world members)
    (validate-no-duplicate-members! world members)
    (validate-creator-provenance! created-by)
    (let [creator-provenance (build-creator-provenance
                              (assoc created-by :authorization/assurance assurance))
          record (build-related-claims-record world type members semantics reason
                                              creator-provenance created-by created-at-step assurance
                                              incident-root shared-evidence-root resolution-policy-root)
          rel-id (:relationship/id record)
          world' (-> world
                     (assoc-in [:related-claims rel-id] record)
                     (update :next-related-claim-id inc))]
      (attr/with-attribution {:subject/type :related-claims
                              :subject/id rel-id
                              :action/type :related-claims/create
                              :evidence/reason :related-claims-created}
        (cap/capture-event-evidence!
         :related-claims-created
         {:related-claims/before {:related-claims-count (count (:related-claims world {}))}}
         {:related-claims/after {:related-claims-count (count (:related-claims world' {}))}}
         {:related-claims/id rel-id
          :related-claims/type type
          :related-claims/members-count (count members)
          :related-claims/reason reason
          :related-claims/assurance assurance}))
      (assoc (t/ok world')
             :relationship-id rel-id
             :relationship record))
    (catch Exception e
      (t/fail (or (:type (ex-data e)) :related-claims-invalid)))))

;; ---------------------------------------------------------------------------
;; Action
;; ---------------------------------------------------------------------------

(defn create-related-claims!
  "Create a new related-claims relationship (V3) via DIRECT construction.
   Validates members exist, no duplicates, type is allowed, membership is
   non-empty, semantics are exactly v1's #{:audit-only}, and an explicit
   well-formed `created-by` creator provenance is supplied (no hardcoded default).

   DIRECT construction is unconditionally UNAUTHENTICATED: any caller-supplied
   :authenticated? / :assurance override is REJECTED. Only the governance-gated
   grant-related-claims action can produce an authenticated (:address-bound)
   record, via the internal authenticated builder.

   opts:
     :type         — keyword from allowed-relationship-types
     :members      — [{:claim/kind :sew/workflow :workflow/id N, :claim/scope-hash optional}]
     :semantics    — must be exactly #{:audit-only} (v1); anything else is rejected
     :reason       — string describing why
     :created-by   — explicit {:actor/type ... :actor/address \"0x...\"} (required)
     :created-at-step — integer step number"
  [world {:keys [type members semantics reason created-by created-at-step incident-root
                 shared-evidence-root resolution-policy-root] :as opts
          :or {semantics default-semantics
               reason "unspecified"
               created-at-step 0}}]
  (try
    (validate-no-auth-override! opts)
    (validate-relationship-type! type)
    (validate-members-nonempty! members)
    (validate-semantics! semantics incident-root shared-evidence-root resolution-policy-root)
    (validate-claim-kinds! members)
    (validate-members-exist! world members)
    (validate-no-duplicate-members! world members)
    (validate-creator-provenance! created-by)
    (let [creator-provenance (build-creator-provenance created-by)
          record (build-related-claims-record world type members semantics reason
                                              creator-provenance created-by created-at-step :unauthenticated
                                              incident-root shared-evidence-root resolution-policy-root)
          rel-id (:relationship/id record)
          world' (-> world
                     (assoc-in [:related-claims rel-id] record)
                     (update :next-related-claim-id inc))]
      (attr/with-attribution {:subject/type :related-claims
                              :subject/id rel-id
                              :action/type :related-claims/create
                              :evidence/reason :related-claims-created}
        (cap/capture-event-evidence!
         :related-claims-created
         {:related-claims/before {:related-claims-count (count (:related-claims world {}))}}
         {:related-claims/after {:related-claims-count (count (:related-claims world' {}))}}
         {:related-claims/id rel-id
          :related-claims/type type
          :related-claims/members-count (count members)
          :related-claims/reason reason}))
      (assoc (t/ok world')
             :relationship-id rel-id
             :relationship record))
    (catch Exception e
      (t/fail (or (:type (ex-data e)) :related-claims-invalid)))))

;; ---------------------------------------------------------------------------
;; Lifecycle — archive
;; ---------------------------------------------------------------------------

(defn all-members-terminal?
  "True when every member workflow-id in the relationship is in a terminal state."
  [world relationship]
  (every? #(t/terminal-state? world (:workflow/id %))
          (:relationship/members relationship)))

(defn archive-relationship-if-complete
  "Transition relationship to :archived when all members are terminal. Idempotent."
  [world relationship-id]
  (let [rel (get-related-claims world relationship-id)]
    (if (and rel
             (= :active (:relationship/status rel))
             (all-members-terminal? world rel))
      (assoc-in world [:related-claims relationship-id :relationship/status] :archived)
      world)))

(defn archive-stale-relationships
  "Scan all active relationships and archive those with all-terminal members."
  [world]
  (reduce (fn [w [rel-id _]]
            (archive-relationship-if-complete w rel-id))
          world
          (:related-claims world {})))

;; ---------------------------------------------------------------------------
;; Query
;; ---------------------------------------------------------------------------

(defn find-related-claims-for-workflow
  "Find all active relationship IDs that contain the given workflow-id.
   Return contract: a seq of relationship-id keywords (possibly empty).
   Uses the canonical workflow-group membership predicate."
  [world workflow-id]
  (let [member {:claim/kind :sew/workflow :workflow/id workflow-id}]
    (keep (fn [[rel-id rel]]
            (when (and (= :active (:relationship/status rel))
                       (relationship-member? rel member))
              rel-id))
          (:related-claims world {}))))

(defn find-related-claims-for-workflows
  "Find all active relationship IDs that contain any of the given workflow-ids.
   Return contract: a set of relationship-id keywords (possibly empty)."
  [world workflow-ids]
  (set (mapcat #(find-related-claims-for-workflow world %) workflow-ids)))
