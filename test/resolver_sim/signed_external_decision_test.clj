(ns resolver-sim.signed-external-decision-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.signed-external-decision :as sed]
            [resolver-sim.support.ed25519 :as fx]))

(def domain "PRF_TEST_DECISION_V1")

(defn- sign [kp envelope]
  (sed/sign-envelope envelope domain (:private-key kp) (:key/id kp)))

(deftest sign-then-verify-roundtrip
  (let [kp (fx/keypair)
        envelope (sign kp {:k :v :reasons [:b :a]})
        policy (fx/trust-policy kp)]
    (is (true? (:valid? (sed/verify-envelope envelope domain policy :sensitivity-sentinel))))))

(deftest signature-binds-complete-envelope
  (testing "mutating any field invalidates the signature"
    (doseq [[label mut] [[:sink #(assoc % :sentinel/sink :local)]
                         [:decision #(assoc % :sentinel/decision :allow)]
                         [:level #(assoc % :sentinel/level :sensitivity/public)]
                         [:artifact #(assoc % :sentinel/artifact-hash "sha256:other")]
                         [:policy #(assoc % :sentinel/policy-hash "sha256:other")]]]
      (let [kp (fx/keypair)
            signed (sign kp {:sentinel/sink :ipfs
                             :sentinel/decision :block
                             :sentinel/level :sensitivity/private
                             :sentinel/artifact-hash "sha256:x"
                             :sentinel/policy-hash "sha256:p"})
            tampered (mut signed)
            v (sed/verify-envelope tampered domain (fx/trust-policy kp) :sensitivity-sentinel)]
        (is (false? (:valid? v)) (str label " mutation must invalidate"))
        (is (= :signed-hash-mismatch (:reason v)) (str label " reason"))))))

(deftest domain-separation
  (let [kp (fx/keypair)
        signed (sign kp {:sentinel/decision :block})]
    (is (false? (:valid? (sed/verify-envelope signed "PRF_RELEASE_ATTESTATION_PAYLOAD_V1"
                                              (fx/trust-policy kp) :sensitivity-sentinel)))
        "signature valid in one domain must not verify in another")))

(deftest wrong-key-role-rejected
  (let [kp (fx/keypair)
        signed (sign kp {:sentinel/decision :block})
        v (sed/verify-envelope signed domain (fx/trust-policy kp :release :active) :sensitivity-sentinel)]
    (is (false? (:valid? v)))
    (is (= :wrong-key-role (:reason v)))))

(deftest inactive-key-rejected
  (let [kp (fx/keypair)
        signed (sign kp {:sentinel/decision :block})
        v (sed/verify-envelope signed domain (fx/trust-policy kp :sensitivity-sentinel :revoked) :sensitivity-sentinel)]
    (is (false? (:valid? v)))
    (is (= :inactive-key (:reason v)))))

(deftest untrusted-key-rejected
  (let [kp (fx/keypair)
        other (fx/keypair :other)
        signed (sign kp {:sentinel/decision :block})
        policy (fx/trust-policy other)
        v (sed/verify-envelope signed domain policy :sensitivity-sentinel)]
    (is (false? (:valid? v)))
    (is (= :untrusted-key (:reason v)))))

(deftest missing-signature-rejected
  (let [kp (fx/keypair)
        v (sed/verify-envelope {:sentinel/decision :block} domain (fx/trust-policy kp) :sensitivity-sentinel)]
    (is (false? (:valid? v)))
    (is (= :missing-signature (:reason v)))))

(deftest nondeterministic-reason-ordering-normalizes
  (testing "reason ordering does not change the envelope hash"
    (let [kp (fx/keypair)
          a (sign kp {:sentinel/reasons [:b :a]})
          b (sign kp {:sentinel/reasons [:a :b]})]
      (is (= (:signed-hash (:signature a)) (:signed-hash (:signature b))))
      (is (true? (:valid? (sed/verify-envelope b domain (fx/trust-policy kp) :sensitivity-sentinel)))))))

(deftest request-hash-commits-content
  (let [r1 (sed/attach-request-hash "PRF_REQ_V1" {:a 1 :b 2})
        r2 (sed/attach-request-hash "PRF_REQ_V1" {:b 2 :a 1})]
    (is (= (:request/hash r1) (:request/hash r2))))
  (let [r1 (sed/attach-request-hash "PRF_REQ_V1" {:a 1})
        r2 (sed/attach-request-hash "PRF_REQ_V1" {:a 2})]
    (is (not= (:request/hash r1) (:request/hash r2)))))
