# Claim qualification V0

`resolver-sim.benchmark.claim-qualification` composes existing PRF verification
results for one exact claim subject, cutpoint, and source-root set. The cutpoint
must name a source root already committed by the claim. It is a deterministic,
read-only report; it does not add a canonical evidence graph, admission record,
trust anchor, research acceptance, reward authorization, or payment decision.

## Reused implementation

- Result shape and status semantics come from
  `resolver-sim.benchmark.verification-result`.
- Allocation integrity and semantic rederivation use the existing
  partial-fill allocation evidence validators.
- The PRO-28 pilot loads the retained deployment manifest, witness, proof
  fixture, capture metadata, chain observation, source bundle, and broadcast
  records, then invokes `resolver-sim.evidence.deployment-bound/validate-records`.
- Chain-instance binding reuses the existing
  `prf.local-chain-fingerprint.v1` formula and binds the captured and observed
  chain ID, genesis hash, and fingerprint. This is local lineage/provider
  observation, not consensus inclusion or finality.

The profile selects required checks, dimensions, validator IDs/revisions,
source roots, and required verification levels. The caller supplies the
independently sourced trusted validator revision map. Each validator runner
must execute its check and return the resulting versioned response, observed
subject, and source roots. The composer rejects subject, root, validator,
revision, or level mismatches. Producer labels are retained as assertions and
do not determine the result. Conflicting observations remain visible and fail
the affected checks.

The qualification profile root uses the existing canonical domain-hash
implementation. It commits to policy; it is not a replacement operation,
deployment, artifact, or evidence identity. Re-run qualification from retained
input bodies and the same profile, validator revisions, and trust context to
reconstruct the report.

## No-refactor option

When a consumer needs only one existing validator result, it can consume that
result directly; the qualification composer is unnecessary. Use this layer
only when a caller needs to state which exact proposition follows from several
independent checks or needs to expose stronger claims as blocked while a
narrower claim passes. Existing evidence nodes, forensic graph construction,
validators, and admission boundaries remain unchanged.

## PRO-28 pilot limits

The retained local replay supports the narrow payout-to-entitlement
reconciliation and local chain-instance binding. The report leaves Groth16
cryptographic verification, authenticated chain inclusion, and finality
blocked. It preserves other call-attribution and terminal-state gaps reported
by the full receipt validator. Local Anvil evidence does not qualify public
network execution. The report is not a persisted authority or evidence record.
