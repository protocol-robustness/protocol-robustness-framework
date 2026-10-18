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

(declare get-command-handlers)

(def ^:private handler-symbols
  ;; Commands that are part of either supported runtime distribution resolve
  ;; individually. This prevents an optional command's dependencies (such as
  ;; the gRPC simulation server) from breaking an unrelated CLI invocation.
  ;; Note: :commands-validate is intentionally excluded to avoid circular
  ;; dependency with registry-validate namespace.
  {:protocol-finality-inspect 'resolver-sim.commands.protocol-finality/inspect
   :protocol-finality-verify 'resolver-sim.commands.protocol-finality/verify
   :forensic-run 'resolver-sim.commands.forensic/run
   :forensic-validate 'resolver-sim.commands.forensic/validate
   :forensic-verify 'resolver-sim.commands.forensic/verify
   :forensic-reproduce 'resolver-sim.commands.forensic/reproduce
   :protocol-pro-rata-derive-operation 'resolver-sim.commands.protocol-pro-rata/derive-operation
   :protocol-pro-rata-check-admission 'resolver-sim.commands.protocol-pro-rata/check-admission
   :protocol-pro-rata-verify-execution 'resolver-sim.commands.protocol-pro-rata/verify-execution
   :run-scenario 'resolver-sim.commands.scenario/run
   :run-invariants 'resolver-sim.commands.invariants/run
   :run-benchmark 'resolver-sim.commands.run-benchmark/run
   :parallel-benchmark-run 'resolver-sim.commands.run-benchmark/run
   :verify-scenario 'resolver-sim.commands.verify-scenario/run
   :verify-benchmark 'resolver-sim.commands.verify-benchmark/run
   :compare-runs 'resolver-sim.commands.compare-runs/run
   :compare 'resolver-sim.commands.compare/run
   :root-hash 'resolver-sim.commands.root-hash/run
   :result-root 'resolver-sim.commands.result-root/run
   :semantic-equivalent 'resolver-sim.commands.semantic-equivalent/run
   :declared-dependencies 'resolver-sim.commands.declared-dependencies/run
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
   :evidence-make 'resolver-sim.commands.evidence/make
   :evidence-verify-chain 'resolver-sim.commands.evidence/verify-chain
   :evidence-validate 'resolver-sim.commands.evidence/validate
   :evidence-coverage 'resolver-sim.commands.evidence/coverage
   :evidence-backstop 'resolver-sim.commands.evidence/run-backstop
   :validate 'resolver-sim.commands.validate/run
   :concepts-validate 'resolver-sim.commands.concepts/validate
   :check-ownership 'resolver-sim.commands.ownership/check
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
   :pre-application-checks 'resolver-sim.commands.pre-application-checks/checks
   :notebook-focus 'resolver-sim.commands.notebook-focus/focus
   :notebook-latest 'resolver-sim.commands.notebook-focus/latest
   :notebook-runs 'resolver-sim.commands.notebook-focus/runs
   :invariants-run 'resolver-sim.commands.invariants-run/run
   :verify-run 'resolver-sim.commands.verify-run/run
   :assure-package 'resolver-sim.commands.assure-package/run
   :ref-file 'resolver-sim.commands.ref-file/run
   :publication-trace 'resolver-sim.commands.publication/trace-run
   :sentinel-check 'resolver-sim.commands.sentinel/run
   :publish-check 'resolver-sim.commands.publish/run
   :resubmission-issue 'resolver-sim.commands.resubmission-issue/run
   :resubmission-issue-admission-v2 'resolver-sim.commands.resubmission-issue-admission-v2/run
   :allocation-build-context 'resolver-sim.commands.allocation/build-context
   :allocation-verify-proposal 'resolver-sim.commands.allocation/verify-proposal
   :allocation-vectors 'resolver-sim.commands.allocation/vectors
   :allocation-issue-certificate 'resolver-sim.commands.allocation/issue-certificate
   :research-create 'resolver-sim.commands.research/create
   :research-assign 'resolver-sim.commands.research/assign
   :research-run 'resolver-sim.commands.research/run
   :research-submit 'resolver-sim.commands.research/submit
   :research-verify 'resolver-sim.commands.research/verify
   :research-reproduce 'resolver-sim.commands.research/reproduce
   :research-diff 'resolver-sim.commands.research/diff
   :research-inspect 'resolver-sim.commands.research/inspect
   :researcher-disagree 'resolver-sim.commands.researcher/disagree
   :researcher-approve 'resolver-sim.commands.researcher/approve
   :researcher-check 'resolver-sim.commands.researcher/check
   :research-reward-check 'resolver-sim.commands.research/reward-check
   :research-reward-propose 'resolver-sim.commands.research/reward-propose
   :research-reward-approve 'resolver-sim.commands.research/reward-approve
   :check-aggregate 'resolver-sim.commands.check-aggregate/run})

(def ^:private sew-command-ids
  #{:protocol-finality-inspect :protocol-finality-verify
    :benchmark-validate :benchmark-validate-jar :benchmark-smoke
    :run-scenario :run-invariants :run-benchmark :parallel-benchmark-run :run-simulation
    :scenario-list :scenario-compare :scenario-pick :scenario-run-search
    :benchmark-list :suite-list :invariants-run :verify-run :assure-package})

(defn sew-capable?
  "True when this distribution contains the Sew protocol implementation."
  []
  (boolean (io/resource "resolver_sim/protocols/sew.clj")))

(defn command-available? [cmd-id]
  (or (not (sew-command-ids cmd-id)) (sew-capable?)))

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

(def ^:private dispatcher-common-options
  [["-h" "--help" "Show command help"]
   ["-j" "--json" "Output results as JSON"]
   [nil "--explain" "Print a stack trace for unexpected command errors"]])

(defn- registry-cli-option
  [{:keys [name short type doc default] :as option}]
  (let [takes-value? (not= :flag type)
        value-name (case type :integer "N" :string "VALUE" "VALUE")
        long-name (str name (when takes-value? (str " " value-name)))
        base [(or short nil) long-name (or doc "")]
        with-parser (if (= :integer type)
                      (conj base :parse-fn #(Long/parseLong %))
                      base)]
    (if (contains? option :default)
      (conj with-parser :default default)
      with-parser)))

(defn- command-cli-options
  [cmd]
  (let [common-names #{"--help" "--json" "--explain"}
        command-options (remove #(common-names (:name %)) (:command/options cmd))]
    (into dispatcher-common-options (map registry-cli-option command-options))))

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
        cmds (sort-by (comp #(str/join " " %) :path) visible)
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

(defn- print-command-help [cmd]
  (let [{:keys [min max mode]} (:command/positional-args cmd)
        positional (cond
                     (= mode :arbitrary) " [ARGS...]"
                     (pos? (or min 0)) (str " " (str/join " " (repeat min "ARG"))
                                             (when (and max (> max min))
                                               (str " [ARG" (when (> max (inc min)) "...") "]")))
                     :else "")]
    (println (str "Usage: bin/prf " (str/join " " (:command/path cmd)) positional " [OPTIONS]")))
  (println (:command/description cmd))
  (doseq [{:keys [name short doc required]} (:command/options cmd)]
    (println (str "  " (when short (str short ", ")) name
                  (when required " (required)") "  " doc)))
  0)

(def ^:private path-option-names
  #{"--artifact-dir" "--run-root" "--suite-root"
    "--output-dir" "--scenario-output-dir" "--save-output" "--claim-registry"
    "--out" "--output" "--package-a" "--package-b" "--key" "--check"
    "--trust-context" "--input" "--cas-dir" "--params" "--output-file"
    "--baseline" "--candidate" "--dir" "--mailbox-dir"})

(defn- caller-directory []
  (or (System/getenv "PRF_CLI_CALLER_DIR") (System/getProperty "user.dir")))

(defn- caller-path [path]
  (when (and (string? path) (seq path) (not= "-" path))
    (let [expanded (if (str/starts-with? path "~/")
                     (str (System/getProperty "user.home") (subs path 1)) path)
          file (io/file expanded)]
      (.getCanonicalPath (if (.isAbsolute file) file (io/file (caller-directory) expanded))))))

(defn- normalize-raw-path-args [args extra-path-options]
  (loop [remaining (seq args) result []]
    (if-let [arg (first remaining)]
      (let [[option inline] (str/split arg #"=" 2)]
        (if (or (contains? path-option-names option)
                (contains? extra-path-options option))
          (if inline
            (recur (next remaining)
                   (conj result (str option "=" (or (caller-path inline) inline))))
            (if-let [value (second remaining)]
              (recur (nnext remaining) (conj result option (or (caller-path value) value)))
              (recur (next remaining) (conj result arg))))
          (recur (next remaining) (conj result arg))))
      result)))

(defn- normalize-user-paths [opts args]
  (let [specified (set (filter path-option-names
                               (map #(first (str/split % #"=" 2)) args)))
        option-keys (into {} (map (fn [name] [(keyword (subs name 2)) name]) specified))
        opts (reduce-kv (fn [m k v]
                          (if-let [name (get option-keys k)]
                            (assoc m k (or (caller-path v) v)) m))
                        opts opts)]
    (if (= :compare (:command/id (registry/get-command
                                  (get (registry/path->command-id-map)
                                       (:cmd/path opts)))))
      (update opts :cmd/args #(mapv (fn [p] (or (caller-path p) p)) %))
      opts)))

(defn- required-option-error [cmd args]
  (let [supplied (set (keep (fn [arg]
                              (when (str/starts-with? arg "--")
                                (first (str/split arg #"=" 2)))) args))
        missing (sort (keep (fn [{:keys [name required]}]
                              (when (and required (not (contains? supplied name))) name))
                            (:command/options cmd)))]
    (when (seq missing)
      (str "Missing required option(s): " (str/join ", " missing)))))

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
    (let [opts (normalize-user-paths (assoc opts
                                           :cmd/path cmd-path
                                           :cmd/args cmd-args
                                           :cmd/raw-args raw-args)
                                    raw-args)
          result (handler-var opts)]
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
  (let [raw-command (when-let [[path cmd-args] (command-path args)]
                      (when-let [cmd-id (get (registry/path->command-id-map) path)]
                        [path cmd-args (registry/get-command cmd-id)]))
        cmd (nth raw-command 2 nil)
        raw-cmd-id (:command/id cmd)]
    (cond
      (empty? args)
      (do (println "Usage: java -jar prf.jar <command> [options]")
          (println "Run 'java -jar prf.jar help' for available commands.")
          2)

      (= "help" (first args))
      (:exit-code (print-help))

      (#{"--help" "-h"} (first args))
      (do (print-help) 0)

      (nil? raw-command)
      (do (println "Unknown command:" (str/join " " args))
          (println "Run 'java -jar prf.jar help' for available commands.")
          2)

      (= :handler (:command/options-source cmd))
      (if-let [handler-var (handler-for raw-cmd-id)]
        (let [extra-path-options (case raw-cmd-id
                                   :run-simulation #{"--scenario" "-p" "-o"}
                                   (#{:community-task-list :community-task-show
                                      :community-task-register :community-task-run
                                      :community-task-reproduce :community-task-verify
                                      :community-task-report :community-graph-export
                                      :community-mailbox-clear} raw-cmd-id)
                                   (cond-> #{"-d" "-m"}
                                     (#{:community-task-run :community-task-reproduce}
                                      raw-cmd-id) (conj "-k"))
                                   #{})
              cmd-args (normalize-raw-path-args (second raw-command) extra-path-options)]
          (run-command handler-var {} (first raw-command) cmd-args cmd-args))
        (do (println "Unknown command:" (first raw-command))
            (println "Run 'java -jar prf.jar help' for available commands.")
            2))

      :else
      (let [[cmd-path raw-args _] raw-command
            {:keys [options arguments summary errors]}
            (cli/parse-opts raw-args (command-cli-options cmd))
            options (cond-> options (:json options) (assoc :json? true))]
        (cond
          errors
          (do (doseq [e errors]
                (binding [*out* *err*]
                  (println (str/replace e "Unknown option: " "Unsupported option: "))))
              (binding [*out* *err*] (println) (println summary))
              2)

          (:help options)
          (print-command-help cmd)

          :else
          (if-let [argument-error (registry/positional-args-error cmd arguments)]
            (do (println argument-error) 2)
            (if-let [option-error (required-option-error cmd raw-args)]
              (do (binding [*out* *err*] (println option-error)) 2)
              (if-let [handler-var (handler-for raw-cmd-id)]
                (run-command handler-var options cmd-path arguments raw-args)
                (do (println "Unknown command:" cmd-path)
                    (println "Run 'java -jar prf.jar help' for available commands.")
                    2)))))))))
