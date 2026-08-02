# Solidity Equivalence Core (`solidity-equivalence-core-v1`)

Status: **Canonical** · Version 1 · Profile root `31d07038dcde86ac6f34b229fded0fce98b679c2bd83130b607f0b9a2a27e19f`

## 1. Overview

`solidity-equivalence-core-v1` is the invariant profile that drives portable,
cross-implementation equivalence checks between the Clojure simulation and the
Solidity contracts. Each entry defines an exact equation or semantic property
that is checked **independently by both implementations** while replaying a CDRS
v0.2 trace, with no shared evaluation code between them.

It is the "core" profile: it covers only the accounting and dispute-resolution
state machine that both implementations model. Yield, evidence-module,
slashing-module, and related-claims behavior are explicitly **out of scope**
(see [Exclusions](#6-exclusions)) because they have no Solidity equivalent in
the current harness.

The authoritative definition lives in `etc/solidity-invariant-profile.edn`.
The binding scope and the concrete trace set are committed in the cross-repo
manifest `etc/trace-solidity-manifest.edn`.

## 2. Identity & canonical hash

| Field | Value |
|---|---|
| `:profile/id` | `:solidity-equivalence-core-v1` |
| `:profile/version` | `1` |
| `:profile/content-root` | `31d07038dcde86ac6f34b229fded0fce98b679c2bd83130b607f0b9a2a27e19f` |
| Source of truth | `etc/solidity-invariant-profile.edn` |
| Binding scope | `:solidity-equivalence-scope-v1` (`etc/trace-solidity-manifest.edn`) |
| Projection schema | `:evm-projection-v1` |

## 3. How the profile is bound

The profile is not free-standing; it is committed into an equivalence scope:

- **Scope** — `:solidity-equivalence-scope-v1` in `etc/trace-solidity-manifest.edn`
  binds the profile root, the trace-set root (18 trace hashes), the projection
  schema root, and the source/replica implementation commits.
- **Trace stamping** — every exported CDRS v0.2 trace carries an
  `:invariant_profile` block with this profile's id, version, and content root
  (`protocols_src/.../sew/io/trace_export.clj:412`), so a trace can be
  validated against the exact invariant profile that produced it.
- **Replay** — Forge `TraceEquivalenceTest.sol` evaluates the Solidity-side
  check for each invariant; the Clojure sim evaluates the corresponding
  `:clojure-check` on the same canonical projection fields.

## 4. Equivalence classification

| Class | Meaning |
|---|---|
| `:exact` | The same equation, evaluated on the same canonical projection fields with the same arithmetic, produces the same Boolean result in both implementations. No tolerance, no semantic mapping. |
| `:exact-quantified` | The equation is exact, but its quantification is restricted to the primary traced workflow rather than all escrows in the system. Validated per-step on manifest traces. |
| `:semantic` | The property holds in both implementations but is tested differently: a prohibited-action **revert** (Solidity) vs a **guard-rejection predicate** (Clojure). Outcome-equivalent but not structurally identical. |

## 5. Invariants

Seven invariants are checked in both implementations.

| Invariant | Clojure check | Solidity check | Kind | Class |
|---|---|---|---|---|
| `conservation-of-funds` | `conservation-of-funds` | `checkStateEquations(→_checkConservationOfFunds)` | state-equation | `:exact` |
| `held-reconstruction` | `held-non-negative` | `checkHeldReconstruction` | state-equation | `:exact-quantified` |
| `dispute-level-bounded` | `dispute-level-bounded` | `checkStateEquations(→_checkDisputeLevelBounded)` | state-equation | `:exact` |
| `terminal-payout-exclusivity` | `cancellation-mutex` | `checkStateEquations(→_checkTerminalPayoutExclusivity)` | state-equation | `:exact` |
| `state-transition-valid` | `escrow-state-transition-valid` | `checkTransitionEquations(→_checkStateTransitionValid)` | transition-equation | `:exact` |
| `escalation-monotonic` | `escalation-level-monotonic` | `checkTransitionEquations(→_checkEscalationMonotonic)` | transition-equation | `:exact` |
| `terminal-state-immutable` | `terminal-states-unchanged` | `checkTransitionEquations(→_checkTerminalStateImmutable)` | transition-equation | `:exact` |

### 5.1 `conservation-of-funds`

`deposited = held + fees + released + refunded`.

- Equation root: `PRF-ACCT-CONSERVATION-V1`
- Quantification: `:token` scope, `:sum-over-escrows` aggregate
- Required fields: `deposited`, `held`, `fees`, `released`, `refunded`
- Deposited/released/refunded are tracked by test-harness accumulators that
  mirror the Clojure metrics.

### 5.2 `held-reconstruction`

`totalHeld == amountAfterFee` for the primary workflow while active.

- Equation root: `PRF-ACCT-HELD-RECON-V1`
- Quantification: `:workflow` scope, `:trace-primary-workflow` selector
- Applicability: requires `:established-workflow` and `:pending-or-disputed`
- Exact for single-escrow traces; the system-wide variant is deferred.

### 5.3 `dispute-level-bounded`

Dispute level ≤ `MAX_DISPUTE_ROUNDS` (2).

- Equation root: `PRF-DR-LEVEL-BOUNDED-V1`
- Quantification: `:workflow` scope, bound `:max-dispute-rounds`

### 5.4 `terminal-payout-exclusivity`

Terminal escrows (`RELEASED`/`REFUNDED`) must not have pending settlements.

- Equation root: `PRF-SM-TERMINAL-PAYOUT-EXCL-V1`
- Quantification: `:workflow` scope

### 5.5 `state-transition-valid`

`NONE→PENDING→{DISPUTED,RELEASED,REFUNDED}`; `DISPUTED→{RELEASED,REFUNDED}`.

- Equation root: `PRF-SM-STATE-GRAPH-V1`
- Quantification: `:workflow` scope

### 5.6 `escalation-monotonic`

Dispute level never decreases.

- Equation root: `PRF-DR-ESCALATION-MONO-V1`
- Quantification: `:workflow` scope

### 5.7 `terminal-state-immutable`

`RELEASED`/`REFUNDED` escrows do not transition.

- Equation root: `PRF-SM-TERMINAL-IMMUTABLE-V1`
- Quantification: `:workflow` scope

## 6. Exclusions

These invariants are **not** part of the core profile, with the stated reason:

| Invariant | Reason |
|---|---|
| `related-claims-consistency` | `:sim-only-artifact` — Clojure evidence-layer structure, no EVM equivalent |
| `yield-position-consistency` | `:absent-from-solidity-model` — yield positions tracked only in Clojure |
| `shortfall-fidelity` | `:absent-from-solidity-model` — Clojure yield-policy concept |
| `migration-parity` | `:sim-only-artifact` — storage migration (uint256→bytes32) is simulation-only |
| `held-adjustments-reconstruct-total-held` | `:sim-only-accounting` — Clojure ledger overlay for held-position deltas |
| `stake-lifecycle` | `:pre-established-in-harness` — `register_stake`/`withdraw_stake` are no-ops in Forge; stake is pre-established in `setUp()` |

Stake actions are handled as no-ops in the Forge harness and no stake lifecycle
equivalence is asserted; traces requiring dynamic stake modification remain
excluded.

## 7. Coverage & verification

- 18 manifest-bound trace entries across the Sew-domain, reference-validation,
  and EF-review suites.
- 21 Forge equivalence tests pass against 10 Solidity-tested manifest traces
  (`etc/trace-solidity-manifest.edn` `:solidity-coverage`).
- Pre-existing fixtures (14) are legacy and reported as warnings, not counted
  toward the verified set.
- Excluded traces are recorded with per-trace reasons (appeal-window-duration
  divergence, evidence/slashing/yield-only actions, etc.).

## 8. Related documentation

| Document | Relation |
|---|---|
| `etc/solidity-invariant-profile.edn` | Authoritative profile definition (source of truth) |
| `etc/trace-solidity-manifest.edn` | Binding scope, trace set, coverage, exclusions |
| `docs/framework/invariant-parity.md` | Broader simulation↔Solidity invariant parity table |
| `docs/specs/SOLIDITY_SHADOW_REGISTRY_SPEC_V1.md` | Shadow-registry of simulation↔Solidity differences |
| `protocols_src/.../sew/io/trace_export.clj` | Trace exporter that stamps `:invariant_profile` |
