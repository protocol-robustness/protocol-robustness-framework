# Pro-rata EVM V2 Conformance

## Status and authority

This package defines language-neutral conformance vectors for
`pro-rata-evm-v2`. It is an additive conformance surface over
`portable-pro-rata-contract.v1`, whose release root is committed by every
positive vector. The portable contract remains the authority for allocation,
canonical effects, canonical state, and canonical transitions.

`pro-rata-evm-v2` proves deterministic derivation relative to its committed
canonical state-before and retained compilation bodies. It does **not** prove
that state-before is a chain head, that native realization occurred, or that a
transition was admitted or persisted.

## Positive-vector requirements

Every vector field is classified as one of:

- **semantic input** — a value required to derive transition semantics;
- **external authoritative commitment** — a commitment incorporated into the
  V2 identity but not derived by this vector;
- **derived intermediate** — a value produced while reconstructing the chain;
- **expected output** — a comparison target, never a derivation input; or
- **metadata** — descriptive package or case information.

`:state-before` and retained allocation/target-map/semantics bodies are
semantic inputs. `:configuration/root` is an external authoritative commitment
which the implementation must faithfully bind, not derive from this vector.
`:portable-contract/release-root` identifies the normative semantic contract;
it is neither a transition-derived root nor a substitute for configuration
authority.

An implementation MUST reconstruct every expected root from semantic input
bodies. Expected roots and expected bodies MUST remain comparison-side values
and MUST NOT be loaded as inputs to the derivation under test.

Each positive vector exposes:

1. semantic input bodies;
2. external authority commitments;
3. allocation and target-map identities;
4. V3 compilation and normalized-effects bodies/identities;
5. canonical before/after state bodies/identities;
6. canonical transition identity; and
7. the final V2 EVM statement identity.

The aggregate profile additionally requires reconstruction of the complete
Stage A target-map-validation material. It must not be projected into a weaker
identity.

## Rejection vectors

The rejection vectors define three cross-runtime refusal classes:

- forged post-state;
- effect substitution; and
- retained-body substitution.

Each rejection vector is a mutation relative to a named positive vector. The
mutation records whether enclosing artifacts are rehashed and declares a broad
failure class, not an implementation-specific exception string. The complete
dependency mutation matrix remains in the executable Clojure conformance tests.

The package verifier MUST verify manifest completeness, reconstruct positive
intermediates without consuming expected roots, and apply every declared
negative mutation after the declared rehash step.

## Exclusions

This package does not define native realization, SEW persistence, write-back,
read-back, historical execution, chain-head authority, admission, or SP1 public
values. Those are later, separately versioned claims.
