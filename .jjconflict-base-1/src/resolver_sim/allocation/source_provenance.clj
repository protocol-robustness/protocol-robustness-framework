(ns resolver-sim.allocation.source-provenance
  "Authority and deterministic projection for a realized-allocation source.

   The source is deliberately upstream of the SP1 input: it owns allocation
   facts, while the sibling realized-statement input is only its projection.
   A source hash alone is not authority; admission requires a signature by an
   active :allocation-source-authority key supplied in external trust policy."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [resolver-sim.allocation.persisted-statement-admission :as persisted]
            [resolver-sim.conformance.json :as strict-json]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.signed-external-decision :as sed])
  (:import [java.nio.file Files]))

(def ^:const source-schema "realized-allocation-source.v1")
(def ^:const source-domain "REALIZED_ALLOCATION_SOURCE_V1")
(def ^:const source-role :allocation-source-authority)
(def ^:const source-file-name "realized-allocation-source.json")

(defn source-preimage [source]
  (select-keys source [:source/schema-version :source/id :source/scope
                       :allocation-context :available :requested :policy
                       :fail-action-policy :round-state]))

(defn source-root [source]
  (hash-ref/sha256-ref (hc/domain-hash source-domain (source-preimage source))))

(defn build-source [source]
  (assoc source :source/root (source-root source)))

(defn valid-source? [source]
  (and (map? source)
       (= source-schema (:source/schema-version source))
       (string? (:source/id source))
       (= :fixed-scenario-test-vector (:source/scope source))
       (map? (:allocation-context source))
       (some? (:available source))
       (map? (:requested source))
       (map? (:policy source))
       (map? (:fail-action-policy source))
       (some? (:round-state source))
       (= (:source/root source) (source-root source))))

(defn sign-source [source private-key key-id]
  (sed/sign-envelope (assoc (source-preimage source) :source/root (:source/root source))
                     source-domain private-key key-id))

(defn verify-source [source trust-policy]
  (let [expected (assoc (source-preimage source) :source/root (source-root source))
        signature (sed/verify-envelope source source-domain trust-policy source-role)]
    (cond
      (not (valid-source? source)) {:valid? false :reason :invalid-source}
      (not= expected (dissoc source :signature)) {:valid? false :reason :source-binding-mismatch}
      (not (:valid? signature)) signature
      :else {:valid? true :key-id (:key-id signature)})))

(defn project-realized-input
  "Semantic realized-statement input projection. No downstream input may add or
   rename facts: all values are selected from the authority-signed source."
  [source]
  {"allocation-context" (:allocation-context source)
   "available" (:available source)
   "requested" (:requested source)
   "policy" (:policy source)
   "fail-action-policy" (:fail-action-policy source)
   "round-state" (:round-state source)})

(defn canonical-input-json [source]
  ;; The existing Gate-A input uses this exact compact JSON encoding. JSON object
  ;; order is fixed explicitly by ordered maps in the source fixture generator.
  (json/write-str (project-realized-input source)))

(defn input-sha256 [source]
  (persisted/sha256-bytes-ref (.getBytes (canonical-input-json source) "UTF-8")))

(defn strict-source-json [raw]
  (cond
    (not (string? raw)) {:valid? false :reason :not-json-text}
    (strict-json/duplicate-json-key raw) {:valid? false :reason :duplicate-json-key}
    (strict-json/nesting-too-deep? raw) {:valid? false :reason :nesting-too-deep}
    :else
    (try
      (let [v (json/read-str raw :key-fn identity)
            sig (get v "signature")
            source (build-source {:source/schema-version (get v "source_schema_version")
                                  :source/id (get v "source_id")
                                  :source/scope (some-> (get v "source_scope") keyword)
                                  :allocation-context (get v "allocation_context")
                                  :available (get v "available")
                                  :requested (get v "requested")
                                  :policy (get v "policy")
                                  :fail-action-policy (get v "fail_action_policy")
                                  :round-state (get v "round_state")})
            source (assoc source :source/root (or (get v "source_root") (:source/root source))
                          :signature {:schema-version (get sig "schema_version")
                                      :key-id (some-> (get sig "key_id") keyword)
                                      :algorithm (some-> (get sig "algorithm") keyword)
                                      :signed-hash (get sig "signed_hash")
                                      :signature-encoding (some-> (get sig "signature_encoding") keyword)
                                      :signature-bytes (get sig "signature_bytes")})]
        {:valid? (valid-source? source)
         :reason (when-not (valid-source? source) :invalid-source)
         :source source})
      (catch Exception _ {:valid? false :reason :malformed-json}))))

(defn verify-source-to-input
  "Fail-closed source-provenance boundary. It independently verifies source
   authority, derives the input, and compares parsed canonical values. This
   permits historical Gate-A input bytes to retain their already-receipted hash;
   newly emitted inputs use `canonical-input-json` and need no second editable
   source. The returned persisted digest is the exact bytes Gate A binds."
  [source-path persisted-input-path trust-policy]
  (try
    (let [ingested (strict-source-json (slurp source-path))
          source (:source ingested)
          authority (when (:valid? ingested) (verify-source source trust-policy))
          projected (when (:valid? authority) (project-realized-input source))
          actual-bytes (Files/readAllBytes (.toPath (io/file persisted-input-path)))
          actual (json/read-str (String. actual-bytes "UTF-8") :key-fn identity)]
      {:valid? (and (:valid? ingested) (:valid? authority) (= projected actual))
       :reason (cond
                 (not (:valid? ingested)) (:reason ingested)
                 (not (:valid? authority)) (:reason authority)
                 (not= projected actual) :projected-input-mismatch)
       :source-root (:source/root source)
       :projected-input-sha256 (when projected (input-sha256 source))
       :persisted-input-sha256 (persisted/sha256-bytes-ref actual-bytes)})
    (catch Exception e {:valid? false :reason :source-provenance-unreadable :detail (.getMessage e)})))
