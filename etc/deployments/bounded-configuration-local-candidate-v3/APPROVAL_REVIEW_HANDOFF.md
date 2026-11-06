# Current-source bounded configuration candidate review handoff

This is a new review candidate for the current PRF source revision. It is not
an approval, trusted-key enrollment, deployment authorization, or successor
transition approval. The previous v2 candidate and its handoff remain
unchanged as historical review material.

## Candidate identity

- Package set: `candidate-package-set.edn`
- Derived roots: `candidate-derivation.edn`
- Source-bound manifest: `review-manifest.v3.edn`
- Source lock: `../baselines/bounded-configuration-release-sources.current.v2.json`
- Source parent: `75c5055b23a2f1b27f43af3696eb4be99f51ed7b`
- Source tree: `sha256:ac464875d45654294a43e0fbcdccfa7bb3cd0fb50db2fba098d0f4a89d30ec95`
- Canonical manifest root: `sha256:c68a6ee9bc99dcacf04c28730e9c7b5f5f83e750709679d90c9ba4a570d22c9f`
- Raw manifest digest: `sha256:ef1ec44816ef5d3bae1a40cd4a564a64ce3e4546359784d67b3292a45ca8a78e`
- C0: `sha256:131ff6b74047de747bd9ace93413b6b793d677749095f0dadf5dcc173e120f95`
- Transition-validator key ID: `bounded-local-transition-validator-v1`
- Transition-validator EVM signer: `0xc78884593859e84524e41ce94cce38fbcb58c5f8`
- Scope: local Anvil, chain ID 31337, action `approve-initial-inputs`
- Principal in the proposed manifest: `0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266`

The lock covers 926 files and independently verifies against the current
checkout. It refreshes nine file digests from the frozen v1 source baseline,
including `src/resolver_sim/hash/canonical.clj`; the canonical package roots
still reproduce from these artifacts. Review those changed sources and the
complete package before approval. The source lock is review material, not an
authenticated trust root. The approver must independently select the source
revision, source-tree digest, source root and lock path.

The four package artifacts and seven C0 roots are unchanged from candidate v2.
Their status remains `candidate-owner-approval-required`. The validator key
address and local governor are proposed candidate values, not authenticated
identities. No private key for the transition-validator address is retained in
this repository, and external key custody has not been demonstrated. Before
approving this policy, the owner must confirm that an owner-controlled signer
can produce the required EIP-191 attestation for that exact address and that
the independent trust policy binds the stated key ID and address. Otherwise,
replace the validator address with an owner-controlled signer, rederive the
policy and package roots, and issue a new manifest. Do not use test keys, the
candidate Anvil governor, or this handoff as owner approval material.

## Initial-input approval

Before signing, the owner must review the exact manifest root, all four package
artifacts, derived roots, source lock and the nine changed source files. Enroll
an owner-controlled Ed25519 key in an independently managed trust policy with
role `:prf-local-configuration-approver`, active status and
`:approval/principal` equal to the selected manifest principal. If the owner
selects a different principal, regenerate the manifest and review its new root.

Use the dedicated `sign-initial-inputs` command with independently supplied
trusted context and a private key kept outside this repository. It validates
the source lock and fixed action and refuses to overwrite an existing approval
file. Retain only the detached envelope and external public trust record.

## Separate successor approval

Initial-input approval does not authorize C0-to-C1. After approved deployment
facts exist, derive a separate transition manifest over the sealed instance,
parent/new roots, epoch, registry and proof mode. Obtain a separate authenticated
approval under `approve-successor-transition`. No successor approval or live
deployment is present in this candidate.
