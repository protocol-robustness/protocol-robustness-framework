# Stability Policy

This project is under active development. Not all code is stable.

## Stability Manifest

Source-level stability is tracked in `STABILITY_MANIFEST.edn`. Each
entry records a canonical hash (via `hash-with-intent` with intent
`:stability/snapshot`) of the source files that define a stability
surface. The manifest self-entry covers the entire manifest with
`:stability/hash` fields removed recursively, plus the checker
implementation files. Run `bb stability:check` to verify which surfaces have
changed since their last recorded checkpoint.

```
$ bb stability:check

Stability Check — 2026-06-27
──────────────────────────────────────────────────────────────────────────────
  Surface                                          Level        Started At     Status
  ──────────────────────────────────────────────────────────────────────────────
  :stability/canonical-hashing                     stable       2026-06-27     ✅
  :stability/scenario-schema                       stable       2026-06-27     ✅
  ...
──────────────────────────────────────────────────────────────────────────────
  9 unchanged, 0 changed, 0 missing — 9 total
```

Adding a new stability surface: add an entry to `STABILITY_MANIFEST.edn`
with `:stability/id`, `:stability/files`, and `:stability/hash` (run
`bb stability:check` once to populate the initial hash).

## Stable surfaces

The following are stability-controlled:

- scenario schema and scenario semantics
- public CLI/test commands
- core regression scenarios
- artifact registry format
- emitted evidence artifacts
- invariant identifiers and meanings
- **outside-option semantics** — the individual-rationality and coalition outside-option baseline and definition-root binding (`src/resolver_sim/economics/terminal_payoff.clj`, `src/resolver_sim/stochastic/economics.clj`).
- **pro-rata slash evidence claims** — evaluator registry (`prorata-evaluator-resolver`), evidence node shape (`:claims/input-context`, `:claims/direct-result`, `:claims/projection-artifact`), `build-claim-evaluation-node` public API.
- **attestor registry validation** — `validate-attestor-registry-entries` runs at startup and hard-fails on §9 violations (duplicate ids, invalid verification method, duplicate active key ids, malformed public keys). The registry data (`attestors`, `attestor-registry`) is a startup-controlled artifact.

Changes to these surfaces require:

- passing unit and invariant tests
- changelog entry
- migration note if behaviour changes
- updated golden outputs where applicable

## Recently stabilized

These surfaces have been stabilized in the current development cycle. They are safe to depend on but may receive backward-compatible additions:

- **attestation data model** (`build-attestation`, `validate-attestation-shape`) — the canonical attestation structure (§3), required fields (§4), and structural shape validation (§9) are stable. Signature field shape (§5) is stable; cryptographic verification is pluggable via `verify-fn`.
- **revocation records** (`build-revocation`, `register-revocation!`, `find-revocations`, `attestation-revoked?`) — the revocation data model (§7) and the in-memory registry are stable. Persistence to disk is not yet implemented; only the in-memory atom is available. `with-fresh-registry` macro is the supported test isolation mechanism.
- **registry-backed verification checks** (`verify-attestation`, `verify-attestation-summary`) — the 6-check pipeline (attestor-exists, attestor-active, key-authorized, signature-verified, subject-exists, revocation-status) is stable. All non-cryptographic checks (`verify-fn`, `subject-resolver`, `revocation-resolver`) are pluggable and default to `:unavailable`.

## Experimental surfaces

The following are experimental:

- local workflow scripts
- jj/jujutsu automation
- exploratory notebooks
- prototype scenarios
- unfinished temporal/yield/governance refactors
- **attestation integration into equilibrium** (`scenario/equilibrium.clj` `:strict-attestation` mode) — the gating logic exists but `verify-attestation` has no production caller that populates `:attestation {:status ...}` in result maps. The bridge between the verification pipeline and the equilibrium evaluator is not wired.
- **registry-backed attestation in sharing/chain** — `benchmark/sharing/verify-attestation` is a standalone signature-only verifier that bypasses the attestor registry. `evidence/chain/verify-registry-signature` is internal to chain.clj and not connected to the attestation module. Integration of registry-backed attestation into the evidence chain is not yet implemented.
- **revocation persistence** — revocations live in an in-memory atom; there is no on-disk persistence, no replay log, and no evidence-chain integration for revocation records.

Experimental surfaces may change without migration support.

## Incomplete surfaces

These areas have defined specifications but are not yet implemented:

- **Phase 5 attestation integration** — wire `verify-attestation` into `scenario/equilibrium` (`:strict-attestation` mode), `benchmark/sharing`, `evidence/chain` (`verify-registry-signature`), and evidence-node provenance. The data model, verification pipeline, and registry validation are complete; the integration call sites are not.

## Bug fixes

Bug fixes are allowed across all surfaces.

If a bug fix changes public behaviour, the change must be documented as a correction, not silently treated as ordinary drift.
