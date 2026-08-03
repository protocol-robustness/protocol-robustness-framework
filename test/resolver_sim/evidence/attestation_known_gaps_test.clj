(ns resolver-sim.evidence.attestation-known-gaps-test
  "Known-gap characterisation tests.

   These tests document places where the current implementation accepts or
   silently handles inputs that a hardened system should reject or validate.
   They pass by asserting the *insecure* behaviour to make the gap visible.

   The canonical issue metadata for each gap (including
   :removal-or-conversion-condition) lives in data/known-gaps/attestation.edn,
   the central known-gaps registry. Each test references its issue by
   :issue/id instead of duplicating the map inline.

   Do not add these tests to any CI gate without explicit security review."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.evidence.attestation :as att]
            [resolver-sim.evidence.attestation-integrity :as integ]))

(defn- valid-attestor [] {:type :ci-runner :id :ci-validation})
(defn- valid-subject [] {:type :evidence-node :hash "sha256:abcdef1234567890"})

(def ^:private known-gap-registry
  "Loaded from the central known-gaps registry (data/known-gaps/attestation.edn)."
  (edn/read-string (slurp (io/resource "data/known-gaps/attestation.edn"))))

(defn- issue [id]
  (or (first (filter #(= (:issue/id %) id) known-gap-registry))
      (throw (ex-info (str "Known gap not found in registry: " id) {:issue/id id}))))

;; Gap: builder accepts nil attestor-id — create-attestation does not
;; require one. The validation layer is responsible for rejecting it,
;; but the builder provides no structural guarantee.
;;
;; REMOVAL-OR-CONVERSION CONDITION MET: the integrity verifier (used by the
;; attestation bundle verifier) rejects a nil attestor-id as a hard error, so
;; this test is now converted to assert that downstream rejection.
(deftest build-nil-attestor-id-rejected-by-verifier
  (testing "RESOLVED: builder still accepts nil attestor-id, but the integrity verifier rejects it"
    (is (= :gap-attestor-id-nil (:issue/id (issue :gap-attestor-id-nil))))
    (is (true? (:resolved (issue :gap-attestor-id-nil)))
        "gap is marked resolved in the known-gaps registry")
    (let [built (att/build-attestation {:type :ci-runner :id nil} (valid-subject) :verified)
          result (integ/verify-attestation-integrity built)]
      (is (nil? (:attestation/attestor-id built)))
      (is (false? (:valid? result))
          "integrity verifier rejects a nil attestor-id as a hard error")
      (is (some #(re-find #"attestor-id" %) (:errors result))))))

;; Gap: builder accepts any subject type without validation.
;; The subject :type is passed through to :attestation/subject-kind
;; without checking it is one of the allowed values.
(deftest build-unsupported-subject-type-accepted
  (testing "GAP: builder does not validate subject type against allowed kinds"
    (is (= :gap-subject-type-unsupported (:issue/id (issue :gap-subject-type-unsupported))))
    (let [result (att/build-attestation (valid-attestor) {:type :widget :hash "sha256:x"} :verified)]
      (is (= :widget (:attestation/subject-kind result))
          "builder accepts :widget as subject-kind — should require :evidence-node or :claim"))))

;; Gap: signing function returning nil produces nil signature without warning.
;; The builder stores nil as the signature when signing-fn returns nil.
(deftest build-signing-fn-returning-nil-produces-nil-signature
  (testing "GAP: signing fn returning nil is silently stored"
    (is (= :gap-signing-fn-nil (:issue/id (issue :gap-signing-fn-nil))))
    (let [result (att/build-attestation (valid-attestor) (valid-subject) :verified
                                        {:signing-key-id "k1" :signing-fn (fn [_] nil)})]
      (is (nil? (:attestation/signature result))
          "nil signature from signing-fn — should warn or reject"))))

;; Gap: signing function returning a string is stored as-is without validation.
;; The builder stores any return value from signing-fn without checking shape.
(deftest build-signing-fn-returning-string-stored-as-signature
  (testing "GAP: signing fn returning string is stored as signature map"
    (is (= :gap-signing-fn-string (:issue/id (issue :gap-signing-fn-string))))
    (let [result (att/build-attestation (valid-attestor) (valid-subject) :verified
                                        {:signing-key-id "k1" :signing-fn (fn [_] "badsig")})]
      (is (= "badsig" (:attestation/signature result))
          "string stored as signature — builder should validate signing result shape"))))

;; Gap: subject-hash tampering passes shape validation.
;; Shape validation checks only that :attestation/subject-hash is non-nil.
;; Integrity/hash recomputation (separate layer) detects the inconsistency.
(deftest tamper-subject-hash-shape-valid-but-integrity-rejected
  (testing "GAP: shape validation accepts tampered subject-hash — integrity layer must catch it"
    (is (= :gap-subject-hash-tamper (:issue/id (issue :gap-subject-hash-tamper))))
    (let [a (-> (att/build-attestation (valid-attestor) (valid-subject) :verified)
                (assoc :attestation/subject-hash "sha256:tampered"))
          shape-r (att/validate-attestation-shape a)]
      (is (:valid? shape-r)
          "subject-hash tamper is not detected by shape validation — it only checks non-nil"))))
