# Evidence Chain Architecture

## Chain Structure

Content-addressed evidence chain binding evidence records into a self-hashed artifact registry:

```
transition-evidence → evidence-hash
evidence-registry   → registry-hash  (commits to all evidence hashes)
manifest            → artifact-registry-sha (signs the registry hash)
```

## Implementation: `src/resolver_sim/evidence/chain.clj`

### Registry Atom
- `evidence-registry-atom` — dynamic var accumulating artifacts and hashes during a run.
- `reset-registry!` — clears the atom for a new run (optional `:run-id`, `:run-label`).
- `with-fresh-registry` — macro for per-run chain isolation; restores outer registry on exit.
- `registry-status` — summary of current state (count, run-id).

### Evidence Registration
- `register-evidence!` — idempotent registration of evidence records into the chain. Accepts maps from `emit-evidence!`, produces artifact entries with truncated evidence-hash IDs.

### Registry Builder
- `build-registry` — produces a self-hashed registry map with schema-version, contract-version, run-id, generated-at timestamp, all evidence artifacts, and a `registry-hash` computed over all content (matching sign-manifest pattern).

### Persistence
- `write-registry!` — writes registry as pretty-printed JSON to `artifact-dir/evidence-registry.json`.
- `finalize-and-write!` — single call to build, write, and return `{:registry ... :artifact-entry ... :path ...}`.

### Artifact Registry Integration
- `registry-artifact-entry` — produces `test-artifacts.json` compatible entry with SHA-256 and registry-hash for artifact chain anchoring.
- `index-artifact-entry` — compatible entry for index files (`evidence-mechanisms.json`, `evidence-coverage-report.json`).
- `register-additional-artifact!` — registers index files after the main registry is built.

### Chain Verification
- `verify-registry-hash` — checks `registry-hash` consistency with registry content.
- `verify-evidence-in-registry` — verifies a specific evidence-hash is recorded.
- `evidence-chain-integrity` — full chain integrity check: registry hash validity + all hashes registered.

## Run-Scoped Evidence Chain

Every targeted artifact includes:
- `:evidence/chain-seq` — sequential position in the run-scoped chain.
- `:evidence/chain-prev-hash` — evidence-hash of the previous artifact.
- `:evidence/chain-self-hash` — self-referencing hash (excludes chain fields from input).

Cursor managed via `chain-cursor` atom and `reset-chain-cursor!` macro (see `io/event_evidence.clj`).

## Current Guarantee

- Runtime attribution propagation across setup/resolution/async paths.
- Run-scoped chain with tamper detection.
- Full registry persistence and artifact-level integrity verification.

## Related Source

| Component | File |
|-----------|------|
| Evidence chain registry | `src/resolver_sim/evidence/chain.clj` |
| Event evidence capture | `src/resolver_sim/io/event_evidence.clj` |
| Attribution context | `src/resolver_sim/util/attribution.clj` |
| Evidence query API | `src/resolver_sim/evidence/` |
| Post-hoc chain verification | `src/resolver_sim/evidence/verification.clj` |
| Mechanism index | `src/resolver_sim/evidence/index.clj` |
| Coverage report | `src/resolver_sim/evidence/coverage.clj` |
