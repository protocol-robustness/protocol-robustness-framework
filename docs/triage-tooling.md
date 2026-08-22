# Triage Tooling: Provenance & Failure Attribution

Track which agent workspace (agent-a … agent-d, or any merge parent) caused a
test failure on `integration` — without re-running every agent directory by
hand.

Bookmarked as `triage/tooling`. Components:

| Piece | Where | What it does |
|---|---|---|
| Provenance stamp | `scripts/test.sh` → `.provenance.json` | Every gate run self-describes the revision(s) under test |
| Static attribution | `bb triage:attribute` | Maps failing targets to contributing parents via file evidence |
| Dynamic disambiguation | `bb triage:attribute --rerun-failed` | Replays only failed targets against baseline / tips / pairwise merges |
| Pre-merge guard | `bb triage:guard` | Rejects empty, undescribed, conflicted or untracked agent tips |

---

## 1. Provenance stamping

Every invocation of `./scripts/test.sh <target>` writes
`$ARTIFACT_DIR/.provenance.json` (schema `test-provenance.v2`) before running
anything:

```jsonc
{
  "schema_version": "test-provenance.v2",
  "vcs": "jj",
  "run_id": "20260822-091256",
  "created_at": "…",
  "mode": "coverage",              // $MODE passed to test.sh
  "args": [],                      // extra CLI args of the run
  "targets": [],                   // PRF_TARGETS env when set (comma-separated)
  "working_copy": {                // @ — always the tested tree, even if empty
    "change_id": "nmynqllqykvy", "commit_id": "…",
    "empty": true, "conflict": false,
    "bookmarks": [], "description": ""
  },
  "tested_revision": { /* same shape; == working_copy */ },
  "parents": [                     // PRIMARY attribution candidates:
    { "change_id": "…", "bookmarks": ["triage/tooling"], … },   // all direct
    { "change_id": "…", "bookmarks": ["integration","main"], … } // parents, incl. merges
  ],
  "context": [ /* bounded ancestor sample from all lineages — supplementary only */ ],
  "warning": "…"                   // present only when jj queries failed,
                                   // e.g. stale working copy
}
```

Properties:

- Merge-aware: uses nested `parents()` revsets, never `@-` chains, so every
  contributor of a merge working copy is captured.
- The tested revision is **always** `@` itself: an empty working-copy commit
  still has a full merged tree.
- Bounded cost (`timeout 60` overall, 15s per query); failures degrade to the
  `"warning"` field and never break the gate.

## 2. Static attribution

```bash
bb triage:attribute                       # newest results/test-artifacts-*
bb triage:attribute <artifact-dir>        # specific run
bb triage:attribute <dir> --at REV       # override 'integrated tree' for divergence
bb triage:attribute <dir> --baseline REV # override contribution baseline
bb triage:attribute <dir> --json         # machine-readable report
```

How it works:

1. Reads failing targets from `test-summary.json`, extracts failure tokens
   from each target log (test names, files, namespaces, `[:fail]` suite ids,
   `:scenario-id`s).
2. Candidate discovery: `.provenance.json` parents if present, otherwise an
   `agent-*` bookmark fallback (works for runs made before this tooling
   existed).
3. **Contributions are measured against a common baseline**, not each
   candidate's immediate parent:
   `contribution = candidate tree − fork_point(candidates)` (override with
   `--baseline`). This catches work hidden in ancestor commits under empty
   bookmark tips. The chosen baseline is printed in the report.
4. Each failing target × candidate gets evidence classified into tiers:

| Tier | Meaning |
|---|---|
| HIGH | failing test/source path directly changed by that candidate |
| MEDIUM | failing namespace / scenario id maps into directories touched |
| LOW | weak basename overlap only (auto-demoted ambiguous basenames) |
| NONE | no candidate-specific evidence |

5. Verdicts: `LIKELY CULPRIT (HIGH)`, `MULTIPLE PLAUSIBLE CONTRIBUTORS`,
   `PLAUSIBLE CONTRIBUTOR (MEDIUM)`, `UNATTRIBUTED`. Evidence lines are printed
   so verdicts are auditable.

### Divergence report

For every file touched by ≥2 candidates, blob hashes are compared against the
tested tree:

- `MATCHES-PARENT(S)` — merged copy identical to at least one contributor.
- `COMPOSITE-MERGE` — matches nobody. **Not inherently bad**: it also covers
  legitimate merges combining non-overlapping edits from several parents.
- `COMPOSITE-MERGE-CONFLICT-MARKERS` — committed file still contains
  `<<<<<<<`; investigate immediately.
- `ABSENT-IN-TREE` — deleted/renamed away in the integrated tree.

Per-parent ops (`M`/`A`/`D`/`ABSENT`) are listed per row.

## 3. Rerun-failed (dynamic disambiguation)

Static evidence cannot attribute behavioural failures whose files were not
edited. That is what this mode is for:

```bash
bb triage:attribute results/test-artifacts-XXXX --rerun-failed
bb triage:attribute <dir> --rerun-failed --only unit,suites   # subset only
bb triage:attribute <dir> --rerun-failed --max-pairs 15      # cap stage 2
```

Stages:

- **Stage 0** — failed targets on the resolved baseline. Baseline failures mean
  pre-existing/environmental problems; attribution is suppressed rather than
  blaming agents.
- **Stage 1** — failed targets on each bare parent tip.
- **Stage 2** — pairwise merges (`jj new P1 P2`) of the parents.

Each cell runs inside a throwaway jj workspace under `/tmp/opencode`
(sanitised + randomised name), with artifacts redirected to
`$WS/results/artifacts`, skipped-and-flagged if the temp merge has unresolved
conflicts, and cleaned up via `finally` **and** a JVM shutdown hook
(`jj workspace forget` + directory removal guarded to `/tmp/opencode`).

Verdict taxonomy per target:

```
BASELINE_FAILURE            preexisting/environmental — attribution suppressed
SINGLE_PARENT_FAILURE       reproduced on tip(s): agent-c
PAIRWISE_INTERACTION        reproduced on merge(s): agent-b+agent-c
HIGHER_ORDER_OR_UNREPRODUCED  everything tested passes
INFRASTRUCTURE_FAILURE      exit=127 or conflict-skip rows
```

Caveats printed with every verdict: pairwise testing cannot prove absence of
three-way interactions; stage 2 is truncated beyond `--max-pairs`.

## 4. Pre-merge guard

```bash
bb triage:guard                 # before merging agent tips into integration
bb triage:guard --baseline REV  # make the descent check strict/error
```

Hard errors (exit 1):

- bookmark does not resolve uniquely
- tip is `EMPTY`
- tip has `NO-DESCRIPTION`
- tip has unresolved `CONFLICTS`
- sibling workspace (dir named like the bookmark) has untracked files under
  `src/`, `test/`, `protocols_src/`

Advisory warning (does not fail): tip does not descend from
`integration@origin` — normal for merge-based agent branches. Pass
`--baseline` to turn this into a hard error when you actually require
rebased-on-latest tips.

Example output:

```
WARN: agent-b (4a01eb4a38c5): does not descend from 'integration@origin' (normal …)
triage:guard FAILED — reject these tips before merging:
   agent-a (7b356c453955): EMPTY;DESCRIBED;CLEAN
```

## 5. Recommended workflow

1. Agent finishes in its workspace → describe the tip, keep it non-empty
   (`jj describe` / squash work up), no stray untracked impl files.
2. Before merging: `bb triage:guard`.
3. Merge into integration; run the gate (`./scripts/test.sh all` or targeted).
4. On failures:
   - `bb triage:attribute` first — instant file-level suspects +
     COMPOSITE-MERGE flags.
   - Ambiguous or unattributed? `--rerun-failed [--only <fast targets>]`.
5. Interpretation cheat-sheet:
   - single tip reproduces → that agent owns the fix;
   - only pairs reproduce → interaction between those two agents;
   - nothing reproduces but full merge fails → higher-order interaction
     (add triples manually or bisect the merge);
   - baseline reproduces → not the agents' fault; check environment/seeds.

## 6. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `.provenance.json` has `"warning"` and empty parents | Working copy stale (history rewritten underneath, e.g. tooling updates). Run `jj workspace update-stale` in that workspace. |
| `candidate source : agent-* bookmark fallback` | Artifact predates provenance stamping; attribution still works via bookmarks but divergence needs `--at <tested-rev>`. |
| Guard warns about descent for every agent | Expected unless you enforce rebased tips; use `--baseline` for strict mode. |
| Stage-2 cell shows `SKIPPED (unresolved conflicts)` | The pairwise auto-merge conflicts; resolve manually in a scratch workspace to test that pair. |

## 7. Case study — 2026-08-21 integration failure

Four failing targets after a four-agent merge:

- Static pass flagged `src/resolver_sim/hash/canonical.clj` as
  `COMPOSITE-MERGE` (matches no parent — three agents had edited it).
- `--rerun-failed`: generators/suites/reference-validation reproduced on
  **agent-c's tip alone**; unit attributed to agent-c with HIGH confidence
  (`sew.clj`, `chain.clj`). agent-b also broke unit independently.
- Post-mortem lesson baked into the tooling: agent-a's bookmark pointed at an
  *empty* tip hiding real composition changes in ancestors — invisible to
  immediate-parent diffs, caught by baseline-relative contributions and now
  blocked by the guard.

## 8. Files & internals

- `scripts/test.sh` — `write_provenance()` (python heredoc; JSON built with
  `json.dump` so descriptions/bookmarks are escaped safely).
- `scripts/attribute_failures.clj` — babashka script behind both bb tasks;
  ~600 lines; no deps outside bb bundled libs.
- `bb.edn` — `triage:attribute`, `triage:guard`.
- jj templates used: `change_id.short()`, `commit_id.short()`,
  `if(empty)`, `if(conflict)`, `bookmarks.map(...)`, `description.first_line()`
  (note: this jj build has no `normal_short()`).
