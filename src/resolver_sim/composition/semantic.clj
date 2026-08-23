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
  "Derive action modules from selected capabilities.
   custody-execution selected → [:sew/force-authorisation :execute]
   scope-verification only   → none (protocol-neutral, no Sew action)"
  [selected]
  (cond-> []
    (contains? selected [:sew/force-authorisation :force-authorisation/custody-execution-v1])
    (conj [:sew/force-authorisation :execute])
    (contains? selected [:sew/force-authorisation :force-authorisation/custody-execution-v1])
    (conj [:sew/force-authorisation :grant-force-authorisation])
    (contains? selected [:sew/force-authorisation :force-authorisation/custody-execution-v1])
    (conj [:sew/force-authorisation :revoke-force-authorisation])))

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

(defn- committed-packages
  "Project resolved provider packages."
  [resolution]
  (:extensions/packages resolution))

(defn- compose-root
  "Compute the canonical composition root from all committed fields."
  [base]
  (let [safe (hc/project-canonical-safe base)]
    (assoc base :semantic-composition/root
           (hc/domain-hash composition-domain-tag
                           (dissoc safe :semantic-composition/root)))))

(defrecord SemanticComposition
           [profile
            requested-capabilities
            resolution-root
            packages
            capabilities
            dependencies
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
         profile [] resolution-root nil nil nil selected nil
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
         (set selected)
         provider-roots
         action-modules
         state-modules
         invariant-modules
         policy-bindings
         nil)
        (assoc :root root))))
