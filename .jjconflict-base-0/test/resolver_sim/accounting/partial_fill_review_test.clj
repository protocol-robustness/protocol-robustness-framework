(ns resolver-sim.accounting.partial-fill-review-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [resolver-sim.accounting.partial-fill-review :as a]
            [resolver-sim.assurance.subject-decisions :as d]
            [resolver-sim.assurance.subject-decisions-test :as helpers])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def manifest-pin "sha256:48afd9aa8553076ba24b017344d07dc3cb7fa2756a5e591caf4f2881feb05798")
(def evidence (delay (a/load-evidence! "examples/partial-fill/evidence/v3-live-context" manifest-pin)))

(defn setup [phase]
  (let [reviewer (helpers/keypair) ratifier (helpers/keypair)
        p (d/policy {:domain a/domain :purpose (a/purposes phase) :scope a/scope :subject-schema a/schema
                     :validation-root a/validation-root
                     :reviewer {:id "fixture-accounting-reviewer" :public-key (helpers/public-pem reviewer)}
                     :ratifier {:id "fixture-accounting-ratifier" :public-key (helpers/public-pem ratifier)}})
        subject (a/statement @evidence phase)]
    {:p p :reviewer reviewer :ratifier ratifier :subject subject
     :subjects {(:artifact/root subject) subject} :verifier (a/verifier @evidence)}))

(deftest actual-v3-statements-are-purpose-separated
  (let [allocation (a/statement @evidence :allocation)
        realized (a/statement @evidence :realized)]
    (is (nil? (:settlement allocation)))
    (is (not= (:purpose allocation) (:purpose realized)))
    (is (= [35 21 14] (mapv :allocated (:rows allocation))))
    (is (= [35 21 14] (mapv :paid (get-in realized [:settlement :withdrawals]))))
    (is (every? #(not (contains? % :allocated)) (get-in realized [:settlement :withdrawals])))
    (is (= 10 (get-in realized [:settlement :restricted-refund :amount])))
    (doseq [s [allocation realized]]
      (is (:verified? (a/verify-statement @evidence s)))
      (is (= a/limitations (:limitations s))))))

(deftest conservation-is-not-policy-compliance
  (let [candidate (a/statement @evidence :allocation true nil)
        report (a/verify-statement @evidence candidate)]
    (is (:synthetic? candidate))
    (is (nil? (:settlement candidate)))
    (is (= [40 24 16] (mapv :allocated (:rows candidate))))
    (is (true? (get-in report [:checks :conservation])))
    (is (false? (get-in report [:checks :restricted-capital])))
    (is (false? (get-in report [:checks :recipient-policy])))
    (is (false? (:eligible? report)))))

(deftest independent-review-and-adoption-of-both-accounting-cutoffs
  (doseq [phase [:allocation :realized]]
    (let [{:keys [p verifier subjects subject] :as f} (setup phase)
          a (helpers/request f [] :accept)
          accepted (d/reconstruct p verifier subjects [a])
          ratification (helpers/request f [a] :ratify)
          state (d/reconstruct p verifier subjects [a ratification])]
      (is (nil? (:authoritative accepted)))
      (is (= (:artifact/root subject) (get-in state [:authoritative :subject/root])))
      (is (= (:artifact/root a) (get-in state [:authoritative :acceptance/root])))
      (is (false? (:capital-authority (verifier subject)))))))

(deftest invalid-interpretation-rejected-by-an-authenticated-reviewer
  (let [{:keys [p] :as f} (setup :allocation)
        bad (a/statement @evidence :allocation true nil)
        f (assoc f :subject bad :subjects {(:artifact/root bad) bad})
        {:keys [verifier subjects]} f
        review (helpers/request f [] :reject)]
    (is (thrown? Exception (d/reconstruct p verifier subjects [(helpers/request f [] :accept)])))
    (is (= :reject (get-in (d/reconstruct p verifier subjects [review]) [:decisions (:artifact/root bad) :decision])))
    (is (thrown? Exception (d/reconstruct p verifier subjects [review (helpers/request f [review] :ratify)])))))

(deftest accounting-bindings-and-coverage-cannot-be-silently-expanded
  (let [original (a/statement @evidence :realized)]
    (doseq [altered [(assoc original :economic-policy/root "0xdead")
                     (assoc original :evidence/root (d/root "other-evidence" {}))
                     (assoc original :source/root "0xother")
                     (assoc original :limitations [])
                     (update original :rows pop)
                     (assoc-in original [:settlement :restricted-refund :beneficiary] "0xwrong")
                     (assoc-in original [:settlement :remaining-liability] 1)
                     (assoc-in original [:rows 0 :allocated] 40)]]
      (is (false? (:verified? (a/verify-statement @evidence
                                                  (assoc altered :artifact/root (d/artifact-root altered)))))))))

(deftest altered-primary-evidence-is-rejected
  (let [bad (assoc-in @evidence [:files "transfers.json"] (.getBytes "[]" "UTF-8"))]
    (is (thrown? Exception (a/evidence-material bad))))
  (is (thrown? Exception (a/load-evidence! "examples/partial-fill/evidence/v3-live-context"
                                           (d/root "wrong-manifest" {})))))

(deftest realized-statement-cannot-enter-allocation-review
  (let [{:keys [p verifier]} (setup :allocation)]
    (is (thrown? Exception (d/verify-subject p verifier (a/statement @evidence :realized))))))

(deftest cross-history-consistency-requires-shared-operation-and-evidence
  ;; P2: the allocation and realized-settlement statements share no signed link;
  ;; an independent check requires them to name the same operation/evidence/policy.
  (let [alloc (a/statement @evidence :allocation)
        realized (a/statement @evidence :realized)]
    (is (a/cross-history-consistent? alloc realized))
    (is (not (a/cross-history-consistent? alloc (assoc realized :operation/id "0xother"))))
    (is (not (a/cross-history-consistent? alloc (assoc realized :evidence/root (d/root "other" {})))))
    (is (not (a/cross-history-consistent? alloc (assoc realized :economic-policy/root "0xother"))))
    (is (not (a/cross-history-consistent? alloc alloc)))))

;; ── P1: derivation generality on a modified but internally consistent fixture ─
;;
;; The retained fixture is gross 80 / restricted 10 / available 70 with 50/30/20
;; requests. This builds a DIFFERENT, internally consistent minimal source
;; fixture on disk (gross 90 / restricted 20 / available 70, requests 40/40/20,
;; allocated 28/28/14, refund 20) and asserts the adapter derives every value
;; from evidence + policy rather than from literals. If any value were hardcoded,
;; these assertions would fail.

(defn- write-json! [dir name value]
  (spit (io/file dir name) (json/write-str value)))

(defn- sha-hex [bytes] (subs (a/file-root bytes) 7))

(defn- modified-source-fixture!
  "Write a minimal internally consistent V3 source fixture and return [dir pin]."
  []
  (let [dir (.toFile (Files/createTempDirectory "pf-modified" (make-array FileAttribute 0)))
        a1 "0x1111111111111111111111111111111111111111"
        a2 "0x2222222222222222222222222222222222222222"
        a3 "0x3333333333333333333333333333333333333333"
        reserve "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        allocator "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        token "0xcccccccccccccccccccccccccccccccccccccccc"
        op-id "0x6fbe4a9888b84c0245dcc21e15835a7e7c5ede9b9fc42a77baf0600b8f46a414"
        exec-root "0xb0738f50aa2a33b26d7d1dd6d99d027185be805b547f6473c30b563bd1ee34be"
        econ-root "88cad6aed4bc4e33b364275dc9a0b7d1b21b67faf11833ce1a0a63b6bfe122c4"
        replay {:realized-statement-root (subs exec-root 2)
                :operation-id op-id
                :public-values "deadbeef"
                :ordered-entitlements [{:claim-id "a1" :beneficiary a1 :amount "28"}
                                       {:claim-id "a2" :beneficiary a2 :amount "28"}
                                       {:claim-id "a3" :beneficiary a3 :amount "14"}]}
        claimants [{:claim-id "a1" :economic-owner-id a1 :amount "40"}
                   {:claim-id "a2" :economic-owner-id a2 :amount "40"}
                   {:claim-id "a3" :economic-owner-id a3 :amount "20"}]
        witness {:realized-statement-input {:allocation-context {:claimants claimants}
                                            :available "70"
                                            :policy {:mode "pro-rata" :rounding-policy "largest-remainder"}
                                            :fail-action-policy {:mode "unmet-request"}}
                 :economic-reserve-state {:gross-balance "90" :available-amount "70"
                                          :protected-capital "20" :policy-root econ-root}
                 :prepared-operation {:policy-root exec-root :operation-id op-id
                                      :chain-id "31337" :reserve reserve :token token}}
        policy {:restrictedAmount 20 :reserve reserve :allocator allocator :token token
                :operationId op-id :policyRoot (str "0x" econ-root)
                :beneficialOwnerAndRefundBeneficiary a3}
        funding {:chainId 31337 :observedTransferAmount 90 :reserveBalanceAfterDeposit 90
                 :availableCapital 70 :sponsorTransferObserved true
                 :refundBeneficiary a3 :policyRoot (str "0x" econ-root) :operationId op-id}
        transfers [{:token token :from reserve :to allocator :amount 70}
                   {:token token :from allocator :to a1 :amount 28}
                   {:token token :from allocator :to a2 :amount 28}
                   {:token token :from allocator :to a3 :amount 14}
                   {:token token :from reserve :to a3 :amount 20}]
        opening {:stage "opening" :reserveBalance 90 :allocatorBalance 0 :aliceBalance 0 :bobBalance 0 :carolBalance 0
                 :claimedAmount 0 :claimedCount 0 :outstandingLiability 0 :allocatorState 0 :ratificationState 0 :reserveFinalized false :availabilityRevision 0}
        obs [opening
             (assoc opening :stage "proof_admitted" :ratificationState 1)
             (assoc opening :stage "funded" :reserveBalance 20 :allocatorBalance 70 :allocatorState 2 :ratificationState 3 :availabilityRevision 1)
             (assoc opening :stage "activated" :reserveBalance 20 :allocatorBalance 70 :outstandingLiability 70 :allocatorState 3 :ratificationState 4 :availabilityRevision 1)
             (assoc opening :stage "after_alice_claim" :reserveBalance 20 :allocatorBalance 42 :aliceBalance 28 :claimedAmount 28 :claimedCount 1 :outstandingLiability 42 :allocatorState 4 :ratificationState 4 :availabilityRevision 1)
             (assoc opening :stage "after_bob_claim" :reserveBalance 20 :allocatorBalance 14 :aliceBalance 28 :bobBalance 28 :claimedAmount 56 :claimedCount 2 :outstandingLiability 14 :allocatorState 4 :ratificationState 4 :availabilityRevision 1)
             (assoc opening :stage "allocator_closed" :reserveBalance 20 :allocatorBalance 0 :aliceBalance 28 :bobBalance 28 :carolBalance 14 :claimedAmount 70 :claimedCount 3 :outstandingLiability 0 :allocatorState 5 :ratificationState 4 :availabilityRevision 1)
             (assoc opening :stage "reserve_finalized" :reserveBalance 0 :allocatorBalance 0 :aliceBalance 28 :bobBalance 28 :carolBalance 34 :claimedAmount 70 :claimedCount 3 :outstandingLiability 0 :allocatorState 5 :ratificationState 6 :reserveFinalized true :availabilityRevision 2)]
        terminal {:chainId 31337 :policyRoot (str "0x" econ-root) :operationId op-id
                  :operationState "Closed" :ratificationState "Finalized" :outstandingLiability 0
                  :allocatorBalance 0 :reserveBalance 0 :protectedCapitalRefunded 20
                  :refundBeneficiary a3 :beneficiaryBalance 34 :reserveFinalized true}
        log (str (apply str (map #(str "PARTIAL_FILL_V3_ACCOUNTING_STATE_JSON " (json/write-str %) "\n") obs))
                 "PARTIAL_FILL_TRANSFERS_JSON " (json/write-str transfers) "\n"
                 "PARTIAL_FILL_V3_TERMINAL_JSON " (json/write-str terminal) "\n")]
    (write-json! dir "witness.json" witness)
    (write-json! dir "protected-capital-policy.json" policy)
    (write-json! dir "funding-observation.json" funding)
    (write-json! dir "transfers.json" transfers)
    (write-json! dir "terminal-observation.json" terminal)
    (write-json! dir "rust-replay.json" replay)
    (write-json! dir "clojure-replay.json" replay)
    (write-json! dir "proof.json" {:public_values "0xdeadbeef"})
    (write-json! dir "accounting-states.json" {:schema "eae.partial-fill.v3-accounting-state-observations.v1"
                                               :states (mapv (fn [o] {:observation o}) obs)})
    (spit (io/file dir "v3-accounting-qualification.log") log)
    (let [names (remove #{"manifest.json"} (map #(.getName %) (.listFiles dir)))
          files (into (sorted-map)
                      (for [n names :let [bytes (Files/readAllBytes (.toPath (io/file dir n)))]]
                        [n {:sha256 (sha-hex bytes) :bytes (alength bytes)}]))]
      (write-json! dir "manifest.json" {:schema "eae.partial-fill.v3-live-context-evidence.v1" :files files})
      [dir (a/file-root (Files/readAllBytes (.toPath (io/file dir "manifest.json"))))])))

(deftest modified-consistent-fixture-is-derived-not-hardcoded
  (let [[dir pin] (modified-source-fixture!)]
    (try
      (let [material (a/load-evidence! (.getPath dir) pin)
            derived (:derived (a/evidence-material material))
            allocation (a/statement material :allocation)
            realized (a/statement material :realized)]
        (is (= {:deposited 90 :allocatable 70 :restricted 20} (:capital derived)))
        (is (= [28 28 14] (mapv :allocated (:rows derived))))
        (is (= 70 (:funded derived)))
        (is (= 20 (get-in derived [:restricted-refund :amount])))
        (is (= 0 (:remaining-liability derived) (:reserve-balance derived) (:allocator-balance derived)))
        (is (= [28 28 14] (mapv :allocated (:rows allocation))))
        (is (= [28 28 14] (mapv :paid (get-in realized [:settlement :withdrawals]))))
        (is (= 20 (get-in realized [:settlement :restricted-refund :amount])))
        (is (= {:deposited 90 :allocatable 70 :restricted 20} (:capital allocation)))
        (is (true? (:verified? (a/verify-statement material allocation))))
        (is (true? (:verified? (a/verify-statement material realized))))
        ;; the retained fixture is not accepted against the modified evidence
        (is (thrown? Exception (a/load-evidence! (.getPath dir)
                                                 "sha256:48afd9aa8553076ba24b017344d07dc3cb7fa2756a5e591caf4f2881feb05798"))))
      (finally
        (doseq [f (.listFiles dir)] (.delete f))
        (.delete dir)))))
