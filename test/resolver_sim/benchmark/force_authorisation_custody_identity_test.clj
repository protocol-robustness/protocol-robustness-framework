(ns resolver-sim.benchmark.force-authorisation-custody-identity-test
  "Canonical benchmark identity regression tests.
   Verifies that every persisted definition and registry artifact uses the
   PRF-owned benchmark identity, not the legacy SEW-pack identity."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]))

(def canonical-benchmark-path
  "benchmarks/packs/prf-core/force-authorisation-custody-v1.edn")

(def sew-alias-path
  "benchmarks/packs/sew/force-authorisation-custody-v1.edn")

(def sew-registry-path
  "benchmarks/packs/sew/registry.edn")

(defn- edn-read [path]
  (edn/read-string (slurp path)))

(def prf-registry-path
  "benchmarks/packs/prf-core/registry.edn")

(def sew-registry-path
  "benchmarks/packs/sew/registry.edn")

(defn- edn-read [path]
  (edn/read-string (slurp path)))

(deftest canonical-benchmark-uses-prf-identity
  (let [defn (edn-read canonical-benchmark-path)]
    (is (= :benchmark/force-authorisation-custody-v1 (:benchmark/id defn))
        "Canonical ID must be :benchmark/force-authorisation-custody-v1")
    (is (= :protocol/sew (:benchmark/protocol defn))
        "Protocol disclosure must be :protocol/sew")
    (is (= {:provider/id :protocol/sew
            :suite/id :suite/sew-force-authorisation-custody-v1}
           (:benchmark/suite-provider defn))
        "Suite-provider must identify Sew as the implementation supplier")
    (is (some? (:benchmark/capability defn))
        "Capability must be present")
    (is (some? (:benchmark/subject-kind defn))
        "Subject-kind must be present")))

(deftest sew-alias-resolves-to-canonical
  (let [alias-defn (edn-read sew-alias-path)]
    (is (= :benchmark/sew-force-authorisation-custody-v1 (:benchmark/id alias-defn))
        "Alias ID must match old identifier")
    (is (= :benchmark/force-authorisation-custody-v1 (:benchmark/alias-of alias-defn))
        "Alias must resolve to canonical PRF ID")
    (is (= :alias (:benchmark/status alias-defn))
        "Alias status must be :alias")))

(deftest registry-has-no-duplicate-canonical
  (let [prf-reg (edn-read prf-registry-path)]
    (is (some #(= :benchmark/force-authorisation-custody-v1 (:benchmark/id %))
              (:benchmarks prf-reg))
        "PRF-core registry must contain the canonical entry")
    (is (not-any? #(= :benchmark/force-authorisation-custody-v1 (:benchmark/id %))
                  (:benchmarks (edn-read sew-registry-path)))
        "Sew registry must NOT contain a duplicate canonical entry")))

(deftest canonical-definition-source-is-prf-core
  (let [prf-reg (edn-read prf-registry-path)
        entry (first (filter #(= :benchmark/force-authorisation-custody-v1 (:benchmark/id %))
                             (:benchmarks prf-reg)))]
    (is (= "force-authorisation-custody-v1.edn" (:benchmark/file entry))
        "Registry must point to the prf-core file")
    (is (= :experimental (:benchmark/status entry))
        "Status must match canonical definition")))

(deftest sew-pack-has-no-force-authorisation-benchmark
  (let [sew-ids (set (map :benchmark/id (:benchmarks (edn-read sew-registry-path))))]
    (is (not (contains? sew-ids :benchmark/force-authorisation-custody-v1))
        "Sew registry must not contain the canonical ID")
    (is (not (contains? sew-ids :benchmark/sew-force-authorisation-custody-v1))
        "Sew registry must not contain the legacy alias ID either")))

(deftest canonical-suite-is-prf-owned
  (let [defn (edn-read canonical-benchmark-path)]
    (is (= :suite/force-authorisation-custody-v1 (:benchmark/scenario-suite defn))
        "The canonical suite key must be :suite/force-authorisation-custody-v1")
    (is (= {:provider/id :protocol/sew
            :suite/id :suite/sew-force-authorisation-custody-v1}
           (:benchmark/suite-provider defn))
        "The suite-provider must reference the Sew-specific implementation suite")))

(deftest generic-claims-exist
  (let [claims (edn/read-string (slurp "data/claims/force_authorisation_claims.edn"))]
    (is (= 5 (count claims)) "Expected 5 generic force-authorisation claims")
    (doseq [claim-id [:force-authorisation/scope-enforced
                      :force-authorisation/single-use
                      :force-authorisation/expiry-enforced
                      :force-authorisation/evidence-linkage
                      :force-authorisation/custody-isolation]]
      (is (some #(= claim-id (:claim/id %)) claims)
          (str "Claim " claim-id " must be present in generic claims file")))))
