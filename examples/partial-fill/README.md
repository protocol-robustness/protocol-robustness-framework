# Partial Fill: controlled local qualification

The source requests are Alice 50, Bob 30 and Carol 20. A local standard ERC-20
reserve is actually funded with 80 integer base units. Ten are protected; the
live available balance is 70. The committed pro-rata policy derives 35, 21 and
14. The source initiator commits the complete canonical realized statement in
the prepared operation's `policyRoot`; the controlled SP1 program requires that
commitment to match its reconstructed input. This is explicitly a controlled
source authority, not a claim that external requests or liabilities are true.

## Gap audit and smallest integration

| Boundary | Existing capability | Addition in this example |
| --- | --- | --- |
| Requests and policy | Canonical realized statements and entitlement derivation | An explicit `unmet-request` disposition, plus initiator commitment using the existing controlled-source program |
| Integer allocation | Existing largest-remainder pro-rata primitive | Reused; exact 35/21/14, zero rounding residual |
| Reserve availability | `OnchainShortfallReserveV2` balance/reservation/protected-capital commitment | Actual funded local token balance; exported from the active contract methods |
| Proof and admission | SP1 Groth16, nine-word adapter, registry, configuration executor, admission | Fresh controlled-program identity and two operation-specific proofs |
| Governance identity | Existing canonical protocol/configuration/chain-genesis builders | Local configuration derived from actual verifier registry and contract identities; single test governor explicitly scoped |
| Ratification and withdrawals | Existing successor assurance/controller/allocator/representations | One focused integration harness and operation-bound local dispatcher; no replacement protocol |
| Stale assurance | Existing guarded stale-replacement lifecycle | Rejection and recovery with a fresh real proof, while preserving the live reserve check |
| Public evidence | Existing website manifest/download conventions | One Partial Fill route and one linked qualification bundle |

## The 30 unfilled units

This example selects `fail-action-policy = {"mode":"unmet-request"}` explicitly.
The existing canonical realization schema records 15/9/6 in `unrealized`;
`deferred` and `haircut` are zero. These are unmet requests. They are not funded
entitlements, reserve assets, recognized losses, cancellation rights or future
payment obligations. The live funded withdrawal liability is 70, then zero
after all three withdrawals. The pre-existing deferred profile remains
unchanged; its continuing obligations must not be relabelled as this profile.

The stale-recovery case changes gross reserve balance to 81 and separately
reserves one unit: `81 - 1 - 10 = 70` remains available. The replacement
proof binds the new revision and snapshot; it is not a second settlement or
replenishment of the 30 unmet requests. This operation commits a fixed source
and 70-unit allocation. Recovery when available capital itself changes would
require source-consistent requalification/new operation; that is not qualified
by this fixed-capital example.

The reserve's ten protected units are a separate quantity. They are not the
30 unfilled units. The terminal accounting is:

```text
100 requests = 70 allocated + 30 unmet
70 funded entitlements = 70 withdrawn + 0 remaining funded liability
80 deposited base units = 70 beneficiary balances + 10 protected reserve + 0 allocator
```

Under the controlled fixture's stated assumption that the 80-unit deposit is
the complete source capital for these requests, the unmet 30 decomposes as
20 units not deposited against requests (`100 - 80`) plus 10 deposited units
held behind the configured protected-capital floor (`80 - 70 available`).
The 10 units remain reserve assets; this qualification does not establish
their beneficial owner, economic purpose, or permitted release/refund
authority. The decomposition is not a claim that 30 units were lost.

## Intermediate accounting states

`accounting-states.json` and `qualification.json` retain actual contract getter
observations after each local Foundry call boundary: source prepared, proof
admitted, allocator funded, entitlements activated, Alice and Bob withdrawn,
and operation closed. Each snapshot checks custody conservation across reserve,
allocator and beneficiary token balances. After activation, each also checks
that the 70-unit liability equals amounts paid plus liability remaining.
Funding and activation are separate states: the funded entitlement table holds
70 before activation, while the liability is zero until activation records it.

These snapshots are taken inside a local EVM test. They are not separate mined
transactions, public-chain state observations, or evidence that the protected
10 can be released to a particular party. The transaction log and terminal
balances remain the evidence for actual local token movements.

## Reproduction

The descriptive `policy.json` is a readable projection. `policy.edn` preserves
the canonical keyword namespaces and exact value types used for the descriptor
root. Recompute its `:evidence-content` domain hash through the retained PRF
canonical API; do not hash the JSON serialization as if it were canonical bytes.
The source statement separately binds the executable allocation and disposition
policy roots.

The owning EVM integration is `prf-evm/test/PartialFillControlled.t.sol`.
`materialize.clj` invokes the existing PRF canonical/genesis and Clojure
entitlement implementations. `controlled-entitlement-source-replay` is a thin
CLI over the existing Rust derivation, not another canonical encoder. Their
entitlements, roots and all 288 public bytes must agree before proving.

The staged export tests do not admit proofs or distribute funds. They export
the actual registry, genesis runtime identities and reserve snapshot, after
which the Clojure builders compute the canonical roots. The proof verifies
through the official SP1 v6.1.0 Solidity verifier and the configured registry;
no accepting verifier stub participates in this harness. One negative case conserves
70 while substituting 40/16/14; the committed entitlement table rejects it.
A separate reserve amount-guard check supplies an 80-unit request under the
configured reserve authority and requires atomic rejection.

Set `SP1_GROTH16_ARTIFACTS` to the actual installed SDK circuit directory
(for this retained run: `$HOME/.sp1/circuits/groth16/v6.1.0`). The circuit VK
hash is verified by the SDK and the official Solidity verifier.

Run `run-local.sh NEW_OUTPUT_DIRECTORY` from this PRF checkout, with neighboring
`prf-evm` or `PRF_EVM_ROOT` set to its path. The script requires Clojure, Rust,
SP1 6.3.1 and Foundry with solc 0.8.33. It refuses an existing output directory.
It uses CPU Groth16 proving, sequentially, with eight Rayon worker threads by
default to limit peak memory. The default Go worker limit is also eight, with
`GOMEMLIMIT=20GiB` as a soft heap budget; these are scheduling/GC settings, not
changes to the proof program, verification or policy. CPU worker queues are
bounded to one core/proof worker. Groth16 proof bytes are randomized; independently
reproduced proofs need not be byte-identical to the retained proofs. For the published source archives, start from the
retained EVM base revision and initialize its dependencies at the revisions in
`toolchain.json`, then overlay the two source archives in their owning checkouts.
The guest ELF is also retained; its SHA-256 must equal the registered identity. The existing SDK compressed/shrink/wrap APIs run first and persist checkpoints.
A fresh lightweight process invokes the same native Groth16 FFI and SDK
cryptographic verification, preventing SP1 trace caches from competing with
the large Gnark circuit/key. The original monolithic SDK path encountered an
out-of-memory kill on this host; its wrapper was retained and completed through
that same FFI. This does not replace the circuit, guest, VKey or public values.

Retained proof fixtures also allow re-running the
EVM qualification without regenerating proofs:

```sh
# In prf-evm, with the retained files installed under test/fixtures/partial-fill:
PARTIAL_FILL_STAGE=2 forge test --match-path test/PartialFillControlled.t.sol \
  --match-test 'test_(Qualification|Reject)' --offline -vv

# In PRF:
cargo run --manifest-path coprocessor/Cargo.toml -p allocation-kernel \
  --bin controlled-entitlement-source-replay < witness.json
clojure -M examples/partial-fill/materialize.clj witness EVIDENCE_DIR context.json
```

## Assurance boundary

The retained result is a local Foundry EVM execution, with real proof verification
and contract/token state changes in that execution. It is not a mined public
chain transaction, Base Sepolia deployment, production governor qualification,
or cryptographic chain-state proof. Base deployment remains a separate task.
The local test governor intentionally defines the requests and admits the exact
program/configuration. An observer can reproduce this bounded authority and
the resulting calculation/execution, not infer truth about outside obligations.

The controlled source has no continuing legal obligation semantics. Request
authority is the local initiator; external requests would require a separately
qualified source adapter. The canonical input also carries 100 units of
eligible capacity; this describes the original request population, not funded
capital. Available reserve capital is separately 70.

All three withdrawals request a `Required` caller minimum and supply valid
claim proofs and table openings. Requested/Required are proof-enforcement modes;
they are distinct from requested token quantities.

## Protected sponsor capital policy and boundary

The controlled fixture now declares a versioned policy for the protected 10:
Carol (the recorded Carol beneficiary address) is the beneficial owner and
refund beneficiary of restricted sponsor capital. The local test governor is the
sponsor: its mock test tokens are minted locally, and the ERC-20 `Transfer` plus
reserve `Deposited` events verify that it transferred 80 units to the reserve.
The fixture policy assigns 10 of that deposit to Carol; that assignment is a
policy declaration, not something inferred from the token transfer or a prior
historical fact. The record is tied to chain 31337, the reserve, token and
operation, and carries a keccak256 ABI-encoded policy root. Evidence hashes bind
the policy and funding attribution records into the retained demo manifest.

This policy excludes the 10 from claimant entitlements, fees, protocol income
and available allocation capital. The ratification controller is designated to
authorize terminal finalization but is not the beneficial owner. This remains a
declared fixture policy only: the frozen `OnchainShortfallReserveV2` stores an
immutable amount, not this policy, and has no terminal refund function. The
seven retained states therefore end at **allocator closure**, with 10 still in
reserve custody. They do not demonstrate **final reserve settlement** or a
refund to Carol. The reserve rejects additional allocation below the configured
floor, but policy-specific beneficiary enforcement, early-release rejection,
substitution rejection and double-release rejection cannot be qualified without
a release route.

A bounded successor should bind the versioned policy at reserve construction or
one-time pre-funding configuration; scope the reserve to the named operation;
track sponsor transfer attribution; prevent new operations after finalization;
and allow only the ratification controller to release the exact protected
amount to the recorded Carol address after the allocator is closed, claimant
liability is zero, and all reserve holds are terminal. It should reject early
release, beneficiary substitution, repeated release and release while any
reservation or scoped operation remains unresolved. The accounting journal
should wait until that path has retained successful local-EVM evidence. A code
level assessment of why V2 or a simple subclass cannot safely provide this
release is in [the protected-capital gap assessment](protected-capital-release.md).
