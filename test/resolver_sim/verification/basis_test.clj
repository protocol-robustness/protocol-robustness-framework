(ns resolver-sim.verification.basis-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.extensions.fixtures :as fx]
            [resolver-sim.extensions.registry :as registry]
            [resolver-sim.extensions.resolution :as res]
            [resolver-sim.hash.reference :as ref]
            [resolver-sim.verification.basis :as basis]))

(def root-a "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
(def root-b "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
(def root-c "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc")
(def root-d "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd")
(def root-e "sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee")
(def root-f "sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff")

(defn valid-basis []
  (basis/build-basis
   {:verification-basis/subject {:capability/kind :assurance/governed-authority
                                 :capability/id :resolver-sim/three-member-v1
                                 :capability/contract-version 1
                                 :subject/root root-a}
    :chain-configuration/root root-b
    :currently-authorized-chain-configuration/root root-b
    :verifier-registry/root root-c
    :extensions/resolution-root root-d}))

(deftest basis-is-closed-self-authenticating-and-authority-bound
  (let [b (valid-basis)]
    (is (:valid? (basis/validate-basis b)))
    (is (= root-b (:currently-authorized-chain-configuration/root b)))
    (is (not (:valid? (basis/validate-basis (assoc b :verifier-registry/root root-e))))
        "changing any authority input invalidates the committed basis root")
    (is (not (:valid? (basis/validate-basis (assoc b :currently-authorized-chain-configuration/root root-f))))
        "a claimed current root must equal the bound configuration root")))

(deftest verifier-results-bind-the-exact-basis-and-implementation
  (let [b (valid-basis)
        r (basis/build-result
           {:verification-basis/root (:verification-basis/root b)
            :verification/verifier-ref {:capability/kind :evidence/verifier
                                        :capability/id :fixture/governed-authority
                                        :descriptor-root (subs root-e 7)}
            :verification/verifier-package-root (subs root-f 7)
            :verification/status :verified})]
    (is (:valid? (basis/validate-result r)))
    (is (not (:valid? (basis/validate-result (assoc r :verification/status :unauthorised))))
        "unknown status vocabulary cannot be accepted")
    (is (not (:valid? (basis/validate-result (assoc r :verification-basis/root root-a))))
        "a result root cannot be replayed against another basis")))

;; ── authority-bound selection tests ─────────────────────────────────────

(def ^:private verifier-subject
  {:capability/kind :assurance/governed-authority
   :capability/id :resolver-sim/three-member-v1
   :capability/contract-version 1})

(def ^:private test-schemas
  "Schemas needed for verifier resolution tests."
  (merge fx/schemas
         {:fixture/governed-authority-basis.v1 "sha256:governed-authority-basis"
          :fixture/governed-authority-result.v1 "sha256:governed-authority-result"}))

(defn- verifier-package
  "Build a sealed package containing a fixture verifier for `subject`."
  [subject]
  (assoc fx/scaled-share-pack
         :extension/id :fixture/governed-authority-verifier-package
         :extension/capabilities
         [{:capability/kind :evidence/verifier
           :capability/id :fixture/governed-authority-verifier
           :capability/version 1
           :capability/contract-version 1
           :entrypoint 'fixture.governed-authority/verify
           :input-schema :fixture/governed-authority-basis.v1
           :output-schema :fixture/governed-authority-result.v1
           :verifies subject}]))

(defn- build-verifier-extensions
  "Build an extension-map containing only the fixture verifier package."
  []
  (let [package (verifier-package verifier-subject)]
    (registry/register-package (registry/empty-extension-map) package)))

(defn- resolve-verifier
  "Build a resolution snapshot containing the fixture verifier."
  [extensions]
  (-> (res/resolve-requested extensions
                             [[:evidence/verifier :fixture/governed-authority-verifier]]
                             {:schemas test-schemas})
      :resolution))

(defn- build-authority-basis
  "Build a self-validating basis bound to the supplied extension-map and resolution."
  [extensions resolution]
  (basis/build-basis
   {:verification-basis/subject (assoc verifier-subject :subject/root root-a)
    :chain-configuration/root root-b
    :currently-authorized-chain-configuration/root root-b
    :verifier-registry/root (basis/verifier-registry-root extensions)
    :extensions/resolution-root (ref/sha256-ref (res/resolution-root resolution))}))

(defn- build-authority-result
  "Build a verification result for the selected verifier in `basis`."
  [basis selected]
  (basis/build-result
   {:verification-basis/root (:verification-basis/root basis)
    :verification/verifier-ref {:capability/kind :evidence/verifier
                                :capability/id :fixture/governed-authority-verifier
                                :descriptor-root (get-in selected [:entry :descriptor-root])}
    :verification/verifier-package-root (get-in selected [:provider :package-root])
    :verification/status :verified}))

(deftest accepted-results-bind-verifier-registry-and-resolution
  (let [extensions (build-verifier-extensions)
        resolution (resolve-verifier extensions)
        b (build-authority-basis extensions resolution)
        selected (registry/select-verifier extensions verifier-subject)
        result (build-authority-result b selected)]
    (is (:valid? (basis/verify-result-selection extensions resolution b result)))
    (is (= :verification/unselected-verifier
           (:reason (basis/verify-result-selection
                     extensions resolution b
                     (basis/build-result
                      (assoc (dissoc result :verification-result/schema :verification/result-root)
                             :verification/verifier-package-root (subs root-f 7))))))
        "a result referencing a different package root is rejected")))

(deftest registry-substitution-rejected
  (let [extensions (build-verifier-extensions)
        resolution (resolve-verifier extensions)
        alt-extensions (-> (registry/empty-extension-map)
                           (registry/register-package (verifier-package verifier-subject))
                           (registry/register-package fx/scaled-share-pack))
        b (build-authority-basis extensions resolution)
        selected (registry/select-verifier extensions verifier-subject)
        result (build-authority-result b selected)]
    (is (not= (basis/verifier-registry-root extensions)
              (basis/verifier-registry-root alt-extensions))
        "different extension-maps produce different verifier-registry roots")
    (let [rejected (basis/verify-result-selection alt-extensions resolution b result)]
      (is (not (:valid? rejected)))
      (is (= :verification/registry-root-mismatch (:reason rejected))))))

(deftest resolution-substitution-rejected
  (let [extensions (build-verifier-extensions)
        resolution (resolve-verifier extensions)
        alt-resolution (-> (res/resolve-requested extensions
                                                  [[:evidence/verifier :fixture/governed-authority-verifier]]
                                                  {:schemas test-schemas
                                                   :runtime-profile {:prf/version "0.0.0-snapshot"
                                                                     :jvm/profile :jvm-21}})
                           :resolution)
        b (build-authority-basis extensions resolution)
        selected (registry/select-verifier extensions verifier-subject)
        result (build-authority-result b selected)]
    (is (not= (ref/sha256-ref (res/resolution-root resolution))
              (ref/sha256-ref (res/resolution-root alt-resolution)))
        "different resolutions produce different resolution roots")
    (let [rejected (basis/verify-result-selection extensions alt-resolution b result)]
      (is (not (:valid? rejected)))
      (is (= :verification/resolution-root-mismatch (:reason rejected))))))
