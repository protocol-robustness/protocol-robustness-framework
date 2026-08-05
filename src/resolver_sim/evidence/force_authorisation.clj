(ns resolver-sim.evidence.force-authorisation
  "Evidence contract definitions for force-authorisation lifecycle.

   Defines the structure and validation of forensic evidence for
   force-authorisation grant, execution, consumption, and custody linkage.
   Protocol-independent: operates on evidence maps and returns validation maps.

   BOUNDARY GUARD — This namespace MUST NOT import or depend on:
     - resolver-sim.protocols.sew
     - any form under protocols_src/
     - benchmarks/packs/sew/"
  (:require [clojure.string :as str]
            [resolver-sim.hash.canonical :as hash]
            [resolver-sim.assurance.force-authorisation :as fa]))

(declare valid-force-auth-add-held?
         valid-force-auth-add-held-v2?
         v1-summary-shape-valid?
         summary-body-v2
         summary-body-v1
         admit-members!
         check-aggregate)

(def scope-schema
  "Canonical keys that a force-authorisation scope map must contain."
  #{:authorization/id
    :authorization/type
    :held/direction
    :token
    :amount
    :held/account
    :owner/address
    :held/reason
    :held/workflow-id})

(def evidence-envelope-schema
  "Canonical keys that a forensic force-authorisation evidence envelope
   must contain for audit/invariant processing."
  #{:evidence/kind
    :evidence/auth-id
    :evidence/grant-time
    :evidence/scope-hash
    :evidence/execution-time
    :evidence/consumption-time
    :evidence/held-adjustment-id})

(defn valid-scope?
  "True when scope-map contains all required scope-schema keys."
  [scope-map]
  (every? (fn [k] (contains? scope-map k)) scope-schema))

(defn scope-matches?
  "True when the scope declared in evidence matches the authorization record."
  [evidence authorization]
  (and (= (:evidence/auth-id evidence) (:authorization/id authorization))
       (= (:evidence/scope-hash evidence) (:authorization/scope-hash authorization))))

(defn valid-envelope?
  "True when the evidence envelope contains all required keys."
  [envelope]
  (every? (fn [k] (contains? envelope k)) evidence-envelope-schema))

(defn grant-before-execution?
  "True when the evidence grant timestamp precedes the execution timestamp."
  [envelope]
  (if (and (:evidence/grant-time envelope) (:evidence/execution-time envelope))
    (<= (:evidence/grant-time envelope) (:evidence/execution-time envelope))
    false))

(defn execution-before-consumption?
  "True when execution precedes consumption (or they are simultaneous)."
  [envelope]
  (if (and (:evidence/execution-time envelope) (:evidence/consumption-time envelope))
    (<= (:evidence/execution-time envelope) (:evidence/consumption-time envelope))
    false))

;; ═══════════════════════════════════════════════════════════════════════════
;; Versioned, content-addressed force-authorisation evidence file-artifacts
;; ═══════════════════════════════════════════════════════════════════════════
;;
;; Three derived, content-addressed evidence artifacts. Each commits its
;; fields under a content hash and an exact preimage so an independent
;; consumer can re-verify it without re-deriving the analysis.
;;   - :force-auth-add-held        a force-authorised add-held custody mutation
;;   - :force-auth-lifecycle       the force-authorisation lifecycle verification
;;   - :force-auth-lifecycle-summary  a counts/consistency summary of the lifecycle
;;
;; All verification booleans are recomputed by the builder, never trusted.
;; Missing or malformed evidence is non-passing.
;; Protocol-independent: operates on plain data maps.

(def add-held-schema-version
  "Canonical schema version for a force-auth-add-held evidence artifact."
  "force-auth-add-held.v1")

(def add-held-verifier-id
  "Canonical verifier identifier for a force-auth-add-held evidence artifact."
  "force-auth-add-held-verifier.v1")

(def add-held-v2-schema-version
  "Canonical schema version for a force-auth-add-held.v2 member that commits its
   canonical scope projection so the scope binding is independently re-derivable."
  "force-auth-add-held.v2")

(def add-held-v2-verifier-id
  "Canonical verifier identifier for a force-auth-add-held.v2 member."
  "force-auth-add-held-verifier.v2")

(def add-held-scope-derivation-id
  "Algorithm/version identifier for the :authorization/scope-hash commitment
   (:authorization/scope-derivation on v2 members)."
  "force-authorisation-scope-hash.v1")

(def ^:private lifecycle-schema-version "force-auth-lifecycle.v1")
(def ^:private lifecycle-verifier-id "force-auth-lifecycle-verifier.v1")

(def ^:private lifecycle-summary-schema-version "force-auth-lifecycle-summary.v2")
(def ^:private lifecycle-summary-v1-schema-version "force-auth-lifecycle-summary.v1")
(def ^:private lifecycle-summary-verifier-id "force-auth-lifecycle-summary-verifier.v1")

(def add-held-kind
  "Canonical :artifact/kind for a force-authorised add-held custody mutation."
  :force-auth-add-held)

(def lifecycle-kind
  "Canonical :artifact/kind for the force-authorisation lifecycle verification."
  :force-auth-lifecycle)

(def lifecycle-summary-kind
  "Canonical :artifact/kind for the force-authorisation lifecycle summary."
  :force-auth-lifecycle-summary)

(def add-held-summary-kind
  "Canonical :artifact/kind for the force-auth add-held summary."
  :force-auth-add-held-summary)

(def ^:private lifecycle-summary-v2-only-keys
  "Top-level keys introduced in v2 (absent from v1)."
  [:counts-by-status :counts-by-authorization-type :created :revoked
   :failed-after-consumption :rolled-back :outstanding-usable :consumption-count
   :conflicting-consumers :assurance-counts :governance-mode-counts
   :creator-provenance-counts :time-range :triage])

(def add-held-summary-schema-version
  "Canonical schema version for the force-auth-add-held-summary aggregate (v2)."
  "force-auth-add-held-summary.v2")

(def add-held-summary-v1-schema-version
  "Canonical schema version for the force-auth-add-held-summary aggregate (v1)."
  "force-auth-add-held-summary.v1")

(def add-held-summary-verifier-id
  "Canonical verifier identifier for the force-auth-add-held-summary aggregate."
  "force-auth-add-held-summary-verifier.v1")

(def add-held-summary-v2-only-keys
  "Top-level keys introduced in v2 (absent from v1)."
  [:invalid-artifacts :unverified-authorization-ids :min-amount :max-amount
   :consumed-at-earliest :consumed-at-latest :distinct-tokens :distinct-accounts
   :distinct-owners :amount-by-token :amount-by-direction :amount-by-account
   :amount-by-owner :missing-amount-count :non-numeric-amount-count
   :negative-amount-count :amount-issues])

(def add-held-summary-v1-category-keys
  "The sub-categories catalogued in v1 (v2 added :by-owner, :by-position-id,
   :by-authorization-type)."
  [:by-account :by-reason :by-authorization :by-consumed-by :by-token-direction])

(def ^:private add-held-summary-v2-category-keys
  "Sub-category dimensions introduced in v2 (absent from v1)."
  [:by-owner :by-position-id :by-authorization-type])

(def ^:private add-held-summary-v1-body-keys
  "Exact set of top-level keys that a force-auth-add-held-summary.v1 body may
   carry (excluding the :artifact/hash / :artifact/preimage envelope)."
  #{:schema-version :artifact/kind :artifact/verifier
    :total :valid-count :invalid-count
    :scope-verified-count :scope-unverified-count
    :total-amount
    :distinct-adjustment-ids
    :by-token :by-direction
    :categories})

(def ^:private category-field
  "Mapping from summary category key to the member field it catalogues."
  {:by-account :held/account
   :by-reason :held/reason
   :by-authorization :authorization/id
   :by-consumed-by :held/consumed-by
   :by-owner :owner/address
   :by-position-id :held/position-id
   :by-authorization-type :authorization/type})

;; ── Conceptual layer predicates ────────────────────────────────────────────
;;
;; The repository models force-authorisation as a layered contract. Only the
;; bottom two layers are materialised as content-addressed evidence artifacts
;; (:force-auth-add-held and :force-auth-add-held-summary); the upper two are
;; conceptual layers carried *within* those artifacts:
;;
;;   force-auth                base authorisation identity, policy, scope,
;;                             and validity — an authorization record / the
;;                             base authorization binding fields
;;   force-auth-add            an authorised add operation / add-specific
;;                             evidence (base + the held-add fields)
;;   force-auth-add-held       the content-addressed member artifact
;;   force-auth-add-held-summary.v1  the derived aggregate over members
;;
;; `valid-force-auth?` / `valid-force-auth-add?` are deliberately POLYMORPHIC:
;; they accept any artifact that carries the shared base / add contract, so an
;; exact :force-auth-add-held artifact satisfies both. Boundary-sensitive code
;; (check-aggregate member validation) MUST use the exact-kind predicates
;; (`exact-force-auth-add-held?`, i.e. valid-force-auth-add-held?) so a lower
;; layer can never masquerade as a member. The exact-* variants exist so the
;; boundary is explicit and testable.

(def force-auth-kind
  "Conceptual :artifact/kind for the base force-authorisation layer."
  :force-auth)

(def force-auth-schema-version
  "Conceptual schema version for the base force-authorisation layer."
  "force-auth.v1")

(def force-auth-verifier-id
  "Conceptual verifier identifier for the base force-authorisation layer."
  "force-auth-verifier.v1")

(def force-auth-add-kind
  "Conceptual :artifact/kind for the force-authorisation add layer."
  :force-auth-add)

(def force-auth-add-schema-version
  "Conceptual schema version for the force-authorisation add layer."
  "force-auth-add.v1")

(def force-auth-add-verifier-id
  "Conceptual verifier identifier for the force-authorisation add layer."
  "force-auth-add-verifier.v1")

(defn valid-force-auth?
  "POLYMORPHIC base-authorization predicate: true when report carries the
   shared force-authorization identity, policy, scope, and validity contract
   (:authorization/id, :authorization/type, :authorization/scope-hash).

   This deliberately accepts any layer carrying the base contract (including
   :force-auth-add-held). Boundary-sensitive code must not use this predicate
   where exact artifact-kind membership is required — use `exact-force-auth?`
   or the per-artifact exact predicates instead."
  [report]
  (and (map? report)
       (some? (:authorization/id report))
       (some? (:authorization/type report))
       (some? (:authorization/scope-hash report))))

(defn exact-force-auth?
  "EXACT-kind predicate for the conceptual :force-auth artifact: the report
   must carry the exact base artifact kind, schema version, and verifier id.

   No production builder produces a standalone :force-auth artifact (the base
   layer is carried inside :force-auth-add-held), so this is true only for
   explicitly hand-crafted fixtures; it exists to keep the layer boundary
   explicit and to prevent a polymorphic base predicate from being misread as
   an exact-kind check."
  [report]
  (and (map? report)
       (= force-auth-schema-version (:schema-version report))
       (= force-auth-kind (:artifact/kind report))
       (= force-auth-verifier-id (:artifact/verifier report))))

(defn valid-force-auth-add?
  "POLYMORPHIC add-operation predicate: true when report carries the base
   authorization contract AND the add-specific evidence fields (adjustment id,
   token, direction, amount, account).

   Accepts any layer carrying the add contract, including :force-auth-add-held.
   Exact-kind checks must use `exact-force-auth-add?` or `exact-force-auth-add-held?`."
  [report]
  (and (valid-force-auth? report)
       (some? (:held/adjustment-id report))
       (some? (:held/token report))
       (some? (:held/direction report))
       (some? (:held/amount report))
       (some? (:held/account report))))

(defn exact-force-auth-add?
  "EXACT-kind predicate for the conceptual :force-auth-add artifact: the report
   must carry the exact add artifact kind, schema version, and verifier id.

   No production builder produces a standalone :force-auth-add artifact; it
   exists to keep the layer boundary explicit and testable."
  [report]
  (and (map? report)
       (= force-auth-add-schema-version (:schema-version report))
       (= force-auth-add-kind (:artifact/kind report))
       (= force-auth-add-verifier-id (:artifact/verifier report))))

(defn exact-force-auth-add-held?
  "EXACT-kind predicate for the :force-auth-add-held member artifact: the
   exact supported schema version, artifact kind, verifier id, and full content
   round trip (canonical preimage + content hash) must all agree.

   Dispatches on the member schema version — v1 via valid-force-auth-add-held?,
   v2 via valid-force-auth-add-held-v2? (which also verifies the derived scope
   commitment). This is the predicate boundary-sensitive code (check-aggregate)
   uses so a lower layer can never masquerade as a member."
  [report]
  (cond
    (= add-held-schema-version (:schema-version report)) (valid-force-auth-add-held? report)
    (= add-held-v2-schema-version (:schema-version report)) (valid-force-auth-add-held-v2? report)
    :else false))

(defn- finalize-artifact
  "Attach the content hash and exact preimage to an artifact body."
  [body]
  (let [hash (str "sha256:"
                  (hash/domain-hash :evidence-record body))]
    (assoc body
           :artifact/hash hash
           :artifact/preimage (pr-str body))))

(def artifact-envelope-keys
  "Envelope keys that are stripped from the artifact body before hashing or
   canonical preimage computation. Includes the legacy :artifact/hash and
   :artifact/preimage plus the OPTIONAL parallel canonical commitment
   (:artifact/canonical-bytes-v2 / :artifact/canonical-hash-v2). The canonical
   commitment is representation-independent proof of the committed hash; it is
   envelope metadata so attaching it never changes :artifact/hash."
  #{:artifact/hash
    :artifact/preimage
    :artifact/canonical-bytes-v2
    :artifact/canonical-hash-v2})

(defn- artifact-body
  "The artifact body with every envelope key removed (what the content hash and
   canonical preimage commit to)."
  [report]
  (apply dissoc report artifact-envelope-keys))

(defn- bytes->hex-str
  "Lowercase hex encoding of a byte array."
  [^bytes ba]
  (apply str (map #(format "%02x" (bit-and (int %) 0xFF)) ba)))

(defn- canonical-commitment-valid?
  "Verify the optional parallel canonical commitment when present. When the
   commitment keys exist, :artifact/canonical-hash-v2 must equal the committed
   :artifact/hash AND :artifact/canonical-bytes-v2 must be the hex of
   canonical-bytes(artifact body) — the standard typed encoding per
   CANONICAL_HASH_SPEC_V1. A cross-language consumer proves portable hashing via
   sha256(domain-tag || hex-decode(canonical-bytes-v2)) == :artifact/hash.

   Artifacts without the commitment (the default) validate exactly as before."
  [report body]
  (if (or (contains? report :artifact/canonical-bytes-v2)
          (contains? report :artifact/canonical-hash-v2))
    (and (string? (:artifact/hash report))
         (string? (:artifact/canonical-hash-v2 report))
         (= (:artifact/hash report) (:artifact/canonical-hash-v2 report))
         (string? (:artifact/canonical-bytes-v2 report))
         (= (:artifact/canonical-bytes-v2 report)
            (bytes->hex-str (hash/canonical-bytes body))))
    true))

(defn- preimage-and-hash-valid?
  "Enforce the full content round trip for a content-addressed artifact:

     body → canonical preimage (pr-str of the exact body) → content hash

   Both must hold, so the :artifact/preimage and the decoded body can never
   disagree: the stored preimage must be the exact string serialization of the
   artifact body (with the envelope removed), and the stored hash must re-derive
   from that same body. When the optional parallel canonical commitment is
   present it is verified too. Independent of schema/kind/verifier identity
   checks."
  [report]
  (and (string? (:artifact/hash report))
       (string? (:artifact/preimage report))
       (let [body (artifact-body report)]
         (and (= (:artifact/preimage report) (pr-str body))
              (= (:artifact/hash report)
                 (str "sha256:" (hash/domain-hash :evidence-record body)))
              (canonical-commitment-valid? report body)))))

(defn attach-canonical-commitment
  "Attach an OPTIONAL, non-breaking parallel commitment proving
   representation-independent hashing alongside the legacy pr-str preimage:

     :artifact/canonical-bytes-v2  lowercase hex of the canonical typed bytes
                                   (canonical-bytes, per CANONICAL_HASH_SPEC_V1)
                                   of the artifact body
     :artifact/canonical-hash-v2   the domain-separated hash of the body, which
                                   equals :artifact/hash

   The commitment keys are envelope metadata: they are stripped before hashing,
   so attaching them never changes :artifact/hash or :artifact/preimage.
   valid-artifact? (and therefore every reader) verifies the commitment when
   present, and it cannot be forged (canonical-hash-v2 must equal the committed
   hash and the bytes must match canonical-bytes of the body). This is the
   non-breaking migration path toward portable, cross-language verification:
   adopt it behind your own version/feature flag and migrate gradually.

   Throws ex-info if the body contains a type canonical-bytes cannot encode."
  [artifact]
  (let [body (artifact-body artifact)
        hex (bytes->hex-str (hash/canonical-bytes body))]
    (assoc artifact
           :artifact/canonical-bytes-v2 hex
           :artifact/canonical-hash-v2 (:artifact/hash artifact))))

(defn valid-artifact?
  "Re-verify a content-addressed artifact: schema version, kind, verifier id,
   and the full content round trip (exact canonical preimage + content hash,
   plus the optional parallel canonical commitment) must all agree."
  [report schema-version kind verifier]
  (and (map? report)
       (= schema-version (:schema-version report))
       (= kind (:artifact/kind report))
       (= verifier (:artifact/verifier report))
       (preimage-and-hash-valid? report)))

;; ── force-auth-add-held ────────────────────────────────────────────────────

(defn build-force-auth-add-held
  "Build the versioned, content-addressed evidence artifact for a
   force-authorised add-held custody mutation.

   opts:
     :authorization  authorization record (must carry :authorization/scope-hash)
     :scope-map      the scope that was authorized
     :adjustment     held-adjustment map (uses :held-adjustment/id and, where
                     present, :token/:amount/:held/direction/:held/account)
     :consumed-at    consumption timestamp
     :consumed-by    consumption actor

   :authorization/scope-verifies? is recomputed (never caller-supplied): it is
   true only when the recorded scope-hash equals the recomputed scope-hash."
  [opts]
  (let [authorization (:authorization opts)
        scope-map (fa/normalize-force-authorisation-scope (:scope-map opts))
        adjustment (:adjustment opts)
        recomputed-scope-hash (fa/force-authorisation-scope-hash scope-map)
        recorded-scope-hash (:authorization/scope-hash authorization)
        body {:schema-version add-held-schema-version
              :artifact/kind add-held-kind
              :artifact/verifier add-held-verifier-id
              :authorization/id (:authorization/id authorization)
              :authorization/type (or (:authorization/type authorization)
                                      :force-authorisation)
              :authorization/scope-hash recomputed-scope-hash
              :authorization/scope-verifies?
              (and (some? recorded-scope-hash)
                   (= recorded-scope-hash recomputed-scope-hash))
              :held/adjustment-id (:held-adjustment/id adjustment)
              :held/token (or (:token adjustment) (:token scope-map))
              :held/direction (or (:held/direction adjustment)
                                  (:held/direction scope-map))
              :held/amount (or (:amount adjustment) (:amount scope-map))
              :held/account (or (:held/account adjustment) (:held/account scope-map))
              :held/position-id (or (:held/position-id adjustment)
                                    (:held/position-id scope-map))
              :owner/address (:owner/address scope-map)
              :held/reason (:held/reason scope-map)
              :held/consumed-at (:consumed-at opts)
              :held/consumed-by (:consumed-by opts)}]
    (finalize-artifact body)))

(defn valid-force-auth-add-held?
  "Re-verify a force-auth-add-held evidence artifact (v1)."
  [report]
  (valid-artifact? report add-held-schema-version add-held-kind
                   add-held-verifier-id))

(defn force-auth-add-held-scope-verifies?
  "Derive whether a member's authorization-scope binding verifies. The result is
   NEVER read from a stored boolean that cannot be checked.

   - v2 members (:force-auth-add-held.v2): derived from the committed
     three-part scope commitment — the :authorization/scope-hash must equal
     hash(canonical :authorization/scope-projection) under the declared
     :authorization/scope-derivation algorithm. A v2 member cannot assert a
     verified binding that its committed projection does not authenticate.
   - v1 members (:force-auth-add-held.v1): the scope map is not committed in v1,
     so the flag cannot be re-derived from the member alone; the hash-committed
     :authorization/scope-verifies? boolean is used. This is a documented v1
     limitation; only v2 members give full independent re-derivability."
  [m]
  (if (= add-held-v2-schema-version (:schema-version m))
    (and (= add-held-scope-derivation-id (:authorization/scope-derivation m))
         (map? (:authorization/scope-projection m))
         (string? (:authorization/scope-hash m))
         (= (:authorization/scope-hash m)
            (fa/force-authorisation-scope-hash (:authorization/scope-projection m))))
    (true? (:authorization/scope-verifies? m))))

(defn build-force-auth-add-held-v2
  "Build a force-auth-add-held.v2 member that commits the canonical normalized
   scope so the authorization-scope binding is independently re-derivable:

     :authorization/scope-projection  canonical normalized scope map
     :authorization/scope-hash        hash(scope-projection) under the declared
                                      :authorization/scope-derivation algorithm
     :authorization/scope-derivation  algorithm/version identifier

   scope-verifies? is NEVER stored on a v2 member — it is derived by
   force-auth-add-held-scope-verifies?. The recorded scope-hash on the
   authorization record is not needed to verify the member's own scope
   commitment, which makes the aggregate's scope counts independently checkable.

   opts: same as build-force-auth-add-held (:authorization, :scope-map,
     :adjustment, :consumed-at, :consumed-by)."
  [opts]
  (let [authorization (:authorization opts)
        projection (fa/normalize-force-authorisation-scope (:scope-map opts))
        adjustment (:adjustment opts)
        recomputed-scope-hash (fa/force-authorisation-scope-hash projection)
        body {:schema-version add-held-v2-schema-version
              :artifact/kind add-held-kind
              :artifact/verifier add-held-v2-verifier-id
              :authorization/id (:authorization/id authorization)
              :authorization/type (or (:authorization/type authorization)
                                      :force-authorisation)
              :authorization/scope-projection projection
              :authorization/scope-hash recomputed-scope-hash
              :authorization/scope-derivation add-held-scope-derivation-id
              :held/adjustment-id (:held-adjustment/id adjustment)
              :held/token (or (:token adjustment) (:token projection))
              :held/direction (or (:held/direction adjustment)
                                  (:held/direction projection))
              :held/amount (or (:amount adjustment) (:amount projection))
              :held/account (or (:held/account adjustment) (:held/account projection))
              :held/position-id (or (:held/position-id adjustment)
                                    (:held/position-id projection))
              :owner/address (:owner/address projection)
              :held/reason (:held/reason projection)
              :held/consumed-at (:consumed-at opts)
              :held/consumed-by (:consumed-by opts)}]
    (finalize-artifact body)))

(defn valid-force-auth-add-held-v2?
  "Re-verify a force-auth-add-held.v2 member: full content round trip (exact
   canonical preimage + content hash), the supported scope-derivation id, and
   the three-part scope commitment — :authorization/scope-hash must equal
   hash(canonical :authorization/scope-projection). Also rejects any member that
   stores an :authorization/scope-verifies? boolean, which must be derived."
  [report]
  (and (valid-artifact? report add-held-v2-schema-version add-held-kind
                        add-held-v2-verifier-id)
       (= add-held-scope-derivation-id (:authorization/scope-derivation report))
       (map? (:authorization/scope-projection report))
       (string? (:authorization/scope-hash report))
       (not (contains? report :authorization/scope-verifies?))
       (= (:authorization/scope-hash report)
          (fa/force-authorisation-scope-hash (:authorization/scope-projection report)))))

;; ── force-auth-lifecycle ───────────────────────────────────────────────────

(defn build-force-auth-lifecycle
  "Build the versioned, content-addressed evidence artifact for the
   force-authorisation lifecycle.

   opts:
     :authorisations        map of {auth-id record}
     :consumption-registry  map of {auth-id consumption-entry}
     :now                   current block time for usability checks (default 0)

   :lifecycle-consistent? and :authorisation-usable are recomputed by the
   builder via the protocol-independent assurance validators."
  [opts]
  (let [auths (fa/normalize-force-authorisation-records (:authorisations opts))
        registry (fa/normalize-force-authorisation-consumption-registry
                  (:consumption-registry opts))
        now (long (or (:now opts) 0))
        consistency (fa/verify-authorisation-lifecycle-consistency auths registry)
        usable (into {}
                     (map (fn [[id record]]
                            [id (fa/verify-authorisation-usable
                                 record registry (:authorization/scope record) now)]))
                     auths)
        body {:schema-version lifecycle-schema-version
              :artifact/kind lifecycle-kind
              :artifact/verifier lifecycle-verifier-id
              :lifecycle-consistent? (:holds? consistency)
              :lifecycle-violations (vec (:violations consistency))
              :authorisation-count (count auths)
              :consumption-count (count registry)
              :authorisation-usable (into {} (map (fn [[id r]] [id (:valid? r)]) usable))
              :authorisation-errors (into {}
                                          (map (fn [[id r]] [id (:errors r)]))
                                          (filter (fn [[_ r]] (not (:valid? r))) usable))
              :authorisations-root (hash/domain-hash :evidence-collection
                                                     (vec (sort (keys auths))))
              :consumptions-root (hash/domain-hash :evidence-collection
                                                   (vec (sort (keys registry))))}]
    (finalize-artifact body)))

(defn valid-force-auth-lifecycle?
  "Re-verify a force-auth-lifecycle evidence artifact."
  [report]
  (valid-artifact? report lifecycle-schema-version lifecycle-kind
                   lifecycle-verifier-id))

;; ── force-auth-lifecycle-summary ───────────────────────────────────────────

(defn build-force-auth-lifecycle-summary
  "Build the versioned, content-addressed summary evidence artifact for the
   force-authorisation lifecycle: counts by status, orphan consumptions, and
   the lifecycle-consistency outcome.

   opts:
     :authorisations        map of {auth-id record}
     :consumption-registry  map of {auth-id consumption-entry}
     :now                   current block time for expiry/usability classification
     :assurance              (optional) map of auth-id -> assurance class keyword
     :governance-mode        (optional) map of auth-id -> governance mode keyword
     :creator-provenance     (optional) map of auth-id -> creator provenance keyword"
  [opts]
  (let [auths (fa/normalize-force-authorisation-records (:authorisations opts))
        registry (fa/normalize-force-authorisation-consumption-registry
                  (:consumption-registry opts))
        now (long (or (:now opts) 0))
        assurance (or (:assurance opts) {})
        governance (or (:governance-mode opts) {})
        provenance (or (:creator-provenance opts) {})
        statuses (mapv :authorization/status (vals auths))
        records (vals auths)
        expired? (fn [r] (and (:expires-at r) (>= now (long (:expires-at r)))))
        expired (count (filter expired? records))
        consumed-ids (set (keys registry))
        outstanding-usable
        (count (filter (fn [[_ r]]
                         (:valid? (fa/verify-authorisation-usable
                                   r registry (:authorization/scope r) now)))
                       auths))
        consumed-by (into {} (map (fn [[id r]] [id (:consumed-by r)]) registry))
        conflicting-consumers
        (count (filter (fn [[id r]]
                         (and (consumed-ids id)
                              (some? (:executed-by r))
                              (not= (:executed-by r) (get consumed-by id))))
                       auths))
        ids (keys auths)
        created-ats (keep #(get-in % [:created-at]) records)
        consumed-ats (keep :consumed-at (vals registry))
        time-range {:created-at-earliest (when (seq created-ats) (apply min created-ats))
                    :created-at-latest (when (seq created-ats) (apply max created-ats))
                    :consumed-at-earliest (when (seq consumed-ats) (apply min consumed-ats))
                    :consumed-at-latest (when (seq consumed-ats) (apply max consumed-ats))}
        scope-hash-mismatches
        (vec (sort (filter (fn [id]
                             (let [r (get auths id)]
                               (and (:authorization/scope-hash r)
                                    (fa/scope-hash-mismatch? r (:authorization/scope r)))))
                           ids)))
        body {:schema-version lifecycle-summary-schema-version
              :artifact/kind lifecycle-summary-kind
              :artifact/verifier lifecycle-summary-verifier-id
              :total (count auths)
              :counts-by-status (into (sorted-map) (frequencies statuses))
              :counts-by-authorization-type (into (sorted-map)
                                                  (frequencies (map :authorization/type records)))
              :created (count (filter #(= :active %) statuses))
              :consumed (count (filter #(= :consumed %) statuses))
              :revoked (count (filter #(= :revoked %) statuses))
              :expired expired
              :failed-after-consumption (count (filter #(= :failed-after-consumption %) statuses))
              :rolled-back (count (filter #(= :rolled-back %) statuses))
              :outstanding-usable outstanding-usable
              :consumption-count (count registry)
              :conflicting-consumers conflicting-consumers
              :orphan-consumptions (count (remove #(contains? auths %) (keys registry)))
              :assurance-counts (into (sorted-map) (frequencies (vals assurance)))
              :governance-mode-counts (into (sorted-map) (frequencies (vals governance)))
              :creator-provenance-counts (into (sorted-map) (frequencies (vals provenance)))
              :time-range time-range
              :triage {:invalid-scope-hash scope-hash-mismatches
                       :expired-after-window (vec (sort (map :authorization/id (filter expired? records))))}
              :lifecycle-consistent?
              (:holds? (fa/verify-authorisation-lifecycle-consistency auths registry))}]
    (finalize-artifact body)))

(defn valid-force-auth-lifecycle-summary?
  "Re-verify a force-auth-lifecycle-summary evidence artifact (v2)."
  [report]
  (valid-artifact? report lifecycle-summary-schema-version
                   lifecycle-summary-kind lifecycle-summary-verifier-id))

(defn downgrade-force-auth-lifecycle-summary-v2->v1
  "Project a v2 lifecycle summary body back to the v1 shape (for migration
   verification). Discards the v2-only keys and the artifact envelope."
  [report]
  (let [stripped (reduce dissoc
                         (reduce dissoc report lifecycle-summary-v2-only-keys)
                         artifact-envelope-keys)]
    (assoc (assoc (assoc stripped
                         :schema-version lifecycle-summary-v1-schema-version)
                  :artifact/kind lifecycle-summary-kind)
           :artifact/verifier lifecycle-summary-verifier-id)))

(defn build-force-auth-lifecycle-summary-v1
  "Build a v1-shaped lifecycle summary artifact. Provided for migration and
   backward-compatibility testing; production callers should use the v2 builder."
  [opts]
  (finalize-artifact (downgrade-force-auth-lifecycle-summary-v2->v1
                      (build-force-auth-lifecycle-summary opts))))

(defn valid-force-auth-lifecycle-summary-v1?
  "Migration reader for persisted v1 force-auth-lifecycle-summary artifacts:
   verifies schema-version, kind, and verifier, then recomputes the v1 content
   hash by projecting away the v2-only fields."
  [report]
  (and (map? report)
       (= lifecycle-summary-v1-schema-version (:schema-version report))
       (= lifecycle-summary-kind (:artifact/kind report))
       (= lifecycle-summary-verifier-id (:artifact/verifier report))
       (string? (:artifact/hash report))
       (string? (:artifact/preimage report))
       (let [body (downgrade-force-auth-lifecycle-summary-v2->v1 report)]
         (= (:artifact/hash report)
            (str "sha256:" (hash/domain-hash :evidence-record body))))))

;; ── force-auth-add-held-summary ────────────────────────────────────────────

(defn build-force-auth-add-held-summary
  "Build the versioned, content-addressed summary evidence artifact over a
   collection of force-auth-add-held evidence artifacts.

   FAIL-FAST (production builder): the member set is admitted before
   construction. If any member does not pass the full aggregate membership
   classification (canonical force-auth-add-held verification, identity fields,
   verified authorization binding, and the set-level integrity rules), this
   throws ex-info with structured :invalid-members diagnostics. The resulting
   artifact is therefore always passing under check-aggregate for the same
   member set and options.

   For mixed-validity / triage construction (the legacy behavior), use
   `build-force-auth-add-held-summary-permissive`; for the v1 migration shape,
   use `build-force-auth-add-held-summary-v1`.

   opts / options:
     :artifacts  (1-arity opts form) a seq of force-auth-add-held artifacts.
     :unique-adjustment-ids?     reject duplicate held adjustment ids (default false)
     :unique-authorization-ids?  reject duplicate authorization ids (default false)

   Two arities:
     (build-force-auth-add-held-summary {:artifacts [...]})   legacy opts form
     (build-force-auth-add-held-summary members options)      members + options form
   Both produce the same artifact. Construction (content addressing) and
   validation (check-aggregate) are separate, but aligned: the builder shares
   the canonical summary-body derivation with recompute-force-auth-add-held-summary
   and check-aggregate, so builder output and canonical recomputation are
   byte-identical for an admitted member set."
  ([opts]
   (build-force-auth-add-held-summary (or (:artifacts opts) []) opts))
  ([members options]
   (admit-members! members options "build-force-auth-add-held-summary")
   (finalize-artifact (summary-body-v2 members))))

(defn build-force-auth-add-held-summary-permissive
  "LEGACY permissive builder for mixed-validity / triage construction. Accepts
   any member set (including members that fail canonical verification, lack
   identity fields, or carry unverified authorization bindings) and commits the
   triage views (:invalid-artifacts, :scope-unverified-count,
   :unverified-authorization-ids, :amount-issues) over the complete set.

   Output from this builder is NOT guaranteed to pass check-aggregate — invalid
   or unverified members make the aggregate non-passing. Use this only where
   legacy permissive behavior is required; production construction should use
   the fail-fast `build-force-auth-add-held-summary`.

   Two arities:
     (build-force-auth-add-held-summary-permissive {:artifacts [...]})
     (build-force-auth-add-held-summary-permissive members options)"
  ([opts]
   (build-force-auth-add-held-summary-permissive (or (:artifacts opts) []) opts))
  ([members _options]
   (finalize-artifact (summary-body-v2 members))))

(defn valid-force-auth-add-held-summary?
  "Two arities.

   (valid-force-auth-add-held-summary? report) — content-addressed reader:
   re-verifies a force-auth-add-held-summary artifact (v2) by checking schema
   version, kind, verifier, exact canonical preimage, and content hash. This
   does NOT check aggregate membership or reconciliation.

   (valid-force-auth-add-held-summary? summary members options) — aggregate
   predicate: delegates to check-aggregate for the v2 target. True only when
   the summary is a well-formed v2 aggregate consistent with the member set."
  ([report]
   (valid-artifact? report add-held-summary-schema-version
                    add-held-summary-kind add-held-summary-verifier-id))
  ([summary members options]
   (:valid? (check-aggregate summary members
                             (assoc (or options {}) :summary-version :v2)))))

(defn downgrade-add-held-summary-v2->v1
  "Project a v2 summary artifact body back to the v1 shape (for migration
   verification). Discards the v2-only keys and v2-only category dimensions."
  [report]
  (let [v1-categories (select-keys (:categories report)
                                   add-held-summary-v1-category-keys)
        stripped (reduce dissoc
                         (reduce dissoc report add-held-summary-v2-only-keys)
                         artifact-envelope-keys)]
    (assoc stripped
           :schema-version add-held-summary-v1-schema-version
           :artifact/kind add-held-summary-kind
           :artifact/verifier add-held-summary-verifier-id
           :categories v1-categories)))

(defn build-force-auth-add-held-summary-v1
  "Build a v1-shaped summary artifact from a collection of force-auth-add-held
   artifacts. MIGRATION / backward-compatibility builder; production callers
   should use the v2 `build-force-auth-add-held-summary`.

   Like the v2 production builder this is FAIL-FAST: non-passing members throw
   ex-info with structured diagnostics. The permissive path for building v1
   triage artifacts is `build-force-auth-add-held-summary-permissive` followed
   by `downgrade-add-held-summary-v2->v1`.

   Two arities:
     (build-force-auth-add-held-summary-v1 {:artifacts [...]})   legacy opts form
     (build-force-auth-add-held-summary-v1 members options)      members + options form"
  ([opts]
   (build-force-auth-add-held-summary-v1 (or (:artifacts opts) []) opts))
  ([members options]
   (admit-members! members options "build-force-auth-add-held-summary-v1")
   (finalize-artifact (summary-body-v1 members))))

(defn valid-force-auth-add-held-summary-v1?
  "Two arities.

   (valid-force-auth-add-held-summary-v1? report) — EXACT v1 reader and the
   boundary gate for persisted v1 artifacts. Verifies schema version, kind,
   verifier, exact canonical preimage, content hash, AND the exact v1 shape
   (no v2-only key, no unknown key, v1 category keys only). A .v2 artifact — or
   a v1-labeled artifact carrying v2-only fields — can therefore never validate
   as v1. The projection-based migration reader is
   `valid-force-auth-add-held-summary-v1-migration?` and must be invoked
   explicitly for legacy migration.

   (valid-force-auth-add-held-summary-v1? summary members options) — aggregate
   predicate: delegates to check-aggregate for the v1 target."
  ([report]
   (and (map? report)
        (v1-summary-shape-valid? report)
        (valid-artifact? report add-held-summary-v1-schema-version
                         add-held-summary-kind add-held-summary-verifier-id)))
  ([summary members options]
   (:valid? (check-aggregate summary members options))))

(defn valid-force-auth-add-held-summary-v1-migration?
  "MIGRATION reader for persisted v1 artifacts created through the projection
   path. Verifies schema version, kind, and verifier, then recomputes the v1
   content hash by projecting away the v2-only fields.

   This is intentionally PERMISSIVE: it accepts v2-only keys by projecting them
   away before hashing, so it is NOT an exact boundary gate. Boundary-sensitive
   code must use `valid-force-auth-add-held-summary-v1?` (exact) or
   `check-aggregate`. Invoke this function only when explicitly migrating
   legacy content that was stored with v2-only keys under a v1 label."
  [report]
  (and (map? report)
       (= add-held-summary-v1-schema-version (:schema-version report))
       (= add-held-summary-kind (:artifact/kind report))
       (= add-held-summary-verifier-id (:artifact/verifier report))
       (string? (:artifact/hash report))
       (string? (:artifact/preimage report))
       (let [v1-body (downgrade-add-held-summary-v2->v1 report)]
         (= (:artifact/hash report)
            (str "sha256:" (hash/domain-hash :evidence-record v1-body))))))

;; ═══════════════════════════════════════════════════════════════════════════
;; force-auth-add-held-summary — aggregate boundary, membership, reconciliation
;; ═══════════════════════════════════════════════════════════════════════════
;;
;; One canonical summary-body derivation (force-auth-add-held-summary-fields)
;; is shared by the production builder, the permissive builder,
;; recompute-force-auth-add-held-summary, and check-aggregate reconciliation.
;; Given the same member set they always produce the same semantic body, so
;; builder output and recomputation are byte-identical and reconciliation never
;; silently drops a committed field.
;;
;; check-aggregate is a pure, deterministic, data-only checker. It takes a
;; summary artifact AND the member set it is claimed to aggregate over, then:
;;
;;   1. verifies the aggregate identity (kind, schema version, verifier, full
;;      content round trip — exact canonical preimage + content hash — and exact
;;      shape, rejecting v2-only and unknown keys on a v1 target);
;;   2. validates every member through the canonical exact-kind
;;      valid-force-auth-add-held? verifier (never a weaker base/add predicate),
;;      plus identity fields, verified authorization binding, and the set-level
;;      integrity rules;
;;   3. reconciles every derivable summary field (including the v2 triage views)
;;      against the shared canonical recomputation (never trusts stored values).
;;
;; Construction and validation are separated but ALIGNED:
;;   - build-force-auth-add-held-summary / -v1 are FAIL-FAST: non-passing
;;     members throw ex-info with structured :invalid-members diagnostics, so
;;     their output is always passing under check-aggregate;
;;   - build-force-auth-add-held-summary-permissive is the LEGACY path for
;;     mixed-validity / triage construction; its output can be non-passing.
;; Invalid members always make the aggregate non-passing, are surfaced in
;; :invalid-members / :mismatches, and their amounts never enter the financial
;; totals of the shared derivation.
;;
;; Boundary direction: this section depends only on the public validation and
;; projection operations of the lower layers in this namespace. The lower-layer
;; artifacts (:force-auth-add-held) do not depend on the summary implementation.

(defn- content-hash-valid?
  "True when the artifact's full content round trip holds: the exact canonical
   preimage (pr-str of the body with the envelope removed) and a content hash
   re-derived from that same body. Independent of schema/kind/verifier identity
   checks."
  [report]
  (and (map? report)
       (preimage-and-hash-valid? report)))

(defn- member-family-version?
  "True when a schema-version string belongs to the force-auth-add-held member
   artifact family (a different version of the member), as opposed to an
   unrelated artifact or a summary version."
  [v]
  (and (string? v)
       (str/starts-with? v "force-auth-add-held.")
       (not (str/starts-with? v "force-auth-add-held-summary"))))

(defn- member-schema-supported?
  "True when a member schema version is a supported force-auth-add-held version."
  [v]
  (or (= add-held-schema-version v)
      (= add-held-v2-schema-version v)))

(defn- member-verifier-supported?
  "True when a member verifier id matches the schema version's verifier."
  [v]
  (or (= add-held-verifier-id v)
      (= add-held-v2-verifier-id v)))

(defn- canonical-member-valid?
  "Canonical exact-kind content verification for a member, dispatching on the
   member's schema version."
  [m]
  (cond
    (= add-held-schema-version (:schema-version m)) (valid-force-auth-add-held? m)
    (= add-held-v2-schema-version (:schema-version m)) (valid-force-auth-add-held-v2? m)
    :else false))

(defn- classify-member
  "Primary per-member classification. Returns nil when the member is a fully
   valid force-auth-add-held artifact (canonical content-addressed verification
   for its schema version, plus required identity fields and a verified
   authorization binding — derived for v2 members, committed for v1), else a
   stable reason keyword.

   Priority: not-a-map → kind → version → verifier → content hash → missing
   identity → authorization binding. Set-level conditions (duplicate members,
   duplicate ids, conflicting scope bindings) are applied in validate-member-set."
  [m]
  (cond
    (not (map? m)) :not-force-auth-add-held
    (not= add-held-kind (:artifact/kind m))
    (if (some? (:artifact/kind m))
      :artifact-kind-mismatch
      :not-force-auth-add-held)
    (not (member-schema-supported? (:schema-version m)))
    (if (member-family-version? (:schema-version m))
      :unsupported-member-version
      :schema-version-mismatch)
    (not (member-verifier-supported? (:artifact/verifier m)))
    :verifier-mismatch
    (not (canonical-member-valid? m))
    :content-hash-mismatch
    (nil? (:authorization/id m))
    :missing-authorization-id
    (nil? (:held/adjustment-id m))
    :missing-adjustment-id
    (not (force-auth-add-held-scope-verifies? m))
    :authorization-binding-mismatch
    :else nil))

(defn- duplicate-indexes-by
  "Indexes of members (in the valid subset) whose key-extracted value repeats
   across the group. Returns a set of original member indexes (every member of a
   duplicated group)."
  [valid-indexed key-fn]
  (->> (group-by key-fn valid-indexed)
       (keep (fn [[v ms]]
               (when (and (some? v) (> (count ms) 1))
                 (map :index ms))))
       (mapcat identity)
       set))

(defn- validate-member-set
  "Classify every supplied member and apply the set-level integrity checks.

   Returns
     {:member-count n
      :valid-count   number of fully valid members
      :valid-indexed [{:index i :member m} ...]   fully valid members with original indexes
      :valid-members [m ...]
      :invalid       [{:index :adjustment-id :authorization-id :reason} ...] (index-ordered)
      :duplicate-member-indexes          set of original indexes
      :duplicate-adjustment-indexes      set of original indexes
      :duplicate-authorization-indexes   set of original indexes
      :warnings        [{:kind :duplicate-adjustment-id|:duplicate-authorization-id ...}]}

   Set-level rules:
     - identical duplicate members (:duplicate-member) always fail;
     - conflicting authorization bindings (same authorization id, different
       scope hashes) always fail (:authorization-binding-mismatch);
     - duplicate adjustment / authorization ids fail only when the corresponding
       :unique-*? option is set; otherwise they are reported as warnings."
  [members options]
  (let [members (vec (or members []))
        opts (or options {})
        unique-adj? (true? (:unique-adjustment-ids? opts))
        unique-auth? (true? (:unique-authorization-ids? opts))
        indexed (mapv (fn [i m] {:index i :member m}) (range) members)
        per-member (mapv (fn [{:keys [member]}] (classify-member member)) indexed)
        with-reason (mapv (fn [e r] (assoc e :reason r)) indexed per-member)
        per-valid? (fn [{:keys [reason]}] (nil? reason))
        pass-a (filterv per-valid? with-reason)
        pass-a-indexed (mapv (fn [{:keys [index member]}] {:index index :member member}) pass-a)
        dup-member-idxs (duplicate-indexes-by pass-a-indexed (comp :artifact/hash :member))
        binding-conflict-idxs
        (->> (group-by (comp :authorization/id :member) pass-a-indexed)
             (keep (fn [[id ms]]
                     (when (and (some? id)
                                (> (count ms) 1)
                                (> (count (distinct (map (comp :authorization/scope-hash :member) ms))) 1))
                       (map :index ms))))
             (mapcat identity)
             set)
        dup-adj-idxs (duplicate-indexes-by pass-a-indexed (comp :held/adjustment-id :member))
        dup-auth-idxs (duplicate-indexes-by pass-a-indexed (comp :authorization/id :member))
        final-reason (fn [e]
                       (let [i (:index e)]
                         (cond
                           (contains? binding-conflict-idxs i) :authorization-binding-mismatch
                           (contains? dup-member-idxs i) :duplicate-member
                           (and unique-adj? (contains? dup-adj-idxs i)) :duplicate-adjustment-id
                           (and unique-auth? (contains? dup-auth-idxs i)) :duplicate-authorization-id
                           :else (:reason e))))
        final (mapv (fn [e] (assoc e :reason (final-reason e))) with-reason)
        valid-final (filterv (comp nil? :reason) final)
        invalid (into []
                      (keep (fn [e]
                              (when-let [r (:reason e)]
                                {:index (:index e)
                                 :adjustment-id (:held/adjustment-id (:member e))
                                 :authorization-id (:authorization/id (:member e))
                                 :reason r})))
                      final)
        adj-groups (->> (group-by (comp :held/adjustment-id :member) pass-a-indexed) vals)
        auth-id-groups (->> (group-by (comp :authorization/id :member) pass-a-indexed) vals)
        dup-adj-warn (when-not unique-adj?
                       (keep (fn [ms]
                               (let [v (:held/adjustment-id (:member (first ms)))]
                                 (when (and (some? v) (> (count ms) 1))
                                   {:kind :duplicate-adjustment-id
                                    :value v
                                    :indexes (mapv :index ms)})))
                             adj-groups))
        dup-auth-warn (when-not unique-auth?
                        (keep (fn [ms]
                                (let [v (:authorization/id (:member (first ms)))]
                                  (when (and (some? v) (> (count ms) 1))
                                    {:kind :duplicate-authorization-id
                                     :value v
                                     :indexes (mapv :index ms)})))
                              auth-id-groups))]
    {:member-count (count members)
     :valid-count (count valid-final)
     :valid-indexed (mapv (fn [{:keys [index member]}] {:index index :member member}) valid-final)
     :valid-members (mapv :member valid-final)
     :invalid (vec invalid)
     :duplicate-member-indexes dup-member-idxs
     :duplicate-adjustment-indexes dup-adj-idxs
     :duplicate-authorization-indexes dup-auth-idxs
     :warnings (vec (sort-by pr-str (concat dup-adj-warn dup-auth-warn)))}))

(defn- force-auth-add-held-summary-fields
  "SINGLE canonical summary-body derivation shared by the production builder,
   the permissive builder, recompute-force-auth-add-held-summary, and
   check-aggregate reconciliation. Given the same member set it always produces
   the same semantic field set, so builder and recomputation cannot diverge.

   Per-member classification (classify-member) drives :valid-count,
   :invalid-artifacts, and the financial/category projections: members that do
   not verify as force-auth-add-held artifacts (or that lack identity fields or
   a verified authorization binding) are never counted as valid, and their
   amounts never enter monetary totals. Triage views (:invalid-artifacts, scope
   counts, unverified authorization ids, amount issues) are computed over the
   COMPLETE supplied member set. Only numeric amounts contribute to monetary
   totals; a missing or non-numeric amount is never coerced to zero."
  [members]
  (let [artifacts (vec (or members []))
        total (count artifacts)
        reasons (mapv classify-member artifacts)
        per-valid? (fn [r] (nil? r))
        valid-count (count (filter per-valid? reasons))
        invalid-count (- total valid-count)
        invalid-artifacts (into []
                                (keep-indexed (fn [i a]
                                                (when-let [r (nth reasons i)]
                                                  {:index i
                                                   :adjustment-id (:held/adjustment-id a)
                                                   :authorization-id (:authorization/id a)
                                                   :reason r})))
                                artifacts)
        valid-members (into []
                            (keep-indexed (fn [i a]
                                            (when (per-valid? (nth reasons i)) a)))
                            artifacts)
        scope-verified (count (filter force-auth-add-held-scope-verifies? artifacts))
        unverified-auth-ids (vec (sort
                                  (distinct
                                   (keep (fn [a]
                                           (when-not (force-auth-add-held-scope-verifies? a)
                                             (:authorization/id a)))
                                         artifacts))))
        amount-issues (into []
                            (keep-indexed (fn [i a]
                                            (let [amt (:held/amount a)
                                                  issue (cond
                                                          (nil? amt) :missing-amount
                                                          (not (number? amt)) :non-numeric-amount
                                                          (neg? (double amt)) :negative-amount
                                                          :else nil)]
                                              (when issue
                                                {:index i
                                                 :adjustment-id (:held/adjustment-id a)
                                                 :amount-issue issue}))))
                            artifacts)
        issue-count (fn [issue-kind]
                      (count (filter #(= issue-kind (:amount-issue %)) amount-issues)))
        amounts (vec (keep (fn [a]
                             (let [amt (:held/amount a)]
                               (when (and (some? amt) (number? amt))
                                 (long amt))))
                           valid-members))
        total-amount (reduce + 0 amounts)
        min-amount (when (seq amounts) (apply min amounts))
        max-amount (when (seq amounts) (apply max amounts))
        consumed-ats (->> valid-members (keep :held/consumed-at) (map long) vec)
        consumed-at-earliest (when (seq consumed-ats) (apply min consumed-ats))
        consumed-at-latest (when (seq consumed-ats) (apply max consumed-ats))
        sorted-freq (fn [field] (into (sorted-map) (frequencies (keep field valid-members))))
        sum-by (fn [k]
                 (into (sorted-map)
                       (reduce (fn [m a]
                                 (let [v (get a k)]
                                   (if (some? v)
                                     (let [amt (:held/amount a)]
                                       (if (and (some? amt) (number? amt))
                                         (update m v (fnil + 0) (long amt))
                                         m))
                                     m)))
                               {}
                               valid-members)))
        by-token-direction (frequencies
                            (keep (fn [a]
                                    (when-let [t (:held/token a)]
                                      (when-let [d (:held/direction a)]
                                        [(keyword t) (keyword d)])))
                                  valid-members))
        categories (into {}
                         (map (fn [[cat-k field]] [cat-k (sorted-freq field)]))
                         category-field)
        categories (assoc categories :by-token-direction (into (sorted-map) by-token-direction))]
    {:total total
     :valid-count valid-count
     :invalid-count invalid-count
     :invalid-artifacts (vec invalid-artifacts)
     :scope-verified-count scope-verified
     :scope-unverified-count (- total scope-verified)
     :unverified-authorization-ids unverified-auth-ids
     :total-amount total-amount
     :min-amount min-amount
     :max-amount max-amount
     :missing-amount-count (issue-count :missing-amount)
     :non-numeric-amount-count (issue-count :non-numeric-amount)
     :negative-amount-count (issue-count :negative-amount)
     :amount-issues (vec amount-issues)
     :consumed-at-earliest consumed-at-earliest
     :consumed-at-latest consumed-at-latest
     :distinct-adjustment-ids (count (distinct (keep :held/adjustment-id valid-members)))
     :distinct-tokens (count (distinct (keep :held/token valid-members)))
     :distinct-accounts (count (distinct (keep :held/account valid-members)))
     :distinct-owners (count (distinct (keep :owner/address valid-members)))
     :by-token (into (sorted-map) (frequencies (keep :held/token valid-members)))
     :by-direction (into (sorted-map) (frequencies (keep :held/direction valid-members)))
     :amount-by-token (sum-by :held/token)
     :amount-by-direction (sum-by :held/direction)
     :amount-by-account (sum-by :held/account)
     :amount-by-owner (sum-by :owner/address)
     :categories categories}))

(defn- summary-body-v2
  "Canonical v2 summary body (without :artifact/hash / :artifact/preimage)
   derived from a member set via the shared derivation."
  [members]
  (let [f (force-auth-add-held-summary-fields members)]
    {:schema-version add-held-summary-schema-version
     :artifact/kind add-held-summary-kind
     :artifact/verifier add-held-summary-verifier-id
     :total (:total f)
     :valid-count (:valid-count f)
     :invalid-count (:invalid-count f)
     :invalid-artifacts (:invalid-artifacts f)
     :scope-verified-count (:scope-verified-count f)
     :scope-unverified-count (:scope-unverified-count f)
     :unverified-authorization-ids (:unverified-authorization-ids f)
     :total-amount (:total-amount f)
     :min-amount (:min-amount f)
     :max-amount (:max-amount f)
     :missing-amount-count (:missing-amount-count f)
     :non-numeric-amount-count (:non-numeric-amount-count f)
     :negative-amount-count (:negative-amount-count f)
     :amount-issues (:amount-issues f)
     :consumed-at-earliest (:consumed-at-earliest f)
     :consumed-at-latest (:consumed-at-latest f)
     :distinct-adjustment-ids (:distinct-adjustment-ids f)
     :distinct-tokens (:distinct-tokens f)
     :distinct-accounts (:distinct-accounts f)
     :distinct-owners (:distinct-owners f)
     :by-token (:by-token f)
     :by-direction (:by-direction f)
     :amount-by-token (:amount-by-token f)
     :amount-by-direction (:amount-by-direction f)
     :amount-by-account (:amount-by-account f)
     :amount-by-owner (:amount-by-owner f)
     :categories (:categories f)}))

(defn- summary-body-v1
  "Canonical v1 summary body (without :artifact/hash / :artifact/preimage)
   derived from a member set: the v2 body projected to the v1 shape."
  [members]
  (downgrade-add-held-summary-v2->v1 (summary-body-v2 members)))

(defn- admit-members!
  "Fail-fast admission for the production builders. Throws ex-info with
   structured diagnostics when any supplied member does not pass the full
   aggregate membership classification (canonical force-auth-add-held
   verification, identity fields, verified authorization binding, and the
   set-level integrity rules)."
  [members options builder-name]
  (let [members (vec (or members []))
        {:keys [invalid]} (validate-member-set members options)]
    (when (seq invalid)
      (throw (ex-info (str builder-name ": member set contains non-passing members")
                      {:member-count (count members)
                       :invalid-count (count invalid)
                       :invalid-members (vec invalid)})))))

(defn- v1-summary-shape-valid?
  "Exact-shape check for a v1 summary body: no v2-only key, no unknown top-level
   key, and category keys within the v1 category set."
  [report]
  (and (map? report)
       (let [body (artifact-body report)
             v1-category-set (set add-held-summary-v1-category-keys)]
         (and (every? (fn [k] (not (contains? body k))) add-held-summary-v2-only-keys)
              (every? (fn [k] (contains? add-held-summary-v1-body-keys k)) (keys body))
              (let [cats (:categories body)]
                (and (map? cats)
                     (every? (fn [k] (contains? v1-category-set k)) (keys cats))))))))

(defn- v2-summary-shape-valid?
  "Exact-shape check for a v2 summary body: no unknown top-level key, and
   category keys within the v1 ∪ v2 category set."
  [report]
  (and (map? report)
       (let [body (artifact-body report)
             v2-body-keys (into add-held-summary-v1-body-keys add-held-summary-v2-only-keys)
             v2-category-set (set (into add-held-summary-v1-category-keys
                                        add-held-summary-v2-category-keys))]
         (and (every? (fn [k] (contains? v2-body-keys k)) (keys body))
              (let [cats (:categories body)]
                (and (map? cats)
                     (every? (fn [k] (contains? v2-category-set k)) (keys cats))))))))

(def ^:private v1-simple-paths
  "Derivable summary fields committed by the v1 shape."
  [[[:total] :total]
   [[:valid-count] :valid-count]
   [[:invalid-count] :invalid-count]
   [[:scope-verified-count] :scope-verified-count]
   [[:scope-unverified-count] :scope-unverified-count]
   [[:total-amount] :total-amount]
   [[:distinct-adjustment-ids] :distinct-adjustment-ids]
   [[:by-token] :by-token]
   [[:by-direction] :by-direction]])

(def ^:private v2-simple-paths
  "Derivable summary fields committed by the v2 shape (superset of v1).
   Includes the triage views (:invalid-artifacts, :unverified-authorization-ids,
   :amount-issues) so reconciliation never silently excludes a committed field."
  (into v1-simple-paths
        [[[:min-amount] :min-amount]
         [[:max-amount] :max-amount]
         [[:consumed-at-earliest] :consumed-at-earliest]
         [[:consumed-at-latest] :consumed-at-latest]
         [[:missing-amount-count] :missing-amount-count]
         [[:non-numeric-amount-count] :non-numeric-amount-count]
         [[:negative-amount-count] :negative-amount-count]
         [[:distinct-tokens] :distinct-tokens]
         [[:distinct-accounts] :distinct-accounts]
         [[:distinct-owners] :distinct-owners]
         [[:amount-by-token] :amount-by-token]
         [[:amount-by-direction] :amount-by-direction]
         [[:amount-by-account] :amount-by-account]
         [[:amount-by-owner] :amount-by-owner]
         [[:invalid-artifacts] :invalid-artifacts]
         [[:unverified-authorization-ids] :unverified-authorization-ids]
         [[:amount-issues] :amount-issues]]))

(defn- reconcile
  "Compare every derivable field against the canonical recomputation. Returns a
   vector of {:path [...] :expected <recomputed> :actual <stored>}. A missing
   stored field compares as nil, so an aggregate can never silently drop a
   derivable value."
  [summary fields simple-paths category-keys]
  (let [simple (mapcat (fn [[path k]]
                         (let [expected (get fields k)
                               actual (get-in summary path)]
                           (when-not (= expected actual)
                             [{:path path :expected expected :actual actual}])))
                       simple-paths)
        cats (mapcat (fn [k]
                       (let [expected (get-in fields [:categories k])
                             actual (get-in summary [:categories k])]
                         (when-not (= expected actual)
                           [{:path [:categories k] :expected expected :actual actual}])))
                     category-keys)]
    (vec (concat simple cats))))

(defn- compute-member-root
  "Deterministic informational root over the supplied member multiset. The v1
   and v2 summary shapes do NOT commit this root, so it cannot be verified
   against the artifact — it is surfaced for machine-readable diagnostics."
  [members]
  (hash/domain-hash :evidence-collection
                    (vec (sort-by (fn [m] (or (:artifact/hash m) "")) members))))

(defn- resolve-summary-version
  "Normalize the :summary-version option (:v1 / :v2 keywords or the schema
   version strings). Throws ex-info only for unsupported option values — this is
   a configuration error, not malformed artifact input."
  [options]
  (let [v (or (:summary-version options) :v1)]
    (cond
      (contains? #{:v1 :v2} v) v
      (= v add-held-summary-v1-schema-version) :v1
      (= v add-held-summary-schema-version) :v2
      :else (throw (ex-info "Unsupported summary version"
                            {:summary-version v
                             :supported #{:v1 :v2 add-held-summary-v1-schema-version
                                          add-held-summary-schema-version}})))))

(defn recompute-force-auth-add-held-summary
  "Canonical recomputation of the force-auth-add-held-summary artifact from a
   member set. Pure projection: it reads ONLY members and options and never
   copies identity fields, totals, triage, or category values from any supplied
   summary.

   options:
     :summary-version  :v1 (default) or :v2

   Uses the SAME shared summary-body derivation as the builders, so for any
   member set (all-valid, mixed-validity, verified or unverified
   authorisations, empty) recomputation reproduces the builder output
   byte-for-byte:

     (recompute-force-auth-add-held-summary members {:summary-version :v2})
       == (build-force-auth-add-held-summary-permissive members opts)
       == (build-force-auth-add-held-summary members opts)   ; when all members pass

   Invalid members are excluded from financial totals but are represented in the
   :invalid-artifacts / :unverified-authorization-ids triage views exactly as
   the builders commit them. Returns a finalized content-addressed artifact in
   the requested shape."
  [members options]
  (let [options (or options {})
        version (resolve-summary-version options)
        members (vec (or members []))
        body (if (= version :v1)
               (summary-body-v1 members)
               (summary-body-v2 members))]
    (finalize-artifact body)))

(defn check-aggregate
  "Structured, deterministic, data-only aggregate check for a
   force-auth-add-held-summary artifact against the member artifacts it is
   claimed to aggregate over.

     (check-aggregate summary members options)

   summary  — a force-auth-add-held-summary artifact (v1 or v2 body).
   members  — a seq of force-auth-add-held member artifacts.
   options  — optional map:
     :summary-version            :v1 (default) or :v2
     :unique-adjustment-ids?     when true, duplicate held adjustment ids fail
                                 the aggregate (otherwise a warning)
     :unique-authorization-ids?  when true, duplicate authorization ids fail
                                 the aggregate (otherwise a warning)

   Returns
     {:valid? bool
      :status :valid | :invalid
      :aggregate-kind :force-auth-add-held-summary
      :schema-version \"...\"
      :member-count n :valid-member-count n :invalid-member-count n
      :checks {...}            one boolean gate per required check
      :invalid-members [...]   per-member failure classification
      :mismatches [...]        stored-vs-recomputed derivable field differences
      :warnings [...]          non-fatal conditions (duplicate ids, amount
                               integrity triage, uncommitted member root)
      :member-root {...}}      informational (v1/v2 do not commit a member root)

   Every gate in :checks must be true for :valid? to be true. Ordinary malformed
   input (nil/non-map summary, nil/duplicated members) yields a non-passing
   result and never throws; only unsupported option values throw ex-info."
  [summary members options]
  (let [options (or options {})
        version (resolve-summary-version options)
        expected-schema-version (if (= version :v1)
                                  add-held-summary-v1-schema-version
                                  add-held-summary-schema-version)
        summary-map? (map? summary)
        kind-valid? (and summary-map? (= add-held-summary-kind (:artifact/kind summary)))
        schema-valid? (and summary-map? (= expected-schema-version (:schema-version summary)))
        verifier-valid? (and summary-map?
                             (= add-held-summary-verifier-id (:artifact/verifier summary)))
        hash-valid? (and summary-map? (content-hash-valid? summary))
        shape-valid? (and summary-map?
                          (if (= version :v1)
                            (v1-summary-shape-valid? summary)
                            (v2-summary-shape-valid? summary)))
        {:keys [member-count valid-count invalid
                duplicate-member-indexes duplicate-adjustment-indexes
                duplicate-authorization-indexes warnings]}
        (validate-member-set members options)
        invalid-count (- member-count valid-count)
        members-valid? (zero? invalid-count)
        fields (force-auth-add-held-summary-fields members)
        simple-paths (if (= version :v1) v1-simple-paths v2-simple-paths)
        category-keys (if (= version :v1)
                        add-held-summary-v1-category-keys
                        (into add-held-summary-v1-category-keys
                              add-held-summary-v2-category-keys))
        mismatches (if summary-map?
                     (reconcile summary fields simple-paths category-keys)
                     [{:path [] :expected expected-schema-version :actual summary}])
        summary-recomputes? (empty? mismatches)
        identities-unique? (and (empty? duplicate-member-indexes)
                                (or (not (:unique-adjustment-ids? options))
                                    (empty? duplicate-adjustment-indexes))
                                (or (not (:unique-authorization-ids? options))
                                    (empty? duplicate-authorization-indexes)))
        member-set-complete? (and summary-map?
                                  (= (:total summary) member-count)
                                  (= (:valid-count summary) valid-count)
                                  (= (:invalid-count summary) invalid-count)
                                  identities-unique?)
        checks {:aggregate-kind-valid? kind-valid?
                :aggregate-schema-valid? schema-valid?
                :aggregate-verifier-valid? verifier-valid?
                :aggregate-hash-valid? hash-valid?
                :aggregate-shape-valid? shape-valid?
                :members-valid? members-valid?
                :member-identities-unique? identities-unique?
                :member-set-complete? member-set-complete?
                :summary-recomputes? summary-recomputes?}
        amount-warnings (mapv (fn [{:keys [index adjustment-id amount-issue]}]
                                {:kind amount-issue
                                 :index index
                                 :adjustment-id adjustment-id})
                              (:amount-issues fields))
        all-warnings (into []
                           (sort-by pr-str
                                    (concat warnings
                                            amount-warnings
                                            [{:kind :member-root-not-committed
                                              :detail (str expected-schema-version
                                                           " does not commit a member-set root; completeness is "
                                                           "reconciled by counts and per-member validation only.")}])))
        valid? (every? true? (vals checks))]
    {:valid? valid?
     :status (if valid? :valid :invalid)
     :aggregate-kind add-held-summary-kind
     :schema-version expected-schema-version
     :member-count member-count
     :valid-member-count valid-count
     :invalid-member-count invalid-count
     :checks checks
     :invalid-members invalid
     :mismatches mismatches
     :warnings all-warnings
     :member-root {:committed? false
                   :computed (compute-member-root members)}}))
