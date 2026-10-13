# EVIDENCE_COMMITMENT_ROOT_SPEC_V1

Status: Draft V1

## 1. Purpose

Define the evidence commitment root node: a post-hoc DAG anchor emitted at the
end of a forensic run that binds together the execution node, the evidence chain
cursor, and the bundle root hash into a single verifiable evidence node.

The commitment root is the **externally meaningful evidence anchor** for a run.
Runners, selectors, and downstream consumers reference this node as the root of
the evidence DAG rather than traversing the full execution chain.

## 2. Design Principles

### 2.1 Post-Hoc Construction

The commitment root is built **after** the execution node and evidence chain are
finalized. It cannot be constructed during execution — it requires the final
state of both artifacts.

### 2.2 Distinct Status from Execution

The commitment root's `:result :status` reflects **commitment construction**,
not the underlying execution. A failed execution still produces a `:pass`
commitment root (the commitment itself succeeded; it is an honest record of
failure). The execution status is preserved separately in `:outputs
:execution/status`.

### 2.3 Typed References

All references use typed format:
- `sha256:<64-hex>` for execution node hashes and bundle root hashes
- `evidence-chain:sha256:<64-hex>` for evidence chain cursor hashes

### 2.4 No Back-Edge

The commitment root references the execution node as a parent, but the execution
node remains parentless. This guarantees a DAG structure: no cycle can form
between execution and commitment nodes.

## 3. Node Structure

```clojure
{:execution-id    :evidence/commitment-root
 :policy-id       :evidence-policy/computed
 :status          :pass                    ;; commitment construction status
 :parent-hashes   ["sha256:<exec-node-hash>"]  ;; references execution node
 :bootstrap-roots ["evidence-chain:sha256:<cursor-hash>"]  ;; evidence chain anchor
 :inputs          {:execution/node-hash       "sha256:<exec-node-hash>"
                   :evidence/chain-cursor-hash "sha256:<cursor-hash>"}
 :outputs         {:bundle/root-hash  "sha256:<bundle-root-hash>"   ;; optional
                   :execution/status <:pass | :fail | :error>}     ;; from parent
 :execution-kind  :commitment-root
 :runner          :scenario-runner}
```

### 3.1 Required Fields

| Field | Type | Description |
|---|---|---|
| `:execution-id` | `keyword` | Must be `:evidence/commitment-root` |
| `:policy-id` | `keyword` | Evidence policy used (`:evidence-policy/computed`) |
| `:status` | `keyword` | `:pass` — commitment construction always succeeds if called |
| `:parent-hashes` | `vector<string>` | Typed references to execution node(s) |
| `:bootstrap-roots` | `vector<string>` | Typed references to evidence chain cursor(s) |
| `:inputs` | `map` | Execution node hash and evidence chain cursor hash |
| `:outputs` | `map` | Bundle root hash (optional) and execution status |
| `:execution-kind` | `keyword` | Must be `:commitment-root` |
| `:runner` | `keyword` | Must be `:scenario-runner` |

### 3.2 Outputs

| Key | Type | Required | Description |
|---|---|---|---|
| `:bundle/root-hash` | `string` | No | `sha256:<hash>` of the bundle root, if available |
| `:execution/status` | `keyword` | Yes | Mirrors the execution node's `:result :status` |

`bundle/root-hash` is absent when the bundle root has not yet been written
at commitment-root construction time (e.g. external-output writers that finalize
the bundle root after the evidence DAG).

### 3.3 Execution Registry Registration

The commitment root execution type is registered in the passive execution
registry (`src/resolver_sim/definitions/passive_registries.clj`):

```clojure
{:id :evidence/commitment-root
 :version 1
 :kind :commitment-root
 :runner :scenario-runner
 :entry 'resolver-sim.io.scenario-runner/run-and-report
 :execution/type :commitment-root
 :execution/mode :inline
 :description "Evidence commitment root node — post-hoc DAG anchor committing
  to the execution node, evidence chain cursor, and bundle root hash. This is
  the externally meaningful evidence anchor for a run."
 :claims #{}}
```

## 4. Construction Flow

The commitment root is emitted at the end of `run-and-report` in
`resolver-sim.io.scenario-runner` (line ~974):

```
Execution node finalized   ──┐
Evidence chain written     ──┤──→ Build commitment root node
Bundle root available?     ──┘         │
                                        ▼
                              emit-execution-node!
                                        │
                                        ▼
                              Registered in node registry
                              Persisted to evidence DAG
```

### 4.1 Construction Pseudocode

```
let evidence-root  ← chain/evidence-root-hash
let exec-hash      ← (:node-hash execution-node)
let exec-status    ← (get-in execution-node [:result :status])
let bundle-hash    ← (some-> execution-node :policy-output ... :bundle/root-hash)

when evidence-root and exec-hash are both present:
  emit-execution-node!
    execution-id:    :evidence/commitment-root
    policy-id:       :evidence-policy/computed
    parent-hashes:   ["sha256:<exec-hash>"]
    bootstrap-roots: ["evidence-chain:sha256:<evidence-root>"]
    status:          :pass
    inputs:          {:execution/node-hash       "sha256:<exec-hash>"
                     :evidence/chain-cursor-hash "sha256:<evidence-root>"}
    outputs:         {:bundle/root-hash   (optional "sha256:<bundle-hash>")
                     :execution/status   exec-status}
```

Failure during construction is logged as a warning (`:commitment-root-node-failed`)
but does not fail the run. The run is complete without a commitment root; the
anchor is a best-effort enhancement.

## 5. Validation

### 5.1 Structural Validation

The commitment root is validated through the same `validate-node` pipeline as all
evidence nodes. Additional constraints enforced by the test suite:

- **Typed reference format:** `:parent-hashes` must match `sha256:<64-hex>`;
  `:bootstrap-roots` must match `evidence-chain:sha256:<64-hex>`.
- **Parent resolvability:** All parent hashes must resolve to registered nodes.
- **No cycle:** Execution node must remain parentless.

### 5.2 Hash Stability

The node hash (`:node-hash`) is deterministic and excludes timestamp:
- Identical inputs produce identical hashes across registries and calls.
- Different execution parents produce different hashes.
- Different evidence cursor hashes produce different hashes.
- Different timestamps produce identical `:node-hash` (but different `:record-hash`).

The canonical projection includes `:execution-id`, `:policy-id`, `:parent-hashes`,
`:bootstrap-roots`, `:status`, `:inputs`, `:outputs`, `:execution-kind`, and `:runner`.
Timestamp is excluded from the canonical hash.

## 6. Use Cases

| Consumer | How they use the commitment root |
|---|---|
| **Runners** | Point to the commitment root as the top-level evidence anchor for a run |
| **Selectors** | Resolve the commitment root to verify execution integrity without replaying |
| **Auditors** | Verify the chain from commitment root → execution node → evidence chain |
| **Bundles** | Use the commitment root as the manifest root for attestation bundles |
| **Downstream verifiers** | Check `:execution/status` against the commitment without traversing the full DAG |

## 7. Related Tests

| Test | What it covers |
|---|---|
| `commitment-root-created-after-execution` | Node is created with correct execution-id |
| `parent-hashes-match-execution-node` | Parent references the execution node |
| `bootstrap-roots-include-evidence-chain` | Bootstrap root uses `evidence-chain:sha256:` format |
| `bundle-root-hash-present-in-outputs` | Outputs hash is present and valid |
| `execution-status-reflects-parent` | Execution status is distinct from commitment status |
| `no-cycle-between-execution-and-commitment` | DAG structure is preserved (no back-edge) |
| `commitment-root-resolvable-via-parenthood` | Graph walk from commit → exec → nil works |
| `execution-node-immutable-after-commitment` | Execution node is not mutated |
| `commitment-status-distinct-from-execution-status` | `:result :status` vs `:outputs :execution/status` distinction |
| `commitment-hash-*` (6 tests) | Hash stability across identical content, different parents, different evidence cursors, different timestamps, and different registries |
| `commitment-root-*` (4 tests) | Schema validity, execution section, typed reference format, unresolved parent rejection, optional bundle-root-hash |

## 8. References

| Document | Location |
|---|---|
| Commitment root construction | `src/resolver_sim/io/scenario_runner.clj:974-1000` |
| Commitment root registry definition | `src/resolver_sim/definitions/passive_registries.clj:560-568` |
| Node emission (`emit-execution-node!`) | `src/resolver_sim/evidence/node.clj` |
| Evidence chain root hash | `src/resolver_sim/evidence/chain.clj:890` |
| Commitment root tests (19 tests) | `test/resolver_sim/evidence/commitment_root_test.clj` |
| Attestation resolver spec | `docs/specs/ATTESTATION_RESOLVER_SPEC_V1.md` |
| Evidence DAG overview | `docs/evidence/EVIDENCE_DAG_OVERVIEW.md` |
