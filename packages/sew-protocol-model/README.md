# Sew protocol model

Status: extracted source candidate published on this PR branch. This package
uses immutable PRF and economics revisions and is not a package-registry
release.

## Dependency pins

The runtime manifest pins PRF core to
`4c4b9ac4b3bbbac3e44a47cb4df7d5197bee7dc3` and the Sew pro-rata economics
adapter to `dcdf49720f7d2ab52eeb6e34a0a29f652d4dfddd`. Both revisions are
fetchable from the PRF repository. The test alias does not replace either
runtime dependency with a local source override.

## Qualification

From a clean PRF repository checkout:

```sh
cd packages/sew-protocol-model
clojure -Srepro -M:test -m resolver-sim.protocols.sew.package-test-runner
```

The test alias includes the Yield integration source as test support. The
package runtime paths remain limited to `src`; tests and support sources are
added only by the explicit `:test` alias.
