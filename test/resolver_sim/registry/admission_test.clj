(ns resolver-sim.registry.admission-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.registry.admission :as admission]))

(def manifest
  {:manifest-root "sha256:manifest-1"
   :chain-id 8453})

(def candidate
  (admission/build-candidate
   {:deployment-manifest-root (:manifest-root manifest)
    :chain-id 8453
    :address "0x0000000000000000000000000000000000000001"
    :logical-id :sew/base-escrow
    :artifact-root "sha256:artifact-1"
    :runtime-code-hash "sha256:runtime-1"
    :local-only? false}))

(def scope
  {:scope/type :protocol-network
   :scope/value {:protocol :sew :network :base-mainnet}})

(def policy
  {:type :prf/registry-admission-policy.v1
   :registry/id :sew/official-deployments
   :rules [{:binding :escrow-entrypoint
            :allowed-scope-types [:protocol-network]
            :allowed-chain-ids [8453]
            :allowed-logical-ids [:sew/base-escrow]
            :allow-local-only? false}]})

(deftest candidate-root-is-derived
  (testing "candidate roots are stable and not caller supplied"
    (is (= (:candidate-root candidate)
           (admission/candidate-root (dissoc candidate :candidate-root))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (admission/verify-candidate
                  (assoc candidate :candidate-root "sha256:tampered")
                  manifest)))))

(deftest candidate-manifest-substitution-is-rejected
  (doseq [[candidate* manifest*] [[(assoc-in candidate [:contract :chain-id] 1) manifest]
                                  [candidate (assoc manifest :manifest-root "sha256:other")]
                                  [(assoc-in candidate [:contract :address] "0x0000000000000000000000000000000000000002") manifest]]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (admission/verify-candidate candidate* manifest*)))))

(deftest admission-is-derived
  (let [evaluation (admission/evaluate candidate :escrow-entrypoint scope policy manifest)]
    (is (= :admit (get-in evaluation [:result :decision])))
    (is (seq (get-in evaluation [:result :checks])))
    (is (empty? (get-in evaluation [:result :reasons])))
    (is (string? (admission/evaluation-root evaluation)))))

(deftest local-only-policy-is-explicit
  (let [local (assoc-in candidate [:properties :local-only?] true)
        evaluation (admission/evaluate local :escrow-entrypoint scope policy manifest)]
    (is (= :reject (get-in evaluation [:result :decision])))
    (is (some #{:local-only-not-permitted}
              (get-in evaluation [:result :reasons])))))

(deftest release-binds-admitted-evaluation
  (let [evaluation (admission/evaluate candidate :escrow-entrypoint scope policy manifest)
        evaluation-root (admission/evaluation-root evaluation)
        release {:type :prf/protocol-registry-release.v1
                 :registry/id :sew/official-deployments
                 :registry/parent-root nil
                 :admission-policy-root (admission/policy-root policy)
                 :bindings [{:binding-id :escrow-entrypoint
                             :scope-root (admission/scope-root scope)
                             :candidate-root (:candidate-root candidate)
                             :deployment-manifest-root (:manifest-root manifest)
                             :admission-evaluation-root evaluation-root}]}]
    (is (admission/validate-release release {evaluation-root evaluation}))
    (is (string? (admission/release-root release)))))

(deftest policy-binds-candidate-deployment-identity
  (let [wrong (assoc-in candidate [:deployment :logical-id] :sew/governance-token)
        evaluation (admission/evaluate wrong :escrow-entrypoint scope policy manifest)]
    (is (= :reject (get-in evaluation [:result :decision])))
    (is (some #{:logical-id-not-permitted}
              (get-in evaluation [:result :reasons])))))

(deftest release-binds-registry-and-parent-lineage
  (let [evaluation (admission/evaluate candidate :escrow-entrypoint scope policy manifest)
        root (admission/evaluation-root evaluation)
        base {:type :prf/protocol-registry-release.v1
              :registry/id :other/registry
              :registry/parent-root "not-a-root"
              :admission-policy-root (admission/policy-root policy)
              :bindings [{:binding-id :escrow-entrypoint
                          :scope-root (admission/scope-root scope)
                          :candidate-root (:candidate-root candidate)
                          :deployment-manifest-root (:manifest-root manifest)
                          :admission-evaluation-root root}]}]
    (is (thrown? clojure.lang.ExceptionInfo
                 (admission/validate-release base {root evaluation})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (admission/validate-release (assoc base :registry/parent-root nil) {root evaluation})))))

(deftest release-rejects-evaluation-substitution
  (let [evaluation (admission/evaluate candidate :escrow-entrypoint scope policy manifest)
        root (admission/evaluation-root evaluation)
        release {:type :prf/protocol-registry-release.v1
                 :registry/id :sew/official-deployments
                 :registry/parent-root nil
                 :admission-policy-root (admission/policy-root policy)
                 :bindings [{:binding-id :other-binding
                             :scope-root (admission/scope-root scope)
                             :candidate-root (:candidate-root candidate)
                             :deployment-manifest-root (:manifest-root manifest)
                             :admission-evaluation-root root}]}]
    (is (thrown? clojure.lang.ExceptionInfo
                 (admission/validate-release release {root evaluation})))))
