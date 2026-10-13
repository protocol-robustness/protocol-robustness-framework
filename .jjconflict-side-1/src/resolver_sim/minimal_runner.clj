(ns resolver-sim.minimal-runner
  "Standalone CLI entry point.  Loads the scenario runner lazily so
   --help and --self-test work without triggering Sew protocol or DB init.
   Usage:
     java -jar prf-runner-sew.jar -m resolver-sim.minimal-runner --help
     java -jar prf-runner-sew.jar -m resolver-sim.minimal-runner \\
       --fixtures ./fixtures --scenario scenario.trace.json
     java -jar prf-runner-sew.jar -m resolver-sim.minimal-runner \\
       --suite :dispute-resolution-scenarios --self-test"
  (:require [clojure.java.io :as io]
            [clojure.tools.cli :as cli])
  (:gen-class))

(def cli-options
  [["-s" "--suite SUITE" "Suite keyword (e.g. :sew-invariants)"
    :parse-fn keyword]
   ["-f" "--fixture-suite SUITE" "Fixture suite keyword"
    :parse-fn keyword]
   ["-c" "--scenario PATH" "Single scenario file path"]
   ["-d" "--fixtures DIR" "Fixture directory (resolves relative scenario paths)"]
   ["-o" "--output FILE" "Output file for JSON results"]
   ["-p" "--protocol PROTOCOL" "Protocol ID (default sew-v1)"
    :default "sew-v1"]
   ["-t" "--self-test" "Run scenario/suite twice and compare bundle roots for determinism"]
   ["-h" "--help"]])

(defn- print-help [summary]
  (println "PRF Minimal Scenario Runner — Sew protocol replay")
  (println)
  (println "Usage:")
  (println "  java -jar prf-runner-sew.jar -m resolver-sim.minimal-runner [options]")
  (println)
  (println summary)
  (println)
  (println "Examples:")
  (println "  Replay a single scenario:")
  (println "    --scenario s-auto-cancel-time-via-keeper.trace.json")
  (println)
  (println "  With fixture directory:")
  (println "    --fixtures ./data/fixtures/traces --scenario s-auto-cancel-time-via-keeper.trace.json")
  (println)
  (println "  Suite replay:")
  (println "    --suite :dispute-resolution-scenarios")
  (println)
  (println "  Self-test (determinism check):")
  (println "    --suite :dispute-resolution-scenarios --self-test")
  0)

(defn- resolve-scenario-path
  "Prepend fixtures dir to scenario path if --fixtures is given
   and the scenario path is relative (doesn't start with /)."
  [fixtures-dir scenario-path]
  (if (and fixtures-dir scenario-path
           (not (.startsWith scenario-path "/"))
           (not (.startsWith scenario-path ".")))
    (str (io/file fixtures-dir scenario-path))
    scenario-path))

(defn- run-scenario!
  "Load the scenario runner lazily and execute."
  [options]
  (let [runner (requiring-resolve 'resolver-sim.io.scenario-runner/run-and-report)
        fixtures (:fixtures options)
        scenario (resolve-scenario-path fixtures (:scenario options))
        scenario-path (or scenario (:scenario options))]
    (when scenario-path
      (let [f (java.io.File. scenario-path)]
        (when-not (.exists f)
          (.println System/err (str "ERROR: Scenario file not found: " scenario-path))
          (when fixtures
            (.println System/err (str "  (resolved via --fixtures " fixtures ")")))
          (.println System/err "  Provide a valid path or use --suite for built-in suites.")
          (System/exit 1))))
    (let [result (runner {:protocol (:protocol options)
                          :suite (:suite options)
                          :fixture-suite (:fixture-suite options)
                          :scenario scenario-path
                          :output-file (:output options)}
                         {:report-format :summary})]
      (System/exit (:exit-code result)))))

(defn- runner-opts
  "Build the runner options map from CLI options."
  [options]
  (let [fixtures (:fixtures options)
        scenario (resolve-scenario-path fixtures (:scenario options))
        scenario-path (or scenario (:scenario options))]
    {:protocol (:protocol options)
     :suite (:suite options)
     :fixture-suite (:fixture-suite options)
     :scenario scenario-path
     :output-file (:output options)}))

(defn- self-test!
  "Run the same scenario/suite twice, compare bundle roots, and report determinism.
   Exits 0 if deterministic, 1 if non-deterministic."
  [options]
  (when (and (:scenario options) (not (.exists (java.io.File. (:scenario options))))
             (:fixtures options))
    (let [resolved (resolve-scenario-path (:fixtures options) (:scenario options))]
      (when (and resolved (not (.exists (java.io.File. resolved))))
        (.println System/err (str "ERROR: Scenario file not found: " resolved))
        (System/exit 1))))
  (let [runner (requiring-resolve 'resolver-sim.io.scenario-runner/run-and-report)
        opts (runner-opts options)
        run-fn (fn [] (try
                        (runner opts {:report-format :summary})
                        (catch Exception e
                          (let [msg (.getMessage e)]
                            (.println System/err (str "ERROR: " msg))
                            (when (and (= "No matching clause: " msg)
                                       (not (:scenario options)))
                              (.println System/err "  This may be caused by an invalid or unregistered suite key.")
                              (.println System/err "  Use --fixture-suite for fixture-based suites (e.g. :all-invariants).")
                              (.println System/err "  Use --suite only for registered path-list suites."))
                            (System/exit 1)))))
        _ (println "Running pass 1...")
        result-1 (run-fn)
        _ (println "Running pass 2...")
        result-2 (run-fn)
        bundle-1 (:bundle-root result-1)
        bundle-2 (:bundle-root result-2)]
    (if (and bundle-1 bundle-2)
      (let [eq? (= bundle-1 bundle-2)]
        (if eq?
          (do (println "PASS: Deterministic replay confirmed — bundle roots are identical across two runs")
              (System/exit 0))
          (do (println "FAIL: Non-deterministic replay detected — bundle roots differ")
              (doseq [k (sort (keys (merge bundle-1 bundle-2)))]
                (when (not= (get bundle-1 k) (get bundle-2 k))
                  (println (str "  " k " differs:"))
                  (println (str "    run 1: " (pr-str (get bundle-1 k))))
                  (println (str "    run 2: " (pr-str (get bundle-2 k))))))
              (System/exit 1))))
      (do (.println System/err "ERROR: run-and-report did not return bundle roots")
          (System/exit 1)))))

(defn -main [& args]
  (let [{:keys [options errors summary]} (cli/parse-opts args cli-options)]
    (cond
      errors (do (run! println errors) (System/exit 1))
      (:help options) (System/exit (print-help summary))
      (:self-test options) (self-test! options)
      :else (run-scenario! options))))
