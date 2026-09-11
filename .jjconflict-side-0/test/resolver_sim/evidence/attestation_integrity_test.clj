(ns resolver-sim.evidence.attestation-integrity-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.evidence.attestation :as att]
            [resolver-sim.evidence.attestation-integrity :as ai]
            [resolver-sim.evidence.attestation-registry :as ar]))

(defn- attestor [] {:type :ci-runner :id :ci-validation})
(defn- subject [] {:type :evidence-node :hash "sha256:abc"})

(defn- build-a
  [& {:keys [signed-at claim claim-id signing-key-id]
      :or {signed-at "2025-01-01T00:00:00Z" claim :verified}}]
  (att/build-attestation (attestor) (subject) claim
                         (cond-> {:signed-at signed-at}
                           claim-id (assoc :claim-id claim-id)
                           signing-key-id (assoc :signing-key-id signing-key-id)
                           signing-key-id (assoc :signing-fn (fn [_]
                                                               {:algorithm :ed25519
                                                                :public-key-id signing-key-id
                                                                :signature-bytes "deadbeef"})))))

;; ── hash-valid? ──────────────────────────────────────────────────────────────

(deftest hash-valid-returns-true-for-valid-attestation
  (let [a (build-a)]
    (is (true? (ai/hash-valid? a)))))

(deftest hash-valid-returns-false-for-tampered-attestation
  (let [a (assoc (build-a) :attestation/claim-result :tampered)]
    (is (false? (ai/hash-valid? a)))))

(deftest hash-valid-handles-nil-hash
  (let [a (dissoc (build-a) :attestation/hash)]
    (is (nil? (ai/hash-valid? a)))))

(deftest hash-valid-deterministic
  (let [a (build-a)]
    (is (= (ai/hash-valid? a) (ai/hash-valid? a)))))

;; ── verify-attestation-integrity ─────────────────────────────────────────────

(deftest integrity-passes-for-valid-attestation
  (let [result (ai/verify-attestation-integrity (build-a))]
    (is (:valid? result))))

(deftest integrity-detects-missing-id
  (let [a (dissoc (build-a) :attestation/id)
        result (ai/verify-attestation-integrity a)]
    (is (false? (:valid? result)))
    (is (some #(re-find #"attestation/id" %) (:errors result)))))

(deftest integrity-detects-missing-hash
  (let [a (dissoc (build-a) :attestation/hash)
        result (ai/verify-attestation-integrity a)]
    (is (false? (:valid? result)))
    (is (some? (:errors result)))))

(deftest integrity-detects-missing-subject-hash
  (let [a (dissoc (build-a) :attestation/subject-hash)
        result (ai/verify-attestation-integrity a)]
    (is (false? (:valid? result)))
    (is (some #(re-find #"subject-hash" %) (:errors result)))))

(deftest integrity-detects-missing-subject-kind
  (let [a (dissoc (build-a) :attestation/subject-kind)
        result (ai/verify-attestation-integrity a)]
    (is (false? (:valid? result)))
    (is (some #(re-find #"subject-kind" %) (:errors result)))))

(deftest integrity-detects-missing-claim-result
  (let [a (dissoc (build-a) :attestation/claim-result)
        result (ai/verify-attestation-integrity a)]
    (is (false? (:valid? result)))
    (is (some #(re-find #"claim-result" %) (:errors result)))))

(deftest integrity-detects-missing-attestor-id
  (let [a (dissoc (build-a) :attestation/attestor-id)
        result (ai/verify-attestation-integrity a)]
    (is (false? (:valid? result)))
    (is (some #(re-find #"attestor-id" %) (:errors result)))))

(deftest integrity-detects-missing-signed-at
  (let [a (dissoc (build-a) :attestation/signed-at)
        result (ai/verify-attestation-integrity a)]
    (is (false? (:valid? result)))
    (is (some #(re-find #"signed-at" %) (:errors result)))))

(deftest integrity-detects-id-hash-mismatch
  (let [a (assoc (build-a) :attestation/id "tampered-id")
        result (ai/verify-attestation-integrity a)]
    (is (false? (:valid? result)))
    (is (some #(re-find #"must equal" %) (:errors result)))))

(deftest integrity-detects-invalid-subject-kind
  (let [a (assoc (build-a) :attestation/subject-kind :invalid-kind)
        result (ai/verify-attestation-integrity a)]
    (is (false? (:valid? result)))
    (is (some #(re-find #"Invalid.*subject-kind" %) (:errors result)))))

(deftest integrity-detects-invalid-claim-result
  (let [a (assoc (build-a) :attestation/claim-result :invalid-claim)
        result (ai/verify-attestation-integrity a)]
    (is (false? (:valid? result)))
    (is (some #(re-find #"Invalid.*claim-result" %) (:errors result)))))

(deftest integrity-detects-tampered-claim-result
  (let [a (assoc (build-a) :attestation/claim-result :approved)
        result (ai/verify-attestation-integrity a)]
    ;; :approved is valid claim-result, but hash won't match because the
    ;; original was built with :verified. So this should fail on hash.
    (is (false? (:valid? result)))
    (is (some #(re-find #"Hash mismatch" %) (:errors result)))))

(deftest integrity-accumulates-multiple-errors
  (let [a (-> (build-a)
              (dissoc :attestation/id)
              (dissoc :attestation/hash)
              (dissoc :attestation/subject-kind))
        result (ai/verify-attestation-integrity a)]
    (is (false? (:valid? result)))
    (is (>= (count (:errors result)) 2))))

;; ── verify-attestation-registry ──────────────────────────────────────────────

(deftest registry-check-all-valid
  (ar/with-fresh-registry
    (ar/register-attestation! (build-a :signed-at "2025-01-01T00:00:00Z"))
    (ar/register-attestation! (build-a :signed-at "2025-01-02T00:00:00Z"))
    (let [result (ai/verify-attestation-registry (ar/all-attestations))]
      (is (= 2 (:total-checked result)))
      (is (= 2 (:valid-count result)))
      (is (= 0 (:invalid-count result))))))

(deftest registry-check-detects-invalid
  (ar/with-fresh-registry
    (ar/register-attestation! (build-a :signed-at "2025-01-01T00:00:00Z"))
    (let [a (assoc (build-a :signed-at "2025-01-02T00:00:00Z")
                   :attestation/claim-result :tampered)]
      (ar/register-attestation! a)
      (let [result (ai/verify-attestation-registry (ar/all-attestations))]
        (is (= 2 (:total-checked result)))
        (is (= 1 (:valid-count result)))
        (is (= 1 (:invalid-count result)))))))

(deftest registry-check-empty
  (let [result (ai/verify-attestation-registry [])]
    (is (= 0 (:total-checked result)))
    (is (= 0 (:valid-count result)))
    (is (= 0 (:invalid-count result)))))

;; ── integrity-report ─────────────────────────────────────────────────────────

(deftest report-includes-generated-at
  (let [r (ai/integrity-report [(build-a)])]
    (is (string? (:generated-at r)))))

(deftest report-includes-per-attestation
  (let [r (ai/integrity-report [(build-a) (build-a)])]
    (is (= 2 (:total-attestations r)))
    (is (= 2 (count (:per-attestation r))))))

(deftest report-tracks-signed-unsigned
  (let [a1 (build-a :signed-at "2025-01-01T00:00:00Z")
        a2 (build-a :signed-at "2025-01-02T00:00:00Z" :signing-key-id "key-001")
        r (ai/integrity-report [a1 a2])]
    (is (= 1 (:signed-count r)))
    (is (= 1 (:unsigned-count r)))))

(deftest report-tracks-hash-validation
  (let [a1 (build-a)
        a2 (assoc (build-a :signed-at "2025-01-02T00:00:00Z")
                  :attestation/claim-result :tampered)
        r (ai/integrity-report [a1 a2])]
    (is (= 1 (:hash-verified-count r)))
    (is (= 1 (:hash-failed-count r)))
    (is (= 1 (:valid-count r)))
    (is (= 1 (:invalid-count r)))))

;; ── Edge: attestation with metadata ─────────────────────────────────────────

(deftest integrity-passes-with-metadata
  (let [a (build-a :metadata {:env "test"})]
    (is (true? (:valid? (ai/verify-attestation-integrity a))))))

;; ── Edge: signed attestation ─────────────────────────────────────────────────

(deftest integrity-passes-with-signature
  (let [a (build-a :signing-key-id "key-001")]
    (is (true? (:valid? (ai/verify-attestation-integrity a))))))
