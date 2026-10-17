(ns resolver-sim.genesis-seal-gate-test
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.deployment-package-set-test :as package-fixtures]
            [resolver-sim.deployment.source-identity-fixture :as source-fixture]
            [resolver-sim.genesis :as genesis]
            [scripts.deployment.configuration-review :as review]
            [scripts.deployment.genesis-roots :as roots]
            [scripts.deployment.genesis-seal-gate :as gate])
  (:import [java.security KeyPairGenerator]))

(def ^:private principal "0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266")

(defn- release-lock-info []
  ;; The seal gate consumes this matching test lock; the checked-in release
  ;; baseline describes a different immutable source tree and must fail here.
  (source-fixture/create-lock-fixture))

(defn- signed-approval [manifest]
  (let [pair (.generateKeyPair (KeyPairGenerator/getInstance "Ed25519"))
        encoded (.getEncoded (.getPublic pair))
        raw (java.util.Arrays/copyOfRange encoded (- (alength encoded) 32) (alength encoded))
        public-hex (apply str (map #(format "%02x" (bit-and 0xff %)) raw))
        key-id :gate-test-key
        trust {:trusted-keys [{:key/id key-id :key/public public-hex
                               :key/role review/approval-role :key/status :active
                               :approval/principal principal}]}]
    {:envelope (review/approval-envelope manifest "approve-initial-inputs" (.getPrivate pair) key-id)
     :trust trust}))

(defn- bounded-candidate-packages []
  (edn/read-string
   (slurp "etc/deployments/bounded-configuration-local-candidate-v2/candidate-package-set.edn")))

(defn- fixture-observation [packages]
  (let [derived (roots/derive-generic-candidate-package-set packages)
        roots (get derived "roots")
        protocol (get roots "protocol-genesis")
        authority (get roots "authority-state")]
    {:execution/chain-id 31337
     :settlement/chain-id 31337
     :genesis/sealer-address "0x3333333333333333333333333333333333333333"
     :genesis/address "0x1111111111111111111111111111111111111111"
     :genesis/runtime-code-keccak256 (str "keccak256:" (apply str (repeat 64 "1")))
     :genesis/execution-chain-id 31337
     :genesis/settlement-chain-id 31337
     :genesis/authority-adapter "0x2222222222222222222222222222222222222222"
     :genesis/protocol-genesis-root protocol
     :genesis/initial-authority-state-root authority
     :genesis/initial-configuration-root (get roots "initial-configuration")
     :genesis/stored-runtime-code-keccak256 (str "keccak256:" (apply str (repeat 64 "0")))
     :genesis/stored-authority-adapter-runtime-code-keccak256 (str "keccak256:" (apply str (repeat 64 "0")))
     :genesis/sealed? false
     :genesis/chain-instance-genesis-root (str "sha256:" (apply str (repeat 64 "0")))
     :authority/address "0x2222222222222222222222222222222222222222"
     :authority/runtime-code-keccak256 (str "keccak256:" (apply str (repeat 64 "2")))
     :authority/protocol-genesis-root protocol
     :authority/state-root authority
     :authority/constructor-protocol-genesis-root protocol
     :authority/constructor-initial-state-root authority
     :observation/block-number 81
     :observation/block-hash (str "keccak256:" (apply str (repeat 64 "3")))}))

(deftest independently-derived-seal-gate-binds-approved-inputs-and-readbacks
  (let [packages (package-fixtures/generic-packages)
        {:keys [source-revision source-tree-sha256 source-root source-lock-path]} (release-lock-info)
        manifest (review/review-manifest-v3 packages source-revision source-tree-sha256 principal)
        {:keys [envelope trust]} (signed-approval manifest)
        observation (fixture-observation packages)
        preseal-observation (-> observation
                                (assoc :observation/block-number 82)
                                (assoc :observation/block-hash (str "keccak256:" (apply str (repeat 64 "4")))))
        artifact (gate/observed-chain-instance-genesis packages preseal-observation)
        root (genesis/chain-instance-genesis-root artifact)
        call {:from (:genesis/sealer-address preseal-observation)
              :target (:genesis/address preseal-observation)
              :function "sealGenesis"
              :args [(subs root 7)
                     (subs (:genesis/runtime-code-keccak256 preseal-observation) 10)
                     (subs (:authority/runtime-code-keccak256 preseal-observation) 10)]}
        request {:candidate-packages packages :manifest manifest :approval-envelope envelope
                 :trusted-context {:source-revision source-revision
                                   :source-tree-sha256 source-tree-sha256
                                   :source-root source-root
                                   :source-lock-path source-lock-path
                                   :principal principal :trust-policy trust :action "approve-initial-inputs"
                                   :expected-genesis-runtime-code-keccak256 (:genesis/runtime-code-keccak256 observation)
                                   :expected-authority-runtime-code-keccak256 (:authority/runtime-code-keccak256 observation)}
                 :observation observation :preseal-observation preseal-observation :seal-call call}
        result (gate/verify-seal-preflight request)]
    (is (= root (:computed-root result)))
    (is (= artifact (:artifact result)))
    (is (= source-tree-sha256 (:source-tree-sha256 result)))
    (is (= {:observation observation :preseal-observation preseal-observation}
           (edn/read-string (:observations-edn result))))
    (is (= call (:seal-call (gate/verify-seal-preflight (dissoc request :seal-call)))))
    (let [request-file (java.io.File/createTempFile "prf-seal-request-" ".edn")
          context-file (java.io.File/createTempFile "prf-seal-context-" ".edn")
          request-for-cli (dissoc request :trusted-context)
          context (:trusted-context request)
          output (try
                   (spit request-file (pr-str request-for-cli))
                   (spit context-file (pr-str context))
                   (with-out-str (gate/-main (.getAbsolutePath request-file)
                                             (.getAbsolutePath context-file) "--json"))
                   (finally
                     (.delete request-file)
                     (.delete context-file)))
          parsed (json/read-str output :key-fn keyword)]
      (is (= "prf-chain-instance-seal-preflight.v2" (:schema parsed)))
      (is (= root (:computed-root parsed)))
      (is (= (:manifest/canonical-roots manifest)
             (into {} (map (fn [[key value]] [(name key) value])) (:canonical-roots parsed))))
      (is (= (into {} (map (fn [[key value]] [(str (namespace key) "/" (name key)) value]))
                   (:manifest/initial-configuration manifest))
             (into {} (map (fn [[key value]] [(str (namespace key) "/" (name key)) value]))
                   (:initial-configuration parsed))))
      (is (string? (:observations-edn parsed))))
    (is (= {:initial {:number 81 :hash (:observation/block-hash observation)}
            :preseal {:number 82 :hash (:observation/block-hash preseal-observation)}}
           (:observation-blocks result)))
    (testing "a source-tree substitution stops preflight"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not match independently trusted release identity"
                            (gate/verify-seal-preflight
                             (assoc-in request [:trusted-context :source-tree-sha256]
                                       (apply str (repeat 64 "f")))))))
    (testing "an altered transaction argument cannot be sealed"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"planned seal transaction differs"
                            (gate/verify-seal-preflight (assoc request :seal-call (update call :args assoc 0 (apply str (repeat 64 "0"))))))))
    (testing "changed live authority state stops preflight"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"live constructor/readback values differ"
                            (gate/verify-seal-preflight (assoc request :observation (assoc observation :authority/state-root (str "sha256:" (apply str (repeat 64 "f")))))))))
    (testing "state drift in the immediately-before-seal readback stops preflight"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"genesis must remain unsealed"
                            (gate/verify-seal-preflight
                             (assoc request :preseal-observation
                                    (assoc preseal-observation :genesis/sealed? true))))))
    (testing "different block hashes at the same height stop preflight"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"observation order is invalid"
                            (gate/verify-seal-preflight
                             (assoc request :preseal-observation
                                    (assoc preseal-observation
                                           :observation/block-number (:observation/block-number observation)
                                           :observation/block-hash (str "keccak256:" (apply str (repeat 64 "4")))))))))
    (testing "missing authenticated owner approval stops preflight"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"authenticated initial-input approval"
                            (gate/verify-seal-preflight (assoc request :approval-envelope (dissoc envelope :signature))))))
    (testing "unapproved runtime substitution stops preflight"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not match independently pinned"
                            (gate/verify-seal-preflight
                             (assoc-in request [:trusted-context :expected-authority-runtime-code-keccak256]
                                       (str "keccak256:" (apply str (repeat 64 "f"))))))))
    (let [receipt-hash (str "keccak256:" (apply str (repeat 64 "5")))
          sealed-observation (-> preseal-observation
                                 (assoc :observation/block-number 83)
                                 (assoc :observation/block-hash receipt-hash)
                                 (assoc :genesis/sealed? true)
                                 (assoc :genesis/sealer-address "0x0000000000000000000000000000000000000000")
                                 (assoc :genesis/stored-runtime-code-keccak256 (:genesis/runtime-code-keccak256 observation))
                                 (assoc :genesis/stored-authority-adapter-runtime-code-keccak256 (:authority/runtime-code-keccak256 observation))
                                 (assoc :genesis/chain-instance-genesis-root root))
          receipt {:receipt/status :success
                   :receipt/transaction-hash (str "0x" (apply str (repeat 64 "6")))
                   :receipt/block-number 83 :receipt/block-hash receipt-hash}
          transaction (merge call {:transaction/hash (:receipt/transaction-hash receipt)
                                   :transaction/block-number 83 :transaction/block-hash receipt-hash})
          sealed (gate/verify-seal-result {:preflight result :receipt receipt
                                           :transaction transaction :sealed-observation sealed-observation})]
      (is (= root (:chain-instance-genesis-root sealed)))
      (is (= "seal-receipt-and-readback-verified" (:qualification sealed)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not mined after the retained preseal observation"
                            (gate/verify-seal-result
                             {:preflight result :receipt (assoc receipt :receipt/block-number 82)
                              :transaction (assoc transaction :transaction/block-number 82)
                              :sealed-observation (assoc sealed-observation :observation/block-number 82)})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not contain the independently derived root"
                            (gate/verify-seal-result {:preflight result :receipt receipt :transaction transaction
                                                      :sealed-observation (assoc sealed-observation
                                                                                 :genesis/chain-instance-genesis-root
                                                                                 (str "sha256:" (apply str (repeat 64 "f"))))}))))))

(deftest bounded-policy-v3-candidate-recomputes-through-the-seal-gate
  ;; This ephemeral signature exercises the gate only. It is not retained as
  ;; owner approval and cannot authorize deployment.
  (let [packages (bounded-candidate-packages)
        {:keys [source-revision source-tree-sha256 source-root source-lock-path]} (release-lock-info)
        manifest (review/review-manifest-v3 packages source-revision source-tree-sha256 principal)
        {:keys [envelope trust]} (signed-approval manifest)
        observation (fixture-observation packages)
        preseal-observation (-> observation
                                (assoc :observation/block-number 82)
                                (assoc :observation/block-hash (str "keccak256:" (apply str (repeat 64 "4")))))
        artifact (gate/observed-chain-instance-genesis packages preseal-observation)
        root (genesis/chain-instance-genesis-root artifact)
        call {:from (:genesis/sealer-address preseal-observation)
              :target (:genesis/address preseal-observation)
              :function "sealGenesis"
              :args [(subs root 7)
                     (subs (:genesis/runtime-code-keccak256 preseal-observation) 10)
                     (subs (:authority/runtime-code-keccak256 preseal-observation) 10)]}
        result (gate/verify-seal-preflight
                {:candidate-packages packages
                 :manifest manifest
                 :approval-envelope envelope
                 :trusted-context {:source-revision source-revision
                                   :source-tree-sha256 source-tree-sha256
                                   :source-root source-root
                                   :source-lock-path source-lock-path
                                   :principal principal :trust-policy trust :action "approve-initial-inputs"
                                   :expected-genesis-runtime-code-keccak256
                                   (:genesis/runtime-code-keccak256 observation)
                                   :expected-authority-runtime-code-keccak256
                                   (:authority/runtime-code-keccak256 observation)}
                 :observation observation
                 :preseal-observation preseal-observation
                 :seal-call call})]
    (is (= "0xc78884593859e84524e41ce94cce38fbcb58c5f8"
           (get (roots/derive-generic-candidate-package-set packages)
                "authorityTransitionValidatorAddress")))
    (is (= root (:computed-root result)))
    (is (= artifact (:artifact result)))
    (is (= :gate-test-key (:approval-key-id result)))
    (is (= "approved-inputs-and-live-observations-verified" (:qualification result)))))
