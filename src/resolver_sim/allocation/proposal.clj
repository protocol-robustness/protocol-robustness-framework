(ns resolver-sim.allocation.proposal
  "Proposal-level derivation and validation for an allocation context.

   Owns exact ratio normalisation, common denominator derivation, the rate
   derived summary, exact expected allocations, and the structural proposal
   checks (all-or-nothing, exact capacity, eligibility, duplicate claims,
   proportional-proposed cross products). All arithmetic is arbitrary-precision
   integer arithmetic;   no floating point is permitted.

   Derived totals are never trusted from callers: every summary value is
   recomputed from the source claims, outcomes, and rates."
  (:require [resolver-sim.hash.canonical :as hc]))

(defn big-int-gcd
  "Greatest common divisor of two bigint values (positive result)."
  [a b]
  (let [g (.gcd (.toBigInteger (bigint a)) (.toBigInteger (bigint b)))]
    (bigint (if (neg? g) (.negate g) g))))

(defn canonical-rate
  "Normalise a rate to reduced {numerator, denominator} form. Zero becomes 0/1.
   Declared rates are otherwise preserved by the context parser; this helper is
   the canonical normalisation reference for exact-ratio tests."
  [{:keys [numerator denominator]}]
  (let [n (bigint numerator)
        d (bigint denominator)]
    (if (zero? n)
      {:numerator (bigint 0) :denominator (bigint 1)}
      (let [g (big-int-gcd n d)]
        {:numerator (bigint (/ n g)) :denominator (bigint (/ d g))}))))

(defn ratio-normalised?
  "True when a rate map is a reduced exact ratio: positive denominator,
   non-negative numerator, and gcd(numerator, denominator) == 1. Zero is 0/1."
  [{:keys [numerator denominator]}]
  (and (integer? numerator)
       (integer? denominator)
       (not (neg? numerator))
       (pos? denominator)
       (= 1 (big-int-gcd numerator denominator))))

(defn ratio-sum-common
  "Given rates in outcome canonical order, derive:
     - :common-denominator   lcm of reduced denominators
     - :scaled-numerators    numerator per outcome scaled to the common denominator
     - :sum                  sum of scaled numerators (== common denominator iff rates sum to 1)"
  [rates]
  (let [reduced (mapv :rate rates)
        common-denominator
        (bigint
         (reduce (fn [acc-bigint {:keys [denominator]}]
                   (let [d (.toBigInteger (bigint denominator))
                         g (.gcd acc-bigint d)]
                     (.multiply (.divide acc-bigint g) d)))
                 java.math.BigInteger/ONE
                 reduced))
        scaled (mapv (fn [{:keys [numerator denominator]}]
                       (* (bigint numerator)
                          (/ (bigint common-denominator) (bigint denominator))))
                     reduced)
        sum (reduce + 0 scaled)]
    {:common-denominator common-denominator
     :scaled-numerators scaled
     :sum sum}))

(defn rates-sum-to-one?
  "True when the proposed rates sum exactly to one under the common denominator."
  [rates]
  (let [{:keys [common-denominator sum]} (ratio-sum-common rates)]
    (= sum common-denominator)))

(defn expected-allocation-numerators
  "For each claimant in canonical order, the exact expected-allocation numerator
   under the common rate denominator D:

     E-num_i = sum_j (scaled numerator of outcome j) * allocation_i_j

   Returns a vector of bigint numerators parallel to :claimants."
  [context]
  (let [{:keys [claimants outcomes proposed-rates]} context
        {D :common-denominator scaled :scaled-numerators} (ratio-sum-common proposed-rates)
        outcome-scaled (mapv (fn [outcome s]
                               {:allocations (:allocations outcome) :scaled s})
                             outcomes scaled)]
    {:common-denominator D
     :numerators
     (mapv (fn [claimant]
             (let [cid (:claim/id claimant)]
               (reduce + 0
                       (map (fn [{:keys [allocations scaled]}]
                              (let [alloc (first (filter #(= cid (:claim/id %)) allocations))]
                                (* scaled (bigint (or (:allocated alloc) 0)))))
                            outcome-scaled))))
           claimants)}))

(defn build-rate-derived-summary
  "Derive the rate-derived summary from the context. Every value is recomputed
   from source claims, outcomes, and rates; no derived totals are accepted."
  [context]
  (let [{:keys [claimants outcomes proposed-rates capacity total-eligible-weight]} context
        {D :common-denominator scaled :scaled-numerators sum :sum} (ratio-sum-common proposed-rates)
        numerators (:numerators (expected-allocation-numerators context))]
    {:common-rate-denominator D
     :scaled-rate-numerators
     (mapv (fn [o s] {:outcome/id (:outcome/id o) :numerator s})
           outcomes scaled)
     :expected-allocations
     (mapv (fn [claimant e]
             (let [w (bigint (:weight claimant))]
               {:claim/id (:claim/id claimant)
                :expected-allocation-numerator e
                :expected-allocation-denominator D
                :exact-pro-rata-numerator (* (bigint capacity) w)
                :exact-pro-rata-denominator (bigint total-eligible-weight)}))
           claimants numerators)
     :rates-sum sum
     :rates-sum-to-one? (= D sum)}))

(defn rate-derived-summary-hash
  "Domain-separated hash of the rate-derived summary."
  [context]
  (hc/domain-hash :rate-derived-summary
                  {:schema-version "rate-derived-summary.v1"
                   :summary (build-rate-derived-summary context)}))

;; ──────────────────────────────────────────────────────────────────────────────
;; Structural proposal checks
;; ──────────────────────────────────────────────────────────────────────────────

(defn outcomes-eligible-only?
  "Every claim referenced by every outcome must be an admitted claimant."
  [context]
  (let [claimant-ids (set (map :claim/id (:claimants context)))]
    (every? (fn [outcome]
              (every? #(contains? claimant-ids (:claim/id %))
                      (:allocations outcome)))
            (:outcomes context))))

(defn outcomes-no-duplicate-claims?
  "No claim may appear twice within a single outcome."
  [context]
  (every? (fn [outcome]
            (let [ids (map :claim/id (:allocations outcome))]
              (= (count ids) (count (distinct ids)))))
          (:outcomes context)))

(defn outcomes-all-or-nothing?
  "Every claimant allocation in every outcome is either zero or the claimant's
   complete admitted amount (all-or-nothing)."
  [context]
  (let [amount-by-claim (into {} (map (juxt :claim/id :amount)) (:claimants context))]
    (every? (fn [outcome]
              (every? (fn [entry]
                        (let [cid (:claim/id entry)
                              amount (amount-by-claim cid)
                              allocated (bigint (:allocated entry))]
                          (or (zero? allocated)
                              (and (some? amount) (= allocated amount)))))
                      (:allocations outcome)))
            (:outcomes context))))

(defn outcomes-exact-capacity?
  "Every outcome allocates exactly the committed capacity; no residual capacity
   is admitted in any outcome."
  [context]
  (let [capacity (bigint (:capacity context))]
    (every? (fn [outcome]
              (= capacity
                 (reduce + 0 (map (comp bigint :allocated) (:allocations outcome)))))
            (:outcomes context))))

(defn proportional-proposed?
  "Exact cross-product proportionality check for every claimant:

     E-num_i * W = C * w_i * D

   where E-num_i is the expected-allocation numerator under common rate
   denominator D, W is total eligible weight, C is capacity, and w_i is the
   claimant weight. No rounded division is used."
  [context]
  (let [W (bigint (:total-eligible-weight context))
        C (bigint (:capacity context))
        {D :common-denominator} (ratio-sum-common (:proposed-rates context))
        numerators (:numerators (expected-allocation-numerators context))
        weights (mapv :weight (:claimants context))]
    (every? (fn [[e-num w]]
              (= (* e-num W) (* C w D)))
            (map vector numerators weights))))

(defn rates-canonical-exact?
  "Every proposed rate must be a canonical exact ratio (reduced, non-negative
   numerator, positive denominator, zero as 0/1)."
  [context]
  (every? (fn [rate-entry] (ratio-normalised? (:rate rate-entry)))
          (:proposed-rates context)))
