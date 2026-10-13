# Community Participation — Thin Slice

This guide describes how an external researcher or runner operator can
participate in PRF benchmark execution, result publication, reproduction
and challenge using the community participation layer.

## Overview

The community participation layer enables:

1. Discover a registered research task
2. Execute it using a documented command
3. Publish a signed result and supporting evidence
4. Reproduce or challenge another runner's result
5. Publish the reproduction or challenge through the mailbox
6. Generate an inspectable evidence graph linking the task, runs,
   attestations and outcome
7. Verify every hash and signature from persisted records

Identity is pseudonymous using Ed25519 signing keys. No proof of
personhood, KYC, staking, reputation, or onchain integration is required.

## Prerequisites

- Java 17+ (or Babashka)
- The PRF repository checked out (`deps.edn`, `bb.edn`)
- An Ed25519 key pair

### Create a signing key

```bash
ssh-keygen -t ed25519 -f ~/.prf-community/key -N ""
```

This creates `~/.prf-community/key` (private) and `~/.prf-community/key.pub`
(public). Your runner identity is derived from the key.

### Initialize the mailbox

The mailbox is a filesystem directory at `~/.protocol-robustness/community-mailbox/`.
It is created automatically the first time you use a community command.

## Available commands

All commands use the `community:` prefix in bb.edn:

| Command | Purpose |
|---------|---------|
| `bb community:task:list` | List registered tasks |
| `bb community:task:show -t <ref>` | Show task details |
| `bb community:run -t <ref> -r <id> -k <key>` | Execute a task and publish result |
| `bb community:reproduce -t <ref> -o <ref> -r <id> -k <key>` | Reproduce a result |
| `bb community:verify -t <ref>` | Verify evidence chain |
| `bb community:report -t <ref>` | Generate evidence report |

Options:
- `-t, --task REF` — task reference (e.g. `research-task:sha256:<hash>`)
- `-r, --runner ID` — runner identity (pseudonymous string)
- `-k, --key PATH` — path to Ed25519 private key
- `-o, --original-attestation REF` — original attestation ref (for reproduce)
- `-d, --dir DIR` — artifact storage directory (default: `~/.protocol-robustness/community-artifacts`)
- `-m, --mailbox-dir DIR` — mailbox directory (default: `~/.protocol-robustness/community-mailbox`)

## Workflow

### 1. Create and register a task (researchers)

```bash
bb community:task:register \
  --title "PRF-DR3 Deterministic Replay" \
  --benchmark-id :benchmark/prf-deterministic-replay-v1 \
  --suite-id :suite/prf-replay-v1
```

This builds a content-addressed `research-task.v0` record from the task
specification and publishes a `TASK_ANNOUNCEMENT` message to the mailbox.
The output includes the task reference needed for execution.

The `:benchmark/id` must match a registered benchmark in
`benchmarks/registry.edn`. The CLI uses it during `community:run` and
`community:reproduce` to resolve the correct benchmark manifest.

**Task spec fields:**
- `--title` — short human-readable name (required)
- `--benchmark-id` — keyword matching `:benchmark/<name>` in the registry
- `--suite-id` — keyword matching a registered scenario suite

### 2. Discover a task (runners)

```bash
bb community:task:list
```

This lists all tasks announced in the mailbox. Output shows the task
reference, title, and current status.

```bash
bb community:task:show --task research-task:sha256:<hash>
```

Shows task details and all mailbox messages associated with it.

### 3. Execute a task

```bash
bb community:run \
  --task research-task:sha256:<hash> \
  --runner my-runner \
  --key ~/.prf-community/key
```

This runs the benchmark associated with the task, collects execution
evidence, builds a signed attestation, and publishes a `RUNNER_RESULT`
message to the mailbox.

### 4. Reproduce a result

```bash
bb community:reproduce \
  --task research-task:sha256:<hash> \
  --original-attestation attestation:sha256:<hash> \
  --runner my-runner \
  --key ~/.prf-community/key
```

This re-executes the task, compares the result with the original, builds
a reproduction attestation, and publishes an `AGREEMENT` or `DISAGREEMENT`
message.

### 5. Verify the evidence chain

```bash
bb community:verify --task research-task:sha256:<hash>
```

Checks every mailbox message hash, verifies signatures where present,
and resolves attestations.

### 6. Generate a report

```bash
bb community:report --task research-task:sha256:<hash>
```

Prints a human-readable summary containing:

- Task ID, type, title, benchmark, suite
- Original runner and execution hash
- Original attestation verification status
- Reproduction runner and comparison result
- Challenges
- Agreement / disagreement status
- Evidence graph statistics
- Unresolved references

## Data model

### Task record (`research-task.v0`)

A content-addressed task binding a benchmark and its claims:

```clojure
{:schema-version "research-task.v0"
 :task/type :benchmark-execution
 :title "PRF-DR3 Deterministic Replay"
 :benchmark/id :benchmark/prf-deterministic-replay-v1
 :suite/id :suite/prf-replay-v1
 :claim-ids [:claim/replay-identical-results ...]
 :acceptance-criteria [...]
 :task/hash "sha256:<64-hex>"
 :task/ref "research-task:sha256:<64-hex>"}
```

### Execution attestation (`community-attestation.v0`)

Signed statement: "Runner R executed task T using evidence E":

```clojure
{:schema-version "community-attestation.v0"
 :attestation/predicate :runner/execution-attested
 :subject {:kind :research-task :reference <task-ref> :hash <task-hash>}
 :assertion {:runner/id "my-runner"
             :execution-node-hash "sha256:..."
             :result-projection-hash "sha256:..."}
 :attestation/hash "sha256:<64-hex>"
 :attestation/ref "attestation:sha256:<64-hex>"
 :attestation/signature <hex>}
```

### Reproduction attestation

Signed statement comparing reproduced result to original:

```clojure
{:attestation/predicate :runner/result-reproduced
 :assertion {:runner/id "..."
             :original-attestation-ref "..."
             :comparison-status :matched|:semantically-matched|:mismatched|:inconclusive
             :original-result-projection-hash "..."
             :reproduction-result-projection-hash "..."}}
```

### Challenge attestation

Additive record that never mutates the challenged result:

```clojure
{:attestation/predicate :runner/result-challenged
 :assertion {:runner/id "..."
             :challenged-attestation-ref "..."
             :challenge-type :evidence-unresolvable|:result-mismatch|:replay-failure|...
             :reason "..."}}
```

### Mailbox message types

| Type | Purpose |
|------|---------|
| `TASK_ANNOUNCEMENT` | Register a new research task |
| `RUNNER_RESULT` | Publish execution result |
| `REPRODUCTION_RESULT` | Reserved for two-step reproduction workflow |
| `CHALLENGE` | Challenge a result |
| `AGREEMENT` | Agree with a result |
| `DISAGREEMENT` | Disagree with a result |

### Task statuses

Derived from mailbox messages:

| Status | Trigger |
|--------|---------|
| `:announced` | Has `TASK_ANNOUNCEMENT` |
| `:executed` | Has `RUNNER_RESULT` |
| `:reproduced` | Has `REPRODUCTION_RESULT` |
| `:challenged` | Has `CHALLENGE` |
| `:agreed` | Has `AGREEMENT` |
| `:disagreed` | Has `DISAGREEMENT` |
| `:inconclusive` | Challenge with no resolution |

## Stable result comparison

Results are compared using the **stable projection** method (`project-stable-result`
in `resolver-sim.community.result`). This strips volatile fields before hashing:

**Striped before comparison:**
- `:repo` — git metadata, dirty state (varies per checkout)
- `:environment` — OS name, version, Java version (varies per machine)
- `:results[*]:file`, `:simulator/scenario-path` — absolute filesystem paths
- `:run/manifest` — run-specific paths
- `:evidence/hash`, `:benchmark-certification` — self-reference hashes

**Preserved:**
- `:benchmark` — benchmark definition (deterministic)
- `:results[*]:scenario/id`, `:outcome`, `:halt-reason`
- `:results[*]:invariant-results` — invariant pass/fail per scenario
- `:results[*]:scenario/evidence-root` — deterministic replay hash
- `:metrics` — execution counts
- `:claim-results` — claim evaluation outcomes
- `:invariant-summary` — aggregate invariant results

The comparison policy (`:stable-projection-v0`) is committed in the reproduction
attestation. The reproduction runner independently computes its own projection
and compares it to the original attestation's claimed stable hash. A match means
the two runs produced semantically identical outcomes.

## What the result proves

An agreed task demonstrates:

- The benchmark is executable and produces consistent results
- Two independent runners obtained semantically comparable outcomes (stable
  projections match)
- The evidence chain is intact and verifiable

A challenged task demonstrates:

- Disagreement exists about the result or methodology
- The original result is preserved (not deleted or mutated)
- An independent verifier can inspect both sides

## What a finding does and does not assert

A finding's **record integrity** (`finding/valid-finding?`) confirms:

- The finding hash matches its content (no tampering)
- The finding structure is well-formed

A finding's **semantic validity** (`finding/verify-finding-evidence`) additionally
checks:

- An `:execution-result` finding references at least one execution
- A `:reproduction` finding references two or more executions
- A `:counterexample` finding references an execution where the claimed property
  was violated
- A `:challenge` finding references a challenged claim
- A `:bounded-negative-result` finding references supporting evidence

Record integrity alone does not imply the finding's conclusion is correct.

## What this does NOT provide

This thin slice explicitly excludes:

- PDRA epochs, certificates, and payments
- Automated rewards or incentives
- Reputation scoring or ranking
- Staking or slashing
- Onchain settlement or verification
- Permissionless task markets or auctions
- Proof of personhood or legal identity
- Sophisticated networking or peer discovery

These capabilities are intentionally deferred. The thin slice focuses
on the minimum path to external community participation with
verifiable evidence.

## Acceptance gates

Before announcing this capability for external use, verify each gate:

| # | Gate | Verification |
|---|---|---|
| 1 | Clean checkout | `git clone <url> /tmp/prf-check && cd /tmp/prf-check && bb test:community` — all tests pass with no generated artifacts |
| 2 | Runner creation | `ssh-keygen -t ed25519 -f /tmp/key1 -N "" && ssh-keygen -t ed25519 -f /tmp/key2 -N ""` |
| 3 | Task discovery | `bb community:task:list` — lists tasks from mailbox |
| 4 | Original execution | `bb community:run --task <ref> --runner r1 --key /tmp/key1` — invokes real benchmark runner |
| 5 | Publication | Evidence persisted to `~/.protocol-robustness/community-artifacts/`; mailbox message published |
| 6 | Independent reproduction | `bb community:reproduce --task <ref> --original-attestation <ref> --runner r2 --key /tmp/key2` — re-executes and compares |
| 7 | Positive path | Reproduction produces `:matched` and `:agreed` status |
| 8 | Negative path | Tampering with a result produces additive `:challenge` or `:DISAGREEMENT` |
| 9 | Resolution | Every referenced object recoverable from `~/.protocol-robustness/` |
| 10 | Verification | `bb community:verify --task <ref>` — all hashes OK, signatures OK |
| 11 | Reporting | `bb community:report --task <ref>` — human-readable graph with status |
| 12 | Portability | Repeat steps 3–11 in a second checkout or environment |
| 13 | Tamper resistance | Changing any semantic reference (task ref, attestation ref, runner ID) changes the object hash — confirmed by `test:community` |
| 14 | Documentation | An unfamiliar person completes steps 1–11 from this guide |

### Acceptance transcript format

A completed gate demonstration produces a trace like:

```
task reference:            research-task:sha256:a1b2c3d4...
original runner ID:        runner-alpha
original evidence stable hash:  sha256:stable-hash-1...
execution attestation ref:     attestation:sha256:att-1...
reproduction runner ID:        runner-beta
reproduction stable hash:      sha256:stable-hash-1...
reproduction attestation ref:  attestation:sha256:att-2...
reproduction comparison:       :matched
mailbox agreement hash:        mailbox:sha256:msg-agree...
task status:                   :agreed
task graph root:               research-task:sha256:a1b2c3d4...
final verification:            all hashes OK, all signatures OK
```

### Readiness score

Current: **9/10** — feature-complete, all tests pass, design concerns
documented and tested. Remaining 1 point requires a second person
completing the workflow from the documentation.

## Architecture notes

- All records are content-addressed: changing the content changes the
  hash and reference
- Signatures are excluded from content hashes (circular dependency
  otherwise). The verifier checks both `recomputed hash == claimed ID`
  and `signature verifies over that ID`.
- Public keys are stored as key paths (not raw key content) and
  resolved during verification. The sender/runner identity is committed
  in the content hash — a verifier must check that the resolved key
  corresponds to the claimed identity.
- `:issued-at` timestamps on attestations are included in the content
  hash. Mailbox message timestamps are excluded from the hash (they are
  mutable metadata) but are bound by the signature when signed.
- Only explicit top-level self-identity keys are excluded from hashes
  (`:attestation/id`, `:attestation/hash`, `:attestation/ref`, etc.).
  Semantic references inside nested maps (`:subject`, `:assertion`,
  `:body`) are always included.
- Attestations are additive: challenges never mutate or delete the
  challenged result
- Conflicting results are preserved for independent verification
- Unknown predicates and record kinds cause hard failures (no silent nil)
- Records use explicit versioned schemas for forward compatibility
- The mailbox uses filesystem transport (EDN files on disk) — no
  network infrastructure required
- Signing uses Ed25519 via the existing `resolver-sim.benchmark.signing`
  namespace
