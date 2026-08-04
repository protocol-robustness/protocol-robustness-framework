# Trace-Equivalence Harness — Maturity & Upgradeability Contract

Status: Phase 0 (claim correctness and coverage). Companion to
`EQUIVALENCE_ATTESTATION.md` and `etc/trace-solidity-manifest.edn`.

## Vocabulary (load-bearing)

These terms must not be used interchangeably:

| Term | Meaning |
|---|---|
| Fixture validation | A fixture is structurally valid (schema) and semantically valid (action/state rules). |
| Fixture sync integrity | A copied fixture is byte-identical (SHA-256) to its Clojure source. **Not** equivalence. |
| Contract replay | The fixture was executed against live contracts by a Forge test and produced a replay receipt. |
| Invariant evaluation | The compiled invariant profile was applied and its equations evaluated during replay. |
| Equivalence attestation | The reviewer-facing claim document, built from replay receipts. |
| Attestation reproduction | Re-deriving the attestation from the bound commits (Phase 3 `--mode reproduce`). |
| Candidate compatibility | Testing a proposed contract/simulator change against an unbound fixture set (Phase 3 `--mode candidate`). |

**`equivalence verified`** is reserved for a trace that:
1. passed schema and semantic validation;
2. was replayed against an identified contract (replay receipt exists);
3. had the identified invariant profile resolved **and applied** (observable);
4. passed the required invariants;
5. has its replay receipt included in the attestation.

Sync integrity alone never establishes `equivalence verified`.

## Phase 0 contract

### Per-trace replay receipts

`TraceEquivalenceTest.sol` emits one receipt per fully replayed fixture under
`<sew-repo>/out/receipts/`. A receipt binds:

- `trace_id` (scenario id) and `fixture_path`;
- `fixture_hash` (keccak256 of the fixture bytes on disk — verified by reconcile);
- `replay_spec_id` (negotiated `cdrs.<v>.schema.<v>.profile.<v>.harness.<v>`);
- `profile_id`, `profile_version`, `profile_applied`, `invariant_evaluations`;
- `replay_status`;
- `extension_resolution_root` (Phase 0 built-in frozen snapshot).

The harness **fails closed** when a v0.2 fixture's profile is not both resolved
and applied with at least one invariant evaluation ("profile-inert" detection).

### Set reconciliation gate

`scripts/reconcile.py` enforces:

```
manifest included traces
   == fixtures selected for replay
   == Forge replay receipts
   == attestation per-trace results
manifest excluded/byte-synced traces
   == explicit, machine-readable records
```

It rejects: included-without-receipt, replayed-but-unclassified fixture,
duplicate fixture paths, receipt/fixture hash mismatch, `:forge-wired` inventory
drift, profile-inert receipts, and receipts for byte-synced-only traces.
Duplicate `trace_id`s are advisory only (the corpus legitimately reuses
scenario ids, e.g. the byte-identical sew-001/ref-005 mirrors).

### Negative tests are non-vacuous

Negative fixtures (n01–n07) carry an invariant profile and must replay fully
then fail on a **semantic** assertion. The tests assert the revert is a forge
assertion abort (selector `0xeeaa9e6f`) whose message contains ` mismatch`, so a
fixture that reverts at the profile gate no longer counts as a passing negative
test.

## Extension-model alignment (reserved in Phase 0)

The harness is a closed-world consumer of the framework extension model.
Executable capabilities (trace actions, invariant profiles, state projections)
will share a common extension descriptor and be resolved into a frozen,
content-addressed snapshot before replay (Phase 1), bound into receipts and the
attestation (Phase 3–4). Fixture specs, schemas, role assignments, the manifest,
and harness versions remain negotiated data/protocol artifacts, not extensions.

Phase 0 reserves the shape:

```clojure
:extension-resolution
{:root "sha256:..."
 :entries [{:extension/id :trace/action.resolve :extension/version "1" :extension/kind :trace/action}
           {:extension/id :trace/profile.equivalence-v1 :extension/version "1" :extension/kind :trace/invariant-profile}
           {:extension/id :trace/projection.sew-v2 :extension/version "2" :extension/kind :trace/state-projection}]}
```

The current capability-aware built-in root is
`sha256:091280b11d9fb3ae220517a7e8e3e4f23a985d650f89ea168a07b71863779e3d`
(the SHA-256 of the canonical serialisation
`id|version|kind|supported-fixture-specs` over the three built-in entries).
The harness recomputes this root and rejects drift.

## Phase 1 — negotiated compatibility

- **Layered identity.** `fixture-spec` = `{:cdrs-version :schema-version}`;
  `replay-spec` = fixture-spec + `:invariant-profile {:id :version}` +
  `:harness-version`. The manifest `:replay-spec/id` is
  `cdrs-0.2.schema-2.profile-1.harness-1` (derived from the fields).
- **Supported-combination registry.** The exact combinations
  `(0.1, 1)` and `(0.2, 2)` are enumerated in the harness, `reconcile.py`,
  `trace-solidity-verify`, and this manifest. Unknown, missing, or
  invalid-combination inputs **fail closed**; key-presence autodetection is
  removed and legacy v0.1 replay is an explicit handler (`cdrs_version: "0.1"`).
- **Extension-resolved support.** Each resolved extension declares the
  fixture-specs it supports; a replay is only valid if every resolved
  extension covers the negotiated fixture-spec. The resolution root encodes
  those support sets, so capability drift changes the root and fails closed.
- **Rejection tests.** `test_version_reject_{unknown_cdrs,missing_cdrs,
  invalid_combo,unsupported_profile}` prove the harness rejects unsupported
  combinations before any action is interpreted.

## Version-bump ownership (preview)

| Change | Version/binding to update |
|---|---|
| Fixture JSON structure changes | `schema-version` |
| Meaning of an existing field/action changes | `cdrs-version` |
| New/changed equivalence assertion | `invariant-profile.version` |
| Harness protocol/dispatch semantics change | `harness-version` |
| Manifest representation changes | manifest version |
| Contract implementation change (no trace-language change) | contract commit + re-attestation |
| Simulator implementation change (no trace-language change) | simulator commit + re-attestation |
| New action with new trace semantics | normally CDRS + schema |
| New role identifier only | schema or role-registry version |

A contract upgrade forces regeneration, replay, and re-attestation — it does
NOT automatically bump CDRS or schema version.

## Commands

```bash
# Emit receipts (also run by the attestation generator):
cd <sew-repo> && mkdir -p out/receipts && forge test --match-contract TraceEquivalenceTest

# Reconciliation gate (execution evidence vs manifest):
python3 scripts/reconcile.py --sew-repo <sew-repo>

# Receipt-derived attestation:
python3 etc/generate-equivalence-attestation.py --sew-repo <sew-repo>
```
