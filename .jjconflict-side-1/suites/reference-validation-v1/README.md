# Reference Validation Suite v1

Reference Validation Suite v1 is currently a **deterministic public evidence harness**.

## Status

Public candidate, **v1.3 simulator-backed**.

## Evidence classification

- All 8 scenarios are simulator-backed (replay + trace export + evidence invariant verification)
- Evidence invariant IDs map to simulator canonical IDs in `src/resolver_sim/sim/reference_validation_evidence.clj`
- Claim-to-invariant mapping: see `manifest.edn`

Rows are produced only by `resolver-sim.sim.reference-validation` (see `manifest.edn`).

## Commands

```bash
make reference-validation-v1          # write actual/
make verify-reference-validation-v1   # compare actual/ vs expected/
clojure -M:reference-validation --refresh-expected   # refresh pinned hashes after intentional output changes
./scripts/test.sh reference-validation
```

