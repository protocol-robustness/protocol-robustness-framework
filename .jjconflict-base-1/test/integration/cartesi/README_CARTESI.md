# Sew Cartesi DApp — Experimental

> **Status: Experimental / Proof-of-concept.**
> This integration has not been audited. It is not production-ready.
> It demonstrates that the Protocol Robustness Framework kernel can be compiled for the
> Cartesi Rollups stack and run verifiably on-chain, but the integration
> is incomplete and not actively maintained.

This DApp runs the Sew protocol simulation verifiably on the Cartesi Rollups stack.

## Quick Start

### 1. Build and Start the Node
Follow the Cartesi documentation to start your local development environment (e.g., using `cartesi build` and `cartesi run`).

### 2. Interact using the CLI
We provide a unified tool for sending scenarios and inspecting the DApp state:

```bash
# Send a simulation
python3 cartesi_cli.py send scenario_min.json

# Check simulation pass/fail metrics
python3 cartesi_cli.py inspect metrics

# View recent simulation history
python3 cartesi_cli.py inspect history

# Fetch simulation traces (Notices)
python3 cartesi_cli.py notices
```

## Documentation
- [CARTESI_INTERACTIONS.md](CARTESI_INTERACTIONS.md) — Detailed explanation of the on-chain simulation architecture and interaction types.
- [../README.md](../README.md) — Documentation for the core Clojure simulation engine.
- [verification-suite/INSTRUCTIONS.md](verification-suite/INSTRUCTIONS.md) — Batch submission/fetch flow for Cartesi verification.
- [REPO_ORGANIZATION_PLAN.md](REPO_ORGANIZATION_PLAN.md) — Repository ownership boundaries and migration plan.

## Repository Organization

- `../scenarios/` is the canonical source of scenario JSON files.
- `verification-suite/` is the Cartesi execution harness (submission/fetch scripts + generated outputs).

## Environment Configuration (Dev / Staging / Prod)

The Docker image includes a local default:

- `ROLLUP_HTTP_SERVER_URL=http://127.0.0.1:5004`

For staging/prod, override this at runtime (orchestrator/manifests), do not rely on local defaults.

Use `.env.example` as a template for required runtime variables:

- `APP_ENV` (`dev`, `staging`, `prod`)
- `ROLLUP_HTTP_SERVER_URL` (Rollups HTTP endpoint)
- `RPC_URL` (L1/L2 RPC used by verification scripts)
- `DAPP_ADDRESS`
- `INPUT_BOX_ADDRESS`
- `PRIVATE_KEY` (secret; never commit)

### Environment Profile Guidance

- **dev**: local node URLs (`127.0.0.1`), ephemeral/dev keys.
- **staging/testnet**: testnet RPC + deployed testnet DApp/InputBox addresses, keys from secret manager.
- **prod/mainnet**: production RPC + mainnet addresses, restricted key management and approval workflow.

## Productionization Checklist

1. Inject all runtime env vars from deployment system (not hardcoded in repo).
2. Store `PRIVATE_KEY` in a secret manager and mount/inject at runtime.
3. Pin image tags by digest in staging/prod deployment manifests.
4. Validate with `run_all.py --dry-run` before real submissions.
5. Run a small smoke scenario subset on staging before promoting to prod.
6. Ensure logs/alerts exist for rollup endpoint connectivity and failed submissions.
