# Current-source bounded configuration candidate v8 review handoff

This candidate supersedes v7 for review only. It is not an approval, trusted-key
enrollment, deployment authorization, or successor-transition approval. Earlier
candidate artifacts and handoffs remain preserved as historical records.

## Candidate identity

- Package set: `candidate-package-set.edn`
- Derived roots: `candidate-derivation.edn`
- Source-bound manifest: `review-manifest.v3.edn`
- Source lock: `../baselines/bounded-configuration-release-sources.current.v7.json`
- Source-lock SHA-256: `913eb4a6e91a81e7cbcc1fb156e0b51b8c1844a5677e04e4dd82e04c58494d44`
- Source parent: `e6fe09666adf8092b2023ea1ecdd84fbf24fa98a`
- Source tree: `sha256:066ee1dcd021ecf8e4d182f17a4ded02095cf3fb51e600ddbe96006f9d87ef09`
- Source file count: `934`
- Canonical manifest root: `sha256:44a5c842a98b59653a4d2ea4c3a7933785d7e8f9fa7919e575a44fbc9589dc9f`
- Raw manifest digest: `sha256:a19ef856e57d9c5043741ddc53793a2b13b4048c7e0edb31842015c2f8d437f5`
- PRF-EVM deployables bundle root: `1c040be8f3364d303dc66cabd0bb676994c836eb4021e9216862dc991a9c3bae`
- PRF-EVM deployables manifest SHA-256: `04b5b96971cb91b59adb294c3fc06ebf5de5061fabbe6328971702ebdcae3c41`
- PRF-EVM source revision: `0dad4c2790a60ecca9a41c5d518f99522eab94ce`
- C0: `sha256:131ff6b74047de747bd9ace93413b6b793d677749095f0dadf5dcc173e120f95`
- Scope: local Anvil, chain ID 31337, action `approve-initial-inputs`
- Proposed principal: `0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266`

The source lock contains 934 sorted file identities and includes the PRF
authority-continuity and deployable-export-normalization patches plus the
protocol-deployments W6 replay-fixture and report-compatibility patches.
Capital-flow replay now uses the v0.4.2 core ABIs and its 34 replay tests pass.
New verification reports use schema v4; retained v3 reports remain verifiable
under their original fixed trust assumptions. The exporter normalizes Foundry's
project-local absolute artifact paths before allow-list comparison; its tests
pass. The v0.4.2 local candidate was rebuilt with the authority-transition
validator and now contains 31 artifacts; its root and manifest digest above
are separately derived deployment-bundle identities, not fields in the
initial-input approval manifest. The canonical protocol-genesis,
authority-state, policy, initial configuration and seven C0 roots remain
unchanged. The source lock and deployables bundle are review material, not an
authenticated trust root. The reviewer must independently select the source
parent, source-tree digest, source root, bundle root and lock path.

## Initial-input approval

The signed v8 initial-input approval is retained at
`initial-input-approval.edn`. Its manifest root is
`sha256:44a5c842a98b59653a4d2ea4c3a7933785d7e8f9fa7919e575a44fbc9589dc9f`;
the detached signature and independently selected trusted context verify for
the exact 934-file source lock, source parent and canonical input package above.
The verifier must run against that pinned source tree; later source changes in
the working copy do not inherit this approval. This approves the canonical
initial inputs for the stated local-only scope, not deployment, genesis sealing,
or a successor configuration transition.

The initial-input review path requires its detached PRF Ed25519 approval
envelope. An EIP-191 authority-transition signature is a separate action and
cannot substitute for this approval. Keep the trust-context file and public-key
binding independently selected by the reviewer.

## Separate successor approval

Initial-input approval did not authorize C0-to-C1. The C0 deployment and
genesis seal have now completed on local Anvil. The independently replayed
deployment is retained in `live-execution/`; it is partial by design and stops
before the successor transition.

## Local W3/W4 execution evidence

- Deployment manifest root: `0xed7d64e4002322f351a362a721d6b7675f4cd117cb730ac194f98750c7ea4e78`
- Plan execution root: `0xbe533c0bff75fc6a17f133a9d470790d82a23114ab10a74ab2b2b343428c9b3d`
- Independent verification report root: `042507fd7c6acca3d69625c9d3527878d23e2fe21a11bb48b6e91dada24c0dfe`
- Chain-instance genesis root: `sha256:cddcc621c4f7f0e0d1952203785de6099e4819b2dbc8268b68b28cd76bf2c154`
- Genesis: `0x3fdc08d815cc4ed3b7f69ee246716f2c8bcd6b07`
- Authority adapter: `0x742489f22807ebb4c36ca6cd95c3e1c044b7b6c8`
- Configuration executor: `0x4000f8820522ac96c4221b299876e3e53bcc8525`
- Verified contract count: 8 of 14; plan execution status: partial

The retained replay independently verified the initial-input approval,
successor registry derivation, empty-registry seal, genesis seal preflight and
receipt/readback, effective verifier runtime closure, and live deployed
contracts. It replayed four PRF verifiers and recomputed live chain verification.
No successor transition, proof admission, or capital-flow transaction is
claimed. The local chain is now at block 120; old receipts from its earlier
state remain unusable.

## Prepared C0-to-C1 approvals

The deployment independently derives the following successor, but it remains
unapproved:

- C0: `sha256:131ff6b74047de747bd9ace93413b6b793d677749095f0dadf5dcc173e120f95`
- Successor verifier registry: `0xca8198811ed67ad944d7228512ae09dbe1648c2badd677a876c2a3f6790be2bd`
- C1: `sha256:c6d7c1e00696a1cca7fc8b0be5ce8581e862a3884d4f3521743fb4a960fee689`
- Chain-instance root: `sha256:cddcc621c4f7f0e0d1952203785de6099e4819b2dbc8268b68b28cd76bf2c154`
- Decision/transition root: `sha256:d64de70816ff38269d09a3cb3d18feb62a68da5ba05865ada057bbd1a013adc6`
- Successor authority-state root: `sha256:371ebf1e187672f9a005f2c6292afdbbb6c97a69a05d35f86ade7b373357ddfb`
- Successor-review manifest root: `sha256:1d4a8db274a8f9c502c534be1225a90bdb5a8e3f3a4430bca4a180d89446932b`
- EIP-191 execution commitment: `0xbdb96e441fd48a2781eb4b77abc0973d03d15587a1dc2fef3ebf4a66b9653fd9`

The review manifest, registry, unsigned authority-transition request, and
proposed trust contexts are retained beside this handoff. The signed
successor-review approval must use the dedicated
`approve-successor-transition` domain and role. Reusing the initial Ed25519 key
is supported only because it is separately registered under that role and
produces a distinct manifest-bound signature. The policy-v3 transition signer
is fixed by the approved local-authority-policy at `0xc78884593859e84524e41ce94cce38fbcb58c5f8`.
The previously supplied signer address `0xC0E09b04327fE31061CB1e7B869bA274D3A90C6C`
does not match that approved identity and cannot authorize this transition.
Obtain both fresh signatures before resuming deployment: the Ed25519 review
approval and an EIP-191 signature over the execution commitment above. The
older EIP-191 signature is not bound to this sealed instance or commitment.

Successor review artifact digests:

- Manifest: `successor-transition-manifest.edn`, SHA-256 `217a44f1bbd4a08e92152ba1bb78632fee6843631620b785b68719d7aa0a0bec`
- Verifier registry: `successor-verifier-registry.edn`, SHA-256 `6d5c958b84e3571b05f2e4dc0db44d30abb5630f5262f255f2c156614cd6724c`
- Proposed Ed25519 trust context: `/home/user/.config/prf/bounded-v8-successor-transition-trusted-context.proposed.edn`, SHA-256 `b8c547b2fa10327a241d50a14a48408f623ee48244cdfbff8c1229b79bfeee0d`
- Unsigned authority-transition request: `authority-transition-request.unsigned.edn`, SHA-256 `d6f8b3b6e37a3b0dd37e7e6171448f954c34e3d77e707ce035cf87396d9540d2`
- Proposed ECDSA trust context: `/home/user/.config/prf/bounded-v8-authority-transition-trusted-context.proposed.edn`, SHA-256 `c6eeb946630103a5b1c9677d63f2ed95b01287d4a9dc3a148e7f7b44049e9116`

After reviewing these exact roots and scopes, the successor Ed25519 envelope
can be created with:

```sh
clojure -M:test -m scripts.deployment.successor-transition-review sign \
  etc/deployments/bounded-configuration-local-candidate-v8/successor-transition-manifest.edn \
  etc/deployments/bounded-configuration-local-candidate-v8/successor-verifier-registry.edn \
  /home/user/.config/prf/bounded-v8-successor-transition-trusted-context.proposed.edn \
  /home/user/.ssh/prf-bounded-v8-initial-input-approval \
  :bounded-v8-local-successor-transition-approver-2026-10-19 \
  etc/deployments/bounded-configuration-local-candidate-v8/successor-transition-approval.edn
```

The policy-v3 EIP-191 signature must be produced by the key whose address is
`0xc78884593859e84524e41ce94cce38fbcb58c5f8`. From a local terminal, Cast can
sign the exact commitment above without putting the key in this repository:

```sh
cast wallet sign --interactive \
  0xbdb96e441fd48a2781eb4b77abc0973d03d15587a1dc2fef3ebf4a66b9653fd9
```

The command prompts for the private key and prints the EIP-191 signature. The
signer must be the policy-pinned address above; the `0xC0E…` key previously
provided is a different address and fails the on-chain validator check.

## Prepared genesis-seal preflight inputs

- Request: `genesis-seal-request.edn`
- Request SHA-256: `690dfc06336dd73fb3299da867b2b5a9409b07c3a7670c3dc001ce56b3c4aa9e`
- Proposed trusted context: `/home/user/.config/prf/bounded-v8-genesis-seal-trusted-context.proposed.edn`
- Proposed context SHA-256: `7ab1e08bcaf378e68fd9cc312f3e333a75ef2d5e93acea399deb71d5f9794e3a`

The proposed context pins runtime identities derived by EVM `eth_call` from the
v0.4.2 artifacts and approved constructor roots. The live seal gate matched
both expected hashes to on-chain runtime code and verified the seal transaction
and same-block readbacks. Genesis is at
`0x3fdc08d815cc4ed3b7f69ee246716f2c8bcd6b07`; the earlier predicted address in
the draft was for the verifier registry because the plan deploys it before
genesis. The approved authority adapter address and expected runtime hashes
were correct.
