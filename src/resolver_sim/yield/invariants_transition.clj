(ns resolver-sim.yield.invariants-transition
  "Transition-step invariants (world-before → world-after). Used by yield-v1 replay."
  (:require [resolver-sim.yield.risk :as risk]))

(defn- normalize-token [token]
  (cond
    (keyword? token) token
    (string? token)  (keyword token)
    :else token))

(defn- index-at [world module-id token]
  (let [tok (normalize-token token)
        mid module-id]
    (double (or (get-in world [:yield/indices mid tok])
                (get-in world [:yield/indices mid (name tok)])
                1.0))))

(defn- indices-changed
  "[[module-id token] ...] for token indices that differ between worlds.
   Detects changed, newly added, and removed indices."
  [world-before world-after]
  (let [before (:yield/indices world-before {})
        after  (:yield/indices world-after {})
        mids   (set (concat (keys before) (keys after)))]
    (for [mid mids
          :let [b-toks (get before mid {})
                a-toks (get after mid {})
                toks   (set (concat (keys b-toks) (keys a-toks)))]
          tok toks
          :let [old-v (or (get b-toks tok) (get b-toks (name tok)) 1.0)
                new-v (or (get a-toks tok) (get a-toks (name tok)) 1.0)]
          :when (not= (double old-v) (double new-v))]
      [mid tok])))

(defn- negative-yield-active? [world module-id token]
  (let [tok   (normalize-token token)
        risk  (or (get-in world [:yield/risk module-id tok])
                  (get-in world [:yield/risk module-id (name tok)])
                  {})]
    (contains? (risk/normalize-failure-modes (:failure-modes risk)) :negative-yield)))

(defn index-monotone-ok?
  "Positive APY ⇒ index non-decreasing; :negative-yield mode ⇒ non-increasing.
   Returns false if an existing index is removed (new nil).
   Returns true if a new index appears (old nil) — initialization is allowed."
  [old-index new-index negative-yield?]
  (cond
    (nil? new-index) false
    (nil? old-index) true
    negative-yield?  (<= (double new-index) (double old-index))
    :else            (>= (double new-index) (double old-index))))

(defn check-index-monotone-transition
  [world-before world-after]
  (every? (fn [[mid tok]]
            (let [old (index-at world-before mid tok)
                  new (index-at world-after mid tok)
                  neg? (negative-yield-active? world-before mid tok)]
              (index-monotone-ok? old new neg?)))
          (indices-changed world-before world-after)))

(defn check-all-transitions
  "Returns {inv-kw {:holds? bool}} for transition checks."
  [world-before world-after]
  {:yield/index-monotone {:holds? (check-index-monotone-transition
                                   world-before world-after)}})

(defn transition-violations
  "Map of failed invariants for replay (same shape as single-world checks)."
  [world-before world-after]
  (into {}
        (keep (fn [[k r]] (when-not (:holds? r) {k r}))
              (check-all-transitions world-before world-after))))
