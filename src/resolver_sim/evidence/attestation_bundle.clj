(ns resolver-sim.evidence.attestation-bundle
  "Portable attestation verification bundles: self-contained packages
   containing attestations, claim results, evidence nodes, and registry
   snapshots for offline verification.

   Bundle manifest shape follows ATTESTATION_BUNDLE_SPEC_V1.

   Usage:
     (require '[resolver-sim.evidence.attestation-bundle :as ab])

     ;; Build a bundle from attestations and supporting data
     (ab/build-attestation-bundle
       {:attestations [attestation-1 attestation-2]
        :claim-results [claim-result-1]
        :evidence-nodes [node-1]
        :registries {:attestors attestor-registry
                     :claim-definitions claim-def-registry
                     :hash-intents hash-intents-map}
        :sensitivity-report {:sentinel/decision :allowed ...}
        :options {:bundle-dir \"path/to/bundle\"}})

     ;; Verify a bundle
     (ab/verify-attestation-bundle bundle)

     ;; Persist
     (ab/write-attestation-bundle! bundle)
     (ab/read-attestation-bundle path)"
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.walk :as walk]
            [resolver-sim.evidence.attestation-completeness-profile :as acp]
            [resolver-sim.evidence.attestation-integrity :as integrity]
            [resolver-sim.evidence.attestation-signature :as signature]
            [resolver-sim.definitions.passive-registries :as registries]
            [resolver-sim.hash.canonical :as hc])
  (:import [java.security MessageDigest]))

;; ── Constants ────────────────────────────────────────────────────────────────

(def ^:const default-bundle-dir "results/attestation-bundle")

(def ^:const bundle-version "attestation-bundle.v1")
(def ^:const bundle-kind :attestation-verification-package)

(def ^:const verification-statuses
  #{:fully-verified :hash-linked :partially-verified :invalid
    :blocked-by-sensitivity-policy :internal-retention
    :unverified-sensitivity})

(def ^:const fully-verified-statuses
  "Statuses that semantically mean all applicable required verification
   completed successfully. Only these may expose :verified? true. A bundle
   that is structurally valid (:valid? true) but not :fully-verified (e.g.
   :hash-linked, :partially-verified, :unverified-sensitivity) is NOT assured."
  #{:fully-verified})

(def ^:private runtime-root-key :bundle/runtime-root)

(defn- bundle-root
  "Return the trusted filesystem root supplied by read-attestation-bundle.
   Builder-created in-memory bundles retain compatibility by deriving it from
   their first object path; untrusted on-disk bundles always carry runtime root."
  [bundle]
  (or (get bundle runtime-root-key)
      (some-> (get-in bundle [:bundle/objects 0 :object/path])
              io/file .getParentFile .getParentFile .getCanonicalPath)))

(defn- contained-path
  "Resolve path only when it remains inside bundle's trusted root."
  [bundle path]
  (let [root (some-> (bundle-root bundle) io/file .getCanonicalFile)
        candidate (when (and root (string? path))
                    (let [p (io/file path)]
                      (if (.isAbsolute p)
                        (.getCanonicalFile p)
                        (.getCanonicalFile (io/file root path)))))]
    (when (and root candidate (.startsWith (.toPath candidate) (.toPath root)))
      candidate)))

(defn- require-contained-path [bundle path]
  (or (contained-path bundle path)
      (throw (ex-info "Bundle path escapes trusted bundle root"
                      {:path path :bundle-root (bundle-root bundle)}))))

;; ── Bundle Builder ───────────────────────────────────────────────────────────

(defn- compute-object-hash
  [obj]
  (hc/hash-with-intent {:hash/intent :evidence-record} obj))

(defn- object-path
  [base-dir kind hash]
  (str base-dir "/" (name kind) "/" hash ".edn"))

(defn build-attestation-bundle
  "Build an attestation verification bundle.

   Arguments (map):
     :attestations     — vector of attestation records (required)
     :claim-results    — vector of claim result maps (optional)
     :evidence-nodes   — vector of evidence node maps (optional)
     :registries       — map with keys :attestors, :claim-definitions,
                         :hash-intents (required)
     :sensitivity-report — map with :sentinel/decision and :sentinel/report-hash
     :sensitivity-provenance — optional map with :originating-scenario,
                               :declared-level, :risk-meta (propagated from
                               scenario metadata through evidence/attestations)
     :completeness-profile — completeness profile map (optional; defaults
                             to acp/review-profile)
     :options          — map with :bundle-dir (output directory)

   Returns the bundle manifest map."
  [{:keys [attestations claim-results evidence-nodes registries sensitivity-report
           sensitivity-provenance completeness-profile options]
    :or {attestations [] claim-results [] evidence-nodes []
         completeness-profile acp/review-profile}}]
  (let [profile (acp/validate-profile completeness-profile)
        bundle-dir (or (:bundle-dir options) default-bundle-dir)
        _ (.mkdirs (io/file bundle-dir "attestations"))
        _ (.mkdirs (io/file bundle-dir "claims"))
        _ (.mkdirs (io/file bundle-dir "evidence-nodes"))
        _ (.mkdirs (io/file bundle-dir "registries"))
        _ (.mkdirs (io/file bundle-dir "reports"))

        ;; Build object entries
        att-entries (mapv (fn [a]
                            (let [h (:attestation/id a)]
                              {:object/kind :attestation-record
                               :object/hash h
                               :object/path (object-path bundle-dir "attestations" h)
                               :object/availability :included}))
                          attestations)
        claim-entries (mapv (fn [c]
                              (let [h (or (:claim-result-hash c)
                                          (compute-object-hash c))]
                                {:object/kind :claim-result
                                 :object/hash h
                                 :object/path (object-path bundle-dir "claims" h)
                                 :object/availability :included}))
                            claim-results)
        node-entries (mapv (fn [n]
                             (let [h (:node-hash n)]
                               {:object/kind :evidence-node
                                :object/hash h
                                :object/path (object-path bundle-dir "evidence-nodes" h)
                                :object/availability (if n :included :hash-only)}))
                           evidence-nodes)
        entrypoints (mapv (fn [a]
                            {:attestation/hash (:attestation/id a)
                             :attestation/path (object-path bundle-dir "attestations"
                                                            (:attestation/id a))})
                          attestations)

        ;; Convert sets to vectors for canonical encoding
        canon-att-reg (walk/postwalk
                       (fn [x]
                         (cond (set? x) (vec (sort x))
                               (fn? x) (str x)
                               (instance? clojure.lang.Var x) (str x)
                               :else x))
                       registries/attestor-registry)
        canon-cd-reg (walk/postwalk
                      (fn [x]
                        (cond (set? x) (vec (sort x))
                              (fn? x) (str x)
                              (instance? clojure.lang.Var x) (str x)
                              :else x))
                      registries/claim-definition-registry)
        canon-hi-map (walk/postwalk
                      (fn [x]
                        (cond (set? x) (vec (sort x))
                              (fn? x) (str x)
                              (instance? clojure.lang.Var x) (str x)
                              :else x))
                      hc/hash-intents)
        registry-snapshot {:attestors
                           {:registry/hash (hc/hash-with-intent
                                            {:hash/intent :registry}
                                            canon-att-reg)
                            :registry/path (str bundle-dir "/registries/attestor-registry.edn")}
                           :claim-definitions
                           {:registry/hash (hc/hash-with-intent
                                            {:hash/intent :registry}
                                            canon-cd-reg)
                            :registry/path (str bundle-dir "/registries/claim-definition-registry.edn")}
                           :hash-intents
                           {:registry/hash (hc/hash-with-intent
                                            {:hash/intent :registry}
                                            canon-hi-map)
                            :registry/path (str bundle-dir "/registries/hash-intent-registry.edn")}}

         ;; Base manifest (without root-hash)
        base-manifest {:bundle/version bundle-version
                       :bundle/kind bundle-kind
                       :bundle/entrypoints entrypoints
                       :bundle/objects (vec (concat att-entries claim-entries node-entries))
                       :bundle/registries registry-snapshot
                       :bundle/sensitivity (cond-> {:sentinel/decision (:decision sensitivity-report :blocked)
                                                    :sentinel/report-hash (:report-hash sensitivity-report)
                                                    :sensitivity-report/ref
                                                    {:schema "sensitivity-report.v2"
                                                     :semantic-hash (:report/semantic-hash sensitivity-report)
                                                     :sha256 (:report-byte-hash sensitivity-report)
                                                     :byte-length (:report-byte-length sensitivity-report)
                                                     :path (str bundle-dir "/reports/sensitivity-report.json")}}
                                             sensitivity-provenance
                                             (assoc :sentinel/provenance sensitivity-provenance))
                       :bundle/completeness-profile
                       {:profile/schema-version (:profile/schema-version profile)
                        :profile/mode (:profile/mode profile)
                        :profile/hash (:profile/hash profile)
                        :profile/signature-required (get-in profile [:profile/rules :signature/required] false)}
                       :bundle/verification-profile (cond-> {:integrity? true
                                                             :registry-backed? true
                                                             :subject-content-included? (boolean (seq claim-results))
                                                             :quorum? false}
                                                      (:signature? options) (assoc :signature? true))}

        ;; Canonical root hash (excludes self-referential fields)
        root-input (dissoc base-manifest :bundle/root-hash)
        root-hash (hc/hash-with-intent {:hash/intent :manifest} root-input)]

    (assoc base-manifest :bundle/root-hash root-hash)))

;; ── Bundle Verification ──────────────────────────────────────────────────────

(defn- check-version
  [bundle]
  (let [v (:bundle/version bundle)]
    (if (= v bundle-version)
      {:check/id :bundle-version-valid :check/status :pass}
      {:check/id :bundle-version-valid :check/status :fail
       :reason (str "Expected " bundle-version ", got " v)})))

(defn- check-root-hash
  [bundle]
  (let [recorded (:bundle/root-hash bundle)
        base (dissoc bundle :bundle/root-hash runtime-root-key)
        computed (hc/hash-with-intent {:hash/intent :manifest} base)]
    (if (= recorded computed)
      {:check/id :bundle-root-hash-valid :check/status :pass}
      {:check/id :bundle-root-hash-valid :check/status :fail
       :reason (str "Root hash mismatch: recorded " recorded ", computed " computed)})))

(defn- check-object-integrity
  [bundle]
  (let [objects (:bundle/objects bundle [])
        results (mapv (fn [obj]
                        (let [obj-path (:object/path obj)
                              recorded-hash (:object/hash obj)
                              file (when obj-path (contained-path bundle obj-path))]
                          (cond (nil? obj-path)
                                {:object/hash recorded-hash
                                 :check/status :warning
                                 :reason :hash-only}
                                (or (nil? file) (not (.exists file)))
                                {:object/hash recorded-hash
                                 :check/status :warning
                                 :reason (str "File not found: " obj-path)}
                                :else
                                (try
                                  (let [content (edn/read-string (slurp file))
                                        computed (compute-object-hash content)]
                                    (if (= computed recorded-hash)
                                      {:object/hash recorded-hash :check/status :pass}
                                      {:object/hash recorded-hash :check/status :fail
                                       :reason (str "Hash mismatch for " obj-path)}))
                                  (catch Exception e
                                    {:object/hash recorded-hash :check/status :error
                                     :reason (.getMessage e)})))))
                      objects)
        all-pass? (every? #(= :pass (:check/status %)) results)]
    {:check/id :object-integrity-valid
     :check/status (if all-pass? :pass :warning)
     :detail {:total (count results)
              :pass (count (filter #(= :pass (:check/status %)) results))
              :warning (count (filter #(= :warning (:check/status %)) results))
              :fail (count (filter #(= :fail (:check/status %)) results))}
     :objects results}))

(defn- check-attestation-integrity
  [bundle]
  (let [objects (:bundle/objects bundle [])
        att-objects (filter #(= :attestation-record (:object/kind %)) objects)
        results (mapv (fn [obj]
                        (let [path (:object/path obj)]
                          (cond
                            (nil? path)
                            {:attestation/id (:object/hash obj)
                             :check/status :warning
                             :reason :hash-only}
                            (nil? (contained-path bundle path))
                            {:attestation/id (:object/hash obj)
                             :check/status :warning
                             :reason (str "File not found: " path)}
                            :else
                            (try
                              (let [content (edn/read-string (slurp (require-contained-path bundle path)))
                                    integrity-result (integrity/verify-attestation-integrity content)]
                                {:attestation/id (:object/hash obj)
                                 :check/status (if (:valid? integrity-result) :pass :fail)
                                 :errors (:errors integrity-result)})
                              (catch Exception e
                                {:attestation/id (:object/hash obj)
                                 :check/status :error
                                 :reason (.getMessage e)})))))
                      att-objects)
        has-fail? (some #(= :fail (:check/status %)) results)
        all-warning? (every? #(#{:warning :pass} (:check/status %)) results)]
    {:check/id :attestation-integrity-valid
     :check/status (cond has-fail? :fail
                         all-warning? :warning
                         :else :pass)
     :detail {:total (count results)
              :pass (count (filter #(= :pass (:check/status %)) results))
              :fail (count (filter #(= :fail (:check/status %)) results))}
     :attestations results}))

(defn- canonical-registry-snapshot [snapshot]
  (walk/postwalk (fn [x]
                   (cond (set? x) (vec (sort x))
                         (fn? x) (str x)
                         (instance? clojure.lang.Var x) (str x)
                         :else x))
                 snapshot))

(defn- check-attestor-registry-trust
  "Verify the bundled attestor registry's integrity and external trust anchor.
   No verifier policy means legacy/observe-only warning, never trusted success."
  [bundle opts]
  (let [declared (get-in bundle [:bundle/registries :attestors])
        path (:registry/path declared)
        expected (:registry/hash declared)
        file (when path (contained-path bundle path))]
    (cond
      (nil? declared) {:check/id :attestor-registry-trusted :check/status :fail :reason :registry-missing}
      (nil? file) {:check/id :attestor-registry-trusted
                   :check/status (if opts :fail :warning)
                   :reason :registry-path-invalid}
      (not (.isFile file)) {:check/id :attestor-registry-trusted
                            :check/status (if opts :fail :warning)
                            :reason :registry-file-missing}
      :else
      (try
        (let [snapshot (edn/read-string (slurp file))
              computed (hc/hash-with-intent {:hash/intent :registry}
                                            (canonical-registry-snapshot snapshot))
              integrity? (= expected computed)
              trusted-hashes (:trusted-attestor-registry-hashes opts)
              trusted-registry (:trusted-attestor-registry opts)
              trusted-registry-hash (when trusted-registry
                                      (hc/hash-with-intent {:hash/intent :registry}
                                                           (canonical-registry-snapshot trusted-registry)))
              trusted? (or (contains? trusted-hashes computed)
                           (contains? trusted-hashes (str "sha256:" computed))
                           (= computed trusted-registry-hash))]
          (cond
            (not integrity?) {:check/id :attestor-registry-trusted :check/status :fail
                              :reason :registry-hash-mismatch :expected expected :computed computed}
            (and (nil? trusted-hashes) (nil? trusted-registry))
            {:check/id :attestor-registry-trusted :check/status :warning
             :reason :no-external-trust-anchor :registry-hash computed}
            (not trusted?) {:check/id :attestor-registry-trusted :check/status :fail
                            :reason :untrusted-registry :registry-hash computed}
            :else {:check/id :attestor-registry-trusted :check/status :pass
                   :registry-hash computed :registry snapshot}))
        (catch Exception e
          {:check/id :attestor-registry-trusted :check/status :fail
           :reason :malformed-registry :detail (.getMessage e)})))))

(defn- check-attestation-signatures
  [bundle trusted-registry policy signatures-required?]
  (let [objects (:bundle/objects bundle [])
        att-objects (filter #(= :attestation-record (:object/kind %)) objects)
        results (mapv (fn [obj]
                        (try
                          (let [content (edn/read-string (slurp (require-contained-path bundle (:object/path obj))))
                                sig (:attestation/signature content)]
                            (cond
                              (nil? sig) {:attestation/id (:object/hash obj)
                                          :check/status (if signatures-required? :fail :warning)
                                          :reason :unsigned}
                              (nil? trusted-registry) {:attestation/id (:object/hash obj)
                                                       :check/status (if signatures-required? :fail :warning)
                                                       :reason :no-trusted-registry}
                              :else (let [result (signature/verify-attestation-signature content trusted-registry policy)]
                                      {:attestation/id (:object/hash obj)
                                       :check/status (if (:valid? result) :pass :fail)
                                       :verification result})))
                          (catch Exception e
                            {:attestation/id (:object/hash obj)
                             :check/status :error
                             :reason (.getMessage e)})))
                      att-objects)
        signed (count (filter #(= :pass (:check/status %)) results))
        unsigned (count (filter #(= :warning (:check/status %)) results))
        failed? (and (or trusted-registry signatures-required?)
                     (some #(#{:fail :error} (:check/status %)) results))]
    {:check/id :attestation-signature-valid
     :check/status (cond failed? :fail (zero? unsigned) :pass :else :warning)
     :detail {:signed signed :unsigned unsigned}
     :attestations results}))

(defn- check-registry-references
  [bundle]
  (let [objects (:bundle/objects bundle [])
        att-objects (filter #(= :attestation-record (:object/kind %)) objects)
        results (mapv (fn [obj]
                        (try
                          (let [content (edn/read-string (slurp (require-contained-path bundle (:object/path obj))))
                                attestor-id (:attestation/attestor-id content)]
                            {:attestation/id (:object/hash obj)
                             :attestor-id attestor-id
                             :check/status :pass
                             :note "Registry snapshot included for external verification"})
                          (catch Exception e
                            {:attestation/id (:object/hash obj)
                             :check/status :error
                             :reason (.getMessage e)})))
                      att-objects)]
    {:check/id :registry-references-valid
     :check/status :pass
     :detail {:registries-included (set (keys (:bundle/registries bundle)))
              :attestations-checked (count results)}
     :attestations results}))

(defn- check-claim-definition-references
  [bundle]
  (let [objects (:bundle/objects bundle [])
        att-objects (filter #(= :attestation-record (:object/kind %)) objects)
        results (mapv (fn [obj]
                        (try
                          (let [content (edn/read-string (slurp (require-contained-path bundle (:object/path obj))))
                                claim-id (:attestation/claim-id content)]
                            {:attestation/id (:object/hash obj)
                             :claim-id claim-id
                             :check/status (if claim-id :pass :warning)
                             :reason (when (nil? claim-id) :no-claim-reference)})
                          (catch Exception e
                            {:attestation/id (:object/hash obj)
                             :check/status :error
                             :reason (.getMessage e)})))
                      att-objects)]
    {:check/id :claim-definition-references-valid
     :check/status :pass
     :attestations results}))

(defn- check-subject-availability
  [bundle]
  (let [objects (:bundle/objects bundle [])
        hash-only (filter #(= :hash-only (:object/availability %)) objects)
        included (filter #(= :included (:object/availability %)) objects)]
    {:check/id :subject-content-available
     :check/status (if (seq hash-only) :warning :pass)
     :detail {:total (count objects)
              :included (count included)
              :hash-only (count hash-only)
              :note (when (seq hash-only)
                      "Some subjects are hash-only: content not available for verification")}}))

(defn- sha256-bytes
  "Hex SHA-256 of a byte array."
  [bytes]
  (let [digest (doto (MessageDigest/getInstance "SHA-256")
                 (.update bytes))]
    (format "%064x" (java.math.BigInteger. 1 (.digest digest)))))

(defn- verify-sensitivity-report-file
  "Verify a sensitivity report file against its reference metadata.
   Returns nil on success or a detail map on failure."
  [file ref]
  (try
    (let [bytes (java.nio.file.Files/readAllBytes (.toPath file))
          actual-sha256 (sha256-bytes bytes)
          expected-sha256 (:sha256 ref)
          actual-length (alength bytes)
          expected-length (:byte-length ref)]
      (cond
        (and expected-sha256 (not= actual-sha256 expected-sha256))
        {:check/status :fail :reason "SHA-256 mismatch" :expected expected-sha256 :actual actual-sha256}
        (and expected-length (not= actual-length expected-length))
        {:check/status :fail :reason "byte-length mismatch" :expected expected-length :actual actual-length}
        :else nil))
    (catch java.io.FileNotFoundException _
      {:check/status :warning :reason "Report file not found at referenced path"})
    (catch Exception e
      {:check/status :error :reason (str "File read error: " (.getMessage e))})))

(defn- verify-sensitivity-report-content
  "Parse and verify the sensitivity report JSON content.
   Returns a detail map or nil on success."
  [report-str ref]
  (try
    (let [data (json/read-str report-str :key-fn keyword)
          schema (:schema-version data)
          ;; Recompute semantic hash from parsed data
          hash-input (dissoc data :evaluated-at :report-hash :report-byte-hash)
          computed-semantic-hash (hc/hash-with-intent {:hash/intent :evidence-record} hash-input)
          expected-semantic-hash (:semantic-hash ref)]
      (cond
        (not= schema "sensitivity-report.v2")
        {:check/status :fail :reason "Unexpected schema" :schema schema}
        (and expected-semantic-hash (not= computed-semantic-hash expected-semantic-hash))
        {:check/status :fail :reason "Semantic hash mismatch"
         :computed computed-semantic-hash :expected expected-semantic-hash}
        :else nil))
    (catch Exception e
      {:check/status :error :reason (str "JSON parse error: " (.getMessage e))})))

(defn- ref-present?
  [ref]
  (and ref (or (:sha256 ref) (:semantic-hash ref))))

(defn- check-sensitivity-sentinel
  [bundle]
  (let [sensitivity (:bundle/sensitivity bundle)
        ref (:sensitivity-report/ref sensitivity)
        decision (:sentinel/decision sensitivity)]
    (if (and decision (not (ref-present? ref)))
      ;; Backward-compatible path: no report ref, trust embedded decision
      (merge {:check/id :sensitivity-sentinel-approved}
             (cond
               (= :blocked decision)
               {:check/status :blocked :decision decision
                :reason "Sensitivity sentinel blocked export (no report ref)"}
               (= :internal-retention decision)
               {:check/status :policy-constrained :decision decision
                :reason "Sensitivity sentinel restricted to internal retention"}
               :else
               {:check/status :pass :decision decision
                :reason "Sensitivity sentinel approved (embedded decision)"}))
      ;; Full verification chain: verify report file against ref
      (let [report-path (:path ref)
            report-file (when report-path (contained-path bundle report-path))
            file-check (when report-file
                         (verify-sensitivity-report-file report-file ref))
            content-check (when (and report-file (nil? file-check))
                            (verify-sensitivity-report-content (slurp report-file) ref))
            result (cond
                     (not (ref-present? ref))
                     {:check/status :warning
                      :reason "No sensitivity-report/ref in bundle — cannot verify report binding"}
                     (not report-file)
                     {:check/status :warning
                      :reason (str "Report file not found: " report-path)
                      :path report-path}
                     file-check
                     (assoc file-check :step :file-verification :path report-path)
                     content-check
                     (assoc content-check :step :content-verification)
                     (nil? decision)
                     {:check/status :warning
                      :reason "No decision in sensitivity report"}
                     (= :blocked decision)
                     {:check/status :blocked
                      :decision decision
                      :reason "Sensitivity sentinel blocked export"}
                     (= :internal-retention decision)
                     {:check/status :policy-constrained
                      :decision decision
                      :reason "Sensitivity sentinel restricted to internal retention"}
                     :else
                     {:check/status :pass
                      :decision decision
                      :reason "Sensitivity sentinel approved"})]
        (merge {:check/id :sensitivity-sentinel-approved}
               result)))))

(defn verify-attestation-bundle
  "Verify an attestation bundle against the verification pipeline.

   Performs all 13 verification checks and returns a structured report
   with a bundle-level status.

   Returns:
     {:valid? true/false
      :verified? true/false
      :bundle/status <one of the :bundle/status vocabulary values>
      :checks [<check-result> ...]
      :summary {...}}

   SEMANTIC DISTINCTION:
     :valid?    — the artifact is internally well-formed / no hard verification
                  contradiction. True when no check is :fail, :blocked,
                  :policy-constrained, or unknown.
     :verified? — all checks required by the applicable profile actually
                  completed successfully. True only for :fully-verified.
                  A bundle can be :valid? true yet :verified? false (e.g.
                  :hash-linked, :partially-verified, :unverified-sensitivity)
                  when assurance/completeness was not actually established.
   Assurance consumers MUST use :verified?, not :valid? alone."
  ([bundle] (verify-attestation-bundle bundle nil))
  ([bundle opts]
   (let [registry-check (check-attestor-registry-trust bundle opts)
         trusted-registry (when (= :pass (:check/status registry-check)) (:registry registry-check))
         completeness-profile-ref (:bundle/completeness-profile bundle)
         resolved-profile (when completeness-profile-ref
                            (let [mode (:profile/mode completeness-profile-ref)]
                              (try (acp/resolve-profile mode)
                                   (catch Exception _ nil))))
         signatures-required? (true? (or (get-in bundle [:bundle/verification-profile :signature?])
                                         (get-in bundle [:bundle/completeness-profile :profile/signature-required])))
         cp-status (if completeness-profile-ref
                     (if resolved-profile
                       (let [status (acp/evaluate-evidence-status
                                     resolved-profile
                                     {:bundle/objects (:bundle/objects bundle [])
                                      :sensitivity/decision (get-in bundle
                                                                    [:bundle/sensitivity :sentinel/decision])
                                      :sensitivity/report-hash (get-in bundle
                                                                       [:bundle/sensitivity :sentinel/report-hash])})]
                         {:check/id :completeness-profile-evaluated
                          :check/status (case status
                                          :invalid :fail
                                          :blocked-by-sensitivity-policy :blocked
                                          :partially-verified :warning
                                          :pass)
                          :evidence-status status
                          :profile/mode (:profile/mode completeness-profile-ref)})
                       {:check/id :completeness-profile-evaluated
                        :check/status :warning
                        :reason "Unknown completeness profile mode — cannot evaluate"})
                     {:check/id :completeness-profile-evaluated
                      :check/status :warning
                      :reason "No completeness profile in bundle"})
         checks [(check-version bundle)
                 (check-root-hash bundle)
                 (check-object-integrity bundle)
                 (check-attestation-integrity bundle)
                 registry-check
                 (check-attestation-signatures bundle trusted-registry opts signatures-required?)
                 (check-registry-references bundle)
                 (check-claim-definition-references bundle)
                 (check-subject-availability bundle)
                 (check-sensitivity-sentinel bundle)
                 cp-status]
         failures (filter #(= :fail (:check/status %)) checks)
         blocked (filter #(= :blocked (:check/status %)) checks)
         policy-constrained (filter #(= :policy-constrained (:check/status %)) checks)
         unknown (filter #(and (not= :pass (:check/status %))
                               (not= :fail (:check/status %))
                               (not= :warning (:check/status %))
                               (not= :blocked (:check/status %))
                               (not= :policy-constrained (:check/status %)))
                         checks)
         sens-warnings (filter #(and (= :warning (:check/status %))
                                     (= :sensitivity-sentinel-approved (:check/id %)))
                               checks)
         other-warnings (filter #(and (= :warning (:check/status %))
                                      (not= :sensitivity-sentinel-approved (:check/id %)))
                                checks)
         all-pass? (and (empty? failures) (empty? blocked) (empty? policy-constrained) (empty? unknown))
          ;; The completeness-profile evaluation now drives the bundle-level
          ;; status: :invalid / :blocked-by-sensitivity-policy propagate through
          ;; the failure/blocked collections above, and a missing-required
          ;; (:partially-verified) completeness outcome surfaces explicitly
          ;; instead of collapsing to :hash-linked.
         evidence-status (get-in (some #(when (= :completeness-profile-evaluated (:check/id %)) %)
                                       checks)
                                 [:evidence-status])
         status (cond
                  (seq unknown) :invalid
                  (seq failures) :invalid
                  (seq blocked) :blocked-by-sensitivity-policy
                  (seq policy-constrained) :internal-retention
                  (= :partially-verified evidence-status) :partially-verified
                  (seq sens-warnings) :unverified-sensitivity
                  (and (empty? other-warnings) all-pass?) :fully-verified
                  (some #(= :warning (:check/status %))
                        (filter #(= :subject-content-available (:check/id %)) checks))
                  :partially-verified
                  :else :hash-linked)]
     (let [valid? (and (empty? failures) (empty? blocked) (empty? policy-constrained) (empty? unknown))
           verified? (contains? fully-verified-statuses status)]
       {:valid? valid?
        :verified? verified?
        :bundle/status status
        :checks checks
        :summary {:total-checks (count checks)
                  :pass (count (filter #(= :pass (:check/status %)) checks))
                  :warning (count (filter #(= :warning (:check/status %)) checks))
                  :fail (count failures)
                  :blocked (count blocked)
                  :policy-constrained (count policy-constrained)}}))))

;; ── Bundle I/O ───────────────────────────────────────────────────────────────

(defn write-attestation-bundle!
  "Write an attestation bundle to disk.

   Persists each object to its declared path and writes the manifest
   as manifest.edn in the bundle root directory.

   Arguments:
     bundle — bundle manifest map (from build-attestation-bundle)
     objects-map — map of attestion/claim/node data to write:
                    {:attestations [..] :claim-results [..] :evidence-nodes [..]}

   trusted-bundle-root — caller-authorized destination root (required)

   Returns the bundle directory path."
  ([bundle objects-map]
   (throw (ex-info "write-attestation-bundle! requires an explicit trusted bundle root"
                   {:hint "Pass trusted-bundle-root as the third argument"})))
  ([bundle objects-map trusted-bundle-root]
   (let [bundle-dir (.getCanonicalPath (io/file trusted-bundle-root))
         bundle (assoc bundle runtime-root-key bundle-dir)
         _ (.mkdirs (io/file bundle-dir))
        ;; Write attestations
         _ (doseq [a (:attestations objects-map [])]
             (let [path (object-path bundle-dir "attestations" (:attestation/id a))]
               (spit path (pr-str a))))
        ;; Write claim results
         _ (doseq [c (:claim-results objects-map [])]
             (let [h (or (:claim-result-hash c) (compute-object-hash c))
                   path (object-path bundle-dir "claims" h)]
               (spit path (pr-str c))))
        ;; Write evidence nodes
         _ (doseq [n (:evidence-nodes objects-map [])]
             (let [path (object-path bundle-dir "evidence-nodes" (:node-hash n))]
               (spit path (pr-str n))))
        ;; Write registries
         _ (doseq [[reg-kind reg-map] (:bundle/registries bundle)]
             (spit (require-contained-path bundle (:registry/path reg-map))
                   (pr-str (get objects-map reg-kind))))
        ;; Write sensitivity report
         _ (when-let [report-path (get-in bundle [:bundle/sensitivity :sentinel/path])]
             (spit (require-contained-path bundle report-path)
                   (pr-str (:sensitivity-report objects-map))))
        ;; Write manifest without runtime-only trust context.
         manifest-path (io/file bundle-dir "manifest.edn")]
     (spit manifest-path (pr-str (dissoc bundle runtime-root-key)))
     (str (io/file bundle-dir ".written")))))

(defn read-attestation-bundle
  "Read an attestation bundle from disk.

   Reads the manifest from bundle-dir/manifest.edn and returns
   the manifest map.

   Arguments:
     bundle-dir — path to the bundle directory

   Returns the bundle manifest map."
  [bundle-dir]
  (let [manifest-path (str bundle-dir "/manifest.edn")]
    (when-not (.exists (io/file manifest-path))
      (throw (ex-info "Bundle manifest not found" {:path manifest-path})))
    (assoc (edn/read-string (slurp manifest-path))
           runtime-root-key (.getCanonicalPath (io/file bundle-dir)))))
