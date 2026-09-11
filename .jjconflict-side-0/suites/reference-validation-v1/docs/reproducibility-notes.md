# Reproducibility Notes

- Inputs are pinned via `SUITE.yaml`, `VERSION`, `LOCKFILE.md`, and `configs/*.edn`.
- `scripts/run.sh` emits canonical compact JSON without wall-clock fields.
- Expected outputs are committed under `expected/` with SHA-256 files.
- `scripts/verify.sh` checks presence, byte-equality, and hash equality.
- Runtime assumptions target Linux + Java 21 + Clojure 1.12 + Python 3.11.

## Verifying Evidence Integrity
This suite uses the Framework Artifact Registry (v1.1). To independently verify the evidence bundle:

1. Ensure the bundle is in `results/actual/`.
2. Use the verifier script:
   ```bash
   python3 scripts/verify_evidence_bundle.py --bundle-dir results/actual/ --public-key keys/reference-suite-signer.pub
   ```
3. See `keys/README.md` for trust model details and identity attestation.
