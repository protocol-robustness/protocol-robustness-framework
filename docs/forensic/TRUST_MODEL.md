# Forensic Bundle Workflow Trust Model

## Scope

The workflow records and verifies evidence about a scenario execution. Its output is useful for reproducibility and tamper detection within the trust boundaries below. It must not be described as a constrained execution environment unless a deployment establishes and independently verifies those constraints.

## Trust boundaries

| Property | Current behavior | Assurance limit |
|---|---|---|
| Input declaration | Run request, registry snapshot, and evidence policy are recorded in the bundle. | Example files contain placeholders; real inputs must be supplied explicitly. |
| Source provenance | The runner records source-tree hash, commit state, and selected source roots. | This is not a hermetic build or a complete resolved-dependency lock. |
| Output integrity | Bundle records are self-hashed; verification recomputes supported hashes. Optional Ed25519 signing is supported via native Python (PyNaCl), cross-checked against the Clojure implementation. | Integrity relies on verifier implementation and trusted public-key distribution. Native Python path means signature verification does not require the Clojure runtime. |
| Network policy | The request declares allow/deny intent. | This is not a network sandbox or proof that no network access occurred. |
| Filesystem isolation | Shared filesystem is the default. `--private-tmpfs` requests a mount-namespace/tmpfs mode and records the actual mode. | It is optional and may fall back when host capabilities are unavailable. |
| Process isolation | The runner records host checks such as UID, ptrace scope, `/proc`, workspace access, and root status. | It does not enforce containers, seccomp, capability dropping, or a read-only root filesystem. |
| Immutability | Completed bundles can be hardened read-only, with best-effort filesystem immutability. | Local filesystem permissions are not an independent chain of custody. |

## Required language for external review

Use: **forensic bundle workflow**, **forensic execution configuration**, or **reference forensic run**.

Do not use: **isolated workspace**, **constrained execution environment**, **forensic-grade assurance**, or **immutable evidence** without qualifying the deployment-specific controls and independent verification that support the claim.

## Verification architecture

The Python verifier (`scripts/forensic/verify.py`) provides three verification layers:

| Layer | What | Implementation independence |
|---|---|---|
| Structural | File/directory existence, bundle root schema, preflight status | Pure Python — no Clojure dependency |
| Integrity | Self-referential hashes, Ed25519 signatures | **Native Python** (PyNaCl) for signature verification; Clojure comparison for cross-check only |
| Content | Claims, attestations, anchors, evidence-DAG inventory | Pure Python — no Clojure dependency |
| Protocol-semantic | Protocol-specific lifecycle validation (dispatched by `:protocol` identity) | Pure Python — dispatcher + registered per-protocol validators |

The committed golden-bundle fixture (`test/forensic_python/fixtures/golden-bundle.tar.gz`) is verified through the same public entry point used by an external reviewer, providing continuous cross-language compatibility validation.

### Protocol-semantic validation

Protocol-semantic validation is **dispatched by the bundle's declared protocol identity**, not inferred from evidence events. Every bundle root SHOULD carry a `:protocol` descriptor:

```json
{"protocol": {"id": "sew", "version": "1"}}
```

The dispatcher (`scripts/forensic/validate.py`) reads this descriptor and selects the registered validator. Outcomes:

| Status | Meaning |
|---|---|
| `pass` | The protocol validator found semantically valid evidence |
| `fail` | The protocol validator found invalid lifecycle/state transitions |
| `not-verified` | No validator is registered for the declared protocol (or protocol identity is absent — legacy bundle) |

An unknown or missing protocol is **not silently accepted** — it is reported as `not-verified`, which is visible in the verification report without causing a hard structural failure. Generic chain integrity verification proceeds independently.

**Legacy bundles** (produced before the `:protocol` descriptor was added) receive status `not-verified` — no heuristic inference is performed.

**Malformed protocol descriptors** (present but with missing or invalid fields) are reported as `fail`, not `not-verified`. An absent descriptor is the only path to the legacy `not-verified` state.

### Verification policy

The `not-verified` status can be upgraded to a hard failure through verification policy:

- ``--require-protocol-semantics`` — fail if protocol-semantic verification is unavailable (legacy bundle, unknown protocol, or missing validator)
- ``--expected-protocol <id>/<version>`` — cross-check the bundle's ``:protocol`` descriptor against an independently supplied expectation (e.g. from the run plan). A mismatch is a hard failure.

These flags are available on ``bb forensic:verify``. For the Sew forensic pipeline, ``--require-protocol-semantics`` is expected to be the default in production profiles.

### Protocol identity is cryptographically committed

The `:protocol` descriptor is included in the bundle root's self-referential hash computation (it is not in the ``_SIGN_EXCLUDE_KEYS`` set). Modifying the protocol identity after finalization breaks the bundle hash and (when present) the Ed25519 signature, providing cryptographic integrity for the dispatch decision.

However, the protocol descriptor is **self-declared** by the bundle producer. A validly signed bundle could declare an unknown protocol and thereby avoid Sew semantic checks. The hash proves the identity declaration was not tampered after finalization; it does not prove that the declared identity is the expected one. Use ``--expected-protocol`` to supply an independently committed expectation.

### Sew protocol validator (`validate_sew.py`)

Registered for `("sew", "1")`. Validates force-authorisation lifecycle:

| Check | What it validates | Failure mode |
|---|---|---|
| protocol-state-hashes-present | Bundle root contains `:protocol/state-hashes` | Hard fail if Sew evidence found without hashes |
| force-authorisations-hash-well-formed | Hash is non-empty string | Hard fail if missing/empty |
| force-authorisations-consumed-hash-well-formed | Consumed hash is non-empty string | Hard fail if missing/empty |
| force-authorisation-state-witness-consistent | `protocol/state-witness-hash` matches canonical hash of `protocol/state` | Hard fail on mismatch |
| force-authorisation-evidence-state-consistent | Sew evidence events agree with committed state witness | Hard fail on disagreement (e.g., evidence says executed, state says active) |
| Evidence lifecycle (discrete) | grant→execute ordering, no double-execute, execute-before-grant, grant-without-execute | Hard fail on ordering violations |

The Sew validator runs as part of the standard `bb forensic:verify` pipeline via `verify.check_protocol_semantics()`, which calls `validate.validate_protocol_bundle()`.

### Validator contract hardening

The dispatcher validates that a registered protocol validator returns a well-formed report:

| Condition | Behaviour |
|---|---|
| Validator returns a non-dict | Error — not silently upgraded to `not-verified` |
| Validator returns missing namespace-qualified keys | Error |
| Validator returns unrecognised status | Error |
| Validator raises an exception | Error — the exception is not mistaken for unavailable assurance |
| Validator returns malformed individual check entries | Per-check error reported |

These checks prevent implementation defects from being misclassified as `not-verified` (unavailable assurance).

### Protocol identity is cryptographically committed

The `:protocol` descriptor is included in the bundle root's self-referential hash computation (it is not in the `_SIGN_EXCLUDE_KEYS` set). Modifying the protocol identity after finalization breaks the bundle hash and (when present) the Ed25519 signature, providing cryptographic integrity for the dispatch decision.

## External-review baseline

An externally reviewable bundle should include a pinned source revision, generated registry snapshot, explicit policy inputs, output from `bb forensic:verify`, signing public-key provenance when signed, and clean-environment reproduction instructions. A release artifact or intentionally versioned fixture should carry the bundle; it should not be mixed with editable configuration.
