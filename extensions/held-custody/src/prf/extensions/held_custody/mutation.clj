(ns prf.extensions.held-custody.mutation
  "Held-custody mutation evidence (extension).

   A protocol-neutral, direction-neutral representation of a force-authorised
   held-custody mutation (add-held, sub-held, finalize-released, refund-held,
   ...). The artifact commits the canonical force-authorisation scope projection
   so the authorized-scope binding is independently re-derivable; :valid? and
   :verified? are deliberately separated (see check-force-auth-held-mutation).

   This extension depends ONLY on approved public PRF core namespaces
   (resolver-sim.evidence.artifact, resolver-sim.assurance.force-authorisation)
   and never on a protocol implementation.

   BOUNDARY GUARD — This namespace MUST NOT import or depend on:
     - resolver-sim.protocols.sew
     - any form under protocols_src/
     - any other extension namespace"
  (:require [resolver-sim.evidence.artifact :as artifact]
            [resolver-sim.assurance.force-authorisation :as fa]))

;; ── artifact contract ──────────────────────────────────────────────────────

(def schema-version
  "Canonical schema version for a held-custody mutation evidence artifact."
  "force-auth-held-custody-mutation.v1")

(def artifact-kind
  "Canonical :artifact/kind for a held-custody mutation evidence artifact."
  :force-auth-held-custody-mutation)

(def verifier-id
  "Canonical verifier identifier for a held-custody mutation evidence artifact."
  "force-auth-held-custody-mutation.verifier.v1")

(def scope-derivation-id
  "Algorithm/version identifier for the committed :authorization-scope/projection-hash."
  "force-authorisation-scope-hash.v1")

;; ── action / direction contract ────────────────────────────────────────────

(def action->direction
  "Closed action → direction mapping for capability contract version 1.
   Action is the operation; direction is the custody classification. The
   precise source action is preserved — outward operations are NOT normalised
   to :sub-held. Unknown actions fail closed; extending the accepted vocabulary
   requires an explicit capability / verification-contract version change."
  {:add-held          :in
   :sub-held          :out
   :finalize-released :out
   :refund-held       :out})

(def supported-actions
  "Set of actions accepted by this contract version."
  (set (keys action->direction)))

(defn normalize-action
  "Normalize an action to a canonical keyword (keyword or string spelling)."
  [a]
  (when (some? a)
    (if (keyword? a) a (keyword (name a)))))

(defn normalize-direction
  "Normalize a held-custody mutation direction to a keyword (:in / :out)."
  [d]
  (when (some? d)
    (if (keyword? d) d (keyword (name d)))))

(defn action-direction-valid?
  "True when the action is a supported closed-set action AND its mapped
   direction equals the supplied direction."
  [action direction]
  (and (contains? supported-actions action)
       (= (get action->direction action) direction)))

;; ── scope projection ───────────────────────────────────────────────────────

(defn scope-projection
  "Canonical authorized-scope projection for a held-custody mutation, using the
   existing force-authorisation scope vocabulary and hashing rules. Optional
   scope keys (:held/position-id, :parameter/context, :parameter/address) are
   included only when present, so the projection mirrors the grant scope
   construction exactly and reconciles with its :authorization/scope-hash. The
   :operation discriminator is metadata and is NOT part of the committed
   scope-hash."
  [m]
  (let [base {:authorization/id (:authorization/id m)
              :authorization/type (or (:authorization/type m) :force-authorisation)
              :held/direction (:held/direction m)
              :token (:held/token m)
              :amount (:held/amount m)
              :held/account (:held/account m)
              :owner/address (:owner/address m)
              :held/reason (:held/reason m)
              :held/workflow-id (:held/workflow-id m)}]
    (assoc (fa/normalize-force-authorisation-scope
            (cond-> base
              (some? (:held/position-id m)) (assoc :held/position-id (:held/position-id m))
              (some? (:parameter/context m)) (assoc :parameter/context (:parameter/context m))
              (some? (:parameter/address m)) (assoc :parameter/address (:parameter/address m))))
           :operation :held-custody-mutation)))

(defn projection-hash
  "Content-addressed hash of the scope part of a projection, using the existing
   force-authorisation scope hashing rule. The :operation discriminator is
   excluded so this reconciles with the grant's :authorization/scope-hash."
  [projection]
  (fa/force-authorisation-scope-hash (dissoc projection :operation)))

(defn projection-integrity-valid?
  "True when the projection is a map and its committed projection-hash equals
   hash(scope part of the projection)."
  [artifact]
  (let [projection (:authorization-scope/projection artifact)]
    (and (map? projection)
         (string? (:authorization-scope/projection-hash artifact))
         (= (:authorization-scope/projection-hash artifact)
            (projection-hash projection)))))

(defn mutation-scope-compatible?
  "True when the held mutation's own fields agree with its committed scope
   projection (direction, token, amount, account, owner, reason, workflow-id)."
  [artifact]
  (let [p (:authorization-scope/projection artifact)
        compatible? (fn [k pk]
                      (= (get artifact k) (get p pk)))]
    (and (compatible? :held/direction :held/direction)
         (compatible? :held/token :token)
         (compatible? :held/amount :amount)
         (compatible? :held/account :held/account)
         (compatible? :owner/address :owner/address)
         (compatible? :held/reason :held/reason)
         (compatible? :held/workflow-id :held/workflow-id))))

;; ── builder ────────────────────────────────────────────────────────────────

(defn build-force-auth-held-mutation
  "Build a force-auth-held-custody-mutation.v1 member from a verified
   authorisation and a held-custody mutation input.

     verified-authorisation — the authorization record (must carry
                              :authorization/id and :authorization/scope-hash)
     adjustment            — the stable mutation input:
                              {:mutation/id
                               :held/action :add-held | :sub-held | ...
                               :held/direction :in | :out
                               :held/amount (non-negative number)
                               :held/token :held/account :owner/address
                               :held/reason :held/workflow-id :held/position-id
                               :consumed-at :consumed-by}
     options               — reserved for future use

   FAIL-FAST: unknown action, action/direction inconsistency, and non-positive
   amounts throw ex-info with structured data. No stored scope-verifies? flag is
   ever written."
  [verified-authorisation adjustment _options]
  (let [action (normalize-action (:held/action adjustment))
        direction (normalize-direction (:held/direction adjustment))
        amount (:held/amount adjustment)
        _ (when-not (action-direction-valid? action direction)
            (throw (ex-info "held-custody mutation: unknown action or action/direction mismatch"
                            {:error :held-custody/invalid-action-direction
                             :held/action action
                             :held/direction direction})))
        _ (when-not (and (some? amount) (number? amount) (not (neg? amount)))
            (throw (ex-info "held-custody mutation: amount must be a non-negative number"
                            {:error :held-custody/invalid-amount
                             :held/amount amount})))
        projection (scope-projection (assoc adjustment
                                            :authorization/id (:authorization/id verified-authorisation)
                                            :authorization/type (:authorization/type verified-authorisation)))
        body {:schema-version schema-version
              :artifact/kind artifact-kind
              :artifact/verifier verifier-id
              :authorization/id (:authorization/id verified-authorisation)
              :authorization/type (or (:authorization/type verified-authorisation)
                                      :force-authorisation)
              :mutation/id (:mutation/id adjustment)
              :held/action action
              :held/direction direction
              ;; scope fields mirror the committed projection exactly (canonical
              ;; normalized vocabulary), so mutation/scope compatibility holds
              ;; and the extension never invents an alternative representation
              :held/amount (:amount projection)
              :held/token (:token projection)
              :held/account (:held/account projection)
              :held/position-id (:held/position-id projection)
              :owner/address (:owner/address projection)
              :held/reason (:held/reason projection)
              :held/workflow-id (:held/workflow-id projection)
              :authorization-scope/projection projection
              :authorization-scope/projection-hash (projection-hash projection)
              :held/consumed-at (:consumed-at adjustment)
              :held/consumed-by (:consumed-by adjustment)}]
    (artifact/finalize-artifact body)))

;; ── checker ────────────────────────────────────────────────────────────────

(defn check-force-auth-held-mutation
  "Structured, deterministic, data-only check of a held-custody mutation member.

     (check-force-auth-held-mutation artifact context)

   context is optional: {:authorization <authorization-record>}. When supplied,
   the checker independently verifies the authorization reference (that the
   committed projection-hash equals the grant's :authorization/scope-hash and
   that the authorization id matches). When absent, the artifact is still
   checked on every intrinsic property but :authorization-reference-verified?
   is false and the reference is surfaced in :unverified.

   Semantics:
     :valid?     — artifact is intrinsically valid (exact shape, identity, hash,
                   canonical commitment, action/direction, amount, projection
                   integrity, mutation/scope compatibility) — every property
                   derivable from the artifact itself.
     :verified?  — :valid? AND all required external references (the referenced
                   authorisation) were supplied and independently verified.

   Returns {:valid? :verified? :status :checks :unverified :invalid-members []}."
  [artifact context]
  (let [authorization (:authorization context)
        ;; The held-custody .v1 contract uses the strict :exact preimage policy
        ;; (decodes to body AND canonical fixed-point serialization).
        intrinsic (artifact/valid-artifact? artifact schema-version artifact-kind
                                            verifier-id :exact)
        action (normalize-action (:held/action artifact))
        direction (normalize-direction (:held/direction artifact))
        amount (:held/amount artifact)
        action-direction-ok? (action-direction-valid? action direction)
        amount-ok? (and (some? amount) (number? amount) (not (neg? amount)))
        identity-ok? (and (some? (:authorization/id artifact))
                          (some? (:mutation/id artifact)))
        projection-ok? (projection-integrity-valid? artifact)
        scope-compat? (mutation-scope-compatible? artifact)
        auth-id (:authorization/id artifact)
        auth-ok? (and (some? authorization)
                      (= auth-id (:authorization/id authorization))
                      (some? (:authorization/scope-hash authorization))
                      (= (:authorization-scope/projection-hash artifact)
                         (:authorization/scope-hash authorization)))
        unverified (cond
                     (not (some? authorization))
                     [{:reason :authorization-record-unavailable
                       :authorization/id auth-id}]
                     (not auth-ok?)
                     [{:reason :authorization-reference-mismatch
                       :authorization/id auth-id}]
                     :else [])
        valid? (and intrinsic action-direction-ok? amount-ok? identity-ok?
                       projection-ok? scope-compat?)
        verified? (and valid? auth-ok?)
        checks {:artifact-integrity-valid? intrinsic
                :action-direction-valid? action-direction-ok?
                :amount-valid? amount-ok?
                :identity-fields-valid? identity-ok?
                :projection-integrity-valid? projection-ok?
                :mutation-scope-compatible? scope-compat?
                :authorization-reference-verified? auth-ok?}]
    {:valid? valid?
     :verified? verified?
     :status (cond
               (not valid?) :invalid
               verified? :valid-verified
               :else :valid-unverified)
     :checks checks
     :unverified (vec unverified)
     :invalid-members []}))

(defn valid-force-auth-held-mutation?
  "Intrinsic validity: every property derivable from the artifact itself passes.
   Does NOT require authorization context."
  [artifact context]
  (:valid? (check-force-auth-held-mutation artifact context)))

(defn verified-force-auth-held-mutation?
  "Full assurance: the artifact is intrinsically valid AND every required
   external reference (the referenced authorisation) was supplied and verified.
   Must NOT pass without the authorization context."
  [artifact context]
  (:verified? (check-force-auth-held-mutation artifact context)))
