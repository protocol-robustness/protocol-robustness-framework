(ns prf.extensions.held-custody.historical-read-conformance-test
  "Cross-checks core classification tables against the extension-owned
   historical-read contract.

   After Phase 3B the held-custody vocabulary's semantic source of truth is the
   extension package manifest (:extension/historical-read). Core keeps only
   MINIMAL classification tables (sensitivity sentinel, canonical reconciliation,
   neutral artifact hashing) so it can recognize historical artifacts before
   extension dispatch. This test prevents those core tables from independently
   drifting: every historical artifact class core classifies must be declared
   in the extension contract, and the declaration itself must be well-formed,
   registry-validated, read-only, and forbid historical production."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.extensions.manifest :as em]
            [resolver-sim.extensions.registry :as reg]
            [resolver-sim.sensitivity.sentinel :as sentinel]
            [resolver-sim.assurance.canonical-force-authorisation :as cfa]
            [prf.extensions.held-custody.manifest :as manifest]))

(def hr
  "The extension's machine-readable historical-read contract."
  (:extension/historical-read manifest/package))

(def historical-kinds
  "Artifact kinds declared as historical read by the extension."
  (set (map :artifact/kind (:historical-read hr))))

(def historical-schema-prefixes
  "Schema-version prefixes (before the first dot) declared as historical read."
  (set (map #(-> % :schema-version (str/split #"\.") first) (:historical-read hr))))

(deftest manifest-declares-historical-read-contract
  (testing "current production is the held-custody mutation capability"
    (is (= :held-custody/mutation (get-in hr [:current-production :capability/id])))
    (is (string? (get-in hr [:current-production :schema-version]))))
  (testing "historical read entries are explicitly read-only"
    (is (seq (:historical-read hr)))
    (is (every? #(true? (:read-only %)) (:historical-read hr)))
    (is (every? #(string? (:schema-version %)) (:historical-read hr)))
    (is (every? #(keyword? (:artifact/kind %)) (:historical-read hr))))
  (testing "historical production is forbidden"
    (is (= :forbidden (:historical-production hr)))))

(deftest manifest-registers-and-validates
  (testing "the package and its historical-read declaration validate"
    (is (:valid? (em/validate-package manifest/package)))
    (is (:valid? (em/validate-historical-read hr))))
  (testing "the package registers cleanly into the pure extension map"
    (let [m (reg/register-package (reg/empty-extension-map) manifest/package)]
      (is (contains? m [:force-authorisation/effect-evidence :held-custody/mutation])))))

(deftest sentinel-classification-conforms-to-historical-read
  (testing "every historical held-custody kind the sentinel classifies as
            remote-authority-required is declared in the extension contract"
    (let [sentinel-historical
          (set/intersection sentinel/remote-authority-required-artifact-kinds
                            #{:force-auth-add-held
                              :force-auth-add-held-summary})]
      (is (set/subset? sentinel-historical historical-kinds)
          (str "sentinel historical kinds outside the extension contract: "
               (set/difference sentinel-historical historical-kinds)))))
  (testing "the sentinel reason table mirrors exactly the historical kinds"
    (is (= #{:force-auth-add-held :force-auth-add-held-summary}
           (set (keys sentinel/force-auth-artifact-kind->reason))))
    (is (set/subset? (set (keys sentinel/force-auth-artifact-kind->reason))
                     historical-kinds)))
  (testing "the held add-held action remains remote-authority-required"
    (is (contains? sentinel/remote-authority-required-held-actions :add-held))))

(deftest canonical-classification-conforms-to-historical-read
  (testing "every held-custody schema prefix the extension declares as historical
            read is classified as the legacy-evidence world by canonical
            reconciliation (the extension contract is the source of truth; core
            recognizes at least those classes)"
    (let [missing (remove (set cfa/legacy-evidence-prefixes)
                          historical-schema-prefixes)]
      (is (empty? missing)
          (str "extension historical-read prefixes canonical does not classify "
               "as legacy-evidence: " missing)))
    (is (set/subset? historical-schema-prefixes
                     (set cfa/legacy-evidence-prefixes)))))
