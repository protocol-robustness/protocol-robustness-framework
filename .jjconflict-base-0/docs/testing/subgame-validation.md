# Subgame Validation: Strategic Tree Expansion

## Overview
The `expand-strategic-tree` mechanism is a core component of our Subgame Perfect Equilibrium (SPE) validation framework. Unlike trace-based validators that only check if the *observed* path is optimal, this engine performs **Dynamic Strategic Expansion** to validate counterfactual actions.

It determines if an agent's chosen action is optimal by simulating all legal alternatives available at a strategic decision point and evaluating the resulting terminal outcomes.

## Mechanism

The expansion relies on two foundational systems:

1.  **Branching Replay Kernel**: Allows the simulation to "fork" from any intermediate world state (`:world-checkpoints` captured during replay) and execute a continuation path using the existing replay engine. Checkpoints are full world-state maps keyed by event `:seq`, recorded before each event is applied. The `:decision-nodes-only` retention policy keeps only strategic decision points (`raise_dispute`, `escalate_dispute`, `execute_resolution`) to reduce memory; `:retain-all` keeps every checkpoint for debugging.
2.  **Dynamic Action Enumeration**: Leverages the `available-actions` method in `SimulationAdapter`, enabling the protocol to report all legal moves for a given agent in the current state.

### Execution Workflow

When `evaluate-subgame-counterfactual` encounters a decision node (e.g., `create-escrow`, `raise-dispute`):

1.  **Node Classification**: The node is classified as a `:proper-subgame` (if the state is public) or an `:information-set-node` (if private state exists).
2.  **Action Enumeration**: It queries `proto/available-actions` to get all valid `{:action ... :params ...}` maps for the actor.
3.  **Recursive Forking**: For each action, the kernel:
    *   Creates a simulation fork from the pre-action `world-state`.
    *   Applies the alternative action as a deviation.
    *   Follows the original trace's downstream events (or the default strategy profile) to reach a terminal state.
4.  **Utility Attribution**: Computes the terminal utility for the actor in each branch.
5.  **Regret Computation**: Compares the utility of the *observed* action against the maximum utility found in the expanded tree to calculate the deviation regret.

## Configuration & Usage

Tree expansion is opt-in to manage computational complexity. It is enabled via the `spe-config` map passed to the evaluator:

```clojure
spe-config {:regret-threshold 0
            :enable-tree-expansion? true
            :utility-spec {:type :terminal-realized-v1 :version "v1"}}
```

### Technical Constraints

- **Depth Bounded**: Tree expansion is depth-bounded to avoid state-space explosion in complex protocols.
- **Protocol Requirements**: Protocols must implement `available-actions` in the `SimulationAdapter` protocol to enable tree expansion.
- **Alias Handling**: The branching kernel preserves the `id-alias-map` across forks, ensuring entity identity (e.g., workflow IDs) remains consistent in counterfactual paths.
