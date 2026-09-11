# Evidence DAG Overview

## Purpose

The evidence DAG is the canonical researcher-facing evidence structure in PRF.
It records execution as immutable, content-addressed nodes with explicit parent
relationships, evidence hashes, result status, provenance, attestations, and
policy-filtered presentation output.

The DAG exists to answer four questions:

1. What exactly ran?
2. What evidence did it produce?
3. What earlier evidence does it depend on?
4. Can another runner, reviewer, or researcher verify the same chain of facts?

The canonical implementation is `resolver-sim.evidence.node`. The separate
`resolver-sim.forensic.execution-dag` output is a run-planning artifact for
scenario-run metadata. It is not the canonical researcher-facing evidence DAG.

## Core Model

Each evidence DAG node is an immutable execution record. A node contains:

- `:execution` metadata: execution id, execution kind, runner, registry hash,
  evidence policy id, and evidence policy hash.
- `:result` metadata: status and policy-applied summary.
- `:evidence` hashes: canonical hashes for inputs and outputs.
- `:parent-hashes`: explicit links to predecessor evidence nodes.
- `:bootstrap-roots`: approved external parents for imported roots or replay
  roots.
- `:attestations`: records that bind claims or external approval to the node.
- `:extensions`: versioned extension data for future protocol-specific or
  forensic features.

The node hash is computed over the canonical node projection. Volatile or
display-only fields are excluded from the node hash:

- `:node-id`
- `:node-hash`
- `:timestamp`
- `:policy-output`

This preserves a stable evidence identity while still allowing wall-clock
timestamps and researcher-specific presentation views.

## Relationship To The Bundle Root

The bundle root is the run-level commitment. It records the run request,
registry snapshot, execution environment, execution summary, overview hash, and
the evidence DAG root:

```clojure
{:bundle/schema-version "bundle-root.v1"
 :run/request           {...}
 :registry/snapshot     {...}
 :execution/summary     {...}
 :dag/root-node-hash    "sha256:..."
 :overview/hash         "sha256:..."
 :bundle/hash           "sha256:..."}
```

The DAG root is the most recent node in the validated evidence DAG that has no
children in the local collection. Bundle validation requires the root hash to
resolve to a valid node and the node collection to pass DAG validation.

## Forensic-Grade Features

The DAG is designed for forensic review rather than convenience logging.
Important forensic properties include:

- Content-addressed identity: node identity is derived from canonical content.
- Self-referential hash discipline: hash fields are excluded while computing
  the hash, then set to the resulting value.
- Explicit parent links: every dependency is represented as a hash edge.
- Acyclic validation: node collections must not contain cycles.
- Parent resolution: parent hashes must resolve locally or be declared as
  bootstrap roots.
- Registry binding: nodes carry registry and policy hashes from the time of
  execution.
- Evidence hash separation: input and output hashes are recorded as evidence
  commitments, not opaque prose.
- Bundle-root binding: the run-level bundle commits to the evidence DAG root.
- Attestation support: claims and external approvals can point at claim results
  or evidence nodes.
- Timestamp and signature compatibility: the surrounding forensic chain can
  sign registry hashes, write final cursors, and anchor hashes with RFC 3161
  timestamp tokens.

Forensic-grade status is broader than the DAG alone. The run also depends on
registry verification, signatures, cursor verification, timestamp verification,
strict validation, and a clean forensic workspace boundary.

## Researcher-Friendly Features

The DAG is also designed to be navigable by researchers who need to inspect a
specific result without reading the entire evidence corpus.

Researcher-facing support includes:

- Short hashes for human-scale references.
- `build-dag-index`, which groups nodes by hash, parent, child, execution id,
  status, root, and leaf.
- `show-node`, which prints a compact summary for one node.
- `trace-node`, which follows a node back through its ancestors.
- `list-roots`, which shows entry points into the validated DAG.
- `list-failures`, which isolates failing and error nodes.
- `dag-summary`, which returns root nodes, failure nodes, status counts,
  execution-id counts, orphan counts, and failure paths.
- `:policy-output`, which allows presentation-specific output without changing
  the canonical node hash.

This supports common review workflows:

- Start from a bundle root and resolve the DAG root.
- Find failed or error nodes.
- Trace a failed node back to the evidence it depends on.
- Compare the same execution id across runs.
- Separate raw integrity checks from derived semantic summaries.

## Derived Research Views

Some researcher-facing artifacts are derived from the canonical evidence DAG.
Examples include mechanism persistence indexes, scenario matrices, summaries,
claim reports, and visual evidence packs.

These artifacts are useful because they answer semantic questions such as:

- Which mechanisms were exercised?
- Which scenarios passed, failed, or were not applicable?
- Which claims or invariants support a mechanism-level conclusion?
- Which evidence path led to a counterexample?

Derived artifacts must remain downstream of the DAG. They may reference node
hashes, episode paths, claim results, attestations, and bundle hashes, but they
must not replace or reinterpret the canonical DAG root.

## Canonicality Boundaries

The evidence DAG should stay narrow and audit-friendly.

Canonical DAG nodes are:

- immutable execution records
- hash-addressed protocol artifacts
- linked by explicit parent hashes
- validated by node and DAG validation rules

Canonical DAG nodes are not:

- debug logs
- notebook output
- report prose
- private researcher notes
- mechanism summary tables
- benchmark-local presentation fields
- the run-plan `forensic/execution-dag.json` artifact

This boundary matters because the same evidence DAG must support multiple
views: forensic verification, researcher navigation, benchmark reporting,
mechanism persistence, and public evidence presentation.

## Validation Expectations

A valid evidence DAG collection must satisfy:

- every node has required fields
- every node has schema version `1`
- every node hash recomputes correctly
- every `:node-id` equals `:node-hash`
- every parent hash resolves locally or through `:bootstrap-roots`
- every node has input and output evidence hashes
- every attestation field is structurally valid
- the graph is acyclic

When the DAG is referenced by a bundle root, validation also requires:

- `:dag/root-node-hash` is present
- the root hash resolves to a valid node
- the full node collection validates as a DAG

## Why This Matters

The evidence DAG turns simulation output into a reviewable evidence object. A
researcher can begin with a bundle hash, resolve the DAG root, navigate to the
relevant execution node, inspect its evidence hashes, follow its parents, and
compare that structure against claims, attestations, and derived reports.

That gives PRF a useful separation of concerns:

- The DAG protects integrity.
- The bundle root protects run identity.
- Claims and attestations express reviewable assertions.
- Derived reports make the evidence understandable.
- Researcher views make the evidence navigable without weakening the canonical
  hash model.

## References

- `src/resolver_sim/evidence/node.clj`
- `docs/architecture/EVIDENCE_DAG_ARCHITECTURE.md` — Architecture doc (lifecycle, topology, validation, persistence, bundle integration)
- `docs/specs/DAG_NODE_VALIDATION_SPEC_V1.md`
- `docs/specs/evidence/EVIDENCE_NODE_SPEC_V1.md`
- `docs/specs/RUN_BUNDLE_ROOT_SPEC_V1.md`
- `docs/architecture/EVIDENCE_CHAIN_ARCHITECTURE.md`
- `docs/architecture/FORENSIC_EVIDENCE.md`
- `docs/specs/MECHANISM_PERSISTENCE_SPEC_V1.md`
