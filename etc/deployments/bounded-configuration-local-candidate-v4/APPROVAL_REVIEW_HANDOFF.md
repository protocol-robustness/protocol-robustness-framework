# Current-source bounded configuration candidate v4 review handoff

This is a new review candidate for the current PRF source revision. It is not
an approval, trusted-key enrollment, deployment authorization, or successor
transition approval. The previous v2 and v3 candidates and their handoffs
remain unchanged as historical review material.

## Candidate identity

- Package set: `candidate-package-set.edn`
- Derived roots: `candidate-derivation.edn`
- Source-bound manifest: `review-manifest.v3.edn`
- Source lock: `../baselines/bounded-configuration-release-sources.current.v3.json`
- Source parent: `75c5055b23a2f1b27f43af3696eb4be99f51ed7b`
- Source tree: `sha256:c39f3aa7a96304d038c5263e811e46f1012831f5a702a2d7f38f332f45701527`
- Canonical manifest root: `sha256:b25d89eecf2c245c03e49ddd5f78080c183c9c44e66d7a8b9eac0193d953ac56`
- Raw manifest digest: `sha256:714f5414ec2302795cec4d517af52326bcd16c1c05fb3cdad8dc872725d1adb7`
- C0: `sha256:131ff6b74047de747bd9ace93413b6b793d677749095f0dadf5dcc173e120f95`
- Transition-validator key ID: `bounded-local-transition-validator-v1`
- Transition-validator EVM signer: `0xc78884593859e84524e41ce94cce38fbcb58c5f8`
- Scope: local Anvil, chain ID 31337, action `approve-initial-inputs`
- Principal in the proposed manifest: `0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266`

The lock covers 930 files and independently verifies against the current
checkout. It refreshes nine file digests from the frozen v1 source baseline,
including `src/resolver_sim/hash/canonical.clj`, and adds the project dependency
declarations and local accounting dependency loaded by the deployment
classpath. The canonical package roots still reproduce from these artifacts.
Review the changed sources and complete package before approval. The source lock
is review material, not an authenticated trust root. The approver must
independently select the source revision, source-tree digest, source root and
lock path.

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
artifacts, derived roots, full source lock, and the nine source changes plus the
four newly bound dependency-closure files. Enroll an owner-controlled Ed25519
key in an independently managed trust policy with role
`:prf-local-configuration-approver`, active status and `:approval/principal`
equal to the selected manifest principal. If the owner selects a different
principal, regenerate the manifest and review its new root.

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
