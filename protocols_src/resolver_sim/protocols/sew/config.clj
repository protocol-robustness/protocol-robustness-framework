(ns resolver-sim.protocols.sew.config
  "Centralized configuration for Sew protocol parameters.

   All hardcoded numerical parameters should be defined here
   rather than duplicated across source, simulation, and test files."
  (:require [resolver-sim.time.context :as time-ctx]))

(def DEFAULT_ESCALATION_CONFIG
  "Default configuration for appeal bonds and resolver stakes."
  {:resolver-stake-base 10000
   :appeal-bond-base 1000
   :bond-multiplier 1.5
   :slashing-rate 0.5
   :round-configs {0 {:stake-multiplier 1.0
                      :bond-multiplier 1.0
                      :time-to-appeal-hours 48}
                   1 {:stake-multiplier 2.0
                      :bond-multiplier 1.5
                      :time-to-appeal-hours 72}
                   2 {:stake-multiplier 3.0
                      :bond-multiplier 2.0
                      :time-to-appeal-hours 0}}})

(def DEFAULT_TIMEOUT_CONFIG
  "Default timeout configuration for disputes and appeals."
   {:max-dispute-duration time-ctx/seconds-per-day
    :appeal-window-duration (* 2 time-ctx/seconds-per-day)
   :default-auto-release-delay 0
   :default-auto-cancel-delay 0})

(def EFFORT_LEVELS
  "Effort level configurations for trust floor threshold sweeps."
  [{:label :baseline :fee 50  :emer 2 :trust 0.2}
   {:label :medium   :fee 100 :emer 4 :trust 0.5}
   {:label :best     :fee 150 :emer 8 :trust 0.8}])
