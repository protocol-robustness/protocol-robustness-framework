(ns resolver-sim.allocation.proof-verifier-issuer
  "Issue a trusted verifier receipt only after independently re-verifying a
   persisted realized-statement bundle. Private keys are loaded from an explicit
   external path; this namespace neither generates nor enrolls keys."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [resolver-sim.allocation.persisted-statement-admission :as persisted]
            [resolver-sim.allocation.proof-admission :as admission]
            [resolver-sim.allocation.proof-artifact-verify :as bundle]
            [resolver-sim.benchmark.signing :as signing]))

(def receipt-file-name "realized-allocation-proof-verification.json")

(defn- artifact-from [artifact-path]
  (let [ingested (admission/ingest-proof-artifact-json (slurp artifact-path))]
    (when-not (:valid? ingested)
      (throw (ex-info "invalid persisted proof artifact" ingested)))
    (:artifact ingested)))

(defn- run-verifier! [verifier-bin artifact-path]
  (let [{:keys [exit out err]} (shell/sh verifier-bin "--artifact" artifact-path)]
    (when-not (zero? exit)
      (throw (ex-info "independent SP1 verifier failed" {:exit exit :stderr err})))
    (try (json/read-str out :key-fn identity)
         (catch Exception _
           (throw (ex-info "independent verifier emitted malformed JSON" {:stdout out}))))))

(defn- decision-matches-artifact? [artifact decision]
  (= {"verification_schema_version" admission/verifier-receipt-schema
      "verification_verdict" "verified"
      "proof_profile" (subs (str (:proof/profile artifact)) 1)
      "statement_root" (:statement/root artifact)
      "program_id" (:program/id artifact)
      "program_elf_sha256" (:program/elf-sha256 artifact)
      "program_vkey" (:program/vkey artifact)
      "public_values_sha256" (:public-values/sha256 artifact)
      "proof_sha256" (:proof/sha256 artifact)}
     (select-keys decision ["verification_schema_version" "verification_verdict"
                            "proof_profile" "statement_root" "program_id"
                            "program_elf_sha256" "program_vkey"
                            "public_values_sha256" "proof_sha256"])))

(defn receipt->wire [receipt]
  {"verification_schema_version" (:verification/schema-version receipt)
   "verification_verdict" (name (:verification/verdict receipt))
   "proof_artifact_hash" (:proof/artifact-hash receipt)
   "proof_profile" (subs (str (:proof/profile receipt)) 1)
   "statement_root" (:statement/root receipt)
   "program_id" (:program/id receipt)
   "program_elf_sha256" (:program/elf-sha256 receipt)
   "program_vkey" (:program/vkey receipt)
   "public_values_sha256" (:public-values/sha256 receipt)
   "proof_sha256" (:proof/sha256 receipt)
   "persisted_input_sha256" (:persisted-input/sha256 receipt)
   "verifier_id" (subs (str (:verifier/id receipt)) 1)
   "verifier_version" (:verifier/version receipt)
   "signature" {"schema_version" (get-in receipt [:signature :schema-version])
                "key_id" (subs (str (get-in receipt [:signature :key-id])) 1)
                "algorithm" (name (get-in receipt [:signature :algorithm]))
                "signed_hash" (get-in receipt [:signature :signed-hash])
                "signature_encoding" (name (get-in receipt [:signature :signature-encoding]))
                "signature_bytes" (get-in receipt [:signature :signature-bytes])}})

(defn issue!
  "Issue a signed receipt from a persisted bundle. `verifier-bin` is an
   independently deployed/compiled verifier executable. Every signed fact is
   derived here from the bundle and verifier output, never accepted as a
   caller-provided assertion."
  [{:keys [artifact-path verifier-bin private-key key-id trust-policy]}]
  (let [bundle-result (bundle/verify! artifact-path)
        _ (when-not (:valid? bundle-result)
            (throw (ex-info "persisted bundle is not admissible for issuance" bundle-result)))
        artifact (artifact-from artifact-path)
        input-result (persisted/verify-persisted-input artifact-path artifact)
        decision (run-verifier! verifier-bin artifact-path)
        _ (when-not (decision-matches-artifact? artifact decision)
            (throw (ex-info "independent verifier decision does not match artifact" {:decision decision})))
        receipt (admission/build-verifier-receipt
                 {:artifact artifact
                  :persisted-input-sha256 (:input-sha256 input-result)
                  :verifier-id (keyword (get decision "verifier_id"))
                  :verifier-version (get decision "verifier_version")
                  :verdict :verified})
        signed (admission/sign-verifier-receipt receipt private-key key-id)
        verified (admission/verify-verifier-receipt artifact signed trust-policy)]
    (when-not (:valid? verified)
      (throw (ex-info "configured key is not an active allocation proof verifier" verified)))
    signed))

(defn -main [& args]
  (let [[artifact-path verifier-bin private-key-path key-id-text trust-policy-path output-path] args]
    (when-not (every? some? [artifact-path verifier-bin private-key-path key-id-text trust-policy-path output-path])
      (throw (ex-info "usage: <artifact> <verifier-bin> <private-key-path> <key-id> <trust-policy.edn> <output>" {})))
    (let [receipt (issue! {:artifact-path artifact-path
                           :verifier-bin verifier-bin
                           :private-key (signing/load-private-key! private-key-path nil)
                           :key-id (keyword key-id-text)
                           :trust-policy (edn/read-string (slurp trust-policy-path))})]
      (spit output-path (json/write-str (receipt->wire receipt)))
      (println output-path))))
