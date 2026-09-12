(ns resolver-sim.pro-rata.engine
  "Single-pass pro-rata claimant allocation engine.

   Shared foundation for the pro-rata semantic closure: the unscoped single-pass
   allocation computation and the detached claimant parallel machinery. Both
   resolver-sim.pro-rata.allocation (single-pass semantics) and
   resolver-sim.pro-rata.redistribution (cap-redistribution semantics) build on
   this namespace; it owns no public mechanism contract of its own.

   Part of the pro-rata semantic closure: must never depend on protocol, runner,
   research, application, or generic-economics allocator namespaces."
  (:require [resolver-sim.config.defaults :as config-defaults]
            [resolver-sim.execution.budget :as budget]
            [resolver-sim.execution.realization :as realization]
            [resolver-sim.pro-rata.progress :as progress]
            [resolver-sim.util.thread-quiescence :as quiesce])
  (:import [java.util.concurrent Callable Executors]))

(defn non-negative-integer
  "Coerce a value to a non-negative bigint; nil becomes 0 and fractional or
   negative-hostile values fail explicitly."
  [x]
  (let [x (or x 0)]
    (if (integer? x)
      (max 0 (bigint x))
      (throw (ex-info "Expected an integer amount" {:value x})))))

(def ^:dynamic *pro-rata-parallelism*
  "Runtime-only claimant worker budget. It is deliberately absent from every
   canonical request, decision, evidence, and proof projection. Defaults to the
   value in config/defaults.edn (:hardening :pro-rata-parallelism)."
  (config-defaults/default [:hardening :pro-rata-parallelism] 1))

(def ^:dynamic *pro-rata-parallel-threshold*
  "Minimum claimant count at which detached claimant determination may use the
   runtime worker budget. Small inputs retain the identical map/reduce path but
   execute serially to avoid executor overhead. Root default is centralised in
   config/defaults.edn (:hardening :claimant-parallel-threshold)."
  (config-defaults/default [:hardening :claimant-parallel-threshold] 16))

(defn effective-claimant-parallelism
  [requested item-count observer]
  ;; Fine-grained callbacks historically observe per-item progress ordering.
  ;; V1 preserves that compatibility by retaining the serial reference path.
  (let [requested (long (or requested *pro-rata-parallelism* 1))]
    (when-not (pos? requested)
      (throw (ex-info "Pro-rata claimant parallelism must be positive"
                      {:parallelism requested})))
    (let [[effective reason] (cond
                               (= requested 1) [1 :requested-serial]
                               observer [1 :observer-requires-serial]
                               (< item-count *pro-rata-parallel-threshold*) [1 :below-claimant-threshold]
                               :else [requested :parallel-eligible])]
      (realization/record! {:candidate-parallelism effective :reason reason})
      effective)))

(declare run-claimant-tasks! claimant-quiesce!)

(defn ordered-detached-mapv
  "Determine independent claimant-local values on a bounded pool, then return
   them in the source vector's stable order. Exceptions are observed in that
   same order, so a malformed row cannot produce schedule-dependent failures.

   When a shared execution budget is bound, the claimant layer BORROWS spare
   capacity (acquire-many!) instead of creating an independent pool: borrowing as
   many permits as are actually available, or running serially when none remain.
   When no budget is bound, the previous per-call fixed pool is used unchanged."
  ([parallelism f values]
   (ordered-detached-mapv parallelism nil f values))
  ([parallelism quiescence-timeout-seconds f values]
   (let [values (vec values)
         budgeted (budget/current)]
     (if budgeted
       (let [acquired (budget/acquire-many! parallelism)]
         (try
           (do
             (realization/record! {:budget-limited? (< acquired parallelism)
                                   :realized-parallelism acquired})
             (if (< acquired 2)
               (mapv f values)
               (run-claimant-tasks! acquired quiescence-timeout-seconds f values)))
           (finally
             (budget/release-many! acquired))))
       (do
         (realization/record! {:budget-limited? false
                               :realized-parallelism parallelism})
         (if (<= parallelism 1)
           (mapv f values)
           (run-claimant-tasks! parallelism quiescence-timeout-seconds f values)))))))

(defn- run-claimant-tasks!
  "Run claimant-local tasks on a bounded fresh pool with size `parallelism`,
   collecting results in stable order and quiescing authoritatively."
  [parallelism quiescence-timeout-seconds f values]
  (realization/record-executor-dispatch! parallelism)
  (let [executor (Executors/newFixedThreadPool (int parallelism))]
    (try
      (let [futures (mapv (fn [value]
                            (let [task (bound-fn [] (f value))]
                              (.submit executor ^Callable
                                       (reify Callable
                                         (call [_] (task))))))
                          values)]
        (mapv #(.get %) futures))
      (finally
        (claimant-quiesce! executor parallelism quiescence-timeout-seconds)))))

(defn- claimant-quiesce!
  "Authoritatively quiesce a claimant executor, failing closed on non-termination.

   The quiescence timeout is resolved consistently:
     1. explicit execution/quiescence-timeout-seconds from the allocation request;
     2. the canonical config default (config/defaults.edn :hardening
        :quiescence-timeout-seconds, falling back to code constant 30).

   This ensures --quiescence-timeout-seconds config changes flow through the
   claimant shutdown path even when no explicit timeout was carried on the
   request (e.g. direct protocol calls or tests)."
  [executor parallelism quiescence-timeout-seconds]
  (let [timeout (or quiescence-timeout-seconds (quiesce/config-default-timeout-seconds))
        q (quiesce/quiesce-executor! executor timeout)]
    (when-not (= :terminated (:status q))
      (throw (quiesce/quiescence-failed-exception
              "Claimant executor threads did not terminate before pool release"
              {:quiescence/status (:status q)
               :quiescence/remaining-tasks (:remaining-tasks q)
               :quiescence/timeout-seconds timeout
               :executor-parallelism parallelism})))
    (realization/record-quiesced!)))

(defn- pro-rata-requests
  [amount prepared total-weight rounding ordering-policy]
  (let [floors (mapv (fn [{:keys [weight]}]
                       (quot (* amount weight) total-weight))
                     prepared)]
    (case rounding
      :floor
      floors

      :floor-with-largest-remainder
      (let [allocated (reduce +' 0 floors)
            shortage (- amount allocated)
            remainders (mapv (fn [{:keys [idx id weight]}]
                               {:idx idx
                                :id id
                                :remainder (mod (* amount weight) total-weight)})
                             prepared)
            tie-key (case ordering-policy
                      :input-order :idx
                      :canonical-id :id)
            remainder-order (->> remainders
                                 (sort-by (juxt (comp - :remainder) tie-key))
                                 (map :idx)
                                 (take shortage)
                                 set)]
        (mapv (fn [idx allocated]
                (if (contains? remainder-order idx)
                  (inc allocated)
                  allocated))
              (range) floors)))))

(defn allocate-pro-rata*
  "Unscoped semantic implementation for one pro-rata allocation.

   Public entry points establish realization scope around this function.
   Inputs are intentionally generic. Protocol-specific namespaces should adapt
   their domain data into {:id ... :weight ... :cap ...} items before calling.

   Supported policies:
   - :rounding :floor (default) leaves integer dust in :remainder
   - :rounding :floor-with-largest-remainder distributes dust by Hare quota
   - :remainder-policy :unallocated reports capped/unallocated amounts; it does not redistribute
   - :ordering-policy :input-order breaks equal-remainder ties by input order
   - :ordering-policy :canonical-id breaks equal-remainder ties by stable item ID,
     making allocation ownership independent of input order"
  [{:keys [amount items id-fn weight-fn cap-fn rounding remainder-policy ordering-policy
           progress-atom on-progress parallelism execution/quiescence-timeout-seconds]
    :or {id-fn :id
         weight-fn :weight
         cap-fn (constantly nil)
         rounding :floor
         remainder-policy :unallocated
         ordering-policy :input-order}}]
  (when-not (#{:floor :floor-with-largest-remainder} rounding)
    (throw (ex-info "Unsupported pro-rata rounding policy" {:rounding rounding})))
  (when-not (= :unallocated remainder-policy)
    (throw (ex-info "Unsupported pro-rata remainder policy" {:remainder-policy remainder-policy})))
  (when-not (#{:input-order :canonical-id} ordering-policy)
    (throw (ex-info "Unsupported pro-rata ordering policy" {:ordering-policy ordering-policy})))
  (let [progress-observer (or on-progress progress-atom)
        amount (non-negative-integer amount)
        items (vec (or items []))
        total-items (count items)
        claimant-parallelism (effective-claimant-parallelism parallelism total-items progress-observer)
        _ (progress/report-pro-rata-progress! progress-observer
                                              {:event :phase-started
                                               :status :running
                                               :phase :preparing
                                               :current 0
                                               :total total-items})
        prepared (ordered-detached-mapv
                  claimant-parallelism quiescence-timeout-seconds
                  (fn [[idx item]]
                    (let [weight (non-negative-integer (weight-fn item))
                          cap-raw (cap-fn item)
                          cap (when (some? cap-raw)
                                (non-negative-integer cap-raw))]
                      {:idx idx
                       :item item
                       :id (id-fn item)
                       :weight weight
                       :cap cap}))
                  (mapv vector (range) items))
        total-weight (reduce +' 0 (map :weight prepared))
        _ (progress/report-pro-rata-progress! progress-observer {:event :phase-started :phase :requesting})
        requests (if (zero? total-weight)
                   (repeat (count prepared) 0)
                   (pro-rata-requests amount prepared total-weight rounding ordering-policy))
        _ (progress/report-pro-rata-progress! progress-observer {:event :phase-started :phase :allocating})
        allocations (ordered-detached-mapv
                     claimant-parallelism quiescence-timeout-seconds
                     (fn [[{:keys [id weight cap]} requested]]
                       (let [allocated (min requested (or cap requested))
                             unmet (- requested allocated)]
                         ;; Parallel eligibility requires no observer, so this
                         ;; preserves legacy callback sequencing on the serial path.
                         (progress/report-pro-rata-progress! progress-observer
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
    (progress/report-pro-rata-progress! progress-observer
                                        {:event :allocation-completed
                                         :status :completed
                                         :phase :completed})
    result))