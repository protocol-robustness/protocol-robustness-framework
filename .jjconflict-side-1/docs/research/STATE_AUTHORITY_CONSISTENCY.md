# State-Authority Consistency: Fences and Zanzibar Semantics

## Scope and conclusion

This note compares PRF governed-authority fences with Zanzibar-style externally consistent authorization semantics. Zanzibar's useful abstraction is not “permissions are globally fresh”; it is that an authorization decision is evaluated against a specified, externally consistent revision, and a caller can require a sufficiently fresh revision (a ZedToken) for a later read.

PRF already has the stronger primitive needed for state-changing admission: an authority fence is a one-time, store-issued capability bound to one current authoritative head and consumed in the same CAS that advances that head. The model can be simplified by treating several existing concepts as two layers:

1. **state-addressed authorization evidence**: a durable answer about an exact predecessor state; and
2. **current-head finalization fence**: an ephemeral, linearizable capability to apply one authorized transition from that state.

Do not describe a fence as a general authorization token. It is an optimistic-concurrency capability with authority evidence attached.

## Definitions

Let `E0` be the current authoritative state envelope, `S0` its execution state root, `A0` its authority material, and `R` an authority report derived from `A0`.

### Authorized at what point?

An action is **authorized at E0** exactly when:

- the authority resolution basis names `S0` and `E0` through authenticated store resolution;
- the resolved context, governance, signer-key set, review round, position-time basis/index, and control-plane evidence are authenticated members of E0 material;
- the report evaluated against that frozen material has `:authority-status :authorised`; and
- the report authorizes the exact subject/target committed by the request.

For `:current-admission`, resolution requires E0 to be the store head. For historical audit or replay, the same authorization can be evaluated at an anchored historical state, but it is not a finalization right.

### Fresh relative to what?

Freshness is relative to the authoritative publication head, not wall-clock time and not an arbitrary database read.

At fence issuance, PRF observes `(E0, S0, publication-sequence)` and CAS-checks that the same tuple remains current when the fence record is installed. At finalization, it checks that E0 remains the current head before consuming the fence. Therefore a fence is fresh relative to the single authoritative lineage head at issuance and consumption.

Time-based freshness is separate. Position acceptance times, governance constitution times, and policy-defined expiry determine whether a report is valid *at E0*. They do not make an E0 fence current after the head has advanced.

### What does a fence guarantee?

An issued finalizable fence guarantees all of the following together:

- a store-owned record exists for an authorized report, not merely an observation or caller-delivered report;
- the report, resolved context, evaluation basis, signer-key set, review governance, and pre-state are bound to the record;
- the record names E0/S0 and is issued only while E0 is current;
- at most one compatible finalization can consume it;
- exact retry after successful consumption returns the stored terminal result;
- conflicting reuse rejects;
- finalization must preserve the bound predecessor and bind the exact expected pre/post state and authority context;
- the head advance and fence consumption occur in one CAS update.

For the derived finalization path, the caller does not supply the successor semantic state; the store recovers the authorized target and mechanically derives the successor.

### What does a fence not guarantee?

A fence does not prove that the underlying policy was morally correct, that a signer was uncompromised, that an external side effect occurred, or that a different store has observed the result. It also does not grant authority over a different predecessor, a different transition, or a successor selected after issuance.

## Zanzibar comparison

| Zanzibar concept | PRF counterpart | Difference |
|---|---|---|
| Relationship tuple state | authenticated authority material plus authoritative envelope | PRF commits a closed, content-addressed authority bundle |
| Revision | authoritative envelope root plus publication sequence | PRF has a single CAS-controlled head in this store model |
| Check at revision | governed-authority evaluation against E0 material | PRF binds the evaluation to explicit roots |
| ZedToken / minimum revision | current-admission basis and current-head checks | PRF rejects stale E0 rather than waiting for a replica to catch up |
| External consistency | issuance/finalization CAS against one head | Valid within the authority store; cross-store external consistency requires a shared sequencer or consensus boundary |
| Write condition | fence consumption with predecessor check | PRF fence is stronger than a bare read token because it is single-use and binds transition finalization |

The useful imported rule is: authorization answers need an explicit state point, and freshness claims need an explicit reference state. PRF should not claim Zanzibar-style external consistency beyond the publication boundary actually controlled by its CAS store.

## What invalidates a fence?

| Event | Effect |
|---|---|
| Another transition advances the authoritative head | Fence becomes unusable; finalization returns `:state-not-at-required-head` |
| The same fence successfully finalizes | Fence is consumed; exact retry returns its stored result, conflicting reuse rejects |
| Binding pre-state, post-state, predecessor, or context differs | Finalization rejects with the corresponding mismatch |
| Authority report is not authorized or missing | Fence cannot be issued or consumed |
| Authority semantics provenance no longer matches the retained configuration/policy | V2 finalization rejects |
| Resolution basis is not valid/current-admission V2 | Fence cannot be issued |
| Historical governance rotation after a fence is issued, without head advance | It does not independently invalidate the fence, because the authority state is frozen at E0 |

The last row is deliberate. Governance rotation becomes authoritative only by publishing a successor head. If it advances the head, the old fence is stale. If it has not advanced the head, it is not yet authoritative for current admission.

## What survives governance rotation?

The following survive as durable evidence:

- the resolved authority context and report for E0;
- the signer, governance, review-round, and position-time roots that were authenticated at E0;
- the authorization evidence and finalization receipt;
- historical replay/audit of the transition under its anchored predecessor state.

The following do not survive for new admission once rotation is published:

- an unconsumed E0 fence;
- a `:current-admission` resolution basis naming E0;
- any claim that old governance is the current authority.

This is snapshot isolation for authorization evidence combined with serializable single-head finalization. It avoids retroactively changing whether past signatures were valid while preventing old authority from authorizing a new current transition.

## What does retry preserve?

Retry preserves identity, not a renewed freshness claim.

| Retry type | Preserved property |
|---|---|
| Re-evaluate before any head advance | May produce a new fence for the same E0 and exact authorization inputs; it is another current-head observation/capability |
| Retry finalization after successful consumption with the same binding | Returns the original terminal result; no second transition is created |
| Retry finalization with a conflicting binding | Rejects as fence already consumed |
| Retry after another head advance | Does not reuse old authority; requires a new authorization evaluation at the new head |
| Historical replay | Preserves the old state-addressed authorization result, not a right to publish now |

The important simplification is to reserve “idempotence” for terminal result replay and reserve “freshness” for current-head admission. A retry can be idempotent without being fresh.

## Fence invariants

1. **State addressability:** every authority report used for admission names one authenticated predecessor state.
2. **Authority closure:** all governance, signer, round, time, and policy inputs used to decide authority are authenticated members of that state.
3. **Subject closure:** the report and transition binding authorize one exact target/transition, not a class of future targets.
4. **Currentness at issuance:** a finalizable fence is installed only if its predecessor remains the authoritative head in the issuing CAS.
5. **Currentness at consumption:** a finalizable fence is consumed only if that same predecessor remains head.
6. **Single consumption:** one fence yields at most one state transition.
7. **Retry determinism:** same consumed fence plus same binding returns the retained result; conflict does not create a second result.
8. **Lineage conservation:** the successor names the fenced predecessor and follows the single publication chain.
9. **Historical durability:** rotation cannot rewrite the authority basis of an already committed historical transition.
10. **Scope boundary:** guarantees are only as strong as the single authoritative store/CAS boundary; distributed replicas need an explicit externally consistent sequencer to claim the same property globally.

## Recommended vocabulary and simplification

Use the following terms consistently:

| Current term or concern | Recommended term | Meaning |
|---|---|---|
| Authority evaluation/report | state-addressed authorization evidence | Durable evaluation of an exact subject at E0 |
| Resolution observation | observation handle | Read-only result; never finalization authority |
| Authority fence | current-head finalization fence | Single-use capability combining E0 currentness and exact transition finalization |
| Freshness | head-currentness | Relation to the authoritative publication head |
| Governance rotation invalidation | lineage advancement invalidation | Rotation matters when it becomes the new head |
| Retry | terminal-result replay or re-admission | Separate these two cases |

This vocabulary removes the false question “is authority current?” Authority evidence is valid *at a state*. A finalization fence is usable *while that state remains the head*.

## Design implications

- Keep historical audit and transition replay state-addressed; they should not require present governance to validate past authority.
- Keep current admission V2-only and bind it to an explicit resolver identity, as the current model does.
- Keep fence issuance and finalization as store-owned CAS operations. Do not expose a caller-constructible fence artifact.
- Require a fresh authorization evaluation after any head advance, including governance rotation, configuration activation, or any other state transition that changes E0.
- Do not add wall-clock expiry to fences unless there is a concrete operational need. Head-currentness already solves the stale-decision race; time expiry solves a different risk and adds liveness failure modes.
- If PRF later uses replicated or distributed authorization storage, define the linearization point explicitly. A local CAS alone is not a Zanzibar external-consistency proof across replicas.

Relevant implementation references: `src/resolver_sim/benchmark/governed_authority_resolution.clj`, `src/resolver_sim/benchmark/governed_authority_state.clj`, `src/resolver_sim/benchmark/governed_authority_derived_finalization.clj`, and `src/resolver_sim/benchmark/configuration_activation_publication.clj`.
