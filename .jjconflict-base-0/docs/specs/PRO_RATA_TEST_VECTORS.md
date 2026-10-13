# Pro-Rata Test Vectors

The pro-rata test-vector emitters turn the Clojure reference model into canonical JSON artifacts for regression and future Solidity/Foundry parity tests.

## Source of truth

Current vector emitters are additive wrappers around existing implementation functions:

- Liquidity fulfillment: `resolver-sim.yield.partial-fill/calculate-fulfillment-pro-rata`
- Sew slash allocation: `resolver-sim.protocols.sew.economics/calculate-sew-slash-allocation`
- Shared generic allocation primitive used by Sew: `resolver-sim.pro-rata.engine/allocate-pro-rata*` (public entry `resolver-sim.pro-rata.allocation/allocate-pro-rata`)

The emitters do not reimplement allocation math and should not be used to change settlement semantics.

## Namespace

```clojure
resolver-sim.test-vectors.pro-rata
```

Useful functions:

- `emit-liquidity-fulfillment-vector`
- `emit-slash-allocation-vector`
- `write-liquidity-fulfillment-vectors!`
- `write-slash-allocation-vectors!`
- `write-golden-vectors!`

By default, writer functions emit generated artifacts under `results/test-vectors/pro-rata/`. Committed golden fixtures live in `resources/test-vectors/pro-rata/`.

## Canonical schemas

Two versioned schemas are emitted:

- `liquidity-fulfillment-vector.v1`
- `slash-allocation-vector.v1`

Each vector includes:

- `schema-version`
- `vector-id`
- `domain`
- `description`
- `input`
- `expected-output`
- `invariants`
- `source-function`
- `source-metadata`
- `policy-metadata`
- `units`
- `snapshot-metadata`
- `trust-boundary`
- `edge-case-tags`
- `notes`
- `canonical-input-hash`
- `canonical-expected-output-hash`
- `full-vector-hash`

## Numeric representation

Canonical JSON is designed for Solidity and audit tooling:

- Amount-like and weight-like values are decimal strings.
- Floats are not emitted in canonical fields.
- Exact Clojure ratios in raw reference output are encoded as numerator/denominator objects.
- Map keys are sorted before JSON encoding; vector order is preserved.

## Invariants

Liquidity vectors include machine-readable invariants for:

- conservation: `total_requested == total_fulfilled + total_unmet`
- fulfilled amount bounded by available liquidity
- no over-fulfillment per bucket
- no negative fulfilled or unmet amounts
- deterministic input ordering

Slash vectors include machine-readable invariants for:

- conservation: `total_obligation == total_debited + total_unmet + remainder`
- cap enforcement
- no negative debit or unmet amount
- zero-weight handling under current Sew policy
- deterministic input ordering

## Solidity parity workflow

A future Foundry test can consume the committed JSON fixtures as follows:

1. Load a vector from `resources/test-vectors/pro-rata/`.
2. Decode decimal-string amounts into `uint256`.
3. Run the Solidity implementation with `input`.
4. Compare Solidity output against `expected-output` canonical fields.
5. Optionally verify `canonical-input-hash`, `canonical-expected-output-hash`, and `full-vector-hash` using the same canonical JSON rules.

The Clojure model is the reference model for parity tests, not a trusted runtime for production settlement.

## Evidence artifacts

See `docs/specs/PRO_RATA_PROPORTIONAL_MATH_SPEC.md` Section 5 for the complete evidence
stack: projection artifacts, allocation result artifacts, claim evaluation nodes,
execution evidence nodes, and the artifact dependency graph. Test vectors are
the committed, schema-versioned subset of this stack — they freeze the input,
expected output, and invariants for Solidity parity testing without depending
on the full replay pipeline.
