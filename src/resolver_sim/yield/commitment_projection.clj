(ns resolver-sim.yield.commitment-projection
  "Closed, versioned commitment projections for yield economic state.

   Runtime yield world state is Clojure host data (Ratios, Doubles, sets,
   ...) that is deliberately outside the strict canonical encoder.  These
   projections map only the committed economic fields to canonical-safe EDN,
   normalizing every committed numeric value to exact reduced-rational form
   via project-yield-number.

   The global canonical numeric domain (resolver-sim.hash.canonical) is left
   unchanged: Ratio, Double, and BigDecimal do not become canonical.  Instead
   the economic meaning of a number is committed as a reduced {numerator,
   denominator} pair, so 0.05, 0.050, 5e-2, and 1/20 all commit to 1/20.
   Two implementations reconstructing the same economic state therefore
   converge on the same state root despite differing internal numeric
   representations.")

(def ^:const number-schema
  "Canonical reduced-rational commitment schema."
  :yield-rational.v1)

(def ^:const transition-basis-schema
  "Transition-basis schema the state/policy commitments are bound to."
  :prf/yield-transition-basis.v1)

(def ^:const state-projection-schema
  "Closed commitment projection schema for yield economic state."
  :yield-state-commitment-projection-v1)

(def ^:const policy-projection-schema
  "Closed commitment projection schema for the yield effective policy."
  :yield-effective-policy-commitment-projection-v1)

(def ^:const event-projection-schema
  "Closed commitment projection schema for a yield transition event."
  :yield-event-commitment-projection-v1)

;; ── Reduced rational arithmetic ──────────────────────────────────────────────

(defn- abs-big [n]
  (if (neg? n) (- n) n))

(defn- gcd-big
  "Euclidean gcd of two bigints (over absolute values)."
  [a b]
  (loop [a (abs-big (bigint a))
         b (abs-big (bigint b))]
    (if (zero? b) a (recur b (mod a b)))))

(defn- reduce-rational
  "Reduce n/d to lowest terms with denominator > 0 and gcd(|n|, d) = 1.
   Returns [n d] as bigints.  Zero always reduces to 0/1."
  [n d]
  (let [n (bigint n)
        d (bigint d)]
    (when (zero? d)
      (throw (ex-info "yield rational projection requires a non-zero denominator"
                      {:type :yield.projection/zero-denominator
                       :numerator n :denominator d})))
    (let [sign (if (neg? d) -1 1)
          d (abs-big d)
          n (* sign n)
          g (gcd-big n d)
          n (quot n g)
          d (quot d g)]
      [n d])))

(defn- decimal->rational
  "Exact decimal (BigDecimal) to reduced rational [n d].
   Handles negative scales (e.g. 1E+3 -> 1000/1)."
  [^java.math.BigDecimal v]
  (let [unscaled (.unscaledValue v)
        scale (.scale v)]
    (if (neg? scale)
      (reduce-rational (.multiply unscaled
                                  (.pow java.math.BigInteger/TEN (- scale)))
                       1)
      (reduce-rational unscaled (.pow java.math.BigInteger/TEN scale)))))

;; ── Numeric commitment primitive ─────────────────────────────────────────────

(defn project-yield-number
  "Normalize a committed yield numeric value to exact reduced-rational form.

   Finite Double/Float inputs cross the compatibility boundary through their
   canonical decimal representation (BigDecimal/valueOf), so 0.05 and 1/20
   commit identically — the IEEE-754 bit pattern is never committed.  Rejects
   NaN, ±Infinity, and unsupported numeric classes."
  [v]
  (let [[n d] (cond
                (instance? Double v)
                (do (when (or (Double/isNaN v) (Double/isInfinite v))
                      (throw (ex-info "yield commitment rejects non-finite Double"
                                      {:type :yield.projection/non-finite :value v})))
                    (decimal->rational (java.math.BigDecimal/valueOf v)))

                (instance? Float v)
                (do (when (or (Float/isNaN v) (Float/isInfinite v))
                      (throw (ex-info "yield commitment rejects non-finite Float"
                                      {:type :yield.projection/non-finite :value v})))
                    (decimal->rational (java.math.BigDecimal/valueOf (double v))))

                (instance? java.math.BigDecimal v)
                (decimal->rational v)

                (ratio? v)
                (reduce-rational (numerator v) (denominator v))

                (integer? v)
                (reduce-rational v 1)

                :else
                (throw (ex-info "yield commitment rejects unsupported numeric value"
                                {:type :yield.projection/unsupported-numeric
                                 :value v :value-class (some-> v class .getName)})))]
    {:yield.number/schema number-schema
     :yield.number/numerator n
     :yield.number/denominator d}))

;; ── Closed structural transform over committed fields ───────────────────────

(defn- project-committed
  "Strict structural transform over committed yield data (never raw world
   state).  Numbers -> project-yield-number; nil/boolean/string/keyword pass
   through; maps and vectors recurse; sets project to sorted vectors; map keys
   must be string or keyword; any other host type (functions, records,
   temporal values, ...) is rejected so the projection cannot silently
   rewrite runtime state it does not intend to commit."
  [x]
  (cond
    (number? x)
    (project-yield-number x)

    (or (nil? x) (boolean? x) (string? x) (keyword? x))
    x

    (map? x)
    (into {}
          (map (fn [[k v]]
                 (when-not (or (string? k) (keyword? k))
                   (throw (ex-info "yield commitment projection rejects non-canonical map key"
                                   {:type :yield.projection/unsupported-key
                                    :key k :key-class (some-> k class .getName)})))
                 [k (project-committed v)]))
          x)

    (vector? x)
    (mapv project-committed x)

    (set? x)
    (mapv project-committed (sort-by pr-str x))

    :else
    (throw (ex-info "yield commitment projection rejects unsupported value"
                    {:type :yield.projection/unsupported-type
                     :value x :value-class (some-> x class .getName)}))))

;; ── Closed commitment projections ───────────────────────────────────────────

(defn project-yield-state
  "Closed commitment projection of runtime yield state.

   Only the five committed economic fields enter the commitment; all runtime
   bookkeeping (modules, ops, temporal context, ...) is excluded."
  [world]
  {:yield.projection/schema state-projection-schema
   :schema transition-basis-schema
   :held-balances (project-committed (:yield/held-balances world {}))
   :indices (project-committed (:yield/indices world {}))
   :positions (project-committed (:yield/positions world {}))
   :withdrawal-ledger (project-committed (:yield/withdrawal-ledger world []))
   :partial-fill-decisions (project-committed (:yield/partial-fill-decisions world {}))})

(defn project-effective-policy
  "Closed commitment projection of the yield effective policy.

   Only the four committed policy fields enter the commitment; runtime
   schedule and module machinery is excluded."
  [world]
  {:yield.projection/schema policy-projection-schema
   :schema transition-basis-schema
   :risk (project-committed (:yield/risk world {}))
   :rates (project-committed (:yield/rates world {}))
   :shortfall-models (project-committed (:yield/shortfall-models world {}))
   :withdrawal-policies (project-committed (:yield/withdrawal-policies world {}))})

(defn project-event
  "Closed commitment projection of a yield transition event.

   Param numeric values are normalized through the same project-yield-number
   primitive, so event identity commits to economic meaning rather than the
   host representation (e.g. 0.5 and 1/2 params commit identically)."
  [event]
  {:yield.projection/schema event-projection-schema
   :schema transition-basis-schema
   :seq (:seq event)
   :time (:time event)
   :agent (:agent event)
   :action (:action event)
   :params (project-committed (:params event {}))})