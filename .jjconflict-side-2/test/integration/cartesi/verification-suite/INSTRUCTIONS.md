# Sew On-Chain Verification Suite

This directory contains the tools required to run the full Sew Protocol validation suite on Cartesi Rollups.

## Contents
- `../../scenarios/`: Canonical scenario source (S-series JSON scenarios).
- `run_all.py`: Script to batch-submit all scenarios to the Cartesi DApp.
- `fetch_results.py`: Script to retrieve the verifiable verification outputs (Notices) from the DApp.
- `outputs/`: Destination for verification result files.

## Instructions

### 1. Prerequisites
- Ensure the Cartesi node is running (GraphQL at `localhost:8080`, RPC at `localhost:8545`).
- Python 3 installed.
- `cast` CLI (from Foundry) available in your path.

### 1.1 Environment Variables
`run_all.py` now supports environment-based configuration.

Local/dev defaults are provided, but for staging/prod you should explicitly set:

- `APP_ENV` (`staging` or `prod`)
- `RPC_URL`
- `DAPP_ADDRESS`
- `INPUT_BOX_ADDRESS`
- `PRIVATE_KEY` (from secret manager)

You can start from `../.env.example` and inject values via CI/orchestrator.

### 2. Execute All Scenarios
Run the batch submission script. This will send each canonical scenario as an input to the Cartesi InputBox.
```bash
python3 run_all.py
```

For a safe preflight check (no transactions sent):
```bash
python3 run_all.py --dry-run
```

### 3. Verify Processing
Wait for the Cartesi node to process the inputs. You can monitor the node logs to see the Clojure simulation engine executing each scenario.

### 4. Fetch Verification Outputs
Once the node has finished processing, fetch the results (Notices). These results contain the full execution trace and pass/fail status, cryptographically signed by the Cartesi Machine.
```bash
python3 fetch_results.py
```

### 5. View Results
The verification outputs will be saved in the `outputs/` directory. Each file (e.g., `S01_output.json`) contains:
- `outcome`: `:pass` or `:fail`.
- `scenario-id`: The identifier of the verified scenario.
- `trace`: The step-by-step state transitions and invariant checks.
- `metrics`: Final aggregate metrics for that specific run.

## Why this is verifiable
Every output in the `outputs/` directory corresponds to a **Cartesi Notice**. Because the Cartesi Machine is deterministic and its state transitions are proven via Merkle tree proofs, these outputs are as trustless as the underlying Layer 1 blockchain.

## Staging/Prod Rollout Notes

1. Validate non-secret config values in staging first (`RPC_URL`, addresses, environment profile).
2. Inject `PRIVATE_KEY` only through secret management tooling.
3. Run `run_all.py --dry-run` before live submissions.
4. Run a limited smoke subset on staging/testnet, confirm notice outputs.
5. Promote the same container image digest to prod.
