# Forensic Reference Run

This directory contains **illustrative inputs** for the forensic bundle workflow. It is not an executed run and does not constitute evidence.

- `run-request.example.edn` shows the shape of a declared forensic run request.
- `registry-snapshot.example.edn` intentionally contains placeholder values and must be replaced by a snapshot generated for the source revision being evaluated.

The default configuration lives in `examples/config/forensic/`. To use these inputs explicitly:

```bash
bb forensic:preflight \
  --run-request examples/forensic-reference-run/run-request.example.edn \
  --registry-snapshot examples/forensic-reference-run/registry-snapshot.example.edn \
  --evidence-policy examples/config/forensic/evidence-policy.edn
```

For a real run, create a separate run request and generated registry snapshot outside this example directory, then run `bb forensic:run` with explicit paths. Read `docs/forensic/BUNDLE_WORKFLOW.md` and `docs/forensic/TRUST_MODEL.md` before treating any output as externally reviewable.
