(ns resolver-sim.cli.dispatch
  "Command dispatch for the PRF CLI.
   Maps :command/id from the registry to handler functions.
   Every registered :native command must have a handler here.
   Every handler must have a registry entry (validated by commands:validate)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.cli :as cli]
            [resolver-sim.cli.registry :as registry]))

;; ---------------------------------------------------------------------------
;; Command handler registry
;; Every :command/id with :jar-availability :native must have an entry here.
;; Use resolve at runtime to avoid circular load dependencies.
;; ---------------------------------------------------------------------------

(def ^:private handler-cache (atom {}))

(declare get-command-handlers)

(def ^:private handler-symbols
  ;; Commands that are part of either supported runtime distribution resolve
  ;; individually. This prevents an optional command's dependencies (such as
  ;; the gRPC simulation server) from breaking an unrelated CLI invocation.
  ;; Note: :commands-validate is intentionally excluded to avoid circular
  ;; dependency with registry-validate namespace.
  {:run-scenario 'resolver-sim.commands.scenario/run
   :run-invariants 'resolver-sim.commands.invariants/run
   :run-benchmark 'resolver-sim.commands.run-benchmark/run
   :verify-scenario 'resolver-sim.commands.verify-scenario/run
   :verify-benchmark 'resolver-sim.commands.verify-benchmark/run
   :compare-runs 'resolver-sim.commands.compare-runs/run
   :scenario-list 'resolver-sim.commands.scenario-list/list-scenarios
   :scenario-compare 'resolver-sim.commands.scenario-compare/compare-scenarios
   :scenario-pick 'resolver-sim.commands.scenario-pick/pick-scenarios
   :scenario-run-search 'resolver-sim.commands.scenario-run-search/run
   :benchmark-list 'resolver-sim.commands.benchmark-list/list-benchmarks
   :benchmark-validate 'resolver-sim.commands.benchmark/validate
   :benchmark-validate-jar 'resolver-sim.commands.benchmark-validate-jar/validate-jar
   :benchmark-smoke 'resolver-sim.commands.benchmark-smoke/smoke
   :suite-list 'resolver-sim.commands.suite-list/list-suites
   :backstop 'resolver-sim.commands.backstop/run-default
   :backstop-fast 'resolver-sim.commands.backstop/run-fast
   :evidence-verify-chain 'resolver-sim.commands.evidence/verify-chain
   :evidence-validate 'resolver-sim.commands.evidence/validate
   :evidence-coverage 'resolver-sim.commands.evidence/coverage
   :evidence-backstop 'resolver-sim.commands.evidence/run-backstop
   :validate 'resolver-sim.commands.validate/run
   :concepts-validate 'resolver-sim.commands.concepts/validate
   :fmt-check 'resolver-sim.commands.validate/fmt-check
   :lint 'resolver-sim.commands.validate/lint
   :run-simulation 'resolver-sim.commands.run-simulation/run
   :community-task-list 'resolver-sim.commands.community/task-list
   :community-task-show 'resolver-sim.commands.community/task-show
   :community-task-register 'resolver-sim.commands.community/task-register
   :community-task-run 'resolver-sim.commands.community/task-run
   :community-task-reproduce 'resolver-sim.commands.community/task-reproduce
   :community-task-verify 'resolver-sim.commands.community/task-verify
   :community-task-report 'resolver-sim.commands.community/task-report
   :community-graph-export 'resolver-sim.commands.community/graph-export
   :community-mailbox-clear 'resolver-sim.commands.community/mailbox-clear
   :notebook-focus 'resolver-sim.commands.notebook-focus/focus
   :notebook-latest 'resolver-sim.commands.notebook-focus/latest
   :notebook-runs 'resolver-sim.commands.notebook-focus/runs})

(def ^:private sew-command-ids
  #{:benchmark-validate :benchmark-validate-jar :benchmark-smoke
    :run-scenario :run-invariants :run-benchmark :run-simulation
    :scenario-list :scenario-compare :scenario-pick :scenario-run-search
    :benchmark-list :suite-list})

(defn sew-capable?
  "True when this distribution contains the Sew protocol implementation."
  []
  (boolean (io/resource "resolver_sim/protocols/sew.clj")))

(defn command-available? [cmd-id]
  (or (not (sew-command-ids cmd-id)) (sew-capable?)))

(defn- load-handler-if-available [cmd-id]
  "Try to load a handler for the given command ID.
   Returns the handler var if successful, nil otherwise."
  (when-let [symbol (get handler-symbols cmd-id)]
    (try
      (requiring-resolve symbol)
      (catch Exception _ nil))))

(defn- handler-for [cmd-id]
  (when (command-available? cmd-id)
    (if-let [symbol (get handler-symbols cmd-id)]
      (try
        (requiring-resolve symbol)
        (catch Exception _ nil))
      ;; Handle :commands-validate specially to avoid circular dependency
      (when (= cmd-id :commands-validate)
        (try
          (requiring-resolve 'resolver-sim.commands.registry-validate/validate)
          (catch Exception _ nil))))))

(defn get-command-handlers
  "Return a map of available command handlers.
   Only loads handlers that can be resolved without errors.
   Commands requiring unavailable dependencies return nil."
  []
  (let [result (atom {})]
    (doseq [[cmd-id symbol] handler-symbols]
      (when (command-available? cmd-id)
        (try
          (let [handler (requiring-resolve symbol)]
            (swap! result assoc cmd-id handler))
          (catch Exception _ nil))))
    ;; Special case for :commands-validate (circular dependency with dispatch)
    (when (command-available? :commands-validate)
      (try
        (let [handler (requiring-resolve 'resolver-sim.commands.registry-validate/validate)]
          (swap! result assoc :commands-validate handler))
        (catch Exception _ nil)))
    @result))

;; ---------------------------------------------------------------------------
;; CLI option definitions
;; ---------------------------------------------------------------------------

(def cli-options
  [["-h" "--help" "Show help"]
   ["-j" "--json" "Output results as JSON"]
   [nil "--artifact-dir DIR" "Legacy evidence artifact directory (must be explicit)"]
   [nil "--scenario ID" "Scenario ID to run"]
   [nil "--scenario-file PATH" "Scenario file path"]
   [nil "--run-root DIR" "Authoritative root directory for a complete scenario bundle"]
   [nil "--suite-root DIR" "Suite root directory (directory containing manifest.edn)"]
   [nil "--output-dir DIR" "Deprecated scenario alias for --run-root"]
   [nil "--scenario-output-dir DIR" "Deprecated scenario alias for --run-root"]
   [nil "--save-output DIR" "Legacy scenario output-copy option"]
   [nil "--sensitivity-profile PROFILE" "Bundle sensitivity profile: public or internal"
    :parse-fn keyword]
   [nil "--report-format FORMAT" "Scenario report format"]
   ["-v" "--verbose" "Scenario report format: verbose"]
   ["-f" "--failures" "Scenario report format: failures"]
   ["-s" "--summary" "Scenario report format: summary"]
   ["-a" "--audit" "Scenario report format: audit"]
   [nil "--suite NAME" "Suite name"]
   [nil "--pack NAME" "Benchmark pack name"]
   [nil "--fast" "Run fast tier only"]
   [nil "--full" "Run full tier"]
   [nil "--strict" "Strict validation mode"]
   [nil "--explain" "Explain results in detail"]
   [nil "--out DIR" "Output directory"
    :default "target/report"]
   [nil "--output PATH" "Output path for evidence bundle"]
   [nil "--package-a DIR" "First completed canonical package"]
   [nil "--package-b DIR" "Second completed canonical package"]
   [nil "--protocol PROTOCOL" "Protocol ID (default sew-v1)"]
   [nil "--search TEXT" "Filter results by search term"]
   [nil "--key PATH" "Path to private key"]])

;; ---------------------------------------------------------------------------
;; Command path resolution
;; ---------------------------------------------------------------------------

(defn- command-path
  "Resolve the longest registered command-path prefix and preserve all
   remaining positional tokens for the command handler."
  [args]
  (let [paths (keys (registry/path->command-id-map))
        matches (filter (fn [path]
                          (let [tokens (str/split path #" ")]
                            (and (<= (count tokens) (count args))
                                 (= tokens (vec (take (count tokens) args))))))
                        paths)
        path (last (sort-by #(count (str/split % #" ")) matches))]
    (when path
      [path (vec (drop (count (str/split path #" ")) args))])))

(defn resolve-command
  "Turn a path like 'evidence verify-chain' into [command-id positional-args].
   Uses the command registry as the authoritative source for path-to-command
   mapping. Any registered command is automatically routable.
   Returns nil if no command matches."
  [path-str]
  (when-let [cmd-id (get (registry/path->command-id-map) path-str)]
    [cmd-id []]))

;; ---------------------------------------------------------------------------
;; Help
;; ---------------------------------------------------------------------------

(defn print-help
  "Print available commands and their descriptions."
  []
  (println "PRF CLI — Protocol Robustness Framework")
  (println)
  (println "Usage: java -jar prf.jar <command> [options]")
  (println)

  (let [visible (filter (fn [c] (and (command-available? (:id c))
                                     (or (nil? (:surface c)) (= :prf (:surface c)))))
                        (registry/list-commands))
        cmds    (sort-by (comp #(str/join " " %) :path) visible)
        primary #{:benchmark :scenario}]

    (println "Benchmark & Scenario Commands:")
    (doseq [{:keys [path description]} (filter #(primary (:category %)) cmds)]
      (printf "  %-30s %s\n" (str/join " " path) description))
    (println)

    (println "Development & Validation Commands:")
    (doseq [{:keys [path description]} (filter #(not (primary (:category %))) cmds)]
      (printf "  %-30s %s\n" (str/join " " path) description))
    (println))

  (println "Use: java -jar prf.jar <command> --help for command-specific help.")
  {:exit-code 0, :message "help"})

;; ---------------------------------------------------------------------------
;; Command execution (for backstop orchestration)
;; ---------------------------------------------------------------------------

(defn dispatch-command
  "Look up and execute a command handler by :command/id keyword.
   Returns {:exit-code N :message str}.
   Used by backstop and commands:validate to run commands without
   directly depending on the handler map at compile time."
  [cmd-id opts]
  (if-let [handler-var (handler-for cmd-id)]
    (try
      (let [result ((deref handler-var) opts)]
        (or (when (map? result) result)
            {:exit-code (or result 0) :message "ok"}))
      (catch Exception e
        {:exit-code 1 :message (.getMessage e)}))
    {:exit-code 2 :message (str "No handler for command: " (name cmd-id))}))

;; ---------------------------------------------------------------------------
;; Run dispatch
;; ---------------------------------------------------------------------------

(defn- run-command
  "Execute a resolved command handler. Returns exit code.
   Passes :cmd/raw-args (unparsed tokens after command path)
   so handlers can do their own option parsing if needed."
  [handler-var opts cmd-path cmd-args raw-args]
  (try
    (let [result (handler-var (assoc opts
                                     :cmd/path cmd-path
                                     :cmd/args cmd-args
                                     :cmd/raw-args raw-args))]
      (or (:exit-code result) 0))
    (catch Exception e
      (println "Error executing command:" (.getMessage e))
      (when (:explain opts)
        (.printStackTrace e))
      4)))

(defn run
  "Parse args, resolve command, and execute handler.
   Returns an exit code for System/exit."
  [args]
  (let [parsed (cli/parse-opts args cli-options)
        {:keys [options arguments summary errors]} parsed
        options (cond-> options
                  (:json options) (assoc :json? true))]
    (cond
      errors
      (do (doseq [e errors] (println e))
          (println)
          (println summary)
          2)

      (:help options)
      0

      (empty? arguments)
      (do (println "Usage: java -jar prf.jar <command> [options]")
          (println "Run 'java -jar prf.jar help' for available commands.")
          2)

      (= "help" (first arguments))
      (let [h (print-help)]
        (:exit-code h))

      :else
      (if-let [[cmd-path cmd-args] (command-path arguments)]
        (if-let [[cmd-id _] (resolve-command cmd-path)]
          (if-let [handler-var (handler-for cmd-id)]
            (run-command handler-var options cmd-path cmd-args args)
            (do (println "Unknown command:" cmd-path)
                (println "Run 'java -jar prf.jar help' for available commands.")
                2))
          (do (println "Unknown command:" cmd-path)
              (println "Run 'java -jar prf.jar help' for available commands.")
              2))
        (do (println "Unknown command:" (str/join " " arguments))
            (println "Run 'java -jar prf.jar help' for available commands.")
            2)))))
