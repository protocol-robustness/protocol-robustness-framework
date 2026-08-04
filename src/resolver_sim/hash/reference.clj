(ns resolver-sim.hash.reference
  "Shared hash reference format and utilities.

   ════════════════════════════════════════════════════════════════
   AUTHORITY BOUNDARY
   ════════════════════════════════════════════════════════════════

   The canonical hash reference format is \"sha256:<64-hex-chars>\".
   This is the single authoritative namespace for constructing, parsing,
   and validating canonical hash references across all PRF namespaces.

   “Single authority” applies to the canonical format, not to bare SHA-256
   calculation.  Subsystem-specific namespaces may continue to compute bare
   SHA-256 digests (e.g. chain/compute-file-sha256, lifecycle/sha256-file,
   or local MessageDigest usage) as long as the resulting hex string is
   wrapped through sha256-ref when a canonical reference is needed.

   In short:
     - Bare SHA-256 calculation may occur in subsystem-specific namespaces.
     - Construction, parsing, and validation of canonical \"sha256:\"
       references must use this namespace.

   See also resolver-sim.hash.canonical for domain-separated canonical hashing.

   ════════════════════════════════════════════════════════════════"
  (:import [java.security MessageDigest]
           [java.math BigInteger]))

(def ^:const sha256-ref-prefix "sha256:")

(def ^:const sha256-algorithm
  "Algorithm name for SHA-256 MessageDigest."
  "SHA-256")

(def ^:const prf-runner-edn-path
  "JAR entry path for the PRF runner metadata file."
  "META-INF/prf-runner.edn")

(def ^:const provenance-sidecar-suffix
  "Filename suffix for the distribution provenance JSON sidecar."
  ".provenance.json")

(def ^:const provenance-schema-version
  "Current schema version for distribution provenance records."
  "prf-distribution-provenance.v1")

(def ^:const nonexistent-file-path
  "Path guaranteed not to exist, used for missing-file test assertions."
  "/nonexistent/path/file.txt")

(def ^:const evidence-config-path
  "Evidence chain configuration file path (resource or filesystem)."
  "config/evidence.json")

(def ^:const confidence-config-path
  "Confidence derivation policy configuration file path."
  "config/confidence.edn")

(def ^:const results-runs-dir
  "Default root directory for run results."
  "results/runs")

(def ^:const test-artifacts-dir
  "Default root directory for test artifacts."
  "results/test-artifacts")

(def ^:const notebook-focus-path
  "Clerk notebook focus file path (contains a run-id)."
  "results/.notebook-focus")

(def ^:const resource-prefix
  "Prefix for classpath resource references."
  "resource:")

(def ^:const fixture-suite-manifest-path
  "Fixture suite manifest file path (bare — use resource-prefix for classpath)."
  "data/fixtures/suites/manifest.edn")

(def ^:const concept-registry-path
  "Concept registry file path (bare — use resource-prefix for classpath)."
  "data/concepts/registry.edn")

(def ^:const benchmark-registry-path
  "Benchmark pack registry resource path (with resource: prefix for classpath)."
  "resource:benchmarks/registry.edn")

(def ^:const benchmark-registry-bare-path
  "Benchmark pack registry filesystem path (no prefix for direct filesystem access)."
  "benchmarks/registry.edn")

(def ^:const scoring-robustness-dimensions-path
  "Scoring rule: robustness dimensions."
  "resource:benchmarks/scoring/robustness-dimensions-v0.edn")

(def ^:const scoring-binary-claims-path
  "Scoring rule: binary claims."
  "resource:benchmarks/scoring/binary-claims-v1.edn")

(def ^:const scoring-severity-weighted-path
  "Scoring rule: severity-weighted robustness."
  "resource:benchmarks/scoring/severity-weighted-robustness-v1.edn")

(def ^:const scoring-shortfall-allocation-path
  "Scoring rule: shortfall allocation."
  "resource:benchmarks/scoring/shortfall-allocation-v0.edn")

(def ^:const claim-registry-path
  "Benchmark claim registry file path."
  "benchmarks/claim-registry.edn")

(def ^:const command-registry-path
  "CLI command dispatch registry resource path."
  "prf/commands/registry.edn")

(def ^:const sew-pack-registry-path
  "Sew benchmark pack registry file path."
  "benchmarks/packs/sew/registry.edn")

(def ^:const prf-core-pack-registry-path
  "PRF-core benchmark pack registry file path."
  "benchmarks/packs/prf-core/registry.edn")

(def ^:const evidence-bundle-dir
  "Default directory for evidence bundles."
  "results/evidence-bundle")

(def ^:const data-dir
  "Root directory for data assets."
  "data")

(def ^:const fixtures-dir
  "Root directory for fixture data."
  "data/fixtures")

(def ^:const traces-dir
  "Directory for fixture trace files."
  "data/fixtures/traces")

(def ^:const golden-dir
  "Directory for golden fixture files."
  "data/fixtures/golden")

(def ^:const scenarios-edn-dir
  "Directory for EDN scenario files."
  "scenarios/edn")

(def ^:const trace-file-ext
  "File extension for trace files."
  ".trace.json")

(def ^:const deps-edn-path
  "Project deps.edn file path."
  "deps.edn")

(def ^:const bb-edn-path
  "Babashka project file path."
  "bb.edn")

(def ^:const reference-validation-suite-manifest
  "Reference validation suite manifest resource path."
  "resource:suites/reference-validation-v1/manifest.edn")

(def ^:const escrow-dispute-pack-path
  "Sew escrow dispute benchmark pack path (bare — add resource-prefix for classpath)."
  "benchmarks/packs/sew/escrow-dispute-v1.edn")

(def ^:const sha256-ref-pattern
  "Regex matching a canonical sha256 reference: sha256:<64 hex chars>."
  #"^sha256:[0-9a-f]{64}$")

(defn sha256-ref
  "Construct a canonical sha256 reference from a hex digest string.

   (sha256-ref \"abc...\") => \"sha256:abc...\"

   The hex string must be a 64-character lowercase hex SHA-256 digest.
   When the argument already has a sha256: prefix, returns it unchanged."
  [hex-or-ref]
  (if (and (string? hex-or-ref) (.startsWith hex-or-ref sha256-ref-prefix))
    hex-or-ref
    (str sha256-ref-prefix hex-or-ref)))

(defn parse-sha256-ref
  "Parse a canonical sha256 reference to its raw hex digest.

   Returns the 64-character lowercase hex string (without the sha256:
   prefix) when ref is a valid canonical reference, or nil otherwise.

   The return value is always a bare hex digest, suitable for use with
   sha256-ref to reconstruct the canonical form.  Callers MUST NOT add
   their own \"sha256:\" prefix to the result of parse-sha256-ref —
   use sha256-ref for construction.

   Example:
     (parse-sha256-ref \"sha256:abcd...64hex...\")
     => \"abcd...64hex...\"

     (parse-sha256-ref \"sha256:short\") => nil"
  [ref]
  (when (and (string? ref) (re-find sha256-ref-pattern ref))
    (subs ref (count sha256-ref-prefix))))

(defn valid-sha256-ref?
  "Return true if ref is a valid canonical sha256 reference string.
   Accepts strings of the form sha256:<64 lowercase hex chars>."
  [ref]
  (boolean (and (string? ref) (re-find sha256-ref-pattern ref))))

(defn sha256-ref-file
  "Compute the canonical sha256 reference for a file's content.
   Returns \"sha256:<hex>\" or nil if the file does not exist."
  [path]
  (let [f (java.io.File. path)]
    (when (.isFile f)
      (let [digest (MessageDigest/getInstance sha256-algorithm)]
        (.update digest (java.nio.file.Files/readAllBytes (.toPath f)))
        (str sha256-ref-prefix
             (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest digest))))))))
