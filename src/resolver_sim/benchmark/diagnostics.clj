(ns resolver-sim.benchmark.diagnostics
  "Portable runtime health checks for prf-benchmark.jar.
   Doctor verifies JAR metadata, embedded resources, output writability,
   and VCS independence.  verify-portability is a self-test that loads
   all built-in resources from classpath without requiring repo root."
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [resolver-sim.io.resource-path :as rp]
            [resolver-sim.vcs :as vcs]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.io.edn :as ppedn]))

;; ── Check helpers ───────────────────────────────────────────────────────────

(defn- pass
  ([details] (pass :generic details))
  ([check details] {:check check :passed? true :details details}))

(defn- fail
  ([details] (fail :generic details))
  ([check details] {:check check :passed? false :details details}))

(defn- resource-exists? [path]
  (rp/path-exists? path))

(defn- run-check
  "Run a check fn that returns {:passed? bool :details str}, wrap with check name."
  [check-name f]
  (try
    (let [result (f)]
      (assoc result :check check-name))
    (catch Exception e
      {:check check-name :passed? false
       :details (str "Check threw: " (.getMessage e))})))

;; ── Individual doctor checks ────────────────────────────────────────────────

(defn check-jar-metadata
  "Verify META-INF/prf-runner.edn is readable with expected fields."
  []
  (if-let [meta (try (rp/edn-read (str hash-ref/resource-prefix hash-ref/prf-runner-edn-path))
                     (catch Exception _ nil))]
    (let [variant (:variant meta)
          version (:version meta)
          built-at (:built-at meta)]
      (if (and variant version built-at)
        (pass (str "JAR: " variant " " version " built " built-at))
        (fail (str "JAR metadata incomplete: " (pr-str (select-keys meta [:variant :version :built-at]))))))
    (fail "META-INF/prf-runner.edn not found")))

(defn check-embedded-resources
  "Verify all built-in resource: URIs resolve."
  []
  (let [paths [hash-ref/benchmark-registry-path
               (str hash-ref/resource-prefix hash-ref/concept-registry-path)
               (str hash-ref/resource-prefix hash-ref/fixture-suite-manifest-path)
               (str hash-ref/resource-prefix hash-ref/evidence-config-path)
               "resource:scenarios/edn/S01_baseline-happy-path.edn"
               hash-ref/reference-validation-suite-manifest]
        results (mapv (fn [p] {:path p :ok? (resource-exists? p)}) paths)
        missing (filterv #(not (:ok? %)) results)]
    (if (seq missing)
      (fail (str (count missing) " missing: " (pr-str (mapv :path missing))))
      (pass (str (count paths) "/" (count paths) " embedded resources verified")))))

(defn check-output-writable
  "Verify we can create and write to an output directory."
  []
  (try
    (let [test-file (java.io.File. "./prf-out/.doctor-write-test.txt")]
      (io/make-parents test-file)
      (spit test-file "ok")
      (.delete test-file)
      (pass "Output directory ./prf-out/ is creatable and writable"))
    (catch Exception e
      (fail (str "Cannot write to ./prf-out/: " (.getMessage e))))))

(defn check-no-git-required
  "Verify that VCS operations return nil (not throw) when no git/jj repo."
  []
  (try
    (let [root (vcs/root)
          commit (vcs/commit-sha)]
      (pass (str "VCS root: " (pr-str root) " (nullable — no exception thrown)")))
    (catch Exception e
      (fail (str "VCS check threw exception: " (.getMessage e))))))

(defn check-java-version
  []
  (let [v (System/getProperty "java.version" "unknown")]
    (if (re-matches #"^(1[7-9]|[2-9][0-9]).*" v)
      (pass (str "Java version: " v))
      (fail (str "Java version: " v " (expected 17+)")))))

(defn check-vcs-optional
  "Verify vcs/metadata returns nil (not throws) outside a repo."
  []
  (try
    (let [m (vcs/metadata)]
      (pass (str "vcs/metadata returned: " (pr-str (some-> m keys)) " (nullable)")))
    (catch Exception e
      (fail (str "vcs/metadata threw: " (.getMessage e))))))

;; ── Doctor orchestration ────────────────────────────────────────────────────

(def doctor-checks
  "Ordered vector of [check-name check-fn] for all doctor checks."
  [[:jar-metadata check-jar-metadata]
   [:embedded-resources check-embedded-resources]
   [:output-writable check-output-writable]
   [:no-git-required check-no-git-required]
   [:java-version check-java-version]
   [:vcs-optional check-vcs-optional]])

(defn doctor
  "Run all runtime health checks.
   opts: :out-dir — where to write doctor-report.edn (default ./prf-out/doctor)
   Returns {:healthy? bool :checks [map] :output-files [str]}."
  [& {:keys [out-dir]
      :or {out-dir "./prf-out/doctor"}}]
  (let [run-id (str "dr-" (java.time.Instant/now))
        effective-out-dir (str out-dir "/" run-id)
        _ (io/make-parents (io/file effective-out-dir "placeholder"))
        results (mapv (fn [[name f]] (run-check name f)) doctor-checks)
        total (count results)
        passed (count (filter :passed? results))
        healthy? (every? :passed? results)
        report {:healthy? healthy?
                :run-id run-id
                :timestamp (str (java.time.Instant/now))
                :total-checks total
                :passed-checks passed
                :failed-checks (- total passed)
                :checks results}
        edn-path (str effective-out-dir "/doctor-report.edn")
        json-path (str effective-out-dir "/doctor-report.json")]
    (spit edn-path (ppedn/ppr-str report))
    (spit json-path (json/write-str report {:key-fn name}))
    (assoc report
           :exit-code (if healthy? 0 1)
           :output-files [edn-path json-path])))

;; ── Portability verification ────────────────────────────────────────────────

(def portability-checks
  "Resource paths that must load from classpath for portable operation.
   Each entry is {:path <path> :label <human-readable>}."
  [{:path hash-ref/benchmark-registry-path
    :label "Benchmark pack registry"}
   {:path (str hash-ref/resource-prefix hash-ref/concept-registry-path)
    :label "Concept registry"}
   {:path (str hash-ref/resource-prefix hash-ref/fixture-suite-manifest-path)
    :label "Fixture suite manifest"}
   {:path "resource:data/fixtures/suites/equilibrium-validation.edn"
    :label "Equilibrium validation suite"}
   {:path "resource:data/fixtures/suites/cancellation-equilibrium-validation.edn"
    :label "Cancellation equilibrium validation suite"}
   {:path hash-ref/scoring-robustness-dimensions-path
    :label "Scoring rule: robustness dimensions"}
   {:path (str hash-ref/resource-prefix hash-ref/sew-pack-registry-path)
    :label "Sew benchmark pack registry"}
   {:path (str hash-ref/resource-prefix hash-ref/evidence-config-path)
    :label "Evidence config"}
   {:path "resource:scenarios/edn/S01_baseline-happy-path.edn"
    :label "Executable scenario"}
   {:path hash-ref/reference-validation-suite-manifest
    :label "Reference validation suite"}
   {:path "resource:data/concepts/use-case/ecommerce.edn"
    :label "Concept definition file"}])

(defn- resolve-source
  "Determine whether a path resolved via filesystem or classpath."
  [path-spec]
  (cond
    (.exists (java.io.File. path-spec)) :filesystem
    (rp/path-exists? path-spec) :classpath
    :else nil))

(defn verify-portability
  "Self-test: load all built-in resources from classpath.
   Runs from any CWD.  Fails if any required resource loads from
   filesystem instead of classpath.
   opts: :out-dir — where to write portability report (default ./prf-out/verify)
   Returns {:portable? bool :results [map] :output-files [str]}."
  [& {:keys [out-dir]
      :or {out-dir "./prf-out/verify"}}]
  (let [run-id (str "pv-" (java.time.Instant/now))
        effective-out-dir (str out-dir "/" run-id)
        _ (io/make-parents (io/file effective-out-dir "placeholder"))
        results (mapv (fn [{:keys [path label]}]
                        (let [exists? (rp/path-exists? path)
                              source (if exists?
                                       (if (.exists (java.io.File. (subs path (count hash-ref/resource-prefix))))
                                         :filesystem-fallback
                                         :classpath)
                                       :not-found)]
                          {:path path
                           :label label
                           :exists? exists?
                           :source source
                           :ok? (and exists? (= :classpath source))}))
                      portability-checks)
        portable? (every? :ok? results)
        filesystem-fallbacks (filterv #(= :filesystem-fallback (:source %)) results)
        not-found (filterv #(= :not-found (:source %)) results)
        report {:portable? portable?
                :run-id run-id
                :timestamp (str (java.time.Instant/now))
                :total-checks (count results)
                :passed (count (filter :ok? results))
                :failed (count (remove :ok? results))
                :filesystem-fallbacks (count filesystem-fallbacks)
                :not-found (count not-found)
                :filesystem-fallback-details (mapv :path filesystem-fallbacks)
                :not-found-details (mapv :path not-found)
                :results results}
        edn-path (str effective-out-dir "/portability-report.edn")
        json-path (str effective-out-dir "/portability-report.json")]
    (spit edn-path (ppedn/ppr-str report))
    (spit json-path (json/write-str report {:key-fn name}))
    (assoc report
           :exit-code (if portable? 0 1)
           :output-files [edn-path json-path])))
