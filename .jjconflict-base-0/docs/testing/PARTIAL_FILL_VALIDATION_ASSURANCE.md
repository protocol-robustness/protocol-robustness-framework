# Partial-fill validation assurance boundaries

Partial-fill profiles have separate structural/integrity and semantic
verification paths. A self-hash establishes only that the loaded profile
matches its own committed bytes; it does not make its findings true.

| Level | Establishes | Does not establish |
|---|---|---|
| Structural | Supported profile shape and required fields | Hash integrity or finding truth |
| Integrity | Canonical self-hash, complete root references and internally consistent status/check map | Source authenticity or arithmetic correctness |
| Semantic | V2 profile rederived from the supplied authoritative request/result/witness or application/world inputs | Deployment authenticity or independent implementation |
| Deployment-bound | Not provided by these package profiles | Authenticity of an external deployment or transaction |

V1 profiles remain integrity-readable for compatibility, but the V2 semantic
verifiers refuse to upgrade them. Allocation V2 recomputes the public
pro-rata result-invariant set, checks request/result hashes, exact witness
binding, non-negative allocations, policy/request bindings and complete source
commitments. Application V2 recomputes write-back checks and application
bindings; checks for which a source artifact is absent are explicitly
`:not-run`, making the profile `:blocked` rather than semantically verified.

The package verifier can establish integrity and artifact-reference binding
only. Its result therefore uses `:verification-level :integrity` and
`:semantic-status :not-run`. It must not be presented as independent semantic
verification. A claim may explicitly require pro-rata profiles. When required,
the benchmark runner reports the missing profile validation and fails the
benchmark outcome instead of silently omitting it.

## Current limits and follow-up

These profiles do not yet rederive all findings from source-root-bound
mechanism and policy artifacts. The mechanism/policy content references are
retained as explicitly unverified metadata. That source-root-bound semantic
rederivation is a separate follow-up.

The strategic partial-fill checker is useful as bounded counterexample search,
not a general game-theoretic or equilibrium proof. It currently reports a
stratified sampling strategy while some emitted epistemic metadata says
`:bounded-exhaustive`; that label is not justified and must be corrected in
the strategic-validation follow-up. Its payoff model is allocated amount only
and omits fees, timing, recovery risk, side payments and continuation values.
The present profile work does not change that strategic checker.
