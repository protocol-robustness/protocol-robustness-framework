(ns resolver-sim.allocation.proof-admission-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [buddy.core.codecs :as codecs]
            [resolver-sim.allocation.context :as ctx]
            [resolver-sim.allocation.proof-admission :as admission]
            [resolver-sim.allocation.proof-verifier-issuer :as issuer]
            [resolver-sim.allocation.realized-statement :as statement]
            [resolver-sim.allocation.round-state :as round-state]
            [resolver-sim.yield.partial-fill :as partial-fill]
            [resolver-sim.economics.payoffs :as payoffs]
            [resolver-sim.support.ed25519 :as fx]))

(def raw-context
  {"allocation-id" "proof-admission-test"
   "kernel-version" "allocation-kernel.v1"
   "selection-algorithm" "domain-hash-rejection-v1"
   "policy" {"policy-id" "policy" "policy-hash" "0xabababababababababababababababababababababababababababababababab"}
   "claimants" [{"claim-id" "A" "economic-owner-id" "owner-A" "amount" "50" "weight" "50"}
                {"claim-id" "B" "economic-owner-id" "owner-B" "amount" "50" "weight" "50"}]
   "outcomes" [{"outcome-id" "O1" "allocations" [{"claim-id" "A" "allocated" "50"} {"claim-id" "B" "allocated" "0"}]}
               {"outcome-id" "O2" "allocations" [{"claim-id" "A" "allocated" "0"} {"claim-id" "B" "allocated" "50"}]}]
   "proposed-rates" [{"outcome-id" "O1" "numerator" "1" "denominator" "2"}
                     {"outcome-id" "O2" "numerator" "1" "denominator" "2"}]
   "capacity" "50" "total-eligible-weight" "100" "exact-pro-rata-denominator" "100"
   "authoritative-randomness" "0x0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20"})

(def context (ctx/build-context raw-context))
(def lifecycle (round-state/round-lifecycle {} :result-accepted))
(def supported-decision
  {:decision/id :proof-profile-supported
   :requested {:A 50 :B 50}
   :filled {:A 25 :B 25}
   :deferred {:A 25 :B 25}
   :haircut {}
   :policy {:mode :pro-rata :rounding-policy :largest-remainder}})

(defn- sha-ref [c] (str "sha256:" (apply str (repeat 64 c))))
(defn- wire-kw [kw] (subs (str kw) 1))
(defn- sha256-bytes-ref [bs]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")]
    (.update digest bs)
    (str "sha256:" (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest digest))))))

(defn- sha256-utf8-ref [s]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")]
    (.update digest (.getBytes s "UTF-8"))
    (str "sha256:" (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest digest))))))

(defn- statement-fixture [decision]
  (statement/build-statement {:ctx context :decision decision :round-lifecycle lifecycle}))

(defn- artifact-fixture [s]
  (let [statement-root-bytes (codecs/hex->bytes (:statement/root s))
        public-values-bytes32 (str "0x" (codecs/bytes->hex statement-root-bytes))]
    (admission/build-proof-artifact
     {:proof/schema-version admission/proof-artifact-schema
      :proof/profile admission/proof-profile
      :statement/schema-version admission/statement-version
      :statement/root (:statement/root s)
      :program/id "realized-statement-sp1-program.v1"
      :program/elf-sha256 (sha-ref "a")
      :program/vkey "0x1111111111111111111111111111111111111111111111111111111111111111"
      :public-values/schema :evm-bytes32-v1
      :public-values/bytes32 public-values-bytes32
      :public-values/sha256 (sha256-bytes-ref statement-root-bytes)
      :proof/encoding "sp1-bincode.v1"
      :proof/file "proof.sp1-proof.bin"
      :proof/sha256 (sha256-utf8-ref "\u0001\u0002\u0003\u0004")})))
(defn- program-registry [artifact]
  {admission/proof-profile
   (select-keys artifact [:program/id :program/elf-sha256 :program/vkey
                          :statement/schema-version :public-values/schema])})

(def ^:private persisted-input-sha256 (sha-ref "e"))

(defn- signed-receipt [artifact keypair]
  (-> (admission/build-verifier-receipt
       {:artifact artifact :persisted-input-sha256 persisted-input-sha256
        :verifier-id :sp1-sdk :verifier-version "6.3.1"
        :verdict :verified})
      (admission/sign-verifier-receipt (:private-key keypair) (:key/id keypair))))

(deftest narrow-profile-is-explicit
  (is (= :supported (:status (admission/proof-profile-result supported-decision))))
  (testing "any effective cap is excluded until cap/saturation semantics are independently implemented"
    (is (= :uncovered
           (:status (admission/proof-profile-result
                     (assoc-in supported-decision [:evidence :allocation-rows]
                               [{:key :A :owed 50 :filled 25 :effective-cap 50}])))))
    (is (= :uncovered
           (:status (admission/proof-profile-result
                     (assoc-in supported-decision [:evidence :allocation-rows]
                               [{:key :A :owed 50 :filled 25 :cap 50}]))))))
  (testing "redistribution rows are excluded until redistribution semantics are independently implemented"
    (is (= :uncovered
           (:status (admission/proof-profile-result
                     (assoc-in supported-decision [:evidence :allocation-rows]
                               [{:key :A :owed 50 :filled 25 :redistribution 25}])))))
    (is (= :uncovered
           (:status (admission/proof-profile-result
                     (assoc-in supported-decision [:evidence :allocation-rows]
                               [{:key :A :owed 50 :filled 25 :effective-cap 50 :redistribution 25}]))))))
  (testing "haircut and unsupported rounding cannot be silently simplified"
    (is (= :uncovered (:status (admission/proof-profile-result
                                (assoc supported-decision :haircut {:A 1})))))
    (is (= :uncovered (:status (admission/proof-profile-result
                                (assoc-in supported-decision [:policy :rounding-policy] :floor-and-carry)))))))

(deftest statement-recomputation-and-scenario-binding
  (let [s (statement-fixture supported-decision)
        binding {:scenario-id "proof-admission-test"
                 :evidence-content-root "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                 :statements-root (:statement/root s)}
        binding (assoc binding :binding-root (admission/scenario-statement-binding-root binding))]
    (is (admission/statement-match? {:statement s :allocation-context context
                                     :decision supported-decision :round-lifecycle lifecycle}))
    (is (not (admission/statement-match? {:statement (assoc s :statement/root (apply str (repeat 64 "f")))
                                          :allocation-context context :decision supported-decision
                                          :round-lifecycle lifecycle})))
    (is (admission/valid-scenario-statement-binding? binding))
    (is (not (admission/valid-scenario-statement-binding? (assoc binding :scenario-id "other-round"))))))

(deftest production-proof-admission-is-blind-to-parallel-origin
  (let [policy {:mode :pro-rata :rounding-policy :largest-remainder}
        run (fn [parallelism]
              (binding [payoffs/*pro-rata-parallel-threshold* 1]
                (partial-fill/calculate-fulfillment-pro-rata
                 50 {:A 50 :B 50} policy
                 {:execution/claimant-parallelism parallelism})))
        serial-decision (run 1)
        parallel-decision (run 2)
        serial-statement (statement-fixture serial-decision)
        parallel-statement (statement-fixture parallel-decision)
        artifact (artifact-fixture serial-statement)
        kp (fx/keypair :parallel-origin-proof-admission)
        trust (fx/trust-policy kp :allocation-proof-verifier :active)
        receipt (signed-receipt artifact kp)
        request (fn [decision statement]
                  {:artifact artifact :receipt receipt :trust-policy trust
                   :program-registry (program-registry artifact) :statement statement
                   :allocation-context context :decision decision
                   :round-lifecycle lifecycle})]
    (is (= serial-decision parallel-decision))
    (is (= serial-statement parallel-statement))
    (is (true? (admission/cryptographic-computation-admitted?
                (request serial-decision serial-statement))))
    ;; Admission receives only the ordinary canonical decision/statement inputs;
    ;; no parallelism, executor, threshold, or worker provenance is supplied.
    (is (true? (admission/cryptographic-computation-admitted?
                (request parallel-decision parallel-statement))))))

(deftest signed-verifier-receipt-is-the-cryptographic-authority
  (let [s (statement-fixture supported-decision)
        artifact (artifact-fixture s)
        kp (fx/keypair :sp1-verifier)
        trust (fx/trust-policy kp :allocation-proof-verifier :active)
        receipt (signed-receipt artifact kp)
        request {:artifact artifact :receipt receipt :trust-policy trust
                 :program-registry (program-registry artifact) :statement s
                 :allocation-context context :decision supported-decision
                 :round-lifecycle lifecycle}]
    (is (true? (admission/cryptographic-computation-admitted? request)))
    (testing "metadata cannot substitute a different public statement"
      (is (false? (admission/cryptographic-computation-admitted?
                   (assoc-in request [:artifact :statement/root] (sha-ref "d")))))
      (is (false? (admission/cryptographic-computation-admitted?
                   (assoc-in request [:artifact :public-values/bytes32]
                             (str "0x" (apply str (repeat 64 "0"))))))))
    (testing "program/VK identity is registry pinned, not caller nominated"
      (is (false? (admission/cryptographic-computation-admitted?
                   (assoc-in request [:artifact :program/vkey] "0x2222"))))
      (is (false? (admission/cryptographic-computation-admitted?
                   (assoc request :program-registry {})))))
    (testing "a self-consistent receipt from an untrusted signer is rejected"
      (let [other (fx/keypair :other)
            forged (signed-receipt artifact other)]
        (is (false? (admission/cryptographic-computation-admitted?
                     (assoc request :receipt forged))))))))

(deftest persisted-artifact-and-receipt-ingestion-fail-closed
  (let [statement (statement-fixture supported-decision)
        artifact (artifact-fixture statement)
        proof-json (json/write-str {"schema_version" (:proof/schema-version artifact)
                                    "proof_profile" (wire-kw (:proof/profile artifact))
                                    "statement_schema_version" (:statement/schema-version artifact)
                                    "statement_root" (:statement/root artifact)
                                    "program_id" (:program/id artifact)
                                    "program_elf_sha256" (:program/elf-sha256 artifact)
                                    "program_vkey" (:program/vkey artifact)
                                    "public_values_schema" (wire-kw (:public-values/schema artifact))
                                    "public_values_bytes32" (:public-values/bytes32 artifact)
                                    "public_values_sha256" (:public-values/sha256 artifact)
                                    "proof_encoding" (:proof/encoding artifact)
                                    "proof_file" (:proof/file artifact)
                                    "proof_sha256" (:proof/sha256 artifact)
                                    "proof_artifact_hash" (:proof/artifact-hash artifact)})
        kp (fx/keypair :sp1-verifier)
        receipt (signed-receipt artifact kp)
        receipt-json (json/write-str {"verification_schema_version" (:verification/schema-version receipt)
                                      "verification_verdict" (name (:verification/verdict receipt))
                                      "proof_artifact_hash" (:proof/artifact-hash receipt)
                                      "proof_profile" (wire-kw (:proof/profile receipt))
                                      "statement_root" (:statement/root receipt)
                                      "program_id" (:program/id receipt)
                                      "program_elf_sha256" (:program/elf-sha256 receipt)
                                      "program_vkey" (:program/vkey receipt)
                                      "public_values_sha256" (:public-values/sha256 receipt)
                                      "proof_sha256" (:proof/sha256 receipt)
                                      "persisted_input_sha256" (:persisted-input/sha256 receipt)
                                      "verifier_id" (wire-kw (:verifier/id receipt))
                                      "verifier_version" (:verifier/version receipt)
                                      "signature" {"schema_version" (get-in receipt [:signature :schema-version])
                                                   "key_id" (name (get-in receipt [:signature :key-id]))
                                                   "algorithm" (name (get-in receipt [:signature :algorithm]))
                                                   "signed_hash" (get-in receipt [:signature :signed-hash])
                                                   "signature_encoding" (name (get-in receipt [:signature :signature-encoding]))
                                                   "signature_bytes" (get-in receipt [:signature :signature-bytes])}})]
    (is (true? (:valid? (admission/ingest-proof-artifact-json proof-json))))
    (is (false? (:valid? (admission/ingest-proof-artifact-json
                          (str "{\"proof_sha256\":\"x\",\"proof_sha256\":\"y\"," (subs proof-json 1))))))
    (is (false? (:valid? (admission/ingest-proof-artifact-json
                          (str/replace proof-json "proof.sp1-proof.bin" "other.sp1-proof.bin")))))
    (is (true? (:valid? (admission/ingest-verifier-receipt-json receipt-json))))
    (is (false? (:valid? (admission/ingest-verifier-receipt-json "{\"verification_verdict\":\"verified\"}"))))
    (let [dir (.toFile (java.nio.file.Files/createTempDirectory "proof-artifact" (make-array java.nio.file.attribute.FileAttribute 0)))
          file (java.io.File. dir (:proof/file artifact))]
      (spit file "\u0001\u0002\u0003\u0004")
      (is (admission/verify-proof-file! dir artifact))
      (spit file "tampered")
      (is (not (admission/verify-proof-file! dir artifact))))))

(deftest issuer-derives-and-signs-only-verified-bundle-identity
  (let [artifact-path "results/allocation/a-vs-b-plus-c/realized-statement/sp1-proof-artifact.json"
        artifact (:artifact (admission/ingest-proof-artifact-json (slurp artifact-path)))
        kp (fx/keypair :issuer-test)
        trust (fx/trust-policy kp :allocation-proof-verifier :active)
        decision {"verification_schema_version" admission/verifier-receipt-schema
                  "verification_verdict" "verified"
                  "proof_profile" (wire-kw (:proof/profile artifact))
                  "statement_root" (:statement/root artifact)
                  "program_id" (:program/id artifact)
                  "program_elf_sha256" (:program/elf-sha256 artifact)
                  "program_vkey" (:program/vkey artifact)
                  "public_values_sha256" (:public-values/sha256 artifact)
                  "proof_sha256" (:proof/sha256 artifact)
                  "verifier_id" "test-sdk" "verifier_version" "test"}
        script (.toFile (java.nio.file.Files/createTempFile "proof-verifier" ".sh" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (spit script (str "#!/bin/sh\nprintf '%s\\n' '" (json/write-str decision) "'\n"))
        _ (.setExecutable script true)
        receipt (issuer/issue! {:artifact-path artifact-path
                                :verifier-bin (.getPath script)
                                :private-key (:private-key kp)
                                :key-id (:key/id kp)
                                :trust-policy trust})]
    (is (= (:proof/artifact-hash artifact) (:proof/artifact-hash receipt)))
    (is (= "sha256:818def0d82465615755b43d47fe75d6c0bf90035cece880b0736fd291b35dfd0"
           (:persisted-input/sha256 receipt)))
    (is (true? (:valid? (admission/verify-verifier-receipt artifact receipt trust))))
    (is (false? (:valid? (admission/verify-verifier-receipt artifact receipt
                                                            (fx/trust-policy kp :other-role :active)))))))

(deftest signed-receipt-binds-the-persisted-input-bytes
  (let [s (statement-fixture supported-decision)
        artifact (artifact-fixture s)
        kp (fx/keypair :sp1-verifier)
        trust (fx/trust-policy kp :allocation-proof-verifier :active)
        receipt (signed-receipt artifact kp)
        input-result {:valid? true :input-sha256 persisted-input-sha256}]
    (is (true? (admission/persisted-bundle-receipt-admitted?
                artifact receipt trust input-result)))
    (is (false? (admission/persisted-bundle-receipt-admitted?
                 artifact receipt trust (assoc input-result :input-sha256 (sha-ref "f"))))
        "a valid receipt cannot be transplanted to different canonical input bytes")
    (is (false? (admission/persisted-bundle-receipt-admitted?
                 artifact (assoc receipt :persisted-input/sha256 (sha-ref "f")) trust input-result)))))

(deftest one-proof-cannot-cover-a-statement-collection
  (let [a (statement-fixture supported-decision)
        other-decision (assoc supported-decision :decision/id :other
                              :filled {:A 50 :B 50} :deferred {})
        b (statement-fixture other-decision)
        artifact (artifact-fixture a)
        partial [{:artifact artifact}]
        complete [{:artifact artifact}
                  {:artifact (artifact-fixture b)}]]
    (is (false? (:complete? (admission/statement-proof-coverage [a b] partial))))
    (is (true? (:complete? (admission/statement-proof-coverage [a b] complete))))))
