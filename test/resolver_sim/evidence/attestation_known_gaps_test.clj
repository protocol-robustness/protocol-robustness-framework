(ns resolver-sim.evidence.attestation-known-gaps-test
  "Known-gap characterisation tests.

   These tests document places where the current implementation accepts or
   silently handles inputs that a hardened system should reject or validate.
   They pass by asserting the *insecure* behaviour to make the gap visible.

   Each test includes a structured issue map (as a metadata map) with:
     :issue/id               — tracking identifier
     :current-behaviour      — what the implementation does today
     :intended-contract      — what the spec says should happen
     :reason-non-gating      — why this is not in the CI gate
     :removal-or-conversion-condition — what must change before the test
                                         can be inverted or removed

   Do not add these tests to any CI gate without explicit security review."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.evidence.attestation :as att]))

(defn- valid-attestor [] {:type :ci-runner :id :ci-validation})
(defn- valid-subject [] {:type :evidence-node :hash "sha256:abcdef1234567890"})

;; Gap: builder accepts nil attestor-id — create-attestation does not
;; require one. The validation layer is responsible for rejecting it,
;; but the builder provides no structural guarantee.
(deftest build-nil-attestor-id-accepted
  (testing "GAP: builder does not reject nil attestor-id"
    {:issue/id :gap-attestor-id-nil
     :current-behaviour "build-attestation accepts {:type :ci-runner :id nil} and stores nil as :attestation/attestor-id"
     :intended-contract "build-attestation should reject nil attestor-id via precondition or default to a well-known sentinel"
     :reason-non-gating "No downstream consumer currently depends on non-nil attestor-id in the attestation map; shape validation and registry checks catch it at verification time"
     :removal-or-conversion-condition "When a downstream consumer (bundle builder, registry, or verifier) rejects nil attestor-id as a hard error, this test can be inverted to assert rejection or removed"}
    (let [result (att/build-attestation {:type :ci-runner :id nil} (valid-subject) :verified)]
      (is (nil? (:attestation/attestor-id result))
          "attestor-id is nil — should be rejected or defaulted"))))

;; Gap: builder accepts any subject type without validation.
;; The subject :type is passed through to :attestation/subject-kind
;; without checking it is one of the allowed values.
(deftest build-unsupported-subject-type-accepted
  (testing "GAP: builder does not validate subject type against allowed kinds"
    {:issue/id :gap-subject-type-unsupported
     :current-behaviour "build-attestation accepts {:type :widget :hash \"sha256:x\"} and stores :widget as :attestation/subject-kind"
     :intended-contract "build-attestation should reject subject types outside #{:evidence-node :claim} or normalize them"
     :reason-non-gating "Shape validation (validate-attestation-shape) rejects non-standard subject kinds; the gap is only in the builder, not the verifier"
     :removal-or-conversion-condition "When build-attestation itself rejects non-standard subject kinds, this test can be inverted to assert rejection"}
    (let [result (att/build-attestation (valid-attestor) {:type :widget :hash "sha256:x"} :verified)]
      (is (= :widget (:attestation/subject-kind result))
          "builder accepts :widget as subject-kind — should require :evidence-node or :claim"))))

;; Gap: signing function returning nil produces nil signature without warning.
;; The builder stores nil as the signature when signing-fn returns nil.
(deftest build-signing-fn-returning-nil-produces-nil-signature
  (testing "GAP: signing fn returning nil is silently stored"
    {:issue/id :gap-signing-fn-nil
     :current-behaviour "build-attestation stores nil as :attestation/signature when signing-fn returns nil, with no warning"
     :intended-contract "build-attestation should warn or reject when signing-fn returns nil/non-map — a nil signature is indistinguishable from 'no signature requested'"
     :reason-non-gating "Verification treats nil signature as :unsigned (valid pass → no attestation); the only risk is silent data loss when a caller intends to sign but the signing-fn is misconfigured"
     :removal-or-conversion-condition "When build-attestation validates the signing-fn return value (rejecting nil and non-maps), this test can be inverted to assert rejection"}
    (let [result (att/build-attestation (valid-attestor) (valid-subject) :verified
                                        {:signing-key-id "k1" :signing-fn (fn [_] nil)})]
      (is (nil? (:attestation/signature result))
          "nil signature from signing-fn — should warn or reject"))))

;; Gap: signing function returning a string is stored as-is without validation.
;; The builder stores any return value from signing-fn without checking shape.
(deftest build-signing-fn-returning-string-stored-as-signature
  (testing "GAP: signing fn returning string is stored as signature map"
    {:issue/id :gap-signing-fn-string
     :current-behaviour "build-attestation stores arbitrary return values (including strings) as :attestation/signature"
     :intended-contract "build-attestation should validate that the signing-fn return value is a map with required keys (:algorithm, :public-key-id, :signature-bytes)"
     :reason-non-gating "Shape validation (validate-attestation-shape) and envelope validation (attestation-signature/validate-signature-envelope) reject malformed signatures downstream"
     :removal-or-conversion-condition "When build-attestation validates the signing-fn return shape directly, this test can be inverted to assert rejection"}
    (let [result (att/build-attestation (valid-attestor) (valid-subject) :verified
                                        {:signing-key-id "k1" :signing-fn (fn [_] "badsig")})]
      (is (= "badsig" (:attestation/signature result))
          "string stored as signature — builder should validate signing result shape"))))

;; Gap: subject-hash tampering passes shape validation.
;; Shape validation checks only that :attestation/subject-hash is non-nil.
;; Integrity/hash recomputation (separate layer) detects the inconsistency.
(deftest tamper-subject-hash-shape-valid-but-integrity-rejected
  (testing "GAP: shape validation accepts tampered subject-hash — integrity layer must catch it"
    {:issue/id :gap-subject-hash-tamper
     :current-behaviour "validate-attestation-shape only checks :attestation/subject-hash is non-nil — a tampered hash passes shape validation"
     :intended-contract "validate-attestation-shape should not be expected to detect content tampering (that is the integrity layer's job), but the gap is that there is no cross-layer signal when shape validation accepts a tampered field that integrity will reject"
     :reason-non-gating "The integrity layer (hash recomputation) reliably detects subject-hash tampering; shape validation is explicitly scoped to structure, not content correctness"
     :removal-or-conversion-condition "If a future spec revision merges shape and integrity validation into a single gate, this test should be removed and replaced with a passing integrity-level test for tamper detection"}
    (let [a (-> (att/build-attestation (valid-attestor) (valid-subject) :verified)
                (assoc :attestation/subject-hash "sha256:tampered"))
          shape-r (att/validate-attestation-shape a)]
      (is (:valid? shape-r)
          "subject-hash tamper is not detected by shape validation — it only checks non-nil"))))
