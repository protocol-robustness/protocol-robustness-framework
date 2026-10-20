# Current-source bounded configuration candidate v5 review handoff

This candidate supersedes v4 for review only. It is not an approval, trusted-key
enrollment, deployment authorization, or successor-transition approval. Earlier
candidate artifacts and handoffs remain preserved as historical records.

## Candidate identity

- Package set: `candidate-package-set.edn`
- Derived roots: `candidate-derivation.edn`
- Source-bound manifest: `review-manifest.v3.edn`
- Source lock: `../baselines/bounded-configuration-release-sources.current.v4.json`
- Source parent: `01837e6237c625a3d23bdeafc8075232a2dfabd0`
- Source tree: `sha256:708569f9856817c155ad04ab947609cd63dabf659bbe1f4c54bd1404f1d2488d`
- Canonical manifest root: `sha256:a0905e8b132a5e6b260e27f7d9188b6fb8afb7d56a7b6ae0e2d93627fd1f678d`
- Raw manifest digest: `sha256:6beb6f8236d21ce57f4065549d8e6efc49ebfcc3fdccd08d0ba452c6917edadd`
- C0: `sha256:131ff6b74047de747bd9ace93413b6b793d677749095f0dadf5dcc173e120f95`
- Scope: local Anvil, chain ID 31337, action `approve-initial-inputs`
- Proposed principal: `0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266`

The source lock contains 931 sorted file identities and includes the new PRF
authority-continuity follow-up patch. It also refreshes the digest for
`src/resolver_sim/shortfall/allocation_binding.clj`, which had diverged from the
previous lock. The PRF patch rechecks authority-state continuity when sealing
genesis and binds statement admission to the configuration executor's chain
instance at construction and admission. The canonical protocol-genesis,
authority-state, policy, initial configuration and seven C0 roots remain
unchanged. The source lock is review material, not an authenticated trust root.
The reviewer must independently select the source parent, source-tree digest,
source root and lock path.

## Initial-input approval

The previous v4 approval does not cover this source identity. Review this exact
manifest root, the four package artifacts, derived roots and complete source
lock. If approved, enroll the key and principal in an independently managed
trust policy and sign the fixed `approve-initial-inputs` action for this exact
manifest. Keep the private key outside this repository.

## Separate successor approval

Initial-input approval does not authorize C0-to-C1. After the approved input
package is deployed and observed, derive a separate transition manifest over
the sealed instance, parent/new roots, epoch, registry and proof mode, then
obtain a separate authenticated `approve-successor-transition` decision.
