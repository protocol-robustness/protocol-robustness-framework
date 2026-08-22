# Triage Tooling: Provenance & Failure Attribution

Attribute test failures on an integration branch to the candidate change-trees
(merge parents) that most likely contributed them — without re-running every
workspace by hand.

Bookmarked as `triage/tooling`. Components:

| Piece | Where | What it does |
|---|---|---|
| Provenance stamp | `scripts/test.sh` → `.provenance.json` | Every gate run self-describes the revision(s) under test |
| Static attribution | `bb triage:attribute` | Maps failing targets to contributing parents via file evidence |
| Dynamic disambiguation | `bb triage:attribute --rerun-failed` | Replays failed targets against baseline / tips / pairwise merges |
| Pre-merge guard | `bb triage:guard` | Rejects undescribed/conflicted tips; policy flags for the rest |

---

## Scope and guarantees

- This is a **jj-specific implementation** of a VCS-neutral design.
- Attribution statements apply to **recorded candidate trees** — never to
  authorship, personal blame, or who committed what. A branch may contain
  shared commits, inherited work or generated changes.
- The tool can establish: (a) a candidate changed files associated with a
  failure; (b) a failure reproduces in a candidate's tree; (c) a failure
  emerges only in a combination of trees. It does **not** prove causality
  beyond that; absence of evidence is not evidence of innocence.
- Pairwise testing cannot exclude higher-order (three-way+) interactions.

## Repository configuration

Defaults live in code; override per repository with a `.triage.edn` at the
repo root:

```clojure
{:bookmark-glob "agent-*"          ; which bookmarks are candidates
 :impl-roots ["src" "test" "protocols_src"]  ; guard untracked-file roots
 :tmp-root "/tmp/opencode"         ; throwaway workspace root
 :baseline "integration@origin"}   ; fallback baseline when fork_point fails
```

CLI flags override config values. `TRIAGE_TMP_ROOT` overrides the temp root.

## Failure identity and matching

Current matching is **target-level**: a cell "reproduces" when its exit code
is non-zero (`--repetitions N` reports matched k/N runs). Per-test-instance
fingerprints (namespace+var+exception+location hash) distinguishing
MATCHED_FAILURE from DIFFERENT_FAILURE are a **deferred roadmap item** — until
then, treat single-target reproduction as strong-but-not-exact evidence.

## Candidate and baseline semantics

Candidates come from (in priority order): explicit `--candidate label=REV`
flags → `.provenance.json` parents of the tested revision → bookmark glob
fallback. Commit IDs are authoritative; labels/bookmarks are display
metadata.

Contributions are measured against a common baseline:
`candidate tree − fork_point(candidates)`, overridable with `--baseline`,
with configured fallback. This catches work hidden under empty bookmark tips.
Known limitations: multiple disjoint common ancestors, unrelated histories,
and nested rolling merges where one parent aggregates several contributors
are not yet decomposed (an explicit contributor manifest at integration time
is planned).

## Security model

`--rerun-failed` executes code from historical revisions inside throwaway jj
workspaces. Treat it as running untrusted code:

- do not expose production credentials to the rerun environment;
- prefer an environment-variable allowlist;
- run targets sequentially when they share services/ports/databases;
- keep the temp root local (`TRIAGE_TMP_ROOT`), cleanup is guarded to it;
- opt in deliberately in CI or multi-tenant settings.

## Provenance stamp

Written by every `./scripts/test.sh <target>` run into
`$ARTIFACT_DIR/.provenance.json` (schema `test-provenance.v2`):

```jsonc
{
  "schema_version": "test-provenance.v2",
  "vcs": "jj",
  "run_id": "20260822-191050",           // filename-compatible id
  "run_uid": "20260822-191050-bbc13f0e", // collision-resistant identity
  "mode": "coverage", "args": [], "targets": [],
  "execution": { "hostname": "…", "cwd": "…",
                 "command": ["./scripts/test.sh","coverage"],
                 "jj_version": ["jj 0.42.0-…"] },
  "working_copy":  { /* @ — the tested tree, even when commit is empty */ },
  "tested_revision": { /* == working_copy */ },
  "parents": [ /* ALL direct parents of the merge — primary candidates */ ],
  "context": [ /* bounded ancestor sample from every lineage */ ],
  "warning": "…"   // present iff jj queries failed (e.g. stale working copy)
}
```

Merge-aware (`parents()` revsets, not `@-` chains), time-bounded
(60s overall), best-effort — never fails the gate. Full execution-environment
capture (dependency hashes, seeds, two-phase running/finalized stamping) is
deferred.

## Static attribution

```bash
bb triage:attribute                       # newest results/test-artifacts-*
bb triage:attribute <artifact-dir>        # specific run
bb triage:attribute <dir> --at REV        # override integrated tree
bb triage:attribute <dir> --baseline REV  # override contribution baseline
bb triage:attribute --candidate api=REV --candidate storage=REV  # explicit
bb triage:attribute <dir> --json
```

Pipeline: failing targets from `test-summary.json` → failure tokens from logs
→ per-candidate contribution diffs vs baseline → tiered matching.

Evidence tiers: HIGH (failing path directly changed), MEDIUM (namespace/
scenario maps into touched dirs), LOW (weak basename overlap, auto-demoted),
NONE. Verdicts: `HIGH-CONFIDENCE CONTRIBUTOR`, `MULTIPLE PLAUSIBLE
CONTRIBUTORS`, `PLAUSIBLE CONTRIBUTOR (MEDIUM)`, `UNATTRIBUTED`. Evidence
lines are printed so every verdict is auditable.

### Divergence report

Per multi-touched file, blob comparison vs the tested tree:

- `MATCHES-PARENT(S)` — identical to ≥1 contributor.
- `COMPOSITE-MERGE` — matches nobody. Not inherently bad: legitimate merges
  combining non-overlapping edits also look like this.
- `COMPOSITE-MERGE-CONFLICT-MARKERS` — committed `<<<<<<<`; investigate now.
- `ABSENT-IN-TREE` — deleted/renamed away in the integration.

## Rerun-failed

```bash
bb triage:attribute <dir> --rerun-failed [--only unit,suites]
                         [--max-pairs 15] [--repetitions 3] [--require-reproductions 2]
```

Stages: **0** baseline control · **1** each bare tip · **2** pairwise merges.
Cells run in sanitized/randomized throwaway workspaces (root configurable,
deletion guarded), artifacts redirected, conflict-marked cells skipped.

Verdict taxonomy (applies to recorded candidate trees):

```
BASELINE_FAILURE                        preexisting/environmental — suppressed
SINGLE_PARENT_FAILURE                   REPRODUCED WITH: <tip>
PAIRWISE_INTERACTION                    REPRODUCED ON MERGE(S): <a>+<b>
MERGE_CONFLICT_UNTESTED                 untested combination (not infra failure)
INFRASTRUCTURE_FAILURE                  exit=127 etc.
INTEGRATION_RESOLUTION_SUSPECT          all cells pass BUT COMPOSITE-MERGE files exist
                                        → suspect the merge resolution itself
HIGHER_ORDER_INTERACTION /
UNREPRODUCED_OR_NONDETERMINISTIC        everything tested passes
FLAKY_REPRODUCTION                      matched k/N across repetitions (0<k<N)
```

With `--repetitions`, a cell counts as reproducing only after
`--require-reproductions` failing runs; partial match rates print as
`FLAKY_REPRODUCTION`.

## Pre-merge guard

```bash
bb triage:guard                                # warnings don't block
bb triage:guard --require-nonempty-tip         # policy flag
bb triage:guard --bookmark-glob 'feature-*' --impl-roots src,test
bb triage:guard --baseline REV                 # strict descent check
```

Hard errors: undescribed tip · unresolved conflicts · untracked impl-root
files · unresolvable bookmark · (strict mode only) descent/contribution
violations.

Warnings (non-blocking): empty tip commit — *legitimate in jj when ancestry
carries the work*; no tree delta vs baseline; descent mismatch on
merge-based branches.

Exit codes: `0` guard passed / attribution completed · `1` problems found ·
assertion failures surface as nonzero invalid-input errors.

## Recommended workflow

1. Agent finishes → describe tip, no stray untracked impl files.
2. `bb triage:guard`.
3. Merge; run the gate; provenance lands automatically.
4. On failures: `bb triage:attribute` first, then `--rerun-failed [--only …]`
   if ambiguous or unattributed.

## Troubleshooting

| Symptom | Fix |
|---|---|
| `.provenance.json` has `"warning"` | Stale working copy — run `jj workspace update-stale`. |
| `bookmark fallback` source tag on old artifacts | Expected pre-tooling; pass `--at <tested-rev>` for divergence. |
| Descent warnings for every agent | Normal; use `--baseline` to enforce. |
| Stage-2 `MERGE_CONFLICT_UNTESTED` | Resolve that pair manually in a scratch workspace to test it. |

## Case study A — four-agent integration (real)

2026-08-21: four agents merged; unit/generators/suites/reference-validation
failed. Static pass flagged `hash/canonical.clj` as COMPOSITE-MERGE (three
agents edited it). Rerun attributed generators/suites/reference-validation to
agent-c's tip alone and unit to agent-c (HIGH) with agent-b independently
breaking unit. Lesson baked in: agent-a's *empty* tip hid real composition
changes in ancestors — invisible to immediate-parent diffs, caught by
baseline-relative contributions.

## Case study B — neutral example

Three feature lines (`parser`, `storage`, `api`) merge into `main`; the gate
fails `suites` and `reference-validation`.

```bash
bb triage:attribute results/test-artifacts-XXXX
# suites      => AMBIGUOUS between parser, storage (both touch scenario dirs)
bb triage:attribute results/test-artifacts-XXXX --rerun-failed --only suites
# stage1: all tips PASS          stage2: parser+storage FAILS
# => PAIRWISE_INTERACTION — REPRODUCED ON MERGE(S): parser+storage
```

Same flow works for any bookmark family via `--bookmark-glob 'feature-*'` or
explicit `--candidate` flags — nothing is hardcoded to "agents".

## Deferred / roadmap

- Per-test-instance fingerprints (MATCHED_FAILURE vs DIFFERENT_FAILURE,
  flaky/timeout classification) — highest-priority next step.
- INTEGRATION_RESOLUTION_DELTA: construct parents' auto-merge and diff vs
  tested tree (today only the heuristic hint exists).
- Contributor manifest recorded at integration time (nested-merge
  decomposition).
- Pluggable log extractors (JUnit XML, user regex rules); raw vs normalized
  evidence split in JSON output.
- Two-phase provenance stamping (running → finalized) with full execution
  capture (lockfile hashes, seeds, OS/arch).
- Fixture-based golden test suite for the tooling itself.
- Env allowlist/process-group timeouts/network isolation as enforced options.

## Files & internals

- `scripts/test.sh` — `write_provenance()` (python heredoc; `json.dump`
  escaping).
- `scripts/attribute_failures.clj` — babashka script behind both tasks.
- `bb.edn` — `triage:attribute`, `triage:guard` (args forwarded).
- jj notes: this build lacks `normal_short()`; templates must end with `"\n"`
  or multi-commit output concatenates.
