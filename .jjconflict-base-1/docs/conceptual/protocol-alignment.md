# Protocol Alignment Convention

## Why this exists

The Protocol Robustness Framework serves two distinct roles:

1. **Current protocol model** — it models existing Solidity/protocol behaviour.
2. **Proposed enhancement model** — it models protocol improvements discovered
   through framework findings.

These two roles must stay visibly distinct. A proposed feature that has no
Solidity implementation must never be presented as current protocol behaviour,
because doing so would mislead researchers, auditors, and future implementers.

This document defines a lightweight convention for marking code, scenarios, and
documentation with their protocol alignment status.

## `:protocol/*` statuses

| Status | Meaning |
|--------|---------|
| `:protocol/current` | Matches the current Solidity/protocol behaviour. |
| `:protocol/known-gap` | Intentionally demonstrates a current protocol design gap. |
| `:protocol/proposed` | Models a proposed enhancement not yet implemented in Solidity. |
| `:protocol/experimental` | Exploratory, not yet recommended for production. |
| `:protocol/deprecated` | Retained for historical comparison; no longer relevant. |

## `:solidity/*` statuses

| Status | Meaning |
|--------|---------|
| `:solidity/implemented` | Behaviour is implemented in the current Solidity deployment. |
| `:solidity/current-behaviour` | The current Solidity exhibits this behaviour (may be a gap). |
| `:solidity/not-implemented` | No Solidity implementation exists for this behaviour. |
| `:solidity/not-applicable` | The concept does not map to Solidity (e.g. analytic models, research scaffolding). |

## Core rule

**Proposed Clojure code must never be presented as current Solidity behaviour.**

Any code, scenario, or documentation modelling a feature not yet in Solidity
MUST carry `:protocol/status :protocol/proposed` and
`:solidity/status :solidity/not-implemented` (or `:solidity/not-applicable`).

## Recommended metadata keys

| Key | Type | Purpose |
|-----|------|---------|
| `:protocol/status` | Keyword | One of the `:protocol/*` statuses |
| `:solidity/status` | Keyword | One of the `:solidity/*` statuses |
| `:finding/id` | String | Reference to a documented finding (e.g. `"S-DR-073"`) |
| `:proposal/id` | String | Reference to a proposal (e.g. `"PRF-PROP-001"`) |
| `:scenario/kind` | Keyword | One of `:finding-reproduction`, `:mitigation-validation`, `:regression`, `:exploration` |

## Examples

Current protocol behaviour, implemented in Solidity:

```clojure
{:protocol/status :protocol/current
 :solidity/status :solidity/implemented}
```

Known protocol gap, current Solidity exhibits it:

```clojure
{:protocol/status :protocol/known-gap
 :solidity/status :solidity/current-behaviour
 :finding/id "S-DR-073"
 :scenario/kind :finding-reproduction}
```

Proposed mitigation, not yet in Solidity:

```clojure
{:protocol/status :protocol/proposed
 :solidity/status :solidity/not-implemented
 :finding/id "S-DR-073"
 :proposal/id "PRF-PROP-001"
 :scenario/kind :mitigation-validation}
```

## Status cheatsheet

```
                    ┌─────────────────────────────────────────────┐
                    │           Solidity status                   │
                    │  implemented  current   not-impl   N/A      │
┌─────────┬─────────┼─────────────────────────────────────────────┤
│         │ current │    ✅ normal     ✅ gap-doc   🚫 never   🚫  │
│  Proto  │known-gap│    🚫 never     ✅ finding  🚫 never   🚫  │
│  col    │proposed │    🚫 never     🚫 never   ✅ proposal ✅  │
│ status  │experim. │    🚫 never     🚫 never   ✅ research ✅  │
│         │deprecat.│    ✅ history    🚫         ✅ history ✅  │
└─────────┴─────────┴─────────────────────────────────────────────┘
```

Cells marked 🚫 are invalid combinations that should not appear in committed
code or scenarios.
