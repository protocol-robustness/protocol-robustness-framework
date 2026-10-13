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

Run the isolated package qualification (including the complete V1 root chain)
from the repository root:

```sh
bash scripts/qualify_accounting_reductions_package.sh
```

The script copies only this package and its declared canonical dependency to a
temporary directory, computes a deterministic source-content-set hash, then
runs `clojure -Srepro -M:test` without the monorepo source classpath. The suite
pins the same contract, request, plan, work-unit, partial, reconciliation, and
semantic result roots as the repository-wide golden vector. This establishes
the package's clean source boundary, not independent publication or external
dependency resolution. The canonical dependency remains an unpublished
in-tree candidate.

The package public callable surface is documented in
[`REPOSITORY_BOUNDARIES_AND_ARTIFACT_OWNERSHIP.md`](../../docs/research/REPOSITORY_BOUNDARIES_AND_ARTIFACT_OWNERSHIP.md).
