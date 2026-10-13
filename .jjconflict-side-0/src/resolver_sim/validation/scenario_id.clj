(ns resolver-sim.validation.scenario-id
  "Scenario ID validation utilities.")

(def ^:private scenario-id-regex #"^[A-Za-z0-9][A-Za-z0-9_-]*$")

(defn validate-scenario-id!
  "Validates a scenario-id format. Throws exception if invalid."
  [scenario-id]
  (when-not (and (string? scenario-id)
                 (re-matches scenario-id-regex scenario-id))
    (throw (ex-info (format "Invalid scenario-id: '%s'. Must match regex %s"
                            scenario-id (str scenario-id-regex))
                    {:scenario-id scenario-id})))
  scenario-id)