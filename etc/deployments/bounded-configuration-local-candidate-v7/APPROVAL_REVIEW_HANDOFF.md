# Current-source bounded configuration candidate v7 review handoff

This candidate supersedes v6 for review only. It is not an approval, trusted-key
enrollment, deployment authorization, or successor-transition approval. Earlier
candidate artifacts and handoffs remain preserved as historical records.

## Candidate identity

- Package set: `candidate-package-set.edn`
- Derived roots: `candidate-derivation.edn`
- Source-bound manifest: `review-manifest.v3.edn`
- Source lock: `../baselines/bounded-configuration-release-sources.current.v6.json`
- Source-lock SHA-256: `cf7fda26ab45a650b71a5e9ff85cc587cf695a878944617dd97def44edad6ced`
- Source-lock SHA-256: `cf7fda26ab45a650b71a5e9ff85cc587cf695a878944617dd97def44edad6ced`
- Source parent: `896ab2ca29d178d9b3993750d4fb774deaf984c4`
- Source tree: `sha256:6ffee509341fb888235d4bb3f0ef2cee466f29fb8ac4433fb3c892f077f2ae89`
- Source file count: `933`
- Canonical manifest root: `sha256:4a58d73213ff165e9e34fbfcd2694c029e681dbccdc0420bc0c0b3ea0c519f27`
- Raw manifest digest: `sha256:070562e4edd09d07fa0fc625d3e4108ec6a7038a098f55cc86d77bc99fc15b55`
- PRF-EVM deployables bundle root: `5b9da0dfc6b1a2864d0ae174faef3ddf0095dab21b4a158de25aef677235b7ba`
- PRF-EVM deployables manifest SHA-256: `2ecd59e9963fa4ae745b15a7b03c7f263806083717b2750bd689e562021425de`
- PRF-EVM source revision: `8ba7628bdfa46c1673aa15ba6205443ac1750ea9`
- C0: `sha256:131ff6b74047de747bd9ace93413b6b793d677749095f0dadf5dcc173e120f95`
- Scope: local Anvil, chain ID 31337, action `approve-initial-inputs`
- Proposed principal: `0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266`

The source lock contains 933 sorted file identities and includes the PRF
authority-continuity and deployable-export-normalization patches plus the
protocol-deployments W6 replay-fixture patch. Capital-flow replay now uses the
v0.4.2 core ABIs and its 34 replay tests pass. The exporter normalizes Foundry's
project-local absolute artifact paths before allow-list comparison; its tests
pass, and three exports, including one after a clean Foundry build, reproduce
the same 30-artifact bundle byte-for-byte. The canonical protocol-genesis,
authority-state, policy, initial configuration and seven C0 roots remain
unchanged. The source lock and deployables bundle are review material, not an
authenticated trust root. The reviewer must independently select the source
parent, source-tree digest, source root, bundle root and lock path.

## Initial-input approval

The previous v6 approval does not cover this source identity. Review this exact
manifest root, the four package artifacts, derived roots and complete source
lock. If approved, enroll the key and principal in an independently managed
trust policy and sign the fixed `approve-initial-inputs` action for this exact
manifest. Keep the private key outside this repository.

The initial-input review path requires its detached PRF Ed25519 approval
envelope. An EIP-191 authority-transition signature is a separate action and
cannot substitute for this approval. Keep the trust-context file and public-key
binding independently selected by the reviewer.

## Separate successor approval

Initial-input approval does not authorize C0-to-C1. After the approved input
package is deployed and observed, derive a separate transition manifest over
the sealed instance, parent/new roots, epoch, registry and proof mode, then
obtain a separate authenticated `approve-successor-transition` decision.
