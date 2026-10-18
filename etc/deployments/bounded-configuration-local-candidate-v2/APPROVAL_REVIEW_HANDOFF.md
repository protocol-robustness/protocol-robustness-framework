# Initial-input approval review handoff

This handoff identifies the current candidate for owner review. It is not an
approval, trusted-key enrollment, deployment authorization, or successor
transition approval.

## Candidate under review

- Package set: `candidate-package-set.edn`
- Derived roots: `candidate-derivation.edn`
- Source-bound manifest: `review-manifest.v3.edn`
- Canonical manifest root: `sha256:7662a1a217f2077fb63554c46d28d799eb9c0c8f37aeaf6507aaf66d54721d79`
- Raw manifest file digest: `sha256:7e9109321b2c58926c8ba1f41fdd945cc3ae37088973f5de6060875cd6073be1`
- Source parent: `d282a5fa6f2224c71f510353ca6758cedcb635e4`
- Source tree: `d1b819e37633db8af181bf6b637ba5c615cc69f96ab51bad69003eb599218c68`
- Scope: local Anvil, chain ID 31337, action `approve-initial-inputs`
- C0: `sha256:131ff6b74047de747bd9ace93413b6b793d677749095f0dadf5dcc173e120f95`
- Empty verifier registry: `sha256:2099fabf2cca563f8d183749d1a842946286e8704a96c51ec801eff4e834a240`
- Policy-v3 transition-validator key ID: `bounded-local-transition-validator-v1`
- Policy-v3 transition-validator address: `0xc78884593859e84524e41ce94cce38fbcb58c5f8`

The four candidate package artifacts are protocol genesis, initial
configuration, authority state, and local authority policy. The initial
configuration commits all seven roots. All four artifacts are still marked
`candidate-owner-approval-required`.

## Owner decisions before signing

1. Review the complete four-artifact package, its derived roots, and the
source-lock entries, including the policy-v3 validator key ID and address
above. Approval covers the exact manifest root above, not only C0.
2. Select the real approving principal. The manifest currently names
`0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266`, a proposed local principal. Do
not treat that value or the candidate Anvil governor as an authenticated owner
identity. If the owner selects another principal, regenerate the manifest and
recompute its root before signing.
3. Enroll an owner-controlled Ed25519 public key in an independently managed
trust policy with role `:prf-local-configuration-approver`, active status,
and `:approval/principal` equal to the selected manifest principal. Do not use
test keys or the unconfigured release trust policy.
4. Have the owner sign the exact manifest under action
`approve-initial-inputs` with the dedicated PRF approval domain. Keep the
private key outside this repository and retain only the detached approval
envelope and the independently managed public trust record.

If the principal changes, regenerate the manifest with:

```sh
clojure -M:test -m scripts.deployment.configuration-review manifest-v3 \
  etc/deployments/bounded-configuration-local-candidate-v2/candidate-package-set.edn \
  d282a5fa6f2224c71f510353ca6758cedcb635e4 \
  d1b819e37633db8af181bf6b637ba5c615cc69f96ab51bad69003eb599218c68 \
  <owner-principal>
```

The owner can create the detached envelope with the dedicated command. It
rechecks the content, source lock, fixed action, and trusted public-key binding
before signing, and it refuses to overwrite an existing approval file:

```sh
clojure -M:test -m scripts.deployment.configuration-review sign-initial-inputs \
  <manifest.edn> <candidate-package-set.edn> <trusted-context.edn> \
  <private-key-path> :owner-approval-2026 <approval.edn>
```

Keep the private key outside the repository. The key ID must already be active
in the independently supplied trust policy and bound to the manifest principal.

The reviewer verifies the detached envelope using the separately supplied
trusted context and approval policy:

```sh
clojure -M:test -m scripts.deployment.configuration-review verify \
  <manifest.edn> <candidate-package-set.edn> \
  <trusted-context.edn> <approval.edn>
```

The trusted context must independently supply source revision, source-tree
digest, source root, release source-lock path, principal, trust policy, and
fixed action. A successful content check without an authenticated signature
does not authorize deployment.

## Separate successor approval

Do not create or reuse a C0-to-C1 approval from this package. After the
approved initial deployment is sealed and successor verifier identities are
observed, derive a new transition manifest over the exact instance, C0/C1,
registry root, epoch, proof mode, source revision, and local scope. Obtain a
separate approval under `approve-successor-transition` and its distinct trust
role. The signer command is:

```sh
clojure -M:test -m scripts.deployment.successor-transition-review sign \
  <successor-manifest.edn> <verifier-registry.edn> \
  <transition-trusted-context.edn> <transition-private-key-path> \
  :successor-approval-key <successor-approval.edn>
```

The current workspace contains no such deployment-specific manifest or
approval evidence. This signer cannot run until live deployment facts produce
the exact C1 transition review.
