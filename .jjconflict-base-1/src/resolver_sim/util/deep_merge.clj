(ns resolver-sim.util.deep-merge
  "Recursive merge utility for combining nested maps.
   Maps at any depth are merged; scalars from `b` win over `a`.
   Used by fixture-reference resolution to combine fixture defaults
   with inline scenario params.")

(defn deep-merge
  "Recursive merge of two nested maps.
   Right-biased: values in `b` override values in `a`.
   Maps at corresponding keys are merged recursively."
  [a b]
  (cond
    (and (map? a) (map? b))
    (merge-with deep-merge a b)
    :else
    (if (nil? b) a b)))
