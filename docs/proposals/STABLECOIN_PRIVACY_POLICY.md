# Stablecoin privacy policy and sensitivity

Status: proposed design; no runtime capability or policy approval is asserted.
Implementation handoff: [self-contained Luna tasks](STABLECOIN_PRIVACY_POLICY_TASKS.md).

## Purpose

Answer: “Which privacy rules apply to this stablecoin data operation, and can
the operation proceed with this data, purpose, recipient, and disclosure sink?”

Model `privacy-policy` as an immutable policy concept. Model
`current-privacy-policy` as a resolution of that policy against an authoritative
scope and observed head. Currentness is a property of the resolution, rather
than a mutable field on the policy document.

The first implementation qualifies disclosure of stablecoin evidence and
simulated data operations. It does not claim enforcement over a live token,
issuer database, or third-party recipient. Such enforcement requires an adapter
at each actual operation boundary.

## Existing machinery to reuse

| Concern | Existing implementation | Integration rule |
|---|---|---|
| Sensitivity classification and disclosure matrix | `src/resolver_sim/sensitivity/sentinel.clj` | Preserve classification, declared floors, sink rules, and required authority |
| Signed remote disclosure decisions | `src/resolver_sim/sensitivity/sentinel_client.clj`, `contract.clj` | Reuse verification; a policy allow cannot replace a required Sentinel decision |
| Classification derivation and report provenance | `src/resolver_sim/sensitivity/report.clj`, `propagation.clj` | Bind the canonical report and exact artifact; do not invent a parallel classifier |
| Current configuration and atomic activation | `src/resolver_sim/configuration_head.clj` | Reuse head validation and transition fencing where applicable; policy owner authorization remains external |
| Current policy resolution example | `src/resolver_sim/risk/admission.clj:resolve-current` | Follow retained-body/root validation; do not add privacy fields to risk policy or silently redefine configuration V4 |
| Hashes and evidence artifacts | `resolver-sim.hash.canonical`, `resolver-sim.evidence.artifact` | Reuse canonical hashes, register new artifact kinds/intents through existing registries |
| Extension boundary | `docs/architecture/EXTENSION_CORE_API_V1.md` | Keep domain rules in an in-repository extension; any missing core facade access requires a reviewed additive API change |

Sentinel already enforces disclosure restrictions. Privacy evaluation adds
purpose, recipient, data category, retention, and scope constraints. Sensitivity
is an input to that evaluation; a low sensitivity level cannot grant permission.

## Concept boundaries

* Stablecoin: the asset context, including domain-qualified asset identity and
  issuer identity. Avoid identifying an asset by ticker alone.
* Sensitivity: classification of the exact information and its disclosure risk.
* Privacy policy: authorized rules for processing specified data in context.
* Current privacy policy: the applicable immutable policy, selected through a
  trusted scope binding and a verifiable observation of its active head.
* Evidence policy: capture and retention of research evidence. An evidence
  policy cannot require publication that privacy or sensitivity gates deny.

Add stakeholder concepts for privacy policy and current policy resolution.
Reference the existing evidence-backed classification and evidence-policy
concepts. Keep implementation status explicit: a concept entry describes the
design and does not establish deployed enforcement.

## Proposed contracts

These are proposed contracts for V0, not existing schema keys. Final artifact
envelopes and hash intents must follow repository conventions.

### Currency identity and initialization

Do not use a ticker as the currency or asset identity. Record the currency
denomination separately from the chain-qualified token asset and issuer. For a
stablecoin, the issuer's claim that the token represents a currency is distinct
from evidence of reserves, redemption, or legal status. Privacy policy scope
binds both the represented currency and the exact asset root.

The first active privacy policy requires an explicit initialization package.
It commits to the issuer principal, token asset root, represented currency,
jurisdiction and operation scope, initial policy root, authority and key
registry roots, and the bootstrap decision/evidence that approves this binding.
Initialization is accepted only relative to a configured external trust anchor;
hash integrity alone cannot bootstrap its own authority. It establishes the
initial policy lineage. Subsequent changes use the existing authorized
configuration transition path. Until the bootstrap is verified, report the
policy body as integrity-checked at most, with authority and currentness
unverified.

### Immutable policy

`stablecoin-privacy-policy.v1` commits to:

* Policy ID/version, issuer principal, domain-qualified asset, jurisdiction or
  explicit jurisdiction set, covered operations, and validity interval.
* A finite rule set covering data categories, operations, purpose, recipient
  principal/class, sink, sensitivity ceiling, retention limit, and prerequisite
  evidence types. Reject unknown values and ambiguous duplicate rules.
* Policy authority reference and optional readable notice root. A notice and a
  machine policy are separate artifacts; their relationship is explicit.

V0 uses explicit matching rules, with deny overriding allow, and default deny
when no allow matches. Multiple independently applicable policies must all
permit the operation; missing coverage or unresolved jurisdiction blocks it.
There is no implicit global/latest-policy fallback. A permitted data flow does
not establish legal compliance. Consent, where required by a rule, is a
separately verified prerequisite and cannot be fabricated from a policy field.

### Scope binding and current resolution

Use the existing configuration/authority mechanisms to establish an immutable
binding from `(issuer, asset, jurisdiction, operation)` to a policy root. Do not
create a new governance engine. Before implementing the binding, identify the
existing configuration extension seam and document its trust and activation
rules. If none is suitable, propose a versioned configuration change rather
than extending an existing canonical schema in place.

Resolution takes a trusted head observation, retained configuration/binding and
policy bodies, the operation scope, and an explicit evaluation time. It verifies
roots, authorization evidence, scope, validity, and the observation's configured
freshness requirement. Callers supply the request context, not a policy root
that can override the active binding. A valid head self-hash alone establishes
neither legitimate authority nor freshness.

Offline evaluation reports `historical-verified` relative to its supplied trust
anchor, or `currentness-unknown`; it cannot claim globally current policy. A
runtime adapter can report `current-observed` only against its documented trusted
head/freshness mechanism. Historical policy and decision artifacts remain
immutable when the head advances.

### Evaluation request and receipt

The request commits to exact artifact/data-view identity, classification report
root, issuer/asset scope, operation, purpose, recipient identity and class, sink,
retention requested, prerequisite evidence roots, and a request identifier.
Use opaque identity references; do not put raw identity documents, addresses
linked to people, account details, or transaction histories in public receipts.

The receipt commits to the request root, applicable policy roots, observed head,
authority verification evidence, classification/ruleset roots, Sentinel policy
hash and decision root where applicable, evaluation time, bounded validity,
matched rule IDs, and deterministic reasons. Report these dimensions separately:

* Policy integrity and authority verification.
* Temporal applicability/currentness.
* Sensitivity assessment and disclosure permission.
* Privacy rule decision (`allow`, `deny`, or `indeterminate`).
* Execution (`not-executed` in V0).

Only a fully verified allow is usable at an enforcement boundary. Missing data,
unknown classification, unavailable authority, or stale observation is unusable.
An offline allow describes an evaluation under its pinned policy; it does not
authorize a live operation.

## Composition and enforcement

```text
Exact artifact/view -> canonical sensitivity assessment -> Sentinel sink gate
Scope + trusted head -> applicable policies -> contextual privacy evaluation
Both decisions + required authority + current-head fence -> operation adapter
```

Privacy rules can further restrict a Sentinel-allowed movement. They cannot
lower sensitivity, bypass remote authority, or turn an override-pending Sentinel
decision into permission. Preserve existing handling of embargoes and overrides.

A public transfer record does not make a wallet-to-person mapping public. A
public recipient class also cannot declassify a private artifact. Aggregation,
redaction, and proofs produce new artifacts: assess the exact resulting view,
retain its derivation, and check linkability. A hash of low-entropy identity data
can reveal information; public receipts must not expose such commitments by
default. Reuse salted/opaque references only under an explicit threat model.

When a live adapter is added, it must recheck or atomically fence the head at the
actual disclosure/operation boundary. A policy update between evaluation and use
invalidates the old receipt for a current-policy operation. Remote disclosures
require the existing signed Sentinel decision plus a privacy decision bound to
the same context; an old Sentinel signature must not authorize a different
recipient, purpose, or view. No V0 receipt is a reusable bearer capability.

Retention permission describes a bound. Enforcement needs storage/deletion
adapters and evidence; a receipt alone does not prove deletion. Immutable public
storage is incompatible with a finite deletion requirement for the raw data.

## Bounded example and acceptance scenarios

Use synthetic data only. Pin a fictitious issuer, a domain-qualified stablecoin,
one jurisdiction, and two authorized fixture policy versions. No fixture keys
are production authority.

| Operation | Expected result |
|---|---|
| Publish independently assessed public aggregate with permitted purpose and sink | Allow under pinned policy, execution not asserted |
| Export private redemption identity record to public chain | Deny via existing Sentinel and privacy rules |
| Send permitted private view to named processor using approved private sink, within retention bound | Allow only with all required authority and prerequisites |
| Same view and sink, different recipient or purpose | Deny; earlier decision cannot be reused |
| Policy permits disclosure, Sentinel denies or needs remote authority | Block |
| Fresh active head changes between evaluation and use | Reject stale decision at adapter boundary |
| Trusted historical snapshot without live observation | Historical evaluation; currentness unknown |
| Missing/malformed policy, unknown data category, unresolved jurisdiction, mismatched root | Deny or indeterminate, unusable |
| Public output includes a private identity mapping or linkable receipt | Block or require a separately assessed safe projection |

## Delivery limits

Deliver concepts, immutable contracts, pure evaluation, offline inspection, and
deterministic qualification first. Keep the implementation in this repository.
Live issuer integration, new cryptographic privacy schemes, regulatory policy
content, actual consent collection, and deletion enforcement require separately
scoped work and real owners. No funding, settlement, or transfer authorization
follows from a privacy evaluation.
