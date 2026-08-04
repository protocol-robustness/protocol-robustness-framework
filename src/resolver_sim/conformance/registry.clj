(ns resolver-sim.conformance.registry
  "Closed implementation registry with completeness proofs.

   Profiles are identifier-only; this registry resolves those identifiers to
   executable implementations (validators, canonicalizers, transformations,
   projections, invariants, comparison policies, claim policies).

   Machine checks:
     - ids required by committed profiles == ids resolved by the registry;
     - implemented ids not referenced by any active profile are classified
       (active / experimental / deprecated / orphaned) rather than failing."

  (:require [resolver-sim.hash.canonical :as hc]))

(defonce ^:private implementations (atom {}))

(defn register!
  "Register an implementation entry.
     {:implementation/id <kw>
      :implementation/kind <kw>
      :implementation/version <int>
      :implementation/root <sha256>
      :implementation/status :active|:experimental|:deprecated
      :implementation/run (fn [...] ...)}"
  [entry]
  (let [id (:implementation/id entry)]
    (when-not id
      (throw (ex-info "implementation requires :implementation/id" {:entry entry})))
    (swap! implementations assoc id entry)
    id))

(defn resolve-implementation
  "Resolve a registered implementation by id (or nil)."
  [id]
  (get @implementations id))

(defn registered-ids
  []
  (keys @implementations))

(defn completeness
  "Completeness proof: ids required by the active profile(s) vs resolved.

   profile-required-ids — vector of :implementation/id values a profile requires.
   Returns {:ok? bool :missing [...] :resolved [...]}."
  [profile-required-ids]
  (let [missing (vec (remove resolve-implementation profile-required-ids))]
    {:ok? (empty? missing)
     :missing missing
     :resolved (vec (sort (registered-ids)))}))

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
  "Content root of an implementation entry (excluding :run)."
  [entry]
  (hc/domain-hash "conformance.implementation.v1"
                  (dissoc entry :implementation/run)))
