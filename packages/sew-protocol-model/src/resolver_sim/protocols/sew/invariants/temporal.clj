(ns resolver-sim.protocols.sew.invariants.temporal
  "Temporal consistency invariants for the Sew contract model.
   Delegates to the canonical core implementation."
  (:require [resolver-sim.time.context :as time-ctx]))

(defn check-temporal-consistency
  "Invariant: Ensures :block-time matches the :block-ts in :context/time.
   Delegates to the canonical engine in resolver-sim.time.context."
  [world]
  (time-ctx/check-temporal-consistency world))
