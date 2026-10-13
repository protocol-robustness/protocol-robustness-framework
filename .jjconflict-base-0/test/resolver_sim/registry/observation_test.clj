(ns resolver-sim.registry.observation-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.registry.admission :as admission]
            [resolver-sim.registry.observation :as observation]))

(def manifest {:manifest-root "sha256:manifest-1" :chain-id 8453})
(def candidate
  (admission/build-candidate
   {:deployment-manifest-root (:manifest-root manifest)
    :chain-id 8453
    :address "0x0000000000000000000000000000000000000001"
    :logical-id :sew/base-escrow
    :artifact-root "sha256:artifact-1"
    :runtime-code-hash "sha256:runtime-1"
    :local-only? false}))
(def scope-root "1111111111111111111111111111111111111111111111111111111111111111")
(def release
  {:type :prf/protocol-registry-release.v1
   :registry/id :sew/official-deployments
   :registry/parent-root nil
   :admission-policy-root "2222222222222222222222222222222222222222222222222222222222222222"
   :bindings [{:binding-id :escrow-entrypoint
               :scope-root scope-root
               :candidate-root (:candidate-root candidate)
               :deployment-manifest-root (:manifest-root manifest)
               :admission-evaluation-root "3333333333333333333333333333333333333333333333333333333333333333"}]})
(def block {:chain-id 8453
            :block-number 100
            :block-hash "4444444444444444444444444444444444444444444444444444444444444444"})

(defn make-observation []
  (observation/build-observation
   {:release release
    :release-root "5555555555555555555555555555555555555555555555555555555555555555"
    :binding-id :escrow-entrypoint
    :scope-root scope-root
    :candidates {(:candidate-root candidate) candidate}
    :chain block
    :projection-definition-root "6666666666666666666666666666666666666666666666666666666666666666"
    :result {:guardian "0x0000000000000000000000000000000000000002"}}))

(deftest observation-resolves-candidate-through-release
  (let [value (make-observation)]
    (is (= (:candidate-root candidate)
           (get-in value [:registry :candidate-root])))
    (is (= :sew/official-deployments (get-in value [:registry :registry/id])))
    (is (observation/validate-observation value))))

(deftest observation-rejects-chain-substitution
  (is (thrown? clojure.lang.ExceptionInfo
               (observation/build-observation
                {:release release
                 :release-root "5555555555555555555555555555555555555555555555555555555555555555"
                 :binding-id :escrow-entrypoint
                 :scope-root scope-root
                 :candidates {(:candidate-root candidate) candidate}
                 :chain (assoc block :chain-id 1)
                 :projection-definition-root "6666666666666666666666666666666666666666666666666666666666666666"
                 :result true}))))

(deftest observation-rejects-binding-substitution
  (is (thrown? clojure.lang.ExceptionInfo
               (observation/build-observation
                {:release release
                 :release-root "5555555555555555555555555555555555555555555555555555555555555555"
                 :binding-id :other-binding
                 :scope-root scope-root
                 :candidates {(:candidate-root candidate) candidate}
                 :chain block
                 :projection-definition-root "6666666666666666666666666666666666666666666666666666666666666666"
                 :result true}))))

(deftest observation-root-and-result-root-are-derived
  (let [value (make-observation)
        changed (assoc-in value [:result :value :guardian]
                          "0x0000000000000000000000000000000000000003")
        tampered (assoc-in value [:result :result-root]
                           "7777777777777777777777777777777777777777777777777777777777777777")]
    (is (not= (:observation-root value) (observation/observation-root changed)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (observation/validate-observation tampered)))))
