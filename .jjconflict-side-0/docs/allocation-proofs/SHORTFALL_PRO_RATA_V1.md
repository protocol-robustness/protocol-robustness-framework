# Shortfall Pro-Rata V1

## Scope

`shortfall-pro-rata.v1` is the smallest semantic layer above the frozen
realized allocation and EVM allocator path. It establishes a closed scarcity
problem; it does not establish ERC-20 recipients, admit a proof, or execute a
transfer.

```text
complete candidate universe + explicit eligibility
+ authoritative available-capital fact
+ existing allocation context and admitted policy
→ prf/shortfall-context.v1
→ existing realized-allocation-statement.v1
→ frozen execution/proof/admission/allocator layers
```

## Canonical context

`prf/shortfall-context.v1` is committed under:

```text
PRF_SHORTFALL_CONTEXT_V1
```

Its committed preimage contains:

- `:shortfall/profile` — exactly `:shortfall-pro-rata.v1`;
- `:asset/id` and `:asset/unit` — V1 requires `:erc20-base-unit`;
- `:allocation-context-root` — canonical `sha256:` reference to the existing
  complete allocation context;
- `:candidates` — complete, strictly claim-ID-ordered candidate universe;
- each candidate's canonical `:claim/id`, exact requested amount, and explicit
  `:eligible` or `:ineligible` disposition;
- a namespaced ineligibility reason for every ineligible candidate;
- `:availability/source`, `:availability/root`, and exact `:available-amount`;
- the already admitted deferred largest-remainder pro-rata policy;
- optional immutable temporal/lifecycle basis reference.

The availability root is an application authority boundary. For example, an
application may reference a `pool-availability.v2` artifact. The generic
Shortfall context records the authority type and reference; it does not
reinterpret a runtime ERC-20 balance as the semantic availability fact.

## Derived values

The context does **not** duplicate these values in its root preimage:

```text
eligible requested set = candidates where eligibility == eligible
total eligible demand  = sum(eligible requested)
shortfall amount       = max(total eligible demand - available amount, 0)
```

This avoids a consistency-mismatch surface. Changing availability, candidate
eligibility, or any requested amount changes the context root and the derived
result.

## Demand, availability, and residuals

These are distinct:

```text
requested demand   — amounts in the complete candidate universe
available capital  — authority-bound amount available to this allocation
allocated amount   — existing deterministic realized fill
unfilled demand    — eligible demand minus realized fill
allocator residual — frozen execution-profile value, always zero in V1
```

For scarcity:

```text
eligible demand = 100
available       = 70
allocated       = 70
unfilled        = 30
allocator residual = 0
```

Unfilled demand is not an allocator residual.

When available capital equals eligible demand, the regular realized allocation
is deterministically all-active. When availability exceeds eligible demand,
the Shortfall context remains valid and derives zero shortage, but V1 rejects
handoff to the frozen zero-residual allocator profile. Excess capital is not
silently converted into an allocation or allocator residual.

## Eligibility boundary

Every candidate is committed, in canonical order. Therefore an omitted request
and an explicitly ineligible request have different context roots. Only
eligible candidates become the realized statement's request set; ineligible
candidates remain a factual Shortfall context record and never reach payout
execution.

## Existing realized-allocation handoff

`resolver-sim.shortfall.context/allocation-input` validates that the complete
Shortfall candidate universe exactly equals the existing allocation context's
claimant universe. It produces only the existing inputs:

```clojure
{:available ...
 :requested ...
 :policy ...
 :round-state :result-accepted}
```

`realize` then calls the unchanged deterministic partial-fill implementation
and builds the unchanged `realized-allocation-statement.v1`.

The later execution companion, SP1 proof, admission receipt, and
`ProductionProRataAllocatorV1` remain frozen and are not Shortfall logic.

## Deferred follow-up

The generic persisted realized-statement reconstruction currently needs an
independent check that request-map keys are members of its allocation-context
claimant set. Shortfall V1 prevents that ambiguity at its own handoff by
constructing both sets from one closed candidate universe. Tightening the
legacy generic reconstruction is a separate realized-statement hardening task,
not a Shortfall schema change.
