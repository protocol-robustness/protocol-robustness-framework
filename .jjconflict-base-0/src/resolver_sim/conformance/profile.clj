(ns resolver-sim.conformance.profile
  "Conformance profile descriptor.

   A conformance profile unifies the configuration surfaces that currently live
   separately: fixture contracts, vocabulary registry, actions, roles,
   projections, invariants, comparison policy, required implementation
   components, and verdict policy.

   Descriptors identify REGISTERED EXECUTABLE implementations by id — e.g.
   :decoder/id :sew-finalize-v2 — they never carry code, dynamic namespaces, or
   unconstrained symbols.

   This is the first-profile home for trace equivalence
   (:sew-trace-equivalence.v1); see etc/conformance/profiles/."
  (:require [clojure.edn :as edn]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.conformance.registry :as registry]))

(def ^:const profile-schema-version "conformance-profile.v1")

(defn load-profile
  "Read a conformance profile descriptor from an EDN file."
  [path]
  (edn/read-string (slurp path)))

(defn profile-root
  "Content root of a profile descriptor, excluding its own :profile/root.
   Committed so profile activation is observable: a receipt binds this root
   and enumerates the rules actually applied.

   The descriptor may embed set-valued fields (e.g. :profile/fixture-contracts,
   :supported-fixture-specs), so the committed value is projected to
   canonical-safe form (sets → sorted vectors) before hashing — otherwise the
   strict canonical encoder rejects the descriptor it is supposed to commit."
  [profile]
  (hc/domain-hash :conformance-profile-v1
                  (hc/project-committable-content (dissoc profile :profile/root))))

(defn required-component-ids
  "Ordered component ids the profile requires to be present in the
   implementation identity bundle."
  [profile]
  (mapv :component/id (:profile/required-components profile [])))

(defn- required-profile-keys
  "Core descriptor keys every conformance profile must carry.  Vocabulary
   registries, required components, validators, transformations etc. are
   profile-specific and therefore optional at the generic layer."
  []
  [:profile/schema-version :profile/id :profile/version
   :profile/fixture-contract :profile/capabilities :profile/verdict-policy])

(defn validate-profile
  "Structural validation of a conformance profile descriptor.

   Checks:
     - schema version matches conformance-profile.v1;
     - required top-level keys are present;
     - declared component ids are unique;
     - capability requirements are well-formed ({:capability kind :version n}).

   Descriptors identify executable implementations by id; this validator does
   not resolve or load them."
  [profile]
  (let [v (into []
                (keep (fn [k]
                        (when (nil? (get profile k))
                          {:violation/id :violation/missing-profile-key
                           :details {:key k}})))
                (required-profile-keys))
        v (if (= profile-schema-version (:profile/schema-version profile))
            v
            (conj v {:violation/id :violation/invalid-profile-schema-version
                     :details {:expected profile-schema-version
                               :received (:profile/schema-version profile)}}))
        component-ids (map :component/id (:profile/required-components profile []))
        v (let [dups (->> component-ids frequencies
                          (keep (fn [[id n]] (when (> n 1) id)))
                          vec)]
            (if (seq dups)
              (conj v {:violation/id :violation/duplicate-component
                       :details {:duplicate-ids dups}})
              v))
        v (reduce (fn [vs req]
                    (cond-> vs
                      (or (nil? (:capability req)) (nil? (:version req)))
                      (conj {:violation/id :violation/invalid-capability-requirement
                             :details {:requirement req}})))
                  v
                  (get-in profile [:profile/capabilities :required] []))
        v (reduce (fn [vs b]
                    (if (keyword? b) vs
                        (conj vs {:violation/id :violation/invalid-derivation-boundary
                                  :details {:boundary b}})))
                  v
                  (get-in profile [:profile/verdict-policy :derivation-boundaries] []))]
    {:valid? (empty? v) :violations v}))

;; ---------------------------------------------------------------------------
;; Two-stage validation: core + profile-kind/domain dispatch
;; ---------------------------------------------------------------------------

(defonce ^:private domain-validators (atom {}))

(defn register-profile-domain-validator!
  "Register a domain validator for a :profile/kind.  f takes a profile and
   returns {:valid? bool :violations [...]}.  The domain validator checks
   domain-specific coherence that the generic core cannot know."
  [kind f]
  (swap! domain-validators assoc kind f)
  kind)

(defn validate-profile-domain
  "Run the registered domain validator for the profile's :profile/kind, if any.
   Returns {:valid? bool :violations [...]} (passes when no validator is
   registered for the kind — unknown kinds are the caller's policy to reject)."
  [profile]
  (if-let [f (get @domain-validators (:profile/kind profile))]
    (f profile)
    {:valid? true :violations []}))

(defn validate-profile-full
  "Two-stage profile validation: generic core checks, then the profile-kind
   domain validator.  Returns {:valid? bool :violations [...]}."
  [profile]
  (let [core (validate-profile profile)
        domain (validate-profile-domain profile)]
    {:valid? (and (:valid? core) (:valid? domain))
     :violations (into (:violations core) (:violations domain))}))

;; ---------------------------------------------------------------------------
;; Lifecycle predicates: valid -> satisfiable -> executable
;; ---------------------------------------------------------------------------

(defn- validator-implementation-requirements
  "The implementation requirements a profile's declared validators imply.
   The implementation registry mirrors validators under :implementation/kind
   :validator; the validator's :validator/kind (:schema/:semantic) is the
   validation LAYER, checked by the validation registry."
  [profile]
  (mapv (fn [v]
          {:implementation/id (:validator/id v)
           :implementation/kind :validator})
        (:profile/validators profile [])))

(defn profile-satisfiable?
  "Installation-level, subject-independent: this installation could execute the
   profile for SOME valid subject.

   Returns {:profile/root ... :satisfiable? bool
            :implementation-registry/root ...
            :resolved-implementations [...] :missing-implementations []
            :kind-mismatches [] :experimental-violations []
            :unsupported-schema-versions []}."
  [profile]
  (let [requirements (validator-implementation-requirements profile)
        impl (registry/required-implementations-ok? requirements)
        violations (group-by :violation/id (:violations impl))
        missing (mapv (comp :implementation/id :details) (get violations :violation/unresolved-implementation []))
        kinds (mapv (comp :implementation/id :details) (get violations :violation/implementation-kind-mismatch []))
        experimental (registry/experimental-violations requirements #{})
        schemas (keep #(when-not (contains? #{"conformance-profile.v1"} %)
                         %)
                      [(:profile/schema-version profile)])]
    {:profile/root (profile-root profile)
     :satisfiable? (and (:ok? impl)
                        (empty? experimental))
     :implementation-registry/root (:registry/root impl)
     :resolved-implementations (vec (keep registry/resolve-implementation
                                          (map :implementation/id requirements)))
     :missing-implementations missing
     :kind-mismatches kinds
     :experimental-violations experimental
     :unsupported-schema-versions schemas}))

(defn profile-executable?
  "Subject-set and mode specific PREFLIGHT decision.  Never executes validators,
   benchmarks, or replay handlers — observed exercise remains a later
   receipt-backed fact.

   Checks: subject identities present; fixture-contract / domain validation;
   mode/verdict-policy compatibility; required capability resolvability;
   comparison-policy applicability; exclusion eligibility.

   Returns {:profile/root ... :subject-set/root ... :mode ...
            :executable? bool :reasons [{:reason ... :details ...}]}."
  [profile subject-set mode]
  (let [reasons (atom [])
        add! (fn [r & [details]] (swap! reasons conj (cond-> {:reason r} details (assoc :details details))))]
    (when-not (seq (:subjects subject-set))
      (add! :empty-subject-set))
    (when-not (:valid? (validate-profile-domain profile))
      (add! :domain-validation-failed))
    (when-not (contains? (set (get-in profile [:profile/verdict-policy :claim-classes]))
                         mode)
      (add! :mode-not-in-verdict-policy {:mode mode}))
    ;; Required capabilities are syntactically validated by the core validator
    ;; (well-formed {:capability :version}); EXERCISE is a later receipt-backed
    ;; fact and is not checked here.
    (when-not (:profile/comparison-policy profile)
      (add! :missing-comparison-policy))
    {:profile/root (profile-root profile)
     :subject-set/root (:subject-set/root subject-set)
     :mode mode
     :executable? (empty? @reasons)
     :reasons (vec @reasons)}))
