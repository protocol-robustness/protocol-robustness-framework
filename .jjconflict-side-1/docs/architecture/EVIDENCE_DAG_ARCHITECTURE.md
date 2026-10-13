# Evidence DAG Architecture

## 1. Role

The evidence DAG is the canonical integrity structure for evidence in PRF. It
records each execution unit as an immutable, content-addressed node, links nodes
through explicit parent hash references, and anchors the full graph to the
bundle root and evidence chain.

The DAG exists to answer four questions:

1. What exactly ran?
2. What evidence did it produce?
3. What earlier evidence does it depend on?
4. Can another runner, reviewer, or researcher verify the same chain of facts?

The canonical implementation is `resolver-sim.evidence.node`. It is separate
from the linear evidence chain (`resolver-sim.evidence.chain`) — the chain
provides a total sequence of evidence records within a scenario, while the DAG
provides a directed acyclic graph of execution nodes across a run.

## 2. Two DAG Systems

The codebase contains two DAG abstractions with distinct purposes:

### 2.1 Canonical Evidence DAG (`resolver-sim.evidence.node`)

The researcher-facing evidence DAG. Nodes are content-addressed, immutable,
and linked by parent hash references. This is the canonical abstraction.

| Property | Detail |
|----------|--------|
| Purpose | Immutable evidence record for researchers |
| Storage | Individual `.edn` files in `evidence-nodes/` |
| Identity | Content-addressed (`:node-id == :node-hash == :content-hash`) |
| Parent model | Hash-linked parents + typed bootstrap roots |
| Navigation | `build-dag-index`, `show-node`, `trace-node`, etc. |
| Registry | Artifact registry via `resolver-sim.evidence.chain` |

### 2.2 Forensic Execution DAG (`resolver-sim.forensic.execution-dag`)

A separate run-planning artifact for scenario-run metadata. Created before
execution (plan nodes with empty output hashes), populated during the run,
written as a single JSON file. It is NOT the researcher-facing evidence DAG.

| Property | Detail |
|----------|--------|
| Purpose | Run-planning metadata |
| Storage | Single `execution-dag.json` per run |
| Identity | Typed plan nodes with `:scenario-run` type |
| Parent model | Typed edges with `:from` / `:to` |
| Navigation | None (flat JSON file) |

The evidence DAG overview explicitly documents this boundary
(`docs/evidence/EVIDENCE_DAG_OVERVIEW.md`).

## 3. DAG Topology

### 3.1 Node Types

The evidence DAG supports multiple execution types, identified by
`:execution-id`:

| Type | Purpose |
|---|---|
| `:execution/replay` | Deterministic replay of one scenario trace |
| `:execution/pro-rata-allocation` | Pro-rata computation chain |
| `:execution/attestation` | Attestation creation |
| `:evidence/commitment-root` | Post-hoc anchor connecting the DAG root to evidence chain |

New types are added by registering an execution-id in `execution-registry`.

### 3.2 Parent Relationship Model

Each node carries two fields for parent references:

```clojure
{:parent-hashes ["sha256:parent-node-hash-1" ...]  ;; direct DAG parents
 :bootstrap-roots ["evidence-chain:sha256:cursor" ...]}  ;; external anchors
```

**`parent-hashes`** — References to other evidence DAG nodes. These must
resolve to a known node in the collection or be declared in `bootstrap-roots`.

**`bootstrap-roots`** — External anchors that are not themselves evidence DAG
nodes. Supported formats:

| Format | Example | Purpose |
|--------|---------|---------|
| `sha256:<64-hex>` | `sha256:abc...` | Content-addressed bootstrap |
| `evidence-chain:sha256:<64-hex>` | `evidence-chain:sha256:def...` | Evidence chain cursor hash |

This allows the DAG to reference external integrity structures (like the
evidence chain cursor) without requiring corresponding DAG nodes.

### 3.3 Typical DAG Structure

A single-scenario run produces a minimal DAG:

```
┌──────────────────────────────────────┐
│  commitment-root node                │
│  execution-id :evidence/commitment   │
│  parent-hashes: ["sha256:exec-hash"] │
│  bootstrap-roots: ["evidence-chain:  │
│    sha256:cursor-hash"]              │
│  outputs.bundle-root-hash: sha256:.. │
└──────────┬───────────────────────────┘
           │ parent-hashes[0]
┌──────────▼───────────────────────────┐
│  execution node                      │
│  execution-id :execution/replay      │
│  parent-hashes: []                   │
│  (root node — no DAG parents)        │
└──────────────────────────────────────┘
```

A multi-scenario multi-suite run produces a richer DAG:

```
                         ┌───────────────┐
                         │ commitment-   │
                         │ root          │
                         │ (DAG root)    │
                         └───────┬───────┘
                                 │ parent
          ┌──────────────────────┼──────────────────────┐
          │                      │                      │
┌─────────▼─────────┐  ┌────────▼────────┐  ┌──────────▼─────────┐
│ execution/replay   │  │ execution/replay │  │ execution/replay   │
│ suite-1/scenario-1 │  │ suite-1/scenario-2│ │ suite-2/scenario-1 │
│ root node          │  │ root node        │  │ root node          │
└───────────────────┘  └─────────────────┘  └────────────────────┘
```

Each scenario replay produces a root node (no DAG parents). The commitment
root links them as parents. All nodes share the evidence chain cursor as
bootstrap root.

## 4. Node Lifecycle

### 4.1 Creation

Nodes are created outside the replay engine by the scenario runner
(`resolver-sim.io.scenario-runner`). The `with-execution-node+` macro
handles the full lifecycle:

```clojure
(with-execution-node+ exec-spec
  (fn [] (execute-dispatch! ...)))
```

The macro:
1. Builds a node spec from the execution spec
2. Runs the thunk
3. On success: calls `status-fn` / `outputs-fn` to produce the node map
4. On exception: catches, emits an `:error` status node, rethrows
5. Calls `emit-execution-node!` to persist and register

### 4.2 Emission

`emit-execution-node!` performs:

1. **`build-execution-node`** — Creates the node map with canonical hashing:

   ```
   base-map = (merge execution-spec
                     {:node-id nil
                      :node-hash nil
                      :content-hash nil
                      :record-hash nil
                      :timestamp nil
                      :policy-output nil
                      ...})
   node-hash = canonical-hash(base-map)
   content-hash = node-hash  (aliased for all three: node-id, node-hash, content-hash)
   record-hash = sha256(node-hash + "|" + timestamp)
   ```

   Hash stability: `:node-hash` is computed from a projection that excludes
   `:node-id`, `:node-hash`, `:timestamp`, and `:policy-output`. Wall-clock
   and display changes do not affect node identity.

2. **`persist-execution-node!`** — Validates the node, writes `.edn` file,
   registers in the evidence chain:

   ```
   validate-node → write-node-artifact! → chain/register-additional-artifact!
   ```

3. **`register-node!`** — Adds to the in-memory `*node-registry*` atom for
   run-scoped access.

### 4.3 Post-Run Anchor

After all scenarios complete, the runner emits a commitment root:

```clojure
(emit-execution-node!
  {:execution-id :evidence/commitment-root
   :parent-hashes ["sha256:<exec-hash>"]
   :bootstrap-roots ["evidence-chain:sha256:<cursor>"]
   :outputs {:bundle/root-hash "sha256:<bundle-hash>"}})
```

This node is the DAG root — the entry point for external verification.

### 4.4 Thread Isolation

Each scenario run operates in a fresh binding scope:

```clojure
(ev-node/with-fresh-registry
  (chain/with-fresh-registry
    (chain/with-fresh-chain-cursor
      (execute ...))))
```

`with-fresh-registry` binds a new `*node-registry*` atom in a `binding`
form, restoring the outer registry on exit. This ensures scenario isolation.

### 4.5 Error Handling

`with-execution-node` wraps the thunk in try/catch. On exception, it emits
an `:error` status node before rethrowing. The inner emission itself is also
protected with a nested try/catch, so a node is always produced regardless
of execution outcome.

## 5. Chain to DAG Bridge

The evidence chain and evidence DAG are complementary structures that connect
through the commitment root:

```
               EVIDENCE CHAIN                      EVIDENCE DAG
      ┌──────────────────────────┐      ┌──────────────────────────┐
      │ scenario 1: seq 1..N     │      │ commitment-root node     │
      │ scenario 2: seq 1..M     │      │   bootstrap-roots ───────┤──→ cursor hash
      │ ...                      │      │   parent-hashes ────────┤──→ exec nodes
      │ aggregate-cursor.json    │      │                          │
      │   └─ cursor/heads[].hash───────→│   (DAG root)             │
      │ evidence-registry.json   │      └──────────────────────────┘
      └──────────────────────────┘
```

**Evidence chain** — Linear, sequentially numbered evidence records within
each scenario. The aggregate cursor records the chain head for every scenario.

**Evidence DAG** — Directed acyclic graph of execution nodes across the run.
Each execution node is a content-addressed record of what executed and what it
produced.

**Bridge** — The commitment root node carries the evidence chain's aggregate
cursor hash in `bootstrap-roots`. The chain carries the DAG's node artifacts
through `chain/register-additional-artifact!`. Together, they form a
cross-linked integrity structure:

- Chain → DAG: the chain cursor's heads reference scenario chain hashes
- DAG → Chain: the commitment root's bootstrap roots reference chain cursor
- Both → Bundle: the bundle root records both the DAG root and chain summary

## 6. Persistence Model

### 6.1 Storage Layout

```
{artifact-dir}/
  evidence-nodes/
    node-{short-hash}.edn     ← one file per node
    node-{short-hash}.edn
    ...
```

Each node is a single EDN file, written via `pr-str` after canonicalization
with `canonical-disk-value`. Short hashes are 12-character hex prefixes,
sufficient for directory-level disambiguation.

### 6.2 Registration

After writing the EDN file, the node is registered in the evidence chain:

```clojure
(chain/register-additional-artifact!
  {:id (str "evidence-node:" short-hash)
   :kind :evidence-node
   :evidence-hash content-hash
   :artifact-hash node-hash
   :path relative-path})
```

This links DAG nodes into the chain's artifact registry, making them
discoverable alongside other evidence artifacts.

### 6.3 Loading

- **In-memory**: `*node-registry*` atom, populated via `register-node!` during
  emission.
- **From disk**: `read-persisted-node` reads a single `.edn` file.
  `persisted-node-artifact-paths` discovers all `.edn` files in a directory.
- **Verification**: `verify-persisted-node-artifacts!` reads all persisted
  nodes, validates each node individually, and validates the full DAG.

### 6.4 Legacy DAG Persistence

The forensic execution DAG is written to `results/runs/{run-id}/execution-dag.json`
and included in evidence packs as `execution-dag.json`. It is a single JSON
file, not a collection of EDN files.

## 7. Navigation Architecture

The DAG navigation system (`node.clj`) builds an in-memory index that enables
efficient researcher queries without scanning all nodes for each operation.

### 7.1 DAG Index (`build-dag-index`)

The index is a map with the structure:

```clojure
{:dag-index/version "evidence-dag-index.v0"
 :dag-index/nodes-by-hash        {full-hash → summary-entry}
 :dag-index/children-by-parent   {parent-hash → [child-hashes]}
 :dag-index/parents-by-child     {child-hash → [parent-hashes]}
 :dag-index/roots                [root-node-hashes]
 :dag-index/leaves               [leaf-node-hashes]
 :dag-index/by-execution-id      {execution-id → [node-hashes]}
 :dag-index/by-status            {:pass [hashes] :fail [hashes] :error [hashes]}
 :dag-index/short-hashes         {12-char-prefix → full-hash}
 :dag-index/summary              {:node-count N :root-count N
                                  :leaf-count N :failure-count N
                                  :error-count N :orphan-count N}}
```

### 7.2 Navigation Functions

| Function | Purpose |
|----------|---------|
| `build-dag-index` | Build full index from a node collection |
| `show-node` | Print summary for a hash or short prefix |
| `trace-node` | Follow parent chain from a node to its roots |
| `list-roots` | List all root nodes (entry points) |
| `list-failures` | List all failed and error nodes |
| `dag-summary` | Returns aggregate summary with BFS failure paths |

### 7.3 Common Researcher Workflows

1. **Start from bundle root** — resolve `:dag/root-node-hash`, then
   `show-node` on it.
2. **Find failures** — `list-failures`, then `trace-node` on each failure
   to see its ancestor chain.
3. **Compare runs** — `dag-summary` returns comparable summaries across
   runs by execution-id.
4. **Trace a claim** — find nodes by execution-id via `by-execution-id`,
   check their attestation references.

## 8. Validation Architecture

### 8.1 Single-Node Validation (`validate-node`)

| Check | Condition |
|---|---|
| Required fields present | `:schema-version`, `:node-id`, `:node-hash`, `:parent-hashes`, `:execution`, `:result`, `:evidence` |
| Schema version | `:schema-version == 1` |
| Status valid | `:result :status` is one of `:pass`, `:fail`, `:error` |
| Hash matches | `(compute-node-hash node) == (:node-hash node)` |
| ID matches hash | `:node-id == :node-hash` |
| Parents resolvable | Every `:parent-hash` is known or in `:bootstrap-roots` |
| Inputs/outputs present | `:evidence :inputs-hash` and `:outputs-hash` are non-empty |

### 8.2 DAG-Level Validation (`validate-node-dag`)

| Check | Condition |
|---|---|
| Every node valid | Each node passes single-node validation |
| All parents known | Every parent hash exists in the collection or is a bootstrap root |
| No cycles | The directed graph is acyclic (DFS cycle detection) |

`validate-node-dag` accepts `:strict-dag?` (default true). In strict mode,
it also checks for a single root and no duplicate node hashes.

### 8.3 Artifact Verification (`verify-persisted-node-artifacts!`)

Verifies persisted files against chain entries:

| Check | Condition |
|---|---|
| File exists | EDN file at expected path |
| Re-reads | EDN deserialization succeeds |
| Re-validates | Re-read node passes `validate-node` |
| Hash recomputes | `compute-node-hash` matches recorded `:node-hash` |
| Artifact hash matches | Chain entry `:artifact/hash` matches |
| File SHA-256 matches | File content hash matches chain entry |

### 8.4 Bundle Root Validation

The bundle root stores `:dag/root-node-hash` pointing to the DAG root.
Validation requires:

1. `:dag/root-node-hash` is present and non-nil
2. The root node hash resolves to a valid node
3. The full node collection passes DAG validation

## 9. Bundle Root Integration

The bundle root (`resolver-sim.run.bundle-root`) is the top-level artifact
that seals a complete run. The evidence DAG connects to it during
enrichment:

```clojure
(defn build-enriched-bundle-root [bundle-root execution-node source-provenance]
  (merge bundle-root source-provenance
    (when execution-node
      {:execution/node-hash (:node-hash execution-node)
       :execution/content-hash (:content-hash execution-node)
       :execution/record-hash (:record-hash execution-node)
       :dag/root-node-hash (:node-hash execution-node)})))
```

The bundle root captures:
- `:dag/root-node-hash` — DAG root hash (commitment root node)
- `:execution/node-hash` — DAG node hash
- `:execution/content-hash` — Same as node-hash
- `:execution/record-hash` — Node hash + timestamp binding

Validation in `resolver-sim.run.criteria` requires `:dag/root-node-hash` to
be present. Missing it produces `:missing-dag-root-node-hash`.

## 10. Integration Points

| System | Integration |
|--------|------------|
| Evidence chain | Nodes registered via `chain/register-additional-artifact!`; commitment root carries chain cursor as bootstrap root |
| Bundle root | `:dag/root-node-hash` in bundle root; validated by criteria |
| Attestation DAG | Nodes reference attestations via `:attestations ["attestation:sha256:..."]` |
| Execution registry | `:execution-id` must be a registered execution kind |
| Evidence policy | `:policy-id` must be in evidence-policy-registry; `:policy-output` is excluded from hash |
| Evidence packs | Run-plan execution-dag.json included in pack manifests |

## 11. Scaling Characteristics

The evidence DAG is designed for small-to-moderate node counts (tens to low
hundreds per run). Properties relevant to scaling:

- **Per-node files**: One EDN file per node. File count equals node count.
- **In-memory index**: `build-dag-index` loads all nodes into memory. For
  very large DAGs, the index scales with O(n) nodes in memory plus O(e)
  for edge structures.
- **Validation**: `validate-node-dag` performs a full DFS cycle check.
  Validation time is O(n + e) per run.
- **Persistence**: EDN format is human-readable but not optimized for
  partial loading. The full node set must be loaded for validation.
- **Short hashes**: 12-character hex prefixes are used for filenames. For
  very large collections, collision probability increases but remains low
  at the expected node counts.

## 12. References

- `src/resolver_sim/evidence/node.clj` — Canonical evidence DAG implementation
- `src/resolver_sim/evidence/node_test.clj` — DAG tests (hashing, validation, navigation)
- `src/resolver_sim/evidence/commitment_root_test.clj` — Commitment root pattern tests
- `src/resolver_sim/forensic/execution_dag.clj` — Run-plan execution DAG
- `src/resolver_sim/io/scenario_runner.clj` — Orchestration (DAG creation in run-and-report)
- `src/resolver_sim/run/bundle_root.clj` — Bundle root construction
- `src/resolver_sim/run/criteria.clj` — Bundle root validation criteria
- `src/resolver_sim/evidence/chain.clj` — Evidence chain (artifact registry, cursors)
- `src/resolver_sim/evidence/attestation_dag.clj` — Attestation DAG (built on evidence DAG)
- `docs/evidence/EVIDENCE_DAG_OVERVIEW.md` — Researcher-facing overview
- `docs/specs/DAG_NODE_VALIDATION_SPEC_V1.md` — Validation specification
- `docs/specs/evidence/EVIDENCE_NODE_SPEC_V1.md` — Node schema specification
- `docs/specs/RUN_BUNDLE_ROOT_SPEC_V1.md` — Bundle root specification
- `docs/architecture/EVIDENCE_CHAIN_ARCHITECTURE.md` — Evidence chain architecture
- `docs/architecture/FORENSIC_EVIDENCE.md` — Forensic-grade evidence criteria
