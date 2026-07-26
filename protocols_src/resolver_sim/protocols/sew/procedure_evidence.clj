(ns resolver-sim.protocols.sew.procedure-evidence
  "Protocol-specific evidence adapter for Sew force-authorisation procedures.
   Provides correlation-path extraction, expected evidence-type mapping,
   and step-to-event-type mapping for the Sew force-authorised custody path.
   Used by the generic witness verifier via injection.")

(def sew-evidence-adapter
  {:correlation-paths
   {"force-authorisation-granted"
    [[:inputs :force-auth/auth-id]]
    "force-authorisation-executed"
    [[:inputs :force-auth/auth-id]]
    "escrow-released"
    [[:inputs :finalize/authorization-id]
     [:inputs :force-auth/auth-id]]
    "escrow-refunded"
    [[:inputs :finalize/authorization-id]
     [:inputs :force-auth/auth-id]]}
   
   :step-evidence-types
   {:prf.step/authorisation-granted "force-authorisation-granted"
    :prf.step/authorised-execution "force-authorisation-executed"
    :prf.step/authorised-consumption-custody-adjustment "escrow-released"}})
