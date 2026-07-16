(ns resolver-sim.benchmark.cli
  (:require [resolver-sim.benchmark.sharing :as sharing]
            [resolver-sim.benchmark.registry :as registry]
            [resolver-sim.benchmark.coverage :as coverage]
            [resolver-sim.benchmark.signing :as signing]
            [resolver-sim.benchmark.validation :as validation]
            [resolver-sim.benchmark.game-theory-validation :as gt]
            [resolver-sim.benchmark.diagnostics :as diagnostics]
            [resolver-sim.evidence.chain :as chain]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.evidence.timestamping :as ts]
            [resolver-sim.io.resource-path :as rp]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]
            ;; runner is required lazily in -main to avoid loading
            ;; Sew protocol namespaces for commands like --list
            ))

(defn- load-benchmark-description
  "Read a benchmark definition file and extract its :benchmark/description.
   Falls back to a human-readable name derived from the benchmark keyword."
  [path]
  (try (:benchmark/description (rp/edn-read path))
       (catch Exception _ nil)))

(defn- keyword->display-name
  "Convert :benchmark/sew-escrow-dispute-v1 to 'sew escrow dispute v1'."
  [kw]
  (-> (name kw)
      (str/replace "-" " ")
      (str/replace #"\bv(\d+)" (fn [[_ n]] (str "v" n)))))

(defn- load-pack-benchmarks
  "Load all benchmark entries from a single pack registry file.
   Returns a vector of {:id, :description, :manifest} maps."
  [pack-id pack-reg-path]
  (if-let [pack-reg (try (rp/edn-read pack-reg-path) (catch Exception _ nil))]
    (let [pack-name (name pack-id)]
      (mapv (fn [b]
              (let [bench-path (rp/relative-to pack-reg-path (:benchmark/file b))
                    desc (or (load-benchmark-description bench-path)
                             (keyword->display-name (:benchmark/id b)))
                    manifest (try (rp/edn-read bench-path) (catch Exception _ {}))
                    bm-id (str pack-name "/" (name (:benchmark/id b)))]
                {:id bm-id
                 :benchmark/id (:benchmark/id b)
                 :description desc
                 :status (:benchmark/status b)
                 :claims (count (:benchmark/claims manifest))
                 :coverage-status (:benchmark/coverage-status manifest)
                 :manifest bench-path}))
            (:benchmarks pack-reg)))
    (do (println "Pack registry not found:" pack-reg-path) [])))

(def ^:private default-benchmark-manifest
  "resource:benchmarks/packs/sew/escrow-dispute-v1.edn")

(defn- load-index
  "Read benchmarks/registry.edn, walk the pack hierarchy, and return
   a flat list of benchmark entries in the same format as the legacy
   BENCHMARKS.edn (each with :id, :description, :manifest).

   Falls back to BENCHMARKS.edn if the canonical registry is missing."
  []
  (if-let [registry (try (rp/edn-read rp/canonical-registry-path)
                         (catch Exception _ nil))]
    {:benchmarks
     (mapcat (fn [pack]
               (load-pack-benchmarks (:pack/id pack)
                                     (rp/pack-registry-path (:pack/registry pack))))
             (:packs registry))}
    (do (println "benchmarks/registry.edn not found, falling back to BENCHMARKS.edn")
        (when-let [legacy (try (rp/edn-read "benchmarks/BENCHMARKS.edn")
                               (catch Exception _ nil))]
          {:benchmarks legacy}))))

(def cli-options
  [["-o" "--output PATH" "Output path for evidence bundle"
    :default "results/evidence/latest.edn"]
   [nil "--scenario-output-dir DIR" "Write isolated evidence packages for each benchmark scenario execution"]
   ["-k" "--key PATH" "Path to private key for signing/attesting"]
   ["-p" "--password PASS" "Password for private key"]
   ["-v" "--verify" "Verify evidence bundle integrity and signature"]
   ["-H" "--hash-only" "Compute and print hash of an evidence bundle"]
   ["-l" "--list" "List available benchmarks from benchmarks/registry.edn"]
   [nil "--reproduce PATH" "Reproduce a benchmark run from evidence bundle"]
   [nil "--share-summary PATH" "Generate share summary for an evidence bundle"]
   [nil "--export PATH" "Export portable bundle (tar.gz) from evidence bundle"]
   [nil "--publish-ipfs PATH" "Publish exported bundle to IPFS"]
      [nil "--export-dag PATH" "Export evidence DAG SVG/HTML/JSON artifacts"]
      [nil "--menu PATH" "Open the interactive post-run menu for an existing evidence bundle"]
   [nil "--attest PATH" "Generate an independent attestation for an evidence bundle"]
   [nil "--verify-attestation PATH" "Verify an independent attestation"]
   [nil "--tsa-url URL" "RFC 3161 Time-Stamp Authority URL for timestamping evidence artifacts"]
   [nil "--history" "Display local evidence run history"]
   [nil "--non-interactive" "Write the result and exit without the post-run prompt"]
   ["-h" "--help" "Show help"]])

(defn- interactive-run?
  [passed? options]
  (and passed? (not (:non-interactive options))))

(defn- hashable-evidence
  [bundle]
  (dissoc bundle :timestamp :evidence/hash :evidence/signature
          :evidence/public-key-path))

(defn- verify-bundle-hash
  "Verify current bundles and pre-v2 bundles whose hash excluded post-hash
   run metadata and certification."
  [bundle]
  (let [stored-hash (:evidence/hash bundle)
        current-hash (hc/hash-with-intent {:hash/intent :bundle-root}
                                          (hashable-evidence bundle))
        legacy-hash (hc/hash-with-intent {:hash/intent :bundle-root}
                                         (dissoc (hashable-evidence bundle)
                                                 :run/manifest
                                                 :benchmark-certification))]
    (cond
      (hc/intent-hash= current-hash stored-hash)
      {:hash-ok? true :scheme :current :computed-hash current-hash}

      (hc/intent-hash= legacy-hash stored-hash)
      {:hash-ok? true :scheme :legacy-v1 :computed-hash legacy-hash}

      :else
      {:hash-ok? false :scheme nil :computed-hash current-hash})))

(defn- interactive-ux [evidence output-path options]
  (loop []
    (println "\n" (apply str (repeat 40 "─")))
    (println "Next Actions:")
    (println "[1] Export portable bundle (.tar.gz)")
    (println "[2] Generate share summary")
    (println "[3] Generate attestation")
    (println "[4] Publish to IPFS")
    (println "[5] Reproduce locally")
    (println "[6] Export evidence DAG visualisation")
    (println "[q] Quit")
    (print "\nChoice > ")
    (flush)
    (let [choice (read-line)]
      (case choice
        "1" (sharing/export output-path (str output-path ".tar.gz"))
        "2" (println "\n" (sharing/share-summary evidence output-path))
        "3" (if-let [key-path (:key options)]
              (let [att (sharing/attest output-path key-path (:password options))
                    att-path (str output-path ".attestation.edn")]
                (spit att-path (pr-str att))
                (println "Attestation written to:" att-path))
              (println "Private key path (-k) required for attestation."))
        "4" (let [tar-path (str output-path ".tar.gz")]
              (if (or (.exists (io/file tar-path))
                      (sharing/export output-path tar-path))
                (sharing/publish-ipfs tar-path)
                (println "IPFS publication skipped because bundle export failed.")))
        "5" (sharing/reproduce output-path)
        "6" ((requiring-resolve 'resolver-sim.benchmark.dag/export!) output-path)
        "q" (System/exit 0)
        (println "Invalid choice"))
      (when-not (= choice "q")
        (recur)))))

(defn- record-history-best-effort!
  [entry]
  (try
    (registry/record-entry entry)
    (catch Exception e
      (println "Warning: benchmark history write failed:" (.getMessage e)))))

;; ── Exit codes ──────────────────────────────────────────────────────────────────

(def exit-success 0)
(def exit-error 1)
(def exit-unknown-benchmark 2)
(def exit-missing-concept 3)
(def exit-missing-scenario 4)
(def exit-invalid-params 5)
(def exit-duplicate-registries 6)

;; ── Shared orchestration ───────────────────────────────────────────────────────

(defn run-and-report
  "Run a benchmark by manifest path or benchmark ID, write evidence, and
   return {:exit-code <int> :evidence <map> :output-path <string>}.

   This is the shared orchestration function.  Called by the benchmark CLI
   and potentially other CLIs that need benchmark execution without
   taking over the process (no System/exit)."
  [benchmark-id-or-path options]
  (let [index (load-index)
        canonical-id (when (and (string? benchmark-id-or-path)
                                (.startsWith benchmark-id-or-path ":"))
                       (keyword (subs benchmark-id-or-path 1)))
        benchmark-from-index (first (filter #(or (= (:id %) benchmark-id-or-path)
                                                 (= (:benchmark/id %) benchmark-id-or-path)
                                                 (= (:benchmark/id %) canonical-id))
                                            (:benchmarks index)))
        manifest-path (cond
                        benchmark-from-index (:manifest benchmark-from-index)
                        (and benchmark-id-or-path
                             (str/ends-with? benchmark-id-or-path ".edn")) benchmark-id-or-path
                        :else (throw (ex-info "Unknown benchmark ID or manifest path"
                                              {:benchmark benchmark-id-or-path
                                               :available (mapv :id (:benchmarks index))})))
        _ (println "Running benchmark:" manifest-path)]
    (try
      (let [run-benchmark (requiring-resolve 'resolver-sim.benchmark.runner/run-benchmark)
            default-adapter (requiring-resolve 'resolver-sim.benchmark.runner/default-adapter)
            evidence (run-benchmark manifest-path @default-adapter
                                    {:scenario-output-dir (:scenario-output-dir options)
                                     :benchmark-index-path (:benchmark-index-path options)
                                     :execution-plan-path (:execution-plan-path options)})
            output-path (:output options)
            final-evidence (if-let [key-path (:key options)]
                             (let [sig (signing/sign-hash (:evidence/hash evidence) key-path (:password options))
                                   pub-path (str key-path ".pub")]
                               (assoc evidence
                                      :evidence/signature sig
                                      :evidence/public-key-path (if (.exists (io/file pub-path)) pub-path key-path)))
                             evidence)
            scenarios-passed? (= (get-in evidence [:metrics :passed]) (get-in evidence [:metrics :total]))
            required-claims-passed? (or (not= :active (get-in evidence [:benchmark :benchmark/status]))
                                        (coverage/required-claims-passed?
                                         (:benchmark evidence)
                                         (:claim-results evidence)))
            passed? (and scenarios-passed? required-claims-passed?)]
        {:exit-code (if passed? 0 1)
         :evidence final-evidence
         :output-path output-path
         :passed? passed?})
      (catch Exception e
        (println "Benchmark execution failed:" (.getMessage e))
        {:exit-code 1 :evidence nil :output-path nil :passed? false}))))

;; ── CLI dispatch — subcommands ─────────────────────────────────────────────────

(defn- dispatch-run-and-report
  [args options]
  (binding [chain/*signing-key* (:key options)
            chain/*signing-password* (:password options)
            chain/*allow-dirty* true
            ts/*tsa-url* (:tsa-url options)]
    (let [benchmark-id (first args)
          {:keys [exit-code evidence output-path passed?]}
          (run-and-report benchmark-id options)]
      (when evidence
        ((requiring-resolve 'resolver-sim.benchmark.runner/write-evidence)
         evidence output-path)
        (record-history-best-effort! evidence)
        (when (interactive-run? passed? options)
          (interactive-ux evidence output-path options)))
      (System/exit exit-code))))

(defn- print-validate-result
  "Print validation results and return exit code."
  [{:keys [valid? checks summary]}]
  (println "\nValidation" (if valid? "PASSED" "FAILED"))
  (println summary)
  (doseq [c checks]
    (println (str "  " (if (:passed? c) "PASS" "FAIL") "  " (name (:check c)) " — " (:details c))))
  (System/exit (if valid? 0 1)))

(defn- dispatch-validate
  [args options]
  (let [subcmd (first args)]
    (case subcmd
      "resources"
      (print-validate-result (validation/validate-resources))
      ;; default: run all checks
      (print-validate-result (validation/validate-all)))))

(defn- dispatch-game-theory
  [args options]
  (let [{:keys [suite format out claim-id strategic?]} options]
    (if strategic?
      (let [claim-kw (or (some-> claim-id keyword)
                         :claim/pro-rata-shortfall-conservation)
            result (gt/run-strategic-claim-validation
                    :claim-id claim-kw
                    :out-dir (or out "./prf-out/game-theory"))
            artifact (:artifact result)
            summary (:summary artifact)]
        (println "\nGame-theoretic validation"
                 (if (zero? (:exit-code result)) "PASSED" "FAILED"))
        (println "  Claim:" (:claim/id artifact))
        (println "  Matched scenarios:" (:matched-scenario-count summary))
        (println "  Levels:"
                 "passed:" (:passed-level-count summary)
                 "failed:" (:failed-level-count summary)
                 "uncovered:" (:uncovered-level-count summary))
        (doseq [f (:output-files result)]
          (println "  Output:" f))
        (System/exit (:exit-code result)))
      (let [result (gt/run-equilibrium-validation
                    :suite (when suite
                             (keyword (str/replace-first suite #"^:" "")))
                    :format (or (keyword format) :both)
                    :out-dir (or out "./prf-out/game-theory"))]
        (println "\nGame-theoretic validation"
                 (if (zero? (:exit-code result)) "PASSED" "FAILED"))
        (let [s (:summary result)]
          (println "  Suites:" (:suites-executed s) "passed:" (:suites-passed s))
          (println "  Scenarios:" (:scenario-count s))
          (println "  Equilibrium checks:"
                   "passed:" (get-in s [:equilibrium-check-summary :passed])
                   "failed:" (get-in s [:equilibrium-check-summary :failed])
                   "inconclusive:" (get-in s [:equilibrium-check-summary :inconclusive]))
          (doseq [f (:output-files result)]
            (println "  Output:" f)))
        (System/exit (:exit-code result))))))

(defn- dispatch-list
  [args options]
  (let [subcmd (first args)]
    (case subcmd
      "game-theory-checks"
      (let [catalog (gt/list-game-theory-checks)]
        (println "\nMechanism properties:")
        (doseq [mp (:mechanism-properties catalog)]
          (println (str "  " (name (:id mp)) " — " (:title mp))))
        (println "\nEquilibrium concepts:")
        (doseq [ec (:equilibrium-concepts catalog)]
          (println (str "  " (name (:id ec)) " — " (:title ec))))
        (System/exit 0))
      ;; default
      (do (println "Usage: list [game-theory-checks]")
          (System/exit 1)))))

(defn- dispatch-explain
  [args options]
  (let [topic (first args)]
    (case topic
      "game-theory"
      (let [sections (gt/explain-game-theory)]
        (doseq [{:keys [title body]} sections]
          (println "\n" title)
          (println (apply str (repeat (count title) "─")))
          (println body))
        (System/exit 0))
      ;; default
      (do (println "Usage: explain [game-theory]")
          (System/exit 1)))))

(defn- print-doctor-report
  "Print a doctor report to stdout."
  [{:keys [healthy? checks exit-code output-files] :as report}]
  (println "\nDoctor — Runtime Health Check")
  (println (apply str (repeat 35 "─")))
  (println "Status:" (if healthy? "HEALTHY" "ISSUES DETECTED"))
  (doseq [c checks]
    (println (str "  " (if (:passed? c) "PASS" "FAIL") "  " (name (:check c))
                  " — " (:details c))))
  (doseq [f output-files]
    (println "  Report:" f)))

(defn- dispatch-doctor
  [args options]
  (let [out-dir (or (:out options) "./prf-out/doctor")
        report (diagnostics/doctor :out-dir out-dir)]
    (print-doctor-report report)
    (System/exit (:exit-code report))))

(defn- dispatch-verify-portability
  [args options]
  (let [out-dir (or (:out options) "./prf-out/verify")
        result (diagnostics/verify-portability :out-dir out-dir)]
    (println "\nPortability Verification"
             (if (:portable? result) "PASSED" "FAILED"))
    (println "  Resources verified:" (:total-checks result))
    (println "  Passed:" (:passed result))
    (println "  Failed:" (:failed result))
    (when (pos? (:filesystem-fallbacks result))
      (println "  WARNING:" (:filesystem-fallbacks result)
               "resources loaded via filesystem fallback"))
    (doseq [f (:output-files result)]
      (println "  Report:" f))
    (System/exit (:exit-code result))))

;; ── CLI option specs ────────────────────────────────────────────────────────────

(def ^:private game-theory-options
  [["-s" "--suite SUITE" "Equilibrium suite keyword (e.g. :suites/spe-validation)"]
   [nil "--out DIR" "Output directory" :default "./prf-out/game-theory"]
   ["-f" "--format FORMAT" "Output format: edn, json, or both" :default "both"]
   [nil "--strategic" "Run strategic claim validation artifact generation"]
   [nil "--claim-id CLAIM" "Strategic claim id keyword name" :default "claim/pro-rata-shortfall-conservation"]])

(def subcommand-options
  "Additional option specs for subcommands that need more than the global flags."
  {"validate" []
   "validate-game-theory" game-theory-options
   "game-theoretic-validation" game-theory-options
   "doctor"
   [[nil "--out DIR" "Output directory" :default "./prf-out/doctor"]]
   "verify-portability"
   [[nil "--out DIR" "Output directory" :default "./prf-out/verify"]]})

(defn- parse-sub-opts
  "Parse options for a specific subcommand.
   Merges subcommand-specific options with global cli-options."
  [args subcmd]
  (let [extra-opts (get subcommand-options subcmd [])
        all-opts (into cli-options extra-opts)]
    (parse-opts args all-opts)))

;; ── Main dispatch ──────────────────────────────────────────────────────────────

(defn- subcommand?
  "True when the first argument is a subcommand name, not a flag or benchmark ID."
  [args]
  (contains? #{"run-and-report" "validate" "validate-game-theory"
               "game-theoretic-validation" "list" "explain"
               "doctor" "verify-portability"}
             (first args)))

(defn -main [& args]
  (if (subcommand? args)
    ;; Subcommand dispatch: parse sub-args with merged options
    (let [subcmd (first args)
          sub-args (rest args)
          extra-opts (get subcommand-options subcmd [])
          all-opts (into cli-options extra-opts)
          {:keys [options arguments errors]} (parse-opts sub-args all-opts)]
      (if errors
        (do (run! println errors) (System/exit 1))
        (case subcmd
          "run-benchmark"          (dispatch-run-and-report arguments options)
          "validate"               (dispatch-validate arguments options)
          "validate-game-theory"   (dispatch-game-theory arguments options)
          "game-theoretic-validation" (dispatch-game-theory arguments options)
          "list"                   (dispatch-list arguments options)
          "explain"                (dispatch-explain arguments options)
          "doctor"                 (dispatch-doctor arguments options)
          "verify-portability"     (dispatch-verify-portability arguments options)
          ;; fallback
          (dispatch-run-and-report arguments options)))))
    ;; Flag-based dispatch: parse with global options only
  (let [{:keys [options arguments errors summary]} (parse-opts args cli-options)]
    (cond
      errors
      (do (run! println errors) (System/exit 1))

      (:help options)
      (do
        (println "PRF Benchmark CLI")
        (println)
        (println "Usage: java -jar prf-benchmark.jar <subcommand> [options]")
        (println)
        (println "Subcommands:")
        (println "  run-benchmark <benchmark-id>    Run a benchmark (default)")
        (println "  validate [resources]             Run static validation checks")
        (println "  validate-game-theory [options]   Run equilibrium or strategic validation")
        (println "  game-theoretic-validation        Alias for validate-game-theory")
        (println "  list game-theory-checks          List available game-theory checks")
        (println "  explain game-theory              Explain equilibrium validation")
        (println "  doctor [options]                 Runtime health check")
        (println "  verify-portability [options]     Self-test for portable operation")
        (println)
        (println "Flags:")
        (println "  -l, --list          List available benchmarks")
        (println "  -o, --output PATH   Output path for evidence bundle")
        (println "      --scenario-output-dir DIR")
        (println "                       Write isolated per-execution evidence packages")
        (println "  -k, --key PATH      Path to private key")
        (println "  -v, --verify        Verify evidence bundle")
        (println "  -H, --hash-only     Compute hash of evidence bundle")
        (println "  -h, --help          Show this help")
        (System/exit 0))

      (:list options)
      (let [index (load-index)
            history (try (registry/get-history) (catch Exception _ []))
            latest-by-id (into {}
                               (map (fn [entry]
                                      [(get-in entry [:benchmark :benchmark/id]) entry]))
                               history)]
        (println (format "%-38s %-13s %-7s %-18s %s"
                         "ID" "Status" "Claims" "Latest scenarios" "Description"))
        (println (apply str (repeat 120 "-")))
        (doseq [b (:benchmarks index)]
          (let [latest (get latest-by-id (:benchmark/id b))
                total (get-in latest [:metrics :total])
                passed (get-in latest [:metrics :passed])
                latest-summary (if (and (number? total) (number? passed))
                                 (str passed "/" total)
                                 "none")
                status (name (or (:status b) :unknown))]
            (println (format "%-38s %-13s %-7s %-18s %s"
                             (:id b) status (:claims b) latest-summary (:description b)))))
        (System/exit 0))

      (:history options)
      (let [history (registry/get-history)]
        (println (format "%-30s %-20s %s" "Benchmark ID" "Date" "Outcome"))
        (println (apply str (repeat 70 "-")))
        (doseq [h history]
          (println (format "%-30s %-20s %s"
                           (get-in h [:benchmark :benchmark/id])
                           (java.util.Date. (:timestamp h))
                           (if (= (get-in h [:metrics :passed]) (get-in h [:metrics :total]))
                             "PASS" "FAIL"))))
        (System/exit 0))

      (:reproduce options)
      (System/exit (if (sharing/reproduce (:reproduce options)) 0 1))

      (:menu options)
      (let [bundle (rp/edn-read (:menu options))]
        (interactive-ux bundle (:menu options) options)
        (System/exit 0))

      (:share-summary options)
      (let [bundle (rp/edn-read (:share-summary options))]
        (println (sharing/share-summary bundle (:share-summary options)))
        (System/exit 0))

      (:export options)
      (do (sharing/export (:export options) (str (:export options) ".tar.gz"))
          (System/exit 0))

      (:publish-ipfs options)
      (do (sharing/publish-ipfs (:publish-ipfs options))
          (System/exit 0))

      (:export-dag options)
      (do ((requiring-resolve 'resolver-sim.benchmark.dag/export!) (:export-dag options))
          (System/exit 0))

      (:attest options)
      (if-let [key-path (:key options)]
        (let [att (sharing/attest (:attest options) key-path (:password options))
              path (str (:attest options) ".attestation.edn")]
          (spit path (pr-str att))
          (println "Attestation written to:" path)
          (System/exit 0))
        (do (println "Private key path (-k) required for attestation.")
            (System/exit 1)))

      (:verify-attestation options)
      (System/exit (if (sharing/verify-attestation (:verify-attestation options)) 0 1))

      (:verify options)
      (let [bundle-path (first arguments)
            bundle (rp/edn-read bundle-path)
            stored-hash (:evidence/hash bundle)
            {:keys [hash-ok? scheme]} (verify-bundle-hash bundle)]
        (println "Verification for:" (or bundle-path "(stdin)"))
        (println "Hash match:" (if hash-ok? "yes" "no"))
        (when (= :legacy-v1 scheme)
          (println "Hash scheme: legacy-v1 (run metadata and certification were not hash-covered)"))
        (when (:evidence/signature bundle)
          (if-let [pub-key-path (:evidence/public-key-path bundle)]
            (let [sig-ok? (signing/verify-signature stored-hash (:evidence/signature bundle) pub-key-path)]
              (println "Signature valid:" (if sig-ok? "yes" "no")))
            (println "Public key path missing in bundle, cannot verify signature.")))
        (System/exit (if hash-ok? 0 1)))

      (:hash-only options)
      (let [bundle-path (first arguments)
            bundle (rp/edn-read bundle-path)
            computed-hash (hc/hash-with-intent {:hash/intent :bundle-root}
                                               (hashable-evidence bundle))]
        (println computed-hash)
        (System/exit 0))

      ;; Default: run a benchmark (backward compat)
      :else
      (dispatch-run-and-report arguments options))))
