# Force-Authorisation and Held-Custody Threat Model

**Status:** bounded research specification  
**Applies to:** PRF held-custody ledger, forensic run bundle, Sew state machine   
**Version:** 1.0  

## 1. Scope and claim discipline

This document defines the threat model for exceptional force-authorisation and
held custody. It does **not** claim correctness of a deployed smart contract,
key-management system, governance process, or external token custodian.

A passing replay establishes only that an executed, declared trace satisfied
registered invariants under this model. It is evidence for the bounded claims
in `data/claims/sew-claims.edn`; it is not a universal safety, liveness, or
economic-security proof.

## 2. Assets and security objectives

| Asset | Objective | In-model enforcement |
|---|---|---|
| Escrow principal position | Cannot be spent by a different workflow or custody position | Reason-derived position policy; token and position underflow guards |
| Force-authorisation | Can authorize only its immutable, grant-time custody scope once | Active-record, time-window, scope/hash, and atomic-consumption guards |
| Held ledger | Every custody mutation is attributable and replayable | Canonical held adjustments, derived materialized views, reconstruction invariants |
| Forensic evidence | A reviewer can connect grant, execution, consumption, and custody adjustment | Protocol-state witness, section commitments, artifact chain, semantic validator |

## 3. Actors and trust boundaries

| Actor / component | Trusted for | Not trusted for / attacker capability |
|---|---|---|
| Governance actor | Granting or revoking exceptional authority according to external policy | May grant a harmful but syntactically valid authorization; governance intent is not inferred by the model |
| Resolved executor | Submitting an authorized execution request | May choose the wrong release/refund direction, replay a request, or submit after expiry/revocation |
| Accounting primitive | Deriving and enforcing custody mutation constraints | Cannot protect state if code or persisted state is arbitrarily replaced outside its invocation boundary |
| Evidence producer / bundle writer | Recording deterministic state witness and commitments | May emit malformed, omitted, reordered, or inconsistent evidence; validators must reject it |
| Bundle signer / verifier | Authenticating a sealed bundle | Key compromise, key revocation, or external timestamping are outside this model |
| Yield module | Supplying yield and shortfall state | May create deferred liabilities; terminal principal closure does not claim yield liabilities are zero |

## 4. Attacker model

The model considers an attacker who can:

1. submit any public action with arbitrary parameters and a resolved actor;
2. retry a command or reuse an authorization ID;
3. choose a release/refund direction inconsistent with the grant;
4. attempt a token-level outflow from a different workflow, bond, or custody position;
5. present caller-supplied authorization provenance that has no persisted grant;
6. tamper with exported evidence/state before independent validation; or
7. produce a reordered, incomplete, or malformed evidence set.

The model assumes the attacker **cannot** alter the in-memory transition
atomically after its guards run, forge the configured governance identity inside
the simulator, or break the configured hash/signature primitive. Those are
production deployment assumptions, not properties proven here.

## 5. Safety properties and falsifiers

| ID | Bounded property | Falsified by |
|---|---|---|
| `:force-authorisation/exact-scope-single-use` | A force-authorisation executes at most once and only for its grant-time token, amount, workflow, account, recipient, reason, and direction | A trace reaches a held adjustment whose authorization ID is reused or whose derived scope differs from the persisted grant scope/hash |
| `:held-custody/position-isolation` | An outflow cannot drive a reason-derived custody position negative, even when aggregate token custody is sufficient | A trace produces a negative `:by-position`, `:by-account`, or `:by-workflow` balance, or spends workflow A custody as workflow B |
| `:held-custody/terminal-principal-closure` | Terminal escrows retain no `:escrow-principal` position balance | A terminal workflow has non-zero principal position custody |
| `:forensic/authorisation-custody-linkage` | A forensic force-authorisation execution has a committed state witness and a consistent authorization → consumption → held-adjustment link | The validator accepts an absent/inconsistent witness, orphan consumption, missing scope, or evidence/state disagreement |

## 6. In-model guarantees

Provided all calls use the model transition boundary and the registered checks
are run:

- `add-held` / `sub-held` reject aggregate and position-level overdrafts.
- Address-scoped custody reasons require an explicit owner; account/position
  policy overrides that conflict with the reason are rejected.
- Force-authorisation accounting reloads the persisted record. Caller supplied
  provenance is evidence, not authority.
- Active records are valid only in their time window. A consumed record must
  have one matching adjustment and consumption entry.
- A complete held ledger must reconstruct materialized balances and pass
  artifact hash, predecessor continuity, local delta, non-negative, and replay
  checks.
- Forensic validation requires a protocol-state witness when force-authorisation
  evidence is present and validates state/evidence linkage.

## 7. Explicit non-claims

The following are not established by this feature:

- governance decisions are legitimate, fair, or legally authorized;
- the authorization process has threshold signatures, quorum, timelock, or
  independent human review;
- external token transfers, wallet balances, bridge custody, or on-chain event
  inclusion match the simulator's state;
- atomicity survives database crashes, distributed retries, or concurrent live
  processes without a transactional persistence layer;
- hashes alone provide provenance without a verified signature/key lifecycle
  and independently verified bundle contents;
- liveness within a bounded number of blocks under arbitrary censorship,
  partition, unavailable governance, or unavailable failover executors; or
- economic deterrence against a coalition, Sybil identities, or corrupt
  governance.

## 8. Evidence requirements for a research result

A result claiming one of the properties above must publish:

1. scenario input, source/dependency hashes, model version, and parameter set;
2. the exact claim ID, assumptions, threat capability exercised, and falsifier;
3. invariant results for every successful transition;
4. held adjustments/artifacts when custody changes occur;
5. force-authorisation grant, revoke, and execution evidence when applicable;
6. the signed bundle root, protocol-state witness, and validator report; and
7. negative/adversarial cases that demonstrate rejection of the corresponding
   forbidden action.

## 9. Production follow-up requirements

Before adopting this model as a production control, replace or augment its
trusted boundaries with authenticated governance approvals, durable transactional
storage, concurrency control, audited key rotation/revocation, independently
verifiable hash encodings, incident response, and integration tests against the
actual custody/settlement environment.
