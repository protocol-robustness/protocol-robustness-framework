Semantic Composition V1 — Specification
Status
Active — Phase 2A (authoritative constructor) + Phase 2C (benchmark integration)

Purpose
This specification defines `semantic-composition.v1`: the canonical identity of a protocol's operational semantics, binding a frozen extension resolution to a set of operational module descriptors, capability identities, and policy roots. A semantic composition is the authoritative declaration of *which* semantics govern a run — not merely a label, but a content-addressed, tamper-evident commitment that propagates through evidence, finalization, and completion.

Design principles:
    • Composition identity is content-addressed: the root is `sha256(DOMAIN_TAG || canonical-bytes(projection))` where the projection excludes the root itself (self-hash field, never enters a preimage).
    • The composition root is committed upstream at three independent layers: (1) the scenario input in the committed `input_set` (covered by `input_set_root`), (2) the evidence bundle-root hash (via `:results` entries carrying `:semantic-composition-root`), and (3) the finalization `final_ref` projection. An attacker cannot substitute a different composition without breaking at least one of these independent commitments.
    • Legacy (non-authoritative) runs carry `semantic_composition_root = ""` (empty string). The empty string is a legitimate value when no composition is declared; it is distinct from a non-empty root.
    • Domain separation is enforced by the domain tag, NOT by prefix. The `SEMANTIC_COMPOSITION_V1` tag is registered in `resolver-sim.hash.canonical/domain-tags` and is NOT the same tag as CC3's `PRF_COMMAND_LINEAGE_*` tags (no aliasing).

1. Terminology

Semantic Composition
A map carrying `:semantic-composition/schema`, `:semantic-composition/root`, and the 12 projection fields. The root is a self-hash: it is computed over the projection (all 12 fields, canonicalized and domain-separated) and then attached as a 13th field.

Composition Root
A `sha256:`-prefixed reference (e.g. `"sha256:49be81da..."`) produced by `resolver-sim.composition.semantic/root`. The reference format is `sha256:` + 64 lowercase hex digits, as enforced by `resolver-sim.hash.reference/sha256-ref`.

Self-hash field
A field whose value is derived from the hash of the object *excluding* the field itself. `:semantic-composition/root` is the sole self-hash field in semantic-composition.v1. It is stripped from the projection before hashing (per `self-hash-keys` in `resolver-sim.hash.canonical`, canonical.clj:1290).

Domain Tag
The ASCII string `"SEMANTIC_COMPOSITION_V1"` (registered as `:semantic-composition-v1` in `domain-tags`, canonical.clj:334). The domain tag is UTF-8 encoded and prepended to canonical bytes before SHA-256.

Projection
The 12-field canonical subset of a composition, produced by `resolver-sim.composition.semantic/projection`. The projection excludes `:semantic-composition/root`. Canonical value normalization: sets → sorted vectors, maps → sorted by key with canonical values, vectors/seqs → `mapv`.

2. Schema

Every semantic-composition.v1 artifact MUST carry the following keys:

| Key | Type | Required | Description |
|-----|------|----------|-------------|
| `:semantic-composition/schema` | string | yes | MUST be `"semantic-composition.v1"` |
| `:semantic-composition/version` | integer | yes | MUST be `1` |
| `:semantic-composition/protocol` | string | yes | MUST be `"sew-v1"` |
| `:semantic-composition/profile` | keyword | yes | One of `:production-plain`, `:production-governed` |
| `:semantic-composition/resolution-root` | string (sha256 ref) | yes | Root of the frozen extension resolution snapshot |
| `:semantic-composition/resolution` | map | yes | The full extension resolution snapshot |
| `:semantic-composition/packages` | vector | yes | Derived package set (sorted) |
| `:semantic-composition/capabilities` | vector of 2-vectors | yes | Canonical capability keys `[kind id]` |
| `:semantic-composition/action-modules` | vector | yes | Active action module descriptors (sorted by `:module/id`) |
| `:semantic-composition/state-region-modules` | vector | yes | Active state-region module descriptors (sorted) |
| `:semantic-composition/invariant-modules` | vector | yes | Active invariant module descriptors (sorted) |
| `:semantic-composition/policy-bindings` | map | yes | Force-authorisation policy binding (or `{}` when inactive) |
| `:semantic-composition/root` | string (sha256 ref) | conditional | Computed via §4; present when authoritative, nil when non-authoritative |

No other keys are permitted. Unknown keys are rejected by `semantic/validate` (`{:violation/id :semantic-composition/unknown-field}`).

3. Module Descriptors

Each module descriptor is a map with this exact shape (produced by `semantic/module`):

| Key | Type | Description |
|-----|------|-------------|
| `:module/id` | keyword | Unique module identifier |
| `:module/version` | integer | Module version |
| `:module/actions` | vector | Sorted action strings |
| `:module/state-regions` | vector | Sorted state-region keywords |
| `:module/invariant-ids` | vector | Sorted invariant id keywords |

The canonical force-authorisation module set (active when `:sew/force-authorisation` / `:force-authorisation/custody-execution-v1` capability is selected):

| Module | id | Version | Actions | State regions | Invariants |
|--------|----|---------|---------|---------------|------------|
| Action | `:sew.module/force-authorisation-actions` | 1 | 6 grant/revoke/execute actions | (none) | (none) |
| State | `:sew.module/force-authorisation-state` | 1 | (none) | `:force-authorisations`, `:force-authorisations/consumed`, `:force-authorisations/consumption-records`, `:next-force-authorisation-id` | (none) |
| Invariant | `:sew.module/force-authorisation-invariants` | 1 | (none) | (none) | `:force-authorisations-lifecycle-consistent`, `:force-authorisations-governance-origin` |

Only the custody-execution capability activates live Sew modules. Scope-verification and governed-permit capabilities do NOT activate modules.

4. Root Construction

```
compositional-projection(composition) :=
    canonicalize(selectKeys(composition, PROJECTION_FIELDS))
    ;; :semantic-composition/root is excluded (self-hash field)

ROOT = sha256-ref(
    SHA256(
        UTF8("SEMANTIC_COMPOSITION_V1")
        ||
        canonical-bytes(compositional-projection(composition))
    )
)
```

Where:
- `canonical-bytes` follows `resolver-sim.hash.canonical` type-tagging rules (§4.2 of CANONICAL_HASH_SPEC_V1): null=0x00, bool=0x01, int=0x02, string=0x03, list=0x04, map=0x05
- Maps are sorted by canonical key encoding (type precedence then lexicographic)
- Type tags are 1 byte; strings are `tag || 4-byte LE len || UTF-8 bytes`; ints are `tag || 8-byte BE signed`
- The root field itself is NOT part of the preimage

5. Validation Rules

A semantic-composition.v1 MUST satisfy:

    • `validate` (structural): no unknown keys, no missing required keys, correct schema/version/protocol, `resolution-root` is a string, capabilities are 2-vectors, and if `:semantic-composition/root` is present it must match `root(composition)`.
    • `validate-module-consistency` (derivation): the action-modules, state-region-modules, and invariant-modules MUST exactly match the canonical derivation from capabilities (`derive-modules`). Surplus or missing modules are rejected (`{:violation/id :semantic-composition/action-module-mismatch}` etc.).
    • `validate-authoritative` combines both.

Construction:
    • `build-authoritative` (production): derives every field from a canonical extension resolution. Caller supplies only `extension-map`, `requested-capabilities`, and opts (`:schemas`, `:runtime-profile`, `:sealed?`, `:effect-schemas`, `:force-authorisation-policy`). Fails closed on any resolution failure.
    • `build-unchecked` (legacy/fixture): manual constructor. Does NOT validate resolution against physical manifests. Retained for backwards compatibility.

6. Profile Derivation

    • All capabilities with `:production-governed` profile → `:production-governed`
    • All capabilities with nil profile → `:production-plain`
    • Mixed profiles → throws (fail closed)

7. Policy Binding

When custody-execution is active (capability `[:sew/force-authorisation :force-authorisation/custody-execution-v1]` selected):
    • A force-authorisation policy artifact is required (canonical default if not supplied)
    • Policy MUST conform to the canonical three-member/2-of-3 standard (`canonical-policy-conforming?`)
    • Policy root is the self-committing hash of the policy artifact
    • Issuance assurance = `:governed-research-authority`

When custody-execution is NOT active:
    • `:semantic-composition/policy-bindings` is `{}`

8. State-Region Invalidation

`state-region-invalidation` checks that a world-state map does not contain live force-authorisation state keys when the active composition does not own them. When no composition is supplied (`nil`), ALL force-authorisation live state keys are violations — no ambient default enables force-auth state ownership.

Live state keys (owned by the force-authorisation state module):
    `:force-authorisations`, `:force-authorisations/consumed`, `:force-authorisations/consumption-records`, `:next-force-authorisation-id`

9. Benchmark Integration (Phase 2C)

9.1 Scenario Input Declaration

A benchmark scenario input MAY declare a semantic composition by carrying:

    ```edn
    {:scenario/id :s
     :semantic-composition
     {:schema "semantic-composition.v1"
      :semantic-composition/root "sha256:49be81daef2e3c1d6a3a6440671d7816d29b933934c527c53f83d93d5378ddd1"}
     :execution-mode :authoritative}
    ```

    • `:semantic-composition/root` references the composition root (§4).
    • `:execution-mode :authoritative` marks the run as composition-authoritative.
    • The scenario input is committed in the `input_set` (covered by `input_set_root`).

9.2 Evidence Propagation

Each evidence result entry carries `:semantic-composition-root` — the composition root string (or nil for legacy results without composition). The bundle-root commitment (`hashable-evidence`) covers `:results` entries (excluding `:scenario/artifacts` only), so `:semantic-composition-root` is part of the committed evidence hash. Stripping or substituting the root without re-stamping `:evidence/hash` fails the bundle-root verification.

9.3 Finalization

`finalization.json` carries `semantic_composition_root`:
    • Non-authoritative (legacy): `""` (empty string)
    • Authoritative: the composition root string

The finalization projection (`prf/benchmark-finalization/v1`) includes `semantic_composition_root`, which feeds the `final_ref` hash. A different composition root yields a different `final_ref`.

9.4 Completion

`completion.json` carries `semantic_composition_root` with the same value as finalization. The verifier checks finalization == completion consistency.

9.5 Verification Checks (Phase 2C)

The verifier (`resolver-sim.benchmark.verify`) performs these checks:

    • `semantic-composition-root`: finalization `semantic_composition_root` == completion `semantic_composition_root` AND present in finalization.
    • `authoritative-composition-presence`: when `input_set` declares authoritative, the finalization must carry a non-empty root AND evidence results must agree. (Anti-downgrade)
    • `composition-root-derivation`: when authoritative, the root derived from the committed scenario input MUST equal the root derived from evidence results. (Anti-substitution)

The independent authoritative discriminator is the `input_set` itself — scenario input snapshots are committed via `input_set_root`. An attacker cannot strip or substitute the composition root in evidence without also changing the committed scenario input, which would break the `input-set-root` check.

10. Root Comparison

Composition roots are `sha256:`-prefixed 64-hex-digit references. Comparison is exact string equality on the full reference form (including prefix). The CC3 composition root (`resolver-sim.composition.v1/composition-root`) is bare hex (no prefix) and is distinct from the Phase-2A root — the two systems are domain-separated by tag (`PRF_COMPOSITION_V1` vs `SEMANTIC_COMPOSITION_V1`) and MUST NOT be compared directly.

11. Legacy Compatibility

    • When no `:semantic-composition` is present in the scenario input, the run is non-authoritative: `input_set_declares-authoritative?` returns nil/false, `semantic_composition_root` is `""` everywhere, and all Phase 2C checks pass trivially.
    • The `:semantic-composition/root` key name in EDN (`.clj` maps) corresponds to the JSON field `semantic_composition_root` (underscore) in `finalization.json`/`completion.json`. Evidence results use the kebab-case `:semantic-composition-root` key.

12. Security Considerations

    • Root substitution: The `composition-root-derivation` check prevents substituting composition B while leaving the committed scenario at A. Even if an attacker rewrites evidence/finalization/completion to carry root B and recomputes `final_ref`, the committed scenario input (covered by `input-set-root`) still carries root A, so the derivation check fails.
    • Root stripping: The `authoritative-composition-presence` check prevents stripping `:semantic-composition-root` from evidence results to downgrade to legacy. Stripping changes the bundle-root hash (since results are part of `hashable-evidence`).
    • Cross-domain aliasing: The `SEMANTIC_COMPOSITION_V1` tag is distinct from `BENCHMARK_INPUT_SET_V1` and `PRF_COMPOSITION_V1` tags, preventing hash collisions across domains.
    • Profile mixing: Mixed capability profiles fail closed during construction; the composition builder throws rather than silently selecting a profile.
