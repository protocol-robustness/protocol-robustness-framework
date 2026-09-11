(ns resolver-sim.demos.liquidity-shortfall.assertions
  "Deterministic expectations for the 'liquidity shortfall' demonstration.

   The demo commits to a set of verdicts about the real allocation output:
   the pool is fully allocated, every request is partially filled pro-rata,
   conservation holds (requested = allocated + shortfall), and each shortfall
   is exactly requested - allocated. These checks fail the build if the
   demonstration ever drifts from what the engine actually computes."
  (:require [resolver-sim.demos.liquidity-shortfall.demo :as demo]))

(defn check
  "Run the demo and verify every committed expectation holds.

   Returns {:demo/id ... :pass? bool :failures [<details>]}."
  []
  (let [result (demo/run)
        pool (:demo/pool result)
        requests (:demo/requests result)
        allocation (:demo/allocation result)
        conservation (:demo/conservation result)
        failures (cond-> []
                   (not= (:requested pool) (reduce + 0 (map :requested requests)))
                   (conj {:where :pool-requested
                          :expected {:requested (reduce + 0 (map :requested requests))}
                          :actual {:requested (:requested pool)}})

                   (not= (:available pool) (:total-allocated allocation))
                   (conj {:where :pool-exhausted
                          :expected {:allocated (:available pool)}
                          :actual {:allocated (:total-allocated allocation)}})

                   (not (:holds? conservation))
                   (conj {:where :conservation
                          :expected {:holds? true}
                          :actual {:holds? false}})

                   (seq (filter #(zero? (:allocated %)) requests))
                   (conj {:where :every-request-partially-filled
                          :expected {:no-zero-allocations true}
                          :actual {:zero-allocations
                                   (mapv :request/id
                                         (filter #(zero? (:allocated %)) requests))}})

                   (seq (filter #(= (:requested %) (:allocated %)) requests))
                   (conj {:where :no-request-fully-filled
                          :expected {:all-shortfall-pos true}
                          :actual {:fully-filled
                                   (mapv :request/id
                                         (filter #(= (:requested %) (:allocated %))
                                                 requests))}})

                   (seq (filter #(not= (- (:requested %) (:allocated %))
                                       (:shortfall %))
                                requests))
                   (conj {:where :shortfall-defined
                          :expected {:shortfall-shape "requested - allocated"}
                          :actual requests}))]
    {:demo/id (:demo/id result)
     :pass? (empty? failures)
     :failures failures
     :result result}))
