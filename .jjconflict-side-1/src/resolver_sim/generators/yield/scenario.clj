(ns resolver-sim.generators.yield.scenario
  "Build yield-provider replay scenarios from generators."
  (:require [clojure.test.check.generators :as gen]
            [resolver-sim.generators.yield.core :as ycore]
            [resolver-sim.generators.yield.events :as yevents]
            [resolver-sim.yield.presets :as presets]))

(defn- default-agents []
  [{:id "vault" :address "0xVault" :strategy "honest"}])

(defn build-yield-scenario
  "Build a minimal replay scenario for `yield-v1` / `replay-yield-scenario`.

   Options:
     :seed :scenario-id :profile :yield-config :events
     :initial-block-time :owner-id :threat-tags :expectations
     :failure-modes :liquidity-mode — shorthand for token config"
  [{:keys [seed scenario-id profile yield-config events expectations threat-tags
           initial-block-time owner-id failure-modes liquidity-mode shortfall-ratio]
    :or {seed 42
         profile :aave-v3
         initial-block-time 1000
         owner-id "vault"}}]
  (let [scenario-id (or scenario-id (str "gen-yield-" seed))
        yc (or yield-config
               (ycore/yield-config-for-profile profile "USDC"
                                               :failure-modes (or failure-modes [])
                                               :liquidity-mode (or liquidity-mode :available)
                                               :shortfall-ratio (or shortfall-ratio 0.5)))
        ev (or events (yevents/deposit-accrue-withdraw-seq :owner owner-id
                                                           :initial-time initial-block-time))]
    (cond-> {:scenario-id scenario-id
             :schema-version "1.0"
             :seed seed
             :initial-block-time initial-block-time
             :agents (default-agents)
             :protocol-params {:yield-profile profile
                               :default-owner-id owner-id}
             :yield-config yc
             :events (vec (map-indexed (fn [i e] (assoc e :seq i)) ev))
             :options {:minimal true}}
      (seq threat-tags) (assoc :threat-tags (vec threat-tags))
      expectations (assoc :expectations expectations))))

(defn build-shortfall-scenario
  "Preset-backed partial-liquidity shortfall-affected scenario."
  ([] (build-shortfall-scenario {}))
  ([{:keys [seed recover?] :or {seed 301 recover? false}}]
   (let [preset-cfg (presets/preset->yield-config :yield.preset/shortfall-partial)
         events (if recover?
                  (yevents/shortfall-affected-seq :recover? true)
                  (yevents/shortfall-affected-seq))]
     (build-yield-scenario
      {:seed seed
       :scenario-id (str "gen-yield-shortfall-" seed)
       :yield-config preset-cfg
       :events events
       :threat-tags ["yield-stress" "shortfall-affected" "partial-liquidity"]}))))

(defn build-liquidity-shortage-scenario
  ([] (build-liquidity-shortage-scenario {}))
  ([{:keys [seed] :or {seed 302}}]
   (build-yield-scenario
    {:seed seed
     :scenario-id (str "gen-yield-liquidity-shortage-" seed)
     :yield-config (ycore/yield-config-for-profile :aave-v3 "USDC"
                                                   :liquidity-mode :shortfall
                                                   :shortfall-ratio 0.5)
     :events (yevents/liquidity-shortage-seq)
     :threat-tags ["yield-stress" "liquidity-shortage" "deposit-blocked"]
     :expectations {:expected-outcomes [{:seq 0 :action "yield_deposit" :expect "rejected"}]}})))

(def gen-yield-scenario
  (gen/bind gen/pos-int
            (fn [seed]
              (gen/bind (gen/tuple ycore/gen-yield-config yevents/gen-yield-event-sequence)
                        (fn [[yc events]]
                          (gen/return
                           (build-yield-scenario
                            {:seed seed
                             :yield-config yc
                             :events events})))))))
