(ns resolver-sim.evidence.forensic-populate
  "Populate claims/ and attestations/ directories in the forensic workspace.
   Evaluates registered forensic claims and produces claim result + attestation
   files with self-referential SHA-256 naming, per FORENSIC_CLAIMS_SPEC_V1
   and FORENSIC_ATTESTATIONS_SPEC_V1."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [resolver-sim.evidence.chain :as chain]
            [resolver-sim.evidence.config :as evcfg])
  (:import [java.security MessageDigest]))

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

(defn write-claim-result!
  "Write a single claim evaluation result to artifact-dir/claims/.
   File is named claim-result-<hash-prefix>.json with self-referential
   SHA-256 (result/hash computed from all fields except result/hash).
   Returns {:path <str> :hash <str> :result <map>}."
  [{:keys [claim-id category status evaluated-at evidence-refs description
           assumptions falsified-if failure-detail confidence counterexamples inputs]
    :or {evaluated-at (str (java.time.Instant/now))}}]
  (let [artifact-root (str (evcfg/artifact-dir))
        claims-dir (io/file artifact-root "claims")
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
        filename (str "claim-result-" (subs result-hash 0 (min 16 (count result-hash))) ".json")
        out-file (io/file claims-dir filename)]
    (spit out-file (json/write-str (sort-keys result) :key-fn (fn [k]
                                                                (if (namespace k)
                                                                  (str (namespace k) "/" (name k))
                                                                  (name k)))
                                   :indent true
                                   :escape-slash false))
    (println "  wrote" (.getPath out-file))
    {:path (.getPath out-file) :hash result-hash :result result}))

(defn write-attestation!
  "Write a single attestation record to artifact-dir/attestations/.
   File is named attestation-<hash-prefix>.json with self-referential
   SHA-256 (hash computed from all fields except attestation/id,
   attestation/hash, and attestation/signature).
   Returns {:path <str> :hash <str> :record <map>}."
  [{:keys [subject-kind subject-hash claim-id claim-result attestor-id signed-at
           signing-key-id signature provenance metadata]
    :or {signed-at (str (java.time.Instant/now))}}]
  (let [artifact-root (str (evcfg/artifact-dir))
        att-dir (io/file artifact-root "attestations")
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
        filename (str "attestation-" (subs att-hash 0 (min 16 (count att-hash))) ".json")
        out-file (io/file att-dir filename)]
    (spit out-file (json/write-str (sort-keys record) :key-fn (fn [k]
                                                                (if (namespace k)
                                                                  (str (namespace k) "/" (name k))
                                                                  (name k)))
                                   :indent true
                                   :escape-slash false))
    (println "  wrote" (.getPath out-file))
    {:path (.getPath out-file) :hash att-hash :record record}))

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
              cr (write-claim-result!
                  {:claim-id (str (name criterion))
                   :category "audit"
                   :confidence (if pass? "high" "low")
                   :status (if pass? "pass" "fail")
                   :evidence-refs (criterion-evidence-refs criterion detail)
                   :description (str "Forensic claim: " (name criterion))
                   :failure-detail (when-not pass?
                                     (pr-str (select-keys detail [:error :valid :recorded])))})]
          (swap! claim-results conj cr)))
      ;; Write composite forensic-grade claim result
      (let [composite-cr (write-claim-result!
                          {:claim-id "forensic-grade"
                           :category "composite"
                           :confidence (if all-pass? "high" "low")
                                                      :status (if all-pass? "pass" "fail")
                           :evidence-refs (vec (mapv (fn [cr]
                                                       {:ref/kind "claim-result"
                                                        :ref/hash (:hash cr)
                                                        :ref/path (str "claims/claim-result-"
                                                                       (subs (:hash cr) 0 (min 16 (count (:hash cr))))
                                                                       ".json")})
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
            att-count (atom 0)]
        (doseq [cr all-results]
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
                         :prov/producer "resolver-sim.evidence.forensic-populate/populate-claims-and-attestations!"}})
          (swap! att-count inc))
        {:claim-count (count all-results)
         :attestation-count @att-count
         :all-pass? all-pass?}))))
