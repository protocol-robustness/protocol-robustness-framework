(ns prf.pro-rata.witness
  "Pure mathematical quota and cap-redistribution witness construction."
  (:require [prf.pro-rata.rows :as allocation-rows]))

(defn- compare-fractions-desc
  [left right]
  (let [comparison (compare (* (:remainder-numerator right) (:remainder-denominator left))
                            (* (:remainder-numerator left) (:remainder-denominator right)))]
    (if (zero? comparison)
      (compare (allocation-rows/canonical-id-key (:row/id left)) (allocation-rows/canonical-id-key (:row/id right)))
      comparison)))

(defn- witness-round
  [round-index available active]
  (let [weight-total (reduce + 0 (map :weight active))]
    {:round/index round-index
     :available-at-start available
     :active-row-ids (mapv :row/id active)
     :active-weight-total weight-total
     :quotas (mapv (fn [row]
                     {:row/id (:row/id row)
                      :quota/numerator (* available (:weight row))
                      :quota/denominator weight-total})
                   active)}))

(defn- redistribution-witness
  "Construct a mathematical cap-redistribution witness. Rounding is performed
   once over the final active group; cap-constrained rows are committed before
   that final rounding group is formed."
  [available rows rounding-policy redistribution-policy]
  (loop [round-index 0
         remaining available
         active rows
         committed {}
         rounds []]
    (let [weight-total (reduce + 0 (map :weight active))]
      (cond
        (empty? active)
        {:rounds rounds :committed committed :active []
         :remaining remaining
         :residual-reason (if (seq rows) :all-participants-capped :no-remaining-capacity)}

        (zero? weight-total)
        {:rounds rounds :committed committed :active active
         :remaining remaining :residual-reason :no-active-weight}

        :else
        (let [round (witness-round round-index remaining active)
              cap-constrained
              (filterv (fn [row]
                         (>= (* remaining (:weight row))
                             (* (:effective-cap row) weight-total)))
                       active)
              can-redistribute? (and (= redistribution-policy :redistribute-cap-excess)
                                     (seq cap-constrained))]
          (if can-redistribute?
            (let [committed-amount (reduce + 0 (map :effective-cap cap-constrained))
                  constrained-ids (set (map :row/id cap-constrained))
                  round (assoc round
                               :newly-cap-constrained-row-ids (mapv :row/id cap-constrained)
                               :committed-by-cap committed-amount
                               :available-after-caps (- remaining committed-amount))]
              (recur (inc round-index)
                     (- remaining committed-amount)
                     (vec (remove #(contains? constrained-ids (:row/id %)) active))
                     (merge committed (into {} (map (fn [row]
                                                      [(:row/id row) (:effective-cap row)])
                                                    cap-constrained)))
                     (conj rounds round)))
            {:rounds (conj rounds (assoc round
                                         :newly-cap-constrained-row-ids []
                                         :committed-by-cap 0
                                         :available-after-caps remaining))
             :committed committed
             :active active
             :remaining remaining
             :residual-reason (if (= rounding-policy :floor) :floor-rounding :none)}))))))

(defn- unallocated-witness
  "Mathematical witness for the :unallocated redistribution policy.

   The allocator clamps every row's uncapped quota to its effective cap and
   records the clamped share as per-row :unmet; nothing is redistributed. The
   witness therefore commits cap-constrained rows at their effective cap in a
   single round and derives the quota of unconstrained rows from the FULL
   available quantity and full weight total (not the post-cap remainder), so
   the persisted remainder/floor witness agrees with the capped allocation."
  [available rows rounding-policy]
  (let [weight-total (reduce + 0 (map :weight rows))
        no-capacity? (or (empty? rows) (zero? weight-total))
        residual-reason (cond
                          (empty? rows) :no-remaining-capacity
                          (zero? weight-total) :no-active-weight
                          (= rounding-policy :floor) :floor-rounding
                          :else :none)]
    (if no-capacity?
      {:allocation/rounds []
       :residual-reason residual-reason
       :committed {}
       :active rows
       :remaining available
       :rows (mapv (fn [row]
                     (let [denominator (max 1 weight-total)
                           quota {:quota/numerator (* available (:weight row))
                                  :quota/denominator denominator}]
                       (assoc row
                              :initial-quota quota
                              :effective-quota quota
                              :floor-allocation 0
                              :fractional-remainder {:remainder-numerator 0
                                                     :remainder-denominator denominator}
                              :remainder-rank nil
                              :remainder-unit-awarded? false)))
                   rows)}
      (let [cap-constrained (filterv (fn [row]
                                       (>= (* available (:weight row))
                                           (* (:effective-cap row) weight-total)))
                                     rows)
            constrained-ids (set (map :row/id cap-constrained))
            committed-amount (reduce + 0 (map :effective-cap cap-constrained))
            round (assoc (witness-round 0 available rows)
                         :newly-cap-constrained-row-ids (mapv :row/id cap-constrained)
                         :committed-by-cap committed-amount
                         :available-after-caps (- available committed-amount))
            floors (into {}
                         (map (fn [row]
                                [(:row/id row) (quot (* available (:weight row)) weight-total)]))
                         rows)
            remainder-rows (mapv (fn [row]
                                   {:row/id (:row/id row)
                                    :remainder-numerator (mod (* available (:weight row)) weight-total)
                                    :remainder-denominator weight-total})
                                 rows)
            remainder-units (if (= rounding-policy :largest-remainder)
                              (- available (reduce + 0 (vals floors)))
                              0)
            awarded-ids (set (map :row/id (take remainder-units
                                                (sort compare-fractions-desc remainder-rows))))
            ranks (zipmap (map :row/id
                               (sort compare-fractions-desc
                                     (remove #(contains? constrained-ids (:row/id %))
                                             remainder-rows)))
                          (range))]
        {:allocation/rounds [round]
         :residual-reason residual-reason
         :committed (into {} (map (fn [row]
                                    [(:row/id row) (:effective-cap row)])
                                  cap-constrained))
         :active (vec (remove #(contains? constrained-ids (:row/id %)) rows))
         :remaining available
         :rows (mapv (fn [row]
                       (let [id (:row/id row)
                             committed? (contains? constrained-ids id)
                             quota {:quota/numerator (* available (:weight row))
                                    :quota/denominator weight-total}
                             floor-allocation (if committed? (:effective-cap row) (get floors id 0))
                             awarded? (and (not committed?) (contains? awarded-ids id))]
                         (assoc row
                                :initial-quota quota
                                :effective-quota quota
                                :floor-allocation floor-allocation
                                :fractional-remainder {:remainder-numerator (if committed?
                                                                              0
                                                                              (mod (:quota/numerator quota) weight-total))
                                                       :remainder-denominator weight-total}
                                :remainder-rank (when-not committed? (get ranks id))
                                :remainder-unit-awarded? awarded?)))
                     rows)}))))

(defn witness-rows
  [available rows rounding-policy redistribution-policy]
  (if (= redistribution-policy :unallocated)
    (unallocated-witness available rows rounding-policy)
    (let [{:keys [rounds committed active remaining residual-reason]}
          (redistribution-witness available rows rounding-policy redistribution-policy)
          weight-total (reduce + 0 (map :weight active))
          final-quota (fn [row]
                        {:quota/numerator (* remaining (:weight row))
                         :quota/denominator (if (pos? weight-total) weight-total 1)})
          floors (into {}
                       (map (fn [row]
                              [(:row/id row) (if (pos? weight-total)
                                               (quot (* remaining (:weight row)) weight-total)
                                               0)])
                            active))
          remainder-rows (mapv (fn [row]
                                 (let [numerator (* remaining (:weight row))
                                       denominator (if (pos? weight-total) weight-total 1)]
                                   {:row/id (:row/id row)
                                    :remainder-numerator (mod numerator denominator)
                                    :remainder-denominator denominator}))
                               active)
          remainder-units (if (= rounding-policy :largest-remainder)
                            (- remaining (reduce + 0 (vals floors)))
                            0)
          awarded-ids (set (map :row/id (take remainder-units
                                              (sort compare-fractions-desc remainder-rows))))
          ranks (zipmap (map :row/id (sort compare-fractions-desc remainder-rows)) (range))]
      {:allocation/rounds rounds
       :residual-reason residual-reason
       :rows (mapv (fn [row]
                     (let [id (:row/id row)
                           committed? (contains? committed id)
                           quota (if committed?
                                   (let [round (first (filter #(some #{id} (:newly-cap-constrained-row-ids %)) rounds))
                                         entry (first (filter #(= id (:row/id %)) (:quotas round)))]
                                     (select-keys entry [:quota/numerator :quota/denominator]))
                                   (final-quota row))
                           floor-allocation (if committed? (:effective-cap row) (get floors id 0))
                           awarded? (and (not committed?) (contains? awarded-ids id))]
                       (assoc row
                              :initial-quota {:quota/numerator (* available (:weight row))
                                              :quota/denominator (max 1 (reduce + 0 (map :weight rows)))}
                              :effective-quota quota
                              :floor-allocation floor-allocation
                              :fractional-remainder {:remainder-numerator (if committed? 0 (mod (:quota/numerator quota) (:quota/denominator quota)))
                                                     :remainder-denominator (:quota/denominator quota)}
                              :remainder-rank (when-not committed? (get ranks id))
                              :remainder-unit-awarded? awarded?)))
                   rows)})))

