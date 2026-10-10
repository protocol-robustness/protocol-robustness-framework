(ns resolver-sim.research.native-accounting-batch
  "Batch orchestration for already-rooted native accounting partitions.

   This is a runtime composition over existing work-unit, assignment,
   execution and reconciliation artifacts; it defines no new semantic schema."
  (:require [resolver-sim.research.accelerated-accounting :as accounting]
            [resolver-sim.research.native-accounting-work :as native]
            [resolver-sim.research.remote-execution :as remote]
            [resolver-sim.research.runner-transport :as transport])
  (:import [java.util.concurrent Callable ExecutorCompletionService Executors]))

(defn- dispatch-root [item]
  (get-in item [:work :accounting/work-unit :accounting-work-unit/root]))

(declare eligible-routes)

(defn- valid-route-for-work? [item {:keys [participation endpoint]}]
  (let [work (:work item)
        eligible? (and (map? participation) (map? endpoint)
                       (:eligible? (remote/eligibility (:work/job work)
                                                       (:work/execution-environment work)
                                                       (:work/execution-policy work)
                                                       participation)))]
    (if-not (seq (:eligible-runners item))
      (and eligible?
           (= (:runner-participation/root participation)
              (get-in work [:work/runner-participation :runner-participation/root])))
      (let [assignment (when eligible?
                         (remote/steal-next (:work/job work) (:work/execution-environment work)
                                            (:work/execution-policy work) participation []))]
        (and assignment
             (= (:research-run-slot/root assignment)
                (get-in work [:work/assignment :research-run-slot/root]))
             (= (:run-slot/index assignment)
                (get-in work [:work/assignment :run-slot/index])))))))

(defn- validate-batch! [contract dispatches]
  (let [works (mapv :work dispatches)
        first-work (first works)
        request (:accounting/request first-work)
        plan (:accounting/partition-plan first-work)
        expected (set (map :accounting-work-unit/root (:partition/work-units plan)))
        actual (set (map #(get-in % [:accounting/work-unit :accounting-work-unit/root]) works))
        assignments (map #(get-in % [:work/assignment :research-run-assignment/root]) works)
        routes-valid? (every? (fn [item]
                                (let [routes (eligible-routes item)
                                      runner-roots (map #(get-in % [:participation :runner/reference]) routes)]
                                  (and (seq routes)
                                       (= (count routes) (count (set runner-roots)))
                                       (every? #(valid-route-for-work? item %) routes))))
                              dispatches)]
    (when-not (and (seq dispatches)
                   (every? #(and (native/valid-work? (:work %))
                                 (or (map? (:endpoint %))
                                     (seq (:eligible-runners %)))) dispatches)
                   routes-valid?
                   (every? #(= (:accounting-request/root request)
                               (get-in % [:accounting/request :accounting-request/root])) works)
                   (every? #(= (:accounting-partition-plan/root plan)
                               (get-in % [:accounting/partition-plan :accounting-partition-plan/root])) works)
                   (accounting/valid-reduction-contract? contract)
                   (= (:accounting-reduction-contract/root request)
                      (:accounting-reduction-contract/root contract))
                   (= expected actual)
                   (= (count dispatches) (count expected))
                   (= (count assignments) (count (set assignments)))
                   (every? some? assignments))
      (throw (ex-info "Native accounting batch must bind every partition exactly once"
                      {:expected-work-unit-roots (vec (sort expected))
                       :actual-work-unit-roots (vec (sort actual))})))
    {:request request :plan plan}))

(defn dispatch-and-reconcile!
  "Dispatch one already-assigned work bundle per partition concurrently.
   Endpoint and completion order are runtime-only. A canonical aggregate is
   returned only when coordinator admission contains the exact valid plan.

   `dispatches` entries are {:work rooted-native-work, :endpoint runtime-endpoint,
   :logical-cutpoint integer}. Failures remain per-dispatch outcomes and never
   manufacture zero-valued or complete accounting results."
  [coordinator contract dispatches]
  (let [{:keys [request plan]} (validate-batch! contract dispatches)
        tasks (mapv (fn [item]
                      (future
                        (try
                          {:accounting-work-unit/root (dispatch-root item)
                           :dispatch (transport/dispatch-native-accounting!
                                      coordinator
                                      {:endpoint (:endpoint item)
                                       :accounting-work (:work item)
                                       :logical-cutpoint (or (:logical-cutpoint item) 0)})}
                          (catch Throwable e
                            {:accounting-work-unit/root (dispatch-root item)
                             :dispatch/status :failed
                             :failure/class (.getName (class e))}))))
                    dispatches)
        outcomes (->> tasks (map deref) (sort-by :accounting-work-unit/root) vec)
        ingestion (get-in (transport/coordinator-state coordinator)
                          [:accounting-partials (:accounting-partition-plan/root plan)] {})
        completeness (accounting/accounting-completeness request plan ingestion)
        complete? (and (= :complete (:accounting/completeness completeness))
                       (every? #(contains? #{:accepted :already-completed}
                                            (get-in % [:dispatch :delivery :delivery/status]))
                               outcomes))]
    (cond-> {:accounting/batch-status (if complete? :complete :incomplete)
             :accounting/request-root (:accounting-request/root request)
             :accounting/partition-plan-root (:accounting-partition-plan/root plan)
             :accounting/completeness completeness
             :accounting/dispatch-outcomes outcomes}
      complete?
      (assoc :accounting/reconciliation
             (accounting/reconcile request contract plan ingestion)))))

(defn- dispatch-one [coordinator item]
  (try
    {:accounting-work-unit/root (dispatch-root item)
     :dispatch (transport/dispatch-native-accounting!
                coordinator {:endpoint (:endpoint item) :accounting-work (:work item)
                             :logical-cutpoint (or (:logical-cutpoint item) 0)})}
    (catch Exception e
      {:accounting-work-unit/root (dispatch-root item)
       :dispatch/status :failed :failure/class (.getName (class e))})))

(defn- runner-root [item]
  (or (:runtime/selected-runner-root item)
      (get-in item [:work :work/assignment :runner/reference])))

(defn- eligible-routes [item]
  (or (:eligible-runners item)
      [{:participation (get-in item [:work :work/runner-participation])
        :endpoint (:endpoint item)}]))

(defn- route-permitted? [assignment-allowed? item route]
  (boolean (assignment-allowed? (:work item) (:participation route))))

(defn- available-route [item active per-runner assignment-allowed?]
  (let [work (:work item)]
   (->> (eligible-routes item)
       (filter (fn [{:keys [participation endpoint]}]
                 (and (map? endpoint)
                      (route-permitted? assignment-allowed? item
                                        {:participation participation :endpoint endpoint})
                      (:eligible? (remote/eligibility (:work/job work)
                                                      (:work/execution-environment work)
                                                      (:work/execution-policy work)
                                                      participation))
                      (< (get active (:runner/reference participation) 0) per-runner))))
       (sort-by (juxt #(get active (get-in % [:participation :runner/reference]) 0)
                      #(get-in % [:participation :runner/reference])))
       first)))

(defn- bind-work-to-route [item {:keys [participation endpoint]}]
  (let [work (:work item)
        assignment (if (= (:runner-participation/root participation)
                          (get-in work [:work/runner-participation :runner-participation/root]))
                     (:work/assignment work)
                     (remote/steal-next (:work/job work) (:work/execution-environment work)
                                        (:work/execution-policy work) participation []))]
    (when-not (and assignment
                   (= (:research-run-slot/root assignment)
                      (get-in work [:work/assignment :research-run-slot/root]))
                   (= (:run-slot/index assignment)
                      (get-in work [:work/assignment :run-slot/index])))
      (throw (ex-info "Pull scheduling could not preserve the rooted accounting run slot"
                      {:accounting-work-unit/root (dispatch-root item)})))
    (assoc item :work (native/build-work (assoc work :work/assignment assignment
                                                :work/runner-participation participation))
           :endpoint endpoint
           :runtime/selected-runner-root (:runner/reference participation))))

(defn- dispatch-group [coordinator items]
  (if (= 1 (count items))
    [(dispatch-one coordinator (first items))]
    (try
      (transport/dispatch-native-accounting-batch!
       coordinator (:endpoint (first items))
       (mapv (fn [item]
               {:accounting-work (:work item)
                :logical-cutpoint (:logical-cutpoint item 0)}) items))
      (catch Exception e
        (mapv (fn [item]
                {:accounting-work-unit/root (dispatch-root item)
                 :dispatch/status :failed :failure/class (.getName (class e))}) items)))))

(defn- dispatch-profile [intervals capacity]
  (let [intervals (vec intervals)
        durations (sort (map #(- (:finished-ns %) (:started-ns %)) intervals))
        start (when (seq intervals) (apply min (map :started-ns intervals)))
        end (when (seq intervals) (apply max (map :finished-ns intervals)))
        wall (if start (- end start) 0)
        busy (reduce + 0 (map #(- (:finished-ns %) (:started-ns %)) intervals))
        points (mapcat (fn [{:keys [started-ns finished-ns]}]
                         [[started-ns 1] [finished-ns -1]]) intervals)
        max-active (second
                    (reduce (fn [[active maximum] [_ delta]]
                              (let [active (+ active delta)]
                                [active (max maximum active)]))
                            [0 0]
                            (sort-by (juxt first second) points)))
        first-finish (when (seq intervals) (apply min (map :finished-ns intervals)))
        last-finish (when (seq intervals) (apply max (map :finished-ns intervals)))
        per-runner (group-by :runner-root intervals)]
    {:measurement/clock :jvm-system-nano-time
     :dispatch/capacity capacity
     :dispatch/group-count (count intervals)
     :dispatch/work-unit-count (reduce + 0 (map :work-unit-count intervals))
     :dispatch/wall-ns wall
     :dispatch/aggregate-busy-ns busy
     :dispatch/group-duration-ns (when (seq durations)
                                   {:min (first durations)
                                    :median (nth durations (quot (count durations) 2))
                                    :max (last durations)})
     :dispatch/per-runner-call-time-ns
     (into (sorted-map)
           (map (fn [[runner groups]]
                  [runner (reduce + 0 (map #(- (:finished-ns %) (:started-ns %)) groups))]))
           per-runner)
     :dispatch/per-runner-finish-tail-ns
     (into (sorted-map)
           (map (fn [[runner groups]]
                  [runner (- end (apply max (map :finished-ns groups)))]))
           per-runner)
     :dispatch/idle-capacity-ns (max 0 (- (* capacity wall) busy))
     :dispatch/utilization-permille (if (pos? (* capacity wall))
                                      (quot (* 1000 busy) (* capacity wall)) 0)
     :dispatch/max-concurrent-groups max-active
     :dispatch/first-completion-tail-ns (if (and first-finish last-finish)
                                          (- last-finish first-finish) 0)}))

(defn- bounded-dispatch! [coordinator dispatches capacity per-runner batch-size
                         assignment-allowed?]
  (when-not (and (integer? capacity) (pos? capacity)
                 (integer? per-runner) (pos? per-runner)
                 (integer? batch-size) (<= 1 batch-size 32))
    (throw (ex-info "Accounting dispatch limits must be positive integers" {})))
  (let [pool (Executors/newFixedThreadPool capacity)
        completion (ExecutorCompletionService. pool)
        intervals (atom [])]
    (try
      (loop [pending (vec dispatches) running {} active {} results []]
        (let [cancelled (filterv (fn [item]
                                   (every? #(not (route-permitted? assignment-allowed? item %))
                                           (eligible-routes item))) pending)
              cancelled-roots (set (map dispatch-root cancelled))
              pending (vec (remove #(contains? cancelled-roots (dispatch-root %)) pending))
              results (into results (map (fn [item]
                                           {:accounting-work-unit/root (dispatch-root item)
                                            :dispatch/status :cancelled-before-assignment}) cancelled))]
          (if (and (empty? pending) (empty? running))
            {:outcomes (vec (sort-by :accounting-work-unit/root results))
             :profile (dispatch-profile @intervals capacity)}
            (if-let [[_ route]
                     (when (< (count running) capacity)
                       (first (keep-indexed
                               (fn [i item]
                                 (when-let [route (available-route item active per-runner assignment-allowed?)]
                                   [i route])) pending)))]
              (let [selected (->> pending
                                  (filter #(some (fn [candidate-route]
                                                   (and (= (get-in route [:participation :runner/reference])
                                                           (get-in candidate-route [:participation :runner/reference]))
                                                        (= (:endpoint route) (:endpoint candidate-route))))
                                                 (eligible-routes %)))
                                  (take batch-size)
                                  (mapv #(bind-work-to-route % route)))
                    selected-roots (set (map dispatch-root selected))
                    task (bound-fn []
                           (let [started (System/nanoTime)]
                             (try
                               (dispatch-group coordinator selected)
                               (finally
                                 (swap! intervals conj {:started-ns started
                                                        :finished-ns (System/nanoTime)
                                                        :runner-root (runner-root (first selected))
                                                        :work-unit-count (count selected)})))))
                    submitted (.submit completion ^Callable (reify Callable (call [_] (task))))
                    runner (get-in route [:participation :runner/reference])]
                (recur (vec (remove #(contains? selected-roots (dispatch-root %)) pending))
                       (assoc running submitted runner) (update active runner (fnil inc 0)) results))
              (let [finished (.take completion)
                    runner (get running finished)]
                (recur pending (dissoc running finished) (update active runner dec)
                       (into results (.get finished))))))))
      (finally (.shutdownNow pool)))))

(defn dispatch-accounts-and-reconcile!
  "Bounded runtime orchestration over a canonical parent accounting request.
   Derives the exact required account requests from the parent, validates all
   account/partition bindings before dispatch, and retains completed account
   results if another account fails. The parent is complete only when every
   declared account reconciles. Optional `:eligible-runners` routes enable a
   pending-unit pull schedule and require `:assignment-allowed?`, a live
   caller-supplied control/eligibility check invoked before each new assignment.
   Limits and delivery order are runtime-only;
   the result is a completeness view over existing canonical account results,
   not a new canonical accounting artifact."
  [coordinator parent-request contract dispatches
   {:keys [max-concurrent-dispatches max-outstanding-per-runner max-transport-batch-size
           assignment-allowed?]
    :or {max-concurrent-dispatches 4 max-outstanding-per-runner 1
         max-transport-batch-size 1}}]
  (let [accounts (native/account-requests parent-request contract)
        groups (group-by #(get-in % [:work :accounting/request :accounting-request/root]) dispatches)
        expected (set (map :accounting-request/root (vals accounts)))
        actual (set (keys groups))]
    (when-not (= expected actual)
      (throw (ex-info "Accounting parent requires exact account coverage"
                      {:missing-account-request-roots (vec (sort (remove actual expected)))
                       :unexpected-account-request-roots (vec (sort (remove expected actual)))})))
    ;; This eager pass prevents partial dispatch of a malformed parent batch.
    (let [contexts (into (sorted-map)
                         (map (fn [[account request]]
                                (let [items (get groups (:accounting-request/root request))
                                      context (validate-batch! contract items)]
                                  (when-not (= request (:request context))
                                    (throw (ex-info "Accounting account request substitution" {})))
                                  [account (assoc context :dispatches items)]))) accounts)
          pull-routing? (some #(seq (:eligible-runners %)) dispatches)
          _ (when (and pull-routing? (not (fn? assignment-allowed?)))
              (throw (ex-info "Pull scheduling requires a current assignment/cancellation eligibility check" {})))
          assignment-allowed? (or assignment-allowed? (constantly true))
          {:keys [outcomes profile]} (bounded-dispatch! coordinator dispatches max-concurrent-dispatches
                                                        max-outstanding-per-runner max-transport-batch-size
                                                        assignment-allowed?)
          outcome-by-unit (into {} (map (juxt :accounting-work-unit/root identity) outcomes))
          state (transport/coordinator-state coordinator)
          results (into
                   (sorted-map)
                   (map (fn [[account {:keys [request plan dispatches]}]]
                          (let [ingestion (get-in state [:accounting-partials
                                                        (:accounting-partition-plan/root plan)] {})
                                coverage (accounting/accounting-completeness request plan ingestion)
                                delivered? (every? #(contains? #{:accepted :already-completed}
                                                              (get-in (outcome-by-unit (dispatch-root %))
                                                                      [:dispatch :delivery :delivery/status]))
                                                   dispatches)
                                complete? (and delivered? (= :complete (:accounting/completeness coverage)))]
                            [account (cond-> {:accounting/status (if complete? :complete :incomplete)
                                              :accounting/completeness coverage}
                                       complete? (assoc :accounting/reconciliation
                                                        (accounting/reconcile request contract plan ingestion)))])))
                   contexts)
          missing (vec (keep (fn [[account result]]
                               (when-not (= :complete (:accounting/status result)) account)) results))]
      {:accounting/parent-request-root (:accounting-request/root parent-request)
       :accounting/batch-status (if (empty? missing) :complete :incomplete)
       :accounting/required-account-roots (vec (keys accounts))
       :accounting/incomplete-account-roots missing
       :accounting/account-results results
       :accounting/dispatch-outcomes outcomes
       :runtime/dispatch-profile profile})))
