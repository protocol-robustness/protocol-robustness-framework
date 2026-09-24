(ns resolver-sim.research.distributed-policy
  "Explicit semantic policies for controlled distributed scheduling."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

(def ^:const schema "distributed-scheduler-policy.v1")
(def policy-kinds #{:local-only :distributed-work-stealing :distributed-distance-aware})

(defn policy-root [policy]
  (hash-ref/sha256-ref
   (hc/domain-hash :distributed-scheduler-policy-v1
                   (dissoc policy :distributed-scheduler-policy/root))))

(defn build-policy
  [{:keys [kind retry-limit transfer-timeout-ms]
    :or {retry-limit 0 transfer-timeout-ms 1000000}}]
  (when-not (and (contains? policy-kinds kind)
                 (integer? retry-limit) (not (neg? retry-limit))
                 (pos-int? transfer-timeout-ms))
    (throw (ex-info "Invalid distributed scheduler policy parameters"
                    {:kind kind :retry-limit retry-limit
                     :transfer-timeout-ms transfer-timeout-ms})))
  (let [selection (case kind
                    :local-only :task-home-worker
                    :distributed-work-stealing :earliest-worker-availability
                    :distributed-distance-aware :earliest-finish-including-transfer)
        base {:artifact/schema schema
              :policy/kind kind
              :policy/selection-rule selection
              :policy/tie-break-rule :worker-id-lexicographic
              :policy/remote-preference (if (= kind :local-only) :forbidden :permitted)
              :policy/retry-limit retry-limit
              :policy/retry-eligibility [:distribution-dependency-failure
                                         :remote-worker-unreachable
                                         :transfer-timeout]
              :policy/failure-fallback :task-home-worker
              :policy/transfer-timeout-ms transfer-timeout-ms}
        rooted (assoc base :distributed-scheduler-policy/root (policy-root base))]
    rooted))
