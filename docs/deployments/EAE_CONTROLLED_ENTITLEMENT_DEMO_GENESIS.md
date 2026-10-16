# Controlled entitlement demo genesis

These files define candidate inputs for a separate, local-only
`protocol-genesis.v1` identity and its initial authority/configuration state.
They do not reuse `protocol-genesis-fixture` and are not the production
PRF/SEW genesis. All three packages are explicitly unapproved candidates and
must not be used to qualify a deployment.

The protocol identity is:

```text
protocol/id: :protocol/eae-controlled-entitlement-demo
protocol-genesis root:
sha256:668bc1d132b8745a3f6db7fa0b2dad2b85a11246e00c39e7b24d266af4724775
```

The protocol candidate's component materials, recomputed component roots and
declared root are in
[`protocol-genesis-package.edn`](../../etc/deployments/eae-controlled-entitlement-demo/protocol-genesis-package.edn).
The root derivation uses the existing PRF `protocol-genesis.v1` projection and
`PRF_PROTOCOL_GENESIS_V1` domain. The deployment CLI recomputes each component
root as well as the aggregate and rejects mismatched declarations.

The candidate local authority package is
[`authority-state-package.edn`](../../etc/deployments/eae-controlled-entitlement-demo/authority-state-package.edn).
It proposes the first Anvil account as a single-principal local fixture for
chain 31337 only. This is not an approved operator identity or a public
authority.

The candidate initial configuration is
[`initial-configuration-package.edn`](../../etc/deployments/eae-controlled-entitlement-demo/initial-configuration-package.edn).
It uses the canonical empty verifier-registry root for initial genesis; the
successor verifier registry is a separate later configuration transition.
The module, evidence, escrow, parameter, governance and interoperability
materials are explicit semantic proposals requiring owner approval. In
particular, the descriptor reference is copied from the pinned B2 Vector A
descriptor and does not constitute deployment qualification.

Reproduce candidate derivations from the PRF repository root:

```bash
clojure -M -m scripts.deployment.genesis-roots protocol-genesis etc/deployments/eae-controlled-entitlement-demo/protocol-genesis-package.edn
clojure -M -m scripts.deployment.genesis-roots authority-state etc/deployments/eae-controlled-entitlement-demo/authority-state-package.edn
clojure -M -m scripts.deployment.genesis-roots initial-configuration etc/deployments/eae-controlled-entitlement-demo/initial-configuration-package.edn
```

Every result says `derived-only-not-approved`. The candidate roots currently
derive to protocol genesis `sha256:668bc1…4775`, authority state
`sha256:7daba0…517a`, and initial configuration `sha256:131ff6…20f95`.

The committed policy describes a controlled Vector A source, the existing
source-bound entitlement proof profile, and a single explicitly configured
deployer authority. It does not establish a production claims authority,
production governance, or production PRF/SEW identity. Do not use this genesis
for another application or represent it as a production deployment.

Before any future deployment, obtain semantic approval for these candidates.
Then derive `chain-instance-genesis.v1` from the actual predicted
local Anvil deployment addresses and runtime code hashes, chain ID 31337,
initial authority-state root, and initial configuration root. Simulate first,
derive the canonical root with the existing Clojure implementation, confirm the
deployer nonce has not changed, then broadcast and verify the deployed values.
The Solidity script's root environment variable is an input; it is not a
canonical-root validator.

Review the three packages together with
`scripts.deployment.genesis-roots/derive-candidate-package-set`. It checks
cross-package protocol and execution scope and emits the exact seven-root C0
artifact plus package content digests. The `candidate-package-set` CLI command
accepts an EDN map containing the three complete packages under
`:protocol-genesis`, `:authority-state` and `:initial-configuration`.

`scripts.deployment.configuration-review/review-manifest` binds that record to
an explicit derivation source revision, derivation version, proposed approval
principal and local-only scope. `validate-review-bindings` recomputes the whole
manifest against the reviewer's expected revision and principal; unknown or
changed content fails. `manifest-root` provides the exact commitment an
external authenticated approval record must bind. These functions validate
review content; they do not authenticate approval or change candidate status.

The additive generic authority schema and local governor policy are specified
in [Authority state v1](../architecture/canonical-artifacts/AUTHORITY_STATE_V1.md).
They do not replace the demo candidate automatically. Migrating this package
requires review of the new inputs and roots.
