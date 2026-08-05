# Out-of-process sensitivity sentinel

The sensitivity sentinel makes disclosure decisions for artifacts that must not
leak beyond an allowed set of sinks. This document describes the
**out-of-process** sentinel authority, its assurance model, the signed decision
envelope it produces, and the operational key-isolation requirements that
determine how strong the guarantee actually is.

## Why out-of-process

The in-process sentinel (`resolver-sim.sensitivity.sentinel`) classifies
artifacts and enforces a disclosure matrix, but it runs inside the same process
that handles the sensitive artifact. A compromised or defective caller can
forget the check, forge a clean artifact, or invoke it against a benign sink —
so in-process enforcement alone does not establish an *independent*
disclosure authority.

The out-of-process sentinel moves the decision authority into a separate
process the caller must consult before disclosing to any sink the committed
policy classifies as `:remote` (the public-sink set). Even a fully compromised
caller cannot fabricate a valid decision because the signing key lives only in
the authority process.

## Components

| Component | File | Role |
| --- | --- | --- |
| Generic signed-decision primitive | `src/resolver_sim/signed_external_decision.clj` | domain-separated envelope hashing, Ed25519 sign/verify, trust-role + key-status checks. Domain-neutral. |
| Contract | `src/resolver_sim/sensitivity/contract.clj` | wire request, decision-envelope, response shapes; projection hash; policy commitment. |
| Authority | `src/resolver_sim/commands/sentinel.clj` | `prf sentinel check`: reads one EDN request, recomputes findings from hash-verified content, signs the envelope. |
| Client | `src/resolver_sim/sensitivity/sentinel_client.clj` | spawns the authority via `ProcessBuilder`, verifies the returned decision, exposes `out-of-process-gate!`. |
| Bundle verification | `src/resolver_sim/evidence/attestation_bundle.clj` | `check-sensitivity-sentinel` requires the full remote chain for `:remote`-required sinks. |

## What the authority does (and does not) trust

The authority **does not** trust caller-supplied findings, levels, or decisions.
It:

1. Recomputes and verifies the artifact projection hash from the content it
   receives (`:artifact/declared-hash` must equal its own recomputation).
2. Cross-checks the committed policy hash against its own `sentinel/policy-hash`;
   a mismatch is fail-closed.
3. Runs the secret scanner over the hash-verified content to derive its own
   findings, then builds the sentinel report and classification.
4. Assembles and signs the complete decision envelope under a dedicated domain
   tag (`PRF_SENSITIVITY_SENTINEL_DECISION_V1`).

Caller-supplied values are commitments to cross-check, never trusted inputs.

## Signed decision envelope

The signature binds the complete envelope, not just the report hash:

```
:artifact/kind :sensitivity-sentinel-decision
:artifact/version 1
:sentinel/request-hash ...
:sentinel/artifact-hash ...
:sentinel/sink ...
:sentinel/policy-id ...
:sentinel/policy-hash ...
:sentinel/report-hash ...
:sentinel/decision :allow | :block
:sentinel/level ... :sentinel/structural-level ...
:sentinel/reasons [...]
:sentinel/override-required? ...
:sentinel/authority-key-id ...
:sentinel/authority-assurance ...
:sentinel/issued-at ...
```

Verification recomputes the envelope hash from the embedded preimage (minus the
signature) and requires a trusted key with the `:sensitivity-sentinel` role and
`:active` status. A signature valid under a different domain tag never verifies.

## Assurance levels

Process separation alone does not mean the caller cannot use the signing key.
The authority reports an explicit `:sentinel/authority-assurance` value:

- `:process-isolated` — the decision runs in a separate process, but under the
  same OS principal as the caller. This gives fresh recomputation, failure
  isolation, and auditable signed decisions, **but** a caller that can read the
  private key file could still sign directly. This is the honest default.
- `:principal-isolated` — the signing key is inaccessible to the caller's OS
  principal (separate OS user with restricted key permissions, or a container /
  sandbox with a distinct identity).
- `:hardware-backed` — the key lives in an HSM or OS key store.

Do not describe a `:process-isolated` deployment as an "independent authority"
that the caller cannot influence. That claim requires `:principal-isolated` or
higher.

## Key isolation (operations)

- Use a **dedicated** sentinel key pair with `:key/role :sensitivity-sentinel`.
  Do not reuse or derive it from release-attestation keys — the roles and
  compromise domains differ.
- The signer (authority) receives private-key configuration. Verifiers (the
  client and bundle verifier) receive **only** a public trust policy.
- Keep the key path out of argv. The authority reads it from
  `PRF_SENTINEL_KEY`; the subprocess client sets it in the process environment
  rather than on the command line.
- Restrict key-file permissions so the artifact-handling process cannot read it
  (`chmod 400`, separate OS user) before claiming `:principal-isolated`.

### Offline key generation

Generate the Ed25519 keypair offline and protect the private key. The private
key is written as a PKCS#8 PEM file consumed via `buddy.core.keys/private-key`
(what `prf sentinel check` loads). The public key is published in the sentinel
trust policy (`:key/id`, `:key/public`, `:key/role :sensitivity-sentinel`,
`:key/status :active`) used by verifiers.

Example (offline):

```clojure
(let [kg (java.security.KeyPairGenerator/getInstance "Ed25519")
      kp (.generateKeyPair kg)]
  ;; write kp.getPrivate() as PKCS#8 PEM for the authority
  ;; publish kp.getPublic() raw 32 bytes hex in the trust policy
  kp)
```

## Invocation

The client invokes the authority with an explicit argv vector (never a shell
string):

```
["/path/to/java" "-jar" "/path/to/prf.jar" "sentinel" "check"]
```

Precedence for resolving the command: injected runner (tests) → explicit
config → `PRF_SENTINEL_JAR` (+ optional `PRF_JAVA`) → self-jar discovery only
when the classpath is exactly one unambiguous prf jar → else fail closed with
`:sentinel-command-unavailable`.

## Verification guarantees

The bundle verifier (`check-sensitivity-sentinel`) requires, for a
`:remote`-required sink, that the embedded decision is out-of-process and
signed by a trusted sentinel-role key, and that the request, artifact, sink and
policy commitments match. A local-only or in-process decision **never** satisfies
a `:remote`-required sink, and an override-pending decision is never treated as
approval. Any failure is fail-closed.
