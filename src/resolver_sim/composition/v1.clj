(ns resolver-sim.composition.v1
  "Phase-2 production-side conformance for Semantic Composition V1.

  This namespace independently implements the V1 compact projection and
  composition-root using the production canonical encoder
  (resolver-sim.hash.canonical).  It consumes clean-room conformance vectors
  only as EDN data; it shares no code with prf-clean-room.composition.

  Architecture (per docs/SEMANTIC_COMPOSITION_V1.md in prf-clean-room):

    production semantic objects
            │
            ▼
    production projection         ← project-* fns (adapter section)
            │
            ▼
    semantic-composition.v1 value ← compactly
            │
            ▼
    independent production encoder ← hc/canonical-bytes / hc/domain-hash
            │
            ├── canonical bytes
            └── composition-root
                    │
                    ▼
              golden vectors (verification only)

  The V1 compact projection (compactly, composition-root, identify) is a
  closed semantic boundary.  It is free of production-specific concepts:
  no execution controls, no authorisation issuance, no researcher control
  flow, no force-authorisation lifecycle.  Those concerns live in the
  adapter section below."
  (:require [resolver-sim.hash.canonical :as hc]))

;; ──────────────────────────────────────────────────────────────────────────────
;; V1 Domain Constants
;; ──────────────────────────────────────────────────────────────────────────────

(def v1-domain-tag
  "Domain tag for SHA-256(domain-tag || canonical-bytes(compact)).
   Matches SEMANTIC_COMPOSITION_V1.md exactly."
  "SEMANTIC_COMPOSITION_V1")

(def schema-version
  "Semantic Composition V1 schema version. Always 1."
  1)

;; ──────────────────────────────────────────────────────────────────────────────
;; V1 Compact Projection
;; Independently implemented — does not reference prf-clean-room code.
;; ──────────────────────────────────────────────────────────────────────────────

(defn- require-keys!
  "Fail if any of ks is absent from m."
  [m ks]
  (when-not (every? #(contains? m %) ks)
    (throw (ex-info "Semantic composition is missing required fields"
                    {:composition m :required ks}))))

(defn- compact-ideal-pro-rata
  "Normalize an ideal-pro-rata source form to its compact representation.

   Laws:
   1. :floor-and-carry and :largest-remainder have identical allocation
      semantics; both compact to :largest-remainder.
   2. A non-empty set of identical claimant contexts compacts to one
      :claimant-context.  Non-uniform contexts remain an ordered vector.
   3. Claimant repetition/cardinality is input identity, not composition
      identity: the root describes the constraint/operator."
  [dimensions]
  (require-keys! dimensions [:rounding-policy])
  (let [rounding-policy (:rounding-policy dimensions)
        contexts (vec (:claimant-contexts dimensions []))
        uniform? (and (seq contexts) (apply = contexts))
        effective-policy (case rounding-policy
                           :floor-and-carry :largest-remainder
                           rounding-policy)]
    (when-not (#{:floor :floor-and-carry :largest-remainder} rounding-policy)
      (throw (ex-info "Unsupported ideal-pro-rata rounding policy"
                      {:rounding-policy rounding-policy})))
    (cond-> {:rounding-policy effective-policy}
      uniform?  (assoc :claimant-context (first contexts))
      (not uniform?) (assoc :claimant-contexts contexts))))

(defn- compact-authorisation
  "Authorisation dimensions are independent: both booleans are preserved.
   No negation or derivation collapses the four Boolean points."
  [dimensions]
  (require-keys! dimensions [:forbidden? :authorized?])
  (let [forbidden? (:forbidden? dimensions)
        authorized? (:authorized? dimensions)]
    (when-not (and (boolean? forbidden?) (boolean? authorized?))
      (throw (ex-info "Authorisation dimensions must both be booleans"
                      {:dimensions dimensions})))
    {:forbidden? forbidden? :authorized? authorized?}))

(defn- compact-sequence
  "Sequence order is material; vector order is semantic."
  [dimensions]
  (require-keys! dimensions [:purpose :components])
  (let [purpose (:purpose dimensions)
        components (:components dimensions)]
    (when-not (and (keyword? purpose) (vector? components))
      (throw (ex-info "Composition sequence requires a keyword purpose and ordered vector components"
                      {:dimensions dimensions})))
    {:purpose purpose :components components}))

(defn- compact-consecutive-relation
  "Consecutive means predecessor then successor adjacency, not byte
   concatenation and not a state-transition proof."
  [dimensions]
  (require-keys! dimensions [:predecessor :successor])
  (let [predecessor (:predecessor dimensions)
        successor (:successor dimensions)]
    (when-not (and (some? predecessor) (some? successor))
      (throw (ex-info "Consecutive relation requires predecessor and successor identities"
                      {:dimensions dimensions})))
    {:predecessor predecessor :successor successor}))

(defn compactly
  "Return the canonical V1 compact representation for one supported family.

   This is the independently-implemented V1 compaction law.  It rejects
   :execution and :diagnostic keys rather than allowing them to affect
   semantic identity.

   Supported families (Phase-2 first pass — only rooted entries):
   - :ideal-pro-rata
   - :authorisation-usability-classification
   - :composition-sequence
   - :composition-consecutive-relation"
  [source]
  (when (some #(contains? source %) [:execution :diagnostic])
    (throw (ex-info "Execution and diagnostic data are not semantic composition fields"
                    {:composition source})))
  (require-keys! source [:composition/version :composition/family :composition/dimensions])
  (let [dimensions (:composition/dimensions source)]
    (case (:composition/family source)
      :ideal-pro-rata
      {:composition/version schema-version
       :composition/family :ideal-pro-rata
       :composition/dimensions (compact-ideal-pro-rata dimensions)}

      :authorisation-usability-classification
      {:composition/version schema-version
       :composition/family :authorisation-usability-classification
       :composition/dimensions (compact-authorisation dimensions)}

      :composition-sequence
      {:composition/version schema-version
       :composition/family :composition-sequence
       :composition/dimensions (compact-sequence dimensions)}

      :composition-consecutive-relation
      {:composition/version schema-version
       :composition/family :composition-consecutive-relation
       :composition/dimensions (compact-consecutive-relation dimensions)}

      (throw (ex-info "Unsupported semantic composition family"
                      {:family (:composition/family source)})))))

;; ──────────────────────────────────────────────────────────────────────────────
;; Composition Root
;; ──────────────────────────────────────────────────────────────────────────────

(defn canonical-bytes
  "Canonical binary encoding of a V1 compact value.
   Delegates to the production canonical encoder (hc/canonical-bytes)."
  [compact]
  (hc/canonical-bytes compact))

(defn canonical-bytes-hex
  "Lowercase hex of canonical-bytes(compact)."
  [compact]
  (hc/canonical-bytes-hex compact))

(defn composition-root
  "composition-root = lowercase-hex(SHA-256(domain-tag-bytes || compact-bytes))
   where domain-tag-bytes = UTF-8(\"SEMANTIC_COMPOSITION_V1\").

   Uses the production canonical encoder (hc/domain-hash).  Returns bare
   lowercase hex (no sha256: prefix), matching the clean-room spec."
  [compact]
  (hc/domain-hash v1-domain-tag compact))

(defn identify
  "Return both the V1 compact value and its composition-root for a source form."
  [source]
  (let [compact (compactly source)]
    {:composition/compact compact
     :composition/root (composition-root compact)}))

;; ──────────────────────────────────────────────────────────────────────────────
;; Production Projections (Adapter Layer)
;;
;; These map production-native structures into V1 source forms.  The V1 compact
;; projection above remains free of production-specific concepts.  The adapter
;; does not teach the semantic layer about authorisation issuance, signatures,
;; researcher control flow, or force-authorisation lifecycle.
;; ──────────────────────────────────────────────────────────────────────────────

(defn project-ideal-pro-rata
  "Project a production ideal-pro-rata allocation policy and claimant contexts
   into a V1 source form.

   production-policy:  {:mode :pro-rata :rounding-policy :floor-and-carry | :floor | :largest-remainder ...}
   claimant-contexts:  [{:account <kw> :direction :add|:sub} ...]

   The :mode is verified to be :pro-rata; other modes belong to different
   composition families and are not projected here."
  [production-policy claimant-contexts]
  (when-not (= :pro-rata (:mode production-policy))
    (throw (ex-info "project-ideal-pro-rata requires :mode :pro-rata"
                    {:mode (:mode production-policy)})))
  (let [rounding-policy (:rounding-policy production-policy)]
    (when-not (#{:floor :floor-and-carry :largest-remainder} rounding-policy)
      (throw (ex-info "Unsupported production rounding-policy for ideal-pro-rata"
                      {:rounding-policy rounding-policy})))
    {:composition/version schema-version
     :composition/family :ideal-pro-rata
     :composition/dimensions {:rounding-policy rounding-policy
                              :claimant-contexts (vec claimant-contexts)}}))

(defn project-authorisation
  "Project production authorisation validation facts into a V1 source form.

   forbidden? and authorized? are independent Booleans carried directly from
   production validation.  The adapter does not derive one from the other.

   Production callers supply the two facts as determined by their validation
   precedence (e.g. cancellation evaluation, force-authorisation scope check).
   The semantic layer does not learn which production rule produced them."
  [forbidden? authorized?]
  {:composition/version schema-version
   :composition/family :authorisation-usability-classification
   :composition/dimensions {:forbidden? (boolean forbidden?)
                            :authorized? (boolean authorized?)}})

(defn project-sequence
  "Project a production ordered component sequence into a V1 source form.

   purpose:  keyword identifying the semantic purpose of the sequence
   components: ordered vector of semantic identity keywords

   Vector order is semantic and must not be reordered by the adapter."
  [purpose components]
  {:composition/version schema-version
   :composition/family :composition-sequence
   :composition/dimensions {:purpose purpose :components (vec components)}})

(defn project-consecutive-relation
  "Project production predecessor/successor identities into a V1 source form.

   Represents adjacency (A then B), not byte concatenation, not a
   state-transition proof."
  [predecessor successor]
  {:composition/version schema-version
   :composition/family :composition-consecutive-relation
   :composition/dimensions {:predecessor predecessor :successor successor}})
