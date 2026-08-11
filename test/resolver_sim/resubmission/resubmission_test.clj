(ns resolver-sim.resubmission.resubmission-test
  "Tests for the resubmission contract implementation: golden canonical
   projections, submission-attempt receipt, immutable dispositions, root
   comparison kind derivation, the resubmission-link artifact, linear-chain CAS
   admission, and the composed validation report."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.evidence.artifact :as artifact]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.resubmission.basis :as basis]
            [resolver-sim.resubmission.chain :as chain]
            [resolver-sim.resubmission.derive-kind :as derive]
            [resolver-sim.resubmission.disposition :as disposition]
            [resolver-sim.resubmission.link :as link]
            [resolver-sim.resubmission.receipt :as receipt]
            [resolver-sim.resubmission.verify :as verify]
            [resolver-sim.support.ed25519 :as ed]))

;; ── fixed fixtures (golden values locked below) ─────────────────────────────

(def golden-basis
  {:results-artifact {:id "results-artifact" :sha256 "a" :kind "results-artifact"}
   :certificate {:schema-version "allocation-assurance-certificate.v1" :result-root "0xab"}
   :execution-evidence {:execution-id "e1"}
   :registry-entries [{:id "test-run" :sha256 "b"}]
   :publisher-envelope-unsigned {:schema-version "envelope.v1" :run-id "run-1"}
   :publisher-policy {:policy/id "artifact-publish-policy.v1" :key/id "pk-1"}})

(def golden-subject-root "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc")
(def golden-results-root "sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee")
(def golden-parent-id "sha256:PARENTPARENTPARENTPARENTPARENTPARENTPARENTPARENTPARENTPARENT")

(defn golden-receipt-base
  []
  {:attempt-receipt/schema "submission-attempt-receipt.v1"
   :attempt-receipt/submitted-bundle-root "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
   :attempt-receipt/roots
   {:research-subject {:root/schema "research-subject-root.v1" :status :verified :hash golden-subject-root}
    :execution-context {:root/schema "execution-context-root.v1" :status :verified
                        :hash "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"}
    :results {:root/schema "results-root.v1" :status :verified :hash golden-results-root}
    :submission-basis {:root/schema "submission-basis-root.v1" :status :verified
                       :hash "sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"}}
   :attempt-receipt/results {:status :valid
                             :submitted-hash golden-results-root
                             :verified-hash golden-results-root}
   :attempt-receipt/submitter {:status :verified :researcher-id "res-1"
                               :identity-source :publisher-signature
                               :policy-hash "sha256:1111111111111111111111111111111111111111111111111111111111111111"
                               :key-id "rk-1"}
   :attempt-receipt/outcome :rejected
   :attempt-receipt/finality :final
   :attempt-receipt/resubmission-eligibility :eligible
   :attempt-receipt/lifecycle-status :active
   :attempt-receipt/chain {:admission-status :admitted
                           :family-id "sha256:9999999999999999999999999999999999999999999999999999999999999999"
                           :sequence 1 :parent-receipt-hash nil}
   :attempt-receipt/evaluation {:acceptance-report-hash "sha256:7777777777777777777777777777777777777777777777777777777777777777"
                                :validator-version "v1"
                                :policy-hash "sha256:1111111111111111111111111111111111111111111111111111111111111111"
                                :evaluated-bundle-root "sha256:8888888888888888888888888888888888888888888888888888888888888888"
                                :evaluated-at "2026-08-06T00:00:00Z"}
   :attempt-receipt/findings
   [{:finding/id "sha256:abababababababababababababababababababababababababababababababab"
     :stage :allocation/reconciliation :assertion/id 14
     :reason :result-award-mismatch :subject {:claim-id "A"} :blocking? true}]
   :attempt-receipt/validator
   {:id "val-1" :version "v1" :policy/id "acceptance-policy.v1" :policy/version "1"
    :policy/hash "sha256:1111111111111111111111111111111111111111111111111111111111111111"
    :authorisation/id "va-1" :key/id "vk-1"}
   :attempt-receipt/observed-at "2026-08-06T00:00:00Z"})

(defn golden-link-base
  []
  {:schema-version link/link-schema
   :artifact/kind link/link-kind
   :artifact/verifier link/link-verifier
   :resubmission/family-id "sha256:9999999999999999999999999999999999999999999999999999999999999999"
   :resubmission/kind :exact-retry
   :resubmission/sequence 2
   :resubmission/parent {:attempt-receipt-hash golden-parent-id :sequence 1}
   :resubmission/current
   {:run-id "run-2"
    :research-subject {:root/schema "research-subject-root.v1" :root/hash golden-subject-root}
    :execution-context {:root/schema "execution-context-root.v1"
                        :root/hash "sha256:2222222222222222222222222222222222222222222222222222222222222222"}
    :results {:root/schema "results-root.v1" :root/hash golden-results-root}
    :results-artifact-hash "sha256:3333333333333333333333333333333333333333333333333333333333333333"
    :submission-basis {:root/schema "submission-basis-root.v1"
                       :root/hash "sha256:4444444444444444444444444444444444444444444444444444444444444444"}}
   :resubmission/remediation
   [{:finding-id "sha256:abababababababababababababababababababababababababababababababab"
     :disposition :addressed
     :evidence-hash "sha256:5555555555555555555555555555555555555555555555555555555555555555"}]
   :resubmission/change-set
   {:execution-context-changed? true :submission-basis-changed? true :results-changed? false}
   :resubmission/idempotency-key "sha256:27701d40ed609970eaa1df36b5c08a365c8fda8ad60e0a78ce3334408e7ee9e9"
   :resubmission/researcher
   {:researcher-id "res-1" :authorisation-id "auth-1"
    :policy/id "researcher-policy.v1" :policy/version "1"
    :policy/hash "sha256:6666666666666666666666666666666666666666666666666666666666666666"
    :key/id "rk-1"}})

;; ── golden canonical projections ─────────────────────────────────────────────

(deftest golden-projections
  (testing "locked canonical hashes for fixed inputs"
    (is (= "sha256:f0cf2e24bc701c36ab84e3d53c630128bb895d18b9b649edabaf1a8a02dd2b53"
           (basis/submission-basis-root golden-basis)))
    (is (= "sha256:c8b4a41628d737e5306afbaf1effe2ece36ceca141aa36e4f6fc33ef4a3fb7a7"
           (link/family-id golden-subject-root)))
    (is (= "sha256:27701d40ed609970eaa1df36b5c08a365c8fda8ad60e0a78ce3334408e7ee9e9"
           (link/idempotency-key {:parent-attempt-receipt-hash golden-parent-id
                                  :current-submission-basis-root "sha256:4444444444444444444444444444444444444444444444444444444444444444"
                                  :researcher-authorisation-id "auth-1"})))
    (is (= "sha256:81e5e3050afa4bd8ed18fa5e25a5381e829fc7d9add1ebe428473b928a116721"
           (receipt/receipt-hash (golden-receipt-base))))
    (is (= "sha256:feb9027047ecaaef03f108e980c0a8b010b56d42633c267fe70c0d5421ce203a"
           (link/resubmission-hash (golden-link-base))))
    (is (= "sha256:129662234b4d2c382e21a89af1139edebf996a71ada7ab378275dbf7691cbf62"
           (str "sha256:" (hc/domain-hash
                           "prf.acceptance-finding.v1"
                           {:stage :allocation/reconciliation :assertion-id 14
                            :reason :result-award-mismatch
                            :subject {:claim-id "A"} :blocking? true}))))))

;; ── basis (package cutpoints) ───────────────────────────────────────────────

(deftest basis-cutpoints
  (testing "shape validation"
    (is (true? (basis/basis-shape-valid? golden-basis)))
    (is (false? (basis/basis-shape-valid? (dissoc golden-basis :certificate))))
    (is (false? (basis/basis-shape-valid? (assoc golden-basis :final-bundle-root "x")))))
  (testing "deterministic roots"
    (is (= (basis/submission-basis-root golden-basis)
           (basis/submission-basis-root golden-basis)))
    (is (not= (basis/submission-basis-root golden-basis)
              (basis/submission-basis-root (assoc golden-basis
                                                  :results-artifact {:id "results-artifact" :sha256 "X"})))))
  (testing "final bundle root differs from basis root and commits the link"
    (let [proj (basis/final-bundle-projection
                {:submission-basis-root (basis/submission-basis-root golden-basis)
                 :resubmission-link-hash "sha256:feb9027047ecaaef03f108e980c0a8b010b56d42633c267fe70c0d5421ce203a"
                 :publisher-envelope-hash "sha256:abc"
                 :registry-root "sha256:def"})
          bundle (basis/final-bundle-root proj)]
      (is (string? bundle))
      (is (not= bundle (basis/submission-basis-root golden-basis)))
      (is (not= bundle
                (basis/final-bundle-root
                 (assoc proj :resubmission-link-hash "sha256:CHANGED")))))))

;; ── attempt receipt ─────────────────────────────────────────────────────────

(deftest receipt-contract
  (let [validator (ed/keypair :validator-key)
        signed (receipt/sign-receipt (golden-receipt-base) (:private-key validator))]
    (testing "shape and signing"
      (is (true? (receipt/valid-receipt-shape? signed)))
      (is (string? (:attempt-receipt/id signed)))
      (is (= (receipt/receipt-hash signed) (:attempt-receipt/id signed))))
    (testing "signature verifies and binds the full unsigned projection"
      (is (true? (:valid? (receipt/verify-receipt-signature signed (:public-hex validator)))))
      (is (false? (:valid? (receipt/verify-receipt-signature signed (:public-hex (ed/keypair)))))))
    (testing "tampering any authoritative field breaks hash and signature"
      (let [tampered (assoc-in signed [:attempt-receipt/validator :policy/hash] "sha256:FORGED")
            tampered2 (assoc signed :attempt-receipt/observed-at "2026-01-01T00:00:00Z")]
        (is (= :receipt-hash-mismatch (:reason (receipt/verify-receipt-signature tampered (:public-hex validator)))))
        (is (= :receipt-hash-mismatch (:reason (receipt/verify-receipt-signature tampered2 (:public-hex validator)))))))
    (testing "direct resubmission eligibility"
      (is (true? (receipt/direct-resubmission-parent? signed)))
      (is (nil? (receipt/resubmission-parent-requirement-mismatch signed)))
      (doseq [[k v reason] [[:attempt-receipt/outcome :accepted :parent-not-rejected]
                            [:attempt-receipt/finality :provisional :parent-rejection-not-final]
                            [:attempt-receipt/resubmission-eligibility :ineligible :parent-not-resubmittable]
                            [:attempt-receipt/lifecycle-status :withdrawn :parent-attempt-withdrawn]]]
        (let [signed-k (assoc signed k v)]
          (is (= reason (receipt/resubmission-parent-requirement-mismatch signed-k)))
          (is (false? (receipt/direct-resubmission-parent? signed-k))))))))

;; ── immutable dispositions ──────────────────────────────────────────────────

(deftest disposition-contract
  (let [authority (ed/keypair :disposition-key)
        base {:attempt-disposition/schema "attempt-disposition.v1"
              :attempt-disposition/attempt-receipt-hash "sha256:81e5e3050afa4bd8ed18fa5e25a5381e829fc7d9add1ebe428473b928a116721"
              :attempt-disposition/status :superseded
              :attempt-disposition/superseding-receipt-hash "sha256:NEW"
              :attempt-disposition/policy-hash "sha256:1111111111111111111111111111111111111111111111111111111111111111"}
        signed (disposition/sign-disposition base (:private-key authority))]
    (testing "sign/verify round-trip"
      (is (true? (:valid? (disposition/verify-disposition signed (:public-hex authority)))))
      (is (false? (:valid? (disposition/verify-disposition signed (:public-hex (ed/keypair)))))))
    (testing "effective lifecycle resolved from latest valid disposition"
      (let [withdraw (disposition/sign-disposition
                      (assoc base :attempt-disposition/status :withdrawn
                             :attempt-disposition/previous-disposition-hash (disposition/disposition-hash base))
                      (:private-key authority))]
        (is (= :withdrawn
               (disposition/effective-lifecycle-status
                [withdraw signed]
                (:attempt-disposition/attempt-receipt-hash signed)
                #(disposition/verify-disposition % (:public-hex authority))))))
      (is (= :active
             (disposition/effective-lifecycle-status
              []
              (:attempt-disposition/attempt-receipt-hash signed)
              #(disposition/verify-disposition % (:public-hex authority)))))
      (let [final (disposition/sign-disposition
                   (assoc base :attempt-disposition/status :final)
                   (:private-key authority))]
        (is (= :active
               (disposition/effective-lifecycle-status
                [final]
                (:attempt-disposition/attempt-receipt-hash signed)
                #(disposition/verify-disposition % (:public-hex authority))))
            "disposition finality does not deactivate the receipt"))
      (is (nil?
           (disposition/effective-lifecycle-status
            [(assoc signed :attempt-disposition/status :final)]
            (:attempt-disposition/attempt-receipt-hash signed)
            #(disposition/verify-disposition % (:public-hex authority))))
          "invalid chains fail closed rather than becoming active"))))

;; ── kind derivation ─────────────────────────────────────────────────────────

(defn- parent-with
  [& {:keys [results-status results-hash rejection-classification subject-hash]
      :or {results-status :verified
           results-hash golden-results-root
           rejection-classification :publisher-signature-invalid
           subject-hash golden-subject-root}}]
  {:roots {:research-subject {:status :verified :hash subject-hash}
           :execution-context {:status :verified
                               :hash "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"}
           :results {:status results-status :hash results-hash}
           :submission-basis {:status :verified
                              :hash "sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"}}
   :rejection-classification rejection-classification})

(deftest derived-kind
  (testing ":exact-retry — same verified result, non-semantic rejection, package/execution changed"
    (let [d (derive/derive-kind
             (parent-with)
             {:research-subject-hash golden-subject-root
              :results-hash golden-results-root
              :execution-context-hash "sha256:2222222222222222222222222222222222222222222222222222222222222222"
              :submission-basis-hash "sha256:4444444444444444444444444444444444444444444444444444444444444444"})]
      (is (= :exact-retry (:kind d)))))
  (testing ":corrected-result — verified result changed"
    (let [d (derive/derive-kind
             (parent-with :rejection-classification :result-award-mismatch)
             {:research-subject-hash golden-subject-root
              :results-hash "sha256:CHANGEDRESULT"
              :execution-context-hash "sha256:2222222222222222222222222222222222222222222222222222222222222222"
              :submission-basis-hash "sha256:4444444444444444444444444444444444444444444444444444444444444444"})]
      (is (= :corrected-result (:kind d)))))
  (testing ":submission-repair — parent results missing/invalid"
    (let [d (derive/derive-kind
             (parent-with :results-status :missing :results-hash nil)
             {:research-subject-hash golden-subject-root
              :results-hash "sha256:NEW"
              :execution-context-hash "sha256:E"
              :submission-basis-hash "sha256:B"})]
      (is (= :submission-repair (:kind d)))
      (is (= :parent-results-not-verified (:reason d)))))
  (testing ":lineage — subject changed"
    (let [d (derive/derive-kind
             (parent-with)
             {:research-subject-hash "sha256:DIFFERENTSUBJECT"
              :results-hash golden-results-root
              :execution-context-hash "sha256:E"
              :submission-basis-hash "sha256:B"})]
      (is (= :lineage (:kind d)))
      (is (= :subject-root-mismatch (:reason d)))))
  (testing ":duplicate-or-reevaluation — no authoritative change, non-semantic"
    (let [d (derive/derive-kind
             (parent-with)
             {:research-subject-hash golden-subject-root
              :results-hash golden-results-root
              :execution-context-hash "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
              :submission-basis-hash "sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"})]
      (is (= :duplicate-or-reevaluation (:kind d)))
      (is (= :no-authoritative-change (:reason d)))))
  (testing ":result-change-required — semantic rejection with unchanged result"
    (let [d (derive/derive-kind
             (parent-with :rejection-classification :result-award-mismatch)
             {:research-subject-hash golden-subject-root
              :results-hash golden-results-root
              :execution-context-hash "sha256:E"
              :submission-basis-hash "sha256:B"})]
      (is (= :none (:kind d)))
      (is (= :result-change-required (:reason d))))))

;; ── resubmission link artifact ──────────────────────────────────────────────

(deftest link-artifact
  (let [researcher (ed/keypair :researcher-key)
        link-artifact (link/finalize-link (golden-link-base) (:private-key researcher))]
    (testing "shape + artifact validity"
      (is (true? (link/valid-link-shape? link-artifact)))
      (is (true? (:valid? (artifact/verify-artifact link-artifact
                                                    link/link-schema link/link-kind link/link-verifier))))
      (is (string? (:artifact/hash link-artifact)))
      (is (string? (:artifact/preimage link-artifact))))
    (testing "signature + hash round-trip survives finalization"
      (is (true? (:valid? (link/verify-link-signature link-artifact (:public-hex researcher)))))
      (is (false? (:valid? (link/verify-link-signature link-artifact (:public-hex (ed/keypair)))))))
    (testing "mutating any authoritative field changes hash and breaks signature"
      (doseq [mut [(assoc-in link-artifact [:resubmission/researcher :policy/hash] "sha256:FORGED")
                   (assoc-in link-artifact [:resubmission/parent :attempt-receipt-hash] "sha256:OTHER")
                   (assoc-in link-artifact [:resubmission/researcher :key/id] "rk-2")
                   (assoc link-artifact :resubmission/sequence 3)]]
        (is (= :resubmission-hash-mismatch (:reason (link/verify-link-signature mut (:public-hex researcher)))))))
    (testing "hash stable across re-finalization of the same shape"
      (let [a (link/finalize-link (golden-link-base) (:private-key researcher))
            b (link/finalize-link (golden-link-base) (:private-key researcher))]
        (is (= (:resubmission/hash a) (:resubmission/hash b)))
        (is (= (:resubmission/hash a) "sha256:feb9027047ecaaef03f108e980c0a8b010b56d42633c267fe70c0d5421ce203a"))))))

;; ── linear-chain CAS admission ──────────────────────────────────────────────

(defn- admit-request
  [receipt-hash sequence parent & {:keys [link-hash idempotency basis-root]
                                   :or {link-hash "sha256:L" idempotency "sha256:I" basis-root "sha256:B"}}]
  {:receipt-hash receipt-hash :sequence sequence :parent-receipt-hash parent
   :link-hash link-hash :idempotency-key idempotency :basis-root basis-root})

(deftest canonical-chain-admission-requires-the-signed-authority-receipt
  (let [authority (ed/keypair :chain-validator)
        signed (receipt/sign-receipt (golden-receipt-base) (:private-key authority))
        c (chain/new-chain "sha256:FAM" nil (:public-hex authority))
        request {:receipt-hash "sha256:FORGED"
                 :candidate-attempt-receipt signed
                 :sequence 1 :parent-receipt-hash nil
                 :link-hash "sha256:L1" :idempotency-key "sha256:I1"
                 :basis-root "sha256:B1"}
        result (chain/admit! c request)]
    (is (= :admitted (:admission-status result)))
    (is (= (:attempt-receipt/id signed) (chain/current-head c))
        "the supplied bare receipt-hash cannot select chain identity")
    (is (= :receipt-authority-not-configured
           (:reason (chain/admit! (chain/new-chain "sha256:FAM") request))))))

(deftest linear-chain-admission
  (testing "initial attempt then a linear child"
    (let [c (chain/new-chain "sha256:FAM")
          r1 (chain/admit-compat! c (admit-request "sha256:R1" 1 nil :basis-root "sha256:B1" :link-hash "sha256:L1" :idempotency "sha256:I1"))]
      (is (= :admitted (:admission-status r1)))
      (is (= "sha256:R1" (chain/current-head c)))
      (let [r2 (chain/admit-compat! c (admit-request "sha256:R2" 2 "sha256:R1" :basis-root "sha256:B2" :link-hash "sha256:L2" :idempotency "sha256:I2"))]
        (is (= :admitted (:admission-status r2)))
        (is (= "sha256:R2" (chain/current-head c))))))
  (testing "parent must be current head"
    (let [c (chain/new-chain "sha256:FAM")
          _ (chain/admit-compat! c (admit-request "sha256:R1" 1 nil :basis-root "sha256:B1" :link-hash "sha256:L1" :idempotency "sha256:I1"))
          _ (chain/admit-compat! c (admit-request "sha256:R2" 2 "sha256:R1" :basis-root "sha256:B2" :link-hash "sha256:L2" :idempotency "sha256:I2"))
          r3 (chain/admit-compat! c (admit-request "sha256:R3" 3 "sha256:R1" :basis-root "sha256:B3" :link-hash "sha256:L3" :idempotency "sha256:I3"))]
      (is (= :parent-not-current-head (:reason r3)))
      (is (= "sha256:R2" (chain/current-head c)))))
  (testing "parent already has a successor"
    (let [c (chain/new-chain "sha256:FAM")
          _ (chain/admit-compat! c (admit-request "sha256:R1" 1 nil :basis-root "sha256:B1" :link-hash "sha256:L1" :idempotency "sha256:I1"))
          _ (chain/admit-compat! c (admit-request "sha256:R2" 2 "sha256:R1" :basis-root "sha256:B2" :link-hash "sha256:L2" :idempotency "sha256:I2"))
          r3 (chain/admit-compat! c (admit-request "sha256:R3" 2 "sha256:R1" :basis-root "sha256:B3" :link-hash "sha256:L3" :idempotency "sha256:I3"))]
      (is (= :parent-not-current-head (:reason r3)))))
  (testing "sequence gap and regression"
    (let [c (chain/new-chain "sha256:FAM")
          _ (chain/admit-compat! c (admit-request "sha256:R1" 1 nil :basis-root "sha256:B1" :link-hash "sha256:L1" :idempotency "sha256:I1"))]
      (is (= :sequence-gap (:reason (chain/admit-compat! c (admit-request "sha256:R9" 9 "sha256:R1" :basis-root "sha256:B9" :link-hash "sha256:L9" :idempotency "sha256:I9")))))
      (let [c2 (chain/new-chain "sha256:FAM")
            _ (chain/admit-compat! c2 (admit-request "sha256:R1" 1 nil :basis-root "sha256:B1" :link-hash "sha256:L1" :idempotency "sha256:I1"))
            _ (chain/admit-compat! c2 (admit-request "sha256:R2" 2 "sha256:R1" :basis-root "sha256:B2" :link-hash "sha256:L2" :idempotency "sha256:I2"))]
        (is (= :sequence-regression (:reason (chain/admit-compat! c2 (admit-request "sha256:R3" 2 "sha256:R2" :basis-root "sha256:B3" :link-hash "sha256:L3" :idempotency "sha256:I3"))))))))
  (testing "cycle detection"
    (let [c (chain/new-chain "sha256:FAM")
          _ (chain/admit-compat! c (admit-request "sha256:R1" 1 nil :basis-root "sha256:B1" :link-hash "sha256:L1" :idempotency "sha256:I1"))
          r (chain/admit-compat! c (admit-request "sha256:R1" 2 "sha256:R1" :basis-root "sha256:B2" :link-hash "sha256:L2" :idempotency "sha256:I2"))]
      ;; R1 is already committed as the root, so re-admitting it (even as its
      ;; own parent) is a prior-state integrity violation, reported before the
      ;; child==parent cycle check.
      (is (= :receipt-already-committed (:reason r)))
      (is (= :not-admitted (:admission-status r)))))
  (testing "dedup before head check"
    (let [c (chain/new-chain "sha256:FAM")
          _ (chain/admit-compat! c (admit-request "sha256:R1" 1 nil :basis-root "sha256:B1" :link-hash "sha256:L1" :idempotency "sha256:I1"))
          _ (chain/admit-compat! c (admit-request "sha256:R2" 2 "sha256:R1" :basis-root "sha256:B2" :link-hash "sha256:L2" :idempotency "sha256:I2"))]
      (testing "idempotent replay of R2"
        (let [r (chain/admit-compat! c (admit-request "sha256:R2x" 3 "sha256:R1" :basis-root "sha256:B2" :link-hash "sha256:L2" :idempotency "sha256:I2"))]
          (is (= :submission-already-observed (:reason r)))
          (is (= "sha256:R2" (:existing r)))))
      (testing "same link projection under a different key"
        (let [r (chain/admit-compat! c (admit-request "sha256:R2x" 3 "sha256:R1" :basis-root "sha256:B2" :link-hash "sha256:L2" :idempotency "sha256:I2x"))]
          (is (= :duplicate-content-submission (:reason r)))))
      (testing "same basis root, another parent (transplant)"
        (let [c3 (chain/new-chain "sha256:FAM")
              _ (chain/admit-compat! c3 (admit-request "sha256:Q1" 1 nil :basis-root "sha256:B1" :link-hash "sha256:L1" :idempotency "sha256:I1"))
              _ (chain/admit-compat! c3 (admit-request "sha256:Q2" 2 "sha256:Q1" :basis-root "sha256:B2" :link-hash "sha256:L2" :idempotency "sha256:I2"))
              r (chain/admit-compat! c3 (admit-request "sha256:Q3" 3 "sha256:Q2" :basis-root "sha256:B2" :link-hash "sha256:L3" :idempotency "sha256:I3"))]
          (is (= :idempotency-key-rebound (:reason r)))))))
  (testing "concurrent admit! admits exactly one successor"
    (let [c (chain/new-chain "sha256:FAM")
          _ (chain/admit-compat! c (admit-request "sha256:R1" 1 nil :basis-root "sha256:B1" :link-hash "sha256:L1" :idempotency "sha256:I1"))
          futures (doall (map (fn [i]
                                (future (chain/admit-compat! c (admit-request (str "sha256:C" i) 2 "sha256:R1"
                                                                       :basis-root (str "sha256:BC" i)
                                                                       :link-hash (str "sha256:LC" i)
                                                                       :idempotency (str "sha256:IC" i)))))
                              (range 6)))
          results (mapv deref futures)
          admitted (count (filter #(= :admitted (:admission-status %)) results))]
      (is (= 1 admitted))
      (is (some? (chain/current-head c)))
      (is (not= "sha256:R1" (chain/current-head c))))))

(defn- packaging-receipt
  "A parent receipt rejected for a packaging/signature failure (non-semantic),
   so the same verified result is a legitimate :exact-retry parent."
  [validator-kp]
  (receipt/sign-receipt
   (assoc (golden-receipt-base)
          :attempt-receipt/findings
          [{:finding/id "sha256:abababababababababababababababababababababababababababababababab"
            :stage :publisher :assertion/id nil
            :reason :publisher-signature-invalid
            :subject {} :blocking? true}])
   (:private-key validator-kp)))

;; ── composed validation report ──────────────────────────────────────────────

(deftest composed-verification
  (let [validator (ed/keypair :validator-key)
        researcher (ed/keypair :researcher-key)
        parent (packaging-receipt validator)
        link-artifact (link/finalize-link (golden-link-base) (:private-key researcher))]
    (testing "local + historical stages compose into an acceptance report"
      (let [stage1 (verify/validate-link-artifact link-artifact (:public-hex researcher))
            stage2 (verify/validate-bundle-binding
                    link-artifact
                    {:run-id "run-2"
                     :results-artifact-hash "sha256:3333333333333333333333333333333333333333333333333333333333333333"
                     :results-root golden-results-root})
            stage3a (verify/validate-researcher-authority
                     link-artifact
                     (fn [_policy-hash _key-id]
                       {:public-hex (:public-hex researcher)
                        :status :active :valid-at-cutpoint true}))
            stage3b (verify/validate-derived-kind
                     parent :exact-retry
                     {:research-subject-hash golden-subject-root
                      :results-hash golden-results-root
                      :execution-context-hash "sha256:2222222222222222222222222222222222222222222222222222222222222222"
                      :submission-basis-hash "sha256:4444444444444444444444444444444444444444444444444444444444444444"})
            stage3c (verify/validate-remediation parent link-artifact)
            stage4 (verify/validate-parent-receipt
                    parent (:public-hex validator) []
                    (fn [_] {:valid? true}))
            report (verify/resubmission-acceptance-report
                    {:link-artifact stage1
                     :bundle-binding stage2
                     :authority stage3a
                     :derived-kind stage3b
                     :remediation stage3c
                     :parent stage4
                     :previous-blocking-findings ["sha256:abababababababababababababababababababababababababababababababab"]
                     :current-gate-results {:result-capacity-reconciles :pass
                                            :valid-certificate :pass
                                            :results-artifact :pass}})]
        (is (true? (:resubmission-link-valid? report)))
        (is (= :pass (get-in report [:current-gate-results :result-capacity-reconciles])))))
    (testing "valid link with failing current gate is NOT a successful correction"
      (let [stage1 (verify/validate-link-artifact link-artifact (:public-hex researcher))
            stage4 (verify/validate-parent-receipt parent (:public-hex validator) []
                                                   (fn [_] {:valid? true}))
            current {:research-subject-hash golden-subject-root
                     :results-hash golden-results-root
                     :execution-context-hash "sha256:2222222222222222222222222222222222222222222222222222222222222222"
                     :submission-basis-hash "sha256:4444444444444444444444444444444444444444444444444444444444444444"}
            report (verify/resubmission-acceptance-report
                    {:link-artifact stage1
                     :bundle-binding (verify/validate-bundle-binding
                                      link-artifact
                                      {:run-id "run-2"
                                       :results-artifact-hash "sha256:3333333333333333333333333333333333333333333333333333333333333333"
                                       :results-root golden-results-root})
                     :authority (verify/validate-researcher-authority
                                 link-artifact (fn [_ _] {:public-hex (:public-hex researcher)
                                                          :status :active :valid-at-cutpoint true}))
                     :derived-kind (verify/validate-derived-kind parent :exact-retry current)
                     :remediation (verify/validate-remediation parent link-artifact)
                     :parent stage4
                     :previous-blocking-findings []
                     :current-gate-results {:result-capacity-reconciles :fail
                                            :valid-certificate :pass
                                            :results-artifact :pass}})]
        (is (= :fail (get-in report [:current-gate-results :result-capacity-reconciles])))
        (is (= :pass (get-in report [:current-gate-results :valid-certificate])))))
    (testing "a missing blocking finding is structurally invalid"
      (let [stage3c (verify/validate-remediation parent (assoc-in link-artifact [:resubmission/remediation] []))]
        (is (= :rejection-finding-unaccounted (:reason stage3c)))
        (is (seq (:missing stage3c)))))
    (testing "declared kind mismatch is rejected"
      (let [stage3b (verify/validate-derived-kind
                     parent :corrected-result
                     {:research-subject-hash golden-subject-root
                      :results-hash golden-results-root
                      :execution-context-hash "sha256:E"
                      :submission-basis-hash "sha256:B"})]
        (is (= :declared-kind-mismatch (:reason stage3b)))))
    (testing "submission-repair with a verified parent result is rejected"
      (let [stage3b (verify/validate-derived-kind
                     parent :submission-repair
                     {:research-subject-hash golden-subject-root
                      :results-hash golden-results-root
                      :execution-context-hash "sha256:E"
                      :submission-basis-hash "sha256:B"})]
                 (is (= :submission-repair-not-permitted (:reason stage3b))))))

;; ── new-chain arities ────────────────────────────────────────────────────────

(deftest new-chain-3-arity-stores-receipt-public-hex
  (let [c (chain/new-chain "sha256:FAM" nil "sha256:receipt-pk")]
    (is (= "sha256:receipt-pk" (.receipt-public-hex c)))))

;; ── admit! finality guard ────────────────────────────────────────────────────

(deftest admit-rejects-non-final-receipt
  (let [authority (ed/keypair :chain-validator)
        signed (receipt/sign-receipt
                (assoc (golden-receipt-base) :attempt-receipt/finality :pending)
                (:private-key authority))
        c (chain/new-chain "sha256:FAM" nil (:public-hex authority))
        request {:receipt-hash "sha256:FORGED"
                 :candidate-attempt-receipt signed
                 :sequence 1 :parent-receipt-hash nil
                 :link-hash "sha256:L1" :idempotency-key "sha256:I1"
                 :basis-root "sha256:B1"}]
    (is (= :not-admitted (:admission-status (chain/admit! c request))))
    (is (= :receipt-not-final (:reason (chain/admit! c request))))))

;; ── admit-compat! runtime guard ──────────────────────────────────────────────

(deftest admit-compat-guard-fails-closed-when-active
  (let [c (chain/new-chain "sha256:FAM")]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"admit-compat! is forbidden"
                          (binding [chain/*admit-compat-guard* :enforced]
                            (chain/admit-compat! c (admit-request "sha256:R1" 1 nil)))))))

(deftest admit-compat-allowed-when-guard-is-nil
  (let [c (chain/new-chain "sha256:FAM")]
    (is (= :admitted
           (:admission-status
            (binding [chain/*admit-compat-guard* nil]
              (chain/admit-compat! c (admit-request "sha256:R1" 1 nil)))))))))
