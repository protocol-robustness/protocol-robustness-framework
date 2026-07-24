(ns resolver-sim.commands.backstop
  "Backstop orchestration: runs registered review-gate commands in-process.
   Port of scripts/backstop.clj — no ProcessBuilder, no bb shelling out."
  (:require [clojure.string :as str]
            [resolver-sim.cli.registry :as reg]
            [resolver-sim.cli.dispatch :as dispatch]))

(def tier-rank {:fast 0 :default 1 :full 2 :manual 3 :deprecated 4})

(defn- include-command?
  "True when a command should run for the given target-tier.
   Keys match output of reg/list-commands: :tier, :id, :jar-avail.
   Also checks if command is available in current distribution."
  [target-tier {:keys [tier jar-avail status] :or {status :active}} cmd-id]
  (and (= :active status)
       (not= :external jar-avail)
       (dispatch/command-available? cmd-id)
       (<= (tier-rank (or tier :manual))
           (tier-rank target-tier))
       (contains? #{:fast :default :full} tier)))

(defn- dispatch-command
  "Resolve and call dispatch-command at runtime to avoid circular load."
  [cmd-id opts]
  (let [fn-var (requiring-resolve 'resolver-sim.cli.dispatch/dispatch-command)]
    (fn-var cmd-id opts)))

(defn- run-handler
  "Execute a single command's handler given opts."
  [cmd-id opts]
  (println (str "▶ " (name cmd-id)))
  (flush)
  (let [result (dispatch-command cmd-id opts)]
    (when-not (zero? (:exit-code result 0))
      (println (str "  " (if (= 2 (:exit-code result)) "SKIPPED" "FAILED")
                    ": " (:message result))))
    result))

(defn run-tier
  "Run backstop for a specific tier.
   opts — {:keys [tier include-external? json? explain?]}"
  [{:keys [tier] :as opts}]
  (println (str "▶ backstop " (name (or tier :default))))
  (let [commands (filter #(include-command? (or tier :default) % (:id %)) (reg/list-commands))
        results (mapv (fn [cmd]
                        (assoc (run-handler (:id cmd) opts)
                               :command-id (:id cmd)))
                      commands)]
    (doseq [r results]
      (when-not (zero? (:exit-code r 0))
        (println (str "✗ " (name (:command-id r)) ": FAILED"))))
    (let [failures (filter #(not (zero? (:exit-code % 0))) results)]
      (if (empty? failures)
        (do (println (str "BACKSTOP " (str/upper-case (name (or tier :default))) " PASSED"))
            {:exit-code 0 :message "backstop passed" :results results})
        (do (println (str "BACKSTOP " (str/upper-case (name (or tier :default))) " FAILED - "
                            (count failures) " failure(s)"))
            {:exit-code 1 :message (str (count failures) " failure(s)")
             :results results :failures failures})))))

(defn run-default
  "Run the default review gate (all :default tier commands)."
  [opts]
  (run-tier (assoc opts :tier :default)))

(defn run-fast
  "Run the fast edit-loop review gate (all :fast tier commands)."
  [opts]
  (run-tier (assoc opts :tier :fast)))
