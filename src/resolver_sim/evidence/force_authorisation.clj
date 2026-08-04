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
  "EXACT-kind predicate for the :force-auth-add-held member artifact: schema
   version, artifact kind, verifier id, and content hash must all agree.

   Delegates to valid-force-auth-add-held?, which is already an exact-kind,
   content-addressed verifier. This alias exists so boundary-sensitive code
   (check-aggregate) can name the exact predicate explicitly."
  [report]
  (valid-force-auth-add-held? report))

(defn- finalize-artifact
  "Attach the content hash and exact preimage to an artifact body."
  [body]
  (let [hash (str "sha256:"
                  (hash/domain-hash :evidence-record body))]
    (assoc body
           :artifact/hash hash
           :artifact/preimage (pr-str body))))

(defn- valid-artifact?
  "Re-verify a content-addressed artifact: schema version, kind, verifier id,
   and content hash must all agree."
  [report schema-version kind verifier]
  (and (map? report)
       (= schema-version (:schema-version report))
       (= kind (:artifact/kind report))
       (= verifier (:artifact/verifier report))
       (string? (:artifact/hash report))
       (string? (:artifact/preimage report))
       (let [body (dissoc report :artifact/hash :artifact/preimage)]
         (= (:artifact/hash report)
            (str "sha256:" (hash/domain-hash :evidence-record body))))))

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
  "Re-verify a force-auth-add-held evidence artifact."
  [report]
  (valid-artifact? report add-held-schema-version add-held-kind
                   add-held-verifier-id))

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
   verification). Discards the v2-only keys."
  [report]
  (-> (reduce dissoc report lifecycle-summary-v2-only-keys)
      (dissoc :artifact/hash :artifact/preimage)
      (assoc :schema-version lifecycle-summary-v1-schema-version)
      (assoc :artifact/kind lifecycle-summary-kind)
      (assoc :artifact/verifier lifecycle-summary-verifier-id)))

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

   opts:
     :artifacts  a seq of force-auth-add-held evidence artifacts (as produced by
                 build-force-auth-add-held). Each is re-verified before counting.

   Commits aggregate counts, amount sums and ranges, cardinality, a triage view
   of non-passing artifacts (with a per-item invalidity reason), an amount
   integrity triage (missing / non-numeric / negative amounts are never counted
   into the sums), a view of unverified authorizations, and a catalogue of
   sub-category summaries (account, reason, authorization, consumer, owner,
   position, authorization type, and the token × direction breakdown).

   Two arities:
     (build-force-auth-add-held-summary {:artifacts [...]})   legacy opts form
     (build-force-auth-add-held-summary members options)      members + options form
   Both produce the same artifact. Construction and validation are separate:
   the builder performs content-addressing; check-aggregate performs the
   aggregate membership and reconciliation check."
  ([opts]
   (build-force-auth-add-held-summary (or (:artifacts opts) []) opts))
  ([members options]
   (let [artifacts (vec (or members []))
         valid? valid-force-auth-add-held?
         valid-count (count (filter valid? artifacts))
         invalid-artifacts (into []
                                 (keep-indexed (fn [i a]
                                                 (when-not (valid? a)
                                                   {:index i
                                                    :adjustment-id (:held/adjustment-id a)
                                                    :reason (cond
                                                              (not= add-held-schema-version (:schema-version a))
                                                              :schema-version-mismatch
                                                              (not= add-held-kind (:artifact/kind a))
                                                              :artifact-kind-mismatch
                                                              (not= add-held-verifier-id (:artifact/verifier a))
                                                              :verifier-mismatch
                                                              :else :content-hash-mismatch)})))
                                 artifacts)
         scope-verified (count (filter :authorization/scope-verifies? artifacts))
         unverified-auth-ids (vec (sort
                                   (distinct
                                    (keep (fn [a]
                                            (when-not (:authorization/scope-verifies? a)
                                              (:authorization/id a)))
                                          artifacts))))
         by-token (frequencies (keep :held/token artifacts))
         by-direction (frequencies (keep :held/direction artifacts))
         indexed (map-indexed vector artifacts)
         amount-issues (into []
                             (keep (fn [[i a]]
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
                             indexed)
         issue-count (fn [issue-kind]
                       (count (filter #(= issue-kind (:amount-issue %)) amount-issues)))
         amounts (vec (keep (fn [[_ a]]
                              (let [amt (:held/amount a)]
                                (when (and (some? amt) (number? amt))
                                  (long amt))))
                            indexed))
         total-amount (reduce + 0 amounts)
         min-amount (when (seq amounts) (apply min amounts))
         max-amount (when (seq amounts) (apply max amounts))
         consumed-ats (->> artifacts (keep :held/consumed-at) (map long) vec)
         consumed-at-earliest (when (seq consumed-ats) (apply min consumed-ats))
         consumed-at-latest (when (seq consumed-ats) (apply max consumed-ats))
         sorted-freq (fn [k] (into (sorted-map) (frequencies (keep k artifacts))))
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
                                artifacts)))
         by-token-direction (frequencies
                             (keep (fn [a]
                                     (when-let [t (:held/token a)]
                                       (when-let [d (:held/direction a)]
                                         [(keyword t) (keyword d)])))
                                   artifacts))
         categories {:by-account (sorted-freq :held/account)
                     :by-reason (sorted-freq :held/reason)
                     :by-authorization (sorted-freq :authorization/id)
                     :by-consumed-by (sorted-freq :held/consumed-by)
                     :by-owner (sorted-freq :owner/address)
                     :by-position-id (sorted-freq :held/position-id)
                     :by-authorization-type (sorted-freq :authorization/type)
                     :by-token-direction (into (sorted-map) by-token-direction)}
         body {:schema-version add-held-summary-schema-version
               :artifact/kind add-held-summary-kind
               :artifact/verifier add-held-summary-verifier-id
               :total (count artifacts)
               :valid-count valid-count
               :invalid-count (- (count artifacts) valid-count)
               :invalid-artifacts (vec invalid-artifacts)
               :scope-verified-count scope-verified
               :scope-unverified-count (- (count artifacts) scope-verified)
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
               :distinct-adjustment-ids (count (distinct (keep :held/adjustment-id artifacts)))
               :distinct-tokens (count (distinct (keep :held/token artifacts)))
               :distinct-accounts (count (distinct (keep :held/account artifacts)))
               :distinct-owners (count (distinct (keep :owner/address artifacts)))
               :by-token (into (sorted-map) by-token)
               :by-direction (into (sorted-map) by-direction)
               :amount-by-token (sum-by :held/token)
               :amount-by-direction (sum-by :held/direction)
               :amount-by-account (sum-by :held/account)
               :amount-by-owner (sum-by :owner/address)
               :categories categories}]
     (finalize-artifact body))))

(defn valid-force-auth-add-held-summary?
  "Two arities.

   (valid-force-auth-add-held-summary? report) — content-addressed reader:
   re-verifies a force-auth-add-held-summary artifact (v2) by checking schema
   version, kind, verifier, and content hash. This does NOT check aggregate
   membership or reconciliation.

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
        without-v2 (reduce dissoc report add-held-summary-v2-only-keys)]
    (-> without-v2
        (dissoc :artifact/hash :artifact/preimage)
        (assoc :schema-version add-held-summary-v1-schema-version)
        (assoc :artifact/kind add-held-summary-kind)
        (assoc :artifact/verifier add-held-summary-verifier-id)
        (assoc :categories v1-categories))))

(defn build-force-auth-add-held-summary-v1
  "Build a v1-shaped summary artifact from a collection of force-auth-add-held
   artifacts. Provided for migration/backward-compatibility testing; production
   callers should use the v2 builder.

   Two arities:
     (build-force-auth-add-held-summary-v1 {:artifacts [...]})   legacy opts form
     (build-force-auth-add-held-summary-v1 members options)      members + options form"
  ([opts]
   (build-force-auth-add-held-summary-v1 (or (:artifacts opts) []) opts))
  ([members options]
   (let [v2 (build-force-auth-add-held-summary members options)
         v1-body (downgrade-add-held-summary-v2->v1 v2)]
     (finalize-artifact v1-body))))

(defn valid-force-auth-add-held-summary-v1?
  "Two arities.

   (valid-force-auth-add-held-summary-v1? report) — migration reader for
   persisted v1 artifacts: verifies schema-version, kind, and verifier, then
   recomputes the v1 content hash by projecting away the v2-only fields.

   (valid-force-auth-add-held-summary-v1? summary members options) — aggregate
   predicate: delegates to check-aggregate for the v1 target. True only when
   the summary is a well-formed v1 aggregate consistent with the member set.
   Unlike the 1-arity migration reader, the aggregate check rejects v2-only and
   unknown keys and reconciles every derivable field against the members."
  ([report]
   (and (map? report)
        (= add-held-summary-v1-schema-version (:schema-version report))
        (= add-held-summary-kind (:artifact/kind report))
        (= add-held-summary-verifier-id (:artifact/verifier report))
        (string? (:artifact/hash report))
        (string? (:artifact/preimage report))
        (let [v1-body (downgrade-add-held-summary-v2->v1 report)]
          (= (:artifact/hash report)
             (str "sha256:" (hash/domain-hash :evidence-record v1-body))))))
  ([summary members options]
   (:valid? (check-aggregate summary members options))))

;; ═══════════════════════════════════════════════════════════════════════════
;; force-auth-add-held-summary — aggregate boundary, membership, reconciliation
;; ═══════════════════════════════════════════════════════════════════════════
;;
;; check-aggregate is a pure, deterministic, data-only checker. It takes a
;; summary artifact AND the member set it is claimed to aggregate over, then:
;;
;;   1. verifies the aggregate identity (kind, schema version, verifier, content
;;      hash, exact shape — rejecting v2-only and unknown keys on a v1 target);
;;   2. validates every member through the canonical exact-kind
;;      valid-force-auth-add-held? verifier (never a weaker base/add predicate);
;;   3. reconciles every derivable summary field against a canonical
;;      recomputation from the validated member set (never trusts stored values).
;;
;; Construction (build-force-auth-add-held-summary) and validation
;; (check-aggregate) are separate: a builder output is not automatically a
;; passing aggregate, and check-aggregate performs no coercion of malformed
;; input. Invalid members always make the aggregate non-passing, are surfaced
;; in :invalid-members / :mismatches, and their amounts never influence the
;; financial totals of the canonical recomputation.
;;
;; Boundary direction: this section depends only on the public validation and
;; projection operations of the lower layers in this namespace. The lower-layer
;; artifacts (:force-auth-add-held) do not depend on the summary implementation.

(defn- content-hash-valid?
  "True when the artifact's :artifact/hash re-derives from its exact body
   (the artifact with the :artifact/hash and :artifact/preimage envelope
   removed). Independent of schema/kind/verifier identity checks."
  [report]
  (and (map? report)
       (string? (:artifact/hash report))
       (string? (:artifact/preimage report))
       (let [body (dissoc report :artifact/hash :artifact/preimage)]
         (= (:artifact/hash report)
            (str "sha256:" (hash/domain-hash :evidence-record body))))))

(defn- member-family-version?
  "True when a schema-version string belongs to the force-auth-add-held member
   artifact family (a different version of the member), as opposed to an
   unrelated artifact or a summary version."
  [v]
  (and (string? v)
       (str/starts-with? v "force-auth-add-held.")
       (not (str/starts-with? v "force-auth-add-held-summary"))))

(defn- classify-member
  "Primary per-member classification. Returns nil when the member is a fully
   valid force-auth-add-held artifact (canonical content-addressed verification
   plus required identity fields and a verified authorization binding), else a
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
    (not= add-held-schema-version (:schema-version m))
    (if (member-family-version? (:schema-version m))
      :unsupported-member-version
      :schema-version-mismatch)
    (not= add-held-verifier-id (:artifact/verifier m))
    :verifier-mismatch
    (not (valid-force-auth-add-held? m))
    :content-hash-mismatch
    (nil? (:authorization/id m))
    :missing-authorization-id
    (nil? (:held/adjustment-id m))
    :missing-adjustment-id
    (not (true? (:authorization/scope-verifies? m)))
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

(defn- canonical-summary-fields
  "Canonical projection of the aggregate field set over the validated member
   subset. Financial totals, category counts, scope counts, and cardinality
   projections are computed ONLY from valid members, and only numeric amounts
   contribute to monetary totals. A missing or non-numeric amount is never
   coerced to zero.

   valid-indexed entries carry the ORIGINAL member index so triage views can be
   traced back to the supplied member list."
  [valid-indexed member-count valid-count]
  (let [valid-members (mapv :member valid-indexed)
        invalid-count (- member-count valid-count)
        scope-verified (count (filter :authorization/scope-verifies? valid-members))
        amount-issues (into []
                            (keep (fn [{:keys [index member]}]
                                    (let [amt (:held/amount member)
                                          issue (cond
                                                  (nil? amt) :missing-amount
                                                  (not (number? amt)) :non-numeric-amount
                                                  (neg? (double amt)) :negative-amount
                                                  :else nil)]
                                      (when issue
                                        {:index index
                                         :adjustment-id (:held/adjustment-id member)
                                         :amount-issue issue}))))
                            valid-indexed)
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
                         (map (fn [[cat-k field]]
                                [cat-k (sorted-freq field)]))
                         category-field)
        categories (assoc categories :by-token-direction (into (sorted-map) by-token-direction))]
    {:total member-count
     :valid-count valid-count
     :invalid-count invalid-count
     :scope-verified-count scope-verified
     :scope-unverified-count (- valid-count scope-verified)
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

(defn- v1-summary-shape-valid?
  "Exact-shape check for a v1 summary body: no v2-only key, no unknown top-level
   key, and category keys within the v1 category set."
  [report]
  (let [body (dissoc report :artifact/hash :artifact/preimage)
        v1-category-set (set add-held-summary-v1-category-keys)]
    (and (every? (fn [k] (not (contains? body k))) add-held-summary-v2-only-keys)
         (every? (fn [k] (contains? add-held-summary-v1-body-keys k)) (keys body))
         (let [cats (:categories body)]
           (and (map? cats)
                (every? (fn [k] (contains? v1-category-set k)) (keys cats)))))))

(defn- v2-summary-shape-valid?
  "Exact-shape check for a v2 summary body: no unknown top-level key, and
   category keys within the v1 ∪ v2 category set."
  [report]
  (let [body (dissoc report :artifact/hash :artifact/preimage)
        v2-body-keys (into add-held-summary-v1-body-keys add-held-summary-v2-only-keys)
        v2-category-set (set (into add-held-summary-v1-category-keys
                                   add-held-summary-v2-category-keys))]
    (and (every? (fn [k] (contains? v2-body-keys k)) (keys body))
         (let [cats (:categories body)]
           (and (map? cats)
                (every? (fn [k] (contains? v2-category-set k)) (keys cats)))))))

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
  "Derivable summary fields committed by the v2 shape (superset of v1)."
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
         [[:amount-by-owner] :amount-by-owner]]))

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
   copies identity fields, totals, or category values from any supplied summary.

   options:
     :summary-version  :v1 (default) or :v2

   Returns a finalized content-addressed artifact in the requested shape,
   computed over the validated member subset (invalid members are excluded from
   totals and categories). This is the value check-aggregate compares a supplied
   summary against."
  [members options]
  (let [options (or options {})
        version (resolve-summary-version options)
        members (vec (or members []))
        {:keys [valid-indexed valid-count]} (validate-member-set members options)
        fields (canonical-summary-fields valid-indexed (count members) valid-count)
        body (if (= version :v1)
               {:schema-version add-held-summary-v1-schema-version
                :artifact/kind add-held-summary-kind
                :artifact/verifier add-held-summary-verifier-id
                :total (:total fields)
                :valid-count (:valid-count fields)
                :invalid-count (:invalid-count fields)
                :scope-verified-count (:scope-verified-count fields)
                :scope-unverified-count (:scope-unverified-count fields)
                :total-amount (:total-amount fields)
                :distinct-adjustment-ids (:distinct-adjustment-ids fields)
                :by-token (:by-token fields)
                :by-direction (:by-direction fields)
                :categories (select-keys (:categories fields)
                                         add-held-summary-v1-category-keys)}
               {:schema-version add-held-summary-schema-version
                :artifact/kind add-held-summary-kind
                :artifact/verifier add-held-summary-verifier-id
                :total (:total fields)
                :valid-count (:valid-count fields)
                :invalid-count (:invalid-count fields)
                :scope-verified-count (:scope-verified-count fields)
                :scope-unverified-count (:scope-unverified-count fields)
                :total-amount (:total-amount fields)
                :min-amount (:min-amount fields)
                :max-amount (:max-amount fields)
                :missing-amount-count (:missing-amount-count fields)
                :non-numeric-amount-count (:non-numeric-amount-count fields)
                :negative-amount-count (:negative-amount-count fields)
                :amount-issues (:amount-issues fields)
                :consumed-at-earliest (:consumed-at-earliest fields)
                :consumed-at-latest (:consumed-at-latest fields)
                :distinct-adjustment-ids (:distinct-adjustment-ids fields)
                :distinct-tokens (:distinct-tokens fields)
                :distinct-accounts (:distinct-accounts fields)
                :distinct-owners (:distinct-owners fields)
                :by-token (:by-token fields)
                :by-direction (:by-direction fields)
                :amount-by-token (:amount-by-token fields)
                :amount-by-direction (:amount-by-direction fields)
                :amount-by-account (:amount-by-account fields)
                :amount-by-owner (:amount-by-owner fields)
                :categories (:categories fields)})]
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
        {:keys [member-count valid-count valid-indexed invalid
                duplicate-member-indexes duplicate-adjustment-indexes
                duplicate-authorization-indexes warnings]}
        (validate-member-set members options)
        invalid-count (- member-count valid-count)
        members-valid? (zero? invalid-count)
        fields (canonical-summary-fields valid-indexed member-count valid-count)
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
