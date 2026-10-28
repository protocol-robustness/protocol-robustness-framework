# PRO-49 — Sew adjudication basis, authority, and ruling path

**Review status:** source-backed model and contract review in progress. This note records what the inspected sources enforce and the evidence that remains missing. It does not qualify Sew for deployment or make an adjudication policy decision.

## Source identity and inspection scope

| Source | Inspected revision | Working-tree qualification |
|---|---|---|
| PRF framework model/tests | `d21d222fba9e18d3533e5b91315fddf6e2730f77` (`agent-c-staging`; review workspace is an empty child) | Clean at inspection |
| Sew contracts | `7549265eb1f0b15dc62c64594fee18bb5b77c778` | Sew working tree has unrelated deployment/configuration edits; the inspected `contracts/core/BaseEscrow.sol`, `contracts/modules/decentralized-resolution-module/DecentralizedResolutionModule.sol`, and `contracts/arbitration/KlerosArbitrableProxy.sol` were not among the modified paths |
| PRF/EVM | `3322aa87` (`@`; parent `c17b4798`) | Read-only path search found governance adapters, but no Sew adjudication/ruling adapter in the inspected tree |

The Sew checkout is local and its current source has not been independently reviewed or published as a clean source revision. The PRF/EVM adapter scan is a path/symbol search, not a full repository audit.

## Transition and authority map

| Transition | What the inspected implementation enforces | Boundary / remaining qualification |
|---|---|---|
| Dispute opened | `BaseEscrow.raiseDispute` verifies a pending workflow and asks its snapshotted resolution module to open/assign the dispute. The Sew model records dispute state and resolver assignment. | Neither a dispute event nor an assigned resolver establishes the truth or completeness of submitted external evidence. The exact deployed module/configuration identity still needs independently retained deployment evidence. |
| Adjudication inputs frozen | PRF builds source envelopes from subsystem-owned dispute, evidence-set, authority, context and configuration roots under a state fence; case/snapshot/instance artifacts bind workflow, round and predecessor lineage. | Roots commit to supplied inputs. They do not authenticate external documents, prove completeness, or establish that an external authority was legally competent. |
| Ruling created | `prf/dispute-ruling.v1` is closed-shape and domain-rooted. It binds basis, dispute, authority, adjudication-instance, case, provider IDs, round, outcome, evidence, policy, timestamp and an authentication root. | A self-root proves only payload integrity. The artifact does not establish that a provider issued the ruling. |
| Ruling authenticated | `prf/adjudication-authentication-verification.v1` binds the ruling and instance to issuer/subject, method, credential-material, provider-evidence and observation roots. | Its validator checks canonical structure and bindings. It does **not** itself verify a signature, query Kleros, authenticate provider evidence, or establish observation-source trust. A trusted provider adapter and its verification evidence are required. |
| Appeal / escalation | Sew Solidity checks appeal caller roles, current dispute/pending-decision state, the configured next resolver and quote; its appeal transition commits chain ID, escrow, workflow, appealed decision root, actor identities, round/resolver transition and bond policy. PRF models appeal-window ordering, stale/reordered actions, round progression and bond projections. | EVM policy/configuration and the PRF model are different execution layers. This review did not reproduce all Solidity appeal/bond branches against matching Clojure traces. |
| Kleros handoff | `KlerosArbitrableProxy` uses a committed Kleros dispute ID tied to escrow/workflow and requires the proxy to be the authorized final-round resolver before forwarding a non-refusal ruling. | The Kleros adapter path is contract/test-fixture based here; no live provider observation or authenticated Kleros ruling artifact was inspected. Refusal (`ruling == 0`) is not represented as an escrow outcome and leaves timeout handling as the exit path. |
| Finality | PRF derives a local finality artifact only for a release/refund ruling authenticated against the current case/instance and the authoritative final round; it re-derives against the current model world. Solidity DRM finalization checks final round or appeal deadline. | Model finality is not Ethereum consensus finality, provider finality, or proof that external evidence is authentic. `BaseEscrow` accepts `resolutionHash` but documents and implements it as unbound metadata; the Kleros proxy's value is a hash of ruling and timestamp, not a canonical authenticated-ruling artifact. |
| Custody-local effect | `BaseEscrow._executeResolution` authorizes the current dispute resolver and executes or records a pending settlement. `DRM.recordResolution` is escrow-only and records round/outcome/deadline; its appeal-bond distribution failure is atomic in the reviewed source. | Current EVM settlement is authorized by the resolver/callback path, not by PRF's canonical ruling/finality artifact. The PRF model's rooted artifact chain must not be described as enforced onchain by these contracts. |
| Closure and incentives | DRM can mark a dispute final and call incentive hooks. | Existing PRO-43 tracks swallowed finalization/bond-cleanup hook failures; PRO-45 tracks swallowed resolver-history and reversal-slash restoration hooks. Those remain separate open risks and are not resolved by this review. |

## Material findings

1. **The PRF artifact chain is not an EVM authorization interface.** The model distinguishes basis, ruling, authentication result, finality and economic effect. The current Solidity seam does not consume those artifacts: `BaseEscrow._executeResolution` authorizes the current resolver, calls `recordResolution`, and accepts `resolutionHash` without binding it to the outcome. Do not claim the model artifacts gate deployed settlement.
2. **Authentication is adapter-owned and currently unqualified end-to-end.** Canonical roots and `result: :authenticated` are not cryptographic verification. No current PRF/EVM Sew ruling adapter was located in the inspected tree, and no retained real Kleros response/signature plus independently selected observation source was reviewed.
3. **Kleros case routing has a narrower contract-level binding.** The proxy's committed dispute ID is tied to an escrow/workflow and the proxy is the authorized resolver for the configured final round. This is a useful routing/authority check, but it is not a commitment to the PRF evidence set, policy snapshot, canonical ruling artifact or its authentication proof.
4. **Appeal and finality have different meanings.** DRM appeal windows and final-round rules govern contract lifecycle. PRF's derived dispute-ruling finality means only that its replayed adjudication context is on the configured final round with an authenticated-result artifact. Neither one proves public-chain consensus finality or the truth of a dispute claim.
5. **Bond disposition is not completely qualified by these passing model surfaces.** `recordResolution` distributes appeal bond on higher-round decisions; its core distribution failure reverts atomically. Resolver-assignment history, finalization callbacks, and reversal-slash restoration still have best-effort hooks tracked by PRO-43/PRO-45. Procedural rejection must also remain distinct from a merits ruling; this review found no authenticated retained Sew reference-case bundle that demonstrates every requested disposition (upheld merits, reversal refund, fees, and procedural rejection) end to end.

## Existing focused model coverage

The current test suite contains substitution checks for basis/ruling/authentication/finality roots, case/workflow/round/instance mismatches, non-final rounds, refusal outcomes, deadline boundaries, stale/reordered escalation, and fenced authority/snapshot replacement. Relevant suites include:

- `protocols_src/test/resolver_sim/protocols/sew/resolution_test.clj`
- `protocols_src/test/resolver_sim/protocols/sew/resolution_basis_test.clj`
- `protocols_src/test/resolver_sim/protocols/sew/dispute_ruling_test.clj`
- `protocols_src/test/resolver_sim/protocols/sew/dispute_ruling_finality_test.clj`
- `protocols_src/test/resolver_sim/protocols/adjudication_authentication_verification_test.clj`
- `protocols_src/test/resolver_sim/protocols/sew/dispute_resolution_closure_test.clj`

These are model tests. They do not establish Solidity/Clojure conformance or provider authenticity.

Validation performed on 28 Oct 2026:

- The six focused adjudication/model suites above passed: **76 tests, 342 assertions, 0 failures, 0 errors**.
- The canonical projection suite `protocols_src/test/resolver_sim/protocols/sew/appeal_bond_projection_test.clj` passed when loaded directly: **24 tests, 61 assertions, 0 failures, 0 errors**.
- The broader selection also picked up the legacy integration suite `test/resolver_sim/protocols/sew/appeal_bond_projection_test.clj`; that suite currently fails: **13 tests, 21 assertions, 4 failures, 10 errors**. Its fixtures use pre-v1 bond identifiers and omit fields now required by the canonical projection (dispute/round lineage, posting-cause root, disposition identity and claim asset). Its transition-receipt assertions also assume successful projections. This suite needs a fixture migration before it can provide evidence for the current schema; it is not evidence of a production Solidity failure.
- No Sew Foundry tests were run from the dirty external checkout. No adapter conformance tests or live Kleros/provider checks were run.

## Threat assumptions and open qualification

- The caller supplies the intended and complete source records; a hash cannot make an omitted or false source fact true.
- The configured adjudicator adapter, issuer identity, credential resolution, and observation source are trusted only to the extent separately verified and retained.
- Contract/module addresses and configuration snapshots correspond to the source/configuration being reviewed; this needs deployment-specific evidence.
- External Kleros callbacks and local EVM behavior do not by themselves prove Ethereum consensus finality or a public-chain observation.
- Reordered/stale actions and cross-workflow substitutions need to be tested against the exact Sew contract release, not inferred from the Clojure model.

### Remaining work before PRO-49 can close

1. Pin a clean Sew source revision and PRF/EVM adapter revision, then run focused Solidity tests against them.
2. Retain a real or explicitly controlled provider-ruling input with authenticated case, chain, round, authority and evidence bindings; independently reproduce the adapter verification.
3. Add cross-implementation conformance fixtures showing the same basis/ruling/appeal/finality decision in Clojure and Solidity, including wrong-case, wrong-authority, stale/reordered callback and refusal paths.
4. Retain a complete reference-case evidence bundle that separates party appeal from researcher dissent and records appeal-bond outcomes for merits upheld, reversal/vindication, fees and procedural rejection.
5. Resolve or explicitly carry PRO-43 and PRO-45 hook-failure risks; this review does not change their status.

No production policy, settlement rule, or canonical schema was changed for this review.
