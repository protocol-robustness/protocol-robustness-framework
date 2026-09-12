(ns resolver-sim.allocation.proof-artifact-verify-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [buddy.core.codecs :as codecs]
            [resolver-sim.allocation.proof-admission :as admission]
            [resolver-sim.allocation.proof-artifact-verify :as verify]))

(defn- sha256-bytes-ref [bs]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")]
    (.update digest bs)
    (str "sha256:" (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest digest))))))

(deftest persisted-bundle-requires-proof-file-hash
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "artifact-verify" (make-array java.nio.file.attribute.FileAttribute 0)))
        proof (java.io.File. dir "proof.sp1-proof.bin")
        public-values-bytes (byte-array 32)
        digest (sha256-bytes-ref public-values-bytes)
        _ (spit proof "proof-bytes")
        artifact (admission/build-proof-artifact
                  {:proof/schema-version admission/proof-artifact-schema
                   :proof/profile admission/proof-profile
                   :statement/schema-version admission/statement-version
                   :statement/root "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                   :program/id "test" :program/elf-sha256 "sha256:aaaa" :program/vkey "0xtest"
                   :public-values/schema :evm-bytes32-v1
                   :public-values/bytes32 (str "0x" (codecs/bytes->hex public-values-bytes))
                   :public-values/sha256 digest
                   :proof/encoding "sp1-bincode.v1" :proof/file "proof.sp1-proof.bin"
                   :proof/sha256 "sha256:bbbb"})
        artifact-file (java.io.File. dir "artifact.json")
        wire {"schema_version" (:proof/schema-version artifact)
              "proof_profile" (subs (str (:proof/profile artifact)) 1)
              "statement_schema_version" (:statement/schema-version artifact)
              "statement_root" (:statement/root artifact)
              "program_id" (:program/id artifact)
              "program_elf_sha256" (:program/elf-sha256 artifact)
              "program_vkey" (:program/vkey artifact)
              "public_values_schema" (subs (str (:public-values/schema artifact)) 1)
              "public_values_bytes32" (:public-values/bytes32 artifact)
              "public_values_sha256" (:public-values/sha256 artifact)
              "proof_encoding" (:proof/encoding artifact) "proof_file" (:proof/file artifact)
              "proof_sha256" (:proof/sha256 artifact)
              "proof_artifact_hash" (:proof/artifact-hash artifact)}]
    (spit artifact-file (json/write-str wire))
    (is (false? (:valid? (verify/verify! (.getPath artifact-file))))
        "a proof artifact without independently persisted realization input is not admissible")
    (spit proof "tampered")
    (is (false? (:valid? (verify/verify! (.getPath artifact-file)))))))

(deftest persisted-input-reconstructs-and-tampering-fails-closed
  (let [source "results/allocation/a-vs-b-plus-c/realized-statement"
        dir (.toFile (java.nio.file.Files/createTempDirectory "gate-a-bundle" (make-array java.nio.file.attribute.FileAttribute 0)))
        copy! (fn [name binary?]
                (let [from (io/file source name)
                      to (io/file dir name)]
                  (if binary?
                    (java.nio.file.Files/write (.toPath to) (java.nio.file.Files/readAllBytes (.toPath from)) (make-array java.nio.file.OpenOption 0))
                    (spit to (slurp from)))))
        _ (copy! "sp1-proof-artifact.json" false)
        _ (copy! "sp1-proof-artifact.sp1-proof.bin" true)
        _ (copy! "realized-statement-input.json" false)
        artifact (.getPath (io/file dir "sp1-proof-artifact.json"))
        input (io/file dir "realized-statement-input.json")]
    (is (:valid? (verify/verify! artifact)))
    (spit input (clojure.string/replace (slurp input) "\"available\": \"100\"" "\"available\": \"99\""))
    (let [result (verify/verify! artifact)]
      (is (false? (:valid? result)))
      (is (= :statement-root-mismatch (:reason result))))))
