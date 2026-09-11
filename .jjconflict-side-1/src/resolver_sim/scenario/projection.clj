(ns resolver-sim.scenario.projection
  "Generic projection utilities shared across protocol implementations.

   Each protocol provides its own trace-end projection by implementing the
   trace-projection method on DisputeProtocol.  The utilities below are
   available to all protocol implementations when building their projection.

   See protocols/sew/projection.clj for the Sew reference implementation.

   This namespace is pure — no I/O, no DB, no side effects.")

;; ---------------------------------------------------------------------------
;; Generic helpers (public — usable by any protocol's projection impl)
;; ---------------------------------------------------------------------------

(defn map-delta
  "Return per-key (new - old) deltas for numeric maps."
  [old-map new-map]
  (let [keys-all (into #{} (concat (keys (or old-map {})) (keys (or new-map {}))))]
    (reduce (fn [m k]
              (let [old-v (long (get old-map k 0))
                    new-v (long (get new-map k 0))
                    d     (- new-v old-v)]
                (if (zero? d) m (assoc m k d))))
            {}
            keys-all)))

(defn nested-sum-by-actor
  "Sum nested map values keyed as {workflow-id {actor amount}} into {actor total}."
  [m]
  (reduce-kv (fn [acc _wf actor-map]
               (reduce-kv (fn [a actor amt]
                            (update a actor (fnil + 0) (long amt)))
                          acc
                          (or actor-map {})))
             {}
             (or m {})))

(defn classify-coalition-actor?
  "Best-effort coalition tagging from scenario agent metadata."
  [agent]
  (let [tags (set (map keyword (or (:tags agent) [])))
        coalition (:coalition agent)
        strategy (keyword (or (:strategy agent) ""))
        role (keyword (or (:role agent) ""))
        type (keyword (or (:type agent) ""))]
    (or coalition
        (contains? tags :coalition)
        (= strategy :collusive)
        (= role :collusive)
        (= type :collusive))))
