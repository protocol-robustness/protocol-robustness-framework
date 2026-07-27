# Solidity Equivalence Verification

This directory contains the committed inputs for the cross-repository
Solidity trace-equivalence verification layer:

- `trace-solidity-manifest.edn` — Canonical cross-repository trace manifest
  binding Clojure-generated CDRS traces to Solidity fixture destinations
  with SHA-256 hashes for integrity verification.

- `solidity-invariant-profile.edn` — Portable cross-implementation invariant
  checks for CDRS v0.2 trace equivalence between the Clojure simulation and
  the Solidity contracts.

- `generate-equivalence-attestation.py` — Generates a deterministic,
  reviewer-facing trace equivalence attestation document.

These files are referenced from `docs/review/`, `docs/testing/`, `scripts/`,
and across all trace-bearing suites. They are the source of truth for
what the equivalence layer asserts.
