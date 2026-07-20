(ns resolver-sim.protocols.protocol
  "Tiered adapter interfaces for the Protocol Robustness Framework.

   Protocol implementations should implement SimulationAdapter (mandatory) and
   optionally EconomicModel or AnalysisModule depending on their
   intended simulation depth.

   The replay engine handles these by checking `satisfies?` at runtime.")

;; ---------------------------------------------------------------------------
;; 1. SimulationAdapter (Mandatory)
;;
;; Minimal set required for deterministic scenario replay, invariant checking,
;; and trace generation.
;; ---------------------------------------------------------------------------

(defprotocol SimulationAdapter
  "Essential interface for deterministic state-machine replay."

  (protocol-id [adapter]
    "Return a stable string identifier for this protocol (e.g. \"sew-v1\").")

  (init-world [adapter scenario]
    "Return an initial world-state map from the scenario.")

  (build-execution-context [adapter agents protocol-params]
    "Build a context map passed opaquely to dispatch-action.")

  (dispatch-action [adapter context world event]
    "Apply one event to the world state. Returns {:ok bool :world world' :error kw :extra {...}}.")

  (check-invariants-single [adapter world]
    "Single-world invariant checks. Returns {:ok? bool :violations map-or-nil}.")

  (check-invariants-transition [adapter world-before world-after]
    "Cross-world invariant checks. Returns {:ok? bool :violations map-or-nil}.")

  (world-snapshot [adapter world]
    "Create a lean, serializable map of the world state for trace output.")

  (available-actions [adapter world actor]
    "Return a seq of all valid action maps {:action str :params map} for the actor in the given world state.")

  (resolve-id-alias [adapter event id-alias-map]
    "Resolve entity aliases in the event. Returns {:ok bool :event event' :error kw}.")

  (created-id [adapter action extra]
    "Extract the ID of a newly created entity from an action's extra metadata.")

  (open-entities [adapter world]
    "Return a seq of entity IDs still open/unresolved at end of scenario.")

  (project-state [adapter world query]
    "Query the world state using a protocol-specific projection query."))

;; ---------------------------------------------------------------------------
;; 1b. TemporalDeadlines (Optional)
;;
;; Generic deadline lookup for temporal rule enforcement.
;; Protocols that enforce time-based gates (evidence windows, appeal windows,
;; settlement timelocks) implement this to supply deadline timestamps.
;; The replay engine's built-in :deadline-enforcement temporal rule uses
;; this to reject actions that violate temporal boundaries.
;; ---------------------------------------------------------------------------

(defprotocol TemporalDeadlines
  "Optional interface for deadline-driven temporal rule enforcement."

  (deadline-for [model world deadline-kind subject context]
    "Return the deadline timestamp (integer) for deadline-kind and subject,
     or nil if no deadline applies (action allowed).
     deadline-kind is a keyword such as :evidence-submission, :settlement,
     or :appeal. subject is protocol-specific (e.g., a workflow-id).
     context is the opaque execution context from build-execution-context."))

;; ---------------------------------------------------------------------------
;; 2. EconomicModel (Optional)
;;
;; Required for economic simulations, adversarial metrics, and payoff analysis.
;; ---------------------------------------------------------------------------

(defprotocol EconomicModel
  "Interface for models supporting economic and adversarial metrics."

  (adversarial-event? [model event agent]
    "Return true if the event is an adversarial action.")

  (classify-event [model event result-kw error-kw]
    "Return a set of metric tags for the event.")

  (metric-vocabulary [model]
    "Return the set of protocol-specific metric keywords.")

  (accum-protocol-metrics [model metrics event-tags event accepted? attack? world-before world-after]
    "Update the metrics map with protocol-specific values.")

  (summarise-batch [model outcomes]
    "Compute summary statistics over a batch of trial outcomes.")

  (advisory [model world request-type context]
    "Protocol-specific analysis results (e.g. action suggestions, signals)."))

;; ---------------------------------------------------------------------------
;; 3. AnalysisModule (Optional)
;;
;; Required for theoretical validation, formal projections, and property testing.
;; ---------------------------------------------------------------------------

(defprotocol AnalysisModule
  "Interface for formal analysis, projections, and reference modeling."

  (compute-projection [module world]
    "Return [projection projection-hash] for differential testing.")

  (classify-transition [module action result-kw]
    "Return trace metadata for a completed transition.")

  (trace-projection [module result]
    "Return the terminal trace projection for a replay result.")

  (io-projection [module data target-type]
    "Protocol-specific I/O projection of data for external targets.

     Scope-1 cross-protocol contract note (optional):
     - target-type :funds-ledger-view is reserved for a read-only
       use-of-funds projection contract.
     - Adapters MAY implement it; when implemented, they should emit:

       {:as-of-block-time nat-int
        :by-token {token {:held nat-int
                          :released nat-int
                          :refunded nat-int
                          :withdrawn nat-int
                          :bond-posted nat-int
                          :bond-slashed nat-int}}
        :global {:claimable-total nat-int
                 :bond-locked-total nat-int
                 :bond-fees-total nat-int
                 :bond-distribution-total nat-int
                 :retained-slash-reserves nat-int}
        :conservation {:holds? boolean
                       :drift-total int
                       :drift-by-token {token int}
                       :violations vector}}

     - This target must be read-only and must not mutate world/session state.")

  (mechanism-property-validators [module]
    "Return validators for protocol-specific mechanism properties.")

  (equilibrium-concept-validators [module]
    "Return validators for protocol-specific equilibrium concepts.")

  (reference-model [module scenario]
    "Return results of an idealised reference implementation."))

;; ---------------------------------------------------------------------------
;; 4. BatchConflictModel (Optional)
;;
;; Used by deterministic same-timestamp batch replay to classify event-level
;; serialization/conflict domains.
;; ---------------------------------------------------------------------------

(defprotocol BatchConflictModel
  "Optional interface for deterministic batch conflict classification.
   Returns a set of structured tuples, e.g. #{[:workflow 1] [:resolver \"0xabc\"]}.
   Unknown or unsupported actions should map conservatively (for example,
   #{[:global :unknown]}), never to an empty set."

  (event-conflict-domains [model world event agent-index]
    "Return serialization/conflict domains touched by event in world.
     agent-index maps agent ID strings to agent maps — needed for actions
     (e.g. register-stake) where the resolver address is the performing
     actor, not a param key."))
