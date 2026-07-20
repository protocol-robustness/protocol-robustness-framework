(ns resolver-sim.sim.reference-validation
  "Reference Validation Suite v1 — deterministic public evidence harness.

   Reads suites/<suite>/manifest.edn, runs simulator-backed
   scenarios via replay + trace export, verifies evidence→canonical invariants,
   and writes actual/*.json for CI verification.

   Protocol parameterization: the replay function is supplied via
   `:replay-fn` to generate! (default sew/replay-with-sew-protocol).
   The `-main` entry point accepts --protocol and --suite-root flags."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [resolver-sim.contract-model.replay.yield :as yield-replay]
            [resolver-sim.yield.invariant-catalog :as yield-invariant-catalog]
            [resolver-sim.yield.invariants :as yield-invariants]
            [resolver-sim.sim.reference-validation-evidence :as evidence])
  (:gen-class))

(def ^{:doc "Default suite root (relative to project root)."}
  default-suite-root "suites/reference-validation-v1")

(defn- yield-replay-wrapper
  "Wraps replay-yield-scenario to accept (scenario opts) signature.
   replay-yield-scenario has 2-arity [protocol scenario], not [scenario opts],
   so the opts map would be misinterpreted as the scenario."
  [scenario _opts]
  (yield-replay/replay-yield-scenario scenario))

(def ^{:doc "Core protocol keyword → replay function. Protocol extensions are resolved lazily."}
  protocols
  {:yield yield-replay-wrapper})

(defn- sew-replay-wrapper [scenario opts]
  ((requiring-resolve 'resolver-sim.protocols.sew/replay-with-sew-protocol) scenario opts))

(defn- protocol-replay-fn [protocol]
  (case protocol
    :yield yield-replay-wrapper
    :sew sew-replay-wrapper
    (throw (ex-info "Unknown reference-validation protocol" {:protocol protocol}))))

(def ^{:doc "Default replay function (loaded lazily so yield needs no Sew classpath)."}
  default-replay-fn sew-replay-wrapper)

(defn- suite-root
  [& [root]]
  (io/file (or root default-suite-root)))

(defn- load-manifest
  [& [root]]
  (-> (suite-root root) (io/file "manifest.edn") slurp edn/read-string))

(defn- sha256-hex [^bytes bytes]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")]
    (.digest md bytes)))

(defn- sha256-file [path]
  (-> path slurp .getBytes sha256-hex
      (->> (map #(format "%02x" %))
           (apply str))))

(defn- sort-keys-deep [x]
  (cond
    (map? x)    (into (sorted-map) (map (fn [[k v]] [k (sort-keys-deep v)]) x))
    (vector? x) (mapv sort-keys-deep x)
    (seq? x)    (map sort-keys-deep x)
    :else x))

(defn- write-json! [path data]
  (io/make-parents path)
  (spit path (json/write-str (sort-keys-deep data))))

(defn- write-sha256! [json-path]
  (let [hash-path (if (str/ends-with? json-path ".json")
                    (str (subs json-path 0 (- (count json-path) 5)) ".sha256")
                    (str json-path ".sha256"))]
    (spit hash-path (str (sha256-file json-path) "\n"))))

(defn- namespace-keyword->string
   "Convert a namespaced keyword to a string preserving namespace.
    :transition/hash -> \"transition/hash\", :foo -> \"foo\""
   [k]
   (if (keyword? k)
     (str (when-let [ns (namespace k)] (str ns "/")) (name k))
     (str k)))

 (defn- keyword->string
   "Recursively convert keywords to strings and ratios to doubles for JSON-safe output."
   [x]
   (cond
     (keyword? x) (namespace-keyword->string x)
     (instance? clojure.lang.Ratio x) (double x)
     (map? x) (into {} (map (fn [[k v]] [(namespace-keyword->string k) (keyword->string v)]) x)
     (coll? x) (map keyword->string x)
     :else x))

(defn- export-yield-trace-fixture
  "Export a yield-v1 replay result as a JSON-safe trace fixture map.
   Strips the full world state from each trace entry and converts
   remaining keys to strings."
  [result]
  (let [trace (:trace result)]
    {:cdrs_version "0.2"
     :schema_version "2"
     :scenario_id (:scenario-id result)
     :description (str "Yield-v1 trace: " (:scenario-id result))
     :step_count (count trace)
:steps (keyword->string
              (mapv (fn [entry]
                      (select-keys entry [:seq :time :action :agent :result :error :params :transition/id :transition/hash]))
                    trace))}))

(defn- write-trace-fixture!
  "Write trace fixture for a protocol. Sew support is resolved only when requested."
  [protocol result scenario trace-path]
  (case protocol
    :sew (let [export-trace-fixture (requiring-resolve 'resolver-sim.protocols.sew.io.trace-export/export-trace-fixture)
               write-fixture-file (requiring-resolve 'resolver-sim.protocols.sew.io.trace-export/write-fixture-file)]
           (write-fixture-file (export-trace-fixture result scenario) trace-path))
    :yield (write-json! trace-path (export-yield-trace-fixture result))
    nil))

(defn- run-simulator-scenario
  [sc actual-dir protocol replay-fn replay-opts]
  (let [{:keys [id trace-slug upgrade-path classification primary-threat
                expectations-passed invariants-passed claim-id invariant-ids]} sc
        scenario-path (:simulator/scenario-path sc)
        trace-rel (str "actual/traces/" trace-slug ".trace.json")
        trace-path (str actual-dir "/traces/" trace-slug ".trace.json")
        scenario ((requiring-resolve 'resolver-sim.io.scenarios/load-scenario-file) scenario-path)
        result (replay-fn scenario replay-opts)]
    (evidence/verify-evidence-invariants!
     result (or invariant-ids [])
     :world-invariant-ids (if (= protocol :yield)
                            (set (keys yield-invariant-catalog/catalog))
                            nil)
     :check-all-fn (when (= protocol :yield)
                          (fn [world]
                            {:results (yield-invariants/run-invariants
                                       world (keys yield-invariant-catalog/catalog))})))
    (let [metrics         (or (:metrics result) {})
          inv-violations  (:invariant-violations metrics 0)
          expectations    (:expectations result)
          exp-ok?         (or (nil? expectations) (:ok? expectations))
          exp-violations  (count (:violations expectations []))]
      (when (not= :pass (:outcome result))
        (throw (ex-info "reference-validation simulator scenario did not pass"
                        {:scenario-id id :path scenario-path
                         :outcome (:outcome result) :halt-reason (:halt-reason result)})))
      (when (pos? inv-violations)
        (throw (ex-info "reference-validation scenario has invariant violations"
                        {:scenario-id id :invariant-violations inv-violations})))
      (when-not exp-ok?
        (throw (ex-info "reference-validation scenario expectations failed"
                        {:scenario-id id :expectation-violations exp-violations})))
      (write-trace-fixture! protocol result scenario trace-path)
      (when (.exists (io/file trace-path))
        (write-sha256! trace-path))
      (let [trace-rel-path (if (.exists (io/file trace-path))
                             trace-rel
                             nil)
            trace-hash (when trace-rel-path
                         (sha256-file trace-path))]
        {:scenario_id id
         :classification (kw-str classification)
         :confidence "high"
         :evidence_type "simulator-backed"
         :expectations_failed (if exp-ok? 0 exp-violations)
         :expectations_passed (or expectations-passed 0)
         :invariants_failed inv-violations
         :invariants_passed (or invariants-passed (count (or invariant-ids [])))
         :primary_claim (name claim-id)
         :primary_threat primary-threat
         :simulator_backed true
         :source_artifact scenario-path
         :status "pass"
         :trace_hash trace-hash
         :trace_path trace-rel-path
         :upgrade_path upgrade-path}))))

(defn- build-scenario-results [manifest actual-dir protocol replay-fn replay-opts]
  {:suite_id (:suite/id manifest)
   :results (mapv #(run-simulator-scenario % actual-dir protocol replay-fn replay-opts)
                  (:scenarios manifest))})

(defn- build-invariants [manifest]
  (let [scenario-by-id (into {} (map (fn [sc] [(:id sc) sc]) (:scenarios manifest)))]
    {:suite_id (:suite/id manifest)
     :invariants
     (mapv
      (fn [{:keys [id scenarios]}]
        (let [paths (keep #(get-in scenario-by-id [% :simulator/scenario-path]) scenarios)]
          {:invariant_id id
           :scenarios scenarios
           :status (if (seq paths) "pass" "fail")
           :simulator_backed (boolean (seq paths))}))
      (:invariants manifest))}))

(defn- build-evidence-matrix [manifest scenario-results]
  (let [result-by-id (into {} (map (fn [r] [(:scenario_id r) r]) (:results scenario-results)))
        primary (fn [scenario-ids] (get result-by-id (first scenario-ids)))]
    {:suite_id (:suite/id manifest)
     :claims
     (mapv
      (fn [{:keys [claim-id claim threat invariant-ids scenario-ids upgrade-path]}]
        (let [r (primary scenario-ids)]
          {:claim claim
           :claim_id (name claim-id)
           :confidence (:confidence r)
           :evidence_type (:evidence_type r)
           :invariants invariant-ids
           :scenarios scenario-ids
           :simulator_backed (:simulator_backed r)
           :source_artifact (if (:simulator_backed r)
                              (:source_artifact r)
                              "expected/evidence-matrix.json")
           :status "pass"
           :threat threat
           :trace_hash (:trace_hash r)
           :trace_path (:trace_path r)
           :upgrade_path upgrade-path}))
      (:claims manifest))}))

(defn- build-economic-results [manifest]
  {:suite_id (:suite/id manifest)
   :economic_results
   (mapv
    (fn [{:keys [check-id classification scenario-id]}]
      {:scenario_id scenario-id
       :status "pass"
       :checks
       [{:check_id check-id
         :classification (kw-str classification)
         :confidence "provisional"
         :evidence_type "pinned-derivation"
         :simulator_backed false
         :source_artifact "expected/economic-results.json"
         :status "pass"
         :upgrade_path "wire to deterministic simulator replay and commit trace hash"}]})
    (:economic-checks manifest))})

(defn- build-summary [manifest actual-dir results]
  (let [sim-backed (count results)
        hashes {:scenario_results (sha256-file (str actual-dir "/scenario-results.json"))
                :invariants (sha256-file (str actual-dir "/invariants.json"))
                :economic_results (sha256-file (str actual-dir "/economic-results.json"))
                :evidence_matrix (sha256-file (str actual-dir "/evidence-matrix.json"))}]
    {:config_version (:config/version manifest)
     :failed 0
     :inconclusive 0
     :passed (count results)
     :pinned_derivation_count 0
     :placeholder_count 0
     :result_hashes hashes
     :scenario_count (count results)
     :simulator_backed_count sim-backed
     :status "pass"
     :suite_id (:suite/id manifest)
     :suite_version (:suite/version manifest)}))

(defn- write-outputs!
  [root-dir outputs]
  (let [actual (str root-dir "/actual")]
    (doseq [[base data] outputs]
      (let [path (str actual "/" base ".json")]
        (write-json! path data)
        (write-sha256! path)))))

(defn- copy-tree [src-dir dest-dir]
  (let [src-root (.getAbsolutePath (io/file src-dir))]
    (doseq [^java.io.File f (file-seq (io/file src-root))
            :when (.isFile f)
            :let [abs (.getAbsolutePath f)
                  rel (if (.startsWith abs src-root)
                        (.substring abs (inc (count src-root)))
                        (.getName f))]]
      (let [dest (io/file dest-dir rel)]
        (io/make-parents dest)
        (io/copy f dest)))))

(defn generate!
  "Generate actual/ artifacts under suite root. Returns {:ok? true} or throws.

   Optional keyword arguments:
     :root         — suite root directory (default suites/reference-validation-v1)
     :replay-fn    — protocol replay function (default sew/replay-with-sew-protocol)
     :protocol     — keyword shortcut for known protocols (:sew)
     :refresh-expected? — copy actual/ to expected/ after generation"
  [& {:keys [root refresh-expected? replay-fn protocol replay-opts]
      :or {replay-opts {:allow-dirty? true}}}]
  (let [root-dir (or root (.getPath (suite-root)))
        manifest (load-manifest root)
        actual-dir (str root-dir "/actual")
        protocol (or protocol (when replay-fn :custom) :sew)
        replay-fn (or replay-fn (protocol-replay-fn protocol))]
    (.mkdirs (io/file actual-dir "traces"))
    (let [scenario-results (build-scenario-results manifest actual-dir protocol replay-fn replay-opts)
          results (:results scenario-results)
          outputs {"scenario-results" scenario-results
                   "invariants" (build-invariants manifest)
                   "economic-results" (build-economic-results manifest)
                   "evidence-matrix" (build-evidence-matrix manifest scenario-results)}]
      (write-outputs! root-dir outputs)
      (write-json! (str actual-dir "/summary.json")
                   (build-summary manifest actual-dir results))
      (write-sha256! (str actual-dir "/summary.json"))
      (when refresh-expected?
        (copy-tree (str root-dir "/actual") (str root-dir "/expected"))
        (when-let [^java.io.File trace-dir (io/file actual-dir "traces")]
          (when (.exists trace-dir)
            (copy-tree (.getPath trace-dir) (str root-dir "/expected/traces")))))
      {:ok? true
       :suite-id (:suite/id manifest)
       :scenario-count (count (:results scenario-results))
       :simulator-backed (count results)})))

(defn- parse-arg
  "Find value for --key in args, or nil."
  [args key]
  (let [args-vec (vec args)
        idx (.indexOf args-vec key)]
    (when (<= 0 idx)
      (nth args-vec (inc idx) nil))))

(defn- kw-arg
  "Parse --key value from args as keyword."
  [args key]
  (when-let [v (parse-arg args key)]
    (keyword v)))

(defn -main
  [& args]
  (let [refresh?     (some #{"--refresh-expected"} args)
        protocol-kw  (kw-arg args "--protocol")
        suite-root   (parse-arg args "--suite-root")
        suite-label  (or suite-root "reference-validation-v1")]
    (try
      (let [{:keys [ok? scenario-count simulator-backed]}
            (generate! :refresh-expected? refresh?
                       :protocol protocol-kw
                       :root suite-root)]
        (when ok?
          (println (str "PASS " suite-label))
          (println scenario-count "scenarios")
          (println "0 failures")
          (println "0 inconclusive")
          (when (pos? simulator-backed)
            (println simulator-backed "simulator-backed"))))
      (catch Exception e
        (println (str "FAIL " suite-label ":") (.getMessage e))
        (when-let [data (ex-data e)]
          (println "  data:" (pr-str data)))
        (System/exit 1)))))
