# Sew Canonical Asset Identity V1

## Status and scope

This record defines the asset identity that future Sew accounting projections
must use. It prevents a symbol, display name, or address from being treated as
a globally unique asset when the accounting domain is multi-chain or
multi-asset.

This is a semantic and commitment boundary only. It does not migrate current
world maps, token keys, Solidity interfaces, or existing artifacts in this
step.

## Canonical identity

An asset identity is a closed, domain-qualified record:

```clojure
{:schema-version "prf/asset.v1"
 :domain {:kind :evm
          :chain-id 8453}
 :asset-type :erc20
 :address "0x..."
 :unit :erc20-base-unit
 :asset/root "sha256:..."}
```

The exact schema version and hash domain must be registered before runtime
implementation. The existing `prf/evm-erc20-asset.v1` builder is the current
repository precedent for the EVM ERC-20 specialization and should be reused or
made the implementation basis rather than introducing a second address/chain
commitment format.

## Identity rules

- `:domain` is part of identity. The same address on two chains is two assets.
- `:chain-id` is an integer in the supported chain-id range and is never
  inferred from a token symbol.
- `:asset-type` is closed. V1 supports `:erc20`; native assets require an
  explicit later representation and must not reuse the ERC-20 address field.
- `:address` is a canonical non-zero contract address for `:erc20`.
- `:unit` identifies raw integer accounting units. V1 ERC-20 amounts use
  `:erc20-base-unit`.
- Human-readable symbol, name, decimals, and metadata are descriptive data,
  not identity fields.
- Amounts are non-negative integers in the asset's canonical base unit.
- Unknown fields, malformed addresses, missing domains, and ambiguous asset
  types are rejected; they are never dropped or normalized into a fallback
  bucket.

## Root and map usage

The `:asset/root` is the canonical content identity of the validated asset
record. Accounting projections should key per-asset buckets by this root or by
the exact validated asset record, not by `:USDC`, a symbol string, or a bare
contract address.

The root must be computed over the closed identity payload only. It must not
include mutable balances, valuation, observed custody, display metadata, or
projection cutpoints. Those belong to separate commitments.

Equivalent input map orderings must produce the same root. Distinct domains,
asset types, addresses, or units must produce different roots.

## Compatibility boundary

Current Sew token keys such as `:USDC` and `"USDC"` are legacy runtime keys.
They may remain at protocol ingress while the migration is staged, but they are
not sufficient as the key of a proof-oriented accounting projection.

The migration must be explicit:

1. Resolve each legacy token key through a committed asset registry or
   configuration mapping.
2. Reject unresolved or conflicting mappings.
3. Use the resolved asset identity for projection buckets and roots.
4. Keep legacy display keys only as derived presentation fields.

String/keyword normalization may remove representational differences inside one
legacy domain, but it must never be presented as domain qualification.

## Separation from solvency and observations

An asset identity says *which asset and unit* an amount refers to. It does not
assert:

- that the token contract is authentic;
- that the asset is currently held by a custody contract;
- that a balance observation is current;
- that the asset has a valuation in another asset;
- that the asset is sufficient to satisfy liabilities.

Those claims require separate observation, custody, valuation, and solvency
records that reference the asset root.

## Required tests for implementation

The runtime implementation must cover:

- same symbol on different chains produces different identities;
- same address on different chains produces different roots;
- same asset with reordered input maps produces the same root;
- malformed or missing domain/address/unit fails closed;
- native-asset input cannot be silently encoded as an ERC-20;
- legacy token keys cannot enter a projection without an explicit mapping;
- amounts remain integer base units and are never decimal-normalized.
