(ns resolver-sim.commands.community
  "Community researcher commands for the PRF CLI.
   Wraps resolver-sim.community.cli functions into individual
   command handlers for the PRF command dispatch.
   Usage: clojure -M:cli community task list"
  (:require [clojure.tools.cli :as cli]
            [resolver-sim.community.cli :as ccli]))

(def community-cli-options
  [["-h" "--help" "Show help"]
   ["-t" "--task TASK-REF" "Task reference"]
   ["-r" "--runner RUNNER-ID" "Runner identity"]
   ["-k" "--key PATH" "Path to private signing key"]
   ["-o" "--original-attestation REF" "Original attestation reference for reproduction"]
   ["-d" "--dir DIR" "Artifact/mailbox directory"]
   ["-m" "--mailbox-dir DIR" "Mailbox directory"]
   ["-b" "--benchmark-id ID" "Benchmark ID"]
   ["-n" "--title TITLE" "Task title"]
   [nil "--description DESC" "Task description"]
   ["-s" "--suite-id ID" "Suite ID"]
   [nil "--claim-ids CLAIMS" "Comma-separated claim IDs"]
   [nil "--out DIR" "Output directory for graph export files"]
   [nil "--allow-dirty" "Allow dirty git working copy"]
   [nil "--benchmark-filter ID" "Filter tasks by benchmark ID"]
   [nil "--suite-filter ID" "Filter tasks by suite ID"]])

(defn- parse-and-call
  "Parse raw args with community options, then call f with parsed options.
   f is one of the resolver-sim.community.cli handler functions."
  [f raw-args]
  (let [{:keys [options errors summary]} (cli/parse-opts raw-args community-cli-options)]
    (cond
      errors
      (do (doseq [e errors] (println e))
          {:exit-code 2})
      (:help options)
      (do (println "Available community subcommand options:")
          (println summary)
          {:exit-code 0})
      :else
      (f options))))

(defn task-list
  [{:keys [cmd/raw-args]}]
  (parse-and-call ccli/list-tasks raw-args))

(defn task-show
  [{:keys [cmd/raw-args]}]
  (parse-and-call ccli/show-task raw-args))

(defn task-register
  [{:keys [cmd/raw-args]}]
  (parse-and-call ccli/register-task raw-args))

(defn task-run
  [{:keys [cmd/raw-args]}]
  (parse-and-call ccli/run-task raw-args))

(defn task-reproduce
  [{:keys [cmd/raw-args]}]
  (parse-and-call ccli/reproduce-task raw-args))

(defn task-verify
  [{:keys [cmd/raw-args]}]
  (parse-and-call ccli/verify-task raw-args))

(defn task-report
  [{:keys [cmd/raw-args]}]
  (parse-and-call ccli/generate-report raw-args))

(defn graph-export
  [{:keys [cmd/raw-args]}]
  (parse-and-call ccli/export-graph raw-args))

(defn mailbox-clear
  [{:keys [cmd/raw-args]}]
  (parse-and-call ccli/clear-mailbox raw-args))
