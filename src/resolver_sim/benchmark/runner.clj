(ns resolver-sim.benchmark.runner
  (:require [resolver-sim.benchmark.packs.partial-fill.evidence :as pf-evidence]
            [resolver-sim.allocation.proof-admission :as proof-admission]
            [resolver-sim.benchmark.repo :as repo]
            [resolver-sim.benchmark.adapter :as adapter]
            [resolver-sim.benchmark.claims :as benchmark-claims]
            [resolver-sim.benchmark.coverage :as benchmark-coverage]
            [resolver-sim.benchmark.execution-identity :as execution-identity]
            [resolver-sim.benchmark.case-set :as case-set]
            [resolver-sim.concepts.benchmark :as benchmark-concepts]
            [resolver-sim.evidence.chain :as chain]
            [resolver-sim.evidence.config :as evidence-config]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.io.resource-path :as rp]
            [resolver-sim.io.input-source :as input-source]
            [resolver-sim.io.scenarios :as io-sc]
            [resolver-sim.logging :as log]
            [resolver-sim.contract-model.replay :as replay]
            [resolver-sim.protocols.registry :as protocols]
            [resolver-sim.scenario.runner :as scenario-runner]
            [resolver-sim.scenario.suites :as suites]
            [resolver-sim.yield.module :as yield-module]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [resolver-sim.config.paths :as paths]
            [resolver-sim.io.edn :as ppedn])
  (:import [java.math BigInteger]
           [java.nio.file Files]
           [java.util UUID]
           [java.security MessageDigest]))

;; ── Helpers ──────────────────────────────────────────────────────────────────

(declare normalize-runtime-values)

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

(defn- execute-scenario
  [suite-kw scenario-source ordinal repetition-index run-count executions-dir]
  (let [path (:input/ref scenario-source)
        scenario (load-scenario scenario-source)
        descriptor (execution-identity/descriptor scenario-source scenario repetition-index)
        execution-id (execution-identity/execution-id descriptor)
        protocol (or (:protocol scenario) protocols/default-protocol-id)
        adapter (protocols/get-protocol protocol)
        _ (when-not adapter
            (throw (ex-info "Benchmark scenario protocol extension is unavailable"
                            {:protocol protocol
                             :known-protocols (vec (protocols/known-protocol-ids))})))
        output-dir (execution-output-dir executions-dir ordinal descriptor)
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
        replay-result (if output-dir
                        (binding [evidence-config/*artifact-dir* output-dir]
                          (run-replay))
                        (run-replay))
        entry (scenario-runner/run-scenario scenario
                                            {:replay-fn (fn [_] replay-result)
                                             :source :benchmark
                                             :evaluate-theory? (:evaluate-theory?
                                                                (scenario-runner/runner-opts-for-scenario scenario))})
        execution-package (write-execution-package! output-dir scenario-source scenario replay-result)
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
            :simulator/scenario-path path
            :execution/id execution-id
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
            :scenario/artifacts execution-package})))

(defrecord SewAdapter [scenario-output-dir]
  adapter/RepositoryAdapter
  (load-scenarios [_ benchmark]
    (if-let [suite-kw (:benchmark/scenario-suite benchmark)]
      (resolve-suite-scenarios suite-kw)
      (mapv #(input-source/source (.getPath %))
            (find-scenarios-in-suites (:scenario-suites benchmark)))))

  (execute-benchmark [_ benchmark scenarios]
    (let [suite-kw (:benchmark/scenario-suite benchmark)
          run-count (benchmark-run-count benchmark)
          plan (vec (for [repetition-index (range run-count)
                          scenario-file scenarios]
                      [repetition-index scenario-file]))]
      (mapv (fn [ordinal [repetition-index scenario-file]]
              (execute-scenario suite-kw scenario-file (inc ordinal)
                                repetition-index run-count scenario-output-dir))
            (range)
            plan)))

  (collect-metrics [_ results]
    {:total (count results)
     :passed (count (filter :pass? results))
     :execution-count (count results)
     :passed-execution-count (count (filter :pass? results))
     :unique-scenario-count (unique-scenario-count results)
     :declared-run-count (apply max 1 (keep :benchmark/run-count results))}))

(def default-adapter (->SewAdapter nil))

;; ── Benchmark artifact index ──────────────────────────────────────────────────

(defn- reconcile-execution-plan! [plan results]
  (let [planned-by-id (into {} (map (juxt :execution/id identity) plan))
        result-by-id (group-by :execution/id results)
        planned-ids (set (keys planned-by-id))
        result-ids (set (keys result-by-id))
        missing (sort (clojure.set/difference planned-ids result-ids))
        extra (sort (clojure.set/difference result-ids planned-ids))
        duplicates (->> result-by-id (filter (fn [[_ rs]] (not= 1 (count rs)))) (map first) sort vec)
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
    (when (or (seq missing) (seq extra) (seq duplicates) (seq misplaced))
      (throw (ex-info "Benchmark execution plan reconciliation failed"
                      {:missing missing :extra extra :duplicates duplicates :misplaced misplaced})))
    true))

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

(defn run-benchmark
  ([manifest-path] (run-benchmark manifest-path default-adapter {}))
  ([manifest-path adapter] (run-benchmark manifest-path adapter {}))
  ([manifest-path adapter {:keys [scenario-output-dir benchmark-index-path execution-plan-path]}]
   (let [adapter (if scenario-output-dir
                   (->SewAdapter scenario-output-dir)
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
         plan (build-execution-plan manifest scenarios)
         _ (write-execution-plan! execution-plan-path manifest plan)
         _ (do
             (println "Executing" (count scenarios) "scenarios...")
             (log/info! "benchmark/execute" {:scenario-count (count scenarios)
                                             :manifest manifest-path}))
         results (adapter/execute-benchmark adapter manifest scenarios)
         _ (reconcile-execution-plan! plan results)
         artifact-index (write-artifact-index! scenario-output-dir benchmark-index-path (:benchmark/id manifest) results)

         metrics (adapter/collect-metrics adapter results)
         passed? (= (:total metrics) (:passed metrics))

          ;; Aggregate invariant summary across all scenarios
         all-inv-results (mapcat :invariant-results results)
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
         claim-results (try
                         (benchmark-claims/evaluate-manifest-claims manifest results)
                         (catch Exception e
                           (log/warn! "benchmark/claim-evaluation-failed"
                                      {:error (.getMessage e)})
                           []))

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
                   :benchmark-certification certification}

         ;; The committed hash covers the normalized (persisted) representation,
         ;; not the raw in-memory map: write-evidence serializes the same
         ;; normalized form, so verify-bundle-hash can recompute it from the
         ;; artifact on disk. Hashing the raw map here would silently diverge
         ;; once normalization rewrites runtime values (e.g. Instants).
         hashable-evidence (normalize-runtime-values (dissoc evidence :timestamp))
         bundle-root-hash (hc/hash-with-intent {:hash/intent :bundle-root} hashable-evidence)
         final-evidence (assoc evidence :evidence/hash bundle-root-hash)]

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
    (fn? x) {:type :fn :class (str (class x))}
    ;; Instant → ISO-8601 string, matching project-world-to-structure-view's
    ;; :instant→iso8601-string contract. Persisting the canonical form (instead
    ;; of a #object[java.time.Instant ...] tag) keeps evidence fully portable
    ;; and lets the committed :bundle-root hash recompute from the file.
    (instance? java.time.Instant x) (.toString x)
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
