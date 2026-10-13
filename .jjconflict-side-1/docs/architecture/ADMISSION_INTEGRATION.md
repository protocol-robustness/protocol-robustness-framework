# Admission Integration

PRF supplies an admission boundary. The adopter supplies the policy and the
authoritative consequences of admission or rejection.

An integration should define:

- the exact candidate and predecessor projections;
- evidence closure and verifier requirements;
- authority, trust, quorum, and policy roots;
- semantic, economic, risk, and freshness conditions;
- the atomic commit and finalization boundary;
- typed rejection reasons and non-mutation behavior; and
- retry, reservation, compensation, cancellation, or escalation after rejection.

## Integration contract

Record the following in the protocol specification or its rooted admission
policy before treating the boundary as authoritative.

| Concern | Required definition |
| --- | --- |
| Candidate | Canonical candidate projection, artifact version, and exact predecessor/state binding |
| Evidence | Required evidence kinds, closure rules, verifier versions, and handling of unavailable evidence |
| Authority | Trust roots, eligible roles/signers, quorum, capability requirements, and revocation/freshness rules |
| Semantic policy | Protocol invariants and outcome conditions that must hold before admission |
| Economic and risk policy | Limits, units, valuation basis, reservations, and risk/economic predicates where applicable |
| Finalization | Atomic storage operation, expected-state/version fence, idempotency key, and authoritative publication record |
| Rejection | Typed reason codes, no-mutation guarantee, observability, and actor-visible response |
| Follow-up | Retry, replacement, reservation release, compensation, cancellation, dispute, and escalation semantics |

## Required boundary behavior

An authoritative admission path should:

1. Verify the exact candidate, predecessor, evidence, and policy roots.
2. Evaluate integrity, authenticity, authorization, semantic, and risk/economic
   conditions as separate gates.
3. Reject fail-closed if any required input, verifier, policy body, or authority
   source is unavailable, malformed, stale, revoked, or mismatched.
4. Preserve authoritative state on pre-commit rejection.
5. Atomically publish only a successful decision whose expected predecessor still
   matches the authoritative store.
6. Emit a durable decision record that binds the candidate, policy, authority,
   result, and finalization outcome.

## Minimum adversarial coverage

Test successful admission and rejection for:

- tampered candidate, evidence, or canonical root;
- missing, unauthorized, expired, or revoked authority;
- policy-root substitution and unavailable policy body;
- stale predecessor and concurrent finalization;
- semantic, economic, or risk-limit failure;
- duplicate request/idempotent replay; and
- every rejection path asserting no authoritative-state mutation.

Integrity, authenticity, authorization, and admission are separate gates. A
valid hash or signature is not itself permission to mutate authoritative state.
The `not-admitted` notebook demonstrates this generic boundary: it does not
define a canonical ledger, application lifecycle, recovery procedure, or policy.
See `docs/notebooks/SELF_EXPLANATORY_DEMOS.md`.
