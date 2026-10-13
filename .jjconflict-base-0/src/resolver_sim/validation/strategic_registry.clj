(ns resolver-sim.validation.strategic-registry
  "Pure registry composition for game-theoretic validation extensions.

   Provides deterministic, rooted registries for the four extension surfaces:

     - validator descriptors   (resolver-sim.validation.validator-descriptor)
     - strategic claims
     - deviation contracts
     - deviation generators

   Composition model:

     (build-validator-registry
       builtin-validators        ;; framework-owned
       protocol-validators       ;; protocol-supplied
       application-validators)   ;; application-supplied

   Registries are constructed by pure function (no mutable register! API).
   Each registry carries:

     - :entries  — canonical, sorted vector of committed identity maps
     - :by-id    — id -> committed entry (for resolution)
     - :executables — id -> executable (function), kept separate from the
                      committed identity so that the root never commits
                      runtime behaviour
     - :root     — deterministic content root (sha256 hex) over the sorted
                   committed entries

   The root is reproducible byte-for-byte: registration order never changes
   it, and two executions that resolve the same claim ID against different
   registry contents produce different registry roots.  Consumers should
   bind :deviation-contract-registry-root / :validator-registry-root (not
   just resulting IDs) so that 'same claim id' can never silently mean
   'different strategic semantics'.

   This namespace is pure — no I/O, no DB, no side effects."

  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.validation.validator-descriptor :as vd]))

;; ---------------------------------------------------------------------------
;; Registry construction
;; ---------------------------------------------------------------------------

(defn- normalize-source
  "Normalize a single source map into {:origin kw :entries [...]}.
   Accepts either {:origin kw :entries [...]} or a plain vector of entries
   (origin defaults to :framework)."
  [source]
  (cond
    (map? source)
    (let [origin (or (:origin source) :framework)
          entries (:entries source)]
      (when-not (sequential? entries)
        (throw (ex-info "registry source :entries must be sequential"
                        {:source source})))
      {:origin origin :entries entries})

    (sequential? source)
    {:origin :framework :entries source}

    :else
    (throw (ex-info "invalid registry source" {:source source}))))

(defn- sorted-entries
  "Canonical, deterministic order for rooting: sorted by the entry's own id
   key when present, then by its printed form (stable across registration
   order and map key ordering)."
  [entries]
  (vec (sort-by (juxt (fn [e] (or (:id e) (pr-str e))) pr-str) entries)))

(defn registry-root
  "Deterministic content root of a registry over its committed entries.
   Domain-separated and stable across registration order."
  [registry-tag entries]
  (hc/domain-hash registry-tag {:registry/entries (sorted-entries entries)}))

;; ---------------------------------------------------------------------------
;; Validator registry
;; ---------------------------------------------------------------------------

(defn- validate-validator-entry!
  "Validate a validator entry (descriptor) at build time."
  [entry]
  (when-not (map? entry)
    (throw (ex-info "validator entry must be a descriptor map" {:entry entry})))
  (vd/validate-descriptor! entry)
  entry)

(defn build-validator-registry
  "Build a rooted validator registry from sources.

   Each source is either:
     - {:origin kw :entries [descriptor ...]} where each descriptor follows
       prf/game-theoretic-validator.v1, OR
     - a plain vector of descriptors (origin :framework).

   Executables are supplied separately as {validator-id fn}; they are the
   local runtime realization of the committed identity and are NEVER part of
   the registry root.

   Returns {:entries [...] :by-id {...} :executables {...} :root <sha256>}."
  [& {:keys [sources executables]
      :or {sources [] executables {}}}]
  (let [normalized (mapv (fn [s]
                           (update (normalize-source s)
                                   :entries
                                   (fn [es] (mapv validate-validator-entry! es))))
                         sources)
        entries (distinct (vec (mapcat :entries normalized)))
        by-id (reduce (fn [acc entry]
                        (let [id (:validator/id entry)]
                          (when-not id
                            (throw (ex-info "validator descriptor missing :validator/id"
                                            {:entry entry})))
                          (if-let [existing (get acc id)]
                            (do
                              (when-not (= existing entry)
                                (throw (ex-info "Duplicate validator id with different identity"
                                                {:validator/id id
                                                 :existing existing
                                                 :incoming entry})))
                              acc)
                            (assoc acc id entry))))
                      {}
                      entries)]
    {:entries entries
     :by-id by-id
     :executables executables
     :root (registry-root vd/validator-registry-schema-tag entries)}))

(defn resolve-validator
  "Resolve a validator descriptor by id from an explicit registry.
   registry — the map returned by build-validator-registry.
   Returns the committed descriptor, or nil."
  [registry id]
  (get (:by-id registry) id))

(defn resolve-validator-executable
  "Resolve a validator's executable function by id from an explicit registry.
   Returns nil when the descriptor exists but no executable was supplied."
  [registry id]
  (get (:executables registry) id))

(defn validator-ids
  "Sorted vector of validator ids in a registry."
  [registry]
  (vec (sort (keys (:by-id registry)))))

;; ---------------------------------------------------------------------------
;; Generic registry for claims, deviation contracts, generators
;; ---------------------------------------------------------------------------

(defn build-entry-registry
  "Build a rooted registry for a generic entry kind (claims, deviation
   contracts, generators).

   entry-id-key   — the key holding the entry id (e.g. :claim/id).
   registry-tag   — string domain tag for rooting.
   sources        — seq of {:origin kw :entries [...]} or plain vectors.
   executables    — optional {id fn} association (generators).
   committed-fn   — optional projection applied to entries BEFORE rooting.
                    The registry's :by-id index keeps the raw entries (so
                    callers resolve the original committed data), while the
                    root commits the projected (canonical-safe) identity.
                    This lets entries carry runtime values (e.g. a resolved
                    manifest path) that are projected to a reproducible form
                    without changing what resolution returns.

   Returns {:entries [...] :by-id {...} :executables {...} :root <sha256>}."
  [entry-id-key registry-tag & {:keys [sources executables committed-fn]
                                :or {sources [] executables {} committed-fn identity}}]
  (let [normalized (mapv normalize-source sources)
        entries (mapcat :entries normalized)
        by-id (reduce (fn [acc entry]
                        (let [id (get entry entry-id-key)]
                          (when-not id
                            (throw (ex-info "registry entry missing id key"
                                            {:entry-id-key entry-id-key
                                             :entry entry})))
                          (if-let [existing (get acc id)]
                            (do
                              (when-not (= existing entry)
                                (throw (ex-info "Duplicate registry entry id with different identity"
                                                {:id id
                                                 :existing existing
                                                 :incoming entry})))
                              acc)
                            (assoc acc id entry))))
                      {}
                      entries)
        committed (mapv committed-fn entries)]
    {:entries (vec entries)
     :by-id by-id
     :executables executables
     :root (registry-root registry-tag committed)}))

(defn resolve-entry
  "Resolve a registry entry by id from an explicit registry."
  [registry id]
  (get (:by-id registry) id))

;; ---------------------------------------------------------------------------
;; Composition helpers
;; ---------------------------------------------------------------------------

(defn composed-sources
  "Combine builtin + protocol + application sources into one source list.
   Each arg is nil-able; nil sources are skipped.  Sources appear in the
   order builtin, protocol, application so that the provenance is auditable."
  [& {:keys [builtin protocol application]}]
  (vec (keep identity [builtin protocol application])))