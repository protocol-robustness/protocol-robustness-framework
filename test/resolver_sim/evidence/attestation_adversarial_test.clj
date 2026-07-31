(ns resolver-sim.evidence.attestation-adversarial-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.evidence.attestation :as att]
            [resolver-sim.evidence.attestation-registry :as ar]))

(defn- valid-attestor [] {:type :ci-runner :id :ci-validation})
(defn- valid-subject [] {:type :evidence-node :hash "sha256:abcdef1234567890"})
(defn- sample-signing-fn [body] {:algorithm :ed25519 :public-key-id "key-001" :signature-bytes "deadbeef"})

;; ── Builder adversarial: unsupported / bad inputs that the builder accepts ──

(deftest build-accepts-any-attestor-type
  (let [result (att/build-attestation {:type :made-up-type :id :unknown} (valid-subject) :verified)]
    (is (= :unknown (:attestation/attestor-id result)) "attestor-id is set regardless of type")))

(deftest build-subject-with-nil-type-produces-nil-kind
  (let [result (att/build-attestation (valid-attestor) {:hash "sha256:abc"} :verified)]
    (is (nil? (:attestation/subject-kind result)))
    (is (= "sha256:abc" (:attestation/subject-hash result)))))

(deftest build-subject-with-nil-hash-produces-nil-hash
  (let [result (att/build-attestation (valid-attestor) {:type :evidence-node} :verified)]
    (is (= :evidence-node (:attestation/subject-kind result)))
    (is (nil? (:attestation/subject-hash result)))))

(deftest build-signing-fn-returning-nil-produces-nil-signature
  (let [result (att/build-attestation (valid-attestor) (valid-subject) :verified
                                      {:signing-key-id "k1" :signing-fn (fn [_] nil)})]
    (is (nil? (:attestation/signature result)))))

(deftest build-signing-fn-returning-string-stored-as-signature
  (let [result (att/build-attestation (valid-attestor) (valid-subject) :verified
                                      {:signing-key-id "k1" :signing-fn (fn [_] "badsig")})]
    (is (= "badsig" (:attestation/signature result)))))

(deftest build-signing-fn-throwing-rejected
  (is (thrown? RuntimeException
               (att/build-attestation (valid-attestor) (valid-subject) :verified
                                      {:signing-key-id "k1" :signing-fn (fn [_] (throw (RuntimeException. "boom")))}))))

(deftest build-nil-attestor-id-accepted
  (let [result (att/build-attestation {:type :ci-runner :id nil} (valid-subject) :verified)]
    (is (nil? (:attestation/attestor-id result)))))

(deftest build-unsupported-subject-type-accepted
  (let [result (att/build-attestation (valid-attestor) {:type :widget :hash "sha256:x"} :verified)]
    (is (= :widget (:attestation/subject-kind result)))))

;; ── Shape validation adversarial: what validate-attestation-shape catches ──

(deftest validate-rejects-missing-schema-version
  (let [a (dissoc (att/build-attestation (valid-attestor) (valid-subject) :verified)
                  :schema-version)
        r (att/validate-attestation-shape a)]
    (is (false? (:valid? r)))
    (is (some #(re-find #"schema-version" (:message %)) (:errors r)))))

(deftest validate-rejects-missing-id
  (let [a (dissoc (att/build-attestation (valid-attestor) (valid-subject) :verified)
                  :attestation/id)
        r (att/validate-attestation-shape a)]
    (is (false? (:valid? r)))
    (is (some #(= :attestation/missing-field (:type %)) (:errors r)))))

(deftest validate-rejects-missing-subject-hash
  (let [a (dissoc (att/build-attestation (valid-attestor) (valid-subject) :verified)
                  :attestation/subject-hash)
        r (att/validate-attestation-shape a)]
    (is (false? (:valid? r)))
    (is (some #(re-find #"subject-hash" (:message %)) (:errors r)))))

(deftest validate-rejects-unsupported-subject-kind
  (let [a (assoc (att/build-attestation (valid-attestor) (valid-subject) :verified)
                 :attestation/subject-kind :unsupported)
        r (att/validate-attestation-shape a)]
    (is (false? (:valid? r)))
    (is (some #(= :attestation/invalid-subject-type (:type %)) (:errors r)))))

(deftest validate-rejects-nil-subject-kind
  (let [a (assoc (att/build-attestation (valid-attestor) {:hash "sha256:abc"} :verified)
                 :attestation/subject-kind nil)
        r (att/validate-attestation-shape a)]
    (is (false? (:valid? r)))))

(deftest validate-detects-multiple-missing-fields
  (let [a (-> (att/build-attestation (valid-attestor) (valid-subject) :verified)
              (dissoc :attestation/id :attestation/hash :attestation/subject-kind))
        r (att/validate-attestation-shape a)]
    (is (false? (:valid? r)))
    (is (>= (count (:errors r)) 3))))

(deftest validate-detects-missing-attestor-id-new-shape
  (let [a (dissoc (att/build-attestation (valid-attestor) (valid-subject) :verified)
                  :attestation/attestor-id)
        r (att/validate-attestation-shape a)]
    (is (false? (:valid? r)))
    (is (some #(= :attestation/invalid-attestor (:type %)) (:errors r)))))

(deftest validate-detects-malformed-signature-string
  (let [a (assoc (att/build-attestation (valid-attestor) (valid-subject) :verified)
                 :attestation/signature "not-a-map")
        r (att/validate-attestation-shape a)]
    (is (false? (:valid? r)))
    (is (some #(= :attestation/malformed-signature (:type %)) (:errors r)))))

(deftest validate-detects-malformed-signature-missing-algorithm
  (let [a (assoc (att/build-attestation (valid-attestor) (valid-subject) :verified)
                 :attestation/signature {:public-key-id "k1" :signature-bytes "x"})
        r (att/validate-attestation-shape a)]
    (is (false? (:valid? r)))
    (is (some #(= :attestation/malformed-signature (:type %)) (:errors r)))))

;; ── Shape validation gaps: things the builder does not structurally check ──

(deftest validate-rejects-bad-schema-version
  (let [a (assoc (att/build-attestation (valid-attestor) (valid-subject) :verified)
                 :schema-version "attestation.v999")
        r (att/validate-attestation-shape a)]
    (is (false? (:valid? r)))
    (is (some #(= :attestation/unsupported-schema-version (:type %)) (:errors r)))))

(deftest validate-rejects-id-hash-mismatch
  (let [a (assoc (att/build-attestation (valid-attestor) (valid-subject) :verified)
                 :attestation/id "tampered-id")
        r (att/validate-attestation-shape a)]
    (is (false? (:valid? r)))
    (is (some #(= :attestation/id-hash-mismatch (:type %)) (:errors r)))))

(deftest tamper-subject-hash-shape-valid-but-integrity-rejected
  (let [a (-> (att/build-attestation (valid-attestor) (valid-subject) :verified)
              (assoc :attestation/subject-hash "sha256:tampered"))
        shape-r (att/validate-attestation-shape a)]
    (is (:valid? shape-r)
        "shape validation accepts any non-nil subject-hash string; integrity/hash verification is the layer that detects tampering")))

;; ── Registry adversarial ────────────────────────────────────────────────────

(deftest register-attestation-with-nil-id-rejected
  (ar/with-fresh-registry
    (let [a (-> (att/build-attestation (valid-attestor) (valid-subject) :verified)
                (dissoc :attestation/id))]
      (is (thrown? Exception (ar/register-attestation! a))
          "registering without :attestation/id must be rejected"))))

(deftest register-attestation-idempotent-with-identical-content
  (ar/with-fresh-registry
    (let [a1 (att/build-attestation (valid-attestor) (valid-subject) :verified
                                    {:signed-at "2025-01-01T00:00:00Z"})
          id (:attestation/id a1)]
      (ar/register-attestation! a1)
      (ar/register-attestation! a1)
      (is (= 1 (count (ar/all-attestations))))
      (is (= a1 (ar/find-attestation id))))))

;; ── Verification adversarial ────────────────────────────────────────────────

(deftest verify-unknown-attestor-rejected
  (let [attestation (att/build-attestation {:type :ci-runner :id :does-not-exist}
                                           (valid-subject) :verified)
        a (assoc attestation :attestor {:type :ci-runner :id :does-not-exist}
                 :claim :verified :timestamp "2025-01-01T00:00:00Z"
                 :attestation-id (:attestation/id attestation))]
    (is (= false (:valid? (att/verify-attestation a))))))

(deftest verify-signature-check-reports-unsigned
  (let [attestation (att/build-attestation (valid-attestor) (valid-subject) :verified)
        a (assoc attestation :attestor {:type :ci-runner :id :ci-validation}
                 :claim :verified :timestamp "2025-01-01T00:00:00Z"
                 :attestation-id (:attestation/id attestation))]
    (let [r (att/verify-attestation a)
          sig-check (first (filter #(= :signature-verified (:check %)) (:checks r)))]
      (is (= :unsigned (:pass? sig-check))))))

(deftest verify-verify-fn-returning-nil-treated-as-false
  (let [attestation (att/build-attestation (valid-attestor) (valid-subject) :verified
                                           {:signing-key-id "k1"
                                            :signing-fn sample-signing-fn})
        a (assoc attestation :attestor {:type :ci-runner :id :ci-validation}
                 :claim :verified :timestamp "2025-01-01T00:00:00Z"
                 :attestation-id (:attestation/id attestation))
        r (att/verify-attestation a {:verify-fn (fn [_ _] nil)})
        sig-check (first (filter #(= :signature-verified (:check %)) (:checks r)))]
    (is (false? (:pass? sig-check)))))

(deftest verify-subject-resolver-returning-false-reports-not-found
  (let [attestation (att/build-attestation (valid-attestor) (valid-subject) :verified)
        a (assoc attestation :attestor {:type :ci-runner :id :ci-validation}
                 :claim :verified :timestamp "2025-01-01T00:00:00Z"
                 :attestation-id (:attestation/id attestation))
        r (att/verify-attestation a {:subject-resolver (fn [_] false)})
        subj-check (first (filter #(= :subject-exists (:check %)) (:checks r)))]
    (is (false? (:pass? subj-check)))))

(deftest verify-revocation-resolver-returning-nil-shows-error
  (let [attestation (att/build-attestation (valid-attestor) (valid-subject) :verified)
        a (assoc attestation :attestor {:type :ci-runner :id :ci-validation}
                 :claim :verified :timestamp "2025-01-01T00:00:00Z"
                 :attestation-id (:attestation/id attestation))
        r (att/verify-attestation a {:revocation-resolver (fn [_] nil)})
        rev-check (first (filter #(= :revocation-status (:check %)) (:checks r)))]
    (is (= :error (:pass? rev-check))
        "nil from revocation-resolver is treated as an error, not coerced to false")))

(deftest verify-old-shape-missing-attestor-id-rejected
  (let [a {:attestation-id "test" :claim :verified :timestamp "2025-01-01T00:00:00Z"}
        r (att/verify-attestation a)]
    (is (false? (:valid? r)))
    (is (some #(and (= :attestor-exists (:check %)) (false? (:pass? %))) (:checks r)))))

(deftest verify-missing-signed-at-new-shape-valid
  (let [a (dissoc (att/build-attestation (valid-attestor) (valid-subject) :verified)
                  :attestation/signed-at)]
    (is (nil? (:attestation/signed-at a)) "signed-at can be nil from builder")))

;; ── Claim-result-adversarial ────────────────────────────────────────────────

(deftest build-claim-missing-result-hash-produces-nil-subject
  (let [claim-result {:claim-id :conservation :holds? true :status :pass}
        a (att/build-claim-result-attestation (valid-attestor) claim-result
                                              {:signed-at "2025-01-01T00:00:00Z"})]
    (is (nil? (:attestation/subject-hash a)))))

(deftest build-claim-nil-claim-id-produces-nil-claim-id
  (let [claim-result {:claim-result-hash "sha256:abc" :holds? true :status :pass}
        a (att/build-claim-result-attestation (valid-attestor) claim-result
                                              {:signed-at "2025-01-01T00:00:00Z"})]
    (is (= "sha256:abc" (:attestation/subject-hash a)))
    (is (nil? (:attestation/claim-id a)))))

(deftest build-claim-nil-attestor-id-sets-nil-attestor-id
  (let [claim-result {:claim-id :conservation :claim-result-hash "sha256:abc"}
        a (att/build-claim-result-attestation {:type :ci-runner :id nil} claim-result
                                              {:signed-at "2025-01-01T00:00:00Z"})]
    (is (nil? (:attestation/attestor-id a)))))
