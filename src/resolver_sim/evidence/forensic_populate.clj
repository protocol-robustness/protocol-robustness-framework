(ns resolver-sim.evidence.forensic-populate
  "Populate claims/ and attestations/ directories in the forensic workspace.
   Evaluates registered forensic claims and produces claim result + attestation
   files with self-referential SHA-256 naming, per FORENSIC_CLAIMS_SPEC_V1
   and FORENSIC_ATTESTATIONS_SPEC_V1."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [resolver-sim.evidence.chain :as chain]
            [resolver-sim.evidence.config :as evcfg]
            [resolver-sim.evidence.confidence :as confidence])
  (:import [java.security MessageDigest]
           [java.nio.file Files StandardCopyOption]
           [java.util UUID]))

(defn- sort-keys
  "Recursively sort map keys alphabetically for deterministic JSON serialization.
   Python's json.dumps(sort_keys=True) sorts all nested dict keys — this mirrors that."
  [x]
  (cond
    (map? x) (into (sorted-map-by compare)
                   (map (fn [[k v]] [k (sort-keys v)]) x))
    (vector? x) (mapv sort-keys x)
    :else x))

(defn- json-bytes
  "Serialize a map to UTF-8 JSON bytes with qualified keyword keys and
   alphabetically sorted keys (matching Python's sort_keys=True)."
  [m]
  (.getBytes (json/write-str (sort-keys m)
                             :key-fn (fn [k]
                                       (if (namespace k)
                                         (str (namespace k) "/" (name k))
                                         (name k)))
                             :indent true
                             :escape-slash false)
             "UTF-8"))

(defn- sha256-hex
  "Compute SHA-256 hex digest of a byte array."
  [^bytes ba]
  (let [digest (MessageDigest/getInstance "SHA-256")
        raw (.digest digest ba)]
    (apply str (map #(format "%02x" (bit-and % 0xff)) raw))))

(defn- json-string [value]
  (json/write-str (sort-keys value) :key-fn (fn [k]
                                              (if (namespace k)
                                                (str (namespace k) "/" (name k))
                                                (name k)))
                  :indent true :escape-slash false))

(defn- atomic-create-json!
  "Publish immutable full-hash JSON by atomic hard-link create-if-absent.
   A different object at the same full hash is a fail-closed collision."
  [dir full-hash value]
  (let [target (io/file dir (str full-hash ".json"))
        temp (io/file dir (str "." full-hash ".tmp-" (UUID/randomUUID)))
        content (json-string value)]
    (.mkdirs dir)
    (spit temp content)
    (try
      (Files/createLink (.toPath target) (.toPath temp))
      (catch java.nio.file.FileAlreadyExistsException _
        (when-not (= content (slurp target))
          (throw (ex-info "Forensic full-hash content collision"
                          {:reason :forensic-hash-content-collision
                           :hash full-hash :path (str target)}))))
      (finally (Files/deleteIfExists (.toPath temp))))
    {:path (.getPath target) :hash full-hash}))

(defn- atomic-replace-json!
  [file value]
  (let [target (io/file file)
        parent (.getParentFile target)
        temp (io/file parent (str "." (.getName target) ".tmp-" (UUID/randomUUID)))]
    (.mkdirs parent)
    (spit temp (json-string value))
    (Files/move (.toPath temp) (.toPath target)
                (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE
                                                StandardCopyOption/REPLACE_EXISTING]))
    (.getPath target)))

(defn write-claim-result!
  "Publish a full-hash immutable claim object under claims/sha256/."
  [{:keys [claim-id category status evaluated-at evidence-refs description
           assumptions falsified-if failure-detail confidence counterexamples inputs]
    :or {evaluated-at (str (java.time.Instant/now))}}]
  (let [artifact-root (str (evcfg/artifact-dir))
        claims-dir (io/file artifact-root "claims" "sha256")
        _ (.mkdirs claims-dir)
        base {:result/schema-version "forensic-claim-result.v1"
              :result/hash nil
              :result/claim-id claim-id
              :result/category category
              :result/status status
              :result/evaluated-at evaluated-at
              :result/evidence-refs (vec (or evidence-refs []))
              :result/description description
              :result/assumptions (vec (or assumptions []))
              :result/falsified-if falsified-if
              :result/failure-detail failure-detail
              :result/confidence confidence
              :result/counterexamples (vec (or counterexamples []))
              :result/inputs (or inputs {})}
        can-bytes (json-bytes (dissoc base :result/hash))
        result-hash (sha256-hex can-bytes)
        result (assoc base :result/hash result-hash)
        published (atomic-create-json! claims-dir result-hash result)]
    (println "  wrote" (:path published))
    (assoc published :result result)))

(defn write-attestation!
  "Publish a full-hash immutable attestation object under attestations/sha256/."
  [{:keys [subject-kind subject-hash claim-id claim-result attestor-id signed-at
           signing-key-id signature provenance metadata]
    :or {signed-at (str (java.time.Instant/now))}}]
  (let [artifact-root (str (evcfg/artifact-dir))
        att-dir (io/file artifact-root "attestations" "sha256")
        _ (.mkdirs att-dir)
        base {:attestation/schema-version "forensic-attestation.v1"
              :attestation/id nil
              :attestation/hash nil
              :attestation/subject-kind subject-kind
              :attestation/subject-hash subject-hash
              :attestation/claim-id claim-id
              :attestation/claim-result claim-result
              :attestation/attestor-id attestor-id
              :attestation/signed-at signed-at
              :attestation/signing-key-id signing-key-id
              :attestation/signature signature
              :attestation/provenance (or provenance {})
              :attestation/metadata (or metadata {})}
        can-bytes (json-bytes (dissoc base :attestation/id :attestation/hash :attestation/signature))
        att-hash (sha256-hex can-bytes)
        record (assoc base :attestation/id att-hash :attestation/hash att-hash)
        published (atomic-create-json! att-dir att-hash record)]
    (println "  wrote" (:path published))
    (assoc published :record record)))

(defn- criterion-evidence-refs
  "Return assertion-level evidence references for one forensic criterion.
   Paths are relative to the generated claims/ directory."
  [criterion detail]
  (case criterion
    :registry-hash-verifies
    [{:ref/kind "artifact-assertion"
      :artifact/id "evidence-registry.json"
      :assertion/path ["registry-hash"]
      :ref/hash (or (:recorded detail) (:computed detail))
      :ref/path "../evidence-registry.json"}]

    :registry-hash-signed
    [{:ref/kind "artifact-assertion"
      :artifact/id "signature.json"
      :assertion/path ["signature"]
      :ref/hash (:hash detail)
      :ref/path "../signature.json"}]

    :cursor-verifies
    [{:ref/kind "artifact-assertion"
      :artifact/id "chain-cursor-final.json"
      :assertion/path ["cursor/signed-hash"]
      :ref/hash (:hash detail)
      :ref/path "../chain-cursor-final.json"}]

    :tsa-token-verifies
    [{:ref/kind "artifact-assertion"
      :artifact/id "tsa-response.tsr"
      :assertion/path ["timestamp/verified?"]
      :ref/hash (:hash detail)
      :ref/path "../time-stamping-authority/tsa-response.tsr"}]

    [{:ref/kind "criterion-result"
      :artifact/id (name criterion)
      :assertion/path ["criterion"]
      :ref/hash (:hash detail)}]))

(defn populate-claims-and-attestations!
  "Evaluate all registered forensic claims, write claim results to claims/
   and self-attestations to attestations/.  Called after scenario execution
   completes.  Attestor identity is derived from the evidence chain root
   hash, NOT from the bundle root — so this function is callable without
   a bundle having been built.

   Arguments:
     run-id  — identifier for the current run

   Returns {:claim-count <int> :attestation-count <int> :all-pass? <bool>}."
  [run-id]
  (let [dir (str (evcfg/artifact-dir))
        root-hash (chain/evidence-root-hash :dir dir)
        attestor-id (if root-hash (str "self:" root-hash) (str "self:" run-id))]
    ;; Evaluate forensic-grade acceptance criteria from chain
    (.println *err* "  evaluating forensic claims...")
    (.println *err* (str "  evaluating forensic claims from " dir))
    (let [fs (try
               (chain/forensic-status :dir dir)
               (catch Exception e
                 (.println *err* (str "  warning: forensic-status evaluation failed: " (.getMessage e)))
                 {:all-pass? false :criteria []}))
          criteria (vec (:criteria fs))
          all-pass? (and (seq criteria) (:all-pass? fs))
          claim-results (atom [])]
      ;; Write a claim result for each individual criterion
      (doseq [c criteria]
        (let [criterion (:criterion c)
              pass? (:pass c)
              detail (:detail c)
              level (if pass? :high :low)
              cr (write-claim-result!
                  {:claim-id (str (name criterion))
                   :category "audit"
                   :confidence (name level)
                   :status (if pass? "pass" "fail")
                   :evidence-refs (criterion-evidence-refs criterion detail)
                   :description (str "Forensic claim: " (name criterion))
                   :failure-detail (when-not pass?
                                     (pr-str (select-keys detail [:error :valid :recorded])))})]
          (swap! claim-results conj cr)))
      ;; Write composite forensic-grade claim result
      (let [comp-level (if all-pass? :high :low)
            composite-cr (write-claim-result!
                          {:claim-id "forensic-grade"
                           :category "composite"
                           :confidence (name comp-level)
                           :status (if all-pass? "pass" "fail")
                           :evidence-refs (vec (mapv (fn [cr]
                                                       {:ref/kind "claim-result"
                                                        :ref/hash (:hash cr)
                                                        :ref/path (str "claims/sha256/" (:hash cr) ".json")})
                                                     @claim-results))
                           :description "All forensic-grade acceptance criteria pass"
                           :failure-detail (when-not all-pass?
                                             (if (seq criteria)
                                               (str "Failed criteria: "
                                                    (str/join ", "
                                                              (map (comp name :criterion)
                                                                   (remove :pass criteria))))
                                               "No forensic acceptance criteria were evaluated"))})]
        (swap! claim-results conj composite-cr))
      ;; Write self-attestations for each claim result
      (let [all-results @claim-results
            attestations (mapv (fn [cr]
                                 (write-attestation!
                                  {:subject-kind "claim-result"
                                   :subject-hash (:hash cr)
                                   :claim-id (get-in cr [:result :result/claim-id])
                                   :claim-result (if (= "pass" (get-in cr [:result :result/status]))
                                                   "verified" "rejected")
                                   :attestor-id attestor-id
                                   :provenance {:prov/schema-version "forensic-provenance.v1"
                                                :prov/trigger "run-complete"
                                                :prov/generated-at (str (java.time.Instant/now))
                                                :prov/run-id run-id
                                                :prov/producer "resolver-sim.evidence.forensic-populate/populate-claims-and-attestations!"}}))
                               all-results)
            claim-hashes (mapv :hash all-results)
            attestation-hashes (mapv :hash attestations)
            index-base {:index/schema-version "forensic-claims-index.v2"
                        :index/run-id run-id
                        :index/evidence-root root-hash
                        :index/claim-hashes claim-hashes
                        :index/claim-count (count claim-hashes)
                        :index/attestation-hashes attestation-hashes
                        :index/attestation-count (count attestation-hashes)
                        :index/all-pass? all-pass?}
            ;; The root is a commitment to the complete index projection, not to
            ;; the file path or a self-referential serialized representation.
            index (assoc index-base :index/root (sha256-hex (json-bytes index-base)))
            index-path (atomic-replace-json! (io/file dir "forensic-claims-index.json") index)]
        {:claim-count (count all-results)
         :attestation-count (count attestations)
         :all-pass? all-pass?
         :index-path index-path
         :index index}))))
