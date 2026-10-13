(ns resolver-sim.assurance.force-authorisation
  "Protocol-independent single-scope force-authorisation lifecycle validation.

   Accepts authorization records, consumption registries, and scope maps
   as plain data. Returns validation result maps.

   SCOPE: This namespace validates force-authorisation lifecycle for SINGLE-CLAIM
   grants only — status, consumption, timing, and scope-hash/scope equality.
   It does NOT model :authorization/scope-kind or related-claims semantics
   (relationship membership, per-member consumption, record/provenance
   relationship binding).  Those remain a protocol-layer concern in the Sew
   adapter's ensure-force-authorisation-usable!, which derives its scope-kind
   branch from the persisted record, not caller-supplied provenance.

   BOUNDARY GUARD — This namespace MUST NOT import or depend on:
     - resolver-sim.protocols.sew       (Sew protocol adapter)
     - any form under protocols_src/    (Sew-only source path)
     - benchmarks/packs/sew/            (Sew benchmark pack)

   Protocol-independence is enforced by having zero :require entries for
   Sew namespaces. All dependencies are core Clojure or resolver-sim.core.
   The portability test in test/resolver_sim/assurance/ verifies this.")

(def force-authorisation-scope-domain
  "Domain constant for scope-hash computation."
  "force-authorisation-scope")

(defn force-authorisation-scope-hash
  "Compute the domain-scoped hash for a force-authorisation scope map.
   scope-map must contain the keys that define the authorisation's
   immutable custody scope."
  [scope-map]
  (let [h (requiring-resolve 'resolver-sim.hash.canonical/domain-hash)]
    (h force-authorisation-scope-domain scope-map)))

(defn scope-hash-missing?
  "True when the authorization record has no scope-hash at all."
  [auth-provenance]
  (nil? (:authorization/scope-hash auth-provenance)))

(defn scope-hash-mismatch?
  "True when the scope-hash recorded in auth-provenance does not match
   the recomputed hash from scope-map. Indicates scope drift or forgery.
   Returns false when scope-hash is absent (see scope-hash-missing?)."
  [auth-provenance scope-map]
  (let [recorded  (:authorization/scope-hash auth-provenance)
        recomputed (force-authorisation-scope-hash scope-map)]
    (and (some? recorded) (not= recorded recomputed))))

;; ── Normalization ──────────────────────────────────────────────────────────
;; Force-authorisation data may arrive from JSON (string keys), from
;; test fixtures (keyword keys), or from the Sew protocol (namespaced
;; keywords). These normalization functions coerce to canonical form.

(defn- coerce-long
  "Coerce a value to Long, returning nil for nil input.
   Handles both number and string representations."
  [v]
  (when v
    (cond
      (number? v) (long v)
      (string? v) (Long/parseLong v)
      :else (long v))))

(defn- coerce-long-default
  "Like coerce-long but defaults to 0 for nil."
  [v]
  (if (nil? v) 0
      (cond
        (number? v) (long v)
        (string? v) (Long/parseLong v)
        :else (long v))))

(require '[resolver-sim.accounting.held-adjustment :as held-adjustment])

(def ^:private scope-keys
  "Canonical keys that a force-authorisation scope map must contain."
  #{:authorization/id :authorization/type :held/direction
    :token :amount :held/account :held/position-id :owner/address
    :held/reason :held/workflow-id :parameter/context :parameter/address})

(defn normalize-force-authorisation-scope
  "Normalize a force-authorisation scope map to canonical form.
   Coerces field types and discards unknown keys.
   Accepts maps with string or keyword keys. Returns nil for nil input."
  [scope-map]
  (when scope-map
    (let [m (reduce-kv (fn [acc k v]
                         (let [k' (if (string? k) (keyword k) k)]
                           (if (contains? scope-keys k')
                             (assoc acc k'
                                    (case k'
                                      (:token :authorization/type :held/direction
                                              :held/account :held/reason)
                                      (if (keyword? v) v (keyword (name v)))
                                      (:amount :held/workflow-id) (coerce-long-default v)
                                      (:authorization/id :owner/address) (name v)
                                      (:parameter/context :parameter/address) v
                                      v))
                             acc)))
                       {} scope-map)]
      (when (seq m)
        m))))

(defn normalize-force-authorisation-record
  "Normalize a force-authorisation record to canonical form.
   Accepts records from JSON (string keys) or EDN.
   Coerces types on known keys, preserves unknown keys as-is,
   and fills safe defaults for :consumed?, :authorization/status,
   and :authorization/type."
  [record]
  (when record
    (let [m (reduce-kv (fn [acc k v]
                         (let [k' (if (string? k) (keyword k) k)]
                           (assoc acc k'
                                  (case k'
                                    :consumed? (boolean (or v false))
                                    :authorization/status
                                    (if (keyword? v) v (keyword (name v)))
                                    :authorization/type
                                    (if (keyword? v) v :force-authorisation)
                                    :authorization/scope
                                    (normalize-force-authorisation-scope v)
                                    (:workflow-id :starts-at :expires-at
                                                  :created-at :executed-at)
                                    (coerce-long v)
                                    (:executed-by :nonce) (when v (name v))
                                    v))))
                       {} record)]
      (merge {:consumed? false
              :authorization/status :active
              :authorization/type :force-authorisation}
             m))))

(defn normalize-force-authorisation-records
  "Normalize a map of force-authorisation records.
   Accepts a map of {auth-id record} and returns a map with each
   record normalized via normalize-force-authorisation-record."
  [records]
  (when records
    (reduce-kv (fn [acc k v]
                 (assoc acc k (normalize-force-authorisation-record v)))
               {} records)))

(defn normalize-force-authorisation-consumption-registry
  "Normalize a force-authorisation consumption registry.
   Accepts a map of {auth-id consumption-entry} and returns
   a normalized map with coerced field types."
  [registry]
  (when registry
    (reduce-kv (fn [acc k v]
                 (let [m (if (map? v)
                           (reduce-kv (fn [a kk vv]
                                        (let [kk' (if (string? kk) (keyword kk) kk)]
                                          (assoc a kk'
                                                 (case kk'
                                                   :consumed-at (coerce-long-default vv)
                                                   :consumed-by (name vv)
                                                   :consumed-amount (coerce-long-default vv)
                                                   :consumed-token
                                                   (if (keyword? vv) vv (keyword (name vv)))
                                                   :held-adjustment-id (name vv)
                                                   vv))))
                                      {} v)
                           {})]
                   (assoc acc k (merge {:consumed-at 0} m))))
               {} registry)))

;; ── Validation ──────────────────────────────────────────────────────────────

(defn- validation-error
  "Build a validation result map entry."
  [code detail]
  {:code code :detail detail})

(defn verify-authorisation-usable
  "Validate that an authorization record is usable for a given single scope.
   Returns {:valid? true} or {:valid? false :errors [...]}.

   Accepts:
     record            — the authorization record (map with :authorization/status,
                         :consumed?, :starts-at, :expires-at, :authorization/scope-hash,
                         :authorization/scope, etc.)
     consumption-registry — map of consumed auth-ids → consumption records
     scope-map          — the scope being requested
     now-ts             — current block time for timing checks

   This is a SINGLE-SCOPE force-authorisation lifecycle validator.  It covers
   the lifecycle checks (status, consumed?, timing, scope-hash, scope equality)
   but does NOT model :authorization/scope-kind or related-claims semantics:
     - relationship existence/activity
     - member identity membership
     - member scope-hash authorization
     - per-member consumption
     - record/provenance relationship binding

   Sew's related-claims enforcement remains a protocol-layer concern in
   protocols_src/.../accounting.clj's ensure-force-authorisation-usable!, which
   selects its validation branch from the persisted record's scope-kind — not
   caller-supplied provenance.

   To model related-claims semantics, extend this namespace with an explicit
   closed :single-claim / :related-claims mode that receives the necessary
   relationship state."
  [record consumption-registry scope-map now-ts]
  (let [errors (cond-> []
                 (nil? record)
                 (conj (validation-error :authorisation-not-found
                                         "No authorization record exists"))

                 (not= :active (:authorization/status record))
                 (conj (validation-error :authorisation-not-active
                                         (str "Status is " (:authorization/status record)
                                              ", expected :active")))

                 (:consumed? record)
                 (conj (validation-error :authorisation-already-consumed
                                         "Authorization has already been consumed"))

                 (get consumption-registry (:authorization/id record))
                 (conj (validation-error :authorisation-already-consumed
                                         "Authorization found in consumption registry"))

                 (and (:starts-at record) (< now-ts (:starts-at record)))
                 (conj (validation-error :authorisation-not-yet-started
                                         (str "now-ts " now-ts " < starts-at " (:starts-at record))))

                 (and (:expires-at record) (>= now-ts (:expires-at record)))
                 (conj (validation-error :authorisation-expired
                                         (str "now-ts " now-ts " >= expires-at " (:expires-at record))))

                 (held-adjustment/parameter-attribution-error scope-map)
                 (conj (validation-error :invalid-parameter-attribution
                                         (name (held-adjustment/parameter-attribution-error scope-map))))

                 (scope-hash-missing? record)
                 (conj (validation-error :missing-scope-hash
                                         "Authorization record has no scope-hash"))

                 (scope-hash-mismatch? record scope-map)
                 (conj (validation-error :scope-hash-mismatch
                                         "Recorded scope-hash does not match recomputed scope-hash"))

                 (and (:authorization/scope record) (not= (:authorization/scope record) scope-map))
                 (conj (validation-error :scope-mismatch
                                         "Scope map does not match the authorization's granted scope")))]
    (if (seq errors)
      {:valid? false :errors errors}
      {:valid? true})))

(defn verify-authorisation-lifecycle-consistency
  "Validate that force-authorisation state is internally consistent.
   Returns {:holds? true} or {:holds? false :violations [...]}.

   Checks:
     - Every consumed auth has a corresponding grant record
     - Every grant has a matching scope-hash
     - No consumption occurs without a preceding grant

   This is the protocol-independent extraction of
   protocols_src/.../invariants.clj's force-authorisations-lifecycle-consistent?."
  [authorisations consumption-registry]
  (let [violations (cond-> []
                     (some (fn [[auth-id _]]
                             (not (contains? authorisations auth-id)))
                           consumption-registry)
                     (conj {:error :orphan-consumption
                            :detail "Consumption registry contains auth-ids without matching grant records"})

                     (some (fn [[auth-id record]]
                             (and (get consumption-registry auth-id)
                                  (not= :consumed (:authorization/status record))))
                           authorisations)
                     (conj {:error :consumed-without-status
                            :detail "Grant record status is not :consumed despite being in consumption registry"})

                     (some (fn [[_ record]]
                             (and (:authorization/scope-hash record)
                                  (nil? (:authorization/scope record))))
                           authorisations)
                     (conj {:error :scope-hash-without-scope
                            :detail "Grant record has scope-hash but no scope map"})

                     (some (fn [[_ record]]
                             (and (:authorization/scope record)
                                  (or (held-adjustment/parameter-attribution-error (:authorization/scope record))
                                      (not= (:authorization/scope-hash record)
                                            (force-authorisation-scope-hash (:authorization/scope record))))))
                           authorisations)
                     (conj {:error :stored-scope-hash-mismatch
                            :detail "Grant scope hash does not authenticate its stored scope"}))]
    (if (seq violations)
      {:holds? false :violations violations}
      {:holds? true})))
