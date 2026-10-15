# Cross-repository provenance — accounting review remediation

This file records the immutable source identities used to qualify the accounting
review across the three repositories. It is intentionally **not** copied into the
retained bundle, so it can be updated without changing the bundle manifest.

## Immutable revisions

| Repository | Identity | Notes |
| --- | --- | --- |
| PRF (this repo) | reviewed commit `4cccc824` (change `pqmnmstp`); remediation working change `xvummzyu` on top of `4cccc824` | v1 substrate and frozen `evidence/` bundle remain untouched; v2 generation is `evidence-v2/` |
| `prf-evm` | `b1f7c10469d64326009f03fc3bd8fb40a296fe72` (`archive/pre-split-2026-10-06-38-gb1f7c10`) | boundary overlay `test/AccountingReviewBoundary.t.sol` + `test/fixtures/accounting-review/*` |
| `prf-site` | `dc026a7163545e14350db4009430d1c701476f6c` + related uncommitted accounting-review updates | Next.js demo `app/demos/partial-fill-accounting/`, verifier scripts |

The `prf-evm` and `prf-site` working trees were modified locally for verification
only. Nothing was committed, pushed or deployed.

## Qualified artifact identity

- Retained bundle manifest: `sha256:f426d0097f413ce414181f6501ce06ae23b370d03c4965983bec89e5f50be39d`
- Anchored pins root (`trust-pins-v2.edn`): see `trust-pins-v2.edn` (`:pins/root`); digest recorded in `trust-pins-v2.edn.sha256`.
- The same bundle manifest digest is pinned by the site at
  `generated/verification/accounting-review.v1.json` (`bundleManifestSha256`), so
  the files the site offers for download are the same artifacts the Clojure
  anchored verifier and the EVM boundary test qualify.

## Commands and observed results

```sh
# Local reproducibility (not independently anchored)
clojure -M examples/partial-fill/accounting-review/run.clj verify-local \
  examples/partial-fill/accounting-review/evidence-v2 \
  examples/partial-fill/evidence/v3-live-context

# Independent qualification (externally supplied pins)
clojure -M examples/partial-fill/accounting-review/run.clj verify-anchored \
  examples/partial-fill/accounting-review/evidence-v2 \
  examples/partial-fill/evidence/v3-live-context \
  examples/partial-fill/accounting-review/trust-pins-v2.edn

# EVM boundary (owning prf-evm checkout)
PARTIAL_FILL_STAGE=2 forge test --match-path test/AccountingReviewBoundary.t.sol \
  --match-test test_AccountingRatificationCannotAuthorizeCapital -vv

# Site verification
node scripts/verify-partial-fill-accounting.mjs
node scripts/verify-accounting-review.mjs
node --test scripts/accounting-review-state.test.mjs
pnpm typecheck
```

Observed: LOCAL pass; ANCHORED pass; EVM 1 passed (both accounting records
rejected at proof/admission/authorization boundaries); site verifiers pass;
4 browser-state tests pass; TypeScript clean; 60 Clojure tests / 362 assertions
across the four retained namespaces, 0 failures.

Negative checks: a pins file inside the bundle is refused
(`bundle-own-pins-not-independent`); a missing pins argument is refused
(`trusted-pins-required`); a tampered bundle file is refused
(`bundle-file-integrity`); substituting a foreign pins file is refused
(`policy-trust-pin-mismatch`).
