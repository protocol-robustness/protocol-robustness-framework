# Specification-Ambiguity Log

Every point where a verifier implementer could not determine behaviour directly
from the normative specification is recorded here.  Each entry states the
implementer's question, the choice they made, the existing verifier behaviour,
and the resolution.  This log is arguably more valuable than a clean first-pass
implementation: it tests whether the protocol is independently implementable.

## Schema

```edn
{:ambiguity/id ...
 :spec/section ...
 :question ...
 :implementer-choice ...
 :existing-verifier-behaviour ...
 :resolution :normative-clarification | :additional-vector | :additional-corpus-case
             | :implementation-defined | :new-version
 :change-class :compatible | :new-minor-version | :new-envelope-version
               | :new-profile-version | :breaking-core-version
 :status :open | :resolved}
```

Resolutions MUST NOT silently alter normative behaviour under an existing
release root.  A clarification that changes roots or verdicts requires a new
version per `VERSIONING.md`.

## Seeded entries (found during externalisation of the reference implementations)

- **CR-001** — Spec §13 "Verifier behaviour" said status/claimability but not
  that a JSON round-trip preserves the verdict.  Both reference verifiers
  initially reported `pass` with `claimable? false` on a serialized bundle
  because `reconciliation/status` remained a string.
  Resolution: normative clarification (bundles are verified in their portable
  serialized form; string envelope fields normalize without altering evidence).
  Change class: compatible (no root/verdict change after fix).
  Status: resolved.

- **CR-002** — Spec §6 "Environment commitments" did not state which
  environment root a bundle's plan/reconciliation/coverage envelopes must bind.
  The reference fixture generator bound the `current-environment-root` while the
  bundle's environment envelope was built from different committed fields, so
  the two roots disagreed while verification still passed.
  Resolution: normative clarification (the bundle's environment envelope and the
  roots bound by plan/reconciliation/coverage MUST agree; the envelope is the
  committed snapshot).  Change class: new-minor-version (no root/verdict change
  once fixtures were corrected).
  Status: resolved.

- **CR-003** — Spec §10 "Claim parity core" listed five fields but not whether
  informational metadata (`claim/scope`, `claim/does-not-establish`) may enter
  the hashed core.  Reference implementations disagreed on set/vector
  round-tripping of the scope set.
  Resolution: normative clarification (informational metadata MUST NOT enter
  the parity core; it is a fixed property of every claim).  Change class:
  compatible.
  Status: resolved.

- **CR-004** — Spec §3 "Canonical JSON rules" does not define duplicate-key
  handling or rejection, and does not define a maximum nesting depth or bundle
  size.
  Resolution: implementation-defined for now; a resource-limits section is
  being adopted and duplicate keys are a holdout case.  Change class:
  new-minor-version (pending adoption).
  Status: open.

- **CR-005** — Spec §12 "Cryptographic admission" defines key status at signing
  time but not whether a later revocation rewrites historical results.
  Resolution: normative clarification (bare `:revoked` is explicit retrospective;
  a `:key/status-effective-at` makes revocation prospective from that instant).
  Change class: compatible (new capability).
  Status: resolved.

## Template for the independent implementer

```edn
{:ambiguity/id :CR-xxx
 :spec/section ...
 :question ...
 :implementer-choice ...
 :existing-verifier-behaviour ...
 :resolution :open
 :change-class :open
 :status :open}
```

Submit every open item with the gate results.  The review process resolves each
item via the approved resolution mechanisms (see `GOVERNANCE.md`).
