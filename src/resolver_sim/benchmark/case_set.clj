(ns resolver-sim.benchmark.case-set
  "Generated case set identity and indexing.

   A case set is an ordered collection of cases generated for one benchmark
   execution.  Each case is identified by the pair:

     {:generated-case-set-root \"sha256:...\"
      :case/key N}

   The root commits to the ordered case identifiers, so two case sets with
   the same cases in the same order produce the same root.

   Case keys are dense zero-based integers matching the execution ordinal
   of each scenario run.  They enable compact references to individual
   cases without repeating content hashes."
  (:require [resolver-sim.hash.canonical :as hc]))

(defn case-key-for-execution
  "Derive the :case/key for an execution from its :execution/ordinal.
   Ordinals are 1-based; case keys are 0-based.

     (case-key-for-execution 1)  ;; => 0
     (case-key-for-execution 5)  ;; => 4"
  [ordinal]
  (dec ordinal))

(defn execution-ordinal-for-case-key
  "Derive the :execution/ordinal from a :case/key.
   Inverse of case-key-for-execution.

     (execution-ordinal-for-case-key 0)  ;; => 1
     (execution-ordinal-for-case-key 4)  ;; => 5"
  [case-key]
  (inc case-key))

(defn build-case-set
  "Build a case set from execution plan entries.

   Each plan entry must have :execution/ordinal and :execution/id.
   Returns a vector of case maps sorted by ordinal:

     [{:case/key 0, :execution/id \"sha256:...\", :execution/ordinal 1}
      {:case/key 1, :execution/id \"sha256:...\", :execution/ordinal 2}
      ...]"
  [plan]
  (let [sorted (sort-by :execution/ordinal plan)]
    (mapv (fn [entry]
            {:case/key (case-key-for-execution (:execution/ordinal entry))
             :execution/id (:execution/id entry)
             :execution/ordinal (:execution/ordinal entry)})
          sorted)))

(defn compute-case-set-root
  "Compute the authoritative :generated-case-set-root from a case set.

   The root is a domain-separated hash over the ordered list of case
   entries, each containing :case/key, :execution/id, and
   :execution/ordinal.

   Returns \"sha256:...\" hash string.

     (compute-case-set-root (build-case-set plan))
     ;; => \"sha256:...\""
  [case-set]
  (str "sha256:"
       (hc/domain-hash :generated-case-set
                       {:case-count (count case-set)
                        :cases case-set})))
