(ns resolver-sim.benchmark.claim-registry
  "Single authority for resolving, loading, and validating the benchmark claim
   registry (the auditor-facing claim definitions EDN).

   Every consumer of claim definitions MUST resolve through this namespace so a
   single selection boundary applies everywhere:

     explicit CLI path → PRF_BENCHMARKS_CLAIM_REGISTRY → repository default

   The selected registry is validated fail-closed: a missing file, malformed
   EDN, unsupported schema version, duplicate claim IDs, missing required keys,
   or an :claim/evaluator that has no compiled evaluator all make the registry
   unrunnable.  There is deliberately NO fallback to the built-in registry when
   an external one was requested — otherwise an auditor could think a custom
   registry governed the run while the built-in one actually did.

   External registries may select any claim whose evaluator is compiled into
   the running jar (resolver-sim.benchmark.claims/evaluator-registry); they
   cannot invent evaluator code (e.g. :claim/evaluator :auditor/my-code)."
  (:require [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.io.resource-path :as rp]))

;; ── Path constants ───────────────────────────────────────────────────────────

(def supported-registry-schema-versions
  "Schema versions accepted for the claim registry document.
   The repository registry declares :claim-registry/version 1."
  #{1})

(def required-claim-keys
  "Keys every claim entry must carry."
  [:claim/id :claim/title :claim/description :claim/property-types :claim/evaluator])

;; ── Path resolution (single boundary) ───────────────────────────────────────

(defn env-var
  "Read a process environment variable. Indirection keeps the selection
   boundary testable (tests may redef this var)."
  [k]
  (System/getenv k))

(defn claim-registry-path
  "Resolve the claim registry path with precedence:
   explicit CLI path → PRF_BENCHMARKS_CLAIM_REGISTRY → repository default.
   Returns a path string; nil when no explicit path was given (callers falling
   back to the default should use default-claim-registry-path)."
  ([]
   (claim-registry-path nil))
  ([cli-path]
   (or (when (and (string? cli-path) (seq cli-path)) cli-path)
       (when-let [env (env-var "PRF_BENCHMARKS_CLAIM_REGISTRY")]
         (when (seq env) env))
       hash-ref/claim-registry-path)))

(defn claim-registry-source
  "Which boundary selected the registry: :cli | :environment | :default."
  ([]
   (claim-registry-source nil))
  ([cli-path]
   (cond
     (and (string? cli-path) (seq cli-path)) :cli
     (and (env-var "PRF_BENCHMARKS_CLAIM_REGISTRY")
          (seq (env-var "PRF_BENCHMARKS_CLAIM_REGISTRY"))) :environment
     :else :default)))

(defn default-claim-registry-path
  "The repository default claim registry path (benchmarks/claim-registry.edn)."
  []
  hash-ref/claim-registry-path)

(defn registry-file-sha256
  "Compute a canonical sha256 ref for the selected registry file, whether it
   resolves as a filesystem path or a resource: path. Returns nil when the
   file cannot be read."
  [path]
  (try
    (let [digest (java.security.MessageDigest/getInstance "SHA-256")]
      (with-open [in (rp/open-input-stream path)]
        (let [buf (byte-array 65536)]
          (loop []
            (let [n (.read in buf)]
              (when (pos? n)
                (.update digest buf 0 n)
                (recur)))))
        (str hash-ref/sha256-ref-prefix
             (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest digest))))))
    (catch Exception _ nil)))

;; ── Loading ─────────────────────────────────────────────────────────────────

(defn- read-registry-edn
  [path source]
  (try
    (rp/edn-read path)
    (catch Exception e
      (throw (ex-info "Claim registry unreadable"
                      {:claim-registry/path path
                       :claim-registry/source source
                       :error (.getMessage e)})))))

(defn- schema-version
  [data]
  (or (:claim-registry/version data) (:registry/version data) 1))

(defn- claim-entries
  [data]
  (:claims data []))

;; ── Fail-closed validation ──────────────────────────────────────────────────

(defn validate-claim-registry
  "Validate registry data fail-closed. Returns a vector of error maps.

   Errors (each {:kind kw ...}):
     :missing-file          — path does not resolve to an existing file
     :unreadable            — EDN parse failure
     :unsupported-schema    — :claim-registry/version not in
                              supported-registry-schema-versions
     :not-a-sequence        — :claims is not a sequential collection
     :duplicate-claim-id    — two entries share :claim/id
     :missing-required-key  — entry missing a key in required-claim-keys
     :unknown-evaluator     — :claim/evaluator has no compiled evaluator

   `evaluator-available?` is a predicate over the evaluator keyword; it defaults
   to a function that resolves through resolver-sim.benchmark.claims/evaluator-resolver
   when available, else returns false for every keyword (fail closed).

   `unknown-evaluator-fatal?` controls whether :unknown-evaluator is an error.
   External registries (CLI/env) must set it true — an auditor must not be able
   to invent evaluator code. The repository default registry may declare claims
   whose evaluators are not yet compiled (known gaps surfaced by coverage), so
   callers loading the default registry should pass false."
  ([data] (validate-claim-registry data nil true))
  ([data evaluator-available?] (validate-claim-registry data evaluator-available? true))
  ([data evaluator-available? unknown-evaluator-fatal?]
   (let [avail (or evaluator-available?
                   (try
                     (let [resolver (requiring-resolve
                                     'resolver-sim.benchmark.claims/evaluator-resolver)]
                       (fn [k] (boolean (resolver k))))
                     (catch Exception _ (fn [_] false))))
         claims (claim-entries data)
         structural-errors (cond-> []
                             (not (map? data))
                             (conj {:kind :not-a-map
                                    :value-type (some-> data type str)})

                             (and (map? data)
                                  (not (contains? supported-registry-schema-versions (schema-version data))))
                             (conj {:kind :unsupported-schema
                                    :schema-version (schema-version data)
                                    :supported (vec supported-registry-schema-versions)})

                             (and (map? data) (not (sequential? claims)))
                             (conj {:kind :not-a-sequence
                                    :claims-type (some-> claims type str)}))]
     (cond-> structural-errors
       (sequential? claims)
       (into (mapcat (fn [entry]
                       (let [cid (:claim/id entry)
                             missing (remove #(contains? entry %) required-claim-keys)]
                         (cond-> []
                           (seq missing)
                           (conj {:kind :missing-required-key
                                  :claim/id cid
                                  :missing (vec missing)})
                           (and unknown-evaluator-fatal?
                                (not (avail (:claim/evaluator entry))))
                           (conj {:kind :unknown-evaluator
                                  :claim/id cid
                                  :claim/evaluator (:claim/evaluator entry)}))))
                     claims))

       (sequential? claims)
       (into (map (fn [group] {:kind :duplicate-claim-id :claim/id (:claim/id (first group))})
                  (filter #(> (count %) 1) (vals (group-by :claim/id claims)))))))))

(defn load-claim-registry
  "Resolve, load, and fail-closed validate the claim registry.

   Returns {:claim-registry/path str
            :claim-registry/source :cli|:environment|:default
            :claim-registry/version int
            :claim-registry/data <raw-document-map>
            :claims [entry ...]
            :claim-map {claim-id entry}}.

   Throws ex-info on any validation error (missing file, malformed EDN,
   unsupported schema version, duplicate claim IDs, missing keys, unknown
   evaluator). There is never a silent fallback to the built-in registry."
  ([]
   (load-claim-registry nil))
  ([cli-path]
   (let [path (claim-registry-path cli-path)
         source (claim-registry-source cli-path)
         external? (contains? #{:cli :environment} source)]
     (when-not (rp/path-exists? path)
       (throw (ex-info "Claim registry not found"
                       {:kind :missing-file
                        :claim-registry/path path
                        :claim-registry/source source})))
     (let [data (read-registry-edn path source)]
       (when-let [errors (seq (validate-claim-registry data nil external?))]
         (throw (ex-info "Claim registry validation failed"
                         {:kind :claim-registry-invalid
                          :claim-registry/path path
                          :claim-registry/source source
                          :external external?
                          :errors errors})))
       {:claim-registry/path path
        :claim-registry/source source
        :claim-registry/version (schema-version data)
        :claim-registry/data data
        :claims (claim-entries data)
        :claim-map (into {} (map (fn [c] [(:claim/id c) c])) (claim-entries data))}))))
