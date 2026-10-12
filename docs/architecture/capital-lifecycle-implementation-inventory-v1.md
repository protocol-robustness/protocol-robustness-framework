# Capital lifecycle implementation inventory

Status: **inventory for extraction planning; no runtime consumer**  
Contract: [`capital-lifecycle-package-v1.md`](capital-lifecycle-package-v1.md)

This inventory records where the current NOW/Protected Capital path is
implemented. It is not an instruction to move files as a unit. Repository roots
and language-specific adapters remain in their current locations until an
in-tree package boundary is implemented and checked for parity.

## Current implementation map

| Concern | Current implementation | Classification and extraction note |
| --- | --- | --- |
| Canonical beneficiary entitlement, set, and representation descriptor derivation | PRF `src/resolver_sim/allocation/source_bound_entitlement.clj`; Rust `coprocessor/core/src/source_bound_entitlement.rs` | Existing V1 canonical artifacts plus EVM commitment/table projections. The builders consume Shortfall allocation binding and realized allocation statement roots. Mixed concern; do not move the whole namespace/crate module to lifecycle. Preserve the current builders as compatibility adapters until ownership is split and vectors prove parity. |
| NOW source-bound derivation / public values | PRF `derive` / `derive-controlled-source`; Rust `derive` / `projection_json`; SP1 guests in `coprocessor/source-bound-entitlement-sp1-program` and `coprocessor/controlled-entitlement-source-sp1-program` | Proof-input and projection path, tied to existing public-value schemas and VKeys. Not a generic lifecycle API. Keep guest, public layout, and verifier identity stable during extraction. |
| Shortfall source observation and reserve binding | PRF `src/resolver_sim/shortfall/reserve_observation.clj`, `reserve_binding.clj`, `context_v2.clj`; allocation binding in `src/resolver_sim/shortfall/allocation_binding.clj` | Shortfall-owned source, scarcity, and application lineage semantics. A future `SourceBindingPort` adapter can depend on these contracts without making lifecycle depend on Shortfall. |
| Reservation accounting, reservation release/expiry, reserve observations, and reserve custody | Solidity `OnchainShortfallReserveV2` in companion `prf-evm/src/applications/shortfall/OnchainShortfallReserveV2.sol` | Economic lifecycle rules are present, but colocated with the Shortfall reserve implementation and EVM custody/accounting. Methods include `reserveForOtherObligation`, `releaseOtherObligationReservation`, `expireOtherObligationReservation`, `economicState`, and `authoritativeObservation`. Extract pure transition rules separately; retain this contract as an adapter and do not change its behavior as part of packaging. |
| NOW ratification and source-bound funding | Solidity `OnchainShortfallReserveV2.ratifyAndFund` and `ratifyAndFundEntitlement`; controllers under `prf-evm/src/applications/shortfall/` | Shortfall-specific source proof, reserve, allocator, and entitlement bindings sit at the execution edge. These are adapters/integration contracts, not a generic lifecycle API. |
| Activation, cancellation/refund, and withdrawal realization | Solidity `OnchainShortfallReserveV2.activateEntitlement`, `cancelAndRefundEntitlement`, `cancelAndRefund`; allocator methods in `IShortfallAllocatorV2` | EVM state and custody implementation currently define these observable transitions. Activation consumes an entitlement representation; cancellation/refund remains pre-activation. The reusable lifecycle contract needs transition vectors derived from current tests before implementation. |
| Entitlement representation / commitment table | PRF derivation plus Solidity `CommittedImmutableEntitlementRepresentationV1` in `prf-evm/src/applications/pro-rata/` | Representation is an EVM commitment adapter for the current representation profile. Its path under `pro-rata` does not establish ownership of lifecycle admission, liabilities, or withdrawal rules. Preserve existing identity and commitment calculations. |
| Reusable assurance admission and safe consumption | Solidity `SourceBoundProRataAssuranceV2`, `SourceBoundProRataRatificationControllerV2`, and related verifier/admission interfaces in `prf-evm` | AO/protected-operations capability is partly composed with the current Shortfall execution route. Lifecycle should call a protected-operations port; it must not duplicate freshness, verifier, proof, nonce, or route checks. |
| Clojure lifecycle transition kernel | `packages/capital-lifecycle/clj/src/prf/capital_lifecycle/transition.clj` | New, non-runtime initial transition slice. Consumes explicit admission/custody outcomes; does not derive source or allocation bindings. |
| Rust lifecycle transition kernel | `packages/capital-lifecycle/rust/src/lib.rs` | New, non-runtime implementation with matching shared vectors. Clojure/Rust parity is tested only for the current 18 transition cases; this is not yet complete conformance for the full normative lifecycle. |
| Capital lifecycle canonical artifacts for cancellation/consequence, withdrawal realization, and realized settlement | No adopted definitions found in the canonical artifact inventory | Missing by design in the current proposal. Preserve normative NOW requirements, but do not mint identities until a concrete canonical preimage/schema is reviewed and independently vectorized. |

## Boundary consequence

There is no existing self-contained capital-lifecycle source cluster to move
without bringing Shortfall semantics, EVM custody, proof plumbing, or the current
pro-rata/NOW derivation path with it. The current implementation is an
integration path spanning semantic owners; source location must not turn that
path into shared ownership.

The next code increment should extend and type the pure lifecycle transition
package boundary in-tree, initially with the state/command slice and shared
conformance fixtures already added. EVM custody, Shortfall source binding,
allocation-basis construction, and protected-operations authorization remain
injected ports. Derive expected transition outcomes from current
`prf-evm` tests and freeze those as language-neutral fixtures before porting
logic. Keep the existing Clojure and Rust entitlement derivation untouched
until a separate adapter split demonstrates that the present roots and proof
public values are unchanged.

## Dependencies and gates before external extraction

1. The lifecycle core must build without depending on `resolver-sim.shortfall`,
   `resolver-sim.allocation`, EVM libraries, SP1, or deployment packages.
2. Clojure and Rust must pass the same accepted/rejected transition fixtures.
3. The `prf-evm` adapter tests must continue to prove the existing transitions
   and identities without Solidity economic changes.
4. Current entitlement, allocation-binding, reserve-observation, configuration,
   and proof identity fixtures must remain byte-for-byte stable.
5. External repository extraction follows the in-tree boundary, compatibility
   re-exports, and a pinned dependency release.
