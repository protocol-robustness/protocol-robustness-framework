(ns resolver-sim.run.verdict-policy
  "Canonical, self-validating verdict-policy closure for reviewable run packages."
  (:require [clojure.java.io :as io]
            [resolver-sim.commands.run-lifecycle :as lifecycle]
            [resolver-sim.hash.canonical :as canonical]))

(def schema-version "verdict-policy.v1")
(def domain "PRF_VERDICT_POLICY_V1")

(defn sha-ref [file]
  (str "sha256:" (lifecycle/sha256-file file)))

(defn policy-hash [artifact]
  (str "sha256:" (canonical/domain-hash domain (dissoc artifact "policy_sha256"))))

(defn build
  [{:keys [run-id run-type policy-id semantic-outcome inputs registries semantic-environment evaluator-implementation distribution-provenance]}]
  (let [artifact {"schema_version" schema-version
                  "policy_id" policy-id
                  "run" {"id" run-id "type" run-type}
                  "verdict" {"semantic_outcome" semantic-outcome
                             "mapping" {"pass" "pass" "fail" "fail"}}
                  "registries" registries
                                    "evaluator_implementation" evaluator-implementation
                                                      "distribution_provenance" distribution-provenance
                                                      "immutable_inputs" (vec (sort-by #(get % "logical_id") inputs))
                  "semantic_environment" semantic-environment}]
    (assoc artifact "policy_sha256" (policy-hash artifact))))

(defn write! [file artifact]
  (lifecycle/atomic-json! file artifact)
  artifact)

(defn- contained-file [root path]
  (let [root-path (.toAbsolutePath (.normalize (.toPath (io/file root))))
        file (.toAbsolutePath (.normalize (.toPath (io/file root path))))]
    (when (.startsWith file root-path) (.toFile file))))

(defn verify!
  "Validate the artifact's schema, self-commitment, and every package-local input.
   The caller supplies the expected run identity because completion is authoritative."
  [root artifact expected-run-type expected-run-id]
  (let [inputs (get artifact "immutable_inputs")
        input-valid? (and (vector? inputs)
                          (every? (fn [entry]
                                    (let [path (get entry "path")
                                          file (and (string? path) (contained-file root path))]
                                      (and file (.isFile file)
                                           (= (get entry "sha256") (sha-ref file)))))
                                  inputs))
        env (get artifact "semantic_environment")
        registries (get artifact "registries")
                distribution (get artifact "distribution_provenance")]
    {:valid? (and (= schema-version (get artifact "schema_version"))
                  (= expected-run-id (get-in artifact ["run" "id"]))
                  (= expected-run-type (get-in artifact ["run" "type"]))
                  (= (get artifact "policy_sha256") (policy-hash artifact))
                  input-valid?
                  (map? registries)
                                    (map? (get artifact "evaluator_implementation"))
                                    (string? (get-in artifact ["evaluator_implementation" "source_tree_hash"]))
                                    (string? (get-in artifact ["evaluator_implementation" "source_tree_hash_algorithm"]))
                                    (string? (get registries "evidence_policy_hash"))
                  (map? env)
                  (string? (get env "runner_id"))
                  (string? (get env "protocol_id"))
                  (map? distribution)
                  (contains? #{"release-distribution" "unverified-distribution" "source-classpath"}
                             (get distribution "mode")))
     :inputs-valid? input-valid?}))
