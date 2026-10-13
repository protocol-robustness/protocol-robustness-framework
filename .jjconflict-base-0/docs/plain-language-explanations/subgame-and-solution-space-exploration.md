# Proper subgames and solution-space exploration (plain language)

This document explains how the Protocol Robustness Framework project thinks about **whether people are actually incentivised to behave honestly**—not just whether one recorded story “looks fine,” but whether anyone could have done better by choosing differently at important moments.

You do not need game theory vocabulary to follow it. The same ideas apply to any sequential game where players take turns (or roles act in order) and care about how things end.

---

## The basic question

Imagine a dispute over money held in escrow. A buyer, a seller, and a resolver each make choices over time: open a dispute, escalate, settle, issue a verdict, and so on.

After the fact you have **one timeline**—what actually happened. That alone cannot prove the rules are “incentive-safe.” Someone might have taken a bad move simply because they did not try a better one, or because hidden information changed what was rational.

So the simulator does two related things:

1. **Replay what happened** (deterministic scenarios, invariants).
2. **Explore nearby possibilities**—other legal moves at decision points and, separately, many randomised economic setups (Monte Carlo).

“**Solution-space exploration**” here means systematically asking: *among the outcomes and strategies the rules allow, are there profitable deviations from honest play?* The project does not solve the entire game mathematically; it **samples and bounds** that space in a disciplined way.

---

## What is a “subgame”?

Think of a **subgame** as: *“The dispute from this moment onward, given everything everyone already knows.”*

Example: escrow is locked, a dispute is open, the resolver is about to rule. From that snapshot you can ask: “Starting here, could the resolver earn more by ruling differently, assuming everyone else then behaves according to a clear rule for what happens next?”

In game theory, **subgame perfect equilibrium (SPE)** means: at **every** such restart point, no player can gain by unilaterally changing their move, given how others are assumed to respond afterward.

The codebase does not claim a full formal SPE proof. It produces **SPE proxy evidence**: bounded, replay-based checks that are strong enough to support design review, not a theorem.

For implementation detail, see `docs/testing/subgame-validation.md` and `src/resolver_sim/scenario/subgame_counterfactual.clj`.

---

## What makes a subgame “proper”?

Not every decision point is fair game for this style of check.

### Proper subgame

A node is a **proper subgame** when the information needed to judge the move is **public on the protocol**—escrow state, dispute stage, who is allowed to act, block time, and similar fields everyone can see on-chain.

At these nodes the simulator can:

- Freeze the world at the moment **before** the action.
- List **all legal moves** the actor could take (via the protocol adapter).
- **Fork** the simulation: try each alternative, then continue under a declared **continuation policy** (often: follow the rest of the original trace where still valid).
- Compare **payoffs** (fees, bonds, slashed amounts, terminal wealth) for the move that was taken vs the best alternative.

If the chosen move is worse than some legal alternative by more than a small tolerance (“epsilon”), that is **regret**—evidence of a profitable deviation.

Intuition: *“Everyone saw the same board; could this player have done better by playing a different legal card?”*

### Information-set node

Some moves depend on **private** knowledge the chain does not expose—for example, how strong the buyer’s evidence really is when deciding whether to escalate.

Those are classified as **information-set nodes**, not proper subgames. The checker marks them as **not fully SPE-checkable** with the current model, because comparing payoffs without modelling hidden information would be misleading.

Intuition: *“We cannot fairly replay ‘what if they escalated?’ without knowing what they knew in their head.”*

### Not checkable

Other points are skipped: no pre-state snapshot, or not a recognised strategic action (e.g. routine bookkeeping steps).

---

## The other essential pieces (what you must define first)

Subgame checking is not magic—it is a **recipe**. If any ingredient is vague, the answer “was this move optimal?” is vague too. Below is what the recipe needs, in everyday terms, and how Sew fills each slot today.

### 1. The rules of the game (protocol model + adapter)

Something has to know **what is allowed**: legal moves, who may act, how state updates. In this repo that is the **protocol adapter**—code that turns “player tries action X” into the next world state, or rejects it.

- **Layman:** the rulebook and referee.
- **Sew example:** escrow states, dispute stages, resolver authority, bond locks.

Without this you cannot list alternatives or fork a timeline.

### 2. Payoff function (utility)

A **payoff function** (here called a **utility spec**) answers: *when the story is over (or at this checkpoint), how well did this player do, on the scale we care about?*

It must be **explicit**. “Feeling unfair” is not a payoff; “+50 tokens in claimable balance” or “lost the match” is.

- **Layman:** the scoring system at the end of the game.
- **Sew examples:**
  - **Terminal money:** stakes + claimable + bonds for the actor (`:terminal-realized-v1`).
  - **Reputation-aware:** same money, plus a penalty for lost future work if they were slashed (`:resolver-reputation-v1`).

If two designs use different payoffs, “optimal” means different things. Always state which scoring you used.

### 3. Continuation policy (what happens after a “what if”)

When you imagine *“what if they had played differently here?”* the story must continue somehow. The **continuation policy** fixes that:

- Keep following the **recorded trace** where it still makes sense?
- Switch to a **declared policy** (“everyone plays honest after a deviation”)?
- Treat some moves as **ending** the player’s part of the game?

- **Layman:** after you rewind and try another move, who plays which cards next?
- **Sew default:** largely **trace-following**—replay the rest of the logged events unless the deviation makes them invalid.

A regret number is only meaningful **relative to this policy**. Change the policy, the regret can change.

### 4. Strategy profile (assumed styles of play)

A **strategy profile** is a labelled set of assumptions: “buyer acts like policy X, resolver like policy Y.” It does not have to be AI; it is documentation of **what kind of behaviour you treat as normal** when interpreting counterfactuals.

- **Layman:** the character sheet for each role (“honest resolver”, “rational buyer”).
- **Sew example:** `:honest-resolution-v1` with per-role policy keywords in the scenario’s `:spe-config`.

The trace shows one history; the profile says what you *claim* players were trying to do in equilibrium terms.

### 5. The trace (recording of what happened)

You need a **step-by-step recording**: who acted, what they did, and ideally a **snapshot of the world before and after** each important step.

- **Layman:** the match replay or security camera footage.
- **Sew example:** deterministic scenario fixtures replayed through `contract_model/replay`.

No trace → nothing to compare “chosen move” against “alternatives.”

### 6. Which moments count as decisions

Not every log line is strategic. The checker focuses on **decision nodes**—moments where a player had a real choice that could change payoffs (raise dispute, escalate, execute resolution, etc.).

- **Layman:** only pause the film at points where someone could have played a different legal move.
- **Sew example:** a fixed set of action names; others are ignored for SPE-style checks.

### 7. Tolerance (epsilon and regret threshold)

Real systems have rounding, fees, and noise. **Epsilon** says: “ignore differences smaller than this.” **Regret threshold** says how much better an alternative must be to count as a meaningful deviation.

- **Layman:** “close enough” and “big enough to matter.”
- **Sew example:** `:epsilon-abs`, `:epsilon-rel`, `:regret-threshold` inside `:spe-config` on the theory block.

### 8. How far you search (bounds)

Exploration is capped: max alternatives per node, max deviation depth, optional tree expansion on or off, forward vs **backward induction** mode for multi-step games.

- **Layman:** you are not solving every possible game to the end of time—you are doing a **bounded** search.
- **Sew example:** `:max-alternatives-per-node`, `:enable-tree-expansion?`, `:evaluation-mode :forward` or `:backward-induction`.

### Quick checklist

Before trusting a subgame result, ask:

| Question | If undefined… |
|----------|----------------|
| What moves are legal here? | Alternatives are guesswork. |
| How do we score the player at the end? | “Better move” has no number. |
| What happens after a hypothetical deviation? | Regret is not comparable. |
| What recording are we analysing? | No baseline “what they did.” |
| Is this moment public or private information? | You may need an information-set label, not a proper subgame. |

---

## What “solution-space exploration” means in practice

“Solution space” is the set of **stable, self-consistent outcomes** the mechanism could produce if everyone optimised their payoffs. The simulator explores that space in **layers**, each with different scope:

| Layer | What is explored | Layman analogy |
|--------|------------------|----------------|
| **Counterfactual branches** | At key decision nodes, legal alternative actions and where they lead | “What if the resolver had deferred instead of ruling now?” |
| **Strategic tree expansion** (optional) | Dynamically enumerate protocol-legal actions and fork replay for each | “Try every allowed move from this board position” |
| **Continuation policy** | After a deviation, how do others behave? (e.g. follow trace, declared honest policies) | “If I cheat here, do we assume others keep playing as in the recording?” |
| **Parameter sweeps (Monte Carlo)** | Thousands of runs varying fees, detection rates, coalition size, etc. | “Across many market conditions, is honesty still the money-maximising strategy on average?” |
| **Deterministic scenarios** | Named attack paths and invariant checks | “Does this specific scam sequence break a safety rule?” |

Together:

- **Proper-subgame work** is **local and structural**: “At this public decision, was the move optimal among legal alternatives?”
- **Monte Carlo work** is **statistical and economic**: “Across many random worlds, do bad strategies pay less than good ones?”

Neither alone is sufficient; they answer different questions. See [SYSTEM_OVERVIEW.md](../SYSTEM_OVERVIEW.md) for the two-engine framing.

---

## How exploration actually runs (conceptually)

When the subgame counterfactual evaluator hits a strategic node (e.g. raise dispute, escalate, execute resolution):

1. **Classify** the node: proper subgame, information-set, or skip.
2. **Enumerate alternatives**—either from a small fixed menu or, when tree expansion is on, from everything the protocol reports as legal.
3. **Fork** from the pre-action snapshot; apply one alternative; **continue** the scenario under the continuation policy.
4. **Score** terminal utility for the actor (money in stakes, claimable balances, bonds, etc., per a declared utility spec).
5. **Compute regret**: best alternative minus what was chosen.
6. **Report coverage**: how many proper subgames were checked, how many branches were inconclusive, and any **counterexamples** (structured records of profitable deviations).

Exploration is **deliberately bounded**: depth limits, caps on alternatives per node, epsilon thresholds so tiny numerical differences do not fail a run. That keeps runtime manageable and avoids pretending the entire game tree was solved.

---

## What you can and cannot conclude

**You can reasonably say:**

- At checked **proper subgames**, we did not find a materially profitable one-step deviation under the stated continuation policy and utility model.
- We found a **counterexample** at node X: action A would have beaten action B by amount Y.
- This node was **not** checked as a proper subgame because of private information or missing state.

**You should not say:**

- “The protocol is subgame perfect in the mathematical sense for all players and all histories.”
- “Every possible attack is unprofitable” (Monte Carlo is statistical; replay is only as good as the scenarios and branches you explore).
- “Buyers always act optimally when escalating” without modelling private evidence.

The honest label is **bounded equilibrium evidence**: a structured search over part of the strategy/outcome space, with explicit gaps where information or compute limits apply.

---

## A single analogy to tie it together

Treat the protocol like a **board game with money**:

- **Deterministic scenarios** ask: “If someone plays this exact cheating line, does the rules engine catch it?”
- **Monte Carlo** asks: “If we shuffle the economy and player types thousands of times, does cheating still lose on average?”
- **Proper-subgame exploration** asks: “At this public moment in the game, was the move they played the best among the moves the rules allowed—or did they leave money on the table (or on the table for an attacker)?”

**Proper subgame** = a fair restart point where everyone shares the same visible board.

**Solution-space exploration** = systematically visiting alternative moves and, at a higher level, alternative economic worlds, to see whether “honest play” is actually a stable solution rather than just the path that happened to be recorded.

---

## Using the same machinery for a different game (Backgammon example)

The **replay engine and subgame checker are protocol-agnostic**. Sew is the main worked example wired in today. To analyse another sequential game—say **Backgammon between two players**—you reuse the **framework** and replace the **game-specific** parts.

Think of three layers:

```text
  [ Your game rules ]     ← you write this (Backgammon adapter)
  [ Replay + fork ]       ← already in contract_model/replay
  [ Subgame / SPE check ] ← already in scenario/subgame_counterfactual
```

### What Backgammon would need (conceptually)

| Piece | Backgammon intuition | What you’d implement |
|--------|----------------------|----------------------|
| **World state** | Board, bar, borne-off counts, whose turn, dice (if known), match score, doubling cube | A map the adapter updates each ply |
| **Rules / adapter** | Legal moves from a position; reject illegal plays | `init-world`, `dispatch-action`, invariants (“no checker off bar illegally”) |
| **Legal moves list** | All dice rolls / moves allowed now | `available-actions` for tree expansion |
| **Trace** | Record of each ply with snapshots | Scenario fixture → replay produces `raw-trace` |
| **Payoff** | Win (+1), loss (−1), money wager, or match points | Utility spec reading terminal world |
| **Decision nodes** | “Player A to move after dice 3-5”, “double offered”, etc. | Tag which actions are strategic in your classifier |
| **Proper vs private** | Board is public → most turns are **proper subgames** | Classifier marks info-set only where you model hidden intent (e.g. cube timing without modelling risk preference) |
| **Continuation** | After “what if they moved 8/5 instead?” play out rest of recorded line or policy | Same `:continuation-policy` idea as Sew |

You would **not** copy Sew’s escrow, bonds, or resolver slashing. You **would** copy the pattern: adapter + traces + utility + optional `:spe-config` on a theory block.

### Minimal steps for a new game (checklist)

1. **Implement a simulation adapter** (`protocols/protocol.clj`): world init, dispatch, invariants, snapshots, and optionally `available-actions`.
2. **Define scenarios** as ordered events (like a game transcript).
3. **Run replay** so each step has a world snapshot in the trace.
4. **Declare utility**—e.g. `+1` if player A wins the match, `0` draw, with version string so results stay comparable.
5. **Declare continuation policy and strategy profile** in config so reviewers know the assumptions.
6. **Point the equilibrium layer** at your validators (or reuse generic ones) if you want `:pass` / `:fail` labels in reports.
7. **Write plain scenarios** first (one obvious blunder, one optimal line) to sanity-check regret goes the right way.

### What stays the same vs what you replace

| Reuse as-is | Replace or add for Backgammon |
|-------------|-------------------------------|
| Replay, fork-from-snapshot, resume continuation | Backgammon state machine |
| Subgame counterfactual evaluator (regret, epsilon, coverage) | Node classifier for “strategic ply” vs bookkeeping |
| Concept of proper subgame / information set | Rules for when hidden info matters (if any) |
| Fixture runner, invariant pattern | Backgammon-specific invariants (e.g. checker count conserved) |
| Sew escrow economics, dispute FSM, XTDB telemetry | Your payoff and roles (two players, no resolver) |

### Realistic expectations

- You get **bounded evidence**, not a proof that Backgammon—or Sew—is solved.
- A two-player perfect-information game is **simpler** than Sew in some ways (public board) and **harder** in others (huge branching factor); bounds and `available-actions` matter more.
- Start with **one recorded game** and **one decision** (“was this move best among legal moves after this dice roll?”). Expand once that works.

For contributor-oriented adapter boundaries, see [framework-boundaries.md](../architecture/framework-boundaries.md).

---

## Related docs

| Doc | Audience |
|-----|----------|
| [SYSTEM_OVERVIEW.md](../SYSTEM_OVERVIEW.md) | Two engines (replay vs Monte Carlo) |
| [subgame-validation.md](../testing/subgame-validation.md) | Tree expansion and `spe-config` (technical) |
| [framework-boundaries.md](../architecture/framework-boundaries.md) | What is reusable vs Sew-specific |
| [ADDING_GAME_THEORETIC_VALIDATION.md](../testing/ADDING_GAME_THEORETIC_VALIDATION.md) | Adding new equilibrium checks |
