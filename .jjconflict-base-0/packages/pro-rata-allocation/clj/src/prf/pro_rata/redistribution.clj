(ns prf.pro-rata.redistribution
  "Active-set cap redistribution with explicit runtime execution callbacks."
  (:require [prf.pro-rata.kernel :as kernel]
            [prf.pro-rata.single-pass :as single-pass]))

(defn- allocations-in-input-order
  [items id-fn allocations]
  (let [by-id (into {} (map (juxt :id identity) allocations))]
    (mapv #(get by-id (id-fn %)) items)))

(defn allocate
  "Unscoped semantic implementation for cap redistribution.

   Rows that cannot receive their current exact quota because of a cap are
   committed at that cap, removed, and the remaining availability is recomputed
   over the remaining weighted rows. Integer largest-remainder rounding occurs
   only in the final unconstrained group.

   Runtime callbacks have the same contracts as single-pass/allocate.
   Optional :claimant-hook receives each item inside the ordered mapper;
   :claimant-determination-hook receives round metadata on the coordinator.
   Hooks are instrumentation only and are excluded from allocation artifacts."
  [{:keys [amount items id-fn weight-fn cap-fn rounding ordering-policy progress-atom on-progress parallelism execution/quiescence-timeout-seconds]
    :or {id-fn :id weight-fn :weight cap-fn :cap
         rounding :floor-with-largest-remainder ordering-policy :input-order}}
   {:keys [effective-claimant-parallelism ordered-detached-mapv report-progress!
           claimant-hook claimant-determination-hook] :as runtime}]
  (let [allocate-single-pass #(single-pass/allocate % runtime)
        amount (kernel/non-negative-integer amount)
        items (vec (or items []))
        observer (or on-progress progress-atom)
        item-id (fn [item] (id-fn item))
        cap-of (fn [item]
                 (when-let [cap (cap-fn item)]
                   (kernel/non-negative-integer cap)))
        max-redistribution-rounds (count items)]
    (loop [round-index 0
           remaining amount
           active items
           committed {}
           passes []]
      (when (> round-index max-redistribution-rounds)
        (throw (ex-info "Pro-rata redistribution exceeded active claimant bound"
                        {:reason :redistribution-round-bound-exceeded
                         :round-index round-index
                         :initial-active-count max-redistribution-rounds})))
      (let [weight-total (reduce +' 0 (map #(kernel/non-negative-integer (weight-fn %)) active))]
        (cond
          (empty? active)
          (let [;; Every active item was committed at its cap. The residual was
                ;; claimed by those caps but could not be satisfied, so it is
                ;; unmet, not unallocatable remainder. Attribute that shortfall
                ;; to the committed rows pro-rata by weight (largest remainder)
                ;; so per-row :unmet sums exactly to the aggregate shortfall and
                ;; no obligation vanishes from the committed rows.
                share-allocation (allocate-single-pass
                                  {:amount remaining
                                   :items (vals committed)
                                   :id-fn :id :weight-fn :weight
                                   :cap-fn (constantly nil)
                                   :rounding :floor-with-largest-remainder
                                   :remainder-policy :unallocated
                                   :ordering-policy ordering-policy
                                   :on-progress observer})
                unmet-by-id (into {} (map (juxt :id :allocated))
                                  (:allocations share-allocation))
                committed (into {} (map (fn [[id entry]]
                                          [id (assoc entry :unmet (get unmet-by-id id 0))])
                                        committed))
                allocations (allocations-in-input-order items id-fn (vals committed))
                total-allocated (reduce +' 0 (map :allocated allocations))
                ;; Only report a redistribution when some pass left uncapped
                ;; survivors (i.e. excess actually flowed between rounds); a
                ;; single all-capped pass is not a redistribution.
                redistributed? (some #(not= (:active-ids %) (:capped-ids %)) passes)]
            {:allocations allocations :total-requested amount
             :total-allocated total-allocated :total-unmet remaining :remainder 0
             :policy {:rounding rounding :remainder-policy :unallocated
                      :ordering-policy ordering-policy :total-weight weight-total}
             :redistribution (when redistributed?
                               {:passes passes :total-passes round-index
                                :residual-reason :no-remaining-capacity})})

          (zero? weight-total)
          (let [all-rows (merge committed
                                (into {} (map (fn [item]
                                                [(item-id item)
                                                 {:id (item-id item) :allocated 0 :unmet 0
                                                  :weight 0 :cap (cap-of item)}]) active)))
                allocations (allocations-in-input-order items id-fn (vals all-rows))
                total-allocated (reduce +' 0 (map :allocated allocations))]
            {:allocations allocations :total-requested amount
             :total-allocated total-allocated :total-unmet 0 :remainder remaining
             :policy {:rounding rounding :remainder-policy :unallocated
                      :ordering-policy ordering-policy :total-weight 0}
             :redistribution {:passes passes :total-passes round-index
                              :residual-reason :no-active-weight}})

          :else
          (let [claimant-parallelism (effective-claimant-parallelism parallelism (count active) observer)
                _ (when claimant-determination-hook
                    (claimant-determination-hook
                     {:active-count (count active)
                      :parallelism claimant-parallelism
                      :redistribution-pass round-index}))
                ;; Detached facts are computed from this immutable round snapshot.
                ;; The coordinator alone commits caps, changes remaining liquidity,
                ;; and derives the next active set below.
                round-facts (ordered-detached-mapv
                             claimant-parallelism quiescence-timeout-seconds
                             (fn [item]
                               (when claimant-hook
                                 (claimant-hook item))
                               (let [id (item-id item)
                                     weight (kernel/non-negative-integer (weight-fn item))
                                     cap (cap-of item)]
                                 {:item item
                                  :id id
                                  :weight weight
                                  :cap cap
                                  :cap-constrained?
                                  (and (some? cap)
                                       (>= (* remaining weight) (* cap weight-total)))}))
                             active)
                capped-facts (filterv :cap-constrained? round-facts)
                capped (mapv :item capped-facts)
                pass {:pass round-index
                      :available-at-start remaining
                      :active-ids (mapv item-id active)
                      :active-weight-total weight-total
                      ;; Compatibility aliases retained for historical evidence readers.
                      :capped-ids (mapv item-id capped)
                      :excess remaining
                      :newly-capped-ids (mapv item-id capped)}]
            (if (seq capped)
              (let [committed-amount (reduce +' 0 (map cap-of capped))
                    capped-ids (set (map item-id capped))
                    next-active (vec (remove #(contains? capped-ids (item-id %)) active))]
                (when-not (< (count next-active) (count active))
                  (throw (ex-info "Pro-rata redistribution round made no active-set progress"
                                  {:reason :redistribution-non-progress
                                   :round-index round-index
                                   :active-ids (mapv item-id active)
                                   :constrained-ids (mapv item-id capped)})))
                (report-progress! observer
                                  {:event :redistribution-started
                                   :status :running
                                   :phase :redistributing
                                   :pass-index round-index})
                (recur (inc round-index)
                       (- remaining committed-amount)
                       next-active
                       (merge committed
                              (into {} (map (fn [item]
                                              [(item-id item)
                                               {:id (item-id item)
                                                :allocated (cap-of item)
                                                :unmet 0
                                                :weight (kernel/non-negative-integer (weight-fn item))
                                                :cap (cap-of item)}]) capped)))
                       (conj passes (assoc pass :committed-by-cap committed-amount
                                           :available-after-caps (- remaining committed-amount)))))
              (let [final-result (allocate-single-pass
                                  {:amount remaining :items active :id-fn id-fn
                                   :weight-fn weight-fn :cap-fn cap-fn :rounding rounding
                                   :remainder-policy :unallocated
                                   :ordering-policy ordering-policy
                                   :on-progress observer})
                    final-map (into {} (map (juxt :id identity) (:allocations final-result)))
                    merged (merge committed final-map)
                    allocations (allocations-in-input-order items id-fn (vals merged))
                    total-allocated (reduce +' 0 (map :allocated allocations))]
                {:allocations allocations :total-requested amount
                 :total-allocated total-allocated :total-unmet (:total-unmet final-result)
                 :remainder (:remainder final-result)
                 :policy (:policy final-result)
                 :redistribution {:passes (conj passes (assoc pass
                                                              :committed-by-cap 0
                                                              :available-after-caps remaining))
                                  :total-passes (inc round-index)
                                  :residual-reason (if (pos? (:remainder final-result))
                                                     (if (= rounding :floor) :floor-rounding :unallocated)
                                                     :none)}}))))))))

