# SENSITIVITY_SENTINEL_SPEC_V1

Status: Draft V1

## 1. Purpose

The sensitivity sentinel is a classification and enforcement layer that prevents accidental disclosure of sensitive artifacts across boundary crossings.

It is not an access control system.

It is not an encryption layer.

It is not a claims engine.

It is a gate that asks: "Would moving this object to this destination disclose something dangerous?"

------

## 2. Design Principles

### 2.1 Sentinel, Not Metadata

Sensitivity metadata describes an object. The sentinel controls movement across boundaries.

An artifact can carry `:sensitivity/private` as metadata, but without enforcement that label is advisory. The sentinel makes it enforceable.

### 2.2 Honest Classification

The sentinel MUST be conservative. When uncertain, it MUST classify higher rather than lower. A blocked public export is recoverable. A leaked private vulnerability is not.

### 2.3 No Sensitive Leakage in Reports

The sentinel report MUST use reason codes and hashes, not plaintext descriptions.

Bad:

```
{:reason "Scenario shows exploit path for Protocol X using withdrawal race in function Y"}
```

Better:

```
{:sentinel/reasons [:contains-reproducible-exploit-path
                    :contains-protocol-identifier]}
```

Detailed explanations remain local or in sealed logs.

### 2.4 Separation of Concerns

| Layer | Question |
|---|---|
| Integrity | Is this object structurally valid and untampered? |
| Sensitivity | Would this movement disclose something dangerous? |
| Policy | Is this actor allowed to use this object in this way? |
| Privacy | How is the data encrypted, redacted, or selectively disclosed? |

The sentinel belongs in the sensitivity layer and MUST NOT decide truth of claims, verify signatures, manage encryption keys, replace access control, or pretend to perfectly detect sensitivity.

------

## 3. Sensitivity Levels

### 3.1 Defined Levels

| Level | Description | Example |
|---|---|---|
| `:sensitivity/public` | No restrictions. Safe for any sink. | Public benchmark results, published specs |
| `:sensitivity/internal` | Safe within the organisation but not for public disclosure. | Internal audit summaries, team reports |
| `:sensitivity/private` | Sensitive. Disclosure would harm the project or stakeholders. | Unfixed vulnerabilities, counterparty data |
| `:sensitivity/embargoed` | Time-limited sensitivity. Must not be disclosed before embargo date. | Pre-disclosure reports, coordinated disclosure materials |
| `:sensitivity/critical-private` | Maximum sensitivity. Catastrophic if disclosed. | Live exploits, private researcher identities, reproducible exploit paths |

### 3.2 Level Ordering

Levels are ordered by severity:

```
:public < :internal < :private < :embargoed < :critical-private
```

A higher level implies all restrictions of lower levels plus additional ones.

------

## 4. Reason Codes

### 4.1 Code Definitions

| Code | Description |
|---|---|
| `:contains-live-vulnerability` | Artifact describes a vulnerability that has not been patched |
| `:potential-live-vulnerability` | Artifact describes a scenario that may indicate a vulnerability |
| `:contains-reproducible-exploit-path` | Artifact includes enough detail to reproduce an exploit |
| `:contains-protocol-identifier` | Artifact names the specific protocol or contract affected |
| `:contains-counterparty-identifier` | Artifact identifies a specific counterparty, user, or entity |
| `:contains-private-researcher-identity` | Artifact identifies the researcher who discovered the issue |
| `:contains-unredacted-scenario` | Artifact includes scenario input that has not been redacted |
| `:contains-claim-result` | Artifact includes a claim evaluation result |
| `:contains-attestation` | Artifact includes an attestation (credible attributable statement) |
| `:contains-unpublished-evidence` | Artifact includes evidence that has not been published |
| `:contains-linkable-subject-hash` | Artifact includes a subject hash that can be linked to real data |
| `:contains-public-sink-reference` | Artifact references a public sink (IPFS CID, on-chain address) |
| `:contains-timing-metadata` | Artifact includes timing data that could reveal operational patterns |

### 4.2 Usage

Reason codes explain why something was blocked without exposing the sensitive content itself. Multiple codes may apply to a single artifact.

------

## 5. Sink Classes

### 5.1 Defined Sinks

| Sink | Risk Level | Description |
|---|---|---|
| `:local` | `:safe` | Local filesystem. No disclosure risk. |
| `:sealed-log` | `:safe` | Sealed, append-only log. No disclosure risk. |
| `:private-encrypted-bundle` | `:safe` | Encrypted bundle with restricted decryption. |
| `:sealed-private-workspace` | `:safe` | Encrypted collaborator workspace. |
| `:encrypted-bundle` | `:low` | Encrypted but may be widely distributed. |
| `:public-bundle` | `:medium` | Unencrypted verifiable bundle. |
| `:ipfs` | `:high` | Public IPFS publication. |
| `:nostr-public-relay` | `:high` | Public Nostr relay. |
| `:on-chain-registry` | `:high` | Public on-chain registry write. |
| `:public-ci-artifact` | `:medium` | Public CI/CD artifact. |
| `:git-commit` | `:low` | Git commit (supports private repos, amend, revert, audit trail). |

### 5.2 Sink Risk Groups

```clojure
(def safe-sinks
  #{:local :sealed-log :private-encrypted-bundle :sealed-private-workspace})

(def low-risk-sinks
  #{:encrypted-bundle :git-commit})

(def medium-risk-sinks
  #{:public-bundle :public-ci-artifact})

(def high-risk-sinks
  #{:ipfs :nostr-public-relay :on-chain-registry})

(def public-sinks
  (set/union medium-risk-sinks high-risk-sinks))
```

------

## 6. Classification

### 6.1 Classification Function

```clojure
(classify artifact)
```

Returns a sensitivity level. The classifier examines artifact content and structure:

- Evidence nodes with `:result :status :fail` → at least `:sensitivity/internal`
- Evidence nodes with failure details → at least `:sensitivity/private`
- Attestations → at least `:sensitivity/internal` (attestations are credible attributable statements)
- Attestations with claim results → at least `:sensitivity/private`
- Claim results with `:holds? false` → at least `:sensitivity/internal`
- Artifacts with provenance including scenario identifiers → at least `:sensitivity/internal`
- Unredacted scenarios → at least `:sensitivity/private`
- Default (unknown) → `:sensitivity/critical-private` (conservative default)

### 6.2 Content-Aware Classification (V1 Only)

V1 uses simple structural heuristics:

- Presence of certain key patterns
- Result status values
- Attestation presence
- Claim result values
- Provenance detail level

Content-aware scanning (NLP, pattern matching, embeddings) is deferred to V2.

------

## 7. Sink-Level Allow Rules

### 7.1 Disclosure Matrix

| Level | Safe Sinks | Low Risk Sinks | Medium Risk Sinks | High Risk Sinks |
|---|---|---|---|---|
| `:public` | allowed | allowed | allowed | allowed |
| `:internal` | allowed | allowed | blocked | blocked |
| `:private` | allowed | blocked | blocked | blocked |
| `:embargoed` | allowed | blocked | blocked | blocked |
| `:critical-private` | allowed | blocked | blocked | blocked |

### 7.2 Default Behaviour

Unknown levels and unknown sinks default to `:blocked` (conservative).

------

## 8. Sentinel Report Shape

```clojure
{:sentinel/version        "sensitivity-sentinel.v1"
 :sentinel/policy-hash    "sha256-hex..."
 :sentinel/evaluated-at   "2026-06-25T12:00:00Z"
 :sentinel/input-kind     :attestation-record | :evidence-node | :claim-result | :bundle
 :sentinel/input-hash     "sha256-hex..."
 :sentinel/requested-sink :ipfs | :public-bundle | ...
 :sentinel/decision       :allowed | :blocked | :requires-override
 :sentinel/level          :sensitivity/private | ...
 :sentinel/reasons        [:code1 :code2 ...]
 :sentinel/allowed-sinks  [:local :sealed-log ...]
 :sentinel/redaction-required? true | false
 :sentinel/override-required?
 {:required?  true | false
  :mode       :single | :multi-party-approval
  :approved-by nil | [:id1 :id2]}}
```

------

## 9. Override Rules

### 9.1 When Override Is Required

Override is required when an artifact with level `:sensitivity/private` or higher is directed at a sink that would normally be blocked.

### 9.2 Override Modes

| Mode | Description |
|---|---|
| `:single` | A single authorised actor may override (for embargoed time-based releases) |
| `:multi-party-approval` | Multiple independent approvals required (for critical-private) |

### 9.3 Audit Requirement

Every override MUST be recorded in an auditable log with:

- The artifact hash
- The requested sink
- The override reason
- The approving party or parties
- The timestamp

------

## 10. Integration Points

### 10.1 Assertion Functions

```clojure
(assert-export-allowed! artifact {:sink :public-bundle})
(assert-publish-allowed! evidence-node {:sink :ipfs})
(assert-relay-allowed! sealed-event {:sink :nostr})
(assert-attestation-allowed! attestation {:sink :on-chain-registry})
```

Each function classifies the artifact, checks the sink against the level, and either returns the sentinel report or throws.

### 10.2 Pipeline Placement

```
Artifact / Evidence / Claim / Attestation
        ↓
Integrity validation
        ↓
Sensitivity sentinel     ← here
        ↓
Policy decision
        ↓
Allowed sink
```

------

## 11. Interaction with Private Discovery

Recommended flow for private vulnerability discovery:

1. Researcher runs scenario
2. Evidence + claims generated locally
3. Sensitivity sentinel classifies as `:sensitivity/critical-private`
4. System blocks public export
5. System allows sealed log entry
6. System allows encrypted collaborator workspace
7. Later, after disclosure/remediation:
8. Selective disclosure bundle generated
9. Sentinel re-checks downgraded/redacted bundle

------

## 12. Non-Goals (V1)

- Content-aware NLP / pattern matching classification
- Automatic redaction
- Encryption key management
- Dynamic sink discovery
- Multi-party override workflow state
- Quorum verification of override decisions
- Integration with external disclosure systems (CVE, etc.)

------

## 13. Acceptance Criteria

A V1 sensitivity sentinel is acceptable when:

1. All five sensitivity levels are defined and ordered
2. All sink classes are defined with risk groupings
3. Classification produces a level from artifact content
4. Disclosure matrix blocks private artifacts from public sinks
5. Reason codes explain blocks without leaking sensitive content
6. Override mechanism exists with audit logging
7. Conservative default (unknown→blocked, unknown→critical-private)
8. Sentinel report is deterministic
9. Assertion functions gate the four main operations
