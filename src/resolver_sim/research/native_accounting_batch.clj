(ns resolver-sim.research.native-accounting-batch
  "Batch orchestration for already-rooted native accounting partitions.

   This is a runtime composition over existing work-unit, assignment,
   execution and reconciliation artifacts; it defines no new semantic schema."
  (:require [resolver-sim.research.accelerated-accounting :as accounting]
            [resolver-sim.research.native-accounting-work :as native]
            [resolver-sim.research.runner-transport :as transport])
  (:import [java.util.concurrent Callable ExecutorCompletionService Executors]))

(defn- dispatch-root [item]
  (get-in item [:work :accounting/work-unit :accounting-work-unit/root]))

(defn- validate-batch! [contract dispatches]
  (let [works (mapv :work dispatches)
        first-work (first works)
        request (:accounting/request first-work)
        plan (:accounting/partition-plan first-work)
        expected (set (map :accounting-work-unit/root (:partition/work-units plan)))
        actual (set (map #(get-in % [:accounting/work-unit :accounting-work-unit/root]) works))
        assignments (map #(get-in % [:work/assignment :research-run-assignment/root]) works)]
    (when-not (and (seq dispatches)
                   (every? #(and (map? (:endpoint %)) (native/valid-work? (:work %))) dispatches)
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

(defn- runner-root [item] (get-in item [:work :work/assignment :runner/reference]))

(defn- bounded-dispatch! [coordinator dispatches capacity per-runner]
  (when-not (and (integer? capacity) (pos? capacity)
                 (integer? per-runner) (pos? per-runner))
    (throw (ex-info "Accounting dispatch limits must be positive integers" {})))
  (let [pool (Executors/newFixedThreadPool capacity)
        completion (ExecutorCompletionService. pool)]
    (try
      (loop [pending (vec dispatches) running {} active {} results []]
        (if (and (empty? pending) (empty? running))
          (vec (sort-by :accounting-work-unit/root results))
          (if-let [index (when (< (count running) capacity)
                           (first (keep-indexed
                                   (fn [i item]
                                     (when (< (get active (runner-root item) 0) per-runner) i))
                                   pending)))]
            (let [item (nth pending index)
                  task (bound-fn [] (dispatch-one coordinator item))
                  submitted (.submit completion ^Callable (reify Callable (call [_] (task))))
                  runner (runner-root item)]
              (recur (into (subvec pending 0 index) (subvec pending (inc index)))
                     (assoc running submitted runner) (update active runner (fnil inc 0)) results))
            (let [finished (.take completion)
                  runner (get running finished)]
              (recur pending (dissoc running finished) (update active runner dec)
                     (conj results (.get finished)))))))
      (finally (.shutdownNow pool)))))

(defn dispatch-accounts-and-reconcile!
  "Bounded runtime orchestration over a canonical parent accounting request.
   Derives the exact required account requests from the parent, validates all
   account/partition bindings before dispatch, and retains completed account
   results if another account fails. The parent is complete only when every
   declared account reconciles. Limits and delivery order are runtime-only;
   the result is a completeness view over existing canonical account results,
   not a new canonical accounting artifact."
  [coordinator parent-request contract dispatches
   {:keys [max-concurrent-dispatches max-outstanding-per-runner]
    :or {max-concurrent-dispatches 4 max-outstanding-per-runner 1}}]
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
          outcomes (bounded-dispatch! coordinator dispatches max-concurrent-dispatches
                                      max-outstanding-per-runner)
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
       :accounting/dispatch-outcomes outcomes})))
