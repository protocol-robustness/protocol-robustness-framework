(ns resolver-sim.allocation.proof-admission
  "Fail-closed admission checks for the narrow realized-allocation SP1 profile.

   This namespace deliberately does not verify SP1 proof bytes: no local SP1
   verifier artifact is currently available to Clojure. It verifies the
   *application-side identity contract* that a cryptographic verifier must
   establish before a claim may be graduated. A caller-supplied `:verified?`
   flag is therefore never sufficient."
  (:require [clojure.data.json :as json]
            [buddy.core.codecs :as codecs]
            [resolver-sim.allocation.realized-statement :as statement]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.signed-external-decision :as sed]
            [resolver-sim.conformance.json :as strict-json])
  (:import [java.security MessageDigest]))

(def proof-profile :allocation-proof/largest-remainder-deferred-pro-rata.v1)
(def statement-version statement/schema-version)

(def assurance-levels
  #{:assurance/evidence
    :assurance/cryptographic-computation
    :assurance/cryptographic-activation
    :assurance/effect-bound})

(def proof-artifact-schema "realized-allocation-proof.v1")
(def verifier-receipt-schema "realized-allocation-proof-verification.v1")
(def ^:private proof-artifact-domain "REALIZED_ALLOCATION_PROOF_V1")
(def ^:private verifier-receipt-domain "REALIZED_ALLOCATION_PROOF_VERIFICATION_V1")

(defn- sha256-ref? [value]
  (boolean (and (string? value)
                (re-matches #"sha256:[0-9a-f]{64}" value))))

(defn- sha256-utf8-ref [s]
  (when (string? s)
    (let [digest (MessageDigest/getInstance "SHA-256")]
      (.update digest (.getBytes s "UTF-8"))
      (str "sha256:" (codecs/bytes->hex (.digest digest))))))

(defn- sha256-bytes-ref [bytes]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (.update digest bytes)
    (str "sha256:" (codecs/bytes->hex (.digest digest)))))

(defn proof-artifact-preimage
  "Identity projection for one SP1 proof of one statement. Proof bytes are not
   interpreted by Clojure; their hash is bound to the verifier receipt. The
   public projection is included so Clojure can independently validate exactly
   what the SP1 guest committed."
  [artifact]
  (select-keys artifact [:proof/schema-version :proof/profile
                         :statement/schema-version :statement/root
                         :program/id :program/elf-sha256 :program/vkey
                         :public-values/schema :public-values/utf8-json
                         :public-values/sha256 :proof/encoding :proof/file :proof/sha256]))

(defn proof-artifact-hash [artifact]
  (hash-ref/sha256-ref
   (hc/domain-hash proof-artifact-domain (proof-artifact-preimage artifact))))

(defn build-proof-artifact
  "Build an unsigned, self-addressed proof artifact. It is evidence, not
   verification authority: a separate trusted verifier receipt is required."
  [artifact]
  (assoc artifact :proof/artifact-hash (proof-artifact-hash artifact)))

(defn valid-proof-artifact?
  [artifact]
  (and (map? artifact)
       (= proof-artifact-schema (:proof/schema-version artifact))
       (= proof-profile (:proof/profile artifact))
       (= statement-version (:statement/schema-version artifact))
       (string? (:statement/root artifact))
       (string? (:program/id artifact))
       (sha256-ref? (:program/elf-sha256 artifact))
       (string? (:program/vkey artifact))
       (= :utf8-json-v1 (:public-values/schema artifact))
       (string? (:public-values/utf8-json artifact))
       (= (:public-values/sha256 artifact)
          (sha256-utf8-ref (:public-values/utf8-json artifact)))
       (sha256-ref? (:public-values/sha256 artifact))
       (= "sp1-bincode.v1" (:proof/encoding artifact))
       (string? (:proof/file artifact))
       (sha256-ref? (:proof/sha256 artifact))
       (= (:proof/artifact-hash artifact) (proof-artifact-hash artifact))))

(defn- strict-json-map [raw]
  (cond
    (not (string? raw)) {:error :not-json-text}
    (strict-json/duplicate-json-key raw) {:error :duplicate-json-key}
    (strict-json/nesting-too-deep? raw) {:error :nesting-too-deep}
    :else (try {:value (json/read-str raw :key-fn keyword)}
               (catch Exception _ {:error :malformed-json}))))

(defn ingest-proof-artifact-json
  "Strictly ingest persisted artifact JSON. Raw bytes are parsed only after
   duplicate-key/depth checks, then content hashes are recomputed. The proof
   file itself is verified by `verify-proof-file!` before verifier admission."
  [raw]
  (let [{:keys [value error]} (strict-json-map raw)]
    (if error {:valid? false :reason error}
        (let [artifact {:proof/schema-version (:schema_version value)
                        :proof/profile (some-> (:proof_profile value) keyword)
                        :statement/schema-version (:statement_schema_version value)
                        :statement/root (:statement_root value)
                        :program/id (:program_id value)
                        :program/elf-sha256 (:program_elf_sha256 value)
                        :program/vkey (:program_vkey value)
                        :public-values/schema (some-> (:public_values_schema value) keyword)
                        :public-values/utf8-json (:public_values_utf8_json value)
                        :public-values/sha256 (:public_values_sha256 value)
                        :proof/encoding (:proof_encoding value)
                        :proof/file (:proof_file value)
                        :proof/sha256 (:proof_sha256 value)}
              derived (build-proof-artifact artifact)
              stored (:proof_artifact_hash value)
              artifact (assoc derived :proof/artifact-hash (or stored (:proof/artifact-hash derived)))]
          {:valid? (and (or (nil? stored) (= stored (:proof/artifact-hash derived)))
                        (valid-proof-artifact? artifact))
           :reason (cond
                     (and stored (not= stored (:proof/artifact-hash derived))) :artifact-hash-mismatch
                     (not (valid-proof-artifact? artifact)) :artifact-integrity-mismatch)
           :artifact artifact}))))

(defn verify-proof-file!
  "Verify that the persisted proof bytes named by an already-ingested artifact
   exist beneath `artifact-dir` and reproduce :proof/sha256. File names must
   be a single relative name, preventing an artifact from escaping its bundle."
  [artifact-dir artifact]
  (let [file-name (:proof/file artifact)
        file (when (and (string? file-name)
                        (= file-name (.getName (java.io.File. file-name))))
               (java.io.File. artifact-dir file-name))]
    (and (valid-proof-artifact? artifact)
         file (.isFile file)
         (= (:proof/sha256 artifact)
            (sha256-bytes-ref (java.nio.file.Files/readAllBytes (.toPath file)))))))

(defn ingest-verifier-receipt-json
  "Strictly ingest a persisted verifier receipt. JSON is normalized to the
   receipt contract and cannot nominate a trusted map: callers must still pass
   the result to `verify-verifier-receipt` with an external trust policy."
  [raw]
  (let [{:keys [value error]} (strict-json-map raw)]
    (if error {:valid? false :reason error}
        (let [signature (:signature value)
              receipt {:verification/schema-version (:verification_schema_version value)
                       :verification/verdict (some-> (:verification_verdict value) keyword)
                       :proof/artifact-hash (:proof_artifact_hash value)
                       :proof/profile (some-> (:proof_profile value) keyword)
                       :statement/root (:statement_root value)
                       :program/id (:program_id value)
                       :program/elf-sha256 (:program_elf_sha256 value)
                       :program/vkey (:program_vkey value)
                       :public-values/sha256 (:public_values_sha256 value)
                       :proof/sha256 (:proof_sha256 value)
                       :persisted-input/sha256 (:persisted_input_sha256 value)
                       :verifier/id (some-> (:verifier_id value) keyword)
                       :verifier/version (:verifier_version value)
                       :signature {:schema-version (:schema_version signature)
                                   :key-id (some-> (:key_id signature) keyword)
                                   :algorithm (some-> (:algorithm signature) keyword)
                                   :signed-hash (:signed_hash signature)
                                   :signature-encoding (some-> (:signature_encoding signature) keyword)
                                   :signature-bytes (:signature_bytes signature)}}
              required [:verification/schema-version :verification/verdict
                        :proof/artifact-hash :proof/profile :statement/root
                        :program/id :program/elf-sha256 :program/vkey
                        :public-values/sha256 :proof/sha256 :persisted-input/sha256
                        :verifier/id :verifier/version]]
          {:valid? (and (every? #(some? (get receipt %)) required)
                        (= verifier-receipt-schema (:verification/schema-version receipt))
                        (contains? #{:verified :rejected} (:verification/verdict receipt))
                        (map? signature))
           :reason (when-not (and (every? #(some? (get receipt %)) required)
                                  (= verifier-receipt-schema (:verification/schema-version receipt))
                                  (contains? #{:verified :rejected} (:verification/verdict receipt))
                                  (map? signature))
                     :invalid-receipt-shape)
           :receipt receipt}))))

(defn verifier-receipt-preimage
  "The signed authority decision over one exact proof artifact. Trust comes
   from the externally supplied policy passed to `verify-verifier-receipt`,
   never from a key or policy carried by this receipt."
  [receipt]
  (select-keys receipt [:verification/schema-version :verification/verdict
                        :proof/artifact-hash :proof/profile :statement/root
                        :program/id :program/elf-sha256 :program/vkey
                        :public-values/sha256 :proof/sha256 :persisted-input/sha256
                        :verifier/id :verifier/version]))

(defn build-verifier-receipt
  "Build an unsigned verifier decision. Only an external verifier process that
   has actually run SP1 verification may issue a :verified receipt; this
   function deliberately does not accept a caller-provided `:verified?` flag."
  [{:keys [artifact persisted-input-sha256 verifier-id verifier-version verdict]}]
  {:verification/schema-version verifier-receipt-schema
   :verification/verdict verdict
   :proof/artifact-hash (:proof/artifact-hash artifact)
   :proof/profile (:proof/profile artifact)
   :statement/root (:statement/root artifact)
   :program/id (:program/id artifact)
   :program/elf-sha256 (:program/elf-sha256 artifact)
   :program/vkey (:program/vkey artifact)
   :public-values/sha256 (:public-values/sha256 artifact)
   :proof/sha256 (:proof/sha256 artifact)
   :persisted-input/sha256 persisted-input-sha256
   :verifier/id verifier-id
   :verifier/version verifier-version})

(defn sign-verifier-receipt [receipt private-key key-id]
  (sed/sign-envelope (verifier-receipt-preimage receipt)
                     verifier-receipt-domain private-key key-id))

(defn verify-verifier-receipt
  "Verify receipt signature and every binding to an artifact. `trust-policy`
   must independently name active :allocation-proof-verifier keys."
  [artifact receipt trust-policy]
  (let [expected (verifier-receipt-preimage
                  (build-verifier-receipt {:artifact artifact
                                           :persisted-input-sha256 (:persisted-input/sha256 receipt)
                                           :verifier-id (:verifier/id receipt)
                                           :verifier-version (:verifier/version receipt)
                                           :verdict (:verification/verdict receipt)}))
        signature-result (sed/verify-envelope receipt verifier-receipt-domain
                                              trust-policy :allocation-proof-verifier)]
    (cond
      (not (valid-proof-artifact? artifact)) {:valid? false :reason :invalid-proof-artifact}
      (not= expected (verifier-receipt-preimage receipt)) {:valid? false :reason :receipt-binding-mismatch}
      (not (:valid? signature-result)) (assoc signature-result :valid? false)
      (not= :verified (:verification/verdict receipt)) {:valid? false :reason :verifier-rejected-proof}
      :else {:valid? true :key-id (:key-id signature-result)})))

(defn approved-program?
  "Registry shape: {profile {:program/id .. :program/elf-sha256 .. :program/vkey
   .. :statement/schema-version .. :public-values/schema ..}}. The registry is
   a verifier/admission policy input, never proof-supplied configuration."
  [program-registry artifact]
  (= (get program-registry (:proof/profile artifact))
     (select-keys artifact [:program/id :program/elf-sha256 :program/vkey
                            :statement/schema-version :public-values/schema])))

(defn public-values-match?
  "Validate the guest's passing public projection against the exact statement.
   This checks semantic projection fields; the verifier receipt separately binds
   the raw UTF-8 JSON byte hash that SP1 actually verified."
  [artifact statement]
  (let [public (try (json/read-str (:public-values/utf8-json artifact))
                    (catch Exception _ nil))]
    (and (map? public)
         (= "passing" (get public "result/status"))
         (= statement-version (get public "schema-version"))
         (= (:statement/root statement) (:statement/root artifact)
            (get public "statement-root"))
         (every? #(= (get statement %) (get public (name %)))
                 [:allocation-context-root :request-set-root :allocation-policy-root
                  :realized-results-root :fail-action-policy-root :round-lifecycle-root]))))

(defn statement-proof-coverage
  "Canonical per-statement proof coverage. A scenario collection is covered
   only when every statement root has exactly one artifact/receipt pair; one
   proof cannot stand in for a multi-statement collection."
  [statements admissions]
  (let [roots (set (map :statement/root statements))
        by-root (group-by (comp :statement/root :artifact) admissions)]
    {:complete? (and (= roots (set (keys by-root)))
                     (every? #(= 1 (count %)) (vals by-root)))
     :statement-roots roots
     :covered-roots (set (keys by-root))}))

(defn supported-decision?
  "True only for the semantic subset independently realized by the current
   Rust/SP1 guest: pro-rata, largest-remainder, deferred-only, without
   effective per-claim caps or redistribution rows. This is intentionally
   stricter than the Clojure fairness evaluator; unsupported is not failure of
   fairness, but is uncovered for cryptographic admission."
  [decision]
  (let [policy (:policy decision)
        rows (get-in decision [:evidence :allocation-rows] [])
        positive? (fn [amounts] (some pos? (map #(long (or % 0)) (vals amounts))))]
    (and (= :pro-rata (:mode policy))
         (= :largest-remainder (:rounding-policy policy))
         (not (positive? (:haircut decision)))
         ;; A row-level cap is an effective semantic constraint even if it is
         ;; non-binding in this particular allocation; the narrow profile has
         ;; no independently specified cap/saturation/redistribution semantics.
         (not-any? #(or (contains? % :cap)
                        (contains? % :effective-cap)) rows)
         (not-any? #(contains? % :redistribution) rows))))

(defn proof-profile-result
  "Classify a decision for the current proof profile without trusting a label."
  [decision]
  (if (supported-decision? decision)
    {:status :supported :proof/profile proof-profile}
    {:status :uncovered
     :proof/profile proof-profile
     :reason :unsupported-realization-regime}))

(defn scenario-statement-binding-preimage
  "Canonical application-layer relation between a scenario evidence-content
   root and the collection of statement roots it reports. SP1 does not prove
   this relation; application admission recomputes it independently."
  [{:keys [scenario-id evidence-content-root statements-root]}]
  {:binding/schema-version "scenario-realized-statement-binding.v1"
   :scenario/id scenario-id
   :scenario/evidence-content-root evidence-content-root
   :realized-allocation-statements-root statements-root})

(defn scenario-statement-binding-root
  [binding]
  (hc/domain-hash :scenario-evidence-binding
                  (scenario-statement-binding-preimage binding)))

(defn valid-scenario-statement-binding?
  "Require complete inputs and a recomputing canonical binding root."
  [{:keys [scenario-id evidence-content-root statements-root binding-root] :as binding}]
  (and (some? scenario-id)
       (string? evidence-content-root)
       (string? statements-root)
       (= binding-root (scenario-statement-binding-root binding))))

(defn recompute-statement
  "Rebuild a statement from canonical decision/context/lifecycle inputs.
   The caller must supply the original inputs; a bare root is never evidence of
   recomputation."
  [{:keys [allocation-context decision round-lifecycle]}]
  (when (and allocation-context decision round-lifecycle)
    (statement/build-statement {:ctx allocation-context
                                :decision decision
                                :round-lifecycle round-lifecycle})))

(defn statement-match?
  "Verify that a supplied statement is the independently rebuilt statement for
   the supplied inputs and belongs to the supported proof profile."
  [{:keys [statement allocation-context decision round-lifecycle]}]
  (let [rebuilt (recompute-statement {:allocation-context allocation-context
                                      :decision decision
                                      :round-lifecycle round-lifecycle})]
    (and (= :supported (:status (proof-profile-result decision)))
         (map? statement)
         (= statement-version (:schema-version statement))
         (= (:statement/root statement) (:statement/root rebuilt))
         (= (select-keys statement [:allocation-context-root :request-set-root
                                    :allocation-policy-root :realized-results-root
                                    :fail-action-policy-root :round-lifecycle-root])
            (select-keys rebuilt [:allocation-context-root :request-set-root
                                  :allocation-policy-root :realized-results-root
                                  :fail-action-policy-root :round-lifecycle-root])))))

(defn persisted-bundle-receipt-admitted?
  "Verify a signed receipt against an already independently verified persisted
   bundle result. The receipt must bind the exact reconstructed input bytes as
   well as the artifact identity; this prevents receipt substitution across
   bundles that share a proof artifact name or statement label."
  [artifact receipt trust-policy persisted-input-result]
  (and (:valid? persisted-input-result)
       (= (:persisted-input/sha256 receipt) (:input-sha256 persisted-input-result))
       (:valid? (verify-verifier-receipt artifact receipt trust-policy))))

(defn cryptographic-computation-admitted?
  "Fail-closed cryptographic-computation admission for one statement.

   Required input: {:artifact .. :receipt .. :trust-policy .. :program-registry
   .. :statement ..}. The signed external verifier receipt is the verification
   authority. Neither an artifact, a self-consistent unsigned receipt, nor a
   caller-provided `:verified?` field can pass this boundary."
  [{:keys [artifact receipt trust-policy program-registry statement
           allocation-context decision round-lifecycle]}]
  (and (statement-match? {:statement statement
                          :allocation-context allocation-context
                          :decision decision
                          :round-lifecycle round-lifecycle})
       ;; Canonical inputs are supplied by the independently verified scenario
       ;; bundle, never recovered from the proof artifact.
       (approved-program? program-registry artifact)
       (public-values-match? artifact statement)
       (:valid? (verify-verifier-receipt artifact receipt trust-policy))))
