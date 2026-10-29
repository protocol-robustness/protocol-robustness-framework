# Research dependency impact report v1

`research-dependency-impact-report.v1` compares two dependency states already
reconstructed from a manifest, dependency policy, and resolution evidence. It
does not acquire dependencies, verify external observations, or establish that
the observed state is globally current.

Call `resolver-sim.research.api/compare-research-dependency-state` with:

```clojure
{:baseline {:assessment-root prior-assessment-root
            :assessment-dependency-set-root prior-assessment-dependency-root
            :manifest baseline-manifest
            :policy baseline-policy
            :resolved baseline-resolved-set}
 :observed {:manifest observed-manifest
            :policy observed-policy
            :resolved observed-resolved-set}
 :trusted-baseline-root expected-baseline-resolved-set-root}
```

Both resolved sets are reconstructed from their inputs before comparison. The
baseline assessment root identifies the historical finding being compared;
its recorded dependency-set root, taken from an independently verified
assessment, must equal the reconstructed baseline set.
The resolved-set baseline root must come from an external trust context
supplied by the caller. An absent or mismatching anchor leaves impact
`:unknown-unanchored-baseline`.

| Report status | Meaning |
| --- | --- |
| `:requalification-required` | The supplied dependency states differ. Requalify the historical assessment before applying it to the observed state. This compares content; it does not assert when the difference occurred. |
| `:matching-roots-freshness-unverified` | The supplied roots match, but this comparison cannot show that the observation is fresh or latest. |
| `:unknown-unanchored-baseline` | The states match but no trusted baseline root matched, so the comparison cannot anchor its historical point. |

The report lists changed dependency identities and safe root projections, with
root-level reasons for manifest, policy, or resolved-set differences. It hashes
dependency metadata rather than copying it into the report. The historical
assessment root is preserved by reference. The report always says latestness
is not established and operational authority is false. It does not classify a
change as safe or unsafe, prove that a repair succeeded, or block an operation.
Retain both input states alongside the report: validating the report root alone
does not reconstruct the source manifests or acquisition evidence.

This is a researcher dependency comparison primitive. Protocol-specific
deployment, signer, chain-finality, and enforcement checks remain with their
existing domain verifiers.

For researcher dependencies, a changed source, model, dataset, verifier, or
runtime artifact root triggers requalification. A signer revocation or policy
rotation is visible here only if it changes a dependency or policy root; this
report does not determine signer eligibility. Matching roots cannot detect a
stale source observation, delayed chain head, or conflicting observers. Those
cases remain freshness-unknown and need a temporal/observation verifier.

## Conflicting observers

When more than one observer reports a state, use
`resolver-sim.research.api/compare-research-dependency-observations`. Each
observation must identify its observer and the exact reference root it reports
about, plus the manifest, policy, and resolved set. These roots are supplied
claims; this report does not authenticate the observers or verify that their
reference roots are canonical heads. The report retains each observer's impact
report. If observers report different dependency roots for
the same reference, status is `:conflicting`. If they report different
reference roots, status is `:not-comparable`; the API does not assume those
references are ordered.

There is no first-wins, latest-timestamp, or majority rule. Exact retries from
the same observer for the same reference and content collapse to one rooted
observation. `:observers-agree` means only that supplied roots match at one
reference; observer authentication, independence, and latestness remain
unestablished. Existing observation and publication code follows the same
boundary by preserving conflicting cell results or signed branches rather than
overwriting them.
