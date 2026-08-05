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
size.
