(ns resolver-sim.allocation.bounded-source-snapshot-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.allocation.bounded-source-snapshot :as bounded]
            [resolver-sim.signed-external-decision :as sed]
            [resolver-sim.support.ed25519 :as fx]))

(defn- signed-snapshot [kp]
  (let [body {:snapshot/schema bounded/snapshot-schema
              :snapshot/id "snapshot-2026-10-21T12:00Z"
              :snapshot/source-id "pseudonymous-request-ledger"
              :snapshot/population-id "round-17-requests"
              :snapshot/query-root (str "sha256:" (apply str (repeat 64 "a")))
              :snapshot/start-time 100
              :snapshot/cutoff 300
              :snapshot/watermark 300
              :snapshot/first-sequence 1
              :snapshot/last-sequence 3
              :snapshot/event-count 3
              :snapshot/events [{:event/id "evt-001" :event/entity-id "claim-a"
                                 :event/sequence 1 :event/time 110 :event/kind :request/open
                                 :event/payload {:amount 50}}
                                {:event/id "evt-002" :event/entity-id "claim-a"
                                 :event/sequence 2 :event/time 210 :event/kind :request/amend
                                 :event/payload {:amount 45}}
                                {:event/id "evt-003" :event/entity-id "claim-b"
                                 :event/sequence 3 :event/time 250 :event/kind :request/open
                                 :event/payload {:amount 30}}]}
        rooted (assoc body :snapshot/root (bounded/snapshot-root body))]
    (sed/sign-envelope rooted bounded/snapshot-domain (:private-key kp) (:key/id kp))))

(defn- signed-binding [body domain kp]
  (sed/sign-envelope (assoc body :binding/root (bounded/binding-root body))
                     domain (:private-key kp) (:key/id kp)))

(defn- re-root-snapshot [snapshot]
  (assoc snapshot :snapshot/root (bounded/snapshot-root snapshot)))

(defn- re-sign-binding [binding kp]
  (let [body (dissoc binding :signature :binding/root)
        rooted (assoc body :binding/root (bounded/binding-root body))]
    (sed/sign-envelope rooted bounded/authority-domain
                       (:private-key kp) (:key/id kp))))

(deftest bounded-source-snapshot-requires-authority-and-complete-boundaries
  (let [kp (fx/keypair :bounded-source)
        trust (fx/trust-policy kp bounded/source-role :active)
        snapshot (signed-snapshot kp)]
    (is (:valid? (bounded/verify-snapshot snapshot trust)))
    (testing "rejects a substituted event even if local hashes are refreshed"
      (let [changed (assoc-in snapshot [:snapshot/events 1 :event/payload :amount] 44)
            changed (assoc changed :snapshot/root (bounded/snapshot-root changed))]
        (is (false? (:valid? (bounded/verify-snapshot changed trust)))))
      (is (= :invalid-or-incomplete-event-sequence
             (:reason (bounded/validate-snapshot-shape
                       (assoc snapshot :snapshot/events (subvec (:snapshot/events snapshot) 1))))))
      (is (= :invalid-or-incomplete-event-sequence
             (:reason (bounded/validate-snapshot-shape
                       (assoc-in snapshot [:snapshot/events 1 :event/sequence] 4)))))
      (is (= :invalid-or-incomplete-event-sequence
             (:reason (bounded/validate-snapshot-shape
                       (assoc-in snapshot [:snapshot/events 0 :event/time] 301))))))
    (is (false? (:valid? (bounded/verify-snapshot snapshot
                                                  (fx/trust-policy kp :unrelated-role :active)))))))

(deftest bounded-source-snapshot-rejects-duplicate-boundary-and-lifecycle-cases
  (let [kp (fx/keypair :bounded-source-negative-cases)
        snapshot (signed-snapshot kp)
        events (:snapshot/events snapshot)
        duplicate-event-id (re-root-snapshot
                            (assoc-in snapshot [:snapshot/events 2 :event/id]
                                      (:event/id (first events))))
        duplicate-sequence (re-root-snapshot
                            (assoc-in snapshot [:snapshot/events 1 :event/sequence] 1))
        wrong-event-count (re-root-snapshot
                           (assoc snapshot :snapshot/event-count 4))
        wrong-sequence-range (re-root-snapshot
                              (assoc snapshot :snapshot/first-sequence 0))
        duplicate-open (re-root-snapshot
                        (assoc-in snapshot [:snapshot/events 1 :event/kind] :request/open))
        amendment-after-cancellation
        (re-root-snapshot
         (assoc snapshot :snapshot/events
                [(assoc (nth events 0) :event/entity-id "claim-a"
                        :event/sequence 1 :event/time 110 :event/kind :request/open)
                 (assoc (nth events 1) :event/entity-id "claim-a"
                        :event/sequence 2 :event/time 210 :event/kind :request/cancel)
                 (assoc (nth events 2) :event/entity-id "claim-a"
                        :event/sequence 3 :event/time 250 :event/kind :request/amend)]))]
    (doseq [[label candidate]
            [["duplicate event ID" duplicate-event-id]
             ["duplicate sequence number" duplicate-sequence]
             ["declared event count mismatch" wrong-event-count]
             ["declared sequence range mismatch" wrong-sequence-range]
             ["opening an already-open request" duplicate-open]
             ["amending a cancelled request" amendment-after-cancellation]]]
      (testing label
        (is (= :invalid-or-incomplete-event-sequence
               (:reason (bounded/validate-snapshot-shape candidate))))))))

(deftest beneficiary-authorization-is-separate-and-pseudonymous
  (let [kp (fx/keypair :beneficiary-authority)
        trust (fx/trust-policy kp bounded/beneficiary-role :active)
        body {:binding/id "beneficiary-map-round-17"
              :binding/source-root "sha256:source-root"
              :policy/root "sha256:policy-root"
              :beneficiary/authorizations
              [{:claim/id "claim-a" :owner/pseudonym "subject-01"
                :beneficiary/destination "0x1111111111111111111111111111111111111111"
                :authorization/id "auth-a"}
               {:claim/id "claim-b" :owner/pseudonym "subject-02"
                :beneficiary/destination "0x2222222222222222222222222222222222222222"
                :authorization/id "auth-b"}]}
        rooted (assoc body :binding/root (bounded/binding-root body))
        binding (sed/sign-envelope rooted bounded/authority-domain
                                   (:private-key kp) (:key/id kp))]
    (is (:valid? (bounded/verify-authority-binding binding :beneficiary trust)))
    (is (:valid? (bounded/bind-beneficiaries-to-positive-claims
                  {"claim-a" 35 "claim-b" 21} binding)))
    (is (= :beneficiary-coverage-mismatch
           (:reason (bounded/bind-beneficiaries-to-positive-claims
                     {"claim-a" 35 "claim-c" 14} binding))))
    (doseq [invalid-allocation [nil [] {"claim-a" "35"} {"claim-a" -1}
                                {nil 35} {"" 35}]]
      (testing (str "malformed allocation " (pr-str invalid-allocation))
        (is (= {:valid? false
                :reason :invalid-allocation-map
                :missing []
                :unexpected []
                :mapping {}
                :identity-scope :pseudonymous-owner-authorization
                :limitations [:allocation-claim-ids-must-be-nonempty-strings
                              :allocation-amounts-must-be-nonnegative-integers
                              :does-not-establish-legal-identity-from-wallet-address]}
               (bounded/bind-beneficiaries-to-positive-claims
                invalid-allocation binding)))))
    (is (false? (:valid? (bounded/verify-authority-binding binding :funding trust))))
    (let [duplicate-authorization
          (re-sign-binding
           (assoc-in binding [:beneficiary/authorizations 1 :authorization/id]
                     (get-in binding [:beneficiary/authorizations 0 :authorization/id]))
           kp)]
      (is (= :invalid-authority-binding
             (:reason (bounded/verify-authority-binding duplicate-authorization
                                                        :beneficiary trust)))))))

(deftest stage-b-inputs-require-cross-bound-authority-envelopes
  (let [source-kp (fx/keypair :stage-b-source)
        policy-kp (fx/keypair :stage-b-policy)
        beneficiary-kp (fx/keypair :stage-b-beneficiary)
        funding-kp (fx/keypair :stage-b-funding)
        snapshot (signed-snapshot source-kp)
        policy-root (str "sha256:" (apply str (repeat 64 "b")))
        source-root (:snapshot/root snapshot)
        policy (signed-binding
                {:binding/id "policy-decision-17"
                 :binding/source-root source-root
                 :policy/root policy-root
                 :binding/subject {:policy/version "round-17-v1"
                                   :policy/root policy-root
                                   :policy/rules {:allocation "largest-remainder"}}}
                bounded/authority-domain policy-kp)
        beneficiaries (signed-binding
                       {:binding/id "beneficiary-map-17"
                        :binding/source-root source-root
                        :policy/root policy-root
                        :beneficiary/authorizations
                        [{:claim/id "claim-a" :owner/pseudonym "research-subject-a"
                          :beneficiary/destination "0x1111111111111111111111111111111111111111"
                          :authorization/id "auth-a"}
                         {:claim/id "claim-b" :owner/pseudonym "research-subject-b"
                          :beneficiary/destination "0x2222222222222222222222222222222222222222"
                          :authorization/id "auth-b"}]}
                       bounded/authority-domain beneficiary-kp)
        funding (signed-binding
                 {:binding/id "funding-decision-17"
                  :binding/source-root source-root
                  :policy/root policy-root
                  :binding/subject {:operation/id "operation-17"
                                    :reserve/root "sha256:reserve"
                                    :asset/id "issuer-x/token-y"
                                    :funder/id "treasury-01"
                                    :chain/instance-id "local-chain-31337-genesis-x"
                                    :deployment/code-hash "0xcodehash"
                                    :amount 70}}
                 bounded/authority-domain funding-kp)
        inputs {:snapshot snapshot
                :source-trust (fx/trust-policy source-kp bounded/source-role :active)
                :policy policy
                :policy-trust (fx/trust-policy policy-kp bounded/policy-role :active)
                :beneficiaries beneficiaries
                :beneficiary-trust (fx/trust-policy beneficiary-kp bounded/beneficiary-role :active)
                :funding funding
                :funding-trust (fx/trust-policy funding-kp bounded/funding-role :active)
                :allocation {"claim-a" 35 "claim-b" 21}}]
    (is (:valid? (bounded/qualify-stage-b-inputs inputs)))
    (is (= :bound (:status (bounded/qualify-stage-b-inputs inputs))))
    (is (false? (:valid? (bounded/qualify-stage-b-inputs
                          (assoc-in inputs [:funding :binding/source-root] "sha256:other")))))
    (testing "rejects individually valid signatures whose source and policy roots do not join"
      (let [wrong-policy-source
            (re-sign-binding (assoc policy :binding/source-root "sha256:other") policy-kp)
            wrong-beneficiary-source
            (re-sign-binding (assoc beneficiaries :binding/source-root "sha256:other")
                             beneficiary-kp)
            wrong-beneficiary-policy
            (re-sign-binding (assoc beneficiaries :policy/root "sha256:other")
                             beneficiary-kp)
            wrong-funding-policy
            (re-sign-binding (assoc funding :policy/root "sha256:other") funding-kp)]
        (doseq [changed [(assoc inputs :policy wrong-policy-source)
                         (assoc inputs :beneficiaries wrong-beneficiary-source)
                         (assoc inputs :beneficiaries wrong-beneficiary-policy)
                         (assoc inputs :funding wrong-funding-policy)]]
          (is (false? (:valid? (bounded/qualify-stage-b-inputs changed)))))))
    (testing "rejects zero funding even when signed by the selected funding authority"
      (let [zero-funding (re-sign-binding
                          (assoc-in funding [:binding/subject :amount] 0) funding-kp)]
        (is (= :invalid-authority-binding
               (:reason (bounded/verify-authority-binding
                         zero-funding :funding (:funding-trust inputs)))))))
    (is (false? (:valid? (bounded/qualify-stage-b-inputs
                          (assoc-in inputs [:beneficiaries :beneficiary/authorizations 1 :claim/id]
                                    "claim-c")))))))
