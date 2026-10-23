# Bounded configuration local demonstration

This directory records a local demonstration profile separate from the frozen
v8 candidate. It is scoped to Anvil, chain ID `31337`; it is not a mainnet or
production deployment package.

## Approved configuration

- Protocol genesis root: `sha256:668bc1d132b8745a3f6db7fa0b2dad2b85a11246e00c39e7b24d266af4724775`
- Initial configuration C0: `sha256:131ff6b74047de747bd9ace93413b6b793d677749095f0dadf5dcc173e120f95`
- Successor configuration C1: `0xf8fa9013e9ba64dac0215844f95aee2b32adecaba7402ec5fcc428b0c0349f71`
- Local chain-instance root: `sha256:2a19747a3680218c7654011fa6a8c711c6f24344992194841977dae8e236299f`
- Initial-input approval manifest: `sha256:ce9ee290f19ff43728144ef9e0bd93bb76dc42b596fea8564fe4496ba5bc4def`
- C0-to-C1 approval manifest: `sha256:4918f4062ddb93ba4b9cb1da3648feeeab4749fbbf932640bbd0fd280a9b6ac8`

The package preserves the signed approvals, trusted contexts, derivations and
transition request used for the local run. The governor is Anvil account 0;
the separately configured transition validator is Anvil account 1. These are
demonstration identities only.

## Qualification record

The attached `deployment-manifest.v1.json` and
`deployment-plan-execution.v1.json` record the live Anvil deployment and
ordered plan. The plan execution root is
`0x5d95c85ffba91a1e25bcece31936d094475a9c6c888802fd120c935dd790ab85`; it
contains 19 verified stages, zero blocked stages, and three not-executed
stages. The files' SHA-256 digests are recorded below.

The three not-executed stages are proof admission and operation, entitlement
activation and claims, and independent reconcile. The proof attempt details are
preserved in `proof-generation-attempt.v1.json`. Local SP1 Groth16 proof
generation was attempted with the deployed source verifier identity and the
current witness. Native Rust derivation accepted the witness, but the CPU
prover reached approximately 16 GB resident memory and exhausted nearly all
system swap before a proof artifact was produced. The run was stopped to
protect the workstation. This is a local resource limit, not a proof rejection.

After the resource stop, the managed Anvil process stopped and was restarted
from its persisted state without a reset. Latest-state reads at block 147
confirm the sealed chain-instance root, approved C1 root, epoch 1, and sealed
successor registry; see `current-chain-readback.v1.json`. The full independent
plan replay could not be repeated because the reloaded node no longer serves
the earlier seal block 128 (`BlockOutOfRangeError`). The live verifier also
refused to report qualification while the retained plan assertions were not
re-evaluated. The existing deployment report remains a partial execution
record; the latest-state readback does not replace historical receipt replay.

The reserve-binding input uses the explicitly labeled local-demo schema in the
deployment manifest. Do not present that demonstration binding as a qualified
cross-language PRF/Solidity equivalence result. The local demonstration is
therefore not end-to-end qualified until the three remaining stages complete.
Do not change the frozen v8 source identity when recording this run.

Evidence file SHA-256 digests:

- Deployment manifest: `ff38c5def60d5bb3c761c1a97ddd61d334248eb80b3140ceecbd115d2e73cab5`
- Plan execution report: `599c0ffd15fc3fc643a5549fe79183d1de6416fd8851f9c62793f79263dffb69`
- Latest-state readback: `9af5fead79a308d6317b479dd4a2e5bf348659dc6e62b278563c417494422bdc`
- Proof attempt record: `d786bf22b3abbe6ef8e0b74fa5ddff7a426f97d853991a3c33a811cf10ec2d82`
