(ns resolver-sim.yield.invariants-transition
  "Transition-step invariants (world-before → world-after). Used by yield-v1 replay."
  (:require [resolver-sim.yield.risk :as risk]
            [resolver-sim.yield.transition-basis :as basis]
            [resolver-sim.yield.partial-fill :as partial-fill]))

(defn- normalize-token [token]
  (cond
    (keyword? token) token
    (string? token) (keyword token)
    :else token))

(defn- index-at [world module-id token]
  (let [tok (normalize-token token)
        mid module-id]
    (or (get-in world [:yield/indices mid tok])
        (get-in world [:yield/indices mid (name tok)]))))

(defn- indices-changed
  "[[module-id token] ...] for token indices that differ between worlds.
   Detects changed, newly added, and removed indices."
  [world-before world-after]
  (let [before (:yield/indices world-before {})
        after (:yield/indices world-after {})
        mids (set (concat (keys before) (keys after)))]
    (for [mid mids
          :let [b-toks (get before mid {})
                a-toks (get after mid {})
                toks (set (concat (keys b-toks) (keys a-toks)))]
          tok toks
          :let [old-v (or (get b-toks tok) (get b-toks (name tok)))
                new-v (or (get a-toks tok) (get a-toks (name tok)))]
          :when (not= old-v new-v)]
      [mid tok])))

(defn- negative-yield-active? [world module-id token]
  (let [tok (normalize-token token)
        risk (or (get-in world [:yield/risk module-id tok])
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
    negative-yield? (<= (double new-index) (double old-index))
    :else (>= (double new-index) (double old-index))))

(defn check-index-monotone-transition
  [world-before world-after]
  (every? (fn [[mid tok]]
            (let [old (index-at world-before mid tok)
                  new (index-at world-after mid tok)
                  neg? (negative-yield-active? world-before mid tok)]
              (and (number? old) (number? new)
                   (index-monotone-ok? old new neg?))))
          (indices-changed world-before world-after)))

(defn check-transition-authoritative
  "Validate a yield transition only against its supplied authenticated basis."
  [world-before world-after event transition-basis]
  (let [address (basis/validate world-before world-after event transition-basis)
        ;; Ledger cutpoints are always the pre-withdrawal state. Only records
        ;; appended by this transition are checked, so historical records retain
        ;; their own authenticated transition context.
        new-ledgers (drop (count (:yield/withdrawal-ledger world-before []))
                          (:yield/withdrawal-ledger world-after []))
        expected-cutpoint (partial-fill/ledger-state-cutpoint-root world-before)
        cutpoint-result {:holds? (every? #(or (nil? (:ledger/state-cutpoint-root %))
                                              (= expected-cutpoint (:ledger/state-cutpoint-root %)))
                                         new-ledgers)
                         :violations (vec (keep #(when (and (:ledger/state-cutpoint-root %)
                                                            (not= expected-cutpoint (:ledger/state-cutpoint-root %)))
                                                   {:reason :withdrawal-cutpoint-transition-mismatch
                                                    :ledger/id (:ledger/id %)}) new-ledgers))}
        index-result {:holds? (check-index-monotone-transition world-before world-after)}
        results {:yield/state-addressed address
                 :yield/withdrawal-cutpoint cutpoint-result
                 :yield/index-monotone index-result}]
    {:all-hold? (every? :holds? (vals results))
     :results results}))

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
