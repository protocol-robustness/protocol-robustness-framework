# PRF canonical codec

Status: local standalone source candidate. It is not a published package and
does not yet have a pinned remote dependency coordinate.

This package contains the generic canonical value codec and domain-separated
SHA-256 implementations in Clojure and Rust, plus the canonical SHA-256
reference-format API in Clojure. Artifact domain tags, canonical artifact
schemas, and semantic admission rules remain owned by their domain packages.

The binary contract is specified in
[`CANONICAL_HASH_SPEC_V1_BINARY_ENCODING_ABI.md`](../../docs/specs/evidence/CANONICAL_HASH_SPEC_V1_BINARY_ENCODING_ABI.md).
Both implementations consume the same package-owned vectors at
`clj/test/resources/canonical-v1-vectors.tsv`.

## Verify locally

Run the Clojure conformance tests from `clj/`:

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
