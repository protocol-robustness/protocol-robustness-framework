(ns resolver-sim.research.independent-accounting-impl
  "Second accounting fold used to qualify artifact-level implementation diversity.

   This module independently groups selected source rows by category before
   reducing each group. It implements only the declared integer sum operation;
   it does not validate source claims or determine economic correctness.")

(def algorithm-id :accounting.implementation/group-then-fold.v1)

(defn partial-values [request work-unit]
  (let [selected (set (:partition/source-roots work-unit))
        rows (filter #(contains? selected (:source/root %))
                     (:accounting/sources request))]
    (into (sorted-map)
          (map (fn [[category entries]]
                 [category (reduce + 0 (map :accounting/value entries))]))
          (group-by :accounting/category rows))))
