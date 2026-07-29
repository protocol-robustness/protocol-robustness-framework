(ns resolver-sim.evidence.config
  "Canonical evidence chain configuration from config/evidence.json.

   ════════════════════════════════════════════════════════════════
   CONFIGURATION AUTHORITY
   ════════════════════════════════════════════════════════════════

   This namespace is the ONLY explicit, documented loader for
   evidence chain configuration.  The authoritative source is:

     config/evidence.json

   with optional overrides via PRF_EVIDENCE_CONFIG_PATH (env var)
   and PRF_ARTIFACT_DIR (env var / thread-local binding).

   All other files under config/ that are NOT loaded here or in
   resolver-sim.definitions.passive-registries have NO effect on
   runtime behaviour.  See resolver-sim.definitions.passive-registries
   for code-defined policy registries.

   ════════════════════════════════════════════════════════════════

   All consumers should read from this namespace rather than hardcoding paths or versions."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [resolver-sim.hash.reference :as hash-ref]))

(def ^:private config
  (delay
    (or (when-let [path (System/getenv "PRF_EVIDENCE_CONFIG_PATH")]
          (try (-> (io/file path) slurp (json/read-str :key-fn keyword))
               (catch Exception e
                 (.println *err* (str "CONFIG-FAILURE: PRF_EVIDENCE_CONFIG_PATH=" path " — " (.getMessage e)))
                 nil)))
        (when-let [r (io/resource hash-ref/evidence-config-path)]
          (try (-> r slurp (json/read-str :key-fn keyword))
               (catch Exception e
                 (.println *err* (str "CONFIG-FAILURE: resource " hash-ref/evidence-config-path " — " (.getMessage e)))
                 nil)))
        (try (-> hash-ref/evidence-config-path io/file slurp (json/read-str :key-fn keyword))
             (catch Exception e
               (.println *err* (str "CONFIG-FAILURE: " hash-ref/evidence-config-path " — " (.getMessage e)))
               nil)))))

(defn get-config
  "Return the full evidence config map, reading from disk on first call."
  []
  @config)

(def ^:dynamic *artifact-dir*
  "Thread-local override for artifact directory.
   When set via binding, artifact-dir returns this value instead of
   consulting env var / config file. Used by parallel test runner to
   give each namespace its own artifact directory."
  nil)

(defn schema
  "Resolve a schema key to its version string, e.g. (schema :test-summary) → \"test-summary.v2\""
  [k]
  (get-in (get-config) [:schemas (keyword (name k))]))

(defn producer
  "Resolve a producer key to its ID string, e.g. (producer :summary) → \"summary-emitter.v1\""
  [k]
  (get-in (get-config) [:producers (keyword (name k))]))

(defn artifact
  "Return the artifact definition map for the given id keyword or string."
  [artifact-id]
  (let [id-str (name artifact-id)
        arts (get (get-config) :artifacts [])]
    (some #(when (= (:id %) id-str) %) arts)))

(defn artifact-dir
  "Return the artifact directory path.
   Checks *artifact-dir* thread-local override first (for per-thread isolation),
   then PRF_ARTIFACT_DIR env var (for per-run workspaces),
   then config/evidence.json's artifact_dir,
   then a default for standalone operation."
  []
  (or *artifact-dir*
      (System/getenv "PRF_ARTIFACT_DIR")
      (get (get-config) :artifact_dir)
      hash-ref/test-artifacts-dir))

(defn artifact-file
  "Resolve an artifact id to its filename, e.g. (artifact-file :test-summary) → \"test-summary.json\"."
  [artifact-id]
  (get (artifact artifact-id) :file))

(defn artifact-path
  "Resolve an artifact id to its full path relative to project root."
  [artifact-id]
  (let [adir (artifact-dir)
        f    (artifact-file artifact-id)]
    (str adir "/" f)))

(defn contract-version []
  (get (get-config) :contract_version))

(defn rounding-policy []
  (get (get-config) :rounding_policy))

(defn framework []
  (get (get-config) :framework))

(defn runs-root []
  (or (System/getenv "PRF_RUNS_ROOT")
      (get (get-config) :runs_root)
      hash-ref/results-runs-dir))

(defn evidence-bundle-dir []
  (or (System/getenv "PRF_BUNDLE_DIR")
      (get (get-config) :evidence_bundle_dir)
      hash-ref/evidence-bundle-dir))

(defn strict-mode?
  "Return true when strict validation mode is enabled in config.
   In strict mode, recommended checks are promoted to required (warnings → failures)."
  []
  (boolean (get (get-config) :strict_mode false)))

;; ── Confidence policy ─────────────────────────────────────────────────────────

(def ^:private confidence-policy-config
  "Delay-loaded confidence derivation policy from config/confidence.edn.
   Override with PRF_CONFIDENCE_POLICY_PATH env var."
  (delay
    (or (when-let [path (System/getenv "PRF_CONFIDENCE_POLICY_PATH")]
          (try (-> (io/file path) slurp edn/read-string)
               (catch Exception e
                 (.println *err* (str "CONFIDENCE-POLICY-FAILURE: PRF_CONFIDENCE_POLICY_PATH="
                                      path " — " (.getMessage e)))
                 nil)))
        (when-let [r (io/resource hash-ref/confidence-config-path)]
          (try (-> r slurp edn/read-string)
               (catch Exception e
                 (.println *err* (str "CONFIDENCE-POLICY-FAILURE: resource " hash-ref/confidence-config-path
                                      " — " (.getMessage e)))
                 nil)))
        (try (-> hash-ref/confidence-config-path io/file slurp edn/read-string)
             (catch Exception e
               (.println *err* (str "CONFIDENCE-POLICY-FAILURE: " hash-ref/confidence-config-path
                                    " — " (.getMessage e)))
               nil)))))

(defn confidence-policy
  "Return the confidence derivation policy map, or nil if no config file found.
   Callers should supply their own code-level defaults when this returns nil."
  []
  @confidence-policy-config)
