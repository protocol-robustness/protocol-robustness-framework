(ns resolver-sim.examples.ecommerce.reporting
  "Stakeholder-only ecommerce interpretation of benchmark scenario results.

   This projection runs after benchmark execution and does not affect replay,
   claims, evidence capture, or hashes. It only classifies terminal state that
   is already present in the benchmark result metrics."
  (:require [clojure.string :as str]))

(defn- terminal-state
  [result]
  (some-> (or (get-in result [:metrics :escrow/live-state])
              (get-in result [:metrics :escrow/state]))
          name
          str/lower-case))

(defn classify-scenario
  "Return an ecommerce-language interpretation of one benchmark result.

   Terminal escrow state determines the stakeholder outcome. A dispute count is
   retained separately so a resolved dispute is not misreported as funds being
   locked merely because a dispute occurred earlier in the scenario."
  [result]
  (let [state (terminal-state result)
        disputed? (pos? (or (get-in result [:metrics :disputes-triggered]) 0))
        base {:scenario/id (:scenario/id result)
              :scenario/outcome (:outcome result)
              :stakeholder/roles {:buyer "buyer" :merchant "merchant"}
              :stakeholder/dispute-raised? disputed?}]
    (cond
      (= state "released")
      (assoc base
             :stakeholder/outcome :merchant-paid
             :stakeholder/headline "Merchant payment released"
             :stakeholder/summary
             (if disputed?
               "A buyer–merchant dispute was raised and the escrowed payment was released to the merchant."
               "The buyer's escrowed payment was released to the merchant."))

      (= state "refunded")
      (assoc base
             :stakeholder/outcome :buyer-refunded
             :stakeholder/headline "Buyer refunded"
             :stakeholder/summary
             (if disputed?
               "A buyer–merchant dispute was raised and the escrowed payment was returned to the buyer."
               "The escrowed payment was returned to the buyer."))

      (#{"disputed" "pending" "stuck"} state)
      (assoc base
             :stakeholder/outcome :funds-locked
             :stakeholder/headline "Payment remains locked"
             :stakeholder/summary
             "The buyer and merchant do not yet have access to the escrowed payment.")

      :else
      (assoc base
             :stakeholder/outcome :unclassified
             :stakeholder/headline "Order outcome not classified"
             :stakeholder/summary
             "The benchmark result does not include a terminal escrow state that can be translated into ecommerce language."))))

(defn ecommerce-results
  "Build an ecommerce-language report section from existing benchmark results."
  [results]
  (let [scenarios (mapv classify-scenario results)
        outcome-counts (frequencies (map :stakeholder/outcome scenarios))]
    {:concept/id :ecommerce/purchase
     :stakeholder/summary
     "Stakeholder-facing interpretation of the benchmark's escrow outcomes. This is a report overlay, not a claim about delivery, product quality, or legal enforceability."
     :stakeholder/outcome-counts outcome-counts
     :stakeholder/scenarios scenarios
     :stakeholder/limitations
     ["This report does not establish delivery, product quality, marketplace policy, or legal enforceability."
      "An ecommerce interpretation does not change benchmark claims, scenario outcomes, or protocol evidence."]}))
