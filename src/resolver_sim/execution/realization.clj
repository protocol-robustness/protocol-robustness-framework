(ns resolver-sim.execution.realization
  "Private runtime realization state shared by claimant execution helpers.

   This namespace intentionally has no dependency on canonical artifacts or
   allocation code so worker dispatch can report facts without a dependency
   cycle.")

(def ^:dynamic *claimant-execution-observation-sink* nil)
(def ^:dynamic *claimant-execution-runtime-profile-root* nil)
(def ^:dynamic *claimant-execution-realization* nil)

(defn- update-while-open!
  [realization f]
  (loop []
    (let [state @realization]
      (when (= :open (:lifecycle state))
        (let [next-state (f state)]
          (if (compare-and-set! realization state next-state)
            true
            (recur)))))))

(defn record! [fact]
  (when-let [realization *claimant-execution-realization*]
    (update-while-open! realization #(update % :phases conj fact))))

(defn open []
  (atom {:lifecycle :open
         :phases []
         :executor-backed-phases 0
         :successfully-quiesced-phases 0
         :emitted? false}))

(defn record-executor-dispatch! [parallelism]
  (when-let [realization *claimant-execution-realization*]
    (update-while-open! realization
                        #(-> %
                             (update :phases conj {:executor-parallelism parallelism})
                             (update :executor-backed-phases inc)))))

(defn record-quiesced! []
  (when-let [realization *claimant-execution-realization*]
    (update-while-open! realization #(update % :successfully-quiesced-phases inc))))

(defn- effective [state]
  (let [phases (:phases state)
        executor-parallelism (keep :executor-parallelism phases)
        max-parallelism (apply max 1 executor-parallelism)
        parallel? (> max-parallelism 1)
        budget-limited? (boolean (some :budget-limited? phases))
        reasons (set (keep :reason phases))]
    {:execution/path (if parallel? :parallel :serial)
     :execution/max-claimant-parallelism max-parallelism
     :execution/parallel-work-observed? parallel?
     :execution/budget-limited? budget-limited?
     :execution/reason (cond
                         (and parallel? budget-limited?) :parallel-budget-limited
                         parallel? :parallel
                         budget-limited? :serial-budget-limited
                         (contains? reasons :observer-requires-serial) :serial-observer
                         (contains? reasons :below-claimant-threshold) :serial-threshold
                         :else :serial-requested)}))

(defn freeze!
  "Close a realization to new facts. Freezing is separate from finalization so
   executor quiescence can be checked against an immutable phase record."
  [realization]
  (loop []
    (let [state @realization]
      (when-not (= :open (:lifecycle state))
        (throw (ex-info "Claimant realization is not open"
                        {:lifecycle (:lifecycle state)})))
      (let [frozen (assoc state :lifecycle :frozen)]
        (if (compare-and-set! realization state frozen)
          frozen
          (recur))))))

(defn finalize!
  "Build and retain the optional observation for a frozen realization.
   Finalization never invokes the configured sink."
  [realization]
  (loop []
    (let [state @realization]
      (when-not (= :frozen (:lifecycle state))
        (throw (ex-info "Claimant realization must be frozen before finalization"
                        {:lifecycle (:lifecycle state)})))
      (when-not (= (:executor-backed-phases state)
                   (:successfully-quiesced-phases state))
        (throw (ex-info "Cannot finalize claimant observation before executor quiescence"
                        (select-keys state [:executor-backed-phases
                                            :successfully-quiesced-phases]))))
      (let [observation (when *claimant-execution-runtime-profile-root*
                          (let [build (requiring-resolve 'resolver-sim.execution.observation/build)
                                valid? (requiring-resolve 'resolver-sim.execution.observation/valid?)
                                observation (build {:runtime-profile-root *claimant-execution-runtime-profile-root*
                                                    :effective (effective state)
                                                    :completion {:status :completed}})]
                            (when-not (valid? observation)
                              (throw (ex-info "Execution observation failed validation"
                                              {:observation observation})))
                            observation))
            finalized (assoc state :lifecycle :finalized :observation observation)]
        (if (compare-and-set! realization state finalized)
          observation
          (recur))))))

(defn emit!
  "Emit a finalized observation at most once. Closed root or sink modes are
   intentional no-ops: a missing root produces no observation and a missing sink
   retains the finalized observation without delivery."
  [realization]
  (loop []
    (let [state @realization]
      (when-not (= :finalized (:lifecycle state))
        (throw (ex-info "Claimant realization must be finalized before emission"
                        {:lifecycle (:lifecycle state)})))
      (cond
        (:emitted? state) nil
        (nil? (:observation state)) nil
        (nil? *claimant-execution-observation-sink*) nil
        :else (let [claimed (assoc state :emitted? true)]
                (if (compare-and-set! realization state claimed)
                  (*claimant-execution-observation-sink* (:observation state))
                  (recur)))))))

(defn complete!
  "Complete a successful realization in lifecycle order and deliver its optional
   observation exactly once."
  [realization]
  (freeze! realization)
  (let [observation (finalize! realization)]
    (emit! realization)
    observation))
