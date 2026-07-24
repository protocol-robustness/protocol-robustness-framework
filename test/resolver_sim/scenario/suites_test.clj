(ns resolver-sim.scenario.suites-test
  (:require [clojure.data.json]
            [clojure.edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.io.resource-path :as rp]
            [resolver-sim.protocols.registry :as preg]
            [resolver-sim.scenario.suites :as suites]
            [resolver-sim.io.scenario-runner :as sr]
            [resolver-sim.io.serialization :as ser]))

(def ^:private expected-suite-keys
  #{:yield-provider-scenarios
    :sew-yield-scenarios
    :dispute-resolution-scenarios
    :sew-reversal-slashing
    :sew-reference-v1})

(def ^:private expected-metadata-keys
  #{:protocol-id :title :description :kind :ci-tier})

(deftest registry-exposes-canonical-suite-keys
  (is (= expected-suite-keys (clojure.core/set (suites/known-suite-keys))))
  (is (= expected-suite-keys (clojure.core/set (keys (suites/known-suite-definitions))))))

(deftest registry-definitions-are-well-formed
  (doseq [suite-key (suites/known-suite-keys)]
    (testing (name suite-key)
      (let [definition (suites/suite-definition suite-key)
            metadata   (suites/suite-metadata suite-key)
            paths      (suites/suite-paths suite-key)]
        (is (map? definition))
        (is (= expected-metadata-keys (clojure.core/set (keys metadata)))
            (str "metadata keys: " (pr-str (keys metadata))))
        (is (= :file-path-suite (:kind definition)))
        (is (seq paths))
        (is (= (count paths) (suites/suite-path-count suite-key)))
        (is (every? #(some? (rp/resolve-path %)) paths)
            (str "missing paths: " (->> paths (remove #(some? (rp/resolve-path %))) vec pr-str)))
        (is (contains? (clojure.core/set (preg/known-protocol-ids)) (:protocol-id definition)))
        (is (str/ends-with? (:description metadata) ".")
            "descriptions should read like sentences")))))

(deftest yield-provider-suite-is-canonical-top-level
  (let [paths (suites/suite-paths :yield-provider-scenarios)]
    (is (= ["scenarios/edn/Y01_vault-shared-liquidity.edn"
            "scenarios/edn/Y02_vault-shortfall-partial-withdraw.edn"
            "scenarios/edn/Y03_vault-risk-override-schedule-shadowing.edn"
            "scenarios/edn/Y04_vault-recovery-claim-deferred.edn"
            "scenarios/edn/Y05_auto-generated-shortfall.edn"
            "scenarios/edn/Y06_multi-party-pro-rata-shortfall.edn"
            "scenarios/edn/Y07_adversarial-shortfall-exploit.edn"]
           paths))
    (is (every? #(str/starts-with? % "scenarios/edn/Y") paths))
    (is (not-any? #(str/includes? % "resource:scenarios/yield/") paths))
    (is (= "yield-v1" (suites/suite-protocol-id :yield-provider-scenarios)))))

(deftest machine-readable-suite-summary-can-be-parsed
  (let [summaries (sr/known-suite-summaries)
        edn-str (pr-str {:suites summaries})
        parsed (clojure.edn/read-string edn-str)]
    (is (map? parsed))
    (is (contains? parsed :suites))
    (is (vector? (:suites parsed)))))

(deftest machine-readable-json-format-can-be-parsed
  (let [summaries (sr/known-suite-summaries)
        json-str (ser/serialize-artifact {:suites summaries} {:pretty? true})
        parsed (clojure.data.json/read-str json-str)]
    (is (map? parsed))
    (is (contains? parsed "suites"))))
