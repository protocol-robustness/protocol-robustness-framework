# Scenario review highlights

This guide is a **derived reviewer aid** for a completed canonical
single-scenario package. It does not add to, replace, or alter the authoritative
artifact closure. Verify `completion.json` and the package before relying on any
summary below.

## Review order

1. Read `completion.json` for terminal lifecycle status and the exact
   `manifest/run-package-index.json` byte commitment.
2. Read `manifest/run-package-index.json` for the package identity, required
   artifact references, and exact persisted-byte commitments.
3. Confirm package completeness, integrity, and runnability. These are distinct
   from the scenario's semantic result.
4. Use the indexed input snapshot, execution DAG, scenario finalization, and
   run finalization to examine the event and evidence path.
5. Treat this document and generated diagnostics as navigation aids only.

## High-value claim evaluation

Prioritize claims and invariant results that answer whether execution preserved:

| Review concept | Typical Sew claim / invariant | What to inspect |
|---|---|---|
| Conservation-aware accounting | `:conservation-of-funds`, `:solvency`, `:held-non-negative` | Before/after custody, held balances, payouts, and the claim/invariant result for the relevant transition. |
| Settlement finality | `:settlement-finality`, `:no-state-change-after-finalization` where applicable | The finalized state, any later attempted transition, and whether the later event was rejected or changed state. |
| Authorization and custody | resolver authority, `:no-unauthorized-release`, single-resolution payout checks | Caller identity, transition result, recipient, and custody movement. |
| Reversal and appeal behavior | reversal/slash execution and appeal-related claims | The decision being reviewed, reversal/appeal transition, post-transition state, and any violated claim. |
| Multi-member allocation | pro-rata conservation, quota, cap, and residual checks | The allocation projection, participant rows, requested/filled/deferred totals, ordering rule, and residual. |

Claim results are execution evidence: a semantic `:fail` may be the desired
result of a negative-control scenario. It does not mean that execution aborted
or that the package lacks integrity.

## Value at risk

PRF does not assign a universal market valuation. For Sew examples, use the
scenario's declared token amounts and persisted custody/accounting projections.
Report value at risk with all of:

- **asset and unit** — for example, USDC units; do not silently convert to USD;
- **protected amount** — escrow, stake, bond, shared liquidity, or disputed
  amount named by the scenario;
- **exposure type** — unauthorized payout, double release, finality change,
  shortfall, withheld custody, or allocation error;
- **expected versus observed custody** — grounded in the relevant transition
  evidence and conservation/invariant result;
- **artifact references** — input snapshot, execution-DAG node, claim or
  invariant result, and finalization references.

For `Y06_multi-party-pro-rata-shortfall`, the review guide records a concrete
shared-pool example: 3,000 requested USDC, 1,800 available/filled, and 1,200
deferred. That is an allocation exposure summary, not a generalized valuation
claim.

## Reversal review

`S-NC-001-freeze-active-dispute-negative-control` is the generated semantic-failure
control. Review it as a completed execution with a failed semantic result:

```text
execution termination = completed
semantic outcome      = fail
package integrity     = valid
package runnable      = true
```

Start with `diagnostics/scenario-semantic-failure/diagnostic.md` only after
package verification. Then trace the highlighted transition back through the
indexed execution DAG, scenario finalization, run finalization, and the
persisted claim/invariant evidence. The diagnostic is non-authoritative; the
sealed package is authoritative.

## Evidence-chain and package boundaries

The canonical single-scenario package exposes two complementary structures:

```text
input snapshot
→ event evidence / claim evaluation
→ scenario finalization
→ run finalization and evidence reconciliation
→ artifact registry and registry validation
→ package index
→ completion seal
```

- The **evidence chain** supports review of event-to-finalization commitments.
- `manifest/run-package-index.json` defines the required authoritative package
  closure and commits to exact bytes for each indexed artifact.
- `completion.json` is the terminal lifecycle seal for the exact persisted
  package-index bytes. It is not indexed by the package index, avoiding a
  circular commitment.
- An unsigned canonical package can be complete, integrity-valid, and runnable
  while release eligibility remains false because signer/operator assurance is
  absent.

## Current boundary

A run finalization can represent multiple scenario-finalization members, but the
only supported package-validation profile in this review surface is
`:single-scenario`. Benchmark, suite, parallel, and aggregate-round package
profiles remain unsupported and must fail closed rather than inherit this
single-scenario contract.
