(ns resolver-sim.resubmission.receipt-test
  "Tests for V1/V2 receipt structure, hash separation, and signature dispatch."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.resubmission.attempt-subject :as attempt-subject]
            [resolver-sim.resubmission.receipt :as receipt]
            [resolver-sim.support.ed25519 :as ed]))

(def ^:private app-root
  "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc")

(def ^:private subj-root
  "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")

(def ^:private bundle-root
  "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")

(defn- v1-candidate
  "A minimal V1 receipt candidate with valid shape."
  []
  {:attempt-receipt/schema receipt/receipt-schema
   :attempt-receipt/submitted-bundle-root bundle-root
   :attempt-receipt/outcome :rejected
   :attempt-receipt/finality :final
   :attempt-receipt/resubmission-eligibility :eligible
   :attempt-receipt/lifecycle-status :active
   :attempt-receipt/roots
   {:research-subject {:root/schema "research-subject-root.v1" :status :verified :hash subj-root}
    :execution-context {:root/schema "execution-context-root.v1" :status :verified :hash subj-root}
    :results {:root/schema "results-root.v1" :status :verified :hash subj-root}
    :submission-basis {:root/schema "submission-basis-root.v1" :status :verified :hash subj-root}}
   :attempt-receipt/results {:status :valid
                             :submitted-hash bundle-root
                             :verified-hash bundle-root}
   :attempt-receipt/submitter {:status :verified :researcher-id "r1"
                               :identity-source :publisher-signature
                               :policy-hash bundle-root
                               :key-id "rk-1"}
   :attempt-receipt/evaluation {:acceptance-report-hash bundle-root
                                :validator-version "v1"
                                :policy-hash bundle-root
                                :evaluated-bundle-root bundle-root
                                :evaluated-at "2026-08-06T00:00:00Z"}
   :attempt-receipt/findings []
   :attempt-receipt/validator
   {:id "val-1" :version "v1" :policy/id "acceptance-policy.v1" :policy/version "1"
    :policy/hash bundle-root
    :authorisation/id "va-1" :key/id "vk-1"}
   :attempt-receipt/observed-at "2026-08-06T00:00:00Z"})

(defn- v2-candidate
  "A minimal V2 receipt candidate (same content as V1 but schema v2 + optional subject root)."
  [with-subject-root?]
  (let [c (-> (v1-candidate)
              (assoc :attempt-receipt/schema receipt/receipt-v2-schema)
              (assoc :attempt-receipt/chain
                     {:admission-status :admitted
                      :family-id "sha256:FAM"
                      :sequence 2
                      :parent-receipt-hash "sha256:R1"
                      :transaction-ordering-hash "sha256:ORD"}))]
    (if with-subject-root?
      (assoc c :attempt-receipt/attempt-subject-root subj-root)
      c)))

(defn- v2-chain-receipt
  "Build a V2 receipt with a chain and optional subject root."
  [with-subject-root?]
  (let [c (v2-candidate with-subject-root?)]
    (assoc-in c
              [:attempt-receipt/chain :transaction-ordering-hash]
              "sha256:ORD")))

(deftest v2-receipt-schema-distinction
  (testing "V1 and V2 schemas are distinct"
    (is (not= receipt/receipt-schema receipt/receipt-v2-schema))
    (is (= "submission-attempt-receipt.v1" receipt/receipt-schema))
    (is (= "submission-attempt-receipt.v2" receipt/receipt-v2-schema)))
  (testing "receipt-schema-of defaults to V1 for bare receipts"
    (is (= receipt/receipt-schema (receipt/receipt-schema-of (v1-candidate))))
    (is (false? (receipt/v2-receipt? (v1-candidate)))))
  (testing "v2-receipt? detects V2 schema"
    (let [v2 (v2-candidate true)]
      (is (true? (receipt/v2-receipt? v2)))
      (is (not (receipt/v2-receipt? (v1-candidate)))))))

(deftest v2-requires-attempt-subject-root-at-signing
  (testing "sign-receipt-v2 throws when attempt-subject-root is absent"
    (let [v2-no-subject (v2-candidate false)
          pk (:private-key (ed/keypair :v2-sign-test-1))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"V2 receipt requires"
                            (receipt/sign-receipt-v2 v2-no-subject pk)))))
  (testing "sign-receipt-v2 accepts a valid attempt-subject-root"
    (let [v2-with-subject (v2-candidate true)
          pk (:private-key (ed/keypair :v2-sign-test-2))
          signed (receipt/sign-receipt-v2 v2-with-subject pk)]
      (is (some? (:attempt-receipt/id signed)))
      (is (some? (get-in signed [:attempt-receipt/validator :signature])))))
  (testing "sign-receipt-v2 rejects a malformed attempt-subject-root"
    (let [v2-bad-subject (-> (v2-candidate false)
                             (assoc :attempt-receipt/attempt-subject-root "not-a-ref"))
          pk (:private-key (ed/keypair :v2-sign-test-3))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"V2 receipt requires"
                            (receipt/sign-receipt-v2 v2-bad-subject pk))))))

(deftest v2-hash-domain-separation
  (testing "identical content under V1 vs V2 schemas produces different roots"
    (let [validator-key (ed/keypair :v2-hash-test)
          v1 (v1-candidate)
          v2 (v2-chain-receipt true)
          v1-id (:attempt-receipt/id (receipt/sign-receipt v1 (:private-key validator-key)))
          v2-id (:attempt-receipt/id (receipt/sign-receipt-v2 v2 (:private-key validator-key)))]
      (is (not= v1-id v2-id)
          "V1 and V2 receipts must never share an id due to domain separation")
      (is (hash-ref/valid-sha256-ref? v1-id))
      (is (hash-ref/valid-sha256-ref? v2-id))))
  (testing "v2 receipt-hash-v2 is deterministic"
    (let [v2-a (v2-chain-receipt true)
          v2-b (v2-chain-receipt true)]
      (is (= (receipt/receipt-hash-v2 v2-a) (receipt/receipt-hash-v2 v2-b)))))
  (testing "v2 hash changes when attempt-subject-root changes"
    (let [v2-base (v2-chain-receipt true)
          v2-diff (assoc v2-base
                         :attempt-receipt/attempt-subject-root
                         "sha256:9999999999999999999999999999999999999999999999999999999999999999")]
      (is (not= (receipt/receipt-hash-v2 v2-base)
                (receipt/receipt-hash-v2 v2-diff))))))

(deftest v2-shape-validation
  (testing "valid-receipt-dispatch? routes by schema"
    (let [v1 (v1-candidate)
          v2-no-subj (v2-candidate false)
          v2-with-subj (v2-candidate true)]
      (is (true? (receipt/valid-receipt-dispatch? v1)))
      (is (false? (receipt/valid-receipt-dispatch? v2-no-subj))
          "V2 receipt without subject root fails strict validation")
      (is (true? (receipt/valid-receipt-dispatch? v2-with-subj)))
      (is (not (receipt/v2-receipt? v1)))))
  (testing "valid-receipt-dispatch? rejects missing required roots"
    (let [v2-bad (-> (v2-candidate true)
                     (assoc-in [:attempt-receipt/chain :transaction-ordering-hash]
                               "sha256:ORD")
                     (assoc-in [:attempt-receipt/roots :research-subject :hash]
                               nil))]
      (is (false? (receipt/valid-receipt-dispatch? v2-bad)))))
  (testing "valid-receipt-dispatch? rejects V2 with malformed subject root"
    (let [v2-bad-subj (-> (v2-candidate false)
                          (assoc-in [:attempt-receipt/chain :transaction-ordering-hash]
                                    "sha256:ORD")
                          (assoc :attempt-receipt/attempt-subject-root "not-a-ref"))]
      (is (false? (receipt/valid-receipt-dispatch? v2-bad-subj))))))

(deftest v2-signature-verification-dispatch
  (testing "verify-receipt-signature-dispatch routes V1 and V2 correctly"
    (let [validator-key (ed/keypair :validator-sig-test)
          v1 (v1-candidate)
          v2 (v2-chain-receipt true)
          signed-v1 (receipt/sign-receipt v1 (:private-key validator-key))
          signed-v2 (receipt/sign-receipt-v2 v2 (:private-key validator-key))]
      (is (:valid? (receipt/verify-receipt-signature-dispatch
                    signed-v1 (:public-hex validator-key))))
      (is (:valid? (receipt/verify-receipt-signature-dispatch
                    signed-v2 (:public-hex validator-key))))
      (is (not (:valid? (receipt/verify-receipt-signature
                         signed-v2 (:public-hex validator-key)))))
      (is (not (:valid? (receipt/verify-receipt-signature-v2
                         signed-v1 (:public-hex validator-key)))))))
  (testing "tampered V2 signature is rejected by dispatch"
    (let [validator-key (ed/keypair :validator-sig-test-2)
          v2 (v2-chain-receipt true)
          signed-v2 (receipt/sign-receipt-v2 v2 (:private-key validator-key))
          tampered (assoc-in signed-v2
                             [:attempt-receipt/validator :signature :signature]
                             (apply str (repeat 64 "0")))]
      (is (false? (:valid? (receipt/verify-receipt-signature-dispatch
                            tampered (:public-hex validator-key))))))
    (testing "missing signature on V2 receipt is rejected"
      (let [v2 (v2-chain-receipt true)]
        (is (= :missing-validator-signature
               (:reason (receipt/verify-receipt-signature-dispatch v2 "deadbeef"))))))))

(deftest v2-id-excludes-signature
  (testing "V2 id equals recomputed hash; signature does not affect hash"
    (let [pk (:private-key (ed/keypair :v2-proj-test))
          v2 (v2-chain-receipt true)
          signed (receipt/sign-receipt-v2 v2 pk)
          proj-hash (receipt/receipt-hash-v2 v2)
          tampered (assoc-in signed
                             [:attempt-receipt/validator :signature :signature]
                             (apply str (repeat 64 "0")))
          proj-hash-alt (receipt/receipt-hash-v2 tampered)]
      (is (hash-ref/valid-sha256-ref? proj-hash))
      (is (= proj-hash (:attempt-receipt/id signed)))
      (is (= proj-hash proj-hash-alt)
          "signature bytes do not affect the hash"))))

(deftest attempt-subject-validity
  (testing "valid? checks closed shape and subject-root"
    (let [subject (attempt-subject/build
                   bundle-root subj-root
                   {:attempt-target/type :use-case-application
                    :attempt-target/root app-root})]
      (is (true? (attempt-subject/valid? subject)))
      (is (= (:attempt/subject-root subject)
             (attempt-subject/root subject)))))
  (testing "attempt subject rejects non-use-case-application target type"
    (is (false? (attempt-subject/valid-target?
                 {:attempt-target/type :other
                  :attempt-target/root app-root}))))
  (testing "attempt subject rejects malformed target root"
    (is (false? (attempt-subject/valid-target?
                 {:attempt-target/type :use-case-application
                  :attempt-target/root "not-a-ref"})))))

(deftest receipt-binds-attempt-subject
  (testing "receipt-binds-attempt-subject verifies subject root against receipt"
    (let [subject (attempt-subject/build
                   bundle-root subj-root
                   {:attempt-target/type :use-case-application
                    :attempt-target/root app-root})
          receipt-v2 (-> (v2-candidate false)
                         (assoc-in [:attempt-receipt/chain :transaction-ordering-hash]
                                   "sha256:ORD")
                         (assoc :attempt-receipt/attempt-subject-root
                                (:attempt/subject-root subject)))]
      (is (true? (receipt/receipt-binds-attempt-subject? receipt-v2 subject)))))
  (testing "rejects when subject root does not match receipt"
    (let [subject (attempt-subject/build
                   bundle-root subj-root
                   {:attempt-target/type :use-case-application
                    :attempt-target/root app-root})
          receipt-v2 (-> (v2-candidate false)
                         (assoc-in [:attempt-receipt/chain :transaction-ordering-hash]
                                   "sha256:ORD")
                         (assoc :attempt-receipt/attempt-subject-root
                                "sha256:9999999999999999999999999999999999999999999999999999999999999999"))]
      (is (false? (receipt/receipt-binds-attempt-subject? receipt-v2 subject))))))

(deftest v2-verification-path-complete
  (testing "V2 verification composes: schema dispatch → hash verification → signature → subject binding"
    (let [validator-key (ed/keypair :v2-verification-path)
          evaluation-root "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
          submitted-bundle-root "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
          target {:attempt-target/type :use-case-application
                  :attempt-target/root app-root}
          subject (attempt-subject/build evaluation-root submitted-bundle-root target)
          v2-candidate (-> (v2-candidate false)
                           (assoc-in [:attempt-receipt/chain :transaction-ordering-hash]
                                     "sha256:ORD")
                           (assoc :attempt-receipt/attempt-subject-root
                                  (:attempt/subject-root subject)))
          signed (receipt/sign-receipt-v2 v2-candidate (:private-key validator-key))]
      (testing "1. schema dispatch routes to V2"
        (is (receipt/v2-receipt? signed))
        (is (= receipt/receipt-v2-schema (:attempt-receipt/schema signed))))
      (testing "2. receipt hash verification"
        (is (= (:attempt-receipt/id signed) (receipt/receipt-hash-v2 signed))
            "receipt id matches recomputed V2 hash"))
      (testing "3. signature verification"
        (is (:valid? (receipt/verify-receipt-signature-dispatch
                      signed (:public-hex validator-key)))
            "signature verifies under V2 domain"))
      (testing "4. attempt-subject reconstruction and binding"
        (is (receipt/receipt-binds-attempt-subject? signed subject)
            "receipt binds the reconstructed subject root"))
      (testing "5. full verification path composes correctly"
        (let [sig-result (receipt/verify-receipt-signature-dispatch
                          signed (:public-hex validator-key))
              binds-subject? (receipt/receipt-binds-attempt-subject? signed subject)]
          (is (and (:valid? sig-result) binds-subject?)
              "complete V2 verification path succeeds"))))))

(deftest v2-verification-rejects-missing-subject-root
  (testing "V2 receipt without subject root fails verification"
    (let [validator-key (ed/keypair :v2-no-subj-verify)
          v2-no-subj (-> (v2-candidate false)
                         (assoc-in [:attempt-receipt/chain :transaction-ordering-hash]
                                   "sha256:ORD"))
          signed (receipt/sign-receipt-v2
                  (assoc v2-no-subj :attempt-receipt/attempt-subject-root subj-root)
                  (:private-key validator-key))
          ;; Mutate to remove subject root
          no-subj-root (dissoc signed :attempt-receipt/attempt-subject-root)]
      ;; First, verify that signing without subject root fails
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"V2 receipt requires"
                            (receipt/sign-receipt-v2 v2-no-subj (:private-key validator-key)))
          "signing V2 receipt without subject root throws")
      ;; Then verify that a signed receipt with subject root that is removed fails
      (is (false? (receipt/valid-receipt-dispatch? no-subj-root))
          "V2 receipt without subject root fails shape validation"))))