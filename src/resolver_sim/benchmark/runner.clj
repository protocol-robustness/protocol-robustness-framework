(ns resolver-sim.benchmark.runner
  (:require [resolver-sim.benchmark.repo :as repo]
            [resolver-sim.benchmark.adapter :as adapter]
            [resolver-sim.benchmark.claims :as benchmark-claims]
            [resolver-sim.benchmark.coverage :as benchmark-coverage]
            [resolver-sim.concepts.benchmark :as benchmark-concepts]
            [resolver-sim.evidence.chain :as chain]
            [resolver-sim.evidence.config :as evidence-config]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.io.resource-path :as rp]
            [resolver-sim.io.input-source :as input-source]
            [resolver-sim.io.scenarios :as io-sc]
            [resolver-sim.logging :as log]
            [resolver-sim.contract-model.replay :as replay]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.protocols.sew.invariants :as sew-inv]
            [resolver-sim.protocols.yield :as yp]
            [resolver-sim.scenario.suites :as suites]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.math BigInteger]
           [java.security MessageDigest]))

;; ── Helpers ──────────────────────────────────────────────────────────────────

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
    (let [manifest (edn/read-string (slurp (io/resource "suites/reference-validation-v1/manifest.edn")))
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

(defn- execution-output-dir
  [scenario-output-dir scenario-source run-index]
  (when scenario-output-dir
    (let [base-name (:input/display-name scenario-source)
          scenario-name (str/replace base-name #"\.(edn|json)$" "")]
      (str (io/file scenario-output-dir
                    (safe-path-component scenario-name)
                    (str "run-" run-index))))))

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
      (spit replay-file (pr-str result))
      (spit summary-file (pr-str {:scenario/source-path (:input/ref scenario-source)
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

(defn- execute-scenario
  [suite-kw scenario-source run-index run-count scenario-output-dir]
  (let [path (:input/ref scenario-source)
        scenario (load-scenario scenario-source)
        protocol (:protocol scenario)
        output-dir (execution-output-dir scenario-output-dir scenario-source run-index)
        run-replay (fn []
                      (if (= "yield-v1" protocol)
                        (replay/replay-events (yp/protocol) scenario
                                              {:flags {:yield-dt-validation? true
                                                       :metrics-profile :yield-provider}})
                        (sew/replay-with-sew-protocol scenario
                                                      {:allow-dirty? (or chain/*allow-dirty* false)})))
        result (if output-dir
                 (binding [evidence-config/*artifact-dir* output-dir]
                   (run-replay))
                 (run-replay))
        execution-package (write-execution-package! output-dir scenario-source scenario result)
        public-id (benchmark-public-scenario-id suite-kw path)
        scenario-evidence (hc/hash-with-intent
                           {:hash/intent :evidence-content}
                           (select-keys result
                                        [:events-processed :outcome :halt-reason]))
        ;; Post-hoc invariant check: run check-all on final world, then
        ;; merge with any per-step failures from the replay metrics.
        final-world (:world result)
        step-failures (get-in result [:metrics :invariant-results] {})
        all-inv-ids (sort sew-inv/canonical-ids)
        post-check (when final-world
                     (:results (sew-inv/check-all final-world)))
        inv-results (mapv (fn [id]
                            {:id id
                             :result (cond
                                       (contains? step-failures id) :fail
                                       (get post-check id) :pass
                                       (false? (get-in post-check [id :holds?])) :fail
                                       :else :pass)})
                          all-inv-ids)]
    {:file path
     :scenario/id public-id
     :simulator/scenario-path path
     :benchmark/run-index run-index
     :benchmark/run-count run-count
     :outcome (:outcome result)
     :halt-reason (:halt-reason result)
     :metrics (:metrics result)
     ;; Preserve allocation artifacts required by closed-form benchmark claims,
     ;; without embedding the complete final protocol world in the bundle.
     :partial-fill-decisions (->> (get-in final-world [:yield/partial-fill-decisions] {})
                                  vals
                                  (sort-by :decision/id)
                                  vec)
     :invariant-results inv-results
     :scenario/evidence-root scenario-evidence
     :scenario/artifacts execution-package}))

(defrecord SewAdapter [scenario-output-dir]
  adapter/RepositoryAdapter
  (load-scenarios [_ benchmark]
    (if-let [suite-kw (:benchmark/scenario-suite benchmark)]
      (resolve-suite-scenarios suite-kw)
      (mapv #(input-source/source (.getPath %))
            (find-scenarios-in-suites (:scenario-suites benchmark)))))

  (execute-benchmark [_ benchmark scenarios]
    (let [suite-kw (:benchmark/scenario-suite benchmark)
          run-count (benchmark-run-count benchmark)]
      (vec
       (mapcat (fn [run-index]
                 (map (fn [scenario-file]
                        (execute-scenario suite-kw scenario-file run-index run-count scenario-output-dir))
                      scenarios))
               (range 1 (inc run-count))))))

  (collect-metrics [_ results]
    {:total (count results)
     :passed (count (filter #(= :pass (:outcome %)) results))
     :execution-count (count results)
     :passed-execution-count (count (filter #(= :pass (:outcome %)) results))
     :unique-scenario-count (unique-scenario-count results)
     :declared-run-count (apply max 1 (keep :benchmark/run-count results))}))

(def default-adapter (->SewAdapter nil))

;; ── Benchmark artifact index ──────────────────────────────────────────────────

(defn- write-artifact-index!
  [scenario-output-dir benchmark-id results]
  (when scenario-output-dir
    (let [index-path (str (io/file scenario-output-dir "benchmark-index.edn"))
          executions (mapv (fn [result]
                             (let [artifacts (:scenario/artifacts result)]
                               {:scenario/id (:scenario/id result)
                                :scenario/source-path (:simulator/scenario-path result)
                                :benchmark/run-index (:benchmark/run-index result)
                                :benchmark/run-count (:benchmark/run-count result)
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
      (spit index-path (pr-str index))
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
  ([manifest-path adapter {:keys [scenario-output-dir]}]
   (let [adapter (if scenario-output-dir
                   (->SewAdapter scenario-output-dir)
                   adapter)
         manifest (load-manifest manifest-path)
         repo-meta (repo/metadata)
         scenarios (adapter/load-scenarios adapter manifest)
         _ (do
             (println "Executing" (count scenarios) "scenarios...")
             (log/info! "benchmark/execute" {:scenario-count (count scenarios)
                                             :manifest manifest-path}))
         results (adapter/execute-benchmark adapter manifest scenarios)
         artifact-index (write-artifact-index! scenario-output-dir (:benchmark/id manifest) results)

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
                   :reproduce      {:command (str "bb benchmark:reproduce " (or manifest-path "benchmarks/packs/sew/escrow-dispute-v1.edn"))}
                   :invariant-summary {:per-invariant  inv-summary
                                       :total-checks   total-inv-checks
                                       :passed-checks  passed-inv-checks
                                       :all-pass?      all-invariants-pass?}
                   :concept/section concept-section
                   :concept/coverage concept-coverage
                   :run/manifest run-manifest
                   :benchmark/artifact-index artifact-index
                   :benchmark-certification certification}

         hashable-evidence (dissoc evidence :timestamp)
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

(defn write-evidence
  ([evidence output-path]
   (write-evidence evidence output-path nil))
  ([evidence output-path run-manifest]
   (io/make-parents output-path)
   (let [stable (-> evidence
                    (cond-> run-manifest (assoc :run/manifest run-manifest))
                    sort-maps)]
     (spit output-path (pr-str stable))
     (log/info! "benchmark/evidence-written" {:output-path output-path
                                              :sorted-keys? true})
     (println "Evidence bundle written to:" output-path))))
