# ATTESTOR_REGISTRY_SPEC_V1

Status: Draft V1

## 1. Purpose

The Attestor Registry is the authoritative catalog of entities permitted to issue attestations.

Attestors may be:

- researchers
- CI systems
- replay validators
- benchmark validators
- auditors
- organizations
- delegated signing identities

Attestors SHALL be registered before issuing attestations.

------

## 2. Design Principles

### 2.1 Identity Is Explicit

Attestors SHALL possess stable identifiers.

------

### 2.2 Verification Is Independent

Attestation verification SHALL NOT require trust in the execution environment.

------

### 2.3 Key Rotation Is Supported

Attestor identities SHALL remain stable across key rotations.

------

### 2.4 Registration Is Auditable

Attestor definitions are immutable protocol artifacts.

------

## 3. Registry Structure

```clojure
{:registry-version 1

 :attestors [...]}
```

------

## 4. Attestor Definition

```clojure
{:id :github-actions

 :type :ci-runner

 :display-name "GitHub Actions"

 :status :active

 :verification {...}

 :metadata {...}}
```

------

## 5. Required Fields

### :id

Stable attestor identifier.

Example:

```clojure
:github-actions
```

------

### :type

Examples:

```clojure
:ci-runner
:validator
:auditor
:organization
:individual
```

------

### :status

Allowed values:

```clojure
:active
:revoked
:retired
```

------

### :verification

Verification method.

Example:

```clojure
{:type :public-key

 :algorithm :ed25519

 :key-id "key-001"

 :public-key "..."}
```

------

## 6. Delegated Identities

Organizations MAY delegate signing authority.

Example:

```clojure
{:id :audit-firm

 :delegates
 [{:id :audit-firm-key-1}
  {:id :audit-firm-key-2}]}
```

Delegates inherit attestation authority.

------

## 7. Key Rotation

Key rotation SHALL NOT require attestor id changes.

Example:

```clojure
:key-history
[{:key-id "v1"}
 {:key-id "v2"}]
```

Historical attestations SHALL remain verifiable.

------

## 8. Revocation

Attestors MAY be revoked.

Example:

```clojure
{:status :revoked
 :revoked-at "..."}
```

Revocation SHALL NOT invalidate historical attestations.

------

## 9. Registry Validation

Startup SHALL fail if:

- duplicate ids exist
- invalid verification method
- duplicate active key ids
- malformed public keys

------

## 10. Hashing

Purpose:

The attestor canonical hash identifies the stable attestation-verification
surface of one attestor registry entry. It is used for registry-backed
validation, auditability, and replay-safe attestation verification.

Hashing domain tag:

```text
ATTESTOR
```

Canonical projection SHALL include exactly:

```clojure
:id
:type
:status
:verification
:delegates
:key-history
```

Canonical projection SHALL exclude:

```clojure
:canonical-hash
:attestor-hash
:display-name
:metadata
```

It SHALL also exclude transient runtime state and cached verification data.

If `:delegates` or `:key-history` are absent in source data, implementations
SHALL project them as empty vectors so the projected attestor shape remains
explicit and deterministic.

------

## 11. Audit Requirement

Given an attestation and attestor registry entry, an auditor SHALL be able to determine:

- who issued the attestation
- whether issuer was registered
- whether signature verification succeeds
- whether issuer was active at issuance time