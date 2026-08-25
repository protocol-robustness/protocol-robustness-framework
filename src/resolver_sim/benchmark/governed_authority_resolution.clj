(ns resolver-sim.benchmark.governed-authority-resolution
  "Stage A contracts for state-addressed governed-authority resolution.

  These artifacts commit the question, the authenticated answer, and the later
  transition binding separately. They intentionally contain no resolver,
  callback, state-store, or admission implementation; authenticated lookup,
  current-head fencing, and consumer integration are subsequent stages."
  (:require [clojure.set :as set]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as ref]))

(def ^:const resolution-basis-schema "governed-authority-resolution-basis.v1")
(def ^:const resolution-basis-v2-schema "governed-authority-resolution-basis.v2")
(def ^:const resolver-schema "governed-authority-resolver.v1")
(def ^:const resolved-context-schema "resolved-review-authority-context.v1")
(def ^:const transition-binding-schema "governed-authority-transition-binding.v1")

(def ^:const resolution-basis-domain :governed-authority-resolution-basis-v1)
(def ^:const resolution-basis-v2-domain :governed-authority-resolution-basis-v2)
(def ^:const resolver-domain :governed-authority-resolver-v1)
(def ^:const resolved-context-domain :resolved-review-authority-context-v1)
(def ^:const transition-binding-domain :governed-authority-transition-binding-v1)

(def resolution-purposes
  #{:current-admission :transition-replay :historical-audit})

(def failure-classes
  "Stable failure taxonomy for later state-resolution implementations. A failure
   class is not evidence of absence unless Stage B supplies an authenticated
   state view or non-membership proof."
  #{:state-unavailable :state-not-authoritative :state-wrong-chain
    :state-not-at-required-head :round-not-found-at-state :round-context-mismatch
    :governance-not-active-at-state :governance-activation-invalid
    :position-time-basis-invalid :reviewer-not-authorized-at-state
    :threshold-not-satisfied-at-state :control-plane-evidence-invalid
    :authority-state-membership-unproven :resolver-unavailable
    :resolver-internal-error})

(def ^:private basis-fields
  #{:artifact/schema :resolution/purpose :chain-instance-genesis/root
    :resolution/state-before-root :resolution/anchor-root :review-round/hash})

(def ^:private basis-v2-fields
  (conj basis-fields :authority-resolver/root))

(def ^:private resolver-fields
  #{:artifact/schema :resolver/id :resolver/contract :resolver/profile :resolver/version})

(def ^:private context-fields
  #{:artifact/schema :resolution-basis/root :chain-instance-genesis/root
    :resolution/state-before-root :authority-state/root
    :chain-configuration/root :review-governance/root
    :review-governance-activation/root :review-round/hash :review-round/root
    :position-time-basis/root :review-governance-admissibility/root
    :control-plane-evidence/root})

(def ^:private binding-fields
  #{:artifact/schema :resolved-review-authority-context/root :transition/root
    :transaction/state-before-root :transaction/state-after-root
    :authorization/result-root})

(defn- root [domain root-key value]
  (ref/sha256-ref
   (hc/domain-hash domain
                   (hc/project-canonical-safe (dissoc value root-key)))))

(defn- validate-closed-rooted
  [artifact schema fields root-key domain]
  (let [errors (atom [])
        report! #(swap! errors conj %)]
    (if-not (map? artifact)
      (report! "artifact must be a map")
      (let [have (set (keys artifact))
            extra (set/difference have fields #{root-key})
            missing (set/difference fields have)]
        (when-not (= schema (:artifact/schema artifact))
          (report! (str "artifact/schema must be " schema)))
        (when (seq extra)
          (report! (str "unknown artifact keys: " (sort extra))))
        (when (seq missing)
          (report! (str "missing artifact keys: " (sort missing))))
        (doseq [field (disj fields :artifact/schema :resolution/purpose)
                :when (contains? have field)
                :let [value (get artifact field)]]
          (when-not (ref/valid-sha256-ref? value)
            (report! (str field " must be a valid sha256 reference"))))
        (when (and (empty? extra) (contains? have root-key))
          (let [declared (get artifact root-key)]
            (cond
              (not (ref/valid-sha256-ref? declared))
              (report! (str root-key " must be a valid sha256 reference"))

              (not= declared (root domain root-key artifact))
              (report! (str root-key " does not match recomputed root")))))))
    {:valid? (empty? @errors) :errors (vec @errors)}))

(defn resolver-root
  "Root of a semantic resolver descriptor; never includes implementation or host identity."
  [descriptor]
  (let [have (if (map? descriptor) (set (keys descriptor)) #{})
        errors (cond-> []
                 (not= resolver-schema (:artifact/schema descriptor)) (conj "artifact/schema is invalid")
                 (seq (set/difference have resolver-fields #{:governed-authority-resolver/root})) (conj "resolver has unknown keys")
                 (seq (set/difference resolver-fields have)) (conj "resolver has missing keys")
                 (not (keyword? (:resolver/id descriptor))) (conj "resolver/id must be a keyword")
                 (not (keyword? (:resolver/contract descriptor))) (conj "resolver/contract must be a keyword")
                 (not (keyword? (:resolver/profile descriptor))) (conj "resolver/profile must be a keyword")
                 (not (pos-int? (:resolver/version descriptor))) (conj "resolver/version must be positive"))]
    (when (seq errors)
      (throw (ex-info "governed-authority-resolver.v1 is invalid" {:errors errors})))
    (root resolver-domain :governed-authority-resolver/root descriptor)))

(defn build-resolver-descriptor [descriptor]
  (let [base (assoc descriptor :artifact/schema resolver-schema)]
    (assoc base :governed-authority-resolver/root (resolver-root base))))

(def default-resolver
  (build-resolver-descriptor {:resolver/id :governed-review-authority
                              :resolver/contract :governed-authority-resolution.v1
                              :resolver/profile :state-addressed
                              :resolver/version 1}))

(def known-descriptors
  "Known governed-authority resolver descriptors. This is the single source of
   truth for recognized semantic resolver identity; `known-resolver-roots` and
   `known-resolver-map` are derived by re-rooting each descriptor, so descriptor
   definition and registry membership cannot drift."
  #{default-resolver})

(def ^:private known-resolver-map
  "Root → descriptor mapping derived from `known-descriptors`. A descriptor is
   retained only when its committed `:governed-authority-resolver/root` matches
   the recomputed `resolver-root`, guaranteeing self-validation at the dispatch
   boundary."
  (into {}
        (for [d known-descriptors
              :when (= (resolver-root d)
                       (:governed-authority-resolver/root d))]
          [(:governed-authority-resolver/root d) d])))

(def known-resolver-roots
  "Set of recognized resolver roots, derived from `known-descriptors` by
   re-rooting. A root is recognized only when a descriptor in
   `known-descriptors` self-validates to it."
  (set (keys known-resolver-map)))

(defn recognized-resolver-descriptor
  "Return the recognized resolver descriptor for a committed resolver root.
   Returns nil for unknown roots. For recognized roots, returns the descriptor
   only when it self-validates to the requested root:
   `(= root (resolver-root descriptor))`.

   This is the dispatch boundary between a committed V2 resolution basis and the
   resolver implementation: the descriptor dispatched for a committed root must be
   the descriptor that actually roots to it, which prevents a basis that commits
   R1 from dispatching an implementation for R2."
  [root]
  (when-let [descriptor (get known-resolver-map root)]
    (when (= root (resolver-root descriptor))
      descriptor)))

(defn validate-resolution-basis
  "Validate a closed resolution question. Operation intent is deliberately not
   part of v1: adding it requires a separate presence-profile to avoid an
   ambiguous absent-versus-nil commitment."
  [basis]
  (let [base (validate-closed-rooted basis resolution-basis-schema basis-fields
                                     :resolution-basis/root resolution-basis-domain)
        errors (cond-> (:errors base)
                 (and (map? basis)
                      (not (contains? resolution-purposes (:resolution/purpose basis))))
                 (conj (str ":resolution/purpose must be one of "
                            (sort resolution-purposes))))]
    {:valid? (empty? errors) :errors (vec errors)}))

(defn resolution-basis-root [basis]
  (let [result (validate-resolution-basis basis)]
    (when-not (:valid? result)
      (throw (ex-info "governed-authority-resolution-basis.v1 is invalid"
                      {:type :governed-authority-resolution-basis/invalid
                       :errors (:errors result)})))
    (root resolution-basis-domain :resolution-basis/root basis)))

(defn build-resolution-basis
  [basis]
  (assoc basis :artifact/schema resolution-basis-schema
         :resolution-basis/root
         (resolution-basis-root (assoc basis :artifact/schema resolution-basis-schema))))

(defn validate-resolved-context
  "Validate the closed, data-only answer to a resolution basis. It proves only
   artifact integrity and declared bindings; Stage B will prove that these roots
   were authenticated members of the requested authority state."
  [context]
  (validate-closed-rooted context resolved-context-schema context-fields
                          :resolved-review-authority-context/root
                          resolved-context-domain))

(defn validate-resolution-basis-v2 [basis]
  (let [base (validate-closed-rooted basis resolution-basis-v2-schema basis-v2-fields
                                     :resolution-basis/root resolution-basis-v2-domain)
        root (:authority-resolver/root basis)
        errors (cond-> (:errors base)
                 (and (map? basis)
                      (not (contains? resolution-purposes (:resolution/purpose basis))))
                 (conj ":resolution/purpose is invalid")
                 (and (map? basis) (not (contains? known-resolver-roots root)))
                 (conj ":authority-resolver/root is unknown")
                 (and (map? basis)
                      (contains? known-resolver-roots root)
                      (nil? (recognized-resolver-descriptor root)))
                 (conj ":authority-resolver/root descriptor failed self-validation"))]
    {:valid? (empty? errors) :errors (vec errors)}))

(defn build-resolution-basis-v2 [basis]
  (let [base (assoc basis :artifact/schema resolution-basis-v2-schema)]
    (assoc base :resolution-basis/root
           (let [result (validate-resolution-basis-v2 base)]
             (when-not (:valid? result)
               (throw (ex-info "governed-authority-resolution-basis.v2 is invalid" result)))
             (root resolution-basis-v2-domain :resolution-basis/root base)))))

(defn validate-resolution-basis-any
  "Validate a resolution basis under the schema declared by the artifact.
   V1 remains acceptable for `:historical-audit` and `:transition-replay`
   (backward compatibility with historical artifacts). V2 is required for
   `:current-admission`: a live admission cannot silently downgrade to a basis
   that lacks a committed resolver identity. Historical replay and audit retain
   V1 compatibility; live current-admission does not."
  [basis]
  (case (:artifact/schema basis)
    "governed-authority-resolution-basis.v1"
    (let [result (validate-resolution-basis basis)]
      (if (and (:valid? result)
               (= :current-admission (:resolution/purpose basis)))
        (assoc result
               :valid? false
               :errors (conj (:errors result)
                             "current-admission requires governed-authority-resolution-basis.v2"))
        result))
    "governed-authority-resolution-basis.v2" (validate-resolution-basis-v2 basis)
    {:valid? false :errors ["unsupported resolution basis schema"]}))

(defn resolved-context-root [context]
  (let [result (validate-resolved-context context)]
    (when-not (:valid? result)
      (throw (ex-info "resolved-review-authority-context.v1 is invalid"
                      {:type :resolved-review-authority-context/invalid
                       :errors (:errors result)})))
    (root resolved-context-domain :resolved-review-authority-context/root context)))

(defn build-resolved-context [context]
  (assoc context :artifact/schema resolved-context-schema
         :resolved-review-authority-context/root
         (resolved-context-root (assoc context :artifact/schema resolved-context-schema))))

(defn validate-transition-binding [binding]
  (validate-closed-rooted binding transition-binding-schema binding-fields
                          :governed-authority-transition-binding/root
                          transition-binding-domain))

(defn transition-binding-root [binding]
  (let [result (validate-transition-binding binding)]
    (when-not (:valid? result)
      (throw (ex-info "governed-authority-transition-binding.v1 is invalid"
                      {:type :governed-authority-transition-binding/invalid
                       :errors (:errors result)})))
    (root transition-binding-domain :governed-authority-transition-binding/root binding)))

(defn build-transition-binding [binding]
  (assoc binding :artifact/schema transition-binding-schema
         :governed-authority-transition-binding/root
         (transition-binding-root (assoc binding :artifact/schema transition-binding-schema))))

(defn transition-binding-compatible?
  "Pure cross-artifact relation for post hoc verification. This is intentionally
   separate from context construction, keeping the authority dependency graph
   acyclic: authority is decided from pre-state; after-state belongs to the
   completed transition binding."
  [context binding]
  (and (:valid? (validate-resolved-context context))
       (:valid? (validate-transition-binding binding))
       (= (:resolved-review-authority-context/root binding)
          (:resolved-review-authority-context/root context))
       (= (:resolution/state-before-root context)
          (:transaction/state-before-root binding))))
