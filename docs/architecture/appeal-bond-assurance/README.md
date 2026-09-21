# Appeal-bond assurance layers

This directory defines the evidence boundary for replacing embedded appeal-bond
custody with the `ResolverIncentiveModule -> BondLedger` architecture. A passing
Solidity unit suite alone is insufficient: the candidate must preserve the
canonical Sew semantic projection under the supported asset domain.

## Layer A — legacy behavioural equivalence

The established scenario corpus remains immutable migration evidence:

| Scenario | Scope |
| --- | --- |
| `DR-C-001` | ordinary escalation; per-funder scaling |
| `DR-C-002` | slash-appeal lifecycle; separate mechanism |
| `DR-C-003` | ordinary escalation reversal/refund |
| `DR-C-004` | ordinary escalation failed-appeal resolver distribution |
| `DR-C-005` | ordinary escalation forfeiture/reserve accounting |
| `DR-C-006` | ordinary escalation settlement failure atomicity |
| `DR-N-002` | reversal-slash appeal rejection; separate mechanism |

Layer A is run through the Sew policy adapter. A scenario is not rewritten just
to fit the primitive. Any non-equivalence must have a Layer C decision record.

## Layer B — primitive-native assurance

Primitive-native cases use the authoritative appeal-bond projection, not
aggregate ledgers or ad-hoc scenario counters.

| Family | Required property |
| --- | --- |
| `AP-ID-*` | bond ID binds workflow, dispute, ordinal, and posting operation; rounds isolate |
| `AP-DISP-*` | open bonds have zero terminal dispositions; disposed bonds have exactly one |
| `AP-CONS-*` | `posted = fee + custodied`; `custodied = allocation + remaining custody` |
| `AP-CLAIM-*` | entitlement is immutable; `allocated = claimable + claimed` |
| `AP-CAUSE-*` | every terminal disposition has a typed, non-empty cause root |
| `AP-ATOM-*` | rejected settlement/resolution preserves appeal-bond authoritative-state root |
| `AP-REPLAY-*` | duplicate posting/settlement/claim attempts are rejected without mutation |
| `AP-POLICY-*` | bond obligation uses the posting-time policy snapshot, not later parameters |

Layer B intentionally covers semantics unavailable in the former aggregate
model, including exact per-bond lineage and distinction between disposition and
claim payment.

## Layer C — intentional divergence records

Each record states the old behavior, candidate behavior, reason, affected
invariant, migration consequence, and replacement scenario. No behavior is
silently adapted to make a legacy scenario pass.

## Gate

Canonicalization requires all applicable Layer A scenarios, all Layer B cases,
and zero unresolved Layer C decisions. The resulting comparison artifact must
include the reference/candidate identities, corpus root, projection version,
accepted/rejected transition counts, and scenario/transition-level mismatches.
