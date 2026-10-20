# Current-source bounded configuration candidate v6 review handoff

This candidate supersedes v5 for review only. It is not an approval, trusted-key
enrollment, deployment authorization, or successor-transition approval. Earlier
candidate artifacts and handoffs remain preserved as historical records.

## Candidate identity

- Package set: `candidate-package-set.edn`
- Derived roots: `candidate-derivation.edn`
- Source-bound manifest: `review-manifest.v3.edn`
- Source lock: `../baselines/bounded-configuration-release-sources.current.v5.json`
- Source parent: `eb6da5102d14ca4080fe08df7b6c349311ea943c`
- Source tree: `sha256:1ce2108ce7c2cda3e1ff7cdcfea351b4b2f5a689622aae0fa05b3a950f429523`
- Source file count: `932`
- Canonical manifest root: `sha256:01d108c28db1d40d64f0de67cdab8e2e05f5cb6a745e38e9069b55061de4b63c`
- Raw manifest digest: `sha256:3e92400f70aa917a60cff88c41f5a8dfbe223b5b247522ca622eb62b8a61dbc2`
- PRF-EVM deployables bundle root: `5b9da0dfc6b1a2864d0ae174faef3ddf0095dab21b4a158de25aef677235b7ba`
- PRF-EVM deployables manifest SHA-256: `2ecd59e9963fa4ae745b15a7b03c7f263806083717b2750bd689e562021425de`
- PRF-EVM source revision: `8ba7628bdfa46c1673aa15ba6205443ac1750ea9`
- C0: `sha256:131ff6b74047de747bd9ace93413b6b793d677749095f0dadf5dcc173e120f95`
- Scope: local Anvil, chain ID 31337, action `approve-initial-inputs`
- Proposed principal: `0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266`

The source lock contains 932 sorted file identities and includes the PRF
authority-continuity and deployable-export-normalization patches. The latter
normalizes Foundry's project-local absolute artifact paths before allow-list
comparison; its tests pass, and three exports, including one after a clean
Foundry build, reproduce the same 30-artifact bundle byte-for-byte. The
canonical protocol-genesis,
authority-state, policy, initial configuration and seven C0 roots remain
unchanged. The source lock and deployables bundle are review material, not an
authenticated trust root. The reviewer must independently select the source
parent, source-tree digest, source root, bundle root and lock path.

## Initial-input approval

The previous v5 approval does not cover this source identity. Review this exact
manifest root, the four package artifacts, derived roots and complete source
lock. If approved, enroll the key and principal in an independently managed
trust policy and sign the fixed `approve-initial-inputs` action for this exact
manifest. Keep the private key outside this repository.

## Separate successor approval

Initial-input approval does not authorize C0-to-C1. After the approved input
package is deployed and observed, derive a separate transition manifest over
the sealed instance, parent/new roots, epoch, registry and proof mode, then
obtain a separate authenticated `approve-successor-transition` decision.
