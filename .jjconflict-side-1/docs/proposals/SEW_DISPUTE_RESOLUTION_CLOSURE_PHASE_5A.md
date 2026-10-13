# SEW Dispute Resolution Closure: Phase 5A Feasibility

## Decision

Phase 5B may define a derived dispute-resolution closure only as a
post-settlement reconstruction artifact. It must not add a closure root to a
semantic transition, candidate post-settlement world, settlement identity, or
the existing V1 terminal reservation.

The existing `:consensus-terminal-reservation` and
`:terminal-evidence-publication` patterns provide the required outer
publication boundary. They are not presently integrated with the
high-assurance dispute-ruling path, and their V1 schemas do not contain a
dispute-resolution closure root. Phase 5B must therefore introduce a new,
strict envelope version or an equivalent external immutable binding rather
than change either V1 artifact in place.

## Existing Semantic Facts

The terminal settlement path already exposes these pre-publication facts:

| Fact | Existing source | Root or identity |
| --- | --- | --- |
| Settlement effect | `sew/lifecycle.clj` | `:settlement/root` |
| Held effects | settlement artifact | `:settlement/held-adjustment-set-root` |
| Terminal custody projection | `sew/terminal_state_snapshot.clj` | `:snapshot/hash` |
| Reconstruction transition | `sew/reclaimed_reconstruction.clj` | `:operation-root`, `:before-root`, `:effects-root`, `:after-root` |
| Candidate-world transition boundary | `sew.clj` terminal reservation | `:sew/world-before-root`, `:sew/world-after-root` |
| Terminal publication | `terminal_evidence_publication.clj` | `:terminal/hash` to `:publication/hash` mapping |

`settlement-identity` is deliberately upstream: it commits workflow, token,
direction, filled amount, and recipient. It must remain independent of
post-settlement state, finality, evidence, closure, or publication roots.

The terminal-state snapshot is deliberately limited to held custody and
force-authorisation closure. It excludes escrow terminal state,
`:sew/settlements`, claimables, and ruling lineage. It is useful as one
independent closure input, but cannot be reinterpreted as the complete
semantic post-settlement state root.

## Required Ordering

The Phase 5B construction must preserve this direction:

```text
state before + authenticated ruling + derived finality
  -> semantic settlement effect / transition
  -> post-settlement semantic state
  -> dispute-resolution closure
  -> authoritative terminal envelope binding closure root
  -> terminal evidence publication
```

The closure may reference existing authoritative inputs, including the ruling,
authentication verification, finality, settlement effect, and post-settlement
state. The envelope may reference the closure. No earlier node may reference
the closure or envelope.

## Cycle Checks

The following designs are rejected:

1. `state-after-root` commits `closure-root` while the closure commits
   `state-after-root`.
2. A semantic transition root commits `closure-root` while the closure commits
   that transition root.
3. `:settlement/root` commits a closure, post-state, terminal-envelope, or
   publication root.
4. A terminal reservation commits its publication hash, or a publication is
   incorporated into the terminal candidate world.
5. Phase 5B extends the existing V1 terminal reservation or terminal evidence
   publication shape in place. Both self-hash their complete schemas, so this
   would change their established contracts rather than add an outer binding.

## Phase 5B Entry Criteria

Do not define `prf/dispute-resolution-closure.v1` until all of the following
are selected from existing, reconstructable facts:

1. One terminal semantic transition/effect root for the high-assurance
   release/refund path.
2. One post-settlement semantic state root that excludes closure and envelope
   publication fields.
3. One external authoritative terminal-envelope version or immutable binding
   that commits `:dispute-resolution/closure-root` after closure derivation.
4. A reconstruction procedure that validates every chosen root without
   executing a second settlement engine.

Refusal, expiry, successor/failover, and `:appeals-exhausted` remain outside
this design because their complete authoritative transition facts are not part
of the current Phase 4B terminal path.
