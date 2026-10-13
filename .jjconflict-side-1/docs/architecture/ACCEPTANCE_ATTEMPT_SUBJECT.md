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

## P1A receipt assurance boundary

P1A receipts authenticate the retained pre-commit candidate against its
committed identity, validate the committed transaction replay, bind the frozen
receipt schema and signing authority, and bind the resulting transaction
ordering. V2 additionally binds the committed attempt subject.

P1A does not claim independent historical reconstruction of application
functions, historical verifier execution, or exact historical executable replay.
Those claims require a future runtime implementation-attestation mechanism that
binds committed verifier material to an immutable callable and prohibits
current-classpath fallback. Until then, no receipt V3 replay evidence is issued.

The candidate/pre-commit authority and post-commit issued-receipt authority are
distinct roles. Current P1A configuration supplies the same configured public
key for both; that is configuration coupling, not semantic equivalence.

## Receipt proposition and issuance ownership

The durable issuance lifecycle and the meaning of a receipt are separate
concerns:

```text
generic issuance mechanism != receipt proposition
```

### Generic PRF issuance machinery

Generic issuance machinery owns only mechanics:

- obligation lifecycle mechanics;
- pending discovery;
- scheduling, batching, and retry;
- conditional pending-to-issued completion;
- equivalent-completion idempotency;
- conflicting-completion rejection;
- durable recovery mechanics; and
- generic canonical-byte and signature primitives.

It does not own receipt proposition semantics, protocol subject meaning,
application validation, the signed receipt projection, receipt schema/version
semantics, authority-selection policy, or application evidence interpretation.

Generic issuance namespaces must not import protocol or application semantic
namespaces.

### Protocol/application receipt proposition

A protocol/application receipt proposition owns what is attested, its committed
subject binding, required semantic validation, unsigned artifact construction,
receipt schema and domain, signed projection, authority basis, and required
evidence/results.

The current `resubmission-receipt-obligation.v1` remains a resubmission
proposition binding. It must not be replaced with a generic obligation artifact
or an opaque proposition-root wrapper before a concrete second proposition
requires that abstraction.

### Future dependency direction

When generic issuance is justified, the intended dependency direction is:

```text
generic worker / daemon
    -> generic obligation-store interface
    -> generic proposition-evaluator interface

protocol-specific evaluator
    -> protocol-specific committed-subject stores
    -> protocol-specific validation and receipt construction
```

The generic layer must never directly require resubmission committed
transactions, held-custody state, force-authorisation, or application semantics.
Protocol-specific subject resolution, such as resolving a committed transaction,
stays outside the generic obligation-store seam.

The likely future generic persistence subset is only:

```text
resolve obligation
list pending obligations
conditionally complete obligation
```

Signing remains split at the same boundary. Generic code may provide canonical
byte sign/verify primitives. A proposition owns its canonical signed projection,
domain/version, signature location, receipt identity, authority selection, and
subject/evidence binding.

### Future extraction criterion

Do not extract a generic issuance namespace, interface, or artifact solely
because the current resubmission lifecycle has a reusable shape. Extraction is
justified only when a second real proposition needs materially the same durable
obligation, pending discovery, conditional completion, idempotent retry,
conflict rejection, and daemon-processing lifecycle. Compare both concrete
consumers before fixing the interface.

If that trigger is met, extract in this order:

1. daemon scheduling mechanics;
2. narrow obligation-store interface;
3. worker lifecycle/orchestration; and
4. only if genuinely shared, a generic obligation artifact.

Do not begin with a universal attestation schema.

The future generic result taxonomy is `:success`, `:retryable`,
`:semantic-invalid`, and `:conflict`. Detailed reasons remain proposition
namespaced: for example, `:candidate-id-recomputation-mismatch` remains a
resubmission semantic reason.

### Profile and future held composition

Canonical hashing, references, and cryptographic primitives are strict PRF
framework material. The issuance lifecycle shape is framework-adjacent reusable
infrastructure. Resubmission receipt/obligation/worker code remains resubmission
protocol code, and the PostgreSQL chain store remains resubmission persistence.
Do not add receipt daemon/store/worker namespaces to `:prf/framework-v1` before
a second real consumer justifies extraction.

Future held-custody composition remains deliberately indirect:

```text
held application checker
    -> held-specific verification evidence
    -> held receipt proposition
    -> generic issuance machinery
```

The generic daemon must not understand `:forbidden`,
`:forbidden-authorized`, or force-authorisation. The held checker must not
depend on resubmission receipt semantics.
