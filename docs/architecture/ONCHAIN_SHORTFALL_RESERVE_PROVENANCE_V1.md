# Onchain Shortfall Reserve Provenance V1 — Frozen Boundary

Status: **FROZEN for the current V1 scope**

This document freezes the first application-owned capital provenance path:

```text
OnchainShortfallReserveV1
→ evm-erc20-asset.v1
→ onchain-shortfall-reserve-observation.v1
→ shortfall-context.v2
→ onchain-shortfall-reserve-binding.v1
```

No implementation may change these contracts, schemas, canonical roots, or
cross-runtime vectors without an explicit successor version or a demonstrated
correctness defect.

## Frozen components

### `OnchainShortfallReserveV1`

The reserve owns one standard ERC-20 asset. Its V1 semantics are limited to:

- immutable token, application authority, and protected-capital configuration;
- ordinary reservations with explicit active/released state;
- at most one active Shortfall reservation;
- atomic locking of all currently allocable capital;
- immutable source snapshots;
- exact, one-time forwarding into the already-frozen allocator boundary.

At lock time:

```text
gross = token.balanceOf(reserve)
available = gross - reservedBefore - protectedCapital
```

The contract derives these values from custody and retained accounting. The
caller cannot supply the amount. V1 supports only balance-preserving standard
ERC-20 behavior.

The reserve snapshot hash is a Solidity-local Keccak identity. It is not a PRF
canonical root.

### `prf/evm-erc20-asset.v1`

The canonical computational asset identity is exactly:

```text
chain ID
ERC-20 contract address
:erc20-base-unit
```

It contains no symbol, name, alias, price, oracle, issuer, or authenticity
claim.

### `prf/onchain-shortfall-reserve-observation.v1`

This observation reproduces the immutable reserve snapshot and binds:

```text
chain ID
reserve
ERC-20 token
snapshot ID
snapshot hash
operation ID
allocator
creation block
 gross amount
reserved amount before
protected amount
available amount
source/profile identity
EVM asset root
```

Validation independently recomputes the Solidity snapshot hash and requires:

```text
reservedBefore + protectedCapital <= gross
availableAmount = gross - reservedBefore - protectedCapital
availableAmount > 0
```

### `prf/shortfall-context.v2`

V2 preserves the narrow `shortfall-pro-rata.v1` semantics of V1 while replacing
the ambiguous application asset fields with:

```text
:asset/root  <prf/evm-erc20-asset.v1 root>
```

`shortfall-context.v1` remains unchanged. V2 does not add operation identity;
operation binding remains source-specific.

### `prf/onchain-shortfall-reserve-binding.v1`

The binding has only three canonical inputs:

```text
reserve observation root
Shortfall V2 context root
operation ID
```

Child artifacts are reconstructed and validated rather than trusted as derived
fields. Validation requires:

```text
Shortfall schema == prf/shortfall-context.v2
Shortfall source == :onchain-shortfall-reserve-observation.v1
Shortfall availability root == reserve observation root
Shortfall available amount == observation available amount
Shortfall asset root == observation-derived EVM asset root
binding operation ID == observation operation ID
```

## Frozen conformance vector

The checked-in raw fixture is:

```text
prf-coprocessor@e2e4e9b25622c52eb028fc373a66e81b3982e933/core/tests/fixtures/onchain-shortfall-reserve-provenance.json
```

It is consumed independently by Clojure and Rust. Expected roots are assertion
outputs only, never reconstruction inputs. The vector uses:

```text
gross              = 100
reservedBefore     = 20
protected          = 10
available          = 70
```

The conformance tests independently derive and compare:

```text
Solidity snapshot hash
EVM asset root
reserve observation root
Shortfall V2 root
reserve binding root
available amount
```

## Explicitly outside this freeze

This freeze does not establish:

- arbitrary historical EVM state reconstruction;
- chain finality policy;
- economic legitimacy of protected capital;
- legitimacy of ordinary reservation policy;
- fairness of the candidate universe;
- Shortfall-to-realized-statement lineage beyond existing commitments;
- Aave, lending, insurance, liquidation, or yield semantics;
- generic asset registries, availability abstractions, or oracle systems;
- upgrades, cancellation, expiry, recovery, or multi-round funding.

Any future change must introduce a separately reviewed successor schema or
contract rather than silently extending these V1 identities.
