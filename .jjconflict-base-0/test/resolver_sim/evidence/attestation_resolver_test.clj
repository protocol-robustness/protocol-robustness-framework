(ns resolver-sim.evidence.attestation-resolver-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.evidence.attestation :as att]
            [resolver-sim.evidence.attestation-registry :as ar]
            [resolver-sim.evidence.attestation-resolver :as ars]))

;; ── Helpers ──────────────────────────────────────────────────────────────────

(defn- attestor [] {:type :ci-runner :id :ci-validation})
(defn- subject [] {:type :evidence-node :hash "sha256:abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"})

(defn- build-a
  "Build a well-formed attestation with optional signature."
  [& {:keys [signed-at claim claim-id signing-fn]
      :or {signed-at "2025-01-01T00:00:00Z" claim :verified}}]
  (att/build-attestation (attestor) (subject) claim
                         (cond-> {:signed-at signed-at}
                           claim-id (assoc :claim-id claim-id)
                           signing-fn (assoc :signing-fn signing-fn))))

(defn- ref-of
  "Build a typed reference from an attestation."
  [attestation]
  (str "attestation:sha256:" (:attestation/id attestation)))

;; ── Parse tests ──────────────────────────────────────────────────────────────

(deftest parse-valid-typed-reference
  (let [result (ars/parse-reference "attestation:sha256:abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789")]
    (is (= :attestation (:type result)))
    (is (= :sha256 (:algorithm result)))
    (is (= "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789" (:hash result)))))

(deftest parse-rejects-bare-attestation-id
  (is (nil? (ars/parse-reference "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"))))

(deftest parse-rejects-short-hash
  (is (nil? (ars/parse-reference "attestation:sha256:aaaa"))))

(deftest parse-rejects-nil
  (is (nil? (ars/parse-reference nil))))

(deftest parse-rejects-empty-string
  (is (nil? (ars/parse-reference ""))))

(deftest parse-rejects-wrong-prefix
  (is (nil? (ars/parse-reference "sha256:abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"))))

(deftest parse-rejects-unknown-algorithm
  (is (nil? (ars/parse-reference "attestation:sha512:abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"))))

;; ── Resolve errors ───────────────────────────────────────────────────────────

(deftest resolve-rejects-unparseable-reference
  (let [result (ars/resolve-attestation "bare-attestation-id")]
    (is (not (:valid? result)))
    (is (= :unparseable-reference (:error result)))))

(deftest resolve-rejects-unsupported-algorithm
  (ar/with-fresh-registry
    (let [result (ars/resolve-attestation "attestation:md5:abcdef0123456789abcdef0123456789")]
      (is (not (:valid? result)))
      (is (= :unparseable-reference (:error result))))))

(deftest resolve-missing-attestation-produces-missing
  (ar/with-fresh-registry
    (let [result (ars/resolve-attestation "attestation:sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff")]
      (is (not (:valid? result)))
      (is (= :missing (:error result))))))

(deftest resolve-missing-not-generic-failure
  (ar/with-fresh-registry
    (let [result (ars/resolve-attestation "attestation:sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff")]
      (is (= :missing (:error result)))
      (is (not= :generic-failure (:error result)))
      (is (string? (:detail result))))))

;; ── Resolve success ──────────────────────────────────────────────────────────

(deftest resolve-valid-attestation
  (ar/with-fresh-registry
    (let [a (build-a)
          _ (ar/register-attestation! a)
          result (ars/resolve-attestation (ref-of a))]
      (is (:valid? result))
      (is (= (:attestation/id a) (get-in result [:attestation :attestation/id])))
      (is (true? (get-in result [:checks :hash-ok?])))
      (is (true? (get-in result [:checks :type-ok?]))))))

(deftest resolved-attestation-hash-matches-reference
  (ar/with-fresh-registry
    (let [a (build-a)
          _ (ar/register-attestation! a)
          ref (ref-of a)
          result (ars/resolve-attestation ref)]
      (is (:valid? result))
      (is (= (:attestation/hash a) (get-in result [:checks :hash]))))))

(deftest resolved-artifact-has-expected-type
  (ar/with-fresh-registry
    (let [a (build-a)
          _ (ar/register-attestation! a)
          result (ars/resolve-attestation (ref-of a))]
      (is (:valid? result))
      (is (= "attestation.v1" (get-in result [:attestation :schema-version])))
      (is (get-in result [:attestation :attestation/id]))
      (is (get-in result [:attestation :attestation/attestor-id])))))

;; ── Hash mismatch ────────────────────────────────────────────────────────────

(deftest resolve-hash-mismatch-produces-hash-mismatch
  (ar/with-fresh-registry
    (let [a (build-a)
          ;; Manually corrupt the hash so it doesn't match the reference
          tampered (assoc a :attestation/hash "0000000000000000000000000000000000000000000000000000000000000000")
          ref (str "attestation:sha256:" (:attestation/id a))]
      ;; Register under the original (uncorrupted) id so find-attestation works
      (ar/register-attestation! tampered)
      (let [result (ars/resolve-attestation ref)]
        (is (not (:valid? result)))
        (is (= :hash-mismatch (:error result)))))))

;; ── Signature verification ───────────────────────────────────────────────────

(deftest resolve-checks-signature-when-verify-fn-provided
  (ar/with-fresh-registry
    (let [called (atom false)
          a (build-a :signing-fn (fn [_] "fake-signature"))
          _ (ar/register-attestation! a)
          result (ars/resolve-attestation (ref-of a)
                                          {:verify-fn (fn [att]
                                                        (reset! called true)
                                                        (some? (:attestation/signature att)))})]
      (is (:valid? result))
      (is (true? (get-in result [:checks :signature-valid?]))))))

(deftest resolve-reports-signature-failure-when-verify-fn-rejects
  (ar/with-fresh-registry
    (let [a (build-a :signing-fn (fn [_] "fake-signature"))
          _ (ar/register-attestation! a)
          result (ars/resolve-attestation (ref-of a)
                                          {:verify-fn (fn [_] false)})]
      (is (:valid? result)
          "Signature failure alone should not invalidate structural resolution")
      (is (false? (get-in result [:checks :signature-valid?]))))))

;; ── Resolve! (throwing) ─────────────────────────────────────────────────────

(deftest resolve-bang-throws-on-missing
  (ar/with-fresh-registry
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"resolution failed"
                          (ars/resolve-attestation! "attestation:sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff")))))

(deftest resolve-bang-returns-attestation-on-success
  (ar/with-fresh-registry
    (let [a (build-a)
          _ (ar/register-attestation! a)
          result (ars/resolve-attestation! (ref-of a))]
      (is (map? result))
      (is (= (:attestation/id a) (:attestation/id result))))))
