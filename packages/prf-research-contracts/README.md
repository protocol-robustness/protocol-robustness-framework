# PRF research contracts package candidate

This directory records the explicit package contract and dependency manifest
for resolver-sim.research.api. The authoritative builders and validators
remain in their existing PRF research namespaces; this candidate does not copy
them or define new artifact semantics.

The public boundary covers analysis-output verification and claim derivation,
claim acceptance, claim lifecycle replay, and research-input validation. Claim
acceptance remains independent of incentives and accounting. See
DEPENDENCIES.edn for declared package dependencies, the in-tree source closure
limitation, compatibility requirements, and qualification commands.

Status: in-tree source candidate, not independently buildable or released. A
consumer still requires the authoritative research implementation closure and
an external immutable prf-canonical coordinate. The qualified canonical
source revision is available through the in-tree package path. The isolated API-closure test proves
the current staged closure loads with explicitly listed Maven dependencies; it
does not constitute an independently pinned package release.

The research-specific isolated conformance command is
`bash scripts/qualify_research_contracts_closure.sh`. It stages the manifest's
24-namespace closure without the monorepo source classpath and checks public
API delegation plus claim acceptance and lifecycle behavior.
