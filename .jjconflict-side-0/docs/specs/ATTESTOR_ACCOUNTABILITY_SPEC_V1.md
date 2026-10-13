# ATTESTOR_ACCOUNTABILITY_SPEC_V1

Status: Draft V1

## 1. Purpose

Attestor accountability provides evidence-backed attestor misconduct detection, violation case management, simulated stake ledger updates, and deterministic accountability decisions.

It is not a governance system.

It is not a token staking contract.

It is not a court.

It is an audit trail and deterministic accounting model for attestor behaviour, designed to be verifiable by external adjudicators.

------

## 2. Design Principles

### 2.1 Evidence-Backed

All accountability actions MUST be backed by verifiable evidence. A violation case references attestation hashes, registry snapshots, quorum reports, and bundle hashes. No action is taken on unverified claims.

### 2.2 Objective Violations Only

Only objectively verifiable attestor misconduct is slashable. Honest disagreement, model uncertainty, framework bugs, and ambiguous definitions are explicitly excluded from punishment.

### 2.3 Simulated Economics First

V1 uses `:accountability-points` as the stake unit. No real asset custody, no token prices, no smart contract deployment. The ledger is a deterministic state machine that external systems can adopt later.

### 2.4 Deterministic Decisions

Given the same violation case, registry state, and policies, the accountability module MUST produce the same decision. Randomness, subjective scoring, and external oracle inputs are excluded.

### 2.5 Appeal-Preserving

Every decision preserves an appeal window and finalization state. The system records the decision but does not enforce it until the appeal window closes.

------

## 3. Architecture

### 3.1 Three-Layer Model

```
Governance parameters
        ↓
Attestor registry and stake ledger
        ↓
Evidence-backed violation cases
        ↓
Accountability decision
        ↓
Registry/stake update
```

### 3.2 Layer Responsibilities

| Layer | Responsibility | V1 Scope |
|---|---|---|
| Governance | Attestor admission, suspension, retirement, quorum policies, violation definitions, stake requirements, appeal windows, finalization rules | Narrow: only the parameters needed for accountability |
| Registry + Ledger | Attestor state machine, stake balances | State transitions with evidence hashes |
| Violation Cases | Evidence collection, verification, decision | Objective violations only |

------

## 4. Attestor States

### 4.1 State Machine

```
:candidate ──→ :active ──→ :suspended ──→ :slashed
                 ↑             │              │
                 │             │              │
                 └─────────────┘              │
                                              │
                  :retired ←──────────────────┘
```

### 4.2 State Definitions

| State | Description |
|---|---|
| `:attestor/candidate` | Registered but not yet active. Cannot attest. |
| `:attestor/active` | Fully authorised. Can attest, participate in quorum. |
| `:attestor/suspended` | Temporarily removed. Open violation case pending. |
| `:attestor/slashed` | Permanently penalised. Stake deducted, attestor removed. |
| `:attestor/retired` | Voluntary or administrative exit. No further action. |

### 4.3 State Transition Record

```clojure
{:attestor/id             "..."
 :attestor/status         :attestor/suspended
 :status/reason           :open-violation-case
 :status/evidence-hash    "sha256-hex..."
 :status/effective-at     "2026-06-25T12:00:00Z"
 :status/previous         :attestor/active}
```

Every transition MUST reference an evidence hash (violation case, quorum report, or governance decision).

------

## 5. Stake Ledger

### 5.1 Ledger Shape

```clojure
{:stake/ledger-version "attestor-stake-ledger.v1"
 :stake/entries
 [{:attestor/id    "..."
   :stake/amount   1000
   :stake/unit     :accountability-points
   :stake/status   :bonded
   :stake/bond-hash "sha256-hex..."}]

 :stake/ledger-hash "sha256-hex..."}
```

### 5.2 Operations

| Operation | Effect |
|---|---|
| `bond` | Lock stake for an attestor. Status → `:bonded` |
| `slash` | Deduct from bonded stake. Record violation reference. |
| `release` | Return remaining stake on retirement or slashing. |
| `top-up` | Add to bonded stake (if minimum requirement increased). |

### 5.3 Unit

V1 uses `:accountability-points`. These are integer amounts with no external price feed.

```
1 point = 1 unit of accountable stake
minimum requirement = 1000 points (default)
slash amount = up to 100% of bonded stake per violation
```

------

## 6. Slashable Violations

### 6.1 Objective Violations (Slashable)

| Code | Description | Required Evidence |
|---|---|---|
| `:violation/conflicting-attestations` | Same attestor signed both pass and fail for the same subject and claim | Two attestations, same scope, different claim result |
| `:violation/unauthorized-key-used` | Attestation signed with a key not authorised for the attestor at signing time | Attestation + registry snapshot |
| `:violation/attestation-after-suspension` | Attestation created while attestor was in suspended state | Attestation + registry state at signing time |
| `:violation/registry-policy-breach` | Attestation violates a registered policy (subject-kind, claim-id, attestor restriction) | Attestation + policy registry |
| `:violation/tampered-evidence-submitted` | Attestation references evidence with non-matching hash | Attestation + integrity check failure |
| `:violation/quorum-manipulation-duplicate-identity` | Same entity operates multiple attestor identities to manipulate quorum | Quorum report + attestor registry |

### 6.2 Non-Slashable (Honest Disagreement)

The following MUST NOT be treated as slashable violations:

| Situation | Reason |
|---|---|
| `:honest-disagreement` | Attestors reached different conclusions in good faith |
| `:model-uncertainty` | Framework model produced ambiguous output |
| `:later-discovered-framework-bug` | A bug was found after the attestation was created |
| `:ambiguous-claim-definition` | Claim definition allowed multiple interpretations |
| `:insufficient-evidence` | Attestor concluded there was not enough evidence to decide |
| `:good-faith-inconclusive-result` | Attestor reported inconclusive rather than pass/fail |

This distinction is critical. Research-grade accountability MUST NOT punish honest uncertainty.

------

## 7. Violation Case

### 7.1 Case Shape

```clojure
{:violation/case-version "attestor-violation-case.v1"
 :violation/case-id      "..."
 :violation/accused-attestor-id "..."
 :violation/type         :violation/conflicting-attestations

 :violation/evidence
 {:attestation-hashes     ["sha256..." "sha256..."]
  :registry-snapshot-hash "sha256..."
  :quorum-report-hash     "sha256..."
  :bundle-hash            "sha256..."}

 :violation/status       :open   ;; :open :under-review :decided :appealed :finalized

 :violation/required-checks
 [:attestation-integrity
  :signature-verification
  :registry-at-time
  :same-scope-conflict
  :sensitivity-sentinel]

 :violation/timeline
 [{:event :opened :at "2026-06-25T12:00:00Z"}
  {:event :evidence-attached :at "2026-06-25T12:05:00Z"}]

 :violation/hash "sha256-hex..."}
```

### 7.2 Case Status Flow

```
:open ──→ :under-review ──→ :decided ──→ :appealed ──→ :finalized
                              │                          ↑
                              └──────────────────────────┘
                                        (no appeal)
```

------

## 8. Accountability Decision

### 8.1 Decision Shape

```clojure
{:accountability/decision-version "attestor-accountability-decision.v1"
 :violation/case-hash "sha256-hex..."
 :accountability/outcome :exonerate
                        | :suspend-only
                        | :suspend-and-slash
                        | :slash-only
                        | :finalize-retirement

 :accountability/actions
 [{:action/type :attestor/suspend
   :attestor/id "..."}
  {:action/type :stake/slash
   :attestor/id "..."
   :stake/amount 100
   :stake/unit :accountability-points}]

 :accountability/reasons
 [:objective-conflicting-attestations
  :same-subject
  :same-claim-definition
  :no-valid-supersession]

 :accountability/appeal
 {:appeal/allowed? true
  :appeal/window   "P14D"
  :appeal/status   :not-opened}

 :accountability/hash "sha256-hex..."}
```

### 8.2 Outcome Definitions

| Outcome | Meaning |
|---|---|
| `:exonerate` | No violation found. Attestor cleared. |
| `:suspend-only` | Violation confirmed but no stake penalty. Temporary suspension. |
| `:suspend-and-slash` | Violation confirmed. Suspension + stake deduction. |
| `:slash-only` | Stake deducted. No suspension (rare, e.g. retrospective policy breach). |
| `:finalize-retirement` | Attestor retirement finalized. No further action. |

------

## 9. Process

### 9.1 Full Flow

1. **Open violation case** — create case with accused attestor, violation type, and initial evidence hashes
2. **Attach evidence** — link attestations, registry snapshots, quorum reports, bundles
3. **Verify attestation integrity** — run Phase 9 integrity checks on all referenced attestations
4. **Verify signatures** — confirm cryptographic signatures on referenced attestations
5. **Verify registry state at signing time** — confirm attestor status and key authorization
6. **Verify violation type requirements** — confirm the evidence supports the alleged violation
7. **Run sensitivity sentinel** — check disclosure safety before producing decision
8. **Produce accountability decision** — deterministic outcome with actions and reasons
9. **Allow appeal window** — preserve appeal state with configurable window
10. **Finalize and update** — apply state changes to attestor registry and stake ledger

------

## 10. Public API

### 10.1 Case Management

```clojure
(open-violation-case
  {:accused-attestor-id "..."
   :violation/type      :violation/conflicting-attestations
   :evidence            {:attestation-hashes [...]}})
```

### 10.2 Evaluation

```clojure
(evaluate-violation-case
  {:case       violation-case
   :registries {:attestors ... :claim-definitions ...}
   :policies   {:quorum ... :sensitivity ...}})
```

### 10.3 Finalization

```clojure
(finalize-accountability-decision
  {:decision        decision
   :stake-ledger    stake-ledger
   :attestor-registry attestor-registry})
```

Returns updated stake ledger and attestor registry.

------

## 11. Integration

### 11.1 With Attestation Bundle

Violation cases and accountability decisions can be embedded in bundles:

- `:bundle/objects` entries with `:object/kind :violation-case` or `:accountability-decision`
- `:bundle/verification-profile` extension with `:accountability? true`

### 11.2 With Attestor Registry

The attestor registry is the source of truth for attestor state. The accountability module reads from it and writes state transitions.

### 11.3 With Stake Ledger

The stake ledger is a separate data structure. The accountability module produces stake update actions. A later layer (governance, external contract) executes them.

------

## 12. Non-Goals (V1)

- DAO voting
- Live token staking with real assets
- Smart contract custody
- Price feeds or external oracles
- Automatic enforcement (V1 produces decisions, does not execute them)
- Appeal court design or multi-level appeals
- Reputation-weighted quorum
- Insurance or waterfall coverage
- Dynamic minimum stake requirements
- Cross-chain attestation accountability

------

## 13. Acceptance Criteria

A V1 accountability module is acceptable when:

1. Objective slashable violations are defined and distinguished from honest disagreement
2. Violation cases can be opened with evidence hashes
3. Evidence-backed integrity checks run before any decision
4. Deterministic accountability decisions are produced
5. A simulated stake ledger supports bond, slash, release, and top-up
6. Attestor registry state transitions are recorded with evidence hashes
7. Appeal window and finalization state are preserved
8. The module produces artifacts suitable for external adjudication
9. All outputs are content-addressed
10. The module composes with attestation bundles, quorum reports, and sensitivity sentinel
