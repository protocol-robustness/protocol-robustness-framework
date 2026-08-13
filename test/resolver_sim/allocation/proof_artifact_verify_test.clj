(ns resolver-sim.allocation.proof-artifact-verify-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.data.json :as json]
            [resolver-sim.allocation.proof-admission :as admission]
            [resolver-sim.allocation.proof-artifact-verify :as verify]))

(deftest persisted-bundle-requires-proof-file-hash
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "artifact-verify" (make-array java.nio.file.attribute.FileAttribute 0)))
        proof (java.io.File. dir "proof.sp1-proof.bin")
        public "{\"result/status\":\"passing\"}"
        digest (fn [s] (let [d (java.security.MessageDigest/getInstance "SHA-256")]
                         (.update d (.getBytes s "UTF-8"))
                         (str "sha256:" (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest d))))))
        _ (spit proof "proof-bytes")
        artifact (admission/build-proof-artifact
                  {:proof/schema-version admission/proof-artifact-schema
                   :proof/profile admission/proof-profile
                   :statement/schema-version admission/statement-version
                   :statement/root "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                   :program/id "test" :program/elf-sha256 (digest "elf") :program/vkey "0xtest"
                   :public-values/schema :utf8-json-v1 :public-values/utf8-json public
                   :public-values/sha256 (digest public)
                   :proof/encoding "sp1-bincode.v1" :proof/file "proof.sp1-proof.bin"
                   :proof/sha256 (digest "proof-bytes")})
        artifact-file (java.io.File. dir "artifact.json")
        wire {"schema_version" (:proof/schema-version artifact)
              "proof_profile" (subs (str (:proof/profile artifact)) 1)
              "statement_schema_version" (:statement/schema-version artifact)
              "statement_root" (:statement/root artifact)
              "program_id" (:program/id artifact)
              "program_elf_sha256" (:program/elf-sha256 artifact)
              "program_vkey" (:program/vkey artifact)
              "public_values_schema" (subs (str (:public-values/schema artifact)) 1)
              "public_values_utf8_json" (:public-values/utf8-json artifact)
              "public_values_sha256" (:public-values/sha256 artifact)
              "proof_encoding" (:proof/encoding artifact) "proof_file" (:proof/file artifact)
              "proof_sha256" (:proof/sha256 artifact)
              "proof_artifact_hash" (:proof/artifact-hash artifact)}]
    (spit artifact-file (json/write-str wire))
    (is (:valid? (verify/verify! (.getPath artifact-file))))
    (spit proof "tampered")
    (is (false? (:valid? (verify/verify! (.getPath artifact-file)))))))
