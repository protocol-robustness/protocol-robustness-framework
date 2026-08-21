Canonical Hash Specification V1
Status
Draft
Purpose
This specification defines the canonical serialization and hashing algorithm used by the Protocol Robustness Framework.
The goals are:
    • Deterministic hashing across implementations and programming languages
    • Independent verification by third parties
    • Solidity-compatible verification
    • TSA-compatible attestations
    • Long-term forensic reproducibility
This specification supersedes implementation-defined hashing based on Clojure EDN printers, JSON serializers, or language-specific object representations.

1. Terminology
Canonical Bytes
A deterministic byte sequence produced from a value according to this specification.
Two logically equivalent values MUST produce identical canonical bytes.
Content Hash
The SHA-256 digest of a domain-separated canonical byte sequence.
Domain Tag
A fixed ASCII identifier prepended to canonical bytes before hashing.
Domain tags prevent cross-domain hash collisions.

2. Hash Algorithm
All hashes SHALL use SHA-256.
Content hashes SHALL be computed as:
HASH = SHA256(
DOMAIN_TAG || CANONICAL_BYTES
)
Where:
    • DOMAIN_TAG is UTF-8 encoded ASCII
    • CANONICAL_BYTES are produced by this specification
    • || denotes byte concatenation

2.1 Domain-Tag Framing (consecutive concatenation)
The concatenation DOMAIN_TAG || CANONICAL_BYTES carries no length frame on the
domain tag. Framing is therefore only unambiguous when no registered domain tag
is a strict prefix of another: a prefix relationship is the ONLY way two
distinct (DOMAIN_TAG, value) pairs can produce the same concatenated byte
stream (CANONICAL_BYTES is itself prefix-free and injective over the canonical
type algebra, so the canonical-bytes side of the concatenation cannot introduce
ambiguity).

Implementations and registries SHALL enforce prefix-freeness of the domain-tag
set: adding a tag that is a strict prefix of (or is prefixed by) an existing tag
is a framing violation and MUST fail closed at registry load.  This invariant is
enforced by the authoritative registry validation
(resolver-sim.hash.canonical/validate-registry!).

A future revision MAY replace the unframed concatenation with a length-prefixed
form (e.g. varuint(len) || DOMAIN_TAG || CANONICAL_BYTES).  Because every
existing content hash depends on the current framing, such a change is a
breaking format change: it requires recomputing all golden hashes, conformance
test vectors, and cross-language verifier preimages, and MUST NOT be introduced
without coordinated versioning.

2.2 Canonical Consecutive Composition
A canonical consecutive composition is a versioned encoding of one ordered
sequence of canonical member values.  The current `canonical-value-sequence.v1`
contract encodes exactly one canonical map containing:

    • `:encoding-contract` — the framing/schema version
    • `:purpose` — the composition domain
    • `:component-count` — the explicit non-negative arity
    • `:components` — the ordered canonical member vector

For a fixed contract version and purpose, write this encoder as `C_v`.  Its
domain is the set of canonical ordered member sequences `D_v*`, including the
empty sequence.  The required framing property is:

    C_v(X) = C_v(Y) if and only if X = Y, for all X,Y in D_v*.

In particular, the encoding preserves arity, member boundaries, ordering, and
repetition: `[] ≠ [A]`, `[A B] ≠ [C]`, `[A] ≠ [B C]`, `[A B] ≠ [B A]` when
`A ≠ B`, and `[A A] ≠ [A]`.  This is an encoding theorem, tested against
canonical bytes; it is not a claim that SHA-256 is injective.  Hashing
`SHA256(C_v(X))` provides a collision-resistant commitment to the already
unambiguous canonical bytes.

This low-level sequence contract accepts an already-normalized member vector;
it does not assign semantics to nested composition trees.  Any higher-level
consecutive operator that adopts associativity MUST normalize `(A ; B) ; C` and
`A ; (B ; C)` to the same `[A B C]` member vector before invoking `C_v`.

3. Supported Types
Implementations MUST support only the following value types.
Null
Represents absence of a value.
Boolean
Values:
    • true
    • false
String
UTF-8 encoded Unicode string.
Keyword
Qualified or unqualified symbolic identifier.
Examples:
    • :resolver/id
    • :active
Keywords are distinct from strings.
The keyword:
:active
MUST NOT hash identically to:
"active"
Integer
Arbitrary precision signed integer.
Implementations MUST support values larger than 64-bit range.
Implementations MUST accept native integer types as well as arbitrary-precision
wrapper types (e.g., Clojure BigInt, Java BigInteger). All integer types
are encoded identically via the signed integer encoding.
Vector
Ordered sequence of values.
Ordering is significant.
Map
Collection of key-value pairs.
Map ordering is not significant.
Keys MUST be:
    • keyword
    • string
No other key types are permitted.

4. Unsupported Types
The following types are prohibited:
    • ratio
    • symbol
    • set
    • list
    • bigdec
    • record
    • function
    • atom
    • promise
    • future
    • delay
    • arbitrary Java objects
Attempting to hash an unsupported type MUST fail.
Implementations MUST NOT silently coerce unsupported types.

5. Record Handling
Language-specific record types are prohibited in canonical hashing.
Examples include:
    • Clojure defrecord
    • Java record
    • Rust struct wrappers
    • Python dataclasses
Values must be converted into supported canonical forms before hashing.
Recommended conversion:
{
:type "...",
...
}
or
{
"type": "...",
...
}
Implementations MUST reject raw records.

6. Canonical Encoding
Each value is encoded as a typed binary sequence per the Binary Encoding ABI.
Every value begins with a single-byte type tag, followed by a type-specific payload.

6.1 Type Tags
Tag   Type
0x00  null
0x01  boolean false
0x02  boolean true
0x10  signed integer (INT)
0x20  string (UTF-8)
0x22  keyword
0x30  vector (ARRAY)
0x31  map

6.2 Null
Encoding: 0x00
No payload.

6.3 Boolean
false: 0x01
true:  0x02
No payload.

6.4 Integer
All integers (including BigInt/BigInteger) are encoded as signed integers.
Encoding: 0x10 || zigzag(varuint)
Steps:
    1. Apply ZigZag transform: n → (n << 1) ^ (n >> (bit-length - 1)), where bit-length is the minimum needed to represent n in two's complement
    2. Encode the resulting unsigned value as LEB128 varuint (little-endian base-128, continuation bit in MSB)
Examples:
0       → 0x10 0x00
1       → 0x10 0x02
-1      → 0x10 0x01
123     → 0x10 0xF6 0x01
-123    → 0x10 0xF5 0x01
127     → 0x10 0xFE 0x01
-127    → 0x10 0xFD 0x01

6.5 String
Encoding: 0x20 || length(varuint) || UTF-8 bytes
Length is the number of UTF-8 bytes (not characters).
Examples:
"active" → 0x20 0x06 0x61 0x63 0x74 0x69 0x76 0x65
"hello"  → 0x20 0x05 0x68 0x65 0x6C 0x6C 0x6F

6.6 Keyword
Keywords are distinct from strings and use a separate type tag.
Encoding: 0x22 || length(varuint) || UTF-8 bytes
Length is the number of UTF-8 bytes of the keyword name (including namespace prefix).
The colon prefix and optional namespace separator (/) are preserved in the byte payload.
Examples:
:active      → 0x22 0x06 0x61 0x63 0x74 0x69 0x76 0x65
:resolver/id → 0x22 0x0B 0x72 0x65 0x73 0x6F 0x6C 0x76 0x65 0x72 0x2F 0x69 0x64

6.7 Vector
Encoding: 0x30 || count(varuint) || concat(element_encodings)
Count is the number of elements (not byte length).
Each element is fully encoded (type tag + payload).
Ordering is significant and preserved.
Examples:
[]       → 0x30 0x00
[1 2 3]  → 0x30 0x03 0x10 0x02 0x10 0x04 0x10 0x06

6.8 Map
Encoding: 0x31 || count(varuint) || concat(kv_pairs)
Count is the number of key-value pairs (not byte length).
Each pair: encode(key) || encode(value)
Map keys MUST be sorted in canonical key order:
    1. All keys are sorted lexicographically by their full encoded canonical bytes (type tag + payload), using unsigned byte comparison.
    2. Since keyword tag (0x22) < string tag (0x20) in unsigned comparison, keywords sort before strings.
    3. Within each tag group, keys sort by payload bytes (UTF-8 lexicographic).
No duplicate keys are permitted.
Examples:
{}        → 0x31 0x00
{:a 1}    → 0x31 0x01 0x22 0x01 0x61 0x10 0x02
{:a 1 :b 2} → 0x31 0x02 0x22 0x01 0x61 0x10 0x02 0x22 0x01 0x62 0x10 0x04


7. Canonical Equality
Values are equal only when:
    • type is identical
    • encoded representation is identical
Examples:
:active != "active"
1 != "1"
true != "true"

8. Domain Tags
The following domain tags are reserved.
WORLD_STATE_V1
Hash of a canonicalized world state.
EVIDENCE_RECORD_V1
Hash of a finalized evidence record.
EVIDENCE_CHAIN_V1
Hash of chain metadata.
EVIDENCE_MERKLE_LEAF_V1
Merkle leaf hashing.
EVIDENCE_MERKLE_NODE_V1
Merkle internal node hashing.
REGISTRY_V1
Evidence registry commitment.
MANIFEST_V1
Manifest commitment.
PROVENANCE_V1
Provenance commitment.
BUNDLE_ROOT_V1
Top-level benchmark commitment.
Implementations MAY define additional tags.
Tags MUST be globally unique.

9. Compliance Requirements
A compliant implementation MUST:
    • Produce identical canonical bytes for identical values
    • Reject unsupported types
    • Preserve keyword/string distinction
    • Use SHA-256
    • Use domain-separated hashing
    • Implement canonical map ordering exactly
A compliant implementation MUST NOT:
    • Depend on object identity
    • Depend on language printer output
    • Depend on locale settings
    • Depend on JVM implementation details
    • Depend on JSON serializer behavior

10. Test Vectors
A conformance suite SHALL be maintained.
Each test vector SHALL specify:
    • input value
    • canonical bytes (hex)
    • domain tag
    • expected SHA-256 digest
All implementations MUST pass the published test vectors.

11. World State Projection (Semantic Identity Lens)
Domain: WORLD_STATE_V1

The simulation world state is a runtime structure containing both domain data
and simulation infrastructure (yield module implementations, temporal objects,
non-canonical collection types). Before hashing, the world state SHALL be
projected through a semantic identity lens called
`project-world-to-structure-view`.

11.1 Purpose
The projection selects the identity-relevant structure from the world state
by transforming runtime and non-canonical types into canonical-safe
representations. This is NOT a data-cleaning step — it is an intentional
identity lens. Every transformation is explicit and documented.

11.2 Transformation Rules

| Source Type              | Projected Form              | Rationale |
|--------------------------|------------------------------|-----------|
| Canonical types (null, bool, int, string, keyword, vector, map) | Pass through unchanged | Already hash-safe |
| java.time.Instant        | ISO-8601 string (.toString)  | JVM interop type; string is deterministic and portable |
| Double, Float            | Scientific notation string (%.17g) | Floating-point precision is environment-sensitive; string is deterministic |
| Ratio / Rational         | Scientific notation string (%.17g) | Ratio is implementation-specific; double approximation is deterministic and portable |
| PersistentHashSet        | Sorted vector (sorted by element projection, then vec) | Set ordering is implementation-dependent; sorted vector is deterministic |
| List, LazySeq, other sequential types | Vector (recursively projected) | Only vector is a canonical type; other seqs MUST be converted |
| Function / IFn           | :fn keyword                  | Functions are simulation infrastructure, not domain state |
| All other non-canonical types | Error (rejected)          | No silent coercion; all runtime types have explicit projections defined |

11.3 Idempotency
The projection is idempotent: applying it to an already-projected value returns
the same projected value. Projected values pass validate-canonical-value!
and are safe for direct canonical encoding.

11.4 Domain Exclusions
No data is silently excluded. All world state keys pass through the
projection recursively. The projection rejects types it cannot handle,
preventing silent data loss.

11.5 Intent Registry Contracts
Each hash intent is a machine-readable contract with explicit declarations.
Contracts are defined in `hash-intents` and include:

Fields:
    • :intent/name        — unique keyword identifier
    • :intent/description — human-readable description of the intent
    • :intent/scope       — set of data categories intentionally covered
    • :intent/excludes    — set of data categories explicitly excluded
    • :project            — projection function (see 11.2 for rules)
    • :domain             — domain tag (see 8 for reservations)

Intent contracts prevent semantic drift by making boundaries explicit
and machine-readable. Future tooling MAY validate that data being hashed
matches the declared :intent/scope and does not include :intent/excludes.

Registered Contracts:

| Intent              | Scope                                                          | Excludes                                                  |
|---------------------|----------------------------------------------------------------|-----------------------------------------------------------|
| :world-structure    | domain-state, positions, balances, config, oracle-state,       | module-implementations, runtime-values, functions,         |
|                     | resolver-registry, bond-state, dispute-state, escrow-state,    | sets, ratios, instants, doubles                           |
|                     | time-context                                                   |                                                           |
| :evidence-record    | attribution, action, result, context, artifact-kind,           | evidence-hash, timestamp, chain-metadata                  |
|                     | temporal-context, sub-hashes                                   |                                                           |
| :evidence-content   | serialized-content, evidence-fields, artifact-body             | chain-metadata, timestamps                               |
| :evidence-chain     | chain-links, registry-structure, prev-hash, chain-seq,         | artifact-content, evidence-payload, timestamps            |
|                     | self-hash                                                      |                                                           |
| :manifest           | manifest-metadata, bundle-structure, schema-version            | content-payloads, individual-artifacts                    |
| :bundle-root        | benchmark-metadata, environment, evidence-aggregates,         | runtime-posthash-fields, operational-locations,            |
|                     | reproducibility, benchmark-certification, run/manifest          | materialization-metadata, runtime-types                    |

> **Operational realization of `:bundle-root`.** The authoritative, currently
> implemented benchmark commitment is the **flat** `:evidence/hash` field:
> `:evidence/hash = hash-with-intent {:hash/intent :bundle-root}
> (hashable-evidence evidence)` (`resolver-sim.benchmark.integrity/
> hashable-evidence`, domain tag `BUNDLE_ROOT_V1`). `hashable-evidence` performs
> the field-level selection (excluding the `:intent/excludes` categories above as
> concrete fields); `hash-with-intent` then applies
> `project-world-to-structure-view` for runtime-type normalization. This is
> **not** the hierarchical `evidence_root || manifest_root || provenance_root`
> "Bundle Root" described normatively in `EVIDENCE_COMMITMENT_SPEC_V1.md` §13,
> which is an aspirational layered construction not yet realized. `:bundle-root`
> is the hash-intent name (an alias); `:evidence/hash` is the sole trusted
> field (see `docs/benchmarks/EVIDENCE_INTEGRITY_CONTRACT.md`). Scheme is selected
> by the bundle's `:evidence/commitment-version` (absent ⇒ current); there is no
> opportunistic cross-scheme fallback.
| :registry           | registry-index, artifact-catalog, commitment-root              | artifact-content, detailed-evidence, world-state          |
| :provenance         | provenance-lineage, verification-metadata, links               | raw-evidence-content, world-snapshots                     |

11.6 Claim Definition Registry Projection
Domain: CLAIM_DEFINITION

The `:claim-definition` intent defines the canonical identity of one claim
registry entry for registry-backed claim verification.

The registered `:claim-definition` contract currently uses version 1.

Purpose:
    • identify the stable claim identity and semantic inputs
    • support audit-grade claim verification against the registry
    • exclude presentation-only and runtime-only data from canonical identity

Canonical projection fields:
    • :id
    • :version
    • :category
    • :inputs
    • :evaluation
    • :outputs
    • :depends-on (when present)

Excluded from the projection:
    • :canonical-hash
    • :description
    • display metadata and non-identity metadata
    • transient runtime state
    • cached values

11.7 Attestor Registry Projection
Domain: ATTESTOR

The `:attestor` intent defines the canonical identity of one attestor registry
entry for registry-backed attestation verification.

The registered `:attestor` contract currently uses version 1.

Purpose:
    • identify the stable attestor identity and verification surface
    • support audit-grade attestation verification against the registry
    • exclude presentation-only and runtime-only data from canonical identity

Canonical projection fields:
    • :id
    • :type
    • :status
    • :verification
    • :delegates
    • :key-history

Excluded from the projection:
    • :canonical-hash
    • :attestor-hash
    • display metadata and non-identity metadata
    • transient runtime state
    • cached verification data

If `:delegates` or `:key-history` are absent in source data, they SHALL be
projected as empty vectors so the attestor projection remains explicit and
stable across implementations.

11.7 Implementation Reference
Clojure reference implementation: `resolver-sim.hash.canonical/project-world-to-structure-view`
Attestor projection: `resolver-sim.hash.canonical/project-attestor`
Intent-based API: `resolver-sim.hash.canonical/hash-with-intent`
Contract lookup: `resolver-sim.hash.canonical/resolve-intent`
See `hash-intents` for all supported intents and their projections.

Files migrated to use intent-based hashing: util/evidence, capture, checkpoints,
aggregate, event-evidence, benchmark/runner, yield/evidence, notebooks/manifest
All use (hash-with-intent {:hash/intent intent-keyword} data)

