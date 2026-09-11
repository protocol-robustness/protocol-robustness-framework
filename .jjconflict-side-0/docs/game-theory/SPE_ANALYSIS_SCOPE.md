### Scope of SPE Analysis

Full mechanism-wide subgame perfect equilibrium analysis is not a tractable target for the unrestricted protocol model. The protocol has an effectively unbounded state and action space: arbitrary numbers of escrows and resolvers, large parameterised token amounts, repeated interactions, appeal sequences, reputation-dependent histories, and cross-workflow economic dependencies. Complete extensive-form analysis would also require explicit observation models, information sets, continuation strategies, beliefs, and utility functions for every participant role.

The framework therefore provides bounded sequential-rationality diagnostics rather than claiming unrestricted mechanism-wide SPE.

The current implementation performs trace-conditioned deviation analysis: it forks deterministic replay at selected strategic checkpoints, evaluates encoded alternative actions, and measures regret while using the original trace as the continuation where possible. This can identify profitable deviations in concrete scenarios, but it does not exhaustively evaluate every action, continuation strategy, information set, or subgame.

The practical development frontier is:

1. **Strategic action generation** — connect adapter-defined actions to the fork machinery, while canonicalising and bounding parameterised alternatives.
2. **Policy- or simulation-based continuation values** — replace mechanically following the original trace with declared actor policies or seeded stochastic rollouts.
3. **Selective multi-ply search** — evaluate deviations and responses at important decision nodes under explicit depth, branch, pruning, and horizon limits.
4. **Finite mechanism-specific game abstractions** — define bounded game profiles for high-value mechanisms such as cancellation, appeals, resolver selection, and shortfall allocation.

Results must expose their analytical scope, including the decision nodes examined, candidate actions, continuation model, search horizon, omitted alternatives, utility assumptions, and statistical uncertainty. The term “SPE” should be reserved for finite, explicitly defined game abstractions in which all relevant subgames and deviations have been evaluated. Current unrestricted-protocol results should be described as trace-conditioned, policy-conditioned, bounded, or statistical sequential-rationality diagnostics.

