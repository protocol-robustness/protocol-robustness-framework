(ns resolver-sim.benchmark.claim-qualification-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [resolver-sim.benchmark.claim-qualification :as sut]
            [resolver-sim.benchmark.verification-result :as vr]
            [resolver-sim.evidence.deployment-bound :as deployment-bound]))

(def ^:private claim
  {:claim/id :claim/operation-reconciled
   :claim/subject {:operation/id :operation/one
                   :chain-instance/root "sha256:chain"}
   :claim/cutpoint {:cutpoint/id :cutpoint/operation-evidence
                    :cutpoint/source-root "sha256:evidence"}
   :claim/source-roots {:operation/root "sha256:operation"
                        :evidence/root "sha256:evidence"}})

(def ^:private profile
  {:qualification-profile/schema "prf-claim-qualification-profile.v1"
   :qualification-profile/id :profile/operation-reconciliation
   :qualification-profile/checks
   [{:check/id :accounting/reconciliation
     :check/dimension :semantic-accounting
     :check/validator-id :prf/accounting-validator
     :check/validator-revision "accounting-validator.v1"
     :check/required? true
     :check/required-level :semantic
     :check/source-root-keys [:operation/root :evidence/root]}
    {:check/id :proof/cryptographic
     :check/dimension :cryptographic-proof
     :check/validator-id :prf/sp1-proof-validator
     :check/validator-revision "sp1-proof-validator.v1"
     :check/required? false
     :check/required-level :deployment-bound
     :check/source-root-keys [:operation/root :evidence/root]}]})

(defn- validator-result [status]
  (vr/build-result
   {:subject (:claim/subject claim)
    :requested-level :semantic
    :achieved-level (when (= :verified status) :semantic)
    :checks [{:check/id :input-check
              :check/status (case status
                              :verified :pass
                              :invalid :fail
                              :blocked :blocked
                              :not-run :not-run
                              :not-applicable :not-applicable)
              :required? true
              :check/reason (when (not= :verified status) "test-result")}]
    :source-roots (:claim/source-roots claim)
    :verifier {:id :prf/accounting-validator :revision "accounting-validator.v1"}
    :producer-assertions {:status :verified}}))

(defn- check-runner [status overrides]
  {:revision "accounting-validator.v1"
   :run (fn [_ _]
          (merge {:verification-result (validator-result status)
                  :observed-subject (:claim/subject claim)
                  :source-roots (:claim/source-roots claim)
                  :findings {:reconciled? (= :verified status)}}
                 overrides))})

(defn- read-json-file [file]
  (with-open [reader (io/reader file)] (json/read reader)))

(defn- pro28-retained-records []
  (let [root (io/file "examples/partial-fill/evidence/pro-28-v1/raw/fresh-replay-20261018")
        files {:manifest (io/file root "deployment-manifest.json")
               :witness (io/file root "witness.json")
               :fixture (io/file root "proof-fixture.json")
               :provenance (io/file root "witness-provenance.json")
               :source-bundle (io/file root "source/source-bound-entitlement-v1.json")
               :capture-metadata (io/file root "capture-metadata.json")
               :live-chain-check (io/file root "live-chain-check.json")
               :final-state (io/file root "final-state.log")
               :fork-test-log (io/file root "fork-test.log")
               :deploy (io/file root "broadcast/deployment.json")
               :run (io/file root "broadcast/funding.json")
               :activate (io/file root "broadcast/activation.json")
               :withdrawal (io/file root "broadcast/withdrawals.json")}
        parsed (into {} (map (fn [[key file]]
                               [key (if (#{:final-state :fork-test-log} key)
                                      (slurp file) (read-json-file file))]) files))
        hashes (into {} (map (fn [[key file]]
                               [key (deployment-bound/file-sha256 file)])
                             files))
        report {"byte_identical_artifacts"
                {"manifest" {"sha256" (:manifest hashes)}
                 "witness" {"sha256" (:witness hashes)}
                 "proof_fixture" {"sha256" (:fixture hashes)}}
                "foundry_fork_tests"
                {"status" (if (str/includes? (:fork-test-log parsed)
                                             "4 tests passed, 0 failed, 0 skipped")
                            "pass" "fail")}}
        broadcasts (select-keys parsed [:deploy :run :activate :withdrawal])]
    (assoc parsed :report report :broadcasts broadcasts :artifact-hashes hashes)))

(defn- run-qualification [status overrides trust]
  (sut/qualify
   {:profile profile
    :subject claim
    :evidence {:producer-status :verified}
    :validators {:prf/accounting-validator (check-runner status overrides)}
    :trusted-validators trust}))

(deftest exact-claim-and-separate-dimensions-qualify
  (let [result (run-qualification :verified {}
                                  {:prf/accounting-validator "accounting-validator.v1"})]
    (is (= :verified (:status result)))
    (is (= :verified (get-in result [:dimensions :semantic-accounting :status])))
    (is (= :blocked (get-in result [:dimensions :cryptographic-proof :status])))
    (is (= "sha256:operation" (get-in result [:claim :claim/source-roots :operation/root]))) (is (= {:accounting/reconciliation {:status :verified}} (:producer-assertions result))) (is (true? (get-in result [:independent-findings :accounting/reconciliation :reconciled?])))
    (is (:valid? (sut/validate-qualification result)))))

(deftest qualification-is-deterministic-for-fixed-inputs
  (let [input {:profile (update profile :qualification-profile/checks #(subvec % 0 1))
               :subject claim
               :evidence {:producer-status :verified}
               :validators {:prf/accounting-validator (check-runner :verified {})}
               :trusted-validators {:prf/accounting-validator "accounting-validator.v1"}}
        first-result (sut/qualify input)
        second-result (sut/qualify input)]
    (is (= first-result second-result))
    (is (= (sut/profile-root (:profile input))
           (:qualification-profile/root first-result)))))

(deftest cutpoint-must-reference-a-committed-source-root
  (let [profile (update profile :qualification-profile/checks #(subvec % 0 1))
        subject (assoc-in claim [:claim/cutpoint :cutpoint/source-root] "sha256:unbound")
        validator (check-runner :verified {})]
    (is (thrown? Exception
                 (sut/qualify
                  {:profile profile
                   :subject subject
                   :evidence {}
                   :validators {:prf/accounting-validator validator}
                   :trusted-validators
                   {:prf/accounting-validator "accounting-validator.v1"}})))))

(deftest required-check-fails-closed-on-validator-outcomes
  (doseq [[validator-status expected]
          [[:invalid :invalid] [:blocked :blocked] [:not-run :blocked]
           [:not-applicable :blocked]]]
    (testing (name validator-status)
      (is (= expected
             (:status (run-qualification
                       validator-status {}
                       {:prf/accounting-validator "accounting-validator.v1"})))))))

(deftest trust-context-and-subject-binding-are-enforced
  (let [untrusted (run-qualification :verified {} {})
        wrong-subject (run-qualification
                       :verified {:observed-subject {:operation/id :operation/other}}
                       {:prf/accounting-validator "accounting-validator.v1"})
        wrong-roots (run-qualification
                     :verified {:source-roots {:operation/root "sha256:other"
                                               :evidence/root "sha256:evidence"}}
                     {:prf/accounting-validator "accounting-validator.v1"})]
    (is (= :blocked (:status untrusted)))
    (is (= :invalid (:status wrong-subject)))
    (is (= :invalid (:status wrong-roots)))
    (is (= :claim-subject-mismatch
           (:check/reason (first (:checks wrong-subject)))))))

(deftest producer-status-and-verification-labels-cannot-promote-a-check
  (let [blocked (run-qualification :blocked
                                   {:findings {:producer-status :verified
                                               :verification-label true}}
                                   {:prf/accounting-validator "accounting-validator.v1"})
        forged-report (assoc blocked :status :verified)]
    (is (= :blocked (:status blocked)))
    (is (not (:valid? (sut/validate-qualification forged-report))))))

(deftest invalid-profile-and-callback-failure-are-contained
  (let [bad-profile (assoc profile :qualification-profile/checks
                           [{:check/id :duplicate :check/dimension :integrity
                             :check/validator-id :v :check/validator-revision "1"
                             :check/required? true :check/required-level :integrity
                             :check/source-root-keys []}
                            {:check/id :duplicate :check/dimension :integrity
                             :check/validator-id :v :check/validator-revision "1"
                             :check/required? true :check/required-level :integrity
                             :check/source-root-keys []}])
        throwing (assoc (check-runner :verified {}) :run
                        (fn [_ _] (throw (ex-info "x" {}))))]
    (is (thrown? Exception
                 (sut/qualify {:profile bad-profile :subject claim :evidence {}
                               :validators {} :trusted-validators {}})))
    (let [result (sut/qualify
                  {:profile (update profile :qualification-profile/checks #(subvec % 0 1))
                   :subject claim :evidence {}
                   :validators {:prf/accounting-validator throwing}
                   :trusted-validators {:prf/accounting-validator "accounting-validator.v1"}})]
      (is (= :blocked (:status result)))
      (is (= :validator-execution-failed
             (:check/reason (first (:checks result))))))))

(deftest lower-verification-level-cannot-satisfy-stronger-claim
  (let [low-result (vr/build-result
                    {:subject (:claim/subject claim) :requested-level :integrity :achieved-level :integrity :checks [{:check/id :integrity :check/status :pass :required? true}] :source-roots (:claim/source-roots claim) :verifier {:id :prf/accounting-validator :revision "accounting-validator.v1"}})
        result (sut/qualify
                {:profile (update profile :qualification-profile/checks #(subvec % 0 1))
                 :subject claim
                 :evidence {}
                 :trusted-validators
                 {:prf/accounting-validator "accounting-validator.v1"}
                 :validators
                 {:prf/accounting-validator
                  {:revision "accounting-validator.v1"
                   :run (fn [_ _] {:verification-result low-result :observed-subject (:claim/subject claim) :source-roots (:claim/source-roots claim)})}}})]
    (is (= :invalid (:status result)))
    (is (= :required-level-not-achieved
           (:check/reason (first (:checks result)))))))

(deftest conflicting-independent-observations-remain-visible-and-fail
  (let [root-keys [:operation/root]
        check-spec (fn [id validator-id]
                     {:check/id id :check/dimension :chain-observation
                      :check/validator-id validator-id
                      :check/validator-revision "v1"
                      :check/required? true :check/required-level :binding
                      :check/source-root-keys root-keys})
        profile {:qualification-profile/schema "prf-claim-qualification-profile.v1"
                 :qualification-profile/id :profile/conflicting-observations
                 :qualification-profile/checks
                 [(check-spec :observation/a :prf/observer-a)
                  (check-spec :observation/b :prf/observer-b)]}
        runner (fn [validator-id block-hash]
                 {:revision "v1"
                  :run (fn [_ _] {:verification-result (vr/build-result {:subject (:claim/subject claim) :requested-level :binding :achieved-level :binding :checks [{:check/id :block-context :check/status :pass :required? true}] :source-roots {:operation/root (get-in claim [:claim/source-roots :operation/root])} :verifier {:id validator-id :revision "v1"}}) :observed-subject (:claim/subject claim) :source-roots {:operation/root (get-in claim [:claim/source-roots :operation/root])} :observations {:chain/block-hash block-hash}})})
        result (sut/qualify
                {:profile profile :subject claim :evidence {}
                 :trusted-validators {:prf/observer-a "v1" :prf/observer-b "v1"}
                 :validators {:prf/observer-a (runner :prf/observer-a "0xaaa")
                              :prf/observer-b (runner :prf/observer-b "0xbbb")}})]
    (is (= :invalid (:status result)))
    (is (= :conflicting-independent-observations
           (:check/reason (first (:checks result)))))
    (is (= #{:observation/a :observation/b}
           (set (get-in result [:conflicts :chain/block-hash :checks]))))))

(deftest retained-pro28-replay-qualifies-only-the-supported-claim
  (let [records (pro28-retained-records)
        validation (deployment-bound/validate-records records)
        manifest (:manifest records)
        capture (:capture-metadata records)
        live-check (:live-chain-check records)
        captured-identity (get capture "localChainIdentity")
        observed-identity (get live-check "localChainIdentity")
        chain-fingerprint (fn [identity]
                            (str "sha256:"
                                 (deployment-bound/sha256-hex
                                  (.getBytes
                                   (str "prf.local-chain-fingerprint.v1\n"
                                        (get identity "chainId") "\n"
                                        (str/lower-case (get identity "genesisBlockHash")) "\n")
                                   "UTF-8"))))
        chain-context-valid?
        (fn [evidence]
          (let [manifest (:manifest evidence)
                capture (:capture-metadata evidence)
                live-check (:live-chain-check evidence)
                captured (get capture "localChainIdentity")
                observed (get live-check "localChainIdentity")]
            (and (= "prf.local-chain-fingerprint.v1" (get captured "schema"))
                 (= (select-keys captured ["schema" "chainId" "genesisBlockHash" "fingerprint"])
                    (select-keys observed ["schema" "chainId" "genesisBlockHash" "fingerprint"]))
                 (= (get manifest "chainId") (get captured "chainId"))
                 (= (get manifest "chainId") (get live-check "chainId"))
                 (= (chain-fingerprint captured) (get captured "fingerprint"))
                 (map? (get manifest "localOnly"))
                 (str/starts-with? (get capture "classification" "") "localOnly")
                 (str/starts-with? (get live-check "classification" "") "localOnly")
                 (= "passed" (get live-check "status"))
                 (false? (get capture "publicNetworkTransactionsSubmitted")))))
        roots (into (sorted-map)
                    (map (fn [[artifact digest]]
                           [(keyword (str (name artifact) "/file-root"))
                            (str "sha256:" digest)]))
                    (:artifact-hashes records))
        subject {:operation/id (get manifest "operationId")
                 :chain/id (get manifest "chainId")
                 :chain-instance/root (get captured-identity "fingerprint")
                 :prepared-operation/root (get manifest "preparedOperationRoot")
                 :configuration/root (get manifest "configurationRoot")}
        root-keys (vec (sort (keys roots)))
        profile
        {:qualification-profile/schema "prf-claim-qualification-profile.v1"
         :qualification-profile/id :profile/pro28-retained-payout-reconciliation
         :qualification-profile/checks
         [{:check/id :pro28/artifact-integrity :check/dimension :integrity
           :check/validator-id :prf/pro28-retained-record-validator
           :check/validator-revision "deployment-bound-retained-records.v0"
           :check/required? true :check/required-level :integrity :check/source-root-keys root-keys}
          {:check/id :pro28/operation-source-binding :check/dimension :source-binding
           :check/validator-id :prf/pro28-retained-record-validator
           :check/validator-revision "deployment-bound-retained-records.v0"
           :check/required? true :check/required-level :binding :check/source-root-keys root-keys}
          {:check/id :pro28/chain-instance-binding :check/dimension :chain-observation
           :check/validator-id :prf/pro28-retained-record-validator
           :check/validator-revision "deployment-bound-retained-records.v0"
           :check/required? true :check/required-level :binding :check/source-root-keys root-keys}
          {:check/id :pro28/receipt-payout-reconciliation :check/dimension :semantic-accounting
           :check/validator-id :prf/pro28-retained-record-validator
           :check/validator-revision "deployment-bound-retained-records.v0"
           :check/required? true :check/required-level :semantic :check/source-root-keys root-keys}
          {:check/id :pro28/admission-call-attribution :check/dimension :source-binding
           :check/validator-id :prf/pro28-retained-record-validator
           :check/validator-revision "deployment-bound-retained-records.v0"
           :check/required? false :check/required-level :binding :check/source-root-keys root-keys}
          {:check/id :pro28/cryptographic-proof-verification :check/dimension :cryptographic-proof
           :check/validator-id :prf/sp1-groth16-verifier
           :check/validator-revision "unavailable-in-prf-v0"
           :check/required? false :check/required-level :deployment-bound :check/source-root-keys root-keys}
          {:check/id :pro28/chain-inclusion :check/dimension :chain-inclusion
           :check/validator-id :prf/trusted-header-verifier
           :check/validator-revision "not-configured-v0"
           :check/required? false :check/required-level :deployment-bound :check/source-root-keys root-keys}
          {:check/id :pro28/chain-finality :check/dimension :finality
           :check/validator-id :prf/finality-verifier
           :check/validator-revision "not-configured-v0"
           :check/required? false :check/required-level :deployment-bound :check/source-root-keys root-keys}]}
        validator-id :prf/pro28-retained-record-validator
        revision "deployment-bound-retained-records.v0"
        selected-checks
        (fn [check]
          (case (:check/id check)
            :pro28/artifact-integrity
            [:source-files-match-report :manifest-digest-recomputed :proof-bytes-hash-bound]
            :pro28/operation-source-binding
            [:fixture-identities-bound :proof-witness-manifest-crosslinks
             :prepared-context-bound :reserve-input-bound
             :public-values-structure-and-binding]
            :pro28/chain-instance-binding [:chain-instance-identity-consistent]
            :pro28/receipt-payout-reconciliation
            [:witness-derived-public-values :source-bundle-content-bound
             :all-retained-receipt-records-consistent :payout-transfer-logs-reconcile]
            :pro28/admission-call-attribution [:admission-call-carried-exact-proof-and-public-values]
            []))
        runner
        {:revision revision
         :run (fn [evidence check]
                (let [validation (deployment-bound/validate-records evidence)
                      selected (selected-checks check)
                      selected-statuses (if (= :pro28/chain-instance-binding (:check/id check))
                                          {:chain-instance-identity-consistent
                                           (if (chain-context-valid? evidence) :pass :fail)}
                                          (into {}
                                                (map (fn [check-id]
                                                       [check-id (case (get-in validation [:checks check-id])
                                                                   true :pass
                                                                   false :fail
                                                                   :not-run)]))
                                                selected))
                      all-pass? (and (seq selected-statuses)
                                     (every? #{:pass} (vals selected-statuses)))
                      requested-level (:check/required-level check)
                      result (vr/build-result
                              {:subject (assoc (:claim/subject (:qualification/subject evidence))
                                               :chain-instance/root
                                               (get-in evidence [:capture-metadata "localChainIdentity" "fingerprint"]))
                               :requested-level requested-level
                               :achieved-level (when all-pass? requested-level)
                               :checks (mapv (fn [[check-id status]]
                                               (cond-> {:check/id check-id
                                                        :check/status status
                                                        :required? true}
                                                 (= :not-run status)
                                                 (assoc :check/reason "retained validator did not establish this check")))
                                             selected-statuses)
                               :source-roots (:qualification/source-roots evidence)
                               :verifier {:id validator-id :revision revision}
                               :independent-findings
                               {:recomputed-checks (select-keys (:checks validation) selected)
                                :local-chain-identity (when (= :pro28/chain-instance-binding
                                                               (:check/id check))
                                                        {:fingerprint (get captured-identity "fingerprint")
                                                         :chain-id (get captured-identity "chainId")
                                                         :valid? (chain-context-valid? evidence)})
                                :gaps (:gaps validation)}})
                      receipt-block-hashes (->> (vals (:broadcasts evidence))
                                                (mapcat #(get % "receipts"))
                                                (keep #(get % "blockHash"))
                                                distinct sort vec)]
                  {:verification-result result
                   :observed-subject (assoc (:claim/subject (:qualification/subject evidence))
                                            :chain-instance/root
                                            (get-in evidence [:capture-metadata "localChainIdentity" "fingerprint"]))
                   :source-roots (:qualification/source-roots evidence)
                   :findings {:checks (select-keys (:checks validation) selected)
                              :chain-instance-identity-consistent (chain-context-valid? evidence)
                              :full-validator-valid? (:valid? validation)}
                   :gaps (:gaps validation)
                   :observations {:receipt/block-hashes receipt-block-hashes}}))}
        qualification
        (sut/qualify
         {:profile profile
          :subject {:claim/id :claim/pro28-retained-payouts-match-entitlements
                    :claim/subject subject
                    :claim/cutpoint {:cutpoint/id :cutpoint/pro28-withdrawal-payouts
                                     :cutpoint/source-root (:withdrawal/file-root roots)}
                    :claim/source-roots roots}
          :evidence (assoc records :qualification/subject {:claim/subject subject}
                           :qualification/source-roots roots)
          :trusted-validators {validator-id revision}
          :validators {validator-id runner}})
        report-checks (into {} (map (juxt :check/id identity) (:checks qualification)))]
    (is (false? (:valid? validation))
        "the full receipt validator reports unrelated call-attribution/state gaps")
    (is (true? (get-in validation [:checks :witness-derived-public-values])))
    (is (true? (get-in validation [:checks :payout-transfer-logs-reconcile])))
    (is (= :verified (:status qualification))
        "only selected artifact, chain-identity and payout-reconciliation claims qualify")
    (is (= :verified (get-in qualification [:dimensions :semantic-accounting :status])))
    (is (= :verified (get-in qualification [:dimensions :chain-observation :status])))
    (is (= :blocked (get-in qualification [:dimensions :cryptographic-proof :status])))
    (is (= :blocked (get-in qualification [:dimensions :chain-inclusion :status])))
    (is (= :blocked (get-in qualification [:dimensions :finality :status])))
    (is (= :blocked (:check/status (:pro28/admission-call-attribution report-checks))))
    (is (= (get captured-identity "fingerprint")
           (get-in qualification [:claim :claim/subject :chain-instance/root])))
    (is (seq (:gaps qualification)))
    (is (not-empty (:independent-findings qualification)))
    (is (:valid? (sut/validate-qualification qualification)))))
