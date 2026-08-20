(ns prf.extensions.force-authorisation.manifest-test
  "Physical-package tests for the capability split. These tests exercise only
   package manifests and resolution; they deliberately do not load Sew."
  (:require [clojure.test :refer [deftest is testing]]
            [prf.extensions.force-authorisation.manifest :as force]
            [prf.extensions.held-custody.manifest :as held]
            [resolver-sim.extensions.core :as core]
            [resolver-sim.extensions.manifest :as manifest]
            [resolver-sim.extensions.registry :as registry]
            [resolver-sim.extensions.resolution :as resolution]))

(defn- schemas-for [& packages]
  (into {}
        (map (fn [schema] [schema (str "sha256:" (name schema))]))
        (keep identity
              (mapcat (fn [package]
                        (mapcat #(map % [:input-schema :output-schema :verification/contract])
                                (:extension/capabilities package)))
                      packages))))

(defn- provider-package [id capability]
  {:extension/id id
   :extension/version "1.0.0"
   :extension/api-version 1
   :extension/manifest-version 1
   :extension/capabilities [capability]})

(def envelope-package
  (provider-package
   :fixture/envelope
   {:capability/kind :prf/content-addressed-artifacts
    :capability/id :prf/envelope
    :capability/version 1
    :capability/contract-version 1
    :entrypoint 'fixture/envelope
    :input-schema :fixture/envelope-input.v1
    :output-schema :fixture/envelope-output.v1
    :composition-contract {:composition-contract/version 1
                           :composition/input {:schema-ref :fixture/envelope-input.v1}
                           :composition/output {:schema-ref :fixture/envelope-output.v1}}}))

(def governed-authority-package
  (provider-package
   :fixture/governed-authority
   {:capability/kind :assurance/governed-authority
    :capability/id :resolver-sim/three-member-v1
    :capability/version 1
    :capability/contract-version 1
    :capability/profile :production-governed
    :entrypoint 'fixture/governed-authority
    :input-schema :fixture/governed-authority-input.v1
    :output-schema :fixture/governed-authority-output.v1
    :composition-contract {:composition-contract/version 1
                           :composition/input {:schema-ref :fixture/governed-authority-input.v1}
                           :composition/output {:schema-ref :fixture/governed-authority-output.v1}}}))

(deftest package-manifest-is-valid-and-identifies-three-separate-capabilities
  (let [validation (manifest/validate-package force/package)
        capabilities (:extension/capabilities force/package)]
    (is (:valid? validation) (pr-str (:violations validation)))
    (is (= #{[:prf/force-authorisation :force-authorisation/scope-verification]
             [:assurance/force-authorisation :force-authorisation/governed-permit-v1]
             [:sew/force-authorisation :force-authorisation/custody-execution-v1]}
           (set (map manifest/capability-key capabilities))))
    (is (every? symbol? (map :entrypoint capabilities)))
    (is (= (manifest/package-root force/package)
           (manifest/package-root force/package)))
    (is (not= (manifest/package-root force/package)
              (manifest/package-root (assoc force/package :extension/version "0.1.1"))))))

(deftest governed-permit-requires-a-matching-governed-authority-provider
  (let [schemas (schemas-for force/package governed-authority-package)
        absent (resolution/resolve-requested
                (registry/register-package (registry/empty-extension-map) force/package)
                [[:assurance/force-authorisation :force-authorisation/governed-permit-v1]]
                {:schemas schemas})
        present-map (-> (registry/empty-extension-map)
                        (registry/register-package governed-authority-package)
                        (registry/register-package force/package))
        present (resolution/resolve-requested present-map
                                              [[:assurance/force-authorisation :force-authorisation/governed-permit-v1]]
                                              {:schemas schemas})]
    (is (not (:valid? absent)))
    (is (some #(= :extensions/error-missing-dependency (:violation/id %))
              (:violations absent)))
    (is (:valid? present))))

(deftest custody-execution-requires-permit-and-held-custody-mutation
  (let [schemas (schemas-for force/package held/package governed-authority-package envelope-package)
        base (-> (registry/empty-extension-map)
                 (registry/register-package governed-authority-package)
                 (registry/register-package force/package))
        no-held (resolution/resolve-requested base
                                               [[:sew/force-authorisation :force-authorisation/custody-execution-v1]]
                                               {:schemas schemas})
        complete-map (-> (registry/empty-extension-map)
                         (registry/register-package core/core-economics-package)
                         (registry/register-package envelope-package)
                         (registry/register-package governed-authority-package)
                         (registry/register-package force/package)
                         (registry/register-package held/package))
        complete (resolution/resolve-requested complete-map
                                               [[:sew/force-authorisation :force-authorisation/custody-execution-v1]]
                                               {:schemas schemas})]
    (is (not (:valid? no-held)))
    (is (some #(and (= :extensions/error-missing-dependency (:violation/id %))
                    (= [:force-authorisation/effect-evidence :held-custody/mutation]
                       (get-in % [:details :capability])))
              (:violations no-held)))
    (is (:valid? complete) (pr-str (:violations complete)))
    (is (contains? (get-in complete [:resolution :extensions/capabilities])
                   [:prf/force-authorisation :force-authorisation/scope-verification]))
    (is (contains? (get-in complete [:resolution :extensions/capabilities])
                   [:force-authorisation/effect-evidence :held-custody/mutation]))))

(deftest held-custody-resolves-through-the-physical-scope-verifier
  (let [schemas (schemas-for force/package held/package envelope-package)
        extension-map (-> (registry/empty-extension-map)
                          (registry/register-package core/core-economics-package)
                          (registry/register-package envelope-package)
                          (registry/register-package force/package)
                          (registry/register-package held/package))
        result (resolution/resolve-requested
                extension-map
                [[:force-authorisation/effect-evidence :held-custody/mutation]]
                {:schemas schemas})]
    (is (:valid? result) (pr-str (:violations result)))
    (is (contains? (get-in result [:resolution :extensions/capabilities])
                   [:prf/force-authorisation :force-authorisation/scope-verification]))))
