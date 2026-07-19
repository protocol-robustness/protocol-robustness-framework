# Benchmark Report Fields

A benchmark report is a plain map produced by `resolver-sim.benchmark.report/build-report`. It combines an evidence bundle (from `bb benchmark:run`), concept definitions, and scoring rules into a single data structure for rendering (Clerk notebook, EDN export, etc.).

This document describes every field and how an external reviewer should interpret it.

---

## Identity

```
:benchmark/id   <keyword>   — e.g. :benchmark/prf-protocol-robustness-v0
:benchmark/domain <keyword> — benchmark taxonomy area, e.g. :domain/protocol-value-conservation
:benchmark/domain-description <string or nil> — human-readable description from benchmarks/registry.edn
:benchmark/concepts <vector> — resolved benchmark concept entries with :concept/id and :concept/source
:benchmark/concept-summary <vector> — compact reviewer-facing concept entries
:benchmark/framework-concepts <vector> — framework-level concept IDs referenced by the benchmark concepts
:purpose         <string>    — what this benchmark evaluates
:scenario/suite  <keyword>   — suite keyword registered in scenario/suites.clj
:scenario/suite-description <string or nil> — human-readable scenario summary
```

`:scenario/suite` is an internal keyword. `:scenario/suite-description` is optional; when absent, the suite name may be opaque to external readers.
`:benchmark/domain` is the report's high-level grouping surface. It lets readers and downstream tools distinguish, for example, evidence-integrity benchmarks from protocol-value-conservation benchmarks even when both reference Sew scenarios.
`:benchmark/concepts` shows the declared concept IDs and their resolution status. `:concept/source` is one of `:benchmark-local`, `:global`, or `:missing`.
`:benchmark/concept-summary` is the short reviewer scan surface. Each entry includes `:concept/id`, `:concept/title`, `:concept/source`, and `:concept/framework-concepts`.
`:benchmark/framework-concepts` is the compact reviewer-facing bridge back to reusable framework concepts such as `:concept/conservation`.
When `build-report` is given an explicit `concepts-path`, that file is merged with the standard embedded benchmark concept set rather than replacing it. This keeps direct report construction aligned with `resolve-report`.

---

## Results

```
:total-scenarios   <int>   — number of scenarios executed
:passed-scenarios  <int>   — number of scenarios with outcome :pass
:all-pass?         <bool>  — true when total > 0 and total = passed
```

`:outcome :pass` means the scenario replayed without unexpected halts or guard rejections. It does **not** mean all claims were verified — see `:claim/status` below.

---

## Invariant checks

```
:invariant-summary
  {:per-invariant {<inv-kw> {:passed <int> :total <int>} …}
   :total-checks   <int>
   :passed-checks  <int>
   :all-pass?      <bool>}
```

Each invariant is checked against the terminal world state after replay (`sew-inv/check-all`). Passing invariants appear with `:result :pass`; only per-step failures from the replay metrics produce `:result :fail`.

- 64 canonical invariants per scenario (49 world-level + 15 transition-level)
- Transition invariants are not re-checked post-hoc — they are inferred as `:pass` when no per-step failure was recorded
- `:total-checks` = scenarios × 64 (e.g. 3 × 64 = 192)

---

## Evidence traceability

```
:evidence/path  <string>   — file path to the evidence bundle EDN
:evidence/hash  <string>   — bundle-level SHA-256 root hash
:environment    {:os-name <string> :os-version <string> …}
:reproduce      {:command <string>}
```

Each dimension in `:dimensions` carries its own per-scenario evidence root:

```
:scenario/evidence-root <hex-string>  — SHA-256 (:hash/intent :evidence-content)
                                         of {:events-processed :outcome :halt-reason}
```

This is a fingerprint of the scenario result, not a link to on-disk evidence chain artifacts. For byte-level evidence traceability, use `bb benchmark:reproduce <bundle-path>` against the bundle.

---

## Scoring classification

```
:scoring/classification
  {:classification-label <string>
   :scoring/summary      <string>
   :claim-maturity       <map>}  ;; present when claim results exist
```

The label is derived from the report builder's classifier and uses
clear action-oriented language:

| Scenario count | Label |
|----------------|-------|
| All scenarios pass | `"Scenario replay passed"` |
| All scenarios fail | `"Scenario replay failed"` |
| Mixed | `"Partial scenario replay"` |
| No scenarios executed | `"No scenarios executed"` |

When per-claim results are available, the label reflects claim status:

| Claim result | Label |
|--------------|-------|
| All claims pass | `"Mechanical claims passed"` |
| Any claim fails | `"Mechanical claims failed"` |
| Any claim inconclusive (no evaluator) | `"Semantic claims deferred"` |

When `:classifier` is `:pass-fail-critical` (severity-weighted):

| Condition | Label |
|-----------|-------|
| All claims pass | `"All claims pass — mechanical and invariant verification passed"` |
| Critical claim fails | `"Critical claim failed — semantic claim violation detected"` |
| Inconclusive claims exist | `"Semantic claims deferred — not evaluated"` |
| Non-critical failures only | `"Non-critical failures only"` |

`:scoring/summary` is the scoring rule's `:score-fn` description (a human-readable string like `"Ratio of passing scenarios to total scenarios"`). It is **not** an executable function.

### Claim Maturity Level

When claim results are present, the classification includes a
`:claim-maturity` map:

```clojure
:claim-maturity
{:label       "Level 1 — mechanical"
 :description "Required artifacts, hashes, evidence roots, and result fields
               exist and are internally consistent."
 :maturity/key :level-1}
```

Maturity levels:

| Level | Label | Meaning |
|-------|-------|---------|
| 1 | Mechanical | Structural checks on evidence bundle fields and hashes |
| 2 | Invariant-backed | Named post-hoc invariants proxying semantic properties |
| 3 | Semantic | Domain-specific reasoning — currently deferred, not evaluated |

---

## Conclusion

```
:conclusion
  {:outcome :pass | :fail | :incomplete
   :statement <scope-bounded summary>
   :limits [<scope limitation> ...]
   :evidence/hash <bundle SHA-256>
   :benchmark-run/hash <run SHA-256 or nil>}
```

`build-report` derives this field from scenario metrics and evaluated claim
results. `:pass` requires at least one executed scenario, all scenarios to
pass, and every evaluated claim to pass. A claim result of `:not-exercised`,
`:not-implemented`, or `:inconclusive` yields `:incomplete`; a scenario or
claim failure yields `:fail`. The conclusion is bounded to the declared
benchmark model, scenarios, runner, and policies.

## Claim status

```
:claim/status   <keyword>
:claim/maturity <keyword-or-nil>  ;; :level-1 | :level-2 | :level-3 | nil
```

`:claim/status` values:

| Value | Meaning |
|-------|---------|
| `:verified` | All declared claims in the manifest were evaluated and passed. |
| `:partial` | Some declared claims failed or returned `:inconclusive` (e.g. no evaluator registered, missing data). |
| `:declared-not-verified` | Claims are declared but no evaluator ran — no `:claim-results` in the evidence bundle. |
| `:none` | The benchmark pack declares no claims. |

`:claim/maturity` indicates the highest verification level present:

| Level | Name | Description |
|-------|------|-------------|
| `:level-1` | Mechanical | Required artifacts, hashes, evidence roots, and result fields exist and are internally consistent. These are the currently verified claims. |
| `:level-2` | Invariant-backed | Named post-hoc invariants passed for each scenario; claim is proxied by invariant results. Evaluators exist for Sew protocol claims. |
| `:level-3` | Semantic | Domain-specific reasoning over scenario results, world state, or evidence nodes. These claims are declared but not evaluated — their verification is deferred. |

When `:claim-results` are present, each entry follows the benchmark result spec:

```clojure
{:claim/id       <kw>      ;; e.g. :evidence-root-present
 :claim/outcome  <kw>      ;; :pass | :fail | :inconclusive
 :claim/severity <kw>      ;; :low | :medium | :high | :critical
 :claim/evidence [<kw> …]  ;; violation type references
 :claim/error    <string>  ;; present only when :outcome is :inconclusive}
```

---

## Verified now vs declared for future

| What | Verified now? | How |
|------|--------------|-----|
| Scenario outcome (`:pass`/`:fail`) | Yes | Replay completes or halts |
| Invariants (64 canonical) | Yes | Post-hoc `check-all` on terminal world |
| Evidence bundle hash | Yes | `hc/hash-with-intent :bundle-root` |
| Per-scenario evidence root | Yes | Hash of `{:events-processed :outcome :halt-reason}` |
| Claims (Level 1 mechanical) | Yes | Structural checks — evidence root exists, replay outcome present |
| Claims (Level 2+ semantic) | **No** | Declared but not evaluated; requires evaluator implementation per claim |

The `:benchmark/claims` field and `:concept/maps-to` references document what the benchmark *aims* to evaluate. Future versions may add claim evaluators that turn these declarations into verifiable checks.

---

## Example (protocol-robustness-v0, 3/3 pass)

```clojure
{:benchmark/id :benchmark/prf-protocol-robustness-v0
 :total-scenarios 3
 :passed-scenarios 3
 :all-pass? true
 :scoring/classification
 {:classification-label "Scenario replay passed"
  :scoring/summary "Ratio of passing scenarios to total scenarios"
  :claim-maturity {:label "Level 1 — mechanical"
                   :description "Required artifacts, hashes, evidence roots, and result fields…"
                   :maturity/key :level-1}}
 :claim/status :verified
 :claim/maturity :level-1
 :claim-results [{:claim/id :evidence-root-present :claim/outcome :pass …}
                 {:claim/id :replay-result-present  :claim/outcome :pass …} …]
 :invariant-summary {:total-checks 192 :passed-checks 192 :all-pass? true}
 :dimensions [{:scenario/id "malicious-resolver-verdict-v1"
               :outcome :pass
               :scenario/evidence-root "dd5ff512..."} …]}
```
