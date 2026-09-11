(ns resolver-sim.conformance.registry
  "Closed implementation registry with completeness proofs.

   Profiles are identifier-only; this registry resolves those identifiers to
   executable implementations (validators, canonicalizers, transformations,
   projections, invariants, comparison policies, claim policies).

   Machine checks:
     - ids required by committed profiles == ids resolved by the registry;
     - implemented ids not referenced by any active profile are classified
       (active / experimental / deprecated / orphaned) rather than failing."

  (:require [resolver-sim.conformance.canonical :as canonical]))

(defonce ^:private implementations (atom {}))

(def registry-schema-version "conformance.implementation-registry/v1")

(def committed-identity-fields
  "Fields that constitute a committed implementation identity. These are the
   only fields bound into the deterministic registry root. The runtime
   :implementation/run value is deliberately NOT committed: it is the local
   realization of the committed identity, not identity itself."
  [:implementation/id
   :implementation/kind
   :implementation/domain
   :implementation/version
   :implementation/source-root
   :implementation/status
   :implementation/artifact-root
   :implementation/entrypoint])

(defn- committed-entry
  "Project an implementation entry to its committed identity fields."
  [entry]
  (select-keys entry committed-identity-fields))

(defn register!
  "Register an implementation entry.
     {:implementation/id <kw>
      :implementation/kind <kw>
      :implementation/domain <kw>
      :implementation/version <int>
      :implementation/source-root <sha256>
      :implementation/status :active|:experimental|:deprecated
      :implementation/artifact-root <optional committed artifact identity>
      :implementation/entrypoint <optional committed entrypoint symbol>
      :implementation/run (fn [...] ...)   ;; local, NON-committed runtime
                                           ;; realization of the identity}

   Registry identity is the committed metadata
   (committed-identity-fields). Duplicate IDs are rejected when the committed
   metadata differs, regardless of registration order; identical committed
   metadata is an idempotent re-registration. :implementation/run is the local
   realization of that committed identity: it is never part of the committed
   root, and re-registering the same committed identity with a different :run
   is a runtime rebind, not a registry change. Registration order never changes
   the committed registry root (entries are canonicalised sorted)."
  [entry]
  (let [id (:implementation/id entry)]
    (when-not id
      (throw (ex-info "implementation requires :implementation/id" {:entry entry})))
    (let [existing (get @implementations id)]
      (when (and existing
                 (not= (committed-entry existing)
                       (committed-entry entry)))
        (throw (ex-info "implementation id already registered with a different committed identity"
                        {:implementation/id id
                         :existing (dissoc (committed-entry existing) :implementation/run)
                         :incoming (dissoc (committed-entry entry) :implementation/run)})))
      (swap! implementations assoc id entry))
    id))

(defn resolve-implementation
  "Resolve a registered implementation by id (or nil)."
  [id]
  (get @implementations id))

(defn registered-ids
  []
  (keys @implementations))

(defn registry-entries
  "Canonical, sorted vector of committed implementation entries (excluding the
   non-committed :run and any other non-identity metadata), used for the
   deterministic registry root."
  []
  (vec (sort-by :implementation/id
                (map committed-entry (vals @implementations)))))

(defn registry-root
  "Deterministic committed root of the combined implementation surface
   (canonical-JSON over the sorted entries).  Registration order and process
   state never change this root; it is reproducible byte-for-byte in other
   implementations."
  []
  (canonical/root (registry-entries)))

(defn committed-registry-root-matches?
  "True when a supplied registry root equals the currently committed root."
  [supplied-root]
  (= supplied-root (registry-root)))

(defn completeness
  "Completeness proof: ids required by the active profile(s) vs resolved.

   profile-required-ids — vector of :implementation/id values a profile requires.
   Returns {:ok? bool :missing [...] :resolved [...]}."
  [profile-required-ids]
  (let [missing (vec (remove resolve-implementation profile-required-ids))]
    {:ok? (empty? missing)
     :missing missing
     :resolved (vec (sort (registered-ids)))}))

(defn resolve-for-kind
  "Resolve an implementation by id only when its kind matches the required
   kind.  Returns nil on missing id or kind mismatch."
  [id kind]
  (let [e (resolve-implementation id)]
    (when (and e (= kind (:implementation/kind e))) e)))

(defn required-implementations-ok?
  "Resolve profile-required implementations ({:implementation/id
   :implementation/kind}) against the committed registry, rejecting missing ids
   and kind mismatches.  Returns {:ok? bool :violations [...]
   :registry/root <committed root>}."
  [required]
  (let [violations (into []
                         (keep (fn [req]
                                 (let [e (resolve-implementation (:implementation/id req))]
                                   (cond
                                     (nil? e)
                                     {:violation/id :violation/unresolved-implementation
                                      :details {:implementation/id (:implementation/id req)}}
                                     (and (:implementation/kind req)
                                          (not= (:implementation/kind req) (:implementation/kind e)))
                                     {:violation/id :violation/implementation-kind-mismatch
                                      :details {:implementation/id (:implementation/id req)
                                                :required (:implementation/kind req)
                                                :actual (:implementation/kind e)}})))
                               required))]
    {:ok? (empty? violations)
     :violations violations
     :registry/root (registry-root)}))

(defn reconcile-registry-snapshot
  "Exact reconciliation of a committed production registry descriptor against
   the runtime registry.  An unexpected namespace registration cannot silently
   alter the production root; a missing registration fails closed.

   declared — {:registry/id :registry/declared-root :registry/declared-entries [...]}
   Returns {:registry/id ... :declared-root ... :runtime-root ...
            :missing [] :unexpected [] :mismatched []
            :status :pass|:fail}."
  [declared]
  (let [declared-entries (:registry/declared-entries declared)
        declared-ids (set (map :implementation/id declared-entries))
        runtime-entries (registry-entries)
        runtime-ids (set (map :implementation/id runtime-entries))
        declared-by-id (into {} (map (fn [e] [(:implementation/id e) e]) declared-entries))
        runtime-by-id (into {} (map (fn [e] [(:implementation/id e) e]) runtime-entries))
        missing (vec (sort (remove runtime-ids declared-ids)))
        unexpected (vec (sort (remove declared-ids runtime-ids)))
        mismatched (vec (sort
                         (keep (fn [id]
                                 (when-not (= (get declared-by-id id) (get runtime-by-id id)) id))
                               declared-ids)))
        runtime-root (registry-root)
        status (if (and (= (:registry/declared-root declared) runtime-root)
                        (empty? missing) (empty? unexpected) (empty? mismatched))
                 :pass :fail)]
    {:registry/id (:registry/id declared)
     :declared-root (:registry/declared-root declared)
     :runtime-root runtime-root
     :missing missing
     :unexpected unexpected
     :mismatched mismatched
     :status status}))

(defn experimental-violations
  "Violations when an attested profile requires implementations that are only
   :experimental and not explicitly allowed."
  [required allowed-experimental-ids]
  (into []
        (keep (fn [req]
                (let [e (resolve-implementation (:implementation/id req))]
                  (when (and e (= :experimental (:implementation/status e))
                             (not (contains? allowed-experimental-ids
                                             (:implementation/id req))))
                    {:violation/id :violation/experimental-implementation-not-allowed
                     :details {:implementation/id (:implementation/id req)}})))
              required)))

(defn classify
  "Classify registered ids relative to the active profiles.

   active       — referenced by an active profile;
   experimental — not referenced, but registered with :experimental status;
   deprecated   — registered with :deprecated status;
   orphaned     — not referenced and not experimental/deprecated."
  [profile-required-ids]
  (let [referenced (set profile-required-ids)]
    (reduce (fn [acc [id entry]]
              (let [status (:implementation/status entry :active)
                    cls (cond
                          (contains? referenced id) :active
                          (= :deprecated status) :deprecated
                          (= :experimental status) :experimental
                          :else :orphaned)]
                (update acc cls conj id)))
            {:active [] :experimental [] :deprecated [] :orphaned []}
            @implementations)))

(defn implementation-root
  "Content root of an implementation's committed identity (the committed
   fields; :implementation/run is explicitly excluded as non-committed runtime
   state)."
  [entry]
  (canonical/root (committed-entry entry)))
