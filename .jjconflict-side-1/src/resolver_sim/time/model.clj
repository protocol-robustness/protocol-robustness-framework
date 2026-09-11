(ns resolver-sim.time.model
  "Simulation time model — minimal two-field representation.
   Only block-ts (Unix seconds) and scenario-step are tracked;
   all protocol timeouts and deadlines run off block-ts alone."
  (:require [resolver-sim.time.context :as time-ctx]))

(defn now
  "Return simulation time snapshot from world.
   Falls back to :block-time for backward compatibility."
  [world]
  (let [tctx (time-ctx/temporal-context world)]
    {:block-ts      (:block-ts tctx)
     :scenario-step (:step tctx)}))

(defn with-time
  "Persist time snapshot back onto world.
   Synchronizes with the canonical temporal context root."
  [world {:keys [block-ts scenario-step]}]
  (time-ctx/with-temporal-context world
    {:block-ts block-ts :step scenario-step}))

(defn advance
  "Advance the simulation clock by a duration map.
   Accepts :seconds (added to block-ts), :blocks (ignored for now),
   :txs (ignored), :epochs (ignored), :steps (added to scenario-step).
   Negative :seconds triggers a validation error.
   Returns an updated world with canonical temporal context set."
  [world {:keys [seconds steps] :or {seconds 0 steps 0}}]
  (when (neg? seconds)
    (throw (ex-info "advance: seconds must be non-negative"
                    {:seconds seconds})))
  (time-ctx/advance-time world {:seconds seconds :steps steps}))
