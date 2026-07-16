# Researcher Investigation Guide: Kleros Escalation (S19)

This guide describes how to investigate Kleros-level escalation behavior for `S19_dr3-kleros-escalation-rejected-l0-resolves`.

## 1. Produce an evidence bundle

Build the Sew distribution, then use a fresh exact run root:

```bash
bb build:sew
java -jar target/prf-runner-sew-0.1.0-uber.jar run-scenario \
  scenarios/edn/S19_dr3-kleros-escalation-rejected-l0-resolves.edn \
  --run-root /tmp/prf-s19 \
  --report-format audit
```

For repository development, the equivalent adapter is:

```bash
bb run:scenario scenarios/edn/S19_dr3-kleros-escalation-rejected-l0-resolves.edn \
  --run-root /tmp/prf-s19 \
  --report-format audit
```

Do not reuse a completed or incomplete root.

## 2. Confirm completion and integrity

1. Confirm `/tmp/prf-s19/completion.json` exists. It is the only positive completion marker.
2. Inspect `/tmp/prf-s19/manifest/sensitivity-report.json` before sharing the bundle.
3. Validate the immutable artifact registry:

   ```bash
   bb validation:artifact-registry /tmp/prf-s19/manifest/artifacts.json
   ```

4. Use only the root-relative paths registered in `manifest/artifacts.json`; do not select artifacts using modification time or a `latest` directory.

## 3. Locate relevant evidence

The registry identifies the primary scenario artifacts:

- `execution.replay-output` — full replay output under `scenarios/<slug>/execution/`.
- `summaries.trace` — normalized event sequence.
- `summaries.mechanisms` — escrow/dispute/appeal mechanism summary.
- `state.world-final` — final world projection.
- `summaries.claimable` — terminal claimable classification.
- `forensic.*` — scenario-scoped evidence, claims, attestations, and chain records when present.

## 4. Interpret the escalation behavior

The trace is structured JSON. Focus on:

- `action` — operation attempted, including `escalate_dispute` and `execute_resolution`.
- `result` / `outcome` — whether the transition succeeded or was rejected.
- `error` / `reject-class` — protocol-level reason for a rejected escalation.
- `transition/id` and trace metadata — semantic interpretation of the attempted state transition.

For a human-oriented sequence, read `summaries/trace-plain.md`. For the detailed evidence chain, follow the forensic entries from the artifact registry.

## 5. Public and internal evidence

The default `public` sensitivity profile scans retained artifacts before completion and redacts known secret-bearing fields from the public final-world projection. Use `--sensitivity-profile internal` only when full-fidelity retention is required and the bundle will be handled under the corresponding internal-retention policy.
