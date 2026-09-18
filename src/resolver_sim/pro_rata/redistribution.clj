(ns resolver-sim.pro-rata.redistribution
  "Canonical pro-rata cap redistribution semantics.

   Owns the redistribution allocator: rows that cannot receive their current
   exact quota because of a cap are committed at that cap, removed, and the
   remaining availability is recomputed over the remaining weighted rows.
   Integer largest-remainder rounding occurs only in the final unconstrained
   group.

   This namespace is part of the pro-rata semantic closure. It builds on the
   single-pass engine in resolver-sim.pro-rata.engine and must never depend
   on protocol, runner, research, application, or generic-economics allocator
   namespaces."
  (:require [resolver-sim.execution.realization :as realization]
            [resolver-sim.pro-rata.engine :as engine]
            [resolver-sim.pro-rata.progress :as progress]))

(def ^:dynamic *redistribution-claimant-hook*
  "Test/runtime-only hook invoked during detached active-set claimant fact
   determination. Never read by canonical allocation/evidence code."
  nil)

(def ^:dynamic *redistribution-claimant-determination-hook*
  "Runtime-only test instrumentation invoked before an active-set redistribution
   round determines claimant-local facts. It is excluded from canonical
   allocation requests, decisions, evidence, and package projections."
  nil)

(defn- allocations-in-input-order
  [items id-fn allocations]
  (let [by-id (into {} (map (juxt :id identity) allocations))]
    (mapv #(get by-id (id-fn %)) items)))

(defn- allocate-pro-rata-with-redistribution*
  "Unscoped semantic implementation for cap redistribution.

   Rows that cannot receive their current exact quota because of a cap are
   committed at that cap, removed, and the remaining availability is recomputed
   over the remaining weighted rows. Integer largest-remainder rounding occurs
   only in the final unconstrained group."
  [{:keys [amount items id-fn weight-fn cap-fn rounding ordering-policy progress-atom on-progress parallelism execution/quiescence-timeout-seconds]
    :or {id-fn :id weight-fn :weight cap-fn :cap
         rounding :floor-with-largest-remainder ordering-policy :input-order}}]
  (let [amount (engine/non-negative-integer amount)
        items (vec (or items []))
        observer (or on-progress progress-atom)
        item-id (fn [item] (id-fn item))
        cap-of (fn [item]
                 (when-let [cap (cap-fn item)]
                   (engine/non-negative-integer cap)))
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
      (let [weight-total (reduce +' 0 (map #(engine/non-negative-integer (weight-fn %)) active))]
        (cond
          (empty? active)
          (let [;; Every active item was committed at its cap. The residual was
                ;; claimed by those caps but could not be satisfied, so it is
                ;; unmet, not unallocatable remainder. Attribute that shortfall
                ;; to the committed rows pro-rata by weight (largest remainder)
                ;; so per-row :unmet sums exactly to the aggregate shortfall and
                ;; no obligation vanishes from the committed rows.
                share-allocation (engine/allocate-pro-rata*
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
          (let [claimant-parallelism (engine/effective-claimant-parallelism parallelism (count active) observer)
                _ (when *redistribution-claimant-determination-hook*
                    (*redistribution-claimant-determination-hook*
                     {:active-count (count active)
                      :parallelism claimant-parallelism
                      :redistribution-pass round-index}))
                ;; Detached facts are computed from this immutable round snapshot.
                ;; The coordinator alone commits caps, changes remaining liquidity,
                ;; and derives the next active set below.
                round-facts (engine/ordered-detached-mapv
                             claimant-parallelism quiescence-timeout-seconds
                             (fn [item]
                               (when *redistribution-claimant-hook*
                                 (*redistribution-claimant-hook* item))
                               (let [id (item-id item)
                                     weight (engine/non-negative-integer (weight-fn item))
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
                (progress/report-pro-rata-progress! observer
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
                                                :weight (engine/non-negative-integer (weight-fn item))
                                                :cap (cap-of item)}]) capped)))
                       (conj passes (assoc pass :committed-by-cap committed-amount
                                           :available-after-caps (- remaining committed-amount)))))
              (let [final-result (engine/allocate-pro-rata*
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

(defn allocate-pro-rata-with-redistribution [request]
  (let [state (realization/open)]
    (binding [realization/*claimant-execution-realization* state]
      (let [result (allocate-pro-rata-with-redistribution* request)]
        (realization/complete! state)
        result))))