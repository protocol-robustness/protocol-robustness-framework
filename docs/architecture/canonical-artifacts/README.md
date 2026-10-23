# Canonical Artifact Registry: NOW extraction proposal

Status: **PROPOSED; zero migration; no runtime consumer**.

This inventory was read from the current PRF `agent-c` working tree and the
companion `/home/user/Code/prf-evm` working tree. The last PRF snapshot inspected
was `6442af9a`; the companion HEAD was
`28b63bacf26c5ced89ceb7c617e61308b56b57c5`. Both trees contain work beyond those
snapshots. These revisions identify the inspection context, not immutable
reproduction inputs for the proposed manifest. No companion files were edited.

The authority for NOW production semantics is
[the Protected Capital freeze](../PROTECTED_CAPITAL_PRO_RATA_V2_SEMANTICS.md),
including its B1, B2 and B3 additions. Older design documents are supporting
records; their stale implementation-status prose does not override that record
or the inspected code.

## Incremental package extraction

The independently loadable Clojure source package now contains normalization,
row ordering, policy/result projections, witness derivation, single-pass
allocation, and active-set cap redistribution. PRF continues to supply explicit
execution and hash adapters. The manifest records those package implementations
alongside the retained PRF entry points and portable reference implementation.
Its 34 identity definitions, owners, domains, baseline pin, and proposed status
are unchanged.

See the [pinned allocation repository](https://github.com/protocol-robustness/prf-pro-rata-allocation/tree/48e0b362748cd39d09994a8aa1496c668199aa74)
for the portable package boundary, adapter contracts, conformance ownership,
and remaining release gates. Its source qualification does not promote release,
SP1, or Solidity proof qualification.

## Decisions and extraction blockers

1. `canonical` owns encoding, hash schemes, domain/intent registration mechanics
   and definition validation. A domain declared in `hash/canonical.clj` does
   **not** confer ownership of its economic semantics on `canonical`.
2. `realized-allocation-statement.v1` belongs to `pro-rata-allocation`. Its six
   sub-roots remain explicit; its context and historical round-lifecycle inputs
   have separate proposed owners. Its frozen preimage is unchanged.
3. The three B2 canonical identities, `beneficiary-entitlement.v1`,
   `beneficiary-entitlement-set.v1` and
   `entitlement-representation-descriptor.v1`, belong to `capital-lifecycle`.
   Their current derivation is coupled to pro-rata and Shortfall; extracting
   them as a mechanism-independent package needs adapters.
4. `realized-allocation-execution.v1` is a frozen execution companion/projection
   contract with semantic inputs and EVM outputs. The implementation has no
   separate canonical SHA-256 execution root. Earlier companion-root proposals
   are expressly superseded in its wire specification. Do not manufacture an
   execution-root registry entry.
5. `AllocationBindingV1` is a frozen architecture record, **not** the implemented
   `prf/onchain-shortfall-allocation-binding.v1`. No constructor, adopted wire
   schema, hash domain or matching vector suite was located for the generic
   record. Its manifest entry is specified/unregistered and cannot pass a
   production readiness gate.
6. The successor prepared-operation derivation and frozen V2 implementation use
   `prf.prepared-operation.v2` for different preimages. The successor adds
   `beneficiary-profile`; the historical Rust and current Solidity shapes do
   not. This must be resolved explicitly before successor Solidity integration;
   this proposal neither renames nor changes either identity.
7. The successor guest derives amounts and entitlement outputs but receives an
   opaque `reserve-binding-root`. Its pure deriver does not reconstruct the
   reserve observation/Shortfall binding or authenticate governed configuration
   from that root. Root sensitivity is not source authentication. A production
   adapter must supply and verify the parent relationship required by B1.
8. The 288-byte guest exists and has execution evidence in the B3 record; VKey
   extraction and cryptographic proof qualification remain pending there.
   Current companion Solidity decodes the historical 192-byte profile. No
   successor 288-byte decoder, entitlement table, withdrawal path or closure
   implementation was located.

## What is checked in

[canonical-artifacts.v1.edn](canonical-artifacts.v1.edn) is a proposed inventory
of **34 definition entries**, including the explicitly incomplete
`AllocationBindingV1` record. It has no own canonical root and is not loaded by
runtime code. Entry IDs are administrative manifest keys; existing artifact
names, schemas and domains retain their exact spellings and meanings.

Every entry identifies one proposed owner, its existing hash definition, input
types, implementation coverage, evidence locations and normative records.
Missing implementations and incomplete conformance are explicit. References
to tests describe available coverage, not a claim that those tests ran in this
inventory pass or establish complete independent proof assurance.

All entries except the specified/unregistered binding use the existing typed
canonical SHA-256 pipeline. This is distinct from the canonical-JSON scheme in
some existing research conformance infrastructure. That infrastructure's
`etc/conformance/registry.v1.edn` is an implementation registry and is not
absorbed here.

## Inventory and classification

The letters below are local inventory classifications, not the letter scheme
used inside older execution-projection documents:

| Class | Meaning | Initial definition registry treatment |
|---|---|---|
| A | Canonical semantic artifact/type or a frozen canonical semantic sub-root | Include adopted definitions; retain specified/unregistered records with explicit missing identity data. |
| B | EVM execution projection/commitment | Reference from semantic definitions; keep in the execution-contract inventory. |
| C | Proof/admission identity | Include only an independently defined canonical semantic assurance fact; otherwise reference the proof/admission contract. |
| D | Configuration/governance identity | Include the canonical configuration/policy definition; do not absorb verifier-registry instances. |
| E | Deployment identity | Reference the deployment ledger. |
| F | Forensic/observational evidence | Reference the evidence store or vector provenance. |
| G | Input, parameter, interface or implementation detail without its own artifact identity | Do not invent a rooted artifact. |

### Registered or specified canonical candidates

The manifest carries exact source/function, domain-registration, implementation,
test/vector and document references for every row. `other/...` is a concrete
proposed owner whose semantics do not fit the four requested module owners.
It does not imply creating another repository in this task.

| Manifest ID / existing name | Class | Proposed sole owner | Identity and coverage |
|---|---|---|---|
| `primitive-request` / `pro-rata-allocation-request.v1` | A | pro-rata-allocation | Frozen normalized public request. Uses the shared `PROJECTION_ARTIFACT_V1` domain with an intent/artifact envelope. Production and portable Clojure implementations; full wire-equivalent Rust mirror not located. |
| `primitive-result` / `pro-rata-allocation-result.v1` | A | pro-rata-allocation | Frozen complete row/witness result, totals, rounding/redistribution and `unallocated-residual`; the portable root strips only the documented self/diagnostic fields. Same domain as request, different exact preimage. |
| `primitive-policy` / `pro-rata-allocation-policy.v1` | A | pro-rata-allocation | Derived mechanism/rounding/tie-break/redistribution policy; distinct from the realized fill-policy root. Clojure implemented. |
| `primitive-policy-outcome` / `pro-rata-policy-outcome.v1` | A | pro-rata-allocation | Derived consequence of validated primitive result, with no consumption/authority claim. Clojure fixtures exist; independent Rust implementation missing. |
| `evaluation-package` / `PRO_RATA_EVALUATION_V1` | A | pro-rata-allocation | Existing evaluation body uses integer `:schema-version 1`. Distinct from the primitive request/result wire schemas; constrained side surface. |
| `evaluation-result-envelope` / `PRO_RATA_ALLOCATION_RESULT_V1` | A | pro-rata-allocation | Existing evaluation/provenance envelope uses integer schema version 1 and an intent-aware root. It is not the primitive `:allocation/hash` merely because both say allocation result. |
| `allocation-context` / `allocation-context.v1` | A | other/allocation-context | Claimants, economic owners, outcomes, rates, policy and randomness; generic selection context, not Shortfall demand or just allocation arithmetic. **Ownership ambiguity O2.** |
| `realized-request-set` / `REALIZED_REQUEST_SET_V1` | A | pro-rata-allocation | Claim-keyed requested map; no separately named/versioned wire schema. Clojure/Rust reconstruction coverage. |
| `realized-fill-policy` / `ALLOCATION_POLICY_V1` | A | pro-rata-allocation | Effective fill policy, excluding fail-action policy. Preserve this root separately from `PRO_RATA_ALLOCATION_POLICY_V1`. |
| `realized-results` / `REALIZED_RESULTS_V1` | A | pro-rata-allocation | Ordered realized rows, disposition and filled/deferred/haircut/unrealized amounts; no separate wire schema. **Ownership ambiguity O3.** |
| `fail-action-policy` / `FAIL_ACTION_POLICY_V1` | A | pro-rata-allocation | Existing general partial-fill treatment/default. Distinct from Shortfall-specific unmet-demand interpretation. **Ownership ambiguity O3.** |
| `round-lifecycle` / `ROUND_LIFECYCLE_V1` | A | other/certified-round-lifecycle | Historical round/certified-cancellation-window projection. It is not the new activation/custody state machine. **Ownership ambiguity O4.** |
| `realized-statement` / `realized-allocation-statement.v1` | A | pro-rata-allocation | Frozen six-root statement. Clojure/Rust and narrow SP1 statement path; Solidity verifies/consumes the opaque root rather than rebuilding canonical bodies. |
| `allocation-binding-record` / `AllocationBindingV1` | A | pro-rata-allocation | Frozen record only; schema/domain/implementation/vectors missing. **Ownership ambiguity O1.** |
| `evm-asset` / `prf/evm-erc20-asset.v1` | A | other/asset-identity | Generic computational chain/token/base-unit identity, neither token authenticity nor a deployment transaction. **Ownership ambiguity O5.** |
| `shortfall-context-v1` / `prf/shortfall-context.v1` | A | Shortfall | Frozen eligibility/candidate universe, authority-bound availability and scarcity context; asset ID/unit fields preserved. Clojure implemented. |
| `shortfall-context-v2` / `prf/shortfall-context.v2` | A | Shortfall | Frozen asset-root successor, not a change to V1. Clojure/Rust provenance path. |
| `reserve-observation-v1` / `prf/onchain-shortfall-reserve-observation.v1` | A | Shortfall | Frozen canonical source-fact definition reconstructing a V1 reserve snapshot. Contains snapshot ID and creation block by existing contract. This does not make arbitrary RPC observations canonical semantics. |
| `reserve-binding-v1` / `prf/onchain-shortfall-reserve-binding.v1` | A | Shortfall | Frozen validated source observation + Shortfall context + operation binding. |
| `reserve-observation-v2` / `prf/onchain-shortfall-reserve-observation.v2` | A | Shortfall | Additive implementation for `OnchainShortfallReserveV2`: recomputes Solidity economic and observation commitments, then binds the source facts to chain-instance genesis, C1/epoch and prepared operation. RPC authenticity and runtime identity remain external observations. |
| `reserve-binding-v2` / `prf/onchain-shortfall-reserve-binding.v2` | A | Shortfall | Validated V2 reserve observation + Shortfall V2 context + operation, chain-instance and C1 binding. V1 identities remain unchanged. |
| `shortfall-allocation-binding-v1` / `prf/onchain-shortfall-allocation-binding.v1` | A | Shortfall | Implemented application binding of reserve-binding root, realized-statement root and operation ID. Successor pure derivation hashes the same body but does not establish all parent authority. |
| `beneficiary-entitlement` / `beneficiary-entitlement.v1` | A | capital-lifecycle | B2 registered canonical member, excludes salt/current signer/proof mode/consumption. Clojure/Rust; successor Solidity missing. |
| `beneficiary-entitlement-set` / `beneficiary-entitlement-set.v1` | A | capital-lifecycle | B2 registered ordered positive members, canonical ordinals, recomputed count/total. Its root is not a public-value word in the 288-byte layout. |
| `entitlement-representation-descriptor` / `entitlement-representation-descriptor.v1` | A | capital-lifecycle | B2 canonical logical descriptor for fixed encoding, opening, claim tree/table and consumption-index schemes. Deployment address/code are excluded. **Ownership ambiguity O6.** |
| `allocation-entitlement-policy` / `allocation-entitlement-policy.v1` | D | capital-lifecycle | Fixed authority-domain configuration policy; excludes realized population. No beneficiary profile or Requested/Required enforcement mode. **Ownership ambiguity O7.** |
| `protocol-genesis` / `protocol-genesis.v1` | A | other/chain-governance | Canonical semantic protocol identity, excludes deployment metadata and current configuration. Solidity consumes the root. |
| `chain-instance-genesis` / `chain-instance-genesis.v1` | A | other/chain-governance | Canonical initial execution/settlement/control-plane/authority binding; prescribed address/code fields are semantic inputs. Solidity's local projection is a different B identity. |
| `authority-semantics-policy` / `authority-semantics-policy.v1` | D | other/chain-governance | Canonical policy selected by configuration V2+. No generic verifier-registry merger. |
| `risk-limit-policy` / `risk-limit-policy.v1` | D | other/risk | Existing closed policy required by configuration V4. Included only as a configuration parent; no risk subsystem redesign. |
| `chain-configuration-v1` / `chain-configuration.v1` | D | other/chain-governance | Frozen seven policy/registry sub-root selection contract. |
| `chain-configuration-v2` / `chain-configuration.v2` | D | other/chain-governance | Frozen schema adds authority-semantics policy. |
| `chain-configuration-v3` / `chain-configuration.v3` | D | other/chain-governance | Frozen schema additionally selects allocation-entitlement policy. |
| `chain-configuration-v4` / `chain-configuration.v4` | D | other/chain-governance | Frozen schema additionally requires risk policy. Its presence does not establish NOW withdrawal mode plumbing. |
| `chain-configuration-transition` / `chain-configuration-transition.v1` | D | other/chain-governance | Canonical expected-parent/new-root/target/epoch transition. Its root is the decision root consumed by the existing executor. |
| `chain-configuration-change-identity` / `prf.chain-configuration-change-identity.v1` | D | other/chain-governance | Derived identity of parent root + new root + target; not another transition wire schema and not the sequencing-sensitive transition root. |

### Execution, assurance and observation candidates kept separate

Owner assignments here identify who should maintain the contract even when it
is not a canonical definition-registry entry. Related classes may describe
substrate or evidence, while the first class is the primary classification.

| Candidate | Class | Proposed owner | Current definition / distinction |
|---|---|---|---|
| `realized-allocation-execution.v1` companion and its 384-byte public projection | B, with semantic inputs | capital-lifecycle | [Clojure](../../../src/resolver_sim/allocation/realized_execution.clj), [Rust](https://github.com/protocol-robustness/prf-coprocessor/blob/e2e4e9b25622c52eb028fc373a66e81b3982e933/core/src/realized_execution.rs), [wire freeze](../../../docs/allocation-proofs/realized-allocation-execution-projection-v1.md); companion Solidity `PrfRealizedExecutionVerifierAdapter.sol` and `ProductionProRataAllocatorV1.sol`. Canonical statement is separate; recipient mapping is explicit V1 input, not derived from economic owner. |
| V1 operation binding and ordered claim root/leaves | B | capital-lifecycle | Execution derivation above; Keccak/static ABI. V1 claim-ID hash uses UTF-8 claim bytes; successor uses PRF canonical string bytes and different leaf/node domains. Preserve both. |
| Historical `PreparedOperationV2`, `prf.prepared-operation.v2` | B | Shortfall | [Rust](https://github.com/protocol-robustness/prf-coprocessor/blob/e2e4e9b25622c52eb028fc373a66e81b3982e933/core/src/source_bound_v2.rs); companion `ShortfallV2Commitments.sol` and controller. SHA-256 wording in older prose does not change the Keccak implementation. |
| Beneficiary-aware successor prepared operation | B | Shortfall | [Clojure](../../../src/resolver_sim/allocation/source_bound_entitlement.clj) `operation-root`; [Rust](https://github.com/protocol-robustness/prf-coprocessor/blob/e2e4e9b25622c52eb028fc373a66e81b3982e933/core/src/source_bound_entitlement.rs) `prepared_operation_root`. Same domain literal as historical V2, additional profile word: unresolved compatibility blocker, not an established compatible successor. |
| `EconomicReserveStateV2`, `prf.economic-reserve-state.v2` | B | Shortfall | Rust `source_bound_v2.rs`, successor derivers, companion `ShortfallV2Commitments.sol`; arithmetic derives available = gross - reserved - protected and includes revision. This source profile is distinct from generic reservation/lifecycle ownership. |
| V1 reserve snapshot hash | B | Shortfall | [snapshot reconstruction](../../../src/resolver_sim/shortfall/reserve_observation.clj), Rust `shortfall_provenance.rs`, companion `OnchainShortfallReserveV1.sol`; domain `prf.onchain-shortfall-reserve.snapshot.v1`. |
| `AuthoritativeReserveObservationV2` root | F, EVM commitment | Shortfall | Rust `source_bound_v2.rs` versus companion `ShortfallV2Commitments.sol`. Rust hashes prepared root + reserve commitment + snapshot ID + block; Solidity hashes commitment + snapshot ID + block. Same domain, unequal layouts; existing coverage does not establish equality. |
| Beneficiary profile values `smart-account-v1`, `immutable-address-v1` | G; prospective A descriptor | capital-lifecycle | NOW beneficiary section and both successor derivers. An inherited profile string is committed in existing roots; no separate adopted canonical profile descriptor/root was located. Keccak profile word is B, wallet signer/recovery state is F. |
| `allocationClaimRoot`, successor leaves/nodes/empty root | B | capital-lifecycle | Successor derivers; `prf.source-bound-entitlement.claim-{leaf,node,empty}.v1`. Derived from the same ordered semantic members, not an independently supplied population. Solidity successor checker missing. |
| Individual salted entitlement commitment, logical table root | B | capital-lifecycle | Successor derivers; commitment/table-node/table-empty/table-root domains. Salt changes table root without changing semantic entitlement-set root. |
| `evm-bytes32-v1` 32-byte statement proof output | C | pro-rata-allocation | [statement guest](https://github.com/protocol-robustness/prf-coprocessor/blob/e2e4e9b25622c52eb028fc373a66e81b3982e933/realized-statement-sp1-program/src/main.rs), companion `PrfRealizedAllocationVerifierAdapter.sol`; one opaque statement root, no execution authorization by itself. |
| `prf/onchain-shortfall-allocation-binding-public-values.v1`, 160 bytes | C | Shortfall | [source-bound guest](https://github.com/protocol-robustness/prf-coprocessor/blob/e2e4e9b25622c52eb028fc373a66e81b3982e933/source-bound-sp1-program/src/main.rs), Rust `source_bound.rs`, companion `PrfSourceBoundStatementVerifierAdapter.sol`. Historical five-word profile. |
| `prf/onchain-shortfall-allocation-binding-public-values.v2`, 192 bytes | C | Shortfall | [frozen V2 guest](https://github.com/protocol-robustness/prf-coprocessor/blob/e2e4e9b25622c52eb028fc373a66e81b3982e933/source-bound-v2-sp1-program/src/main.rs), Rust `source_bound_v2.rs`, companion V2 verifier adapter and assurance. Guest echoes allocation-binding/statement witness roots; preserve as historical source assurance. |
| `prf/source-bound-entitlement-public-values.v1`, 288 bytes | C | capital-lifecycle | [successor guest](https://github.com/protocol-robustness/prf-coprocessor/blob/e2e4e9b25622c52eb028fc373a66e81b3982e933/source-bound-entitlement-sp1-program/src/main.rs), both successor derivers and B2 vector set. Nine words; canonical set root is derived but omitted as an independent word. Shortfall-specific prepared/reserve adapter is required. No companion successor decoder found. |
| `realized-allocation-proof.v1` | C, evidence wrapper | pro-rata-allocation | [proof admission](../../../src/resolver_sim/allocation/proof_admission.clj). Hash binds program, proof/public-value digests and `proof/file`. Not a path-independent economic assurance fact; keep outside this semantic registry. |
| `realized-allocation-proof-verification.v1` | C, signed verifier decision | pro-rata-allocation | Same namespace: `verifier-receipt-preimage`, signing and external-trust verification. Binds exact proof/artifact/persisted-input digests; no standalone canonical economic withdrawal-receipt identity. Do not treat signing domain as a separately defined receipt root. |
| `PrfStatementAdmission` receipt ID | C, EVM admission | canonical | Companion `src/core/PrfStatementAdmission.sol`, `HASH_BOUNDARIES.md` §8.1 and `test/PrfStatementAdmission.t.sol`. Domain `PRF_STATEMENT_ADMISSION_V1`; Keccak authority/verifier snapshot + statement/public-values digest. Reference only; no PRF typed-canonical SHA receipt root. Generic interface ownership is proposed; current concrete executor import is application coupling. |
| Source-assurance consumption/record IDs | C | Shortfall | Companion `SourceBoundProRataAssuranceV2.sol`; snapshot-bound local storage/consumption, not new canonical entitlement identities. |
| Verifier snapshot root, verifier ID, adapter identity, SP1 VKey/ELF/program | C, external registry | canonical interfaces; verifier registry owns instances | Companion `PrfVerifierRegistry.sol`, `HASH_BOUNDARIES.md` §7; historical `source-bound-v2-identity.json`; successor B3 record. SHA-256 registry hashing does not make these entries semantic artifact definitions. |
| Table address/runtime code hash, CREATE2 salt, deployment manifest | E | example/deployment | NOW logical-table/deployment distinction; companion `docs/gate-e.md`, deployment scripts and deployment vector schemas. No table deployment realizes the successor contract yet. |
| `realized-allocation-source.v1` | F, signed scenario-source evidence | other/scenario-source | [source provenance](../../../src/resolver_sim/allocation/source_provenance.clj), `source-preimage`/`valid-source?`; scope is `fixed-scenario-test-vector`. SHA-256 addressing and an authority signature do not establish live Shortfall custody. |
| Optional Requested proof status; losing cancellation/withdrawal attempts; RPC/log/run/proof metadata | F | producing module/evidence store | NOW proof-observation and identity-classification sections; companion receipts/events and host fixtures. Do not change economic roots. |
| Residual amount, unmet-demand arithmetic, count/total, consumption bitmap | G | allocation residual: pro-rata-allocation; unmet demand: Shortfall; liability/consumption: capital-lifecycle | Derived fields with no separate artifact identity. Relevant parent roots commit normative fields. Do not add residual or bitmap artifact types simply because values are important. |

`AllocationBindingV1` is neither an alias nor a successor of the Shortfall
binding. The two bind different parent families: primitive request/policy/result
lineage versus reserve-binding/realized-statement/operation lineage.

### Missing NOW semantic definitions

These are required meanings from the NOW freeze. They are **not registry
entries with invented `.v1` schemas or domains**. Each has one proposed owner,
`capital-lifecycle`; the relevant normative locations are in the NOW document.

| Required semantic family | Class | Frozen meaning / missing identity work |
|---|---|---|
| Canonical beneficiary profile descriptor | A | Stable economic beneficiary and supported profile rules; no adopted descriptor schema/domain/vector set. Existing profile strings remain frozen inputs to B2. |
| Reservation cancellation accepted-cause | A | Subject/root, expected prior state, policy/authorized role or decision; transaction sender is forensic unless policy makes it semantic. No NOW cause constructor/domain located. |
| Canonical release/refund consequence | A | Separate cause from policy-bound economic consequence, exact amount and effect. Release changes reservation accounting without token transfer; pre-activation refund changes custody. No NOW consequence artifact implementation. |
| Realized capital consequence | A | Successful terminal `CancelledReleased`/`CancelledRefunded` accounting fact, not observed log/delta evidence. No NOW canonical realization constructor/domain. |
| Withdrawal/proof-policy family | D | `Requested < Required`, authorization semantics and `stricter(current, callerMinimum)`; no selected-mode field in entitlement identity. No adopted policy schema/domain or governed mode projection. |
| Required membership assurance fact/receipt, where the proof profile defines one | C | Valid membership under immutable operation scheme/root, bound to effective policy. No canonical NOW economic assurance-fact identity; existing local admission receipts do not fill this gap. |
| Withdrawal realization | A | Operation, canonical entitlement/index, stable beneficiary/profile, amount, effective enforcement, successful consumption, resulting liability/accounting/state; Required also binds membership assurance. No adopted schema/domain/validator/vectors. |
| Closure / realized settlement | A | Authoritative final claimed count/amount, zero liability and terminal state; not event counting. No adopted canonical artifact implementation. |

Existing `allocation-activation.v1` and `claim-consumption-receipt.v1` are A
artifacts of `other/certified-round-lifecycle`, documented and implemented in
`allocation/activation.clj` and `allocation/claim_consumption_receipt.clj`.
Their cutpoints and failure/rollback receipt semantics differ from NOW activation
and atomic withdrawal. They are outside this tranche's manifest and must not be
reinterpreted as the missing NOW artifacts. The existing cancellation artifact
map similarly documents Sew party-cancellation and certified-round surfaces;
their existence does not implement Protected Capital cancellation consequences.

### Explicit ownership ambiguities

| Decision | Proposed owner | Reason and question to resolve before moving sources |
|---|---|---|
| O1: `AllocationBindingV1` | pro-rata-allocation | Historical record calls it a domain-neutral framework capability, but requires validation/lineage from the pro-rata primitive. Put the present pro-rata adapter with that mechanism; a truly independent binding contract would need its own explicit boundary. Do not rewrite the historical record or silently invent its schema/domain here. |
| O2: allocation context | other/allocation-context | Current context contains probabilistic outcome selection/randomness, beyond deterministic pro-rata allocation and beyond generic canonical mechanics. Retain as an explicit support dependency initially; decide extraction only for a real consumer. |
| O3: dispositions/fail-action policy | pro-rata-allocation | Preserves the existing generic realized partial-fill statement meaning. Some deferred/haircut interpretation is application policy; expose an adapter instead of making Shortfall the owner of frozen realized-result arithmetic. |
| O4: historical round lifecycle | other/certified-round-lifecycle | Existing statement commits this projection. Ownership by new capital-lifecycle would wrongly imply that result acceptance/claim-consumption-started is the NOW activation cutpoint. Preserve the historical projection through an adapter. |
| O5: ERC-20 asset identity | other/asset-identity | Reusable computational asset semantics are neither hashing mechanics, pro-rata arithmetic nor scarcity. It can remain an existing support module during extraction. |
| O6: representation descriptor | capital-lifecycle | Defines entitlement opening/membership/consumption representation. It uses canonical mechanics but is not a generic hash descriptor; a later independently justified representation package could be a successor ownership decision. |
| O7: allocation-entitlement policy | capital-lifecycle | Selects fixed resource/custody/authority domain. It does not define allocation arithmetic and does not yet define the new withdrawal modes. Keep its V1 scope rather than broadening it. |
| O8: genesis/configuration | other/chain-governance | Reusable domain configuration, but canonical hashing alone is insufficient reason to give economic/governance semantics to the common canonical package. Preserve a support dependency until governance packaging is decided. |

## Minimal closed schema proposal

The checked-in EDN is ordinary definition data. The schema is closed as follows;
there is no new schema DSL, registry root, server, database, publication protocol
or instance-persistence mechanism.

| Shape | Required keys | Optional keys |
|---|---|---|
| Registry | `:registry/schema`, `:registry/status`, `:registry/encoding`, `:registry/shared-domains`, `:registry/entries` | none |
| Encoding | `:scheme`, `:definition`, `:normative` | none |
| Shared-domain exception | `:domain`, `:artifacts` | none |
| Entry | `:artifact/id`, `:artifact/name`, `:artifact/schema`, `:artifact/owner`, `:artifact/class`, `:artifact/status`, `:artifact/hash`, `:artifact/inputs`, `:artifact/implementations`, `:artifact/conformance`, `:artifact/normative` | `:artifact/successor-of` |
| Hash | `:algorithm`, `:domain`, `:registration`, `:derivation` | none |
| Inputs | `:artifacts`, `:related` | none |
| Implementations | `:clojure`, `:rust`, `:solidity` | none |
| Implementation coverage | `:status`, `:sources` | none |
| Conformance coverage | `:status`, `:sources` | none |

`registry/schema` is exactly `"canonical-artifacts.v1"`; registry status is
`:proposed` until reviewed adoption. A later registry-data format uses a new
registry schema; this does not version or mutate any artifact identity.

Entry IDs are unique keywords. `artifact/name` is the exact existing artifact
name or domain-named commitment where no wire type name exists. `artifact/schema`
is the exact existing versioned wire-schema string or nil. A numeric versioned
evaluation envelope is documented in its source, not assigned a new wire-schema
string. Root-only views also use nil. `AllocationBindingV1` has no adopted wire
schema and hash; its nil fields are mandatory evidence of that gap.

Owners are exactly `:canonical`, `:pro-rata-allocation`, `:capital-lifecycle`,
`:Shortfall`, or a named `:other/...` keyword from the inventory above. Each entry
has one scalar owner. There are no ownership lists. Classes in this initial
manifest are A and D; B/E/F/G identities remain related references. C requires an
adopted canonical semantic assurance-fact definition before inclusion.

Definition statuses are `:frozen-current`, `:frozen-historical`,
`:successor-frozen`, `:implemented-current`, `:constrained`, or
`:specified-unregistered`. Historical means a retained older source contract,
not permission to remove it: the successor can still bind that historical fact.
These describe definitions, not
proof qualification or deployed production readiness. All existing versioned
preimages are preserved regardless of status. A specified/unregistered entry
has nil hash; other entries require the closed hash map and `:algorithm :sha256`.
The encoding scheme is exactly `canonical-hash-scheme.v1` for this tranche:
`SHA256(UTF8(domain) || canonical typed value bytes)`. The derivation reference
determines whether the preimage includes an intent wrapper, exclusions, root
prefixes and canonical ordering; the registry does not invent a normalization.

Source references are closed tuples `[:prf "relative/path" "symbol"]` or
`[:prf-evm "relative/path" "symbol"]`; the symbol is optional for documents,
whole files and vector directories. Paths must be relative and may not contain
parent traversal. Repository roots are supplied to the validator by the caller,
not discovered from a home-directory path. A declaration reference may identify
a central keyword-domain registry or an existing file-local string-domain
declaration; both conventions are already used by PRF.

Implementation status is one of `:implemented`, `:opaque-consumer`,
`:projection-only`, `:missing`, `:not-required`. The first three require source
references; the last two require empty sources. Opaque Solidity consumption does
not count as independent canonical reconstruction. An SP1 entrypoint calling the
same Rust core does not count as a second independent implementation.

Conformance status is `:present`, `:partial`, or `:missing`, with source
references. Present/partial requires references; missing requires empty sources.
Present means there is shared comparison coverage for the declared narrow
profile, not all policies, machine widths or deployed environments. Parent
artifact IDs resolve within the manifest; related references are closed pairs
`[class "existing identity or described raw input"]`. These pairs document
external boundaries without importing their registries. Successor links resolve
to existing entries and describe schema lineage, not required runtime inputs.

No arbitrary metadata, package discovery, semantic-ontology, research claim,
mutable-instance, deployment-address, VKey or remote-location fields are added.
Current source references are inspection anchors, not release commitments. Before
extraction, each package release pins its manifest and vectors in version control;
file paths and release metadata do not enter economic artifact roots.

## Narrow deterministic gates before extraction

1. **Closed data and ownership:** parse EDN without evaluation; validate the exact
   keys/enums/tuples above; unique administrative IDs; one allowed scalar owner;
   valid parent/successor references; reject path traversal and unrecognized repo
   aliases. Every proposed owner ambiguity is explicitly reviewed for the package
   being extracted. Unregistered entries cannot satisfy a production contract.
2. **Identity consistency:** domain strings must match their declarations and
   derivation's actual hash scheme/preimage. Require unique domains except exact,
   checked-in legacy sharing declarations. The one existing exception here is
   `PROJECTION_ARTIFACT_V1` for primitive request and primitive result. Request
   preimage has the intent/artifact wrapper; result root uses the frozen result
   body. A naive domain-uniqueness gate would reject valid frozen history.
3. **Frozen baseline:** check the adopted ID/name/schema/class/domain/encoding
   interpretation against its version-controlled baseline, and recompute existing
   canonical body bytes/preimages/root goldens. Reject changed interpretation of
   an existing identity; a new semantic shape needs an explicitly new artifact
   identity and successor declaration. Moving a source reference with unchanged
   byte semantics is permitted. Do not freeze entire source file hashes or treat
   the current proposed inventory as already adopted authority.
4. **Resolvable evidence:** referenced files/directories and symbols must exist
   in explicitly supplied PRF/companion checkouts. Missing coverage is allowed in
   an inventory but fails production readiness for an affected extracted profile.
   In particular, nil identity data for `AllocationBindingV1` is not a passing
   definition. No runtime autoload of referenced symbols is required for this
   static gate.
5. **Cross-language profile conformance:** run the named suites already attached
   to a moved definition. Shared vectors must expose body/order, canonical bytes,
   domain/root and rejection behavior. Where an EVM projection is consumed,
   compare Clojure/Rust output words to the Solidity decoder/commitment helper,
   including uint/address widths and scheme/domain word. Rust core and guest
   output equality is additional evidence; it cannot substitute for the Solidity
   comparison or missing source authentication.
6. **Hash-class boundary:** permit only typed-canonical SHA-256 entries in this
   tranche. Treat EVM Keccak roots and raw `sha256(publicValues)`/ELF/proof digests
   as related identities, never canonical semantic artifact roots. Strings such
   as `sha256:` do not establish their algorithm/preimage or semantic class.
7. **Dependency direction:** canonical's implementation must require no
   pro-rata, capital-lifecycle, Shortfall or deployment code. Pro-rata must not
   require capital lifecycle/Shortfall/deployment; generic capital lifecycle
   must not require a specific allocation mechanism or Shortfall. Shortfall
   adapters may compose pro-rata and capital lifecycle. Configuration may commit
   opaque policy roots without importing the producer. Check actual namespace,
   crate and Solidity imports as well as manifest dependencies; the current
   entitlement derivation fails the target generic boundary and remains a
   composed implementation until adapted.
8. **Successor readiness:** treat the prepared-operation profile/domain reuse and
   Rust/Solidity authoritative-observation mismatch as named blockers, not
   grandfathered equivalence. Preserve 32/160/192/288/384-byte schema distinctions,
   V1 claim leaves and V1/V2 program families. Adopt a reviewed successor identity
   only with its own matching vectors and consumer. Nothing in this task changes
   public values, programs, VKeys, domains or Solidity.

These are proposed adoption/extraction gates, not new CI jobs wired by this
patch. A small data validator plus the existing focused conformance runners is
sufficient. Start with static checks; run production profiles only when their
implementations exist. No broad PRF research gate is needed to decide definition
ownership.

## Repo-separation impact

The PRF integration tree and its immutable library dependencies are distinct:

```text
PRF checkout
├── src/resolver_sim/hash/canonical.clj         codec + many domain declarations
├── src/resolver_sim/pro_rata/                  primitive and evaluation surfaces
├── deps.edn                                    pinned prf-canonical + allocator Git revisions
├── packages/pro-rata-allocation/README.md      external repository linkage only
├── src/resolver_sim/allocation/                statement, context, lifecycle,
│                                             execution, proof, entitlement blend
├── src/resolver_sim/shortfall/                 scarcity and reserve/application bindings
├── pro_rata_contract_src/                     independent aggregate contract reference
├── prf-coprocessor@e2e4e9b25622c52eb028fc373a66e81b3982e933/core/                         Rust codec, selection/fill/lifecycle,
│                                             Shortfall, entitlement and execution blend
├── prf-coprocessor@e2e4e9b25622c52eb028fc373a66e81b3982e933/*-sp1-program/                 distinct guest/profile identities
├── etc/conformance/ + portable.../vectors/    semantic fixtures across current locations
└── docs/architecture/canonical-artifacts/     proposed definition inventory

companion prf-evm checkout
├── src/core/                                 genesis, verifier registry, admission
├── src/applications/shortfall/               source/reserve/ratification
├── src/applications/pro-rata/                custody lifecycle; no share arithmetic
├── src/verification/                         historical commitment helpers/adapters
└── scripts/ + fixtures/                      deployment, proof and vector material
```

The target contract ownership can be established before moving these sources:

```text
canonical
  └── typed codec/hash schemes + registration/definition-validation mechanics

pro-rata-allocation ──uses──> canonical
  ├── primitive request/result/policy/outcome; rounding/redistribution/residual
  └── realized fill-policy/request/results/statement; their conformance vectors
      └── retains explicit allocation-context and historical round-lifecycle inputs

capital-lifecycle ──uses──> canonical
  ├── beneficiary entitlement/set/representation descriptor
  ├── generic reservation/activation/liability/cancellation/withdrawal/closure
  └── mechanism input adapter (planned boundary; frozen identities preserved)

Shortfall ──uses──> canonical + pro-rata-allocation + capital-lifecycle adapters
  └── eligible scarcity, authoritative source/available capital,
      reserve/application binding and Shortfall-specific unmet demand

example/deployment ──assembles──> reviewed packages + proof/verifier/deployment tools
  └── owns host/deployment workflow; never acquires semantic ownership of vectors

existing support contracts retained explicitly during migration
  └── chain governance, asset identity, allocation context, certified round lifecycle
```

The 288-byte projection owner is capital-lifecycle, but its current full guest is
a **Shortfall/pro-rata/capital composition**. Target ownership does not assert
that the present crate or namespace already has the required dependency direction.
The application adapter can live with Shortfall and preserve all frozen roots;
the generic lifecycle accepts a validated result/beneficiary contract through a
narrow API. A new mechanism must have an explicit binding/profile instead of
being declared compatible with frozen pro-rata-specific identities.

Artifacts and vectors should travel with their sole semantic owner. Keep a
compatibility conformance runner at each cross-package binding. Pure Solidity
decoders/commitment helpers travel with the execution owner; deployment hosts may
call them without owning their normative encoding.

Material extraction costs found:

- **The portable primitive and the NOW realized path are different contracts.**
  The primitive supports weighted/capped rows, floor/largest-remainder and cap
  redistribution. The Rust/SP1 realized path admits a narrow no-caps,
  no-redistribution, largest-remainder/deferred profile. Shared terminology does
  not establish full Rust parity for primitive result/policy/outcome artifacts.
- **Realized Clojure reconstruction pulls in yield and certified lifecycle.**
  `persisted-statement-admission/reconstruct-input` calls
  `yield.partial-fill/calculate-fulfillment-pro-rata` and
  `allocation.round-state/round-lifecycle`; the latter uses the certified
  force-authorisation lifecycle. The core realized semantic functions need a
  preserved adapter boundary, not copied economics.
- **Entitlement derivation hardcodes application/mechanism reconstruction.**
  Both successor implementations reconstruct the pro-rata statement and hash a
  Shortfall allocation binding. A mere file move would violate generic
  capital-lifecycle's required boundary.
- **Canonical code is duplicated.** The production Clojure codec, portable
  Clojure codec, core Rust codec and conformance verifier codec are distinct
  implementations. Package extraction must carry the frozen byte contract and
  compare bytes, not assume identical source ownership means identical code.
- **Arithmetic widths are profile constraints.** Generic Clojure allocation
  uses exact arbitrary-precision integers, while the present Rust realized rows
  use `i64` and the reserve fields use `u128`. Solidity has uint256 ABI words.
  Encoding those values in 32-byte words does not widen the supported Rust
  semantic input range. Preserve narrow admission limits and test boundary
  rejection before promising broader cross-language equivalence.
- **Solidity interfaces are less independent than their names imply.**
  `PrfStatementAdmission` imports concrete `SewConfigurationExecutor` and
  `PrfVerifierRegistry`; source controllers and allocation custody are paired.
  Read-only classification here does not fix those dependencies or generalize
  Sew. A future generic admission package needs an explicit existing configuration
  interface boundary.
- **Test ownership currently crosses host paths.** The Rust core entitlement
  test imports the vector materializer from
  `prf-coprocessor@e2e4e9b25622c52eb028fc373a66e81b3982e933/sp1-script/src/source_bound_entitlement_vectors.rs`; fixtures live
  outside the crate. Release conformance must carry that helper/vectors with the
  semantic profile or expose a narrow test-support dependency.
- **Several NOW production semantics are still unimplemented.** Extracting the
  B2 derivation is separate from completing capital custody activation,
  cancellation, governed proof modes, withdrawals and settlement. Packaging
  cannot turn the existing Funded endpoint into the frozen Activated endpoint.

The manifest allows one package to be extracted at a time while the repository
continues consuming explicit pinned/local dependencies. First adopt the
definition inventory and baseline; then package the common codec; then move
allocation semantics with its exact supporting adapters and vectors. Generic
capital lifecycle requires the dependency boundary work above. Shortfall and
deployment remain assembly consumers throughout. This proposal performs none
of those source moves.

## Baseline validator (non-runtime)

The inventory remains explicitly `:proposed` and has no runtime consumer. The
checked-in deterministic validator checks the closed v1 shape, unique IDs and
names, sole owners, status/hash consistency, declared shared domains, input
references and cycles, source-reference existence, cross-language conformance
references, and the frozen identity baseline. It prevents canonical-owned
entries from depending on domain-owned entries and pro-rata entries from
depending on capital-lifecycle entries; it also rejects cycles in artifact
parent relationships. The manifest records current capital-lifecycle links to
pro-rata and Shortfall explicitly as extraction adapter work. The validator
does not prove implementations or replace conformance tests.

Run it with the PRF, EVM, and pinned allocation repository roots supplied:

```bash
bb canonical-artifacts:validate \
  docs/architecture/canonical-artifacts/canonical-artifacts.v1.edn \
  /path/to/prf \
  /path/to/prf-evm \
  /path/to/pinned-prf-canonical \
  /path/to/prf-pro-rata-allocation-at-the-deps.edn-pin
```

The validator pins the baseline identity tuple digest
`0d0af1b9e7e487f334c11f6a7029a6026caa75af9383e899db03747eb4a6c71a`.
Adding an entry or changing a pinned identity tuple requires an explicit review
and corresponding validator-baseline update; coverage/reference corrections do
not alter the pin. New successor definitions must retain a distinct name and
hash domain. This pin protects definition identity; it is not a new canonical
artifact root or a runtime registry commitment.

## Original validation performed for this proposal

- Babashka parsed the actual EDN file as data. An isolated temporary checker
  validated the documented closed shapes, IDs/names, ownership/status enums,
  domain declarations and shared-domain exception, input references and cycles,
  source paths/symbols, and local Markdown links: **34 entries, 146 distinct
  source references, zero errors**. That was the proposal-time review; the
  checked-in static validator and task above now retain the narrow baseline
  checks without adding a runtime consumer.
- The required `bb backstop` was attempted. Its yield unit stage passed **179
  tests / 969 assertions**. The CLI scenario stage reported **139/140 passing**,
  with S106 `reversal-track2-evidence-appeal` failing after `:not-resolver` and
  `:no-active-appeal`. Formatting reported 13 existing Clojure source/test files
  incorrectly formatted, all outside this proposal. The remaining gate was
  interrupted during lint after those failures; command exit was 130. Full
  backstop completion is therefore **not claimed**. Log:
  `/tmp/prf-artifact-registry-backstop.log`.
- No Clojure/Rust/Solidity source changes, structural editor operations, nREPL
  evaluations, new tests, source moves or golden updates were performed.
  `bb validate`, new cryptographic proving and companion Foundry suites were
  skipped for this documentation/data-only proposal. The changelog is unchanged
  because this is an unadopted design, not a changed runtime/interface contract.
- Remaining uncertainty is explicit: eight ownership decisions need review,
  specified NOW identity families need adoption/implementations, and successor
  source authentication and Clojure/Rust/Solidity conformance need the concrete
  adapter/consumer checks identified above. Static reference validation does not
  resolve those production readiness gaps.
