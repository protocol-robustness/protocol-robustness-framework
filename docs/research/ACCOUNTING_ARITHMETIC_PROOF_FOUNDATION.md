# Accounting arithmetic proof foundation

The observed-run SP1 program proves canonical lineage, not accounting sums.
Its frozen 384-byte public values and guest semantics are unchanged. A remotely
produced validation receipt or implementation agreement also does not by itself
replace the accounting V1 arithmetic validation floor.

This separate, closed statement now has an opt-in proof-backed coordinator
admission path. Existing V1 admission and reconciliation remain unchanged and
continue to recompute arithmetic; the new path is additive and does not
reinterpret `research-native-accounting-validation.v1`.

## Authoritative derivation

`accounting-proof-statement/derive-statement` and `proof-input` take the existing
request, reduction contract, complete partition plan and valid partial artifact.
They accept no independently supplied public-root tuple, expected sum, runner
assertion or `validated?` flag. Host validation remains mandatory.

The guest uses the existing Rust canonical hashing ABI in `allocation-kernel`.
It independently checks artifact self-roots, the exact closed reduction
contract, request/plan/unit/partial bindings, sorted unique sources and partition
indices, complete nonoverlapping plan coverage, selected source membership and
the actual per-category sum. It rejects duplicate canonical map keys.

The guest derives five raw SHA-256 roots in order (160 bytes):

1. accounting request;
2. partition plan;
3. selected work unit;
4. reduction contract;
5. canonical accounting partial.

This statement means that the committed canonical partial is the declared
partition's sum-by-category result under a complete exactly-once plan. It does
not prove source claims true, runner honesty, execution lineage, confidentiality,
primitive acceptance, allocation correctness or application authority.

The semantic partial excludes runner/implementation/assurance provenance. Two
valid executions of the same unit therefore yield identical arithmetic public
values, while their existing execution/assurance artifacts remain distinct.
Future admission must bind verified arithmetic public values to the exact
assured execution's partial; neither proof can substitute for the other.

## Proof-backed admission

`native-accounting-work/validate-execution-lineage` checks the self-rooted
execution, run, fixed-validation and run-assurance bindings without asserting
the reported sum. It is explicitly insufficient for ordinary admission.
`accounting-proof-admission/verify-and-build!` then derives the guest witness
from the coordinator's rooted work and claimed partial, binds expected public
values to that lineage, and invokes the real SP1 verifier against the pinned
program/ELF/VKey. Only successful cryptographic verification yields
`accounting-proof-backed-admission.v1`. The artifact links the request, plan,
unit, run, partial, generation receipt and verification evidence. The
coordinator's `admit-native-accounting-proof!` ingests the partial only after
this path succeeds; duplicate admission is idempotent and conflicting partials
remain hard conflicts. A separate authenticated `/v1/accounting-proof` endpoint
now carries the execution, generation receipt, metadata and proof bytes (base64
inside the existing EDN transport envelope). The coordinator resolves the work
from its persisted assignment intent, stages received bytes in a temporary
directory, and applies its own verifier configuration. Sender-local paths and
sender-selected verifier policy are never trusted. Ordinary `/v1/accounting`
delivery is unchanged and continues to use V1 recomputation.

Real proof verification takes materially longer than ordinary result
delivery. Callers should configure the runner endpoint timeout explicitly, for
example with `(coordinator-endpoint coordinator secret 180000)`. This is
runtime transport configuration and is excluded from proof/accounting roots.
A timed-out sender retains the content-addressed proof and may redeliver the
same bytes; coordinator admission remains idempotent. The transport regression
test exercises a slow first response and successful retry with byte-identical
proof delivery. The external SP1 host process also has a runtime-only
600,000 ms deadline, configurable with `PRF_ACCOUNTING_SP1_HOST_TIMEOUT_MS` or
the dynamic `*host-command-timeout-ms*` binding. On expiry the host process and
its descendants are terminated and no verification evidence is emitted.

This path proves the checked-i64 arithmetic statement for the exact partition.
It does not prove source-claim truth or the full accounting result. Global
reconciliation still requires exact plan coverage and currently performs its
existing serial-equivalence check. The transport API accepts an already
generated proof bundle; it does not invoke the prover on runners, persist proof
files inside the EDN journal, or alter the ordinary result-delivery route. The
runner persists proof bytes in a content-addressed sibling artifact directory
before journaling the pending proof delivery. Restart retries the same bytes,
receipt and execution without re-running accounting or proof generation. This
is same-journal local durability; loss of that runner filesystem still requires
regeneration or external artifact recovery. The new endpoint therefore closes
transport/admission and same-journal retry, but does not remove all
coordinator-side O(source-count) reconciliation work or demonstrate a
throughput improvement.

## Arithmetic domain

This first statement deliberately supports checked signed-64-bit source values
and sums only. The host rejects BigInt source representations and overflow as
`:not-comparable`; it does not change accounting V1's wider integer semantics.
The Rust validator rejects out-of-range JSON values and checked-add overflow.
Both full canonical serial order and selected partition order must fit this
domain. It is not a general algebra, fixed-point or floating-point framework.

## Real proof host

`resolver-sim.research.accounting-arithmetic-sp1-host/prove-and-verify!` accepts
only the existing native accounting work and its execution artifact, validates
that lineage, and derives the witness through `accounting-proof-statement`.
It creates a real SP1 proof with the pinned 6.3.1 host SDK, checks the exact
160-byte guest public values, invokes cryptographic verification, then writes
the existing `research-proof-generation-receipt.v1` and
`research-proof-verification-evidence.v1`. The receipt binds the existing run
assurance, dependency closure, job, environment, prover, verifier registry,
program, public values and proof artifact.

The new host lives in a small separate Cargo package so accounting core proofs
do not require the allocation script package's native Groth16/gnark feature.
This host crate is compile-checked. No proof receipt is emitted until the real
prover and verifier finish successfully.

## Validation and remaining work

The native conformance executable `accounting_arithmetic_statement` exercises
the exact function called by the SP1 guest; it is **not a prover**. Tests compare
its derived bytes to Clojure and reject substitutions, false sums and omitted
partitions even when the attacker recomputes self-roots.

The actual RISC-V ELF and host crate build using the repository's SP1 6.3.1
toolchain:

```sh
make setup-coprocessor
PRF_COPROCESSOR_ROOT="$(./scripts/resolve-prf-coprocessor.sh)"
CARGO_HOME=/tmp/chome GOFLAGS=-buildvcs=false \
  cargo check --locked --manifest-path "$PRF_COPROCESSOR_ROOT/Cargo.toml" \
  -p accounting-arithmetic-sp1-host
```

A real local qualification run completed for the small native-accounting
fixture. It produced and cryptographically verified a proof, with a valid
generation receipt (`sha256:b0cc271a…`) and verification evidence
(`sha256:0e3d1965…`). The proof artifact root was
`sha256:68682777…`; the public-values root was `sha256:b86f80f0…`. Artifacts
were written under `/tmp/prf-accounting-arithmetic-proof`, not committed as
research results. Release compilation first exhausted the root filesystem
while building SP1's nested helper; retrying with the already-built SP1 helper
through `SP1_CORE_RUNNER_OVERRIDE_BINARY` and placing the target on the
workspace mount succeeded.

Proof metadata records the exact ELF and VKey used. A proof produced against an
older embedded ELF/VKey is intentionally rejected by a verifier built with a
different program identity, even if the arithmetic input and resulting scalar
match. Fresh proof generation and coordinator verification have passed over
the authenticated HTTP delivery endpoint using the same current host binary.

A clean release rebuild of `accounting-arithmetic-sp1-host` under a separate
`CARGO_TARGET_DIR` produced the same guest ELF bytes as the previously
qualified build:

```text
sha256: a152827b3e500afb583c203f890a17225612a0ec2e31cc5745bff7f4daf57849
```

This qualifies byte-for-byte guest ELF stability across target-directory
relocation using the same pinned SP1 6.3.1 dependencies, Rust toolchain,
Cargo home and source checkout. It does not qualify independent Cargo caches,
toolchain versions, operating systems, or fully hermetic transitive source
closure. Proof metadata still records exact ELF and VKey identities; retain the
ELF/program artifact associated with each recorded identity for historical
re-verification across future toolchain changes.

The opt-in real proof-delivery qualification generates a fresh proof, delivers
it to a live coordinator, checks successful cryptographic admission, then
checks wrong proof bytes, modified public values, program/VKey substitution,
and cross-run substitution against the authoritative verifier API. These
substitution negatives fail lineage/metadata preflight before launching the
cryptographic verifier; the positive coordinator admission performs real
cryptographic verification.

```sh
make setup-coprocessor
PRF_COPROCESSOR_ROOT="$(./scripts/resolve-prf-coprocessor.sh)"
SP1_PROVER=cpu \
PRF_RUN_REAL_SP1_DELIVERY_E2E=1 \
PRF_ACCOUNTING_SP1_HOST_BIN="$PRF_COPROCESSOR_ROOT/target/release/accounting-arithmetic-sp1-host" \
clojure -M:test -e '(require (quote clojure.test) (quote resolver-sim.research.accounting-arithmetic-sp1-host-test)) (let [r (clojure.test/run-tests (quote resolver-sim.research.accounting-arithmetic-sp1-host-test))] (System/exit (+ (:fail r) (:error r))))'
```

Existing V1 receipts cannot silently change from “arithmetic recomputed” to
“proof checked.” Proof-backed admission is a separate artifact and API.
Reconciliation's serial-equivalence obligation remains in place; this proof
run alone makes no throughput claim.

The initial witness contains the full request and plan. It does not yet optimize
prover work or repeated source hashing. Future coordinator scaling also requires
reuse of already-validated immutable inputs by root; proof verification alone
does not eliminate every O(source-row-count) integrity check.
