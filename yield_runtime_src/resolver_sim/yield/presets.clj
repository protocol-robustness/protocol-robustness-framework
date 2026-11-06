(ns resolver-sim.yield.presets
  "Scenario yield-config presets. Stress and profile variants belong here, not in module factories.

   Each preset is a fragment suitable for `registry/apply-yield-config` (shape {:modules ...})."
  (:require [resolver-sim.yield.registry :as registry]))

(def presets
  {:yield.preset/aave-baseline
   {:modules
    {:aave-v3
     {:tokens
      {"USDC" {:initial-index 1.0
               :apy 0.05
               :liquidity-mode :available}}}}}

   :yield.preset/negative-yield-mild
   {:modules
    {:aave-v3
     {:tokens
      {"USDC" {:apy -0.01
               :failure-modes #{:negative-yield}}}}}}

   :yield.preset/shortfall-partial
   {:modules
    {:aave-v3
     {:tokens
      {"USDC" {:failure-modes #{:partial-liquidity}
               :shortfall {:available-ratio 0.5
                           :reason :liquidity-shortfall}}}}}}

   :yield.preset/oracle-stale-aave
   {:modules
    {:aave-v3
     {:tokens
      {"USDC" {:apy 0.05
               :failure-modes #{:oracle-stale}}}}}}

   :yield.preset/withdrawal-queue-aave
   {:modules
    {:aave-v3
     {:tokens
      {"USDC" {:apy 0.05
               :failure-modes #{:withdrawal-queue}}}}}}})

(defn preset->yield-config
  "Return the yield-config map for a preset id, or nil if unknown."
  [preset-id]
  (get presets preset-id))

(defn apply-preset
  "Apply a named preset yield-config fragment onto world (modules must already be registered)."
  [world preset-id]
  (if-let [cfg (preset->yield-config preset-id)]
    (registry/apply-yield-config world cfg)
    (throw (ex-info "Unknown yield preset" {:preset-id preset-id}))))
