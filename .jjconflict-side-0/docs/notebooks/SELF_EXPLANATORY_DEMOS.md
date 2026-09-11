# Self-explanatory notebook demonstrations

A notebook can explain a concrete use case without making that use case part of
the framework. This distinction is especially important for the `not admitted`
material: admission and verification are reusable framework capabilities, while
the domain record, its interpretation, and the response to rejection belong to
the adopter.

## Ownership boundary

| Concern | Owner |
| --- | --- |
| Constructing and verifying evidence commitments | Framework |
| Admission boundary, configured chain, and consecutive composition validation | Framework |
| Selecting a corpus, ledger shape, amount, mutation, and domain interpretation | Notebook/adopter |
| Authorization policy, reservations, consumption/finalization, accounting, cancellation/dispute handling, and pro-rata decisions | Notebook/adopter |
| Workflow-specific follow-up after rejection | Notebook/adopter |

The framework therefore exposes generic primitives rather than prescribing a
particular operational workflow.

## Notebook-only amount example

`notebooks/demo_not_admitted.clj` is a user-supplied, notebook-only example. It
creates a small local application record, projects it into verifier-facing
evidence input, changes one submitted amount, and reruns the real verifier. The
failed integrity check demonstrates the narrow property: changing committed
evidence is detectable.

It does **not** define a canonical framework ledger, admission product, CLI
command, public product demonstration, or recovery procedure. An adopter chooses
what the amount means, which evidence is relevant, and what to do when their
policy rejects it.

Run notebook validation with:

```sh
bb test:notebooks
```

## Generic admission boundaries

`notebooks/not_admitted.clj` presents the higher-level framework boundaries:
scoped authority, an unconfigured-chain fail-closed gate, and consecutive
composition. It deliberately does not prescribe a reservation,
accounting, cancellation, dispute, or allocation workflow.

The clean-room corpus material remains separately illustrated by
`notebooks/clean_room_not_admitted.clj`; corpus selection and its rationale are
also adopter concerns.

## Framework demo retained separately

`bb demo:reorder-chain` remains an executable framework demonstration of a
generic ordering property: the same evidence in a different committed order is
not equivalent. Its assertion suite is included in:

```sh
bb demo:test
```

That CLI demo is separate from the notebook-only amount example. Keeping the two
surfaces distinct prevents the example ledger from being mistaken for framework
policy.
