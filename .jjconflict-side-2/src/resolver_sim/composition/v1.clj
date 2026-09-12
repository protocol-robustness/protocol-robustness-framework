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
  (:require [clojure.set :as set]
            [resolver-sim.hash.canonical :as hc]))

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
;; V1 Source Key Contracts (versioned, exact — the canonical boundary)
;; A source is a canonical composition object ONLY if every key is accounted
;; for here.  No key is silently dropped.
;; ──────────────────────────────────────────────────────────────────────────────

(def ^:private root-keys
  "Exact allowed keys of a V1 semantic-composition source map. Any key outside
   #{root-keys ∪ non-semantic-source-keys} is rejected, so a richer object
   carrying surplus fields cannot collapse onto a canonical composition shape
   and thereby become eligible for recursive consecutive flattening."
  #{:composition/version :composition/family :composition/dimensions})

(def ^:private non-semantic-source-keys
  "Root keys that carry non-semantic runtime/diagnostic data. They are REJECTED
   explicitly — reported as :violation/non-semantic-composition-fields — rather
   than silently dropped, so they never influence the composition root. The only
   non-canonical keys the V1 schema names as ignorable are handled here; any
   other unknown key fails closed (see :violation/unknown-composition-source-key)."
  #{:execution :diagnostic})

(def ^:private dimension-keys
  "Exact allowed keys per family, for the :composition/dimensions sub-map. Any
   dimension key outside its family's set is rejected (fail closed) so a richer
   dimensions map cannot collapse onto the canonical shape."
  {:ideal-pro-rata                   #{:rounding-policy :claimant-contexts}
   :authorisation-usability-classification #{:forbidden? :authorized?}
   :composition-sequence             #{:purpose :components}
   :composition-consecutive-relation #{:predecessor :successor}})

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

(defn- source-violations
  "Validate a raw V1 semantic-composition source against the exact allowed-key
   contract — the authoritative consecutive-composition boundary.

   Returns a vector of violation maps (empty iff the source is a canonical
   composition object). Surplus keys are classified, never silently dropped:

   - known explicitly non-semantic field (:execution / :diagnostic)
     → :violation/non-semantic-composition-fields (rejected, per schema);
   - any other key not in the versioned allowed-key set
     → :violation/unknown-composition-source-key (fail closed);
   - the same rule applies per-family to :composition/dimensions
     → :violation/unknown-composition-dimension-key.

   A richer object is never silently projected down to canonical shape: it is
   rejected, so recognition for recursive consecutive flattening cannot be
   triggered by an accidental lossy projection."
  [source]
  (if-not (map? source)
    [{:violation/id :violation/non-map-composition-source
      :details {:source source}}]
    (let [src-keys      (set (keys source))
          known         (set/union root-keys non-semantic-source-keys)
          unknown-root  (seq (remove known src-keys))
          rejected-semantic (seq (filter non-semantic-source-keys src-keys))
          family         (:composition/family source)
          family-known?  (contains? dimension-keys family)
          dims           (:composition/dimensions source)
          unknown-dims   (when (and family-known? (map? dims))
                           (seq (remove (dimension-keys family) (keys dims))))]
      (cond-> []
        (seq unknown-root)
        (conj {:violation/id :violation/unknown-composition-source-key
               :details {:unknown (vec (sort unknown-root))
                         :allowed (vec (sort root-keys))}})

        (seq rejected-semantic)
        (conj {:violation/id :violation/non-semantic-composition-fields
               :details {:rejected (vec (sort rejected-semantic))}})

        (nil? (:composition/version source))
        (conj {:violation/id :violation/missing-composition-version :details {}})

        (nil? family)
        (conj {:violation/id :violation/missing-composition-family :details {}})

        (nil? dims)
        (conj {:violation/id :violation/missing-composition-dimensions :details {}})

        (and (some? dims) (not (map? dims)))
        (conj {:violation/id :violation/invalid-composition-dimensions
               :details {:composition/dimensions dims}})

        (and (some? (:composition/version source))
             (not= schema-version (:composition/version source)))
        (conj {:violation/id :violation/invalid-composition-version
               :details {:version (:composition/version source)
                         :supported schema-version}})

        (and (some? family) (not family-known?))
        (conj {:violation/id :violation/unsupported-semantic-composition-family
               :details {:family family
                         :supported (vec (sort (keys dimension-keys)))}})

        (seq unknown-dims)
        (conj {:violation/id :violation/unknown-composition-dimension-key
               :details {:family family
                         :unknown (vec (sort unknown-dims))
                         :allowed (vec (sort (dimension-keys family)))}})))))

(defn validate-source
  "Authoritative recogniser for a V1 semantic-composition source.

   Returns {:valid? bool :violations [...]}. Violations are empty iff the
   source is a canonical composition object under the exact allowed-key
   contract (fail-closed on unknown keys; known non-semantic :execution /
   :diagnostic keys rejected explicitly).

   Use this — not `compactly` — to decide whether an arbitrary value is a
   canonical consecutive-composition node prior to recursive flattening.
   `compactly` projects only after this validates; it never silently projects a
   richer object down to canonical shape."
  [source]
  (let [violations (source-violations source)]
    {:valid? (empty? violations) :violations (vec violations)}))

(defn compactly
  "Return the canonical V1 compact representation for one supported family.

   Authoritative boundary: the source is first validated against the exact
   allowed-key contract (validate-source). Non-map sources, missing required
   keys, unknown keys, and non-semantic :execution / :diagnostic keys all fail
   closed — they are rejected rather than silently projected away, so a richer
   object cannot become eligible for recursive consecutive flattening merely
   because a lossy projection drops its surplus fields.

   Supported families (Phase-2 first pass — only rooted entries):
   - :ideal-pro-rata
   - :authorisation-usability-classification
   - :composition-sequence
   - :composition-consecutive-relation"
  [source]
  (let [validation (validate-source source)]
    (when-not (:valid? validation)
      (throw (ex-info "Source is not a canonical V1 semantic-composition object"
                      (assoc validation :composition source)))))
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
