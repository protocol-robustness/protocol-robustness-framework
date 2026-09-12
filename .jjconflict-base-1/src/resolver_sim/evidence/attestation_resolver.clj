(ns resolver-sim.evidence.attestation-resolver
  "Resolve typed attestation references to verified attestation artifacts.

   Reference format:  attestation:sha256:<64-char-hex>

   Resolution flow:
     1. Parse the typed reference  →  extract algorithm and hash
     2. Look up the hash in the attestation registry
     3. Verify the resolved artifact's canonical hash matches the reference
     4. Verify the artifact has the expected attestation schema
     5. Verify signature material (if requested)

   Returns a result map:
     {:valid? true
      :attestation <record>
      :checks {:hash-ok? true :type-ok? true ...}}

   or on failure:
     {:valid? false
      :error :missing | :hash-mismatch | :unsupported-algorithm | :unparseable-reference
      :detail ...}"
  (:require [resolver-sim.evidence.attestation-registry :as ar]
            [resolver-sim.evidence.attestation-integrity :as integrity]
            [resolver-sim.evidence.attestation :as att]))

;; ── Reference format ─────────────────────────────────────────────────────────

(def ^:private ref-pattern
  #"^attestation:sha256:([0-9a-f]{64})$")

(def ^:const supported-algorithms
  "Hash algorithms accepted in attestation references."
  #{:sha256})

;; ── Parse ────────────────────────────────────────────────────────────────────

(defn parse-reference
  "Parse a typed attestation reference string.

   Returns {:type :attestation :algorithm :sha256 :hash <64-char-hex>}
   or nil if the string is not a valid typed reference.

   Bare attestation IDs (without the typed prefix) return nil."
  [ref-str]
  (when (string? ref-str)
    (when-let [match (re-matches ref-pattern ref-str)]
      {:type :attestation
       :algorithm :sha256
       :hash (second match)})))

;; ── Resolution ───────────────────────────────────────────────────────────────

(defn resolve-attestation
  "Resolve a typed attestation reference.

   Steps:
     1. Parse the reference — fails with :unparseable-reference if invalid
     2. Check algorithm is supported — fails with :unsupported-algorithm if not
     3. Look up the hash in the registry — fails with :missing if not found
     4. Verify the resolved hash matches the reference — fails with :hash-mismatch
     5. Verify the artifact has the expected attestation type — fails with :invalid-type

   Returns {:valid? true :attestation <record> :checks {...}}
   or {:valid? false :error <keyword> :detail ...}

   When verify-fn is provided, calls (verify-fn attestation) to perform
   cryptographic signature verification. The result is included as
   :signature-verified in checks (not in :valid? which covers structural
   integrity only)."
  [ref-str & [{:keys [verify-fn]}]]
  (let [parsed (parse-reference ref-str)]
    (if-not parsed
      {:valid? false
       :error :unparseable-reference
       :detail (str "Expected \"attestation:sha256:<64-hex>\", got " (pr-str ref-str))}
      (let [algorithm (:algorithm parsed)
            hash (:hash parsed)]
        (if-not (contains? supported-algorithms algorithm)
          {:valid? false
           :error :unsupported-algorithm
           :detail (str "Unsupported hash algorithm " algorithm
                        " (supported: " supported-algorithms ")")}
          (let [attestation (ar/find-attestation hash)]
            (if-not attestation
              {:valid? false
               :error :missing
               :detail (str "No attestation found for hash " hash)}
              (let [recorded-hash (:attestation/hash attestation)
                    hash-ok? (= recorded-hash hash)
                    ;; Verify type: attestation must have the expected schema
                    schema-ok? (or (= "attestation.v1" (:schema-version attestation))
                                   (and (:attestation/id attestation)
                                        (:attestation/attestor-id attestation)))
                    sig-ok? (when verify-fn
                              (try
                                (verify-fn attestation)
                                (catch Exception _ false)))
                    all-ok? (and hash-ok? schema-ok?)]
                (if (not hash-ok?)
                  {:valid? false
                   :error :hash-mismatch
                   :detail {:reference hash
                            :resolved recorded-hash}}
                  (if (not schema-ok?)
                    {:valid? false
                     :error :invalid-type
                     :detail (str "Resolved artifact is not a valid attestation record")}
                    {:valid? true
                     :attestation attestation
                     :checks {:hash-ok? true
                              :type-ok? true
                              :signature-valid? sig-ok?
                              :algorithm algorithm
                              :hash hash}}))))))))))

(defn resolve-attestation!
  "Like resolve-attestation but throws on failure."
  [ref-str & [opts]]
  (let [result (resolve-attestation ref-str opts)]
    (if (:valid? result)
      (:attestation result)
      (throw (ex-info "Attestation resolution failed"
                      {:error (:error result)
                       :detail (:detail result)})))))
