(ns resolver-sim.run.bundle-root
  "Bundle root construction and validation for reproducible suite runs.
   A bundle root captures the run request, registry snapshot, execution
   summary, and normalized overview hash M-bM-^@M-^T enough information to re-run
   the same suite and verify the same execution graph.

   Usage:
     (require '[resolver-sim.run.bundle-root :as br])

     (br/build-bundle-root run-request run-result)
     ;; => {:bundle/schema-version \"bundle-root.v1\"
     ;;     :bundle/id \"<hash>\"
     ;;     :run/request {...}
     ;;     :registry/snapshot {...}
     ;;     :execution/summary {...}
     ;;     :overview/hash \"...\"}

     (br/runnable? bundle-root registries)
     ;; => {:runnable? true} | {:runnable? false :errors [...]}"
  (:require [clojure.walk :as walk]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.io.paths :as paths]
            [resolver-sim.run.overview :as overview]
            [resolver-sim.run.criteria :as criteria]
            [resolver-sim.sensitivity.propagation :as prop]
            [resolver-sim.sensitivity.report :as sens-report]
            [resolver-sim.vcs :as vcs])
  (:import [java.security MessageDigest]
           [java.util Arrays]))

(def ^:const schema-version "bundle-root.v2")
(def ^:const legacy-schema-version "bundle-root.v1")

(declare compute-json-hash)

(defn- json-encode
  "Canonical JSON encoding: sorted keys, no whitespace.
   Handles maps, vectors, strings, numbers, booleans, nil, keywords (as strings)."
  [v]
  (cond
    (nil? v) "null"
    (instance? Boolean v) (if v "true" "false")
    (number? v) (pr-str v)
    (instance? String v) (str "\"" (-> v (.replace "\\" "\\\\") (.replace "\"" "\\\"")) "\"")
    (keyword? v) (json-encode (str v))
    (instance? java.util.Map v)
    (let [kvs (sort (map (fn [[k v]] (str (json-encode k) ":" (json-encode v))) v))]
      (str "{" (clojure.string/join "," kvs) "}"))
    (coll? v)
    (let [items (map json-encode v)]
      (str "[" (clojure.string/join "," items) "]"))
    :else (json-encode (str v))))

(defn- json-encode-safe
  "Like json-encode but handles keywords and non-String keys by converting them."
  [v]
  (letfn [(kfn [k] (if (keyword? k) (str k) (str k)))]
    (cond
      (instance? java.util.Map v)
      (let [kvs (sort (map (fn [[k v]] (str (json-encode (kfn k)) ":" (json-encode v))) v))]
        (str "{" (clojure.string/join "," kvs) "}"))
      :else (json-encode v))))

(defn- json-canonical-bytes
  "Serialize a value to canonical UTF-8 JSON bytes (safe encoding)."
  [v]
  (.getBytes (json-encode-safe v) "UTF-8"))

(defn- protocol-state-wire-value
  "Convert a protocol-state witness into a JSON-native shape without losing
   keyword namespaces. This is the cross-language verification surface."
  [v]
  (cond
    (map? v) (into (sorted-map)
                   (map (fn [[k value]] [(str k) (protocol-state-wire-value value)]) v))
    (vector? v) (mapv protocol-state-wire-value v)
    (set? v) (mapv protocol-state-wire-value (sort v))
    (keyword? v) (str v)
    :else v))

(defn protocol-state-witness-hash
  "SHA-256 of canonical JSON for the JSON-native protocol-state witness.
   This intentionally has no Clojure-only domain/projection semantics so an
   independent verifier can recompute it with standard canonical JSON rules."
  [witness]
  (let [digest (MessageDigest/getInstance "SHA-256")
        bytes (json-canonical-bytes witness)]
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest digest bytes)))))

;; ── Protocol state hashing ────────────────────────────────────────────────────

(defn- hash-sorted-map
  "Compute a deterministic hash of a map by sorting all keys recursively.
   Only includes keys whose values are maps — filters out fns, refs, etc.
   Returns nil if the map is empty or nil."
  [label m]
  (when (seq m)
    (letfn [(sorted [x]
              (cond
                (instance? java.util.Map x)
                (into (sorted-map) (map (fn [[k v]] [(str k) (sorted v)]) x))
                (vector? x) (mapv sorted x)
                (keyword? x) (name x)
                (instance? clojure.lang.Var x) (str (.sym ^clojure.lang.Var x))
                (fn? x) (str (.getName (class x)))
                :else x))]
      (let [cleaned (sorted m)]
        (hc/hash-with-intent {:hash/intent :protocol-state :section label} cleaned)))))

;; ── Registry snapshot helpers ─────────────────────────────────────────────────

(defn- canonicalize-registry
  "Prepare a registry value for hashing: sets become sorted vectors,
   symbols become strings, fns become their names."
  [registry]
  (walk/postwalk
   (fn [x]
     (cond
       (set? x) (vec (sort x))
       (symbol? x) (str x)
       (fn? x) (str (some-> x meta :name (or (class x))))
       (instance? clojure.lang.Var x) (str (.sym ^clojure.lang.Var x))
       :else x))
   registry))

(defn- registry-hash
  "Compute a stable hash of a registry value."
  [registry]
  (hc/hash-with-intent {:hash/intent :registry} (canonicalize-registry registry)))

(defn- lazy-resolve
  "Resolve a var by fully-qualified symbol, loading the namespace if needed."
  [sym]
  (try
    (when-let [v (requiring-resolve sym)]
      @v)
    (catch Exception _ nil)))

(defn- lookup-orchestrator-id
  "Resolve the orchestrator-id for a given runner-id from
   execution-runner-definitions at runtime."
  [runner-id]
  (when runner-id
    (when-let [defs (lazy-resolve
                     'resolver-sim.definitions.passive-registries/execution-runner-definitions)]
      (some #(when (= (:id %) runner-id) (:orchestrator-id %)) defs))))

(defn registry-snapshot
  "Compute a snapshot of all active registries at call time.
   Uses lazy resolution to avoid hard dependencies on every registry namespace.
   Returns a map suitable for :registry/snapshot in the bundle root."
  []
  (let [exec-reg (lazy-resolve 'resolver-sim.definitions.passive-registries/execution-registry)
        ep-reg   (lazy-resolve 'resolver-sim.definitions.passive-registries/evidence-policy-registry)
        att-reg  (lazy-resolve 'resolver-sim.definitions.passive-registries/attestor-registry)
        cd-reg   (lazy-resolve 'resolver-sim.definitions.passive-registries/claim-definition-registry)
        suites   (lazy-resolve 'resolver-sim.scenario.suites/suites)
        protocols (try
                    (let [v (requiring-resolve 'resolver-sim.protocols.registry/known-protocol-ids)]
                      (vec (v)))
                    (catch Exception _ nil))]
    {:attestor-registry-hash (when att-reg (registry-hash att-reg))
     :scenario-suite-hash (when suites (registry-hash suites))
     :dispatcher-registry-hash (when protocols (registry-hash protocols))
     :execution-registry-hash (when exec-reg (registry-hash exec-reg))
     :evidence-policy-hash (when ep-reg (registry-hash ep-reg))
     :claim-definition-registry-hash (when cd-reg (registry-hash cd-reg))}))

;; ── Environment helpers ───────────────────────────────────────────────────────

(defn- git-commit
  "Current VCS HEAD SHA via the jj-then-git resolver, or nil when unavailable."
  []
  (vcs/commit-sha))

(defn- git-dirty?
  "True when the working copy has uncommitted changes (jj-then-git)."
  []
  (vcs/dirty?))

(defn environment
  "Capture the current execution environment."
  []
  {:git/commit (git-commit)
   :git/dirty? (git-dirty?)
   :clojure/version (clojure-version)
   :java/version (System/getProperty "java.version")
   :os/name (System/getProperty "os.name")})

;; ── Bundle root builder ──────────────────────────────────────────────────────

(defn build-bundle-root
  "Build a bundle root from a run request and run result.
   request M-bM-^@M-^T the :scenario-run/request map
   result  M-bM-^@M-^T the :scenario-run/result map
   Optional :protocol/force-authorisations and :protocol/force-authorisations-consumed
   keys in result are emitted as a forensic protocol-state witness and hashed
   into :protocol/state-hashes.
   Returns a bundle-root.v2 map with:
   - run request (for reproducibility)
   - registry snapshot hashes
   - protocol state hashes (when provided)
   - execution environment
   - execution summary
   - normalized overview hash
   - a self-referential canonical-JSON hash (standalone-verifiable)\n\n   Historical bundle-root.v1 values remain identifiable through\n   `verify-bundle-content`, but cannot be classified as content-verified\n   runnable roots because their binary/domain preimage differs."
  [request result]
  (let [overview (overview/build-overview result)
        overview-h (overview/overview-hash overview)
        req (select-keys request
                         [:runner/backend :runner-selection
                          :suite/key :protocol/default-id
                          :evidence/profile :output/profile])
        runner-id (get-in request [:runner-selection :runner-id])
        orch-id (lookup-orchestrator-id runner-id)
        proto-fa (hash-sorted-map :force-authorisations
                                  (:protocol/force-authorisations result))
        proto-fa-consumed (hash-sorted-map :force-authorisations-consumed
                                           (:protocol/force-authorisations-consumed result))
        proto-held-adjustments (hash-sorted-map :held-adjustments
                                                (:protocol/held-adjustments result))
        ;; Canonical protocol descriptor.
        ;; Grammar: <name>-v<N> where <name> = [a-z][a-z0-9]* (lowercase, no hyphens/underscores)
        ;;          and <N>    = [1-9][0-9]* (positive integer, no leading zero).
        ;; Examples: "sew-v1", "yield-v1", "yield-v2".
        ;; Deliberately excludes hyphens in the name to avoid ambiguous splitting.
        ;; The version is always emitted as a string, never an integer, so that
        ;; cross-language consumers (Python, etc.) can reliably compare by string equality.
        proto-id (:protocol/default-id request)
        proto-descriptor (when proto-id
                           (let [pid (if (keyword? proto-id) (name proto-id) (str proto-id))
                                 valid-pattern #"^[a-z][a-z0-9]*-v[1-9][0-9]*$"]
                             (when-not (re-matches valid-pattern pid)
                               (throw (ex-info "Malformed protocol/default-id — expected format '<name>-v<N>'"
                                               {:protocol/default-id proto-id
                                                :valid-format "<name>-v<N>"
                                                :observed pid})))
                             (let [version-idx (clojure.string/last-index-of pid "-v")]
                               {:id (subs pid 0 version-idx)
                                :version (subs pid (+ version-idx 2))})))
        proto-hashes (cond-> {}
                       proto-fa (assoc :force-authorisations/hash proto-fa)
                       proto-fa-consumed (assoc :force-authorisations/consumed-hash proto-fa-consumed)
                       proto-held-adjustments (assoc :held-adjustments/hash proto-held-adjustments))
        proto-state (cond-> {}
                      (seq (:protocol/force-authorisations result))
                      (assoc :force-authorisations (:protocol/force-authorisations result))
                      (seq (:protocol/force-authorisations-consumed result))
                      (assoc :force-authorisations/consumed (:protocol/force-authorisations-consumed result))
                      (seq (:protocol/held-adjustments result))
                      (assoc :held-adjustments (:protocol/held-adjustments result)))
        proto-witness (protocol-state-wire-value proto-state)
        proto-witness-hash (when (seq proto-witness)
                             (protocol-state-witness-hash proto-witness))
        ;; Compute run-level sensitivity from all scenario results
        results (:results result [])
        run-sensitivity (prop/merge-sensitivity
                         (mapv prop/effective-scenario-sensitivity results))
        base {:bundle/schema-version schema-version
              :run/request (assoc req
                                  :registry-key (or (:registry-key request) :default)
                                  :workspace (or (:workspace request) :current)
                                  :orchestrator/id orch-id)
              :orchestrator/id orch-id
              :registry/snapshot (registry-snapshot)
              :run/environment (environment)
              :execution/summary (select-keys result [:totals :status])
              :overview/hash overview-h
              :overview overview}
        base (cond-> base
               proto-descriptor (assoc :protocol proto-descriptor)
               (seq proto-hashes) (assoc :protocol/state-hashes proto-hashes)
               (seq proto-witness) (assoc :protocol/state proto-witness
                                          :protocol/state-witness-hash proto-witness-hash))
        ;; Every emitted bundle field is included in the content-addressed preimage.
        ;; Downstream lifecycle objects must reference this bundle; they must not enrich it.
        ;; Lightweight sensitivity summary (no independently constructed
        ;; provenance — full provenance lives in the persisted sensitivity
        ;; report which downstream consumers reference by hash).
        base (cond-> base
               run-sensitivity
               (assoc :bundle/sensitivity
                      {:sentinel/run-level (:level run-sensitivity)
                       :sentinel/risk-meta (:risk-meta run-sensitivity)
                       :sentinel/risk-meta-hash (when-let [rm (:risk-meta run-sensitivity)]
                                                  (hc/hash-with-intent
                                                   {:hash/intent :evidence-record}
                                                   rm))
                       :sentinel/scenario-count (count results)
                       :sentinel/sensitive-scenario-count
                       (count (filter (fn [r]
                                        (let [s (get-in r [:scenario-metadata :scenario/sensitivity])]
                                          (and s (not= :sensitivity/public (:level s)))))
                                      results))
                       :sentinel/report-reference
                       {:schema "sensitivity-report-reference"
                        :format sens-report/report-schema-version
                        :path paths/sensitivity-report}}))
        ;; :bundle/hash is the portable canonical-JSON content address verified
        ;; by compute-json-hash. Keeping construction and verification on this
        ;; single projection prevents an ID/hash pair from blessing altered data.
        bundle-hash (compute-json-hash base)]
    (assoc base :bundle/id bundle-hash :bundle/hash bundle-hash)))

;; ── Bundle root validation ────────────────────────────────────────────────────

(defn compute-json-hash
  "Compute the canonical content address of a bundle root.
   Strips :bundle/id and :bundle/hash, serializes the rest as canonical
   JSON (sorted keys, no whitespace), then computes
   SHA-256(\"BUNDLE_ROOT_V2\" || canonical-json-bytes).

   This function defines the bundle-root.v2 contract. Returns a 64-char hex
   string and is the authoritative value for both
   :bundle/id and :bundle/hash and is verifiable without a Clojure runtime."
  [bundle-root]
  (let [preimage (dissoc bundle-root :bundle/id :bundle/hash)
        json-str (json-encode-safe preimage)
        tag-bytes (.getBytes "BUNDLE_ROOT_V2" "UTF-8")
        canon-bytes (.getBytes json-str "UTF-8")
        combined (Arrays/copyOf tag-bytes (+ (alength tag-bytes) (alength canon-bytes)))
        _ (System/arraycopy canon-bytes 0 combined (alength tag-bytes) (alength canon-bytes))
        digest (MessageDigest/getInstance "SHA-256")
        hash-bytes (.digest digest combined)]
    (apply str (map #(format "%02x" (bit-and % 0xff)) hash-bytes))))

(defn compute-v1-hash
  "Compute the historical bundle-root.v1 binary/domain hash without changing
   its meaning. This is retained only to identify legacy commitments."
  [bundle-root]
  (hc/hash-with-intent {:hash/intent :bundle-root}
                       (dissoc bundle-root :bundle/id :bundle/hash)))

(defn verify-bundle-content
  "Classify a bundle root against the hash contract selected by its schema.
   v1 roots can be integrity-identified, but are deliberately not elevated to
   content-verified/runnable because v1 did not use the portable v2 contract."
  [bundle-root]
  (let [schema (:bundle/schema-version bundle-root)
        declared (:bundle/hash bundle-root)
        id (:bundle/id bundle-root)]
    (cond
      (or (nil? declared) (nil? id))
      {:status :invalid :reason :missing-bundle-hash}

      (not= id declared)
      {:status :invalid :reason :bundle-id-hash-mismatch}

      (= legacy-schema-version schema)
      (if (= declared (compute-v1-hash bundle-root))
        {:status :legacy-not-content-verified
         :reason :legacy-binary-domain-hash}
        {:status :invalid :reason :legacy-bundle-content-hash-mismatch})

      (= schema-version schema)
      (let [computed (compute-json-hash bundle-root)]
        (if (= declared computed)
          {:status :content-verified :computed-hash computed}
          {:status :invalid
           :reason :bundle-content-hash-mismatch
           :computed-hash computed}))

      :else
      {:status :invalid :reason :unsupported-bundle-schema})))

(defn structurally-valid?
  "Validate only the immutable inner bundle-root structure. This does not
   inspect package artifacts or establish package-level runnability."
  [bundle-root]
  (criteria/runnable-bundle-root? bundle-root))

(defn runnable?
  "Deprecated compatibility alias for `structurally-valid?`.
   Canonical package runnability is `resolver-sim.run.package-index/runnable?`."
  [bundle-root]
  (structurally-valid? bundle-root))
