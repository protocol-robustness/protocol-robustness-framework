^{:nextjournal.clerk/visibility {:code :show :result :show}
  :nextjournal.clerk/dark-mode true}
(ns notebooks.archive.yield-shortfall-analysis
  "General analysis notebook for yield module shortfall resilience.
   
   This notebook demonstrates how to instrument and analyze liquidity
   shortfalls in arbitrary yield modules independently of specific protocol logic."
  (:require [resolver-sim.yield.accounting :as acct]
            [resolver-sim.yield.model :as model]
            [nextjournal.clerk :as clerk]
            [clojure.pprint :refer [pprint]]))

;; =============================================================================
;; 1. Resilience Workbench
;; =============================================================================

(defn- analyze-resilience [pos]
  (let [shortfall (:shortfall pos)
        deferred (:deferred-amount shortfall 0)
        original (:original-deferred-amount shortfall 0)]
    {:position-id (:position/id pos)
     :status (:status pos)
     :liquidity-loss-percent (if (pos? original) (* 100.0 (/ deferred original)) 0.0)
     :deferred-amount deferred
     :needs-manual-intervention? (and shortfall (> deferred 0))}))

(defn shortfall-workbench [available-ratio principal]
  (let [pos (model/make-position {:owner/id "user-1" :module/id :aave-v3 :token :USDC :principal principal})
        ;; Simulate stress
        stressed-pos (assoc pos 
                            :status :unwinding
                            :shortfall {:reason :liquidity-shortfall
                                        :available-ratio available-ratio
                                        :deferred-amount (* principal (- 1.0 available-ratio))
                                        :original-deferred-amount (* principal (- 1.0 available-ratio))})]
    (analyze-resilience stressed-pos)))

;; Visualizing the impact of decreasing liquidity:
(clerk/table
 (for [ratio [1.0 0.8 0.5 0.2]]
   (shortfall-workbench ratio 1000)))

;; =============================================================================
;; 2. Understanding Shortfall Data Structures
;; =============================================================================

(clerk/md "
### Position State under Shortfall
When a position enters a shortfall, it is enriched with a `:shortfall` map. 
The `:status` field transitions from `:active` to `:unwinding`.
")

;; A position in shortfall looks like this for demonstration:
(def sample-shortfall-position
  (model/make-position {:owner/id "user-1" :module/id :aave-v3 :token :USDC :principal 1000}))

(def shortfall-state
  (assoc sample-shortfall-position
         :status :unwinding
         :shortfall {:reason :liquidity-shortfall
                     :available-ratio 0.4
                     :fulfilled-amount 400
                     :deferred-amount 600
                     :original-deferred-amount 600}))

(pprint shortfall-state)

;; =============================================================================
;; 3. Demonstration: Isolated Scenario S107
;; =============================================================================

(def scenario-s107
  {:schema-version "1.1"
   :scenario-id "s107-isolated-shortfall"
   :id "s107-isolated-shortfall"
   :title "Isolated Shortfall Demonstration"
   :purpose "demonstration"
   :scenario-author "agent-d"
   :events [
     {:seq 0 :action "set-yield-risk"
      :params {:module-id "aave" :token "USDC" :liquidity-mode "shortfall"
               :shortfall {:available-ratio 0.5}}}]})

(require '[resolver-sim.scenario.runner :as runner]
         '[resolver-sim.contract-model.replay :as replay]
         '[resolver-sim.protocols.registry :as preg])

(defn- run-s107 []
  (let [protocol (preg/get-protocol "sew-v1")]
    (runner/run-scenario scenario-s107 {:replay-fn #(replay/replay-with-protocol protocol %)})))

(clerk/table [ (run-s107) ])

