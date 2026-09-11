(ns resolver-sim.config.defaults
  "Centralized tunable defaults loaded from config/defaults.edn.

   ════════════════════════════════════════════════════════════════
   CONFIGURATION AUTHORITY — DEFAULTS
   ════════════════════════════════════════════════════════════════

   Tunable numeric/simple defaults (asset decimals, pagination, limits,
   bar scales, terminal dimensions, demo sizes) belong in
   config/defaults.edn and are read through this namespace.

   Precedence for every default:
     1. PRF_DEFAULTS_CONFIG_PATH env var pointing at a custom .edn file
     2. config/defaults.edn on the resource/classpath or filesystem
     3. code-level fallback supplied by the caller

   Genuinely-semantic constants (basis-point denominators, exit codes,
   canonical formats) may stay as inline ^:const values in their owning
   namespaces; this namespace is for *operational* tunables.
   ════════════════════════════════════════════════════════════════"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def ^:private config-file
  "Default path to the defaults configuration file."
  "config/defaults.edn")

(def ^:private defaults
  (delay
    (or (when-let [path (System/getenv "PRF_DEFAULTS_CONFIG_PATH")]
          (try (-> (io/file path) slurp edn/read-string)
               (catch Exception e
                 (.println *err* (str "DEFAULTS-CONFIG-FAILURE: PRF_DEFAULTS_CONFIG_PATH="
                                      path " — " (.getMessage e)))
                 nil)))
        (when-let [r (io/resource config-file)]
          (try (-> r slurp edn/read-string)
               (catch Exception e
                 (.println *err* (str "DEFAULTS-CONFIG-FAILURE: resource " config-file
                                      " — " (.getMessage e)))
                 nil)))
        (try (-> config-file io/file slurp edn/read-string)
             (catch Exception e
               (.println *err* (str "DEFAULTS-CONFIG-FAILURE: " config-file
                                    " — " (.getMessage e)))
               nil)))))

(defn default
  "Resolve a nested default value by keyword path with a code-level fallback.
   (default [:yield :default-asset-decimals] 18) => 18"
  [path fallback]
  (let [cfg @defaults]
    (if (nil? cfg)
      fallback
      (get-in cfg path fallback))))