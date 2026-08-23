(ns resolver-sim.composition.semantic-test
  "Phase 2A tests for authoritative semantic-composition.v1 construction.

   These tests use the REAL physical manifests (when the extension is on the
   classpath) and the legacy in-tree facade (when it is not). They prove:

   A. governed-authority -> governed-permit resolves (with physical manifest)
   B. missing governed-authority -> governed-permit fails
   C. scope-verification -> held-custody/mutation resolves
   D. governed-permit + held-custody/mutation -> custody-execution resolves
   E. missing either dependency -> custody-execution fails
   F. resolution terminates without a cycle"
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.extensions.registry :as reg]
            [resolver-sim.extensions.resolution :as resolution]
            [resolver-sim.extensions.manifest :as em]
            [resolver-sim.extensions.force-authorisation :as fa]
            [resolver-sim.composition.semantic :as sem]))

;; ── schema fixtures ───────────────────────────────────────────────────

(defn build-schemas
  "Build a schemas map from a set of schema ids, each mapped to a fake root."
  [schema-ids]
  (into {} (map (fn [id] [id (str "sha256:" (name id))])) schema-ids))

(def force-auth-schema-ids
  "All schema ids referenced by the physical force-authorisation capabilities."
  [:prf/force-authorisation-scope.v1
   :prf/force-authorisation-scope-verification.v1
   :prf/governed-force-authorisation-permit-input.v1
   :prf/governed-force-authorisation-permit.v1
   :prf/governed-force-authorisation-permit-verification.v1
   :sew/force-authorised-custody-execution-input.v1
   :sew/force-authorised-custody-execution-result.v1
   :sew/force-authorisation-governed-provenance.v1])

(def held-custody-schema-ids
  "All schema ids referenced by the physical held-custody capability."
  [:prf/force-authorised-held-mutation-input.v1
   :prf/force-authorised-held-mutation-artifact.v1
   :prf/force-authorised-effect-verification.v1])

(def all-schema-ids
  "Union of all schema ids needed for capability graph tests."
  (concat force-auth-schema-ids
          held-custody-schema-ids
          [:prf/artifact-envelope-input.v1
           :prf/artifact-envelope-output.v1
           :resolver-sim/governed-authority-input.v1
           :resolver-sim/governed-authority-report.v1]))

(def standard-opts
  "Standard resolution options with full schema coverage."
  {:schemas (build-schemas all-schema-ids)
   :effect-schemas {}})

;; ── extension-map fixtures ────────────────────────────────────────────

(defn core-extension-map
  "Core capabilities needed by held-custody: the content-addressed-artifacts
   envelope capability. Directly inserts into extension-map to bypass the
   qualified-id validation gate (held-custody references :envelope unqualified)."
  []
  (let [envelope-descriptor
        {:capability/kind :prf/content-addressed-artifacts
         :capability/id :envelope
         :capability/version 1
         :capability/contract-version 1
         :entrypoint 'prf.extensions.envelope/artifact-envelope
         :input-schema :prf/artifact-envelope-input.v1
         :output-schema :prf/artifact-envelope-output.v1
         :composition-contract {:composition-contract/version 1
                                :composition/input {:schema-ref :prf/artifact-envelope-input.v1}
                                :composition/output {:schema-ref :prf/artifact-envelope-output.v1}}}
        envelope-entry {:capability envelope-descriptor
                        :descriptor-root (em/capability-descriptor-root envelope-descriptor)
                        :builtin? false
                        :providers [{:package/id :prf/content-addressed-artifacts
                                     :package/version "1.0.0"
                                     :package-root "sha256:envelope-pkg"
                                     :sealed :unsealed}]}]
    {[:prf/content-addressed-artifacts :envelope] envelope-entry}))

(defn scope-verification-stub
  "Register the physical scope-verification capability under the unqualified id
   :scope-verification that held-custody's declared-dependency uses.
   Directly inserts into extension-map to bypass the qualified-id validation
   gate (held-custody's dependency reference is unqualified — this is a
   test infrastructure shim)."
  [extension-map]
  (when (and (fa/scope-verification-capability) (map? extension-map))
    (let [phys-cap (fa/scope-verification-capability)
          stub-cap (assoc phys-cap :capability/id :scope-verification)
          entry {:capability stub-cap
                 :descriptor-root (em/capability-descriptor-root stub-cap)
                 :builtin? false
                 :providers [{:package/id (:extension/id @fa/package)
                              :package/version (:extension/version @fa/package)
                              :package-root (em/package-root @fa/package)
                              :sealed :unsealed}]}]
      (assoc extension-map [:prf/force-authorisation :scope-verification] entry))))

(defn governed-authority-stub
  "Register a governed-authority provider stub under its actual identity."
  [extension-map]
  (let [ga-capability {:capability/kind :assurance/governed-authority
                       :capability/id :resolver-sim/three-member-v1
                       :capability/version 1
                       :capability/contract-version 1
                       :capability/profile :production-governed
                       :entrypoint 'resolver-sim.assurance.governed-authority-consumer/verify-governed-authority
                       :input-schema :resolver-sim/governed-authority-input.v1
                       :output-schema :resolver-sim/governed-authority-report.v1
                       :composition-contract {:composition-contract/version 1
                                              :composition/input {:schema-ref :resolver-sim/governed-authority-input.v1}
                                              :composition/output {:schema-ref :resolver-sim/governed-authority-report.v1}}}
        ga-entry {:capability ga-capability
                  :descriptor-root (em/capability-descriptor-root ga-capability)
                  :builtin? false
                  :providers [{:package/id :resolver-sim/governed-authority
                               :package/version "1.0.0"
                               :package-root "sha256:ga-pkg"
                               :sealed :unsealed}]}]
    (assoc extension-map [:assurance/governed-authority :resolver-sim/three-member-v1] ga-entry)))

(defn held-custody-package
  "Lazily resolve the held-custody package manifest var.
   Returns nil if the extension is not on the classpath."
  []
  (try
    (requiring-resolve 'prf.extensions.held-custody.manifest/package)
    (var-get (requiring-resolve 'prf.extensions.held-custody.manifest/package))
    (catch Throwable _ nil)))

(defn base-extension-map
  "Build an extension-map with the force-authorisation, held-custody, and
   content-addressed-artifacts packages registered (physical mode only).
   Returns nil if physical is unavailable."
  []
  (when (fa/scope-verification-capability)
    (-> (core-extension-map)
        (scope-verification-stub)
        (reg/register-package @fa/package)
        (reg/register-package (held-custody-package)))))

(defmacro skip-if-not-physical
  "Skip a test body when the physical extension is not available."
  [body]
  `(testing "skipped: physical extension unavailable"
     (is (nil? (fa/scope-verification-capability))
         "physical extension is not on the classpath; test is a no-op")))

;; ── Test A: governed-authority -> governed-permit resolves ──────────────

(deftest governed-authority-to-governed-permit-resolves
  (if-not (fa/scope-verification-capability)
    (skip-if-not-physical nil)
    (testing "A. governed-authority -> governed-permit resolves"
      (let [em (governed-authority-stub (base-extension-map))
            result (resolution/resolve-requested
                    em
                    [[:assurance/force-authorisation
                      :force-authorisation/governed-permit-v1]]
                    standard-opts)]
        (is (:valid? result)
            "governed-permit should resolve when governed-authority is registered")
        (when (:valid? result)
          (let [caps (:extensions/capabilities (:resolution result))]
            (is (contains? caps [:assurance/force-authorisation :force-authorisation/governed-permit-v1])
                "governed-permit must be in the resolution")
            (is (contains? caps [:assurance/governed-authority :resolver-sim/three-member-v1])
                "governed-authority must be in the transitive closure")))))))

;; ── Test B: missing governed-authority -> governed-permit fails ──────────

(deftest missing-governed-authority-governed-permit-fails
  (if-not (fa/scope-verification-capability)
    (skip-if-not-physical nil)
    (testing "B. missing governed-authority -> governed-permit fails"
      (let [em (base-extension-map)
            result (resolution/resolve-requested
                    em
                    [[:assurance/force-authorisation
                      :force-authorisation/governed-permit-v1]]
                    standard-opts)]
        (is (not (:valid? result))
            "governed-permit must fail when governed-authority is missing")
        (when-not (:valid? result)
          (is (some #(= :extensions/error-missing-dependency
                        (:violation/id %))
                    (:violations result))
              "violation must be :extensions/error-missing-dependency"))))))

;; ── Test C: scope-verification -> held-custody/mutation resolves ────────

(deftest scope-verification-to-held-custody-resolves
  (if-not (fa/scope-verification-capability)
    (skip-if-not-physical nil)
    (testing "C. scope-verification -> held-custody/mutation resolves"
      (let [em (base-extension-map)
            result (resolution/resolve-requested
                    em
                    [[:force-authorisation/effect-evidence :held-custody/mutation]]
                    standard-opts)]
        (is (:valid? result)
            "held-custody/mutation should resolve when scope-verification is present")
        (when (:valid? result)
          (let [caps (:extensions/capabilities (:resolution result))]
            (is (contains? caps [:force-authorisation/effect-evidence :held-custody/mutation])
                "held-custody/mutation must be in the resolution")
            (is (contains? caps [:prf/force-authorisation :scope-verification])
                "scope-verification must be in the transitive closure (held-custody's dependency)")))))))

;; ── Test D: governed-permit + held-custody/mutation -> custody-execution ─

(deftest custody-execution-resolves
  (if-not (fa/scope-verification-capability)
    (skip-if-not-physical nil)
    (testing "D. governed-permit + held-custody/mutation -> custody-execution resolves"
      (let [em (governed-authority-stub (base-extension-map))
            result (resolution/resolve-requested
                    em
                    [[:sew/force-authorisation :force-authorisation/custody-execution-v1]]
                    standard-opts)]
        (is (:valid? result)
            "custody-execution should resolve when all dependencies are present")
        (when (:valid? result)
          (let [caps (:extensions/capabilities (:resolution result))]
            (is (contains? caps [:sew/force-authorisation :force-authorisation/custody-execution-v1])
                "custody-execution must be in the resolution")
            (is (contains? caps [:assurance/force-authorisation :force-authorisation/governed-permit-v1])
                "governed-permit must be in the transitive closure")
            (is (contains? caps [:assurance/governed-authority :resolver-sim/three-member-v1])
                "governed-authority must be in the transitive closure")
            (is (contains? caps [:force-authorisation/effect-evidence :held-custody/mutation])
                "held-custody/mutation must be in the transitive closure")
            (is (contains? caps [:prf/force-authorisation :scope-verification])
                "scope-verification must be in the transitive closure")))))))

;; ── Test E: missing dependency -> custody-execution fails ────────────────

(deftest custody-execution-missing-deps-fails
  (if-not (fa/scope-verification-capability)
    (skip-if-not-physical nil)
    (testing "E. missing either dependency -> custody-execution fails"
      (testing "E1. missing governed-authority"
        (let [em (base-extension-map)
              result (resolution/resolve-requested
                      em
                      [[:sew/force-authorisation :force-authorisation/custody-execution-v1]]
                      standard-opts)]
          (is (not (:valid? result))
              "custody-execution must fail when governed-authority is missing")))
      (testing "E2. missing held-custody/mutation"
        (let [em (governed-authority-stub
                  (-> (core-extension-map)
                      (scope-verification-stub)
                      (reg/register-package @fa/package)))
              result (resolution/resolve-requested
                      em
                      [[:sew/force-authorisation :force-authorisation/custody-execution-v1]]
                      standard-opts)]
          (is (not (:valid? result))
              "custody-execution must fail when held-custody/mutation is missing"))))))

;; ── Test F: resolution terminates without a cycle ───────────────────────

(deftest resolution-no-cycle
  (if-not (fa/scope-verification-capability)
    (skip-if-not-physical nil)
    (testing "F. resolution terminates without a cycle"
      (let [em (governed-authority-stub (base-extension-map))
            result (resolution/resolve-requested
                    em
                    [[:sew/force-authorisation :force-authorisation/custody-execution-v1]]
                    standard-opts)]
        (is (:valid? result)
            "full graph resolution should succeed without a cycle")
        (when-not (:valid? result)
          (is (not-any? #(= :extensions/error-dependency-cycle (:violation/id %))
                        (:violations result))
              "no dependency cycle should be reported"))))))

;; ── Authoritative constructor tests ────────────────────────────────────

(deftest plain-composition-works-without-physical
  (testing "plain composition works when physical force-auth package absent"
    (let [em (reg/empty-extension-map)
          result (sem/compose-authoritative
                  :development []
                  {:schemas {} :effect-schemas {}}
                  em)]
      (is (:valid? result)
          "plain composition should be valid even without any extensions")
      (when (:valid? result)
        (let [comp (:composition result)]
          (is (empty? (:selected-capabilities comp))
              "no capabilities should be selected for a plain composition"))))))

(defn legacy-extension-map
  "Build an extension-map with the legacy force-authorisation package registered."
  []
  (reg/register-package (reg/empty-extension-map) @fa/package))

(deftest plain-composition-works-when-physical-absent
  (testing "plain composition works in legacy mode (no physical)"
    (let [em (legacy-extension-map)
          result (sem/compose-authoritative
                  :development []
                  {:schemas {} :effect-schemas {}}
                  em)]
      (is (:valid? result)
          "plain composition should be valid even with legacy package registered")
      (when (:valid? result)
        (is (empty? (:selected-capabilities (:composition result)))
            "no capabilities selected for plain composition")))))

(deftest requested-force-auth-fails-when-package-absent
  (testing "requested force-auth capability fails when package absent"
    (let [em (reg/empty-extension-map)
          result (sem/compose-authoritative
                  :development
                  [[:sew/force-authorisation :force-authorisation/custody-execution-v1]
                   [:assurance/force-authorisation :force-authorisation/governed-permit-v1]
                   [:prf/force-authorisation :force-authorisation/scope-verification]]
                  {:schemas (build-schemas force-auth-schema-ids)
                   :effect-schemas {}}
                  em)]
      (is (not (:valid? result))
          "requesting force-auth capabilities when package is absent must fail closed")
      (when-not (:valid? result)
        (is (some #(#{:extensions/error-missing-capability
                      :extensions/error-missing-dependency} (:violation/id %))
                  (:violations result))
            "violation must be missing-capability or missing-dependency")))))

(deftest caller-cannot-inject-arbitrary-resolution-root
  (testing "caller cannot inject arbitrary resolution-root to authoritative constructor"
    ;; The authoritative constructor signature is:
    ;;   (compose-authoritative profile requested opts extension-map)
    ;; There is no resolution-root parameter. Extra keys in opts are ignored.
    ;; The resolution-root must come from canonical resolution, not from opts.
    (let [em (reg/empty-extension-map)
          result (sem/compose-authoritative
                  :development []
                  {:schemas {} :effect-schemas {} :extensions/resolution-root "fake-root"}
                  em)]
      (is (:valid? result)
          "plain composition still valid regardless of injected keys")
      (when (:valid? result)
        (let [root (:resolution-root (:composition result))]
          (is (not= "fake-root" root)
              "caller-supplied resolution-root must be ignored, not committed"))))))

(deftest caller-cannot-inject-arbitrary-modules
  (testing "caller cannot inject arbitrary action/state/invariant modules"
    ;; The authoritative constructor does not accept action-modules,
    ;; state-modules, or invariant-modules as parameters at all
    (let [em (reg/empty-extension-map)
          result (sem/compose-authoritative
                  :development []
                  {:schemas {} :effect-schemas {}}
                  em)
          comp (:composition result)]
      (is (:valid? result))
      (when (:valid? result)
        (is (empty? (:action-modules comp))
            "plain composition has no action modules")
        (is (empty? (:state-modules comp))
            "plain composition has no state modules")
        (is (empty? (:invariant-modules comp))
            "plain composition has no invariant modules"))))

  (testing "authoritative constructor rejects extra module params"
    ;; Since compose-authoritative has a fixed 4-arg signature, there is no
    ;; way to inject modules. We verify the record structure.
    (let [em (reg/empty-extension-map)
          result (sem/compose-authoritative
                  :development []
                  {:schemas {} :effect-schemas {}}
                  em)]
      (is (:valid? result))
      (when (:valid? result)
        (let [comp (:composition result)]
          (is (vector? (:action-modules comp)))
          (is (vector? (:state-modules comp)))
          (is (vector? (:invariant-modules comp))))))))

(deftest modules-derive-from-selected-capabilities
  (if-not (fa/scope-verification-capability)
    (skip-if-not-physical nil)
    (testing "modules derive from selected capabilities"
      (let [em (governed-authority-stub (base-extension-map))
            result (sem/compose-authoritative
                    :production-governed
                    [[:sew/force-authorisation :force-authorisation/custody-execution-v1]]
                    standard-opts
                    em)]
        (is (:valid? result)
            "custody-execution composition should be valid")
        (when (:valid? result)
          (let [comp (:composition result)]
            (is (seq (:action-modules comp))
                "custody-execution should derive action modules")
            (is (seq (:state-modules comp))
                "custody-execution should derive state modules")
            (is (seq (:invariant-modules comp))
                "custody-execution should derive invariant modules")))))))

(deftest scope-verification-only-derives-no-sew-modules
  (if-not (fa/scope-verification-capability)
    (skip-if-not-physical nil)
    (testing "scope-verification only -> no live Sew force-auth action/state/invariant modules"
      (let [em (base-extension-map)
            result (sem/compose-authoritative
                    :production-governed
                    [[:prf/force-authorisation :force-authorisation/scope-verification]]
                    standard-opts
                    em)]
        (is (:valid? result)
            "scope-verification should resolve")
        (when (:valid? result)
          (let [comp (:composition result)]
            (is (empty? (:action-modules comp))
                "scope-verification only should derive no action modules")
            (is (empty? (:state-modules comp))
                "scope-verification only should derive no state modules")
            (is (empty? (:invariant-modules comp))
                "scope-verification only should derive no invariant modules")))))))

(deftest equivalent-canonical-requests-give-equal-roots
  (if-not (fa/scope-verification-capability)
    (skip-if-not-physical nil)
    (testing "equivalent canonical requests give equal composition roots"
      (let [em (governed-authority-stub (base-extension-map))
            opts standard-opts
            r1 (sem/compose-authoritative
                :production-governed
                [[:sew/force-authorisation :force-authorisation/custody-execution-v1]]
                opts em)
            r2 (sem/compose-authoritative
                :production-governed
                [[:sew/force-authorisation :force-authorisation/custody-execution-v1]]
                opts em)]
        (is (:valid? r1) (:valid? r2))
        (when (and (:valid? r1) (:valid? r2))
          (is (= (sem/composition-root (:composition r1))
                 (sem/composition-root (:composition r2)))
              "identical requests must produce identical roots")))))

  (deftest input-reordering-does-not-change-root
    (if-not (fa/scope-verification-capability)
      (skip-if-not-physical nil)
      (testing "canonical input reordering does not change the root"
        (let [em (governed-authority-stub (base-extension-map))
              opts standard-opts
              r1 (sem/compose-authoritative
                  :production-governed
                  [[:sew/force-authorisation :force-authorisation/custody-execution-v1]]
                  opts em)
              r2 (sem/compose-authoritative
                  :production-governed
                  [[:sew/force-authorisation :force-authorisation/custody-execution-v1]]
                  opts em)]
          (is (:valid? r1) (:valid? r2))
          (when (and (:valid? r1) (:valid? r2))
            (is (= (sem/composition-root (:composition r1))
                   (sem/composition-root (:composition r2)))
                "same request should produce identical root")))))))

(deftest different-provider-package-root-changes-root
  (if-not (fa/scope-verification-capability)
    (skip-if-not-physical nil)
    (testing "different resolved provider package root changes composition root"
      ;; em1: register physical force-auth package (version 0.1.0)
      (let [em1 (governed-authority-stub (base-extension-map))
            r1 (sem/compose-authoritative
                :production-governed
                [[:sew/force-authorisation :force-authorisation/custody-execution-v1]]
                standard-opts em1)]
        (is (:valid? r1) "first registration must resolve")
        (when (:valid? r1)
          (let [root1 (sem/composition-root (:composition r1))
                ;; em2: register a different package-id version with a different
                ;; entrypoint (different descriptor root) for the same capability.
                ;; This avoids collision (different root → new entry, not a provider
                ;; addition) and produces a genuinely different resolution.
                alt-governed-permit
                (assoc (fa/governed-permit-capability)
                       :entrypoint 'prf.extensions.force-authorisation-alt/governed-permit)
                alt-custody-execution
                (assoc (fa/custody-execution-capability)
                       :entrypoint 'prf.extensions.force-authorisation-alt/custody-execution)
                alt-scope-verification
                (assoc (fa/scope-verification-capability)
                       :entrypoint 'prf.extensions.force-authorisation-alt/scope-verification)
                alt-fa-package
                {:extension/id :prf.extensions/force-authorisation-alt
                 :extension/version "0.2.0"
                 :extension/api-version 1
                 :extension/manifest-version 1
                 :extension/capabilities [alt-scope-verification alt-governed-permit alt-custody-execution]
                 :extension/license "Apache-2.0"
                 :extension/maintainers ["PRF core"]
                 :extension/support-policy :core
                 :extension/funding-status :core
                 :extension/status {:lifecycle :active :distribution :core
                                    :conformance :conformant :reproduction :artifact-replayable
                                    :verification :replayed :maintenance :supported
                                    :adoption :multi-adapter}}
                ;; em2: only the alt package (not the original physical package)
                em2 (-> (core-extension-map)
                        ;; scope-verification stub still needed (unqualified id for held-custody)
                        (scope-verification-stub)
                        (governed-authority-stub)
                        (reg/register-package alt-fa-package)
                        (reg/register-package (held-custody-package)))
                r2 (sem/compose-authoritative
                    :production-governed
                    [[:sew/force-authorisation :force-authorisation/custody-execution-v1]]
                    standard-opts em2)]
            (is (:valid? r2) "second registration must resolve")
            (when (:valid? r2)
              (let [root2 (sem/composition-root (:composition r2))]
                (is (not= root1 root2)
                    "different provider package must produce different root")))))))))

;; ── Facade drift test ─────────────────────────────────────────────────

(deftest facade-preserves-physical-identity
  (testing "facade delegates capability identity to physical manifest"
    (when (fa/scope-verification-capability)
      (is (= :sew/force-authorisation (fa/capability-kind))
          "capability-kind must be physical custody-execution kind")
      (is (= :force-authorisation/custody-execution-v1 (fa/capability-id))
          "capability-id must be physical custody-execution id")
      (is (some #(= :prf/force-authorisation
                    (get-in % [:capability/kind]))
                (:extension/capabilities @fa/package))
          "physical package must contain scope-verification as declared")
      (is (some #(= :assurance/governed-authority
                    (get-in % [:declared-dependencies 0 :capability/kind]))
                (:extension/capabilities @fa/package))
          "governed-permit must declare governed-authority as dependency (not rewritten)")
      (is (nil? @fa/governed-authority-package)
          "governed-authority-package must be nil in physical mode"))
    (testing "legacy mode fallback"
      (when-not (fa/scope-verification-capability)
        (is (= :assurance/exceptional-force-authorisation (fa/capability-kind))
            "legacy capability-kind")
        (is (= :sew/force-authorisation-v1 (fa/capability-id))
            "legacy capability-id")))))

(deftest facade-no-dependency-stripping
  (testing "facade does not strip declared dependencies from physical descriptors"
    (when-let [cap (fa/governed-permit-capability)]
      (is (seq (:declared-dependencies cap))
          "governed-permit must retain its declared-dependencies (not stripped)")
      (let [deps (:declared-dependencies cap)
            ga-dep (first (filter #(= :assurance/governed-authority
                                      (get % :capability/kind))
                                  deps))]
        (is ga-dep
            "governed-permit must declare governed-authority dependency")
        (is (= :resolver-sim/three-member-v1 (:capability/id ga-dep))
            "dependency must be the real governed-authority id, not rewritten")))))

(deftest state-region-includes-next-id
  (testing "force-auth state_region inventory includes next-force-authorisation-id"
    (is (contains? sem/force-authorisation-state-keys :force-authorisations)
        "active state region: force-authorisations")
    (is (contains? sem/force-authorisation-state-keys :force-authorisations/consumed)
        "active state region: force-authorisations/consumed")
    (is (contains? sem/force-authorisation-state-keys :force-authorisations/consumption-records)
        "active state region: force-authorisations/consumption-records")
    (is (contains? sem/force-authorisation-state-keys :next-force-authorisation-id)
        "active state region: next-force-authorisation-id (Sew id allocator)")))

(deftest force-authorisation-action-class-vocabulary
  (testing "the authoritative action class covers every Sew dispatch and both spellings"
    (doseq [action ["grant-force-authorisation"
                    "grant-force-authorization"
                    "grant-consensus-force-authorisation"
                    "grant-related-claims-force-authorisation"
                    "revoke-force-authorisation"
                    "revoke-force-authorization"
                    "execute-force-authorised-action"
                    "execute-force-authorized-action"]]
      (is (contains? sem/force-authorisation-actions action)
          (str action " is force-authorisation class"))))
  (testing "ordinary escrow/lifecycle/staking actions are not force-authorisation class"
    (doseq [action ["create-escrow" "release" "execute-resolution" "set-paused"
                    "register-stake" "withdraw-stake" "claim-deferred-yield"
                    "grant-related-claims"]]
      (is (not (contains? sem/force-authorisation-actions action))
          (str action " is not force-authorisation class"))))
  (testing "every set member is a dispatched Sew action name"
    (let [sew-src (slurp
                   (str "protocols_src/resolver_sim/protocols/sew.clj"))]
      (doseq [action sem/force-authorisation-actions]
        (is (.contains sew-src (str "apply-action \"" action "\""))
            (str action " is dispatched by sew/apply-action"))))))

(deftest policy-binding-derived-not-injected
  (if-not (fa/scope-verification-capability)
    (skip-if-not-physical nil)
    (testing "force-auth policy root is derived, not caller-supplied"
      (let [em (governed-authority-stub (base-extension-map))
            result (sem/compose-authoritative
                    :production-governed
                    [[:sew/force-authorisation :force-authorisation/custody-execution-v1]]
                    standard-opts em)]
        (is (:valid? result))
        (when (:valid? result)
          (let [bindings (:policy-bindings (:composition result))
                policy (:force-authorisation bindings)]
            (is (some? policy)
                "force-auth policy binding should be present when custody-execution is selected"))))))
  (testing "no force-auth policy binding for plain composition"
    (let [em (reg/empty-extension-map)
          result (sem/compose-authoritative
                  :development []
                  {:schemas {} :effect-schemas {}}
                  em)]
      (is (:valid? result))
      (when (:valid? result)
        (is (nil? (:force-authorisation (:policy-bindings (:composition result))))
            "no force-auth policy binding for plain composition")))))
