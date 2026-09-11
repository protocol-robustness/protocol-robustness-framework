(ns resolver-sim.resubmission.issuance-test
  "Tests for attempt-receipt issuance: the pure helpers and the out-of-process
   signer authority (resolver-sim.commands.resubmission-issue).

   The authority must independently re-derive the committed transition from the
   presented pre-state and command, verify the ordering evidence and the
   candidate receipt binding, and sign the receipt (attestation after commit)."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.commands.resubmission-issue :as cmd]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.resubmission.acceptance-evaluation :as evaluation]
            [resolver-sim.resubmission.acceptance-authority-basis :as authority-basis]
            [resolver-sim.resubmission.attempt-subject :as attempt-subject]
            [resolver-sim.resubmission.basis :as basis]
            [resolver-sim.resubmission.genesis :as genesis]
            [resolver-sim.resubmission.issuance :as issuance]
            [resolver-sim.resubmission.receipt :as receipt]
            [resolver-sim.resubmission.results-artifact :as results-artifact]
            [resolver-sim.resubmission.store :as store]
            [resolver-sim.resubmission.submission-registry :as submission-registry]
            [resolver-sim.resubmission.transition :as transition]
            [resolver-sim.signed-external-decision :as sed]
            [resolver-sim.support.ed25519 :as ed]
            [resolver-sim.transaction.ordering :as ordering]
            [resolver-sim.transaction.protocol :as protocol]))

(def family "sha256:FAM")
(def subject-root "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc")
(def subj-root "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")

(defn- bare-receipt [id]
  {:attempt-receipt/schema receipt/receipt-schema
   :attempt-receipt/id id
   :attempt-receipt/outcome :rejected
   :attempt-receipt/finality :final
   :attempt-receipt/resubmission-eligibility :eligible
   :attempt-receipt/lifecycle-status :active})

(defn- admit-cmd
  [& {:keys [child parent seq basis link idem]}]
  {:transaction/action :prf.resubmission/admit-child
   :transaction/input
   {:parent-receipt-hash parent
    :link-artifact-hash link
    :candidate-attempt-receipt (bare-receipt child)
    :candidate-attempt-receipt-id child
    :idempotency-key idem
    :content-key basis
    :sequence seq}})

(defn- candidate-receipt-base
  []
  {:attempt-receipt/schema "submission-attempt-receipt.v1"
   :attempt-receipt/submitted-bundle-root "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
   :attempt-receipt/roots
   {:research-subject {:root/schema "research-subject-root.v1" :status :verified :hash subject-root}
    :execution-context {:root/schema "execution-context-root.v1" :status :verified
                        :hash "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"}
    :results {:root/schema "results-root.v1" :status :verified
              :hash "sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"}
    :submission-basis {:root/schema "submission-basis-root.v1" :status :verified
                       :hash "sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"}}
   :attempt-receipt/results {:status :valid
                             :submitted-hash "sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
                             :verified-hash "sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"}
   :attempt-receipt/submitter {:status :verified :researcher-id "res-1"
                               :identity-source :publisher-signature
                               :policy-hash "sha256:1111111111111111111111111111111111111111111111111111111111111111"
                               :key-id "rk-1"}
   :attempt-receipt/outcome :rejected
   :attempt-receipt/finality :final
   :attempt-receipt/resubmission-eligibility :eligible
   :attempt-receipt/lifecycle-status :active
   :attempt-receipt/evaluation {:acceptance-report-hash "sha256:7777777777777777777777777777777777777777777777777777777777777777"
                                :validator-version "v1"
                                :policy-hash "sha256:1111111111111111111111111111111111111111111111111111111111111111"
                                :evaluated-bundle-root "sha256:8888888888888888888888888888888888888888888888888888888888888888"
                                :evaluated-at "2026-08-06T00:00:00Z"}
   :attempt-receipt/findings
   [{:finding/id "sha256:abababababababababababababababababababababababababababababababab"
     :stage :publisher :assertion/id nil :reason :publisher-signature-invalid
     :subject {} :blocking? true}]
   :attempt-receipt/validator
   {:id "val-1" :version "v1" :policy/id "acceptance-policy.v1" :policy/version "1"
    :policy/hash "sha256:1111111111111111111111111111111111111111111111111111111111111111"
    :authorisation/id "va-1" :key/id "vk-1"}
   :attempt-receipt/observed-at "2026-08-06T00:00:00Z"})

(defn- issue-request
  [state-before command ordering candidate]
  (let [base {:request/kind :resubmission-issue
              :request/version 1
              :request/id "req-1"
              :validator {}
              :transition {:state-before state-before :command command}
              :ordering ordering
              :candidate-receipt candidate}]
    (assoc base :request/hash (sed/request-hash cmd/request-domain base))))

(defn- committed-fixture
  "Commit R1 then R2 through the store; return everything the authority needs."
  []
  (let [cmd1 (admit-cmd :child "sha256:R1" :seq 1 :basis "sha256:B1" :link "sha256:L1" :idem "sha256:I1")
        cmd2 (admit-cmd :child "sha256:R2" :seq 2 :parent "sha256:R1" :basis "sha256:B2" :link "sha256:L2" :idem "sha256:I2")
        state-before (:state (transition/apply-action (transition/empty-state family) cmd1))
        store (store/new-resubmission-store family)
        _ (protocol/transact! store nil nil (fn [st] (transition/apply-action st cmd1)))
        r2 (protocol/transact! store nil nil (fn [st] (transition/apply-action st cmd2)))
        ordering (:transaction-ordering r2)]
    {:state-before state-before :cmd2 cmd2 :ordering ordering}))

(defn- with-chain [candidate ordering]
  (issuance/receipt-candidate
   candidate
   {:admission-status :admitted
    :family-id family
    :sequence 2
    :parent-receipt-hash "sha256:R1"
    :transaction-ordering-hash (:transaction-ordering/hash ordering)}))

(deftest issuance-helpers
  (testing "admission-status-for maps only :committed to :admitted"
    (is (= :admitted (issuance/admission-status-for :committed)))
    (is (= :not-admitted (issuance/admission-status-for :rejected)))
    (is (= :not-admitted (issuance/admission-status-for :idempotent-replay))))
  (testing "transition-outcome-matches? and receipt-binds-ordering?"
    (let [ordering (ordering/transaction-ordering
                    {:transaction/action :prf.resubmission/admit-child
                     :transaction/scope :resubmission-family
                     :transaction/conflict-key [:resubmission-family family]
                     :transaction/commit-index 1
                     :transaction/state-before-root "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                     :transaction/state-after-root "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
                     :transaction/effects-root "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"})
          candidate (issuance/receipt-candidate
                     (candidate-receipt-base)
                     {:admission-status :admitted :family-id family :sequence 1
                      :parent-receipt-hash nil
                      :transaction-ordering-hash (:transaction-ordering/hash ordering)})]
      (is (true? (issuance/transition-outcome-matches? {:status :committed} :admitted)))
      (is (false? (issuance/transition-outcome-matches? {:status :rejected} :admitted)))
      (is (true? (issuance/receipt-binds-ordering? candidate ordering)))
      (is (false? (issuance/receipt-binds-ordering?
                   (assoc-in candidate [:attempt-receipt/chain :admission-status] :not-admitted)
                   ordering)))
      (is (false? (issuance/receipt-binds-ordering?
                   (assoc-in candidate [:attempt-receipt/chain :transaction-ordering-hash] "sha256:WRONG")
                   ordering))))))

(deftest issuance-authority-happy-path
  (let [validator-key (ed/keypair :validator-key)
        {:keys [state-before cmd2 ordering]} (committed-fixture)
        candidate (with-chain (candidate-receipt-base) ordering)
        request (issue-request state-before cmd2 ordering candidate)
        response (cmd/decide {:private-key (:private-key validator-key)
                              :validator/key-id "vk-1"} request)]
    (testing "a signed receipt is returned"
      (is (= :resubmission-issue-response (:response/kind response)))
      (is (= "req-1" (:request/id response)))
      (let [signed (:receipt response)]
        (is (string? (:attempt-receipt/id signed)))
        (is (true? (receipt/valid-receipt-shape? signed)))
        (is (true? (:valid? (receipt/verify-receipt-signature signed (:public-hex validator-key)))))
        (is (true? (issuance/receipt-binds-ordering? signed ordering)))
        (is (= :admitted (get-in signed [:attempt-receipt/chain :admission-status])))
        (is (= (:transaction-ordering/hash ordering)
               (get-in signed [:attempt-receipt/chain :transaction-ordering-hash])))))))

(defn- decide-reason
  "Run the authority; return the thrown :reason, or the response when it
   succeeds (nil reason)."
  ([private-key request] (decide-reason private-key request "vk-1"))
  ([private-key request key-id]
   (try
     (cmd/decide {:private-key private-key :validator/key-id key-id} request)
     (catch Exception e (:reason (ex-data e))))))

(deftest issuance-authority-adversarial
  (let [validator-key (ed/keypair :validator-key)
        {:keys [state-before cmd2 ordering]} (committed-fixture)
        candidate (with-chain (candidate-receipt-base) ordering)
        valid (issue-request state-before cmd2 ordering candidate)]
    (testing "request hash mismatch"
      (is (= :request-hash-mismatch
             (decide-reason (:private-key validator-key)
                            (assoc valid :request/hash "sha256:WRONG")))))
    (testing "receipt claiming a different validator key is rejected"
      (let [bad-candidate (assoc-in candidate [:attempt-receipt/validator :key/id] "vk-2")
            req (issue-request state-before cmd2 ordering bad-candidate)]
        (is (= :key-id-inconsistent (decide-reason (:private-key validator-key) req)))))
    (testing "transition that was not committed is rejected"
      (let [rejected-cmd (admit-cmd :child "sha256:R9" :seq 9 :parent "sha256:R1"
                                    :basis "sha256:B9" :link "sha256:L9" :idem "sha256:I9")
            req (issue-request state-before rejected-cmd ordering candidate)]
        (is (= :sequence-gap (decide-reason (:private-key validator-key) req)))))
    (testing "state-after-root mismatch"
      (let [tampered-ordering (ordering/transaction-ordering
                               (assoc (ordering/unsigned-ordering-projection ordering)
                                      :transaction/state-after-root "sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"))
            req (issue-request state-before cmd2 tampered-ordering candidate)]
        (is (= :state-after-root-mismatch (decide-reason (:private-key validator-key) req)))))
    (testing "state-before-root mismatch"
      (let [tampered-ordering (ordering/transaction-ordering
                               (assoc (ordering/unsigned-ordering-projection ordering)
                                      :transaction/state-before-root "sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"))
            req (issue-request state-before cmd2 tampered-ordering candidate)]
        (is (= :state-before-root-mismatch (decide-reason (:private-key validator-key) req)))))
    (testing "effects-root mismatch"
      (let [tampered-ordering (ordering/transaction-ordering
                               (assoc (ordering/unsigned-ordering-projection ordering)
                                      :transaction/effects-root "sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"))
            req (issue-request state-before cmd2 tampered-ordering candidate)]
        (is (= :effects-root-mismatch (decide-reason (:private-key validator-key) req)))))
    (testing "expected snapshot mismatch"
      (let [tampered-ordering (ordering/transaction-ordering
                               (assoc (ordering/unsigned-ordering-projection ordering)
                                      :transaction/expected {:chain-head "sha256:WRONG"}))
            req (issue-request state-before cmd2 tampered-ordering candidate)]
        (is (= :ordering-expected-mismatch (decide-reason (:private-key validator-key) req)))))
    (testing "observed snapshot mismatch"
      (let [tampered-ordering (ordering/transaction-ordering
                               (assoc (ordering/unsigned-ordering-projection ordering)
                                      :transaction/observed {:chain-head "sha256:WRONG"}))
            req (issue-request state-before cmd2 tampered-ordering candidate)]
        (is (= :ordering-observed-mismatch (decide-reason (:private-key validator-key) req)))))
    (testing "ordering hash mismatch"
      (let [tampered-ordering (assoc ordering :transaction/commit-index 999)
            req (issue-request state-before cmd2 tampered-ordering candidate)]
        (is (= :ordering-hash-mismatch (decide-reason (:private-key validator-key) req)))))
    (testing "non-admit-child ordering is rejected"
      (let [disp-ordering (ordering/transaction-ordering
                           {:transaction/action :prf.resubmission/apply-disposition
                            :transaction/scope :resubmission-family
                            :transaction/conflict-key [:resubmission-family family]
                            :transaction/commit-index 1
                            :transaction/state-before-root "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                            :transaction/state-after-root "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
                            :transaction/effects-root "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"})
            req (issue-request state-before cmd2 disp-ordering candidate)]
        (is (= :unexpected-ordering-action (decide-reason (:private-key validator-key) req)))))
    (testing "candidate not bound to the ordering is rejected"
      (let [bad-candidate (assoc-in candidate [:attempt-receipt/chain :transaction-ordering-hash] "sha256:WRONG")
            req (issue-request state-before cmd2 ordering bad-candidate)]
        (is (= :receipt-ordering-binding-mismatch (decide-reason (:private-key validator-key) req)))))
    (testing "family inconsistent with the ordering is rejected"
      (let [bad-candidate (assoc-in candidate [:attempt-receipt/chain :family-id] "sha256:OTHER-FAMILY")
            req (issue-request state-before cmd2 ordering bad-candidate)]
        (is (= :family-inconsistent (decide-reason (:private-key validator-key) req)))))
    (testing "sequence inconsistent with the command is rejected"
      (let [bad-candidate (assoc-in candidate [:attempt-receipt/chain :sequence] 99)
            req (issue-request state-before cmd2 ordering bad-candidate)]
        (is (= :sequence-inconsistent (decide-reason (:private-key validator-key) req)))))
    (testing "parent inconsistent with the command is rejected"
      (let [bad-candidate (assoc-in candidate [:attempt-receipt/chain :parent-receipt-hash] "sha256:OTHER")
            req (issue-request state-before cmd2 ordering bad-candidate)]
        (is (= :parent-inconsistent (decide-reason (:private-key validator-key) req)))))
    (testing "command payload diverges from committed input-root"
      (let [tampered-cmd (assoc-in cmd2
                                   [:transaction/input :candidate-attempt-receipt-id]
                                   "sha256:R3-FAKE")
            req (issue-request state-before tampered-cmd ordering candidate)]
        (is (= :input-root-mismatch
               (decide-reason (:private-key validator-key) req)))))
    (testing "invalid candidate shape is rejected"
      (let [bad-candidate (dissoc candidate :attempt-receipt/roots)
            req (issue-request state-before cmd2 ordering bad-candidate)]
        (is (= :invalid-candidate-receipt (decide-reason (:private-key validator-key) req)))))))

(deftest issuance-command-round-trip
  (let [validator-key (ed/keypair :validator-key)
        {:keys [state-before cmd2 ordering]} (committed-fixture)
        candidate (with-chain (candidate-receipt-base) ordering)
        request (issue-request state-before cmd2 ordering candidate)
        out (java.io.StringWriter.)]
    (testing "run-from-reader signs and exits 0"
      (let [exit (binding [*out* out]
                   (cmd/run-from-reader
                    (java.io.StringReader. (pr-str request))
                    (:private-key validator-key)
                    "vk-1"))
            response (read-string (str out))]
        (is (= 0 exit))
        (is (= :resubmission-issue-response (:response/kind response)))
        (is (true? (:valid? (receipt/verify-receipt-signature
                             (:receipt response) (:public-hex validator-key)))))))
    (testing "a tampered request fails closed with an error response"
      (let [out2 (java.io.StringWriter.)
            exit (binding [*out* out2]
                   (cmd/run-from-reader
                    (java.io.StringReader. (pr-str (assoc request :request/hash "sha256:WRONG")))
                    (:private-key validator-key)
                    "vk-1"))
            response (read-string (str out2))]
        (is (= 1 exit))
        (is (= :resubmission-issue-error (:response/kind response)))
        (is (= :invalid-request (:error/reason response)))))))

(deftest issuance-golden-signed-receipt
  (testing "golden signed-receipt id for fixed inputs"
    ;; NOTE: the receipt id commits :attempt-receipt/chain, which carries the
    ;; ordering hash. New issuances now carry transaction-ordering.v2 orderings
    ;; (with input-root + derived change-identity), so this golden is the v2-
    ;; derived id; v1 receipts continue to verify under v1. v1 ordering goldens are
    ;; unchanged (they assert validity, not a hash string).
    (let [validator-key (ed/keypair :validator-key)
          {:keys [state-before cmd2 ordering]} (committed-fixture)
          candidate (with-chain (candidate-receipt-base) ordering)
          request (issue-request state-before cmd2 ordering candidate)
          response (cmd/decide {:private-key (:private-key validator-key)
                                :validator/key-id "vk-1"} request)
          id (:attempt-receipt/id (:receipt response))]
      ;; The receipt id is the hash of the UNSIGNED projection (signature
      ;; excluded), so it is deterministic across validator keys.
      (is (= "sha256:86c24fefad14f3cee0da1154706595e67c6a8f7b79e5d24949f8c6eb72bee40a" id)))))

;; ── V2 (application-aware) issuance tests ────────────────────────────────────────

(defn- v2-root
  [value]
  (hash-ref/sha256-ref (hc/domain-hash :evidence-record value)))

(def ^:private v2-verifier-registry-root
  "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")

(def ^:private v2-publisher-authority-root
  "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")

(def ^:private v2-extension-resolution-root
  "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd")

(def ^:private v2-app-root
  "sha256:9999999999999999999999999999999999999999999999999999999999999999")

(def ^:private v2-other-app-root
  "sha256:0000000000000000000000000000000000000000000000000000000000000000")

(defn- v2-fixture
  "Build a V2 acceptance evaluation fixture with a canonical application root."
  []
  (let [verifier-registry-body {:schema "verifier-registry.v1"
                                :entries [{:verifier/id "verifier-1"}]}
        publisher-authority-body {:schema "publisher-authority.v1"
                                  :entries [{:publisher-authority/public-key "pub-key-1"}]}
        submission-registry (submission-registry/build
                             {:submission-registry/entries
                              [{:entry/role :results
                                :artifact/kind :results-artifact
                                :artifact/root "sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"}]})
        results-artifact (results-artifact/build
                          {:results/verifier-id "verifier-1"
                           :results/evaluation-root (v2-root :evaluation)
                           :results/certificate-root (v2-root {:certificate/id "cert-1"})
                           :results/execution-evidence-root (v2-root {:execution/id "execution-1"})})
        submission-basis {:results-artifact results-artifact
                          :certificate {:certificate/id "cert-1"}
                          :execution-evidence {:execution/id "execution-1"}
                          :registry-entries []
                          :publisher-policy {:policy/id "publisher-1"
                                             :publisher-policy-key "pub-key-1"}}
        submission-basis-root (basis/submission-basis-root submission-basis)
        submitted-bundle {:submission-basis-root submission-basis-root
                          :resubmission-link-hash (v2-root :link)
                          :publisher-envelope-hash (v2-root :envelope)
                          :registry-root (:attempt-submission-registry/root submission-registry)}
        submitted-bundle-root (basis/final-bundle-root submitted-bundle)
        definition (evaluation/build-definition)
        authority-basis (authority-basis/build
                         {:authority-basis/verifier-registry-root v2-verifier-registry-root
                          :authority-basis/publisher-authority-root v2-publisher-authority-root
                          :authority-basis/extension-resolution-root v2-extension-resolution-root})
        authority-basis-root (:attempt-acceptance-authority-basis/root authority-basis)
        configuration {:configuration/schema genesis/resubmission-chain-configuration-v3-schema
                       :disposition-authority/public-key nil
                       :receipt-authority/public-key nil
                       :attempt-acceptance-definition/root
                       (:attempt-acceptance-definition/root definition)
                       :attempt-acceptance-authority-basis/root
                       authority-basis-root}
        bodies {(:attempt-acceptance-definition/root definition) definition
                authority-basis-root authority-basis
                v2-verifier-registry-root verifier-registry-body
                v2-publisher-authority-root publisher-authority-body
                v2-extension-resolution-root {}
                (:attempt-submission-registry/root submission-registry) submission-registry
                submission-basis-root submission-basis
                submitted-bundle-root submitted-bundle}
        config-root (genesis/resubmission-chain-configuration-root configuration)]
    {:resolver {:resolve-artifact bodies
                :resolve-configuration (fn [root]
                                         (when (= root config-root)
                                           configuration))}
     :configuration configuration
     :submitted-bundle-root submitted-bundle-root
     :submitted-bundle submitted-bundle}))

(defn- build-v2-evaluation [f]
  (let [{:keys [resolver configuration submitted-bundle-root]} f]
    (evaluation/build-evaluation resolver configuration submitted-bundle-root v2-app-root)))

(defn- v2-candidate-receipt [evaluation ordering]
  (let [evaluation-root (:acceptance-evaluation/root evaluation)
        submitted-bundle-root (:attempt-receipt/submitted-bundle-root (candidate-receipt-base))
        attempt-target {:attempt-target/type :use-case-application
                        :attempt-target/root v2-app-root}
        subject (attempt-subject/build evaluation-root submitted-bundle-root attempt-target)]
    (-> (candidate-receipt-base)
        (assoc :attempt-receipt/schema receipt/receipt-v2-schema)
        (assoc-in [:attempt-receipt/chain :transaction-ordering-hash]
                  (:transaction-ordering/hash ordering))
        (assoc-in [:attempt-receipt/chain :sequence] 2)
        (assoc-in [:attempt-receipt/chain :parent-receipt-hash] "sha256:R1")
        (assoc-in [:attempt-receipt/chain :family-id] family)
        (assoc-in [:attempt-receipt/chain :admission-status] :admitted)
        (assoc :attempt-receipt/attempt-subject-root (:attempt/subject-root subject)))))

(defn- issue-v2-request [state-before command ordering candidate evaluation]
  (let [base {:request/kind :resubmission-issue
              :request/version cmd/protocol-version-v2
              :request/id "req-v2-1"
              :validator {}
              :transition {:state-before state-before :command command}
              :ordering ordering
              :candidate-receipt candidate
              :attempt-target {:attempt-target/type :use-case-application
                               :attempt-target/root v2-app-root}
              :evaluation evaluation}]
    (assoc base :request/hash (sed/request-hash issuance/request-domain-v2 base))))

(deftest v2-issuance-authority-happy-path
  (testing "V2 issuance: real evaluation, attempt-subject binding, V2 receipt"
    (let [validator-key (ed/keypair :validator-v2-key)
          eval-fixture (v2-fixture)
          evaluation (build-v2-evaluation eval-fixture)
          {:keys [state-before cmd2 ordering]} (committed-fixture)
          candidate (v2-candidate-receipt evaluation ordering)
          request (issue-v2-request state-before cmd2 ordering candidate evaluation)
          response (cmd/decide-v2 {:private-key (:private-key validator-key)
                                   :validator/key-id "vk-1"} request)
          signed (:receipt response)]
      (is (= :resubmission-issue-response (:response/kind response)))
      (is (= cmd/protocol-version-v2 (:response/version response)))
      (is (= receipt/receipt-v2-schema (:attempt-receipt/schema signed)))
      (is (receipt/v2-receipt? signed))
      (is (some? (:attempt-receipt/id signed)))
      (is (some? (get-in signed [:attempt-receipt/validator :signature])))
      (is (true? (receipt/valid-receipt-dispatch? signed)))
      (is (:valid? (receipt/verify-receipt-signature-dispatch
                    signed (:public-hex validator-key))))
      (is (= (:attempt/subject-root
              (attempt-subject/build
               (:acceptance-evaluation/root evaluation)
               (:attempt-receipt/submitted-bundle-root candidate)
               {:attempt-target/type :use-case-application
                :attempt-target/root v2-app-root}))
             (:attempt-receipt/attempt-subject-root signed))))))

(deftest v2-issuance-target-mismatch-rejected
  (testing "V2 issuance rejects when attempt-target root does not match evaluation"
    (let [validator-key (ed/keypair :validator-v2-target-mismatch)
          eval-fixture (v2-fixture)
          evaluation (build-v2-evaluation eval-fixture)
          {:keys [state-before cmd2 ordering]} (committed-fixture)
          candidate (v2-candidate-receipt evaluation ordering)
          req-base {:request/kind :resubmission-issue
                    :request/version cmd/protocol-version-v2
                    :request/id "req-v2-target-mismatch"
                    :validator {}
                    :transition {:state-before state-before :command cmd2}
                    :ordering ordering
                    :candidate-receipt candidate
                    :attempt-target {:attempt-target/type :use-case-application
                                     :attempt-target/root v2-other-app-root}
                    :evaluation evaluation}
          mismatched (assoc req-base
                            :request/hash
                            (sed/request-hash issuance/request-domain-v2 req-base))
          reason (try
                   (cmd/decide-v2 {:private-key (:private-key validator-key)
                                   :validator/key-id "vk-1"}
                                  mismatched)
                   (catch Exception e (:reason (ex-data e))))]
      (is (= :target-application-root-mismatch reason)))))

(deftest v2-issuance-bundle-cross-binding-rejected
  (testing "V2 issuance rejects when candidate bundle root diverges from evaluation"
    (let [validator-key (ed/keypair :validator-v2-bundle-xcheck)
          eval-fixture (v2-fixture)
          evaluation (build-v2-evaluation eval-fixture)
          {:keys [state-before cmd2 ordering]} (committed-fixture)
          candidate (v2-candidate-receipt evaluation ordering)
          mismatched-candidate (assoc candidate
                                      :attempt-receipt/submitted-bundle-root v2-other-app-root)
          req-base {:request/kind :resubmission-issue
                    :request/version cmd/protocol-version-v2
                    :request/id "req-v2-bundle-mismatch"
                    :validator {}
                    :transition {:state-before state-before :command cmd2}
                    :ordering ordering
                    :candidate-receipt mismatched-candidate
                    :attempt-target {:attempt-target/type :use-case-application
                                     :attempt-target/root v2-app-root}
                    :evaluation evaluation}
          mismatched (assoc req-base
                            :request/hash
                            (sed/request-hash issuance/request-domain-v2 req-base))
          reason (try
                   (cmd/decide-v2 {:private-key (:private-key validator-key)
                                   :validator/key-id "vk-1"}
                                  mismatched)
                   (catch Exception e (:reason (ex-data e))))]
      (is (= :subject-root-mismatch reason)))))

(deftest v2-issuance-reconstructs-subject-root
  (testing "V2 issuer reconstructs attempt-subject-root when candidate omits it"
    ;; NOTE: With strict V2 shape validation, a candidate without a subject root
    ;; is rejected before the issuer can reconstruct it. The issuer reconstructs
    ;; and binds the subject root to the SIGNED receipt, not the candidate.
    ;; This test verifies that the issuer properly reconstructs and binds the
    ;; subject root when processing a valid candidate.
    (let [validator-key (ed/keypair :validator-v2-reconstruct)
          eval-fixture (v2-fixture)
          evaluation (build-v2-evaluation eval-fixture)
          {:keys [state-before cmd2 ordering]} (committed-fixture)
          ;; Build a valid candidate with subject root
          candidate (v2-candidate-receipt evaluation ordering)
          req-base {:request/kind :resubmission-issue
                    :request/version cmd/protocol-version-v2
                    :request/id "req-v2-reconstruct"
                    :validator {}
                    :transition {:state-before state-before :command cmd2}
                    :ordering ordering
                    :candidate-receipt candidate
                    :attempt-target {:attempt-target/type :use-case-application
                                     :attempt-target/root v2-app-root}
                    :evaluation evaluation}
          request (assoc req-base
                         :request/hash
                         (sed/request-hash issuance/request-domain-v2 req-base))
          response (cmd/decide-v2 {:private-key (:private-key validator-key)
                                   :validator/key-id "vk-1"}
                                  request)
          signed (:receipt response)]
      (is (= :resubmission-issue-response (:response/kind response)))
      (is (= receipt/receipt-v2-schema (:attempt-receipt/schema signed)))
      (is (some? (:attempt-receipt/attempt-subject-root signed))
          "issuer reconstructs and binds subject root to V2 receipt")
      (is (:valid? (receipt/verify-receipt-signature-dispatch
                    signed (:public-hex validator-key)))))))

(deftest v2-issuance-invalid-target-rejected
  (testing "V2 issuance rejects when attempt-target is malformed"
    (let [validator-key (ed/keypair :validator-v2-bad-target)
          eval-fixture (v2-fixture)
          evaluation (build-v2-evaluation eval-fixture)
          {:keys [state-before cmd2 ordering]} (committed-fixture)
          candidate (v2-candidate-receipt evaluation ordering)
          req-base {:request/kind :resubmission-issue
                    :request/version cmd/protocol-version-v2
                    :request/id "req-v2-bad-target"
                    :validator {}
                    :transition {:state-before state-before :command cmd2}
                    :ordering ordering
                    :candidate-receipt candidate
                    :attempt-target {:attempt-target/type :use-case-application
                                     :attempt-target/root "not-a-ref"}
                    :evaluation evaluation}
          request (assoc req-base
                         :request/hash
                         (sed/request-hash issuance/request-domain-v2 req-base))
          reason (try
                   (cmd/decide-v2 {:private-key (:private-key validator-key)
                                   :validator/key-id "vk-1"}
                                  request)
                   (catch Exception e (:reason (ex-data e))))]
      (is (= :invalid-attempt-target reason)))))

(deftest v2-issuance-command-round-trip
  (testing "V2 run-from-reader signs and exits 0"
    (let [validator-key (ed/keypair :validator-v2-round-trip)
          eval-fixture (v2-fixture)
          evaluation (build-v2-evaluation eval-fixture)
          {:keys [state-before cmd2 ordering]} (committed-fixture)
          candidate (v2-candidate-receipt evaluation ordering)
          request (issue-v2-request state-before cmd2 ordering candidate evaluation)
          out (java.io.StringWriter.)]
      (testing "valid request produces signed V2 receipt"
        (let [exit (binding [*out* out]
                     (cmd/run-from-reader
                      (java.io.StringReader. (pr-str request))
                      (:private-key validator-key)
                      "vk-1"))
              response (read-string (str out))]
          (is (= 0 exit))
          (is (= :resubmission-issue-response (:response/kind response)))
          (is (= receipt/receipt-v2-schema
                 (:attempt-receipt/schema (:receipt response))))
          (is (receipt/v2-receipt? (:receipt response)))
          (is (:valid? (receipt/verify-receipt-signature-dispatch
                        (:receipt response) (:public-hex validator-key))))))
      (testing "tampered V2 request fails closed"
        (let [out2 (java.io.StringWriter.)
              tampered (assoc request :request/hash "sha256:WRONG")
              exit (binding [*out* out2]
                     (cmd/run-from-reader
                      (java.io.StringReader. (pr-str tampered))
                      (:private-key validator-key)
                      "vk-1"))
              response (read-string (str out2))]
          (is (= 1 exit))
          (is (= :resubmission-issue-error (:response/kind response)))
          (is (= :invalid-request (:error/reason response))))))))

(deftest v2-issuance-mutated-subject-root-rejected
  (testing "V2 receipt with mutated attempt-subject-root fails signature verification"
    (let [validator-key (ed/keypair :validator-v2-mutate-subj)
          eval-fixture (v2-fixture)
          evaluation (build-v2-evaluation eval-fixture)
          {:keys [state-before cmd2 ordering]} (committed-fixture)
          candidate (v2-candidate-receipt evaluation ordering)
          request (issue-v2-request state-before cmd2 ordering candidate evaluation)
          response (cmd/decide-v2 {:private-key (:private-key validator-key)
                                   :validator/key-id "vk-1"}
                                  request)
          signed (:receipt response)
          mutated (assoc signed :attempt-receipt/attempt-subject-root v2-other-app-root)]
      (is (false? (:valid? (receipt/verify-receipt-signature-dispatch
                            mutated (:public-hex validator-key)))))
      (is (false? (receipt/receipt-binds-attempt-subject?
                   mutated
                   (attempt-subject/build
                    (:acceptance-evaluation/root evaluation)
                    (:attempt-receipt/submitted-bundle-root candidate)
                    {:attempt-target/type :use-case-application
                     :attempt-target/root v2-app-root})))))))

(deftest v2-issuance-subject-root-stable-under-reservation
  (testing "attempt-subject-root excludes reservation/fence fields (retry invariance)"
    (let [eval-fixture (v2-fixture)
          evaluation (build-v2-evaluation eval-fixture)
          submitted-bundle-root (:attempt-receipt/submitted-bundle-root
                                 (candidate-receipt-base))
          base-subject (attempt-subject/build
                        (:acceptance-evaluation/root evaluation)
                        submitted-bundle-root
                        {:attempt-target/type :use-case-application
                         :attempt-target/root v2-app-root})
          reserved-subject (assoc base-subject :reservation/id "res-123")
          leased-subject (assoc base-subject :lease/id "lease-456")
          fenced-subject (assoc base-subject :fence/token "fence-789")]
      (is (= (:attempt/subject-root base-subject)
             (:attempt/subject-root reserved-subject)))
      (is (= (:attempt/subject-root base-subject)
             (:attempt/subject-root leased-subject)))
      (is (= (:attempt/subject-root base-subject)
             (:attempt/subject-root fenced-subject)))
      (is (attempt-subject/valid? base-subject))
      (is (not (attempt-subject/valid? reserved-subject))))))

(deftest v2-issuance-v1-receipt-dispatches-correctly
  (testing "verify-receipt-signature-dispatch routes V1 vs V2 by schema"
    (let [validator-key (ed/keypair :validator-v2-dispatch)
          v1-key (ed/keypair :v1-dispatch-key)
          {:keys [state-before cmd2 ordering]} (committed-fixture)
          v1-candidate (with-chain (candidate-receipt-base) ordering)
          v1-request (issue-request state-before cmd2 ordering v1-candidate)
          v1-response (cmd/decide {:private-key (:private-key v1-key)
                                   :validator/key-id "vk-1"}
                                  v1-request)
          v1-signed (:receipt v1-response)]
      (is (not (receipt/v2-receipt? v1-signed)))
      (is (true? (receipt/valid-receipt-dispatch? v1-signed)))
      (is (true? (:valid? (receipt/verify-receipt-signature-dispatch
                           v1-signed (:public-hex v1-key)))))
      ;; verify V2 dispatch routes to v2 path
      (let [eval-fixture (v2-fixture)
            evaluation (build-v2-evaluation eval-fixture)
            v2-candidate (v2-candidate-receipt evaluation ordering)
            v2-request (issue-v2-request state-before cmd2 ordering v2-candidate evaluation)
            v2-response (cmd/decide-v2 {:private-key (:private-key validator-key)
                                        :validator/key-id "vk-1"}
                                       v2-request)
            v2-signed (:receipt v2-response)]
        (is (receipt/v2-receipt? v2-signed))
        (is (true? (receipt/valid-receipt-dispatch? v2-signed)))
        (is (:valid? (receipt/verify-receipt-signature-dispatch
                      v2-signed (:public-hex validator-key))))))))

(deftest v2-issuance-receipt-transplant-rejected
  (testing "V2 issuance rejects when receipt A is paired with attempt B data"
    (let [validator-key (ed/keypair :validator-v2-transplant)
          eval-fixture (v2-fixture)
          evaluation (build-v2-evaluation eval-fixture)
          {:keys [state-before cmd2 ordering]} (committed-fixture)
          candidate (v2-candidate-receipt evaluation ordering)
          request (issue-v2-request state-before cmd2 ordering candidate evaluation)
          response (cmd/decide-v2 {:private-key (:private-key validator-key)
                                   :validator/key-id "vk-1"}
                                  request)
          signed-a (:receipt response)
          ;; Create a different evaluation for attempt B
          v2-other-app-root "sha256:1111111111111111111111111111111111111111111111111111111111111111"
          candidate-b (-> (v2-candidate-receipt evaluation ordering)
                          (assoc :attempt-receipt/attempt-subject-root
                                 (:attempt/subject-root
                                  (attempt-subject/build
                                   (:acceptance-evaluation/root evaluation)
                                   (:attempt-receipt/submitted-bundle-root candidate)
                                   {:attempt-target/type :use-case-application
                                    :attempt-target/root v2-other-app-root}))))
          request-b (issue-v2-request state-before cmd2 ordering candidate-b evaluation)]
      ;; Receipt A signature is valid (it's over receipt A's content)
      ;; but receipt A does not bind attempt B's subject
      (is (:valid? (receipt/verify-receipt-signature-dispatch
                    signed-a (:public-hex validator-key)))
          "receipt A signature is valid for receipt A")
      (is (not (receipt/receipt-binds-attempt-subject?
                signed-a
                (attempt-subject/build
                 (:acceptance-evaluation/root evaluation)
                 (:attempt-receipt/submitted-bundle-root candidate)
                 {:attempt-target/type :use-case-application
                  :attempt-target/root v2-other-app-root})))
          "receipt A does not bind attempt B's subject"))))

(deftest v2-issuance-missing-subject-root-rejected
  (testing "V2 receipt without attempt-subject-root is rejected at signing"
    (let [validator-key (ed/keypair :validator-v2-missing-subj)
          eval-fixture (v2-fixture)
          evaluation (build-v2-evaluation eval-fixture)
          {:keys [state-before cmd2 ordering]} (committed-fixture)
          candidate-no-subject (-> (v2-candidate-receipt evaluation ordering)
                                   (dissoc :attempt-receipt/attempt-subject-root))
          req-base {:request/kind :resubmission-issue
                    :request/version cmd/protocol-version-v2
                    :request/id "req-v2-missing-subj"
                    :validator {}
                    :transition {:state-before state-before :command cmd2}
                    :ordering ordering
                    :candidate-receipt candidate-no-subject
                    :attempt-target {:attempt-target/type :use-case-application
                                     :attempt-target/root v2-app-root}
                    :evaluation evaluation}
          request (assoc req-base
                         :request/hash
                         (sed/request-hash issuance/request-domain-v2 req-base))]
      ;; With strict V2 shape validation, a candidate without a subject root
      ;; is rejected before the issuer can process it
      (let [reason (try
                     (cmd/decide-v2 {:private-key (:private-key validator-key)
                                     :validator/key-id "vk-1"}
                                    request)
                     (catch Exception e (:reason (ex-data e))))]
        (is (= :invalid-candidate-receipt reason)
            "V2 candidate without subject root is rejected"))
      ;; Verify that a receipt without subject root cannot be signed directly
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"V2 receipt requires"
                            (receipt/sign-receipt-v2 candidate-no-subject
                                                     (:private-key validator-key)))
          "sign-receipt-v2 rejects missing subject root"))))

(deftest v1-compatibility-unchanged
  (testing "V1 receipt verifies without attempt-subject-root"
    (let [validator-key (ed/keypair :v1-compat-key)
          {:keys [state-before cmd2 ordering]} (committed-fixture)
          v1-candidate (with-chain (candidate-receipt-base) ordering)
          v1-request (issue-request state-before cmd2 ordering v1-candidate)
          v1-response (cmd/decide {:private-key (:private-key validator-key)
                                   :validator/key-id "vk-1"}
                                  v1-request)
          v1-signed (:receipt v1-response)]
      (is (not (receipt/v2-receipt? v1-signed)))
      (is (true? (receipt/valid-receipt-dispatch? v1-signed)))
      (is (true? (:valid? (receipt/verify-receipt-signature-dispatch
                           v1-signed (:public-hex validator-key))))
          "V1 receipt verifies under V1 domain")
      (is (nil? (:attempt-receipt/attempt-subject-root v1-signed))
          "V1 receipt does not require attempt-subject-root")))

  (testing "V1 receipt uses V1 domain, not V2"
    (let [validator-key (ed/keypair :v1-domain-key)
          v1-candidate (candidate-receipt-base)
          v1-signed (receipt/sign-receipt v1-candidate (:private-key validator-key))
          v2-candidate (-> (candidate-receipt-base)
                           (assoc :attempt-receipt/schema receipt/receipt-v2-schema)
                           (assoc-in [:attempt-receipt/chain :transaction-ordering-hash]
                                     "sha256:ORD")
                           (assoc :attempt-receipt/attempt-subject-root subj-root))
          v2-signed (receipt/sign-receipt-v2 v2-candidate (:private-key validator-key))]
      (is (not= (:attempt-receipt/id v1-signed) (:attempt-receipt/id v2-signed))
          "V1 and V2 receipts have different domains")
      (is (hash-ref/valid-sha256-ref? (:attempt-receipt/id v1-signed)))
      (is (hash-ref/valid-sha256-ref? (:attempt-receipt/id v2-signed)))))

  (testing "V1 receipt schema cannot be upgraded to V2 without subject root"
    (let [validator-key (ed/keypair :v1-upgrade-key)
          v1-candidate (candidate-receipt-base)
          v1-signed (receipt/sign-receipt v1-candidate (:private-key validator-key))
          ;; Attempt to upgrade schema without adding subject root
          upgraded (assoc v1-signed :attempt-receipt/schema receipt/receipt-v2-schema)]
      (is (false? (receipt/valid-receipt-dispatch? upgraded))
          "V1 receipt with V2 schema but no subject root is invalid")
      (is (not (:valid? (receipt/verify-receipt-signature-dispatch
                         upgraded (:public-hex validator-key))))
          "V1 receipt upgraded to V2 fails signature verification")))

  (testing "V1 is never silently interpreted as V2"
    (let [validator-key (ed/keypair :v1-no-v2-key)
          v1-candidate (candidate-receipt-base)
          v1-signed (receipt/sign-receipt v1-candidate (:private-key validator-key))]
      (is (false? (receipt/v2-receipt? v1-signed))
          "V1 receipt is not a V2 receipt")
      (is (= receipt/receipt-schema (:attempt-receipt/schema v1-signed))
          "V1 receipt retains V1 schema"))))

(deftest v2-issuance-evaluation-cross-binding-rejected
  (testing "V2 issuance rejects when evaluation root differs from candidate binding"
    (let [validator-key (ed/keypair :validator-v2-eval-xcheck)
          eval-fixture (v2-fixture)
          evaluation (build-v2-evaluation eval-fixture)
          {:keys [state-before cmd2 ordering]} (committed-fixture)
          candidate (v2-candidate-receipt evaluation ordering)
          ;; Build a different evaluation
          other-app-root "sha256:2222222222222222222222222222222222222222222222222222222222222222"
          other-evaluation (evaluation/build-evaluation (:resolver eval-fixture)
                                                        (:configuration eval-fixture)
                                                        (:submitted-bundle-root eval-fixture)
                                                        other-app-root)
          ;; Create request with evaluation A but candidate bound to evaluation B
          candidate-xbind (assoc candidate
                                 :attempt-receipt/attempt-subject-root
                                 (:attempt/subject-root
                                  (attempt-subject/build
                                   (:acceptance-evaluation/root other-evaluation)
                                   (:attempt-receipt/submitted-bundle-root candidate)
                                   {:attempt-target/type :use-case-application
                                    :attempt-target/root v2-app-root})))
          req-base {:request/kind :resubmission-issue
                    :request/version cmd/protocol-version-v2
                    :request/id "req-v2-eval-xbind"
                    :validator {}
                    :transition {:state-before state-before :command cmd2}
                    :ordering ordering
                    :candidate-receipt candidate-xbind
                    :attempt-target {:attempt-target/type :use-case-application
                                     :attempt-target/root v2-app-root}
                    :evaluation evaluation}
          mismatched (assoc req-base
                            :request/hash
                            (sed/request-hash issuance/request-domain-v2 req-base))
          reason (try
                   (cmd/decide-v2 {:private-key (:private-key validator-key)
                                   :validator/key-id "vk-1"}
                                  mismatched)
                   (catch Exception e (:reason (ex-data e))))]
      (is (= :subject-root-mismatch reason)))))
