(ns resolver-sim.extensions.manifest
  "Extension package and capability manifest validation, identity projections,
   and sealed classification.

   Terminology (per ADR-0005 'Framework Extension Packages and Economics
   Capabilities'):

   - extension-map   — the explicit, data-driven configuration that identifies
                       extension points and maps extension identifiers to their
                       implementations; the inspectable and validateable source
                       of truth for discovering, selecting, and composing
                       extensions.
   - extension-backed — behaviour/capabilities/artifacts whose implementation
                       is supplied through the extension system rather than the
                       framework core; applied only when extension identity and
                       provenance remain observable.

   Phase 1 scope (generic package + resolution substrate): manifest schemas,
   identity roots, and sealed classification. No dispatch wiring, no
   entrypoint loading — entrypoints are recorded as symbols and are resolved
   to Vars in a later phase."
  (:require [resolver-sim.hash.canonical :as hc]))

;; ── domain tags (string form, per canonical-hash string convention) ─────

(def ^:const capability-descriptor-domain-tag
  "EXTENSION_CAPABILITY_DESCRIPTOR_V1")

(def ^:const package-manifest-domain-tag
  "EXTENSION_PACKAGE_MANIFEST_V1")

;; ── capability identity ───────────────────────────────────────────────────

(def capability-projection-fields
  "Fields committed to a capability descriptor root. Runtime-resolved objects
   (resolved Vars, live functions) are intentionally excluded.

   Schema references (:input-schema/:output-schema/:verification/contract)
   are symbolic schema ids here; resolution resolves them to exact schema
   roots in the resolution snapshot."
  [:capability/kind
   :capability/id
   :capability/version
   :capability/contract-version
   :capability/profile
   :entrypoint
   :input-schema
   :output-schema
   :composition-contract
   :declared-dependencies
   :verification/contract
   ;; A verifier capability declares the exact subject contract it can assess.
   ;; This field is part of descriptor identity so selection cannot be changed
   ;; by an uncommitted registry-side convention.
   :verifies
   :verification/profile])

(defn capability-key
  "Registry key for a capability: [capability-kind capability-id].
   A method keyword alone is ambiguous across allocation, award amount, and
   funding; the kind is part of registry identity."
  [cap]
  [(get cap :capability/kind) (get cap :capability/id)])

;; ── canonical projection ───────────────────────────────────────────────────

(defn- project-canonical
  "Recursively project a descriptor value into the canonical hash domain:
   sets become sorted vectors. The strict canonical encoder (resolver-sim.hash.
   canonical) rejects raw sets — callers must project before hashing (mirrors
   project-world-to-structure-view's set→sorted-vector contract)."
  [v]
  (cond
    (nil? v) nil
    (boolean? v) v
    (integer? v) v
    (string? v) v
    (keyword? v) v
    (set? v) (vec (sort (map project-canonical v)))
    (map? v) (into {} (map (fn [[k val]] [k (project-canonical val)])) v)
    (vector? v) (mapv project-canonical v)
    (seq? v) (mapv project-canonical v)
    :else v))

(defn capability-projection
  "Project a capability descriptor to its committed identity fields, in the
   canonical hash domain (sets become sorted vectors, entrypoints become
   strings)."
  [cap]
  (project-canonical
   (cond-> (select-keys cap capability-projection-fields)
     (:entrypoint cap) (update :entrypoint str))))

(defn capability-descriptor-root
  "Content-addressed root of a capability descriptor: the exact implementation
   identity referenced by method identity and registration idempotency."
  [cap]
  (hc/domain-hash capability-descriptor-domain-tag (capability-projection cap)))

;; ── dependency validation ─────────────────────────────────────────────────

(defn- validate-dependency
  [dep idx]
  (let [kind (:capability/kind dep)
        id (:capability/id dep)
        req (:requirement dep)]
    (cond-> []
      (not (map? dep))
      (conj {:violation/id :violation/non-map-dependency
             :details {:index idx :dependency dep}})

      (not (keyword? kind))
      (conj {:violation/id :violation/invalid-dependency-kind
             :details {:index idx :dependency dep}})

      (not (keyword? id))
      (conj {:violation/id :violation/invalid-dependency-id
             :details {:index idx :dependency dep}})

      (and (some? req) (not (map? req)))
      (conj {:violation/id :violation/invalid-dependency-requirement
             :details {:index idx :dependency dep}}))))

;; ── capability validation ─────────────────────────────────────────────────

(def legacy-field-aliases
  "Legacy descriptor field aliases mapped to the canonical vocabulary.
   The canonical descriptor vocabulary is :input-schema, :output-schema,
   and :verification/contract. The aliases below are migration aliases:
   they are accepted, normalised to the canonical form for hashing, and
   rejected when they conflict with a canonical value."
  {:input-schema-ref :input-schema
   :output-schema-ref :output-schema
   :verification-contract :verification/contract})

(defn normalize-capability-descriptor
  "Normalize a capability descriptor to the canonical vocabulary. Legacy
   aliases are mapped to canonical keys (removed from the result); a legacy
   key conflicting with a canonical value is reported, not silently resolved.

   Returns {:normalized <descriptor> :conflicts [<legacy-key> ...]}."
  [cap]
  (let [conflicts (vec (keep (fn [[legacy canonical]]
                               (when (and (contains? cap legacy)
                                          (contains? cap canonical)
                                          (not= (get cap legacy) (get cap canonical)))
                                 legacy))
                             legacy-field-aliases))]
    {:normalized (reduce (fn [m [legacy canonical]]
                           (if (contains? m legacy)
                             (-> m (assoc canonical (get m legacy)) (dissoc legacy))
                             m))
                         cap
                         legacy-field-aliases)
     :conflicts conflicts}))

(defn- validate-embedded-composition-contract
  "Local structural gate on a capability's composition contract, applied at
   registration so a malformed contract cannot be content-hashed and
   registered. Full semantic validation happens in the composition compiler."
  [cc]
  (cond-> []
    (not (map? cc))
    (conj {:violation/id :violation/non-map-composition-contract
           :details {:composition-contract cc}})

    (and (map? cc) (not (pos? (or (:composition-contract/version cc) 0))))
    (conj {:violation/id :violation/invalid-composition-contract-version
           :details {:version (:composition-contract/version cc)}})

    (and (map? cc) (nil? (:composition/input cc)))
    (conj {:violation/id :violation/missing-composition-input
           :details {}})

    (and (map? cc) (nil? (:composition/output cc)))
    (conj {:violation/id :violation/missing-composition-output
           :details {}})))

(defn validate-capability
  "Validate a capability descriptor structurally.
   Returns {:valid? bool, :violations [violation-maps]}."
  [cap]
  (let [{:keys [normalized conflicts]} (normalize-capability-descriptor cap)
        cap normalized
        kind (:capability/kind cap)
        id (:capability/id cap)
        deps (:declared-dependencies cap [])
        verifier? (= :evidence/verifier kind)
        verifies (:verifies cap)
        v (cond-> []
            (seq conflicts)
            (conj {:violation/id :violation/conflicting-capability-fields
                   :details {:conflicting-fields conflicts}})

            (not (map? cap))
            (conj {:violation/id :violation/non-map-capability
                   :details {:capability cap}})

            (nil? kind)
            (conj {:violation/id :violation/missing-capability-kind
                   :details {:capability cap}})

            (and kind (not (keyword? kind)))
            (conj {:violation/id :violation/invalid-capability-kind
                   :details {:kind kind}})

            (and kind (keyword? kind) (nil? (namespace kind)))
            (conj {:violation/id :violation/unqualified-capability-kind
                   :details {:kind kind}})

            (nil? id)
            (conj {:violation/id :violation/missing-capability-id
                   :details {:capability cap}})

            (and id (not (keyword? id)))
            (conj {:violation/id :violation/invalid-capability-id
                   :details {:id id}})

            (and id (keyword? id) (nil? (namespace id)))
            (conj {:violation/id :violation/unqualified-capability-id
                   :details {:id id}})

            (nil? (:entrypoint cap))
            (conj {:violation/id :violation/missing-entrypoint
                   :details {:capability/id id}})

            (and (:entrypoint cap)
                 (not (or (symbol? (:entrypoint cap))
                          (string? (:entrypoint cap)))))
            (conj {:violation/id :violation/invalid-entrypoint
                   :details {:entrypoint (:entrypoint cap)}})

            (not (pos? (or (:capability/version cap) 0)))
            (conj {:violation/id :violation/invalid-capability-version
                   :details {:capability/id id
                             :version (:capability/version cap)}})

            (not (pos? (or (:capability/contract-version cap) 0)))
            (conj {:violation/id :violation/invalid-contract-version
                   :details {:capability/id id
                             :contract-version (:capability/contract-version cap)}})

            (not (vector? deps))
            (conj {:violation/id :violation/invalid-declared-dependencies
                   :details {:capability/id id
                             :declared-dependencies deps}})

            (and verifier? (not (map? verifies)))
            (conj {:violation/id :violation/missing-verifier-subject-contract
                   :details {:capability/id id}})

            (and verifier? (map? verifies)
                 (not (keyword? (:capability/kind verifies))))
            (conj {:violation/id :violation/invalid-verifier-subject-kind
                   :details {:capability/id id :verifies verifies}})

            (and verifier? (map? verifies)
                 (not (keyword? (:capability/id verifies))))
            (conj {:violation/id :violation/invalid-verifier-subject-id
                   :details {:capability/id id :verifies verifies}})

            (and verifier? (map? verifies)
                 (not (pos? (or (:capability/contract-version verifies) 0))))
            (conj {:violation/id :violation/invalid-verifier-subject-contract-version
                   :details {:capability/id id :verifies verifies}}))
        v (if (and (vector? deps) (some (complement map?) deps))
            (conj v {:violation/id :violation/invalid-declared-dependency
                     :details {:capability/id id
                               :non-map-dependency (first (remove map? deps))}})
            v)
        v (reduce (fn [vs dep]
                    (into vs (validate-dependency dep (count vs))))
                  v deps)
        v (if-let [cc (:composition-contract cap)]
            (into v (validate-embedded-composition-contract cc))
            v)]
    {:valid? (empty? v)
     :violations (vec v)}))

;; ── package identity ──────────────────────────────────────────────────────

(def package-identity-fields
  "Package fields committed to the package root. Hash fields
   (:extension/manifest-root, :extension/package-root) are excluded."
  [:extension/id
   :extension/version
   :extension/api-version
   :extension/manifest-version
   :extension/license
   :extension/maintainers
   :extension/support-policy
   :extension/funding-status
   :extension/supersedes
   :extension/fork-of
   :extension/status
   :extension/source
   :extension/artifact
   :extension/dependencies
   :extension/runtime
   :extension/historical-read])

(defn package-projection
  "Project a package manifest to its committed identity fields, including
   capability projections."
  [pkg]
  (-> (select-keys pkg package-identity-fields)
      (assoc :extension/capabilities
             (mapv capability-projection (:extension/capabilities pkg [])))))

(defn package-root
  "Content-addressed root of a package manifest. Identifies the declared
   package identity and sealing roots, not the executable artifact itself."
  [pkg]
  (hc/domain-hash package-manifest-domain-tag (package-projection pkg)))

;; ── package validation ────────────────────────────────────────────────────

(def supported-manifest-version 1)

(defn- validate-package-capabilities
  [pkg]
  (let [caps (:extension/capabilities pkg [])
        cap-validations (mapv (fn [cap] (validate-capability cap)) caps)
        cap-violations (into [] (mapcat :violations cap-validations))
        keys (map capability-key caps)
        dups (->> keys frequencies
                  (keep (fn [[k n]] (when (> n 1) k)))
                  vec)]
    (into cap-violations
          (when (seq dups)
            [{:violation/id :violation/duplicate-capability-key
              :details {:duplicate-keys dups}}]))))

(defn validate-historical-read
  "Validate a package's :extension/historical-read declaration (when present).
   Fail closed on malformed or unknown historical versions.

   The declaration distinguishes:
     :current-production   — the live capability and its schema version
     :historical-read      — frozen artifact classes the package is committed
                             to verifying (each entry read-only, schema-version
                             string, artifact-kind keyword)
     :historical-production — must be :forbidden (the package owns no legacy
                             producers)

   Returns {:valid? bool :violations [violation-maps]}."
  [decl]
  (let [v (cond-> []
            (not (map? decl))
            (conj {:violation/id :violation/non-map-historical-read
                   :details {:historical-read decl}})

            (and (map? decl) (nil? (:current-production decl)))
            (conj {:violation/id :violation/missing-historical-current-production
                   :details {}})

            (and (map? decl)
                 (not (map? (:current-production decl))))
            (conj {:violation/id :violation/non-map-historical-current-production
                   :details {:current-production (:current-production decl)}})

            (and (map? (:current-production decl))
                 (not (keyword? (:capability/id (:current-production decl)))))
            (conj {:violation/id :violation/invalid-historical-current-capability-id
                   :details {:current-production (:current-production decl)}})

            (and (map? (:current-production decl))
                 (not (string? (:schema-version (:current-production decl)))))
            (conj {:violation/id :violation/invalid-historical-current-schema-version
                   :details {:current-production (:current-production decl)}})

            (and (map? decl)
                 (nil? (:historical-read decl)))
            (conj {:violation/id :violation/missing-historical-read-list
                   :details {}})

            (and (map? decl)
                 (not (sequential? (:historical-read decl))))
            (conj {:violation/id :violation/non-sequential-historical-read
                   :details {:historical-read (:historical-read decl)}})

            (and (map? decl)
                 (not= :forbidden (:historical-production decl)))
            (conj {:violation/id :violation/historical-production-not-forbidden
                   :details {:historical-production (:historical-production decl)}}))
        v (if (and (map? decl) (sequential? (:historical-read decl)))
            (reduce (fn [vs entry]
                      (cond-> vs
                        (not (map? entry))
                        (conj {:violation/id :violation/non-map-historical-read-entry
                               :details {:entry entry}})

                        (and (map? entry)
                             (not (string? (:schema-version entry))))
                        (conj {:violation/id :violation/invalid-historical-read-schema-version
                               :details {:entry entry}})

                        (and (map? entry)
                             (not (keyword? (:artifact/kind entry))))
                        (conj {:violation/id :violation/invalid-historical-read-artifact-kind
                               :details {:entry entry}})

                        (and (map? entry)
                             (not (true? (:read-only entry))))
                        (conj {:violation/id :violation/historical-read-not-read-only
                               :details {:entry entry}})))
                    v
                    (:historical-read decl))
            v)]
    {:valid? (empty? v)
     :violations (vec v)}))

(defn validate-package
  "Validate a package manifest structurally.
   Returns {:valid? bool, :violations [violation-maps]}."
  [pkg]
  (let [id (:extension/id pkg)
        v (cond-> []
            (not (map? pkg))
            (conj {:violation/id :violation/non-map-package
                   :details {:package pkg}})

            (nil? id)
            (conj {:violation/id :violation/missing-package-id
                   :details {:package pkg}})

            (and id (not (keyword? id)))
            (conj {:violation/id :violation/invalid-package-id
                   :details {:extension/id id}})

            (and id (keyword? id) (nil? (namespace id)))
            (conj {:violation/id :violation/unqualified-package-id
                   :details {:extension/id id}})

            (not (string? (:extension/version pkg)))
            (conj {:violation/id :violation/invalid-package-version
                   :details {:extension/id id
                             :version (:extension/version pkg)}})

            (not (pos? (or (:extension/api-version pkg) 0)))
            (conj {:violation/id :violation/invalid-api-version
                   :details {:extension/id id
                             :api-version (:extension/api-version pkg)}})

            (not= supported-manifest-version (:extension/manifest-version pkg))
            (conj {:violation/id :violation/invalid-manifest-version
                   :details {:extension/id id
                             :manifest-version (:extension/manifest-version pkg)
                             :supported supported-manifest-version}})

            (not (vector? (:extension/capabilities pkg [])))
            (conj {:violation/id :violation/invalid-capabilities-field
                   :details {:extension/id id
                             :capabilities (:extension/capabilities pkg)}}))
        v (if (vector? (:extension/capabilities pkg []))
            (into v (validate-package-capabilities pkg))
            v)
        v (if-let [hr (:extension/historical-read pkg)]
            (into v (:violations (validate-historical-read hr)))
            v)]
    {:valid? (empty? v)
     :violations (vec v)}))

;; ── sealed classification ─────────────────────────────────────────────────

(defn sealing
  "Return the sealing map of a package: the four sealing roots
   (:extension/source, :extension/artifact, :extension/dependencies,
   :extension/runtime) and the committed :extension/package-root."
  [pkg]
  (select-keys pkg [:extension/source :extension/artifact
                    :extension/dependencies :extension/runtime
                    :extension/package-root]))

(defn sealed-classification
  "Classify reproduction assurance for a package:

   :unsealed            — no sealing roots (development mode)
   :source-pinned       — source identity committed, but no executable
                          artifact / dependency closure / runtime profile
   :artifact-replayable — executable artifact, dependency-resolution root,
                          and runtime profile all committed

   A source commit alone establishes source identity, not executable
   reproducibility."
  [pkg]
  (let [artifact (:extension/artifact pkg)
        runtime (:extension/runtime pkg)
        dep-resolution-root (get-in pkg [:extension/dependencies :dependency-resolution-root])]
    (cond
      (and artifact runtime dep-resolution-root)
      :artifact-replayable

      (:extension/source pkg)
      :source-pinned

      :else
      :unsealed)))

(defn sealed?
  "True when a package commits at least a source or artifact sealing root."
  [pkg]
  (boolean (or (:extension/source pkg) (:extension/artifact pkg))))
