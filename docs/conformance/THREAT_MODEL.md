# Conformance Framework — Threat Model

Scope: `:conformance/core-version 1`.  This document describes the adversaries
the framework defends against, the properties it protects, and the attacks it
explicitly does not address.  It accompanies the normative specification.

## Adversaries

| Adversary | Capability | Primary concern |
|---|---|---|
| Malicious bundle producer | Crafts a bundle claiming conformance | Claim inflation, evidence forgery, receipt reordering |
| Compromised execution implementation | Produces incorrect receipts/outcomes | A passed reconciliation that does not reflect real execution |
| Dishonest signer | Signs artifacts they were not authorised to sign | Signature over a misleading artifact |
| Unauthorised but cryptographically valid signer | Holds a valid key for a different domain/kind | Cross-domain signature laundering |
| Stale or altered registry | Modifies the implementation registry | Claiming conformance under a changed surface |
| Verifier under a different environment | Verifies against current state rather than bundled snapshot | Rewriting history |
| Consumer | Interprets a weaker claim as stronger | Class confusion (`reproduced` read as `attested`) |
| Build process | Inserts or omits receipts | Claimable bundles assembled from missing/extra evidence |

## Protected properties

The framework MUST defend the following properties:

1. **Evidence cannot be removed to strengthen a claim** (claim monotonicity).
   Removing evidence MUST NOT change the derived claim class upward.
2. **Unexpected evidence cannot improve claimability.**  Extra receipts not
   covered by the plan, profile, or admission policy MUST cause rejection.
3. **IDs cannot substitute for roots.**  Claims and identities bind content
   roots; an `id`-only binding is not claimable.
4. **Profile or environment changes invalidate dependent claims.**  The
   bundled environment snapshot binds the claim; a different profile or
   environment root yields a different (non-verifying) claim.
5. **Signatures cannot cross artifact domains.**  `signature/domain` is bound
   to the artifact kind; a valid key used outside its domain fails closed.
6. **Artifact integrity does not imply authenticity.**  A correct hash root
   does not make a package signed.
7. **Authenticity does not imply admission.**  A cryptographically valid
   signature from an authorised signer still requires the admission policy to
   admit the package.
8. **Claim labels cannot create claim semantics.**  The claim class is derived
   from evaluation mode and outcome; the label field is checked, not trusted.
9. **Historical bundles verify under their committed environment.**  Current
   registry/policy state must not silently rewrite past results.
10. **Verifier minimality.**  The generic verifier implements only the
    protocol; it cannot be coerced by domain logic into accepting evidence it
    cannot itself verify.

## Attacks explicitly addressed (with mechanism)

| Attack | Mechanism |
|---|---|
| Forged reconciliation root | Verifier recomputes canonical root from bundled receipts and compares |
| Tampered supplied claim | Derived claim parity-core comparison; json-root comparison |
| Claim class upgrade by relabelling | Class is derived, never trusted from the label |
| Signature forgery / wrong preimage | Ed25519 over exact canonical preimage; preimage mismatch fails |
| Unauthorised signer with valid key | Authorisation gate against trust policy + artifact kind |
| Revoked / expired key | Key status evaluated at signing time; revocation fails closed |
| Unknown algorithm | Closed algorithm registry; unknown algorithms fail closed |
| Registry snapshot substitution | Bundled environment binds registry root; current state never used |
| Bundle version guess | `unsupported-bundle-version` typed rejection, never partial verify |
| Identity substitution | Same `subject/id` with different canonical root rejected |
| Missing receipt silently filled | Bundles are closed; verifier never runs domain code to resolve |
| Divergence accepted without evidence | `accepted-divergence` only when a declared divergence exists |

## Non-goals

The framework does NOT defend against:

- **Correctness of the underlying research model.**  Conformance is
  procedural; a conforming process may still be scientifically wrong.
- **Correctness of authorised signer judgment.**  Admission proves the signer
  was authorised and the artifact was signed, not that the signer's
  conclusion was correct.
- **Secrecy of bundled evidence.**  Bundles are not encrypted and are expected
  to be publicly verifiable.
- **Liveness of external evidence sources.**  A bundle is closed; it does not
  depend on, and therefore does not protect against, external sources.
- **Compromise of all trusted implementations and keys.**  If every verifier
  and every key is compromised, no procedural framework can help.
- **Universal equivalence across unsupported domains.**  Profiles declare the
  domains they cover; the framework does not invent equivalence elsewhere.

## Residual risks

- Both current verifiers may share a conceptual misunderstanding of the
  specification.  Mitigation: the implementation-neutral corpus is the
  interoperability contract, and an independent third verifier is the
  recommended next independence step.
- `:claim/scope` and `:claim/does-not-establish` are informational and do not
  enter the parity root; a consumer that parses only the parity core must read
  the scope from the claim envelope, not assume it.
- Deterministic key material committed as test vectors is test-only; it MUST
  NOT be reused for production signing.
