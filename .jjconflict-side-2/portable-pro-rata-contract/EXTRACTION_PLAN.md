# Extraction Plan

## Before pro-rata-evm-v2

1. Treat `SPEC.md` and `contract.edn` as normative authority.
2. Keep portable canonical/reference code in `pro_rata_contract_src` independently loadable.
3. Make production Clojure allocation roots and canonical transitions consume portable projections.
4. Pin shared transition bytes/roots and allocation reconstruction vectors.
5. Require EVM V2 to consume only portable contract data plus generic hash primitives.

## Remain implementation-owned

- Clojure allocator scheduling, parallelism, realization state, and progress reporting.
- Solidity storage, ABI, checked-word implementation, revert encoding, proof program and VK.
- Independent verifier implementation strategy.

## Remain application-owned

- SEW aggregate held-credit and custody mapping.
- Native state/location maps and mirrors.
- Configuration, admission, finalization and publication.
- Profiles, research, benchmarks, observability and experiments.

## Later physical extraction

- Publish the portable source root and package files independently.
- Move all realization namespaces and downstream tests to component repositories.
- Add release signing and immutable registry history.
- Add independent Rust/Solidity vector runners.
