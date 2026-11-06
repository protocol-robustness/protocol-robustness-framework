(ns resolver-sim.yield.exact-math
  "Exact integer and ratio arithmetic for yield accrual, partial-fill ratios,
   shortfall ratios, rounding-drift analysis, and pro-rata allocation.

   Principles:
   - Economic amounts, APYs, indices, available ratios, and fill ratios are
     represented as exact ratios internally.
   - Externally settled token amounts are integer base units.
   - Quantization rounds down (floor) and preserves exact fractional remainders
     for carry-forward across accrual intervals.
   - All arithmetic is deterministic and reproducible.

   ## Framework pro-rata primitive

   largest-remainder-alloc is the canonical Hare-quota allocation function.
   Used by:
   - resolver-sim.yield.partial-fill      (pro-rata fill mode)
   - resolver-sim.economics.payoffs       (prorata slash allocation)
   - resolver-sim.sim.trial-router        (weighted trial distribution)

   Any new pro-rata calculation should call this function.")

(def seconds-per-year
  "Canonical: resolver-sim.time.context/seconds-per-year"
  31536000)

(def scaling-factor
  "Canonical BPS denominator. Also defined as resolver-sim.economics.payoffs/basis-point-denominator (same value)."
  10000)

(defn bps->ratio
  "Convert basis points to an exact ratio. 10% = 1000 bps = 1000/10000 = 1/10."
  [bps]
  (/ (long bps) scaling-factor))

(defn ratio->bps
  "Convert a ratio back to approximate bps (truncated)."
  [r]
  (long (Math/floor (* (double r) scaling-factor))))

(defn index-growth-factor
  "Compute the exact non-negative multiplicative factor for index growth over
   dt seconds given `apy-bps` (integer basis points).

   factor = 1 + (apy-bps/10000) * (dt / seconds-per-year)
          = (10000 * seconds-per-year + apy-bps * dt) / (10000 * seconds-per-year)

   Clamped to >= 0 to prevent negative index (a physical impossibility).
   Returns an exact ratio."
  [apy-bps dt]
  (let [bps (long apy-bps)
        dt  (long dt)
        raw (/ (+ (* scaling-factor seconds-per-year) (* bps dt))
               (* scaling-factor seconds-per-year))]
    (max 0 raw)))

(defn ratio
  "Coerce a value to a Clojure ratio. Accepts long, ratio, double, or nil.
   nil defaults to 1/1. Uses a denominator of 1 and numerator of the value
   cast via bigint to force Ratio representation, ensuring numerator/denominator
   always work. For integer zero, returns 0/1 (a true Ratio rather than 0N)."
  [x]
  (cond
    (ratio? x)                     x
    (integer? x)                   (clojure.lang.Ratio. (biginteger (long x)) (biginteger 1))
    (float? x)                     (rationalize (double x))
    (instance? Number x)           (rationalize (double x))
    (nil? x)                       (clojure.lang.Ratio. (biginteger 1) (biginteger 1))
    :else                          (throw (ex-info "Cannot coerce to ratio"
                                                   {:value x :type (type x)}))))

(defn next-index
  "Compute the next index after accrual: new-index = index * growth-factor.

   All arguments and result are exact ratios."
  [index apy-bps dt]
  (* (ratio index) (index-growth-factor apy-bps dt)))

(defn index-from-bps-and-dt
  "Convenience: compute next-index starting from 1.0 with given bps and dt."
  [apy-bps dt]
  (next-index 1 apy-bps dt))

(defn current-value-exact
  "Compute the exact current value of a position in underlying token units
   (as a ratio): shares × current-index.

   `shares` and `current-index` should be ratios."
  [shares current-index]
  (* (ratio shares) (ratio current-index)))

(defn principal-from-shares-and-index
  "Reverse-calculate principal from shares and entry-index.
   principal = shares × entry-index (exact ratio)."
  [shares entry-index]
  (* (ratio shares) (ratio entry-index)))

(defn shares-from-principal-and-index
  "Calculate shares from principal and entry-index.
   shares = principal / entry-index (exact ratio).  Returns 0 when entry-index is 0."
  [principal entry-index]
  (let [idx (ratio entry-index)]
    (if (zero? idx) 0 (/ (ratio principal) idx))))

(defn- ratio-num
  "Safe numerator accessor: returns the numerator of a Ratio, or the integer
   itself for non-Ratio numbers."
  [r]
  (if (ratio? r) (.numerator ^clojure.lang.Ratio r) (biginteger r)))

(defn- ratio-den
  "Safe denominator accessor: returns the denominator of a Ratio, or 1N for
   non-Ratio numbers."
  [r]
  (if (ratio? r) (.denominator ^clojure.lang.Ratio r) 1))

(defn quantize-base-units
  "Floor a ratio to integer base units and return [base-units remainder].

   base-units = floor(ratio)
   remainder  = ratio - base-units  (always 0 <= remainder < 1)

   Returns [long remainder-ratio] -- units are clamped to Long/MAX_VALUE
   to prevent silent overflow on ray-based indices or extreme supplies."
  [r]
  (let [r (ratio r)
        n (ratio-num r)
        d (ratio-den r)
        exact-quot (quot n d)
        max-long (java.math.BigInteger/valueOf Long/MAX_VALUE)
        units (if (instance? java.math.BigInteger exact-quot)
                (let [bv exact-quot]
                  (cond
                    (neg? (.signum bv)) 0
                    (not (pos? (.compareTo bv max-long))) (.longValue bv)
                    :else Long/MAX_VALUE))
                (max 0 (long exact-quot)))
        rem (- r units)]
    [units rem]))

(defn quantize-with-carry
  "Quantize a ratio into base units, accumulating the fractional remainder
   with a prior carry.

   Returns {:units long :carry ratio} where carry is the new accumulated
   fractional remainder to carry forward to the next interval.

   The prior-carry is added to the value before quantization, so sub-unit
   accrual accumulates across intervals until it crosses one claimable unit."
  [value prior-carry]
  (let [total (+ (ratio value) (ratio prior-carry))
        [units rem] (quantize-base-units total)]
    {:units units
     :carry rem
     :exact-total total}))

(defn floor-and-carry-alloc
  "Allocate `total-available` across `claims` using floor-and-carry policy.

   Each claim is a map with at least `:amount` (ratio or long).
   Returns claims with `:filled` (long base units) and allocator-level
   `:carry` (exact remainder preserved for next round).

   Never allocates more than total-available in base units."
  [total-available claims]
  (let [available (ratio total-available)
        total-amount (reduce + 0 (map (comp ratio :amount) claims))]
    (if (zero? total-amount)
      {:allocations (mapv #(assoc % :filled 0 :ideal-exact 0 :remainder-exact 0) claims)
       :total-available-units (first (quantize-base-units available))
       :total-allocated-units 0
       :shortage-units 0
       :carry 0}
      (let [ideal (mapv (fn [claim] (* available (/ (ratio (:amount claim)) total-amount)))
                        claims)
            [units rems] (reduce (fn [[us rs] ideal-i]
                                   (let [[u r] (quantize-base-units ideal-i)]
                                     [(conj us u) (conj rs r)]))
                                 [[] []]
                                 ideal)
            sum-units (reduce + 0 units)
            available-units (first (quantize-base-units available))
            shortage (- available-units sum-units)
            n (count claims)
            indices-by-rem (when (pos? shortage)
                             (->> (range n)
                                  (sort-by #(- (nth rems %)))
                                  (take shortage)))
            final-units (if (pos? shortage)
                          (reduce (fn [us i] (update us i inc))
                                  (vec units) indices-by-rem)
                          units)
            carry (- available (ratio available-units))]
        {:allocations (mapv (fn [claim u r ideal-i]
                              (assoc claim :filled u :ideal-exact ideal-i :remainder-exact r))
                            claims final-units rems ideal)
         :total-available-units available-units
         :total-allocated-units (reduce + 0 final-units)
         :shortage-units 0
         :carry (ratio carry)}))))

(defn floor-alloc
  "Allocate `total-available` across `claims` using strict floor semantics.

   Each claim receives floor(ideal share); the unallocated unit residual is
   returned in :shortage-units and deliberately NOT carried to any claimant.
   This is the pure-:floor counterpart to floor-and-carry-alloc: it never
   awards an upward unit, so per-claimant deviation from the ideal is 0 and
   the residual is the only source of non-allocation.

   Never allocates more than total-available in base units."
  [total-available claims]
  (let [available (ratio total-available)
        total-amount (reduce + 0 (map (comp ratio :amount) claims))]
    (if (zero? total-amount)
      {:allocations (mapv #(assoc % :filled 0 :ideal-exact 0 :remainder-exact 0) claims)
       :total-available-units (first (quantize-base-units available))
       :total-allocated-units 0
       :shortage-units (first (quantize-base-units available))
       :carry 0}
      (let [ideal (mapv (fn [claim] (* available (/ (ratio (:amount claim)) total-amount)))
                        claims)
            filled (mapv (fn [i] (first (quantize-base-units i))) ideal)
            rems (mapv (fn [i] (second (quantize-base-units i))) ideal)
            sum-filled (reduce + 0 filled)
            available-units (first (quantize-base-units available))
            shortage (max 0 (- available-units sum-filled))]
        {:allocations (mapv (fn [claim u r ideal-i]
                              (assoc claim :filled u :ideal-exact ideal-i :remainder-exact r))
                            claims filled rems ideal)
         :total-available-units available-units
         :total-allocated-units sum-filled
         :shortage-units shortage
         :carry 0}))))

(defn largest-remainder-alloc
  "Allocate `total-available` base units across `claims` using the
   largest-remainder method (Hare quota).

   This is the framework's canonical pro-rata allocation primitive. It is used
   by the yield settlement engine (partial-fill pro-rata mode), the economics
   module (prorata slash allocation), and the simulation engine (weighted trial
   distribution). Any new pro-rata need should call this function.

   ## Contract

   Each claim is a map with at least `:amount` (the weight — any numeric type;
   doubles are exact-rationalized internally). The function computes each
   claim's ideal fractional share of total-available, assigns the floor
   allocation, then distributes the remaining units one at a time to claims
   with the largest fractional remainders. Ties are broken by input order
   (deterministic for reproducible sequences).

   The function guarantees:
   - sum(:filled across all claims) == total-available (conservation)
   - No claim receives more than its ideal share times total-available
   - All amounts are integer base units (no fractional claims)

   ## Returns

   {:allocations [{:amount       ,  ;; original weight
                   :filled       ,  ;; allocated base units (long)
                   :remainder-exact ;; exact fractional remainder
                   :ideal-exact  }  ;; exact ideal share
                  ...]
    :total-available-units     ,  ;; total-available as base units
    :total-allocated-units     ,  ;; sum of :filled (== total-available)
    :shortage-units            ,  ;; always 0 for this method
    :carry                     }  ;; always 0 for this method

   When total claim amount is zero, all claims receive 0 filled units.

   ## Example

   (largest-remainder-alloc 10 [{:key :a :amount 3}
                                {:key :b :amount 3}
                                {:key :c :amount 3}])
   ;; => first two claims get 4, 3, 3 (remainder goes to earliest indices)
   "
  [total-available claims]
  {:pre [(let [tot (ratio total-available)]
           (and (not (nil? tot)) (>= tot 0)))
         (sequential? claims)
         (every? #(and (number? (:amount %)) (not (neg? (long (:amount %))))) claims)]}
  (let [total (ratio total-available)
        [total-units total-rem] (quantize-base-units total)
        n (count claims)
        claims-total (reduce + 0 (map (comp ratio :amount) claims))]
    (if (zero? claims-total)
      {:allocations (mapv #(assoc % :filled 0 :ideal-exact 0 :remainder-exact 0) claims)
       :total-available-units total-units
       :total-allocated-units 0
       :shortage-units 0
       :carry total-rem}
      (let [;; Step 1: Calculate the exact ideal proportional shares
            ideal (mapv (fn [c] (* total (/ (ratio (:amount c)) (ratio (or (:basis c) claims-total)))))
                        claims)

            ;; Step 2: Segment each ideal share into lower-quota (floor) and fractional remainder
            indexed (map-indexed (fn [i c] (assoc c :idx i :ideal (nth ideal i))) claims)
            [units rems] (reduce (fn [[us rs] idx-i]
                                   (let [[u r] (quantize-base-units (:ideal idx-i))]
                                     [(conj us u) (conj rs r)]))
                                 [[] []]
                                 indexed)

            ;; Step 3: Compute the shortage (unallocated units due to flooring)
            sum-units (reduce + 0 units)
            shortage (- total-units sum-units)
            _ (assert (>= shortage 0) "Shortage must be non-negative (lower-quota sum <= total units)")
            _ (assert (< shortage n) "Shortage must be strictly less than the number of claims")

            ;; Step 4: Allocate remainder units to claims with largest fractional remainders
            indices-by-rem (->> (range n)
                                (sort-by #(- (nth rems %)))
                                (take shortage))
            final-units (reduce (fn [us i] (update us i inc))
                                (vec units)
                                (vec indices-by-rem))

            ;; Step 5: Post-condition verification of conservation and quota invariants
            total-allocated (reduce + 0 final-units)
            _ (assert (= total-units total-allocated)
                      (str "Conservation violation: total-units=" total-units " but total-allocated=" total-allocated))]

        ;; Verify Quota rule: each allocation must satisfy floor(ideal) <= allocated <= ceil(ideal)
        (dotimes [i n]
          (let [idl (nth ideal i)
                [flr rem] (quantize-base-units idl)
                cel (if (zero? rem) flr (inc flr))
                alloc (nth final-units i)]
            (assert (and (>= alloc flr) (<= alloc cel))
                    (str "Quota rule violation at index " i ": ideal=" idl " allocated=" alloc " range=[" flr "," cel "]"))))

        {:allocations (mapv (fn [claim u r ideal-v]
                              (assoc claim :filled u :remainder-exact r :ideal-exact ideal-v))
                            claims final-units rems ideal)
         :total-available-units total-units
         :total-allocated-units total-allocated
         :shortage-units 0
         :carry 0}))))

(defn principal-protective-floor-alloc
  "Allocate `total-available` across claims, protecting principal claims first.

   Principal claims receive their full floor allocation, capped at
   total-available. Uses floor-and-carry for all principal claims to preserve
   exact carry and minimize dust loss. Within yield claims, largest-remainder
   applies.

   `principal-pred` is a fn on claim returning truthy for principal claims."
  [total-available claims principal-pred]
  (let [total (ratio total-available)
        total-units (first (quantize-base-units total))
        principal-claims (filterv principal-pred claims)
        yield-claims (filterv (complement principal-pred) claims)
        ;; Floor quantize to get principal request, then cap at total-units
        p-req-exact (reduce + (map (comp ratio :amount) principal-claims))
        p-req-floored (first (quantize-base-units p-req-exact))
        principal-allocated (min total-units p-req-floored)
        available-for-yield (max 0 (- total-units principal-allocated))
        ;; Use floor-and-carry for all principal claims to minimize dust loss
        principal-alloc (if (seq principal-claims)
                          (:allocations (floor-and-carry-alloc principal-allocated principal-claims))
                          [])
        yield-alloc (if (and (seq yield-claims) (pos? available-for-yield))
                      (:allocations (largest-remainder-alloc available-for-yield yield-claims))
                      (mapv #(assoc % :filled 0 :remainder-exact 0 :ideal-exact 0) yield-claims))]
    {:allocations (into (vec principal-alloc) (vec yield-alloc))
     :total-available-units total-units
     :total-allocated-units (+ (reduce + 0 (map :filled principal-alloc))
                               (reduce + 0 (map :filled yield-alloc)))
     :shortage-units (- total-units (+ (reduce + 0 (map :filled principal-alloc))
                                       (reduce + 0 (map :filled yield-alloc))))
     :carry 0}))

(defn adversarial-rounding
  "Round in the worst-case direction for testing rounding-debt conservation.
   All fractional remainders round toward the adversary (up), producing a
   positive rounding debt that must equal the sum of fractional remainders.

   This is only for test purposes; it violates the 'never exceeds available'
   constraint by design."
  [total-available claims]
  (let [total (ratio total-available)
        total-units (first (quantize-base-units total))
        claims-total (reduce + 0 (map (comp ratio :amount) claims))
        ideal (mapv (fn [c] (* total (/ (ratio (:amount c)) (ratio (or (:basis c) claims-total)))))
                    claims)
        filled (mapv (fn [i] (long (Math/ceil (double i)))) ideal)
        sum-filled (reduce + 0 filled)
        rounding-debt (- sum-filled total-units)]
    {:allocations (mapv (fn [claim f i]
                          (assoc claim :filled f :ideal-exact i :remainder-exact (- (ratio f) i)))
                        claims filled ideal)
     :total-available-units total-units
     :total-allocated-units sum-filled
     :shortage-units (- total-units sum-filled)
     :rounding-debt rounding-debt
     :carry 0}))

(defn apy-degradation
  "Apply stale-oracle APY degradation using exact ratio arithmetic.

   Given base-apy-bps and stale-seconds, compute the effective APY:
   - If base is positive: decay toward a configurable floor (default 0)
   - If base is negative: do not make it less conservative (return base unchanged)

   Degradation factor: max(0, 1 - stale-seconds / max-stale-seconds)
   Effective = floor-apy-bps + (base-apy-bps - floor-apy-bps) * degradation

   All values are exact integer bps; result is long bps."
  [base-apy-bps stale-seconds max-stale-seconds floor-apy-bps]
  (let [base  (long base-apy-bps)
        stale (long stale-seconds)
        max-s (long max-stale-seconds)
        floor (long (or floor-apy-bps 0))]
    (if (<= base 0)
      base
      (let [degradation (max 0 (/ (- max-s stale) (max 1 max-s)))
            exact-product (* (- base floor) degradation)
            effective (+ floor (first (quantize-base-units exact-product)))]
        (max floor (min base effective))))))

(defn shortfall-ratio-exact
  "Compute the exact shortfall ratio: available / needed, capped at 1.0."
  [available needed]
  (if (zero? needed)
    1
    (min 1 (/ (ratio available) (ratio needed)))))

(defn split-amount-exact
  "Split an amount into [realized unrealized deferred haircut] based on
   ratios. Returns a map with exact ratio values."
  [total realized-ratio unrealized-ratio deferred-ratio haircut-ratio]
  (let [t (ratio total)]
    {:realized   (* t (ratio realized-ratio))
     :unrealized (* t (ratio unrealized-ratio))
     :deferred   (* t (ratio deferred-ratio))
     :haircut    (* t (ratio haircut-ratio))}))

(defn ratio->json
  "Serialize a ratio for JSON output as {:num \"...\" :den \"...\"}."
  [r]
  (let [r (ratio r)]
    (if (ratio? r)
      {:num (str (.numerator ^clojure.lang.Ratio r))
       :den (str (.denominator ^clojure.lang.Ratio r))}
      {:num (str r)
       :den "1"})))

