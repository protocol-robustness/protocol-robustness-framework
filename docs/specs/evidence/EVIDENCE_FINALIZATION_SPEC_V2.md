# Evidence Finalization Specification V2

**Status:** Frozen contract

## Purpose

`evidence-finalization.v2` is an immutable terminal declaration over a bounded evidence subject. It is neither a mutable cursor, evidence registry, package manifest, signature container, nor validation report.

Supported kinds:

- `scenario-chain-finalization`
- `run-evidence-finalization`

## Commitment layering

```text
evidence records and DAG nodes
  → evidence-content registry root
  → scenario chain finalizations
  → run evidence finalization
  → detached signatures and timestamp receipts
  → package manifest and bundle root
```

The content registry excludes finalizations, detached signatures, timestamps, package manifests, validation/completion reports, and generated claims. The outer package manifest inventories those closure artifacts. A run finalization must not contain the bundle root.

## Bundle layout

```text
<run-root>/
  evidence/
    content-registry.json
    finalizations/
      scenarios/<scenario-artifact-id>/evidence-finalization.json
      run/evidence-finalization.json
      run/signatures/<key-id>.attestation-signature.json
      run/timestamps/<signature-hash>.rfc3161.tsr
  package-manifest.json
```

`<scenario-artifact-id>` is the canonical scenario-JAR artifact ID. References use artifact IDs and typed digests, not relative paths. `chain-cursor-final.json` may be dual-written during migration but is not authoritative.

## Shared envelope

```clojure
{:schema-version "evidence-finalization.v2"
 :finalization-kind "scenario-chain-finalization"|"run-evidence-finalization"
 :canonicalization {:scheme "prf-canonical-hash-v1"
                    :intent "evidence-finalization-v2"}
 :run {...}
 :subject {...}
 :execution {...}
 :evidence {...}
 :bindings {...}
 :verification {...}
 :policy {:profile-id "..." :policy-hash "sha256:..."}}
```

The finalization has no self-hash. Its external artifact inventory supplies the artifact ID, SHA-256 digest, and byte count. Signatures and timestamps are detached.

## Chain requirements

A non-empty verified scenario chain uses `link-v1`. Each link hash commits to the evidence content hash, sequence, and predecessor hash. This repository preserves the existing sequence origin: records are numbered `1..N`.

A verified declared head requires all of:

1. the artifact exists and its persisted digest is valid;
2. its link hash recomputes;
3. it belongs to the scenario chain;
4. it has final sequence `N`;
5. no verified record follows it;
6. it is the unique terminal record;
7. backward traversal reaches genesis;
8. the reachable hashes exactly match the declared list and set commitment;
9. each reachable hash belongs to the evidence-content registry.

A declared head is not a verified head until these checks pass. A run has a set of scenario heads, not a synthetic aggregate chain head.

## Execution and evidence statuses

Execution status (`completed`, `failed`, `aborted`) and evidence status are independent.

| Chain status | Meaning |
|---|---|
| `verified` | Non-empty complete chain with a verified unique terminal head. |
| `valid-empty` | Zero records are explicitly permitted by the scenario contract/policy. |
| `partial` | Persisted prefix exists but terminal completeness is unproven. |
| `invalid` | Verification failed. |

A valid-empty chain has count `0`, no genesis/head, an empty reachable list, and the canonical empty hash-set root. Failed scenarios may still have verified complete evidence. Partial or invalid scenarios cannot satisfy forensic/release policy.

## Hash-set commitment

Use canonical intent `:evidence-hash-set` over:

```clojure
{:schema-version "evidence-hash-set.v1"
 :hash-algorithm "sha256"
 :count (count hashes)
 :hashes (vec (sort (distinct hashes)))}
```

Persisted lists must be sorted and unique. Verification rejects malformed digests, duplicate declarations, unsupported algorithms, unsorted lists, and count mismatches. This is a set commitment, not a Merkle root.

## Exact reconciliation

For a run finalization, all locally available sets must be compared:

```clojure
{:disk-evidence-hashes
 :registry-evidence-hashes
 :chain-reachable-hashes
 :aggregate-declared-hashes}
```

`exact` requires all four sets, counts, and hash-set roots to agree, with no duplicates. The verifier reports disk-only, registry-only, chain-only, aggregate-only, unreachable, and undeclared identities.

Generic unsequenced traces are supplemental in inspection profiles and must not contribute to claims of chain-verified evidence. Forensic/release profiles must either sequence them through a recognised verified channel or exclude them from authoritative evidence content.

## Detached trust material

Detached signature envelopes sign the finalization payload hash and authenticate payload type. Policy evaluates independently whether signatures, trusted signers, algorithms, thresholds, timestamps, trusted timestamp authorities, registry exactness, and finalization completeness are required and satisfied.

A finalization declares policy requirements but cannot truthfully claim its own future detached signatures have verified. Verification results belong in reports and artifact-specific claims.

## Forensic/release eligibility

A forensic/release profile requires:

```text
run finalization present
scenario finalizations accepted
all non-empty accepted chains have verified unique heads
exact reconciliation
verified registry and DAG bindings
signature policy satisfied
timestamp policy satisfied
package closure complete
```

## Migration

During compatibility, readers prefer v2, independently verify it, then compare the legacy cursor only as compatibility material. Cursor-derived results are reported as `legacy-chain-cursor`; discrepancies are fatal in forensic/release profiles. Cursor writing ends only after v2 readers, packages, claims, and historical-bundle compatibility are established.
