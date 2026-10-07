# Accounting arithmetic proof foundation

The observed-run SP1 program proves canonical lineage, not accounting sums.
Its frozen 384-byte public values and guest semantics are unchanged. A remotely
produced validation receipt or implementation agreement also does not by itself
replace the accounting V1 arithmetic validation floor.

This separate, closed statement is the prerequisite for future proof-backed
coordinator admission. **Admission and reconciliation are currently unchanged**:
they still recompute arithmetic. No generated proof or successful verification
receipt is produced by this foundation.

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

## Arithmetic domain

This first statement deliberately supports checked signed-64-bit source values
and sums only. The host rejects BigInt source representations and overflow as
`:not-comparable`; it does not change accounting V1's wider integer semantics.
The Rust validator rejects out-of-range JSON values and checked-add overflow.
Both full canonical serial order and selected partition order must fit this
domain. It is not a general algebra, fixed-point or floating-point framework.

## Validation and remaining work

The native conformance executable `accounting_arithmetic_statement` exercises
the exact function called by the SP1 guest; it is **not a prover**. Tests compare
its derived bytes to Clojure and reject substitutions, false sums and omitted
partitions even when the attacker recomputes self-roots.

The actual RISC-V ELF builds using the repository's SP1 6.3.1 toolchain:

```sh
cd coprocessor/accounting-arithmetic-sp1-program
~/.sp1/bin/cargo-prove prove build --locked --packages accounting-arithmetic-sp1-program
```

This is compilation, not proof generation or verification.

Remaining: generate and cryptographically verify real proofs; record
program/ELF/VKey/public-values/proof identities; bind verifier
registry selection; compose verified arithmetic with run assurance; then add an
explicit proof-backed admission path. Existing V1 receipts cannot silently
change from “arithmetic recomputed” to “proof checked.” Reconciliation's serial
equivalence obligation must likewise be preserved before eliminating its full
serial fold. This foundation alone makes no throughput claim.

The initial witness contains the full request and plan. It does not yet optimize
prover work or repeated source hashing. Future coordinator scaling also requires
reuse of already-validated immutable inputs by root; proof verification alone
does not eliminate every O(source-row-count) integrity check.
