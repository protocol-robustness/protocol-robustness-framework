# Controlled entitlement demo genesis

This package defines a separate, explicitly demo-only `protocol-genesis.v1`
identity for the Base Sepolia controlled-entitlement demonstration. It does not
reuse `protocol-genesis-fixture`, and it is not the production PRF/SEW genesis.

The protocol identity is:

```text
protocol/id: :protocol/eae-controlled-entitlement-demo
protocol-genesis root:
sha256:33f77dc224712d537453e2246fc79cbeaeb3a54f9f4574f910153cd209b8da32
```

The exact canonical component preimages, component roots, and protocol-genesis
record are in
[`protocol-genesis-package.edn`](../../etc/deployments/eae-controlled-entitlement-demo/protocol-genesis-package.edn).
Each component root is `SHA-256("EVIDENCE_CONTENT_V1" || canonical-bytes-v1(component))`;
the protocol root uses the existing `protocol-genesis.v1` projection and
`PRF_PROTOCOL_GENESIS_V1` domain.

The committed policy describes a controlled Vector A source, the existing
source-bound entitlement proof profile, and a single explicitly configured
deployer authority. It does not establish a production claims authority,
production governance, or production PRF/SEW identity. Do not use this genesis
for another application or represent it as a production deployment.

Before deployment, derive `chain-instance-genesis.v1` from the actual predicted
Base Sepolia deployment addresses and runtime code hashes, current chain ID,
initial authority-state root, and initial configuration root. Simulate first,
derive the canonical root with the existing Clojure implementation, confirm the
deployer nonce has not changed, then broadcast and verify the deployed values.
The Solidity script's root environment variable is an input; it is not a
canonical-root validator.
