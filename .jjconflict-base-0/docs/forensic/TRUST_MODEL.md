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

#### Policy-precedence table

| Bundle state | Default | ``--require-protocol-semantics`` | ``--expected-protocol`` (mismatch) | Both flags |
|---|---|---|---|---|
| Valid, supported, validator passes | pass | pass | depends on identity match | identity match + pass |
| Valid, supported, validator fails | fail | fail | identity match + fail | identity match + fail |
| Valid, supported, validator errors | fail | fail | identity match + fail | identity match + fail |
| Valid, unknown protocol | not-verified | **fail** | identity match still not-verified * | fail (require upgrades) |
| Descriptor absent (legacy) | not-verified | **fail** | identity mismatch **fail** | fail |
| Descriptor malformed | fail | fail | identity mismatch **fail** | fail |
| Expected-protocol mismatch | fail | fail | identity mismatch **fail** | fail |

\* ``--expected-protocol`` matching an unknown protocol does **not** auto-upgrade the result to ``pass``. The identity cross-check passes but version remains ``not-verified`` because no validator is registered. ``--require-protocol-semantics`` is needed to upgrade to fail.

### Protocol identity grammar

The protocol descriptor follows a strict canonical grammar:

```
<name>-v<N>
  where:
    <name> = [a-z][a-z0-9]*    # lowercase, single token, no hyphens/underscores
    <N>    = [1-9][0-9]*       # positive integer, no leading zero, string-encoded
```

Examples: ``sew-v1``, ``yield-v1``, ``yield-v2``.

This grammar deliberately excludes hyphens in the name segment to avoid ambiguous splitting when parsing ``<name>-v<N>``. Compound protocol identifiers (e.g. ``partial-fill-v1``) would require a different delimiter convention. The version is always emitted as a string by the Clojure producer, never as an integer, so that cross-language consumers compare by string equality (``"1" == "1"``, not ``1 == "1"``).

### Protocol identity is cryptographically committed

The `:protocol` descriptor is included in the bundle root's self-referential hash computation (it is not in the ``_SIGN_EXCLUDE_KEYS`` set). Modifying the protocol identity after finalization breaks the bundle hash and (when present) the Ed25519 signature, providing cryptographic integrity for the dispatch decision.

The trustworthy verification sequence is:

1. Verify bundle root commitment and Ed25519 signature (structural integrity)
2. Read the committed `:protocol` descriptor
3. Compare with `--expected-protocol` (if supplied) — detects identity mismatch
4. Dispatch to the registered protocol-semantic validator

**Step 3 (`--expected-protocol`) does not authenticate the declaration.** It establishes agreement between an external verifier expectation and the bundle's self-declared identity. A validly signed bundle could declare an unknown protocol and thereby avoid Sew semantic checks. The hash proves the identity declaration was not tampered after finalization; it does not prove that the declared identity is the expected one. Use ``--expected-protocol`` to supply an independently committed expectation (from the run plan, benchmark definition, or verification profile).

**`--expected-protocol` matching does not imply that a validator exists.** If the bundle declares ``unknown-protocol/1`` and `--expected-protocol unknown-protocol/1` is supplied, the identity cross-check passes but the semantic result remains ``not-verified`` — no validator is registered for that protocol.

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

**Top-level contract**

Protocol-specific report fields are protocol-qualified (e.g. ``sew/status``, ``sew/checks``). The dispatcher owns generic aggregate fields (``validate/schema-version``, ``validate/status``, ``validate/protocol``) and exit-code interpretation. Individual check records use the dispatcher-standard fields:

```json
{
  "check": "identifier-string",
  "status": "pass|fail|warn|skip|not-verified",
  "message": "human-readable",
  "details": []  // optional structured violations
}
```

**Enforcement**

| Condition | Behaviour |
|---|---|
| Validator returns a non-dict | Error — not silently upgraded to `not-verified` |
| Validator returns report with no protocol-qualified keys (e.g. no ``sew/`` or ``validate/`` prefix) | Error |
| Validator returns unrecognised status | Error |
| Validator raises an exception | Error — the exception is not mistaken for unavailable assurance |
| Validator returns check entry with missing ``check`` field | Per-check error reported |
| Validator returns check entry with unrecognised status | Per-check error reported |

These checks prevent implementation defects from being misclassified as `not-verified` (unavailable assurance).

### Protocol identity is cryptographically committed

The `:protocol` descriptor is included in the bundle root's self-referential hash computation (it is not in the `_SIGN_EXCLUDE_KEYS` set). Modifying the protocol identity after finalization breaks the bundle hash and (when present) the Ed25519 signature, providing cryptographic integrity for the dispatch decision.

## External-review baseline

An externally reviewable bundle should include a pinned source revision, generated registry snapshot, explicit policy inputs, output from `bb forensic:verify`, signing public-key provenance when signed, and clean-environment reproduction instructions. A release artifact or intentionally versioned fixture should carry the bundle; it should not be mixed with editable configuration.
