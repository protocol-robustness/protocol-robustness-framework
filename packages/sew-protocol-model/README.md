# Sew protocol model

Status: in-tree extraction candidate with immutable PRF and economics commit
pins. It is not a standalone artifact release.

## Dependency pins

The runtime manifest pins PRF core to
`4b628c8ef15b1970425804aaf06014c960469357` and the Sew pro-rata economics
adapter to `ae258b36f3b6f1c0ee4bcf9d913e4cea136994c0`. Both commits are
present in the fetched PRF history. A fresh consumer resolution has not been
verified in this environment; the behavioral test result below used explicit
local source overrides and does not prove remote fetchability.

## Qualification

From a clean PRF repository checkout:

```sh
cd packages/sew-protocol-model
clojure -Srepro -M:test -m resolver-sim.protocols.sew.package-test-runner
```

The test alias includes the Yield integration source as test support. The
package runtime paths remain limited to `src`; tests and support sources are
added only by the explicit `:test` alias.
