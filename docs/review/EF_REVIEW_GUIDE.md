# PRF / Sew technical review guide

## Scope

This packet presents the canonical Protocol Robustness Framework (PRF) execution
path using the Sew protocol model. It is a research and assurance framework,
not a production security certification or deployed-contract audit.

Start with the built Sew distribution:

```bash
java -jar prf-runner-sew-0.1.0-uber.jar help
```

Before inspecting examples, verify the packet from its own root:

```bash
bin/verify-review-packet.sh .
```

This verifies the distributed JAR, selected inputs, review documents, packet
manifest, and every generated evidence bundle using only the packet contents.

The supported review commands are:

```text
run-scenario <scenario> --run-root <fresh-root>
verify-scenario --run-root <completed-root>
run-benchmark <benchmark-id> --run-root <fresh-root>
verify-benchmark --run-root <completed-root>
```

## Architecture and evidence path

```mermaid
flowchart TD
    A[Scenario or benchmark input] --> B[Snapshot and execution plan]
    B --> C[Deterministic replay]
    C --> D[Invariants and claims]
    D --> E[Evidence and forensic artifacts]
    E --> F[Artifact registry and sensitivity scan]
    F --> G[Scenario or benchmark finalization]
    G --> H[completion.json]
    H --> I[Read-only independent verifier]
```

For a scenario bundle the evidence chain is:

```text
input snapshot
→ persisted event evidence
→ per-scenario chain finalization
→ run-level reconciliation/finalization
→ artifact registry and validation
→ completion.json
→ verify-scenario
```

For a benchmark bundle the assurance chain is:

```text
input snapshots and execution plan
→ child execution summaries and invariant results
→ conservation projection and benchmark conclusion
→ benchmark assurance
→ registry and validation
→ finalization / final_ref
→ completion.json
→ verify-benchmark
```

## Review corpus

| Input | Review purpose |
|---|---|
| `S-DR-001-basic-release-ruling.edn` | Straightforward successful lifecycle. |
| `S-DR-084-evidence-after-settlement-rejected.edn` | Safe control: late evidence after settlement is rejected and the finalized workflow remains unchanged. |
| `S-NC-001-freeze-active-dispute-negative-control.edn` | Intentional semantic-failure control: a successful slash transition leaves a frozen resolver assigned to an active dispute, violating a protocol invariant. |
| `Y06_multi-party-pro-rata-shortfall.edn` | Atomic shared-pool constrained allocation with deterministic pro-rata handling. |
| `DR-N-002-reversal-slash-appeal-rejected.edn` | Appeal/slashing adversarial path for focused manual review. |
| `sew/sew-force-authorisation-custody-v1` | Multi-execution benchmark with conservation and assurance finalization. |

The packet generator executes and verifies the safe rejected-interaction control,
the pro-rata scenario, `DR-N-002` as the completed semantic-failure control, and
the benchmark as evidence examples. `DR-N-002` reaches an unsuppressed invariant
violation without aborting the outer lifecycle; its successful package
verification demonstrates that lifecycle completion, content integrity, and
runnability are distinct from semantic success. Its reviewer-facing diagnostic
diagram is available at `diagnostics/scenario-semantic-failure/diagnostic.md`
(with Mermaid source in `diagnostic.mmd`); it is derived and non-authoritative.
`S-NC-001` remains included as an explicitly labelled manual negative-control
input; its fraud-slash evidence path currently depends on a claim-registry repair
before it can replace `DR-N-002` as the generated package example.

### Pro-rata allocation evidence

`Y06_multi-party-pro-rata-shortfall.edn` is a single atomic withdrawal over one
shared USDC liquidity pool. Its canonical allocation record is:

```text
scenarios/<scenario-id>/summaries/partial-fill-decisions.json
```

This CORE-inventoried `partial-fill-decisions.v1` projection is derived from the
content-addressed replay decision. It identifies the shared pool, participants,
available liquidity, requested/filled/deferred totals, explicit `pro-rata` and
rounding policy, canonical owner-ID ordering/tie-break policy, per-participant
rows, and conservation/residual checks. For the review scenario it records 3,000
requested, 1,800 shared liquidity, 1,800 filled, and 1,200 deferred: Alice
receives 600/1,000 and Bob 1,200/2,000. The tied-remainder rule for shared
withdrawals is explicit: canonical owner-ID ascending order.

The packet also includes two files under `inputs/test-vectors/pro-rata/`. They
are deterministic **calculator reference vectors** for insufficient liquidity
and rounding dust. They independently exercise the pro-rata calculator; they
are not asserted to be replay witnesses for Y06 and must not be substituted for
the scenario allocation artifact. Sew slashing has separate projection,
claim-evaluation, and allocation-result evidence; these packet examples do not
claim that Y06 is evidence for slashing allocation.

## Inspecting a completed bundle

1. In a generated packet, begin with `REVIEW_PACKET_MANIFEST.json` for the
   selected inputs, JAR hash, evidence roots, and verifier commands.
2. Read `completion.json` for lifecycle status and terminal commitments.
3. Read `manifest/run.json`, `manifest/summary.json`, and
   `manifest/diagnostic-summary.json` for concise execution context.
4. Use `manifest/artifacts.json` as the only authoritative file inventory.
5. Follow root-relative references into scenario summaries and forensic evidence.
6. For the semantic-failure example, read
   `diagnostics/scenario-semantic-failure/diagnostic.md` after verifying the
   authoritative bundle. It is a concise derived timeline, not evidence.
7. Run the matching verifier. Verifiers are read-only. The canonical
   single-scenario completion-first package validator is implemented as the
   package-assurance API; `verify-scenario` integration remains a separate
   final wiring step, so use the package-assurance result rather than inferring
   that every current verifier path exposes all package reason codes.

`completion.json` means the evidence lifecycle completed. It does **not** mean
a scenario or benchmark semantically passed. Scenario and benchmark outcomes
remain separate from lifecycle completion.

The completion-first package-assurance API verifies content integrity, declared
evidence reconciliation, and terminal finalization for the supported
single-scenario profile. It is intentionally distinct from optional
forensic-grade claims such as a signed cursor: an unsigned package can be
integrity-valid and runnable while release eligibility reports `false` because
signer/operator assurance is absent. That reports the absent trust mechanism; it
does not invalidate the content-addressed package or assert operator identity.

## Limits and maturity

PRF currently provides structured, content-addressed evidence, deterministic
model replay, artifact inventory, sensitivity scanning, and independent
verification for its canonical scenario and benchmark commands.

It does not claim that:

- a Solidity implementation is automatically converted into a complete model;
- unsigned evidence proves operator identity, signer authority, or runtime
  isolation;
- a bounded scenario or benchmark establishes protocol-wide security;
- external benchmark packs are currently supported; filesystem manifests remain
  intentionally quarantined until their full dependency closure is snapshotted;
- standalone forensic-run isolation is complete.

Legacy/internal execution paths remain in the repository for compatibility and
research, but are not part of this review surface.
