(ns resolver-sim.composition.semantic
  "semantic-composition.v1 — authoritative semantic composition derived from
   canonical capability resolution.

   This namespace constructs semantic composition exclusively from resolved
   capability facts. A caller must NOT be able to supply arbitrary:
   - resolution-root;
   - provider package roots;
   - capability descriptors;
   - action modules;
   - state modules;
   - invariant modules.

   The production constructor accepts only:
   - :profile                — semantic profile keyword (e.g. :production-governed)
   - :requested-capabilities  — seq of [kind id] capability references
   - :policy-inputs         — canonical policy inputs (schemas, effect schemas)

   and a canonical extension-map (the registry snapshot). Everything else is
   derived internally: dependency closure, selected capabilities, selected
   provider package roots, action/state/invariant modules, and policy bindings.

   Canonical root domain tag:
     \"SEMANTIC_COMPOSITION_V1\""
  (:require [resolver-sim.extensions.manifest :as em]
            [resolver-sim.extensions.resolution :as resolution]
            [resolver-sim.hash.canonical :as hc]))

;; ── canonical root domain ─────────────────────────────────────────────

(def composition-domain-tag
  "Semantic composition root domain tag."
  "SEMANTIC_COMPOSITION_V1")

(def ^:private semantic-composition-version 1)

;; ── force-auth canonical live-state region keys ───────────────────────

(def force-authorisation-state-keys
  "Authoritative set of force-authorisation live-state region keys.
   These are the only keys that the semantic composition binds as force-auth
   state regions. :next-force-authorisation-id is included because Sew reads
   it for id allocation; :force-authorisations/consumption-records is included
   because it is force-auth-owned bookkeeping, not diagnostic/derived."
  #{:force-authorisations
    :force-authorisations/consumed
    :force-authorisations/consumption-records
    :next-force-authorisation-id})

(def force-authorisation-actions
  "Authoritative force-authorisation action-class vocabulary.
   Membership answers 'is this action exceptional force-authorisation class?'
   for admission gating. Values are the Sew action names (strings) dispatched
   by resolver-sim.protocols.sew/apply-action; both the British and American
   spellings are included because Sew registers both as distinct actions.
   Sew keeps no protocol-owned copy of this set — class membership is owned
   here, selection is answered by the active composition, and legacy
   compatibility is explicit and non-authoritative only."
  #{"grant-force-authorisation"
    "grant-force-authorization"
    "grant-consensus-force-authorisation"
    "grant-related-claims-force-authorisation"
    "revoke-force-authorisation"
    "revoke-force-authorization"
    "execute-force-authorised-action"
    "execute-force-authorized-action"})

(def custody-execution-capability
  "The capability selector whose selection activates force-auth semantics."
  [:sew/force-authorisation :force-authorisation/custody-execution-v1])

(def force-authorisation-state-regions
  "Authoritative live Sew world-state region keys owned by the
   force-authorisation module. A world may carry these keys only when the
   active composition selects :custody-execution-capability; consumers strip
   un-owned regions at initialization and report violations during dispatch."
  #{:force-authorisations
    :force-authorisations/consumed
    :force-authorisations/consumption-records
    :next-force-authorisation-id})

;; ── module descriptors ────────────────────────────────────────────────
;;
;; A module descriptor is a SET of member keys. build expands set-valued
;; module entries into their members, so a composition's stored modules are
;; flat vectors of keys.

(def force-authorisation-action-module
  "Module descriptor selecting the full force-authorisation action class."
  force-authorisation-actions)

(def force-authorisation-state-module
  "Module descriptor selecting every force-authorisation live-state region."
  force-authorisation-state-regions)

(def force-authorisation-invariant-module
  "Module descriptor selecting the force-authorisation operational invariants."
  #{:force-authorisations-lifecycle-consistent
    :force-authorisations-governance-origin})

(defn expand-modules
  "Expand set-valued module entries into their members; pass other entries
   through. Returns a flat vector of distinct keys."
  [modules]
  (vec (distinct (mapcat (fn [m] (if (set? m) m [m])) modules))))

;; ── capability → semantic module selectors ────────────────────────────

(defn- action-modules-for
  "Derive the dispatched Sew action names from selected capabilities. A
   composition's action-modules are queried directly by `allows-action?`, so
   they must use the same string identities as Sew dispatch."
  [selected]
  (if (contains? selected custody-execution-capability)
    (vec (sort force-authorisation-actions))
    []))

(defn- state-modules-for
  "Derive state-region modules from selected capabilities.
   scope-verification only  → none
   governed-permit only     → none (no live Sew state mutation)
   custody-execution        → force-auth state regions"
  [selected]
  (cond-> []
    (contains? selected [:sew/force-authorisation :force-authorisation/custody-execution-v1])
    (into (map (fn [k] [::force-authorisation k]) force-authorisation-state-keys))))

(defn- invariant-modules-for
  "Derive invariant modules from selected capabilities."
  [selected]
  (cond-> []
    (contains? selected [:sew/force-authorisation :force-authorisation/custody-execution-v1])
    (conj [:sew/force-authorisation :lifecycle-consistent])
    (contains? selected [:sew/force-authorisation :force-authorisation/custody-execution-v1])
    (conj [:sew/force-authorisation :governance-origin])))

;; ── module validation ─────────────────────────────────────────────────

(defn validate-modules
  "Reject inconsistent module claims. A composition that requests force-auth
   modules (actions/state/invariants) but does not have the custody-execution
   capability selected is invalid. Conversely, selecting custody-execution
   must produce exactly the force-auth module set — no extra force-auth modules
   from other sources."
  [selected action-modules state-modules invariant-modules]
  (let [fa-action-module-member?
        ;; Action modules come in two shapes: capability-qualified pairs from
        ;; compose-authoritative, and bare Sew action-name strings from build.
        (fn [m]
          (or (and (sequential? m) (= (first m) :sew/force-authorisation))
              (contains? force-authorisation-actions m)))
        fa-action-modules (filter fa-action-module-member? action-modules)
        fa-state-modules (filter #(= (first %) ::force-authorisation) state-modules)
        fa-invariant-modules (filter #(= (first %) :sew/force-authorisation) invariant-modules)
        custody-selected (contains? selected [:sew/force-authorisation :force-authorisation/custody-execution-v1])]
    (cond-> []
      (and (seq (concat fa-action-modules fa-state-modules fa-invariant-modules))
           (not custody-selected))
      (conj {:violation/id :composition/error-force-auth-modules-without-capability
             :details {:action-modules fa-action-modules
                       :state-modules fa-state-modules
                       :invariant-modules fa-invariant-modules}})
      (and custody-selected (empty? fa-state-modules))
      (conj {:violation/id :composition/error-missing-force-auth-state-modules
             :details {:selected (into [] selected)}})
      (and custody-selected (empty? fa-action-modules))
      (conj {:violation/id :composition/error-missing-force-auth-action-modules
             :details {:selected (into [] selected)}}))))

;; ── policy binding ────────────────────────────────────────────────────

(defn- canonical-force-authorisation-policy-root
  "Canonical force-authorisation policy root, derived from the resolution
   snapshot's effect-schemas for force-auth contracts. Not caller-supplied."
  [resolution]
  (when-let [schema-roots (:extensions/schema-roots resolution)]
    (when-let [prov (get schema-roots :sew/force-authorisation-governed-provenance.v1)]
      prov)))

(defn binding-force-authorisation-policy
  "Return the canonical force-authorisation policy root for this composition,
   or nil when no force-auth capability is selected."
  [resolution]
  (let [caps (:extensions/capabilities resolution)]
    (when (or (contains? caps [:sew/force-authorisation :force-authorisation/custody-execution-v1])
              (contains? caps [:assurance/force-authorisation :force-authorisation/governed-permit-v1]))
      (canonical-force-authorisation-policy-root resolution))))

;; ── authoritative constructor ──────────────────────────────────────────

(defn- build-canonical-snapshot
  "Run resolve-requested and build a validated dependency closure.
   Returns [:ok resolution] or [:error violations]."
  [extension-map requested-capabilities opts]
  (let [result (resolution/resolve-requested extension-map requested-capabilities opts)]
    (if (:valid? result)
      [:ok (:resolution result)]
      [:error (:violations result)])))

(defn- selected-capability-keys
  "Set of [kind id] keys from a resolution snapshot's capabilities."
  [resolution]
  (set (map (fn [[k _v]] k) (:extensions/capabilities resolution))))

(defn- provider-package-roots
  "Derive the set of provider package roots from the resolution snapshot."
  [resolution]
  (into #{} (map :package-root)
        (vals (:extensions/packages resolution))))

(defn- committed-capabilities
  "Project resolved capabilities to their committed identity form."
  [resolution]
  (:extensions/capabilities resolution))

(defn- committed-dependencies
  "Project resolved dependency edges."
  [resolution]
  (:extensions/dependencies resolution))

(defn- committed-capability-providers
  "Project exact resolved provider package identities per capability."
  [resolution]
  (:extensions/capability-providers resolution))

(defn- committed-packages
  "Project resolved provider packages."
  [resolution]
  (:extensions/packages resolution))

(defn- composition-root-from-portable-body
  "Derive a semantic-composition root from the exact portable identity
   projection. This is shared by the trusted constructor and the untrusted
   portable-material verifier so they cannot drift."
  [body]
  (hc/domain-hash composition-domain-tag
                  (hc/project-canonical-safe
                   (dissoc body :semantic-composition/root))))

(defn- compose-root
  "Compute the canonical composition root from all committed fields."
  [base]
  (assoc base :semantic-composition/root
         (composition-root-from-portable-body base)))

(defrecord SemanticComposition
           [profile
            requested-capabilities
            resolution-root
            packages
            capabilities
            dependencies
            capability-providers
            selected-capabilities
            provider-package-roots
            action-modules
            state-modules
            invariant-modules
            policy-bindings
            composed-root])

(defn compose-authoritative
  "Authoritative production constructor for semantic-composition.v1.

   Accepts ONLY semantic inputs:
   - profile:               semantic profile keyword (e.g. :production-governed or :development)
   - requested-capabilities: seq of [capability-kind capability-id] vectors
   - opts:                  canonical options passed through to resolution
                            (:runtime-profile, :sealed?, :schemas, :effect-schemas)
   - extension-map:         canonical extension registry snapshot (REQUIRED)

   Derives ALL resolved facts internally via canonical capability resolution.
   A caller must NOT supply resolution-root, provider package roots, capability
   descriptors, action modules, state modules, or invariant modules.

   Physical-package availability rule:
   - physical package absent + plain composition -> valid
   - physical package absent + force-auth capability requested -> fail closed
     with :composition/error-requested-capability-unavailable"
  ([profile requested-capabilities opts extension-map]
   (let [normalised-requested (vec (map (fn [[k id]] [k id]) requested-capabilities))
         resolution-result (build-canonical-snapshot extension-map normalised-requested opts)]
     (if (= :ok (first resolution-result))
       (let [resolution (second resolution-result)
             selected (selected-capability-keys resolution)
             action-modules (vec (set (action-modules-for selected)))
             state-modules (vec (set (state-modules-for selected)))
             invariant-modules (vec (set (invariant-modules-for selected)))
             module-violations (validate-modules selected action-modules state-modules invariant-modules)]
         (if (seq module-violations)
           {:valid? false
            :violations module-violations}
           (let [policy-binding {:force-authorisation
                                 (binding-force-authorisation-policy resolution)}
                 composition (->SemanticComposition
                              profile
                              normalised-requested
                              (:extensions/resolution-root resolution)
                              (committed-packages resolution)
                              (committed-capabilities resolution)
                              (committed-dependencies resolution)
                              (committed-capability-providers resolution)
                              selected
                              (provider-package-roots resolution)
                              action-modules
                              state-modules
                              invariant-modules
                              policy-binding
                              nil)
                 root (-> {:semantic-composition/version semantic-composition-version
                           :semantic-composition/profile profile
                           :semantic-composition/requested-capabilities normalised-requested
                           :semantic-composition/resolution-root (:extensions/resolution-root resolution)
                           :semantic-composition/packages (committed-packages resolution)
                           :semantic-composition/capabilities (committed-capabilities resolution)
                           :semantic-composition/dependencies (committed-dependencies resolution)
                           :semantic-composition/capability-providers (committed-capability-providers resolution)
                           :semantic-composition/selected-capabilities selected
                           :semantic-composition/provider-package-roots (provider-package-roots resolution)
                           :semantic-composition/action-modules action-modules
                           :semantic-composition/state-modules state-modules
                           :semantic-composition/invariant-modules invariant-modules
                           :semantic-composition/policy-bindings policy-binding}
                          (compose-root))
                 final-composition (assoc composition :composed-root (:semantic-composition/root root))]
             {:valid? true
              :composition (-> final-composition
                               (assoc :root (:semantic-composition/root root))
                               (assoc :resolution resolution))})))
       (let [violations (second resolution-result)]
         {:valid? false
          :violations violations})))))

;; ── query interface ────────────────────────────────────────────────────

(defn validate
  "Validate a semantic-composition.v1 map.
   Returns {:valid? bool :errors [...]}."
  [composition]
  (let [errors (atom [])
        report! (fn [& msgs] (swap! errors #(into % msgs)))]
    (when-not (map? composition)
      (report! "semantic-composition must be a map"))
    (when (map? composition)
      (when-not (= semantic-composition-version
                   (:semantic-composition/version composition))
        (report! (str "semantic-composition/version must be "
                      semantic-composition-version
                      ", got " (pr-str (:semantic-composition/version composition)))))
      (when-not (contains? composition :semantic-composition/root)
        (report! "semantic-composition/root missing"))
      (when-not (contains? composition :selected-capabilities)
        (report! "selected-capabilities missing"))
      (when-not (contains? composition :action-modules)
        (report! "action-modules missing"))
      (when-not (contains? composition :state-modules)
        (report! "state-modules missing"))
      (when-not (contains? composition :invariant-modules)
        (report! "invariant-modules missing"))
      (let [violations
            (validate-modules (:selected-capabilities composition)
                              (:action-modules composition)
                              (:state-modules composition)
                              (:invariant-modules composition))]
        (when (seq violations)
          (report! "module violations" (pr-str violations)))))
    {:valid? (empty? @errors) :errors (vec @errors)}))

(defn selected-capability?
  "True when the named [kind id] capability is in this composition's selected set."
  [composition capability-key]
  (contains? (:selected-capabilities composition) capability-key))

(defn active-regions
  "Set of live state-region keys for this composition."
  [composition]
  (set (:state-modules composition)))

(defn active-actions
  "Vector of active action module keys for this composition."
  [composition]
  (:action-modules composition))

(defn active-invariants
  "Set of active invariant module keys for this composition. Returned as a
   set so membership predicates read as value containment, not vector index
   containment."
  [composition]
  (set (:invariant-modules composition)))

(defn allows-action?
  "True when the action module key is in this composition's active actions."
  [composition action-key]
  (let [actions (set (:action-modules composition))]
    (contains? actions action-key)))

(defn resolution-root
  "The content-addressed resolution root committed by this composition."
  [composition]
  (:resolution-root composition))

(defn composition-root
  "The content-addressed semantic-composition root."
  [composition]
  (:root composition))

(def ^:private portable-keys
  #{:semantic-composition/version :semantic-composition/profile
    :semantic-composition/requested-capabilities :semantic-composition/resolution-root
    :semantic-composition/packages :semantic-composition/capabilities
    :semantic-composition/dependencies :semantic-composition/capability-providers
    :semantic-composition/selected-capabilities
    :semantic-composition/provider-package-roots :semantic-composition/action-modules
    :semantic-composition/state-modules :semantic-composition/invariant-modules
    :semantic-composition/policy-bindings :semantic-composition/root})

(defn portable-body
  "Return the exact canonical semantic-composition body suitable for persistence."
  [composition]
  {:semantic-composition/version semantic-composition-version
   :semantic-composition/profile (:profile composition)
   :semantic-composition/requested-capabilities (:requested-capabilities composition)
   :semantic-composition/resolution-root (:resolution-root composition)
   :semantic-composition/packages (:packages composition)
   :semantic-composition/capabilities (:capabilities composition)
   :semantic-composition/dependencies (:dependencies composition)
   :semantic-composition/capability-providers (:capability-providers composition)
   :semantic-composition/selected-capabilities (:selected-capabilities composition)
   :semantic-composition/provider-package-roots (:provider-package-roots composition)
   :semantic-composition/action-modules (:action-modules composition)
   :semantic-composition/state-modules (:state-modules composition)
   :semantic-composition/invariant-modules (:invariant-modules composition)
   :semantic-composition/policy-bindings (:policy-bindings composition)
   :semantic-composition/root (composition-root composition)})

(defn- portable-capability-key?
  [value]
  (and (vector? value)
       (= 2 (count value))
       (every? keyword? value)))

(defn- distinct-vector?
  [value]
  (and (vector? value) (= (count value) (count (distinct value)))))

(defn- portable-structure-errors
  "Return errors detectable from the persisted material alone. In particular,
   this does not re-resolve capabilities or consult an extension registry: a
   frozen portable body must be a complete closed-form materialization."
  [body]
  (let [requested (:semantic-composition/requested-capabilities body)
        selected (:semantic-composition/selected-capabilities body)
        packages (:semantic-composition/packages body)
        capabilities (:semantic-composition/capabilities body)
        dependencies (:semantic-composition/dependencies body)
        capability-providers (:semantic-composition/capability-providers body)
        provider-roots (:semantic-composition/provider-package-roots body)
        actions (:semantic-composition/action-modules body)
        states (:semantic-composition/state-modules body)
        invariants (:semantic-composition/invariant-modules body)
        policy-bindings (:semantic-composition/policy-bindings body)
        package-roots (when (map? packages)
                        (into #{} (keep :package-root) (vals packages)))
        selected-keys (when (set? selected) selected)
        module-violations (when (and (set? selected) (vector? actions)
                                     (vector? states) (vector? invariants))
                            (validate-modules selected actions states invariants))]
    (cond-> []
      (not (keyword? (:semantic-composition/profile body)))
      (conj :semantic-composition/invalid-profile)
      (not (string? (:semantic-composition/resolution-root body)))
      (conj :semantic-composition/invalid-resolution-root)
      (not (and (distinct-vector? requested)
                (every? portable-capability-key? requested)))
      (conj :semantic-composition/invalid-requested-capabilities)
      (not (set? selected))
      (conj :semantic-composition/invalid-selected-capabilities)
      (and (set? selected) (not (every? portable-capability-key? selected)))
      (conj :semantic-composition/invalid-selected-capability)
      (not (map? packages))
      (conj :semantic-composition/invalid-packages)
      (and (map? packages)
           (not (every? (fn [[id package]]
                          (and (keyword? id)
                               (map? package)
                               (= id (:package/id package))
                               (string? (:package/version package))
                               (string? (:package-root package))
                               (contains? package :sealed)))
                        packages)))
      (conj :semantic-composition/invalid-package-entry)
      (not (map? capabilities))
      (conj :semantic-composition/invalid-capabilities)
      (not (map? capability-providers))
      (conj :semantic-composition/invalid-capability-providers)
      (and (map? capability-providers) (map? capabilities)
           (not= (set (keys capabilities)) (set (keys capability-providers))))
      (conj :semantic-composition/capability-provider-mismatch)
      (and (map? capabilities)
           (not (every? (fn [[key descriptor]]
                          (and (portable-capability-key? key)
                               (map? descriptor)
                               (= key [(:capability/kind descriptor)
                                       (:capability/id descriptor)])))
                        capabilities)))
      (conj :semantic-composition/invalid-capability-entry)
      (and (set? selected-keys) (map? capabilities)
           (not= selected-keys (set (keys capabilities))))
      (conj :semantic-composition/selected-capability-mismatch)
      (and (set? selected-keys)
           (not (every? selected-keys requested)))
      (conj :semantic-composition/requested-capability-missing)
      (not (and (vector? dependencies)
                (every? (fn [edge]
                          (and (map? edge)
                               (portable-capability-key? (:from edge))
                               (portable-capability-key? (:to edge))))
                        dependencies)))
      (conj :semantic-composition/invalid-dependencies)
      (and (vector? dependencies) (set? selected-keys)
           (not (every? #(and (selected-keys (:from %))
                              (selected-keys (:to %)))
                        dependencies)))
      (conj :semantic-composition/dependency-capability-mismatch)
      (not (set? provider-roots))
      (conj :semantic-composition/invalid-provider-package-roots)
      (and (set? provider-roots) (not (every? string? provider-roots)))
      (conj :semantic-composition/invalid-provider-package-root)
      (and package-roots (set? provider-roots) (not= package-roots provider-roots))
      (conj :semantic-composition/provider-package-root-mismatch)
      (not (and (vector? actions) (vector? states) (vector? invariants)))
      (conj :semantic-composition/invalid-module-entry)
      (not (map? policy-bindings))
      (conj :semantic-composition/invalid-policy-bindings)
      (seq module-violations)
      (conj :semantic-composition/module-consistency-failure))))

(defn verify-portable!
  "Verify untrusted persisted composition material and return its canonical body.

   This is the deserialization trust boundary for semantic-composition.v1. It
   validates a closed shape and closed form using only `body`, then recomputes
   the root over the exact constructor projection. It never performs extension
   lookup, provider re-resolution, or ambient recovery."
  [body]
  (when-not (and (map? body) (= portable-keys (set (keys body))))
    (throw (ex-info "Portable semantic composition has invalid shape"
                    {:error :semantic-composition/invalid-portable-shape})))
  (when-not (= semantic-composition-version (:semantic-composition/version body))
    (throw (ex-info "Portable semantic composition has unsupported version"
                    {:error :semantic-composition/unsupported-version})))
  (when-let [errors (seq (portable-structure-errors body))]
    (throw (ex-info "Portable semantic composition has invalid closed form"
                    {:error :semantic-composition/invalid-portable-material
                     :errors (vec errors)})))
  (let [root (composition-root-from-portable-body body)]
    (when-not (= root (:semantic-composition/root body))
      (throw (ex-info "Portable semantic composition root mismatch"
                      {:error :semantic-composition/root-mismatch
                       :declared (:semantic-composition/root body) :computed root})))
    body))

(defn- build*
  "Unchecked constructor backing build. Normalizes module descriptors,
   derives the composition root over the canonical-safe projection of the
   input, and returns a SemanticComposition satisfying validate."
  [{:semantic-composition/keys [profile capabilities action-modules
                                state-region-modules invariant-modules
                                policy-bindings resolution-root]
    :or {capabilities [] action-modules [] state-region-modules []
         invariant-modules [] policy-bindings {}}}]
  (let [selected (set capabilities)
        actions (expand-modules action-modules)
        state-regions-selected (expand-modules state-region-modules)
        ;; Stored state modules use the canonical [::force-authorisation k]
        ;; pair shape consumed by validate-modules and compose-authoritative.
        state-mods (vec (distinct
                         (mapcat (fn [m]
                                   (if (set? m)
                                     (map (fn [k] [::force-authorisation k]) m)
                                     [m]))
                                 state-region-modules)))
        invariants (expand-modules invariant-modules)
        base {:semantic-composition/version semantic-composition-version
              :semantic-composition/profile profile
              :semantic-composition/capabilities (vec selected)
              :semantic-composition/action-modules actions
              :semantic-composition/state-region-modules state-regions-selected
              :semantic-composition/invariant-modules invariants
              :semantic-composition/policy-bindings policy-bindings
              :semantic-composition/resolution-root resolution-root}
        root (str "sha256:"
                  (hc/domain-hash composition-domain-tag
                                  (hc/project-canonical-safe base)))]
    (-> (->SemanticComposition
         profile [] resolution-root nil nil nil nil selected nil
         actions state-mods invariants
         {:force-authorisation (get policy-bindings :force-authorisation)}
         root)
        ;; validate reads these keys directly off the composition value.
        (assoc :root root
               :semantic-composition/root root
               :semantic-composition/version semantic-composition-version))))

(defn build
  "Unchecked constructor for test fixtures and explicitly identified
   local compositions. Accepts a map with :semantic-composition/ keys:
     :schema, :version, :protocol, :profile,
     :capabilities            — seq of [kind id] capability selectors
     :action-modules          — module descriptors (sets expand to members)
     :state-region-modules    — module descriptors (sets expand to members)
     :invariant-modules       — module descriptors (sets expand to members)
     :policy-bindings         — policy binding map
     :resolution-root         — declared resolution provenance root
   NOT for authoritative production use — compose-authoritative is the
   production constructor and derives everything from canonical capability
   resolution. The returned composition satisfies validate."
  [{:semantic-composition/keys [version] :as m}]
  (when-not (= 1 version)
    (throw (ex-info "semantic-composition/version must be 1"
                    {:got version})))
  (build* m))

(defn state-regions-selected?
  "True when the composition selects any force-authorisation live-state
   region. Under validate's module consistency rules this is equivalent to
   selecting :custody-execution-capability."
  [composition]
  (boolean
   (and (map? composition)
        (seq (filter #(= ::force-authorisation (first %))
                     (:state-modules composition))))))

(defn state-region-invalidation
  "Report force-authorisation live-state regions present in `world` that the
   composition does not select. Returns a vector of violations, each carrying
   the offending region under :state-key. A nil or non-selecting composition
   reports every present region as a violation — absence selects nothing;
   callers that require a composition enforce that separately."
  [composition world]
  (let [present (filter #(contains? world %)
                        force-authorisation-state-regions)]
    (if (or (empty? present) (state-regions-selected? composition))
      []
      (mapv (fn [k]
              {:violation/id :composition/error-force-auth-state-region-not-selected
               :state-key k})
            present))))

;; ── legacy unchecked constructor (test-only) ──────────────────────────

(defn ^:private unchecked-compose
  "Low-level unchecked constructor for test fixtures that need to inject
   arbitrary resolved facts. NOT for authoritative use."
  [profile selected packages capabilities dependencies
   action-modules state-modules invariant-modules policy-bindings]
  (let [provider-roots (into #{} (mapcat (fn [[_ entry]]
                                           (map :package-root (:providers entry)))
                                         capabilities))
        base {:semantic-composition/version semantic-composition-version
              :semantic-composition/profile profile
              :semantic-composition/selected selected
              :semantic-composition/packages packages
              :semantic-composition/capabilities capabilities
              :semantic-composition/dependencies dependencies
              :semantic-composition/provider-package-roots provider-roots
              :semantic-composition/action-modules action-modules
              :semantic-composition/state-modules state-modules
              :semantic-composition/invariant-modules invariant-modules
              :semantic-composition/policy-bindings policy-bindings}
        root (hc/domain-hash composition-domain-tag (hc/project-canonical-safe base))]
    (-> (->SemanticComposition
         profile
         []
         nil
         packages
         capabilities
         dependencies
         nil
         (set selected)
         provider-roots
         action-modules
         state-modules
         invariant-modules
         policy-bindings
         nil)
        (assoc :root root))))
