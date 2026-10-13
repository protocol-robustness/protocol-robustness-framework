# Deployment-bound forensic reconstruction V0

This offline adapter reconstructs PRF evidence nodes and a forensic view from
the retained local pro-rata replay bundle. It reads the manifest, witness,
proof fixture, provenance, Forge broadcast/receipt records, test log, and final
state observation. It recomputes file hashes and manifest integrity, checks
the proof/public-values cross-links, checks receipt and log block context, and
reconciles the recorded allocator token transfers against the final balances.

Run it from a PRF checkout with the retained bundle available:

```bash
clojure -M:with-sew -m resolver-sim.evidence.deployment-bound \
  /path/to/prf-withdrawal-independent-replay \
  /path/to/new-forensic-output
```

The output directory must not already exist. It contains
`verification-report.json` and content-addressed EDN evidence nodes. To
independently reconstruct, use a fresh output directory and compare the report
input hashes, node-hash list, graph validation, and recomputed checks. Output
timestamps are provenance metadata; canonical evidence-node hashes are the
stable identity. The existing forensic-node schema requires SHA-256 artifact
references; the operation node therefore references a SHA-256 wrapper over the
lower-case UTF-8 hex spelling of the operation ID and retains the original
operation ID in the report. That wrapper is a graph reference, not a
replacement protocol operation ID.

This V0 checks retained bytes and cross-links. It does not independently run
the SP1 verifier or Rust witness derivation, authenticate RPC observations or
block headers, establish deployment runtime code identity, infer research
acceptance, or authorize a reward/payment. The bundle is local-only. Missing
evidence is reported as typed gaps. Conflicts cause validation failure and
remain visible in the graph/report rather than being normalized away.

The current retained replay contains a conflict: Carol's 14-unit transfer is
logged in a transaction whose calldata selects
`applyConfigurationWithEntitlementProofMode`, not the allocator `withdraw`
entry point. V0 therefore reports the amounts and final balances but does not
claim that all three payouts were executed through withdrawal calls.
