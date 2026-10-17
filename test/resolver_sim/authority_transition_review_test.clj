(ns resolver-sim.authority-transition-review-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.authority-state :as authority]
            [resolver-sim.genesis :as genesis]
            [scripts.deployment.authority-transition-review :as review]))

(def ^:private fixture-policy
  {:authority-policy/schema "local-authority-policy.v1"
   :protocol/genesis-root genesis/protocol-genesis-fixture-root
   :execution/chain-id 31337
   :authority/governor "0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266"
   :authority/capabilities (vec (sort authority/capabilities))})

(defn- fixture-request-and-context []
  (let [fixture (json/read-str (slurp "etc/conformance/fixtures/authority-transition-eip191.v1.json"))
        policy (assoc fixture-policy :authority-policy/schema "local-authority-policy.v3"
                      :authority/transition-validator-key-id (get fixture "validatorKeyId")
                      :authority/transition-validator-address (get fixture "validatorAddress"))
        initial {:authority-state/schema "authority-state.v1"
                 :protocol/genesis-root genesis/protocol-genesis-fixture-root
                 :execution/chain-id 31337
                 :authority/policy-root (authority/local-authority-policy-root policy)
                 :authority/revision 0 :authority/previous-root nil :authority/decisions []}
        instance {:genesis/schema "chain-instance-genesis.v1"
                  :protocol/genesis-root genesis/protocol-genesis-fixture-root
                  :execution/chain-id 31337 :settlement/chain-id 31337
                  :control-plane {:address "0x1111111111111111111111111111111111111111"
                                  :runtime-code-keccak256 (str "keccak256:" (apply str (repeat 64 "1")))}
                  :governance {:authority-adapter "0x2222222222222222222222222222222222222222"
                               :authority-adapter-code-keccak256 (str "keccak256:" (apply str (repeat 64 "2")))
                               :initial-authority-state-root (authority/authority-state-root initial)}
                  :configuration/initial-root (str "sha256:" (apply str (repeat 64 "3")))}
        instance-root (genesis/chain-instance-genesis-root instance)
        transition (-> genesis/chain-configuration-transition-direct-fixture
                       (assoc :protocol/genesis-root (:protocol/genesis-root initial))
                       (assoc-in [:target :target/root] instance-root))
        decision-root (genesis/chain-configuration-transition-root transition)
        decision {:decision/root decision-root :decision/revoked? false :decision/proof-mode 1}
        successor (assoc initial :authority/revision 1
                         :authority/previous-root (authority/authority-state-root initial)
                         :authority/decisions [decision])
        parent-registry-root (str "sha256:" (apply str (repeat 64 "7")))
        preimages [{:decision/root decision-root :decision/transition transition
                    :decision/parent-verifier-registry-root parent-registry-root}]
        unsigned (authority/authority-transition-envelope initial successor policy policy instance preimages)
        envelope (authority/attach-authority-transition-ecdsa-attestation unsigned policy (get fixture "signature"))
        request {:request/schema "prf-authority-transition-request.v1"
                 :previous-state initial :successor-state successor
                 :current-policy policy :successor-policy policy :chain-instance instance
                 :envelope envelope :expected-configuration-transition transition
                 :expected-decision-root decision-root
                 :expected-parent-verifier-registry-root parent-registry-root}
        trust {:trusted-keys [{:key/id (:authority/transition-validator-key-id policy)
                               :key/evm-address (:authority/transition-validator-address policy)
                               :key/role authority/transition-validator-role :key/status :active}]}]
    [request trust]))

(deftest policy-v3-authority-transition-review-binds-c1-and-eip191-signature
  (let [[request trust] (fixture-request-and-context)
        result (review/verify-request request {:authority-transition-trust-policy trust})]
    (is (:valid? result) (pr-str result))
    (is (= :authenticated-authority-transition (:qualification result)))
    (is (= 2 (count (:mutations result))))
    (is (= (:authority-transition/execution-commitment (:envelope request))
           (get-in result [:scope :execution-commitment])))
    (is (= (get-in request [:envelope :signature :signature-bytes]) (:attestation result)))))

(deftest authority-transition-review-cli-returns-abi-ready-json
  (let [[request trust] (fixture-request-and-context)
        request-file (java.io.File/createTempFile "prf-authority-transition-request-" ".edn")
        context-file (java.io.File/createTempFile "prf-authority-transition-context-" ".edn")
        output (try
                 (spit request-file (pr-str request))
                 (spit context-file (pr-str {:authority-transition-trust-policy trust}))
                 (with-out-str (review/-main "verify-json" (.getAbsolutePath request-file) (.getAbsolutePath context-file)))
                 (finally (.delete request-file) (.delete context-file)))
        parsed (json/read-str output :key-fn keyword)]
    (is (true? (:valid parsed)))
    (is (= "authenticated-authority-transition" (:qualification parsed)))
    (is (re-matches #"0x[0-9a-f]{64}" (:decision-preimages-root parsed)))
    (is (every? #(re-matches #"0x[0-9a-f]{64}" (:decisionRoot %)) (:mutations parsed)))))

(deftest policy-v3-authority-transition-review-rejects-substitution
  (let [[request trust] (fixture-request-and-context)
        context {:authority-transition-trust-policy trust}]
    (testing "wrong successor decision root fails"
      (let [result (review/verify-request (assoc request :expected-decision-root
                                                 (str "sha256:" (apply str (repeat 64 "9")))) context)]
        (is (false? (:valid? result)))))
    (testing "wrong expected C1 preimage fails"
      (let [bad (assoc-in request [:expected-configuration-transition :epoch] 9)
            result (review/verify-request bad context)]
        (is (false? (:valid? result)))))
    (testing "wrong EVM attestor fails"
      (let [bad (assoc-in request [:envelope :signature :signature-bytes]
                          (str "0x" (apply str (repeat 130 "0"))))
            result (review/verify-request bad context)]
        (is (false? (:valid? result)))))))
