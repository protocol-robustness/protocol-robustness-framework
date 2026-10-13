(ns resolver-sim.benchmark.incentive-target-qualification
  "Generic, non-economic qualification of an incentive target.

  This layer consumes target roots and evidence lineage. It does not establish
  payment, alter analysis validity, or modify a reward policy."
  (:require [resolver-sim.benchmark.proof-of-possession :as possession]))

(def ^:const schema "incentive-target-qualification.v1")
(def possession-scopes #{:target :contributor})

(defn qualify
  "Evaluate possession conditions for an incentive target.

  `:target` evaluates every lineage member. `:contributor` evaluates only the
  member whose `:researcher/id` equals `contributor`. Unspecified and requested
  requirements do not block by default; required unmet requirements do."
  [{:keys [target-root possession-lineage possession-scope contributor]}]
  (when-not (possession-scopes possession-scope)
    (throw (ex-info "Invalid incentive possession scope"
                    {:possession-scope possession-scope})))
  (when-not (string? target-root)
    (throw (ex-info "Incentive target root is required"
                    {:target-root target-root})))
  (let [lineage (vec (or possession-lineage []))
        selected (if (= :target possession-scope)
                   lineage
                   (filterv #(= contributor (:researcher/id %)) lineage))
        evaluations (mapv (fn [entry]
                            (assoc (possession/evaluate
                                    (or (:proof-of-possession/requirement entry)
                                        :unspecified)
                                    (or (:proof-of-possession/status entry)
                                        :not-established))
                                   :researcher/id (:researcher/id entry)))
                          selected)
        blocking (filterv #(= :required-but-not-established
                              (:proof-of-possession/outcome %)) evaluations)]
    {:artifact/schema schema
     :incentive-target/root target-root
     :possession/scope possession-scope
     :possession/contributor (when (= :contributor possession-scope) contributor)
     :possession/evaluations evaluations
     :possession/outstanding-request? (boolean
                                       (some :proof-of-possession/outstanding-request?
                                             evaluations))
     :incentive/qualified? (empty? blocking)
     :incentive/reasons (if (seq blocking)
                          [:proof-of-possession-required-not-established]
                          [])}))
