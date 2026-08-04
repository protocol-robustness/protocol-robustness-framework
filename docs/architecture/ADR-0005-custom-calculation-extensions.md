# ADR-0005: Custom calculation extensions via extra-paths

**Status:** Accepted (design note; implementation phased)

**Date:** 2026-08-04

**Scope:** `resolver-sim.economics.slash-distribution` and the generic
economics layering around it.

## Context

The slash-distribution engine dispatches on a small, hardcoded set of
`:method` keywords:

| Dimension | Dispatch site | Built-in methods |
|---|---|---|
| Base allocation | `compute-allocation` (`slash_distribution.clj`) | `:weighted` |
| Award amount | `compute-award-amount` | `:rate-of-gross`, `:resolved-amount` |
| Award funding | `compute-award-funding` | `:weighted-deduction` |

Each dispatch site is a `case`/`or`, mirrored by:

- policy validation (`supported-award-amount-method?`,
  `supported-allocation-method?`, `supported-funding-method?`,
  `:violation/unsupported-*-method`);
- the independent verifier (`independent-award` repeats the `case`).

A custom calculation therefore requires editing core or living outside the
content-addressed engine (e.g. the closed-form helpers in
`resolver-sim.economics.calculations`). Neither gives an auditor a committed,
replayable record of the custom calculation's inputs.

The repository already has a proven, classpath-level extension pattern:

- `deps.edn` overlays a directory via data aliases: `:paths/protocols =
  ["protocols_src" "protocols_src/test"]`, selected by `:with-sew`
  (`{:extra-paths [:paths/protocols]}`) and `:test`. User-local paths are
  supported via `:researcher/env` (`:extra-paths [:researcher/paths]`).
- `resolver-sim.protocols.registry` provides `register-extension!`,
  `bootstrap-extension!` (loads `resolver-sim.protocols.<ext>.extension` by
  convention), and lazy `get-protocol` (resolve → bootstrap → resolve).
- Reference bootstrap: `protocols_src/resolver_sim/protocols/sew/extension.clj`
  registers `"sew-v1"` at load time.

## Decisions

### 1. Delivery — new overlay directory and alias

Custom calculation extensions ship in a dedicated overlay directory
`calc_extensions/`, mirroring `protocols_src/`:

```clojure
:paths/calc-extensions ["calc_extensions"]
:with-calc-extensions {:extra-paths [:paths/calc-extensions]}
```

- Canonical tasks and CI never select `:with-calc-extensions` (same isolation
  reasoning as `:researcher/env`; see deps.edn lines 42–51). Extensions are
  opt-in per environment.
- Private/organizational extensions may additionally be delivered through the
  existing user-local `:researcher/paths` mechanism.
- The extension directory may contain its own tests; a test alias may compose
  `:paths/calc-extensions` when the extension author opts in.

### 2. Registry — core calculation registry

New core namespace `resolver-sim.economics.calculation-registry`, mirroring
`resolver-sim.protocols.registry`:

- `register-calculation!` — register an extension-map by method keyword.
- `unregister-calculation!` — remove a registration (isolated tests).
- `known-calculation-methods` — all registered method keywords.
- `resolve-calculation` — lookup with lazy bootstrap by convention:
  `resolver-sim.calc-extensions.<name>.extension` is required on demand.

Semantics preserved from today:

- An unknown method is a loud violation
  (`:violation/unsupported-amount-method` and friends), never a silent
  fallback.
- Registration is in-memory and idempotent; tests inject fixtures via an
  atom snapshot/restore pattern analogous to `with-test-registry`.

Built-in methods are registered as core entries at namespace load, so the
engine has exactly one dispatch path for every method.

### 3. Extension-map schema

Each method keyword maps to an extension-map (the "extension-map"):

```clojure
{:extension/id :custom/foo
 :extension/version 1
 :extension/kinds #{:award-amount :allocation :funding :step}
 ;; computation
 :calculation-basis (fn [ctx] basis)        ;; which base; default gross
 :calculate         (fn [ctx] result)       ;; structured calc record
 :calculate-allocation (fn [ctx] {...})     ;; allocation/funding across ids
 :rounding #{:floor}
 ;; composition
 :concatenable? bool
 ;; adapter metadata (not engine arithmetic)
 :custody-affecting? bool
 :custody/account kw | nil
 :available-actions (fn [ctx] [{:action .. :params ..}])
 :evidence/fields [...]}
```

Contract notes:

- `:calculate` returns a structured record — at minimum `:amount`,
  `:rounding-remainder`, and `:classification` — matching the shape the
  engine already commits per award (`slash_distribution.clj`,
  `calculate-scaled-share`). Built-ins share `calculate-scaled-share` as the
  single arithmetic primitive under `:rate-of-gross`.
- `:calculation-basis` decides the base a step computes from. The chosen
  basis is committed into the award `:calculation` record so calculation
  order is auditable (non-commutativity is visible, not hidden).
- Extensions are pure and content-addressed: same inputs ⇒ same outputs and
  same committed evidence.

### 4. Composition — consecutive concatenation via policy `:steps`

A policy may declare an ordered `:steps` vector. The engine reduces steps in
declared order with basis chaining: a step may declare `:basis :remaining`,
meaning its `:calculation-basis` receives the amount remaining after the
preceding step.

- A single-award policy is an implicit one-step policy; existing policies are
  unchanged and remain valid (backward compatible).
- Step order is canonicalized for hashing (deterministic; ordering-independence
  tests extend to step order).
- Each step's inputs are hash-bound per step, in the same way award
  calculations are bound today via `:distribution/calculations`.

### 5. with-bounty

Bounty is already expressed as a `:rate-of-gross` award funded by weighted
deduction (Sew default policy, `sew.economics.clj`:
`sew-default-slash-distribution-policy`). Under this ADR it becomes a
reference composite step: compute from gross → fund from
insurance/protocol → settle to a payable.

- `distribute-slashing-amount` (`economics/calculations.clj`) remains a
  parity path; a composite step must reproduce it exactly where used.
- `bounty-payable.v1` / `bounty-payable-backing.v1` artifacts are unchanged;
  the step only feeds their builders.

### 6. custody-affecting

Custody is adapter metadata, not engine arithmetic. The extension-map
declares `:custody-affecting?` and `:custody/account`.

- The application plan records per-effect
  `:effect/custody-affecting?` and `:effect/custody/account`.
- The protocol adapter (e.g. `apply_slash_distribution.clj` + `accounting.clj`)
  routes custody-affecting effects through held-custody
  (`resolver-sim.assurance.custody` / `adjust-held`); non-custody effects
  remain ordinary credits.
- The plan gains a committed `:plan/extension-effects` field (additive
  change to the `slash-distribution-application-plan.v1` hash projection;
  a schema bump is decided at implementation time).

### 7. availability / available-actions

Two senses:

- **Step eligibility**: an extension-map `:available-actions` (or
  `:available?`) predicate over context determines whether a step is active
  for the event, feeding the existing trigger/eligibility logic that includes
  or omits an award.
- **Post-application availability**: the protocol adapter's
  `available-actions` (`SimulationAdapter`, `protocol.clj`) surfaces
  obligations/claims produced by extension steps (e.g. a claimable bounty).

## Layering rules

- `src/resolver_sim/economics/*` stays protocol-agnostic and must not depend
  on extension namespaces.
- Extension namespaces live under `calc_extensions/` and must not depend on
  protocol namespaces.
- Generic economics may depend on the registry; the registry may depend only
  on core.
- Extensions are pure, deterministic, and content-addressed.

## Evidence and verification impact

- Hash projections must commit the method keyword and all extension inputs
  (per-award binding already covers `:parameter-key`, `:parameter-value`,
  `:scale`, `:rounding`, `:gross-amount`, `:rounding-remainder`,
  `:calculation-classification`).
- `verify-distribution` recomputes through the registry so extension-backed
  awards verify independently.
- An unresolved/unknown method at verification time is a loud violation
  (mirrors `missing-parameter`).

## Phases (implementation, out of scope for this ADR)

1. Registry core + bootstrap convention + unit tests (mirror
   `test/resolver_sim/protocols/registry_test.clj`).
2. Dispatch refactor in `slash_distribution.clj` (allocation/amount/funding)
   behind the registry; built-ins unchanged; existing 92 tests remain green.
3. `:steps` consecutive concatenation + basis chaining + per-step evidence
   binding.
4. Plan/adapter metadata: `:plan/extension-effects`, custody routing, and
   available-actions surfacing.
5. Reference extension (composite with-bounty step) with parity tests.

## Non-goals / open questions

- Solidity-equivalent checked-width semantics for extension arithmetic (see
  `SLASH_DISTRIBUTION_SCALED_SHARE_SPEC_V1.md`; a `mulDiv`-equivalent profile
  is a separate follow-up).
- Normalization of semantically-equal ratios across different scales
  (e.g. `500/10000` vs `5/100`); the committed pair is used, not a normalized
  fraction.
- Whether custody routing requires a `slash-distribution-application-plan.v2`
  schema bump or an additive key on v1.
- Canonical production parameter naming for the slash reward share remains
  deferred; the test-local `:test.parameter/reward-rate` key is unchanged.
