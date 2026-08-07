(ns resolver-sim.registry.live
  "Unified live registry hub.

   Central point for reading and mutating any registry type,
   preferring in-memory/live state over classpath/filesystem fallback.

   Registry types:
     :benchmark    — Benchmark suite registry (benchmarks/registry.edn, inlines packs)
     :pack         — Single pack registry (requires :pack/id option)
     :concept      — Concept definitions (data/concepts/registry.edn)
     :command      — CLI command dispatch (prf/commands/registry.edn)
     :claim        — Global claim definitions (benchmarks/claim-registry.edn)
     :protocol     — Protocol var/symbol registry (in-memory, extensible)
     :evidence     — Post-run evidence registry (directory scan, requires :run/dir)
     :yield-module — Yield provider modules (from world state)
     :sew-resolver — Sew resolver stake registry (from world state)
     :definitions  — Canonical semantic definitions (resolver-sim.definitions.registry)

   All write operations go through update-live-registry! which records
   provenance metadata. register-registry! adds new registry types at runtime."
  (:require [clojure.string :as str]
            [resolver-sim.benchmark.claim-registry :as claim-registry]
            [resolver-sim.io.resource-path :as rp]
            [resolver-sim.logging :as log]
            [resolver-sim.hash.reference :as hash-ref])
  (:import [java.time Instant]))

;; ── Dynamic test-mode binding ────────────────────────────────────────────────
;; Bind *live-only* to true in a with-test-registry block (or via binding)
;; to skip file/resolver fallback globally without per-call :skip-fallback opts.

(def ^:dynamic ^:private *live-only*
  false)

;; ── Atom-backed live store ───────────────────────────────────────────────────

(defonce ^:private live-registries
  (atom {}))

;; ── Helpers ──────────────────────────────────────────────────────────────────

(defn- now []
  (str (Instant/now)))

(defn- edn-read-path
  [path-spec]
  (try (rp/edn-read path-spec)
       (catch Exception e
         (throw (ex-info "Failed to read registry"
                         {:registry/path path-spec
                          :error (.getMessage e)})))))

;; ── Per-type resolvers ───────────────────────────────────────────────────────

(defn- resolve-benchmark-registry
  ([] (resolve-benchmark-registry nil))
  ([{:keys [inline-packs?]}]
   (let [registry (edn-read-path rp/canonical-registry-path)
         packs (:packs registry [])]
     (cond-> registry
       inline-packs?
       (assoc :packs (mapv (fn [pack]
                             (let [pack-path (rp/pack-registry-path (:pack/registry pack))
                                   pack-data (edn-read-path pack-path)]
                               (assoc pack :pack/benchmarks (:benchmarks pack-data []))))
                           packs))))))

(defn- resolve-concept-registry
  ([] (resolve-concept-registry nil))
  ([_] (edn-read-path (str hash-ref/resource-prefix hash-ref/concept-registry-path))))

(defn- resolve-command-registry
  ([] (resolve-command-registry nil))
  ([_] (edn-read-path (str hash-ref/resource-prefix hash-ref/command-registry-path))))

(defn- resolve-claim-registry
  ([] (resolve-claim-registry nil))
  ([opts]
   (let [registry (resolver-sim.benchmark.claim-registry/load-claim-registry
                   (:claim-registry/path opts))
         data (rp/edn-read (:claim-registry/path registry))]
     ;; Preserve the historical data shape (:claims vector + document fields)
     ;; while surfacing selection provenance so consumers can tell which file
     ;; actually governed the run.
     (merge data
            {:claims (:claims registry)
             :claim-registry/path (:claim-registry/path registry)
             :claim-registry/source (:claim-registry/source registry)}))))

(defn- resolve-protocol-registry
  ([] (resolve-protocol-registry nil))
  ([_]
   (try (require 'resolver-sim.protocols.registry)
        (let [pns (find-ns 'resolver-sim.protocols.registry)]
          {:protocols/known (vec ((ns-resolve pns 'known-protocol-ids)))
           :protocols/default (try @(ns-resolve pns 'default-protocol-id) (catch Exception _ nil))
           :protocols/registered (into {}
                                       (map (fn [id]
                                              [id (some? ((ns-resolve pns 'get-protocol) id))])
                                            ((ns-resolve pns 'known-protocol-ids))))})
        (catch Exception e
          (log/warn! "live-registry/protocol-resolver-failed" {:error (.getMessage e)})
          {:protocols/known []
           :protocols/registered {}}))))

(defn- resolve-evidence-registry
  ([] (resolve-evidence-registry nil))
  ([opts]
   (let [dir (:run/dir opts)]
     (if dir
       (try (require 'resolver-sim.evidence.registry)
            (let [build-fn (ns-resolve (find-ns 'resolver-sim.evidence.registry)
                                       'build-evidence-registry)]
              (if build-fn
                (build-fn dir)
                (throw (ex-info "resolver-sim.evidence.registry/build-evidence-registry not found" {}))))
            (catch Exception e
              (throw (ex-info "Failed to build evidence registry"
                              {:run/dir dir :error (.getMessage e)}))))
       (throw (ex-info "Evidence registry requires :run/dir option"
                       {:registry/type :evidence}))))))

(defn- resolve-definitions-registry
  ([] (resolve-definitions-registry nil))
  ([_]
   (try (require 'resolver-sim.definitions.registry)
        (let [dns (find-ns 'resolver-sim.definitions.registry)
              extract (fn [k] (try @(ns-resolve dns k) (catch Exception _ {})))]
          {:purposes (extract 'purposes)
           :statuses (extract 'statuses)
           :invariants (extract 'invariants)
           :claims (extract 'claims)
           :transitions (extract 'transitions)
           :transition-metadata (extract 'transition-metadata)
           :severities (extract 'severities)
           :story-families (extract 'story-families)})
        (catch Exception e
          (throw (ex-info "Failed to load definitions registry"
                          {:error (.getMessage e)}))))))

;; ── Registry type configuration ──────────────────────────────────────────────

(defonce ^:private registry-resolvers
  (atom {:benchmark   {:resolve-fn resolve-benchmark-registry
                       :canonical-path rp/canonical-registry-path}
         :pack        {:resolve-fn nil
                       :canonical-path nil}
         :concept     {:resolve-fn resolve-concept-registry
                       :canonical-path (str hash-ref/resource-prefix hash-ref/concept-registry-path)}
         :command     {:resolve-fn resolve-command-registry
                       :canonical-path (str hash-ref/resource-prefix hash-ref/command-registry-path)}
         :claim       {:resolve-fn resolve-claim-registry
                       :canonical-path hash-ref/claim-registry-path}
         :protocol    {:resolve-fn resolve-protocol-registry
                       :canonical-path nil}
         :evidence    {:resolve-fn resolve-evidence-registry
                       :canonical-path nil}
         :yield-module {:resolve-fn nil
                        :canonical-path nil
                        :world-path [:yield/modules]}
         :sew-resolver {:resolve-fn nil
                        :canonical-path nil
                        :world-path [:resolver-stakes]}
         :definitions {:resolve-fn resolve-definitions-registry
                       :canonical-path nil}}))

;; ── World-state readers (live, no file fallback) ─────────────────────────────

(defn- read-from-world
  [world world-path]
  (when (map? world)
    (when-let [data (get-in world world-path)]
      {:registry/content data
       :registry/source :live})))

(defn- read-world-registry
  [registry-type world]
  (when-let [wp (get-in @registry-resolvers [registry-type :world-path])]
    (read-from-world world wp)))

;; ── Cache helpers ────────────────────────────────────────────────────────────

(defn- read-cache
  [registry-type]
  (when-let [cached (get @live-registries registry-type)]
    cached))

(defn- write-cache!
  [registry-type data source]
  (swap! live-registries assoc registry-type
         {:registry/content data
          :registry/source source
          :registry/updated-at (now)}))

(defn- clear-cache!
  [registry-type]
  (swap! live-registries dissoc registry-type))

;; ── File-backed resolver dispatch ────────────────────────────────────────────

(defn- read-file-registry
  [registry-type opts]
  (let [resolver (get-in @registry-resolvers [registry-type :resolve-fn])
        path (get-in @registry-resolvers [registry-type :canonical-path])]
    (cond
      resolver
      (try
        (let [data (resolver opts)]
          (when data
            {:registry/content data
             :registry/source :classpath}))
        (catch Exception e
          (log/warn! "live-registry/resolver-failed"
                     {:registry/type registry-type :error (.getMessage e)})
          nil))

      path
      (try
        (let [data (edn-read-path path)]
          {:registry/content data
           :registry/source (if (str/starts-with? path hash-ref/resource-prefix) :classpath :file)})
        (catch Exception e
          (log/warn! "live-registry/read-failed"
                     {:registry/type registry-type :path path :error (.getMessage e)})
          nil))

      :else nil)))

;; ── Public API ───────────────────────────────────────────────────────────────

(defn read-live-registry
  "Read a registry by type, preferring live in-memory state when available.

   Registry types:
     :benchmark      — Benchmark suite registry (inlines packs with :inline-packs? true)
     :pack           — Single pack registry (requires :pack/id option)
     :concept        — Concept definitions registry
     :command        — CLI command dispatch registry
     :claim          — Global claim definitions
     :protocol       — Protocol var/symbol registry
     :evidence       — Post-run evidence registry (requires :run/dir option)
     :yield-module   — Yield provider modules (from world)
     :sew-resolver   — Sew resolver stake registry (from world)
     :definitions    — Canonical semantic definitions

   Resolution order:
     1. World state (when world is supplied and type supports it)
     2. Live registry atom (previously written via update-live-registry!)
     3. Classpath resource or filesystem (for file-backed types)
     4. Resolver fn fallback (for computed types like :protocol, :evidence)

   Options:
     :force         — Skip live atom, re-read from file/resolver source
     :skip-fallback — Skip file/resolver fallback; world + live atom only
     :pack/id       — (for :pack type) which pack to read
     :run/dir       — (for :evidence type) run directory to scan
     :inline-packs? — (for :benchmark type) inline pack benchmark entries

   Test-mode shortcut:
     Pass :skip-fallback true to restrict resolution to world-state and the
     live atom only.  Combine with update-live-registry! to inject fixtures
     without needing file/resolver infrastructure in tests."
  ([registry-type]
   (read-live-registry registry-type nil nil))
  ([registry-type world]
   (read-live-registry registry-type world nil))
  ([registry-type world opts]
   (let [normalized (keyword registry-type)]
     (when-not (contains? @registry-resolvers normalized)
       (throw (ex-info "Unknown registry type"
                       {:registry/type registry-type
                        :known (vec (keys @registry-resolvers))})))
     (let [force (:force opts)
           skip-fallback (or (:skip-fallback opts) *live-only*)
           from-world (when (and world (not force))
                        (read-world-registry normalized world))
           from-cache (when (not (or force from-world))
                        (read-cache normalized))
           from-file  (when (not (or from-world from-cache skip-fallback))
                        (read-file-registry normalized opts))
           result     (or from-world from-cache from-file)]
       (if result
         (let [data (:registry/content result)
               source (:registry/source result)]
           (when (and (not from-world) (not from-cache))
             (write-cache! normalized data source))
           (let [spec (or (:registry/spec data)
                          (:schema-version data)
                          (:spec data)
                          (str "live." (name normalized)))]
             {:registry/spec spec
              :registry/type normalized
              :registry/source source
              :registry/content data}))
         (throw (ex-info "Registry not available"
                         {:registry/type normalized
                          :world? (some? world)
                          :opts opts})))))))

(defn update-live-registry!
  "Write or replace a registry entry in the live atom.
   Subsequent reads return this data with :live source.

   registry-type — keyword or string (normalized to keyword)
   data          — any Clojure data (replaces entire entry)
   opts          — optional :meta map attached to the entry"
  ([registry-type data]
   (update-live-registry! registry-type data nil))
  ([registry-type data opts]
   (let [normalized (keyword registry-type)]
     (swap! live-registries assoc normalized
            {:registry/content data
             :registry/source :live
             :registry/updated-at (now)
             :registry/meta opts})
     normalized)))

(defn register-registry!
  "Register a new or override an existing registry type.

   Examples:
     (register-registry! :my-type {:resolve-fn my-resolver-fn})
     (register-registry! :my-type {:canonical-path \"resource:path/to/registry.edn\"})
     (register-registry! :my-type {:world-path [:my/registry-path]})

   resolver-fn receives opts map, should return registry data.
   world-path is a vector path into the world state map.
   canonical-path is a resource: or file: path spec."
  [registry-type config]
  (let [normalized (keyword registry-type)]
    (swap! registry-resolvers assoc normalized
           (merge {:resolve-fn nil :canonical-path nil :world-path nil}
                  config))
    normalized))

(defn clear-registry!
  "Remove a registry's live atom entry (keep the type registered).
   Returns true if entry existed, false otherwise."
  [registry-type]
  (let [normalized (keyword registry-type)]
    (when (contains? @live-registries normalized)
      (clear-cache! normalized)
      true)))

(defn clear-all-registries!
  "Remove all live atom entries. Resolver configurations are preserved.
   Returns the number of entries cleared."
  []
  (let [keys (keys @live-registries)]
    (doseq [k keys] (clear-cache! k))
    (count keys)))

(defn list-registry-types
  "Return all registered registry type keywords."
  []
  (vec (sort (keys @registry-resolvers))))

(defn registry-info
  "Return metadata about a registry type: config and current live status.
   Returns nil for unregistered types."
  [registry-type]
  (let [normalized (keyword registry-type)
        config (get @registry-resolvers normalized)
        live (get @live-registries normalized)]
    (when (or config live)
      {:registry/type normalized
       :config (or config {})
       :live? (some? live)
       :live-meta (:registry/meta live)
       :live-source (:registry/source live)
       :live-updated-at (:registry/updated-at live)})))

(defn read-live
  "Test-mode convenience wrapper for read-live-registry.

   (read-live)              — return entire live-registries atom snapshot
   (read-live :concept)     — same as (read-live-registry :concept nil {:skip-fallback true})
   (read-live :yield-module world) — same as (read-live-registry :yield-module world {:skip-fallback true})

   Skips file/resolver fallback so only world-state and the live atom
   are consulted.  Use update-live-registry! to inject test fixtures,
   then read-live to retrieve them without touching disk or classpath.

   The zero-arity form returns the raw atom snapshot for inspection."
  ([] @live-registries)
  ([registry-type]
   (read-live-registry registry-type nil {:skip-fallback true}))
  ([registry-type world]
   (read-live-registry registry-type world {:skip-fallback true}))
  ([registry-type world opts]
   (read-live-registry registry-type world (merge {:skip-fallback true} opts))))

(defmacro with-test-registry
  "Execute body with test-mode shortcuts active and fixture data pre-loaded.

   (with-test-registry {:concept mock-concept-data
                        :command mock-cmd-data}
     (read-live :concept)   ;; returns mock-concept-data, no file fallback
     (read-live :command))  ;; returns mock-cmd-data

   The bindings map uses registry-type keywords as keys.  Each value is
   injected via update-live-registry! before body runs.  After body exits,
   the live-registries atom is restored to its pre-call snapshot — no test
   pollution across tests.

   Inside the block *live-only* is bound to true, so all read-live-registry
   calls skip file/resolver fallback automatically."
  [fixtures & body]
  (when (and (map? fixtures) (seq (keys fixtures)))
    `(let [snapshot# @live-registries]
       (try
         (binding [*live-only* true]
           ~@(map (fn [[k v]]
                    `(update-live-registry! ~k ~v))
                  fixtures)
           ~@body)
         (finally
           (reset! live-registries snapshot#)))))
  (when (or (nil? fixtures) (and (map? fixtures) (empty? fixtures)))
    `(binding [*live-only* true]
       ~@body)))
