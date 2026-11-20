(ns resolver-sim.protocols.sew.invariant-scenarios.doc-summaries
  "Single source of truth for human-readable scenario summaries in generated docs.

   Keys are :scenario-id strings from invariant scenario maps. CI checks that every
   baseline S01–S23 scenario has an entry here.")

(def s01-s23-summaries
  {"s01-baseline-happy-path"
   "Create escrow → release; no disputes"

   "s02-dr3-dispute-release"
   "Dispute opened; resolver releases to seller"

   "s03-dr3-dispute-refund"
   "Dispute opened; resolver refunds buyer"

   "s04-dispute-timeout-autocancel"
   "Dispute expires without resolution; auto-cancel"

   "s05-pending-settlement-execute"
   "Pending settlement window; honest execution after deadline"

   "s06-mutual-cancel"
   "Both parties agree to cancel"

   "s07-unauthorized-resolver-rejected"
   "Non-authorized resolver call is rejected"

   "s08-state-machine-attack-gauntlet"
   "Invalid state transitions attempted; all correctly rejected"

   "s09-multi-escrow-solvency"
   "Multiple concurrent escrows; solvency maintained"

   "s10-double-finalize-rejected"
   "Attempt to finalize an already-finalized escrow is rejected"

   "s11-zero-fee-edge-case"
   "Escrow with fee_bps=0; correct handling"

   "s12a-snapshot-isolation-fee-zero"
   "Fee-param change after escrow A created does not apply retroactively to A"

   "s12b-snapshot-isolation-fee-500"
   "Escrow B created after fee change uses new params; A unchanged"

   "s13-pending-settlement-refund"
   "Pending settlement resolved as refund"

   "s14-dr3-module-authorized"
   "DR3 module resolves with correct authority"

   "s15-dr3-module-unauthorized-rejected"
   "DR3 module with wrong authority is rejected"

   "s16-ieo-create-release"
   "IEO escrow: create and release without dispute"

   "s17-ieo-dispute-no-resolver-timeout"
   "IEO dispute with no resolver; timeout resolution"

   "s18-dr3-kleros-l0-resolves"
   "Kleros L0 resolver resolves dispute at level 0 (zero appeal window)"

   "s19-dr3-kleros-escalation-rejected-l0-resolves"
   "Preemptive escalation rejected (no pending settlement); L0 resolves; L1 blocked on terminal escrow"

   "s20-dr3-kleros-max-escalation-guard"
   "Repeated preemptive escalations rejected; wrong-tier resolver rejected; dispute may stay open"

   "s21-dr3-kleros-pending-cleared-on-escalation"
   "L0 resolves to pending; buyer escalates (clears pending); L1 resolves; keeper executes settlement"

   "s22-status-leak-agree-cancel-over-dispute"
   "Regression: agree-to-cancel status cleared when dispute is raised"

   "s23-preemptive-escalation-blocked"
   "Seller preemptive escalation rejected; L0 resolves; post-terminal escalation rejected"})

(def robustness-edge-case-summaries
  (select-keys s01-s23-summaries
               ["s04-dispute-timeout-autocancel"
                "s07-unauthorized-resolver-rejected"
                "s08-state-machine-attack-gauntlet"
                "s10-double-finalize-rejected"
                "s11-zero-fee-edge-case"
                "s14-dr3-module-authorized"
                "s15-dr3-module-unauthorized-rejected"
                "s17-ieo-dispute-no-resolver-timeout"
                "s19-dr3-kleros-escalation-rejected-l0-resolves"
                "s20-dr3-kleros-max-escalation-guard"
                "s21-dr3-kleros-pending-cleared-on-escalation"
                "s22-status-leak-agree-cancel-over-dispute"
                "s23-preemptive-escalation-blocked"]))

(defn summary
  [scenario-id]
  (get s01-s23-summaries scenario-id))

(defn require-s01-s23-summary!
  [scenario-id]
  (or (summary scenario-id)
      (throw (ex-info "Missing doc summary for baseline scenario"
                      {:scenario-id scenario-id
                       :hint "Add an entry to invariant-scenarios.doc-summaries/s01-s23-summaries"}))))
