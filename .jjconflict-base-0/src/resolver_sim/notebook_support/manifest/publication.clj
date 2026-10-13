(ns resolver-sim.notebook-support.manifest.publication
  "Publication layer: canonical manifest hashing, Ed25519 signing,
  and export bundle generation.

  Thin wrapper around resolver-sim.benchmark.{signing,hashing,sharing}
  so the notebook only depends on this namespace."
  (:require [resolver-sim.benchmark.signing :as signing]
            [resolver-sim.notebook-support.manifest.hash :as mhash]
            [clojure.data.json :as json]
            [clojure.java.io :as io]))

;; ── canonical hash ────────────────────────────────────────────────────────────

(defn manifest-hash
  "Return the canonical SHA-256 hex string for manifest, excluding volatile fields."
  [manifest]
  (mhash/canonical-hash manifest))

;; ── signing ───────────────────────────────────────────────────────────────────

(defn sign-manifest
  "Sign the canonical manifest hash with an Ed25519 private key.

  If a registry map is provided, compute its canonical hash and inject it into
  the manifest under :artifact-registry-sha before signing. This makes the
  signature commit to the artifact registry by default.

  Returns {:hash h :signature sig-hex :private-key-path ... :manifest manifest-with-registry}
  or {:error ...}.
  "
  [manifest private-key-path & {:keys [registry password] :or {registry nil password nil}}]
  (try
    (let [manifest-with-registry (if registry
                                   (let [reg-h (mhash/canonical-hash registry)]
                                     (assoc manifest :artifact-registry-sha reg-h))
                                   manifest)
          h   (manifest-hash manifest-with-registry)
          sig (signing/sign-hash h private-key-path password)]
      {:hash h :signature sig :private-key-path private-key-path :manifest manifest-with-registry})
    (catch Exception e
      {:error (.getMessage e)})))

(defn verify-manifest-signature
  "Verify a signature produced by sign-manifest.

   When `:registry` is supplied, reconstruct the same registry-bound manifest
   that sign-manifest signed. Returns {:valid true/false :hash h} or
   {:error ...}."
  [manifest signature-hex public-key-path & {:keys [registry]}]
  (try
    (let [manifest-with-registry (if registry
                                   (assoc manifest :artifact-registry-sha
                                          (mhash/canonical-hash registry))
                                   manifest)
          h     (manifest-hash manifest-with-registry)
          valid (signing/verify-signature h signature-hex public-key-path)]
      {:valid valid :hash h})
    (catch Exception e
      {:error (.getMessage e)})))

;; ── export bundle ─────────────────────────────────────────────────────────────

(defn export-bundle!
  "Write a portable evidence bundle to out-dir/:
    manifest.json    canonical manifest
    provenance.json  run provenance record
    manifest-hash.txt

  Returns {:out-dir ... :hash ... :files [...]} or {:error ...}."
  [manifest registry out-dir]
  (try
    (let [d    (io/file out-dir)
          _    (.mkdirs d)
          h    (manifest-hash manifest)
          prov {:schema_version  "provenance.v1"
                :run_id          (:run_id manifest)
                :canonical_hash  h
                :git_commit      (get-in manifest [:framework :git_commit])
                :suite           (:suite manifest)
                :artifact_count  (count (get registry :artifacts []))
                :generated_at    (str (java.time.Instant/now))}
          files [(io/file out-dir "manifest.json")
                 (io/file out-dir "provenance.json")
                 (io/file out-dir "manifest-hash.txt")]]
      (spit (first files)  (json/write-str manifest {:indent true}))
      (spit (second files) (json/write-str prov     {:indent true}))
      (spit (nth files 2)  (str h "\n"))
      {:out-dir out-dir
       :hash    h
       :files   (map #(.getPath %) files)})
    (catch Exception e
      {:error (.getMessage e)})))

;; ── publication status ────────────────────────────────────────────────────────

(defn publication-status
  "Derive a publication status map for display in the notebook."
  [manifest signature-entry]
  (let [h (manifest-hash manifest)]
    {:canonical-hash  h
     :hash-prefix     (subs h 0 16)
     :signed          (boolean (seq (:signature signature-entry)))
     :signature-valid (:valid signature-entry)
     :run-id          (:run_id manifest)
     :git-commit      (get-in manifest [:framework :git_commit])}))
