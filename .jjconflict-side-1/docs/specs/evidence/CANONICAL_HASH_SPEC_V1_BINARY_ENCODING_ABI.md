Canonical Hash V1 — Binary Encoding ABI (Primitive Layer)
1. Overview
This ABI defines a canonical binary representation for primitive values used inside CanonicalEncode(...).
It is designed to guarantee:
    • Byte-for-byte determinism 
    • No encoding ambiguity 
    • Safe concatenation without delimiters 
    • Cross-language reproducibility 
All values are encoded as:
TYPE_TAG || ENCODED_VALUE
Where:
    • TYPE_TAG is a single byte 
    • ENCODED_VALUE is type-specific 

2. Type System
Type
Tag (hex)
Description
NULL
0x00
Null value
BOOL_FALSE
0x01
Boolean false
BOOL_TRUE
0x02
Boolean true
INT
0x10
Signed integer
UINT
0x11
Unsigned integer
STRING
0x20
UTF-8 string
BYTES
0x21
Raw byte array
ARRAY_START
0x30
Array marker (structural, not standalone value)
MAP_START
0x31
Map marker (structural, not standalone value)

3. Primitive Encoding Rules
3.1 Null
NULL := 0x00
No payload.

3.2 Boolean
false := 0x01
true  := 0x02
No payload.

3.3 Unsigned Integer (UINT)
Format
0x11 || varuint(bytes)
Encoding
    • Variable-length base-128 (LEB128-style) 
    • Little-endian continuation encoding 
Rules
    • Minimal representation required (no leading zeroes) 
    • Must be canonicalized (no alternate encodings allowed) 
Example:
0 → 0x00
1 → 0x01
127 → 0x7f
128 → 0x80 0x01

3.4 Signed Integer (INT)
Format
0x10 || zigzag(varuint)
Encoding Steps
    1. Apply ZigZag transform: 
n -> (n << 1) ^ (n >> 63)
    2. Encode as varuint 
Rationale
    • Efficient encoding for negatives 
    • Deterministic ordering preserved 

3.5 String
Format
0x20 || length(varuint) || UTF8(bytes)
Rules
    • Must be UTF-8 normalized (NFC recommended unless otherwise specified) 
    • Length prefix is mandatory 
    • No null termination 
Example:
"hi"
→ 0x20 0x02 0x68 0x69

3.6 Bytes
Format
0x21 || length(varuint) || raw-bytes
Same as STRING but without UTF-8 constraints.

4. Structural Markers (Internal Use Only)
These are NOT standalone values but used in composite encoding.
4.1 Array Start
0x30
Followed by:
elements...
0x00 (ARRAY_END implicit via length in higher-level encoding)
However, for Canonical Hash V1, arrays MUST use length-prefixed encoding at higher level, so:
ARRAY_START is reserved but NOT used in final canonical encoding.

4.2 Map Start
0x31
Same rule as arrays:
    • reserved for future streaming ABI 
    • not used in final canonical hash encoding 

5. Composite Encoding Rules (ABI Integration Layer)
Although primitives are encoded independently, composite structures use:
5.1 Array Encoding
ARRAY := 0x30 || length(varuint) || concat(encoded_elements)
Where:
    • length = number of elements (not byte length) 
    • each element is fully encoded primitive or composite 

5.2 Map Encoding
MAP := 0x31 || length(varuint) || concat(kv_pairs)
Each pair:
encode(key) || encode(value)
Rules:
    • keys MUST be lexicographically sorted (byte order of encoded key) 
    • no duplicate keys allowed 

6. Canonical Constraints
To ensure ABI determinism:
6.1 Minimal Encoding Rule
    • No leading zeros in integers 
    • No alternate UTF-8 forms 
    • No redundant length encodings 

6.2 Strict Type Tagging
Every value MUST begin with a type tag.
Invalid:
"hello" (raw UTF-8)
Valid:
0x20 ...

6.3 No Implicit Type Coercion
The ABI forbids:
    • string → int coercion 
    • numeric string interpretation 
    • boolean encoding from numeric values 

7. Encoding Examples
7.1 Integer
-5
→ 0x10 || zigzag(-5)
→ 0x10 || 9
→ 0x10 0x09

7.2 String
"abc"
→ 0x20 0x03 0x61 0x62 0x63

7.3 Map
Input:
{ "b": 2, "a": 1 }
Step:
    • sort keys: a, b 
Encoding:
0x31
0x02
  0x20 0x01 0x61   (key "a")
  0x10 0x02        (value 1)
  0x20 0x01 0x62   (key "b")
  0x10 0x04        (value 2 zigzag encoded form example)

8. Concatenation Interaction with ABI
This ABI is explicitly designed so that:
8.1 Safe Concatenation Property
encode(A) || encode(B)
is always unambiguous because:
    • every value is self-delimiting via length or varuint rules 
    • every value is type-tagged 
    • no separator inference is needed 

8.2 Nested Structures
Recursive encoding guarantees:
encode(x) is always prefix-free
Therefore:
    • concatenation is associative at byte level 
    • grouping is irrelevant to final hash 

9. Extensibility Rules
Future versions may add:
    • decimal type (fixed-point) 
    • timestamp type (unix nanos) 
    • cryptographic public key type 
    • structured evidence references 
Rules for extension:
    • new types MUST use new type tags ≥ 0x40 
    • old decoders MUST fail or ignore safely if unknown 

10. ABI Stability Guarantee
This ABI guarantees:
    • deterministic encoding across languages 
    • stable hash outputs across systems 
    • safe composition into evidence chains 
    • compatibility with canonical hash V1 concatenation model

