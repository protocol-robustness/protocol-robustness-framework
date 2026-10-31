(ns resolver-sim.yield.protocols
  "Standardized yield integration helpers for protocols.

   This namespace provides the glue required to attach the yield engine to 
   a protocol implementing SimulationAdapter."
  (:require [resolver-sim.yield.registry :as registry]
            [resolver-sim.yield.ops :as ops]
            [resolver-sim.time.context :as time-ctx]))

(defn init-world
  "Initializes the world state with yield modules and configuration.

    protocol-params — map containing :yield-generation-module or :yield-profile
    yield-config    — map containing module definitions and token settings"
  [world protocol-params yield-config]
  (let [pp         (or protocol-params {})
        yield-id   (or (get pp :yield-generation-module)
                       (get pp :yield-profile))]
    (-> (time-ctx/ensure-temporal-context world)
        (assoc-in [:params :yield-generation-module] yield-id)
        registry/init-yield-modules
        (registry/apply-yield-config yield-config))))

(defn apply-op
  "Delegates a yield operation to the yield engine."
  [world op]
  (ops/apply-yield-op world op))

(defn resolve-yield-profile
  "Resolves a profile ID to its module components."
  [profile-id]
  (registry/resolve-yield-profile profile-id))
