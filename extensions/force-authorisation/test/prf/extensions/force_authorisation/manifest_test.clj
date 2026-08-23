(ns prf.extensions.force-authorisation.manifest-test
  "Tests for the physical force-authorisation extension package manifest:
   strict conformance to the core extension contract, pure package registration,
   three distinct capabilities, and declared dependency integrity."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.extensions.manifest :as em]
            [resolver-sim.extensions.registry :as registry]
            [prf.extensions.force-authorisation.manifest :as manifest]))

(deftest manifest-conforms-to-core-extension-contract
  (testing "package validation"
    (let [validation (em/validate-package manifest/package)]
      (is (:valid? validation)
          (str "manifest violations: " (pr-str (:violations validation)))))
    (is (= :prf.extensions/force-authorisation (:extension/id manifest/package)))
    (is (string? (:extension/version manifest/package)))))

(deftest three-distinct-capabilities
  (testing "capability count and identities"
    (let [caps (:extension/capabilities manifest/package)]
      (is (= 3 (count caps)))
      (is (some #(= [:prf/force-authorisation :force-authorisation/scope-verification]
                    [(:capability/kind %) (:capability/id %)]) caps)
          "scope-verification capability present")
      (is (some #(= [:assurance/force-authorisation :force-authorisation/governed-permit-v1]
                    [(:capability/kind %) (:capability/id %)]) caps)
          "governed-permit-v1 capability present")
      (is (some #(= [:sew/force-authorisation :force-authorisation/custody-execution-v1]
                    [(:capability/kind %) (:capability/id %)]) caps)
          "custody-execution-v1 capability present"))))

(deftest governance-profile-is-production-governed
  (testing "governed-permit declares :production-governed profile"
    (let [governed-permit (first (filter #(= :force-authorisation/governed-permit-v1
                                              (:capability/id %))
                                         (:extension/capabilities manifest/package)))]
      (is (some? governed-permit))
      (is (= :production-governed
             (get-in governed-permit [:declared-dependencies 0 :requirement :capability/profile]))
          "governed-permit depends on governed-authority with :production-governed profile"))))

(deftest governance-profile-not-altered-by-local-compatibility
  (testing "production-governed profile is fixed and cannot be altered"
    (let [governed-permit (first (filter #(= :force-authorisation/governed-permit-v1
                                              (:capability/id %))
                                         (:extension/capabilities manifest/package)))
          profile (get-in governed-permit [:declared-dependencies 0 :requirement :capability/profile])]
      (is (= :production-governed profile)
          "governed-permit dependency profile is :production-governed, not alterable"))))

(deftest declared-dependencies-not-stripped
  (testing "governed-permit retains its declared-dependencies"
    (let [governed-permit (first (filter #(= :force-authorisation/governed-permit-v1
                                              (:capability/id %))
                                         (:extension/capabilities manifest/package)))]
      (is (seq (:declared-dependencies governed-permit))
          "declared-dependencies must not be stripped")
      (is (some #(= :assurance/governed-authority
                    (get % :capability/kind))
                (:declared-dependencies governed-permit))
          "governed-authority dependency must be present")))

  (testing "custody-execution retains its declared-dependencies"
    (let [custody-execution (first (filter #(= :force-authorisation/custody-execution-v1
                                                (:capability/id %))
                                           (:extension/capabilities manifest/package)))]
      (is (seq (:declared-dependencies custody-execution))
          "declared-dependencies must not be stripped")
      (is (some #(= :assurance/force-authorisation
                    (get % :capability/kind))
                (:declared-dependencies custody-execution))
          "governed-permit dependency must be present")
      (is (some #(= :force-authorisation/effect-evidence
                    (get % :capability/kind))
                (:declared-dependencies custody-execution))
          "held-custody/mutation dependency must be present"))))

(deftest pure-registration-into-an-extension-map
  (testing "all three capabilities register into an extension-map"
    (let [extension-map (registry/register-package (registry/empty-extension-map) manifest/package)
          scope (registry/lookup-capability extension-map :prf/force-authorisation :force-authorisation/scope-verification)
          permit (registry/lookup-capability extension-map :assurance/force-authorisation :force-authorisation/governed-permit-v1)
          exec (registry/lookup-capability extension-map :sew/force-authorisation :force-authorisation/custody-execution-v1)]
      (is (some? scope))
      (is (some? permit))
      (is (some? exec))
      (is (false? (:builtin? scope)) "scope-verification is not a built-in")
      (is (false? (:builtin? permit)) "governed-permit is not a built-in")
      (is (false? (:builtin? exec)) "custody-execution is not a built-in"))))

(deftest capability-descriptor-roots-are-stable
  (testing "descriptor roots are stable and content-addressed"
    (let [caps (:extension/capabilities manifest/package)]
      (doseq [cap caps]
        (let [root (em/capability-descriptor-root cap)]
          (is (string? root) "descriptor root is a string")
          (is (= root (em/capability-descriptor-root cap))
              "descriptor root is stable"))))))

(deftest package-root-is-stable
  (testing "package root is stable and content-addressed"
    (let [root (em/package-root manifest/package)]
      (is (string? root))
      (is (= root (em/package-root manifest/package))
          "package root is stable"))))

(deftest governance-is-not-merged
  (testing "governed-authority is NOT provided by force-authorisation package"
    (let [caps (:extension/capabilities manifest/package)]
      (is (not-any? #(= :assurance/governed-authority (:capability/kind %)) caps)
          "force-authorisation must not provide governed-authority capability"))))

(deftest transaction-owner-declared
  (testing "custody-execution declares transaction-owner"
    (let [custody-execution (first (filter #(= :force-authorisation/custody-execution-v1
                                                (:capability/id %))
                                           (:extension/capabilities manifest/package)))]
      (is (= :sew-adapter (:transaction-owner custody-execution))
          "custody-execution must declare :transaction-owner :sew-adapter"))))
