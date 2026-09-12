| `s04-dispute-timeout-autocancel` | Dispute expires without resolution; auto-cancel |
| `s07-unauthorized-resolver-rejected` | Non-authorized resolver call is rejected |
| `s08-state-machine-attack-gauntlet` | Invalid state transitions attempted; all correctly rejected |
| `s10-double-finalize-rejected` | Attempt to finalize an already-finalized escrow is rejected |
| `s11-zero-fee-edge-case` | Escrow with fee_bps=0; correct handling |
| `s14-dr3-module-authorized` | DR3 module resolves with correct authority |
| `s15-dr3-module-unauthorized-rejected` | DR3 module with wrong authority is rejected |
| `s17-ieo-dispute-no-resolver-timeout` | IEO dispute with no resolver; timeout resolution |
| `s19-dr3-kleros-escalation-rejected-l0-resolves` | Preemptive escalation rejected (no pending settlement); L0 resolves; L1 blocked on terminal escrow |
| `s20-dr3-kleros-max-escalation-guard` | Repeated preemptive escalations rejected; wrong-tier resolver rejected; dispute may stay open |
| `s21-dr3-kleros-pending-cleared-on-escalation` | L0 resolves to pending; buyer escalates (clears pending); L1 resolves; keeper executes settlement |
| `s22-status-leak-agree-cancel-over-dispute` | Regression: agree-to-cancel status cleared when dispute is raised |
| `s23-preemptive-escalation-blocked` | Seller preemptive escalation rejected; L0 resolves; post-terminal escalation rejected |