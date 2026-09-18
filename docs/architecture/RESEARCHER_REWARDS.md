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
