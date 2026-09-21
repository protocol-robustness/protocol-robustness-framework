# Adversarial Solidity Reward Reference

`research-assignment-target-binding.v1` binds an existing generic
`research-assignment.v1` to one exact Solidity assurance subject. It reuses
canonical deployment, registry-release, and chain-configuration roots and
derives `:solidity-target/root`; that root is the binding's research subject.

```text
research-assignment.v1
  -> research-assignment-target-binding.v1
  -> research-analytical-claim.v2
  -> researcher-reward-eligibility.v1
  -> researcher-reward-proposal.v1
  -> researcher-reward-obligation.v1
```

`resolver-sim.benchmark.researcher-reward/verify-adversarial-finding-lineage`
is the portable inspection path. It takes resolved existing artifacts and
returns their exact assignment-target, target, claim, and obligation roots.
It reconstructs the analytical verification and claim; it does not create an
evidence graph, target registry, reward bundle, payout, or treasury action.

The verifier requires:

- obligation assignment root equals the target binding assignment root;
- claim subject root equals the target binding subject root;
- the contribution is that exact claim and has type `:adversarial-finding`;
- eligibility evidence names the exact reconstructed claim and verification;
- deployment, release, and configuration root substitutions change the derived
  target and invalidate the lineage.

The reference test is
`resolver-sim.benchmark.research-assignment-target-binding-test`. It uses the
existing on-chain Shortfall assurance shape with deterministic canonical root
fixtures and stops at the reward obligation. No payout or economic lifecycle is
implied. A production reference must resolve the deployment and release roots
from the authoritative registry rather than substitute these test fixtures.

## Incentives V1 Boundary

Frozen: policy, eligibility, proposal, obligation, research integration, and
exact-Solidity-target lineage verification.

Deferred: payments, treasury, on-chain distribution, escrow or budget
reservation, campaigns, disputes, allocation, dynamic pricing, reputation,
staking, identity, governance, cross-chain rewards, reward markets, funding,
and generalized incentive or target DSLs.
