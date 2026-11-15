# Extraction candidate assessment

Date: 2026-11-15

Decision: keep the assessed research and validation capabilities in this
repository. Their modularity is useful now; independent repositories or
published artifacts are not justified until a real external consumer needs
them and their dependency contracts are qualified.

## Candidates

| Capability | Reuse value | Extraction readiness | Recommendation |
|---|---|---|---|
| `prf-core` extension API slice | High as the stable dependency surface for extension authors. | The in-tree candidate now exposes general and portable pro-rata facades over a 54-namespace source closure. Clean-room consumer qualification passes, but no independent core repository or release exists, and many closure namespaces have other monorepo consumers. Sew economics still needs an API-bearing core revision at a fetchable immutable coordinate. | Keep preparing the reviewed source candidate. Defer physical Sew package transfer until the API-bearing core revision is independently resolvable and consumer qualification passes. |
| Research contracts (`packages/prf-research-contracts`) | High for clients that need rooted research analysis, acceptance, and lifecycle contracts without the full researcher network. | A facade, contracts, source manifest, and isolated source qualification exist. It still depends on unpublished core/canonical source, and the broad repository-boundary test reports stale closure metadata. | Keep in-tree; refresh and independently qualify its closure after core is available. This is the strongest research-package candidate. |
| Research incentives (`packages/prf-incentives`) | Medium-high for researcher networks that need reward policy, eligibility, proposal, and obligation artifacts. | A facade and manifest exist, but the package depends on research contracts and canonical behavior. Its source closure is stale and neither dependency is an immutable released package. | Keep in-tree and sequence after research contracts. Preserve the separation between scientific acceptance, incentive eligibility, and payment authority. |
| File-based partial-fill research (`research/partial_fill_file_artifacts`, `research/partial_fill_file_profile`) | Medium as an auditable, bounded demonstration of partial-fill evidence and serial file-backed admission. | The pure rooted artifact layer is separated from the journal/profile. The profile explicitly models one local authority and does not provide distributed ordering, settlement, or payment authority. | Keep the internal split. Reconsider a focused package when a second independent protocol or researcher consumes the artifact contract without the local file profile. |
| Game-theoretic validation (`benchmark/game_theory_validation`, `strategic_claim_validation`) | High as a PRF assurance capability for evaluating bounded strategic claims. | The CLI/orchestrator combines generic scenario analysis, fixture loading, strategic claim validation, and Sew-specific equilibrium validators. Some checks are trace-level proxies and explicitly do not prove equilibrium. | Keep in-tree. First separate generic input/result contracts and validators from Sew adapters and fixture orchestration; require an independent non-Sew consumer before extracting. |
| Research execution, ratification, and researcher network | High for collaborative research, but spans materially different responsibilities. | Execution, runner transport, identity, signatures, policy, review, history, and challenge workflows do not form one narrow package boundary. | Keep integrated in this repository. Extract stable artifact contracts individually; do not create a broad `prf-research` package. |
| Existing capability packages (`force-authorisation`, `held-custody`, Sew pro-rata economics) | High where adopters need these capabilities independently. | Their runtime source imports the corresponding versioned extension facade and passes in-tree or isolated source tests, but there is no API-bearing published `prf-core` artifact. | Keep the package directories in this repository until a pinned core release supports external-consumer qualification. |

## Why reuse does not automatically justify extraction

Extraction adds release ownership, compatibility policy, dependency pinning,
consumer migration, and independent verification. A clean namespace split or
a passing in-repository suite is not enough. Keep a capability in the
monorepo when its only consumer is PRF, its contract still follows changing
protocol semantics, or its verification depends on fixtures and code that
remain in the framework.

Prioritize extraction when all of these are true:

1. At least one independent consumer has a concrete need for the package.
2. The public API and versioned artifact contracts are stable.
3. The package has a complete source, resource, build, and runtime dependency
   closure.
4. It can be built and qualified from a clean checkout without monorepo paths.
5. Ownership, release permissions, compatibility, and deprecation are clear.

## Recommended order

1. Prepare and qualify the narrow `prf-core` extension API slice.
2. Correct the research-contract and incentives manifests against the current
   dependency graph; preserve their existing qualification and authority
   boundaries.
3. Release research contracts before incentives if external demand exists.
4. Decouple generic strategic-claim validation from Sew-specific mechanics
   before considering a game-theoretic validation package.
5. Keep partial-fill research as an in-tree example until another independent
   consumer establishes a useful package contract.

No source moves are authorized or implied by this assessment.
