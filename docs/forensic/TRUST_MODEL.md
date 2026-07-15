# Forensic Bundle Workflow Trust Model

## Scope

The workflow records and verifies evidence about a scenario execution. Its output is useful for reproducibility and tamper detection within the trust boundaries below. It must not be described as a constrained execution environment unless a deployment establishes and independently verifies those constraints.

## Trust boundaries

| Property | Current behavior | Assurance limit |
|---|---|---|
| Input declaration | Run request, registry snapshot, and evidence policy are recorded in the bundle. | Example files contain placeholders; real inputs must be supplied explicitly. |
| Source provenance | The runner records source-tree hash, commit state, and selected source roots. | This is not a hermetic build or a complete resolved-dependency lock. |
| Output integrity | Bundle records are self-hashed; verification recomputes supported hashes. Optional Ed25519 signing is supported. | Integrity relies on verifier implementation and trusted public-key distribution. |
| Network policy | The request declares allow/deny intent. | This is not a network sandbox or proof that no network access occurred. |
| Filesystem isolation | Shared filesystem is the default. `--private-tmpfs` requests a mount-namespace/tmpfs mode and records the actual mode. | It is optional and may fall back when host capabilities are unavailable. |
| Process isolation | The runner records host checks such as UID, ptrace scope, `/proc`, workspace access, and root status. | It does not enforce containers, seccomp, capability dropping, or a read-only root filesystem. |
| Immutability | Completed bundles can be hardened read-only, with best-effort filesystem immutability. | Local filesystem permissions are not an independent chain of custody. |

## Required language for external review

Use: **forensic bundle workflow**, **forensic execution configuration**, or **reference forensic run**.

Do not use: **isolated workspace**, **constrained execution environment**, **forensic-grade assurance**, or **immutable evidence** without qualifying the deployment-specific controls and independent verification that support the claim.

## External-review baseline

An externally reviewable bundle should include a pinned source revision, generated registry snapshot, explicit policy inputs, output from `bb forensic:verify`, signing public-key provenance when signed, and clean-environment reproduction instructions. A release artifact or intentionally versioned fixture should carry the bundle; it should not be mixed with editable configuration.
