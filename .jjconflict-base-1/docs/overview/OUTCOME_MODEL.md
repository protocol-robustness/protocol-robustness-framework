# Outcome Model (Canonical, Cross-Protocol)

Status: Draft v0.1 (backward-compatible overlay)

## Goal

Define a protocol-agnostic outcome contract that separates:

1. **Execution result** (what happened)
2. **Interpretation** (how to classify it)
3. **Story packaging** (how to render/share it)

This allows SPEDS/story tooling to generalize beyond Sew-specific scenario sets.

## Single-declaration pipeline

Semantic definitions are centralized and reused across runtime and narrative layers:

- Registry: `src/resolver_sim/definitions/registry.clj`
- Replay/schema assumptions: `src/resolver_sim/scenario/schema_profile.clj`
- Outcome interpretation: `src/resolver_sim/scenario/outcome_semantics.clj`

Pipeline intent:

`definitions -> replay validation/report labels -> evidence exports -> Clerk/SPEDS framing`

When adding or changing semantics (purposes, statuses, severity meaning, story-family mappings),
update the registry first and keep consumers as lookups, not duplicated conditionals.

---

## Canonical Shape

```edn
{:outcome-model/version "v0.1"

 :outcome
 {:class :research-finding              ; :research-finding | :regression | :robustness-confirmation | :operational-signal | :inconclusive
  :status :assumption-falsified         ; enum scoped by class
  :severity :high                       ; :low | :medium | :high | :critical
  :confidence {:level :medium           ; :low | :medium | :high
               :basis :single-run-artifact-derived
               :rationale "..."}

  :execution
  {:result :pass                        ; replay outcome surface
   :halt-reason nil
   :scenario-id "scenarios/S26_forking-strategist-l1-reversal"
   :scenario-purpose :theory-falsification}

  :evidence
  {:claims [{:claim-id "replay-alignment"
             :value "97.5%"
             :source-artifact "summary"
             :source-path [:summary :replay_match_pct]}]
   :provenance {:run-id "RUN-2026-05-25"
                :git-sha "e533487"
                :trace-digest nil
                :trace-digest-status :missing}}

  :comparison
  {:comparator-id "scenarios/S01_baseline-happy-path"
   :strategy :nearest-baseline-by-id    ; pluggable strategy enum
   :deltas {:threat-tag-count-delta 3
            :threat-tag-overlap-count 1
            :purpose-delta {:scenario :theory-falsification
                            :baseline :baseline}}
   :narrative "Compared against baseline ..."}

  :actionability
  {:owner :triage
   :release-gate-impact :review-required ; :none | :review-required | :blocker
   :next-step "Classify as research finding vs regression before publication."}

  :visual
  {:blocks [{:block :headline :text "..."}
            {:block :what-happened :text "..."}
            {:block :why-it-matters :text "..."}
            {:block :action :text "..."}]}}
}
```

---

## Mapping from Current Fields (Backward-Compatible)

Current `findings` fields remain valid. The canonical model is an additive overlay.

- `:kind`, `:status_kind`, `:severity`, `:confidence`
  -> `:outcome/:class`, `:outcome/:status`, `:outcome/:severity`, `:outcome/:confidence`

- `:classification` (story classification)
  -> `:outcome/:class + :outcome/:status + :outcome/:confidence/:rationale`

- `:provenance`, `:evidence_refs`
  -> `:outcome/:evidence`

- `:story_artifact_spec/:baseline_comparison`
  -> `:outcome/:comparison`

- `:story_artifact_spec/:visual_blocks`
  -> `:outcome/:visual/:blocks`

---

## Compatibility Plan

### Phase A (now)

- Keep existing keys unchanged.
- Add `:outcome` block in generated findings bundle.
- Story renderers prefer `:outcome` when present, fallback to legacy keys.

### Phase B

- Move classification/comparator logic into reusable outcome namespace.
- Introduce validation checks for required evidence/provenance per class.

### Phase C

- Enforce canonical `:outcome` as renderer contract.
- Retain legacy keys for one deprecation window.

---

## Generalization Rules

1. **Do not infer regression from negative result alone.**
   Use scenario purpose/classification rules and comparator context.

2. **Confidence must degrade when provenance/evidence are incomplete.**

3. **Comparator strategy must be explicit.**
   Avoid silent nearest-neighbor assumptions in public reporting.

4. **Story blocks are presentation metadata, not evidence.**
   Evidence must remain traceable to source artifacts.

---

## Minimal Acceptance Criteria

- Every generated finding includes `:outcome/:class`, `:outcome/:status`, `:outcome/:confidence`.
- Every `:research-finding` or `:regression` includes comparator metadata.
- Every published visual claim maps to an evidence claim path.

---

## Comparator Shadow Rollout (Operational)

Use SPEDS issue tooling to evaluate comparator strategies side-by-side before
changing defaults.

### Programmatic entrypoints

- `resolver-sim.notebook-support.speds.issues/generate-comparator-shadow-report`
- `resolver-sim.notebook-support.speds.issues/save-comparator-shadow-report!`
- `resolver-sim.notebook-support.speds.issues/load-comparator-shadow-report`

### Example

```clj
(require '[resolver-sim.notebook-support.speds.data :as data]
         '[resolver-sim.notebook-support.speds.issues :as issues])

(def artifacts (data/load-run-artifacts))

;; In-memory report
(issues/generate-comparator-shadow-report
 artifacts
 {:strategies [:nearest-baseline-by-id :matched-by-purpose :matched-by-tags]
  :enabled? true})

;; Persist to results/test-artifacts/comparator-shadow.json
(issues/save-comparator-shadow-report!
 artifacts
 {:strategies [:nearest-baseline-by-id :matched-by-purpose :matched-by-tags]
  :enabled? true})
```

### Recommended rollout routine

1. Keep production default at `:nearest-baseline-by-id`.
2. Run shadow report on representative suites.
3. Compare `:finding-count` and `:issue-count` drift by strategy.
4. Promote a new strategy only after review sign-off.
