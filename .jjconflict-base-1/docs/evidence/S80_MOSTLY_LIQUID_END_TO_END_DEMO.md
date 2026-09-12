# End-to-End Demonstration: S80 Mostly-Liquid Scenario

Scenario under test:

- `scenarios/S80_yield-mostly-liquid-partial-liquidity.json`
- `scenario_id`: `s80-yield-mostly-liquid-partial-liquidity`

This walkthrough shows the full researcher workflow: run → inspect → sign → share → visual narrative.

---

## 1) Run the scenario and generate primary results

### 1.1 Run this scenario directly

```bash
clojure -M:run -- --invariants --scenario scenarios/S80_yield-mostly-liquid-partial-liquidity.json --output-file results/s80-mostly-liquid.result.json
```

### 1.2 Run canonical contract checks (naming/registry integrity)

```bash
./scripts/test.sh contracts
```

---

## 2) Where evidence/results are saved

### Scenario result output (from 1.1)

- `results/s80-mostly-liquid.result.json`

### Canonical machine-readable test artifacts (from 1.2)

- `results/test-artifacts/test-summary.json`
- `results/test-artifacts/test-run.json`
- `results/test-artifacts/test-artifacts.json`
- `results/test-artifacts/claimable-classification.json`

### Optional trace comparison artifacts

If you compare this scenario against a **fully liquid baseline** (S68):

```bash
bb trace:diff \
  scenarios/S68_yield-aave-long-horizon-10y-monthly-accrual.json \
  scenarios/S80_yield-mostly-liquid-partial-liquidity.json \
  results/trace-compare/s68-vs-s80
```

Outputs:

- `results/trace-compare/s68-vs-s80/comparison.json`
- `results/trace-compare/s68-vs-s80/comparison.md`

---

## 3) How a researcher inspects consequences

Use three layers of inspection:

### 3.1 Fast gate signals

- Open `results/test-artifacts/test-summary.json`
- Confirm contracts target passed.

### 3.2 Scenario-level outcome

- Open `results/s80-mostly-liquid.result.json`
- Inspect terminal state and metrics for liquidity/shortfall effects.
- If using projection output, inspect shortfall fields (explicit block):
  - `shortfall-explicit?`
  - `has-shortfall?`
  - `entry-count`
  - `fulfilled-total` / `deferred-total` / `haircut-total`
  - `reasons`

### 3.3 Comparative interpretation (vs fully liquid)

- Compare S80 against a fully liquid baseline (S68) using `bb trace:diff`.
- Read `comparison.md` for narrative delta and `comparison.json` for machine-readable evidence.

### 3.4 Expected comparison lens (S68 vs S80)

When reviewing the pair, explicitly check:

1. scenario outcome/path differences,
2. any shortfall-related deltas,
3. whether invariants remain satisfied,
4. whether terminal accounting remains conserved.

---

## 4) How a researcher signs the evidence

Use the benchmark evidence tooling (deterministic bundle + signature path).

### 4.1 Generate an evidence bundle

```bash
bb benchmark:run benchmarks/dispute-liveness.edn -o evidence/s80-demo.edn
```

### 4.2 Sign at run-time (embedded signature)

```bash
bb benchmark:run benchmarks/dispute-liveness.edn -o evidence/s80-demo-signed.edn -k test_key.pkcs8
```

### 4.3 Or generate independent attestation

```bash
bb benchmark:attest evidence/s80-demo.edn -k test_key.pkcs8
```

This writes:

- `evidence/s80-demo.edn.attestation.edn`

### 4.4 Verify integrity/signature

```bash
bb benchmark:verify evidence/s80-demo-signed.edn
bb benchmark:verify-attestation evidence/s80-demo.edn.attestation.edn
```

---

## 5) How a researcher shares the evidence

### 5.1 Export portable bundle

```bash
bb benchmark:export evidence/s80-demo-signed.edn
```

Output:

- `evidence/s80-demo-signed.edn.tar.gz`

### 5.2 Publish to IPFS

```bash
bb benchmark:publish-ipfs evidence/s80-demo-signed.edn.tar.gz
```

This generates an `evidence-manifest.json` workflow artifact for downstream consumption.

### 5.3 Share summary for reviewers

```bash
bb benchmark:share-summary evidence/s80-demo-signed.edn
```

Include in your share packet:

1. commit SHA
2. scenario file path + scenario_id
3. evidence hash
4. attestation file (if used)
5. comparison markdown/json (if used)

---

## 6) How to generate a visual narrative linked to evidence

### 6.1 Start Clerk notebook server

```bash
bb notebook
```

Then open:

- `http://localhost:7777/notebooks/workbench_v2`

### 6.2 Generate a scenario snapshot card

```bash
python3 scripts/snapshot_evidence.py scenarios/S80_yield-mostly-liquid-partial-liquidity evidence/s80-mostly-liquid.png
```

Output:

- `evidence/s80-mostly-liquid.png`

This visual is linked back to deterministic scenario/evidence artifacts by scenario ID and run outputs.

---

## 7) Missing-but-important end-to-end steps (recommended)

For a complete research-grade package, include these additional controls:

1. **Reproducibility replay check + hash consistency**
   - Re-run command 1.1 on same commit and verify byte-identical or hash-identical output.
   - Suggested check:

   ```bash
   # first run
   clojure -M:run -- --invariants --scenario scenarios/S80_yield-mostly-liquid-partial-liquidity.json --output-file results/s80-mostly-liquid.result.run1.json

   # second run
   clojure -M:run -- --invariants --scenario scenarios/S80_yield-mostly-liquid-partial-liquidity.json --output-file results/s80-mostly-liquid.result.run2.json

   # compare hashes
   sha256sum results/s80-mostly-liquid.result.run1.json results/s80-mostly-liquid.result.run2.json
   ```

   Hashes should match on the same commit/environment.

2. **Comparator baseline discipline**
   - Always pair S80 with:
     - one fully liquid baseline (S68), and
     - one adverse yield case (S78).

3. **Claim boundaries**
   - Explicitly state what this run proves (scenario-bounded behavior) and does not prove (global equilibrium/safety proof).

4. **Bundle contents checklist**
   - scenario JSON
   - result JSON
   - test-summary JSON
   - signed evidence bundle (+ attestation if used)
   - visual card PNG
   - short narrative markdown

5. **Independent verification handoff**
   - Provide exact commands + expected output paths so a third party can reproduce without private context.

---

## 8) Minimal copy/paste demo script (ordered)

```bash
# 1) Run scenario
clojure -M:run -- --invariants --scenario scenarios/S80_yield-mostly-liquid-partial-liquidity.json --output-file results/s80-mostly-liquid.result.json

# 2) Validate contracts/artifact structure
./scripts/test.sh contracts

# 3) Compare to fully liquid baseline (S68)
bb trace:diff scenarios/S68_yield-aave-long-horizon-10y-monthly-accrual.json scenarios/S80_yield-mostly-liquid-partial-liquidity.json results/trace-compare/s68-vs-s80

# 3b) Optional: compare to adverse-yield scenario (S78)
bb trace:diff scenarios/S78_yield-negative-yield-release-path.json scenarios/S80_yield-mostly-liquid-partial-liquidity.json results/trace-compare/s78-vs-s80

# 4) Build + sign evidence
bb benchmark:run benchmarks/dispute-liveness.edn -o evidence/s80-demo-signed.edn -k test_key.pkcs8

# 5) Export + publish/share
bb benchmark:export evidence/s80-demo-signed.edn
bb benchmark:publish-ipfs evidence/s80-demo-signed.edn.tar.gz
bb benchmark:share-summary evidence/s80-demo-signed.edn

# 6) Visual narrative artifact
bb notebook
python3 scripts/snapshot_evidence.py scenarios/S80_yield-mostly-liquid-partial-liquidity evidence/s80-mostly-liquid.png
```
