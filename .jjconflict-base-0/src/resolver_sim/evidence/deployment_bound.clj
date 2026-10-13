(ns resolver-sim.evidence.deployment-bound
  "Offline reconstruction of deployment-bound forensic evidence.

   This adapter validates retained artifact bytes and cross-links, then emits
   the existing PRF evidence-node DAG and forensic view. It does not establish
   RPC authenticity, SP1 proof validity, source truth, or researcher acceptance."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [resolver-sim.conformance.canonical :as canonical-json]
            [resolver-sim.evidence.forensic-graph :as forensic]))

(def ^:private public-values-schema
  "0x876f2aea9124d79f41dbdd389d498430987fa33bbb34408989849c478a7c2bde")
(def ^:private transfer-topic
  "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef")
(def ^:private expected-payouts
  {"0x1111111111111111111111111111111111111111" 35
   "0x2222222222222222222222222222222222222222" 21
   "0x3333333333333333333333333333333333333333" 14})

(defn sha256-hex [^bytes bytes]
  (let [digest (.digest (java.security.MessageDigest/getInstance "SHA-256") bytes)]
    (apply str (map #(format "%02x" (bit-and (int %) 0xff)) digest))))

(defn file-sha256 [path]
  (sha256-hex (java.nio.file.Files/readAllBytes (.toPath (io/file path)))))

(defn recompute-gate-e-manifest-digest
  "Reproduce the Gate E shell producer: SHA-256 of jq -S -c output, including
   jq's final LF. This is deployment-manifest integrity, not a PRF semantic root."
  [manifest]
  (let [body (dissoc manifest "manifestDigest")
        bytes (.getBytes (str (canonical-json/canonical-json-str body) "\n") "UTF-8")]
    (str "sha256:" (sha256-hex bytes))))

(defn- read-json [path]
  (with-open [r (io/reader path)] (json/read r)))

(defn- bytes-from-hex [value]
  (when (and (string? value) (re-matches #"(?i)^0x(?:[0-9a-f]{2})*$" value))
    (let [hex (subs value 2)]
      (byte-array (map (fn [[a b]] (unchecked-byte (Integer/parseInt (str a b) 16)))
                       (partition 2 hex))))))

(defn- normalized-digest [value]
  (some-> value str/lower-case (str/replace #"^(?:sha256:|0x)" "")))

(defn- word [bytes i]
  (str "0x" (apply str (map #(format "%02x" (bit-and (int %) 0xff))
                            (take 32 (drop (* 32 i) bytes))))))

(defn- tx-receipt-errors [broadcast]
  (let [txs (get broadcast "transactions")
        receipts (get broadcast "receipts")
        receipts-by-hash (into {} (map (juxt #(str/lower-case (get % "transactionHash")) identity) receipts))]
    (if-not (and (vector? txs) (vector? receipts) (= (count txs) (count receipts)))
      [{:kind :missing-observation :path "broadcast.transactions/receipts"
        :message "transaction and receipt vectors are absent or have different lengths"}]
      (vec
       (mapcat (fn [tx]
                 (let [hash (str/lower-case (get tx "hash" (get-in tx ["transaction" "hash"] "")))
                       receipt (get receipts-by-hash hash)
                       receipt-hash (str/lower-case (get receipt "transactionHash" ""))
                       tx-chain (get-in tx ["transaction" "chainId"])
                       bad-logs (filter #(or (not= receipt-hash (str/lower-case (get % "transactionHash" "")))
                                             (not= (get receipt "blockHash") (get % "blockHash"))
                                             (not= (get receipt "blockNumber") (get % "blockNumber")))
                                        (get receipt "logs" []))]
                   (cond-> []
                     (or (empty? hash) (nil? receipt) (not= hash receipt-hash))
                     (conj {:kind :conflicting-evidence :path "broadcast"
                            :message "transaction has no matching receipt by transaction hash"
                            :tx hash :receipt receipt-hash})
                     (not= "0x1" (get receipt "status"))
                     (conj {:kind :missing-observation :path "broadcast.receipts"
                            :message "receipt is not recorded as successful" :tx hash})
                     (not= "0x7a69" (str/lower-case (or tx-chain "")))
                     (conj {:kind :conflicting-evidence :path "broadcast.transaction.chainId"
                            :message "transaction is not bound to local chain 31337" :tx hash})
                     (seq bad-logs)
                     (conj {:kind :conflicting-evidence :path "broadcast.receipt.logs"
                            :message "log transaction/block context differs from receipt" :tx hash}))))
               txs)))))

(defn- payout-observations [broadcast token allocator]
  (let [txs (get broadcast "transactions")
        receipts (get broadcast "receipts")
        txs-by-hash (into {} (map (fn [tx]
                                    [(str/lower-case (get tx "hash" (get-in tx ["transaction" "hash"] ""))) tx]) txs))]
    (->> receipts
         (mapcat (fn [receipt]
                   (let [tx (get txs-by-hash (str/lower-case (get receipt "transactionHash")))]
                     (for [log (get receipt "logs" [])
                           :when (and (= 3 (count (get log "topics" [])))
                                      (= transfer-topic (str/lower-case (first (get log "topics" []))))
                                      (= (str/lower-case token) (str/lower-case (get log "address")))
                                      (= (str/lower-case allocator)
                                         (str "0x" (subs (str/lower-case (second (get log "topics" []))) 26))))]
                       {:recipient (str "0x" (subs (str/lower-case (nth (get log "topics") 2)) 26))
                        :amount (bigint (java.math.BigInteger. (subs (get log "data") 2) 16))
                        :tx (get receipt "transactionHash")
                        :block (get receipt "blockNumber")
                        :function (get tx "function")
                        :selector (some-> (get-in tx ["transaction" "input"])
                                          str/lower-case (subs 0 (min 10 (count (get-in tx ["transaction" "input"])))))}))))
         vec)))

(defn- parse-final-state [text]
  (into {} (map (fn [[_ k v]] [k v])
                (re-seq #"(?m)^([a-zA-Z0-9_]+)=([^\n]+)$" text))))

(defn validate-records
  "Validate parsed retained records. Hashes are lowercase SHA-256 hex without
   prefixes, keyed by artifact name. Caller-supplied verification labels are
   ignored; all statuses here come from recomputed checks."
  [{:keys [report manifest witness fixture provenance broadcasts final-state fork-test-log artifact-hashes]}]
  (let [manifest-hash (get artifact-hashes :manifest)
        witness-hash (get artifact-hashes :witness)
        fixture-hash (get artifact-hashes :fixture)
        proof-bytes (bytes-from-hex (get fixture "proof"))
        public-bytes (bytes-from-hex (get fixture "public_values"))
        gate (get fixture "gate_e")
        prepared (get witness "prepared-operation")
        reserve-input (get witness "economic-reserve-state")
        reserve-manifest (get manifest "economicReserveState")
        words (when (and public-bytes (= 288 (alength public-bytes)))
                (mapv #(word public-bytes %) (range 9)))
        report-hashes (get report "byte_identical_artifacts")
        expected-manifest-root (recompute-gate-e-manifest-digest manifest)
        manifest-root (get manifest "manifestDigest")
        tx-groups (vals broadcasts)
        receipts-errors (vec (mapcat tx-receipt-errors tx-groups))
        run (get broadcasts :run)
        admission-tx (first (filter #(= "admitStatement(bytes32,bytes32,bytes,bytes)" (get % "function"))
                                    (get run "transactions")))
        admission-hash (or (get admission-tx "hash")
                           (get-in admission-tx ["transaction" "hash"]))
        admission-receipt (when admission-tx
                            (some #(when (= (str/lower-case admission-hash)
                                            (str/lower-case (get % "transactionHash"))) %)
                                  (get run "receipts")))
        withdrawals (get broadcasts :withdrawal)
        payouts (payout-observations withdrawals (get-in manifest ["contracts" "token"])
                                     (get-in manifest ["contracts" "allocator"]))
        state (parse-final-state final-state)
        payout-map (into {} (map (juxt :recipient :amount) payouts))
        expected-count (when words (bigint (java.math.BigInteger. (subs (nth words 6) 2) 16)))
        expected-total (when words (bigint (java.math.BigInteger. (subs (nth words 7) 2) 16)))
        checks
        {:source-files-match-report
         (and (= manifest-hash (get-in report-hashes ["manifest" "sha256"]))
              (= witness-hash (get-in report-hashes ["witness" "sha256"]))
              (= fixture-hash (get-in report-hashes ["proof_fixture" "sha256"])))
         :manifest-digest-recomputed (= expected-manifest-root manifest-root)
         :local-chain-and-local-only (= 31337 (get manifest "chainId"))
         :witness-provenance-bound
         (and (= (get provenance "witness_sha256") (str "sha256:" witness-hash))
              (= (get provenance "manifest_digest") manifest-root)
              (= (get provenance "bundle_sha256")
                 (str "sha256:" (subs (get-in fixture ["gate_e" "source_bundle_sha256"]) 2))))
         :fixture-identities-bound
         (and (= "groth16" (get fixture "proof_system"))
              (= (get manifest "programId") (get fixture "program_id"))
              (= (get manifest "programVKey") (get fixture "vkey"))
              (= (get manifest "programElfSha256") (get fixture "elf_sha256"))
              (= "prf/source-bound-entitlement-public-values.v1" (get fixture "public_values_schema"))
              (= public-values-schema (get manifest "publicValuesSchema")))
         :proof-witness-manifest-crosslinks
         (and (= (str "0x" witness-hash) (get-in fixture ["source" "witness_sha256"]))
              (= manifest-root (get gate "manifest_digest"))
              (= (get manifest "operationId") (get gate "operation_id"))
              (= (get manifest "preparedOperationRoot") (get gate "prepared_operation_root"))
              (= (get manifest "reserveStateCommitment") (get gate "reserve_state_commitment"))
              (= (str/lower-case (get manifest "operationId"))
                 (str/lower-case (get prepared "operation-id")))
              (= (str (get manifest "chainId")) (get prepared "chain-id"))
              (= (str/lower-case (get manifest "configurationRoot"))
                 (str/lower-case (get prepared "configuration-root"))))
         :prepared-context-bound
         (and (= (str/lower-case (get manifest "programVKey"))
                 (str/lower-case (get prepared "source-program-vkey")))
              (= (str/lower-case (get manifest "programElfSha256"))
                 (str/lower-case (get prepared "source-program-elf-sha256")))
              (= (str/lower-case (get manifest "policyRoot"))
                 (str/lower-case (get prepared "policy-root")))
              (= (str/lower-case (get-in manifest ["contracts" "allocator"]))
                 (str/lower-case (get prepared "allocator")))
              (= (str/lower-case (get-in manifest ["contracts" "reserve"]))
                 (str/lower-case (get prepared "reserve")))
              (= (str/lower-case (get-in manifest ["contracts" "token"]))
                 (str/lower-case (get prepared "token")))
              (= (str (get manifest "configurationEpoch")) (get prepared "configuration-epoch"))
              (= (str/lower-case (get manifest "beneficiaryProfile"))
                 (str/lower-case (get prepared "beneficiary-profile"))))
         :reserve-input-bound
         (and (= (normalized-digest (get manifest "reserveBindingRoot"))
                 (normalized-digest (get witness "reserve-binding-root")))
              (= (str (get reserve-manifest "availabilityRevision"))
                 (get reserve-input "availability-revision"))
              (= (str (get reserve-manifest "availableAmount"))
                 (get reserve-input "available-amount"))
              (= (str (get reserve-manifest "grossBalance")) (get reserve-input "gross-balance"))
              (= (str (get reserve-manifest "protectedCapital"))
                 (get reserve-input "protected-capital"))
              (= (str (get reserve-manifest "reservedBefore")) (get reserve-input "reserved-before")))
         :public-values-structure-and-binding
         (and (= 288 (some-> public-bytes alength))
              (= public-values-schema (first words))
              (= (str/lower-case (get manifest "preparedOperationRoot")) (second words))
              (= (str/lower-case (get manifest "reserveStateCommitment")) (nth words 2))
              (= 3N expected-count)
              (= 70N expected-total))
         :proof-bytes-hash-bound
         (= (str "0x" (sha256-hex proof-bytes)) (get gate "proof_sha256"))
         :admission-call-carried-exact-proof-and-public-values
         (and admission-tx admission-receipt
              (= "0x1" (get admission-receipt "status"))
              (= (str/lower-case (nth (get admission-tx "arguments") 2))
                 (str/lower-case (get fixture "proof")))
              (= (str/lower-case (nth (get admission-tx "arguments") 3))
                 (str/lower-case (get fixture "public_values"))))
         :all-retained-receipt-records-consistent (empty? receipts-errors)
         :recorded-test-output-matches-report
         (and (string? fork-test-log)
              (str/includes? fork-test-log "4 tests passed, 0 failed, 0 skipped"))
         :payout-transfer-logs-reconcile
         (and (= expected-payouts payout-map)
              (= 3 (count payouts))
              (= expected-total (reduce + 0N (map :amount payouts))))
         :payouts-emitted-by-withdraw-calls
         (every? #(and (= "0x7c365406" (:selector %))
                       (= "withdraw(bytes32,(uint256,bytes,address,uint256,bytes32),bytes32[],bool,bytes32[],uint8)"
                          (:function %))) payouts)
         :final-state-log-reconciles
         (and (= "15" (get state "final_block"))
              (= "10" (get state "reserve_balance"))
              (= "0" (get state "allocator_balance"))
              (= "35" (get state "beneficiary_1111_balance"))
              (= "21" (get state "beneficiary_2222_balance"))
              (= "14" (get state "beneficiary_3333_balance")))}
        errors (vec (concat
                     (for [[check ok?] checks :when (not (true? ok?))
                           :let [kind (if (#{:all-retained-receipt-records-consistent
                                             :payout-transfer-logs-reconcile
                                             :payouts-emitted-by-withdraw-calls
                                             :final-state-log-reconciles} check)
                                        :conflicting-evidence :unverified-claim)]]
                       {:kind kind :check check :message (str "verification failed: " (name check))})
                     receipts-errors))
        gaps (vec (concat
                   [{:kind :missing-source :status :not-available
                     :artifact "source bundle payload"
                     :reason "the bundle SHA is linked, but the source vector bundle is not retained here"}
                    {:kind :missing-observation :status :not-available
                     :artifact "deployed runtime code identities and authenticated block headers"
                     :reason "manifest and local broadcast receipts do not retain independently authenticated runtime-code observations"}
                    {:kind :unverified-claim :status :not-verifiable
                     :artifact "Groth16 proof and witness-derived root recomputation"
                     :reason "this PRF adapter does not run the SP1 verifier or successor Rust derivation"}]
                   (when-not (:payouts-emitted-by-withdraw-calls checks)
                     [{:kind :conflicting-evidence :status :conflicting
                       :artifact "payout transaction call context"
                       :reason "at least one allocator payout Transfer is associated with a non-withdraw outer call"}])
                   (when-not (and (= "pass" (get-in report ["foundry_fork_tests" "status"]))
                                  (:recorded-test-output-matches-report checks))
                     [{:kind :missing-observation :status :not-observed
                       :artifact "Foundry execution result" :reason "no passing test report recorded"}])))]
    {:valid? (empty? errors)
     :checks checks
     :errors errors
     :gaps gaps
     :summary {:transaction-count (reduce + 0 (map #(count (get % "transactions")) tx-groups))
               :successful-payout-count (count payouts)
               :entitlement-count expected-count
               :entitlement-total expected-total
               :payouts payouts}
     :limitations {:chain-id 31337
                   :local-only? (boolean (get manifest "localOnly"))
                   :rpc-observations-authenticated? false
                   :cryptographic-proof-independently-verified? false
                   :acceptance-inferred? false
                   :reward-or-payment-authorized? false}}))

(defn- artifact-ref [hex] (str "sha256:" hex))

(defn build-forensic-view
  "Rebuild canonical PRF evidence nodes from validated input identities.
   `validation` is the recomputed validator output, never a fixture label."
  [{:keys [validation artifact-hashes replayed-at parsed]}]
  (let [checks (:checks validation)
        node-valid? {:manifest (and (:source-files-match-report checks)
                                    (:manifest-digest-recomputed checks))
                     :witness (and (:source-files-match-report checks)
                                   (:witness-provenance-bound checks)
                                   (:prepared-context-bound checks)
                                   (:reserve-input-bound checks))
                     :fixture (and (:source-files-match-report checks)
                                   (:fixture-identities-bound checks)
                                   (:proof-witness-manifest-crosslinks checks)
                                   (:public-values-structure-and-binding checks)
                                   (:proof-bytes-hash-bound checks))
                     :deploy (:all-retained-receipt-records-consistent checks)
                     :run (and (:all-retained-receipt-records-consistent checks)
                               (:admission-call-carried-exact-proof-and-public-values checks))
                     :activate (:all-retained-receipt-records-consistent checks)
                     :withdrawal (:all-retained-receipt-records-consistent checks)
                     :report (:recorded-test-output-matches-report checks)
                     :final-state (:final-state-log-reconciles checks)}
        binding-valid? (and (:source-files-match-report checks)
                            (:manifest-digest-recomputed checks)
                            (:fixture-identities-bound checks)
                            (:proof-witness-manifest-crosslinks checks))
        entries [[:manifest :deployment/manifest :validated nil]
                 [:witness :proof/raw-witness :validated :manifest]
                 [:fixture :proof/fixture :validated :witness]
                 [:deploy :deployment/receipt-set :observed :manifest]
                 [:run :operation/execution-receipts :observed :fixture]
                 [:activate :operation/activation-receipts :observed :run]
                 [:withdrawal :operation/withdrawal-receipts :observed :activate]
                 [:report :execution/outcome-report :validated :withdrawal]
                 [:final-state :execution/state-observation :observed :withdrawal]]
        built (reduce (fn [{:keys [nodes by-name]} [name kind role parent]]
                        (let [root (artifact-ref (get artifact-hashes name))
                              parent-node (get by-name parent)
                              entry (forensic/build-forensic-evidence-node
                                     {:subject {:subject/root root :subject/kind kind}
                                      :role role :visibility :commitment-only
                                      :parent-nodes (cond-> [] parent-node (conj (:node-hash parent-node)))
                                      :causal-parent-roots (cond-> [] parent-node
                                                                   (conj (get-in parent-node [:extensions :forensic/subject :subject/root])))
                                      :actor {:actor/root (artifact-ref (get artifact-hashes :report))
                                              :actor/role :verifier}
                                      :observation-key (when (= role :observed) :evidence/recorded-receipt-set)
                                      :observed-root (when (= role :observed) root)
                                      :status (if (get node-valid? name) :pass :fail)})]
                          {:nodes (conj nodes entry) :by-name (assoc by-name name entry)}))
                      {:nodes [] :by-name {}} entries)
        fixture-root (artifact-ref (get artifact-hashes :fixture))
        manifest-root (artifact-ref (get artifact-hashes :manifest))
        declared-binding (forensic/build-forensic-evidence-node
                          {:subject {:subject/root fixture-root
                                     :subject/kind :proof/declared-manifest-reference}
                           :role :declared :visibility :commitment-only
                           :causal-parent-roots [manifest-root]
                           :actor {:actor/root (artifact-ref (get artifact-hashes :report))
                                   :actor/role :runner}
                           :status :pass})
        validated-binding (forensic/build-forensic-evidence-node
                           {:subject {:subject/root fixture-root
                                      :subject/kind :proof/manifest-binding-validation}
                            :role :validated :visibility :commitment-only
                            :parent-nodes [(:node-hash declared-binding)]
                            :causal-parent-roots [manifest-root]
                            :actor {:actor/root (artifact-ref (get artifact-hashes :report))
                                    :actor/role :verifier}
                            :status (if binding-valid? :pass :fail)})
        operation-id (get-in parsed [:manifest "operationId"])
        ;; Evidence nodes require the established sha256:<hex> reference type.
        ;; This hashes the exact operation-id bytes as a reference wrapper; it
        ;; does not reinterpret or replace the protocol's operation identity.
        operation-root (artifact-ref
                        (sha256-hex (.getBytes (str/lower-case operation-id) "UTF-8")))
        declared-operation (forensic/build-forensic-evidence-node
                            {:subject {:subject/root operation-root
                                       :subject/kind :protected-operation/operation-id}
                             :role :declared :visibility :commitment-only
                             :causal-parent-roots [manifest-root]
                             :actor {:actor/root (artifact-ref (get artifact-hashes :report))
                                     :actor/role :runner}
                             :status :pass})
        validated-operation (forensic/build-forensic-evidence-node
                             {:subject {:subject/root operation-root
                                        :subject/kind :protected-operation/prepared-binding}
                              :role :validated :visibility :commitment-only
                              :parent-nodes [(:node-hash declared-operation)
                                             (:node-hash (:fixture (:by-name built)))]
                              :causal-parent-roots [manifest-root fixture-root]
                              :actor {:actor/root (artifact-ref (get artifact-hashes :report))
                                      :actor/role :verifier}
                              :status (if (get-in validation [:checks :proof-witness-manifest-crosslinks])
                                        :pass :fail)})
        relationship-nodes [declared-binding validated-binding]
        gap-nodes (mapv (fn [gap]
                          (let [gap-root (artifact-ref
                                          (get artifact-hashes
                                               (case (:kind gap)
                                                 :missing-source :witness
                                                 :missing-observation :manifest
                                                 :unverified-claim :fixture
                                                 :conflicting-evidence :withdrawal
                                                 :report)))]
                            (forensic/build-forensic-evidence-node
                             {:subject {:subject/root gap-root
                                        :subject/kind (keyword "forensic-gap" (name (:kind gap)))}
                              :role :declared :visibility :commitment-only
                              :causal-parent-roots [gap-root]
                              :actor {:actor/root (artifact-ref (get artifact-hashes :report)) :actor/role :verifier}
                              :gap {:gap/status (case (:status gap)
                                                  :not-available :not-available
                                                  :not-verifiable :not-verifiable
                                                  :not-observed :not-observed
                                                  :conflicting :conflicting
                                                  :not-applicable)
                                    :gap/reason :evidence-requirement-unresolved}
                              :status :pass}))) (:gaps validation))
        nodes (into (into (into (:nodes built) relationship-nodes)
                          [declared-operation validated-operation]) gap-nodes)
        graph (forensic/validate-forensic-evidence-graph
               {:nodes nodes
                :artifact-roots (mapv (comp artifact-ref val) artifact-hashes)
                :profile {:forensic-profile/requirements []}})]
    {:nodes nodes :graph graph
     :relationships [{:kind :declared-reference :from :proof/declared-manifest-reference
                      :to :deployment/manifest :node-hash (:node-hash declared-binding)}
                     {:kind :validator-confirmed :validator "artifact-and-proof-crosslink-v0"
                      :from :proof/manifest-binding-validation :to :deployment/manifest
                      :node-hash (:node-hash validated-binding)}
                     {:kind :declared-operation-reference :from :protected-operation/operation-id
                      :to :deployment/manifest :node-hash (:node-hash declared-operation)}
                     {:kind :validator-confirmed :validator "artifact-and-proof-crosslink-v0"
                      :from :protected-operation/prepared-binding :to :proof/fixture
                      :node-hash (:node-hash validated-operation)}
                     {:kind :observed-local-receipt-reference :from :operation/execution-receipts
                      :to :proof/fixture}
                     {:kind :validated-economic-reconciliation :from :execution/outcome-report
                      :to :operation/withdrawal-receipts}]
     :replayed-at replayed-at}))

(defn reconstruct-bundle
  "Read the retained independent local replay bundle and reconstruct a PRF
   verification report and canonical forensic node view."
  [bundle-dir]
  (let [root (io/file bundle-dir)
        art (io/file root "artifacts")
        files {:report (io/file root "replay-report.json")
               :manifest (io/file art "manifest.json")
               :witness (io/file art "witness.json")
               :fixture (io/file art "fixture.json")
               :provenance (io/file art "witness.provenance.json")
               :final-state (io/file art "final-state.log")
               :fork-test-log (io/file art "fork-test.log")
               :deploy (io/file root "broadcast/DeployGateEEntitlementOptionA.s.sol/31337/run-latest.json")
               :run (io/file root "broadcast/RunGateEEntitlementLocal.s.sol/31337/run-latest.json")
               :activate (io/file root "broadcast/ActivateGateEEntitlementLocal.s.sol/31337/run-latest.json")
               :withdrawal (io/file root "broadcast/WithdrawGateEEntitlementLocal.s.sol/31337/run-latest.json")}
        missing (->> files (keep (fn [[k f]] (when-not (.isFile ^java.io.File f) k))) vec)]
    (when (seq missing)
      (throw (ex-info "Retained replay bundle is incomplete" {:missing missing})))
    (let [parsed (into {} (map (fn [[k f]]
                                 [k (if (#{:final-state :fork-test-log} k) (slurp f) (read-json f))]) files))
          hashes (into {} (map (fn [[k f]] [k (file-sha256 f)]) files))
          parsed (assoc parsed :broadcasts (select-keys parsed [:deploy :run :activate :withdrawal]))
          validation (validate-records (assoc parsed :artifact-hashes hashes))
          view (build-forensic-view {:validation validation :artifact-hashes hashes
                                     :replayed-at (get-in parsed [:report "operator" "replayed_utc"])
                                     :parsed parsed})]
      {:validation validation :view view :artifact-hashes hashes :parsed parsed})))

(defn write-reconstruction!
  "Persist a deterministic report and canonical EDN nodes without replacing
   existing evidence. Output is diagnostic and never an acceptance/reward record."
  [bundle-dir output-dir]
  (let [{:keys [validation view artifact-hashes parsed] :as result} (reconstruct-bundle bundle-dir)
        out (io/file output-dir)
        nodes-dir (io/file out "nodes")]
    (when (.exists out)
      (throw (ex-info "Refusing to overwrite forensic reconstruction output" {:output output-dir})))
    (.mkdirs nodes-dir)
    (doseq [n (:nodes view)]
      (spit (io/file nodes-dir (str (:node-hash n) ".edn")) (pr-str n)))
    (let [report {:schema "prf.deployment-bound-forensic-verification.v0"
                  :classification "diagnostic reconstruction; local-only; not canonical economic authority"
                  :validator {:id "resolver-sim.evidence.deployment-bound/v0"
                              :profile "protected-withdrawal-local-replay.v0"}
                  :input_artifacts (into (sorted-map)
                                         (map (fn [[k h]] [(name k) {:sha256 (str "sha256:" h)}]) artifact-hashes))
                  :operation_id (get-in parsed [:manifest "operationId"])
                  :identity_context {:chain_id (get-in parsed [:manifest "chainId"])
                                     :local_only (boolean (get-in parsed [:manifest "localOnly"]))
                                     :operation_id (get-in parsed [:manifest "operationId"])
                                     :prepared_operation_root (get-in parsed [:manifest "preparedOperationRoot"])
                                     :configuration_root (get-in parsed [:manifest "configurationRoot"])
                                     :configuration_epoch (get-in parsed [:manifest "configurationEpoch"])
                                     :program_id (get-in parsed [:fixture "program_id"])
                                     :proof_system (get-in parsed [:fixture "proof_system"])
                                     :vkey (get-in parsed [:fixture "vkey"])
                                     :elf_sha256 (get-in parsed [:fixture "elf_sha256"])
                                     :public_values_schema (get-in parsed [:fixture "public_values_schema"])
                                     :manifest_digest (get-in parsed [:manifest "manifestDigest"])}
                  :validation validation
                  :forensic_graph (-> view (dissoc :nodes) (assoc :node_hashes (mapv :node-hash (:nodes view))))
                  :canonical_node_files (mapv #(str "nodes/" (:node-hash %) ".edn") (:nodes view))
                  :source_claims {:proof_cryptographic_validation :not_independently_verified
                                  :deployment_runtime_identity :not_independently_observed
                                  :economic_outcome :reconciled_against_retained_receipt_logs_and_state_log
                                  :research_acceptance :not_inferred
                                  :reward_obligation :not_inferred}
                  :independent_reconstruction {:input_root "the retained bundle directory"
                                               :replayed_at (get-in parsed [:report "operator" "replayed_utc"])}}
          report-file (io/file out "verification-report.json")]
      (spit report-file (json/write-str report {:escape-slash false :indent true}))
      (assoc result :report report :report-path (.getPath report-file)))))

(defn -main [& args]
  (when-not (= 2 (count args))
    (throw (ex-info "Usage: <bundle-dir> <new-output-dir>" {:arguments args})))
  (let [{:keys [validation report-path view]} (write-reconstruction! (first args) (second args))]
    (println (str "report: " report-path))
    (println (str "forensic_nodes: " (count (:nodes view))))
    (println (str "validation: " (if (:valid? validation) "PASS" "FAIL")))
    (println (str "typed_gaps: " (count (:gaps validation))))
    (when-not (:valid? validation) (System/exit 1))))
