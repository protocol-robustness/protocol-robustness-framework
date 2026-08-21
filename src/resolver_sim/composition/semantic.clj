(ns resolver-sim.composition.semantic
  "Closed semantic-composition.v1 artifacts.

   A semantic composition selects operational meaning from a previously frozen
   extension resolution. It deliberately commits symbolic module descriptors,
   policy roots, and capability identities—not Vars or functions. Package and
   capability descriptor details are authenticated transitively by the
   resolution root.

   Phase 2A: the authoritative constructor (`build-authoritative`) derives
   every composition field from a canonical extension resolution snapshot.
   A manual `build-unchecked` constructor remains for tests/fixtures only."
  (:require [clojure.set :as set]
            [resolver-sim.hash.canonical :as canonical]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.extensions.resolution :as resolution]
            [resolver-sim.extensions.manifest :as em]
            [resolver-sim.run.force-authorisation-policy :as fa-policy]))

(def schema-version "semantic-composition.v1")
(def domain "SEMANTIC_COMPOSITION_V1")

(def projection-fields
  [:semantic-composition/schema
   :semantic-composition/version
   :semantic-composition/protocol
   :semantic-composition/profile
   :semantic-composition/packages
   :semantic-composition/capabilities
   :semantic-composition/resolution-root
   :semantic-composition/resolution
   :semantic-composition/action-modules
   :semantic-composition/state-region-modules
   :semantic-composition/invariant-modules
   :semantic-composition/policy-bindings])

(defn- canonical-value [v]
  (cond
    (set? v) (mapv canonical-value (sort v))
    (map? v) (into {} (map (fn [[k value]] [k (canonical-value value)])) v)
    (vector? v) (mapv canonical-value v)
    (seq? v) (mapv canonical-value v)
    :else v))

(defn projection [composition]
  (canonical-value (select-keys composition projection-fields)))

(defn root [composition]
  (hash-ref/sha256-ref (canonical/domain-hash domain (projection composition))))

(defn module
  "Construct a stable, symbolic operational module descriptor."
  [id version actions regions invariant-ids]
  {:module/id id :module/version version
   :module/actions (vec (sort actions))
   :module/state-regions (vec (sort regions))
   :module/invariant-ids (vec (sort invariant-ids))})

;; ── force-authorisation module descriptors ──────────────────────────────────

(def force-authorisation-actions
  #{"grant-force-authorisation" "grant-force-authorization"
    "grant-consensus-force-authorisation" "grant-related-claims-force-authorisation"
    "revoke-force-authorisation" "execute-force-authorised-action"
    "execute-force-authorized-action"})

;; COMPLETE live-state set: every force-auth-owned world-state key required by
;; full custody-execution semantics. :next-force-authorisation-id is the
;; monotonically increasing allocation counter for auth records; it is live
;; state (written under grant), not a derived/diagnostic field.
(def force-authorisation-live-state-regions
  #{:force-authorisations :force-authorisations/consumed
    :force-authorisations/consumption-records
    :next-force-authorisation-id})

(def force-authorisation-invariants
  #{:force-authorisations-lifecycle-consistent
    :force-authorisations-governance-origin
    :force-authorisations-issuance-assurance
    :force-authorisations-scope-consistent
    :force-authorisations-consumption-consistent})

(def force-authorisation-action-module
  (module :sew.module/force-authorisation-actions 1 force-authorisation-actions #{} #{}))
(def force-authorisation-state-module
  (module :sew.module/force-authorisation-state 1 #{} force-authorisation-live-state-regions #{}))
(def force-authorisation-invariant-module
  (module :sew.module/force-authorisation-invariants 1 #{} #{} force-authorisation-invariants))

;; Backwards-compatible alias (the descriptor now carries the complete
;; :next-force-authorisation-id live state).
(def force-authorisation-state-regions force-authorisation-live-state-regions)

;; ── capability → module derivation rules ────────────────────────────────────
;;
;; Full custody execution (:sew/force-authorisation /
;; :force-authorisation/custody-execution-v1) transitively selects the canonical
;; force-authorisation action module, state-region module, and invariant module.
;; Scope-verification and governed-permit alone must NOT imply live Sew modules.

(def custody-execution-capability
  [:sew/force-authorisation :force-authorisation/custody-execution-v1])

(defn custody-execution-capability?
  "True when the given capability key denotes full force-authorisation custody
   execution (which activates live Sew modules)."
  [capability-key]
  (= custody-execution-capability capability-key))

(def capability->derived-modules
  "Maps a resolved capability key to the set of operational modules it authorises.
   Only custody-execution activates live Sew modules."
  {custody-execution-capability
   #{force-authorisation-action-module
     force-authorisation-state-module
     force-authorisation-invariant-module}})

(defn derive-modules
  "Derive the canonical set of active modules from resolved capability keys.
   Returns a map {:action-modules [...] :state-region-modules [...]
   :invariant-modules [...]}."
  [capability-keys]
  (let [active (reduce (fn [acc k]
                         (set/union acc (get capability->derived-modules k #{})))
                       #{}
                       capability-keys)]
    {:action-modules (sort-by :module/id active)
     :state-region-modules (sort-by :module/id active)
     :invariant-modules (sort-by :module/id active)}))

(defn active-action-modules
  "Return the set of action-module ids in the composition."
  [composition]
  (into #{} (map :module/id) (:semantic-composition/action-modules composition)))

(defn active-state-modules
  "Return the set of state-region-module ids in the composition."
  [composition]
  (into #{} (map :module/id) (:semantic-composition/state-region-modules composition)))

(defn active-invariant-modules
  "Return the set of invariant-module ids in the composition."
  [composition]
  (into #{} (map :module/id) (:semantic-composition/invariant-modules composition)))

(defn selected-capability?
  "True when the given capability key is present in the composition's
   selected capabilities vector."
  [composition capability-key]
  (some #(= % capability-key) (:semantic-composition/capabilities composition)))

(defn active-regions
  "Return the set of active state-region keys for the composition.
   These are the :module/state-region values from active state-region modules."
  [composition]
  (into #{}
        (comp (mapcat :module/state-regions))
        (:semantic-composition/state-region-modules composition)))

(defn active-actions
  "Return the set of active action strings for the composition."
  [composition]
  (into #{}
        (comp (mapcat :module/actions))
        (:semantic-composition/action-modules composition)))

(defn allows-action?
  "True when the composition's active action set includes the given action string."
  [composition action]
  (contains? (active-actions composition) action))

(defn active-invariants
  "Return the set of active invariant ids for the composition."
  [composition]
  (into #{}
        (comp (mapcat :module/invariant-ids))
        (:semantic-composition/invariant-modules composition)))

;; ── profile derivation ──────────────────────────────────────────────────────

(def production-plain-profile :production-plain)
(def production-governed-profile :production-governed)

(defn- capability-profile
  "Extract the :capability/profile from a capability projection."
  [cap-proj]
  (:capability/profile cap-proj))

(defn derive-profile
  "Derive the semantic composition profile from the resolved capabilities.
   - All capabilities with production-governed profile → :production-governed
   - All capabilities with nil profile → :production-plain
   - Mixed profiles → throws (fail closed)"
  [resolution]
  (let [caps (:extensions/capabilities resolution)
        profiles (into (sorted-set) (keep capability-profile) (vals caps))
        _ (when (and (contains? profiles :production-governed)
                     (pos? (count profiles)))
            (when (> (count profiles) 1)
              (throw (ex-info "mixed capability profiles in resolution"
                              {:profiles profiles}))))]
    (cond
      (contains? profiles :production-governed) production-governed-profile
      :else production-plain-profile)))

;; ── package derivation ──────────────────────────────────────────────────────

(defn derive-packages
  "Derive the package set from the resolution snapshot.
   Returns a vector of {:extension/id, :extension/package-root} sorted canonically."
  [resolution]
  (let [packages (:extensions/packages resolution)]
    (vec (sort-by :extension/id
                  (fn [p] (str (:extension/id p)))
                  (mapv (fn [[_ p]]
                          {:extension/id (:package/id p)
                           :extension/package-root (:package-root p)
                           :extension/version (:package/version p)
                           :sealed (:sealed p)})
                        packages)))))

(defn derive-capabilities
  "Derive the capability key vector from the resolution snapshot, sorted
   canonically."
  [resolution]
  (vec (sort (map vec (keys (:extensions/capabilities resolution))))))

;; ── policy binding derivation ───────────────────────────────────────────────

(defn default-force-authorisation-policy
  "The canonical default force-authorisation policy artifact, computed from
   the canonical three-member standard. Used when no explicit policy is supplied
   but custody-execution is active."
  []
  @#'fa-policy/default-research-policy)

(defn policy-root
  "Compute the canonical hash root of a force-authorisation policy artifact."
  [policy]
  (let [validate (resolve 'fa-policy/validate)
        build (resolve 'fa-policy/build)]
    (when-not (and validate build)
      (throw (ex-info "force-authorisation policy namespace is unavailable; the physical force-authorisation extension must be on the classpath"
                      {:error :semantic-composition/policy-namespace-unavailable})))
    (when-not (:valid? (try (validate policy) (catch Exception _ {:valid? false}))
                       #_:clj-kondo/ignore)
      (throw (ex-info "invalid force-authorisation policy" {:policy policy})))
    (let [policy-hash-f (resolve 'fa-policy/policy-hash)]
      (policy-hash-f policy))))

(defn canonical-policy-conforming?
  "True when a force-authorisation policy conforms to the canonical three-member
   standard (3 members, 2-of-3 threshold). Local-governance-only profiles
   (e.g. threshold 1) are rejected."
  [policy]
  (let [member-count (or (get policy "member_count") (get policy :member-count))
        threshold (or (get policy "threshold") (get policy :threshold))]
    (and (= member-count 3) (= threshold 2))))

(defn derive-policy-binding
  "Derive the force-authorisation policy binding from the active capabilities
   and an optional policy artifact.

   When custody-execution is active:
   - A policy artifact must be supplied and conformed to the canonical
     three-member standard (no local-governance-only profiles).
   - The policy root is the canonical self-committing hash.
   - Issuance assurance is :governed-research-authority.

   When custody-execution is not active:
   - No force-authorisation policy binding is produced."
  [capability-keys policy]
  (let [custody-active? (some custody-execution-capability? capability-keys)]
    (if custody-active?
      (let [resolved-policy (or policy (default-force-authorisation-policy))]
        (when-not (canonical-policy-conforming? resolved-policy)
          (throw (ex-info "force-authorisation policy does not conform to canonical three-member standard"
                          {:error :semantic-composition/policy-non-canonical
                           :policy resolved-policy})))
        {:force-authorisation
         {:policy/root (policy-root resolved-policy)
          :issuance-assurance :governed-research-authority}})
      {})))

;; ── validation ──────────────────────────────────────────────────────────────

(defn validate-module-consistency
  "Validate that explicitly supplied modules match the canonical derived set
   from resolved capabilities. Caller-supplied modules that diverge from the
   canonical derivation are rejected."
  [composition]
  (let [caps (set (:semantic-composition/capabilities composition []))
        derived (derive-modules caps)
        supplied {:action-modules (set (:semantic-composition/action-modules composition []))
                  :state-region-modules (set (:semantic-composition/state-region-modules composition []))
                  :invariant-modules (set (:semantic-composition/invariant-modules composition []))}
        violations (into []
                         (concat
                          (when-not (= (set (map :module/id (derived :action-modules)))
                                       (set (map :module/id (:action-modules supplied))))
                            [{:violation/id :semantic-composition/action-module-mismatch
                              :details {:supplied (vec (sort (map :module/id (:action-modules supplied))))
                                        :derived (vec (sort (map :module/id (derived :action-modules))))}}])
                          (when-not (= (set (map :module/id (derived :state-region-modules)))
                                       (set (map :module/id (:state-region-modules supplied))))
                            [{:violation/id :semantic-composition/state-module-mismatch
                              :details {:supplied (vec (sort (map :module/id (:state-region-modules supplied))))
                                        :derived (vec (sort (map :module/id (derived :state-region-modules))))}}])
                          (when-not (= (set (map :module/id (derived :invariant-modules)))
                                       (set (map :module/id (:invariant-modules supplied))))
                            [{:violation/id :semantic-composition/invariant-module-mismatch
                              :details {:supplied (vec (sort (map :module/id (:invariant-modules supplied))))
                                        :derived (vec (sort (map :module/id (derived :invariant-modules))))}}])))]
    {:valid? (empty? violations)
     :violations violations}))

(defn validate [composition]
  (let [unknown (seq (remove (conj (set projection-fields) :semantic-composition/root) (keys composition)))
        required [:semantic-composition/schema :semantic-composition/version
                  :semantic-composition/protocol :semantic-composition/profile
                  :semantic-composition/resolution-root]
        missing (seq (remove #(contains? composition %) required))
        cap-keys (set (:semantic-composition/capabilities composition []))]
    {:valid? (and (nil? unknown) (nil? missing)
                  (= schema-version (:semantic-composition/schema composition))
                  (= 1 (:semantic-composition/version composition))
                  (= "sew-v1" (:semantic-composition/protocol composition))
                  (string? (:semantic-composition/resolution-root composition))
                  (every? #(and (vector? %) (= 2 (count %))) cap-keys)
                  (or (nil? (:semantic-composition/root composition))
                      (= (:semantic-composition/root composition) (root composition))))
     :violations (vec (concat
                       (when unknown [{:violation/id :semantic-composition/unknown-field :details {:fields (vec unknown)}}])
                       (when missing [{:violation/id :semantic-composition/missing-field :details {:fields (vec missing)}}])
                       (when (not= schema-version (:semantic-composition/schema composition))
                         [{:violation/id :semantic-composition/invalid-schema :details {}}])
                       (when (not= "sew-v1" (:semantic-composition/protocol composition))
                         [{:violation/id :semantic-composition/invalid-protocol :details {}}])
                       (when (and (:semantic-composition/root composition)
                                  (not= (:semantic-composition/root composition) (root composition)))
                         [{:violation/id :semantic-composition/root-mismatch :details {}}])))}))

;; ── authoritative constructor ───────────────────────────────────────────────

(defn- build-unchecked
  "Construct a semantic-composition.v1 from a caller-supplied map.

   WARNING: This is the unchecked/manual constructor retained for tests,
   fixtures, and backwards-compatible callers. It does NOT derive or
   validate the resolution snapshot, modules, or policy against canonical
   capability resolution. Use `build-authoritative` for production paths.

   Accepts an optional :semantic-composition/resolution snapshot; when absent
   the composition is still structurally valid but is NOT authoritative."
  [composition]
  (let [composition (assoc composition :semantic-composition/schema schema-version
                           :semantic-composition/version 1)]
    (when-not (:valid? (validate composition))
      (throw (ex-info "invalid semantic composition" (validate composition))))
    (assoc composition :semantic-composition/root (root composition))))

;; Backwards-compatible alias. Existing callers use `semantic/build`.
(def ^:private build build-unchecked)

(defn validate-authoritative
  "Validate a semantic composition for authoritative construction.
   Checks structural validity, module derivation consistency, and profile
   constraints."
  [composition]
  (let [base-validation (validate composition)
        module-validation (validate-module-consistency composition)
        all-violations (into []
                             (concat (:violations base-validation)
                                     (:violations module-validation)))]
    {:valid? (and (:valid? base-validation)
                  (:valid? module-validation))
     :violations all-violations}))

(defn build-authoritative
  "Production-authoritative constructor for semantic-composition.v1.

   Derives every composition field from a canonical extension resolution —
   no caller-supplied packages, capabilities, resolution roots, modules, or
   policy bindings. The caller supplies only:

     extension-map           — the frozen extension registry
     requested-capabilities  — seq of [capability-kind capability-id] vectors

   opts:
     :schemas                — schema id→root map (required for resolution)
     :runtime-profile        — runtime profile map committed into the snapshot
     :sealed?                — require every transitive provider to be sealed
     :effect-schemas         — effect schema id→root map
     :force-authorisation-policy — optional validated policy artifact;
                                    canonical default used when custody-execution
                                    is active and no policy is supplied

   Fails closed (throws) when:
   - any requested capability has no provider
   - any dependency is unresolved
   - capability version or contract-version mismatch
   - profile mismatch
   - the resolution snapshot is inconsistent
   - modules do not match canonical derivation
   - the profile would permit local-governance-only execution

   Returns a validated composition with :semantic-composition/root computed."
  [extension-map requested-capabilities & [opts]]
  (let [{:keys [schemas runtime-profile sealed? effect-schemas
                force-authorisation-policy]} opts
        opts (cond-> {:runtime-profile runtime-profile
                      :sealed? sealed?
                      :schemas (or schemas {})
                      :effect-schemas (or effect-schemas {})}
               (some? runtime-profile) (assoc :runtime-profile runtime-profile))
        resolution-result (resolution/resolve-requested extension-map
                                                        requested-capabilities
                                                        opts)]
    (when-not (:valid? resolution-result)
      (throw (ex-info "force-closed: resolution failed"
                      {:error :semantic-composition/resolution-failed
                       :requested requested-capabilities
                       :violations (:violations resolution-result)})))
    (let [resolution (:resolution resolution-result)
          packages (derive-packages resolution)
          capabilities (derive-capabilities resolution)
          profile (derive-profile resolution)
          modules (derive-modules capabilities)
          policy-bindings (derive-policy-binding capabilities force-authorisation-policy)
          composition (-> {:semantic-composition/schema schema-version
                           :semantic-composition/version 1
                           :semantic-composition/protocol "sew-v1"
                           :semantic-composition/profile profile
                           :semantic-composition/packages packages
                           :semantic-composition/capabilities capabilities
                           :semantic-composition/resolution-root (:extensions/resolution-root resolution)
                           :semantic-composition/resolution resolution
                           :semantic-composition/action-modules (:action-modules modules)
                           :semantic-composition/state-region-modules (:state-region-modules modules)
                           :semantic-composition/invariant-modules (:invariant-modules modules)
                           :semantic-composition/policy-bindings policy-bindings})]
      (when-not (:valid? (validate-authoritative composition))
        (throw (ex-info "invalid authoritative semantic composition"
                        (validate-authoritative composition))))
      (assoc composition :semantic-composition/root (root composition)))))

;; Backwards-compatible public constructor (unchecked/manual)
(def build build-unchecked)
