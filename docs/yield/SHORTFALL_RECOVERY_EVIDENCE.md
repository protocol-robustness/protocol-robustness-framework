# Partial-fill shortfall and recovery evidence

The `shortfall-recovery-episode.v2` artifact records an ordered series of
liquidity and entitlement observations. It is intended for qualification and
simulation evidence; it does not change claim execution or allocation policy.

## Observation model

Each observation binds an operation, decision, module, token, source-state root,
time, and ordered block/transaction/log position. Its claim set records the
remaining amount for each entitlement and beneficiary. The reported shortfall
is `max(0, obligation amount - available liquidity)`.

Events between observations account for liquidity and obligation changes.
Beneficiary payments reduce both liquidity and the matching right;
liability-reduction events reduce only the right; capital-recovery events
increase observed liquidity; and new-entitlement events add a right only when
the observation advances to another decision whose allocation row commits that
beneficiary and amount. Event deltas must reconcile to the next observation.
This supports a shortfall that persists through partial recovery, claims paid
during the deficit, and later incoming entitlements. The immutable allocation
decision remains the source of each right's amount and beneficiary.

An observation that begins during an already-open shortfall is left-censored.
The artifact can report elapsed time from detection, but cannot bound the true
onset or establish that the shortfall was short-lived.

If the final observation still has a positive shortfall, the episode is
classified `:not-closed`; it has no closed-at timestamp or duration upper bound
and is not marked short-lived. A trace with a known opening and a positive final
shortfall currently does not report a duration lower bound. Use the observed
timeline and do not infer that the episode later closed.

## Source authentication

Source references and roots establish provenance fields and consistency; by
themselves they do not authenticate chain state or event inclusion. Calling
`verify-shortfall-recovery-evidence` without `:source-authenticator` therefore
reports `:not-performed` for source authentication. A caller can provide a
trusted verifier function that receives every observation and event reference
and root. It must return `:status :verified`, the exact reference and root, and
one consistent non-empty `:verifier/id` and `:verifier/revision` for the entire
episode. Each returned attestation must also have a unique non-empty
`:attestation/ref` and `:attestation/root`; the verification report retains
those bindings for downstream forensic storage. Missing, reused, mismatched,
inconsistent, or rejected attestations make the verification result fail.
Selecting and operating a chain-specific verifier remains the caller's
responsibility; no chain proof verifier is bundled here.

The verifier receives `:source/context` and `:source/expected` with each input.
Observation context includes operation, decision, module, token, time unit,
observation order and phase; chain and deployment IDs are forwarded when the
source observation provides them. Event context also binds the parent
observation and event position within its declared event list, while expected
values include event kind, amount, beneficiary and entitlement where present.
This gives a source-specific verifier enough declared context to compare with
retrieved records. It does not itself establish that those declarations match
an external source.

`capital-recovery` events and changing liquidity are separate measurements.
The former records a declared capital flow; the latter measures a source-state
balance change. Neither establishes gross recovery flows unless the source
event is authenticated. The evidence builder does not infer authentication from
non-empty source references.

## Game-model boundary

The recovery-game fixture catalog parameterizes players, claim arrival order,
submission mode, recovery timing, shortfall exposure, actions, and payoff
inputs. Prepared cases check that these inputs are complete and internally
consistent. They are not equilibrium findings. Strategic evaluation requires
explicit payoff values and outcomes, and its results describe only that
parameterized model.

## Retained PRF qualification run

The focused evidence and strategic-input suites passed 76 tests and 464
assertions with `-Srepro`. The command, source revision, dependency pins,
resolved classpath, raw log, and hashes are retained in
`evidence/partial-fill-v3-prf-recovery/`. This run qualifies the PRF evidence
model and input/payoff bookkeeping tests. It does not authenticate external
shortfall sources or establish a game-theoretic equilibrium.
