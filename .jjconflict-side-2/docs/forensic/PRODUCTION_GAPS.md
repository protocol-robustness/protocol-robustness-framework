# Production Forensic-Grade Gaps

Current implementation status against requirements for a forensic evidence
runner that could produce externally-reliable output.

## Legend

- ✅ Implemented
- ◐ Partial / needs hardening
- ❌ Not implemented
- — Out of scope (requires external system or new component)

## Sealing & Integrity

| Requirement | Status | Notes |
|---|---|---|
| Atomic file writes (temp → fsync → rename) | ✅ | `write_sealed_json` |
| Readback verification after write | ✅ | SHA-256 recomputed from disk, compared to pre-write hash |
| Self-referential bundle root hash | ✅ | `bundle/hash` excludes id/hash/signature/key-id |
| Self-referential overview hash | ✅ | `overview/hash` excludes itself |
| Temp file cleanup on failure | ✅ | `try/finally` across all writes |
| Post-run output hardening (chmod a-w) | ✅ | `chmod -R a-w` after verification |
| Immutable filesystem flag (chattr +i) | ✅ | Best-effort after chmod |
| Ed25519 bundle signing | ✅ | `--sign <key>` via Clojure signing infrastructure |
| Signature verification | ✅ | `bb forensic:verify --public-key <key>` |
| Post-run full verification | ✅ | 22 checks including hash recomputation |
| HMAC with per-run key | ❌ | No per-run signing key |
| Signed chain of custody log | ❌ | No append-only log of runs with signatures |

## Isolation

| Requirement | Status | Notes |
|---|---|---|
| Private tmpfs via unshare | ✅ | `--private-tmpfs` flag, fallback on failure |
| UID check (expected `evidence_runner`) | ✅ | Check 1 — recorded in metadata |
| ptrace_scope ≥ 3 check | ✅ | Check 2 — recorded in metadata |
| /proc accessibility check | ✅ | Check 3 — heuristic |
| Filesystem workspace check | ✅ | Check 4 — `/var/lib/evidence-runner` |
| Root privilege detection | ✅ | Check 5 — recorded in metadata |
| Composite isolation grade | ✅ | `full` → `good` → `partial` → `basic` |
| 5 isolation checks in metadata | ✅ | Per-check detail in `isolation/checks` |
| Dedicated UID per job | ❌ | Single `evidence_runner` user, not per-job |
| Seccomp filter | ❌ | No syscall filtering |
| Capability dropping | ❌ | No `capsh` or similar |
| Read-only root filesystem | ❌ | No `pivot_root` or overlayfs |

## Source Provenance

| Requirement | Status | Notes |
|---|---|---|
| Git commit + dirty state | ✅ | `source-snapshot.json` |
| Source byte size | ✅ | `byte-size` in `run-overview.json` |
| Deterministic source hash | ✅ | `source-hash` (with `code-hash` compatibility alias) over sorted `path:content-sha256` entries |
| Hash algorithm declared | ✅ | `source-hash-algorithm` field |
| Included source roots declared | ✅ | `source-hash-roots` field |
| Clojure-side provenance bridge | ✅ | `PRF_*` env vars, `resolver-sim.forensic.provenance` |
| Source provenance in execution node | ✅ | Merged into `:inputs` in `with-execution-node` |
| Source/request/node hashes in bundle root | ❌ | Not yet in Clojure `build-bundle-root` output |
| Dependency lock snapshot | ◐ | `deps.edn` copied into bundle |

## Time & Anchoring

| Requirement | Status | Notes |
|---|---|---|
| Bundle timestamp (wall clock) | ✅ | ISO-8601 in bundle root |
| RFC 3161 timestamp token | ❌ | No TSA integration |
| IPFS/IPLD content-addressed publication | ❌ | — |
| L1 anchoring (event log entry) | ❌ | — |
| Nostr relay publication | ❌ | — |
| Witness cosignature | ❌ | — |

## Verification & Audit

| Requirement | Status | Notes |
|---|---|---|
| File existence checks | ✅ | 6 required, 8 optional files |
| Directory existence checks | ✅ | 4 required, 1 optional directory |
| Bundle root structural validation | ✅ | Schema version, required keys |
| Overview structural validation | ✅ | Required fields |
| Preflight report validation | ✅ | Status check |
| Self-referential hash recomputation | ✅ | Bundle root + overview |
| Ed25519 signature verification | ✅ | `--public-key` flag |
| Results summary validation | ✅ | `results-summary.json` checked |
| Evidence DAG inventory | ✅ | `evidence-dag-inventory.json` with per-file SHA-256 |
| Registry consistency check | ◐ | File-to-registry comparison reported (warning) |
| Clojure bundle root capture | ✅ | `clojure-bundle-root.json` in bundle |
| Reproduce-from-bundle | ❌ | No `bb forensic:reproduce` |
| Diff between two runs | ❌ | No comparison tool |
| Shared mailbox for runner messages | ✅ | Phase 2: `mailbox.py` — filesystem-backed, content-addressed; runner announcements, run requests, commitments, submissions, error reports, consensus writeback, object store, CLI |
| Multi-runner quorum comparison | ✅ | Phase 1: `consensus.py` — N-run coordinator, field-level agreement, certificate/disagreement artifacts |

## Evidence Pipeline

| Requirement | Status | Notes |
|---|---|---|
| `evidence-dag/` populated | ✅ | 145+ nodes copied from Clojure pipeline |
| `claims/` directory created | ✅ | Populated with 3 claim results by `forensic_populate.clj` |
| `attestations/` directory created | ✅ | Populated with 3 attestations by `forensic_populate.clj` |
| Evidence DAG inventory manifest | ✅ | `evidence-dag-inventory.json` — Phase B (semantic EDN parsing with node-hash, execution-id, parent-hashes, edges) |
| EDN semantic parsing | ✅ | Phase B: Clojure-side EDN reader extracts node-hash, execution-id, result-status, parent-hashes; DAG edges computed from parent references |
| Evidence DAG manifest hash in bundle root | ✅ | `evidence-dag/hash` field in `run-bundle-root.json` — SHA-256 of `evidence-dag-inventory.json` |
| Execution root hash | ✅ | `execution/node-hash` in `run-bundle-root.json` — bridged from Clojure bundle root's execution node hash |
| RFC 3161 timestamp anchoring | ✅ | `--tsa-url` CLI + `PRF_TSA_URL` env var; `ts/*tsa-url*` bound in `scenario_runner.clj`; TSA sidecars copied to `anchors/`; `verify.py` validates |

## Run Request & Reproducibility

| Requirement | Status | Notes |
|---|---|---|
| Run request validated preflight | ✅ | EDN/JSON parsing, required fields |
| Registry snapshot captured | ✅ | Copied into output |
| Source snapshot | ✅ | `source-snapshot.json` with git_commit, dirty, byte-size, source-hash, source-hash-algorithm, source-hash-roots |
| Environment snapshot | ✅ | `environment.json` with OS, Python, Clojure versions |
| Input manifest | ✅ | `input-manifest.json` |
| Runner identity | ✅ | From run request |
| Policy versions | ✅ | Evidence + execution policy refs |
| Preflight report | ✅ | `preflight-report.json` with 14 checks |
| Clojure bundle root (live registry hashes) | ✅ | `clojure-bundle-root.json` with live registry snapshot |
| Scenario files bundled | ✅ | When named suite is used |
| deps.edn bundled | ✅ | Copied into bundle |
| Reproduce-from-bundle | ❌ | No tool to re-execute and compare |

## Bundle Distribution

| Requirement | Status | Notes |
|---|---|---|
| Self-contained output directory | ✅ | All artifacts in single tree |
| Portable archive format | ❌ | No tar.gz or similar |
| Export/import tool | ❌ | No `bb forensic:export` / `bb forensic:import` |
| Redaction support | ❌ | No mechanism to sanitize before sharing |
| Size management | ❌ | No compression, dedup, or retention policy |

## Summary by Severity

### Still missing — will be addressed now

1. **Portable export/import** — tar.gz + manifest for external auditors.
   `bb forensic:export <run-dir> [--output <path>]` and
   `bb forensic:import <archive> [--output <dir>]`.

2. **Reproduce-from-bundle** — Given a bundle, re-execute the run request
   and compare the overview hash. `bb forensic:reproduce <run-dir>`.

3. **Structured failure detail** — Extract per-scenario results from the
   Clojure bundle root and produce `results-summary.json` with scenario-level
   pass/fail counts.

### Remaining after this round

4. **RFC 3161 timestamp anchoring** — Requires TSA client integration.
5. **IPFS/IPLD publication** — Requires IPFS node or pinning service.
6. **Multi-runner quorum** — Requires multi-runner orchestration.
7. **Signed chain-of-custody log** — Requires per-run signing key.
8. **Execution DAG semantic parsing** — Phase B Clojure-side work.
9. **Seccomp / capability dropping** — OS-level hardening.
10. **Diff between runs** — Comparison tool.
