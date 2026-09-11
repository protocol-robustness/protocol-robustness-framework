# ATTESTATION_SPEC_V1

Status: Draft V1

## 1. Purpose

Attestations are signed endorsements regarding evidence, claims, or executions.

An attestation is not evidence.

An attestation is not a claim.

An attestation is a statement by an actor that they have verified, observed, approved, or certified something.

Examples:

- CI verified execution
- Replay verifier confirmed determinism
- External auditor certified benchmark
- Researcher attests to the results of another researcher
- Human reviewer approved report

------

## 2. Design Principles

### 2.1 Attestations Are External

Attestations originate from actors.

Evidence originates from execution.

Claims originate from evaluation.

------

### 2.2 Attestations Are Verifiable

Attestations SHOULD be cryptographically verifiable.

------

### 2.3 Attestations Are Non-Authoritative

Evidence remains authoritative.

Attestations provide endorsements.

Evidence SHALL NOT require attestations to remain valid.

------

### 2.4 Attestations Are Immutable

Attestations SHALL NOT be modified.

Revocation SHALL be represented separately.

------

## 3. Attestation Structure

Canonical record:

```clojure
{:schema-version          "attestation.v1"
 :attestation/id          "abcdef0123456789..."   ;; == :attestation/hash
 :attestation/hash        "abcdef0123456789..."   ;; self-referential content hash
 :attestation/attestor-id {:type :ci-runner :id "github-actions"}
 :attestation/subject-hash "sha256:<evidence-node-hash>"
 :attestation/subject-kind :evidence-node | :claim
 :attestation/claim-result :verified | :reproduced | :certified | :approved | :rejected
 :attestation/claim-id    :accounting-consistency   ;; optional
 :attestation/signed-at   "2025-01-01T00:00:00Z"   ;; ISO UTC timestamp
 :attestation/signature   {:algorithm :ed25519     ;; optional
                           :public-key-id "..."
                           :signature-bytes "..."}
 :attestation/metadata    {...}}                   ;; optional
```

### 3.1 Self-Referential Hash

`:attestation/id` MUST equal `:attestation/hash`. The hash is computed from
the canonical projection of the attestation body (all fields except
`:attestation/id`, `:attestation/hash`, `:attestation/signature`,
and `:attestation/metadata`) using `hash-with-intent {:hash/intent
:attestation-record}`.

This ensures tamper evidence: any change to the attestation content changes
the hash, and the hash mismatch is detectable during integrity verification.

### 3.2 Typed Reference Format

When an attestation is referenced from an evidence node, the reference uses
a typed format:

```
attestation:sha256:<64-hex>
```

This disambiguates attestation references from other content-addressed
artifacts (evidence nodes, claim results) at the string level. The
attestation resolver parses these references and resolves them through
the attestation registry.

See `docs/specs/ATTESTATION_RESOLVER_SPEC_V1.md` for the resolution protocol.

------

## 4. Required Fields

### :schema-version

Must be `"attestation.v1"`. Enables schema evolution without ambiguity.

### :attestation/id and :attestation/hash

Self-referential content hash. `:attestation/id` MUST equal `:attestation/hash`.
The value is a 64-character lowercase hex string (SHA-256).

Example:

```clojure
"abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
```

When referenced from evidence nodes, the typed format is used:

```
attestation:sha256:abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789
```

### :attestation/attestor-id

Attesting entity identifier.

Examples:

```clojure
{:type :ci-runner :id "github-actions"}
{:type :auditor :id "audit-team"}
{:type :validator :id "node-12"}
```

### :attestation/subject-hash and :attestation/subject-kind

Object being attested.

`:attestation/subject-kind` MUST be one of `:evidence-node` or `:claim`.

`:attestation/subject-hash` is the content hash of the subject, in
`sha256:<64-hex>` format.

### :attestation/claim-result

Attestation assertion. MUST be one of:

```clojure
:verified       ;; Subject was independently checked
:reproduced     ;; Independent execution produced equivalent result
:certified      ;; Subject satisfies predefined criteria
:approved       ;; Human or system approval granted
:rejected       ;; Subject failed attestation requirements
```

### :attestation/signed-at

UTC timestamp string.

Required.

------

## 5. Signature

Structure:

```clojure
{:algorithm ...
 :public-key-id ...
 :signature-bytes ...}
```

Cryptographic signatures are RECOMMENDED.

Unsigned attestations MAY exist but SHALL be explicitly marked.

------

## 6. Attestation Types

### Verification

Example:

```clojure
:verified
```

Meaning:

Subject was independently checked.

------

### Reproduction

Example:

```clojure
:reproduced
```

Meaning:

Independent execution produced equivalent result.

------

### Certification

Example:

```clojure
:certified
```

Meaning:

Subject satisfies predefined criteria.

------

### Approval

Example:

```clojure
:approved
```

Meaning:

Human or system approval granted.

------

### Rejection

Example:

```clojure
:rejected
```

Meaning:

Subject failed attestation requirements.

------

## 7. Revocation

Attestations SHALL NOT be deleted.

Revocations SHALL be separate records.

Example:

```clojure
{:revokes-attestation-id ...}
```

------

## 8. Multi-Attestor Consensus

Multiple attestations MAY exist for the same subject.

Example:

```clojure
[:verified
 :verified
 :verified]
```

Consensus interpretation is outside the scope of this specification.

------

## 9. Attestation Validation

Validation uses two independent layers.

### Layer 1 — Registry-Backed Authorization (Mandatory)

Uses the attestor registry (ATTESTOR_REGISTRY_SPEC_V1 §11) exclusively. No cryptographic work.

Validation SHALL fail if:

- attestor missing
- subject missing
- claim missing
- timestamp missing
- attestor not registered
- attestor not active
- signing key not authorized for attestor

### Layer 2 — Cryptographic Verification (Optional)

Pure signature check. No registry lookups. The verify-fn receives (data, signature) and returns pass/fail.

Signature validation SHALL fail if:

- signature malformed
- public key unavailable
- verification fails

Unsigned attestations are valid at Layer 1 (key-authorized returns :unsigned).
They are not verifiable at Layer 2 (signature-verified returns :unavailable).

The two layers are independent. Registry authorization does not imply cryptographic
validity. Cryptographic validity does not imply registry authorization.

------

## 10. Evidence Relationship

Evidence:

```clojure
execution produced result
```

Claim:

```clojure
result satisfies invariant
```

Attestation:

```clojure
independent actor verified claim
```

------

## 11. Provenance

Attestations SHOULD reference:

- evidence node hash (typed: `sha256:<64-hex>`)
- claim id
- registry hash
- policy hash

when available.

When attestations are themselves referenced from evidence nodes or bundles,
the typed reference format SHALL be used:

```
attestation:sha256:<attestation-hash>
```

This typed format is enforced by evidence node validation
(`check-node-attestations-present` in `node.clj`) and is the standard
reference format consumed by the attestation resolver
(`ATTESTATION_RESOLVER_SPEC_V1`).

------

## 12. Long-Term Compatibility

Attestation meaning SHALL be determined by:

- attestation type
- attestor identity
- signature verification

Future schema evolution SHALL preserve verifiability of historical attestations.

------

## 13. Audit Requirement

Given:

- attestation
- public key
- referenced subject

an auditor SHALL be able to determine:

- who made the attestation
- what was attested
- when it was attested
- whether signature verification succeeds