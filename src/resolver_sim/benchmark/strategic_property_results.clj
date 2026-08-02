(ns resolver-sim.benchmark.strategic-property-results
  "Adapter between raw strategic-property verdicts and the structured result
   vocabulary.

   `strategic_partial_fill/validate-strategic-properties` returns a
   self-contained artifact of {:property kw :status :verified|:violated ...}
   entries. This namespace converts those entries into:

     - `equilibrium-result` structured results, where a violation is reported
       with :reason :property-violated; and
     - gate-compatible deviation results consumed by
       `validation.gate/evaluate-strategic-gate`."
  (:require [resolver-sim.scenario.equilibrium-result :as eq-result]))

(def property-classes
  "Property keyword -> validation class.  The current strategic properties all
   assert bounded deviation-resistance over pro-rata partial-fill allocation."
  {:strategy/split-invariance          :validation.class/deviation-resistance
   :strategy/permutation-invariance    :validation.class/deviation-resistance
   :strategy/sybil-invariance          :validation.class/deviation-resistance
   :strategy/request-monotonicity      :validation.class/deviation-resistance
   :allocation/exact-merge-invariance  :validation.class/deviation-resistance})

(defn- validation-class-for
  [property]
  (get property-classes property :validation.class/deviation-resistance))

(defn- entry-status
  "Normalize a property entry's status/verdict to :verified or :violated."
  [entry]
  (let [status (:status entry) verdict (:verdict entry)]
    (cond
      (#{:verified :pass} status) :verified
      (#{:violated :fail} status) :violated
      (#{:verified :pass} verdict) :verified
      (#{:violated :fail} verdict) :violated
      :else :inconclusive)))

(defn- offending-evidence
  "Collect counterexample material for a violated property entry."
  [entry]
  (let [counterexample (:counterexample entry)
        samples (or (:sample-counterexamples entry) [])
        state (:state entry)]
    (vec (concat
          (when counterexample [counterexample])
          (take 3 samples)
          (when state [state])))))

(defn strategic-properties->results
  "Convert a strategic-properties artifact
   {:properties [...] :summary {...}} into a vector of structured
   `equilibrium-result` result maps.

   Verified entries become pass-results; violated entries become fail-results
   with :reason :property-violated."
  [artifact]
  (mapv (fn [entry]
          (let [property (:property entry)
                class (validation-class-for property)
                state-count (long (or (:state-count entry)
                                      (get-in artifact [:summary :states-examined] 0)))
                basis (or (:basis entry) :strategic-partial-fill-enumeration)
                observed {:state-count state-count
                          :violation-count (long (:violation-count entry 0))}
                expected {:state-count state-count :violation-count 0}]
            (case (entry-status entry)
              :verified
              (eq-result/pass-result property basis observed expected
                                     :validation-class class)

              :violated
              (eq-result/fail-result property basis observed expected
                                     (offending-evidence entry)
                                     :validation-class class)

              :inconclusive
              (eq-result/inconclusive-result property basis :inconclusive-strategic-property
                                             :detail entry
                                             :validation-class class))))
        (:properties artifact)))

(defn strategic-properties->deviation-results
  "Convert a strategic-properties artifact into the deviation-results shape
   consumed by `validation.gate/evaluate-strategic-gate`:
   {:property kw :verdict :verified|:violated}."
  [artifact]
  (mapv (fn [entry]
          {:property (:property entry)
           :verdict (case (entry-status entry)
                      :verified :verified
                      :violated :violated
                      :inconclusive)})
        (:properties artifact)))
