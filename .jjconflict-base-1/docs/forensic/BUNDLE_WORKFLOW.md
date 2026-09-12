# Forensic Bundle Workflow

The forensic bundle workflow produces scenario-execution bundles with recorded
inputs, provenance, self-hashes, optional signatures, and verification tools.
These properties support reproducibility and tamper detection; they do not by
themselves establish a constrained or isolated execution environment. See
`TRUST_MODEL.md` for the assurance boundaries.

All commands run from the **project root**. The default configuration is in
`examples/config/forensic/`; illustrative inputs are in
`examples/forensic-reference-run/`.

---

## Quick Start

```bash
# 1. Run the full forensic pipeline (preflight → execute → verify → harden)
bb forensic:run

# 2. Verify the output bundle
bb forensic:verify ~/prf-runs/<run-id>

# 3. Export for external auditors
bb forensic:export ~/prf-runs/<run-id> --output my-bundle.tar.gz

# 4. Reproduce the run (same source, compare results)
bb forensic:reproduce ~/prf-runs/<run-id>

# 5. Validate pipeline determinism (run twice, compare)
bb forensic:self-test
```

---

## Lifecycle

```
  Prepare ──► Preflight ──► Execute ──► Verify ──► Harden ──► Export
     │                                                        │
     └── Edit run-request.edn                                 └── tar.gz
     └── Edit registry-snapshot.edn                                for auditors
     └── Place scenarios/                                          │
                                                          Reproduce ──► Compare
                                                          Self-Test ──► Determinism
```

---

## Commands

### `bb forensic:run`

Full forensic execution pipeline.

```bash
# Run with the illustrative reference inputs
bb forensic:run

# With a custom run request and label
bb forensic:run --run-request my-run.edn --label my-forensic-run

# With private tmpfs isolation (requires user namespace support)
bb forensic:run --private-tmpfs

# With Ed25519 bundle signing
bb forensic:run --sign /path/to/private-key.pem --signing-key-id my-key

# Dry-run (preflight only, no execution)
bb forensic:run --dry-run
```

**What it does:**
1. Runs 14 preflight checks (run request, registry snapshot, evidence policy,
   output directory, runner identity, scenario set, network policy)
2. Creates an output directory at `~/prf-runs/<timestamp>-<label>/`
3. Snapshots source provenance (git commit, code-hash, byte-size)
4. Runs the Clojure scenario pipeline (S01–S107 in-process scenarios, or
   a named suite via `--suite`)
5. Captures the Clojure bundle root with live registry hashes
6. Produces results summary, self-referential overview, and bundle root
7. Signs the bundle root if `--sign` is provided
8. Copies evidence nodes from the Clojure pipeline into the bundle
9. Produces an inventory manifest of all evidence DAG files
10. Runs full verification (22 checks) on the output
11. Hardens the output tree (chmod a-w, optional chattr +i)

**Output layout:**
```
~/prf-runs/<run-id>/
├── preflight-report.json       # 14 preflight checks
├── source-snapshot.json        # git commit, code-hash, byte-size
├── environment.json            # OS, Python, Clojure versions
├── input-manifest.json         # provenance of inputs
├── run-request.json            # copy of the run request
├── registry-snapshot.json      # copy of registry snapshot
├── evidence-policy.edn         # copy of evidence policy
├── deps.edn                    # snapshot of project deps
├── run-overview.json           # self-referential overview
├── run-bundle-root.json        # self-referential bundle root
├── results-summary.json        # exit code, status, suite key
├── clojure-bundle-root.json    # Clojure-side bundle root
├── evidence-dag/               # copied evidence node files
├── evidence-dag-inventory.json # inventory of evidence DAG
├── claims/                     # claim results when produced
├── attestations/               # attestations when produced
├── anchors/
│   └── anchor-cursor.json      # local proof or RFC 3161 reference when configured
└── self-test-report.json       # only if bb forensic:self-test was run
```

---

### `bb forensic:verify`

Validate a forensic run bundle.

```bash
bb forensic:verify ~/prf-runs/<run-id>

# With Ed25519 signature verification
bb forensic:verify ~/prf-runs/<run-id> --public-key /path/to/public-key.pem
```

**22 checks:**
- 6 required file existence checks
- 8 optional file existence checks
- 4 required directory existence checks
- 1 optional directory check
- Bundle root structural validation
- Overview structural validation
- Preflight report validation
- Evidence DAG presence
- Bundle root self-referential hash recomputation
- Overview self-referential hash recomputation
- Ed25519 signature verification (with `--public-key`)
- Results summary validation

---

### `bb forensic:export`

Package a bundle for external auditors.

```bash
bb forensic:export ~/prf-runs/<run-id>
# Creates forensic-run-<run-id>.tar.gz in the current directory

bb forensic:export ~/prf-runs/<run-id> --output /tmp/my-bundle.tar.gz
```

The export includes `export-manifest.json` with bundle-id, bundle-hash,
file listing, and timestamp.

---

### `bb forensic:import`

Import a bundle from a portable archive.

```bash
bb forensic:import forensic-run-<run-id>.tar.gz
# Extracts to ~/prf-runs/<run-id>/

bb forensic:import my-bundle.tar.gz --output /tmp/imported
```

---

### `bb forensic:reproduce`

Re-execute a run from its bundle and compare results.

```bash
bb forensic:reproduce ~/prf-runs/<run-id>
```

**Before reproduce:** checks whether current source `code-hash` matches
the bundle's code-hash. If source changed, warns about expected divergence.

**Comparison** (field by field):
- `bundle/hash`
- `bundle/schema-version`
- `overview/hash`
- `execution/summary/status`
- `execution/summary/totals/*` (total, passed, failed, expected, unexpected)
- `registry/snapshot/*` (all 6 registry hashes)
- `source/tree-hash`, `source/tree-hash-algorithm`

Produces `reproduce-report.json` in the output directory.

---

### `bb forensic:self-test`

Validate pipeline determinism by running twice and comparing.

```bash
bb forensic:self-test
```

Executes the same run twice with identical inputs. Compares 13 stable
fields. Reports one of:
- **deterministic** — all stable fields match
- **non-deterministic** — one or more fields differ
- **inconclusive** — insufficient comparison data
- **failed** — one or both runs aborted

Excludes volatile fields: wall-clock time, execution node hash (includes
timestamp), absolute temp paths.

---

## Configuration (Environment Variables)

All hardening and path constants can be overridden without editing source
files. See `docs/forensic/FORENSIC_HARDENING.md` for the full reference.

| Variable | Default | Purpose |
|---|---|---|
| `PRF_SOURCE_ROOTS` | `src,protocols_src,benchmarks,data/concepts,scenarios,suites,resources` | Replay-critical source dirs for `code-hash` |
| `PRF_CODE_HASH_ALGORITHM` | `source-tree-hash.v1.path-content-sha256` | Hash algorithm name |
| `PRF_RUNS_ROOT` | `~/prf-runs` | Output directory base |
| `PRF_ARTIFACT_DIR` | `results/test-artifacts` | Clojure artifact dir |
| `PRF_EVIDENCE_USER` | `evidence_runner` | Expected UID for check |
| `PRF_EVIDENCE_WORKSPACE` | `/var/lib/evidence-runner` | Expected workspace path |

Example:

```bash
PRF_SOURCE_ROOTS="src,protocols_src,benchmarks,data/concepts,scenarios,suites,resources" \
PRF_RUNS_ROOT=/mnt/evidence-archive \
PRF_EVIDENCE_USER=dedicated-runner \
  bb forensic:run
```

---

## Hardening Checks

Isolation checks run during every forensic execution. Results are recorded
in the bundle root as `isolation/*`. Composite grade:

| Mode | All Checks Pass | Any Check Fails |
|---|---|---|
| `private-tmpfs` | `full` | `good` |
| `shared-filesystem` | `partial` | `basic` |

The 5 checks:
1. **UID** — process runs as expected user (default `evidence_runner`)
2. **ptrace scope** — `/proc/sys/kernel/yama/ptrace_scope` >= 3
3. **/proc isolation** — basic heuristic probe
4. **Filesystem access** — write access to workspace dir
5. **Privileges** — not running as root

---

## Signing

```bash
# Generate a key pair
openssl genpkey -algorithm ed25519 -out signing_key.pem
openssl pkey -in signing_key.pem -pubout -out signing_key.pub.pem

# Run with signing
bb forensic:run --sign signing_key.pem --signing-key-id my-key

# Verify the signature
bb forensic:verify ~/prf-runs/<run-id> --public-key signing_key.pub.pem
```

---

## Private Tmpfs Isolation

```bash
# Requires Linux with user namespace support
bb forensic:run --private-tmpfs
```

Wraps execution in a private mount namespace (`unshare --user --map-root-user -m`)
with a tmpfs workspace. Output is invisible to other processes during execution.
After completion, results are copied to `~/prf-runs/` and the tmpfs is discarded.

Fallback: if `unshare` is unavailable, logs a warning and uses the shared
filesystem. The `isolation/mode` field records the actual mode used.

---

## Scenarios and Suites

The default run executes the **in-process invariant registry** (S01–S107+),
which is internally labeled `:sew-invariants`. To run a named file-backed
suite, set `:suite/key` in the run request:

```clojure
:suite/key :dispute-resolution-scenarios   ;; 44 Sew scenarios
:suite/key :sew-yield-scenarios            ;; 15 Sew + yield scenarios
:suite/key :yield-provider-scenarios       ;; 5 standalone yield scenarios
```

When a named suite is used, the forensic runner automatically bundles the
scenario source files into the output's `scenarios/` directory.

---

## Evidence DAG

The forensic bundle includes an `evidence-dag/` directory populated with
execution node files from the Clojure pipeline. These are content-addressed
evidence records that capture:

- Scenario execution events
- State transitions
- Invariant checks
- Claim evaluations
- Attestations

An `evidence-dag-inventory.json` manifest provides per-file SHA-256 hashes,
byte counts, and extension breakdown. EDN files are inventoried but not
semantically parsed (Phase A — inventory only).

---

## See Also

- `docs/forensic/CAPABILITIES.md`
- `docs/forensic/TRUST_MODEL.md`
- `docs/forensic/FORENSIC_PREFLIGHT_SPEC_V1.md`
- `docs/forensic/FORENSIC_HARDENING.md`
- `docs/forensic/PRODUCTION_GAPS.md`
- `docs/RUN_REQUEST_SPEC_V1.md`
- `docs/RUN_BUNDLE_ROOT_SPEC_V1.md`
- `docs/RUN_OVERVIEW_SPEC_V1.md`
