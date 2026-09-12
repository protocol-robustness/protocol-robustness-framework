# G10b — First Real Bundle and Reviewer Package

## Adoption sequence

Use the **Sew trace-equivalence attestation** first.  It is the best
operational adopter because it already exercises the most complete conformance
path:

- heterogeneous implementation roots;
- semantic fixture validation;
- explicit included/excluded universe;
- execution plans;
- observed capability receipts;
- planned-versus-observed reconciliation;
- coverage-bound attested claims;
- offline bundle verification.

It is also more bounded than the EF review packet, making reviewer confusion
easier to diagnose.

Recommended sequence:

```
Sew trace attestation
→ published benchmark reproduction
→ EF review packet
```

The benchmark then tests researcher-facing interpretation; the EF packet tests
compound packaging and institutional review.

## External reviewer package

The first real bundle MUST ship a very small top-level surface:

```
README
bundle.json
verify.sh          (POSIX) and verify.ps1 (PowerShell)
expected-result.json
CLAIM_SCOPE.md
EXCLUSIONS.md
release descriptor
verifier checksums
```

A reviewer MUST be able to run one command (`./verify.sh`) and receive exactly:

```json
{
  "status": "pass",
  "claimable": true,
  "claim_class": "attested",
  "derived_claim_root": "...",
  "profile_id": "sew-trace-equivalence.v1",
  "included_subjects": 10,
  "excluded_subjects": 8,
  "issues": []
}
```

The package MUST prominently state that procedural conformance does not
establish general contract correctness, economic safety, or the absence of
undiscovered bugs.

## Reviewer usability questions (treated as CLI/docs/packaging work, never a
reason to reopen the conformance core)

- Can a reviewer understand the claim scope?
- Can they obtain and run the verifier easily?
- Is the bundle sufficiently self-contained?
- Are exclusion reasons useful?
- Are issue codes actionable?
- Is the distinction between procedural conformance and underlying correctness
  understood?
- Can the result be archived and verified later?

## Status

The package skeleton lives under `etc/conformance/reviewer-package/`; the real
Sew trace-equivalence bundle is the G10b adoption deliverable and depends on
the live trace pipeline (10 included / 8 excluded subjects).
