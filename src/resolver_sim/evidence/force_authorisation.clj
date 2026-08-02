(ns resolver-sim.evidence.force-authorisation
  "Evidence contract definitions for force-authorisation lifecycle.

   Defines the structure and validation of forensic evidence for
   force-authorisation grant, execution, consumption, and custody linkage.
   Protocol-independent: operates on evidence maps and returns validation maps.

   BOUNDARY GUARD — This namespace MUST NOT import or depend on:
     - resolver-sim.protocols.sew
     - any form under protocols_src/
     - benchmarks/packs/sew/"
  (:require [resolver-sim.hash.canonical :as hash]
            [resolver-sim.assurance.force-authorisation :as fa]))

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

(def ^:private add-held-schema-version "force-auth-add-held.v1")
(def ^:private add-held-verifier-id "force-auth-add-held-verifier.v1")

(def ^:private lifecycle-schema-version "force-auth-lifecycle.v1")
(def ^:private lifecycle-verifier-id "force-auth-lifecycle-verifier.v1")

(def ^:private lifecycle-summary-schema-version "force-auth-lifecycle-summary.v1")
(def ^:private lifecycle-summary-verifier-id "force-auth-lifecycle-summary-verifier.v1")

(def ^:private add-held-summary-schema-version "force-auth-add-held-summary.v1")
(def ^:private add-held-summary-verifier-id "force-auth-add-held-summary-verifier.v1")

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
              :artifact/kind :force-auth-add-held
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
  (valid-artifact? report add-held-schema-version :force-auth-add-held
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
              :artifact/kind :force-auth-lifecycle
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
  (valid-artifact? report lifecycle-schema-version :force-auth-lifecycle
                   lifecycle-verifier-id))

;; ── force-auth-lifecycle-summary ───────────────────────────────────────────

(defn build-force-auth-lifecycle-summary
  "Build the versioned, content-addressed summary evidence artifact for the
   force-authorisation lifecycle: counts by status, orphan consumptions, and
   the lifecycle-consistency outcome.

   opts:
     :authorisations        map of {auth-id record}
     :consumption-registry  map of {auth-id consumption-entry}
     :now                   current block time for expiry classification"
  [opts]
  (let [auths (fa/normalize-force-authorisation-records (:authorisations opts))
        registry (fa/normalize-force-authorisation-consumption-registry
                  (:consumption-registry opts))
        now (long (or (:now opts) 0))
        statuses (mapv :authorization/status (vals auths))
        expired (count (filter (fn [r]
                                 (and (:expires-at r) (>= now (long (:expires-at r)))))
                               (vals auths)))
        body {:schema-version lifecycle-summary-schema-version
              :artifact/kind :force-auth-lifecycle-summary
              :artifact/verifier lifecycle-summary-verifier-id
              :total (count auths)
              :active (count (filter #(= :active %) statuses))
              :consumed (count (filter #(= :consumed %) statuses))
              :expired expired
              :orphan-consumptions (count (remove #(contains? auths %) (keys registry)))
              :lifecycle-consistent?
              (:holds? (fa/verify-authorisation-lifecycle-consistency auths registry))}]
    (finalize-artifact body)))

(defn valid-force-auth-lifecycle-summary?
  "Re-verify a force-auth-lifecycle-summary evidence artifact."
  [report]
  (valid-artifact? report lifecycle-summary-schema-version
                   :force-auth-lifecycle-summary lifecycle-summary-verifier-id))

;; ── force-auth-add-held-summary ────────────────────────────────────────────

(defn build-force-auth-add-held-summary
  "Build the versioned, content-addressed summary evidence artifact over a
   collection of force-auth-add-held evidence artifacts.

   opts:
     :artifacts  a seq of force-auth-add-held evidence artifacts (as produced by
                 build-force-auth-add-held). Each is re-verified before counting.

   Commits aggregate counts, amount sums and ranges, cardinality, a triage view
   of non-passing artifacts, and a catalogue of sub-category summaries (account,
   reason, authorization, consumer, owner, position, authorization type, and the
   token × direction breakdown)."
  [{:keys [artifacts]}]
  (let [artifacts (vec (or artifacts []))
        valid? valid-force-auth-add-held?
        valid-count (count (filter valid? artifacts))
        invalid-artifacts (into []
                                (keep-indexed (fn [i a]
                                                (when-not (valid? a)
                                                  {:index i
                                                   :adjustment-id (:held/adjustment-id a)})))
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
        amounts (mapv #(long (or (:held/amount %) 0)) artifacts)
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
                                     (update m v (fnil + 0) (long (or (:held/amount a) 0)))
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
              :artifact/kind :force-auth-add-held-summary
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
    (finalize-artifact body)))

(defn valid-force-auth-add-held-summary?
  "Re-verify a force-auth-add-held-summary evidence artifact."
  [report]
  (valid-artifact? report add-held-summary-schema-version
                   :force-auth-add-held-summary add-held-summary-verifier-id))
