# RUNNER_MAILBOX_VALIDATION_SPEC_V1

Status: Draft V1

## 1. Purpose

Define the mailbox validation framework: a set of deterministic checks
that verify mailbox lifecycle invariants, message integrity, runner
identity, and signature correctness.  The validation is available as
`bb forensic:mailbox:validate` and as a Python API.

## 2. Design Principles

### 2.1 Deterministic and Portable

Validation checks produce the same results regardless of where the
mailbox directory is located.  No check depends on absolute paths,
filesystem metadata, or host identity.

### 2.2 Layered Severity

Each check has a severity level:

| Severity | Meaning | Exit code |
|---|---|---|
| `pass` | Check passed | — |
| `info` | Informational, not a problem | 0 |
| `warn` | Non-critical issue (e.g. unsigned submission) | 0 |
| `fail` | Invariant broken (e.g. hash mismatch) | 1 |

The overall exit code is 0 if no `fail` checks, 1 otherwise.

### 2.3 Extensible

New checks can be added without changing existing ones.  Each check is
an independent function that returns a `CheckResult`.

## 3. Check Taxonomy

### 3.1 Structural Checks

| Check | Severity | Description |
|---|---|---|
| `mailbox-exists` | fail | `mailbox.json` exists and is valid JSON |
| `run-request-exists` | fail | Run request directory present for given hash |
| `run-request-valid` | fail | `request.json` has required fields |
| `submissions-dir-exists` | info | Submissions directory present |
| `consensus-dir-exists` | info | Consensus output directory present |

### 3.2 Message Integrity Checks

| Check | Severity | Description |
|---|---|---|
| `submission-hash-valid` | fail | `runner-message/hash` matches recomputed canonical hash |
| `submission-schema-version` | warn | `runner-message/schema-version` is `"runner-message.v1"` |
| `submission-timestamp-reasonable` | info | Timestamp is not in the future |
| `submission-run-request-hash` | warn | `runner-message/run-request-hash` matches the enclosing run |

### 3.3 Identity and Signature Checks

| Check | Severity | Description |
|---|---|---|
| `submission-signed` | info | Whether the submission has a signature |
| `submission-signature-valid` | fail | Signature verifies against the embedded public key |
| `submission-signer-status` | warn/fail | Signer's registry status (trusted=pass, unknown=warn, revoked=fail) |
| `submission-equivocation` | warn | Same runner submitted different results (Phase 2) |
| `submission-equivocation-crypto` | fail | Same public key signed different hashes (Phase 3) |

### 3.4 Consensus Certificate Checks

| Check | Severity | Description |
|---|---|---|
| `certificate-hash-valid` | fail | `consensus-certificate/hash` matches recomputed hash |
| `certificate-agreed-hash` | info | Agreed hash matches a majority of submissions |
| `certificate-signature-valid` | fail | Certificate signature verifies (if signed) |
| `certificate-no-absolute-paths` | pass/warn | No `/tmp/`, `/var/`, or host-specific paths in stable fields |

### 3.5 Evidence Node Checks

| Check | Severity | Description |
|---|---|---|
| `evidence-node-execution-id` | info | `execution/execution-id` is `"execution/consensus"` |
| `evidence-node-references` | warn | Node references resolve to existing certificate/disagreement |

### 3.6 Content-Addressed Object Checks

| Check | Severity | Description |
|---|---|---|
| `object-hash-valid` | fail | Object hash matches its filename |
| `object-hash-algorithm` | warn | `object/hash-algorithm` is `"sha256-json-canonical-v1"` |
| `object-schema-version` | warn | `object/schema-version` is `"mailbox-object.v1"` |

## 4. Validation Output

Each check produces a result:

```json
{
  "check/status": "pass",
  "check/key": "submission-hash-valid",
  "check/message": "Submission runner/a hash verifies"
}
```

The overall report:

```json
{
  "validate/schema-version": "mailbox-validate.v1",
  "validate/mailbox-path": "/path/to/mailbox",
  "validate/run-request-hash": "abc123...",
  "validate/timestamp": "2026-06-28T12:00:00Z",
  "validate/checks": [
    { "check/status": "pass", "check/key": "mailbox-exists", ... },
    { "check/status": "fail", "check/key": "submission-signature-valid", ... }
  ],
  "validate/summary": {
    "total": 12,
    "pass": 10,
    "fail": 1,
    "warn": 0,
    "info": 1
  }
}
```

## 5. Equivocation Detection Flow

```
load_result_submissions(mailbox, run_request_hash)
  ↓
group by runner-id
  ├── one submission → OK
  ├── multiple, same hash → deduplicate (idempotent)
  └── multiple, different hashes → EQUIVOCATION
        ↓
  check if signed:
    ├── both signed with same key → CRYPTOGRAPHIC EQUIVOCATION (fail)
    ├── one signed, one unsigned → accept signed, flag unsigned as suspect
    └── both unsigned → WARN (Phase 2 behavior)
```

## 6. Trust Verification Flow

```
submission with signature
  ↓
extract public-key
  ↓
lookup in identity-registry.json
  ├── not found → "unknown" (warn, verification succeeds but untrusted)
  ├── found, status=trusted → OK (pass)
  ├── found, status=inactive → REJECT (fail)
  └── found, status=revoked → REJECT (fail), flag evidence
```

## 7. CLI Usage

```bash
# Validate mailbox structure
bb forensic:mailbox:validate <mailbox-dir>

# Validate a specific run request
bb forensic:mailbox:validate <mailbox-dir> --run-request-hash <hash>

# Validate with identity registry
bb forensic:mailbox:validate <mailbox-dir> --identity-registry <path>

# Validate with strict mode (warnings become failures)
bb forensic:mailbox:validate <mailbox-dir> --strict

# JSON output
bb forensic:mailbox:validate <mailbox-dir> --output report.json
```

## 8. References

- `RUNNER_IDENTITY_SPEC_V1.md` — identity and trust taxonomy
- `RUNNER_SIGNATURE_SPEC_V1.md` — signing and verification
- `RUNNER_MAILBOX_SPEC_V1.md` — mailbox layout and object store
- `RUNNER_MESSAGE_SPEC_V1.md` — message format
- `RUNNER_CONSENSUS_SPEC_V1.md` — consensus protocol
- `scripts/forensic/mailbox.py` — `validate_mailbox()` function (Phase 2 stub,
  Phase 3 full implementation)
