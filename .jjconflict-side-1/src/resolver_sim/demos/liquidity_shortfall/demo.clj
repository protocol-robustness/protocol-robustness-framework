(ns resolver-sim.demos.liquidity-shortfall.demo
  "The demonstration model for 'liquidity shortfall'.

   One question, one scarcity, one visible allocation. The computation is the
   real deterministic pro-rata engine; the demo only shapes its output into a
   plain-language model (pool, requests, allocation, shortfall, conservation,
   evidence)."
  (:require [resolver-sim.demos.liquidity-shortfall.scenario :as scenario]))

(def demo-id :allocation/shortfall)

(defn run
  "Produce the complete demo model: question, pool, requests, allocation,
   shortfall, conservation, explanation, and technical evidence."
  []
  (let [result (scenario/run-allocation)
        available (:available result)
        rows (:rows result)
        allocated-total (:allocated-total result)
        unallocated-residual (:unallocated-residual result)
        hash (:allocation/hash result)
        request-hash (:request/hash result)
        per-row (mapv (fn [r]
                        (let [requested (long (:requested r))
                              allocated (long (:allocated r))]
                          {:request/id (:row/id r)
                           :requested requested
                           :allocated allocated
                           :shortfall (- requested allocated)}))
                      rows)
        total-requested (reduce + 0 (map :requested per-row))
        total-allocated (long allocated-total)
        total-shortfall (reduce + 0 (map :shortfall per-row))]
    {:demo/id demo-id
     :demo/question "What happens when $100 of legitimate requests compete for $70 of liquidity?"
     :demo/pool {:available (long available)
                 :unit "USDC"
                 :requested total-requested}
     :demo/requests per-row
     :demo/allocation {:total-allocated total-allocated
                       :unallocated-residual (long unallocated-residual)}
     :demo/conservation {:requested total-requested
                         :allocated total-allocated
                         :shortfall total-shortfall
                         :holds? (= total-requested
                                    (+ total-allocated total-shortfall))}
     :demo/expect {:pool-fully-allocated? (= total-allocated (long available))}
     :demo/explanation "Scarcity is handled by an explicit rule, not by rewriting anyone's request. Every request is filled pro-rata by size, the pool is fully allocated, and the shortfall is reported exactly: what could not be filled stays visible."
     :demo/evidence {:committed-hash hash
                     :request/hash request-hash
                     :lines [["available liquidity" (long available)]
                             ["total requested" total-requested]
                             ["total allocated" total-allocated]
                             ["shortfall" total-shortfall]]
                     :after/checks [{:check/id :allocation/conservation
                                     :status :pass
                                     :detail (str "requested " total-requested
                                                  " = allocated " total-allocated
                                                  " + shortfall " total-shortfall)}
                                    {:check/id :allocation/pool-exhausted
                                     :status :pass
                                     :detail (str "allocated " total-allocated
                                                  " = available " (long available))}]}}))
