(ns resolver-sim.commands.concepts
  "Validate concept definitions and protocol mapping sets."
  (:require [resolver-sim.concepts.registry :as concepts-registry]))

(defn validate
  "Load and validate the canonical concept registry and its registered mappings."
  [_opts]
  (try
    (let [{:keys [concepts mapping-sets]} (concepts-registry/load-registry)
          missing (vec (concepts-registry/missing-related-concepts concepts))
          errors (mapv (fn [{:keys [from to]}]
                         (str "Concept " from " references missing concept " to))
                       missing)]
      (println "  Concepts:" (count concepts))
      (println "  Protocol mapping sets:" (count mapping-sets))
      (doseq [error errors]
        (println "  ✗" error))
      (println "  " (count errors) "errors")
      {:exit-code (if (seq errors) 1 0)
       :message (if (seq errors) "Concept validation failed" "Concept validation passed")
       :errors errors
       :warnings []})
    (catch Exception e
      (let [message (str "Concept validation failed: " (.getMessage e))]
        (println "  ✗" message)
        {:exit-code 1 :message message :errors [message] :warnings []}))))
