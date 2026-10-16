(ns resolver-sim.authority-state-test
  (:require [clojure.edn :as edn]
            [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.authority-state :as authority]
            [resolver-sim.genesis :as genesis]
            [scripts.deployment.genesis-roots :as deployment])
  (:import [java.security KeyPairGenerator]))

(def policy
  {:authority-policy/schema "local-authority-policy.v1"
   :protocol/genesis-root genesis/protocol-genesis-fixture-root
   :execution/chain-id 31337
   :authority/governor "0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266"
   :authority/capabilities (vec (sort authority/capabilities))})

(def initial
  {:authority-state/schema "authority-state.v1"
   :protocol/genesis-root genesis/protocol-genesis-fixture-root
   :execution/chain-id 31337
   :authority/policy-root (authority/local-authority-policy-root policy)
   :authority/revision 0
   :authority/previous-root nil
   :authority/decisions []})

(def decision
  {:decision/root genesis/chain-configuration-transition-direct-fixture-root
   :decision/revoked? false :decision/proof-mode 1})

(defn successor [state decisions]
  (assoc state :authority/revision (inc (:authority/revision state))
         :authority/previous-root (authority/authority-state-root state)
         :authority/decisions decisions))

(defn local-instance [state]
  {:genesis/schema "chain-instance-genesis.v1"
   :protocol/genesis-root genesis/protocol-genesis-fixture-root
   :execution/chain-id 31337 :settlement/chain-id 31337
   :control-plane {:address "0x1111111111111111111111111111111111111111"
                   :runtime-code-keccak256 (str "keccak256:" (apply str (repeat 64 "1")))}
   :governance {:authority-adapter "0x2222222222222222222222222222222222222222"
                :authority-adapter-code-keccak256 (str "keccak256:" (apply str (repeat 64 "2")))
                :initial-authority-state-root (authority/authority-state-root state)}
   :configuration/initial-root (str "sha256:" (apply str (repeat 64 "3")))})

(deftest canonical-state-fails-closed
  (is (:valid? (authority/validate-policy-binding initial policy)))
  (doseq [state [nil [] (assoc initial :extra true)
                 (dissoc initial :authority/previous-root)
                 (assoc initial :authority/revision -1)
                 (assoc initial :authority/revision 18446744073709551616N)
                 (assoc initial :authority/previous-root (:authority/policy-root initial))
                 (assoc initial :authority/decisions [decision])
                 (assoc initial :authority/decisions {:bad true})]]
    (is (false? (:valid? (authority/validate-authority-state state))))
    (is (thrown? clojure.lang.ExceptionInfo (authority/authority-state-root state))))
  (let [next-state (successor initial [decision])]
    (doseq [decisions [[decision decision]
                       [(assoc decision :extra true)]
                       [(assoc decision :decision/revoked? 1)]
                       [(assoc decision :decision/proof-mode 2)]]]
      (is (false? (:valid? (authority/validate-authority-state
                            (assoc next-state :authority/decisions decisions))))))))

(deftest policy-scope-and-capability-validation
  (doseq [bad [nil [] (assoc policy :extra true)
               (assoc policy :authority/capabilities [])
               (assoc policy :authority/capabilities ["unknown"])
               (assoc policy :authority/capabilities ["revoke-decision" "authorise-decision"])
               (assoc policy :authority/capabilities ["authorise-decision" "authorise-decision"])
               (assoc policy :authority/governor "0x0000000000000000000000000000000000000000")]]
    (is (false? (:valid? (authority/validate-local-authority-policy bad)))))
  (let [other (assoc policy :execution/chain-id 1)
        state (assoc initial :authority/policy-root (authority/local-authority-policy-root other))]
    (is (false? (:valid? (authority/validate-policy-binding initial other))))
    (is (false? (:valid? (authority/validate-policy-binding state other))))))

(deftest transition-lineage-and-decision-lifecycle
  (let [authorised (successor initial [decision])
        revoked (successor authorised [(assoc decision :decision/revoked? true)])]
    (is (:valid? (authority/validate-transition initial authorised policy policy)))
    (is (:valid? (authority/validate-transition authorised revoked policy policy)))
    (testing "replay, skipped revisions, deletion, resurrection and wrong predecessor fail"
      (doseq [[before after] [[initial initial]
                              [initial (assoc authorised :authority/revision 2)]
                              [initial (assoc authorised :authority/previous-root (:authority/policy-root initial))]
                              [authorised (successor authorised [])]
                              [revoked (successor revoked [decision])]
                              [initial (successor initial [(assoc decision :decision/revoked? true)])]]]
        (is (false? (:valid? (authority/validate-transition before after policy policy))))))))

(deftest current-policy-controls-transition
  (doseq [missing ["authorise-decision" "set-proof-mode" "update-authority-state"]]
    (let [restricted (update policy :authority/capabilities #(vec (remove #{missing} %)))
          before (assoc initial :authority/policy-root (authority/local-authority-policy-root restricted))]
      (is (false? (:valid? (authority/validate-transition
                            before (successor before [decision]) restricted restricted))))))
  (let [restricted (update policy :authority/capabilities #(vec (remove #{"revoke-decision"} %)))
        before (successor (assoc initial :authority/policy-root
                                 (authority/local-authority-policy-root restricted)) [decision])]
    (is (false? (:valid? (authority/validate-transition
                          before (successor before [(assoc decision :decision/revoked? true)])
                          restricted restricted)))))
  (testing "policy replacement cannot obtain permissions from its successor"
    (let [restricted (assoc policy :authority/capabilities ["authorise-decision"])
          before (assoc initial :authority/policy-root (authority/local-authority-policy-root restricted))
          after (assoc (successor before []) :authority/policy-root (authority/local-authority-policy-root policy))]
      (is (false? (:valid? (authority/validate-transition before after restricted policy)))))))

(deftest cli-preserves-derivation-only-qualification
  (let [policy-v2 (:policy/material
                   (edn/read-string (slurp "data/fixtures/authority/local-authority-policy.v2.edn")))]
    (doseq [[kind artifact schema] [[:generic-authority-state initial "authority-state.v1"]
                                    [:local-authority-policy policy "local-authority-policy.v1"]
                                    [:local-authority-policy policy-v2 "local-authority-policy.v2"]]]
      (let [result (deployment/derive-root kind artifact)]
        (is (= schema (get result "artifact")))
        (is (= "derived-only-not-approved" (get result "qualification")))))))

(deftest pinned-authority-vectors
  (let [fixture (edn/read-string
                 (slurp "data/fixtures/authority/canonical-authority-state.v1.edn"))
        policy-v2 (edn/read-string
                   (slurp "data/fixtures/authority/local-authority-policy.v2.edn"))]
    (is (= (:policy/root fixture) (authority/local-authority-policy-root (:policy fixture))))
    (is (= (:state/root fixture) (authority/authority-state-root (:state fixture))))
    (is (:valid? (authority/validate-policy-binding (:state fixture) (:policy fixture))))
    (is (= (:policy/root policy-v2)
           (authority/local-authority-policy-root (:policy/material policy-v2))))
    (is (:valid? (authority/validate-local-authority-policy (:policy/material policy-v2))))))

(deftest ecdsa-validator-policy-v3-has-distinct-canonical-identity
  (let [fixture (json/read-str (slurp "etc/conformance/fixtures/authority-transition-eip191.v1.json"))
        policy-v3 (assoc policy :authority-policy/schema "local-authority-policy.v3"
                         :authority/transition-validator-key-id (get fixture "validatorKeyId")
                         :authority/transition-validator-address (get fixture "validatorAddress"))]
    (is (:valid? (authority/validate-local-authority-policy policy-v3)))
    (is (= (get fixture "policyRoot") (authority/local-authority-policy-root policy-v3)))
    (is (not= (authority/local-authority-policy-root policy)
              (authority/local-authority-policy-root policy-v3)))
    (doseq [bad [(assoc policy-v3 :authority/transition-validator-address "0x0000000000000000000000000000000000000000")
                 (assoc policy-v3 :authority/transition-validator-address "0xE05fcc23807536bEe418f142D19fa0d21BB0cfF7")
                 (assoc policy-v3 :authority/transition-validator-key-id "")]]
      (is (false? (:valid? (authority/validate-local-authority-policy bad)))))))

(deftest policy-v3-eip191-transition-attestation-is-verified
  (let [fixture (json/read-str (slurp "etc/conformance/fixtures/authority-transition-eip191.v1.json"))
        key-id (get fixture "validatorKeyId")
        address (get fixture "validatorAddress")
        policy-v3 (assoc policy :authority-policy/schema "local-authority-policy.v3"
                         :authority/transition-validator-key-id key-id
                         :authority/transition-validator-address address)
        initial-v3 (assoc initial :authority/policy-root (authority/local-authority-policy-root policy-v3))
        instance (local-instance initial-v3)
        instance-root (genesis/chain-instance-genesis-root instance)
        transition (-> genesis/chain-configuration-transition-direct-fixture
                       (assoc :protocol/genesis-root (:protocol/genesis-root initial-v3))
                       (assoc-in [:target :target/root] instance-root))
        decision-root (genesis/chain-configuration-transition-root transition)
        next-state (successor initial-v3 [(assoc decision :decision/root decision-root)])
        preimages [{:decision/root decision-root :decision/transition transition
                    :decision/parent-verifier-registry-root
                    (str "sha256:" (apply str (repeat 64 "7")))}]
        unsigned (authority/authority-transition-envelope initial-v3 next-state policy-v3 policy-v3 instance preimages)
        signed (authority/attach-authority-transition-ecdsa-attestation unsigned policy-v3 (get fixture "signature"))
        trust-entry {:key/id key-id :key/evm-address address
                     :key/role authority/transition-validator-role :key/status :active}
        trust {:trusted-keys [trust-entry]}]
    (is (= (get fixture "commitment") (:authority-transition/execution-commitment unsigned)))
    (is (:valid? (authority/verify-authority-transition-attestation
                  signed initial-v3 next-state policy-v3 policy-v3 instance trust)))
    (is (false? (:valid? (authority/verify-authority-transition-attestation
                          (assoc-in signed [:signature :signature-bytes]
                                    "0x0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000001b")
                          initial-v3 next-state policy-v3 policy-v3 instance trust))))
    (is (false? (:valid? (authority/verify-authority-transition-attestation
                          signed initial-v3 next-state policy-v3 policy-v3 instance
                          (assoc-in trust [:trusted-keys 0 :key/evm-address]
                                    "0x0000000000000000000000000000000000000001")))))
    (doseq [entries [[trust-entry (assoc trust-entry :key/evm-address "0x0000000000000000000000000000000000000001")]
                     [(assoc trust-entry :key/evm-address "0x0000000000000000000000000000000000000001") trust-entry]]]
      (is (false? (:valid? (authority/verify-authority-transition-attestation
                            signed initial-v3 next-state policy-v3 policy-v3 instance
                            {:trusted-keys entries})))))))

(deftest proof-mode-assignment-is-final
  (let [before (successor initial [decision])]
    (doseq [mode [nil 0]]
      (is (false? (:valid? (authority/validate-transition
                            before (successor before [(assoc decision :decision/proof-mode mode)])
                            policy policy))))))
  (let [no-mode (assoc decision :decision/proof-mode nil :decision/revoked? true)
        before (successor initial [no-mode])]
    (is (false? (:valid? (authority/validate-transition
                          before (successor before [(assoc no-mode :decision/proof-mode 1)])
                          policy policy))))))

(deftest authority-transition-envelope-binds-preimages-mutations-and-instance
  (let [policy-v2 (assoc policy :authority-policy/schema "local-authority-policy.v2"
                         :authority/transition-validator-key-id "local-test-validator"
                         :authority/transition-validator-public-key (apply str (repeat 64 "f")))
        initial-v2 (assoc initial :authority/policy-root (authority/local-authority-policy-root policy-v2))
        instance (local-instance initial-v2)
        instance-root (genesis/chain-instance-genesis-root instance)
        transition (-> genesis/chain-configuration-transition-direct-fixture
                       (assoc :protocol/genesis-root (:protocol/genesis-root initial-v2))
                       (assoc-in [:target :target/root] instance-root))
        decision-root (genesis/chain-configuration-transition-root transition)
        accepted (assoc decision :decision/root decision-root)
        next-state (successor initial-v2 [accepted])
        preimages [{:decision/root decision-root :decision/transition transition
                    :decision/parent-verifier-registry-root
                    (str "sha256:" (apply str (repeat 64 "7")))}]
        envelope (authority/authority-transition-envelope initial-v2 next-state policy-v2 policy-v2 instance preimages)]
    (is (genesis/chain-instance-genesis-valid? instance))
    (is (:valid? (authority/validate-authority-transition-envelope
                  envelope initial-v2 next-state policy-v2 policy-v2 instance)))
    (testing "a forged decision-root preimage fails canonical recomputation"
      (let [forged (assoc-in envelope [:authority-transition/decision-preimages 0 :decision/transition
                                       :configuration/new-root]
                             (str "sha256:" (apply str (repeat 64 "f"))))]
        (is (false? (:valid? (authority/validate-authority-transition-envelope
                              forged initial-v2 next-state policy-v2 policy-v2 instance))))))
    (testing "a reused decision preimage cannot change the committed mutation payload"
      (let [forged (assoc-in envelope [:authority-transition/mutations :revoke-decision] [decision-root])]
        (is (false? (:valid? (authority/validate-authority-transition-envelope
                              forged initial-v2 next-state policy-v2 policy-v2 instance))))))
    (testing "transition identity remains bound to this genesis and adapter"
      (doseq [field [:authority-transition/instance-root :authority-transition/adapter-address]]
        (let [forged (assoc envelope field (if (= field :authority-transition/instance-root)
                                             genesis/chain-instance-genesis-ethereum-fixture-root
                                             "0x3333333333333333333333333333333333333333"))]
          (is (false? (:valid? (authority/validate-authority-transition-envelope
                                forged initial-v2 next-state policy-v2 policy-v2 instance)))))))
    (testing "v1 does not permit policy/governor replacement"
      (let [replacement (assoc policy-v2 :authority/governor "0x4444444444444444444444444444444444444444")]
        (is (thrown? clojure.lang.ExceptionInfo
                     (authority/authority-transition-envelope initial-v2 next-state policy-v2 replacement
                                                              instance preimages)))))))

(deftest transition-attestation-requires-the-policy-pinned-validator-key
  (let [pair (.generateKeyPair (KeyPairGenerator/getInstance "Ed25519"))
        encoded (.getEncoded (.getPublic pair))
        raw (java.util.Arrays/copyOfRange encoded (- (alength encoded) 32) (alength encoded))
        public-key (apply str (map #(format "%02x" (bit-and 0xff %)) raw))
        key-id "test-validator-key"
        policy-v2 (assoc policy :authority-policy/schema "local-authority-policy.v2"
                         :authority/transition-validator-key-id key-id
                         :authority/transition-validator-public-key public-key)
        initial-v2 (assoc initial :authority/policy-root (authority/local-authority-policy-root policy-v2))
        instance (local-instance initial-v2)
        instance-root (genesis/chain-instance-genesis-root instance)
        transition (-> genesis/chain-configuration-transition-direct-fixture
                       (assoc :protocol/genesis-root (:protocol/genesis-root initial-v2))
                       (assoc-in [:target :target/root] instance-root))
        decision-root (genesis/chain-configuration-transition-root transition)
        next-state (successor initial-v2 [(assoc decision :decision/root decision-root)])
        preimages [{:decision/root decision-root :decision/transition transition
                    :decision/parent-verifier-registry-root
                    (str "sha256:" (apply str (repeat 64 "7")))}]
        unsigned (authority/authority-transition-envelope initial-v2 next-state policy-v2 policy-v2 instance preimages)
        signed (authority/sign-authority-transition-envelope unsigned policy-v2 (.getPrivate pair))
        trust {:trusted-keys [{:key/id key-id :key/public public-key
                               :key/role authority/transition-validator-role :key/status :active}]}]
    (is (:valid? (authority/verify-authority-transition-attestation
                  signed initial-v2 next-state policy-v2 policy-v2 instance trust)))
    (is (false? (:valid? (authority/verify-authority-transition-attestation
                          (assoc-in signed [:authority-transition/mutations :revoke-decision] [decision-root])
                          initial-v2 next-state policy-v2 policy-v2 instance trust))))
    (is (false? (:valid? (authority/verify-authority-transition-attestation
                          signed initial-v2 next-state policy-v2 policy-v2 instance
                          (assoc-in trust [:trusted-keys 0 :key/public] (apply str (repeat 64 "0")))))))))
