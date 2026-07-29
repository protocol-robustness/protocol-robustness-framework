Canonical Hash V1 — Conformance Test Vector Specification
1. Purpose
This specification defines:
    • A deterministic method for producing Canonical Hash V1 
    • A standard format for test vectors 
    • Rules for concatenation in canonical byte construction 
    • Edge cases that must be validated for conformance 
It is designed to ensure interoperability, reproducibility, and audit-grade determinism.

2. Canonical Hash Definition
Canonical Hash V1 is defined as:
HASH_V1 = SHA-256(DOMAIN_TAG || CANONICAL_BYTES)
Where:
    • SHA-256 is the 256-bit hash function defined in FIPS 180-4 (SHA-2 family) 
    • DOMAIN_TAG is a fixed UTF-8 byte sequence identifying the hash domain 
    • || denotes byte-level concatenation (NOT string concatenation) 
    • CANONICAL_BYTES is a deterministic serialization of structured inputs 

3. Core Requirement: Concatenation Semantics
3.1 Byte-Level Concatenation (Mandatory)
All concatenation MUST be:
    • Byte concatenation only 
    • No implicit encoding conversion during concatenation 
    • No delimiter insertion unless explicitly specified 
Formally:
A || B := bytes(A) + bytes(B)

3.2 Concatenation Rules by Type
Type
Rule
All types
1-byte type tag prefix: null=0x00, bool=0x01, int=0x02, string=0x03, list=0x04, map=0x05, held-adjustment=0x06, slash-allocation=0x07, cancellation-equilibrium=0x08
Strings
Tag (0x03) || 4-byte LE byte count || UTF-8 bytes
Integers
Tag (0x02) || big-endian signed 64-bit (fixed per spec version; different width requires new DOMAIN_TAG)
Lists
Tag (0x04) || 4-byte LE element count || canonical encoding of each element
Maps
Tag (0x05) || 4-byte LE entry count || canonical encoding of each key-value pair, sorted by key; mixed-type keys ordered by type precedence: null < false < true < int < string
Held-adjustments
Tag (0x06) || schema-version string || 4-byte LE field count || canonical encoding of each field in fixed order (§4.5.1); final position is a mandatory extensions map
Slash-allocations
Tag (0x07) || schema-version string || 4-byte LE field count || canonical encoding of each field in fixed order (§4.5.2)
Cancellation-equilibrium
Tag (0x08) || schema-version string || 4-byte LE field count (8) || canonical encoding of each field in fixed order (§4.5.3); includes mechanism-reference binding algorithm config
Nested structures
Recursively canonicalized then concatenated

3.3 Field Boundary Ambiguity Rule
To avoid ambiguity:
    • No separators are used unless explicitly defined 
    • Structural boundaries must be encoded via: 
        ◦ length-prefix encoding OR 
        ◦ typed canonical serialization rules 

4. Canonical Bytes Construction
4.1 Base Rule
CANONICAL_BYTES = CanonicalEncode(structure)
Where CanonicalEncode:
    1. Normalizes structure (sorting, typing, normalization) 
    2. Encodes primitives 
    3. Concatenates fields deterministically 

4.2 Deterministic Encoding Rules

Every encoded value starts with a 1-byte type tag:

  Tag  |  Type
  -----|--------
  0x00 |  null
  0x01 |  boolean
  0x02 |  signed integer (64-bit big-endian)
  0x03 |  UTF-8 string
  0x04 |  list
  0x05 |  map
  0x06 |  held-adjustment (§4.5.1)
  0x07 |  slash-allocation (§4.5.2)
  0x08 |  cancellation-equilibrium (§4.5.3)

Null
  NULL := 0x00

Booleans
  FALSE := 0x01 0x00
  TRUE  := 0x01 0x01

Integers
  INT := 0x02 || 8-byte big-endian signed integer
  Integer width is fixed per spec version. A different width requires a different DOMAIN_TAG and spec version identifier.

Strings
  STRING := 0x03 || len_32le || UTF8(value)
  Where len_32le is a 4-byte unsigned little-endian byte count of the UTF-8 encoding.

4.3 Map Encoding
MAP := 0x05 || len_32le || entries
Where:
  • 0x05 is the map type tag
  • len_32le is a 4-byte unsigned little-endian count of key-value entries
  • entries is the concatenation of each key-value pair: encode(key) || encode(value)

Maps MUST follow:
  • sort keys lexicographically by their canonical encoding (byte order)
  • mixed-type keys are ordered by type precedence: null < false < true < int < string
  • within the same type, keys are ordered lexicographically by their canonical encoding

4.4 List Encoding
LIST := 0x04 || len_32le || encode(e1) || encode(e2) || ... || encode(en)
Where:
  • 0x04 is the list type tag
  • len_32le is a 4-byte unsigned little-endian count of elements
  • each element is encoded per §4.2 (includes its own type tag)
No separators.

4.5 Domain-Specific Composite Types

Domain-specific composites encode stable protocol artifacts with a fixed-field tuple core followed by a mandatory extensions map. Each composite starts with its type tag, a schema-version string, and a field count.

4.5.1 Held-Adjustment (0x06)

Encodes a single held-custody adjustment ledger entry.

HELD_ADJUSTMENT :=
  0x06
  || encode(schema-version)         ;; "held-adjustment.v1"
  || len_32le(10)
  || encode(direction)              ;; string: "in" | "out"
  || encode(token)                  ;; string
  || encode(amount)                 ;; int >= 0
  || encode(before)                 ;; int >= 0
  || encode(after)                  ;; int >= 0
  || encode(reason)                 ;; string
  || encode(action)                 ;; string
  || encode(account)                ;; string
  || encode(position-id)            ;; list of strings/ints (§4.4)
  || encode(extensions)             ;; canonical map (§4.3)

Cross-field invariants:
  direction = "in"  ⇒ after = before + amount
  direction = "out" ⇒ after = before - amount
  amount >= 0, before >= 0, after >= 0

Permitted v1 extension keys:
  workflow-id            (string | int)
  bond-id                (string)
  owner-address          (string)
  authorization-provenance (map)
  previous-artifact-hash (string)
  slash-id               (string | int)
  recipient              (string)

Strict v1 encoders and decoders MUST reject any extension key not listed above.
Unknown keys MUST NOT be silently discarded; a non-strict inspection parser MAY
preserve them but MUST NOT re-encode, sign, or certify the result as v1-compliant.

4.5.2 Slash-Allocation (0x07)

Encodes a pro-rata slash allocation result across liable parties.

SLASH_ALLOCATION :=
  0x07
  || encode(schema-version)         ;; "slash-allocation.v1"
  || len_32le(8)
  || encode(basis)                  ;; string
  || encode(slash-obligation)       ;; int >= 0
  || encode(total-basis)            ;; int >= 0
  || encode(recovered-total)        ;; int >= 0
  || encode(unmet-total)            ;; int >= 0
  || encode(allocations)            ;; list of allocation-entry (§4.5.2.1)
  || encode(mechanism/evidence-reference)  ;; string
  || encode(status)                 ;; string: "valid" | "no-liable-basis"

Cross-field invariants:
  recovered-total + unmet-total = slash-obligation
  recovered-total = sum(entry.recovered-amount) over all entries
  unmet-total = sum(entry.unmet-amount) over all entries
  total-basis = sum(entry.basis-amount) over all entries

4.5.2.1 Allocation Entry

ALLOCATION_ENTRY :=
  len_32le(5)
  || encode(liable-id)              ;; string
  || encode(basis-amount)           ;; int >= 0
  || encode(allocated-amount)       ;; int >= 0
  || encode(recovered-amount)       ;; int >= 0
  || encode(unmet-amount)           ;; int >= 0

Allocation entries are sorted by canonical encoding of liable-id.
Derived fields (share, cap) MUST NOT be encoded in the normative entry.
Unrecognised keys or extra positional fields MUST be rejected.

4.5.3 Cancellation-Equilibrium (0x08)

Encodes the result of a cancellation-dominance equilibrium validation.

CANCELLATION_EQUILIBRIUM :=
  0x08
  || encode(schema-version)         ;; "cancellation-equilibrium.v1"
  || len_32le(8)
  || encode(status)                 ;; string: "pass" | "fail" | "inconclusive"
  || encode(cancel-nodes-checked)   ;; int >= 0
  || encode(cancel-max-regret)      ;; int >= 0
  || encode(cancel-fail-count)      ;; int >= 0
  || encode(evidence-basis)         ;; string
  || encode(result-strength)        ;; string
  || encode(mechanism-reference)    ;; string: hash or ID of the mechanism evidence artifact
                                   ;;        binding the algorithm configuration
                                   ;;        (threshold, epsilon, evaluation-mode,
                                   ;;         continuation-policy, utility-spec,
                                   ;;         strategy-profile, max-deviation-depth)
  || encode(cancel-fails)           ;; list of cancel-fail-entry (§4.5.3.1)

Cross-field invariants:
  cancel-fail-count = len(cancel-fails)
  status = "pass"         => cancel-fail-count = 0 and cancel-max-regret = 0
  status = "inconclusive" => cancel-fail-count = 0 and cancel-nodes-checked = 0
  status = "fail"         => cancel-fail-count > 0
  cancel-max-regret >= max(entry.regret) for all entries in cancel-fails
  mechanism-reference must be a non-empty string when status != "inconclusive"

4.5.3.1 Cancel-Fail Entry

CANCEL_FAIL_ENTRY :=
  len_32le(5)
  || encode(seq)                    ;; int
  || encode(agent)                  ;; string
  || encode(action)                 ;; string
  || encode(regret)                 ;; int >= 0
  || encode(utility-evidence)       ;; canonical map (§4.3)

utility-evidence must be either {} or a map containing exactly
{:chosen-utility <int> :max-alt-utility <int>}.

When utility-evidence is non-empty:
  regret = max-alt-utility - chosen-utility
  max-alt-utility >= chosen-utility

A partially populated utility-evidence map MUST be rejected.

Cancel-fail entries are sorted by (seq, canonical-encoding(agent),
canonical-encoding(action)).

5. Domain Separation
5.1 DOMAIN_TAG Rules
DOMAIN_TAG must:
    • Be UTF-8 bytes 
    • Be immutable per spec version 
    • Prevent cross-protocol hash collisions 
Example:
DOMAIN_TAG = "CANONICAL_HASH_V1"
Encoded as:
UTF8("CANONICAL_HASH_V1")

6. Full Hash Construction
HASH_V1 =
SHA256(
    UTF8("CANONICAL_HASH_V1")
    ||
    CanonicalEncode(input)
)

7. Conformance Test Vector Specification
Each test vector MUST include:
7.1 Required Fields
{
  "id": "string",
  "description": "string",
  "input": "structured object",
  "canonical_bytes_hex": "hex string",
  "domain_tag_hex": "hex string",
  "hash_hex": "hex string"
}

7.2 Derived Fields
    • canonical_bytes_hex = hex(CANONICAL_BYTES) 
    • hash_hex = hex(SHA256(DOMAIN_TAG || CANONICAL_BYTES)) 

8. Test Vector Categories
8.1 Primitive Tests
    • single string 
    • single integer 
    • boolean/null values 
8.2 Structural Tests
    • nested maps 
    • mixed-type lists 
    • deep nesting (≥5 levels) 
8.3 Ordering Tests
    • map key ordering independence 
    • list ordering sensitivity 
8.4 Concatenation Stress Tests
    • long strings (>10k bytes) 
    • many-element lists (>10k items) 
    • deeply nested concatenation chains 
8.5 Boundary Ambiguity Tests
Ensures no collisions between:
    • "ab" + "c" vs "a" + "bc" 
    • nested encoding boundaries 

8.6 Domain-Specific Composite Tests
8.6.1 Held-Adjustment Tests
    • minimal adjustment (direction, token, amount, before=0, after=amount, reason, action, account, position-id, {})
    • adjustment with all extension keys populated
    • direction arithmetic: "in" ⇒ after=before+amount, "out" ⇒ after=before-amount
    • position-id with mixed types (string and int elements)
    • unknown extension key rejected in strict mode
    • unknown extension key preserved but marked unverified in inspection mode
    • unknown key discarded and re-encoded: must not be treated as equivalent
    • same adjustments with extension keys in different map orders → identical bytes
    • missing extensions vs {}: both accepted as canonical ({} always encoded)
8.6.2 Slash-Allocation Tests
    • zero-obligation allocation (slash-obligation=0, no-liable-basis)
    • single liable party with full recovery
    • multi-liable with splits and unmet amounts
    • allocation entries in non-canonical order → normalised to liable-id order
    • duplicate liable-id: rejected
    • recovered-total + unmet-total ≠ slash-obligation: rejected
    • encoded share: rejected as invalid schema shape
    • encoded cap: rejected as invalid schema shape
8.6.3 Cancellation-Equilibrium Tests
    • pass with empty cancel-fails
    • fail with multiple cancel-fail entries (with {} utility-evidence)
    • fail with entries containing full utility-evidence (chosen-utility, max-alt-utility)
    • utility-evidence difference ≠ regret: rejected
    • only chosen-utility present: rejected
    • only max-alt-utility present: rejected
    • inconclusive with cancel-nodes-checked=0
    • cancel-fail-count ≠ len(cancel-fails): rejected
    • pass with nonempty cancel-fails: rejected
    • mechanism-reference present on pass/fail, empty string rejected
    • mechanism-reference absent or empty on inconclusive: accepted
    • same equilibrium result with different mechanism-reference: distinct encodings
8.6.4 Cross-Composite Tests
    • composite inside a list
    • composite as a map value
    • nested composites (held-adjustment referenced by cancellation-equilibrium extensions)
    • truncated composite field stream: rejected
    • extra bytes after composite: rejected 

9. Critical Concatenation Edge Cases
9.1 No Delimiter Rule
This is a MUST:
Concatenation must never rely on separators such as |, ,, or whitespace.
Reason:
    • Prevents ambiguity attacks 
    • Ensures cryptographic determinism 

9.2 Length Ambiguity Protection
Collisions such as:
encode("a") || encode("bc")
vs
encode("ab") || encode("c")
are resolved by the mandatory type-tag + length-prefix encoding (§4.2):
encode("a")  → 0x03 || 0x00000001 || 0x61
encode("ab") → 0x03 || 0x00000002 || 0x6162
encode("bc") → 0x03 || 0x00000002 || 0x6263
encode("c")  → 0x03 || 0x00000001 || 0x63
These byte sequences are distinct, eliminating the ambiguity. 

9.3 Mixed-Type Concatenation
Example:
["1", 1]
Ambiguity is prevented by the type-tagged encoding rules in §4.2:
    • "1" encodes as 0x03 || 0x00000001 || 0x31  (string tag + length + UTF-8)
    • 1   encodes as 0x02 || 0x0000000000000001   (int tag + 8-byte value)
The type tag guarantees unambiguous parsing across all types and resolves the empty-value collision (§4.2).

10. Cross-System Concatenation Applicability
Concatenation rules here also apply to:
10.1 Evidence Chains
    • chaining attestations 
    • linking prior hashes 
10.2 Merkle-Like Structures (Non-tree variant)
    • linear hash accumulation 
    • append-only logs 
10.3 Pro-rata / Settlement Systems
    • deterministic aggregation of allocations 
    • ordered sum construction inputs 
10.4 Event Sourcing / Audit Logs
    • event serialization 
    • append-only canonical event stream 
10.5 API Signature Schemes
    • request canonicalization 
    • header + body concatenation 

11. Non-Normative Reference Pattern
A safe canonical encoding pipeline:
normalize(input)
→ sort_if_map
→ encode_primitives
→ recursively_encode
→ byte_concatenate
→ prepend_domain_tag
→ sha256

12. Compliance Requirements
A system is compliant if:
    • Hash output matches all provided test vectors 
    • Every encoded value is prefixed with its type tag per §4.2 
    • Variable-length types (string, list, map) are length-prefixed per §4.2–§4.4 
    • The null encoding (0x00) is distinct from boolean false (0x01 0x00) 
    • Integer width is fixed and consistent; no "unless overridden" allowance 
    • Map keys are ordered by type precedence, then lexicographically by canonical encoding 
    • Byte-level concatenation is deterministic with no delimiter-based ambiguity 
    • Nested structures are recursively canonicalized 
    • Domain composites (§4.5) include a schema-version string at field position 1 
    • Domain composites emit every required fixed-position field, including empty extensions map and empty detail lists 
    • Extensions map (§4.5.1) is always present; {} when no optional fields are supplied 
    • Unknown composite schema versions are rejected 
    • Unknown extension keys are rejected in strict v1 compliance mode 
    • Cross-field invariants (§4.5.1–§4.5.3) are enforced at encoding time
    • Mechanism-reference (§4.5.3) is a non-empty string for pass/fail results; empty only for inconclusive 
    • Nested sub-types (allocation entries, cancel-fail entries) follow their defined schemas and sorting rules 
    • Canonical field order is never derived from implementation map iteration order 
    • A fixed-position field is never omitted because its value is empty (zero, empty string, empty list)

