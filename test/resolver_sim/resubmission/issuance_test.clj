(ns resolver-sim.resubmission.issuance-test
  "Tests for attempt-receipt issuance: the pure helpers and the out-of-process
   signer authority (resolver-sim.commands.resubmission-issue).

   The authority must independently re-derive the committed transition from the
   presented pre-state and command, verify the ordering evidence and the
   candidate receipt binding, and sign the receipt (attestation after commit)."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.commands.resubmission-issue :as cmd]
            [resolver-sim.resubmission.issuance :as issuance]
            [resolver-sim.resubmission.receipt :as receipt]
            [resolver-sim.resubmission.store :as store]
            [resolver-sim.resubmission.transition :as transition]
            [resolver-sim.signed-external-decision :as sed]
            [resolver-sim.support.ed25519 :as ed]
            [resolver-sim.transaction.ordering :as ordering]
            [resolver-sim.transaction.protocol :as protocol]))

(def family "sha256:FAM")
(def subject-root "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc")

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
                     :transaction/state-before-root "sha256:B"
                     :transaction/state-after-root "sha256:A"
                     :transaction/effects-root "sha256:E"})
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
                                      :transaction/state-after-root "sha256:WRONG"))
            req (issue-request state-before cmd2 tampered-ordering candidate)]
        (is (= :state-after-root-mismatch (decide-reason (:private-key validator-key) req)))))
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
                            :transaction/state-before-root "sha256:B"
                            :transaction/state-after-root "sha256:A"
                            :transaction/effects-root "sha256:E"})
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
    (let [validator-key (ed/keypair :validator-key)
          {:keys [state-before cmd2 ordering]} (committed-fixture)
          candidate (with-chain (candidate-receipt-base) ordering)
          request (issue-request state-before cmd2 ordering candidate)
          response (cmd/decide {:private-key (:private-key validator-key)
                                :validator/key-id "vk-1"} request)
          id (:attempt-receipt/id (:receipt response))]
      ;; The receipt id is the hash of the UNSIGNED projection (signature
      ;; excluded), so it is deterministic across validator keys.
      (is (= "sha256:8e0db852a8ad46bc80a22d78fda32b018435f8fe784876353058018ee3ea194d" id)))))
