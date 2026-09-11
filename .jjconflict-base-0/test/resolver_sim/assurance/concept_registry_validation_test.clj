(ns resolver-sim.assurance.concept-registry-validation-test
  "Validate concept registry closure for force-authorisation concepts.
   Verifies that the concept registry resolves the new concept file,
   contains no duplicate identifiers, and includes every concept
   referenced by the five generic claims."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.hash.reference :as hash-ref]))

(def concept-registry-path hash-ref/concept-registry-path)
(def force-auth-concept-path "data/concepts/security/force_authorisation.edn")
(def claims-path "data/claims/force_authorisation_claims.edn")

(defn- edn-read [path]
  (edn/read-string (slurp path)))

(defn- registry-ids []
  (map :concept/id (:concepts (edn-read concept-registry-path))))

(deftest force-authorisation-concept-file-is-registered
  (let [entry (first (filter #(= :security/force-authorisation (:concept/id %))
                             (:concepts (edn-read concept-registry-path))))]
    (is (some? entry) "Concept :security/force-authorisation must be in the registry")
    (is (= "data/concepts/security/force_authorisation.edn" (:concept/file entry))
        "Registry must point to the correct file")
    (is (= :security (:concept/type entry)) "Concept type must be :security")
    (is (= :stakeholder (:concept/layer entry)) "Concept layer must be :stakeholder")
    (is (= #{:protocol/sew-v1 :protocol/prf} (:concept/protocols entry))
        "Concept must span both :protocol/sew-v1 and :protocol/prf")))

(deftest force-authorisation-concept-file-exists-and-reads
  (is (.isFile (io/file force-auth-concept-path))
      "Force-authorisation concept file must exist")
  (let [concept (edn-read force-auth-concept-path)]
    (is (= :security/force-authorisation (:concept/id concept)) "Concept ID must match")
    (is (= :security (:concept/type concept)) "Concept type must match registry")
    (is (seq (:concept/vocabulary concept)) "Concept must have vocabulary entries")
    (is (seq (:concept/failure-modes concept)) "Concept must have failure modes")
    (is (seq (:concept/metrics concept)) "Concept must have metrics")
    (is (seq (:concept/outcomes concept)) "Concept must have outcomes")))

(deftest concept-registry-has-no-duplicate-identifiers
  (let [ids (registry-ids)]
    (is (= (count ids) (count (set ids)))
        "Registry must not contain duplicate concept identifiers")))

(deftest every-claim-namespace-has-matching-concept-entry
  (let [claims (edn-read claims-path)
        claim-namespaces (set (map namespace (keep :claim/id claims)))
        concept-names (set (map (comp name :concept/id) (:concepts (edn-read concept-registry-path))))]
    (testing "all claim namespaces have a corresponding concept entry"
      (doseq [ns claim-namespaces]
        (is (contains? concept-names ns)
            (str "Claim namespace " ns " must be registered as a concept name "
                 "(e.g. :security/" ns ") in the concept registry"))))))

(deftest claims-file-has-expected-structure
  (let [claims (edn-read claims-path)]
    (is (= 5 (count claims)) "Expected 5 generic force-authorisation claims")
    (doseq [c claims]
      (is (:claim/id c) (str "Claim must have :claim/id"))
      (is (:description c) (str "Claim " (:claim/id c) " must have :description"))
      (is (:assumptions c) (str "Claim " (:claim/id c) " must have :assumptions"))
      (is (:falsified-if c) (str "Claim " (:claim/id c) " must have :falsified-if"))
      (is (:validated-by c) (str "Claim " (:claim/id c) " must have :validated-by"))
      (is (every? #(clojure.string/includes? % "benchmarks/packs/prf-core/")
                  (:validated-by c))
          (str "Claim " (:claim/id c) " must reference prf-core path, not sew path"))
      (is (= :trace-bounded (:confidence c))
          (str "Claim " (:claim/id c) " must have :trace-bounded confidence")))))
