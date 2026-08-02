(ns resolver-sim.commands.registry-validate
  "Validate the PRF command registry against the dispatch table."
  (:require [resolver-sim.cli.registry :as reg]
            [resolver-sim.cli.dispatch :as dispatch]
            [clojure.string :as str]))

(defn validate
  "Validate command registry and dispatch table parity."
  [_]
  (let [errors (atom [])
        commands (reg/list-commands)
        native-cmds (filter #(= :native (:jar-avail %)) commands)
        available-cmds (filter #(dispatch/command-available? (:id %)) native-cmds)
        handlers (dispatch/get-command-handlers)]
    (doseq [cmd available-cmds]
      (when-not (get handlers (:id cmd))
        (swap! errors conj (str "No dispatch handler: " (:id cmd)))))
    (doseq [cmd-id (keys handlers)]
      (when-not (reg/get-command cmd-id)
        (swap! errors conj (str "Missing registry: " (name cmd-id)))))
    (let [{valid? :ok? validation-errors :errors} (reg/validate-registry)]
      (when-not valid?
        (swap! errors into validation-errors)))
    (let [{valid? :ok? validation-errors :errors} (reg/validate-paths)]
      (when-not valid?
        (swap! errors into validation-errors)))
    (doseq [cmd available-cmds]
      (when-not (dispatch/resolve-command (str/join " " (:path cmd)))
        (swap! errors conj (str "Not resolvable: " (:id cmd)))))
    (when-let [bb-result (reg/validate-bb-tasks)]
      (when-not (:ok? bb-result)
        (swap! errors into (:errors bb-result))))
    (if (empty? @errors)
      (do (println "Command registry valid.")
          {:exit-code 0 :message "Command registry valid." :errors []})
      (do (doseq [e @errors] (println (str "  X " e)))
          {:exit-code 1 :message (str (count @errors) " validation error(s)")
           :errors @errors}))))