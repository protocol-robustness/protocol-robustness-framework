(ns resolver-sim.evidence.revocation-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.evidence.revocation :as rev]
            [resolver-sim.evidence.attestation :as att]))

;; ── build-revocation ────────────────────────────────────────────────────────

(deftest build-revocation-produces-required-fields
  (let [r (rev/build-revocation "attestation-uuid-1" :key-compromised)]
    (is (some? (:revocation-id r)))
    (is (= "attestation-uuid-1" (:revokes-attestation-id r)))
    (is (= :key-compromised (:reason r)))
    (is (some? (:timestamp r)))
    (is (= 1 (:schema-version r)))))

(deftest build-revocation-accepts-optional-fields
  (let [ts "2026-01-15T10:00:00Z"
        revoked-by {:type :auditor :id "audit-team"}
        r (rev/build-revocation "attestation-uuid-2" :key-compromised
                                {:revoked-by revoked-by :timestamp ts})]
    (is (= ts (:timestamp r)))
    (is (= revoked-by (:revoked-by r)))))

(deftest build-revocation-accepts-signing-fn
  (let [r (rev/build-revocation "attestation-uuid-3" :policy-violation
                                {:signing-fn (fn [_]
                                               {:algorithm :ed25519
                                                :public-key-id "audit-key-1"
                                                :signature-bytes "hexdeadbeef"})})]
    (is (some? (:signature r)))
    (is (= :ed25519 (get-in r [:signature :algorithm])))))

(deftest build-revocation-supports-all-reasons
  (doseq [reason [:key-compromised :policy-violation :superseded :attestor-retired :duplicate]]
    (let [r (rev/build-revocation "attestation-uuid" reason)]
      (is (= reason (:reason r))
          (str "Reason " (pr-str reason) " is supported")))))

;; ── register-revocation! / find-revocations / attestation-revoked? ─────────

(deftest register-and-find-revocation
  (rev/with-fresh-registry
    (let [r (rev/build-revocation "attestation-A" :key-compromised)]
      (rev/register-revocation! r)
      (let [found (rev/find-revocations "attestation-A")]
        (is (= 1 (count found)))
        (is (= (:revocation-id r) (:revocation-id (first found))))))))

(deftest find-revocations-returns-empty-vector-for-unknown
  (rev/with-fresh-registry
    (is (= [] (rev/find-revocations "never-revoked")))))

(deftest attestation-revoked-returns-true-after-registration
  (rev/with-fresh-registry
    (let [r (rev/build-revocation "attestation-B" :policy-violation)]
      (rev/register-revocation! r)
      (is (true? (rev/attestation-revoked? "attestation-B"))))))

(deftest attestation-revoked-returns-false-for-unrevoked
  (rev/with-fresh-registry
    (is (false? (rev/attestation-revoked? "attestation-C")))))

(deftest multiple-revocations-for-same-attestation
  (rev/with-fresh-registry
    (let [r1 (rev/build-revocation "attestation-D" :key-compromised)
          r2 (rev/build-revocation "attestation-D" :superseded)]
      (rev/register-revocation! r1)
      (rev/register-revocation! r2)
      (let [found (rev/find-revocations "attestation-D")]
        (is (= 2 (count found)))
        (is (true? (rev/attestation-revoked? "attestation-D")))))))

(deftest revocation-does-not-modify-original-attestation
  (rev/with-fresh-registry
    (let [attestation (att/build-attestation
                       {:type :ci-runner :id :ci-validation}
                       {:type :evidence-node :hash "sha256:abc"}
                       :verified)
          original-id (:attestation/id attestation)
          original-attestor (:attestation/attestor-id attestation)
          r (rev/build-revocation original-id :key-compromised)]
      (rev/register-revocation! r)
      ;; The original attestation must be untouched
      (is (= original-id (:attestation/id attestation)))
      (is (= original-attestor (:attestation/attestor-id attestation)))
      (is (= :verified (:attestation/claim-result attestation)))
      (is (some? (:attestation/signed-at attestation))))))

;; ── all-revocations ─────────────────────────────────────────────────────────

(deftest all-revocations-returns-flat-vector
  (rev/with-fresh-registry
    (let [r1 (rev/build-revocation "attestation-E" :key-compromised)
          r2 (rev/build-revocation "attestation-F" :policy-violation)]
      (rev/register-revocation! r1)
      (rev/register-revocation! r2)
      (let [all (rev/all-revocations)]
        (is (= 2 (count all)))))))

(deftest all-revocations-returns-empty-vector-when-empty
  (rev/with-fresh-registry
    (is (= [] (rev/all-revocations)))))

;; ── validate-revocation-shape ───────────────────────────────────────────────

(deftest validate-valid-revocation-passes
  (let [r (rev/build-revocation "attestation-uuid" :key-compromised)]
    (is (:valid? (rev/validate-revocation-shape r)))))

(deftest validate-detects-missing-revokes-attestation-id
  (let [r (rev/build-revocation "attestation-uuid" :key-compromised)]
    (is (false? (:valid? (rev/validate-revocation-shape (dissoc r :revokes-attestation-id)))))))

(deftest validate-detects-missing-reason
  (let [r (rev/build-revocation "attestation-uuid" :key-compromised)]
    (is (false? (:valid? (rev/validate-revocation-shape (dissoc r :reason)))))))

(deftest validate-detects-missing-timestamp
  (let [r (rev/build-revocation "attestation-uuid" :key-compromised)]
    (is (false? (:valid? (rev/validate-revocation-shape (dissoc r :timestamp)))))))

(deftest validate-detects-non-string-attestation-id
  (let [r (assoc (rev/build-revocation "test-uuid" :key-compromised)
                 :revokes-attestation-id :keyword-id)]
    (is (false? (:valid? (rev/validate-revocation-shape r))))))

;; ── Integration with verify-attestation ────────────────────────────────────

(defn- attestation->old-shape
  "Map new attestation shape to old shape for verify-attestation compat."
  [a]
  (let [subject-kind (:attestation/subject-kind a)]
    (-> a
        (assoc :attestation-id (:attestation/id a))
        (assoc :attestor {:type :ci-runner :id (:attestation/attestor-id a)})
        (assoc :subject (if (= :claim subject-kind)
                          {:type :claim :claim-id (:attestation/subject-hash a)}
                          {:type subject-kind :hash (:attestation/subject-hash a)}))
        (assoc :claim (:attestation/claim-result a))
        (assoc :timestamp (:attestation/signed-at a))
        (assoc :signature (:attestation/signature a))
        (dissoc :schema-version
                :attestation/id :attestation/hash
                :attestation/subject-hash :attestation/subject-kind
                :attestation/claim-id :attestation/claim-result
                :attestation/attestor-id :attestation/signing-key-id
                :attestation/signed-at :attestation/provenance
                :attestation/signature :attestation/metadata))))

(deftest verify-attestation-with-revocation-resolver
  (rev/with-fresh-registry
    (let [attestation (attestation->old-shape
                       (att/build-attestation
                        {:type :ci-runner :id :ci-validation}
                        {:type :evidence-node :hash "sha256:abc"}
                        :verified))
          rev-id (:attestation-id attestation)]
      ;; Register a revocation
      (rev/register-revocation!
       (rev/build-revocation rev-id :key-compromised))
      ;; Verify with revocation-resolver wired to the revocation registry
      (let [{:keys [valid? checks]} (att/verify-attestation attestation
                                                            {:revocation-resolver rev/attestation-revoked?})
            rev-check (first (filter #(= :revocation-status (:check %)) checks))]
        (is valid? "Revocation is informational — verification still passes")
        (is (true? (:pass? rev-check)) "Revocation check reports revoked")
        (is (true? (get-in rev-check [:detail :revoked?])))))))

(deftest verify-attestation-revocation-does-not-block-other-checks
  (rev/with-fresh-registry
    (let [attestation (attestation->old-shape
                       (att/build-attestation
                        {:type :ci-runner :id :ci-validation}
                        {:type :evidence-node :hash "sha256:abc"}
                        :verified))
          rev-id (:attestation-id attestation)]
      (rev/register-revocation!
       (rev/build-revocation rev-id :key-compromised))
      (let [{:keys [checks]} (att/verify-attestation attestation
                                                     {:revocation-resolver rev/attestation-revoked?})
            non-revocation (remove #(= :revocation-status (:check %)) checks)]
        ;; All non-revocation checks must pass
        (is (every? (fn [c] (or (true? (:pass? c))
                                (= :unsigned (:pass? c))
                                (= :unavailable (:pass? c))))
                    non-revocation)
            (pr-str non-revocation))))))
