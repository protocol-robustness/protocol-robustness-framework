(ns resolver-sim.pro-rata.invariants
  "Independent structural and arithmetic checks for pro-rata result witnesses.
   These validators inspect persisted witnesses and never invoke the allocator."
  (:require [clojure.set]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.pro-rata.allocation :as allocation]))

(defn cap-respecting-violations
  [result]
  (vec
   (mapcat (fn [{:keys [row/id requested effective-cap allocated]}]
             (cond-> []
               (or (not (integer? allocated)) (neg? allocated))
               (conj {:reason :pro-rata/invalid-allocation :row/id id :observed allocated})
               (> allocated effective-cap)
               (conj {:reason :pro-rata/cap-exceeded :row/id id
                      :expected effective-cap :observed allocated})
               (> allocated requested)
               (conj {:reason :pro-rata/request-exceeded :row/id id
                      :expected requested :observed allocated})))
           (:rows result))))

(defn quota-bounded-violations
  [result]
  (vec
   (keep (fn [{:keys [row/id effective-cap effective-quota allocated]}]
           (let [numerator (:quota/numerator effective-quota)
                 denominator (:quota/denominator effective-quota)
                 bounded-numerator (min numerator (* effective-cap denominator))
                 floor-value (quot bounded-numerator denominator)
                 ceiling-value (if (zero? (mod bounded-numerator denominator))
                                 floor-value (inc floor-value))]
             (when (or (< allocated floor-value) (> allocated ceiling-value))
               {:reason :pro-rata/quota-bounded-failed
                :row/id id
                :expected {:minimum floor-value :maximum ceiling-value}
                :observed allocated})))
         (:rows result))))

(defn canonical-remainder-assignment-violations
  [result]
  (if (not= :largest-remainder (:rounding-policy result))
    [{:reason :pro-rata/rounding-policy-not-largest-remainder
      :result :not-exercised}]
    (let [eligible (->> (:rows result)
                        (filter #(some? (:remainder-rank %)))
                        (sort-by :remainder-rank)
                        vec)
          rank-violations (keep-indexed
                           (fn [rank row]
                             (when (not= rank (:remainder-rank row))
                               {:reason :pro-rata/remainder-rank-mismatch
                                :row/id (:row/id row)
                                :expected rank :observed (:remainder-rank row)}))
                           eligible)
          award-violations (keep (fn [row]
                                   (let [expected (+ (:floor-allocation row)
                                                     (if (:remainder-unit-awarded? row) 1 0))]
                                     (when (not= expected (:allocated row))
                                       {:reason :pro-rata/remainder-award-mismatch
                                        :row/id (:row/id row)
                                        :expected expected :observed (:allocated row)})))
                                 eligible)
          rank-order (map :row/id eligible)
          expected-order (map :row/id
                              (sort (fn [left right]
                                      (let [ln (get-in left [:fractional-remainder :remainder-numerator])
                                            ld (get-in left [:fractional-remainder :remainder-denominator])
                                            rn (get-in right [:fractional-remainder :remainder-numerator])
                                            rd (get-in right [:fractional-remainder :remainder-denominator])
                                            comparison (compare (* rn ld) (* ln rd))]
                                        (if (zero? comparison)
                                          (compare (allocation/canonical-id-key (:row/id left))
                                                   (allocation/canonical-id-key (:row/id right)))
                                          comparison)))
                                    eligible))]
      (vec (concat rank-violations
                   award-violations
                   (when (not= rank-order expected-order)
                     [{:reason :pro-rata/remainder-order-mismatch
                       :expected (vec expected-order)
                       :observed (vec rank-order)}]))))))

(defn round-trace-violations
  "Verify that the persisted redistribution trace is a coherent mathematical
   explanation of the final row witness, without rerunning allocation."
  [result]
  (let [rows-by-id (into {} (map (juxt :row/id identity) (:rows result)))
        rounds (vec (:allocation/rounds result))
        continuity (map vector rounds (rest rounds))
        round-errors
        (mapcat (fn [round]
                  (let [active (:active-row-ids round)
                        weight (reduce + 0 (map #(get-in rows-by-id [% :weight] 0) active))
                        committed (:committed-by-cap round 0)
                        start (:available-at-start round)
                        after (:available-after-caps round)]
                    (cond-> []
                      (not= weight (:active-weight-total round))
                      (conj {:reason :pro-rata/active-weight-mismatch
                             :round/index (:round/index round)
                             :expected weight :observed (:active-weight-total round)})
                      (not= start (+ committed after))
                      (conj {:reason :pro-rata/round-conservation-failed
                             :round/index (:round/index round)
                             :expected start :observed (+ committed after)})
                      (some (fn [id]
                              (let [row (get rows-by-id id)
                                    quota (some #(when (= id (:row/id %)) %) (:quotas round))]
                                (or (not= (:allocated row) (:effective-cap row))
                                    (nil? quota)
                                    (< (:quota/numerator quota)
                                       (* (:effective-cap row) (:quota/denominator quota))))))
                            (:newly-cap-constrained-row-ids round))
                      (conj {:reason :pro-rata/cap-commitment-mismatch
                             :round/index (:round/index round)}))))
                rounds)
        continuity-errors
        (mapcat (fn [[prior next]]
                  (let [removed (clojure.set/difference (set (:active-row-ids prior))
                                                        (set (:active-row-ids next)))]
                    (cond-> []
                      (not= (:available-after-caps prior) (:available-at-start next))
                      (conj {:reason :pro-rata/round-continuity-failed
                             :prior-round (:round/index prior)
                             :next-round (:round/index next)
                             :expected (:available-after-caps prior)
                             :observed (:available-at-start next)})
                      (not (every? (set (:newly-cap-constrained-row-ids prior)) removed))
                      (conj {:reason :pro-rata/active-set-monotonicity-failed
                             :round/index (:round/index prior)
                             :removed removed
                             :cap-committed (:newly-cap-constrained-row-ids prior)}))))
                continuity)
        ;; A final round may commit every remaining row at its cap. Such rows
        ;; are not a rounding group and therefore correctly have no remainder
        ;; witness; compare only the rows that remain active after that round's
        ;; cap commitments.
        final-round (last rounds)
        final-active (clojure.set/difference (set (:active-row-ids final-round))
                                             (set (:newly-cap-constrained-row-ids final-round)))
        witness-active (set (map :row/id (filter #(some? (:remainder-rank %)) (:rows result))))]
    (vec (concat round-errors continuity-errors
                 (when (and (seq rounds) (not= final-active witness-active))
                   [{:reason :pro-rata/final-round-binding-mismatch
                     :expected final-active :observed witness-active}])))))

(defn residual-violations
  "Validate that a non-zero residual has a declared, mechanically provable
   cause. Residual is availability that the mechanism did not allocate; cap
   shortfall recorded in participant unmet amounts is intentionally distinct."
  [result]
  (let [residual (:unallocated-residual result)
        reason (:residual-reason result)
        rows (:rows result)
        total-weight (reduce + 0 (map :weight rows))]
    (cond
      (or (not (integer? residual)) (neg? residual))
      [{:reason :pro-rata/invalid-unallocated-residual :observed residual}]

      (zero? residual)
      (if (= :none reason)
        []
        [{:reason :pro-rata/unexpected-residual-reason
          :expected :none :observed reason}])

      (= reason :no-remaining-capacity)
      (if (empty? rows)
        []
        [{:reason :pro-rata/residual-reason-not-established
          :residual-reason reason :expected :no-rows}])

      (= reason :all-participants-capped)
      (if (and (seq rows)
               (every? #(= (:allocated %) (:effective-cap %)) rows))
        []
        [{:reason :pro-rata/residual-reason-not-established
          :residual-reason reason :expected :all-rows-at-effective-cap}])

      (= reason :no-active-weight)
      (if (zero? total-weight)
        []
        [{:reason :pro-rata/residual-reason-not-established
          :residual-reason reason :expected :zero-total-weight
          :observed total-weight}])

      (= reason :floor-rounding)
      (if (= :floor (:rounding-policy result))
        []
        [{:reason :pro-rata/residual-reason-not-established
          :residual-reason reason :expected :floor-rounding-policy
          :observed (:rounding-policy result)}])

      :else
      [{:reason :pro-rata/unsupported-residual-reason
        :observed reason}])) )

(defn result-violations
  [result]
  (vec (concat (when-not (= (:request/hash result)
                            (hc/hash-with-intent {:hash/intent :projection-artifact}
                                                 (:canonical-request result)))
                 [{:reason :pro-rata/request-hash-mismatch
                   :expected :recomputed-hash
                   :observed (:request/hash result)}])
               (when-not (allocation/allocation-hash-valid? result)
                 [{:reason :pro-rata/allocation-hash-mismatch
                   :expected :recomputed-hash
                   :observed (:allocation/hash result)}])
               (cap-respecting-violations result)
               (residual-violations result)
               (round-trace-violations result)
               (quota-bounded-violations result)
               (when (= :largest-remainder (:rounding-policy result))
                 (canonical-remainder-assignment-violations result)))))

(defn remainder-assignment-status
  [result]
  (if (= :largest-remainder (:rounding-policy result))
    {:result :exercised
     :violations (canonical-remainder-assignment-violations result)}
    {:result :not-exercised
     :reason :rounding-policy-not-largest-remainder
     :violations []}))
