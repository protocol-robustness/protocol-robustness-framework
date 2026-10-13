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
uses the merged, compatibility-qualified canonical source candidate through
the in-tree package path; no external immutable coordinate is published yet.
The research-contract dependency also remains an in-tree source candidate. No
implementation is vendored here.
