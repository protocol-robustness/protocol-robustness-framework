# Realized Allocation Execution Projection V1

**Status: frozen execution-projection interface.** This document freezes the
source-of-truth classification and wire contract. It does not change
`realized-allocation-statement.v1`; an EVM transfer remains authorized only by
the separate allocator activation and claim checks.

## Layers

```text
realized-allocation-statement.v1
    portable realized allocation semantics

realized-allocation-execution.v1
    canonical execution companion: mapping, asset, and operation target

execution public-values projection
    compact, proof-bound EVM values derived from statement + companion

allocator operation
    application acceptance, custody, and residual-recipient policy
```

The execution projection is not another canonical realized-allocation statement.
It is a derived, proof-bound EVM projection of a canonical statement and its
canonical execution companion.

## Source-of-truth classification

| Value | Class | Source and rule |
|---|---|---|
| `claim/id` | A | Existing canonical allocation semantic identity. It remains the executable claim identity unless a demonstrated uniqueness problem requires a new version. |
| realized `filled` amount | A → D | Canonical realized allocation result, projected as an executable base-unit amount. |
| allocation row dispositions | A | Existing realized allocation semantics; they determine which rows are executable under the supported profile. |
| `claim/id → EVM recipient` | B | Canonical execution-companion input. It must be exact, complete for executable filled claims, non-zero, unique by claim ID, and canonically ordered. It must not be derived from `economic-owner-id`. |
| ERC-20 token address | B | Canonical execution-companion input. V1 quantities are raw token base units; `decimals()` is not a semantic source of truth. |
| allocator address | B | Explicit canonical execution-target field. |
| chain ID | B | Explicit canonical execution-target field, represented as an unsigned EVM-domain integer. |
| operation ID | B | Explicit canonical execution-target field. |
| available amount | B | Explicit canonical execution-target field in raw token base units. The projection independently checks it against the supported-profile realized total. |
| `operationBinding` | D | Deterministic EVM-local binding derived from explicit target fields under a frozen domain, ABI encoding, field order, widths, and address encoding. |
| execution claim leaf | D | Deterministic application-specific authenticated-membership leaf derived from operation binding, claim ID, mapped recipient, and filled amount. |
| claim root | D | Deterministic aggregation over canonically ordered execution claim leaves. It is distinct from `realized-results-root`. |
| allocation count | D | Number of executable claim rows after supported-profile projection. |
| total allocated | D | Exact sum of executable filled amounts. |
| residual amount | D | Exact `availableAmount - totalAllocated`, subject to the supported-profile invariant. |
| residual recipient | C | Immutable allocator application policy. It is not proof-bound allocation semantics in V1. |
| funding received, token balance deltas, claimed flags, residual-withdrawn flag | E | Runtime-only Solidity facts; they are never SP1 inputs or canonical projection values. |

## Narrow V1 realization profile

Execution Projection V1 supports only the already-admitted realization profile:

```text
largest-remainder-deferred-pro-rata.v1
no effective caps
no redistribution
no haircuts
```

The implementation must establish from the shared realization semantics, not
from diagnostic output, whether `totalAllocated == availableAmount`. If that
holds for this profile, `residualAmount == 0` is a tested projection invariant.
No residual recipient belongs in the projection merely because an allocator may
need one later.

## Non-reuse decisions

The following primitives remain distinct and are not execution-projection
identities:

- `prf/commitment-composition.v1`;
- command/member combination and command concatenation lineage;
- semantic composition;
- requested combination and compiled plan;
- programme progress and `progress-atom`.

The claim root is an application-specific authenticated membership aggregation.
It is neither `realized-results-root` nor a generic composition/lineage object.

## Programme and progress

Execution projection is a derived evidence/output artifact. It does not add a
programme stage in V1. SP1 proof/admission remains an external assurance path;
allocator activation is application reliance.

The separately deferred task is to wire concrete statement/proof/verification/
admission artifacts into `run-programme` only if a programme receipt is intended
to assert cryptographic proof/admission completion.

`progress-atom` remains operational-only and is excluded from companion roots,
claim roots, public values, operation bindings, and programme identity.

The frozen Rust/SP1 wire contract below is the sole normative V1
companion shape, derivation, and public-value definition. Earlier SHA-256
companion-root and concatenation derivations are superseded and are not V1
identities or inputs.

## Frozen Rust/SP1 V1 wire contract

The independent Rust implementation accepts exactly one JSON input object:

```json
{
  "realized-statement-input": { "...": "the raw realized-statement.v1 input" },
  "execution-companion": {
    "schema-version": "realized-allocation-execution.v1",
    "claim-recipient-mapping": [
      {"claim/id": "A", "recipient": "0x1111111111111111111111111111111111111111"}
    ],
    "token-address": "0x4444444444444444444444444444444444444444",
    "execution-target": {
      "allocator-address": "0x5555555555555555555555555555555555555555",
      "chain-id": "1",
      "operation-id": "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "available-amount": "100"
    }
  }
}
```

The statement input is re-parsed and re-realized by the guest; no supplied
statement root, result rows, operation binding, claim leaf/root, count, or
aggregate is accepted. Objects have closed shapes. Addresses are nonzero,
40-digit lowercase `0x` hex; `operation-id` is a lowercase `0x` bytes32; and
amounts and chain IDs are canonical unsigned decimal strings (no sign or
leading zeros). The current realization core supports amounts through `u64::MAX`
and, independently, rejects statement amounts outside its current `i64` range.
`chain-id` is nonzero. The mapping is strictly sorted by UTF-8 `claim/id` byte
order, unique, and exactly covers the positive-filled rows (and no others).

The raw source statement must pass the frozen narrow profile. Positive-filled
rows are executable; zero-filled rows are excluded. The target available amount
must exactly equal the independently re-derived total allocated. Thus
`residualAmount == 0` in V1.

### EVM derivation

`keccak256` below is the EVM Keccak-256 function. `W(x)` means its 32-byte hash
of the UTF-8 literal `x`; it is a `bytes32` domain word. `U(x)` is a 32-byte
big-endian `uint256` word, and `A(x)` is a 32-byte ABI `address` word (12 zero
bytes followed by the 20 address bytes). Concatenation of these static words is
exactly Solidity `abi.encode(...)` for the listed arguments.

```text
operationBinding = keccak256(abi.encode(
  W("prf.realized-allocation-execution.operation-binding.v1"),
  U(chainId), A(allocator), A(token), operationId, U(availableAmount))

claimIdHash = keccak256(UTF8(claim/id))
leaf = keccak256(abi.encode(
  W("prf.realized-allocation-execution.claim-leaf.v1"),
  operationBinding, claimIdHash, A(recipient), U(filled)))
```

Leaves are in the mapping's UTF-8 claim-id order. The claim root is an ordered
binary Merkle tree. At every non-singleton odd level, duplicate the final node;
then hash each adjacent pair as:

```text
node = keccak256(abi.encode(
  W("prf.realized-allocation-execution.claim-node.v1"), left, right))
```

For an empty leaf list, the root is
`keccak256(W("prf.realized-allocation-execution.claim-root-empty.v1"))`.

### SP1 public values

The separate `realized-execution-sp1-program` commits exactly 384 bytes: the
static ABI encoding of this twelve-word tuple, in order:

```text
bytes32 W("realized-allocation-execution-public-values.v1")
bytes32 statementRoot
bytes32 operationBinding
bytes32 claimRoot
address token
address allocator
uint256 chainId
bytes32 operationId
uint256 availableAmount
uint256 allocationCount
uint256 totalAllocated
uint256 residualAmount
```

This is a distinct program and schema from
`realized-statement-sp1-program` / `evm-bytes32-v1`; the latter remains the
unchanged 32-byte statement-root commitment.

### Normative test vector

For the existing all-active statement fixture (statement root
`c22333a16df1c1efa352e9daab42ccbd78f4a1d7530ee3ed3cf7527ba62cbd81`), with
recipients `0x1111…1111`, `0x2222…2222`, `0x3333…3333`, token `0x4444…4444`,
allocator `0x5555…5555`, chain ID `1`, operation ID `0xaaaa…aaaa`, and amount
`100`, Rust derives:

```text
operationBinding = 2af0c75e9385b5b40631c6b05dcc9271b1398c96373899269d061beec929027f
claimRoot        = 407fae5b4caeb20d77a030a74fa4e2659f7d75505ade3645c825da35ab98afa3
leaf(A)          = f80d653aa6c98215e9d3a574dccb08c7b0772dddfff2d4404becf5f08bd4f30a
leaf(B)          = 23472ad42041455ea0d2ae5aaf7b6cb8f440622e2b53ad010bd0bdf8c566d227
leaf(C)          = f08fd8ef2d0ea21606cc29d5ccfb387800f0fa09d79858ee4432e903ed98e612
```

`prf-coprocessor@e2e4e9b25622c52eb028fc373a66e81b3982e933/core/tests/realized_execution_test.rs` asserts the complete
384-byte public-value hex vector, not only its component fields. The shared canonical fixture is
`prf-coprocessor@e2e4e9b25622c52eb028fc373a66e81b3982e933/core/tests/fixtures/realized-execution-all-active.json`. To execute
the SP1 guest without proving, use:

```bash
cd coprocessor
cargo run --locked --release -p allocation-sp1-script --bin realized-execution-prove -- \
  --input core/tests/fixtures/realized-execution-all-active.json --execute
```

To export the real EVM Groth16 fixture, use the locked SP1 6.3.1 toolchain and
the same input:

```bash
cargo run --locked --release -p allocation-sp1-script --bin realized-execution-evm -- \
  --input core/tests/fixtures/realized-execution-all-active.json \
  --fixture ../../prf-evm/src/fixtures/realized-execution-proof-fixture.json
```

The exporter rejects any difference between native derivation, actual guest
public values, and the pinned 384-byte vector before writing a fixture. The
pinned program identity is:

```text
VK          = 0x00c6ef567e2dba426bca4e4280c5a3a5b85ba6d5d090a9b2b4dcb7762a36739a
ELF SHA-256 = 0x549f67119f3ef3ffb7f6179ed88fa0a9604707c08df3459287831643f58ce70a
```
