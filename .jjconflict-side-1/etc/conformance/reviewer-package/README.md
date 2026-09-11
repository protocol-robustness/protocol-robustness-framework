# Sew Trace-Equivalence Attestation — external reviewer package (template)

This directory will contain the first real, externally consumed conformance
bundle (G10b).  Contents on adoption:

- `bundle.json` — the hermetic conformance bundle.
- `verify.sh` / `verify.ps1` — one-command verification (see below).
- `expected-result.json` — the committed expected machine result.
- `CLAIM_SCOPE.md` — what the claim establishes and does not establish.
- `EXCLUSIONS.md` — the explicit excluded-subject universe with reasons.
- release descriptor (from `etc/conformance/release.v1.edn`).
- verifier checksums (from `etc/conformance/assurance.v1.edn`).

IMPORTANT: procedural conformance does not establish general contract
correctness, economic safety, or the absence of undiscovered bugs.
