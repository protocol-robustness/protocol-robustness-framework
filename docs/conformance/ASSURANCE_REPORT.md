# Conformance Core 1 — External-Assurance Report

Scope: `:conformance/core-version 1` release `:conformance-core-1.0.0`
(`etc/conformance/release.v1.edn`, release root `8e34754c…`).

This report records what the G9c gates actually measured, the defects they
found, and the residual risks.  It does not claim more than the evidence
supports.  **A true clean-room implementation by an independent contributor
remains open**; everything below is the standing, reproducible apparatus the
submission will be judged against.

## Evaluated properties

| Property | Evidence |
|---|---|
| Canonical root parity | `scripts/bundle_verify.py` and `scripts/verify3.mjs` recompute the committed vector roots and derive identical claim roots; `parity-test`, `vectors-test`, `corpus-test` |
| Bundle closure | no verifier resolves missing receipts by running domain code; minimal verifiers import no PRF code (dependency-boundary test) |
| Subject identity | `identity-test`; corpus `identity-substitution-001` |
| Environment and registry binding | `historical-verification-test`; holdout `bound-registry-root-001`, `cross-envelope-env-mismatch-001`, `plan-env-mismatch` (fuzz) |
| Planned-vs-observed reconciliation | `reconciliation-test`; fuzz `wrong-root` (reference-only) |
| Coverage completeness | `coverage-test`; fuzz `coverage-incomplete`; holdout `unexpected-receipt-001` |
| Claim derivation | `bundle-test`, `adversarial-test`; fuzz `missing-claim`, `claim-mode-not-evaluated-001` |
| Historical verification | `historical-verification-test` (committed environment, revocation timing, old claim root) |
| Cryptographic authenticity | `crypto-test`, `vectors-test`; holdout `wrong-domain`, `wrong-preimage`, `revoked-key`, `unknown-algorithm`, effective-boundary cases |
| Admission policy | `admission-test`; holdout `unauthorised-kind-001` |
| Adversarial claim monotonicity | `adversarial-test`; fuzz `malformed-claim-status`, `unicode-lookalike-001` |
| Version rejection | fuzz + corpus `version-unsupported-001`; `unsupported-canonicalisation` historical test |
| Resource safety | `resource-safety-test`; JS limits (`bundle-too-large`, `nesting-too-deep`, `too-many-receipts`) |
| Serialization boundary | differential fuzz: key order, whitespace, Unicode escapes, numbers, empty collections, extra fields |

## Gate results (as committed)

- Public corpus: `node scripts/corpus_gate.mjs` — **PASS** (5 bundle cases,
  JS verifier agrees with the manifest on status and claimability).
- Holdout corpus: `node scripts/holdout_gate.mjs` — **PASS** (21 cases across
  Clojure, Python, JS).
- Mutation testing: `node scripts/mutation_test.mjs` — **9/9 security-relevant
  mutations killed** (every protected property has an effective test).
- Differential fuzzing: `python3 scripts/differential_fuzz.py` — **PASS**
  (20 accept-with-identical-root, 7 reject, 0 disagreement, 0 crash).
- Full Clojure suite: 27 namespaces, 550+ assertions, 0 failures.
- Python parity on committed fixtures: pass / reject / reject as expected.

## Defects found by the G9c apparatus

1. Status `pass` with `claimable? false` on JSON round-tripped bundles — all
   three verifiers initially emitted the contradiction; now `pass` requires
   a derivable claim (spec §13).
2. Python verifier missing the reconciliation-vs-coverage environment-root
   check.
3. `unexpected-receipt` (spec §11) unimplemented in all three verifiers.
4. Clojure `contains?` on a JSON round-tripped `authorised-kinds` vector — a
   membership bug that silently failed authorisation (now set-or-vector safe).
5. Clojure `update-in` materialized an absent `:claim` as `{}`, turning a
   claim-less bundle into a rejection.
6. Clojure verifier never checked plan-vs-reconciliation environment roots.
7. JS claim-core comparison was order-sensitive (`JSON.stringify`), rejecting
   key-reordered valid bundles.
8. The bundle environment envelope, plan, and reconciliation bound different
   environment roots (fixed during G9a externalisation).

Each was fixed and a regression case committed (public corpus, holdout, or
fuzz case).

## Independence achieved

| Dimension | Status |
|---|---|
| Implementation languages | Clojure, Python, JavaScript (Node, zero-dependency) |
| Author independence | **NOT met for a clean-room claim**: all three are by the same author |
| Source-code isolation | each verifier implements only the specification; no cross-imports |
| Dependencies | Python and JS verifiers use only standard libraries (JVM/`node:crypto`); JS Ed25519 via built-in `crypto` |
| Vectors/corpus inspected | the JS verifier was written against the spec + public corpus; holdout and mutation/fuzz results then corrected it |
| Holdout process | private holdout set kept out of the public corpus root and the release artifact |
| Mutation / fuzz methodology | `mutation_test.mjs`, `differential_fuzz.py` |

## Residual risks

- **Shared conceptual defect from an ambiguous specification**: all three
  implementations could share a misunderstanding.  The ambiguity log
  (`AMBIGUITY_LOG.md`) is the mitigation; a true independent implementer
  against the clean-room package is the completion gate.
- **Ed25519 correctness delegated** to JDK / Node crypto libraries.
- **Authorised trust policies may themselves be incorrect or compromised**;
  procedural conformance does not validate signer judgment.
- **Procedural conformance does not establish model/protocol correctness**
  (see claim scope metadata and the threat model).
- **Corpus coverage is finite**; fuzzing and mutation testing bound but do not
  eliminate the gap.
- **Denial-of-service limits** are now specified (RESOURCE_SAFETY.md) but
  timeout/CPU budgets for hostile inputs are not yet formally bounded.
- **No migration path exercised** because no v2 exists.

## Pending items (completion gates for G9c)

1. A clean-room verifier by an independent contributor, against only the
   clean-room package, deriving all normative roots and rejecting every corpus
   and holdout case.
2. A human-written ambiguity log from that submission, resolved through the
   approved mechanisms.
3. Adoption on one real externally consumed bundle (EF review packet, a
   published benchmark conclusion, or the Sew trace-equivalence attestation).
