(ns resolver-sim.trace.conformance.validators-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.conformance.validation :as validation]
            [resolver-sim.trace.conformance.validators :as validators]))

(def ^:private good-fixture
  {:cdrs_version "0.2"
   :schema_version "2"
   :scenario_id "sew-003"
   :description "d"
   :fee_bps 100
   :step_count 2
   :invariant_profile {:id "solidity-equivalence-core-v1" :version 1 :root "r"}
   :steps [{:seq 0 :event_type "CREATE ESCROW" :step_type "protocol-transition"
            :context_id "wf0" :actor "buyer" :timestamp 1
            :attributes {:action "create_escrow" :wf_alias "wf0"}
            :expected {:accepted true :escrow_state 1}}
           {:seq 1 :event_type "RELEASE" :step_type "protocol-transition"
            :context_id "wf0" :actor "buyer" :timestamp 2
            :attributes {:action "release" :wf_alias "wf0"}
            :expected {:accepted true :escrow_state 2}}]})

(deftest validators-registered-in-closed-registry
  (testing "trace validators resolve by id"
    (is (= :trace-fixture-v2-schema
           (:validator/id (validation/resolve-validator :trace-fixture-v2-schema))))
    (is (= :trace-fixture-v2-semantics
           (:validator/id (validation/resolve-validator :trace-fixture-v2-semantics))))
    (is (= :schema (:validator/kind (validation/resolve-validator :trace-fixture-v2-schema))))
    (is (= :semantic (:validator/kind (validation/resolve-validator :trace-fixture-v2-semantics))))))

(deftest valid-fixture-passes-both-layers
  (let [{:keys [valid? results]} (validators/validate-fixture good-fixture)]
    (is valid?)
    (is (= 2 (count results)))
    (doseq [res results]
      (is (= :pass (:validation/status res)))
      (is (= 1 (:validation/version res)))
      (is (string? (:validation/implementation-root res)))
      (is (string? (:validation/subject-root res))))))

(deftest rejected-fixture-fails-with-issues
  (let [{:keys [valid? results]} (validators/validate-fixture
                                  (assoc-in good-fixture [:steps 0 :attributes :action]
                                            "execute_resolution"))]
    (is (not valid?))
    (is (= :rejected (get-in results [1 :validation/status])))
    (is (some #(= :ambiguous-execute-resolution (:issue/code %))
              (get-in results [1 :validation/issues])))))

(deftest structural-layer-rejects-missing-cdrs
  (let [{:keys [results]} (validators/validate-fixture (dissoc good-fixture :cdrs_version))]
    (is (= :rejected (get-in results [0 :validation/status])))
    (is (some #(= :unsupported-cdrs-version (:issue/code %))
              (get-in results [0 :validation/issues])))))

(deftest structural-layer-rejects-non-integer-fee-bps
  (let [{:keys [results]} (validators/validate-fixture (assoc good-fixture :fee_bps "100"))]
    (is (= :rejected (get-in results [0 :validation/status])))
    (is (some #(= :fee-bps-not-integer (:issue/code %))
              (get-in results [0 :validation/issues])))))

(deftest structural-layer-rejects-negative-fee-bps
  (let [{:keys [results]} (validators/validate-fixture (assoc good-fixture :fee_bps -1))]
    (is (= :rejected (get-in results [0 :validation/status])))
    (is (some #(= :fee-bps-negative (:issue/code %))
              (get-in results [0 :validation/issues])))))

(deftest semantic-layer-rejects-undefined-alias
  (let [{:keys [results]} (validators/validate-fixture
                           (assoc-in good-fixture [:steps 1 :attributes :wf_alias] "wf9"))]
    (is (= :rejected (get-in results [1 :validation/status])))
    (is (some #(= :undefined-wf-alias (:issue/code %))
              (get-in results [1 :validation/issues])))))

(deftest semantic-layer-rejects-unknown-action-and-role
  (let [{:keys [results]} (validators/validate-fixture
                           (-> good-fixture
                               (assoc-in [:steps 1 :attributes :action] "teleport")
                               (assoc-in [:steps 1 :actor] "mystery")))
        semantic (nth results 1)
        codes (set (map :issue/code (:validation/issues semantic)))]
    (is (= :rejected (:validation/status semantic)))
    (is (contains? codes :unknown-action))
    (is (contains? codes :unknown-role))))
