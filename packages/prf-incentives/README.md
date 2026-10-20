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
implementation is vendored here. Its current isolated closure contains 26
namespaces.

## Identity and dispute-context boundary

Reward eligibility uses the supplied `:researcher/id` as a stable contribution
key. It does not require that value to identify a real-world person, so an
anonymous researcher can use a unique opaque pseudonym. Review-round artifacts
currently reveal their member IDs; callers must use distinct round-scoped
pseudonyms when publishing rounds for anonymous participation. This provides
pseudonymity, not cryptographic anonymity or protection against correlation
through other published evidence.

The V1 reward contract has no dispute-context field or dispute-context
requirement. Disabling dispute context therefore does not change reward
eligibility, and the reward evaluator does not bind claims to a dispute. An
application that requires dispute-scoped eligibility must establish and
validate that binding before calling the reward API; it must not infer such a
guarantee from a valid reward result.
