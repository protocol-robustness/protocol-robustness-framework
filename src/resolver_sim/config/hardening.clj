(ns resolver-sim.config.hardening
  "Generic, typed resolver for operational hardening settings.

  ════════════════════════════════════════════════════════════════
  CONFIGURATION AUTHORITY — OPERATIONAL HARDENING SETTINGS
  ════════════════════════════════════════════════════════════════

  Centralises *operational* constants (timeouts, pool sizes, thread
  caps, byte/char limits, rate limits, ports, region) that previously
  lived as hardcoded literals scattered across namespaces.

  Base values live in config/defaults.edn under :hardening (read
  through resolver-sim.config.defaults). This namespace layers typed
  resolution on top, with an explicit no-surprises precedence:

      1. explicit CLI value supplied at the call site (non-nil)
      2. environment variable (PRF_<KEY_SNAKE>)
      3. config/defaults.edn :hardening value
      4. code-level fallback supplied at the call site

  Resolution is TYPED and FAIL-CLOSED: every setting declares a spec
  (:integer, :positive-integer, or :string). A value present at an
  explicit level (CLI or env) that cannot be coerced to its spec FAILS
  configuration — it does NOT silently fall through to the next source.
  For example, PRF_LAB_HTTP_PORT=banana raises rather than quietly
  using the default port.

  This namespace owns only the keys listed in `specs`. Keys already
  owned by resolver-sim.benchmark.hardening (the benchmark hardening
  mechanism) are deliberately NOT duplicated here, to avoid two
  independent resolvers claiming the same key. The benchmark mechanism
  itself reads config/defaults.edn via resolver-sim.config.defaults,
  so there is no second source of truth — only an additional
  CLI/env layer for the benchmark-specific subset.

  Design notes:
    - No process-global mutable override registry. Callers pass
      :cli-value and :fallback explicitly per call site, which keeps
      tests, multiple entry points, and embedded use composable.
    - Resolution is lazy (per call), so binding env vars or test
      bindings around a resolved value works as expected. Consumers
      resolve at construction/entry boundaries rather than freezing
      values in top-level defs where avoidable."
  (:require [clojure.string :as str]
            [resolver-sim.config.defaults :as config-defaults]))

(defn env-name
  "Convert a hardening keyword to its conventional env var name.
  Example: :lab-http-port -> \"PRF_LAB_HTTP_PORT\""
  [k]
  (str "PRF_" (str/upper-case (str/replace (name k) "-" "_"))))

(def ^:private specs
  "Per-key validation/coercion metadata. Every key resolved through
  `value` must appear here.

  Type tags:
    :integer            — bounded integer (optionally :min/:max)
    :positive-integer   — integer > 0
    :string             — non-blank string"
  {:lab-http-port                  {:type :integer :min 1 :max 65535}
   :lab-http-region                {:type :string}
   :lab-http-max-body-bytes        {:type :positive-integer}
   :lab-http-timeout-ms            {:type :positive-integer}
   :lab-http-max-concurrent-runs   {:type :positive-integer}
   :lab-http-rate-limit-window-ms  {:type :positive-integer}
   :lab-http-rate-limit-max-requests {:type :positive-integer}
   :lab-http-thread-pool-size      {:type :positive-integer}
   :lab-exec-timeout-ms            {:type :positive-integer}
   :lab-publish-s3-timeout-s       {:type :positive-integer}
   :publish-timeout-ms             {:type :positive-integer}
   :publish-max-io-chars           {:type :positive-integer}
   :publish-max-request-bytes      {:type :positive-integer}
   :sentinel-timeout-ms            {:type :positive-integer}
   :sentinel-max-io-chars          {:type :positive-integer}
   :sentinel-max-request-bytes     {:type :positive-integer}
   :resubmission-max-request-bytes {:type :positive-integer}
   :db-pool-size                   {:type :positive-integer}
   :db-pool-min-idle               {:type :positive-integer}
   :db-pool-idle-timeout-ms        {:type :positive-integer}
   :db-pool-connection-timeout-ms  {:type :positive-integer}
   :db-pool-max-lifetime-ms        {:type :positive-integer}
   :tsa-timeout-ms                 {:type :positive-integer}
   :conformance-max-bundle-bytes   {:type :positive-integer}
   :notebook-port                  {:type :integer :min 1 :max 65535}
   :notebook-api-port              {:type :integer :min 1 :max 65535}})

(defn load-defaults
  "Return the parsed :hardening map from config/defaults.edn, or {} when
  defaults are unavailable (e.g. in some test setups)."
  []
  (let [cfg (config-defaults/default [:hardening] nil)]
    (or (and (map? cfg) cfg) {})))

(defn ^:private fail
  "Raise a fail-closed configuration error for `key`."
  [key msg]
  (throw (ex-info (str "invalid hardening config for " (name key) ": " msg)
                  {:key key
                   :env-var (env-name key)
                   :reason msg})))

(defn ^:private parse-long*
  "Parse a trimmed string into a long, failing closed on bad input."
  [key s]
  (try (Long/parseLong (str/trim s))
       (catch NumberFormatException _
         (fail key (str "expected an integer, got: " s)))))

(defn ^:private coerce
  "Coerce a raw value (integer from defaults.edn, string from env, or
  either from CLI) to its specced type. Fails closed on mismatch."
  [key spec v]
  (let [t (:type spec)]
    (case t
      (:integer :positive-integer)
      (let [n (if (string? v)
                (parse-long* key v)
                (if (integer? v)
                  (long v)
                  (fail key (str "expected integer, got: " (type v)))))]
        (when (and (contains? spec :min) (neg? (compare n (:min spec))))
          (fail key (str "must be >= " (:min spec) ", got " n)))
        (when (and (contains? spec :max) (pos? (compare n (:max spec)))
                   (not (= n (:max spec))))
          (fail key (str "must be <= " (:max spec) ", got " n)))
        (when (and (= t :positive-integer) (not (pos? n)))
          (fail key (str "must be positive, got " n)))
        n)

      :string
      (if (string? v)
        (if (str/blank? v)
          (fail key "must be a non-blank string")
          v)
        (fail key (str "expected string, got: " (type v))))

      (fail key (str "unsupported spec type: " t)))))

(defn value
  "Resolve a single hardening setting by keyword.

  Resolution: explicit CLI value (non-nil) -> env var PRF_<KEY> ->
  config/defaults.edn :hardening -> code-level fallback.

  The first PRESENT value (CLI non-nil, or env non-blank) is parsed and
  validated against the key's spec; a malformed value at an explicit
  level fails closed rather than falling through. The config default
  and code fallback are also validated for consistency.

  opts:
    :cli-value  — explicit override from the CLI layer (any type)
    :fallback   — code-level fallback used when nothing else is set
                  (default :none => error if unresolved)
    :env        — env map (defaults to (System/getenv)); injected for tests"
  ([key] (value key nil))
  ([key opts]
   (let [env-map (or (:env opts) (System/getenv))
         spec (specs key)]
     (when-not spec
       (fail key "unknown hardening key (not registered in specs)"))
     (let [base (config-defaults/default [:hardening key]
                                         (:fallback opts :none))
           cli-value (:cli-value opts)
           env-var (env-name key)
           env-val (get env-map env-var)]
       (cond
         (some? cli-value)
         (coerce key spec cli-value)

         (some? env-val)
         (let [s (str/trim (str env-val))]
           (if (str/blank? s)
             ;; A set-but-empty env var is treated as absent (fall through),
              ;; matching the convention that empty == unset. A non-empty but
              ;; malformed value fails closed below.
             (coerce key spec base)
             (coerce key spec s)))

         :else
         (coerce key spec base))))))