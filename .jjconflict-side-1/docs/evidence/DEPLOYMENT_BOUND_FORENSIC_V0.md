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
  /path/to/new-forensic-output \
  --source-bundle etc/conformance/vectors/source-bound-entitlement-v1.json
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

The optional source bundle must match its retained SHA-256 reference and the
controlled vector-A economic inputs, beneficiary mappings and openings. Its
path is not an identity input. Without it, source availability remains a gap.
The source is a controlled example, not a production claims authority.

The adapter calls the reviewed Clojure successor derivation over the raw
witness and compares all 288 public-value bytes. The report retains independently
derived statement, binding, canonical entitlement, claim and table roots.
Caller-supplied expected roots or verification labels cannot replace this check.

The accounting validator decodes the pinned ERC-20/successor event ABIs and
orders events by block, transaction index and log index. It checks receipt
context, funding transfer/reserve/controller agreement, activation ordering,
each withdrawal against the derived canonical member, unique indices,
cumulative amount/count, and the final closure event. Required proof mode must
report a valid proof observation; Requested mode can report an invalid optional
proof. Malformed, duplicated, conflicting or substituted records fail.

The report and existing canonical evidence nodes carry event-derived accounting.
Gap subjects use the existing evidence-record hash intent over the gap content
and source reference so two missing observations cannot collapse into the same
node. Independent reconstruction reproduces those diagnostic subjects.

For the genuine retained bundle, recorded net flows are reserve +10, allocator
0, and beneficiaries +35/+21/+14; events report claims 35/1 → 56/2 → 70/3 and
closure. Those are arithmetic over retained logs. They do not assume authenticated
opening balances, complete chain-wide transfer coverage, or prove final storage.
Historical balance/storage observations remain explicit gaps even when event
checks pass. Script-enforced flags such as `liability_zero` grant no authority.

This V0 does not independently run the cryptographic SP1 verifier, authenticate
RPC observations or block headers, establish deployment runtime code identity, infer research
acceptance, or authorize a reward/payment. The bundle is local-only. Missing
evidence is reported as typed gaps. Conflicts cause validation failure and
remain visible in the graph/report rather than being normalized away.

The current retained replay contains a conflict: Carol's 14-unit transfer is
logged in a transaction whose calldata selects
`applyConfigurationWithEntitlementProofMode`, not the allocator `withdraw`
entry point. V0 therefore reports the amounts and final balances but does not
claim that all three payouts were executed through the recorded outer withdrawal
calls. Funding also has a receipt associated with `bindExecutionCaller` metadata.
Do not repair these associations by reordering hashes or selecting a more
favourable archive. Historical transaction bodies/traces are required to resolve
the discrepancy. The current local devnet is a different chain instance and
cannot replace the unavailable historical observations.
