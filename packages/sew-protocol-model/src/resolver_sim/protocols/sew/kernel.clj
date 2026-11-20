(ns resolver-sim.protocols.sew.kernel
  "Narrow API for Sew escrow world state and guarded state transitions.

   This kernel is a package boundary for the escrow state model only. It does
   not assemble the full Sew protocol, yield, slash settlement, research, or
   runner integrations. The existing `resolver-sim.protocols.sew` namespace
   remains the composed reference implementation."
  (:require [resolver-sim.protocols.sew.types :as types]
            [resolver-sim.protocols.sew.state-machine :as state-machine]))

(def empty-world
  "Create the canonical Sew world map at the supplied block time."
  types/empty-world)

(def make-escrow-transfer
  "Construct an escrow transfer using the canonical Sew field defaults."
  types/make-escrow-transfer)

(def make-escrow-settings
  "Construct the settings map frozen for an escrow."
  types/make-escrow-settings)

(def make-pending-settlement
  "Construct a pending-settlement value."
  types/make-pending-settlement)

(def empty-pending-settlement
  "Canonical empty pending-settlement value."
  types/empty-pending-settlement)

(defn world-with-escrow
  "Create a valid model world containing one escrow.

   `transfer-args` are passed to `make-escrow-transfer`. This initializes the
   state model directly; protocol action side effects are not run."
  ([workflow-id transfer-args]
   (world-with-escrow 0 workflow-id transfer-args))
  ([block-time workflow-id transfer-args]
   (let [workflow-id (types/normalize-workflow-id workflow-id)]
     (when-not (types/valid-entity-id? workflow-id)
       (throw (ex-info "Invalid Sew workflow id" {:workflow-id workflow-id})))
     (-> (types/empty-world block-time)
         (assoc-in [:escrow-transfers workflow-id]
                   (types/make-escrow-transfer transfer-args))
         (update :next-workflow-id max (inc workflow-id))))))

(def escrow-state
  "Return the escrow state for a workflow."
  types/escrow-state)

(def allowed-transitions
  "Authoritative EscrowState transition graph."
  state-machine/allowed-transitions)

(def transitions
  "Declarative transition registry."
  state-machine/transitions)

(def valid-transition?
  "True when `from` to `to` is an allowed state transition."
  state-machine/valid-transition?)

(def execute-transition
  "Execute a registered transition, returning the standard Sew result map."
  state-machine/execute-transition)

(def transition-to-disputed
  "Raise a dispute for a workflow as its caller."
  state-machine/transition-to-disputed)

(def transition-to-released
  "Transition a pending or disputed workflow to released."
  state-machine/transition-to-released)

(def transition-to-refunded
  "Transition a pending or disputed workflow to refunded."
  state-machine/transition-to-refunded)

(def transition-to-resolved
  "Transition a disputed workflow to resolved."
  state-machine/transition-to-resolved)
