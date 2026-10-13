# SOLIDITY_SHADOW_REGISTRY_SPEC_V1

Status: Draft V1

The registry also exposes `semantic-coverage-table`, a machine-readable table
for semantic claims that must not be inferred from Solidity shadow entries.
Its closed status vocabulary is `:semantic/solidity-coverage-absent` and
`:semantic/proof-verified-full-coverage`; matching reasons are
`:reason/solidity-coverage-absent` and `:reason/proof-verified-full-coverage`.
`valid-semantic-coverage-row?` rejects status/reason and boolean-flag
mismatches. In particular, absent Solidity coverage is an explicit coverage
state, not a proof failure, while full coverage requires proof verification.

## 1. Purpose

Define the Solidity Shadow Registry — a machine-readable catalog that tracks
which simulation code shadows which Solidity contract, with documented
intentional differences. The registry provides traceability between the Clojure
simulation and the deployed Solidity implementation, enabling reviewers to
assess fidelity gaps at a glance.

## 2. Design Principles

### 2.1 Informational, Not Enforced

The registry is informational — it does not block startup or fail CI. It
exists to document the mapping so that reviewers and developers can identify
coverage gaps, stale entries, and intentional deviations.

### 2.2 Per-Entry Differences

Every entry carries a `:differences` vector enumerating known discrepancies
between simulation and Solidity behavior. An empty vector means the simulation
and Solidity are expected to be functionally equivalent for that entry.

### 2.3 Two Status Axes

Each entry has two independent status fields:
- `:solidity/status` — whether the Solidity implementation exists
- `:protocol/status` — whether the protocol status is current or proposed

This allows tracking proposed features (`:solidity/not-implemented`,
`:protocol/proposed`) alongside implemented ones.

### 2.4 Review Currency

Every entry has a `:last-reviewed` date. Stale entries should be updated when
the simulation or Solidity code changes.

## 3. Entry Schema

```clojure
{:shadow/id           <keyword>       ;; unique identifier
 :simulation/ns       <string>        ;; fully qualified simulation namespace
 :simulation/role     <keyword>       ;; :action | :invariant | :guard | :predicate | :projection | ...
 :solidity/contract   <string>        ;; Solidity file name
 :solidity/function   <string>        ;; Solidity function signature(s)
 :solidity/status     <keyword>       ;; :solidity/implemented | :solidity/not-implemented
 :protocol/status     <keyword>       ;; :protocol/current | :protocol/proposed
 :description         <string>        ;; human-readable description of the correspondence
 :differences         [<string> ...]  ;; known differences (empty = functionally equivalent)
 :test/link           <string>        ;; path to Foundry test file
 :trace-equivalence   <string>        ;; trace equivalence level ("cdrs-v0.2" | "N/A")
 :last-reviewed       <string>        ;; ISO date of last review
}
```

### 3.1 Required Keys

Every entry must have all keys listed in `shadow-entry-keys`:
`:shadow/id`, `:simulation/ns`, `:simulation/role`, `:solidity/contract`,
`:solidity/function`, `:solidity/status`, `:protocol/status`, `:description`.

### 3.2 Status Values

| `:solidity/status` | Meaning |
|---|---|
| `:solidity/implemented` | Solidity contract has the function implemented |
| `:solidity/not-implemented` | Solidity contract does not exist yet (proposed feature) |

| `:protocol/status` | Meaning |
|---|---|
| `:protocol/current` | Protocol status matches the current deployed version |
| `:protocol/proposed` | Feature is proposed but not yet deployed |

### 3.3 Roles

| Role | Description |
|---|---|
| `:action` | Simulation action that maps to a Solidity function call |
| `:invariant` | Invariant check present in both simulation and Foundry tests |
| `:guard` | Guard condition or assertion that prevents invalid states |
| `:predicate` | Boolean predicate used in state transitions |
| `:projection` | Data transformation or migration logic |
| `:cleanup` | Cleanup operation on terminal paths |
| `:fix` | Bug fix applied in simulation or Solidity |
| `:integration` | Cross-module integration path |
| `:validation` | Validation check for input correctness |

### 3.4 Trace Equivalence

| Value | Meaning |
|---|---|
| `"cdrs-v0.2"` | Trace-level equivalence verified under CDRS v0.2 |
| `"N/A"` | No trace equivalence applicable (Solidity-only, proposed, or structural difference) |

## 4. Registry Contents

The registry (as of the last update) contains **29 entries** across **11 Solidity
contracts**:

| Contract | Entries |
|---|---|
| `BaseEscrow.sol` | 10 (escrow create, cancel, yield unwind, dispute cleanup, governance sandwich, auto-time, split, etc.) |
| `EscrowStateMachine.sol` | 4 (dispute raise, auto-cancel, terminal guards, transition-to-disputed) |
| `ResolverSlashingModuleV1.sol` | 2 (dispute resolve, appeal) |
| `SettlementOps.sol` | 2 (execute settlement, auto-cancel-disputed) |
| `StateManagementLibrary.sol` | 2 (terminal transition guards, transition-to-disputed) |
| `DefaultCancellationStrategy.sol` | 1 (mutual-consent cancellation) |
| `IdentityGuard.sol` | 1 (proposed — identity confusion fix) |
| `StorageMigration.sol` | 1 (proposed — V1→V2 migration) |
| `StateInvariants.t.sol` | 3 (solvency, terminal states, fees monotone) |
| `ResolverInvariants.t.sol` | 1 (appeal window enforcement) |
| `DisputeOps.sol` | 1 (encoding alignment) |
| `EscrowableERC20.sol` | 1 (CEI pattern fix) |
| `EscrowManagementLibrary.sol` | 1 (finalize-dispute selector) |
| `DecentralizedResolutionModule.sol` | 1 (cross-module terminal paths) |

**Status breakdown:**
- Implemented: 27 entries
- Not implemented (proposed): 2 entries
- Entries with documented differences: ~17 entries
- Entries with no differences (functional equivalence): ~12 entries

## 5. Query API

All functions in namespace `resolver-sim.definitions.solidity-shadow-registry`.

### 5.1 `lookup-by-simulation`

Find entries by simulation namespace.

```clojure
(lookup-by-simulation "resolver-sim.sim.adversarial.reorg-check")
;; → [{:shadow/id :escrow-create ...}]

(lookup-by-simulation "resolver-sim.contract-model.replay*")  ;; prefix match
;; → [{:shadow/id :escrow-sender-cancel ...}
;;    {:shadow/id :escrow-recipient-cancel ...} ...]
```

Supports wildcard prefix matching: append `*` to match all namespaces starting
with the prefix.

### 5.2 `lookup-by-solidity`

Find entries by Solidity contract file name.

```clojure
(lookup-by-solidity "BaseEscrow.sol")
;; → [{:shadow/id :escrow-create ...} {:shadow/id :escrow-sender-cancel ...} ...]
```

### 5.3 `all-differences`

Return all entries with non-empty differences vectors.

```clojure
(all-differences)
;; → ({:shadow/id :escrow-create
;;     :simulation/ns "resolver-sim.sim.adversarial.reorg-check"
;;     :solidity/contract "BaseEscrow.sol"
;;     :differences ["Simulation combines deposit + escrow creation in a single step"
;;                   "Simulation does not enforce Nonce-based ID derivation (keccak256)"]}
;;    ...)
```

### 5.4 `all-entries`

Return all registry entries as a vector.

```clojure
(all-entries)
```

### 5.5 `check-shadow-coverage`

Scan `src/` for Clojure namespaces without shadow entries.

```clojure
(check-shadow-coverage)
;; → [{:namespace "resolver-sim.some.new.ns" :path "src/resolver_sim/some/new/ns.clj" :exists? true} ...]
```

Ignores known non-simulation prefixes (`resolver-sim.definitions`,
`resolver-sim.hash`, `resolver-sim.io`, etc.) and test namespaces by default.
Can include `protocols_src/` via `{:include-protocols? true}`.

### 5.6 `format-shadow-report`

Return a human-readable summary string.

```clojure
(println (format-shadow-report))
;; Solidify Shadow Registry — 29 entries, 11 contracts
;;   Implemented: 27
;;   Not impl.:   2
;;   Differences: 17
;;   BaseEscrow.sol
;;     escrow-create (2 diff(s))
;;     escrow-sender-cancel (1 diff(s))
;;     ...
```

## 6. Babashka Tasks

| Task | Description |
|---|---|
| `bb shadow:check` | Check simulation namespaces for shadow registry coverage. Flags unregistered namespaces. |
| `bb shadow:report` | Print a human-readable summary of the shadow registry. |

## 7. Tests

| Test | What it covers |
|---|---|
| `registry-has-entries` | Registry is non-empty |
| `all-entries-valid` | Every entry conforms to required schema keys and valid status values |
| `entries-have-unique-ids` | No duplicate `:shadow/id` values |
| `overlapping-simulation-nses-are-documented` | Multiple entries per namespace is expected and handled |
| `lookup-by-simulation-test` | Exact and wildcard namespace lookup |
| `lookup-by-solidity-test` | Contract name lookup |
| `all-differences-test` | Non-empty difference reporting |
| `format-shadow-report-test` | Report format is non-empty and contains expected header |

## 8. References

| Document | Location |
|---|---|
| Registry source | `src/resolver_sim/definitions/solidity_shadow_registry.clj` |
| Registry tests | `test/resolver_sim/definitions/solidity_shadow_registry_test.clj` |
| `bb shadow:check` task | `bb.edn` (line 447) |
| `bb shadow:report` task | `bb.edn` (line 461) |
| Protocol alignment status values | `src/resolver_sim/protocol_alignment.clj` |
