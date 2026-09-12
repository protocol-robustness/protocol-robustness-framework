# Authentication And Research Provenance

Research verification and producer authentication are separate conclusions.
A missing signature means no proof of possession was established by that
attestation. It does not invalidate canonical artifacts, evidence, or an
independent reproduction chain.

## Provenance Levels

| Level | Research verification | Signature | Identity conclusion |
| --- | --- | --- | --- |
| Anonymous | Allowed | Absent | No producer identity claim |
| Pseudonymous | Allowed | Verified under a self-declared key | Same key holder can be correlated across artifacts; no principal attribution |
| Authenticated principal | Allowed | Verified under an externally resolved key | Trusted binding to an enrolled principal |
| Governed authority | Separate authorization decision | Verified over exact governed request | Authenticated principal is authorized for subject, purpose, and authority state |

Governed authority is not a higher authentication level. It additionally
requires the committed authorization context and does not follow automatically
from principal authentication.

## Consumer Rules

Evidence and equilibrium consumers evaluate artifact integrity, exact subject
binding, and reproduction independently of producer identity. They may report
`:research-verification/status :verified` with
`:researcher/authentication-status :anonymous`.

The equilibrium `:strict-attestation` mode is an explicit proof-of-possession
policy: it accepts only `:pseudonymous` or `:authenticated-principal`
provenance. Relaxed research evaluation does not impose that policy.

Sharing has two distinct questions:

- Local or pseudonymous verification asks whether the holder of a supplied key
  signed an artifact.
- Authenticated attribution resolves a key identifier through externally
  trusted configuration. An attestation must not supply the key material used
  for that conclusion.

## Deferred Identity Work

Principal-to-credential enrollment remains optional infrastructure. When it is
introduced, credential sets should be rooted and historical so verifiers can
evaluate key status at the relevant signing or admission basis. Persistent
revocation, recovery-set closure, and transparency checkpoints are subsequent
work; none is a prerequisite for anonymous research verification.

## V1 Boundary

Authentication/provenance V1 supports anonymous research, pseudonymous proof
of possession, externally resolved principal attribution, and their strict
separation from governed authority. Evidence-chain registries preserve each
member's provenance and report only uniform or `mixed` coverage; a chain never
inherits principal attribution from a wrapper or a different member.

V1 does not provide durable credential enrollment, historical revocation,
credential recovery, transparency, or forward-secure signing.
