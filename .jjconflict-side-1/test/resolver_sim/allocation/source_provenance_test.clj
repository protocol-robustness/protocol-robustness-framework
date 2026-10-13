(ns resolver-sim.allocation.source-provenance-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [resolver-sim.allocation.source-provenance :as provenance]
            [resolver-sim.support.ed25519 :as fx]))

(def source-base
  {:source/schema-version provenance/source-schema
   :source/id "a-vs-b-plus-c"
   :source/scope :fixed-scenario-test-vector
   :allocation-context
   {"allocation-id" "a-vs-b-plus-c"
    "kernel-version" "allocation-kernel.v1"
    "selection-algorithm" "domain-hash-rejection-v1"
    "policy" {"policy-id" "p" "policy-hash" "0xabababababababababababababababababababababababababababababababab" "forbid-duplicate-owners" false}
    "claimants" [{"claim-id" "A" "economic-owner-id" "oA" "amount" "50" "weight" "50"}
                 {"claim-id" "B" "economic-owner-id" "oB" "amount" "30" "weight" "30"}
                 {"claim-id" "C" "economic-owner-id" "oC" "amount" "20" "weight" "20"}]
    "outcomes" [{"outcome-id" "O1" "allocations" [{"claim-id" "A" "allocated" "50"} {"claim-id" "B" "allocated" "0"} {"claim-id" "C" "allocated" "0"}]}
                {"outcome-id" "O2" "allocations" [{"claim-id" "A" "allocated" "0"} {"claim-id" "B" "allocated" "30"} {"claim-id" "C" "allocated" "20"}]}]
    "proposed-rates" [{"outcome-id" "O1" "numerator" "1" "denominator" "2"}
                      {"outcome-id" "O2" "numerator" "1" "denominator" "2"}]
    "capacity" "50" "total-eligible-weight" "100" "exact-pro-rata-denominator" "100"
    "authoritative-randomness" "0x0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20"}
   :available "100"
   :requested {"A" "50" "B" "30" "C" "20"}
   :policy {"mode" "pro-rata" "rounding-policy" "largest-remainder"}
   :fail-action-policy {"mode" "pro-rata-treatment" "deferred-policy" "same-ratio" "haircut-policy" "same-ratio"}
   :round-state "result-accepted"})

(defn signed-source [kp]
  (let [source (provenance/build-source source-base)]
    (merge source (provenance/sign-source source (:private-key kp) (:key/id kp)))))

(defn source-wire [source]
  {"source_schema_version" (:source/schema-version source)
   "source_id" (:source/id source) "source_scope" (name (:source/scope source))
   "allocation_context" (:allocation-context source) "available" (:available source)
   "requested" (:requested source) "policy" (:policy source)
   "fail_action_policy" (:fail-action-policy source) "round_state" (:round-state source)
   "source_root" (:source/root source)
   "signature" {"schema_version" (get-in source [:signature :schema-version])
                "key_id" (subs (str (get-in source [:signature :key-id])) 1)
                "algorithm" (name (get-in source [:signature :algorithm]))
                "signed_hash" (get-in source [:signature :signed-hash])
                "signature_encoding" (name (get-in source [:signature :signature-encoding]))
                "signature_bytes" (get-in source [:signature :signature-bytes])}})

(deftest source-authority-and-projection-are-fail-closed
  (let [kp (fx/keypair :allocation-source-test)
        trust (fx/trust-policy kp provenance/source-role :active)
        source (signed-source kp)]
    (is (:valid? (provenance/verify-source source trust)))
    (is (= {"allocation-context" (:allocation-context source-base)
            "available" "100" "requested" {"A" "50" "B" "30" "C" "20"}
            "policy" (:policy source-base) "fail-action-policy" (:fail-action-policy source-base)
            "round-state" "result-accepted"}
           (provenance/project-realized-input source)))
    (testing "source facts and the source identity are all signature bound"
      (doseq [[label mutate] [[:available #(assoc % :available "99")]
                              [:requested #(assoc-in % [:requested "A"] "49")]
                              [:policy #(assoc-in % [:policy "rounding-policy"] "floor-and-carry")]
                              [:fail-action #(assoc-in % [:fail-action-policy "deferred-policy"] "other")]
                              [:owner #(assoc-in % [:allocation-context "claimants" 0 "economic-owner-id"] "other")]
                              [:round-state #(assoc % :round-state "cancelled")]
                              [:scenario #(assoc % :source/id "other")]]]
        (is (false? (:valid? (provenance/verify-source (mutate source) trust))) (name label))))
    (is (false? (:valid? (provenance/verify-source source (fx/trust-policy kp :other-role :active))))
        "an active wrong-role key is not a source authority")
    (is (false? (:valid? (provenance/verify-source source (fx/trust-policy (fx/keypair :other) provenance/source-role :active))))
        "an untrusted signer is rejected")))

(deftest persisted-input-must-be-the-authorized-source-projection
  (let [kp (fx/keypair :allocation-source-test)
        trust (fx/trust-policy kp provenance/source-role :active)
        dir (.toFile (java.nio.file.Files/createTempDirectory "source-provenance" (make-array java.nio.file.attribute.FileAttribute 0)))
        source-file (io/file dir provenance/source-file-name)
        input-file (io/file dir "realized-statement-input.json")
        source (signed-source kp)
        _ (spit source-file (json/write-str (source-wire source)))
        _ (spit input-file (json/write-str (provenance/project-realized-input source)))]
    (is (:valid? (provenance/verify-source-to-input (.getPath source-file) (.getPath input-file) trust)))
    (spit input-file (json/write-str (assoc (provenance/project-realized-input source) "available" "99")))
    (is (= :projected-input-mismatch
           (:reason (provenance/verify-source-to-input (.getPath source-file) (.getPath input-file) trust))))))
