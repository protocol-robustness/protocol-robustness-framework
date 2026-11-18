# Sew protocol model

Status: extracted source candidate published on this PR branch. This package
uses immutable PRF and economics revisions and is not a package-registry
release.

## Dependency pins

The runtime manifest pins PRF core to
`e32aba6760ac6db87068d7ff88b6cd146ff7180c` and the Sew pro-rata economics
adapter to `63be481007da0bc4c076223c1821626b8672c702`. Both revisions are
fetchable from the PRF repository. No local source override is used by the test
alias.

## Qualification

From the PRF repository checkout:

```sh
cd packages/sew-protocol-model
clojure -Srepro -M:test -m resolver-sim.protocols.sew.package-test-runner
```

The test alias includes the Yield integration source as test support. The
package's runtime paths remain limited to `src`; test and support sources are
only added by the explicit `:test` alias.
