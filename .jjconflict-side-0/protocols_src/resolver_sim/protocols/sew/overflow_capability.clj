(ns resolver-sim.protocols.sew.overflow-capability
  "Over-capacity failover as a coherent capability lifecycle.

   Central model:
     verified force-authorisation -> durable overflow capability
       -> bounded executions -> exhaustion | expiry | revocation

   A :capacity-failover force-authorisation grant is verified and atomically
   consumed at activation to mint a content-addressed overflow capability. The
   capability is then spent within hard bounds (workflow scope, time window,
   workflow count) under a committed policy.

   Integrity model: the capability is a content-addressed record whose
   :overflow-capability/hash is a domain-separated canonical hash over the whole
   record minus its self-hash. Direct injection or mutation of a persisted
   record changes the recomputed hash and fails verification. Capability records
   are only constructible through the validated constructor (activation) or the
   explicitly named test/migration escape hatch."
  (:require [resolver-sim.protocols.sew.types :as t]
            [resolver-sim.time.context :as time-ctx]
            [resolver-sim.hash.canonical :as hash]))

;; ---------------------------------------------------------------------------
;; Constants
;; ---------------------------------------------------------------------------

(def capability-schema-version "overflow-capability.v1")

(def capability-domain
  "Domain tag for the capability self-hash."
  :overflow-capability)

(def capacity-context-domain
  "Domain tag for the committed capacity-context hash."
  :overflow-capacity-context)

(def authorisation-scope-domain
  "Domain tag for a :capacity-failover grant's scope-hash."
  :overflow-authorisation-scope)

(def authorisation-class
  "The authorization class that mints an overflow capability."
  :capacity-failover)

(def capacity-policy-id-default "overflow-policy.v1")
(def capacity-policy-version-default "v1")

;; ---------------------------------------------------------------------------
;; Capacity derivation (shared with the interactive-override path)
;; ---------------------------------------------------------------------------
;;
;; Mirrors DRM.ResolverCapacity semantics (sew-protocol
;; DecentralizedResolutionModule): a resolver is at capacity when
;; max-concurrent > 0 and current-active >= max-concurrent.

(defn capacity-context
  "Derive the capacity context for a resolver from its capacity entry, the count
   of disputed escrows currently assigned to it, and the committed threshold."
  [resolver cap disputed-count capacity-threshold]
  (let [current-active (get cap :current-active 0)
        max-concurrent (get cap :max-concurrent 0)
        at-capacity? (and (pos? max-concurrent)
                          (>= current-active max-concurrent))]
    {:resolver resolver
     :capacity-threshold capacity-threshold
     :current-active current-active
     :max-concurrent max-concurrent
     :disputed-count disputed-count
     :at-capacity? at-capacity?}))

(defn over-capacity?
  "True when a derived capacity context reports the resolver at capacity."
  [ctx]
  (boolean (:at-capacity? ctx)))

(defn disputed-count-for
  "Count escrows currently :disputed and assigned to resolver."
  [world resolver]
  (count (filter (fn [[_ et]]
                   (and (= :disputed (:escrow-state et))
                        (= resolver (:dispute-resolver et))))
                 (:escrow-transfers world {}))))

(defn derive-capacity-context
  "Build the capacity context from live world state under a committed
   capacity-threshold. Context is re-derived at activation — it is never
   accepted as a caller assertion."
  [world resolver capacity-threshold]
  (capacity-context resolver
                    (get-in world [:resolver-capacities resolver])
                    (disputed-count-for world resolver)
                    capacity-threshold))

(defn capacity-context-hash
  "Commit a derived capacity context."
  [ctx]
  (hash/domain-hash capacity-context-domain ctx))

;; ---------------------------------------------------------------------------
;; :capacity-failover grant scope
;; ---------------------------------------------------------------------------

(defn capacity-failover-scope
  "Build the :capacity-failover scope map committed by a grant."
  [{:keys [authorization/id resolver failover-resolvers max-workflows expires-at
           capacity-policy-id]}]
  {:authorization/id id
   :authorization/type :force-authorisation
   :authorization/class authorisation-class
   :authorization/scope-kind :capacity-failover
   :overflow/resolver resolver
   :overflow/failover-resolvers (set failover-resolvers)
   :overflow/max-workflows (when max-workflows (long max-workflows))
   :overflow/expires-at (when expires-at (long expires-at))
   :overflow/capacity-policy-id capacity-policy-id})

(defn capacity-failover-scope-hash
  [scope]
  (hash/domain-hash authorisation-scope-domain scope))

(defn capacity-failover-scope-valid?
  "The grant's stored scope-hash must authenticate its stored scope."
  [grant]
  (let [scope (:authorization/scope grant)
        stored (:authorization/scope-hash grant)]
    (and scope
         stored
         (= stored (capacity-failover-scope-hash scope)))))

(defn- governance-origin?
  "The grant must have been created through the governance grant path."
  [grant]
  (let [prov (or (:authorization/provenance grant)
                 (:authorization/last-provenance grant))]
    (and prov
         (= :with-governance-actor (:authorization/check prov))
         (= :governance (:authorization/source prov))
         (= :governance (:authorization/source grant)))))

;; ---------------------------------------------------------------------------
;; Capability hashing
;; ---------------------------------------------------------------------------

(defn overflow-capability-preimage
  "The canonical preimage of a capability record: the record minus its self-hash."
  [record]
  (dissoc record :overflow-capability/hash))

(defn overflow-capability-hash
  "Recompute the content hash of a capability record."
  [record]
  (hash/domain-hash capability-domain (overflow-capability-preimage record)))

(defn overflow-capability?
  "Structural schema check for a capability record."
  [record]
  (and (map? record)
       (= capability-schema-version (:overflow-capability/version record))
       (string? (:overflow-capability/id record))
       (string? (:overflow-capability/hash record))
       (some? (:overflow-id record))))

;; ---------------------------------------------------------------------------
;; Activation: verify the originating force-authorisation
;; ---------------------------------------------------------------------------

(defn verify-overflow-authorisation
  "Verify a force-authorisation grant as the source of a :capacity-failover
   overflow capability. Returns {:ok true :grant grant} or {:ok false :error}.
   now is the current block time."
  [world grant auth-id now]
  (let [fail (fn [error] {:ok false :error error})]
    (cond
      (nil? grant)
      (fail :force-authorisation-not-found)

      (not= auth-id (:authorization/id grant))
      (fail :invalid-overflow-capability)

      (not= authorisation-class (:authorization/class grant))
      (fail :wrong-authorization-class)

      (not= :capacity-failover (:authorization/scope-kind grant))
      (fail :wrong-authorization-class)

      (not= :active (:authorization/status grant))
      (fail :force-authorisation-not-active)

      (:consumed? grant)
      (fail :force-authorisation-already-consumed)

      (get-in world [:force-authorisations/consumed auth-id])
      (fail :force-authorisation-already-consumed)

      (< now (or (:starts-at grant) 0))
      (fail :force-authorisation-not-yet-started)

      (and (:expires-at grant) (>= now (:expires-at grant)))
      (fail :force-authorisation-expired)

      (not= "activate-resolver-overflow" (:allowed-action grant))
      (fail :force-authorisation-action-mismatch)

      (not= :resolver-overcapacity (:reason grant))
      (fail :unauthorized-overflow-reason)

      (not (capacity-failover-scope-valid? grant))
      (fail :force-authorisation-scope-mismatch)

      (not (governance-origin? grant))
      (fail :invalid-force-authorisation-governance)

      :else
      {:ok true :grant grant})))

;; ---------------------------------------------------------------------------
;; Validated constructor
;; ---------------------------------------------------------------------------

(defn build-overflow-capability
  "Validated constructor for a content-addressed overflow capability.
   Requires a verified :capacity-failover grant, a derived capacity context,
   and the committed policy. Computes the integrity hash over the full record."
  [{:keys [overflow-id grant capacity-context workflow-scope now authorized-by
           provenance policy-id policy-version]
    :or {policy-id capacity-policy-id-default
         policy-version capacity-policy-version-default}}]
  (let [scope (:authorization/scope grant)
        base {:overflow-id overflow-id
              :overflow-capability/version capability-schema-version
              :overflow-capability/id (str "oc-" overflow-id)
              :overflow-capability/hash nil
              :authorization/id (:authorization/id grant)
              :authorization/class authorisation-class
              :authorization/hash (:authorization/scope-hash grant)
              :resolver (:overflow/resolver scope)
              :failover-resolvers (set (:overflow/failover-resolvers scope))
              :workflow-scope (set workflow-scope)
              :workflow-scope-root
              (hash/domain-hash capability-domain
                                {:predicate :disputed-and-assigned-to-primary
                                 :workflows (set workflow-scope)})
              :capacity-context capacity-context
              :capacity-context-hash (capacity-context-hash capacity-context)
              :capacity-policy-id policy-id
              :capacity-policy-version policy-version
              :created-at now
              :issued-at now
              :starts-at now
              :expires-at (:overflow/expires-at scope)
              :max-workflows (long (:overflow/max-workflows scope))
              :used-workflows #{}
              :execution-count 0
              :status :active
              :reason (:reason grant)
              :authorized-by authorized-by
              :authorization/provenance provenance
              :revocation nil
              :overflow-capability/executions []}]
    (assoc base :overflow-capability/hash (overflow-capability-hash base))))

(defn build-overflow-capability-for-test
  "EXPLICIT test/migration escape hatch. Constructs a capability record without
   going through the activation handler. Callers must supply a verified
   :capacity-failover grant, a derived capacity context, and the committed
   policy. Only tests and explicit migration paths may call this."
  [opts]
  (build-overflow-capability opts))

;; ---------------------------------------------------------------------------
;; Execution: detailed verifier
;; ---------------------------------------------------------------------------

(defn- dispute-resolver-for
  [world workflow-id]
  (get-in world [:escrow-transfers workflow-id :dispute-resolver]))

(defn- escrow-state-for
  [world workflow-id]
  (get-in world [:escrow-transfers workflow-id :escrow-state]))

(defn- valid-expiry-value?
  [record]
  (and (some? (:expires-at record))
       (number? (:expires-at record))
       (number? (:starts-at record))))

(defn- valid-time-window?
  [record now]
  (and (valid-expiry-value? record)
       (<= now (:expires-at record))
       (>= now (:starts-at record))))

(defn- within-cap?
  [record]
  (and (number? (:execution-count record))
       (number? (:max-workflows record))
       (pos? (:max-workflows record))
       (< (:execution-count record) (:max-workflows record))))

(defn- policy-committed?
  [policy record]
  (let [current (or (:policy/id policy) capacity-policy-id-default)]
    (= current (:capacity-policy-id record))))

(defn- authorisation-bound?
  "The originating grant exists, was consumed for THIS capability, and its
   committed scope-hash matches the capability's :authorization/hash. This binds
   the capability to exactly one consumed force-authorisation and prevents
   re-minting from a consumed grant."
  [world record]
  (let [auth-id (:authorization/id record)
        grant (get-in world [:force-authorisations auth-id])
        consumed (get-in world [:force-authorisations/consumed auth-id])]
    (and grant consumed
         (= authorisation-class (:authorization/class grant))
         (= :consumed (:authorization/status grant))
         (= :overflow-activation (:consumption/kind consumed))
         (= (:overflow-capability/id consumed) (:overflow-capability/id record))
         (= (:authorization/id consumed) auth-id)
         (= (:authorization/hash record) (:authorization/scope-hash grant)))))

(defn capability-state-consistent?
  "Cross-validate mutable state against the committed preimage. Detects state
   mutation (e.g. a used-workflow injected directly without an execution)."
  [record]
  (let [used (:used-workflows record #{})
        count (:execution-count record 0)
        scope (:workflow-scope record #{})
        max (or (:max-workflows record) 0)]
    (and (set? used)
         (= count (count used))
         (every? #(contains? scope %) used)
         (<= (count used) max)
         (= count (count (:overflow-capability/executions record []))))))

(defn verify-overflow-capability-execution
  "Detailed verifier for execute-overflow-resolution. Returns
   {:ok true :record record} or {:ok false :error <kw> :detail {...}}.
   Runs checks in a fixed order; the first failure wins. Never throws on
   malformed numeric fields — it fails closed instead."
  [world record workflow-id caller now policy]
  (let [fail (fn [error detail] {:ok false :error error :detail (or detail {})})]
    (cond
      (not (overflow-capability? record))
      (fail :invalid-overflow-capability {:reason :schema})

      (not= (:overflow-capability/hash record) (overflow-capability-hash record))
      (fail :invalid-overflow-capability {:reason :hash-mismatch})

      (not= authorisation-class (:authorization/class record))
      (fail :wrong-authorization-class {})

      (not (authorisation-bound? world record))
      (fail :invalid-overflow-capability {:reason :authorisation-binding})

      (not (capability-state-consistent? record))
      (fail :invalid-overflow-capability {:reason :state-inconsistent})

      (not= :active (:status record))
      (fail (case (:status record)
              :revoked :revoked-overflow-capability
              :exhausted :exhausted-overflow-capability
              :invalid-overflow-capability)
            {:status (:status record)})

      (not (valid-time-window? record now))
      (fail (if (valid-expiry-value? record)
              (if (< now (:starts-at record))
                :invalid-overflow-capability
                :expired-overflow-capability)
              :invalid-overflow-capability)
            {:expires-at (:expires-at record) :starts-at (:starts-at record)})

      (not= (:resolver record) (dispute-resolver-for world workflow-id))
      (fail :primary-resolver-mismatch
            {:workflow-id workflow-id :primary (:resolver record)})

      (not (contains? (:failover-resolvers record) caller))
      (fail :resolver-not-authorized {:caller caller})

      (not (contains? (:workflow-scope record) workflow-id))
      (fail :workflow-out-of-scope
            {:workflow-id workflow-id :scope (:workflow-scope record)})

      (not= :disputed (escrow-state-for world workflow-id))
      (fail :workflow-not-disputed {:workflow-id workflow-id})

      (contains? (:used-workflows record) workflow-id)
      (fail :workflow-already-consumed {:workflow-id workflow-id})

      (not (within-cap? record))
      (fail :exhausted-overflow-capability
            {:count (:execution-count record) :max (:max-workflows record)})

      (not (policy-committed? policy record))
      (fail :overflow-policy-mismatch
            {:committed (:capacity-policy-id record)
             :current (or (:policy/id policy) capacity-policy-id-default)})

      :else
      {:ok true :record record})))

;; ---------------------------------------------------------------------------
;; Execution: transition
;; ---------------------------------------------------------------------------

(defn build-execution-evidence
  "Build the fixed execution-evidence fields for an overflow execution. The
   transition fills in status/count before and after."
  [record workflow-id caller is-release now resolution-hash]
  {:execution/schema-version "execution-provenance.v1"
   :execution/type :forced-capacity-failover
   :execution/basis :overflow-capability
   :execution/overflow-capability/id (:overflow-capability/id record)
   :execution/overflow-capability/hash (:overflow-capability/hash record)
   :execution/authorization/id (:authorization/id record)
   :execution/authorization/hash (:authorization/hash record)
   :execution/resolver (:resolver record)
   :execution/executed-by caller
   :execution/workflow-id workflow-id
   :execution/workflow-scope (:workflow-scope record)
   :execution/capacity-context-hash (:capacity-context-hash record)
   :execution/capacity-policy-id (:capacity-policy-id record)
   :execution/capacity-policy-version (:capacity-policy-version record)
   :execution/reason (:reason record)
   :execution/executed-at now
   :execution/is-release is-release
   :execution/resolution-hash resolution-hash})

(defn transition-overflow-capability-execution
  "Spend one unit of the capability: append the execution commitment, update
   lifecycle state, recompute the content hash. Returns
   {:record updated-record :evidence execution-evidence}."
  [record workflow-id caller now evidence-template]
  (let [status-before (:status record)
        count-before (:execution-count record 0)
        used' (conj (:used-workflows record #{}) workflow-id)
        count' (inc count-before)
        status' (if (>= count' (:max-workflows record)) :exhausted :active)
        evidence (assoc evidence-template
                        :execution/status-before status-before
                        :execution/status-after status'
                        :execution/count-before count-before
                        :execution/count-after count')
        updated (-> record
                    (assoc :used-workflows used'
                           :execution-count count'
                           :status status'
                           :overflow-capability/executions
                           (conj (:overflow-capability/executions record []) evidence)))]
    {:record (assoc updated :overflow-capability/hash (overflow-capability-hash updated))
     :evidence evidence}))

;; ---------------------------------------------------------------------------
;; Lifecycle transitions and revocation
;; ---------------------------------------------------------------------------

(def allowed-stored-transitions
  "Capability stored-status transition table. Effective expiry is derived from
   time, not stored. There is no transition out of a terminal state."
  {:active #{:revoked :exhausted}
   :revoked #{}
   :exhausted #{}})

(defn validate-overflow-capability-transition
  "Validate a stored status transition. Returns {:ok true} or
   {:ok false :error <kw>}."
  [from to]
  (if (and (contains? allowed-stored-transitions from)
           (contains? (get allowed-stored-transitions from) to))
    {:ok true}
    {:ok false
     :error (case from
              :revoked :revoked-overflow-capability
              :exhausted :exhausted-overflow-capability
              :invalid-overflow-capability)}))

(defn revoke-overflow-capability
  "Transition a capability :active -> :revoked and recompute the content hash.
   Returns {:record record :transition :revoked}. Idempotent for an already
   revoked capability: returns {:record record :transition :already-revoked}."
  [record {:keys [reason by at provenance]}]
  (case (:status record)
    :active
    (let [updated (assoc record
                         :status :revoked
                         :revocation {:reason reason :by by :at at
                                      :provenance provenance})]
      {:record (assoc updated :overflow-capability/hash (overflow-capability-hash updated))
       :transition :revoked})
    :revoked {:record record :transition :already-revoked}
    :exhausted {:record record :transition :exhausted}))

;; ---------------------------------------------------------------------------
;; Liveness / available-actions surfacing
;; ---------------------------------------------------------------------------

(defn active-overflow-capabilities-for
  "Return the valid, active capability records that apply to workflow-id.
   Invalid records are excluded (they would fail verification anyway)."
  ([world workflow-id] (active-overflow-capabilities-for world workflow-id nil))
  ([world workflow-id policy]
   (let [now (time-ctx/block-ts world)
         et (t/get-transfer world workflow-id)
         primary (:dispute-resolver et)]
     (when (= :disputed (:escrow-state et))
       (vec (for [[_ record] (:resolver-overflows world {})
                  :when (and (overflow-capability? record)
                             (= :active (:status record))
                             (valid-time-window? record now)
                             (within-cap? record)
                             (= (:resolver record) primary)
                             (contains? (:workflow-scope record) workflow-id)
                             (not (contains? (:used-workflows record) workflow-id))
                             (or (nil? policy) (policy-committed? policy record)))]
              record))))))
