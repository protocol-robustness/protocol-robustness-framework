Evidence Commitment Specification V1
Status
Draft
Purpose
This specification defines the cryptographic commitment structure used by the Protocol Robustness Framework.
The goals are:
    • {Tamper-evident evidence records
    • {Cryptographically verifiable evidence chains
    • Efficient Merkle inclusion proofs
    • Third-party auditability
    • RFC3161 TSA attestation support
    • Solidity verification support
    • Long-term forensic reproducibility
This specification depends on Canonical Hash Specification V1.

1. Terminology
Evidence Record
A finalized evidence artifact containing protocol-specific evidence.
Example:
    • transition evidence
    • invariant evidence
    • dispute evidence
    • accrual evidence
An evidence record is the smallest independently verifiable unit.

Evidence Hash
A SHA-256 commitment to an evidence record.
Computed using:
EVIDENCE_RECORD_V1
domain separation.

Evidence Chain
An ordered sequence of evidence records linked through previous-hash commitments.
The chain provides:
    • ordering
    • omission detection
    • tamper evidence

Evidence Merkle Tree
A binary Merkle tree constructed from evidence hashes.
The Merkle tree provides:
    • inclusion proofs
    • efficient verification
    • scalability

Bundle
The top-level cryptographic commitment representing an entire benchmark run.

2. Commitment Hierarchy
The commitment hierarchy SHALL be:
Evidence Record
↓
Evidence Hash
↓
Evidence Merkle Tree
↓
Evidence Root
↓
Bundle Root
↓
RFC3161 Timestamp
↓
On-Chain Attestation
The Bundle Root is the authoritative commitment for a benchmark run.

> **Realized form.** Today "Bundle Root" is the flat `:evidence/hash` field
> (hash intent `:bundle-root`, domain tag `BUNDLE_ROOT_V1`, over
> `hashable-evidence`); the layered `Evidence Merkle Tree → Evidence Root →
> Bundle Root` ladder above is aspirational and not yet implemented for
> benchmark evidence bundles.

3. Evidence Record Hash
An evidence record hash SHALL be computed as:
SHA256(
EVIDENCE_RECORD_V1
||
canonical_bytes(evidence_record)
)
The evidence record MUST NOT contain its own hash field.
Excluded fields SHALL be defined by the evidence schema.
Example exclusions:
    • evidence/hash
    • evidence/signature
Excluded fields MUST be documented explicitly.

4. Evidence Chain
Each evidence record MAY contain:
evidence/prev-hash
representing the immediately preceding evidence record.
Example:
Record 1
{
:evidence/hash H1
}
Record 2
{
:evidence/hash H2
:evidence/prev-hash H1
}
Record 3
{
:evidence/hash H3
:evidence/prev-hash H2
}

5. Chain Validation
A chain verifier MUST validate:
    1. Every record hash recomputes correctly
    2. Every prev-hash references an existing record
    3. Chain order is contiguous
    4. No cycles exist
    5. No duplicate evidence hashes exist
A chain verifier MUST fail if any condition is violated.

6. Evidence Merkle Tree
Evidence hashes SHALL be inserted into a Merkle tree in deterministic order.
The order SHALL be:
ascending evidence chain sequence
or
ascending event sequence
depending on the evidence schema.
The ordering rule MUST be explicit.

7. Merkle Leaf Construction
Each evidence hash SHALL be converted into a Merkle leaf.
Leaf hash:
SHA256(
EVIDENCE_MERKLE_LEAF_V1
||
evidence_hash
)
where:
evidence_hash
is the raw 32-byte digest.
Hex strings MUST NOT be hashed.

8. Merkle Internal Node Construction
Internal nodes SHALL be computed as:
SHA256(
EVIDENCE_MERKLE_NODE_V1
||
left_child
||
right_child
)
where:
left_child and right_child
are raw 32-byte hashes.

9. Odd Node Handling
If a tree level contains an odd number of nodes:
duplicate the final node.
Example:
[A B C]
becomes:
[A B C C]
before computing the next level.
This rule SHALL be applied recursively.

10. Evidence Root
The root of the Merkle tree SHALL be:
evidence_root
The evidence root commits to every evidence record in the benchmark run.
Any modification to:
    • content
    • ordering
    • inclusion
MUST alter the root.

11. Manifest Commitment
The benchmark manifest SHALL be hashed independently:
manifest_root =
SHA256(
MANIFEST_V1
||
canonical_bytes(manifest)
)
The manifest MUST NOT include:
    • bundle_root
    • TSA proof
    • signatures
to avoid recursive commitments.

12. Provenance Commitment
Provenance metadata SHALL be hashed independently:
provenance_root =
SHA256(
PROVENANCE_V1
||
canonical_bytes(provenance)
)
Examples:
    • git commit
    • repository state
    • execution environment
    • benchmark version

13. Bundle Root
The Bundle Root SHALL be:
SHA256(
BUNDLE_ROOT_V1
||
evidence_root
||
manifest_root
||
provenance_root
)
where all inputs are raw 32-byte hashes.
This root is the authoritative benchmark commitment.

> **Implemented form.** The operational benchmark commitment implemented today
> is the **flat** `:evidence/hash` field: `:evidence/hash =
> hash-with-intent {:hash/intent :bundle-root} (hashable-evidence evidence)`
> (domain tag `BUNDLE_ROOT_V1`), covering the whole normalized evidence map in a
> single hash. It is **not** the layered `evidence_root || manifest_root ||
> provenance_root` construction above, which remains aspirational (see
> `CANONICAL_HASH_SPEC_V1.md` §11.5). The ladder rows `Evidence Root → Bundle
> Root` denote that future layered form; consumers MUST trust `:evidence/hash`
> and never treat `:bundle-root` as a separate field. Scheme is selected by the
> bundle's `:evidence/commitment-version` (absent ⇒ current).

14. RFC3161 Timestamping
RFC3161 timestamping SHALL operate on:
bundle_root
directly.
Implementations MUST NOT timestamp:
    • hex strings
    • JSON wrappers
    • SHA256(bundle_root)
The TSA SHALL timestamp the bundle root digest itself.

15. Timestamp Record
Timestamp records SHALL contain:
{
"bundle_root": "...",
"tsa_token": "...",
"tsa_policy": "...",
"tsa_time": "...",
"hash_algorithm": "sha256"
}
The timestamp record is metadata.
It does not modify the bundle root.

16. On-Chain Attestation
Smart contracts SHALL store:
bundle_root
or
evidence_root
depending on deployment requirements.
The preferred commitment is:
bundle_root
because it commits to:
    • evidence
    • manifest
    • provenance
simultaneously.

17. Merkle Proof Structure
A Merkle proof SHALL contain:
{
"leaf_hash": "...",
"path": [
{
"direction": "left",
"hash": "..."
},
{
"direction": "right",
"hash": "..."
}
],
"root": "..."
}
Hashes SHALL be represented as 32-byte values.

18. Solidity Verification
A Solidity verifier SHALL:
    1. Reconstruct the leaf hash
    2. Apply path elements sequentially
    3. Recompute the Merkle root
    4. Compare the result with the committed root
Verification success proves:
    • inclusion
    • ordering position within the proof path
    • commitment integrity
Verification does not prove semantic correctness.

19. Evidence Completeness
Merkle inclusion proves:
"this evidence existed within the committed benchmark run."
It does not prove:
    • evidence sufficiency
    • evidence correctness
    • protocol validity
Those properties are validated separately by benchmark logic and invariant checking.

20. Compliance Requirements
A compliant implementation MUST:
    • Implement Canonical Hash Specification V1
    • Implement domain-separated hashing
    • Use SHA-256
    • Use raw digest bytes in Merkle construction
    • Preserve deterministic evidence ordering
    • Produce identical Merkle roots across implementations
A compliant implementation MUST NOT:
    • Hash hex strings
    • Hash JSON renderings of hashes
    • Depend on language-specific object serialization
    • Depend on object identity

21. Future Extensions
The following are reserved for future versions:
    • Multiple evidence trees
    • Sparse Merkle trees
    • Incremental Merkle trees
    • Signature commitments
    • Multi-party attestations
    • zk-proof commitments
Such extensions SHALL require a new version identifier.

