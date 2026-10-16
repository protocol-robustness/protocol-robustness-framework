(ns scripts.deployment.genesis-seal-gate
  "Independent preflight for a local chain-instance genesis seal.

  The gate derives the canonical artifact from an authenticated initial-input
  manifest and separately collected chain readbacks. It yields evidence for a
  signer/executor to consume; it does not send a transaction. Live CLI mode
  derives the expected call independently; offline mode may compare a supplied
  decoded call."
  (:require [resolver-sim.genesis :as genesis]
            [resolver-sim.deployment.source-identity :as source-identity]
            [scripts.deployment.configuration-review :as review]
            [scripts.deployment.genesis-roots :as roots]
            [clojure.edn :as edn]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [scripts.deployment.genesis-seal-observer :as observer]))

(defn- json-safe [value]
  (cond
    (keyword? value) (if-let [ns (namespace value)] (str ns "/" (name value)) (name value))
    (map? value) (into {} (map (fn [[key item]] [(if (keyword? key)
                                                   (if-let [ns (namespace key)] (str ns "/" (name key)) (name key))
                                                   (str key))
                                                 (json-safe item)])) value)
    (vector? value) (mapv json-safe value)
    (set? value) (mapv json-safe (sort-by pr-str value))
    (sequential? value) (mapv json-safe value)
    :else value))

(defn- fail [reason data]
  (throw (ex-info reason (assoc data :gate :chain-instance-genesis-seal-preflight))))

(defn observed-chain-instance-genesis
  "Construct chain-instance-genesis.v1 using approved semantic roots and live
  addresses/code hashes. Observation is kept outside the canonical artifact."
  [candidate-packages observation]
  (let [derived (if (contains? candidate-packages :local-authority-policy)
                  (roots/derive-generic-candidate-package-set candidate-packages)
                  (roots/derive-candidate-package-set candidate-packages))
        protocol-root (get-in derived ["roots" "protocol-genesis"])
        authority-root (get-in derived ["roots" "authority-state"])
        chain-id (:execution/chain-id observation)]
    (when-not (= 31337 chain-id (:execution/chain-id observation))
      (fail "observed execution chain must be local Anvil chain 31337"
            {:observed-chain-id chain-id}))
    (when-not (= 31337 (:settlement/chain-id observation))
      (fail "local bounded deployment requires settlement chain 31337"
            {:settlement-chain-id (:settlement/chain-id observation)}))
    (when-not (and (= protocol-root (:genesis/protocol-genesis-root observation)
                      (:authority/protocol-genesis-root observation))
                   (= 31337 (:genesis/execution-chain-id observation)
                      (:genesis/settlement-chain-id observation))
                   (genesis/valid-evm-address? (:genesis/sealer-address observation))
                   (= (:authority/address observation) (:genesis/authority-adapter observation))
                   (= authority-root (:genesis/initial-authority-state-root observation))
                   (= authority-root (:authority/state-root observation))
                   (= protocol-root (:authority/constructor-protocol-genesis-root observation))
                   (= authority-root (:authority/constructor-initial-state-root observation))
                   (= (get-in derived ["roots" "initial-configuration"])
                      (:genesis/initial-configuration-root observation)))
      (fail "live constructor/readback values differ from approved initial roots"
            {:approved-roots (get derived "roots")
             :observation observation}))
    (when-not (false? (:genesis/sealed? observation))
      (fail "genesis must remain unsealed through independent preflight"
            {:sealed? (:genesis/sealed? observation)}))
    (when-not (and (= (str "sha256:" (apply str (repeat 64 "0")))
                      (:genesis/chain-instance-genesis-root observation))
                   (= (str "keccak256:" (apply str (repeat 64 "0")))
                      (:genesis/stored-runtime-code-keccak256 observation))
                   (= (str "keccak256:" (apply str (repeat 64 "0")))
                      (:genesis/stored-authority-adapter-runtime-code-keccak256 observation)))
      (fail "unsealed genesis contains preexisting root or runtime identity values"
            {:root (:genesis/chain-instance-genesis-root observation)
             :stored-runtime (:genesis/stored-runtime-code-keccak256 observation)
             :stored-authority-runtime (:genesis/stored-authority-adapter-runtime-code-keccak256 observation)}))
    (let [artifact {:genesis/schema "chain-instance-genesis.v1"
                    :protocol/genesis-root protocol-root
                    :execution/chain-id 31337
                    :settlement/chain-id 31337
                    :control-plane {:address (:genesis/address observation)
                                    :runtime-code-keccak256 (:genesis/runtime-code-keccak256 observation)}
                    :governance {:authority-adapter (:authority/address observation)
                                 :authority-adapter-code-keccak256 (:authority/runtime-code-keccak256 observation)
                                 :initial-authority-state-root authority-root}
                    :configuration/initial-root (get-in derived ["roots" "initial-configuration"])}]
      (when-not (and (genesis/valid-evm-address? (get-in artifact [:control-plane :address]))
                     (genesis/valid-evm-address? (get-in artifact [:governance :authority-adapter]))
                     (genesis/valid-keccak256-ref? (get-in artifact [:control-plane :runtime-code-keccak256]))
                     (genesis/valid-keccak256-ref? (get-in artifact [:governance :authority-adapter-code-keccak256])))
        (fail "observed deployed addresses or runtime hashes are not canonical"
              {:artifact artifact}))
      artifact)))

(defn verify-seal-preflight
  "Independently recompute and compare all seal inputs before a transaction.
  `trusted-context` is supplied out of band and includes the expected source
  parent and source-tree lock root, principal, trusted key policy and required
  approval action. `seal-call` is decoded from the planned transaction."
  [{:keys [candidate-packages manifest approval-envelope trusted-context
           observation preseal-observation seal-call]}]
  (let [{:keys [source-revision source-tree-sha256 source-root source-lock-path
               principal trust-policy action expected-genesis-runtime-code-keccak256
               expected-authority-runtime-code-keccak256]} trusted-context
        source-check (try
                       (source-identity/verify-source-lock
                        {:source-root source-root :source-lock-path source-lock-path
                         :expected-tree-sha256 source-tree-sha256
                         :expected-parent source-revision})
                       (catch Exception error {:error (.getMessage error)}))
        _ (when (:error source-check)
            (fail "current source does not match independently trusted release identity"
                  {:source-check source-check}))
        _ (when-not (= "local-configuration-review-manifest.v3" (:manifest/schema manifest))
            (fail "genesis sealing requires a source-bound configuration review manifest"
                  {:manifest-schema (:manifest/schema manifest)}))
        _ (when-not (= source-tree-sha256 (:manifest/source-tree-sha256 manifest))
            (fail "approved manifest source-tree identity differs from trusted release identity"
                  {:expected source-tree-sha256 :manifest (:manifest/source-tree-sha256 manifest)}))
        content-check (review/validate-review-bindings manifest candidate-packages
                                                        source-revision principal)
        approval-check (review/verify-approval-envelope approval-envelope manifest
                                                        trust-policy action)]
    (when-not (:valid? content-check)
      (fail "initial-input manifest does not match independently derived package contents"
            {:content-check content-check}))
    (when-not (and (:valid? approval-check) (= "approve-initial-inputs" action))
      (fail "authenticated initial-input approval is absent or out of scope"
            {:approval-check approval-check :action action}))
    (doseq [[label value] [[:initial observation] [:preseal preseal-observation]]]
      (when-not (and (map? value)
                     (integer? (:observation/block-number value))
                     (pos? (:observation/block-number value))
                     (genesis/valid-keccak256-ref? (:observation/block-hash value)))
        (fail "seal observation lacks a pinned block identity"
              {:observation label
               :block-number (:observation/block-number value)
               :block-hash (:observation/block-hash value)})))
    (let [observed-artifact (observed-chain-instance-genesis candidate-packages observation)
          artifact (observed-chain-instance-genesis candidate-packages preseal-observation)
          observed-state (dissoc observation :observation/block-number :observation/block-hash)
          preseal-state (dissoc preseal-observation :observation/block-number :observation/block-hash)]
      (when-not (and (= observed-artifact artifact)
                     (= observed-state preseal-state)
                     (or (> (:observation/block-number preseal-observation)
                            (:observation/block-number observation))
                         (and (= (:observation/block-number preseal-observation)
                                 (:observation/block-number observation))
                              (= (:observation/block-hash preseal-observation)
                                 (:observation/block-hash observation)))))
        (fail "live state changed or observation order is invalid before sealing"
              {:initial-observation observation :preseal-observation preseal-observation}))
      (when-not (and (= expected-genesis-runtime-code-keccak256
                        (:genesis/runtime-code-keccak256 preseal-observation))
                     (= expected-authority-runtime-code-keccak256
                        (:authority/runtime-code-keccak256 preseal-observation)))
        (fail "observed runtime code does not match independently pinned deployment artifacts"
              {:expected-genesis-runtime expected-genesis-runtime-code-keccak256
               :observed-genesis-runtime (:genesis/runtime-code-keccak256 preseal-observation)
               :expected-authority-runtime expected-authority-runtime-code-keccak256
               :observed-authority-runtime (:authority/runtime-code-keccak256 preseal-observation)}))
      (let [root (genesis/chain-instance-genesis-root artifact)
          expected-call {:from (:genesis/sealer-address preseal-observation)
                         :target (:genesis/address preseal-observation)
                         :function "sealGenesis"
                         :args [(subs root 7)
                                (subs (:genesis/runtime-code-keccak256 preseal-observation) 10)
                                (subs (:authority/runtime-code-keccak256 preseal-observation) 10)]}]
      (when (and seal-call (not= expected-call seal-call))
        (fail "planned seal transaction differs from independently derived artifact and observations"
              {:expected-call expected-call :planned-call seal-call :root root}))
      {:schema "prf-chain-instance-seal-preflight.v2"
       :qualification "approved-inputs-and-live-observations-verified"
       :manifest-root (review/manifest-root manifest)
       :canonical-roots (:manifest/canonical-roots manifest)
       :initial-configuration (:manifest/initial-configuration manifest)
       :source-tree-sha256 (:source-tree-sha256 source-check)
       :source-parent (:source-parent source-check)
       :source-file-count (:source-file-count source-check)
       :approval-key-id (:key-id approval-check)
       :observation-blocks {:initial {:number (:observation/block-number observation)
                                      :hash (:observation/block-hash observation)}
                            :preseal {:number (:observation/block-number preseal-observation)
                                      :hash (:observation/block-hash preseal-observation)}}
       :observations-edn (pr-str {:observation observation
                                  :preseal-observation preseal-observation})
       :artifact artifact
       :computed-root root
       :seal-call expected-call}))))

(def ^:private seal-continuity-fields
  [:genesis/address :genesis/runtime-code-keccak256
   :genesis/stored-runtime-code-keccak256 :genesis/stored-authority-adapter-runtime-code-keccak256
   :genesis/execution-chain-id :genesis/settlement-chain-id
   :genesis/authority-adapter :genesis/protocol-genesis-root
   :genesis/initial-authority-state-root :genesis/initial-configuration-root
   :authority/address :authority/runtime-code-keccak256
   :authority/protocol-genesis-root :authority/state-root])

(defn verify-seal-result
  "Verify the mined seal transaction against preflight evidence and readbacks
  at the receipt block. The caller must collect transaction and block data
  independently from the execution node."
  [{:keys [preflight receipt transaction sealed-observation]}]
  (let [root (:computed-root preflight)
        expected-call (:seal-call preflight)
        receipt-hash (:receipt/block-hash receipt)
        receipt-number (:receipt/block-number receipt)]
    (when-not (= "prf-chain-instance-seal-preflight.v2" (:schema preflight))
      (fail "seal result has no recognized preflight evidence" {:preflight-schema (:schema preflight)}))
    (when-not (and (= :success (:receipt/status receipt))
                   (string? (:receipt/transaction-hash receipt))
                   (re-matches #"^0x[0-9a-fA-F]{64}$" (:receipt/transaction-hash receipt))
                   (integer? receipt-number) (pos? receipt-number)
                   (genesis/valid-keccak256-ref? receipt-hash))
      (fail "seal transaction receipt is failed or lacks canonical block identity" {:receipt receipt}))
    (when-not (> receipt-number (get-in preflight [:observation-blocks :preseal :number] 0))
      (fail "seal transaction was not mined after the retained preseal observation"
            {:receipt-block receipt-number
             :preseal-block (get-in preflight [:observation-blocks :preseal :number])}))
    (when-not (and (= expected-call (select-keys transaction [:from :target :function :args]))
                   (= (:receipt/transaction-hash receipt) (:transaction/hash transaction)))
      (fail "mined seal transaction does not match independently approved preflight arguments"
            {:expected-call expected-call :transaction transaction}))
    (when-not (and (= receipt-number (:transaction/block-number transaction))
                   (= receipt-hash (:transaction/block-hash transaction))
                   (= receipt-number (:observation/block-number sealed-observation))
                   (= receipt-hash (:observation/block-hash sealed-observation)))
      (fail "seal receipt, transaction and post-seal readback are not pinned to the same canonical block"
            {:receipt receipt :transaction transaction
             :observation-block [(:observation/block-number sealed-observation)
                                 (:observation/block-hash sealed-observation)]}))
    (when-not (and (true? (:genesis/sealed? sealed-observation))
                   (= root (:genesis/chain-instance-genesis-root sealed-observation))
                   (= "0x0000000000000000000000000000000000000000"
                      (:genesis/sealer-address sealed-observation)))
      (fail "post-seal genesis state does not contain the independently derived root"
            {:expected-root root :observed-root (:genesis/chain-instance-genesis-root sealed-observation)
             :sealed? (:genesis/sealed? sealed-observation)}))
    (let [artifact (:artifact preflight)
          expected-continuity {:genesis/address (get-in artifact [:control-plane :address])
                               :genesis/runtime-code-keccak256 (get-in artifact [:control-plane :runtime-code-keccak256])
                               :genesis/stored-runtime-code-keccak256 (get-in artifact [:control-plane :runtime-code-keccak256])
                               :genesis/stored-authority-adapter-runtime-code-keccak256 (get-in artifact [:governance :authority-adapter-code-keccak256])
                               :genesis/execution-chain-id (:execution/chain-id artifact)
                               :genesis/settlement-chain-id (:settlement/chain-id artifact)
                               :genesis/authority-adapter (get-in artifact [:governance :authority-adapter])
                               :genesis/protocol-genesis-root (:protocol/genesis-root artifact)
                               :genesis/initial-authority-state-root (get-in artifact [:governance :initial-authority-state-root])
                               :genesis/initial-configuration-root (:configuration/initial-root artifact)
                               :authority/address (get-in artifact [:governance :authority-adapter])
                               :authority/runtime-code-keccak256 (get-in artifact [:governance :authority-adapter-code-keccak256])
                               :authority/protocol-genesis-root (:protocol/genesis-root artifact)
                               :authority/state-root (get-in artifact [:governance :initial-authority-state-root])}
          observed-continuity (select-keys sealed-observation seal-continuity-fields)]
      (when-not (= expected-continuity observed-continuity)
        (fail "post-seal readbacks disagree with canonical genesis identity or authority continuity"
              {:expected expected-continuity :observed observed-continuity})))
    {:schema "prf-chain-instance-seal-result.v1"
     :qualification "seal-receipt-and-readback-verified"
     :manifest-root (:manifest-root preflight)
     :chain-instance-genesis-root root
     :receipt {:transaction-hash (:receipt/transaction-hash receipt)
               :block-number receipt-number :block-hash receipt-hash}
     :artifact (:artifact preflight)}))

(defn -main [& supplied-args]
  (let [json-output? (= "--json" (last supplied-args))
        args (if json-output? (butlast supplied-args) supplied-args)
        emit (fn [result]
               (if json-output?
                 (println (json/write-str (json-safe result)))
                 (prn result)))]
  (if (= "verify-seal-result" (first args))
    (let [[_ request-path trusted-context-path preflight-result-path rpc-url transaction-hash genesis-address authority-address & extra] args]
      (when (or (nil? request-path) (nil? trusted-context-path) (nil? preflight-result-path) (nil? rpc-url)
                (nil? transaction-hash) (nil? genesis-address) (nil? authority-address) (seq extra))
        (binding [*out* *err*]
          (println "Usage: clojure -M:test -m scripts.deployment.genesis-seal-gate verify-seal-result <preflight-request.edn> <trusted-context.edn> <preflight-result.json> <rpc-url> <transaction-hash> <genesis-address> <authority-adapter-address>"))
        (System/exit 2))
      (let [request (edn/read-string (slurp (io/file request-path)))
            trusted-context (edn/read-string (slurp (io/file trusted-context-path)))
            recorded-preflight (json/read-str (slurp (io/file preflight-result-path)) :key-fn keyword)
            recorded-observations (some-> (:observations-edn recorded-preflight) edn/read-string)
            _ (when-not (and (= "prf-chain-instance-seal-preflight.v2" (:schema recorded-preflight))
                             (map? recorded-observations))
                (fail "retained preflight result lacks its schema or pinned observations"
                      {:preflight-schema (:schema recorded-preflight)}))
            observed-initial (:observation recorded-observations)
            observed-preseal (:preseal-observation recorded-observations)
            initial (observer/observe-chain-instance rpc-url genesis-address authority-address
                                                     (:observation/block-number observed-initial))
            preseal (observer/observe-chain-instance rpc-url genesis-address authority-address
                                                     (:observation/block-number observed-preseal))
            preflight (verify-seal-preflight (assoc (dissoc request :seal-call) :trusted-context trusted-context
                                                    :observation initial :preseal-observation preseal))
            _ (when-not (and (= (:manifest-root recorded-preflight) (:manifest-root preflight))
                             (= (:computed-root recorded-preflight) (:computed-root preflight))
                             (= observed-initial initial) (= observed-preseal preseal))
                (fail "retained preflight identity or block-pinned observations do not reproduce"
                      {:recorded-manifest (:manifest-root recorded-preflight)
                       :recomputed-manifest (:manifest-root preflight)
                       :recorded-root (:computed-root recorded-preflight)
                       :recomputed-root (:computed-root preflight)}))
            observed-result (observer/observe-seal-transaction rpc-url transaction-hash
                                                               genesis-address authority-address)
            result (verify-seal-result (assoc observed-result :preflight preflight))]
        (emit result)))
    (let [[request-path trusted-context-path rpc-url genesis-address authority-address] args]
      (when-not (or (= 2 (count args)) (= 5 (count args)))
        (binding [*out* *err*]
          (println "Usage: clojure -M:test -m scripts.deployment.genesis-seal-gate <preflight-request.edn> <trusted-context.edn> [<rpc-url> <genesis-address> <authority-adapter-address>]"))
        (System/exit 2))
      (let [request (edn/read-string (slurp (io/file request-path)))
            trusted-context (edn/read-string (slurp (io/file trusted-context-path)))
            request (if rpc-url
                      (assoc (dissoc request :seal-call)
                             :observation (observer/observe-chain-instance rpc-url genesis-address authority-address)
                             :preseal-observation (observer/observe-chain-instance rpc-url genesis-address authority-address))
                      request)
            result (verify-seal-preflight (assoc request :trusted-context trusted-context))]
        (emit result))))))
