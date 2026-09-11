# Benchmark Result Spec V1

## Result Shape

```clojure
{:benchmark-run/id       <qualified-kw>   ;; unique run identifier
 :benchmark/id           <qualified-kw>   ;; benchmark that was executed
 :benchmark/hash         <string>          ;; SHA-256 of benchmark definition
 :run-request/hash       <string>          ;; SHA-256 of run request (inputs + config)
 :runner/id              <qualified-kw>   ;; runner instance that executed
 :runner/attestation     <map-or-nil>      ;; optional cryptographic attestation
 :scenario-results       [<map> ...]       ;; per-scenario outcomes
 :claim-results          [<map> ...]       ;; per-claim outcomes
 :score/result           <keyword-or-num>  ;; :pass | :fail | :mixed | numeric
 :score/breakdown        <map>             ;; detailed score components
 :evidence/root-hash     <string>          ;; SHA-256 of all evidence
 :artifact/root-hash     <string>          ;; SHA-256 of all artifacts
 :consensus/basis        <keyword>         ;; :single-run | :multi-run
 :benchmark-run/hash     <string>}         ;; SHA-256 of the entire run record
```

## Status Classification

| Status     | Description                                           |
|------------|-------------------------------------------------------|
| `:pass`    | All claims pass.                                      |
| `:fail`    | One or more critical claims fail or score below threshold. |
| `:mixed`   | Non-critical failures exist but no critical failures.  |
| `<number>` | Raw score (0-1) when scoring rule produces a numeric result. |

## Claim Result

```clojure
{:claim/id        <qualified-kw>     ;; claim identifier
 :claim/outcome   <keyword>          ;; :pass | :fail | :inconclusive | :not-exercised | :not-implemented
 :claim/severity  <keyword>          ;; :critical | :high | :medium | :low
 :claim/evidence  [<ref> ...]}       ;; evidence node references
```

### Claim outcome taxonomy

| Outcome | Meaning | May satisfy an active benchmark? |
|---|---|---|
| `:pass` | The evaluator ran and its required evidence passed. | Yes |
| `:fail` | The evaluator ran and found a violation. | No |
| `:inconclusive` | The evaluator ran but evidence was insufficient to decide. | No |
| `:not-exercised` | An evaluator exists, but its required invariant or workload evidence was not produced. | No |
| `:not-implemented` | No evaluator is registered for the claim. | No |

`:deferred` is manifest metadata, not a positive claim result. Deferred claims
are allowed only in experimental or deprecated manifests and never improve a
score or readiness label.

## Scenario Result

```clojure
{:scenario/file         <path>        ;; scenario path
 :scenario/outcome      <keyword>     ;; :pass | :fail | :error
 :scenario/halt-reason  <keyword-or-nil>
 :scenario/metrics      <map>
 :scenario/invariant-results [<map> ...]}
```

## Hash Computation

All hashes use SHA-256 over canonical EDN serialization
(`resolver-sim.hash.canonical`):

| Hash Field              | Content                                              |
|------------------------|------------------------------------------------------|
| `:benchmark/hash`      | Canonical serialization of benchmark definition       |
| `:run-request/hash`    | Canonical serialization of run request (input config) |
| `:evidence/root-hash`  | Canonical serialization of the evidence bundle        |
| `:artifact/root-hash`  | SHA-256 over sorted concatenation of artifact hashes  |
| `:benchmark-run/hash`  | SHA-256 of the entire run record (excluding itself)   |

## Example

```clojure
{:benchmark-run/id     :run/20260627-abc123
 :benchmark/id         :benchmark/sew-escrow-dispute-v1
 :benchmark/hash       "e3b0c44298fc1c149afbf4c8996fb924..."
 :run-request/hash     "d7a8fbb307d7809469ca9abcb0082e4f..."
 :runner/id            :runner/sew-adapter-v1
 :runner/attestation   nil
 :scenario-results     [{:scenario/file "scenarios/sew/S01.edn"
                         :scenario/outcome :pass
                         :scenario/invariant-results [...]}]
 :claim-results        [{:claim/id :claim/funds-conserved
                         :claim/outcome :pass
                         :claim/severity :critical
                         :claim/evidence [...]}]
 :score/result         :pass
 :score/breakdown      {:passed 5 :failed 0 :critical 0 :total 5}
 :evidence/root-hash   "abc123def456..."
 :artifact/root-hash   "789abcdef012..."
 :consensus/basis      :single-run
 :benchmark-run/hash   "fedcba987654..."}
```

## Future Extensions

- Numeric leaderboards will add `:leaderboard/score` and
  `:leaderboard/percentile` fields.
- Historical trend fields (`:trend/pass-rate`, `:trend/critical-failures`)
  may be added for regression tracking across runs.
