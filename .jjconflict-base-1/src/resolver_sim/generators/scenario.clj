(ns resolver-sim.generators.scenario
  "Sew Protocol scenario composition for deterministic generator output."
  (:require [resolver-sim.generators.stateful :as stateful]
            [resolver-sim.protocols.protocol :as proto]
            [resolver-sim.protocols.registry :as preg]))

(def default-agents
  [{:id "buyer" :strategy "honest" :address "0xBuyer"}
   {:id "seller" :strategy "honest" :address "0xSeller"}
   {:id "resolver" :role "resolver" :address "0xResolver"}])

(def default-protocol-params
  {:resolver-fee-bps 50
   :appeal-window-duration 60
   :max-dispute-duration 3600
   :appeal-bond-protocol-fee-bps 0})

(defn build-scenario
  "Build a deterministic replay-compatible scenario map."
  [{:keys [seed max-steps initial-block-time profile protocol]
    :or {seed 42 max-steps 4 initial-block-time 1000 profile :phase1-lifecycle}}]
  (let [protocol (or protocol (preg/get-protocol preg/default-protocol-id))
        context (proto/build-execution-context protocol default-agents default-protocol-params)
        world0  (proto/init-world protocol {:initial-block-time initial-block-time})
        {:keys [events]} (stateful/generate-event-sequence
                          {:seed seed
                           :max-steps max-steps
                           :profile profile
                           :protocol protocol
                           :context context
                           :world0 world0
                           :initial-time initial-block-time})]
    {:scenario-id        (str "generated-" seed)
     :schema-version     "1.0"
     :seed               seed
     :generator-profile  profile
     :agents             default-agents
     :protocol-params    default-protocol-params
     :initial-block-time initial-block-time
     :events             events}))
