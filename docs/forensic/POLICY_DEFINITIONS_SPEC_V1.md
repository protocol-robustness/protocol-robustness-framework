# POLICY_DEFINITIONS_SPEC_V1

Status: Draft V1

## 1. Purpose

Define the policy definition files used by the forensic runner and protocol
implementations. Policies declare constraints on execution, evidence capture,
output organization, attestation rules, yield distribution, and risk adjustment.

Policy definitions fall into two categories:

1. **Forensic execution configuration** (EDN files in `config/forensic/`)
   — versioned configuration inputs for a specific forensic bundle run.
2. **Source-level policy implementations** (Clojure in `src/` and `protocols_src/`)
   — compiled policy logic for attestation, yield allocation, and risk adjustment.

## 2. Design Principles

### 2.1 Policy is Data

EDN policy files are declarative data, not code. They are versioned, named,
and validated against a schema. This allows the same forensic runner binary
to behave differently under different policy configurations without recompilation.

### 2.2 Composability

A forensic run combines three orthogonal policies (evidence, execution, output).
Each controls a distinct axis. They are designed to be mixed and matched.

### 2.3 Determinism by Default

Execution policies default to deterministic mode. Policy definitions that affect
reproducibility (e.g. timestamps, random seeds) are explicitly declared so that
run reproducibility can be audited.

### 2.4 Policy Versioning

Every policy carries a `:schema-version` and a `:id`. This enables run bundles
to reference which policies were applied, supporting later verification.

## 3. Forensic Runner Policy Definitions

Three EDN files live in `config/forensic/`:

| File | Policy Type | Purpose |
|---|---|---|
| `evidence-policy.edn` | `:evidence-policy` | What is captured in evidence nodes |
| `execution-policy.edn` | `:execution-policy` | How the run executes |
| `output-policy.edn` | `:output-policy` | Where and how output is written |

### 3.1 Evidence Policy

**File:** `config/forensic/evidence-policy.edn`
**Schema:** `"evidence-policy.v1"`

Controls the content and retention of the evidence DAG produced during a run.

**Top-level fields:**

| Field | Type | Description |
|---|---|---|
| `:evidence-policy/schema-version` | `string` | Schema identifier for validation |
| `:evidence-policy/id` | `keyword` | Unique policy name |
| `:evidence-policy/description` | `string` | Human-readable description |
| `:evidence-policy/min-importance` | `integer` | Minimum artifact importance threshold (`0`=CORE, `1`=DIAGNOSTIC, `2`=TRACE) |
| `:evidence-policy/include` | `map` | Per-artifact inclusion flags |
| `:evidence-policy/retention` | `map` | Per-level retention directives |

**Inclusion flags (`:evidence-policy/include`):**

| Key | Type | Default | Description |
|---|---|---|---|
| `:step-results` | `boolean` | `true` | Include per-step execution results |
| `:state-snapshots` | `boolean` | `true` | Include world state snapshots |
| `:invariant-checks` | `boolean` | `true` | Include invariant evaluation results |
| `:diagnostics` | `boolean` | `false` | Include diagnostic output (may be non-reproducible) |
| `:timestamps` | `boolean` | `false` | Include mutable timestamps (affects stable hashes) |
| `:debug-output` | `boolean` | `false` | Include debug logs |

**Retention directives (`:evidence-policy/retention`):**

| Key | Values | Description |
|---|---|---|
| `:core-evidence` | `:permanent`, `:session`, `:discard` | CORE artifacts |
| `:diagnostic-evidence` | `:permanent`, `:session`, `:discard` | DIAGNOSTIC artifacts |
| `:trace-evidence` | `:permanent`, `:session`, `:discard` | TRACE artifacts |

### 3.2 Execution Policy

**File:** `config/forensic/execution-policy.edn`
**Schema:** `"execution-policy.v1"`

Controls how the forensic runner selects a runner, handles determinism, selects
scenarios, and constrains network access.

**Top-level fields:**

| Field | Type | Description |
|---|---|---|
| `:execution-policy/schema-version` | `string` | Schema identifier for validation |
| `:execution-policy/id` | `keyword` | Unique policy name |
| `:execution-policy/description` | `string` | Human-readable description |
| `:runner-selection` | `map` | Runner selection mode and target |
| `:determinism` | `map` | Determinism requirements and seed |
| `:scenario-selection` | `map` | Scenario selection mode and paths |
| `:network-policy` | `keyword` | `:allow` or `:deny` external network access |
| `:output` | `map` | Output target constraints |

**Runner selection (`:runner-selection`):**

| Key | Type | Description |
|---|---|---|
| `:mode` | `keyword` | `:pinned` (specific version), `:capability-match`, or `:quorum` |
| `:runner-id` | `string` | Runner identifier when mode is `:pinned` |

**Determinism (`:determinism`):**

| Key | Type | Description |
|---|---|---|
| `:require-deterministic` | `boolean` | Fail on non-deterministic execution |
| `:random-seed` | `integer` | Fixed seed for randomized steps |
| `:time-source` | `keyword` | `:simulation`, `:wall-clock`, or `:epoch` |

**Scenario selection (`:scenario-selection`):**

| Key | Type | Description |
|---|---|---|
| `:mode` | `keyword` | `:explicit`, `:suite`, or `:registry-default` |
| `:scenario-paths` | `vector` | List of scenario paths when mode is `:explicit` |

**Output constraints (`:output`):**

| Key | Type | Description |
|---|---|---|
| `:target-root` | `string` | External output directory path |
| `:overwrite` | `boolean` | Allow overwriting existing runs |
| `:bundle-format` | `keyword` | Bundle format version |

### 3.3 Output Policy

**File:** `config/forensic/output-policy.edn`
**Schema:** `"output-policy.v1"`

Controls how and where forensic output is written, organized, and serialized.

**Top-level fields:**

| Field | Type | Description |
|---|---|---|
| `:output-policy/schema-version` | `string` | Schema identifier for validation |
| `:output-policy/id` | `keyword` | Unique policy name |
| `:output-policy/description` | `string` | Human-readable description |
| `:output/root` | `string` | Output root directory (outside repo tree) |
| `:output/run-naming` | `keyword` | `:timestamped`, `:uuid`, or `:custom` |
| `:output/include` | `map` | Which artifact types to include |
| `:output/immutable` | `boolean` | Whether output is read-only after creation |
| `:output/serialization` | `keyword` | `:json`, `:edn`, or `:msgpack` |
| `:output/pretty-print` | `boolean` | Whether to pretty-print serialized output |

**Inclusion flags (`:output/include`):**

| Key | Default | Description |
|---|---|---|
| `:evidence-dag` | `true` | Full evidence DAG |
| `:claims` | `true` | Claim results |
| `:attestations` | `true` | Attestations |
| `:anchors` | `true` | Anchor cursors |
| `:run-overview` | `true` | Stable-field overview |
| `:bundle-root` | `true` | Bundle root manifest |
| `:preflight-report` | `true` | Preflight report |

## 4. Source-Level Policy Implementations

Three Clojure namespaces implement policy logic:

| Namespace | File | Purpose |
|---|---|---|
| `resolver-sim.evidence.attestation-policy` | `src/resolver_sim/evidence/attestation_policy.clj` | Attestation rule matching and evaluation |
| `resolver-sim.protocols.sew.yield.policy` | `protocols_src/resolver_sim/protocols/sew/yield/policy.clj` | Yield distribution and fee capture |
| `resolver-sim.protocols.sew.yield.risk-policy` | `protocols_src/resolver_sim/protocols/sew/yield/risk_policy.clj` | Dynamic APY/liquidity risk adjustment |

### 4.1 Attestation Policy

**Namespace:** `resolver-sim.evidence.attestation-policy`

A policy is a named set of rules. Each rule specifies the allowed attestors,
subject kinds, claim IDs, and claim results. The evaluator checks an attestation
against a policy using OR semantics across rules (any matching rule implies
compliance).

**Policy map structure:**

```clojure
{:policy-id    :default                              ;; unique keyword identifier
 :description  "Default attestation policy"          ;; human-readable
 :rules        [{:attestors     #{:runner :auditor}  ;; allowed attestor-ids (nil = all)
                 :subject-kinds #{:claim :evidence}  ;; allowed subject kinds (nil = all)
                 :claim-ids     nil                  ;; allowed claim ids (nil = all)
                 :claim-results #{:pass :fail}}      ;; allowed claim result keywords (nil = all)
                ...]}
```

**API:**

| Function | Arguments | Returns |
|---|---|---|
| `register-policy!` | `policy-map` | Registers policy by `:policy-id` |
| `find-policy` | `policy-id` | Policy map or `nil` |
| `all-policies` | — | Vector of all registered policies |
| `evaluate-attestation` | `attestation [, policy-id]` | `{:compliant? true :matched-rule ...}` or `{:compliant? false :reasons [...]}` |
| `check-attestation` | `attestation, rules` | Same as evaluate-attestation with inline rules |
| `check-registry` | `policy-id` | Summary of compliant/non-compliant attestations in registry |
| `clear-policies!` | — | Reset registry to empty |

The default `policy-id` is `:default`. If a policy is not found, evaluation returns
`{:compliant? :policy-not-found :policy-id <id>}`.

### 4.2 Yield Policy

**Namespace:** `resolver-sim.protocols.sew.yield.policy`

Allocates realized yield from an escrow to claimable balances and protocol fees
based on the settlement outcome and the yield preset.

**Entry point:**

```clojure
(apply-yield-policy world escrow-id settlement-outcome)
```

**Yield presets (`:yield-preset`):**

| Preset | Released to Recipient | Released to Sender | Refunded to Sender | Refunded to Recipient |
|---|---|---|---|---|
| `:to-recipient` | Full yield | — | — | — |
| `:to-sender` | — | Full yield | Full yield | — |
| `:split-50-50` | Split equally | Split equally | Split equally | — |
| `:off` | — | — | — | — |

**Fee capture:** Protocol fee (in bps, from snapshot `:yield-protocol-fee-bps`)
is deducted from gross yield before distribution. Any remaining unallocated
yield after distribution is captured as additional protocol fees.

**Shortfall handling:** Under partial-yield shortfall, only the realized
(liquid) leg is distributable. Positions with shortfall remain active for
later recovery; positions without shortfall are marked `:settled`.

### 4.3 Risk Policy

**Namespace:** `resolver-sim.protocols.sew.yield.risk-policy`

A multimethod dispatch on `policy-id` that computes dynamic APY multipliers
and liquidity mode based on the current shortfall ratio.

**Multimethod:**

```clojure
(calculate-risk-adjustment policy-id available-ratio)
```

**Built-in policies:**

| Policy | Low shortfall (< 20%) | Medium shortfall (20-50%) | High shortfall (> 50%) |
|---|---|---|---|
| `:de-risking` | 1.0x APY, `:available` | 0.5x APY, `:shortfall` | 0.0x APY, `:frozen` |
| `:liquidity-attraction` | 1.0x APY, `:available` | 2.0x APY, `:shortfall` | 5.0x APY, `:shortfall` |
| `:bad-policy` | 1.5x APY, `:available` | 1.5x APY, `:available` | 1.5x APY, `:available` |
| `:default` | 1.0x APY, `:available` | 1.0x APY, `:available` | 1.0x APY, `:available` |

- **`:de-risking`** — Conservative: drops APY and constrains withdrawals during crunch.
- **`:liquidity-attraction`** — Aggressive: increases APY to attract deposits during crunch.
- **`:bad-policy`** — Maintains high APY despite crunch, creating risk cascade.
- **`:default`** — Neutral baseline.

## 5. Policy Composition in a Forensic Run

A forensic run selects one policy per axis. The set forms a policy triple:

```
{:evidence-policy <evidence-policy-id>
 :execution-policy <execution-policy-id>
 :output-policy <output-policy-id>}
```

This triple is recorded in the run bundle manifest so that downstream consumers
(verifiers, researchers, auditors) can inspect the exact policy configuration
that produced the evidence.

## 6. Related Scenario

| Scenario | What it tests |
|---|---|
| `S105_policy-response-cascade` | De-risking vs liquidity-attraction policy response under escalating shortfall. Tests that the risk policy correctly transitions liquidity modes and that the system does not enter a risk cascade under `:bad-policy`. |

## 7. References

| Document | Location |
|---|---|
| Evidence policy configuration | `config/forensic/evidence-policy.edn` |
| Execution policy configuration | `config/forensic/execution-policy.edn` |
| Output policy configuration | `config/forensic/output-policy.edn` |
| Attestation policy source | `src/resolver_sim/evidence/attestation_policy.clj` |
| Attestation policy tests | `test/resolver_sim/evidence/attestation_policy_test.clj` |
| Yield policy source | `protocols_src/resolver_sim/protocols/sew/yield/policy.clj` |
| Yield policy tests | `protocols_src/test/resolver_sim/protocols/sew/yield/policy_test.clj` |
| Risk policy source | `protocols_src/resolver_sim/protocols/sew/yield/risk_policy.clj` |
| Forensic attestations spec | `docs/forensic/FORENSIC_ATTESTATIONS_SPEC_V1.md` |
| S105 policy cascade scenario | `scenarios/S105_policy-response-cascade.json` |
| Replay dedupe policy tests | `protocols_src/test/resolver_sim/protocols/sew/replay_dedupe_policy_test.clj` |
