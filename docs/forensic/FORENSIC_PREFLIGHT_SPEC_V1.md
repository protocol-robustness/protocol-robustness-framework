# FORENSIC_PREFLIGHT_SPEC_V1

Status: Draft V1

## 1. Purpose

Define the checks that MUST pass before a run may be treated as a forensic
(evidence-producing) execution. The preflight prevents accidental generation
of non-reproducible, incomplete, or untrustworthy evidence.

A failed preflight produces a structured report. It does NOT produce a
partial forensic bundle. No evidence output is written when the preflight
fails.

## 2. Design Principles

### 2.1 Fail Early, Fail Closed

All checks run before any scenario execution begins. If any check fails,
the forensic run is rejected. No partial output is produced.

### 2.2 Checks Are Independent

Each check reports pass/fail independently. A single report shows all
failures, not just the first one. The caller can fix multiple issues
before the next attempt.

### 2.3 Checks Are Versioned

The preflight check set is versioned. Changes to the check set increment
the version. Forensic bundle output includes the preflight schema version
used.

### 2.4 Extensible

New checks may be added without changing existing checks. Each check has
a unique key and a stable pass/fail contract. Extension points are
documented.

## 3. Check Registry

### 3.1 Required Checks (Hard Fail)

These checks MUST pass. If any fails, the run MUST NOT proceed.

| # | Key | Check | Failure Guidance |
|---|---|---|---|
| 1 | `run-request-declared` | A run request file exists at the declared path | Provide `--run-request <path>` pointing to a valid RUN_REQUEST_SPEC_V1 file |
| 2 | `run-request-valid` | Run request parses and validates against RUN_REQUEST_SPEC_V1 schema | Fix structural errors in the run request |
| 3 | `registry-snapshot-present` | Registry snapshot file exists | Run with `--registry-snapshot <path>` or use default |
| 4 | `registry-snapshot-valid` | Registry snapshot parses and all referenced registries are known | Ensure snapshot references only registered registries |
| 5 | `runner-identity-present` | Runner identity is declared in run request or config | Set `:runner-selection :mode :pinned` with a known `:runner-id` |
| 6 | `hash-intent-registry-present` | Hash intent registry can be loaded and validated | Check `resolver-sim.hash.canonical/hash-intents` |
| 7 | `evidence-policy-present` | Evidence policy file exists and parses | Provide evidence policy matching EVIDENCE_POLICY_SPEC_V1 |
| 8 | `output-directory-clean` | Target output directory does not exist or is empty | Remove existing output or specify a new run path |
| 9 | `no-source-tree-writes` | Run configuration does not write to any path under the project repo | Configure output to `~/prf-runs/<run-id>/` |
| 10 | `no-evidence-overwrite` | Output path contains no prior forensic run evidence | Use a unique run id (timestamp-based) |
| 11 | `external-network-declared` | Run request declares `:network-policy :allow` or `:deny` | Add `:network-policy` to run request |
| 12 | `scenario-set-declared` | All scenarios to execute are explicitly declared (no ambient discovery) | List all scenario paths in run request or suite definition |

### 3.2 Warning Checks (Soft Fail)

These checks emit warnings. The run MAY proceed, but the output is marked
as `:non-canonical` with a `:non-canonical-reason`.

| # | Key | Check | Warning |
|---|---|---|---|
| 13 | `source-snapshot-recorded` | Git commit and dirty state captured | Bundle marked non-canonical; source provenance incomplete |
| 14 | `environment-snapshot-recorded` | OS, tooling versions captured | Bundle marked non-canonical; reproducibility weakened |
| 15 | `dependencies-pinned` | All dependencies use pinned versions (not "latest") | Bundle marked non-canonical; dependency drift possible |
| 16 | `canonical-hash-values-supported` | All canonical hash algorithms in use are known to this runner | Bundle marked non-canonical; hash algorithm may not be reproducible |

### 3.3 Informational Checks (Never Fail)

These checks produce report entries but never block or warn. They exist
for the audit trail.

| # | Key | Check | Purpose |
|---|---|---|---|
| 17 | `runner-version` | Runner software version recorded | Audit trail — which runner version produced this |
| 18 | `policy-versions` | Evidence and execution policy versions recorded | Audit trail — which policies governed this run |
| 19 | `output-path` | Final output path recorded | Audit trail — where the bundle was written |
| 20 | `run-request-overview` | Summary of run request parameters | Audit trail — what was requested |

## 4. Preflight Report Schema

```clojure
{:preflight/schema-version "forensic-preflight.v1"
 :preflight/timestamp       #inst "2026-06-26T17:30:00Z"
 :preflight/runner-id       "resolver-sim-v0.1.0"
 :preflight/status          :pass       ;; :pass | :fail | :pass-with-warnings
 :preflight/checks
 [{:check/key            "run-request-declared"
   :check/status         :pass        ;; :pass | :fail | :warning | :info
   :check/message        "Run request found at inputs/run-request.edn"
   :check/severity       :required}   ;; :required | :warning | :info
  ;; ... more checks ...
  ]
 :preflight/summary
 {:total   20
  :pass    18
  :fail    0
  :warning 2
  :info    20}}
```

- `:preflight/status` is `:pass` if all required checks pass (warnings
  allowed), `:fail` if any required check fails.
- Each check includes `:check/key`, `:check/status`, `:check/message`,
  and `:check/severity`.
- The summary provides aggregate counts.

## 5. Preflight Report File

The preflight report is written to the forensic run output directory as
`preflight-report.json` before scenario execution begins. This provides a
record that the preflight was run and what it found.

If the preflight passes, scenario execution proceeds and the report is
included in the final bundle.

If the preflight fails, the report is written to a temporary location and
the caller is notified. No scenario execution occurs.

## 6. Usage

```bash
# Standalone preflight (no execution)
bb forensic:preflight --run-request my-run-request.edn

# Preflight as part of forensic run
bb forensic:run --run-request my-run-request.edn
# (runs preflight automatically before execution)
```

## 7. Extending

New checks are added by appending to the check registry (section 3).
Each new check MUST have:
- A unique `:check/key` (string, kebab-case)
- A declared severity (`:required`, `:warning`, or `:info`)
- A stable pass/fail contract (deterministic, no side effects)
- Documentation of what it verifies and what the failure guidance is

## 8. References

- CAPABILITIES.md — forensic bundle workflow layout and scope
- TRUST_MODEL.md — workflow assurance boundaries
- RUN_REQUEST_SPEC_V1 — canonical run request format
- EVIDENCE_POLICY_SPEC_V1 — evidence policy specification
- HASH_INTENT_REGISTRY_SPEC_V1 — hash intent registry specification
- RUN_BUNDLE_ROOT_SPEC_V1 — bundle root format
