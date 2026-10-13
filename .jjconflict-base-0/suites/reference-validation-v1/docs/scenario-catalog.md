# Scenario Catalog

Canonical CI set (8 scenarios). Source of truth: `../manifest.edn`.

| ID | Claim | Threat | Evidence | Simulator | Confidence |
|---|---|---|---|---|---|
| governance-sandwich-v1 | active-escrow-rules-immutable | governance-timing-attack | simulator-backed | yes (S61) | high |
| malicious-resolver-verdict-v1 | malicious-verdict-economically-bounded | resolver-corruption | simulator-backed | yes (S25) | high |
| dispute-flooding-v1 | liveness-under-adversarial-load | dispute-capacity-exhaustion | simulator-backed | yes (S62) | high |
| bond-withdrawal-race-v1 | unresolved-liabilities-block-withdrawal | slash-evasion | simulator-backed | yes (S60) | high |
| same-block-ordering-v1 | settlement-mutual-exclusion | ordering-dependent-double-settlement | simulator-backed | yes (S56) | high |
| autopush-settlement-v1 | pull-first-settlement-safety | unsafe-external-value-movement | simulator-backed | yes (S05) | high |
| appeal-failure-cascade-v1 | corrupt-verdict-must-survive-escalation | appeal-layer-breakdown | simulator-backed | yes (S20) | high |
| yield-accrual-efficiency-v1 | yield-accrual-covers-protocol-fees | fee-drag-on-long-escrows | simulator-backed | yes (S88) | high |

