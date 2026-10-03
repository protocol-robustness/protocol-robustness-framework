(ns resolver-sim.research.native-accounting-batch
  "Batch orchestration for already-rooted native accounting partitions.

   This is a runtime composition over existing work-unit, assignment,
   execution and reconciliation artifacts; it defines no new semantic schema."
  (:require [resolver-sim.research.accelerated-accounting :as accounting]
            [resolver-sim.research.native-accounting-work :as native]
            [resolver-sim.research.runner-transport :as transport]))

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
