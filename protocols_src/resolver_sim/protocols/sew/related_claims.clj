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
            [resolver-sim.hash.canonical :as hash]
            [resolver-sim.workflow-group :as wg]
            [resolver-sim.evidence.capture :as cap]
            [resolver-sim.util.attribution :as attr]))

(def ^:const related-claims-domain
  "V1 domain tag — retained for verifying pre-V2 artifacts."
  "related-claims.v1")

(def ^:const related-claims-domain-v2
  "V2 domain tag — commits members AND authenticated creator provenance."
  "related-claims.v2")

(def ^:const related-claims-version 1)

(def ^:const related-claims-version-v2 2)

(def ^:const default-semantics
  "Default relationship semantics set for v1.
   :audit-only — no settlement coupling, purely descriptive."
  #{:audit-only})

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

;; ---------------------------------------------------------------------------
;; Hash
;; ---------------------------------------------------------------------------

(defn related-claims-hash-v1
  "V1 canonical hash (members only) — retained as a pure reference for verifying
   pre-V2 artifacts. No authoritative V1 artifacts are known to exist; this is a
   reference-only function, NOT the production commitment."
  [members]
  (hash/domain-hash related-claims-domain
                    (vec (sort-by (juxt :workflow/id :claim/kind)
                                  (for [m members]
                                    (select-keys m [:claim/kind :workflow/id :claim/scope-hash]))))))

(defn related-claims-hash
  "V2 canonical hash for a related-claims relationship. Commits sorted members
   PLUS the authenticated creator provenance, so the creator is hash-bound and
   cannot be presented as authenticated merely by attaching metadata outside the
   committed preimage. V1 and V2 are domain-separated (related-claims.v1 vs
   related-claims.v2) and cannot collide."
  [members creator-provenance]
  (hash/domain-hash related-claims-domain-v2
                    {:related-claims/schema-version "related-claims.v2"
                     :relationship/members
                     (vec (sort-by (juxt :workflow/id :claim/kind)
                                   (for [m members]
                                     (select-keys m [:claim/kind :workflow/id :claim/scope-hash]))))
                     :relationship/creator-provenance (or creator-provenance {})}))

;; ---------------------------------------------------------------------------
;; Accessors
;; ---------------------------------------------------------------------------

(defn get-related-claims
  "Lookup a relationship record by id. Returns nil if not found."
  [world relationship-id]
  (get-in world [:related-claims relationship-id]))

(defn related-claims-active?
  "True when the relationship exists and has :active status."
  [world relationship-id]
  (let [rel (get-related-claims world relationship-id)]
    (and rel (= :active (:relationship/status rel)))))

(defn related-claims-member-hash
  "Canonical hash identifying one related-claims member by {claim/kind, workflow/id}.
   Delegates to the framework-neutral workflow-group member hash
   (domain WORKFLOW_GROUP_MEMBER_V1), projecting the member's {claim/kind,
   workflow/id} onto the generic workflow-group member identity. This is distinct
   from the `force-authorisation-scope` hash: it identifies the member identity,
   not a specific held-accounting adjustment scope."
  [member]
  (wg/workflow-group-member-hash
   (wg/workflow-group-member (:claim/kind member) (:workflow/id member))))

(defn relationship-member?
  "True when `member` ({claim/kind, workflow/id}) is present in `relationship`'s
   member set. Delegates to the canonical workflow-group membership predicate:
   a member is identified by its normalized workflow-id plus claim kind,
   independent of any adjustment scope hash."
  [relationship member]
  (wg/workflow-group-member?
   (map (fn [m] (wg/workflow-group-member (:claim/kind m) (:workflow/id m)))
        (:relationship/members relationship))
   (wg/workflow-group-member (:claim/kind member) (:workflow/id member))))

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
  [semantics]
  (when-not (= default-semantics semantics)
    (throw (ex-info "unsupported relationship semantics — v1 supports exactly #{:audit-only}"
                    {:type :invalid-related-claims
                     :error :unsupported-semantics
                     :semantics semantics
                     :allowed default-semantics}))))

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
  (let [wf-ids (set (for [m members
                          :when (= :sew/workflow (:claim/kind m))]
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
  "Canonical projection of the creator provenance committed by the V2 hash.
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

(defn authenticated-related-claims?
  "STRICT authenticated predicate. True only for a record produced by the
   governance-gated action in restricted/address-bound mode, with creator
   provenance committed by its hash. Legacy (:role-declared), open, and direct
   (:unauthenticated) records do NOT satisfy this predicate, even with creator
   metadata attached outside the hash."
  [relationship]
  (and (= :address-bound (:relationship/assurance relationship))
       (some? (:relationship/creator-provenance relationship))
       (= :address-bound
          (get-in relationship [:relationship/creator-provenance :authorization/assurance]))))

;; ---------------------------------------------------------------------------
;; Builder
;; ---------------------------------------------------------------------------

(defn- build-related-claims-record
  "Construct a V2 related-claims record map without storing it.
   `assurance` is the derived assurance classification (:address-bound,
   :role-declared, :open, or :unauthenticated). Authenticated is true ONLY for
   :address-bound."
  [world type members semantics reason creator-provenance created-by created-at-step assurance]
  (let [wf-members (for [m members]
                     (let [scope-hash (or (:claim/scope-hash m)
                                         (related-claims-member-hash m))]
                       (-> m
                           (assoc :claim/scope-hash scope-hash)
                           (update :workflow/id t/normalize-workflow-id))))
        relationship-id (get world :next-related-claim-id 0)
        rel-hash (related-claims-hash wf-members creator-provenance)]
    {:related-claims/version related-claims-version-v2
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
     :relationship/hash rel-hash}))

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

(defn create-related-claims-with-assurance!
  "Authenticated-path builder, intended to be invoked ONLY by the governance-gated
   grant-related-claims action. `assurance` is derived from governance-check
   (:address-bound restricted, :role-declared legacy, :open open). This is the
   only path that produces an :address-bound (authenticated) record. External
   callers should use create-related-claims!, which rejects authentication
   overrides and always emits :unauthenticated."
  [world {:keys [type members semantics reason created-by created-at-step] :as opts}
   assurance]
  (try
    (validate-relationship-type! type)
    (validate-members-nonempty! members)
    (validate-semantics! semantics)
    (validate-claim-kinds! members)
    (validate-members-exist! world members)
    (validate-no-duplicate-members! world members)
    (validate-creator-provenance! created-by)
    (let [creator-provenance (build-creator-provenance
                              (assoc created-by :authorization/assurance assurance))
          record (build-related-claims-record world type members semantics reason
                                              creator-provenance created-by
                                              created-at-step assurance)
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
  "Create a new related-claims relationship (V2) via DIRECT construction.
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
  [world {:keys [type members semantics reason created-by created-at-step] :as opts
          :or {semantics default-semantics
               reason "unspecified"
               created-at-step 0}}]
  (try
    (validate-no-auth-override! opts)
    (validate-relationship-type! type)
    (validate-members-nonempty! members)
    (validate-semantics! semantics)
    (validate-claim-kinds! members)
    (validate-members-exist! world members)
    (validate-no-duplicate-members! world members)
    (validate-creator-provenance! created-by)
    (let [creator-provenance (build-creator-provenance created-by)
          record (build-related-claims-record world type members semantics reason
                                              creator-provenance created-by
                                              created-at-step :unauthenticated)
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
