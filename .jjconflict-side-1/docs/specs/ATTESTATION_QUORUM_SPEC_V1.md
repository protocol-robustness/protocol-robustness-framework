# ATTESTATION_QUORUM_SPEC_V1

Status: Draft V1

## 1. Purpose

Quorum verification answers: "Do enough independent attestors agree on the same claim about the same subject, under a declared quorum policy?"

It does not decide the ultimate truth of the world. It decides whether a set of attestations satisfies a transparent quorum rule.

------

## 2. Design Principles

### 2.1 Deterministic

Given the same policy and attestation set, the quorum module MUST produce the same outcome. Randomness, subjective scoring, and external state are excluded.

### 2.2 Verifiable

The quorum report is content-addressed. Its hash commits to all inputs and decisions. Anyone can recompute the outcome from the same inputs.

### 2.3 Conservative

Conflict handling defaults to `:fail-closed`. The system reports conflict rather than picking a winner. k-of-n is preferred over weighted voting until governance and staking are stable.

### 2.4 Independent

An attestor cannot count twice. Delegates of the same root attestor are not independent. Multiple keys from the same attestor do not create multiple votes.

------

## 3. Quorum Policy

### 3.1 Policy Shape

```clojure
{:policy/id    :quorum/k-of-n-v1
 :policy/hash  "sha256-hex..."
 :quorum/scope
 {:subject-kind          :claim-result | :evidence-node
  :subject-hash          "sha256-hex..."
  :claim-definition-hash "sha256-hex..."}

 :quorum/rule
 {:mode :k-of-n
  :k    2
  :n    3}

 :quorum/independence
 {:distinct-attestor-id?        true
  :distinct-operator-id?        true
  :exclude-delegates-of-same-root? true}

 :quorum/conflict-policy :fail-closed}
```

### 3.2 Fields

| Field | Type | Required | Description |
|---|---|---|---|
| `:policy/id` | keyword | yes | Identifies the policy type. V1 supports `:quorum/k-of-n-v1` |
| `:policy/hash` | string | yes | Canonical hash of the policy (excluding self) |
| `:quorum/scope` | map | yes | Defines what subject and claim the quorum applies to |
| `:quorum/rule` | map | yes | The voting rule (k-of-n) |
| `:quorum/independence` | map | yes | Rules for counting independent attestors |
| `:quorum/conflict-policy` | keyword | yes | `:fail-closed` (default) or `:fail-open` |

### 3.3 Scope

All attestations in a quorum MUST match the scope exactly:

- `:subject-kind` — must match `:attestation/subject-kind`
- `:subject-hash` — must match `:attestation/subject-hash`
- `:claim-definition-hash` — must match `:attestation/claim-id` (resolved to hash) or be absent for scope without claim reference

### 3.4 k-of-n Rule

```
:k = number of independent attestations required
:n = number of independent attestations available (informational)
```

V1 supports `:mode :k-of-n` only. Weighted voting is deferred.

------

## 4. Attestation Eligibility

An attestation counts toward quorum only if ALL of the following are true:

| # | Check | Description |
|---|---|---|
| 1 | Integrity valid | Passes Phase 9 integrity verification |
| 2 | Signature valid | Cryptographic signature verifies (when present) |
| 3 | Attestor active | Attestor status was `:active` at signing time |
| 4 | Key authorized | Signing key was authorized for the attestor at signing time |
| 5 | Subject matches | `:attestation/subject-kind` and `:attestation/subject-hash` match scope |
| 6 | Claim matches | `:attestation/claim-id` matches scope's claim definition |
| 7 | Sensitivity allows | Artifact sensitivity level allows quorum use |
| 8 | Attestor independent | Attestor is independent under the policy's independence rules |

An attestation that fails any check is excluded with a reason code.

------

## 5. Independence Rules

### 5.1 Attestor Identity Fields

```clojure
{:attestor/id           "..."   ;; unique attestor identifier
 :attestor/operator-id  "..."   ;; operator or entity controlling the attestor
 :attestor/root-id      "..."   ;; root of trust for delegated attestors
 :attestor/delegated-from "..." ;; parent attestor in delegation chain
}
```

V1 treats these as optional but enforces them when present.

### 5.2 Rules

| Rule | Behaviour |
|---|---|
| Same `:attestor/id` | Cannot count twice. Second attestation is excluded. |
| Same `:attestor/operator-id` | Should not count twice if present. |
| Same `:attestor/root-id` with `:exclude-delegates-of-same-root? true` | Delegates of the same root are not independent. |
| Multiple keys from same attestor | Do not create multiple votes. First attestation counted, rest excluded. |
| Conflicting attestations from same attestor | Attestor marked as conflicted. Neither vote counted for either side. |

### 5.3 Attestor Registry Lookup

Attestor identity fields are resolved from the attestor registry at signing time. The registry snapshot included in the bundle MUST be at or after the signing time of the latest attestation.

------

## 6. Outcome Model

### 6.1 Defined Outcomes

| Outcome | Meaning |
|---|---|
| `:quorum/confirmed` | Threshold reached for positive/pass result |
| `:quorum/rejected` | Threshold reached for negative/fail result |
| `:quorum/conflicted` | Competing results both have material support, or conflict policy triggered |
| `:quorum/insufficient-quorum` | Not enough eligible independent attestations |
| `:quorum/invalid-input` | Scope mismatch, malformed policy, or no valid attestations |

### 6.2 Outcome Determination

```
eligible-pass   = count of eligible attestations with :pass result
eligible-fail   = count of eligible attestations with :fail result

if eligible-pass >= k  → :quorum/confirmed
if eligible-fail >= k  → :quorum/rejected
if conflicts detected  → :quorum/conflicted
if no eligible atts    → :quorum/invalid-input
else                   → :quorum/insufficient-quorum
```

------

## 7. Conflict Handling

### 7.1 Conflict Cases

| Case | Detection |
|---|---|
| Same attestor signs both pass and fail | Duplicate attestor-id with different claim results |
| Two independent groups reach different conclusions | Both pass and fail groups have material support |
| Claim definitions differ | Claim-definition-hash mismatch across attestations |
| Registry state differs | Attestor registry snapshots differ across attestations |

### 7.2 Default Rule

`:quorum/conflict-policy :fail-closed` — report conflict, do not pick a winner.

------

## 8. Quorum Report Shape

```clojure
{:quorum/report-version "attestation-quorum-report.v1"
 :quorum/policy-hash "sha256-hex..."
 :quorum/scope
 {:subject-kind          :claim-result
  :subject-hash          "sha256-hex..."
  :claim-definition-hash "sha256-hex..."}

 :quorum/outcome :quorum/confirmed
                | :quorum/rejected
                | :quorum/conflicted
                | :quorum/insufficient-quorum
                | :quorum/invalid-input

 :quorum/counts
 {:submitted  5    ;; total attestations submitted
  :eligible   3    ;; passed all eligibility checks
  :excluded   2    ;; failed at least one check
  :pass       2    ;; eligible attestations with :pass result
  :fail       1    ;; eligible attestations with :fail result
  :inconclusive 0} ;; eligible attestations with neither pass nor fail

 :quorum/eligible-attestors
 [{:attestor/id "..."
   :attestation/hash "..."
   :claim/result :pass}]

 :quorum/excluded-attestations
 [{:attestation/hash "..."
   :reason :duplicate-attestor}
  {:attestation/hash "..."
   :reason :signature-invalid}]

 :quorum/conflicts
 [{:type :same-attestor-duplicate-vote
   :attestor/id "..."
   :attestations [...]}]

 :quorum/hash "sha256-hex..."}
```

The report is content-addressed. Its own hash excludes `:quorum/hash` (self-referential field exclusion).

------

## 9. Public API

### 9.1 Core Verification

```clojure
(verify-quorum
  {:policy       quorum-policy
   :attestations [attestation-1 attestation-2 ...]
   :attestor-registry attestor-registry-map})
```

Returns a quorum report map.

### 9.2 Grouping

```clojure
(group-attestations-by-quorum-scope attestations)
```

Groups attestations by `{:subject-kind :subject-hash :claim-id}`. Each group can be evaluated independently.

### 9.3 Explanation

```clojure
(explain-quorum-report quorum-report)
```

Returns a human-readable summary of the quorum outcome and contributing attestations.

------

## 10. Integration with Attestation Bundle

The quorum report can be embedded inside an attestation bundle as:

- An entry in `:bundle/objects` with `:object/kind :quorum-report`
- A reference in `:bundle/verification-profile` via `:quorum? true`
- A check in the bundle verifier pipeline

------

## 11. Non-Goals (V1)

- Stake-weighted voting
- Reputation scores
- Dynamic committee selection
- Probabilistic confidence intervals
- Subjective quality scores
- DAO governance integration
- Automatic slashing
- Weighted voting (deferred until attestor registry, governance, and staking are stable)

------

## 12. Acceptance Criteria

A V1 quorum module is acceptable when:

1. Attestations can be grouped by exact subject and claim scope
2. Invalid or unauthorized attestations are excluded with reason codes
3. Basic independence is enforced (same attestor cannot vote twice)
4. Same-attestor conflicts are detected
5. k-of-n is correctly applied
6. A deterministic quorum report is produced
7. The report hash is content-addressed (excludes self)
8. The module can be embedded inside an attestation bundle
