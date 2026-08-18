# `run-and-report` Provenance Audit — Final Report

## 1. Verdict

**MATERIAL GAPS**

Two distinct `run-and-report` functions exist with different provenance models. The benchmark CLI path (`benchmark.cli/run-and-report`) emits no creation-provenance field at all. The scenario-runner path (`scenario_runner/run-and-report`) always defaults `:creation/provenance` to `:in-band` because no caller threads it through. The standalone `project-creation-provenance` hash intent exists but is dead code — classified `:not-applicable`, never wired into any emission or verification.

The most severe finding was **P1.3**: the pro-rata evidence verifier could not reproduce the hash of an `:out-of-band` profile because it reconstructed with `:in-band` defaults.

## 5. Status Tracking

| Item | Status | Notes |
|------|--------|-------|
| Pro-rata profile provenance reconstruction | **CLOSED** | Verifier now re-extracts stored `:creation/provenance` and passes it to the rebuilder. |
| Out-of-band profile verification | **SUPPORTED** | `verify-pro-rata-execution-evidence[-v2]` correctly reproduces `:out-of-band` profile hashes after the fix. Regression tests added. |
| Tampered profile provenance | **REJECTED** | Mutating `:evidence-profile/creation :provenance` breaks the `:evidence-profile/hash` recomputation → verification reports `:evidence-profile/hash` mismatch. |
| Builder default semantics | **UNDER CALL-SITE AUDIT** | The `(or provenance :in-band)` default at creation boundary is not disproven merely by the former verifier bug. Each call-site that builds evidence for an existing/out-of-band artifact must be audited to determine whether the default is at a genuine creation boundary or a reconstruction boundary. |

## 2. Provenance Flow

### Benchmark CLI path

```
commands/run_benchmark.clj:invoke!
  → benchmark.cli/run-and-report (cli.clj:185)
    → run-benchmark (runner.clj:1128)
      → execute-plan-bounded!
        parallelism/chunk-size/claimant parallelism
        consumed as runtime args, NEVER stored in evidence
      → evidence map (runner.clj:1289–1310)
        NO :creation/provenance field
      → integrity/hashable-evidence (integrity.clj:76)
        excludes :repo, :timestamp, :evidence/hash,
        :benchmark/artifact-index, :run/manifest/:manifest/at
        → hc/hash-with-intent :bundle-root → :evidence/hash
      → write-evidence (runner.clj:1407) — EDN, sorted keys, atomic write
      → [optional] signing → :evidence/signature
    → dispatch-run-and-report (cli.clj:236)
      → write-evidence → record-history → interactive-ux
  ── Outer envelope commitments (all exclude creation/provenance) ──
  → complete-canonical-benchmark-run-root! (clj:272)
    :benchmark-completion.v1 binds:
      bundle_root_hash, artifact_set_root, closure_commitment,
      finalization_ref/sha256, run_package_index_ref/sha256,
      input_set_root, artifact_registry_ref/sha256
  → write-canonical-assurance! (clj:143)
    :canonical-integrity.v1 binds scope, checks, limitations
  → write-verdict-policy! (clj:319)
    :verdict-policy.v1 binds distribution-provenance,
      evaluator-implementation (source-tree-hash)
  → write-package-index! (clj:345)
    :run-package-index.v1 binds all artifact refs+sha256

  ── Consumers ──
  → verify-bundle-hash / verify-evidence-bundle! (integrity.clj:147,187)
  → verify-benchmark (commands/verify_benchmark.clj)
  → report.clj:build-report → :claim/status
```

### Scenario-runner path

```
scenario_orchestration.clj, run_simulation.clj,
minimal_runner.clj, core.clj
  → scenario_runner/run-and-report (scenario_runner.clj:1133)
    → determine-canonicality (clj:789)
      Checks: :parallel? opts, source/dirty?, :mode,
      scenario-filter, pinned runner, quorum
    → source-provenance (forensic.provenance/source-provenance)
      Returns VCS env vars: source/hash, source/commit,
      source/dirty? — NOT parallelism, NOT creation provenance
    → build-execution-node-spec (clj:1023)
      Spec has :inputs (dispatch + runner-selection + source-provenance),
      :outputs-fn, :status-fn, :failure-details-fn
      NO :creation-provenance key
    → with-execution-node+ (evidence/node.clj:1405)
      Destructures [execution-id policy-id inputs parent-hashes
      bootstrap-roots runner status-fn outputs-fn
      failure-details-fn extensions-fn]
      NO :creation-provenance threading
    → emit-execution-node! (evidence/node.clj:386)
      Defaults creation-provenance to :in-band (line 84)
      Stores at [:execution :creation/provenance]
    → execute-dispatch! (clj:957)
      → br/build-bundle-root (bundle_root.clj:225)
        select-keys request: :runner/backend,
          :runner-selection, :suite/key, :protocol/default-id,
          :evidence/profile, :output/profile
        Parallelism NOT selected
    → build-enriched-bundle-root (clj:1120)
      Merges source-provenance + execution/node-hash +
      execution/content-hash + :dag/root-node-hash (redundant alias)
    → write-result-json (clj:615) — JSON, atomic, preserve-ns-key
    → write-run-links! (clj:1084) — run-links/<id>.edn
      :type :forensic-run, :canonical?, :generated-at,
      :tsa/configured?, :signature/configured?

  ── Outer envelope commitments ──
  → default-finalize-run-evidence! (clj:102)
    → runner-finalization/build (runner_finalization.clj)
      :runner-id from runner-selection, :runtime-kind :runner-local
      :source/hash from bundle-root :run/environment
  → default-write-canonical-assurance! (clj:400)
    :canonical-integrity.v1 — scope, checks, limitations
  → default-write-verdict-policy! (clj:448)
    :verdict-policy.v1 — distribution-provenance, evaluator-implementation
  → default-complete! (clj:542)
    :run-completion.v1 — bundle_root_hash, indexes, sha256 refs

  ── Consumers ──
  → node-summary-entry (evidence/node.clj:1099)
    Reads [:execution :creation/provenance] → :creation-provenance
  → validate-node (evidence/node.clj:799)
    Checks hash integrity + shape; does NOT call
    validate-creation-provenance
  → validate-creation-provenance (evidence/node.clj:788)
    Enum check only: :in-band / :out-of-band
    Docstring: "mutation between them is undetectable
    without a separate commitment over provenance"
```

## 3. Provenance Matrix

| Field | Meaning | Producer | Canonical? | Committed? | Persisted? | Validated? | Read-back? | Consumer(s) |
|-------|---------|----------|-----------|-----------|-----------|-----------|-----------|------------|
| `:creation/provenance` (evidence node) | `:in-band` \| `:out-of-band` | `emit-execution-node!` (evidence/node.clj:84, defaults `:in-band`) | **No** (excluded from `project-evidence-node`) | **No** (`project-creation-provenance` is dead code) | Yes (on node) | Enum only (`validate-creation-provenance`) | Yes (`node-summary-entry:1099`) | DAG index, node validators |
| `:creation/provenance` (pro-rata profile) | `:in-band` \| `:out-of-band` | `build-pro-rata-execution-evidence` (partial_fill/pro_rata_execution_evidence.clj:113,232 — `(or provenance :in-band)`) | **Yes** (hashed in `:evidence-profile/hash`) | **Yes** (in profile hash) | Yes | Enum via structural validator; hash via `verify-pro-rata-execution-evidence` | Yes (`verify-pro-rata-execution-evidence`) | Pro-rata evidence consumers |
| `:creation/provenance` (benchmark evidence) | — | **NOT EMITTED** | N/A | N/A | N/A | N/A | N/A | N/A |
| `:evidence/hash` (bundle root) | Canonical semantic identity | `hc/hash-with-intent :bundle-root` (integrity.clj:141, runner.clj:1322) | **Yes** | **Yes** (the hash itself) | Yes | Yes (`verify-bundle-hash`) | Yes | All consumers |
| `:evidence/signature` | Ed25519 signature | `signing/sign-hash` (cli.clj:210) | **No** (post-hash) | **Yes** (persisted in evidence.edn) | Yes | Yes (signature verification) | Yes | Signature verifiers |
| `:source/hash` (VCS) | Source tree hash | `forensic.provenance/source-provenance` → merged into node `:inputs` and bundle root (bundle_root.clj:156) | **Yes** (in node inputs hash + bundle root via `select-keys` in `build-bundle-root`) | **Yes** (in node `:inputs`) | Yes | Yes (via node hash) | Yes | `node-summary-entry`, finalization |
| `:runner-selection` (runner-id) | Runner identity | `run-request` merge (scenario_runner.clj:965, benchmark/runner.clj:166) | **Yes** (in bundle root `select-keys`) | **Yes** (in bundle root) | Yes | Yes (via bundle root hash) | Yes | Bundle root consumers |
| `:execution/parallelism` | Thread count | Runtime opts only (NOT stored in evidence, bundle root, or node) | N/A | N/A | **No** | N/A | **No** | N/A |
| `:execution/chunk-size` | Chunk size | Runtime opts only (NOT stored) | N/A | N/A | **No** | N/A | **No** | N/A |
| `:execution/claimant-parallelism` | Claimant parallelism | Runtime opts only (NOT stored) | N/A | N/A | **No** | N/A | **No** | N/A |
| `:claim/status` | `:verified` \| `:partial` \| `:declared-not-verified` \| `:none` | `report.clj:453` (derived from `:claim/outcome` of all claim-results) | **No** | **No** | Yes (in report) | No (derived) | Yes | Report consumers |
| `:researcher/id` | Researcher identity | `researcher_run_report.clj:33` | **No** (excluded from outcome hash) | **Yes** (in signed report) | Yes | Yes (via signature) | Yes | Researcher report verifier |
| `:outcome-hash` | Cross-researcher comparison anchor | `outcome_manifest.clj` | **Yes** (semantic identity) | **Yes** (in outcome manifest + run report) | Yes | Yes | Yes | All researchers |
| `:bundle/id` (run-links) | `:forensic-run` type | `write-run-links!` (scenario_runner.clj:1095) | **No** (audit-only) | **No** | Yes | No | Yes | Operational audit only |
| `:generated-at` (timestamp) | Wall-clock timestamp | `java.time.Instant/now` (scenario_runner.clj:1104, build-enriched-bundle-root:1106) | **No** (excluded from hashable projections) | **No** | Yes | No | Yes | Audit only |
| `:distribution-provenance` | Distribution identity | `distribution/distribution-identity` → verdict-policy (commands/run_benchmark.clj:342) | **No** | **Yes** (in verdict-policy hash) | Yes | Yes (via verdict-policy hash) | Yes | Verdict policy consumers |

## 4. Findings

### P0 — Can falsely strengthen or misrepresent assurance

*(None — after user correction. No finding meets the P0 bar.)*

### P1 — Provenance can be lost, altered undetected, or materially ambiguous

---

**P1.1 — Pro-rata verifier cannot reproduce `:out-of-band` profile hashes**

- **File/function**: `partial_fill/pro_rata_execution_evidence.clj:385-407` (`verify-pro-rata-execution-evidence`) and `:409-431` (`verify-pro-rata-execution-evidence-v2`)
- **Failure mode**: The verifier calls `(apply build-pro-rata-execution-evidence args)` where `args` are raw builder arguments (benchmark-content-root, model-root, etc.) — NOT including `:creation/provenance`. Because `build-pro-rata-execution-evidence` destructures `:creation/provenance` (line 52) and embeds it as `{:provenance (or provenance :in-band)}` (line 113), the recomputed profile ALWAYS has `:creation/provenance :in-band` regardless of the stored profile's value. If the stored profile was built with `:creation/provenance :out-of-band`, the stored hash was computed over `{:provenance :out-of-band}` but the recomputed hash covers `{:provenance :in-band}` → hash mismatch → verification FAILS.
- **Why it matters**: An `:out-of-band` profile — which is structurally valid and hash-integrity-correct — is **unverifiable** through the public verifier API. The verifier silently substitutes `:in-band`, making out-of-band evidence permanently unverifiable.
- **Root cause**: The verifier does not extract `(:provenance (get-in profile [:evidence-profile/creation]))` from the stored profile and pass it to the rebuilder. The `(or provenance :in-band)` default is correct at the builder's creation boundary, but **wrong** at the verifier's reconstruction boundary.
- **Smallest correct fix**: In `verify-pro-rata-execution-evidence` and `verify-pro-rata-execution-evidence-v2`, extract the stored `:creation/provenance` from the profile and merge it into the args:
  ```clojure
  (let [stored-provenance (get-in profile [:evidence-profile/creation :provenance])
        recomputed (apply build-pro-rata-execution-evidence
                            (assoc args :creation/provenance stored-provenance))] ...)
  ```
- **Regression test**: Build a v2 profile with `:creation/provenance :out-of-band` → call `verify-pro-rata-execution-evidence-v2` with the builder args (minus `:creation/provenance`) → assert `:valid? true` and `:mismatches` empty. Then tamper with `:creation/provenance` in the profile → assert `:valid? false`.

---

**P1.2 — Creation provenance on evidence nodes has no outer-envelope commitment**

- **File/function**: `hash/canonical.clj:1273-1280` (`project-creation-provenance`), `evidence/node.clj:799-827` (`validate-node`), `evidence/node.clj:788-797` (`validate-creation-provenance`), `scenario_runner.clj:1131` (`build-enriched-bundle-root`)
- **Failure mode**: `project-creation-provenance` exists to root-bind `{:creation/provenance ...}` under the `:creation-provenance` hash intent, but is **never called** in any production emission or verification path. The hash intent is classified `:not-applicable` in `corpus_validation.clj:100`. `validate-node` does not call `validate-creation-provenance`. Evidence nodes store `[:execution :creation/provenance]` (always `:in-band`) and it is read back via `node-summary-entry` (line 1099), but there is no hash or signature binding it.
- **Why it matters**: This is the "dangerous middle state" — creation provenance is stored on the node (readable) and excluded from canonical identity (correctly), but NOT bound by any outer envelope. An attacker can change `:creation/provenance` from `:out-of-band` to `:in-band` on a persisted node, and no existing commitment detects it. The node hash is unaffected (provenance is excluded from `project-evidence-node`), so `validate-node` passes.
- **Note (user correction applied)**: A standalone hash of provenance on the node is insufficient — if both `:creation/provenance` and its `:creation-provenance/hash` are outside the authenticated node identity, an attacker can change both. The correct binding is via an **outer envelope** (completion seal, package index, or signed finalization).
- **Smallest correct fix**: Add `:creation/provenance` to the outer envelope commitments that already exist:
  - For the scenario-runner path: add `:creation-provenance-hash` (computed via `hc/domain-hash :creation-provenance {:creation/provenance ...}`) to the `run-completion.v1` or `canonical-integrity.v1` artifact written by `default-complete!` / `default-write-canonical-assurance!`.
  - For the benchmark path: add `:creation/provenance :in-band` to the evidence map (correct — `run-benchmark` actually executes, so this IS in-band) and add `:creation-provenance-hash` to `benchmark-completion.v1` / `canonical-integrity.v1`.
- **Regression test**: After the fix, mutate `:creation/provenance` on a persisted evidence node → assert the outer envelope's commitment check reports a mismatch.

---

**P1.3 — Benchmark evidence map emits no creation-provenance field**

- **File/function**: `benchmark/runner.clj:1289-1310` (evidence map), `benchmark/cli.clj:185-232` (`run-and-report`)
- **Failure mode**: The benchmark evidence map never includes `:creation/provenance`. A consumer reading `benchmark/evidence/evidence.edn` cannot determine whether the evidence was created in-band (by independent execution) or out-of-band (imported/reconstructed).
- **Why it matters**: Without creation provenance, the evidence hash binds only semantic content, not creation context. An out-of-band evidence bundle (imported) has the same structural shape as an in-band one.
- **Smallest correct fix**: Add `:creation/provenance :in-band` to the evidence map at the `run-benchmark` entry point (runner.clj:1289). This is the correct creation boundary — `run-benchmark` actually executes scenarios, so `:in-band` is truthful here. Then bind it in an outer envelope hash (see P1.2), NOT in `:evidence/hash` itself.
- **Regression test**: Assert the evidence map contains `:creation/provenance :in-band`.

---

**P1.4 — No derivation provenance for reconstructed/imported artifacts**

- **File/function**: All `run-and-report` paths
- **Failure mode**: No field distinguishes results that were directly generated by execution from results that were reconstructed or imported. The execution node `:inputs` carries `:dispatch` (scenario path, run-root) and `source-provenance` (VCS hash), but no derivation semantics.
- **Why it matters**: Evidence assembled from pre-existing artifacts cannot signal that it was not freshly generated.
- **Smallest correct fix**: Add `:source/creation {:provenance :out-of-band :root <hash>}` as a distinct field on evidence nodes and benchmark evidence when the source material was not freshly executed. This resolves the two-level distinction the user identified (source creation = out-of-band, report/evidence creation = in-band) without introducing a universal taxonomy.
- **Regression test**: Import an existing evidence bundle → assert the output carries `:source/creation {:provenance :out-of-band ...}`.

---

**P1.5 — No evaluation authority provenance on claim results**

- **File/function**: `benchmark/runner.clj:1112-1126` (`evaluate-claims-coordinator-owned`), `report.clj:453`
- **Failure mode**: Claim results carry `:claim/outcome` and `:claim/evaluation-status` but no field recording the evaluator authority or role that produced the conclusion.
- **Corrected assessment (user feedback point 9)**: Evaluator authority is **already structural** via the `claim-definition-registry` (passive_registries.clj:818-826, `:hash-intent :claim-definition`) which binds claim IDs to `:canonical-hash`, and the `evaluator-registry` (benchmark/claims.clj:507) which maps claim IDs to their `:check` functions. The `commands/run_benchmark.clj:332` verdict-policy already commits to `"evaluator_registry" "resolver-sim.benchmark.claims/evaluator-registry.v1"`. Adding a free-standing `:claim/evaluation-source` to each result would duplicate this structural binding and create two sources of truth.
- **Smallest correct fix**: NONE — the existing `claim-definition-registry` → `evaluator-registry` chain already establishes evaluator authority. Document this relationship in the developer guide. No code change needed.
- **Regression test**: None required — authority is already committed via the claim-definition registry hash in the bundle root.

### P2 — Completeness/clarity/test gap

**P2.1 — `project-creation-provenance` is dead code, classified `:not-applicable`**

- **File/function**: `hash/canonical.clj:1273-1280`, `corpus_validation.clj:100`
- **Failure mode**: The projection function and hash intent exist but are never called. Classified as `:not-applicable` ("not yet wired into the production code path").
- **Smallest correct fix**: Wire it into the outer envelope as described in P1.2, and update the corpus validation classification.
- **Regression test**: Call `hc/domain-hash :creation-provenance {:creation/provenance :out-of-band}` directly → assert it produces a deterministic, distinct root.

---

**P2.2 — `:claim/status :verified` is overstated naming**

- **File/function**: `benchmark/report.clj:453`
- **Failure mode**: `:claim/status :verified` is set when all `(:claim-results evidence)` have `:claim/outcome :pass`. The label `:verified` implies independent verification of evidence integrity, but it actually means claim evaluators passed.
- **Corrected assessment (user feedback point 5)**: No downstream consumer interprets `:claim/status :verified` as authority (grep found zero control-flow consumers). This is P2, not P0/P1.
- **Smallest correct fix**: Rename to `:claim/status :pass` (aligning with `:claim/outcome :pass` values already used). If renaming persisted fields, add a compatibility projection layer.
- **Regression test**: Generate a report with all claim outcomes `:pass` → assert `:claim/status :pass` (not `:verified`).

---

**P2.3 — No round-trip tests for creation/provenance**

- **File/function**: Test suite (no existing tests for creation/provenance round-trip)
- **Failure mode**: No test verifies that `:creation/provenance` (a) survives serialization, (b) is excluded from canonical identity, (c) is bound by an outer envelope, or (d) out-of-band survives end-to-end.
- **Smallest correct fix**: Add tests A–G as described in Section 10.
- **Regression test**: See Section 10.

---

**P2.4 — `:dag/root-node-hash` redundant alias**

- **File/function**: `scenario_runner.clj:1131` (`build-enriched-bundle-root`)
- **Failure mode**: `:dag/root-node-hash` is set to the same value as `:execution/node-hash`.
- **Smallest correct fix**: Remove the alias or document it as a compatibility field.
- **Regression test**: Build enriched bundle root → assert the alias matches.

---

**P2.5 — Parallelism is not persisted in operational audit**

- **File/function**: `benchmark/runner.clj:1131-1133` (opts destructure), `scenario_runner.clj:1150` (`:parallel?` opts)
- **Failure mode**: Parallelism, chunk-size, and claimant-parallel settings are consumed as runtime args but never persisted — not even in operational/audit records. A reader cannot determine from persisted artifacts whether a run was serial or parallel.
- **Corrected assessment (user feedback point 10)**: This is a **policy decision**, not necessarily a defect. Parallelism correctly does NOT enter the `:evidence/hash`. If operational audit requires these values, they should go in an explicitly non-canonical execution-observation record, NOT in canonical evidence.
- **Smallest correct fix**: Add `:execution/observation {:parallelism N :chunk-size N :claimant-parallelism N}` to `run-links/<id>.edn` (already audit-only, scenario_runner.clj:1095-1104) and/or to `run-completion.v1` as a non-canonical audit field. This preserves `serial semantics == parallel semantics` while enabling truthful operational provenance.
- **Regression test**: Run serial and parallel → assert `:evidence/hash` identical → assert `:execution/observation` differs (parallel has `:parallelism 2`).

## 5. Findings Ranked (Final)

| Rank | ID | Finding | Fix Location |
|------|-----|---------|-------------|
| P1 | P1.1 | Pro-rata verifier cannot verify out-of-band profiles | `partial_fill/pro_rata_execution_evidence.clj:391,415` |
| P1 | P1.2 | No outer-envelope commitment for creation provenance | `scenario_orchestration.clj:400`, `commands/run_benchmark.clj:272` |
| P1 | P1.3 | Benchmark evidence emits no creation/provenance | `benchmark/runner.clj:1289` |
| P1 | P1.4 | No source-level derivation provenance | Evidence node spec + benchmark evidence map |
| P2 | P2.1 | `project-creation-provenance` is dead code | `hash/canonical.clj:1273` + wire into envelopes |
| P2 | P2.2 | `:claim/status :verified` overstated | `benchmark/report.clj:453` |
| P2 | P2.3 | No round-trip tests for provenance | Test suite |
| P2 | P2.4 | No operational parallelism audit record | `scenario_runner.clj:1095` (run-links) |
| P3 | P2.5 | `:dag/root-node-hash` redundant alias | `scenario_runner.clj:1131` |
| N/A | P1.5 | (No change needed — evaluator authority already structural) | Document the claim-definition-registry → evaluator-registry chain |
| N/A | P1.1(original) | (Downgraded — defaulting `:in-band` at `emit-execution-node!` is not itself P0 when `run-and-report` actually executes) | N/A |

## 6. Explicit Questions Answered

1. **Can an out-of-band source become apparently in-band through `run-and-report`?**
   **Yes — for reconstructed/imported artifacts.** `with-execution-node+` and `with-execution-node` do not thread `:creation-provenance`, so evidence nodes always default to `:in-band` (evidence/node.clj:84). The `run-and-report` functions actually execute scenarios, so `:in-band` is correct for freshly-executed evidence. The defect is when source material is imported/reconstructed: there is no `:source/creation` field to distinguish the source's out-of-band provenance from the report's in-band creation. The `(or provenance :in-band)` pattern in pro-rata evidence builders (partial_fill/pro_rata_execution_evidence.clj:113,232) is only hit during verification reconstruction, confirming P1.3 as the strongest finding.

2. **Is creation provenance committed against tampering?**
   **No.** `project-creation-provenance` exists but is dead code. No outer envelope (completion, package-index, canonical-integrity, verdict-policy) commits to creation provenance. This is the dangerous middle state: provenance is stored on the node (readable, excluded from canonical identity) but not bound by any separately verifiable commitment.

3. **Is creation provenance correctly excluded from semantic identity where intended?**
   **Yes — for evidence nodes.** `project-evidence-node` (canonical.clj:1282-1302) explicitly excludes `:creation/provenance` from `:execution` select-keys, so it does not enter the node hash. For benchmark bundles, `hashable-evidence` (integrity.clj:76) correctly excludes `:repo`, `:timestamp`, `:evidence/hash`, `:benchmark/artifact-index`, and parallelism was never in the evidence map to begin with. For pro-rata evidence profiles, `:creation/provenance` IS part of the hash — which is correct since it asserts something about the profile's own creation, not about source material.

4. **Can persisted output distinguish source-artifact provenance from report-generation provenance?**
   **No.** The current representation has a single `:creation/provenance` field (on evidence nodes and pro-rata profiles) that conflates both facts. There is no `:source/creation` field to distinguish the source artifact's creation context from the evidence/report's own creation. As the user identified, the model needs: `:creation {:provenance :in-band}` for the current artifact, and `:source {:creation/provenance :out-of-band :root <hash>}` for source material provenance. This is a design gap (P1.4).

5. **Can serial and parallel execution have identical canonical roots while retaining truthful operational provenance?**
   **Identical canonical roots: YES. Truthful operational provenance: NO.** The serial-root model (`:execution/parallelism 1`, `:execution/chunk-size 1`) is the canonical baseline. Parallelism settings are runtime-only: passed as function args, never stored in the evidence map or hashable projection, and stripped via `select-keys` from the bundle root. The integration tests (`benchmark_canonical_package_lifecycle_integration_test.clj:199-238`) confirm identical `package-semantics`, `execution-artifact-bytes`, and `completion-bindings`. However, no operational record persists whether the run was actually serial or parallel — the system cannot truthfully report its own execution mode. Fix: add a non-canonical `:execution/observation` record to run-links (P2.5).

6. **Does any report wording claim stronger verification than the implementation provides?**
   **`:claim/status :verified` — yes, but at P2 severity.** Set at `report.clj:453` when all claim outcomes pass. No downstream consumer interprets `:verified` as authority (grep found zero control-flow consumers). The `canonical-integrity.v1` report (`scenario_orchestration.clj:416`) correctly uses "verified" only for hash-integrity-checked finalization, and its `:scope` explicitly lists `:runtime_isolation false` and `:operator_identity false` as out-of-scope. The report conclusion text (line 393) uses "passed" rather than "verified."

7. **Can a reader reconstruct all material provenance without access to the original process/runtime?**
   **No.** The scenario-runner path stores `:creation/provenance` (always `:in-band`) on evidence nodes, readable via `node-summary-entry`. Source VCS hash is committed in node `:inputs` and bundle root. But: no creation provenance is emitted by the benchmark path; parallelism is not persisted; no derivation semantics exist; and no outer envelope binds creation provenance for tampering detection.

8. **Are legacy/missing-provenance artifacts handled without silently overstating assurance?**
   **No — the `(or provenance :in-band)` pattern silently overstates.** In `pro_rata_execution_evidence.clj:113,232`, when `:creation/provenance` is omitted during profile building, it defaults to `:in-band`. In `emit-execution-node!` (evidence/node.clj:84), the default is also `:in-band`. `validate-creation-provenance` (evidence/node.clj:788) accepts `:out-of-band` as valid but explicitly states mutation is undetectable without a separate commitment. There is no `:unspecified` sentinel or fail-closed fallback for missing provenance.

9. **Is `:pro-rata/integer-domain` still established by its own evaluator rather than inferred from deterministic/parallel equivalence?**
   **Yes.** `:pro-rata/check-integer-domain` (claims.clj:589-613) is a standalone claim evaluator that independently checks `:owed`, `:paid`, `:unmet`, `:cap`, `:basis-amount` for `integer?` using the `non-negative-integer` gate (payoffs.clj:39) which coerces to `bigint` before arithmetic. It is registered in `passive-registries/claim-definition-registry` and invoked by `evaluate-claims-coordinator-owned` → `benchmark-claims/evaluate-manifest-claims`. Serial/parallel equivalence is verified separately via `:stable/hash` projection comparison (`community/result.clj:39`). The two remain independent: integer-domain validity is an independently-checked semantic property; serial/parallel equivalence is an execution-invariance property.

## 7. Implementation Order (Corrected)

Per user guidance, the highest-priority work is binding creation provenance into an outer envelope WITHOUT contaminating semantic identity:

1. **P1.1** — Fix `verify-pro-rata-execution-evidence` / `-v2` to extract stored `:creation/provenance` and pass it to the rebuilder. (Single-function fix, no model change.)
2. **P1.2** — Add `:creation-provenance-hash` to outer envelope commitments (`canonical-integrity.v1` or `run-completion.v1` in both paths). Wire `project-creation-provenance` into emission.
3. **P1.3** — Add `:creation/provenance :in-band` to benchmark evidence map at the `run-benchmark` boundary (truthful — it executed).
4. **P1.4** — Add `:source/creation` field for distinguishing source-level provenance from current-artifact creation provenance.
5. **P2.2** — Rename `:claim/status :verified` → `:pass` (with compatibility projection if persisted).
6. **P2.5** — Add non-canonical `:execution/observation` to run-links for serial/parallel auditability.
