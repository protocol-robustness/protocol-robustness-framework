# Research Analysis Claim V2

`research-analytical-claim.v2` separates the subject of research from the
exact model used to analyze it:

- `:research-claim/subject-root` is derived from
  `:incentive-model/subject-root`.
- `:research-claim/model-root` is derived from `:incentive-model/root`.

Both roots participate in claim identity. A claim may therefore be grouped by
subject across models, or by the exact model artifact, without conflating the
two axes. Claim derivation accepts the model artifact and derives both fields;
callers do not supply them independently.

V1 claims are rejected at V2 validation boundaries. Their `subject-root`
semantics referred to the model root, so interpreting them as V2 claims would
silently corrupt subject-level comparison.

Research-subject identity is independent of researcher provenance. Anonymous,
pseudonymous, and authenticated-principal researchers may all produce valid
claims where the applicable research policy permits.

## Result Semantics V2

Results use `research-analysis-result.v2` and carry a
`:result/descriptor-root`. The descriptor is a separate canonical
`research-result-descriptor.v1` artifact containing the measure identifier,
value domain, unit, direction, comparison semantics, and optional witness
schema root. Producers must build and validate the descriptor before creating
an analysis output; callers cannot nominate an unrelated descriptor root.

Counterexample results require both a witness root and a descriptor that
declares the witness schema. The generic framework commits to recoverability
and descriptor binding; domain-specific verifiers remain responsible for
interpreting and validating the witness contents.

## Status

**RESEARCH ANALYSIS — COMPLETE**

- Production correctness work: **CLOSED**
- Focused assurance validation: **PASS**
- Method-specific proof verification: **INTENTIONALLY DEFERRED**
- Notebook-wide validation: **OPERATIONALLY INCOMPLETE**
- Broad backstop: **OPERATIONALLY INCOMPLETE / unrelated timeout**

The assurance boundary is deliberate:

```text
research-analysis-verification.v1
    verifies what the implemented method can establish

research-analytical-claim.v2
    binds that verified result
```

Neither artifact implies a stronger theorem or general-proof class unless a
method-specific verifier establishes it under an explicit contract. The
deferred verifier is not a convenience gap and must not be filled by weakening
this boundary.
