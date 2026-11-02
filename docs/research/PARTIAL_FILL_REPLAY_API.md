# Partial-fill replay API example

Run from the repository root:

```sh
clojure -M -m examples.partial-fill-replay
```

The example creates two claimants, a shared pool with ten units deposited before
round zero, and fixed public submission order. Alice receives ten units and Bob
receives zero. Each player has one declared plan; the resulting equilibrium is
within that finite action domain. The script generates ephemeral Ed25519 keys,
signs utility assumptions and state-bound grants, constructs the trace, and
verifies it through production allocation and independent reconstruction.
No external credentials or network resolver are required. Generated keys and
the modeled deposit are demonstration inputs, not real-world attestations.

For programmatic use:

```clojure
(require '[examples.partial-fill-replay :as demo]
         '[resolver-sim.yield.strategic-partial-fill :as strategic])

(let [{:keys [prepared model game opts]} (demo/build-example true)]
  ;; true selects two equally weighted recovery branches: five and ten units.
  (strategic/analyze-replayed-recovery-game-profiles
    prepared model game (assoc opts :mixed? true)))
```

The stochastic example derives Alice's expected amount utility as `15/2` using
exact arithmetic. Every branch has a separate signed state-bound grant and
trace. `:mixed? true` selects the two-player support solver; it does not enlarge
the declared strategy domain or make a sequential-equilibrium claim.

The example's `build-example` helper returns artifacts suitable for inspection.
It also returns an ephemeral permission-signing function for adversarial tests;
applications must use their own authority and explicit trust policy. Do not use
the demonstration authority as a production trust anchor.

## Assurance and required execution

| Field or option | Meaning |
|---|---|
| `:require-validated-execution? true` | Caller-outcome APIs return inconclusive; the replay API validates every trace before utilities. |
| `:require-authenticated-assumptions? true` | Requires a valid model signature under `:assumption-trust-policy`. |
| `:permission-trust-policy` | Trusts a separate state-permission authority role. |
| `:permission-sources` | Bounded local map from root to signed grant; unresolved or altered roots reject the profile. |
| `:settlement-replayed?` | All declared profiles/branches passed production and independent settlement verification. |
| `:state-dependent-permissions-verified?` | New submissions were authorized against the actual state, round and residual rights. |
| `:outcomes-authenticated?` | Remains false: replay does not independently authenticate economic deposit assumptions. |
| `:execution-evidence/root` | Binds the complete action domain, profile/branch table, model, initial state and replay evidence. |

Signed static player/action claims cannot replace state-bound grants. Existing
caller-outcome APIs can authenticate utility assumptions and static claims while
still reporting caller-supplied outcomes, no settlement replay, and no verified
state-dependent permissions. The replay API returns inconclusive for the whole
game if even one joint profile is infeasible; it never inserts zero utility or
silently drops a profile.

The wire schemas, limits and unsupported execution models are specified in
[Partial-fill strategic execution and replay v1](../specs/PARTIAL_FILL_STRATEGIC_REPLAY_V1.md).
Waterfall execution rows carry a unique `:key` and an optional shared `:bucket`;
`:fill-order` orders buckets, and fills remain attributable by row key and owner.
In weighted/capped allocation experiments, declare the actual deferral policy:
contractual residual claims do not generally preserve the original request ratio.

## Enumeration coverage

`validate-strategic-properties` remains an amount-only counterexample search.
Its `:validation-scope` fields describe the search actually performed:

| Field | Meaning |
|---|---|
| `:domain-cardinality` | Exact number of request-vector/liquidity states in the declared Cartesian bounds, before either budget. |
| `:state-policy-evaluations` | Executed state × policy combinations. |
| `:distinct-states-examined` | Unique request-vector/liquidity states visited. |
| `:max-state-policy-evaluations` | Evaluation budget; separate from the scope's state-generation cap. |
| `:complete?` | True only for exhaustive generation that evaluated every state under every selected policy. |
| `:incompleteness-reason` | `:sampling` for sampled searches; `:enumeration-or-evaluation-budget` for truncated exhaustive searches. |

Random and stratified runs never report exhaustive completion, even if sampled
states happen to cover the domain. An empty Cartesian domain is vacuously
complete, but its summary is invalid because no checks ran. A zero generation
budget over a nonempty domain is incomplete. Neither complete enumeration nor
an absence of counterexamples proves resistance outside the bounded model.
