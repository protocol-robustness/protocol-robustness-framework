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
    (is (false? (:valid? (bounded/verify-authority-binding binding :funding trust))))))

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
    (is (false? (:valid? (bounded/qualify-stage-b-inputs
                          (assoc-in inputs [:beneficiaries :beneficiary/authorizations 1 :claim/id]
                                    "claim-c")))))))
