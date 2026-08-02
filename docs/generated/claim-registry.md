# Claim Registry (Generated)

Source of truth: `src/resolver_sim/definitions/registry.clj` (`claims`, `claim-scenario-map`).

Definitions hash: `743884729`

| Claim ID | Title | Type | Evidence mode | Supporting scenarios | Falsifying scenarios | Related invariants |
|---|---|---|---|---|---|---|
| `appeal-window-enforced` | Appeal window enforces settlement timing | `time-safety` | `support` | `S32_forking-strategist-premature-settlement-rejected`, `S36_profit-maximizer-pre-window-execute-rejected`, `S74_appeal-deadline-boundary` | _none_ | `finality` |
| `bribery-neutralized-by-l1` | L1 challenge reverses biased L0 ruling | `dispute-resolution` | `support` | `S42_resolver-buyer-bribery-loop` | _none_ | `finality`, `conservation` |
| `dr3-reversal-slash-disabled` | DR3 v3 disables non-zero reversal slashes | `safety` | `support` | `S41_dr3-reversal-slash-disabled` | _none_ | `reversal-slash-disabled` |
| `fork-isolation` | Forking outcomes remain escrow-isolated | `safety` | `support` | `S33_forking-strategist-two-escrow-fork-isolation`, `S62_cross-token-isolation-under-dispute-load`, `S62_cross-token-fee-on-transfer-under-dispute-load`, `S62_cross-token-parallel-appeal-depths-under-dispute-load` | _none_ | `conservation`, `solvency` |
| `forking-l1-reversal` | L1 reversal can overturn L0 decision under valid escalation | `dispute-resolution` | `support` | `S26_forking-strategist-l1-reversal` | _none_ | `finality`, `solvency` |
| `forking-l2-path` | Escalation to L2 path remains valid and bounded | `dispute-resolution` | `support` | `S27_forking-strategist-l2-fork`, `S31_forking-strategist-all-levels-confirm` | _none_ | `finality`, `conservation` |
| `forking-strategist-all-levels-confirm` | Third escalation after max level must reject | `safety` | `support` | `S31_forking-strategist-all-levels-confirm` | _none_ | `finality` |
| `forking-strategist-double-loss` | Double loss: L1 confirms L0 | `safety` | `support` | `S30_forking-strategist-double-loss` | _none_ | `solvency` |
| `forking-strategist-l1-reversal` | L1 reversal after L0 release | `safety` | `support` | `S26_forking-strategist-l1-reversal` | _none_ | `finality` |
| `forking-strategist-l2-fork` | L2 fork after confirming L0 and L1 | `safety` | `support` | `S27_forking-strategist-l2-fork` | _none_ | `finality` |
| `forking-strategist-late-escalation-rejected` | Late escalation rejected; L0 release stands | `safety` | `support` | `S28_forking-strategist-late-escalation-rejected` | _none_ | `finality` |
| `forking-strategist-premature-settlement-rejected` | Premature settlement rejected; L1 fork finalizes | `safety` | `support` | `S32_forking-strategist-premature-settlement-rejected` | _none_ | `finality` |
| `forking-strategist-seller-escalates` | Seller-initiated L1 fork to release | `safety` | `support` | `S29_forking-strategist-seller-escalates` | _none_ | `finality` |
| `optimal-strategy-under-load-bounded` | Optimal resolver strategy is honest under low load | `safety` | `support` | `Y08_optimal-strategy-load-stress` | _none_ | `solvency` |
| `resolver-capacity-enforced` | Resolver concurrent dispute capacity is enforced | `safety` | `support` | `S62_resolver-capacity-concurrent-dispute-load` | _none_ | `solvency` |
| `reversal-slash-track1` | Same-evidence reversal slash executes immediately | `safety` | `support` | `DR-D-001-reversal-slashing-auto-track`, `DR-N-001-reversal-slash-appeal-lifecycle` | _none_ | `solvency`, `conservation` |
| `reversal-slash-track2-executes` | Rejected Track 2 reversal appeal allows slash execution | `safety` | `support` | `DR-N-002-reversal-slash-appeal-rejected` | _none_ | `slash-status-consistent?`, `solvency` |
| `reversal-slash-track2-reversed` | Track 2 reversal slash can be reversed on appeal | `safety` | `support` | `DR-G-001-manual-reversal-slash-t2`, `DR-N-001-reversal-slash-appeal-lifecycle` | _none_ | `slash-status-consistent?`, `solvency` |
| `workflow-dispute-isolation-shared-resolver` | Fork isolation across two disputed escrows | `safety` | `support` | `S33_forking-strategist-two-escrow-fork-isolation` | _none_ | `conservation` |
