# Follow-up task: golden-fixture regeneration drift (separate scope)

Discovered during the cancellation full-gate runs on 2026-08-24; explicitly
OUT OF SCOPE for cancellation work. Restoring the two fixtures in the
cancellation documentation change was correct.

## Symptom

After `PARALLEL_TARGETS=1 ./scripts/test.sh all`, two tracked golden reports
regenerated with a changed classification field:

    data/fixtures/golden/s46a-settlement-before-escalation-window-edge.report.edn
    data/fixtures/golden/s48-max-escalation-exact-boundary.report.edn

Diff (only line): `:suite-id :suites/dr3-critical` → `:suites/equivalence-race-pairs`.
`trace-id` and `final-state-hash` unchanged — scenario content identical;
only suite attribution moved. Both files were restored to their committed
form so unrelated drift would not ride along with cancellation changes.

## Why it must be investigated before accepting

A full gate that rewrites tracked goldens differently from their committed
form means one of:

1. the fixtures are stale relative to current deterministic classification
   rules (benign, needs reviewed refresh), or
2. the generator is non-deterministic or depends on uncommitted /
   environmental state (defect).

## Investigation steps

1. Check out the current revision clean.
2. Run the fixture-suite generator twice from the SAME revision.
3. Compare outputs byte-for-byte between the two runs:
   - identical-to-each-other but ≠ committed → case 1 (stale goldens);
   - differing run-to-run → case 2 (nondeterminism / environmental input).
4. Trace why `s46a`/`s48` classify as `:suites/equivalence-race-pairs`
   instead of `:suites/dr3-critical` (scenario registry entry vs classifier
   rule; check `data/fixtures/suites/manifest.edn` and the classification
   code path).
5. If the new classification is CORRECT: update both fixtures in a dedicated,
   reviewed change titled separately from any feature work.
6. If INCORRECT: fix the generator/classifier.

## Preventive hardening to add afterwards

Add a regeneration-idempotence check to the gate so a passing full gate can
never silently leave tracked drift: after generation in check mode, fail if
any tracked file under `data/fixtures/golden/` differs from its committed
form (clean-tree invariant), with an explicit escape hatch documented for
intentional refresh runs (`bb regenerate-goldens` style task already exists
for suites — extend the same pattern).
