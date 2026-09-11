(ns resolver-sim.allocation.certificate-test
  "Tests for allocation-assurance-certificate.v1 composition."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.allocation.certificate :as cert]
            [resolver-sim.allocation.kernel :as kernel]
            [resolver-sim.allocation.test-fixtures :as fixtures]
            [resolver-sim.support.ed25519 :as ed]))

(deftest certificate-schema-and-subjects
  (let [result (fixtures/kernel-result)
        c (cert/compose-certificate result)]
    (is (= "allocation-assurance-certificate.v1" (:schema-version c)))
    (is (= (:allocation-context-hash result)
           (get-in c [:subject-roots :allocation-context-hash])))
    (is (= (:result-root result) (:result-root c)))
    (is (= (:selected-outcome-id result)
           (get-in c [:selected-outcome :selected-outcome-id])))
    (is (= 14 (count (:assertions c))))))

(deftest certificate-assertion-assurance-classifications
  (let [c (cert/compose-certificate (fixtures/kernel-result))]
    (doseq [assertion (:assertions c)]
      (is (= :independent-replay (:assurance assertion)))
      (is (contains? #{:zk-proof :independent-replay :economic-assumption :not-yet-evaluated}
                     (:assurance assertion))))))

(deftest certificate-never-claims-zk-proof
  (let [c (cert/compose-certificate (fixtures/kernel-result))]
    (is (not-any? #(= :zk-proof (:assurance %)) (:assertions c)))
    (is (not= :zk-proof (get-in c [:proof :status])))
    (is (= :not-yet-evaluated (get-in c [:proof :status])))
    (is (= :none (get-in c [:proof :proof-mode]))
        "no native evidence is :none — never mislabeled :mock-native")))

(deftest certificate-records-economic-assumption
  (let [c (cert/compose-certificate (fixtures/kernel-result))]
    (is (= :economic-assumption (get-in c [:assume-punishment-credible :assurance])))
    (is (= :declared-supported (get-in c [:assume-punishment-credible :status])))))

(deftest certificate-rejected-result-records-classification
  (let [input (assoc (fixtures/happy-input)
                     "outcomes"
                     [{"outcome-id" "O1"
                       "allocations" [{"claim-id" "A" "allocated" "60"}]}])
        result (kernel/run-kernel input)
        c (cert/compose-certificate result)]
    (is (= :rejected (:result/status c)))
    (is (some? (:rejection/classification c)))))

;; ── Content addressing (B4) ──────────────────────────────────────────────────

(deftest certificate-carries-deterministic-self-hash
  (let [a (cert/compose-certificate (fixtures/kernel-result))
        b (cert/compose-certificate (fixtures/kernel-result))]
    (is (string? (:certificate/hash a)))
    (is (re-matches #"sha256:[0-9a-f]{64}" (:certificate/hash a)))
    (is (= (:certificate/hash a) (:certificate/hash b))
        "deterministic across recompositions from the same kernel result")))
(deftest certificate-self-hash-excludes-itself-and-signature
  (let [c (cert/compose-certificate (fixtures/kernel-result))]
    (testing "the self-hash excludes :certificate/hash (no recursion)"
      (is (= (:certificate/hash c)
             (cert/certificate-hash (assoc c :certificate/hash "sha256:WRO")))
          "recomputing with any stored hash yields the same identity"))
    (testing "the self-hash excludes :certificate/signature (attestation-after)"
      (is (= (:certificate/hash c)
             (cert/certificate-hash (assoc c :certificate/signature {:x 1})))))))

(deftest certificate-tampering-changes-self-hash
  (let [c (cert/compose-certificate (fixtures/kernel-result))
        tampered (assoc-in c [:result-totals :total-allocated] 999999)]
    (is (not= (:certificate/hash c) (cert/certificate-hash tampered))
        "recomputation over tampered content differs from the committed hash")))

(deftest verify-certificate-accepts-genuine-document
  (let [c (cert/compose-certificate (fixtures/kernel-result))]
    (is (:valid? (cert/verify-certificate c)))
    (is (nil? (:signature-valid? (cert/verify-certificate c)))
        "unsigned certificate: self-hash valid, attestation absent")
    (is (empty? (:issues (cert/verify-certificate c))))))

(deftest verify-certificate-rejects-tampered-document
  (let [c (cert/compose-certificate (fixtures/kernel-result))
        tampered (update-in c [:selected-outcome :selected-outcome-id] (constantly "O99"))
        v (cert/verify-certificate tampered)]
    (is (not (:valid? v)))
    (is (some #(= :certificate/hash-mismatch (:code %)) (:issues v)))))

(deftest verify-certificate-rejects-forged-self-hash
  (let [c (cert/compose-certificate (fixtures/kernel-result))
        forged (assoc c :certificate/hash "sha256:0000000000000000000000000000000000000000000000000000000000000000")
        v (cert/verify-certificate forged)]
    (is (not (:valid? v)))
    (is (some #(= :certificate/hash-mismatch (:code %)) (:issues v)))))

(deftest certificate-sign-round-trip
  (let [keypair (ed/keypair :validator-key)
        c (cert/compose-certificate (fixtures/kernel-result))
        signed (cert/sign-certificate c (:private-key keypair) :val-1)
        trust {:trusted-keys [{:key/id :val-1
                               :key/public (:public-hex keypair)
                               :key/role :allocation-issuer
                               :key/status :active}]}]
    (testing "signing does not change the certificate identity"
      (is (= (:certificate/hash c) (:certificate/hash signed))))
    (testing "a genuine signature verifies against the trust policy"
      (let [v (cert/verify-certificate signed trust :allocation-issuer)]
        (is (:valid? v))
        (is (true? (:signature-valid? v)))
        (is (empty? (:issues v)))))))

(deftest certificate-signature-fails-closed-on-tamper
  (let [keypair (ed/keypair :validator-key)
        c (cert/compose-certificate (fixtures/kernel-result))
        signed (cert/sign-certificate c (:private-key keypair) :val-1)
        trust {:trusted-keys [{:key/id :val-1
                               :key/public (:public-hex keypair)
                               :key/role :allocation-issuer
                               :key/status :active}]}
        tampered (assoc-in signed [:result-root] "0xdeadbeef")
        v (cert/verify-certificate tampered trust :allocation-issuer)]
    (is (not (:valid? v)))
    (is (some #(= :certificate/hash-mismatch (:code %)) (:issues v)))
    (is (some #(= :certificate/signature-hash-mismatch (:code %)) (:issues v))
        "the attestation no longer commits the recomputed hash")))

(deftest certificate-signature-rejects-untrusted-or-wrong-role
  (let [keypair (ed/keypair :validator-key)
        c (cert/compose-certificate (fixtures/kernel-result))
        signed (cert/sign-certificate c (:private-key keypair) :val-1)
        empty-trust {:trusted-keys []}
        wrong-role {:trusted-keys [{:key/id :val-1
                                    :key/public (:public-hex keypair)
                                    :key/role :other
                                    :key/status :active}]}]
    (let [v (cert/verify-certificate signed empty-trust :allocation-issuer)]
      (is (not (:valid? v)))
      (is (some #(= :certificate/untrusted-key (:code %)) (:issues v))))
    (let [v (cert/verify-certificate signed wrong-role :allocation-issuer)]
      (is (not (:valid? v)))
      (is (some #(= :certificate/wrong-key-role (:code %)) (:issues v))))))

(deftest certificate-signature-rejects-invalid-signature-bytes
  (let [keypair (ed/keypair :validator-key)
        other (ed/keypair :other)
        c (cert/compose-certificate (fixtures/kernel-result))
        signed (cert/sign-certificate c (:private-key keypair) :val-1)
        trust {:trusted-keys [{:key/id :val-1
                               :key/public (:public-hex other)
                               :key/role :allocation-issuer
                               :key/status :active}]}]
    (let [v (cert/verify-certificate signed trust :allocation-issuer)]
      (is (not (:valid? v)))
      (is (some #(= :certificate/invalid-signature (:code %)) (:issues v))))))
