# PRF canonical codec

Status: local standalone source candidate. It is not a published package and
does not yet have a pinned remote dependency coordinate.

This package contains the generic canonical value codec and domain-separated
SHA-256 implementations in Clojure and Rust, plus Clojure projections,
intent-contract hashing/equality, sequence encoding/commitment, and canonical
SHA-256 reference formatting. Artifact domain tags, canonical artifact
schemas, intent definitions, domain projections, and semantic admission rules
remain owned by their domain packages. Keyword domains resolve through an
owner-supplied mapping; intent hashing accepts an owner-supplied contract.

Public Clojure API:

- `prf.canonical.codec`: `canonical-bytes`, `canonical-bytes-hex`, and
  `validate-canonical-value!`.
- `prf.canonical.hash`: `hash-with-domain`, `domain-hash`,
  `resolve-domain-tag`, `project-canonical-safe`,
  `project-committable-content`, `hash-with-intent`, and `intent-hash=`.
- `prf.canonical.sequence`: `canonical-sequence-bytes`, `sequence-hash`, and
  `verify-sequence-commitment`.
- `prf.canonical.reference`: `sha256-ref`, `valid-sha256-ref?`, and
  `parse-sha256-ref`.

Keyword-to-domain and intent definitions remain with their semantic owners.
Owners pass a keyword mapping to `domain-hash`, or pass an intent contract
containing `:hash/intent`, string `:intent/domain-tag`, and optional
`:intent/projection-fn` to `hash-with-intent`. The PRF compatibility adapter
continues to resolve legacy keyword intents through PRF's registry and then
calls this owner-supplied API. Unsupported domains, intents, and hash schemes
fail explicitly. Sequence verification uses the decoder in this package;
PRF's adapter supplies its existing configurable resource limits explicitly.
The standalone package therefore has no dependency on PRF's source classpath,
configuration, domain registry, or intent registry.

The binary contract is specified in
[`CANONICAL_HASH_SPEC_V1_BINARY_ENCODING_ABI.md`](../../docs/specs/evidence/CANONICAL_HASH_SPEC_V1_BINARY_ENCODING_ABI.md).
Both implementations consume the same package-owned vectors at
`clj/test/resources/canonical-v1-vectors.tsv`.

## Verify locally

Run the Clojure conformance tests from `clj/` (standalone, with no PRF source
classpath):

```sh
clojure -M:test -m prf.canonical.test-runner
```

Run Rust conformance tests from the package root:

```sh
cargo test --offline --manifest-path rust/Cargo.toml
```

## Candidate bundle

From the PRF repository root, run `python3 scripts/package_canonical.py`. It
creates a deterministic source archive and SHA-256 sidecar under
`packages/canonical/releases/`. The archive is a reviewable extraction
candidate, not a published or pinned dependency.
