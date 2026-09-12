# Conformance Framework — Resource Safety

Adopted as part of G9c.  Before verification is exposed to arbitrary external
bundles, resource limits MUST be enforced and MUST produce typed rejection
outcomes (`rejected`, non-claimable, with a stable issue code), never crashes,
hangs, or partial verification.

## Limits

| Limit | Value | Typed issue code | Enforced in |
|---|---|---|---|
| Maximum bundle size | 10 MiB | `bundle-too-large` | JS verifier, Clojure CLI |
| Maximum JSON nesting depth | 64 | `nesting-too-deep` | JS verifier, Clojure CLI scanner |
| Maximum receipt count (validation + capability + execution) | 1000 | `too-many-receipts` | JS verifier |
| Maximum issue count emitted | 100 | (truncation, never a crash) | JS verifier |
| Duplicate JSON keys | rejected | `duplicate-json-key` | all three verifiers |
| Malformed JSON | rejected | `malformed-json` | all three verifiers |

## Canonical-value framing (decoder admission profile)

Independent of the JSON bundle surface, `resolver-sim.hash.framing-view`
decodes untrusted canonical-value byte streams.  It enforces its own defensive
admission profile so that hostile framing data cannot cause memory or CPU
exhaustion through valid-looking lengths and counts.  Lengths are validated
before any allocation, and every admission path (`decode-one`,
`frame-stream`, `verify-stream` / `verify-single`) enforces the *same* profile
— these limits are a deliberate consensus boundary between verifiers, not an
accidental one.  Limits are sourced from `config/defaults.edn` (the `:framing`
section, overridable via `PRF_DEFAULTS_CONFIG_PATH`).

| Limit | Default | What it bounds |
|---|---|---|
| `:max-stream-bytes` | 1 MiB | Total bytes of a stream / single canonical value.  Exceeded → `:max-stream-bytes` |
| `:max-component-count` | 10000 | Top-level components in a concatenated stream.  Exceeded → `:max-component-count` |
| `:max-payload-bytes` | 1 MiB | A single string/keyword payload, checked before allocation.  Exceeded → `:max-payload-bytes` |
| `:max-collection-depth` | 64 | Nested collection depth.  Exceeded → `:max-collection-depth` |
| `:max-collection-members` | 100000 | Members of a single vector/map.  Exceeded → `:max-collection-members` |

All violations surface as `{:type :limit-exceeded :code :limit-exceeded}` (or
the `:limit-exceeded` status on `frame-stream`), never as a malformed or
non-canonical finding: inadmissibility under the admission profile is a
resource-policy rejection, distinct from `:malformed` (structural) and
`:noncanonical` (encoding) classes.

The verification path (`verify-stream` / `verify-single`) decodes leanly —
per-byte role annotations and per-component SHA-256 payload commitments are
explanatory artifacts not needed to establish framing/validity, so the
admission path's cost is proportional to stream size without a large constant
factor.

## Rules

1. A limit violation MUST result in a machine result with
   `status: rejected`, `claimable: false`, and the typed issue code above.
   It MUST NOT crash the verifier or return a partial verdict.
2. Limits apply to the portable serialized form (the bytes a consumer ships),
   so all verifiers evaluate the same boundary.
3. Limits are implementation-wide defaults.  A future deployment MAY raise them
   only through a versioned configuration that the bundle can record in its
   informational environment fields; the committed defaults above remain the
   reference for `:conformance/core-version 1`.
4. Duplicate-key and malformed-input behaviour is uniform across verifiers
   (see CR-004); it is a typed rejection, not a last-value guess.

## Rationale

The JSON round-trip defect found during externalisation showed that the
serialization boundary is a high-value attack surface: a parser that silently
resolves duplicate keys to the last value can change a canonical preimage
without changing the visible document.  Resource limits close the remaining
public-verifier concerns that are not covered by logical conformance
correctness: unbounded nesting, unbounded receipt counts, and unbounded input
size.  The canonical-value framing profile applies the same reasoning to the
untrusted-input admission contract of the byte decoder.
