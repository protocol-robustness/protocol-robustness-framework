# Research consensus and policy quorum boundary

## Certificate labels describe assessed positions

The three-member research certificate reports descriptive consensus for
assessed positions. In `src/resolver_sim/benchmark/review/three_member_certificate.clj`,
each consensus cell carries both `:assessed-count` and `:member-count`, plus
the member groups that explain the result. `:unanimous` means all assessed
positions agree; it does not imply that all three round members assessed the
item. A one-of-three cell is `:single-assessment`. Non-assessments remain
separate as absent, not-reviewed, insufficient-information, not-applicable, or
not-evaluable.

`pre-certificate-checks` requires reports, positions, and canonical indices
to match the exact three distinct members in the frozen review round. A
certificate therefore cannot omit a panel member, substitute an outside
assessor, or change the panel denominator and still validate. V3 validation
rebuilds the certificate from its embedded inputs and compares the complete
body. These checks establish integrity of a descriptive certificate; they do
not make its labels a policy quorum or operational authority.

## Consumer inventory

| Consumer | Inputs used | Boundary |
| --- | --- | --- |
| Three-member certificate builder and V3 validator | Frozen round, all member reports and positions, canonical indices | Recomputes descriptive labels and assessed/total counts; does not authorize an action |
| `benchmark.dimension-support` | The allowed dimension vocabulary | Checks dimension names only; it does not read consensus labels or counts |
| `research.ratification/reconstruct-ratification` | Trusted policy, eligible principals, threshold, signed contributions, key bindings | Reconstructs quorum from eligible signed contributions; a missing member contributes no approval and an ineligible principal invalidates the input |
| `benchmark.research-ratification-artifact` | Claim analysis context, target, policy, contributions, trust roots | Independently verifies claim reconstruction and policy ratification; authority and rewards remain false |
| `protocols.sew.research-ratification` | Current SEW basis and snapshot, trusted policy, signed contributions and key bindings | Reconstructs ratification for that exact snapshot; does not consume three-member descriptive labels |
| Force-authorisation and cancellation gates | Their own signed decisions, declared policy/profile, snapshot bindings | Use separate authority inputs; a research certificate label is not a substitute |

The source inventory found no production consumer that promotes
`:unanimous` or `:qualified-majority` from a three-member certificate into
approval, eligibility, policy quorum, or authority. Certificate references
outside its implementation are limited to the dimension vocabulary and
documentation tooling. The policy-governed research and SEW consumers use
their own eligible membership and threshold inputs.

## Required consumer rule

Any future consumer that needs policy approval must independently verify the
trusted policy and its exact eligible panel, apply its missing-member rule,
count only valid eligible signatures, enforce the policy threshold, and bind
the result to its subject and round. It must not infer those properties from
a descriptive consensus label or from `:assessed-count` alone.

The regression fixtures cover 1/3 single assessment, 2/3 qualified majority,
3/3 unanimity, absent and outside assessors, a substituted total count,
an unmet signed-contribution threshold, and a SEW input carrying a favorable
stored status without its quorum contributions.
