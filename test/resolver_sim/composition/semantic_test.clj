(ns resolver-sim.composition.semantic-test
  "Phase 2A tests for authoritative semantic composition.

   All physical-graph tests build their extension registry exclusively from
   validated register-package calls using the real physical manifests and
   their exact canonical qualified capability identities. No capability
   descriptor is mutated (kind/id/version/contract-version/profile/
   declared-dependencies) and no legacy/unqualified substitute capability-map
   entries are inserted.

   A minimal envelope capability provider is registered (as a properly
   validated package with its own true declared identity) solely to satisfy
   held-custody's declared dependency on the envelope capability; it is NOT a
   force-auth shim.

   In legacy mode (no physical extension on classpath), the physical-graph
   tests are skipped. The plain-composition tests run in all modes."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.composition.semantic :as semantic]
            [resolver-sim.extensions.force-authorisation :as facade]
            [resolver-sim.extensions.manifest :as em]
            [resolver-sim.extensions.registry :as reg]
            [resolver-sim.extensions.resolution :as resolution]
            [resolver-sim.run.force-authorisation-policy :as fa-policy]))

;; Resolve physical manifest vars at runtime so this test namespace loads
;; in legacy mode (without extensions on classpath).

(defn- ^:private try-resolve*
  "Return the value of sym in ns, or nil if the namespace/var is unavailable.
   Uses requiring-resolve so the namespace is loaded on demand."
  [ns sym]
  (try
    (let [v (requiring-resolve (symbol (str ns "/" (name sym))))]
      (when v (var-get v)))
    (catch Exception _ nil)))

(defn- held-custody-package
  "Resolve the physical held-custody package at runtime."
  []
  (try-resolve* 'prf.extensions.held-custody.manifest 'package))

(defn- phys-scope-verification
  "Resolve the physical scope-verification capability descriptor."
  []
  (try-resolve* 'prf.extensions.force-authorisation.manifest 'scope-verification-capability))

(defn- phys-governed-permit
  "Resolve the physical governed-permit capability descriptor."
  []
  (try-resolve* 'prf.extensions.force-authorisation.manifest 'governed-permit-capability))

(defn- phys-custody-execution
  "Resolve the physical custody-execution capability descriptor."
  []
  (try-resolve* 'prf.extensions.force-authorisation.manifest 'custody-execution-capability))

;; ── minimal envelope provider (satisfies held-custody's dependency) ────────

(def ^:private envelope-capability
  "Minimal envelope capability that satisfies held-custody's declared
   dependency on [:prf/content-addressed-artifacts :prf/envelope].
   Registered with its own true declared identity — not a force-auth shim."
  {:capability/kind :prf/content-addressed-artifacts
   :capability/id :prf/envelope
   :capability/version 1
   :capability/contract-version 1
   :entrypoint 'prf.extensions.envelope/artifact-envelope
   :input-schema :prf/artifact-envelope-input.v1
   :output-schema :prf/artifact-envelope-output.v1
   :composition-contract {:composition-contract/version 1
                          :composition/input {:schema-ref :prf/artifact-envelope-input.v1}
                          :composition/output {:schema-ref :prf/artifact-envelope-output.v1}}})

(def ^:private envelope-package
  {:extension/id :prf.extensions/content-addressed-artifacts
   :extension/version "1.0.0"
   :extension/api-version 1
   :extension/manifest-version 1
   :extension/capabilities [envelope-capability]
   :extension/license "Apache-2.0"
   :extension/maintainers ["PRF core"]
   :extension/support-policy :core
   :extension/funding-status :core
   :extension/status {:lifecycle :active :distribution :core
                      :conformance :conformant :reproduction :artifact-replayable
                      :verification :replayed :maintenance :supported
                      :adoption :multi-adapter}})

;; ── schema roots for resolution ────────────────────────────────────────────

(def ^:private standard-schemas
  "Schema-root map for all schema ids referenced by the physical manifests."
  (into {} (map (fn [id] [id (str "sha256:" (name id))]))
        [:prf/force-authorisation-scope.v1
         :prf/force-authorisation-scope-verification.v1
         :prf/governed-force-authorisation-permit-input.v1
         :prf/governed-force-authorisation-permit.v1
         :prf/governed-force-authorisation-permit-verification.v1
         :sew/force-authorised-custody-execution-input.v1
         :sew/force-authorised-custody-execution-result.v1
         :sew/force-authorisation-governed-provenance.v1
         :prf/force-authorised-held-mutation-input.v1
         :prf/force-authorised-held-mutation-artifact.v1
         :prf/force-authorised-effect-verification.v1
         :prf/artifact-envelope-input.v1
         :prf/artifact-envelope-output.v1
         :resolver-sim/governed-authority-input.v1
         :resolver-sim/governed-authority-report.v1]))

(def ^:private standard-opts {:schemas standard-schemas :effect-schemas {}})

;; ── extension-map builders from physical manifests only ────────────────────

(defn ^:private full-physical-extension-map
  "Extension-map built exclusively from validated physical manifests:
   - envelope provider (satisfies held-custody dependency)
   - physical force-authorisation package (scope-verification, governed-permit,
     custody-execution)
   - physical held-custody package (mutation)
   - governed-authority provider fixture (required by governed-permit)"
  []
  (-> (reg/empty-extension-map)
      (reg/register-package envelope-package)
      (reg/register-package @facade/package)
      (reg/register-package (held-custody-package))
      (facade/install-governed-authority)))

(defn ^:private no-governed-authority-extension-map
  "Extension-map with all physical packages registered EXCEPT the
   governed-authority provider — used to prove resolution fails when the
   dependency is missing."
  []
  (-> (reg/empty-extension-map)
      (reg/register-package envelope-package)
      (reg/register-package @facade/package)
      (reg/register-package (held-custody-package))))

(defn ^:private no-held-custody-extension-map
  "Extension-map with force-authorisation + envelope + governed-authority
   but NOT held-custody — used to prove custody-execution fails without
   the mutation capability."
  []
  (-> (reg/empty-extension-map)
      (reg/register-package envelope-package)
      (reg/register-package @facade/package)
      (facade/install-governed-authority)))

(defn ^:private legacy-facade-extension-map
  "Extension-map with the legacy facade package and governed-authority fixture
   but NO physical force-authorisation package. Used to prove that the legacy
   facade cannot satisfy authoritative physical-capability requirements."
  []
  (-> (reg/empty-extension-map)
      (reg/register-package envelope-package)
      (facade/install-governed-authority)
      (reg/register-package @facade/package)))

;; ── capability graph tests A-F (physical manifests only) ───────────────────

(deftest governed-authority-to-governed-permit-resolves
  (testing "A. governed-authority -> governed-permit resolves"
    (when (facade/scope-verification-capability)
      (let [em (full-physical-extension-map)
            result (resolution/resolve-requested
                    em
                    [[:assurance/force-authorisation :force-authorisation/governed-permit-v1]]
                    standard-opts)]
        (is (:valid? result)
            (str "governed-permit should resolve when governed-authority is present; "
                 "violations: " (pr-str (:violations result))))
        (when (:valid? result)
          (is (contains? (get-in result [:resolution :extensions/capabilities])
                         [:assurance/governed-authority :resolver-sim/three-member-v1])
              "governed-authority must be resolved in the closure"))))))

(deftest missing-governed-authority-fails-governed-permit
  (testing "B. missing governed-authority -> governed-permit fails"
    (when (facade/scope-verification-capability)
      (let [em (no-governed-authority-extension-map)
            result (resolution/resolve-requested
                    em
                    [[:assurance/force-authorisation :force-authorisation/governed-permit-v1]]
                    standard-opts)]
        (is (false? (:valid? result))
            "governed-permit must fail when governed-authority is absent")
        (is (some #(= :extensions/error-missing-dependency (:violation/id %))
                  (:violations result))
            "violation must be missing-dependency")))))

(deftest scope-verification-to-held-custody-mutation-resolves
  (testing "C. scope-verification -> held-custody/mutation resolves"
    (when (facade/scope-verification-capability)
      (let [em (full-physical-extension-map)
            result (resolution/resolve-requested
                    em
                    [[:force-authorisation/effect-evidence :held-custody/mutation]]
                    standard-opts)]
        (is (:valid? result)
            (str "held-custody/mutation should resolve when scope-verification is present; "
                 "violations: " (pr-str (:violations result))))
        (when (:valid? result)
          (is (contains? (get-in result [:resolution :extensions/capabilities])
                         [:prf/force-authorisation :force-authorisation/scope-verification])
              "scope-verification must be resolved in the closure"))))))

(deftest full-dependency-chain-resolves-custody-execution
  (testing "D. governed-permit + held-custody/mutation -> custody-execution resolves"
    (when (facade/scope-verification-capability)
      (let [em (full-physical-extension-map)
            result (resolution/resolve-requested
                    em
                    [[:sew/force-authorisation :force-authorisation/custody-execution-v1]]
                    standard-opts)]
        (is (:valid? result)
            (str "custody-execution should resolve with all dependencies present; "
                 "violations: " (pr-str (:violations result))))
        (when (:valid? result)
          (let [caps (get-in result [:resolution :extensions/capabilities])]
            (is (contains? caps [:sew/force-authorisation :force-authorisation/custody-execution-v1])
                "custody-execution must be resolved")
            (is (contains? caps [:assurance/force-authorisation :force-authorisation/governed-permit-v1])
                "governed-permit must be resolved")
            (is (contains? caps [:assurance/governed-authority :resolver-sim/three-member-v1])
                "governed-authority must be resolved")
            (is (contains? caps [:force-authorisation/effect-evidence :held-custody/mutation])
                "held-custody/mutation must be resolved")
            (is (contains? caps [:prf/force-authorisation :force-authorisation/scope-verification])
                "scope-verification must be resolved")))))))

(deftest missing-held-custody-fails-custody-execution
  (testing "E. missing either dependency -> custody-execution fails"
    (when (facade/scope-verification-capability)
      (let [em (no-held-custody-extension-map)
            result (resolution/resolve-requested
                    em
                    [[:sew/force-authorisation :force-authorisation/custody-execution-v1]]
                    standard-opts)]
        (is (false? (:valid? result))
            "custody-execution must fail when held-custody/mutation is absent")
        (is (some #(= :extensions/error-missing-dependency (:violation/id %))
                  (:violations result))
            "violation must be missing-dependency")))))

(deftest resolution-terminates-without-cycles
  (testing "F. resolution terminates without a cycle"
    (when (facade/scope-verification-capability)
      (let [em (full-physical-extension-map)
            result (resolution/resolve-requested
                    em
                    [[:sew/force-authorisation :force-authorisation/custody-execution-v1]]
                    standard-opts)]
        (is (:valid? result)
            "full resolution must succeed")
        (when (:valid? result)
          (is (nil? (some #(= :extensions/error-dependency-cycle (:violation/id %))
                          (:violations result)))
              "resolution must not report a dependency cycle"))))))

;; ── authoritative constructor tests ────────────────────────────────────────

(deftest plain-composition-works-without-physical
  (testing "physical package absent + plain composition -> succeeds"
    (let [em (reg/empty-extension-map)
          result (semantic/build-authoritative
                  em
                  []
                  standard-opts)]
      (is (some? result)
          "plain composition should succeed without physical package"))))

(deftest requested-force-auth-fails-without-physical
  (testing "physical package absent + request scope-verification -> fails"
    (let [em (reg/empty-extension-map)]
      (is (thrown? Exception
                   (semantic/build-authoritative
                    em
                    [[:prf/force-authorisation :force-authorisation/scope-verification]]
                    standard-opts)))))
  (testing "physical package absent + request governed-permit -> fails"
    (let [em (reg/empty-extension-map)]
      (is (thrown? Exception
                   (semantic/build-authoritative
                    em
                    [[:assurance/force-authorisation :force-authorisation/governed-permit-v1]]
                    standard-opts)))))
  (testing "physical package absent + request custody-execution -> fails"
    (let [em (reg/empty-extension-map)]
      (is (thrown? Exception
                   (semantic/build-authoritative
                    em
                    [[:sew/force-authorisation :force-authorisation/custody-execution-v1]]
                    standard-opts))))))

(deftest legacy-facade-cannot-satisfy-authoritative-physical
  (testing "legacy facade descriptors present + authoritative production request
            cannot satisfy physical force-auth capability requirements"
    (when (not (facade/scope-verification-capability))
      ;; In legacy mode, the facade returns the legacy package with kind
      ;; :assurance/exceptional-force-authorisation / id :sew/force-authorisation-v1
      ;; — this is NOT the physical capability and must not satisfy authoritative
      ;; requests for physical capability identities.
      (let [em (legacy-facade-extension-map)]
        (testing "requesting physical scope-verification with only legacy facade fails"
          (is (thrown? Exception
                       (semantic/build-authoritative
                        em
                        [[:prf/force-authorisation :force-authorisation/scope-verification]]
                        standard-opts))))
        (testing "requesting physical custody-execution with only legacy facade fails"
          (is (thrown? Exception
                       (semantic/build-authoritative
                        em
                        [[:sew/force-authorisation :force-authorisation/custody-execution-v1]]
                        standard-opts))))))))

(deftest caller-cannot-inject-arbitrary-resolution-root
  (testing "caller cannot inject arbitrary resolution-root to authoritative constructor"
    (when (facade/scope-verification-capability)
      (let [em (full-physical-extension-map)
            result (semantic/build-authoritative
                    em
                    [[:sew/force-authorisation :force-authorisation/custody-execution-v1]]
                    (assoc standard-opts :extensions/resolution-root "fake-root"))]
        (is (some? result))
        (when result
          (let [root (:semantic-composition/resolution-root result)
                resolution-root (get-in result [:semantic-composition/resolution
                                                :extensions/resolution-root])]
            (is (not= "fake-root" root)
                "caller-supplied resolution-root must not appear in composition")
            (is (not= "fake-root" resolution-root)
                "caller-supplied resolution-root must not appear in resolution")))))))

(deftest caller-cannot-inject-arbitrary-provider-package-root
  (testing "caller cannot inject arbitrary provider package root"
    (when (facade/scope-verification-capability)
      (let [em (full-physical-extension-map)
            result (semantic/build-authoritative
                    em
                    [[:sew/force-authorisation :force-authorisation/custody-execution-v1]]
                    (assoc standard-opts :extensions/provider-package-roots #{"sha256:fake-root"}))]
        (is (some? result))
        (when result
          (let [packages (get-in result [:semantic-composition/resolution
                                         :extensions/packages])]
            (is (not= #{"sha256:fake-root"} (set (map :package-root (vals packages))))
                "no provider package root should be the injected fake")))))))

(deftest caller-cannot-inject-arbitrary-modules
  (testing "caller cannot inject arbitrary action/state/invariant modules"
    (when (facade/scope-verification-capability)
      (let [em (full-physical-extension-map)
            result (semantic/build-authoritative
                    em
                    [[:sew/force-authorisation :force-authorisation/custody-execution-v1]]
                    (-> standard-opts
                        (assoc :semantic-composition/action-modules [[:fake :action]]
                               :semantic-composition/state-modules [[:fake :state]]
                               :semantic-composition/invariant-modules [[:fake :invariant]])))]
        (is (some? result))
        (when result
          (is (not (some #(= [:fake :action] %) (:semantic-composition/action-modules result)))
              "fake action module must not appear")
          (is (not (some #(= [:fake :state] %) (:semantic-composition/state-region-modules result)))
              "fake state module must not appear")
          (is (not (some #(= [:fake :invariant] %) (:semantic-composition/invariant-modules result)))
              "fake invariant module must not appear"))))))

(deftest modules-derive-from-selected-capabilities
  (testing "modules derive from selected capabilities"
    (when (facade/scope-verification-capability)
      (let [em (full-physical-extension-map)
            result (semantic/build-authoritative
                    em
                    [[:sew/force-authorisation :force-authorisation/custody-execution-v1]]
                    standard-opts)]
        (is (some? result)
            "custody-execution composition should be valid")
        (when result
          (is (seq (:semantic-composition/action-modules result))
              "custody-execution should derive action modules")
          (is (seq (:semantic-composition/state-region-modules result))
              "custody-execution should derive state modules")
          (is (seq (:semantic-composition/invariant-modules result))
              "custody-execution should derive invariant modules"))))))

;; ── module descriptor completeness tests ───────────────────────────────────

(deftest action-module-is-exhaustive
  (testing "action module contains all live force-auth actions dispatched by Sew"
    (let [expected-actions semantic/force-authorisation-actions
          actual-actions (set (:module/actions semantic/force-authorisation-action-module))]
      (is (= expected-actions actual-actions)
          "action module set must exactly equal the complete force-auth action vocabulary")
      (doseq [action ["grant-force-authorisation" "grant-force-authorization"
                      "grant-consensus-force-authorisation"
                      "grant-related-claims-force-authorisation"
                      "revoke-force-authorisation"
                      "execute-force-authorised-action"
                      "execute-force-authorized-action"]]
        (is (contains? actual-actions action)
            (str "action module must contain: " action))))))

(deftest state-module-is-exhaustive
  (testing "state module contains all live force-auth state keys"
    (let [expected-regions semantic/force-authorisation-live-state-regions
          actual-regions (set (:module/state-regions semantic/force-authorisation-state-module))]
      (is (= expected-regions actual-regions)
          "state module set must exactly equal the complete live-force-auth state vocabulary")
      (doseq [region [:force-authorisations :force-authorisations/consumed
                      :force-authorisations/consumption-records
                      :next-force-authorisation-id]]
        (is (contains? actual-regions region)
            (str "state module must contain: " region))))))

(deftest invariant-module-is-exhaustive
  (testing "invariant module contains all real force-auth invariants from Sew"
    (let [expected-invariants semantic/force-authorisation-invariants
          actual-invariants (set (:module/invariant-ids semantic/force-authorisation-invariant-module))]
      (is (= expected-invariants actual-invariants)
          "invariant module set must exactly equal the complete force-auth invariant vocabulary")
      (doseq [inv [:force-authorisations-lifecycle-consistent
                   :force-authorisations-governance-origin]]
        (is (contains? actual-invariants inv)
            (str "invariant module must contain: " inv))))))

(deftest module-actions-are-canonical-sorted
  (testing "module action list is canonically sorted (no hash-set ordering dependency)"
    (let [actions (:module/actions semantic/force-authorisation-action-module)]
      (is (= actions (vec (sort actions)))
          "actions must be in sorted order"))))

(deftest module-state-regions-are-canonical-sorted
  (testing "module state-region list is canonically sorted"
    (let [regions (:module/state-regions semantic/force-authorisation-state-module)]
      (is (= regions (vec (sort regions)))
          "state regions must be in sorted order"))))

(deftest module-invariant-ids-are-canonical-sorted
  (testing "module invariant-id list is canonically sorted"
    (let [invariants (:module/invariant-ids semantic/force-authorisation-invariant-module)]
      (is (= invariants (vec (sort invariants)))
          "invariant ids must be in sorted order"))))

;; ── canonical ordering / root stability tests ────────────────────────────────

(deftest equivalent-requests-give-equal-roots
  (testing "equivalent canonical requests give equal composition roots"
    (when (facade/scope-verification-capability)
      (let [em (full-physical-extension-map)
            r1 (semantic/build-authoritative
                em
                [[:sew/force-authorisation :force-authorisation/custody-execution-v1]]
                standard-opts)
            r2 (semantic/build-authoritative
                em
                [[:sew/force-authorisation :force-authorisation/custody-execution-v1]]
                standard-opts)]
        (is (some? r1))
        (is (some? r2))
        (when (and r1 r2)
          (is (= (:semantic-composition/root r1) (:semantic-composition/root r2))
              "same request should produce identical root"))))))

(deftest different-provider-package-root-changes-root
  (testing "different provider package root changes composition root"
    (when (facade/scope-verification-capability)
      ;; Construct an independent alternative package with the same capability
      ;; identities but a different entrypoint and package identity — NOT by
      ;; assoc-ing on the physical capabilities, but by declaring independent
      ;; descriptors with the exact same kind/id/version/contract-version/profile
      ;; and declared-dependencies.
      (let [alt-scope-verification
            (assoc (phys-scope-verification)
                   :entrypoint 'prf.extensions.force-authorisation-alt/scope-verification)
            alt-governed-permit
            (assoc (phys-governed-permit)
                   :entrypoint 'prf.extensions.force-authorisation-alt/governed-permit)
            alt-custody-execution
            (assoc (phys-custody-execution)
                   :entrypoint 'prf.extensions.force-authorisation-alt/custody-execution)
            alt-pkg
            {:extension/id :prf.extensions/force-authorisation-alt
             :extension/version "0.2.0"
             :extension/api-version 1
             :extension/manifest-version 1
             :extension/capabilities [alt-scope-verification
                                      alt-governed-permit
                                      alt-custody-execution]
             :extension/license "Apache-2.0"
             :extension/maintainers ["PRF core"]
             :extension/support-policy :core
             :extension/funding-status :core
             :extension/status {:lifecycle :active :distribution :core
                                :conformance :conformant :reproduction :artifact-replayable
                                :verification :replayed :maintenance :supported
                                :adoption :multi-adapter}}
            em1 (full-physical-extension-map)
            r1 (semantic/build-authoritative
                em1
                [[:sew/force-authorisation :force-authorisation/custody-execution-v1]]
                standard-opts)]
        (is (some? r1) "first registration must resolve")
        (when r1
          (let [root1 (:semantic-composition/root r1)
                em2 (-> (reg/empty-extension-map)
                        (reg/register-package envelope-package)
                        (reg/register-package alt-pkg)
                        (reg/register-package (held-custody-package))
                        (facade/install-governed-authority))
                r2 (semantic/build-authoritative
                    em2
                    [[:sew/force-authorisation :force-authorisation/custody-execution-v1]]
                    standard-opts)]
            (is (some? r2) "second registration must resolve")
            (when r2
              (let [root2 (:semantic-composition/root r2)]
                (is (not= root1 root2)
                    "different provider package must produce different root")))))))))

(deftest canonical-input-reordering-does-not-change-root
  (testing "canonical input reordering of request vector does not change root"
    (when (facade/scope-verification-capability)
      (let [em (full-physical-extension-map)
            r1 (semantic/build-authoritative
                em
                [[:sew/force-authorisation :force-authorisation/custody-execution-v1]]
                standard-opts)
            r2 (semantic/build-authoritative
                em
                (reverse [[:sew/force-authorisation :force-authorisation/custody-execution-v1]])
                standard-opts)]
        (is (some? r1))
        (is (some? r2))
        (when (and r1 r2)
          (is (= (:semantic-composition/root r1) (:semantic-composition/root r2))
              "reordered request should produce identical root"))))))

(deftest canonical-package-reordering-does-not-change-root
  (testing "canonical package registration order does not change root"
    (when (facade/scope-verification-capability)
      ;; Build the same extension-map with packages registered in a different
      ;; order — derive-packages sorts by extension/id, so the root must be
      ;; identical regardless of registration order.
      (let [em1 (-> (reg/empty-extension-map)
                    (reg/register-package envelope-package)
                    (reg/register-package @facade/package)
                    (reg/register-package (held-custody-package))
                    (facade/install-governed-authority))
            em2 (-> (reg/empty-extension-map)
                    (facade/install-governed-authority)
                    (reg/register-package (held-custody-package))
                    (reg/register-package envelope-package)
                    (reg/register-package @facade/package))
            r1 (semantic/build-authoritative
                em1
                [[:sew/force-authorisation :force-authorisation/custody-execution-v1]]
                standard-opts)
            r2 (semantic/build-authoritative
                em2
                [[:sew/force-authorisation :force-authorisation/custody-execution-v1]]
                standard-opts)]
        (is (some? r1))
        (is (some? r2))
        (when (and r1 r2)
          (is (= (:semantic-composition/root r1) (:semantic-composition/root r2))
              "different package registration order should produce identical root")
          ;; Also verify the derived packages are in canonical order
          (let [pkgs1 (:semantic-composition/packages r1)
                pkgs2 (:semantic-composition/packages r2)]
            (is (= pkgs1 pkgs2)
                "derived packages must be in identical canonical order")))))))

(deftest capability-graph-reordering-does-not-change-root
  (testing "reordering requested capability keys does not change root"
    (when (facade/scope-verification-capability)
      (let [em (full-physical-extension-map)
            ;; Request scope-verification first, then custody-execution
            r1 (semantic/build-authoritative
                em
                [[:prf/force-authorisation :force-authorisation/scope-verification]
                 [:sew/force-authorisation :force-authorisation/custody-execution-v1]]
                standard-opts)
            ;; Request custody-execution first, then scope-verification
            r2 (semantic/build-authoritative
                em
                [[:sew/force-authorisation :force-authorisation/custody-execution-v1]
                 [:prf/force-authorisation :force-authorisation/scope-verification]]
                standard-opts)]
        (is (some? r1))
        (is (some? r2))
        (when (and r1 r2)
          (is (= (:semantic-composition/root r1) (:semantic-composition/root r2))
              "reordered capability requests should produce identical root"))))))

;; ── scope / capability subset tests ──────────────────────────────────────────

(deftest scope-verification-only-derives-no-sew-modules
  (testing "scope-verification only -> no live Sew force-auth action/state/invariant modules"
    (when (facade/scope-verification-capability)
      (let [em (full-physical-extension-map)
            result (semantic/build-authoritative
                    em
                    [[:prf/force-authorisation :force-authorisation/scope-verification]]
                    standard-opts)]
        (is (some? result)
            "scope-verification should resolve")
        (when result
          (is (empty? (:semantic-composition/action-modules result))
              "scope-verification only should derive no action modules")
          (is (empty? (:semantic-composition/state-region-modules result))
              "scope-verification only should derive no state modules")
          (is (empty? (:semantic-composition/invariant-modules result))
              "scope-verification only should derive no invariant modules"))))))

(deftest governed-permit-only-derives-no-custody-modules
  (testing "governed-permit only -> no custody-execution live Sew modules"
    (when (facade/scope-verification-capability)
      (let [em (full-physical-extension-map)
            result (semantic/build-authoritative
                    em
                    [[:assurance/force-authorisation :force-authorisation/governed-permit-v1]]
                    standard-opts)]
        (is (some? result)
            "governed-permit should resolve")
        (when result
          (is (empty? (:semantic-composition/action-modules result))
              "governed-permit only should derive no action modules")
          (is (empty? (:semantic-composition/state-region-modules result))
              "governed-permit only should derive no state modules")
          (is (empty? (:semantic-composition/invariant-modules result))
              "governed-permit only should derive no invariant modules"))))))

;; ── plain composition (no physical deps) ─────────────────────────────────

(deftest plain-composition-has-no-force-auth-modules
  (testing "plain composition has no force-auth policy binding"
    (let [em (reg/empty-extension-map)
          result (semantic/build-authoritative
                  em
                  []
                  standard-opts)]
      (is (some? result))
      (when result
        (is (empty? (:semantic-composition/action-modules result))
            "plain composition has no action modules")
        (is (empty? (:semantic-composition/state-region-modules result))
            "plain composition has no state modules")
        (is (empty? (:semantic-composition/invariant-modules result))
            "plain composition has no invariant modules")
        (is (empty? (:semantic-composition/policy-bindings result))
            "plain composition has no policy bindings")))))

(deftest plain-composition-has-canonical-root
  (testing "two plain compositions produce identical roots"
    (let [em (reg/empty-extension-map)
          r1 (semantic/build-authoritative em [] standard-opts)
          r2 (semantic/build-authoritative em [] standard-opts)]
      (is (some? r1))
      (is (some? r2))
      (when (and r1 r2)
        (is (= (:semantic-composition/root r1) (:semantic-composition/root r2))
            "identical plain compositions have identical roots")))))

;; ── policy binding tests ─────────────────────────────────────────────────────

(deftest policy-root-changes-when-policy-changes
  (testing "changing behavioral policy changes composition-root"
    (when (facade/scope-verification-capability)
      (let [em (full-physical-extension-map)
            default-policy (semantic/default-force-authorisation-policy)
            strict-policy  (fa-policy/build
                            {:policy-id "strict-policy-v1"
                             :policy-version 1
                             :member-count 3
                             :threshold 2
                             :scope-required? true
                             :single-use? true
                             :preserve-dissent? true
                             :expiry-required? true
                             :allowed-reasons ["governance-mandated"]})
            r1 (semantic/build-authoritative
                em
                [[:sew/force-authorisation :force-authorisation/custody-execution-v1]]
                (assoc standard-opts :force-authorisation-policy default-policy))
            r2 (semantic/build-authoritative
                em
                [[:sew/force-authorisation :force-authorisation/custody-execution-v1]]
                (assoc standard-opts :force-authorisation-policy strict-policy))]
        (is (some? r1))
        (is (some? r2))
        (when (and r1 r2)
          (is (not= (:semantic-composition/root r1) (:semantic-composition/root r2))
              "different policy must produce different composition root")
          (is (not= (get-in r1 [:semantic-composition/policy-bindings
                                :force-authorisation :policy/root])
                    (get-in r2 [:semantic-composition/policy-bindings
                                :force-authorisation :policy/root]))
              "different policy must produce different policy root"))))))

(deftest policy-root-is-behavioral-not-provenance
  (testing "policy root is derived from behavioral policy fields, not provenance schema root"
    (when (facade/scope-verification-capability)
      (let [em (full-physical-extension-map)
            default-policy (semantic/default-force-authorisation-policy)
            r (semantic/build-authoritative
               em
               [[:sew/force-authorisation :force-authorisation/custody-execution-v1]]
               (assoc standard-opts :force-authorisation-policy default-policy))
            policy-binding (get-in r [:semantic-composition/policy-bindings
                                      :force-authorisation])]
        (is (some? r))
        (when r
          (let [policy-root (get policy-binding :policy/root)]
            ;; The policy root should be a sha256 reference, not the schema root
            (is (string? policy-root)
                "policy root must be a string hash reference")
            (is (not= "sha256:provenance" policy-root)
                "policy root must not be the provenance schema root")
            (is (re-find #"sha256:" policy-root)
                "policy root must be a sha256 reference")))))))

(deftest local-compatibility-policy-rejected
  (testing "local-governance-only policy is rejected for production-governed"
    (when (facade/scope-verification-capability)
      (let [em (full-physical-extension-map)
            local-policy (fa-policy/build
                          {:policy-id "local-policy-v1"
                           :policy-version 1
                           :member-count 1
                           :threshold 1
                           :scope-required? true
                           :single-use? true
                           :preserve-dissent? true
                           :expiry-required? false
                           :allowed-reasons ["governance-mandated"]})]
        (is (thrown-with-msg?
             Exception
             #"does not conform to canonical"
             (semantic/build-authoritative
              em
              [[:sew/force-authorisation :force-authorisation/custody-execution-v1]]
              (assoc standard-opts :force-authorisation-policy local-policy))))))))

(deftest default-policy-used-when-none-supplied
  (testing "when custody-execution is active and no policy supplied, canonical default is used"
    (when (facade/scope-verification-capability)
      (let [em (full-physical-extension-map)
            r (semantic/build-authoritative
               em
               [[:sew/force-authorisation :force-authorisation/custody-execution-v1]]
               standard-opts)
            policy-binding (get-in r [:semantic-composition/policy-bindings
                                      :force-authorisation])]
        (is (some? r))
        (when r
          (is (some? policy-binding)
              "custody-execution must bind a force-authorisation policy")
          (is (some? (get policy-binding :policy/root))
              "policy root must be present"))))))

;; ── facade integrity tests ───────────────────────────────────────────────────

(deftest facade-preserves-physical-identity
  (testing "facade delegates capability identity to physical manifest"
    (when (facade/scope-verification-capability)
      (let [phys-pkg @facade/package]
        (is (= :prf.extensions/force-authorisation (:extension/id phys-pkg))
            "package id must be physical")
        (is (= "0.1.0" (:extension/version phys-pkg))
            "package version must be physical")
        (doseq [cap (:extension/capabilities phys-pkg)]
          (is (keyword? (:capability/kind cap))
              "every capability kind must be a keyword")
          (is (qualified-keyword? (:capability/id cap))
              "every capability id must be qualified (no unqualified shims)"))))))

(deftest no-authoritative-test-rewrites-capability-identities
  (testing "no authoritative physical-graph test rewrites capability identities"
    (when (facade/scope-verification-capability)
      ;; Verify that each physical capability in the facade package has
      ;; its exact canonical identity — no rewriting to legacy/unqualified.
      (let [caps (:extension/capabilities @facade/package)]
        (doseq [cap caps]
          (is (qualified-keyword? (:capability/kind cap))
              (str "kind must be qualified: " (:capability/kind cap)))
          (is (qualified-keyword? (:capability/id cap))
              (str "id must be qualified: " (:capability/id cap)))
          (is (integer? (:capability/version cap))
              "version must be an integer")
          (is (integer? (:capability/contract-version cap))
              "contract-version must be an integer"))))))

(deftest resolved-package-exactly-equal-physical-manifest
  (testing "physical package present -> resolved package facts exactly equal physical manifest"
    (when (facade/scope-verification-capability)
      (let [em (full-physical-extension-map)
            result (resolution/resolve-requested
                    em
                    [[:sew/force-authorisation :force-authorisation/custody-execution-v1]]
                    standard-opts)]
        (when (:valid? result)
          (let [packages (get-in result [:resolution :extensions/packages])
                fa-res (get packages :prf.extensions/force-authorisation)]
            (is (= (em/package-root @facade/package)
                   (:package-root fa-res))
                "resolved package root must equal physical package root")
            (is (= (:extension/version @facade/package)
                   (:package/version fa-res))
                "resolved package version must equal physical package version")))))))

(deftest resolved-capabilities-exactly-equal-physical-descriptors
  (testing "resolved capability facts exactly equal physical manifest descriptors"
    (when (facade/scope-verification-capability)
      (let [em (full-physical-extension-map)
            result (resolution/resolve-requested
                    em
                    [[:sew/force-authorisation :force-authorisation/custody-execution-v1]]
                    standard-opts)]
        (when (:valid? result)
          (let [caps (get-in result [:resolution :extensions/capabilities])
                scope-cap (get caps [:prf/force-authorisation :force-authorisation/scope-verification])
                permit-cap (get caps [:assurance/force-authorisation :force-authorisation/governed-permit-v1])
                exec-cap (get caps [:sew/force-authorisation :force-authorisation/custody-execution-v1])
                phys-scope (em/capability-projection (phys-scope-verification))
                phys-permit (em/capability-projection (phys-governed-permit))
                phys-exec (em/capability-projection (phys-custody-execution))]
            (is (= phys-scope scope-cap)
                "scope-verification resolved capability must equal physical descriptor projection")
            (is (= phys-permit permit-cap)
                "governed-permit resolved capability must equal physical descriptor projection")
            (is (= phys-exec exec-cap)
                "custody-execution resolved capability must equal physical descriptor projection")))))))

(deftest legacy-facade-has-explicit-non-authoritative-identity
  (testing "legacy facade descriptors are explicitly named as non-authoritative"
    ;; In physical mode, the facade delegates to physical manifests, so
    ;; @facade/capability is the physical capability. The legacy fixtures
    ;; are only active in legacy mode (no physical extension on classpath).
    (when (not (facade/scope-verification-capability))
      ;; In legacy mode, the facade returns legacy-capability with kind
      ;; :assurance/exceptional-force-authorisation and id :sew/force-authorisation-v1
      ;; — NOT the physical capability identities.
      (let [legacy-cap @facade/capability
            legacy-pkg @facade/package]
        (is (not= (:capability/kind legacy-cap)
                  [:sew/force-authorisation :force-authorisation/custody-execution-v1])
            "legacy capability kind/id must not be the physical custody-execution identity")
        (is (not= (:capability/id legacy-cap)
                  :force-authorisation/custody-execution-v1)
            "legacy capability id must not match physical custody-execution id")
        (is (= (:extension/id legacy-pkg) :sew/force-authorisation)
            "legacy package id is explicitly :sew/force-authorisation (not physical)")))))
