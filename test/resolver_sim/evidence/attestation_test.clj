(ns resolver-sim.evidence.attestation-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.evidence.attestation :as att]
            [resolver-sim.evidence.attestation-registry :as ar]
            [resolver-sim.hash.canonical :as hc]))

(defn- valid-attestor
  []
  {:type :ci-runner :id :ci-validation})

(defn- valid-subject
  []
  {:type :evidence-node :hash "sha256:abcdef1234567890"})

(defn- sample-signing-fn
  [body]
  {:algorithm :ed25519
   :public-key-id "key-001"
   :signature-bytes "deadbeef"})

;; ── build-attestation ───────────────────────────────────────────────────────

(deftest build-attestation-produces-required-fields
  (let [result (att/build-attestation (valid-attestor) (valid-subject) :verified)]
    (is (= "attestation.v1" (:schema-version result)))
    (is (some? (:attestation/id result)))
    (is (= (:attestation/id result) (:attestation/hash result))
        "id must equal hash (content-derived)")
    (is (= "sha256:abcdef1234567890" (:attestation/subject-hash result)))
    (is (= :evidence-node (:attestation/subject-kind result)))
    (is (= :ci-validation (:attestation/attestor-id result)))
    (is (= :verified (:attestation/claim-result result)))
    (is (nil? (:attestation/claim-id result)))
    (is (nil? (:attestation/signing-key-id result)))
    (is (some? (:attestation/signed-at result)))
    (is (nil? (:attestation/signature result)))
    (is (nil? (:attestation/metadata result)))))

(deftest build-attestation-supports-claim-id
  (let [result (att/build-attestation (valid-attestor) (valid-subject) :verified
                                      {:claim-id :claim/reproduced})]
    (is (= :claim/reproduced (:attestation/claim-id result)))))

(deftest build-attestation-supports-provenance
  (let [provenance {:run-id "run-1" :scenario-id "S01"}
        result (att/build-attestation (valid-attestor) (valid-subject) :verified
                                      {:provenance provenance})]
    (is (= provenance (:attestation/provenance result)))))

(deftest build-attestation-supports-signing-key-id
  (let [result (att/build-attestation (valid-attestor) (valid-subject) :verified
                                      {:signing-key-id "key-001"
                                       :signing-fn sample-signing-fn})]
    (is (= "key-001" (:attestation/signing-key-id result)))
    (is (some? (:attestation/signature result)))))

(deftest build-attestation-accepts-override-signed-at
  (let [ts "2025-01-15T10:00:00Z"
        result (att/build-attestation (valid-attestor) (valid-subject) :approved
                                      {:signed-at ts})]
    (is (= ts (:attestation/signed-at result)))))

(deftest build-attestation-attaches-signature-when-signing-fn-provided
  (let [result (att/build-attestation (valid-attestor) (valid-subject) :reproduced
                                      {:signing-key-id "key-001"
                                       :signing-fn sample-signing-fn})]
    (is (some? (:attestation/signature result)))
    (is (= :ed25519 (get-in result [:attestation/signature :algorithm])))
    (is (= "key-001" (get-in result [:attestation/signature :public-key-id])))
    (is (= "deadbeef" (get-in result [:attestation/signature :signature-bytes])))))

(deftest build-attestation-no-signature-when-signing-fn-absent
  (let [result (att/build-attestation (valid-attestor) (valid-subject) :verified)]
    (is (nil? (:attestation/signature result)))))

(deftest build-attestation-attaches-metadata
  (let [meta {:source "test-suite" :environment "ci"}
        result (att/build-attestation (valid-attestor) (valid-subject) :verified
                                      {:metadata meta})]
    (is (= meta (:attestation/metadata result)))))

(deftest build-attestation-signature-does-not-include-itself-or-metadata
  (let [called-with (atom nil)
        signing-fn (fn [data] (reset! called-with data) {:algorithm :ed25519
                                                         :public-key-id "key-001"
                                                         :signature-bytes "deadbeef"})
        result (att/build-attestation (valid-attestor) (valid-subject) :verified
                                      {:signing-key-id "key-001"
                                       :signing-fn signing-fn
                                       :metadata {:env "test"}})]
    (is (= :attestation-record (:intent @called-with))
        "signing-fn must receive the canonical projection with intent")
    (is (map? (:artifact @called-with))
        "signing-fn must receive the canonical projection with artifact")
    (is (nil? (get-in @called-with [:artifact :attestation/signature]))
        "signing-fn must not receive :attestation/signature")
    (is (nil? (get-in @called-with [:artifact :attestation/metadata]))
        "signing-fn must not receive :attestation/metadata")
    (is (nil? (get-in @called-with [:artifact :attestation/id]))
        "signing-fn must not receive :attestation/id")
    (is (nil? (get-in @called-with [:artifact :attestation/hash]))
        "signing-fn must not receive :attestation/hash")
    (is (= "sha256:abcdef1234567890"
           (get-in @called-with [:artifact :attestation/subject-hash]))
        "signing-fn must receive the canonical body fields in the artifact")))

(deftest build-attestation-supports-all-claim-types
  (doseq [claim [:verified :reproduced :certified :approved :rejected]]
    (let [result (att/build-attestation (valid-attestor) (valid-subject) claim)]
      (is (= claim (:attestation/claim-result result))
          (str "Claim type " (pr-str claim) " is supported")))))

(deftest build-attestation-supports-claim-subject
  (let [subject {:type :claim :claim-id :accounting-consistency}
        result (att/build-attestation (valid-attestor) subject :verified)]
    (is (= :claim (:attestation/subject-kind result)))
    (is (= :accounting-consistency (:attestation/subject-hash result))
        "subject-hash captures :claim-id for claim-type subjects")))

;; ── Determinism ─────────────────────────────────────────────────────────────

(deftest build-attestation-deterministic-hash
  (testing "same inputs produce same attestation hash"
    (let [a (att/build-attestation (valid-attestor) (valid-subject) :verified
                                   {:signed-at "2025-01-01T00:00:00Z"
                                    :claim-id :claim/reproduced
                                    :signing-key-id "key-001"
                                    :provenance {:run-id "r1" :scenario-id "s1"}})
          b (att/build-attestation (valid-attestor) (valid-subject) :verified
                                   {:signed-at "2025-01-01T00:00:00Z"
                                    :claim-id :claim/reproduced
                                    :signing-key-id "key-001"
                                    :provenance {:run-id "r1" :scenario-id "s1"}})]
      (is (= (:attestation/id a) (:attestation/id b)))
      (is (= (:attestation/hash a) (:attestation/hash b))))))

(deftest build-attestation-different-inputs-different-hash
  (testing "different inputs produce different attestation hashes"
    (let [a (att/build-attestation (valid-attestor) (valid-subject) :verified
                                   {:signed-at "2025-01-01T00:00:00Z"})
          b (att/build-attestation (valid-attestor) (valid-subject) :certified
                                   {:signed-at "2025-01-01T00:00:00Z"})]
      (is (not= (:attestation/id a) (:attestation/id b))
          "different claim-result must produce different hash"))))

(deftest build-attestation-metadata-does-not-affect-hash
  (testing "metadata is excluded from hash — same hash with different metadata"
    (let [a (att/build-attestation (valid-attestor) (valid-subject) :verified
                                   {:signed-at "2025-01-01T00:00:00Z"
                                    :metadata {:env "test"}})
          b (att/build-attestation (valid-attestor) (valid-subject) :verified
                                   {:signed-at "2025-01-01T00:00:00Z"
                                    :metadata {:env "prod"}})]
      (is (= (:attestation/hash a) (:attestation/hash b))
          "metadata must not affect attestation hash"))))

(deftest build-attestation-self-hash-stripped
  (testing "attestation/hash and attestation/id are excluded from the hash computation"
    (let [result (att/build-attestation (valid-attestor) (valid-subject) :verified
                                        {:signed-at "2025-01-01T00:00:00Z"})
          hash (:attestation/hash result)
          id (:attestation/id result)]
      (is (some? hash))
      (is (= hash id))
      ;; Verify the hash is computed from the canonical projection (without self-hash)
      (let [body {:schema-version "attestation.v1"
                  :attestation/subject-hash "sha256:abcdef1234567890"
                  :attestation/subject-kind :evidence-node
                  :attestation/claim-id nil
                  :attestation/claim-result :verified
                  :attestation/attestor-id :ci-validation
                  :attestation/signed-at "2025-01-01T00:00:00Z"
                  :attestation/provenance nil}
            expected (hc/hash-with-intent {:hash/intent :attestation-record} body)]
        (is (= expected hash)
            "hash must equal hash-with-intent of canonical projection body")))))

(deftest build-attestation-intent-constraints-valid
  (testing "attestation-record intent constraints are satisfied by the canonical body"
    (let [result (att/build-attestation (valid-attestor) (valid-subject) :verified
                                        {:signed-at "2025-01-01T00:00:00Z"})
          hash (:attestation/hash result)]
      (is (string? hash))
      (is (= 64 (count hash))
          "sha256 hex strings are 64 characters"))))

;; ── signing-payload ────────────────────────────────────────────────────────

(deftest signing-payload-returns-projection-structure
  (let [a (att/build-attestation (valid-attestor) (valid-subject) :verified
                                 {:signed-at "2025-01-01T00:00:00Z"
                                  :claim-id :claim/reproduced
                                  :signing-key-id "key-001"
                                  :provenance {:run-id "r1"}})
        payload (att/signing-payload a)]
    (is (= :attestation-record (:intent payload)))
    (is (map? (:artifact payload)))))

(deftest signing-payload-excludes-self-hash-id-signature-metadata
  (let [a (att/build-attestation (valid-attestor) (valid-subject) :verified
                                 {:signed-at "2025-01-01T00:00:00Z"
                                  :signing-key-id "key-001"
                                  :signing-fn (fn [_] {:algorithm :ed25519
                                                       :public-key-id "key-001"
                                                       :signature-bytes "deadbeef"})
                                  :metadata {:env "test"}})
        payload (att/signing-payload a)]
    (is (nil? (get-in payload [:artifact :attestation/id])))
    (is (nil? (get-in payload [:artifact :attestation/hash])))
    (is (nil? (get-in payload [:artifact :attestation/signature])))
    (is (nil? (get-in payload [:artifact :attestation/metadata])))))

(deftest signing-payload-includes-all-canonical-body-keys
  (let [provenance {:run-id "run-1" :scenario-id "S01"}
        a (att/build-attestation (valid-attestor) (valid-subject) :verified
                                 {:signed-at "2025-01-01T00:00:00Z"
                                  :claim-id :claim/reproduced
                                  :signing-key-id "key-001"
                                  :provenance provenance})
        artifact (:artifact (att/signing-payload a))]
    (is (= "attestation.v1" (:schema-version artifact)))
    (is (= "sha256:abcdef1234567890" (:attestation/subject-hash artifact)))
    (is (= :evidence-node (:attestation/subject-kind artifact)))
    (is (= :claim/reproduced (:attestation/claim-id artifact)))
    (is (= :verified (:attestation/claim-result artifact)))
    (is (= :ci-validation (:attestation/attestor-id artifact)))
    (is (= "key-001" (:attestation/signing-key-id artifact)))
    (is (= "2025-01-01T00:00:00Z" (:attestation/signed-at artifact)))
    (is (= provenance (:attestation/provenance artifact)))))

(deftest signing-payload-deterministic
  (let [opts {:signed-at "2025-01-01T00:00:00Z"
              :claim-id :claim/reproduced
              :signing-key-id "key-001"
              :provenance {:run-id "r1" :scenario-id "s1"}}
        a (att/build-attestation (valid-attestor) (valid-subject) :verified opts)
        b (att/build-attestation (valid-attestor) (valid-subject) :verified opts)]
    (is (= (att/signing-payload a) (att/signing-payload b)))))

(deftest signing-payload-works-on-body-before-signing
  (let [body {:schema-version "attestation.v1"
              :attestation/subject-hash "sha256:abc"
              :attestation/subject-kind :evidence-node
              :attestation/claim-id nil
              :attestation/claim-result :verified
              :attestation/attestor-id :ci-validation
              :attestation/signed-at "2025-01-01T00:00:00Z"
              :attestation/provenance nil}
        payload (att/signing-payload body)]
    (is (= :attestation-record (:intent payload)))
    (is (= "sha256:abc" (get-in payload [:artifact :attestation/subject-hash])))))

(deftest signing-payload-round-trip-with-signing-fn
  (let [signed-payload (atom nil)
        signing-fn (fn [data] (reset! signed-payload data) {:algorithm :ed25519
                                                            :public-key-id "key-001"
                                                            :signature-bytes "deadbeef"})
        a (att/build-attestation (valid-attestor) (valid-subject) :verified
                                 {:signed-at "2025-01-01T00:00:00Z"
                                  :claim-id :claim/reproduced
                                  :signing-key-id "key-001"
                                  :signing-fn signing-fn})
        reconstructed (att/signing-payload a)]
    (is (= @signed-payload reconstructed)
        "signing-fn received payload must equal signing-payload of result")))

;; ── Claim integration — build-claim-result-attestation ─────────────────────

(deftest claim-result-attestation-references-claim-result-hash
  (let [claim-result {:claim-id :conservation
                      :claim-definition-hash "sha256:def"
                      :claim-result-hash "sha256:abc123"
                      :holds? true
                      :status :pass
                      :violations []}
        a (att/build-claim-result-attestation (valid-attestor) claim-result
                                              {:signed-at "2025-01-01T00:00:00Z"})]
    (is (= :claim (:attestation/subject-kind a)))
    (is (= "sha256:abc123" (:attestation/subject-hash a)))
    (is (= :conservation (:attestation/claim-id a)))))

(deftest claim-result-attestation-default-claim-is-verified
  (let [claim-result {:claim-id :non-negative
                      :claim-result-hash "sha256:xyz789"
                      :holds? true :status :pass}
        a (att/build-claim-result-attestation (valid-attestor) claim-result
                                              {:signed-at "2025-01-01T00:00:00Z"})]
    (is (= :verified (:attestation/claim-result a)))))

(deftest claim-result-attestation-supports-override-claim
  (let [claim-result {:claim-id :conservation
                      :claim-result-hash "sha256:abc"
                      :holds? true :status :pass}
        a (att/build-claim-result-attestation (valid-attestor) claim-result
                                              {:signed-at "2025-01-01T00:00:00Z"
                                               :claim :reproduced})]
    (is (= :reproduced (:attestation/claim-result a)))))

(deftest claim-result-attestation-supports-signing
  (let [claim-result {:claim-id :allocation-complete
                      :claim-result-hash "sha256:sig-test"
                      :holds? true :status :pass}
        a (att/build-claim-result-attestation (valid-attestor) claim-result
                                              {:signed-at "2025-01-01T00:00:00Z"
                                               :signing-key-id "key-001"
                                               :signing-fn (fn [_]
                                                             {:algorithm :ed25519
                                                              :public-key-id "key-001"
                                                              :signature-bytes "dead"})})]
    (is (some? (:attestation/signature a)))
    (is (= "dead" (get-in a [:attestation/signature :signature-bytes])))))

(deftest claim-result-attestation-hash-is-content-addressed
  (let [claim-result {:claim-id :conservation
                      :claim-result-hash "sha256:hash-test"
                      :holds? true :status :pass}
        a (att/build-claim-result-attestation (valid-attestor) claim-result
                                              {:signed-at "2025-01-01T00:00:00Z"})
        b (att/build-claim-result-attestation (valid-attestor) claim-result
                                              {:signed-at "2025-01-01T00:00:00Z"})]
    (is (= (:attestation/id a) (:attestation/id b)))
    (is (= (:attestation/id a) (:attestation/hash a)))))

(deftest claim-result-attestation-different-results-different-hash
  (let [result-a {:claim-id :conservation
                  :claim-result-hash "sha256:aaa"
                  :holds? true :status :pass}
        result-b {:claim-id :conservation
                  :claim-result-hash "sha256:bbb"
                  :holds? false :status :fail}
        a (att/build-claim-result-attestation (valid-attestor) result-a
                                              {:signed-at "2025-01-01T00:00:00Z"})
        b (att/build-claim-result-attestation (valid-attestor) result-b
                                              {:signed-at "2025-01-01T00:00:00Z"})]
    (is (not= (:attestation/id a) (:attestation/id b))
        "different claim result hashes must produce different attestation hashes")))

(deftest claim-result-attestation-register-and-find-by-claim-id
  (let [claim-result {:claim-id :allocation-complete
                      :claim-result-hash "sha256:reg-test"
                      :holds? true :status :pass}
        a (att/build-claim-result-attestation (valid-attestor) claim-result
                                              {:signed-at "2025-01-01T00:00:00Z"})]
    (ar/register-attestation! a)
    (let [found (ar/find-attestations-by-claim-id :allocation-complete)]
      (is (= 1 (count found)))
      (is (= (:attestation/id a) (:attestation/id (first found)))))))

(deftest claim-result-attestation-register-and-find-by-subject-hash
  (let [claim-result {:claim-id :non-negative
                      :claim-result-hash "sha256:subj-test"
                      :holds? true :status :pass}
        a (att/build-claim-result-attestation (valid-attestor) claim-result
                                              {:signed-at "2025-01-01T00:00:00Z"})]
    (ar/register-attestation! a)
    (let [found (ar/find-attestations-by-subject "sha256:subj-test")]
      (is (= 1 (count found)))
      (is (= :non-negative (:attestation/claim-id (first found)))))))

(deftest claim-result-attestation-full-flow
  (let [attestor {:type :ci-runner :id :ci-validation}
        claim-result {:claim-id :conservation
                      :claim-definition-hash "sha256:def-hash"
                      :claim-result-hash "sha256:flow-test"
                      :holds? true
                      :status :pass
                      :violations []}
        a (att/build-claim-result-attestation
           attestor claim-result
           {:signed-at "2025-01-01T00:00:00Z"
            :claim :reproduced
            :signing-key-id "key-001"
            :signing-fn (fn [_]
                          {:algorithm :ed25519
                           :public-key-id "key-001"
                           :signature-bytes "flow-sig"})
            :provenance {:run-id "run-claim-test" :scenario-id "S01"}})]
    ;; Shape assertions
    (is (= :claim (:attestation/subject-kind a)))
    (is (= "sha256:flow-test" (:attestation/subject-hash a)))
    (is (= :conservation (:attestation/claim-id a)))
    (is (= :reproduced (:attestation/claim-result a)))
    (is (= :ci-validation (:attestation/attestor-id a)))
    (is (= "key-001" (:attestation/signing-key-id a)))
    (is (= "2025-01-01T00:00:00Z" (:attestation/signed-at a)))
    (is (= {:run-id "run-claim-test" :scenario-id "S01"} (:attestation/provenance a)))
    (is (some? (:attestation/signature a)))
    (is (= (:attestation/id a) (:attestation/hash a)))
    ;; Registry flow
    (ar/register-attestation! a)
    (is (= a (ar/find-attestation (:attestation/id a))))
    (is (= 1 (count (ar/find-attestations-by-claim-id :conservation))))
    (is (= a (first (ar/find-attestations-by-subject "sha256:flow-test"))))
    ;; Determinism
    (let [a2 (att/build-claim-result-attestation
              attestor claim-result
              {:signed-at "2025-01-01T00:00:00Z"
               :claim :reproduced
               :signing-key-id "key-001"
               :signing-fn (fn [_]
                             {:algorithm :ed25519
                              :public-key-id "key-001"
                              :signature-bytes "flow-sig"})
               :provenance {:run-id "run-claim-test" :scenario-id "S01"}})]
      (is (= (:attestation/id a) (:attestation/id a2))))))

;; ── Shape conversion for backward-compat verification tests ───────────────

(defn- attestation->old-shape
  "Map new attestation shape to old shape for verify-attestation compat.
   verify-attestation checks old-style :attestor, :subject, :claim,
   :timestamp, :signature, and :attestation-id fields."
  [a]
  (let [attestor-id (:attestation/attestor-id a)
        subject-kind (:attestation/subject-kind a)
        subject (if (= :claim subject-kind)
                  {:type :claim :claim-id (:attestation/subject-hash a)}
                  {:type subject-kind :hash (:attestation/subject-hash a)})]
    (-> a
        (assoc :attestation-id (:attestation/id a))
        (assoc :attestor {:type :ci-runner :id attestor-id})
        (assoc :subject subject)
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

;; ── validate-attestation-shape ──────────────────────────────────────────────

(deftest validate-valid-attestation-passes
  (let [result (att/validate-attestation-shape
                (attestation->old-shape
                 (att/build-attestation (valid-attestor) (valid-subject) :verified)))]
    (is (:valid? result))))

(deftest validate-detects-missing-attestor
  (let [a (attestation->old-shape
           (att/build-attestation (valid-attestor) (valid-subject) :verified))
        result (att/validate-attestation-shape (dissoc a :attestor))]
    (is (false? (:valid? result)))
    (is (some #(= :attestation/missing-field (:type %)) (:errors result)))))

(deftest validate-detects-missing-subject
  (let [a (attestation->old-shape
           (att/build-attestation (valid-attestor) (valid-subject) :verified))
        result (att/validate-attestation-shape (dissoc a :subject))]
    (is (false? (:valid? result)))
    (is (some #(= :attestation/missing-field (:type %)) (:errors result)))))

(deftest validate-detects-missing-claim
  (let [a (attestation->old-shape
           (att/build-attestation (valid-attestor) (valid-subject) :verified))
        result (att/validate-attestation-shape (dissoc a :claim))]
    (is (false? (:valid? result)))
    (is (some #(= :attestation/missing-field (:type %)) (:errors result)))))

(deftest validate-detects-missing-timestamp
  (let [a (attestation->old-shape
           (att/build-attestation (valid-attestor) (valid-subject) :verified))
        result (att/validate-attestation-shape (dissoc a :timestamp))]
    (is (false? (:valid? result)))
    (is (some #(= :attestation/missing-field (:type %)) (:errors result)))))

(deftest validate-detects-attestor-without-type
  (let [result (att/validate-attestation-shape
                {:attestation-id "test"
                 :attestor {:id "no-type"}
                 :subject (valid-subject)
                 :claim :verified
                 :timestamp "2026-01-01T00:00:00Z"})]
    (is (false? (:valid? result)))
    (is (some #(= :attestation/invalid-attestor (:type %)) (:errors result)))))

(deftest validate-detects-attestor-without-id
  (let [result (att/validate-attestation-shape
                {:attestation-id "test"
                 :attestor {:type :ci-runner}
                 :subject (valid-subject)
                 :claim :verified
                 :timestamp "2026-01-01T00:00:00Z"})]
    (is (false? (:valid? result)))
    (is (some #(= :attestation/invalid-attestor (:type %)) (:errors result)))))

(deftest validate-detects-subject-without-type
  (let [result (att/validate-attestation-shape
                {:attestation-id "test"
                 :attestor (valid-attestor)
                 :subject {:hash "sha256:abc"}
                 :claim :verified
                 :timestamp "2026-01-01T00:00:00Z"})]
    (is (false? (:valid? result)))
    (is (some #(= :attestation/invalid-subject (:type %)) (:errors result)))))

(deftest validate-detects-subject-without-hash-for-evidence-node
  (let [result (att/validate-attestation-shape
                {:attestation-id "test"
                 :attestor (valid-attestor)
                 :subject {:type :evidence-node}
                 :claim :verified
                 :timestamp "2026-01-01T00:00:00Z"})]
    (is (false? (:valid? result)))
    (is (some #(= :attestation/invalid-subject (:type %)) (:errors result)))))

(deftest validate-detects-subject-without-claim-id-for-claim
  (let [result (att/validate-attestation-shape
                {:attestation-id "test"
                 :attestor (valid-attestor)
                 :subject {:type :claim}
                 :claim :verified
                 :timestamp "2026-01-01T00:00:00Z"})]
    (is (false? (:valid? result)))
    (is (some #(= :attestation/invalid-subject (:type %)) (:errors result)))))

(deftest validate-detects-malformed-signature
  (let [result (att/validate-attestation-shape
                (attestation->old-shape
                 (att/build-attestation (valid-attestor) (valid-subject) :verified
                                        {:signing-key-id "key-001"
                                         :signing-fn (fn [_] "not-a-map")})))]
    (is (false? (:valid? result)))
    (is (some #(= :attestation/malformed-signature (:type %)) (:errors result)))))

(deftest validate-detects-signature-without-algorithm
  (let [a (attestation->old-shape
           (att/build-attestation (valid-attestor) (valid-subject) :verified
                                  {:signing-key-id "key-001"
                                   :signing-fn (fn [_]
                                                 {:public-key-id "key-001"
                                                  :signature-bytes "abc"})}))]
    (is (false? (:valid? (att/validate-attestation-shape
                          (update a :signature dissoc :algorithm)))))))

(deftest validate-passes-unsigned-attestation
  (let [result (att/validate-attestation-shape
                (attestation->old-shape
                 (att/build-attestation (valid-attestor) (valid-subject) :verified)))]
    (is (:valid? result))
    (is (nil? (:errors result)))))

(deftest validate-claim-subject-passes
  (let [subject {:type :claim :claim-id :accounting-consistency}
        result (att/validate-attestation-shape
                (attestation->old-shape
                 (att/build-attestation (valid-attestor) subject :verified)))]
    (is (:valid? result))))

;; ── verify-attestation ──────────────────────────────────────────────────────

(defn- registry-attestor
  "Build an attestation referencing a real registry attestor.
   Uses :ci-validation which exists in the real registry."
  []
  {:type :ci-runner :id :ci-validation})

(defn- unknown-attestor
  "Build an attestation referencing an attestor not in the registry."
  []
  {:type :ci-runner :id :does-not-exist})

(deftest verify-passes-for-known-active-attestor
  (let [attestation (att/build-attestation (registry-attestor) (valid-subject) :verified)
        attestation (attestation->old-shape attestation)
        result (att/verify-attestation attestation)]
    (is (:valid? result))
    (is (some? (:checks result)))))

(deftest verify-detects-unknown-attestor
  (let [attestation (att/build-attestation (unknown-attestor) (valid-subject) :verified)
        attestation (attestation->old-shape attestation)
        result (att/verify-attestation attestation)]
    (is (false? (:valid? result)))
    (is (some #(and (= :attestor-exists (:check %)) (false? (:pass? %)))
              (:checks result)))))

(deftest verify-check-attestor-active-for-known-attestor
  (let [a (att/build-attestation (registry-attestor) (valid-subject) :verified)
        a (attestation->old-shape a)
        {:keys [checks]} (att/verify-attestation a)
        active-check (first (filter #(= :attestor-active (:check %)) checks))]
    (is (true? (:pass? active-check)))
    (is (= :active (get-in active-check [:detail :status])))))

(deftest verify-check-key-authorized-for-known-key
  (let [attestation (att/build-attestation (registry-attestor) (valid-subject) :verified
                                           {:signing-key-id "ci-validation-placeholder"
                                            :signing-fn (fn [_]
                                                          {:algorithm :ed25519
                                                           :public-key-id "ci-validation-placeholder"
                                                           :signature-bytes "deadbeef"})})
        attestation (attestation->old-shape attestation)
        {:keys [checks]} (att/verify-attestation attestation)
        key-check (first (filter #(= :key-authorized (:check %)) checks))]
    (is (true? (:pass? key-check))
        (str "Key should be authorized, got: " (pr-str key-check)))))

(deftest verify-check-key-not-authorized-for-unknown-key
  (let [attestation (att/build-attestation (registry-attestor) (valid-subject) :verified
                                           {:signing-key-id "completely-unknown-key"
                                            :signing-fn (fn [_]
                                                          {:algorithm :ed25519
                                                           :public-key-id "completely-unknown-key"
                                                           :signature-bytes "deadbeef"})})
        attestation (attestation->old-shape attestation)
        {:keys [checks]} (att/verify-attestation attestation)
        key-check (first (filter #(= :key-authorized (:check %)) checks))]
    (is (false? (:pass? key-check))
        (str "Unknown key should not be authorized, got: " (pr-str key-check)))))

(deftest verify-unavailable-never-produces-fully-verified-with-other-failures
  (let [attestation (att/build-attestation (unknown-attestor) (valid-subject) :verified
                                           {:signing-key-id "ci-validation-placeholder"
                                            :signing-fn (fn [_]
                                                          {:algorithm :ed25519
                                                           :public-key-id "ci-validation-placeholder"
                                                           :signature-bytes "deadbeef"})})
        attestation (attestation->old-shape attestation)
        result (att/verify-attestation attestation)
        {:keys [valid? checks]} result
        sig-check (first (filter #(= :signature-verified (:check %)) checks))]
    (is (= :unavailable (:pass? sig-check))
        "signature check is :unavailable without verify-fn")
    (is (false? valid?)
        "attestation with unknown attestor is not valid even if signature is :unavailable")
    (is (not= :verified (att/verify-attestation-summary attestation))
        ":unavailable signature does not produce :verified when other checks fail")))

(deftest verify-unavailable-alone-prevents-verified
  (let [attestation (att/build-attestation (registry-attestor) (valid-subject) :verified
                                           {:signing-key-id "ci-validation-placeholder"
                                            :signing-fn (fn [_]
                                                          {:algorithm :ed25519
                                                           :public-key-id "ci-validation-placeholder"
                                                           :signature-bytes "deadbeef"})})
        attestation (attestation->old-shape attestation)
        result (att/verify-attestation attestation)
        {:keys [valid? checks]} result
        sig-check (first (filter #(= :signature-verified (:check %)) checks))]
    ;; All other checks pass (known attestor, active, key authorized)
    (is (= :unavailable (:pass? sig-check))
        "signature check is :unavailable without verify-fn even when all other checks pass")
    (is (true? valid?)
        "valid? is true — :unavailable is not a Boolean false failure")
    ;; But the summary does NOT claim :verified — it returns nil/falls through
    (is (= :signature-unverifiable (att/verify-attestation-summary attestation))
        "verify-attestation-summary returns :signature-unverifiable when signature is unavailable")
    ;; Confirm :signature-verified is :unavailable even when all other registry checks pass
    (let [sig-check (first (filter #(= :signature-verified (:check %)) checks))]
      (is (= :unavailable (:pass? sig-check))
          "signature check is :unavailable"))
    ;; Confirm that :signature-unverifiable is preferred over :verified when
    ;; the only non-Boolean-false check is an unavailable signature
    (let [sig-check (first (filter #(= :signature-verified (:check %)) checks))
          other-non-false (filterv (fn [c] (and (not= true (:pass? c))
                                                (not= :signature-verified (:check c))))
                                   checks)]
      (is (every? #{:unavailable} (map :pass? other-non-false))
          "other non-pass checks are :unavailable (subject-exists, revocation-status)")
      (is (= :unavailable (:pass? sig-check))
          "signature is :unavailable — unverifiable rather than unsigned"))))

(deftest verify-signature-check-reports-unavailable-without-verify-fn
  (let [attestation (att/build-attestation (registry-attestor) (valid-subject) :verified
                                           {:signing-key-id "ci-validation-placeholder"
                                            :signing-fn (fn [_]
                                                          {:algorithm :ed25519
                                                           :public-key-id "ci-validation-placeholder"
                                                           :signature-bytes "deadbeef"})})
        attestation (attestation->old-shape attestation)
        {:keys [checks]} (att/verify-attestation attestation)
        sig-check (first (filter #(= :signature-verified (:check %)) checks))]
    (is (= :unavailable (:pass? sig-check)))))

(deftest verify-signature-check-passes-with-verify-fn
  (let [attestation (att/build-attestation (registry-attestor) (valid-subject) :verified
                                           {:signing-key-id "ci-validation-placeholder"
                                            :signing-fn (fn [_]
                                                          {:algorithm :ed25519
                                                           :public-key-id "ci-validation-placeholder"
                                                           :signature-bytes "deadbeef"})})
        attestation (attestation->old-shape attestation)
        {:keys [checks]} (att/verify-attestation attestation
                                                 {:verify-fn (fn [_data _sig] true)})
        sig-check (first (filter #(= :signature-verified (:check %)) checks))]
    (is (true? (:pass? sig-check)))))

(deftest verify-signature-check-fails-with-verify-fn-returning-false
  (let [attestation (att/build-attestation (registry-attestor) (valid-subject) :verified
                                           {:signing-key-id "ci-validation-placeholder"
                                            :signing-fn (fn [_]
                                                          {:algorithm :ed25519
                                                           :public-key-id "ci-validation-placeholder"
                                                           :signature-bytes "deadbeef"})})
        attestation (attestation->old-shape attestation)
        {:keys [checks]} (att/verify-attestation attestation
                                                 {:verify-fn (fn [_data _sig] false)})
        sig-check (first (filter #(= :signature-verified (:check %)) checks))]
    (is (false? (:pass? sig-check)))))

(deftest verify-signature-for-unsigned-attestation
  (let [attestation (att/build-attestation (registry-attestor) (valid-subject) :verified)
        attestation (attestation->old-shape attestation)
        {:keys [checks]} (att/verify-attestation attestation)
        sig-check (first (filter #(= :signature-verified (:check %)) checks))]
    (is (= :unsigned (:pass? sig-check)))))

(deftest verify-subject-check-reports-unavailable-without-resolver
  (let [attestation (att/build-attestation (registry-attestor) (valid-subject) :verified)
        attestation (attestation->old-shape attestation)
        {:keys [checks]} (att/verify-attestation attestation)
        subj-check (first (filter #(= :subject-exists (:check %)) checks))]
    (is (= :unavailable (:pass? subj-check)))))

(deftest verify-subject-check-passes-with-resolver-returning-true
  (let [attestation (att/build-attestation (registry-attestor) (valid-subject) :verified)
        attestation (attestation->old-shape attestation)
        {:keys [checks]} (att/verify-attestation attestation
                                                 {:subject-resolver (fn [_] true)})
        subj-check (first (filter #(= :subject-exists (:check %)) checks))]
    (is (true? (:pass? subj-check)))))

(deftest verify-subject-check-fails-with-resolver-returning-false
  (let [attestation (att/build-attestation (registry-attestor) (valid-subject) :verified)
        attestation (attestation->old-shape attestation)
        {:keys [checks]} (att/verify-attestation attestation
                                                 {:subject-resolver (fn [_] false)})
        subj-check (first (filter #(= :subject-exists (:check %)) checks))]
    (is (false? (:pass? subj-check)))))

(deftest verify-all-checks-present
  (let [attestation (att/build-attestation (registry-attestor) (valid-subject) :verified)
        attestation (attestation->old-shape attestation)
        {:keys [checks]} (att/verify-attestation attestation)]
    (is (= 6 (count checks)))
    (is (= #{:attestor-exists :attestor-active :key-authorized
             :signature-verified :subject-exists :revocation-status}
           (set (map :check checks))))))

(deftest verify-revocation-check-unavailable-without-resolver
  (let [attestation (att/build-attestation (registry-attestor) (valid-subject) :verified)
        attestation (attestation->old-shape attestation)
        {:keys [checks]} (att/verify-attestation attestation)
        rev-check (first (filter #(= :revocation-status (:check %)) checks))]
    (is (= :unavailable (:pass? rev-check)))))

(deftest verify-revocation-check-reports-revoked
  (let [attestation (att/build-attestation (registry-attestor) (valid-subject) :verified)
        attestation (attestation->old-shape attestation)
        {:keys [checks]} (att/verify-attestation attestation
                                                 {:revocation-resolver (fn [_] true)})
        rev-check (first (filter #(= :revocation-status (:check %)) checks))]
    (is (true? (:pass? rev-check)))
    (is (true? (get-in rev-check [:detail :revoked?])))))

(deftest verify-revocation-check-reports-not-revoked
  (let [attestation (att/build-attestation (registry-attestor) (valid-subject) :verified)
        attestation (attestation->old-shape attestation)
        {:keys [checks]} (att/verify-attestation attestation
                                                 {:revocation-resolver (fn [_] false)})
        rev-check (first (filter #(= :revocation-status (:check %)) checks))]
    (is (false? (:pass? rev-check)))
    (is (false? (get-in rev-check [:detail :revoked?])))))

(deftest verify-does-not-fail-on-revoked-attestation
  (let [attestation (att/build-attestation (registry-attestor) (valid-subject) :verified)
        attestation (attestation->old-shape attestation)
        {:keys [valid?]} (att/verify-attestation attestation
                                                 {:revocation-resolver (fn [_] true)})]
    (is valid?
        "Revocation is informational — it must not make verification fail")))

;; ── verify-attestation-summary ─────────────────────────────────────────────

(deftest summary-returns-verified-for-valid-attestation
  (let [a (att/build-attestation (registry-attestor) (valid-subject) :verified)
        a (attestation->old-shape a)]
    (is (= :verified (att/verify-attestation-summary a)))))

(deftest summary-returns-no-such-attestor
  (let [a (att/build-attestation (unknown-attestor) (valid-subject) :verified)
        a (attestation->old-shape a)]
    (is (= :no-such-attestor (att/verify-attestation-summary a)))))

(deftest summary-returns-key-not-authorized
  (let [a (att/build-attestation (registry-attestor) (valid-subject) :verified
                                 {:signing-key-id "completely-unknown-key"
                                  :signing-fn (fn [_]
                                                {:public-key-id "completely-unknown-key"
                                                 :signature-bytes "x"})})
        a (attestation->old-shape a)]
    (is (= :key-not-authorized (att/verify-attestation-summary a)))))

(deftest summary-returns-signature-mismatch
  (let [a (att/build-attestation (registry-attestor) (valid-subject) :verified
                                 {:signing-key-id "ci-validation-placeholder"
                                  :signing-fn (fn [_]
                                                {:algorithm :ed25519
                                                 :public-key-id "ci-validation-placeholder"
                                                 :signature-bytes "deadbeef"})})
        a (attestation->old-shape a)]
    (is (= :signature-mismatch (att/verify-attestation-summary a
                                                               {:verify-fn (fn [_ _] false)})))))

(deftest summary-returns-subject-unknown
  (let [a (att/build-attestation (registry-attestor) (valid-subject) :verified)
        a (attestation->old-shape a)]
    (is (= :subject-unknown (att/verify-attestation-summary a
                                                            {:subject-resolver (fn [_] false)})))))

;; ── Signature Projection Security Fields ─────────────────────────────────────

(def ^:private security-relevant-projection-fields
  "The canonical projection must bind all fields whose tampering would
   change the meaning of an attestation.  These are:
     schema-version       — which schema/rules apply
     subject-hash         — what is being attested about
     subject-kind         — what type of subject
     claim-id             — which claim definition (if applicable)
     claim-result         — the attested outcome
     attestor-id          — who attested
     signing-key-id       — which key (if any) was used
     signed-at            — when the attestation was created
     provenance           — context of the attestation run

   Excluded from the projection by design:
     :attestation/id      — self-referential (derived from this projection)
     :attestation/hash    — same as id
     :attestation/signature  — the signature itself (can't sign self)
     :attestation/metadata  — explicitly ephemeral, excluded from content identity"
  [:schema-version
   :attestation/subject-hash
   :attestation/subject-kind
   :attestation/claim-id
   :attestation/claim-result
   :attestation/attestor-id
   :attestation/signing-key-id
   :attestation/signed-at
   :attestation/provenance])

(deftest signature-projection-binds-security-relevant-fields
  (let [a (att/build-attestation (valid-attestor) (valid-subject) :verified
                                 {:claim-id :claim/consistency
                                  :signing-key-id "k1"
                                  :provenance {:run-id "r1"}})
        projected (hc/project-attestation-record a :attestation-record)
        projected-keys (set (keys (:artifact projected)))]
    (testing "all documented security-relevant fields are included in projection"
      (doseq [k security-relevant-projection-fields]
        (is (contains? projected-keys k)
            (str "projection must include " k))))
    (testing "no extra fields leak into projection"
      (is (= (set security-relevant-projection-fields) projected-keys)
          "projection is exactly the documented set — no more, no less"))
    (testing "projection is deterministic and reusable for verification"
      (let [reprojected (hc/project-attestation-record a :attestation-record)]
        (is (= projected reprojected) "projection is idempotent"))
      (is (= :attestation-record (:intent projected))
          "intent domain tag is bound"))))

(deftest signing-payload-equals-hash-projection
  (let [a (att/build-attestation (valid-attestor) (valid-subject) :verified
                                 {:claim-id :claim/consistency
                                  :signing-key-id "k1"
                                  :provenance {:run-id "r1"}})
        payload (att/signing-payload a)
        projected (hc/project-attestation-record a :attestation-record)]
    (is (= projected payload)
        "signing-payload and project-attestation-record produce identical output")
    (is (= (hc/hash-with-intent {:hash/intent :attestation-record} (:artifact projected))
           (:attestation/hash a))
        "the hash of the projection equals the attestation's content hash")))
