# Reference Validation Suite v1 Lockfile

## Suite

- Suite ID: reference-validation-v1
- Suite version: 1.3.0
- Status: public-candidate

## Repository state

- protocol-robustness-framework commit: current HEAD (refresh with `clojure -M:reference-validation --refresh-expected`)
- generated at: 2026-06-11 (suite refactored to v1.3.0)

## Runtime

- Clojure version: pinned by deps.edn (org.clojure/clojure 1.12.0)
- Java version: 21 (CI target)
- Python version: 3.11 (CI target)
- OS used for expected outputs: Linux (ubuntu-latest)
- Docker image: optional; not required for v1

## Scenario/config versions

- suite config: reference-config-v1
- economic assumptions: economic-assumptions-v1
- resolver capacity assumptions: resolver-capacity-v1
- chain assumptions: chain-assumptions-v1

## Determinism

This suite is expected to produce byte-stable canonical JSON outputs when run with the pinned configuration.

If local runtime metadata differs, result semantics should remain identical, but exact hashes may differ unless canonical JSON normalization is used.

Canonical JSON formatting is used for expected outputs and hash comparisons.
