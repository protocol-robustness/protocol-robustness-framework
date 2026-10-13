(ns resolver-sim.benchmark.integer-coordinate-distance
  "Exact L1 distance for explicitly metric integer coordinate spaces.")

(def missing :missing)
(def not-comparable :not-comparable)

(defn manhattan-distance
  "Return an exact integer L1 distance, or an explicit nonnumeric outcome.

  `space` must declare `:coordinate-space/distance-capable? true`. Researcher
  axes are nominal and are rejected regardless of their integer representation."
  [space left right]
  (when-not (map? space)
    (throw (ex-info "Coordinate space must be declared" {:space space})))
  (when (= :researcher (:coordinate-space/axis-kind space))
    (throw (ex-info "Researcher coordinates are nominal, not metric" {:space space})))
  (when-not (true? (:coordinate-space/distance-capable? space))
    (throw (ex-info "Coordinate space is not distance-capable" {:space space})))
  (cond
    (or (= missing left) (= missing right))
    {:distance/status :missing}

    (or (= not-comparable left) (= not-comparable right))
    {:distance/status :not-comparable}

    (not (and (vector? left) (vector? right)))
    (throw (ex-info "Coordinates must be vectors" {:left left :right right}))

    (not= (count left) (count right))
    (throw (ex-info "Coordinate dimensionality mismatch"
                    {:left-dimensions (count left) :right-dimensions (count right)}))

    (not (every? integer? (concat left right)))
    (throw (ex-info "Coordinate components must be canonical integers"
                    {:left left :right right}))

    :else
    {:distance/status :computed
     :distance/value (reduce +' 0N (map #(abs (-' %1 %2)) left right))}))
