# Static Analysis for Clojure

## Current state

**No automated static analysis tooling is configured for Clojure in this
repository.** The Codacy platform has no Clojure analysis tool enabled,
and no alternative scanner (e.g., clj-kondo, eastwood, cloverage) is
wired into CI.

## What is used instead

1. **REPL-based manual review** — every PR is reviewed by a human with
   REPL access to evaluate complex state transitions.

2. **Invariant checking** — 33 single-world + 14 cross-world invariants
   are checked on every replay step. This catches many classes of bugs
   that static analysis would find (type mismatches, invalid state
   transitions, accounting inconsistencies).

3. **Deterministic replay** — `replay-idempotent-same-trace?` verifies
   that re-running the same scenario produces identical output. This
   catches non-determinism bugs.

4. **Suite runner** — the fixture suite runner replays 100+ scenarios
   with golden report comparison.

## Risk

- **No style enforcement** — naming conventions, form layout, and
  namespace ordering are manual.
- **No dead code detection** — unused functions or bindings are not
  flagged.
- **No type checking** — Clojure's dynamic typing means incorrect
  function arity or nil dereferences are caught at runtime only.
- **No security scan** — dependency vulnerabilities are not
  automatically checked.

## Recommendation (deferred)

If automated Clojure analysis is desired, the following tools could be
added to CI:

| Tool | Purpose | Integration |
|------|---------|-------------|
| clj-kondo | Static linting (unused vars, type hints, arity) | `bb lint` task |
| cloverage | Code coverage | `bb test --coverage` |
| nvd-clojure | Dependency vulnerability scanning | `bb nvd-check` |

These are deferred because the current manual-review + invariant-checking
regime has not yet missed a production bug that static analysis would have
caught. Re-evaluate when the codebase exceeds 50K lines of Clojure.
