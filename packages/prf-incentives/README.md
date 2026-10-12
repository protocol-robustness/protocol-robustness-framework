# PRF incentives package candidate

This directory records the dependency manifest and public API contract for
resolver-sim.incentives.api. The API delegates to the existing authoritative
reward implementation; it does not reproduce claim acceptance or create a
second eligibility/obligation lifecycle.

The V1 sequence remains policy -> eligibility -> proposal -> obligation. Exact
accepted research identities are inputs to incentive evaluation; reward
eligibility is not a research-validity decision, and an obligation is not a
payment or custody authorization. See DEPENDENCIES.edn for direct dependencies,
compatibility conditions, and current release gates.

Status: in-tree source candidate, not independently buildable or released. It
depends on immutable canonical and research-contract package coordinates that
are not yet available. No implementation is vendored here.
