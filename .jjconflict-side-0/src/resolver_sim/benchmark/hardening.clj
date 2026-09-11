(ns resolver-sim.benchmark.hardening
  "Env/CLI resolution for benchmark hardening-control knobs.

   Base defaults live in config/defaults.edn under :hardening (read through
   resolver-sim.config.defaults). This namespace layers operator overrides on
   top of that base:

     - parallel-ceiling           automatic-default cap for the
                                   parallel-benchmark-run capability composition
                                   (env PRF_PARALLEL_CEILING, CLI --parallel-ceiling);
     - quiescence-timeout-seconds post-run executor shutdown wait for the hardened
                                   benchmark runner
                                   (env PRF_QUIESCENCE_TIMEOUT_SECONDS,
                                    CLI --quiescence-timeout-seconds);
     - claimant-parallel-threshold claimant-count threshold for detached claimant
                                   parallelism
                                   (env PRF_CLAIMANT_PARALLEL_THRESHOLD,
                                    CLI --claimant-parallel-threshold).

   Resolution order for each knob: explicit CLI override > env var > config-file
   default > code-level fallback. Values are floored at 1; invalid/non-numeric
   env values fall back rather than throw, so a malformed environment can never
   break a run."
  (:require [clojure.string :as str]
            [resolver-sim.config.defaults :as config-defaults]))

(def ^:private default-parallel-ceiling 8)
(def ^:private default-quiescence-timeout-seconds 30)
(def ^:private default-claimant-parallel-threshold 16)

(defn- valid-positive-integer-string?
  "True when s is a canonical positive decimal integer string."
  [s]
  (and (string? s)
       (not (str/blank? s))
       (re-matches #"[0-9]+" s)
       (not (str/starts-with? s "0"))))

(defn- parse-int [s]
  (Long/parseLong s))

(defn- base-default [path code-fallback]
  (config-defaults/default path code-fallback))

(defn resolve-integer
  "Resolve an integer knob from a CLI value, an env map, and a base default.

   CLI value wins when present and valid; otherwise the env var is consulted;
   otherwise the base default. Invalid CLI/env values fall back to the base
   default and floor at 1."
  [cli-value env env-key base]
  (cond
    (and (some? cli-value) (integer? cli-value) (pos? cli-value))
    (max 1 cli-value)

    (valid-positive-integer-string? (get env env-key))
    (max 1 (parse-int (get env env-key)))

    :else
    (max 1 base)))

(defn parallel-ceiling
  "Resolve the parallel-benchmark-run automatic-default ceiling.

   CLI value wins, then PRF_PARALLEL_CEILING, then the config default."
  ([] (parallel-ceiling nil (or (System/getenv) {})))
  ([cli-value] (parallel-ceiling cli-value (or (System/getenv) {})))
  ([cli-value env]
   (resolve-integer cli-value env "PRF_PARALLEL_CEILING"
                    (base-default [:hardening :parallel-ceiling]
                                  default-parallel-ceiling))))

(defn quiescence-timeout-seconds
  "Resolve the hardened runner post-run shutdown wait in seconds.

   CLI value wins, then PRF_QUIESCENCE_TIMEOUT_SECONDS, then the config default."
  ([] (quiescence-timeout-seconds nil (or (System/getenv) {})))
  ([cli-value] (quiescence-timeout-seconds cli-value (or (System/getenv) {})))
  ([cli-value env]
   (resolve-integer cli-value env "PRF_QUIESCENCE_TIMEOUT_SECONDS"
                    (base-default [:hardening :quiescence-timeout-seconds]
                                  default-quiescence-timeout-seconds))))

(defn claimant-parallel-threshold
  "Resolve the claimant-count parallel threshold.

   CLI value wins, then PRF_CLAIMANT_PARALLEL_THRESHOLD, then the config default."
  ([] (claimant-parallel-threshold nil (or (System/getenv) {})))
  ([cli-value] (claimant-parallel-threshold cli-value (or (System/getenv) {})))
  ([cli-value env]
   (resolve-integer cli-value env "PRF_CLAIMANT_PARALLEL_THRESHOLD"
                    (base-default [:hardening :claimant-parallel-threshold]
                                  default-claimant-parallel-threshold))))
