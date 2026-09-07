(ns resolver-sim.benchmark.outcome-policy)

(defn research-decisions
  "Project full requirement evaluations to the booleans policy may consume."
  [results]
  (mapv (fn [result]
          {:decision/source {:kind :research-requirement :id (:measure/id result)}
           :decision/value (:requirement/satisfied? result)})
        results))

(defn evaluate
  "Evaluate a validated all-of policy against normalized decision projections.
   Missing or ambiguous declared inputs are indeterminate, never false."
  [policy decisions]
  (let [by-source (group-by :decision/source decisions)
        selected (mapv (fn [input]
                         (case (:input/kind input)
                           :research-requirements
                           (mapv (fn [id]
                                   {:source {:kind :research-requirement :id id}
                                    :decisions (get by-source {:kind :research-requirement :id id})})
                                 (:measure-ids input))
                           :scenario-results
                           [{:source {:kind :scenario}
                             :decisions (filter #(= :scenario (get-in % [:decision/source :kind])) decisions)}]
                           :claim-results
                           [{:source {:kind :claim}
                             :decisions (filter #(= :claim (get-in % [:decision/source :kind])) decisions)}]))
                       (:policy/inputs policy))
        unresolved (filter #(not (and (seq (:decisions %))
                                      (every? (comp boolean? :decision/value) (:decisions %))))
                           (mapcat identity selected))]
    (if (seq unresolved)
      {:benchmark-outcome/status :indeterminate
       :benchmark-outcome/reason :benchmark-outcome/unresolved-input
       :benchmark-outcome/inputs (mapv :source unresolved)}
      (let [inputs (mapv :decisions (mapcat identity selected))]
        {:benchmark-outcome/status (if (every? true? (map :decision/value (mapcat identity inputs)))
                                     :passed
                                     :failed)
         :benchmark-outcome/inputs (vec (mapcat identity inputs))}))))
