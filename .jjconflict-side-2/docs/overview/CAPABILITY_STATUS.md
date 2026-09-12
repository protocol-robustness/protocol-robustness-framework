# Capability and Coverage Status

**Status date:** 2026-07-13. This is a navigation summary, not an assurance claim. A passing scenario or benchmark demonstrates only its declared workload and claim set.

| Area | Implementation status | Runnable coverage | Solidity/parity status | Evidence and limits |
|---|---|---|---|---|
| Protocol-agnostic framework | Operational | Unit tests, deterministic replay, scenario and fixture runners | Not applicable | Core framework capabilities are documented in `README.md` and `docs/architecture/`. |
| Sew dispute and slashing model | Primary implemented protocol model | Declared invariant, replay, fixture, and benchmark workloads | Partial; parity requirements and trace-equivalence work remain separate checks | Start with `benchmarks/README.md`, `docs/scenarios.md`, and `docs/architecture/protocol-parity.md`. |
| Sew deterministic replay | Demonstrated | Repeated PRF-runner executions for included Sew workloads | Model-side behavior; not by itself EVM equivalence | See `docs/replay/` and reference-validation suites. |
| Yield shortfall | Experimental | Named workloads and scenario paths; closed-form allocation artifacts are required for stronger claims | Not a production parity claim | See `README.md` public benchmark scope and `docs/yield/`. |
| Game-theoretic validation | Research/limited | Trace-end and bounded public-state proxy checks | Not a proof of full SPE | `:pass` is trace consistency, not universal equilibrium proof; see `docs/testing/` and `docs/game-theory/`. |
| Evidence, attestation, and bundles | Operational components with staged integrations | Evidence tests, verification commands, and bundle specs | Not applicable | Some registry-backed attestation and persistence integrations remain incomplete; see `docs/STABILITY.md` and `docs/forensic/`. |
| Solidity equivalence | Conditional integration | Model-side comparison plus separately run Forge replay | Requires both layers before making equivalence claims | Follow `docs/testing/RUNNING_TESTS.md`; Foundry contracts are external to normal local setup. |

## Reading results responsibly

- **Implemented** means code is present in this repository; it does not mean production-ready or Solidity-deployed.
- **Demonstrated** means a named runnable workload has produced the stated kind of evidence.
- **Experimental** means behavior, interfaces, or claims may change and must not be used as assurance evidence without the associated limitations.
- **Proposed** features must carry the protocol-alignment metadata described in `CONTRIBUTING.md`.

For the source-level stability classification and known incomplete integrations, consult `docs/STABILITY.md`. For benchmark-level claims, consult the benchmark manifest and its generated evidence rather than this summary.
