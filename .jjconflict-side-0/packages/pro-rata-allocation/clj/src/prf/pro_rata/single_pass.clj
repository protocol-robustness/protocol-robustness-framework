(ns prf.pro-rata.single-pass
  "Single-pass allocation semantics with explicit runtime execution callbacks."
  (:require [prf.pro-rata.kernel :as kernel]))

(defn allocate
  "Run one allocation pass using supplied runtime functions.

   :effective-claimant-parallelism receives requested parallelism, item count,
   and observer; an observer requires serial execution for stable progress.
   :ordered-detached-mapv receives parallelism, timeout, function,
   and values; it must preserve input order and deterministic failure order.
   :report-progress! receives observer and event; observer failures must be
   isolated from allocation semantics by that adapter."
  [{:keys [amount items id-fn weight-fn cap-fn rounding remainder-policy ordering-policy
           progress-atom on-progress parallelism execution/quiescence-timeout-seconds]
    :or {id-fn :id
         weight-fn :weight
         cap-fn (constantly nil)
         rounding :floor
         remainder-policy :unallocated
         ordering-policy :input-order}}
   {:keys [effective-claimant-parallelism ordered-detached-mapv report-progress!]}]
  (when-not (#{:floor :floor-with-largest-remainder} rounding)
    (throw (ex-info "Unsupported pro-rata rounding policy" {:rounding rounding})))
  (when-not (= :unallocated remainder-policy)
    (throw (ex-info "Unsupported pro-rata remainder policy" {:remainder-policy remainder-policy})))
  (when-not (#{:input-order :canonical-id} ordering-policy)
    (throw (ex-info "Unsupported pro-rata ordering policy" {:ordering-policy ordering-policy})))
  (let [progress-observer (or on-progress progress-atom)
        amount (kernel/non-negative-integer amount)
        items (vec (or items []))
        total-items (count items)
        claimant-parallelism (effective-claimant-parallelism parallelism total-items progress-observer)
        _ (report-progress! progress-observer
                            {:event :phase-started
                             :status :running
                             :phase :preparing
                             :current 0
                             :total total-items})
        prepared (ordered-detached-mapv
                  claimant-parallelism quiescence-timeout-seconds
                  (fn [[idx item]]
                    (let [weight (kernel/non-negative-integer (weight-fn item))
                          cap-raw (cap-fn item)
                          cap (when (some? cap-raw)
                                (kernel/non-negative-integer cap-raw))]
                      {:idx idx
                       :item item
                       :id (id-fn item)
                       :weight weight
                       :cap cap}))
                  (mapv vector (range) items))
        total-weight (reduce +' 0 (map :weight prepared))
        _ (report-progress! progress-observer {:event :phase-started :phase :requesting})
        requests (if (zero? total-weight)
                   (repeat (count prepared) 0)
                   (kernel/pro-rata-requests amount prepared total-weight rounding ordering-policy))
        _ (report-progress! progress-observer {:event :phase-started :phase :allocating})
        allocations (ordered-detached-mapv
                     claimant-parallelism quiescence-timeout-seconds
                     (fn [[{:keys [id weight cap]} requested]]
                       (let [allocated (min requested (or cap requested))
                             unmet (- requested allocated)]
                         ;; Parallel eligibility requires no observer, so this
                         ;; preserves legacy callback sequencing on the serial path.
                         (report-progress! progress-observer
                                           {:event :claimants-completed
                                            :delta 1})
                         {:id id
                          :allocated allocated
                          :unmet unmet
                          :weight weight
                          :cap cap}))
                     (mapv vector prepared requests))
        total-allocated (reduce +' 0 (map :allocated allocations))
        total-unmet (reduce +' 0 (map :unmet allocations))
        remainder (- amount total-allocated total-unmet)
        result {:allocations allocations
                :total-requested amount
                :total-allocated total-allocated
                :total-unmet total-unmet
                :remainder remainder
                :policy {:rounding rounding
                         :remainder-policy remainder-policy
                         :ordering-policy ordering-policy
                         :total-weight total-weight}}]
    (report-progress! progress-observer
                      {:event :allocation-completed
                       :status :completed
                       :phase :completed})
    result))
