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
an immutable prf-canonical dependency. The isolated API-closure test proves
the current staged closure loads with explicitly listed Maven dependencies; it
does not constitute an independently pinned package release.
