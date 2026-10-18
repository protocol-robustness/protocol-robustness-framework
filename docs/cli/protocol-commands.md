# Protocol, forensic and deployment commands

The PRF source launcher is `bin/prf`. It selects this checkout's normal
`-Srepro -M:with-sew` dependency closure. You can invoke its absolute path from
another directory. `--input` and `--trust-context` paths resolve from the caller's
directory; paths inside a request resolve from the request file's directory.
Embedded paths also support a leading `~/` for the current user's home directory.
For `--input -`, embedded paths resolve from PRF's checkout directory when using
the launcher. Use absolute embedded paths with stdin to avoid ambiguity.

Each new command requires exactly one EDN request map using `--input FILE|-`.
Unknown top-level fields, missing fields and trailing EDN documents reject.
EDN preserves integer workflow keys, keyword values, amounts and root strings.
`--json` projects keyword names with namespaces and integers as decimal strings;
it never changes root strings. JSON output is a presentation format, not an EDN
request round trip. Reports wrap existing artifacts without introducing new
canonical identities. Exit codes are 0 for successful checks/execution, 1 for a
negative check, and 2 for invalid or unreadable requests. Forensic subprocess
exit codes are propagated unchanged in the outer result and process status.

## Protocol finality

```sh
bin/prf protocol finality inspect --input examples/commands/finality-inspect.edn
bin/prf protocol finality verify --input /absolute/path/finality-verification.edn
```

Both commands require `:protocol :sew`, `:world` and an existing nonnegative
integer `:workflow-id`. `:kind` defaults to `:financial`.

For financial inspection, `:finality` contains the unchanged output of
`financial.finality/combine-finality`. Copy that value into the verification
request as `:finality`. Verification recomputes the complete classification,
including open appeal, settlement, recovery and slash-appeal gates. Verification
can succeed while financial finality is still pending: it verifies the reported
classification, not an assertion that all gates are closed.

For `:kind :ruling`, additionally provide `:ruling` and
`:authentication-verification`. Inspection derives the existing rooted
`prf/dispute-ruling-finality.v1` artifact when eligible. Verification requires
`:finality` and invokes `validate-finality-against-context`, including the final
round and adjudication lineage checks. An ineligible inspection exits 1.

The supplied world is the context for these checks. These commands do not
authenticate that world, replay its history, verify external authentication
credentials or observe a chain. Chain finality remains explicitly
`:assumed-by-replay`. Appeal-bond accounting remains governed by its existing
protocol contracts; the CLI adds no new bond-release rule or finality gate.

## Forensic workflows

```sh
bin/prf forensic run --input /absolute/path/run.edn
bin/prf forensic validate --input /absolute/path/validate.edn
bin/prf forensic verify --input /absolute/path/verify.edn --json
bin/prf forensic reproduce --input /absolute/path/reproduce.edn
```

The existing `bb forensic:*` tasks and Python entry points remain available.
The registered commands invoke the existing Python modules in an explicit PRF
checkout. That checkout needs Python dependencies from
`test/forensic_python/requirements.txt`; run and reproduce also need Clojure.
There is no checkout creation or package installation during a command.

| Command | Required request fields | Optional fields |
|---|---|---|
| `forensic run` | `:repo-root`, `:run-request`, `:registry-snapshot`, `:evidence-policy`, `:output-base` | `:label`, `:sign`, `:signing-key-id`, `:tsa-url`, `:dry-run`, `:no-harden` |
| `forensic validate` | `:repo-root`, `:bundle` | `:run-dir` |
| `forensic verify` | `:repo-root`, `:run-dir` | `:public-key`, `:output`, `:expected-protocol`, `:require-protocol-semantics` |
| `forensic reproduce` | `:repo-root`, `:run-dir`, `:output-base` | none |

For example, `verify.edn` can contain:

```clojure
{:repo-root "/absolute/path/to/prf"
 :run-dir "/absolute/path/to/retained-run"
 :public-key "/absolute/path/to/trusted-ed25519.pub"
 :expected-protocol "sew/1"
 :require-protocol-semantics true}
```

Flags must be booleans. Run uses the existing shared-filesystem mode; its output
is written below the explicit output base and hardened unless `:no-harden true`.
Reproduce executes the selected source checkout and writes a new reproduction
directory. Validate and verify retain their existing assurance distinctions,
including `not-verified` for unavailable protocol validation. The outer result
contains the selected repo root, subprocess exit code, stdout and stderr. The
inner report remains unchanged. Scripts execute as argv without a shell.

## Protected pro-rata boundaries

```sh
bin/prf protocol pro-rata derive-operation --input /absolute/path/source.edn
bin/prf protocol pro-rata check-admission --input /absolute/path/admission.edn \
  --trust-context /absolute/path/trust.edn
bin/prf protocol pro-rata verify-execution --input /absolute/path/execution.edn
```

`derive-operation` takes the existing controlled-source witness shape:
`:realized-statement-input`, `:reserve-binding-root`, `:prepared-operation`,
`:economic-reserve-state`, `:beneficiary-mapping`, and `:openings`. It calls
`derive-controlled-source` and returns the unchanged fixture projection, with
the prepared-operation policy commitment checked against the reconstructed
statement. It derives commitments and entitlements for the existing controlled
conformance workload. It neither creates an on-chain prepared operation nor
confers claims authority. The embedded realized input keeps its existing JSON
wire field/value conventions, represented as EDN data.

`check-admission` takes `:artifact`, `:receipt`, `:realized-input` (a persisted
realized-input JSON file) and `:proof-dir`. The separate trust file contains
`:trust-policy` and `:program-registry`. The command independently reconstructs
the statement, requires the receipt to commit to the exact input bytes, checks
proof file integrity, then applies the existing signed external verifier receipt
admission check. The approved program, public values, statement, signature and
trust-policy checks remain owned by that implementation. A supplied `:verified?`
flag cannot establish admission. This is the existing
`allocation-proof/largest-remainder-deferred-pro-rata.v1` boundary, not the
source-bound entitlement proof profile. Trust-file selection is the operator's
external trust decision. The command does not execute an SP1 verifier or admit
state on chain.

`verify-execution` takes `:bundle-dir` and optionally `:source-bundle`. It calls
the existing retained local deployment reconstruction and reports its checks,
artifact digests, forensic view and unresolved gaps. Exit 0 means the retained
checks passed; remaining gaps and unavailable independent proof/storage
verification remain visible. It performs no fresh RPC observation.

| Component | Owner |
|---|---|
| Portable allocation and allocation-result identities | Existing allocation package |
| Effect compilation V2/V3 and EVM statement construction | PRF integration |
| Source-bound entitlement derivation and assurance policy | PRF integration |
| SP1 guests/hosts and proof artifacts | Existing coprocessor source/artifact boundary |
| Capital lifecycle transitions | Existing capital-lifecycle boundary |
| Solidity enforcement and live transactions | Existing EVM/deployment boundaries |

The command adapters change no dependency coordinates, canonical domains or
economic rules. Source derivation, receipt admission and retained execution have
different assurance scopes. None of these command additions establishes SP1 or
Solidity proof qualification or a production release.

## Deployment interface

The separate `protocol-deployments` repository now has
`npm run protocol -- commands --json`, with `resolve`, `deploy`, `deploy resume`,
`verify live`, `verify plan-execution`, validators and devnet commands. Its
catalog describes each command's side effects. Paths in that interface resolve
from its repository root. Existing npm scripts remain available.

The clean committed deployment base inspected here is
`f3b5c7bbf3dbc3950ae26f275a37a41233b21698`. PRF also retains a separately
qualified source overlay under `etc/deployments/baselines`; its extra command
names, including `verify:plan-report`, are not present in that committed base.
The new catalog lists only callable base commands. Integrating that overlay and
the deployment owner's current uncommitted changes remains a distinct source
integration gate. Approval signing/root derivation continues through the
existing `scripts.deployment.configuration-review` and
`scripts.deployment.successor-transition-review` interfaces in PRF. No approval,
deployment, package publication or change to cross-repository pins is implied.
