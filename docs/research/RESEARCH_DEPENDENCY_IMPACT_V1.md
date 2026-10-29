# Research dependency impact report v1

`research-dependency-impact-report.v1` compares two dependency states already
reconstructed from a manifest, dependency policy, and resolution evidence. It
does not acquire dependencies, verify external observations, or establish that
the observed state is globally current.

Call `resolver-sim.research.api/compare-research-dependency-state` with:

```clojure
{:baseline {:manifest baseline-manifest
            :policy baseline-policy
            :resolved baseline-resolved-set}
 :observed {:manifest observed-manifest
            :policy observed-policy
            :resolved observed-resolved-set}
 :trusted-baseline-root expected-baseline-resolved-set-root}
```

Both resolved sets are reconstructed from their inputs before comparison. The
baseline root must come from an external trust context supplied by the caller.
An absent or mismatching anchor leaves impact `:unknown-unanchored-baseline`.

| Report status | Meaning |
| --- | --- |
| `:requalification-required` | A rooted dependency, manifest, or policy changed from the anchored baseline. The old assessment remains historical evidence; a new assessment is needed for current use. |
| `:no-observed-drift-freshness-unverified` | The supplied roots match, but this comparison cannot show that the observation is fresh or latest. |
| `:unknown-unanchored-baseline` | No trusted baseline root matched, so the report makes no drift conclusion. |

The report lists changed dependency identities and safe root projections. It
hashes dependency metadata rather than copying it into the report. Historical
assessment roots are preserved by reference. The report always says latestness
is not established and operational authority is false. It does not classify a
change as safe or unsafe, prove that a repair succeeded, or block an operation.
Retain both input states alongside the report: validating the report root alone
does not reconstruct the source manifests or acquisition evidence.

This is a researcher dependency comparison primitive. Protocol-specific
deployment, signer, chain-finality, and enforcement checks remain with their
existing domain verifiers.
