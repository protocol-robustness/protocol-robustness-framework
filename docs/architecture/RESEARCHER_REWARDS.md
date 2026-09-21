# Researcher Rewards V1

Research evidence, reward eligibility, and reward authorization are separate claims.

`researcher-reward-policy.v1` freezes accepted contribution classes, verification
requirements, accepted claim statuses, reward terms, split rules, and duplicate
and conflict rules. V1 accepts only `:approval`, `:dissent`, `:reproduction`,
and `:adversarial-finding`.

`researcher-reward-eligibility.v1` evaluates one contribution against that
policy. `researcher-reward-proposal.v1` preserves eligible policy-bound terms.
`researcher-reward-obligation.v1` binds the exact proposal to an authorized
approval authority. The deterministic reward key is the hash of researcher,
assignment root, contribution root/type, and policy root.

The obligation is not payment authority or proof of payment. V1 deliberately
does not include funding, treasury operations, payment, discharge, disputes,
appeals, campaign lifecycle, cross-chain settlement, or a generic identity or
signing framework.

Reward eligibility consumes established research artifacts by their exact
content roots. It must not introduce another interpretation layer over
research-analysis or upgrade a verification result. No additional research-
analysis changes are required for incentives.

For Solidity assurance work, `research-assignment-target-binding.v1` separately
binds a generic assignment to its exact derived Solidity subject. The composed
lineage verifier checks that the obligation assignment and analytical-claim
subject match that binding. Reward artifact schemas and reward-key semantics
remain unchanged.

## Status

**RESEARCHER INCENTIVES V1 — CLOSED**

Reward primitives, deterministic keys, replay behavior, research integration,
exact-Solidity subject binding, claim reconstruction, composed obligation
lineage, and adversarial substitution coverage are complete. The reference flow
stops at the reward obligation. The production deployment/release resolver is
an external prerequisite of the Solidity assurance path, not incentives work.

Deferred: payment, treasury, budgets, reservations, disputes, appeals,
campaign lifecycle, reputation, staking, and generalized incentive
infrastructure.
