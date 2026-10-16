(ns resolver-sim.deployment-package-set-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.edn :as edn]
            [resolver-sim.authority-state :as authority]
            [resolver-sim.genesis :as genesis]
            [scripts.deployment.genesis-roots :as deployment]))

(defn packages []
  (into {} (for [kind [:protocol-genesis :authority-state :initial-configuration]]
             [kind (edn/read-string
                    (slurp (str "etc/deployments/eae-controlled-entitlement-demo/"
                                (name kind) "-package.edn")))])))

(deftest candidate-package-set-derives-all-seven-roots
  (let [result (deployment/derive-candidate-package-set (packages))
        configuration (get result "initialConfiguration")]
    (is (= 8 (count configuration)))
    (is (= (get-in result ["roots" "initial-configuration"])
           (genesis/chain-configuration-root configuration)))
    (is (= (genesis/canonical-verifier-registry-root []) (:verifier-registry/root configuration)))
    (is (= 3 (count (get result "artifactDigests"))))
    (is (= "derived-only-not-approved" (get result "qualification")))))

(deftest independently-valid-packages-must-agree-on-protocol
  (let [all (packages)
        altered (-> (:authority-state all)
                    (dissoc :authority-state/root)
                    (assoc-in [:authority-state :protocol/genesis-root]
                              genesis/protocol-genesis-fixture-root))]
    (is (string? (get (deployment/derive-root :authority-state altered) "root")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"disagree on protocol"
                          (deployment/derive-candidate-package-set
                           (assoc all :authority-state altered)))))
  (is (thrown? clojure.lang.ExceptionInfo
               (deployment/derive-candidate-package-set (dissoc (packages) :authority-state)))))

(defn generic-packages []
  (let [legacy (packages)
        protocol-root (get-in legacy [:authority-state :authority-state :protocol/genesis-root])
        policy (-> (edn/read-string
                    (slurp "data/fixtures/authority/local-authority-policy.v2.edn"))
                   :policy/material
                   (assoc :protocol/genesis-root protocol-root))
        policy-root (authority/local-authority-policy-root policy)
        state {:authority-state/schema "authority-state.v1"
               :protocol/genesis-root protocol-root
               :execution/chain-id 31337
               :authority/policy-root policy-root
               :authority/revision 0
               :authority/previous-root nil
               :authority/decisions []}]
    {:protocol-genesis (:protocol-genesis legacy)
     :authority-state {:package/schema "prf-authority-state-package.v1"
                       :package/status :candidate-owner-approval-required
                       :authority-state state
                       :authority-state/root (authority/authority-state-root state)}
     :local-authority-policy {:package/schema "prf-local-authority-policy-package.v2"
                              :package/status :candidate-owner-approval-required
                              :local-authority-policy policy
                              :local-authority-policy/root policy-root}
     :initial-configuration (:initial-configuration legacy)}))

(deftest generic-candidate-package-set-is-distinct-from-the-historical-demo-path
  (let [candidate (generic-packages)
        derived (deployment/derive-generic-candidate-package-set candidate)]
    (is (= "prf-deployment-generic-candidate-package-set.v1" (get derived "schema")))
    (is (= "derived-only-not-approved" (get derived "qualification")))
    (is (= (authority/authority-state-root (:authority-state (:authority-state candidate)))
           (get-in derived ["roots" "authority-state"])))
    (is (= (authority/local-authority-policy-root (:local-authority-policy (:local-authority-policy candidate)))
           (get-in derived ["roots" "local-authority-policy"])))
    (is (= 4 (count (get derived "artifactDigests"))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (deployment/derive-candidate-package-set candidate)))))

(deftest generic-package-set-supports-explicit-policy-v3-without-reinterpreting-v2
  (let [candidate (generic-packages)
        policy (-> (:local-authority-policy (:local-authority-policy candidate))
                   (assoc :authority-policy/schema "local-authority-policy.v3"
                          :authority/transition-validator-address
                          "0xe05fcc23807536bee418f142d19fa0d21bb0cff7")
                   (dissoc :authority/transition-validator-public-key))
        policy-root (authority/local-authority-policy-root policy)
        state (-> (:authority-state (:authority-state candidate))
                  (assoc :authority/policy-root policy-root))
        v3 (assoc candidate
                  :authority-state {:package/schema "prf-authority-state-package.v1"
                                    :package/status :candidate-owner-approval-required
                                    :authority-state state
                                    :authority-state/root (authority/authority-state-root state)}
                  :local-authority-policy {:package/schema "prf-local-authority-policy-package.v3"
                                           :package/status :candidate-owner-approval-required
                                           :local-authority-policy policy
                                           :local-authority-policy/root policy-root})
        derived (deployment/derive-generic-candidate-package-set v3)]
    (is (= "derived-only-not-approved" (get derived "qualification")))
    (is (= policy-root (get-in derived ["roots" "local-authority-policy"])))
    (is (= "0xe05fcc23807536bee418f142d19fa0d21bb0cff7"
           (get derived "authorityTransitionValidatorAddress")))
    (is (thrown? clojure.lang.ExceptionInfo
                 (deployment/derive-generic-candidate-package-set
                  (assoc-in v3 [:local-authority-policy :package/schema]
                            "prf-local-authority-policy-package.v2"))))))
