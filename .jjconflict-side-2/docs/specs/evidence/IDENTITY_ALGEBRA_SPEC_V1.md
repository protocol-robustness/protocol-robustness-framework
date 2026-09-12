Identity Algebra Specification V1
1. Purpose

This specification defines the formal semantics of identity generation, comparison, and composition within the system.

Its goals are:

Eliminate ambiguity around hash meaning.
Prevent cross-domain identity confusion.
Provide a foundation for auditability and replay correctness.
Govern future intent additions.
2. Core Principle

A hash value alone has no meaning.

Identity is defined by the pair:

(Intent, Hash)

Formally:

Identity := (Intent, Digest)

Where:

Intent defines semantic meaning.
Digest is the output of Canonical Hash V1.

Two identical digests with different intents represent different identities.

3. Identity Function

The canonical identity function is:

I(intent, value)
  = SHA256(
      DOMAIN_TAG(intent)
      ||
      CanonicalEncode(
        Projection(intent, value)
      )
    )

Components:

Component	Purpose
Intent	semantic contract
Projection	domain-specific normalization
CanonicalEncode	ABI serialization
Domain Tag	collision isolation
SHA256	digest generation
4. Identity Domains

An Identity Domain is a namespace of meaning.

Formally:

D = (Intent, DomainTag, ProjectionRule)

Current domains:

Intent	Domain Tag
:world-structure	WORLD_STATE_V1
:evm-projection	EVM_PROJECTION_V1
:evidence-record	EVIDENCE_RECORD_V1
:evidence-content	EVIDENCE_CONTENT_V1
:evidence-chain	EVIDENCE_CHAIN_V1
:manifest	MANIFEST_V1
:bundle-root	BUNDLE_ROOT_V1
:registry	REGISTRY_V1
:provenance	PROVENANCE_V1
:claim-definition	CLAIM_DEFINITION
:attestor	ATTESTOR
5. Identity Equivalence
5.1 Strong Equivalence

Two identities are strongly equivalent iff:

IntentA = IntentB
AND
DigestA = DigestB

Formally:

(A ≡ B)
5.2 Cross-Domain Non-Equivalence

Even if digests match:

DigestA = DigestB

If:

IntentA ≠ IntentB

Then:

A ≠ B

Mandatory rule:

Identity equivalence never crosses intent boundaries.

6. Projection Semantics

Projection transforms runtime structures into identity-relevant structures.

Formally:

Projection(intent, x) → x'

Projection is part of identity definition.

Changing projection rules changes identity semantics.

6.1 Projection Idempotency

Required:

Projection(
  Projection(x)
)
=
Projection(x)

This property guarantees stable identity generation.

7. Identity Lattice

Identity domains form a lattice.

Current structure:

world
├─ world-structure
└─ evm-projection

evidence
├─ evidence-record
├─ evidence-content
└─ evidence-chain

distribution
├─ manifest
├─ bundle-root
└─ registry

lineage
└─ provenance
8. Domain Isolation

Each domain MUST have:

Unique Domain Tag

Rule:

DomainTag(A) ≠ DomainTag(B)

for all:

IntentA ≠ IntentB

This prevents:

accidental collisions
semantic aliasing
cross-domain replay ambiguity
9. Identity Operations
9.1 Generate
Generate(Intent, Value)
→ Identity

Maps to:

(hash-with-intent {:hash/intent ...} value)
9.2 Compare

Allowed:

Compare(
  IdentityA,
  IdentityB
)

Only if:

IntentA = IntentB
9.3 Compose

Higher-level structures may contain identities.

Example:

EvidenceChain
=
Hash(
  EvidenceRecord1
  ||
  EvidenceRecord2
  ||
  ...
)

Composition always preserves intent boundaries.

10. Forbidden Operations

The following are invalid.

10.1 Cross-Intent Equality

Invalid:

world-structure
=
evm-projection

even if digest values match.

10.2 Intent Substitution

Invalid:

take digest from Intent A
reinterpret as Intent B
10.3 Domain Tag Reuse

Invalid:

IntentA
IntentB

share same domain tag
10.4 Projection Bypass

Invalid:

CanonicalEncode(raw-value)

when the intent contract requires projection.

11. Intent Registry Requirements

Each intent MUST declare:

Intent Name
Domain Tag
Projection Rule
Semantic Purpose
Included Concepts
Excluded Concepts

Example:

Intent:
  world-structure

Includes:
  topology
  configuration
  oracle state

Excludes:
  functions
  executable behavior
  transient runtime artifacts
12. Evolution Rules
12.1 New Intent

A new intent requires:

Unique domain tag
Intent contract
Test vectors
Projection definition
Changelog entry
12.2 Projection Changes

Projection changes are identity-breaking.

Required:

NEW DOMAIN TAG

Example:

WORLD_STATE_V1
→ WORLD_STATE_V2
12.3 Encoding Changes

ABI changes are identity-breaking.

Required:

NEW DOMAIN TAG
13. Audit Guarantees

This algebra guarantees:

Deterministic identity generation.
Domain isolation.
Projection transparency.
Replay consistency within a domain.
Explicit semantic intent.

It does not guarantee:

Cross-domain equivalence.
Semantic equivalence between different intents.
Stability across projection/version changes.
14. Current Algebra Instance

Current deployed lattice:

WORLD_STATE_V1
EVM_PROJECTION_V1
EVIDENCE_RECORD_V1
EVIDENCE_CONTENT_V1
EVIDENCE_CHAIN_V1
MANIFEST_V1
BUNDLE_ROOT_V1
REGISTRY_V1
PROVENANCE_V1

All domains satisfy:

unique(domain-tag)

and are generated through:

(hash-with-intent {:hash/intent X} value)

as the sole identity entry point.
