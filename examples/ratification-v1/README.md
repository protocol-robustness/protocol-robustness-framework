# Ratification API golden example

`ratified-with-dissent.json` is an API-shaped example generated from the PRF
ratification implementation. It uses the repository's public test-only
Ed25519 fixture key; it is not a real researcher identity, approval, or
authority record.

Regenerate it from this repository root with:

```sh
clojure -M:test -m scripts.ratification.generate-demo-workspace
```

The test `resolver-sim.research.ratification-demo-contract-test` checks that
the checked-in JSON matches regenerated roots and signatures, preserves one
dissent after the approval threshold is met, and never claims operational
authority. A separate frontend can use the JSON as a display and client
integration fixture; it must not treat the fixture signature as trusted input.
