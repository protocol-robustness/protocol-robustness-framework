# Held-Custody Extension — Design (Assurance-Grade Revision)

## Purpose

This document specifies the design of the held-custody extension
(`prf.extensions.held-custody`) and its relationship to force-authorisation,
incorporating the review adjustments. It is the corrected successor to the
in-session design write-up. It records both the **as-designed** properties and
the **recommended changes** not yet reflected in code, so a reader can tell
what is implemented today versus what is required for assurance-grade status.

Scope boundary: this document is about the **evidence/accounting** capability
(`:force-authorisation/effect-evidence`). It deliberately does **not** define
any onchain execution capability.

```
authorization
      ↓
custody mutation evidence        (this extension)
      ↓
execution intent                 (future capability, if ever built)
      ↓
external transaction             (out of scope — not this extension)
      ↓
execution/reconciliation evidence
```

---

## 1. The core contract is a mutation/action vocabulary — not a state machine

The held-custody contract is a **closed mutation vocabulary**, not a
custody-state machine. `:add-held`, `:sub-held`, `:finalize-released`, and
`:refund-held` describe **events/actions** that transition a held position. The
*state* that results is the position/balance plus its lifecycle status.

### Naming

Do **not** call adding an action "adding a new state." The correct operation is:

> **How to add an additional custody mutation/action**

Doing otherwise risks someone later adding `:released`, `:pending`, or
`:finalized` into the mutation vocabulary, conflating a transition with a
state.

### Separate the lifecycle state model

If position lifecycle matters, model it as its own **closed** transition
system, kept separate from the accounting mutation vocabulary:

```
position-state × action → next-position-state
```

For example:

```
:held  →  :finalize-released  →  :released
```

The accounting mutation vocabulary must never be overloaded with lifecycle
states. The two models are linked but distinct: the lifecycle state machine
expresses the position's current phase; the mutation vocabulary records the
accounting event that caused it.

### Current mutation vocabulary (frozen for contract v1)

| Action | Direction | Meaning |
|--------|-----------|---------|
| `:add-held` | `:in` | Increment held balance |
| `:sub-held` | `:out` | Decrement held balance |
| `:finalize-released` | `:out` | Finalize a release, clear held |
| `:refund-held` | `:out` | Refund held, clear custody |

Only two directions exist: `:in` (inward) and `:out` (outward). The set is
**fail-closed**: an unknown action is rejected
(`:held-custody/invalid-action-direction`).

---

## 2. Authority/sensitivity policy must derive from the contract (essential)

### Current gap (do not leave as-is)

Today the authority requirement is maintained as a **second, separately
maintained vocabulary**. The action→direction mapping lives in the mutation
contract, while the remote-authority requirement lives in the sensitivity
sentinel (`remote-authority-required-held-actions`). A developer could add

```
:credit-held :in
```

and get perfectly valid custody accounting while **forgetting** to mark it
remote-authority-sensitive. This is exactly the kind of vocabulary widening
that can silently become fail-open. It is the single biggest implementation
concern in the current design.

### Required change

Make the source of truth **richer** than a flat `action → direction` map.
Conceptually:

```clojure
{:add-held
 {:direction :in
  :authority-class :remote-required}

 :sub-held
 {:direction :out
  :authority-class :local}

 ...}
```

Then **derive everything** from that single contract:

- supported actions
- direction checking
- remote-authority requirement
- aggregate classification
- potentially risk/effect classification

### The invariant that makes it fail-closed

> 1. No supported custody mutation can exist without an explicit authority
>    classification.
> 2. Unknown action → reject.
> 3. Known action but missing authority policy → reject.

That is substantially stronger than relying on a sentinel to stay synchronized
manually. Today the aggregate `:sequence` (via the sensitivity sentinel) and the
mutation contract are two separate lists; consolidation into one contract is
the priority.

---

## 3. Freeze and commit the enabled extension/version set per run (essential)

Opt-in registration is good, but runtime registration raises an assurance
question: **which exact capability package was active when this result was
produced?** An auditor should not need ambient process state to answer that.

### Current state

The live extension registry (`resolver-sim.extensions.registry` `*extension-map*`
atom) can change at runtime and does **not** commit a per-run
capability-set root. There is no committed snapshot proving "held-custody v2
was one of the capabilities frozen into this execution." This item is therefore
**not yet satisfied** — classify it as essential and implement it.

### Required change

Each execution/run should bind a `:capability-set/root` covering at least:

- package / capability ID
- contract version
- schema / verifier version
- package root (or equivalent content identity)
- enabled / disabled state

Then held-custody evidence proves not merely:

> "this artifact conforms to held-custody v2"

but also:

> "held-custody v2 was one of the capabilities frozen into this execution."

If a run manifest already content-addresses the exact registry snapshot, this
is satisfied; otherwise it must be added.

---

## 4. Force-authorisation ordering: make causality structural, not timestamped (tighten)

### Current gap

`grant-before-execution?` and `execution-before-consumption?` currently
establish ordering **from timestamps only**:

```clojure
(<= (:evidence/grant-time envelope) (:evidence/execution-time envelope))
```

Content addressing proves the timestamp was not changed after commitment. It
does **not** prove the timestamp was truthful when created.

### Required change

The stronger model is **structural/hash-linked**:

```
grant root
   ↓
execution root references grant root
   ↓
consumption root references execution root
```

with identifiers and scope also cross-bound. Timestamps become additional
audit metadata — checked for monotonicity but **not** the security basis for
causal order.

If `grant-before-execution?` / `execution-before-consumption?` already verify
those predecessor/root relationships and merely *also* inspect timestamps,
this is fine. As currently written they establish ordering from timestamps
alone; treat this as essential until the reference/root relationships are
bound.

---

## 5. Terminology: "conservation" (tighten)

The listed guarantees prove very strong **ledger consistency**:

- mutation matches local delta;
- no negative resulting balance;
- predecessor continuity;
- deterministic replay;
- authorization scope binding.

They do **not**, from the description, prove conservation of an external
asset. `held += 100` can be internally perfect while no 100 tokens exist
anywhere.

### Required phrasing

Avoid saying these collectively guarantee "balance conservation" unless there
is an explicit equation tying debits/credits or source/sink balances together.
Instead state the guarantee as:

> **custody-ledger integrity, local balance consistency, non-negativity,
> authorization binding, and deterministic replay**

and explicitly **reserve asset conservation/backing** for reconciliation with
external money. This distinction reinforces the accounting → onchain boundary:
none of these guarantees prove actual money moved.

---

## 6. Accounting → onchain boundary (keep as designed — no change)

This part is correct. Keeping

- `:force-authorisation/effect-evidence`

distinct from a future

- `:force-authorisation/effect-execution`

is the right architecture.

- **Do not** add any bridge/onchain executor to held-custody itself.
- Keep execution as a **separate capability** that consumes held-custody
  evidence.
- If it is later built, its execution authorization should bind substantially
  more than today's custody mutation, including:
  - verified mutation root
  - chain / network identity
  - contract / address
  - method / calldata or canonical intent
  - token
  - amount
  - source
  - recipient
  - execution nonce
  - expiry / deadline
  - replay domain
- The execution receipt should bind the resulting transaction/receipt.

This preserves the chain:

```
authorization
      ↓
custody mutation evidence
      ↓
execution intent
      ↓
external transaction
      ↓
execution/reconciliation evidence
```

rather than letting "force-auth" gradually acquire ambiguous powers.

---

## 7. Versioning: separate artifact-kind from schema/contract/verifier (adjust)

Widening the accepted action vocabulary requires a **contract-version**
change. It should **not** automatically require changing `artifact-kind`.

Distinguish these four axes:

| Axis | Meaning |
|------|---------|
| `artifact-kind` | what this artifact is (stable across v1/v2) |
| `schema-version` | representation / semantic version |
| `contract-version` | verifier contract accepted |
| `verifier id/version` | verification implementation identity |

So a held-custody mutation can remain the same logical `artifact-kind` across
v1/v2, while v1 verifiers reject v2 (and vice versa) according to an explicit
compatibility matrix.

### Required cross-version tests

Golden vectors are not sufficient. Add explicit cross-version tests:

| Case | Expected |
|------|----------|
| v1 artifact → v1 verifier | accepted |
| v2 artifact → v2 verifier | accepted |
| v2 action → v1 verifier | rejected |
| unknown version | rejected |
| v1 artifact remains stable | accepted / read-only as specified |
| artifact version tampering | rejected |

And **never rewrite** old v1 artifacts into v2 representations.

---

## 8. Legacy handling and the four existing actions (keep as designed)

Do **not** expand the custody action vocabulary merely to accommodate
`withdraw` or `withdraw-many`. Those are higher-level yield operations. Their
custody consequence can resolve into existing custody mutations where
appropriate; making them held-custody actions would mix application/domain
commands with the primitive custody accounting vocabulary.

The separation is correct:

```
withdraw
   ↓
yield-domain settlement
   ↓
zero or more primitive custody mutations
   ↓
held-custody evidence
```

rather than `:held/action :withdraw`.

The legacy `force-auth-add-held` is appropriately **frozen**. Ensure the
successor verifier **never silently falls back** to interpreting malformed or
new-extension evidence as legacy evidence.

---

## 9. Priority order

Keep the architecture. No major redesign needed. Priority:

1. **Centralize** action direction + authority/sensitivity semantics in one
   closed contract (Section 2).
2. **Commit/freeze** the registered capability package set into each run
   (Section 3).
3. **Explicitly separate** mutation actions from position lifecycle states
   (Section 1).
4. **Verify** lifecycle causality is hash/reference-based, not merely
   timestamp-based (Section 4).
5. **Tighten** "conservation" terminology and add explicit v1↔v2 compatibility
   tests (Sections 5, 7).

After these, the design is structurally strong. The most important
architectural decision — force-authorisation evidence does not itself confer an
onchain execution capability — is already correct (Section 6).

---

## Status summary

| Item | Review disposition | Code status today |
|------|--------------------|-------------------|
| Mutation/action vs lifecycle-state naming | Essential (doc) | Naming already action-based; needs doc + any lifecycle model kept separate |
| Authority-class derived from contract | Essential (impl) | **Gap** — authority lives in sentinel as a separate list |
| Per-run capability-set root | Essential (impl) | **Gap** — registry not committed into each run |
| Structural (hash-linked) causality | Tighten/essential | **Gap** — ordering is timestamp-based today |
| "Conservation" terminology | Tighten (doc) | Needs rephrasing |
| effect-evidence vs effect-execution | Keep | Correct |
| Version axes + cross-version tests | Adjust | Partially present; needs explicit compatibility matrix + tests |
| Legacy freezing; no withdraw actions | Keep | Correct |
