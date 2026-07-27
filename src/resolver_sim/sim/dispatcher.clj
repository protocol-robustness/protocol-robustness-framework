(ns resolver-sim.sim.dispatcher
  "Central transition dispatcher that binds protocol dispatch to evidence emission.
   This is the preferred entry point for scenario execution in the simulation
   kernel. It captures before/after world states, validates attribution context,
   and produces content-hashed evidence records for every transition.

   Legacy proto/dispatch-action calls remain functional — this layer adds
   evidence without changing the protocol interface.

   The implementation is now in contract-model.replay.execution to maintain
   correct dependency layering (contract-model must not depend on sim).

   WARNING: This is a backward-compatibility forwarding shim.
   New code should use resolver-sim.contract-model.replay.execution directly."
  (:require [resolver-sim.contract-model.replay.execution :as execution]
            [clojure.tools.logging :as log]))

(log/warn! :sim-dispatcher-deprecated
           "resolver-sim.sim.dispatcher is a forwarding shim. "
           "Call resolver-sim.contract-model.replay.execution directly.")

(defn apply-action-with-evidence
  "Dispatch an action through the protocol layer and emit a content-hashed
   evidence record for the transition.

   Delegates to contract-model.replay.execution/apply-action-with-evidence."
  [protocol context world event]
  (execution/apply-action-with-evidence protocol context world event))
