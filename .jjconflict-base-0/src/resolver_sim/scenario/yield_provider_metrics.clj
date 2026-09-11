(ns resolver-sim.scenario.yield-provider-metrics
  "Yield-engine metrics for provider-only replay (no Sew escrow fields)."
  (:require [resolver-sim.yield.market-state :as market-state]))

(defn- all-positions [world]
  "Read yield positions from world state.
   Checks :yield/positions (standard), :yield-positions (legacy), and
   :yield-evidence (yield-v1 replay trace snapshot format)."
  (or (:yield/positions world)
      (:yield-positions world)
      (when-let [ev (:yield-evidence world)]
        (into {} (for [[k v] ev] [(keyword k) (assoc v :owner/id k)])))
      {}))

(defn- vault-indices [world]
  (or (:yield/indices world)
      (:yield-indices world)
      {}))

(defn compute-provider-metrics
  "Extract vault-level and multi-position metrics for yield-provider replay."
  [result scenario]
  (let [trace      (:trace result)
        last-world (when (seq trace) (:world (last trace)))
        positions  (when last-world (all-positions last-world))
        indices    (when last-world (vault-indices last-world))
        pos-vals   (vals positions)
        shortfalls (keep :shortfall pos-vals)
        held       (or (:yield/held-balances last-world) (:yield-held last-world) {})
        ;; Use the first module/token found for general vault metrics
        mid        (or (get-in scenario [:protocol-params :yield-generation-module])
                       (get-in scenario [:protocol-params :yield-profile])
                       (:module/id (first pos-vals))
                       :aave-v3)
        tok        (or (get-in scenario [:protocol-params :token])
                       (:token (first pos-vals))
                       :USDC)
        ms         (when (and last-world mid)
                     (market-state/get-market-state last-world mid tok (:block-time last-world)))]
    (cond-> {:yield/positions-count         (count pos-vals)
             :yield/total-principal         (long (reduce + (map #(:principal % 0) pos-vals)))
             :yield/total-unrealized        (long (reduce + (map #(:unrealized-yield % 0) pos-vals)))
             :yield/total-realized          (long (reduce + (map #(:realized-yield % 0) pos-vals)))
             :yield/total-deferred          (long (reduce + (map #(:deferred-amount % 0) shortfalls)))
             :yield/total-haircut           (long (reduce + (map #(:haircut-amount % 0) shortfalls)))
             :yield/total-reclaimed         (long (reduce + (map #(:reclaimed-amount % 0) pos-vals)))
             :yield/total-held              (long (reduce + (vals held)))}

      ms (assoc :yield/module-state     (name (:module-state ms))
                :yield/available-ratio  (:available-ratio ms))

      ;; If a specific owner-id is requested, include their specific metrics
      (get-in scenario [:protocol-params :focus-owner-id])
      (merge (let [owner (get-in scenario [:protocol-params :focus-owner-id])
                   pos   (get positions owner)
                   sf    (:shortfall pos)]
               (cond-> {}
                 pos (assoc :yield/focus-principal  (long (or (:principal pos) 0))
                            :yield/focus-unrealized (long (or (:unrealized-yield pos) 0))
                            :yield/focus-status     (name (:status pos)))
                 sf  (assoc :yield/focus-deferred   (long (or (:deferred-amount sf) 0))
                            :yield/focus-haircut    (long (or (:haircut-amount sf) 0))))))

      ;; First available index for display
      (seq indices)
      (assoc :yield/liquidity-index (double (first (vals (first (vals indices)))))))))

(defn merge-provider-metrics
  [result scenario]
  (update result :metrics merge (compute-provider-metrics result scenario)))
