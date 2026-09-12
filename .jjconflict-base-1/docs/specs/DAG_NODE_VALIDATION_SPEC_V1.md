# DAG_NODE_VALIDATION_SPEC_V1

Status: Draft V1

## 1. Purpose

Defines validation rules for evidence DAG nodes and node collections.
Bundle root validation and reproduction depend on node-level integrity
— this spec provides the canonical checks that both rely on.

## 2. Node Schema

Each evidence node is a content-addressed map with a self-referential hash.

### 2.1 Required Top-Level Fields

| Field | Type | Description |
|---|---|---|
| `:schema-version` | integer | Must be `1` |
| `:node-id` | string | Equals `:node-hash` (identity = content) |
| `:node-hash` | string | Self-referential hash of all other fields |
| `:parent-hashes` | vector | Hashes of parent DAG nodes (may be empty) |
| `:bootstrap-roots` | vector | Hashes of bootstrap roots (may be empty) |
| `:timestamp` | string | ISO-8601 instant (volatile, excluded from hash) |
| `:execution` | map | Execution metadata (see §2.2) |
| `:result` | map | Execution result (see §2.3) |
| `:evidence` | map | Evidence content hashes (see §2.4) |
| `:attestations` | vector | Attestation records (may be empty) |
| `:extensions` | map | Extension data (may be empty) |
| `:policy-output` | map | Policy-filtered presentation (excluded from hash) |

### 2.2 Execution Map

| Field | Type | Description |
|---|---|---|
| `:execution-id` | keyword | Registered execution id from `execution-registry` |
| `:execution-kind` | keyword | Kind from the execution registry entry |
| `:runner` | keyword | Runner that executed this node |
| `:registry-hash` | string | Hash of execution registry at time of creation |
| `:policy-id` | keyword | Evidence policy id used |
| `:policy-hash` | string | Hash of evidence policy at time of creation |

### 2.3 Result Map

| Field | Type | Description |
|---|---|---|
| `:status` | keyword | One of `:pass`, `:fail`, `:error` |
| `:summary` | map | Policy-applied counts |

### 2.4 Evidence Map

| Field | Type | Description |
|---|---|---|
| `:inputs-hash` | string | Content hash of all inputs |
| `:outputs-hash` | string | Content hash of all outputs |

## 3. Hash Projection

The node hash is computed from all fields EXCEPT:

- `:node-id` (set equal to `:node-hash` after computation)
- `:node-hash` (self-referential exclusion)
- `:timestamp` (volatile wall-clock time)
- `:policy-output` (policy-dependent presentation, not content)

```clojure
(hash-with-intent {:hash/intent :evidence-node} node)
```

The content hash (`:content-hash`) is the same as `:node-hash`.
The record hash (`:record-hash`) includes the timestamp for audit:

```clojure
SHA-256(node-hash + "|" + timestamp)
```

## 4. Single-Node Validation

A single node is valid iff:

| Check | Condition |
|---|---|
| Required fields present | `:schema-version`, `:node-id`, `:node-hash`, `:parent-hashes`, `:execution`, `:result`, `:evidence` |
| Schema version | `:schema-version == 1` |
| Status valid | `:result :status` is one of `:pass`, `:fail`, `:error` |
| Hash matches | `(compute-node-hash node) == (:node-hash node)` |
| Parents resolvable | Every `:parent-hash` is known or in `:bootstrap-roots` |

### 4.1 Extended Checks

| Check | Condition |
|---|---|
| Node id == node hash | `:node-id == :node-hash` |
| Inputs/outputs present | `:evidence :inputs-hash` and `:outputs-hash` are non-empty strings |
| Attestations vector | `:attestations` is a vector |
| Parents resolvable (extended) | Same as above, with explicit known-parent-hashes set |

## 5. DAG Validation

A node collection is a valid DAG iff:

| Check | Condition |
|---|---|
| Every node valid | Each individual node passes §4 checks |
| All parents known | Every parent hash in any node exists as a `:node-hash` in the collection or in a `:bootstrap-root` |
| No cycles | The directed graph formed by `:parent-hashes` edges is acyclic |

```clojure
(validate-node-dag nodes)
;; => {:valid? true}
;; => {:valid? false
;;     :errors [{:error :node/missing-fields ...}
;;              {:error :node/hash-mismatch ...}
;;              {:error :node/cycle :cycle [...]}]}
```

## 6. Artifact Verification

Persisted node artifacts can be verified against the chain registry:

| Check | Condition |
|---|---|
| Node file exists | `.edn` file at expected path |
| Node re-reads | EDN deserialization succeeds |
| Node re-validates | Re-read node passes §4 |
| Hash recomputes | `compute-node-hash` matches recorded `:node-hash` |
| Artifact hash matches | Chain entry `:artifact/hash` matches `:node-hash` |
| File SHA-256 matches | File `sha256` matches chain entry `:sha256` |
| Path matches | Artifact path matches recorded path |

```clojure
(verify-persisted-node-artifact! path artifact-entry)
;; => {:valid? true
;;     :checks {:node-valid? true
;;              :node-hash-valid? true
;;              :artifact-hash-valid? true
;;              :artifact-file-hash-valid? true
;;              :path-valid? true}}
```

## 7. Bundle Root Integration

The bundle root stores `:dag/root-node-hash` pointing to the root evidence
node of the execution DAG. The root node is the most recent node in the
DAG that has no children in the collection — typically the `:execution/replay`
node produced by `with-execution-node+`.

When validating a bundle root, the DAG node check requires:

1. `:dag/root-node-hash` is present and non-nil
2. The root node hash resolves to a valid node in the evidence DAG
3. The evidence DAG passes DAG validation (§5)
