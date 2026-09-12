# Claim scope (Sew trace-equivalence attestation)

This bundle asserts PROCEDURAL conformance:

> Declared execution procedures and bundled evidence support the attested
> claim: the solidity trace is equivalent to the reference implementation
> under the declared evaluation mode, within the declared universe, with the
> declared exclusions, and under the bundled environment snapshot.

It does NOT establish:

- correctness of the underlying contract or model;
- economic safety;
- absence of undiscovered bugs;
- truth of research interpretations;
- equivalence for excluded subjects or unsupported domains.

See `docs/conformance/THREAT_MODEL.md` (Non-goals) and the claim metadata
`claim/does-not-establish` in `bundle.json`.
