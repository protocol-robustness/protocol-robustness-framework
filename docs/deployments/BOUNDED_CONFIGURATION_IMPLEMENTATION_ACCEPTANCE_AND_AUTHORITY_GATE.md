# Bounded configuration implementation acceptance and authority gate

## Decision

Accept the implementation as complete within the tested local scope. Deployment
and configuration-transition execution remain blocked on independently
authenticated external authority. This acceptance does not approve the genesis
inputs, authorize sealing, approve a successor transition, or establish public
or production readiness. No further implementation work is required by the
current audit. The deployment gate is **awaiting external authority**, not
implementation. Do not make further automated approval, deployment, or
qualification attempts until the required approval artifacts and independently
trusted context are available.

The candidate is frozen for review at the following identity:

| Item | Frozen value |
| --- | --- |
| Source parent | `d282a5fa6f2224c71f510353ca6758cedcb635e4` |
| Source tree SHA-256 | `d1b819e37633db8af181bf6b637ba5c615cc69f96ab51bad69003eb599218c68` |
| Canonical review-manifest root | `sha256:7662a1a217f2077fb63554c46d28d799eb9c0c8f37aeaf6507aaf66d54721d79` |
| Raw `review-manifest.v3.edn` SHA-256 | `7e9109321b2c58926c8ba1f41fdd945cc3ae37088973f5de6060875cd6073be1` |
| Candidate scope | Local Anvil, chain ID `31337` |
| C0 | `sha256:131ff6b74047de747bd9ace93413b6b793d677749095f0dadf5dcc173e120f95` |
| Candidate package set | [candidate-package-set.edn](../../etc/deployments/bounded-configuration-local-candidate-v2/candidate-package-set.edn) |
| Approval handoff | [APPROVAL_REVIEW_HANDOFF.md](../../etc/deployments/bounded-configuration-local-candidate-v2/APPROVAL_REVIEW_HANDOFF.md) |

The source-bound manifest and package artifacts remain marked
`candidate-owner-approval-required`. Preserve the source parent, source tree,
manifest root and raw digest together as the review identity. Any material
change to the candidate requires a new identity and renewed review.

## Tested acceptance scope

- Focused configuration suite: 138 tests, 612 assertions passed.
- Authority-transition suite: 10/10 passed.
- The broader unit suite passed 891 tests and 3,556 assertions before the final
  focused source-lock test was added.
- Solidity compilation and the bounded Solidity test runs passed for the
  recorded candidate scope; the source and test evidence is summarized in
  [the remaining-work plan](BOUNDED_CONFIGURATION_REMAINING_PLAN.md).
- The candidate derivation, source-bound manifest, and approval handoff are
  retained alongside the four candidate package artifacts.

These results establish implementation acceptance within the tests and local
scope recorded above. They do not authenticate an approver or authorize
deployment.

## Single external-authority dependency

The outstanding gate is authenticated approval and evidence for this exact
frozen candidate. Do not solicit or fabricate missing artifacts repeatedly.
Keep W2 and W4–W7 blocked until the required evidence is supplied through the
independently managed trust context and all bindings verify.

The gate requires two distinct approvals:

1. **Initial inputs:** an authorized input owner signs the exact canonical
   manifest root under `approve-initial-inputs`, scoped to local Anvil chain
   `31337`. The detached Ed25519 envelope must bind the frozen source parent,
   source-tree digest, manifest root, four package artifacts and the selected
   principal. The signing key must be active in an independently supplied
   trust policy with role `:prf-local-configuration-approver`, bound to that
   principal. The handoff documents the signing and verification commands;
   verification must use independently supplied trusted-context references,
   including source revision, source tree, source root, release source lock,
   principal, trust policy and fixed action.
2. **C0→C1 transition:** after deployment facts and verifier identities have
   been independently observed, the authorized transition authority signs a
   separate successor manifest under `approve-successor-transition`, using a
   distinct trusted role/key. It must bind the exact chain-instance root, C0
   and C1, parent and successor verifier-registry roots, epoch 1, proof mode,
   frozen source identity and local scope. This approval cannot be prepared
   against the current candidate alone because the required live identities do
   not yet exist.

Before any transaction, verify both approval envelopes and their trust
bindings. W4–W7 additionally require independently observed deployment
receipts and readbacks, the actual C1 and verifier identities, the proof and
protected-capital-flow evidence, and approval of the qualification
expectations. A deployment receipt is evidence, not automatic registry or
release approval. Keep signing keys outside the repository.

The required external approver roles are therefore the initial-input owner and
the separate successor-transition authority. Their identities and keys must
come from the approved independent trust context; the proposed local governor
address in the candidate is not proof of either identity.

## Printed approval requests

These are the pending requests for the external authority owners; they are
recorded here for review and have not been sent:

- **Initial-input owner:** review the four frozen package artifacts and
  derivation, confirm the approving principal, and provide an authenticated
  Ed25519 approval for manifest root
  `sha256:7662a1a217f2077fb63554c46d28d799eb9c0c8f37aeaf6507aaf66d54721d79`
  under `approve-initial-inputs`, together with independently managed trust
  context sufficient to verify the signer and exact source bindings.
- **Successor-transition authority:** after the approved deployment is
  observed, review the exact chain-instance, C0→C1, verifier-registry, epoch,
  proof-mode and source bindings; provide a separate authenticated approval
  under `approve-successor-transition` and its independent trust context.

No deployment transaction is authorized while either request is outstanding.
