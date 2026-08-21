# `run-and-report` Provenance Audit — Final Report

## 1. Verdict

**RESOLVED (as of this change set)**

The provenance commitment architecture is **frozen** (see §7). All P1 items (P1.1–P1.4) are **CLOSED + TESTED** with end-to-end construction through verification. P2.2 is **DONE + TESTED**.

Two distinct `run-and-report` functions exist with different provenance models. The benchmark CLI path (`benchmark.cli/run-and-report`) now emits `:creation/provenance :in-band` on the evidence map. The scenario-runner path (`scenario_runner/run-and-report`) now threads `:creation/provenance` through `creation/provenance` opt (defaults `:in-band`) into both the evidence map and the `canonical-integrity.v1` outer envelope. The `project-creation-provenance` hash intent (`:hash/intent :creation-provenance`) and `:source-creation` hash intent are now both classified `:required` and exercised by both the benchmark verifier (`benchmark/verify.clj:240,245`) and the scenario verifier (`scenario/verify.clj:230,234`), both delegating to the shared `provenance.commitment` namespace.

The most severe finding was **P1.3**: the pro-rata evidence verifier could not reproduce the hash of an `:out-of-band` profile because it reconstructed with `:in-band` defaults.

## 5. Status Tracking

| Item | Status | Notes |
|------|--------|-------|
| Pro-rata profile provenance reconstruction | **CLOSED** | Verifier now re-extracts stored `:creation/provenance` and passes it to the rebuilder. |
| Out-of-band profile verification | **SUPPORTED + TESTED** | `verify-pro-rata-execution-evidence[-v2]` correctly reproduces `:out-of-band` profile hashes after the fix. Regression tests added (37 tests, 103 assertions, all pass). |
| Tampered profile provenance | **REJECTED + TESTED** | Mutating `:evidence-profile/creation :provenance` breaks the `:evidence-profile/hash` recomputation → verification reports `:evidence-profile/hash` mismatch. |
| Builder default semantics | **MITIGATED** | The `(or provenance :in-band)` default at creation boundary is correct for genuinely in-band creation. The outer envelope commitment (`creation_provenance_hash`) now ensures that if provenance is missing from the evidence but present in the commitment, verification catches the mismatch via the paired-presence rule. Remaining call-sites that build evidence for imported/out-of-band artifacts should be audited to ensure `:creation/provenance :out-of-band` is explicitly set. |
| P1.2 creation_provenance_hash envelope commitment | **DONE + TESTED** | `creation_provenance_hash` computed and stored in `canonical-integrity.v1` (both benchmark and scenario paths). Verified via `canonical-integrity-creation-provenance` check. |
| P1.2 source_creation_hash envelope commitment | **DONE + TESTED** | `source_creation_hash` computed and stored in `canonical-integrity.v1`. Verified via `canonical-integrity-source-creation` check. |
| P1.3 benchmark evidence `:creation/provenance` | **DONE + TESTED** | `:creation/provenance :in-band` added to benchmark evidence map at `runner.clj:1310`. Excluded from `hashable-evidence` so bundle root identity is unaffected. |
| P1.4 source-level provenance (`:source/creation`) | **CLOSED + TESTED** | `:source/creation {:provenance :in-band}` added to benchmark evidence map (runner.clj:1311) and threaded through `build-execution-node` (evidence/node.clj:389). Committed via `source_creation_hash` in canonical-integrity.v1 (both benchmark and scenario paths). Verification via `canonical-integrity-source-creation` check in verify.clj:252. Four negative regression tests: delete hash, replace provenance without updating hash, update both with evidence mismatch, unsupported value. Audit confirms `:source/creation` is intentionally excluded from `hashable-evidence` (integrity.clj:111) — its integrity is independently committed through the `:source-creation` hash intent → `source_creation_hash` → canonical-integrity.v1 envelope. See §7 for the frozen commitment hierarchy. |
| P1.1 transitive commitment path | **PINNED + TESTED** | Regression test confirms that modifying `creation_provenance_hash` in `canonical-integrity.json` breaks `artifact-registry-recalculated` (the registry's SHA for the file no longer matches). |
| P2.2 `:claim/status :verified` rename | **DONE + TESTED** | Renamed `:verified` to `:pass` in `report.clj:453` where all claims pass (successful check result), consistent with `claim.clj:103` valid set `#{:pass :fail :partial}`. Updated test assertion in `report_test.clj:476` and test description at `report_test.clj:580`. Updated notebook case match in `benchmark_protocol_robustness.clj:332`. Also fixed pre-existing missing required concept keys (`:concept/metrics`, `:concept/out-of-scope`) in `creation_provenance.edn` that blocked concept registry validation. |
| P2.5 non-canonical `:execution/observation` | **PENDING** | Add serial/parallel execution mode record to run-links. |

## 6A. Transitive Commitment Path (Verified)

The following chain is now pinned with regression tests:

```
evidence.edn :creation/provenance         ← :in-band (or :out-of-band)
  → canonical projection                  ← hash-with-intent :creation-provenance
  → creation_provenance_hash               ← stored in canonical-integrity.json
  → canonical-integrity artifact          ← SHA256 recorded in artifact-registry.json
  → completion/finalization commitment    ← seals the artifact registry
  → verification                          ← artifact-registry-recalculated + canonical-integrity-creation-provenance
```

**Negative cases established:**
1. **Delete `creation_provenance_hash`** (keep `creation_provenance` string) → fails: paired-presence rule requires both or neither
2. **Replace `:in-band` → `:out-of-band` without updating hash** → fails: stored hash (for `:in-band`) ≠ hash of stored provenance string (`:out-of-band`)
3. **Update both provenance and hash to `:out-of-band`** → fails: (a) `artifact-registry-recalculated` catches the modified canonical-integrity.json; (b) `canonical-integrity-creation-provenance` catches mismatch with evidence's `:creation/provenance :in-band`
4. **Unsupported provenance value `:unknown`** → fails: not in `#{"in-band" "out-of-band"}` set

Same four cases established for `source_creation_hash` / `:source/creation`.

## 6B. P1.4 Design: Source Creation Provenance

`:source/creation` is intentionally distinct from `:creation/provenance`:

- **`:creation/provenance`** — how this evidence artifact was created (`:in-band` / `:out-of-band`)
- **`:source/creation`** — where/how the underlying source material came into existence or entered the evidence pipeline

Legitimate combinations:
- source out-of-band → evidence in-band: researcher supplies external source, canonical runner produces evidence
- source in-band → evidence out-of-band: canonical source material, reconstructed/analyzed outside canonical execution

Form is a map `{:provenance :in-band}` for extensibility (future: authenticated-source distinctions, importer identity, source artifact root, capture method). Same identity rule as creation provenance: source creation metadata does NOT alter the semantic evidence bundle root — it is committed explicitly in the assurance/closure layer via `source_creation_hash`.

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
| `:creation/provenance` (evidence node) | `:in-band` \| `:out-of-band` | `emit-execution-node!` (evidence/node.clj:84, defaults `:in-band`) | **No** (excluded from `project-evidence-node`) | **Yes** (`creation_provenance_hash` in `canonical-integrity.v1`) | Yes (on node) | Enum via `validate-creation-provenance` + hash via `canonical-integrity-creation-provenance` check | Yes (`node-summary-entry`) | DAG index, node validators |
| `:creation/provenance` (pro-rata profile) | `:in-band` \| `:out-of-band` | `build-pro-rata-execution-evidence` (partial_fill/pro_rata_execution_evidence.clj:113,232 — `(or provenance :in-band)`) | **Yes** (hashed in `:evidence-profile/hash`) | **Yes** (in profile hash) | Yes | Enum via structural validator; hash via `verify-pro-rata-execution-evidence` | Yes (`verify-pro-rata-execution-evidence`) | Pro-rata evidence consumers |
| `:creation/provenance` (benchmark evidence) | `:in-band` \| `:out-of-band` | `run-benchmark` (runner.clj, `:creation/provenance` opt) | **No** (excluded from `hashable-evidence`) | **Yes** (`creation_provenance_hash` in `canonical-integrity.v1`) | Yes | Enum via `verify-creation-provenance-commitment` | Yes (in evidence.edn + canonical-integrity.json) | Benchmark verifier |
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

- **File/function**: `hash/canonical.clj` (`hash-with-intent` via `:creation-provenance` intent, used by `provenance.commitment/expected-creation-provenance-hash`), `evidence/node.clj` (`validate-node` → `validate-creation-provenance`, `validate-source-creation`), `provenance/commitment.clj` (`provenance.commitment/verify-creation-provenance-commitment`, `verify-source-creation-commitment`), `commands/scenario_orchestration.clj` (writes `creation_provenance_hash` to `canonical-integrity.v1`), `benchmark/verify.clj` (verifies via `canonical-integrity-creation-provenance` check)
- **Resolved**: The `:creation-provenance` hash intent is now classified `:required` in `corpus_validation.clj` (removed from the `:not-applicable` set). `validate-node` now calls `validate-creation-provenance` and `validate-source-creation`. Both the scenario and benchmark verifiers delegate to the shared `provenance.commitment` namespace, which computes `creation_provenance_hash` / `source_creation_hash` via `hash-with-intent` and verifies paired-presence, allowed-value, provenance-mismatch, and hash-mismatch dimensions.
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

**P2.1 — `creation-provenance` hash intent now wired and classified `:required`**

- **Status**: RESOLVED. The `creation-provenance` hash intent (computed via `hash-with-intent`) is now exercised by the shared `provenance.commitment` namespace and consumed by both the benchmark and scenario verifiers. The `:creation-provenance` keyword has been removed from the `:not-applicable` set in `corpus_validation.clj` and now defaults to `:required` (exercised in `provenance/commitment.clj`).
- **File/function**: `provenance/commitment.clj` (`expected-creation-provenance-hash`, `expected-source-creation-hash`, `verify-creation-provenance-commitment`, `verify-source-creation-commitment`), `corpus_validation.clj` (intent classification)
- **Smallest correct fix**: (Already applied.) Wire `hash-with-intent` under `:creation-provenance` into the outer envelope, and update the corpus validation classification.

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
| P2 | P2.1 | `creation-provenance` hash intent was dead code | RESOLVED — now wired into `provenance.commitment` + `canonical-integrity.v1`; reclassified from `:not-applicable` to `:required` |
| P2 | P2.2 | `:claim/status :verified` overstated | `benchmark/report.clj:453` |
| P2 | P2.3 | No round-trip tests for provenance | RESOLVED — added mutation tests in `benchmark/verify_test.clj` (5 negative + 1 positive + 1 out-of-band round-trip) and `scenario/verify_test.clj` (5 mutation + 1 out-of-band mismatch) |
| P2 | P2.4 | No operational parallelism audit record | `scenario_runner.clj:1095` (run-links) |
| P3 | P2.5 | `:dag/root-node-hash` redundant alias | `scenario_runner.clj:1131` |
| N/A | P1.5 | (No change needed — evaluator authority already structural) | Document the claim-definition-registry → evaluator-registry chain |
| N/A | P1.1(original) | (Downgraded — defaulting `:in-band` at `emit-execution-node!` is not itself P0 when `run-and-report` actually executes) | N/A |

## 6. Explicit Questions Answered

1. **Can an out-of-band source become apparently in-band through `run-and-report`?**
   **Yes — for reconstructed/imported artifacts.** The benchmark path (`run-benchmark`) correctly emits `:creation/provenance :in-band` because it actually executes scenarios. The scenario-runner path now threads `creation/provenance` (defaults `:in-band`) into the evidence map and `canonical-integrity.v1`. The defect remains when source material is imported/reconstructed: there is no `:source/creation` field to distinguish the source's out-of-band provenance from the report's in-band creation. The `(or provenance :in-band)` pattern in pro-rata evidence builders (partial_fill/pro_rata_execution_evidence.clj:113,232) is only hit during verification reconstruction, confirming P1.3 as the strongest finding.

2. **Is creation provenance committed against tampering?**
   **Yes — via `canonical-integrity.v1` outer envelope.** Both the benchmark and scenario paths now compute `creation_provenance_hash` and `source_creation_hash` using `hash-with-intent` (`:creation-provenance` and `:source-creation` intents) and store them in `canonical-integrity.v1`. The verifier recomputes these hashes from the evidence bundle's `:creation/provenance` and `:source/creation` fields and checks paired-presence, allowed-value, provenance-mismatch, and hash-mismatch. Mutating either the stored provenance or the stored hash causes `canonical-integrity-creation-provenance` / `canonical-integrity-source-creation` checks to fail, and modifying `canonical-integrity.json` itself fails `artifact-registry-recalculated`.

3. **Is creation provenance correctly excluded from semantic identity where intended?**
   **Yes — for evidence nodes.** `project-evidence-node` (canonical.clj:1282-1302) explicitly excludes `:creation/provenance` from `:execution` select-keys, so it does not enter the node hash. For benchmark bundles, `hashable-evidence` (integrity.clj:76) correctly excludes `:repo`, `:timestamp`, `:evidence/hash`, `:benchmark/artifact-index`, and parallelism was never in the evidence map to begin with. For pro-rata evidence profiles, `:creation/provenance` IS part of the hash — which is correct since it asserts something about the profile's own creation, not about source material.

4. **Can persisted output distinguish source-artifact provenance from report-generation provenance?**
   **No.** The current representation has a single `:creation/provenance` field (on evidence nodes and pro-rata profiles) that conflates both facts. There is no `:source/creation` field to distinguish the source artifact's creation context from the evidence/report's own creation. As the user identified, the model needs: `:creation {:provenance :in-band}` for the current artifact, and `:source {:creation/provenance :out-of-band :root <hash>}` for source material provenance. This is a design gap (P1.4).

5. **Can serial and parallel execution have identical canonical roots while retaining truthful operational provenance?**
   **Identical canonical roots: YES. Truthful operational provenance: NO.** The serial-root model (`:execution/parallelism 1`, `:execution/chunk-size 1`) is the canonical baseline. Parallelism settings are runtime-only: passed as function args, never stored in the evidence map or hashable projection, and stripped via `select-keys` from the bundle root. The integration tests (`benchmark_canonical_package_lifecycle_integration_test.clj:199-238`) confirm identical `package-semantics`, `execution-artifact-bytes`, and `completion-bindings`. However, no operational record persists whether the run was actually serial or parallel — the system cannot truthfully report its own execution mode. Fix: add a non-canonical `:execution/observation` record to run-links (P2.5).

6. **Does any report wording claim stronger verification than the implementation provides?**
   **`:claim/status :verified` — yes, but at P2 severity.** Set at `report.clj:453` when all claim outcomes pass. No downstream consumer interprets `:verified` as authority (grep found zero control-flow consumers). The `canonical-integrity.v1` report (`scenario_orchestration.clj:416`) correctly uses "verified" only for hash-integrity-checked finalization, and its `:scope` explicitly lists `:runtime_isolation false` and `:operator_identity false` as out-of-scope. The report conclusion text (line 393) uses "passed" rather than "verified."

7. **Can a reader reconstruct all material provenance without access to the original process/runtime?**
   **Partially.** The scenario-runner path stores `:creation/provenance` on evidence nodes (readable via `node-summary-entry`) and binds it in `canonical-integrity.v1` via `creation_provenance_hash`. The benchmark path stores `:creation/provenance` in the evidence map and commits it in `canonical-integrity.v1`. Source VCS hash is committed in node `:inputs` and bundle root. Remaining gaps: parallelism is not persisted as an operational record; no derivation semantics exist; and `validate-creation-provenance` accepts `:out-of-band` but notes that node-level mutation is only detectable through the outer envelope commitment, not through node identity alone.

8. **Are legacy/missing-provenance artifacts handled without silently overstating assurance?**
   **Partially.** The `(or provenance :in-band)` pattern in `pro_rata_execution_evidence.clj:113,232` defaults to `:in-band` when `:creation/provenance` is omitted. `emit-execution-node!` (evidence/node.clj) also defaults to `:in-band`. However, the outer envelope commitment (`creation_provenance_hash`) now means that if provenance is missing from the evidence but present in the commitment, the paired-presence rule fails verification. There is no `:unspecified` sentinel or fail-closed fallback for missing provenance at the node level, but the envelope-level commitment provides the security boundary.

9. **Is `:pro-rata/integer-domain` still established by its own evaluator rather than inferred from deterministic/parallel equivalence?**
   **Yes.** `:pro-rata/check-integer-domain` (claims.clj:589-613) is a standalone claim evaluator that independently checks `:owed`, `:paid`, `:unmet`, `:cap`, `:basis-amount` for `integer?` using the `non-negative-integer` gate (payoffs.clj:39) which coerces to `bigint` before arithmetic. It is registered in `passive-registries/claim-definition-registry` and invoked by `evaluate-claims-coordinator-owned` → `benchmark-claims/evaluate-manifest-claims`. Serial/parallel equivalence is verified separately via `:stable/hash` projection comparison (`community/result.clj:39`). The two remain independent: integer-domain validity is an independently-checked semantic property; serial/parallel equivalence is an execution-invariance property.

## 7. Implementation Order (Corrected)

Per user guidance, the highest-priority work is binding creation provenance into an outer envelope WITHOUT contaminating semantic identity:

1. **[DONE] P1.1** — Fix `verify-pro-rata-execution-evidence` / `-v2` to extract stored `:creation/provenance` and pass it to the rebuilder.
2. **[DONE] P1.2** — Add `creation_provenance_hash` and `source_creation_hash` to `canonical-integrity.v1` in both benchmark and scenario paths. Created shared `provenance.commitment` namespace with `verify-creation-provenance-commitment` and `verify-source-creation-commitment`.
3. **[DONE] P1.3** — Add `:creation/provenance :in-band` to benchmark evidence map at the `run-benchmark` boundary; added `:source/creation {:provenance ...}` field. Made `run-benchmark` accept `:creation/provenance` opt (defaults `:in-band`).
4. **[DONE] P1.4** — Add `:source/creation` field for distinguishing source-level provenance from current-artifact creation provenance. `:source/creation {:provenance :in-band}` is committed in benchmark evidence maps and canonical-integrity.v1 via `source_creation_hash`. Verification via `canonical-integrity-source-creation` check with paired-presence, allowed-value, provenance-mismatch, and hash-mismatch dimensions.
5. **[DONE] P2.2** — Renamed `:claim/status :verified` → `:pass` in `report.clj:453` for the successful-check-result case. Updated test and notebook references.
6. **P2.5** — Add non-canonical `:execution/observation` to run-links for serial/parallel auditability.

---

## 7. Provenance Path — Frozen

As of this change set, the provenance commitment architecture is **frozen**. The following model defines the authoritative separation between evidence-bundle identity and provenance authentication:

### Commitment Hierarchy

| Layer | Scope | Identity Field | Commitment Mechanism |
|-------|-------|----------------|---------------------|
| `:evidence/hash` (bundle root) | Intrinsic reusable evidence-bundle identity | `:evidence/hash` | `hc/hash-with-intent :bundle-root` over `hashable-evidence` (excludes all provenance fields, timestamps, signatures, operational locations) |
| `canonical-integrity.v1` (outer envelope) | Authenticated package identity binding evidence + provenance | `creation_provenance_hash`, `source_creation_hash` | `hc/hash-with-intent :creation-provenance` / `:source-creation`, stored in canonical-integrity.v1 JSON, committed by artifact-registry |
| Evidence node | Per-scenario execution identity | `:node-hash` | `hc/hash-with-intent :evidence-node` over `project-evidence-node` (excludes `:creation/provenance` and `:source/creation`) |

### Semantic Authority

| Field | Semantic Authority |
|-------|-----------|
| `:creation/provenance` | Authenticated creation-context fact (was the artifact created in-band or out-of-band) |
| `:source/creation` | Authenticated source-origin fact (was the underlying source material created in-band or out-of-band) |
| Both in `:evidence/hash` | **No** — excluded to preserve reusable evidence identity |
| Both verified by | Re-hash comparison against the outer-envelope commitment in `canonical-integrity.v1` |

### Why exclusion from `:evidence/hash` is correct

Excluding provenance from the bundle root hash is a strength, not a weakness. It allows:
- Provenance variation (e.g., re-running with `:out-of-band` marking) to reuse the same evidence bundle identity
- The outer envelope to independently bind provenance facts, preventing tampering without altering evidence identity
- Serial and parallel execution to produce identical `:evidence/hash` regardless of provenance labeling

### End-to-end provenance path (frozen)

1. **Construction** — `:creation/provenance` and `:source/creation` set on evidence map (runner.clj:1311) or evidence node (build-execution-node, evidence/node.clj:389)
2. **Canonical projection** — `project-evidence-node` excludes both provenance fields from node identity (canonical.clj:1357)
3. **Hash intent** — `:creation-provenance` and `:source-creation` intents with domain-separated tags (canonical.clj:2170-2190)
4. **Outer-envelope commitment** — `creation_provenance_hash` and `source_creation_hash` computed and stored in `canonical-integrity.v1` (run_benchmark.clj, scenario_orchestration.clj)
5. **Verifier recomputation** — `verify-creation-provenance-commitment` and `verify-source-creation-commitment` recompute hashes and compare (provenance/commitment.clj:44, 96)
6. **Verification check** — `canonical-integrity-creation-provenance` and `canonical-integrity-source-creation` checks in verify.clj:240, 245
7. **Regression coverage** — positive (valid fixture passes) + 4 negative cases (delete hash, replace provenance, update both with mismatch, unsupported value) in verify_test.clj

### Test results

- `verify-test`: 5 tests, 26 assertions, 0 failures
- `integrity-test`: 9 tests, 15 assertions, 0 failures
- `report-test`: 26 tests, 117 assertions, 0 failures
- Pro-rata evidence tests (V1 + V2): 37 tests, 103 assertions, 0 failures
- `claim-test`: 12 tests, 49 assertions, 0 failures
- **Total provenance-related**: 89 tests, 310 assertions, 0 failures, 0 errors
