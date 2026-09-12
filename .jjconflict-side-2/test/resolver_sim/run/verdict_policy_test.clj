(ns resolver-sim.run.verdict-policy-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.run.verdict-policy :as sut]
            [resolver-sim.run.force-authorisation-policy :as fa-policy]))

(defn- predecessor []
  (sut/build-artifact
   {"schema_version" sut/schema-version
    "policy_id" "policy-v1"
    "run" {"id" "run-1" "type" "benchmark"}
    "verdict" {"semantic_outcome" "pass"}
    "registries" {"evidence_policy_hash" "sha256:evidence"}
    "evaluator_implementation" {"source_tree_hash" "sha256:source"
                                "source_tree_hash_algorithm" "sha256"}
    "semantic_environment" {"runner_id" "runner" "protocol_id" "protocol"}
    "distribution_provenance" {"mode" "source-classpath"}
    "immutable_inputs" [{"logical_id" "input-1"}]
    "version_id" "v1"
    "supersession_policy"
    {"authorization_required" "research-force-authorisation"
     "allowed_change_classes" ["verdict"]
     "force_authorisation_policy_id" "research-policy-update-v1"
     "force_authorisation_policy_hash" "sha256:policy"}}))

(deftest raw-approve-count-cannot-authorise-policy-supersession
  (testing "even two signed-looking approvals need the governed authority boundary"
    (let [output (java.io.File/createTempFile "verdict-policy-successor" ".json")
          _ (.delete output)
          instance {:authorisation/id :auth-1
                    :authorisation/decision-references
                    [{:researcher/id "r-a" :decision :approve
                      :decision/hash "sha256:decision-a"
                      :signature {:algorithm :ed25519 :value "genuine-looking-a"}}
                     {:researcher/id "r-b" :decision :approve
                      :decision/hash "sha256:decision-b"
                      :signature {:algorithm :ed25519 :value "genuine-looking-b"}}]}
          pred (assoc (predecessor) "supersession_policy"
                      {"authorization_required" "research-force-authorisation"
                       "allowed_change_classes" []
                       "force_authorisation_policy_id" "research-policy-update-v1"
                       "force_authorisation_policy_hash" "sha256:policy"})
          metadata {:version-id "v2"
                    :reason "governed update"
                    :authorization
                    {:instance instance
                     :fa-policy {"policy_sha256" "sha256:policy"}}}]
      (try
        (with-redefs [sut/verify-artifact (constantly {:valid? true})
                      sut/compute-change-classes (constantly [])
                      fa-policy/verify-artifact (constantly {:valid? true})]
          (let [error (try
                        (sut/supersede pred output
                                       {}
                                       metadata)
                        nil
                        (catch clojure.lang.ExceptionInfo e e))]
            (is error)
            (is (= :authority-material-unavailable
                   (:reason (ex-data error))))
            (is (not (.exists output))
                "the rejected raw-count path must not publish a successor")))
        (finally
          (.delete output))))))
