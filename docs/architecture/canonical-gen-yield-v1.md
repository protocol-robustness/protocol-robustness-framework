# Canonical generated yield scenarios v1

This profile adds two distinct PRF commitments using the existing typed
canonical encoder and SHA-256 domain-hash machinery:

1. `prf.yield-materialized-scenario.v1` identifies validated executable
   `yield-v1` scenario content.
2. `prf.yield-generation-provenance.v1` records how a materialization was
   generated and binds its root.

These roots establish integrity only. They do not establish source
authenticity, replay success, expected-result correctness, admission,
authority, or execution.

## Materialized scenario identity

The materialized body is:

```clojure
{:yield-scenario/schema "prf.yield-materialized-scenario.v1"
 :yield-scenario/materialization <normalized scenario>}
```

The profile uses the existing PRF scenario normalization and yield replay
validator. It accepts supported PRF scenario versions and only the `yield-v1`
protocol (or an omitted protocol, normalized to `yield-v1`). Unknown top-level
fields, unsupported value types, non-finite numbers, fractional event
sequence/time values, and invalid replay inputs are rejected. The supported
top-level envelope is closed to fields present in the PRF yield scenarios and
the current generator.

`:scenario-id`, its `:id` alias, and `:seed` are excluded from materialized
identity. They identify/name a generation attempt rather than replay behavior;
the actual generator seed is committed in provenance. All other accepted
scenario fields, including event order, protocol configuration, expectations,
and schema version, remain in the materialized commitment. `:protocol` is
explicitly normalized to `"yield-v1"`.

The exact domains are:

| Commitment | Domain tag | Body |
| --- | --- | --- |
| Materialized scenario | `YIELD_MATERIALIZED_SCENARIO_V1` | The body above |
| Generation provenance | `YIELD_GENERATION_PROVENANCE_V1` | The provenance body below |

Both use `canonical-hash-scheme.v1` (SHA-256 over domain-tag bytes followed by
the existing PRF typed canonical bytes). Numeric projection reuses
`resolver-sim.hash.canonical/project-committable-content`: integers remain
integers, ratios are exact numerator/denominator pairs, doubles and floats use
their exact hexadecimal representation, and `BigDecimal` retains its exact
decimal spelling. Non-finite floats are rejected. This preserves numeric
representation rather than silently converting APY or ratios to floating
point or basis points. Integer event sequence and clock fields avoid replay's
existing truncation boundary.

The supported serialization round trip for v1 is Clojure EDN
`pr-str`/`clojure.edn/read-string`, followed by the existing scenario
normalizer. Arbitrary JSON parsers are not claimed as equivalent because they
may change numeric host types. A JSON adapter must specify and test its numeric
decoding contract before being treated as a supported commitment input.

## Generation provenance

The closed provenance body contains:

```clojure
{:yield-generation/schema "prf.yield-generation-provenance.v1"
 :generator/id <non-empty string>
 :generator/version <non-empty string>
 :generator/source-commitment "sha256:<64 lowercase hex digits>"
 :rng/algorithm <non-empty string>
 :rng/seed <signed 64-bit integer>
 :generation/options <normalized map>
 :yield-scenario/root "sha256:<materialized scenario root>"}
```

If the source scenario includes `:seed`, it must match `:rng/seed`. Options are
committed after the same existing canonical projection. Verification rebuilds
the materialized root and provenance commitment from the supplied scenario
and provenance body; stored roots alone are not trusted. The source commitment
is a reference supplied by the caller and is not independently authenticated
by this profile.

The API is `resolver-sim.yield.scenario-commitment`:

- `materialized-scenario-body`
- `scenario-commitment`
- `generation-provenance-body`
- `generation-provenance-commitment`
- `verify-generation-provenance!`

## Frozen vector

`resources/test-vectors/gen-yield/materialized-v1.edn` freezes a real
materialized scenario from the existing generator, its canonical bytes and
its root. The root was independently checked as:

```python
hashlib.sha256(
    b"YIELD_MATERIALIZED_SCENARIO_V1" + bytes.fromhex(canonical_bytes_hex)
).hexdigest()
```

Run the independent digest check with:

```bash
python3 scripts/verify_gen_yield_vector.py
```

That check independently hashes the retained canonical bytes, but it does not
re-encode the scenario. The fixture is a canonical commitment vector, not an
assertion that its seed was used by a deterministic PRNG. Seeded generation
and generator-source qualification are separate work.
