(ns resolver-sim.benchmark.runner
  (:require [resolver-sim.benchmark.packs.partial-fill.evidence :as pf-evidence]
            [resolver-sim.allocation.proof-admission :as proof-admission]
            [resolver-sim.benchmark.repo :as repo]
            [resolver-sim.benchmark.integrity :as integrity]
            [resolver-sim.benchmark.adapter :as adapter]
            [resolver-sim.benchmark.claims :as benchmark-claims]
            [resolver-sim.benchmark.coverage :as benchmark-coverage]
            [resolver-sim.benchmark.execution-identity :as execution-identity]
            [resolver-sim.benchmark.case-set :as case-set]
            [resolver-sim.benchmark.hardening :as hardening]
            [resolver-sim.concepts.benchmark :as benchmark-concepts]
            [resolver-sim.evidence.chain :as chain]
            [resolver-sim.evidence.config :as evidence-config]
            [resolver-sim.evidence.node :as evidence-node]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.io.resource-path :as rp]
            [resolver-sim.io.input-source :as input-source]
            [resolver-sim.io.scenarios :as io-sc]
            [resolver-sim.logging :as log]
            [resolver-sim.execution.budget :as budget]
            [resolver-sim.execution.context :as execution-context]
            [resolver-sim.contract-model.replay :as replay]
            [resolver-sim.protocols.registry :as protocols]
            [resolver-sim.protocols.protocol :as protocol-api]
            [resolver-sim.scenario.runner :as scenario-runner]
            [resolver-sim.scenario.suites :as suites]
            [resolver-sim.util.thread-quiescence :as quiesce]
            [resolver-sim.yield.module :as yield-module]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [resolver-sim.config.paths :as paths]
            [resolver-sim.io.edn :as ppedn])
  (:import [java.math BigInteger]
           [java.nio.file Files LinkOption StandardCopyOption]
           [java.util UUID]
           [java.util.concurrent Callable ExecutorCompletionService Executors TimeUnit]
           [java.security MessageDigest]))

;; ── Helpers ──────────────────────────────────────────────────────────────────

(def ^:dynamic *scenario-worker-hook*
  "Test/runtime-only hook at detached scenario worker entry. It is never
   persisted or included in any canonical benchmark/package projection."
  nil)

(defn- report-operational-phase!
  "Emit noncanonical coordinator progress. These events deliberately omit worker
   assignment, completion order, timing, and staging paths: those are operational
   details and must not influence benchmark/package semantics."
  [phase details]
  (println (str "benchmark/" (name phase)) (pr-str details)))

(declare normalize-runtime-values)

(def ^:dynamic ^:private *frozen-protocol-adapters*
  "Coordinator-resolved protocol adapters for bounded benchmark workers."
  nil)

(def ^:dynamic ^:private *canonical-worker?*
  "True only for coordinator-dispatched canonical worker tasks. Such tasks must
   never fall through to the mutable protocol registry."
  false)

(defn- find-scenarios-in-suites [suites]
  (mapcat (fn [suite-path]
            (let [dir (io/file suite-path)
                  scenario-dir (io/file dir "scenarios")
                  search-dir (if (.exists scenario-dir) scenario-dir dir)]
              (if (.isDirectory search-dir)
                (filter #(let [name (.getName %)]
                           (and (or (.endsWith name ".json")
                                    (.endsWith name ".edn"))
                                (not (str/includes? (.getPath %) "expected"))
                                (not (str/includes? (.getPath %) "results"))))
                        (file-seq search-dir))
                [])))
          suites))

(defn- resolve-suite-scenarios
  "Resolve a :suite/ keyword to stream-capable input sources."
  [suite-kw]
  (mapv input-source/source (suites/suite-paths suite-kw)))

(defn- load-scenario [source]
  (io-sc/load-scenario-file (input-source/loadable-ref source)))

(defn- reference-validation-id-by-path
  [scenario-path]
  (try
    (let [manifest (edn/read-string (slurp (io/resource (str (paths/reference-validation-suite-dir) "/manifest.edn"))))
          scenarios (:scenarios manifest)]
      (some (fn [scenario]
              (when (= scenario-path (:simulator/scenario-path scenario))
                (:id scenario)))
            scenarios))
    (catch Exception _ nil)))

(defn- benchmark-public-scenario-id
  [suite-kw scenario-path]
  (when (= suite-kw :suite/reference-validation-v1)
    (reference-validation-id-by-path scenario-path)))

(def ^:private prf-replay-claim-ids
  #{:claim/replay-identical-results
    :claim/hash-consistency-across-runs
    :claim/no-nondeterminism})

(defn- deterministic-replay-benchmark?
  [benchmark]
  (or (= :suite/prf-replay-v1 (:benchmark/scenario-suite benchmark))
      (some (comp prf-replay-claim-ids :claim/id)
            (benchmark-claims/normalize-claim-refs (:benchmark/claims benchmark)))))

(defn- benchmark-run-count
  [benchmark]
  (if (deterministic-replay-benchmark? benchmark) 2 1))

(defn- unique-scenario-count
  [results]
  (->> results
       (map (fn [result]
              (or (:scenario/id result)
                  (:simulator/scenario-path result)
                  (:file result))))
       set
       count))

(defn- safe-path-component
  [value]
  (-> (str value)
      (str/replace #"[^A-Za-z0-9._-]+" "-")
      (str/replace #"^-|-$" "")))

(defn build-execution-plan
  "Plan benchmark executions before replay. The plan is the authoritative
   descriptor set; duplicate identities and directory-prefix collisions fail
   before any execution output is created."
  [benchmark scenarios]
  (let [run-count (benchmark-run-count benchmark)
        planned (mapv (fn [ordinal [repetition-index scenario-source]]
                        (let [scenario (load-scenario scenario-source)
                              descriptor (execution-identity/descriptor scenario-source scenario repetition-index)
                              execution-id (execution-identity/execution-id descriptor)]
                          {:execution/ordinal (inc ordinal)
                           :execution/id execution-id
                           :execution/directory (execution-identity/directory-name (inc ordinal) descriptor)
                           :execution/descriptor descriptor
                           :scenario/source-ref (:input/ref scenario-source)}))
                      (range)
                      (for [repetition-index (range run-count)
                            scenario-source scenarios]
                        [repetition-index scenario-source]))
        ids (map :execution/id planned)
        prefixes (map #(subs (:execution/id %) (- (count (:execution/id %)) 16)) planned)]
    (when-not (= (count ids) (count (set ids)))
      (throw (ex-info "Benchmark execution plan contains duplicate execution IDs" {:ids ids})))
    (when-not (= (count prefixes) (count (set prefixes)))
      (throw (ex-info "Benchmark execution plan contains directory hash-prefix collisions" {:prefixes prefixes})))
    planned))

(defn- expanded-plan-sources
  [benchmark plan scenarios]
  (mapv vector plan
        (for [_repetition-index (range (benchmark-run-count benchmark))
              scenario scenarios]
          scenario)))

(defn- freeze-plan-inputs!
  "Coordinator-owned immutable input snapshotting for canonical execution.
   Worker sources retain the original logical reference, but their readable file
   path is a private frozen copy created before executor dispatch."
  [plan benchmark scenarios staging-root]
  (if-not staging-root
    {:plan plan
     :source-by-id (into {} (map (fn [[entry source]] [(:execution/id entry) source])
                                 (expanded-plan-sources benchmark plan scenarios)))}
    (let [frozen-root (io/file staging-root "frozen-inputs")
          pairs (expanded-plan-sources benchmark plan scenarios)
          frozen (mapv (fn [[entry source]]
                         (let [safe-name (safe-path-component (:input/display-name source))
                               name (if (seq safe-name) safe-name "scenario-input")
                               target (io/file frozen-root (:execution/directory entry) name)
                               provenance (input-source/snapshot! source target)
                               frozen-source (assoc (input-source/source (.getPath target))
                                                    :input/ref (:input/ref source)
                                                    :input/display-name (:input/display-name source)
                                                    :input/origin-ref (:input/ref source))
                               input-root (hash-ref/sha256-ref (:input/sha256 provenance))]
                           [(assoc entry :scenario/input-root input-root)
                            frozen-source]))
                       pairs)
          frozen-plan (mapv first frozen)]
      {:plan frozen-plan
       :source-by-id (into {} (map (fn [[entry source]] [(:execution/id entry) source]) frozen))})))

(defn- validate-and-freeze-global-prerequisites!
  "Coordinator-only preflight. Resolve all protocol implementations required by
   frozen inputs before workers exist, and re-run the canonical intent registry
   invariant at the same boundary. Workers therefore never win a namespace-load
   or extension-registration race while determining canonical results."
  [source-by-id]
  ;; Namespace loading may register protocol extensions, hash intents, or other
  ;; canonical prerequisites. Complete that bootstrap first, then validate and
  ;; freeze the registry state that workers will rely on.
  (doseq [protocol-ns (protocols/known-protocol-namespaces)]
    (require protocol-ns))
  (hc/validate-registry!)
  (let [protocol-ids (->> (vals source-by-id)
                          (map #(or (:protocol (load-scenario %)) protocols/default-protocol-id))
                          distinct
                          sort
                          vec)]
    (doseq [protocol-id protocol-ids]
      (when-not (protocols/get-protocol protocol-id)
        (throw (ex-info "Frozen benchmark plan requires an unavailable protocol"
                        {:reason :unavailable-frozen-protocol
                         :protocol protocol-id
                         :known-protocols (vec (protocols/known-protocol-ids))}))))
    ;; Built-in adapters are zero-field implementations: every replay method
    ;; derives its state from the scenario/world arguments. They are safe to
    ;; share between isolated scenario workers. Unknown extension adapters are
    ;; conservative/exclusive until they declare a safe execution contract.
    (let [shared-protocols #{"sew-v1" "yield-v1" "dummy"}
          adapter-safety (into {} (map (fn [id]
                                         [id (if (contains? shared-protocols id)
                                               :stateless-shared
                                               :exclusive-unknown-adapter)])
                                       protocol-ids))]
      {:preflight/protocol-ids protocol-ids
       :preflight/protocol-adapters (into {} (map (fn [id] [id (protocols/get-protocol id)]) protocol-ids))
       :preflight/adapter-safety adapter-safety
       :preflight/exclusive-protocols (->> adapter-safety
                                           (keep (fn [[id safety]]
                                                   (when (= safety :exclusive-unknown-adapter) id)))
                                           vec)
       :preflight/intent-registry :validated})))

(defn- delete-tree!
  [path]
  (when-let [root (and path (io/file path))]
    (when (.exists root)
      (doseq [entry (reverse (file-seq root))]
        (when-not (.delete entry)
          (throw (ex-info "Could not remove private benchmark staging" {:path (.getPath entry)})))))))

(defn- write-execution-plan! [path benchmark plan]
  (when path
    (let [file (io/file path)
          parent (.getParentFile file)
          content (ppedn/ppr-str
                   (cond-> {:schema_version "benchmark-execution-plan.v1"
                            :benchmark/id (:benchmark/id benchmark)
                            :executions plan}
                     (:benchmark/trust-sequence-definition-root benchmark)
                     (assoc :trust-sequence-definition-root
                            (:benchmark/trust-sequence-definition-root benchmark)
                            :expected-correlation-id
                            (:benchmark/expected-correlation-id benchmark))))
          temp (io/file parent (str "." (.getName file) ".tmp-" (UUID/randomUUID)))]
      (.mkdirs parent)
      (spit temp content)
      (try
        ;; A plan is a frozen execution authority, not a mutable report. Publish
        ;; it once; a concurrent different plan must fail rather than win by
        ;; timing. Identical publication is an idempotent replay.
        (Files/createLink (.toPath file) (.toPath temp))
        (catch java.nio.file.FileAlreadyExistsException _
          (when-not (= content (slurp file))
            (throw (ex-info "Execution plan path is already owned by a different plan"
                            {:reason :execution-plan-path-conflict
                             :path (str file)}))))
        (finally
          (Files/deleteIfExists (.toPath temp)))))))

(defn- execution-output-dir
  [executions-dir ordinal descriptor]
  (when executions-dir
    (let [directory (execution-identity/directory-name ordinal descriptor)]
      (str (io/file executions-dir directory)))))

(defn execution-chunks
  "Deterministically decompose an already frozen execution plan for local
   bounded execution. Chunk boundaries are operational only: canonical reduction
   always reconciles the underlying execution IDs and restores plan order."
  [plan chunk-size]
  (let [chunk-size (long (or chunk-size 1))]
    (when-not (pos? chunk-size)
      (throw (ex-info "Benchmark chunk size must be positive" {:chunk-size chunk-size})))
    (mapv (fn [index entries]
            {:chunk/id (format "chunk-%04d" (inc index))
             :chunk/work-item-ids (mapv :execution/id entries)
             :chunk/work-item-ordinals (mapv :execution/ordinal entries)
             :chunk/work-items (vec entries)})
          (range)
          (partition-all chunk-size plan))))

(defn- staging-execution-dir
  [staging-root plan-entry]
  (when staging-root
    (str (io/file staging-root (:execution/directory plan-entry)))))

(defn- require-plan-match!
  [plan-entry scenario-source scenario]
  (let [descriptor (execution-identity/descriptor scenario-source scenario
                                                  (get-in plan-entry [:execution/descriptor :repetition-index]))
        execution-id (execution-identity/execution-id descriptor)]
    (when-not (and (= descriptor (:execution/descriptor plan-entry))
                   (= execution-id (:execution/id plan-entry)))
      (throw (ex-info "Frozen benchmark execution input no longer matches its plan"
                      {:reason :execution-plan-input-mismatch
                       :expected-execution-id (:execution/id plan-entry)
                       :actual-execution-id execution-id})))
    descriptor))

(defn- sha256-file
  [path]
  (when (.exists (io/file path))
    (let [digest (MessageDigest/getInstance "SHA-256")]
      (with-open [stream (io/input-stream path)]
        (let [buffer (byte-array 8192)]
          (loop [read (.read stream buffer)]
            (when (pos? read)
              (.update digest buffer 0 read)
              (recur (.read stream buffer))))))
      (format "%064x" (BigInteger. 1 (.digest digest))))))

(defn- evidence-node-manifest-entry
  "Build a manifest entry for a persisted evidence-node file using its
   deterministic canonical projection rather than the raw on-disk bytes.

   Evidence nodes embed a wall-clock :timestamp and derived :record-hash for
   audit purposes. Those are volatile envelope fields and must not influence
   canonical package identity. The committed SHA-256 / byte-count are therefore
   computed over canonical-node-projection (which excludes :timestamp and
   :record-hash), and the deterministic :node-hash is recomputed and verified
   against the stored value so integrity is not weakened by trusting the field.
   Returns nil for files that are not under the evidence-nodes directory."
  [rel f]
  (when (str/starts-with? rel "evidence-nodes/")
    (let [raw (slurp f)
          node (edn/read-string raw)
          stored (:node-hash node)
          recomputed (evidence-node/compute-node-hash node)]
      (when-not (= stored recomputed)
        (throw (ex-info "Evidence node hash mismatch in canonical artifact projection"
                        {:path rel :stored stored :recomputed recomputed})))
      (let [bytes (evidence-node/canonical-node-projection node)
            digest (MessageDigest/getInstance "SHA-256")]
        (.update digest bytes)
        {:artifact/relative-path rel
         :artifact/sha256 (format "%064x" (BigInteger. 1 (.digest digest)))
         :artifact/byte-count (alength bytes)
         :artifact/semantic-root nil
         :artifact/canonical-node-hash recomputed
         :artifact/volatile-envelope? false}))))

(defn- artifact-manifest-for-dir
  "Produce a detached artifact manifest for a single execution directory.
   Each entry carries the logical relative path, SHA-256, byte count, and - for
   the canonical replay commitment - the execution's evidence-content semantic
   root. Evidence-node files are committed via their deterministic canonical
   projection (canonical-node-projection), never their raw wall-clock envelope
   bytes. Returns nil when the directory does not exist (legacy/direct exec)."
  [dir semantic-root]
  (when dir
    (let [file (io/file dir)
          root (.toPath file)
          entries (file-seq file)]
      (when (.isDirectory file)
        (doseq [entry entries]
          (let [path (.normalize (.toPath entry))]
            (when (or (Files/isSymbolicLink path)
                      (not (.startsWith path root)))
              (throw (ex-info "Staged artifact path is unsafe"
                              {:reason :unsafe-staged-artifact-path
                               :root (str root) :path (str path)})))))
        (let [artifacts (->> entries
                             (filter #(Files/isRegularFile (.toPath %) (make-array LinkOption 0)))
                             (mapv (fn [f]
                                     (let [rel (str (.relativize root (.toPath f)))]
                                       (or (evidence-node-manifest-entry rel f)
                                           {:artifact/relative-path rel
                                            :artifact/sha256 (sha256-file f)
                                            :artifact/byte-count (.length f)
                                            :artifact/semantic-root
                                            (when (= rel "raw/replay-output.edn") semantic-root)}))))
                             (sort-by :artifact/relative-path)
                             vec)]
          {:artifact/manifest-version "benchmark-artifact-manifest.v1"
           :artifacts artifacts})))))

(defn- reconcile-artifact-manifests!
  "Pure artifact-manifest reconciliation enforced by the coordinator before any
   canonical publication. Given worker manifests (each carrying :execution/id,
   :execution/directory, and :artifacts with :artifact/relative-path,
   :artifact/sha256, :artifact/byte-count):

     - identical logical identity + identical bytes → deterministic idempotent
       dedupe (one entry survives);
     - same logical identity + differing bytes/roots → fail closed.

   Returns the deduplicated manifests unchanged when no collision exists."
  [manifests]
  (let [manifests (vec manifests)
        logical-id (fn [m a] [(:execution/id m) (:artifact/relative-path a)])
        identity-index (reduce (fn [acc manifest]
                                 (reduce (fn [a' artifact]
                                           (update a' (logical-id manifest artifact)
                                                   (fnil conj []) artifact))
                                         acc
                                         (:artifacts manifest)))
                               {}
                               manifests)
        collisions (->> identity-index
                        (keep (fn [[identity artifacts]]
                                (when (> (count (into #{} (map (juxt :artifact/sha256
                                                                     :artifact/byte-count
                                                                     :artifact/semantic-root)
                                                               artifacts))) 1)
                                  {:logical-identity identity
                                   :variants (vec (sort-by pr-str
                                                           (map #(select-keys % [:artifact/sha256
                                                                                 :artifact/byte-count
                                                                                 :artifact/semantic-root])
                                                                artifacts)))})))
                        vec)]
    (when (seq collisions)
      (throw (ex-info "Benchmark artifact collision: same logical identity, differing bytes"
                      {:collisions collisions :reason :artifact-collision})))
    (let [seen (atom #{})]
      (mapv (fn [manifest]
              (assoc manifest :artifacts
                     (->> (:artifacts manifest)
                          (filter (fn [artifact]
                                    (let [id (logical-id manifest artifact)]
                                      (when-not (contains? @seen id)
                                        (swap! seen conj id)
                                        true))))
                          vec)))
            manifests))))

(defn- verify-staged-matches-worker!
  "Fail closed when the (still staged) bytes for one execution diverge from what
   that worker reported in its manifest, or when staged/worker file sets differ."
  [worker-manifest staged-manifest execution-id]
  (let [worker (into {} (map (juxt :artifact/relative-path identity)) (:artifacts worker-manifest))
        staged (into {} (map (juxt :artifact/relative-path identity)) (:artifacts staged-manifest))
        missing (->> worker keys (remove staged) sort vec)
        extra (->> staged keys (remove worker) sort vec)
        changed (->> worker
                     (keep (fn [[path entry]]
                             (when (let [s (get staged path)]
                                     (and s (or (not= (:artifact/sha256 s) (:artifact/sha256 entry))
                                                (not= (:artifact/byte-count s) (:artifact/byte-count entry)))))
                               {:path path
                                :worker-sha256 (:artifact/sha256 entry)
                                :staged-sha256 (get-in staged [path :artifact/sha256])
                                :worker-byte-count (:artifact/byte-count entry)
                                :staged-byte-count (get-in staged [path :artifact/byte-count])})))
                     (sort-by :path)
                     vec)]
    (when (or (seq missing) (seq extra) (seq changed))
      (throw (ex-info "Staged artifact content diverged from worker manifest"
                      {:execution/id execution-id
                       :missing missing :extra extra :changed changed
                       :reason :staged-artifact-divergence})))))

(defn- rehash-canonical-manifest
  "After the coordinator moves a staged directory into its canonical execution
   location, re-read the canonical files and attach their observed SHA-256s so
   post-move byte integrity is re-established on the manifest."
  [canonical-dir worker-manifest]
  (let [observed (artifact-manifest-for-dir canonical-dir nil)
        expected-by-path (into {} (map (juxt :artifact/relative-path identity))
                               (:artifacts worker-manifest))
        observed-by-path (into {} (map (juxt :artifact/relative-path identity))
                               (:artifacts observed))
        missing (->> expected-by-path keys (remove observed-by-path) sort vec)
        extra (->> observed-by-path keys (remove expected-by-path) sort vec)
        changed (->> expected-by-path
                     (keep (fn [[path expected]]
                             (let [actual (get observed-by-path path)]
                               (when (and actual
                                          (or (not= (:artifact/sha256 expected) (:artifact/sha256 actual))
                                              (not= (:artifact/byte-count expected) (:artifact/byte-count actual))))
                                 {:path path
                                  :expected (select-keys expected [:artifact/sha256 :artifact/byte-count])
                                  :actual (select-keys actual [:artifact/sha256 :artifact/byte-count])}))))
                     (sort-by :path)
                     vec)]
    (when (or (seq missing) (seq extra) (seq changed))
      (throw (ex-info "Canonical artifact content diverged after publication"
                      {:reason :canonical-artifact-divergence
                       :missing missing :extra extra :changed changed})))
    (assoc worker-manifest
           :artifacts (mapv (fn [artifact]
                              (assoc artifact :artifact/semantic-root
                                     (:artifact/semantic-root
                                      (get expected-by-path (:artifact/relative-path artifact)))))
                            (:artifacts observed))
           :artifact/canonical-verified true)))

(defn- move-staged-to-canonical!
  "Detached coordinator-only move of one staged execution directory into its
   canonical execution location. Extracted as a seam so publication-failure
   tests can inject a move failure."
  [staged target]
  (Files/move (.toPath staged) (.toPath target)
              (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE])))

(defn- write-execution-package!
  [output-dir scenario-source scenario result]
  (when output-dir
    (let [dir (io/file output-dir)
          input-file (io/file dir "input" (:input/display-name scenario-source))
          replay-file (io/file dir "raw" "replay-output.edn")
          summary-file (io/file dir "execution-summary.edn")]
      (.mkdirs (.getParentFile input-file))
      (.mkdirs (.getParentFile replay-file))
      (with-open [in (input-source/open-stream scenario-source)]
        (io/copy in input-file))
      (spit replay-file (ppedn/ppr-str result))
      (spit summary-file (ppedn/ppr-str {:scenario/source-path (:input/ref scenario-source)
                                         :scenario/protocol (:protocol scenario)
                                         :outcome (:outcome result)
                                         :halt-reason (:halt-reason result)
                                         :events-processed (:events-processed result)}))
      {:scenario/artifact-dir output-dir
       :scenario/input-path (.getPath input-file)
       :scenario/replay-output (.getPath replay-file)
       :scenario/summary (.getPath summary-file)
       :scenario/evidence-registry (let [path (str (io/file dir "evidence-registry.json"))]
                                     (when (.exists (io/file path)) path))
       :scenario/chain-cursor (let [path (str (io/file dir "chain-cursor-final.json"))]
                                (when (.exists (io/file path)) path))})))

(defn- post-invariants
  "Return protocol-native post-replay invariant results without making the
   benchmark core depend on a concrete protocol namespace."
  [protocol world]
  (case protocol
    "sew-v1" (let [canonical-ids @(requiring-resolve 'resolver-sim.protocols.sew.invariants/canonical-ids)
                   check-all (requiring-resolve 'resolver-sim.protocols.sew.invariants/check-all)]
               {:ids (sort canonical-ids)
                :results (:results (check-all world))})
    "yield-v1" (let [catalog @(requiring-resolve 'resolver-sim.yield.invariant-catalog/catalog)
                     run-invariants (requiring-resolve 'resolver-sim.yield.invariants/run-invariants)
                     ids (sort (keys catalog))]
                 {:ids ids :results (run-invariants world ids)})
    {:ids [] :results {}}))

(defn- resolve-worker-adapter!
  "Resolve an adapter at the worker boundary. Canonical executor tasks receive
   a coordinator-frozen adapter map and must fail closed if it lacks the
   planned protocol; only legacy/direct calls may consult the mutable registry."
  [protocol-id]
  (let [adapter (if *canonical-worker?*
                  (get *frozen-protocol-adapters* protocol-id)
                  (protocols/get-protocol protocol-id))]
    (when-not adapter
      (throw (ex-info "Benchmark scenario protocol extension is unavailable"
                      {:reason (if *canonical-worker?*
                                 :missing-frozen-protocol-adapter
                                 :unavailable-protocol)
                       :protocol protocol-id
                       :known-protocols (vec (protocols/known-protocol-ids))})))
    adapter))

(defn- execute-scenario
  "Determine one frozen execution in a worker-owned staging directory. This
   function deliberately does not publish into the canonical executions root."
  [suite-kw scenario-source plan-entry run-count staging-root]
  (let [path (:input/ref scenario-source)
        scenario (load-scenario scenario-source)
        ordinal (:execution/ordinal plan-entry)
        repetition-index (get-in plan-entry [:execution/descriptor :repetition-index])
        descriptor (require-plan-match! plan-entry scenario-source scenario)
        execution-id (:execution/id plan-entry)
        protocol (or (:protocol scenario) protocols/default-protocol-id)
        adapter (resolve-worker-adapter! protocol)
        output-dir (staging-execution-dir staging-root plan-entry)
        run-replay (fn []
                     (if (= "sew-v1" protocol)
                       ((requiring-resolve 'resolver-sim.protocols.sew/replay-with-sew-protocol)
                        scenario {:allow-dirty? (or chain/*allow-dirty* false)})
                       (replay/replay-events
                        adapter scenario
                        (cond-> {:allow-dirty? (or chain/*allow-dirty* false)}
                          (= "yield-v1" protocol)
                          (assoc :flags {:yield-dt-validation? true
                                         :metrics-profile :yield-provider})))))
        raw-replay-result (if output-dir
                            (binding [evidence-config/*artifact-dir* output-dir]
                              (run-replay))
                            (run-replay))
        ;; Replay kernels carry the concrete JVM adapter for in-process control
        ;; flow. It must never cross into detached or rooted benchmark evidence.
        replay-result (-> raw-replay-result
                          ;; The replay kernel may carry its JVM adapter for
                          ;; in-process dispatch. Detached evidence commits a
                          ;; stable protocol identity, never that object.
                          (dissoc :protocol)
                          (assoc :protocol/id protocol
                                 :protocol/projection {:protocol/id protocol
                                                       :protocol/version 1}))
        entry (scenario-runner/run-scenario scenario
                                            {:replay-fn (fn [_] replay-result)
                                             :source :benchmark
                                             :evaluate-theory? (:evaluate-theory?
                                                                (scenario-runner/runner-opts-for-scenario scenario))})
        ;; Stage the same portable representation that will enter canonical
        ;; evidence. Artifact bytes and their detached manifest roots must not
        ;; depend on an adapter object's JVM identity.
        execution-package (write-execution-package! output-dir scenario-source scenario
                                                    (normalize-runtime-values replay-result))
        public-id (benchmark-public-scenario-id suite-kw path)
        final-world (:world replay-result)
        realized-statements (pf-evidence/realized-allocation-statements final-world)
        ;; The generic evidence-content root remains the scenario's broad replay
        ;; commitment. The separate binding below is the canonical reusable
        ;; scenario-evidence ↔ realized-statement relation used by proof-backed
        ;; admission; it is deliberately not an incidental member of this map.
        scenario-evidence (hc/hash-with-intent
                           {:hash/intent :evidence-content}
                           (cond-> (select-keys replay-result
                                                [:events-processed :outcome :halt-reason])
                             realized-statements
                             (assoc :realized-allocation-statements-root
                                    (:statements-root realized-statements))))
        statement-binding (when realized-statements
                            (let [binding {:scenario-id public-id
                                           :evidence-content-root scenario-evidence
                                           :statements-root (:statements-root realized-statements)}]
                              (assoc binding :binding-root
                                     (proof-admission/scenario-statement-binding-root binding))))
        step-failures (get-in replay-result [:metrics :invariant-results] {})
        post-invariant-result (when final-world
                                (if output-dir
                                  (binding [evidence-config/*artifact-dir* output-dir]
                                    (post-invariants protocol final-world))
                                  (post-invariants protocol final-world)))
        all-inv-ids (:ids post-invariant-result)
        post-check (:results post-invariant-result)
        inv-results (mapv (fn [id]
                            {:id id
                             :result (cond
                                       (contains? step-failures id) :fail
                                       (get post-check id) :pass
                                       (false? (get-in post-check [id :holds?])) :fail
                                       :else :pass)})
                          all-inv-ids)
        _ (when-let [summary-path (get-in execution-package [:scenario/summary])]
            (spit summary-path (ppedn/ppr-str {:scenario/source-path (:input/ref scenario-source)
                                               :scenario/protocol protocol
                                               :outcome (:outcome replay-result)
                                               :halt-reason (:halt-reason replay-result)
                                               :events-processed (:events-processed replay-result)
                                               :invariant-results inv-results})))]
    (merge entry
           {:file path
            :scenario/id (or public-id (:scenario-id entry) (:scenario-id scenario))
            ;; This is the portable protocol representation exposed to
            ;; benchmark/package evidence. The concrete adapter remains worker
            ;; runtime state only.
            :scenario/protocol {:protocol/id protocol :protocol/version 1}
            :simulator/scenario-path path
            :execution/id execution-id
            :execution/ordinal ordinal
            :execution/descriptor descriptor
            :case/key (case-set/case-key-for-execution ordinal)
            :benchmark/run-index repetition-index
            :benchmark/run-count run-count
            :partial-fill-decisions (->> (get-in final-world [:yield/partial-fill-decisions] {})
                                         vals
                                         (sort-by :decision/id)
                                         vec)
            :invariant-results inv-results
            :scenario/evidence-root scenario-evidence
            :scenario/realized-allocation-statements
            (when realized-statements
              (mapv :statement/root (:statements realized-statements)))
            ;; Retain canonical statement projections for independent claim-side
            ;; recomputation. Roots alone never establish statement validity.
            :scenario/realized-allocation-statements-data
            (when realized-statements (:statements realized-statements))
            :scenario/realized-allocation-statements-root
            (when realized-statements
              (:statements-root realized-statements))
            :scenario/realized-statement-binding statement-binding
            ;; Inputs retained for claim-side independent recomputation; they
            ;; are covered by the persisted evidence bundle, not trusted as
            ;; caller-supplied report fields.
            :scenario/allocation-context (get-in final-world [:allocation/context])
            :scenario/round-lifecycle (get-in final-world [:allocation/round-lifecycle])
            :scenario/artifacts execution-package
            :scenario/artifact-manifest (artifact-manifest-for-dir output-dir scenario-evidence)})))

(defn- run-with-worker-context
  [artifact-dir allow-dirty? frozen-protocol-adapters runtime-execution-context work]
  ;; Dynamic bindings are intentionally established inside every executor task;
  ;; they are not inherited implicitly across async boundaries.
  (evidence-node/with-fresh-registry
    (chain/with-fresh-evidence-context*
      #(binding [evidence-config/*artifact-dir* artifact-dir
                 chain/*allow-dirty* allow-dirty?
                 *frozen-protocol-adapters* frozen-protocol-adapters
                 *canonical-worker?* (some? frozen-protocol-adapters)
                 execution-context/*context* runtime-execution-context
                 budget/*execution-budget* (when runtime-execution-context
                                             (:execution/shared-budget runtime-execution-context))]
         (work)))))

(def ^:dynamic *outer-scenario-worker-hook*
  "Runtime-only test instrumentation invoked inside an outer scenario worker
   immediately before a scenario replay begins. It is never included in a
   scenario, execution plan, evidence, or package projection."
  nil)

(defn- execute-execution
  [suite-kw source-by-id run-count staging-root allow-dirty? frozen-protocol-adapters runtime-execution-context plan-entry]
  (let [source (get source-by-id (:execution/id plan-entry))
        artifact-dir (staging-execution-dir staging-root plan-entry)]
    (when-not source
      (throw (ex-info "Frozen execution plan has no source"
                      {:execution/id (:execution/id plan-entry)})))
    (run-with-worker-context artifact-dir allow-dirty? frozen-protocol-adapters runtime-execution-context
                             #(do
                                (when *outer-scenario-worker-hook*
                                  (*outer-scenario-worker-hook*
                                   {:execution/id (:execution/id plan-entry)
                                    :execution/ordinal (:execution/ordinal plan-entry)}))
                                ;; Outer benchmark work consumes one permit from the
                                ;; shared JVM execution budget for the scenario's
                                ;; physical duration. Inner claimant work then borrows
                                ;; whatever spare capacity remains.
                                (let [b (budget/acquire-permit!)]
                                  (try
                                    (execute-scenario suite-kw source plan-entry run-count staging-root)
                                    (finally
                                      (budget/release-permit! b))))))))

(defn- reduce-in-original-order
  "Reassemble per-execution results into frozen plan order. Completion timing is
   operational only and is never surfaced as canonical result order."
  [plan results-by-id]
  (mapv (fn [entry]
          (let [result (get results-by-id (:execution/id entry))]
            (when-not result
              (throw (ex-info "Execution completed but result was not recorded"
                              {:execution/id (:execution/id entry)})))
            result))
        plan))

(defn- derive-protocol-by-id
  "Map each execution id to its protocol id, loading each distinct source once.
   Used only for lane scheduling (operational), never for canonical identity."
  [plan source-by-id]
  (let [proto-cache (atom {})
        proto-of (fn [source]
                   (let [ref (:input/ref source)]
                     (or (get @proto-cache ref)
                         (let [p (or (:protocol (load-scenario source))
                                     protocols/default-protocol-id)]
                           (swap! proto-cache assoc ref p)
                           p))))]
    (into {} (map (fn [entry]
                    [(:execution/id entry)
                     (proto-of (get source-by-id (:execution/id entry)))])
                  plan))))

(defn- execute-entries-bounded!
  "Schedule one bounded task per frozen execution-plan entry on a fixed pool,
   observe completion in completion order via ExecutorCompletionService, store
   results by execution id, and fail fast on the first worker failure (cancelling
   outstanding work). After all work completes (or is cancelled) the executor is
   quiesced authoritatively via quiesce-executor!; reduction proceeds only if
   termination is authoritative.

   Lane scheduling (P1-G): all executions share one fixed pool. Safe executions
   run freely (parallel). Exclusive-protocol executions serialize through a single
   global lane gate (a Semaphore(1)) so (a) each exclusive protocol's own
   executions never overlap, and (b) incompatible executions of different exclusive
   protocols never run concurrently — while safe work still overlaps even when
   exclusive work is present. The gate is interrupt-interoperable: a cancelled
   exclusive worker awaiting the gate releases promptly."
  [suite-kw source-by-id run-count staging-root parallelism allow-dirty? frozen-protocol-adapters runtime-execution-context exclusive-protocol-ids protocol-by-id plan]
  (let [parallelism (long (or parallelism 1))
        _ (when-not (pos? parallelism)
            (throw (ex-info "Benchmark parallelism must be positive" {:parallelism parallelism})))
        executor (Executors/newFixedThreadPool (int parallelism))
        completion (ExecutorCompletionService. executor)
        exclusive-gate (java.util.concurrent.Semaphore. 1)]
    (try
      (report-operational-phase! :parallel-determine-started
                                 {:execution-count (count plan)
                                  :parallelism parallelism
                                  :lane-safe-count (count (remove (fn [e] (contains? exclusive-protocol-ids
                                                                                     (get protocol-by-id (:execution/id e))))
                                                                  plan))
                                  :exclusive-protocol-ids (vec (sort exclusive-protocol-ids))})
      (let [submit-task (fn [plan-entry]
                          {:entry plan-entry
                           :future (.submit completion
                                            ^Callable
                                            (reify Callable
                                              (call [_]
                                                (if (contains? exclusive-protocol-ids
                                                               (get protocol-by-id (:execution/id plan-entry)))
                                                  (try
                                                    (.acquire exclusive-gate)
                                                    (execute-execution suite-kw source-by-id run-count
                                                                       staging-root allow-dirty?
                                                                       frozen-protocol-adapters
                                                                       runtime-execution-context
                                                                       plan-entry)
                                                    (finally
                                                      (.release exclusive-gate)))
                                                  (execute-execution suite-kw source-by-id run-count
                                                                     staging-root allow-dirty?
                                                                     frozen-protocol-adapters
                                                                     runtime-execution-context
                                                                     plan-entry)))))})
            submitted (mapv submit-task plan)
            results (atom {})
            failure (atom nil)]
        (doseq [_ (range (count submitted))
                :while (nil? @failure)]
          (let [future (.take completion)
                result (try
                         (.get future)
                         (catch InterruptedException _
                           (throw (ex-info "Parallel execution interrupted"
                                           {:reason :execution-interrupted})))
                         (catch java.util.concurrent.CancellationException _
                           (reset! failure {:reason :execution-cancelled})
                           nil)
                         (catch java.util.concurrent.ExecutionException e
                           (reset! failure {:reason :benchmark-execution-failed
                                            :cause (.getCause e)})
                           nil))]
            (when (and result (nil? @failure))
              (swap! results assoc (:execution/id result) result))))
        (if-let [{:keys [cause reason]} @failure]
          ;; Fail fast: cancel all outstanding work, then quiesce authoritatively.
          ;; Reduction/publication must not proceed unless worker termination is
          ;; confirmed as :terminated; otherwise fail closed with a quiescence error.
          (do
            (doseq [task submitted]
              (.cancel ^java.util.concurrent.Future (:future task) true))
            (let [quiescence (quiesce/quiesce-executor! executor)]
              (if (= :terminated (:status quiescence))
                (throw (ex-info "Benchmark execution failed in worker"
                                {:reason reason
                                 :cause cause
                                 :quiescence quiescence}))
                (throw (quiesce/quiescence-failed-exception
                        "Benchmark worker failure could not be cleanly quiesced"
                        {:reason reason
                         :cause cause
                         :quiescence quiescence})))))
          (do
            (report-operational-phase! :parallel-determine-complete
                                       {:execution-count (count plan)})
            (reduce-in-original-order plan @results))))
      (finally
        (when-not (.isShutdown executor)
          (.shutdown executor)
          (.awaitTermination executor
                             (long (or (:execution/quiescence-timeout-seconds
                                        runtime-execution-context)
                                       (hardening/quiescence-timeout-seconds)))
                             TimeUnit/SECONDS))))))

(defn- execute-plan-bounded!
  ([suite-kw plan source-by-id run-count staging-root parallelism chunk-size]
   (execute-plan-bounded! suite-kw plan source-by-id run-count staging-root parallelism chunk-size nil))
  ([suite-kw plan source-by-id run-count staging-root parallelism chunk-size frozen-protocol-adapters]
   (execute-plan-bounded! suite-kw plan source-by-id run-count staging-root parallelism chunk-size frozen-protocol-adapters nil))
  ([suite-kw plan source-by-id run-count staging-root parallelism chunk-size frozen-protocol-adapters runtime-execution-context]
   (execute-plan-bounded! suite-kw plan source-by-id run-count staging-root parallelism chunk-size frozen-protocol-adapters runtime-execution-context nil))
  ([suite-kw plan source-by-id run-count staging-root parallelism chunk-size frozen-protocol-adapters runtime-execution-context exclusive-protocol-ids]
   (let [allow-dirty? chain/*allow-dirty*
         exclusive-ids (set (or exclusive-protocol-ids []))
         protocol-by-id (derive-protocol-by-id plan source-by-id)]
     ;; chunk-size is retained as decomposition/distributed-execution metadata
     ;; but no longer limits local worker utilization; scheduling is per execution
     ;; and lanes isolate exclusive protocols without blocking safe parallel work.
     (report-operational-phase! :chunks-derived
                                {:execution-count (count plan)
                                 :chunk-count (count (execution-chunks plan (or chunk-size 1)))
                                 :chunk-size (long (or chunk-size 1))
                                 :parallelism (long (or parallelism 1))
                                 :exclusive-protocol-ids (vec (sort exclusive-ids))})
     (execute-entries-bounded! suite-kw source-by-id run-count staging-root
                               parallelism allow-dirty? frozen-protocol-adapters
                               runtime-execution-context exclusive-ids protocol-by-id plan))))

(defn- publish-staged-executions!
  "Coordinator-only canonical publication of detached worker staging.
   Before any move:
     - each worker-reported artifact manifest is reconciled against a fresh read
       of its (still staged) directory and must agree, else fail closed;
     - artifact manifests are reconciled across executions for collision/dedupe.
   After moving a staged directory, the canonical destination files are re-hashed
   onto the manifest so post-move byte integrity is authoritative."
  [canonical-root staging-root plan results]
  (when canonical-root
    (let [canonical-root-file (io/file canonical-root)
          staging-root-file (io/file staging-root)
          plan-by-id (into {} (map (juxt :execution/id identity) plan))
          manifests (mapv (fn [result]
                            (let [entry (get plan-by-id (:execution/id result))
                                  worker-manifest (:scenario/artifact-manifest result)
                                  staged-manifest (artifact-manifest-for-dir
                                                   (staging-execution-dir staging-root entry) nil)]
                              (verify-staged-matches-worker! worker-manifest staged-manifest (:execution/id result))
                              (assoc worker-manifest
                                     :execution/id (:execution/id result)
                                     :execution/directory (:execution/directory entry))))
                          results)
          _ (reconcile-artifact-manifests! manifests)]
      (.mkdirs canonical-root-file)
      (mapv (fn [result]
              (let [entry (get plan-by-id (:execution/id result))
                    staged (io/file staging-root-file (:execution/directory entry))
                    target (io/file canonical-root-file (:execution/directory entry))
                    worker-manifest (:scenario/artifact-manifest result)
                    rewrite-path (fn [path]
                                   (when path
                                     (str (io/file target
                                                   (str (.relativize (.toPath staged)
                                                                     (.toPath (io/file path))))))))]
                (when-not (.isDirectory staged)
                  (throw (ex-info "Detached execution staging directory is missing"
                                  {:execution/id (:execution/id result)
                                   :staging-path (.getPath staged)})))
                (when (.exists target)
                  (throw (ex-info "Canonical execution destination already exists"
                                  {:execution/id (:execution/id result)
                                   :destination (.getPath target)})))
                (move-staged-to-canonical! staged target)
                (-> result
                    (update :scenario/artifacts
                            (fn [artifacts]
                              (-> artifacts
                                  (assoc :scenario/artifact-dir (.getPath target))
                                  (update :scenario/input-path rewrite-path)
                                  (update :scenario/replay-output rewrite-path)
                                  (update :scenario/summary rewrite-path)
                                  (update :scenario/evidence-registry rewrite-path)
                                  (update :scenario/chain-cursor rewrite-path))))
                    (assoc :scenario/artifact-manifest
                           (rehash-canonical-manifest target worker-manifest)))))
            results))))

(defrecord SewAdapter [scenario-output-dir parallelism chunk-size]
  adapter/RepositoryAdapter
  (load-scenarios [_ benchmark]
    (if-let [suite-kw (:benchmark/scenario-suite benchmark)]
      (resolve-suite-scenarios suite-kw)
      (mapv #(input-source/source (.getPath %))
            (find-scenarios-in-suites (:scenario-suites benchmark)))))

  (execute-benchmark [_ benchmark scenarios]
    ;; Compatibility protocol entry point. Canonical callers use the frozen-plan
    ;; path in run-benchmark below. This adapter can use bounded execution, but
    ;; direct calls do not by themselves establish canonical package authority.
    (let [plan (build-execution-plan benchmark scenarios)
          source-by-id (into {}
                             (map (fn [entry source] [(:execution/id entry) source])
                                  plan
                                  (for [repetition-index (range (benchmark-run-count benchmark))
                                        scenario scenarios]
                                    scenario)))
          staging-root (when scenario-output-dir
                         (str (io/file (.getParentFile (io/file scenario-output-dir))
                                       ".staging-direct-adapter")))]
      (execute-plan-bounded! (:benchmark/scenario-suite benchmark) plan source-by-id
                             (benchmark-run-count benchmark) staging-root
                             (or parallelism 1) (or chunk-size 1))))

  (collect-metrics [_ results]
    {:total (count results)
     :passed (count (filter :pass? results))
     :execution-count (count results)
     :passed-execution-count (count (filter :pass? results))
     :unique-scenario-count (unique-scenario-count results)
     :declared-run-count (apply max 1 (keep :benchmark/run-count results))}))

(def default-adapter (->SewAdapter nil 1 1))

;; ── Benchmark artifact index ──────────────────────────────────────────────────

(defn- reconcile-execution-plan! [plan results]
  (let [planned-by-id (into {} (map (juxt :execution/id identity) plan))
        result-by-id (group-by :execution/id results)
        planned-ids (set (keys planned-by-id))
        result-ids (set (keys result-by-id))
        missing (sort (clojure.set/difference planned-ids result-ids))
        extra (sort (clojure.set/difference result-ids planned-ids))
        duplicates (->> result-by-id (filter (fn [[_ rs]] (not= 1 (count rs)))) (map first) sort vec)
        malformed (->> results
                       (keep (fn [result]
                               (let [planned (get planned-by-id (:execution/id result))]
                                 (when (and planned
                                            (or (and (contains? result :execution/ordinal)
                                                     (not= (:execution/ordinal planned) (:execution/ordinal result)))
                                                (and (contains? result :execution/descriptor)
                                                     (not= (:execution/descriptor planned) (:execution/descriptor result)))))
                                   {:execution/id (:execution/id result)
                                    :expected-ordinal (:execution/ordinal planned)
                                    :actual-ordinal (:execution/ordinal result)}))))
                       vec)
        misplaced (->> results
                       (keep (fn [result]
                               (let [planned (get planned-by-id (:execution/id result))
                                     actual (some-> result :scenario/artifacts :scenario/artifact-dir io/file .getName)]
                                 ;; An output directory is optional for legacy/direct
                                 ;; benchmark execution. Reconcile placement only when
                                 ;; the coordinator requested isolated artifacts.
                                 (when (and planned actual (not= (:execution/directory planned) actual))
                                   {:execution/id (:execution/id result)
                                    :expected (:execution/directory planned)
                                    :actual actual}))))
                       vec)]
    (when (or (seq missing) (seq extra) (seq duplicates) (seq malformed) (seq misplaced))
      (throw (ex-info "Benchmark execution plan reconciliation failed"
                      {:missing missing :extra extra :duplicates duplicates
                       :malformed malformed :misplaced misplaced})))
    true))

(defn- order-reconciled-results
  "Restore the frozen plan order after successful exact-set reconciliation."
  [plan results]
  (let [result-by-id (into {} (map (juxt :execution/id identity) results))]
    (mapv #(get result-by-id (:execution/id %)) plan)))

(defn- write-artifact-index!
  [scenario-output-dir benchmark-index-path benchmark-id results]
  (when scenario-output-dir
    (let [index-path (or benchmark-index-path
                         (str (io/file scenario-output-dir "benchmark-index.edn")))
          executions (mapv (fn [result]
                             (let [artifacts (:scenario/artifacts result)]
                               {:execution/id (:execution/id result)
                                :execution/descriptor (:execution/descriptor result)
                                :case/key (:case/key result)
                                :scenario/id (:scenario/id result)
                                :scenario/source-path (:simulator/scenario-path result)
                                :benchmark/run-index (:benchmark/run-index result)
                                :benchmark/run-count (:benchmark/run-count result)
                                :execution/status "completed"
                                :outcome (:outcome result)
                                :halt-reason (:halt-reason result)
                                :scenario/evidence-root (:scenario/evidence-root result)
                                :scenario/artifacts artifacts
                                :scenario/replay-output-sha256
                                (some-> artifacts :scenario/replay-output sha256-file)}))
                           results)
          index {:artifact-index/version "benchmark-artifact-index.v1"
                 :benchmark/id benchmark-id
                 :execution-count (count executions)
                 :executions executions}]
      (.mkdirs (.getParentFile (io/file index-path)))
      (spit index-path (ppedn/ppr-str index))
      {:path index-path
       :sha256 (sha256-file index-path)})))

;; ── Run manifest ────────────────────────────────────────────────────────────────

(defn build-run-manifest
  "Assemble a run manifest describing the current execution context.
   Written alongside the evidence bundle for reproducibility."
  [manifest-path manifest adapter results metrics]
  (let [scenario-hashes (mapv (fn [r]
                                {:scenario/id (:scenario/id r)
                                 :file (:file r)
                                 :benchmark/run-index (:benchmark/run-index r)
                                 :benchmark/run-count (:benchmark/run-count r)
                                 :outcome (:outcome r)
                                 :scenario/evidence-root (:scenario/evidence-root r)})
                              results)]
    {:manifest/version "run-manifest.v1"
     :manifest/at (str (java.time.Instant/now))
     :benchmark/id (:benchmark/id manifest)
     :benchmark/manifest-source manifest-path
     :adapter (str (type adapter))
     :scenario-count (count results)
     :execution-count (:execution-count metrics (count results))
     :unique-scenario-count (:unique-scenario-count metrics)
     :declared-run-count (:declared-run-count metrics)
     :scenario-hashes scenario-hashes
     :metrics metrics}))

(defn load-manifest [path]
  (if-let [resolved (rp/resolve-path path)]
    (rp/edn-read path)
    (throw (ex-info "Benchmark manifest not found" {:path path}))))

(defn- derive-additional-canonical-work
  "The current frozen benchmark graph is closed: scenario replay, invariants,
   and claims inspect the reconciled work set but cannot enqueue new canonical
   scenario work. Keep this explicit so a future work-generating semantic must
   replace this one-round invariant with coordinator-owned rounds."
  [_manifest _results]
  [])

(defn- evaluate-claims-coordinator-owned
  "Coordinator-owned claim evaluation. Returns a vector of claim-result maps.
   On an unexpected evaluation failure the coordinator records a structured
   fail-closed entry rather than silently returning an empty vector, so a
   downstream conclusion can never mistake 'no evaluation ran' for 'all claims
   passed'. Failures are surfaced (not propagated) in keeping with benchmark
   evidence policy: the run still produces an evidence bundle."
  [manifest results]
  (try
    (benchmark-claims/evaluate-manifest-claims manifest results)
    (catch Exception e
      (log/warn! "benchmark/claim-evaluation-failed" {:error (.getMessage e)})
      [{:claim/evaluation-status :failed
        :claim/evaluation-error (.getMessage e)
        :claim/outcome :error}])))

(defn run-benchmark
  ([manifest-path] (run-benchmark manifest-path default-adapter {}))
  ([manifest-path adapter] (run-benchmark manifest-path adapter {}))
  ([manifest-path adapter {:keys [scenario-output-dir benchmark-index-path execution-plan-path
                                  parallelism chunk-size execution/claimant-parallelism
                                  execution/claimant-parallel-threshold execution/budget
                                  execution/quiescence-timeout-seconds]}]
   (let [adapter (if scenario-output-dir
                   (->SewAdapter scenario-output-dir (or parallelism 1) (or chunk-size 1))
                   adapter)
         manifest (load-manifest manifest-path)
         _ (when-not (:benchmark/id manifest)
             (throw (ex-info "Benchmark manifest missing :benchmark/id"
                             {:manifest manifest-path})))
         repo-meta (repo/metadata)
         scenarios (adapter/load-scenarios adapter manifest)
         _ (when (empty? scenarios)
             (throw (ex-info "Benchmark manifest resolved zero scenarios"
                             {:manifest manifest-path
                              :scenario-suite (:benchmark/scenario-suite manifest)
                              :scenario-suites (:scenario-suites manifest)})))
         initial-plan (build-execution-plan manifest scenarios)
         staging-root (when scenario-output-dir
                        (str (io/file (.getParentFile (io/file scenario-output-dir))
                                      ".staging" "benchmark-executions")))
         frozen-inputs (freeze-plan-inputs! initial-plan manifest scenarios staging-root)
         plan (:plan frozen-inputs)
         source-by-id (:source-by-id frozen-inputs)
         _ (when (some? budget)
             (when-not (and (integer? budget) (pos? budget))
               (throw (ex-info "Execution budget must be a positive integer"
                               {:execution/budget budget}))))
         resolved-quiescence-seconds (hardening/quiescence-timeout-seconds
                                      quiescence-timeout-seconds)
         _ (when-not (and (integer? resolved-quiescence-seconds)
                          (pos? resolved-quiescence-seconds))
             (throw (ex-info "Quiescence timeout must be a positive integer"
                             {:execution/quiescence-timeout-seconds resolved-quiescence-seconds})))
         validated-ctx (execution-context/validate-context
                        {:execution/claimant-parallelism claimant-parallelism
                         :execution/claimant-parallel-threshold claimant-parallel-threshold})
         runtime-execution-context (cond-> validated-ctx
                                     budget (assoc :execution/shared-budget
                                                   (java.util.concurrent.Semaphore.
                                                    (int budget)))
                                     :always (assoc :execution/quiescence-timeout-seconds
                                                    resolved-quiescence-seconds))
         _ (report-operational-phase! :plan-frozen
                                      {:benchmark-id (:benchmark/id manifest)
                                       :scenario-count (count scenarios)
                                       :execution-count (count plan)})
         preflight (validate-and-freeze-global-prerequisites! source-by-id)
         exclusive-protocol-ids (or (:preflight/exclusive-protocols preflight) [])
         _ (report-operational-phase! :global-preflight-complete
                                      {:exclusive-protocols (vec (sort (map str exclusive-protocol-ids)))
                                       :effective-parallelism (long (or parallelism 1))})
         _ (write-execution-plan! execution-plan-path manifest plan)
         _ (report-operational-phase! :execution-plan-written
                                      {:execution-count (count plan)
                                       :canonical-plan? (boolean execution-plan-path)})
         _ (do
             (println "Executing" (count scenarios) "scenarios...")
             (log/info! "benchmark/execute" {:scenario-count (count scenarios)
                                             :manifest manifest-path}))
         raw-results (if (instance? SewAdapter adapter)
                       (execute-plan-bounded! (:benchmark/scenario-suite manifest)
                                              plan source-by-id (benchmark-run-count manifest)
                                              staging-root
                                              (or parallelism 1)
                                              (or chunk-size 1)
                                              (:preflight/protocol-adapters preflight)
                                              runtime-execution-context
                                              exclusive-protocol-ids)
                       (adapter/execute-benchmark adapter manifest scenarios))
         _ (reconcile-execution-plan! plan raw-results)
         _ (report-operational-phase! :exact-set-reconciled
                                      {:expected-execution-count (count plan)
                                       :actual-execution-count (count raw-results)})
         reconciled-results (order-reconciled-results plan raw-results)

         ;; Deterministic reduction happens against detached/staged results.
         ;; Nothing under benchmark/executions becomes canonical until all
         ;; semantic checks below have succeeded.
         metrics (adapter/collect-metrics adapter reconciled-results)
         passed? (= (:total metrics) (:passed metrics))

          ;; Aggregate invariant summary across all scenarios
         all-inv-results (mapcat :invariant-results reconciled-results)
         seen-ids (into #{} (map :id) all-inv-results)
         id->passes (fn [id] (filter #(and (= id (:id %)) (= :pass (:result %))) all-inv-results))
         id->total  (fn [id] (count (filter #(= id (:id %)) all-inv-results)))
         inv-summary (into {}
                           (map (fn [id]
                                  [id {:passed (count (id->passes id))
                                       :total  (id->total id)}]))
                           seen-ids)
         total-inv-checks (count all-inv-results)
         passed-inv-checks (count (filter #(= :pass (:result %)) all-inv-results))
         all-invariants-pass? (= total-inv-checks passed-inv-checks)

;; ── Claim evaluation ────────────────────────────────────────────
         claim-results (evaluate-claims-coordinator-owned manifest reconciled-results)
         _ (when (some #(= :failed (:claim/evaluation-status %)) claim-results)
             (throw (ex-info "Benchmark claim evaluation failed; canonical publication aborted"
                             {:reason :claim-evaluation-failed
                              :claim-results claim-results})))
         additional-work (derive-additional-canonical-work manifest reconciled-results)
         _ (when (seq additional-work)
             (throw (ex-info "Frozen benchmark execution graph is not closed after reduction"
                             {:reason :unexpected-derived-work
                              :additional-work additional-work})))
         _ (report-operational-phase! :deterministic-reduction-complete
                                      {:execution-count (count reconciled-results)
                                       :claim-count (count claim-results)})
         _ (report-operational-phase! :one-round-closure-confirmed
                                      {:derived-work-count (count additional-work)})

         ;; The sole canonical scenario-artifact publication boundary.
         _ (report-operational-phase! :canonical-artifact-publication-started
                                      {:execution-count (count reconciled-results)})
         results (or (publish-staged-executions! scenario-output-dir staging-root plan reconciled-results)
                     reconciled-results)
         _ (report-operational-phase! :canonical-artifact-publication-complete
                                      {:execution-count (count results)})
         _ (when staging-root
             (delete-tree! (io/file staging-root "frozen-inputs")))
         artifact-index (write-artifact-index! scenario-output-dir benchmark-index-path (:benchmark/id manifest) results)

            ;; ── Concept enrichment ──────────────────────────────────────────
         concept-ids (:benchmark/concepts manifest)
         concept-section (when (seq concept-ids)
                           (try
                             (let [resolved (benchmark-concepts/resolve-benchmark-concepts concept-ids)]
                               (when (seq (:unknown-concept-ids resolved))
                                 (log/warn! "benchmark/unknown-concepts"
                                            {:stale (:unknown-concept-ids resolved)}))
                               (benchmark-concepts/resolved-concept-section resolved))
                             (catch Exception e
                               (log/warn! "benchmark/concept-enrichment-failed"
                                          {:error (.getMessage e)})
                               nil)))
         concept-coverage (when (seq concept-ids)
                            (try
                              (let [resolved (benchmark-concepts/resolve-benchmark-concepts concept-ids)]
                                (benchmark-coverage/catalogue-coverage (:resolved-concepts resolved)))
                              (catch Exception e
                                (log/warn! "benchmark/concept-coverage-failed"
                                           {:error (.getMessage e)})
                                nil)))

         run-manifest (build-run-manifest manifest-path manifest adapter results metrics)
         certification {:benchmark-id      (or (:id manifest) "unknown")
                        :scenario-count    (:total metrics)
                        :all-invariants-pass all-invariants-pass?
                        :final-state-hash  nil
                        :evidence-chain-root nil
                        :invariant-summary inv-summary}
         certification (assoc certification
                              :certification-hash
                              (hc/hash-with-intent {:hash/intent :benchmark-certification}
                                                   certification))
         evidence {:benchmark      manifest
                   :repo           repo-meta
                   :environment    {:os-name (System/getProperty "os.name")
                                    :os-version (System/getProperty "os.version")
                                    :java-version (System/getProperty "java.version")}
                   :results        results
                   :metrics        metrics
                   :claim-results  claim-results
                   :reproduce      {:command (str "bb benchmark:reproduce " (or manifest-path hash-ref/escrow-dispute-pack-path))}
                   :invariant-summary {:per-invariant  inv-summary
                                       :total-checks   total-inv-checks
                                       :passed-checks  passed-inv-checks
                                       :all-pass?      all-invariants-pass?}
                   :concept/section concept-section
                   :concept/coverage concept-coverage
                   :run/manifest run-manifest
                   :benchmark/artifact-index artifact-index
                   :benchmark/execution-closure {:closure/version 1
                                                 :round-count 1
                                                 :derived-work-count (count additional-work)
                                                 :closed? true}
                    :benchmark-certification certification
                    :creation/provenance :in-band}

         ;; The committed hash covers the normalized (persisted) representation,
         ;; not the raw in-memory map: write-evidence serializes the same
         ;; normalized form, so verify-bundle-hash can recompute it from the
         ;; artifact on disk. Hashing the raw map here would silently diverge
         ;; once normalization rewrites runtime values (e.g. Instants).
         normalized-evidence (normalize-runtime-values evidence)
         hashable-evidence (integrity/hashable-evidence normalized-evidence)
          ;; Sort keys for deterministic hashing; the persisted file uses the same
          ;; sort order so verify-bundle-hash can recompute the identity from disk.
         sorted-hashable (into (sorted-map) hashable-evidence)
         bundle-root-hash (hc/hash-with-intent {:hash/intent :bundle-root} sorted-hashable)
         final-evidence (assoc normalized-evidence :evidence/hash bundle-root-hash)]

     (when-not passed?
       (log/warn! "benchmark/failed" {:passed (:passed metrics) :total (:total metrics)})
       (println "Benchmark FAILED:" (:passed metrics) "/" (:total metrics) "passed."))

     final-evidence)))

;; ── Evidence writer ─────────────────────────────────────────────────────────────

(defn- sort-maps
  "Recursively sort map keys for deterministic serialization.
   Vectors and lists are preserved as-is; nested maps are sorted.
   Non-Comparable keys (e.g. byte arrays) are converted to strings."
  [x]
  (cond
    (map? x) (into (sorted-map-by (fn [a b]
                                    (try
                                      (compare a b)
                                      (catch ClassCastException _
                                        (compare (str a) (str b))))))
                   (map (fn [[k v]] [k (sort-maps v)]) x))
    (coll? x) (into (empty x) (map sort-maps x))
    :else x))

(defn- yield-module-map?
  "True when x is a declarative yield module map: it carries a :module/id and an
   :ops map whose values are runtime functions (the module implementations)."
  [x]
  (and (map? x)
       (some? (:module/id x))
       (map? (:ops x))
       (some fn? (vals (:ops x)))))

(defn normalize-runtime-values
  "WRITER-BOUNDARY normalization: convert runtime Clojure objects into stable,
   portable descriptors so the persisted evidence structure contains no
   functions or opaque runtime values.

   - Declarative yield module maps (:ops carrying fns) → describe-module
     descriptor ({:module/id ... :module/type ... :ops [...]}).
   - Any remaining fn (defensive) → stable {:type :fn :class ...} marker.

   This is the durable fix for the legacy #object[...] compatibility reader:
   evidence is written without runtime values, so a reader never needs to
   reconstruct (or accidentally execute) a function. The committed
   :evidence/hash (:bundle-root) is unaffected because it excludes
   :individual-results/:detailed-evidence and normalizes fns via
   project-world-to-structure-view."
  [x]
  (cond
    (yield-module-map? x) (yield-module/describe-module x)
    ;; Protocol instances are runtime dispatch adapters, not portable benchmark
    ;; evidence. Preserve only their declared deterministic identity wherever a
    ;; replay world/detail still references one.
    (satisfies? protocol-api/SimulationAdapter x)
    {:protocol/id (protocol-api/protocol-id x) :protocol/version 1}
    (fn? x) {:type :fn :class (str (class x))}
    ;; Class values (e.g. a resolved adapter class) are runtime objects, not
    ;; portable data: they print via Object/toString ("class foo.Bar") and do
    ;; not survive an EDN round-trip as the same value, so commit the declared
    ;; class identity as a stable descriptor to keep the persisted evidence
    ;; identical to the form that the :bundle-root commitment was computed over.
    (instance? java.lang.Class x) {:type :class :name (.getName x)}
    ;; Instant → ISO-8601 string, matching project-world-to-structure-view's
    ;; :instant→iso8601-string contract. Persisting the canonical form (instead
    ;; of a #object[java.time.Instant ...] tag) keeps evidence fully portable
    ;; and lets the committed :bundle-root hash recompute from the file.
    (instance? java.time.Instant x) (.toString x)
    ;; Integer-valued clojure.lang.Ratio values (e.g. 100/1) are written as
    ;; "100/1" but edn reduces them to Long 100 on read, so an in-memory hash
    ;; over the Ratio diverges from recomputation over the persisted file.
    ;; Fractional ratios (e.g. 100/3) already round-trip as Ratio, so only
    ;; integer-valued ratios need canonicalizing — to the exact Long the
    ;; persisted form reads back as.
    (instance? clojure.lang.Ratio x)
    (let [n (.numerator x) d (.denominator x)]
      (if (zero? (mod n d))
        (long (/ n d))
        x))
    (map? x) (into {} (map (fn [[k v]] [k (normalize-runtime-values v)]) x))
    (coll? x) (into (empty x) (map normalize-runtime-values x))
    :else x))

(defn write-evidence
  ([evidence output-path]
   (write-evidence evidence output-path nil))
  ([evidence output-path run-manifest]
   (io/make-parents output-path)
   (let [stable (-> evidence
                    (cond-> run-manifest (assoc :run/manifest run-manifest))
                    normalize-runtime-values
                    sort-maps)]
     (spit output-path (ppedn/ppr-str stable))
     (log/info! "benchmark/evidence-written" {:output-path output-path
                                              :sorted-keys? true
                                              :runtime-values-normalized? true})
     (println "Evidence bundle written to:" output-path))))
