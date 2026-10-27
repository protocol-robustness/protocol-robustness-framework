# Extracted pro-rata allocation

The portable allocator is maintained in the separate
[`prf-pro-rata-allocation` repository](https://github.com/protocol-robustness/prf-pro-rata-allocation/tree/e0788f911e4fba3a27544cbccbb95d59d7daf2f7).
PRF consumes it from the immutable Git revision declared in the root
[`deps.edn`](../../deps.edn). This directory is a migration pointer; it does not
contain a second Clojure or Rust implementation.

The accepted source qualification record names revision
`e15501efc04254defd85cdd8e5f4dc4ffd5b1805`. PRF pins
`e0788f911e4fba3a27544cbccbb95d59d7daf2f7`, which moves frozen allocation
fixtures onto the Clojure classpath; allocator implementation sources remain
unchanged from the earlier tested package revision. The package remains a
source-qualified candidate; release, SP1, and production Solidity proof
qualification have not been promoted.

PRF retains host execution, progress, protocol composition, application policy,
evidence binding, EVM integration, lifecycle transitions, and proof assurance.
