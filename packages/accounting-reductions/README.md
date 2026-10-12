# Accounting reductions

Status: in-tree source package; not published and not independently pinned.

The Clojure package under `clj/` owns the existing
`resolver-sim.accounting.reductions` implementation. It defines the closed
V1 integer sum-by-qualified-category operation, exact partition coverage,
partial admission, deterministic reconciliation, and semantic result roots.
It does not accept research claims, issue obligations, allocate pro-rata
outcomes, or move funds.

The only domain dependency is the shared `prf-canonical` package candidate for
canonical value hashing and SHA-256 root references. The explicit accounting
domain-tag table preserves the existing frozen V1 hash domains. Root-level
golden tests enforce byte-for-byte root compatibility with the former source
location.

Run the package-local suite from `clj/`:

```sh
clojure -M:test
```

The local `deps.edn` references the adjacent in-tree canonical package. This
proves a narrow declared source closure, not independent package publication
or immutable remote dependency pinning. A release still requires a pinned
canonical dependency, clean-room artifact/provenance, and package-level
conformance qualification.

The package public callable surface is documented in
[`REPOSITORY_BOUNDARIES_AND_ARTIFACT_OWNERSHIP.md`](../../docs/research/REPOSITORY_BOUNDARIES_AND_ARTIFACT_OWNERSHIP.md).
