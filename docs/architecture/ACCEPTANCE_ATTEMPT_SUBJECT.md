# Acceptance attempt subject (P0-B.1)

P0-B.1 defines the canonical identity used when an authoritative historical
acceptance evaluation is acted upon. It is deliberately separate from
admission coordination, committed transaction identity, and the signed receipt
that may attest those facts.

## Semantic layers

```text
use-case definition
  -> use-case application (:prf/use-case-application.v1)
  -> historical acceptance evaluation
  -> acceptance-attempt-subject.v1
  -> admission/issuance
  -> signed submission-attempt-receipt.v2
  -> committed transaction
```

- **Acceptance evaluation** is the deterministic historical decision. Its V2
  basis commits the evaluated submitted-bundle root and the existing
  `:application/root` of the use-case application.
- **Acceptance attempt subject** is the canonical identity of that evaluation,
  submitted content, and application target.
- **Admission reservation/fence** is current coordination state. Reservation
  IDs, lease IDs, fence tokens, retry counters, worker identity, runtime
  options, and timestamps are not attempt identity.
- **Committed transaction identity** identifies a durable state transition and
  is not interchangeable with either the evaluation or attempt subject.
- **Receipt** is a signed attestation which may bind the attempt subject and
  separately bind durable admission/transaction facts.

## Canonical subject

The closed artifact is `acceptance-attempt-subject.v1`:

```clojure
{:artifact/schema "acceptance-attempt-subject.v1"
 :attempt/evaluation-root <sha256-ref>
 :attempt/submitted-bundle-root <sha256-ref>
 :attempt/target
 {:attempt-target/type :use-case-application
  :attempt-target/root <application/root>}
 :attempt/subject-root <sha256-ref>}
```

The subject root is the domain-separated SHA-256 root of the canonical
projection containing the schema, evaluation root, submitted-bundle root, and
closed target. The `:application/root` is reused verbatim; it is not wrapped in
another application hash. The subject root is derived internally and is never
a trusted caller input.

The target is authoritative only when it agrees with the application root
committed by the V2 evaluation basis. An issuance request may assert the target
for the current operation, but it cannot change the target of the historical
evaluation.

## Receipt version boundary

`submission-attempt-receipt.v1` remains the legacy contract and does not acquire
an attempt-subject requirement. `submission-attempt-receipt.v2` is the
application-aware contract and requires
`:attempt-receipt/attempt-subject-root`. V2 verification must reconstruct the
subject from retained historical inputs and compare the reconstructed root; a
syntactically valid supplied root is insufficient.

Consequently, a V1 receipt is never silently upgraded or interpreted as V2,
and no V2 identity is synthesized for a legacy receipt.

## Retry invariant

For unchanged historical evaluation, submitted bundle, and application target:

```text
reservation/retry/fence changes -> same attempt-subject-root
```

Changing any of the three semantic inputs changes the subject root. In
particular, an evaluation for bundle or application A cannot be issued for B.

## Ownership boundary for wiring

The issuer owns reconstruction and cross-binding of the subject before signing.
The verifier owns schema dispatch and reconstruction for V2. Admission code may
bind current fences and transaction ordering separately, but must not source
attempt identity from those coordination fields.

PRF artifacts expose explicit content-addressed references. General transitive
reachability and graph queries remain an external capability (for example,
Jirl); P0-B.1 does not introduce a traversal subsystem.
