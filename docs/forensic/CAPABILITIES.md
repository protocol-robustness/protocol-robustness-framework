# Forensic Bundle Workflow Capabilities

The forensic bundle workflow is a repository-level execution and evidence-packaging capability. It is **not** a separate workspace or a guarantee of an isolated execution environment.

## What it provides

- Declared run requests, registry snapshots, and evidence-policy inputs.
- Preflight checks before scenario execution.
- Source, input, and environment snapshots in an output bundle.
- Content-addressed bundle and overview records, with verification commands.
- Optional Ed25519 signing and RFC 3161 timestamp anchoring when configured.
- Export, import, reproduction, deterministic self-test, and local quorum tools.
- Optional private-tmpfs execution mode and recorded host isolation checks.

## Repository layout

| Location | Purpose |
|---|---|
| `config/forensic/` | Versioned default evidence, execution, and output policy definitions. |
| `examples/forensic-reference-run/` | Non-evidentiary example inputs. Placeholder values must not be used for a real run. |
| `scripts/forensic/` | Orchestration, preflight, verification, and bundle tools. |
| `docs/forensic/BUNDLE_WORKFLOW.md` | Command reference and lifecycle. |
| `docs/forensic/TRUST_MODEL.md` | Enforced versus recorded properties and limitations. |

Generated bundles belong outside the repository, normally under `~/prf-runs/<run-id>/`, and should be distributed as release artifacts or intentionally versioned test fixtures.

## Non-claims

This workflow does not, by itself, provide a hermetic build, dependency lockfile, mandatory container isolation, enforced network sandbox, external key management, or independent chain of custody. See `TRUST_MODEL.md` and `PRODUCTION_READINESS.md` for the current limitations.
