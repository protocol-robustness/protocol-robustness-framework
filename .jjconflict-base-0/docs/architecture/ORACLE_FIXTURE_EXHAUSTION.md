# Oracle fixture exhaustion (MC-only)

`:on-exhaustion` on `:oracle-fixture` / `:fixed-or` controls **Monte Carlo**
detection rolls in `stochastic/detection.clj`. It is **not** consumed by
`contract_model/replay.clj` or `protocols/sew/resolution.clj`.

`:repeat-last` preserves stochastic trial continuity after scripted rolls are
exhausted. It does **not** model replay determinism and is not validated by
`replay-with-protocol`.

## Exhaustion modes

| Mode | Behavior | Use when |
|------|----------|----------|
| `:throw` (default) | Error once sequence is exhausted | Regression, replay-comparable evidence, benchmarks |
| `:repeat-last` | Repeat final scripted value forever | Exploratory MC only |
| `:cycle` | Wrap with `mod` after exhaustion | Long MC sweeps with explicit periodic script |

**Rule of thumb:** if someone might compare output to replay evidence, do not use
`:repeat-last`.

## Trace metadata

When a fixed roll is past the end of `:rolls`, events include:

- `:roll/exhausted? true`
- `:roll/on-exhaustion` (`:repeat-last` or `:cycle`)
- `:roll/repeated-index` or `:roll/cycled-index` as appropriate
- `:roll/index` (requested index) and `:roll/count`

Trial results may include `:oracle-fixture/exhausted?` and
`:oracle-fixture/warnings`. Batch aggregates include `:oracle-fixture-warnings`.

Set `:evidence-quality? true` on params for stricter warnings (`:error` if
`:repeat-last` actually exhausted during the run).

## MC vs replay (expected divergence)

Replay applies explicit scenario events; reversal slashing on appeal is
**deterministic** in the Sew kernel when prior level flipped and slash bps > 0.

MC `resolve-dispute` uses `reversal-slashed-live?` with `oracle-roll-event` and
`:reversal-detection-probability` — a different layer.

Example (REPL):

```clojure
(require '[resolver-sim.stochastic.detection :as det]
         '[resolver-sim.stochastic.dispute :as dispute]
         '[resolver-sim.stochastic.rng :as rng]
         '[resolver-sim.protocols.sew :as sew]
         '[resolver-sim.protocols.sew.invariant-scenarios.baseline :as bl])

;; MC with repeat-last after a one-element script
(let [p (det/prepare-oracle-params
         {:oracle-fixture {:mode :fixed-roll-sequence
                           :rolls [0.0]
                           :scope #{:detection}
                           :on-exhaustion :repeat-last}
          :reversal-detection-probability 0.5
          :reversal-slash-bps 2500})
      r (dispute/resolve-dispute (rng/make-rng 1) 10000 150 700 2.5 :malicious
                                 0.05 0.99 0.1
                                 :force-strategy :malicious
                                 :oracle-fixture (:oracle-fixture p))]
  {:slashed? (:slashed? r)
   :warnings (:oracle-fixture/warnings r)})

;; Replay ignores :oracle-fixture on :protocol-params
(= (select-keys (sew/replay-with-sew-protocol bl/s02)
                [:outcome :halt-reason :events-processed])
   (select-keys (sew/replay-with-sew-protocol
                 (update bl/s02 :protocol-params merge
                         {:oracle-fixture {:rolls [0.0] :on-exhaustion :repeat-last}}))
                [:outcome :halt-reason :events-processed]))
;; => true
```

Do not wire `oracle-roll-event` into `replay.clj`. To replay oracle choices,
materialize them into explicit scenario events first, then replay.
