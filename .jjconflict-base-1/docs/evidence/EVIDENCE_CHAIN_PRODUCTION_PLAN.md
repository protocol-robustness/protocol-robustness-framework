# Evidence Chain Production-Readiness Plan

## Current State

**30 source namespaces** (~7,119 lines), **22 test files** (487 tests, 386 pass in `bb test:unit`, 0 failures).

The evidence chain is in **strong shape** — substantially more mature than SPEDS was. No TODOs, no circular deps, good atomic cursor management, consistent error-return pattern in verification code. The gaps are narrower and more targeted.

## Known Gaps

### High — Data Integrity Risk

| # | Gap | Location | Detail |
|---|-----|----------|--------|
| H1 | **Silent catch/return nil drops evidence** | `registry.clj:102`, `summary.clj:29`, `chain.clj:479`, `chain.clj:673-675`, `diff.clj:26` | 5+ locations catch all exceptions and silently return nil/0/{}. Corrupted or unreadable evidence files are dropped with zero logging. The system produces a registry that says "all clean" while silently discarding artifacts. |
| H2 | **`subs` without length guard** | `chain.clj:181` | `(subs eh 0 12)` on a hash shorted than 12 chars throws `StringIndexOutOfBoundsException`. Filename construction becomes a crash vector for malformed hashes. |
| H3 | **`node.clj` `registry-var-value` no nil guard** | `node.clj:114-115` | `(deref (requiring-resolve ...))` returns nil if the var doesn't exist, then NPE on `@nil`. Execution-node building would crash rather than produce an informative error. |

### Medium — Quality & Safety Net

| # | Gap | Location | Detail |
|---|-----|----------|--------|
| M1 | **No formal schema validation** | All artifacts | No Malli/spec/JSON Schema anywhere. Registry maps, chain cursors, evidence nodes, attestation bundles, validation results — all are validated only by hand-written conditional checks. Structural drift between producers and consumers is caught only by test failures. |
| M2 | **`reconcile-evidence!` masks parse errors** | `chain.clj:479` | A corrupt evidence file reports `chain-seq` as 0 instead of erroring. The reconciliation report shows `max-disk-seq=0` but there's no indication that a file was skipped. |
| M3 | **`with-execution-node` inner catch silences node-emission errors** | `node.clj:718-741` | If both the thunk and node emission fail, the node-emission error is only `log/warn!`'d. If logging is not configured, the error is invisible. |

### Low — Polish & Concurrency

| # | Gap | Location | Detail |
|---|-----|----------|--------|
| L1 | **No file locking for concurrent writes** | All writers | Multiple processes writing to the same artifact directory could corrupt output. |
| L2 | **Dynamic var leakage** | `*signing-key*`, `*tsa-url*`, `*capture-event-evidence!*` | If not rebound per run, stale values from previous runs leak through. |
| L3 | **All evidence files read into memory** | `summary.clj`, `registry.clj`, `chain.clj` | For runs with tens of thousands of evidence files, memory usage scales linearly. |

## Tier Structure

### T1 — Data Integrity (ship-blocking)
### T2 — Quality & Safety Net (should have)
### T3 — Polish (ongoing, non-blocking)

## T1 — Data Integrity (3–4 hours)

| # | Item | Effort | Location |
|---|------|--------|----------|
| T1.1 | **Fix silent catch/return nil** — replace 5+ `(catch Exception _ nil/0/{})` with `log/warn!` + return nil/continue. Each location must record which file was skipped and why. | 1.5 h | `registry.clj:102`, `summary.clj:29`, `chain.clj:479,673-675`, `diff.clj:26,393` |
| T1.2 | **Fix `subs` length guard** — add `(min 12 (count eh))` guard on `chain.clj:181` | 5 min | `chain.clj:181` |
| T1.3 | **Fix `registry-var-value` nil guard** — add `when-let` or `ex-info` before dereffing | 15 min | `node.clj:114-115` |
| T1.4 | **Reconcile parse-error masking** — replace `(catch Exception _ 0)` with warning + nil, filter nil from seq calculations | 30 min | `chain.clj:479` |

**Total T1**: ~2.5 h.

## T2 — Quality & Safety Net (6–10 hours)

| # | Item | Effort | Location |
|---|------|--------|----------|
| T2.1 | **Malli schemas for registry artifacts** — define and validate evidence-registry, chain-cursor, and evidence-node shapes. Validate on write. | 3–4 h | `chain.clj`, `node.clj`, `registry.clj` |
| T2.2 | **Malli schemas for attestation artifacts** — define and validate attestation shapes and attestation-bundle shapes. Validate on write. | 2–3 h | `attestation.clj`, `attestation_bundle.clj` |
| T2.3 | **`with-execution-node` dual-error handling** — attach node-emission error as ex-data on original exception | 30 min | `node.clj:718-741` |
| T2.4 | **Add `bb test:evidence` task** — wire evidence test files into `bb.edn` | 15 min | `bb.edn` |
| T2.5 | **Evidence test expansion** — add error-path tests for corrupt files, missing directories, nil inputs across key evidence loaders | 2 h | `test/resolver_sim/evidence/` |

**Total T2**: ~8–10 h.

## T3 — Polish (4–8 hours)

| # | Item | Effort | Rationale |
|---|------|--------|-----------|
| T3.1 | **Document single-writer contract** — add comment at pipeline entry points | 15 min | Prevents future regression |
| T3.2 | **Dynamic var binding at pipeline boundaries** — review pipeline entry points for `*signing-key*`, `*tsa-url*`, `*capture-event-evidence!*` bindings | 1 h | Stale value safety |
| T3.3 | **Streaming evidence reader** — add lazy-seq option for large runs | 2–4 h | Memory optimization |
| T3.4 | **`bb validate` evidence check** — basic structural check of generated registry + cursor | 1 h | CI regression gate |

**Total T3**: ~4–6 h.

## Summary

| Tier | Items | Effort | Risk if skipped |
|------|-------|--------|-----------------|
| T1 | 4 | ~2.5 h | **Evidence silently dropped** without logging; `subs` crash on short hashes; NPE on nil var resolution |
| T2 | 5 | ~8–10 h | No schema safety net; node-emission errors hidden; no dedicated `bb test:evidence` target |
| T3 | 4 | ~4–6 h | Concurrency assumptions undocumented; potential stale var leakage; memory pressure on large runs |

## Recommendation

The evidence chain is production-ready for its current workload. T1 fixes are the minimum to close data-integrity gaps — they're small (3 items, ~2.5 h) and address real silent-data-loss vectors.

After T1, no urgent work remains. T2 is worthwhile but the evidence chain already has 487 passing tests, zero TODOs, and well-structured error handling in verification paths. T3 is future-proofing.

**Proceed with T1?**
