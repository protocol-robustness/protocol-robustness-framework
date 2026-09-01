# Cancellation transition hash contract V1

This contract freezes the byte pipeline used by `cancellation-transition-subject.v1`
and `cancellation-transition-result.v1`.

## Pipeline

```text
semantic projection
→ canonical typed value bytes
→ UTF-8 domain tag concatenated directly with value bytes
→ SHA-256
→ lowercase 64-character digest hex
→ `sha256:` + digest hex
```

There is no domain-tag length prefix or separator. The domain tag is UTF-8 bytes
followed immediately by canonical value bytes.

## Canonical type tags

| Type | Tag |
|---|---:|
| nil | `0x00` |
| false | `0x01` |
| true | `0x02` |
| integer | `0x10` |
| string | `0x20` |
| keyword | `0x22` |
| vector | `0x30` |
| map | `0x31` |

Lengths and unsigned integers use minimal unsigned LEB128. Signed integers use
ZigZag first: `0 → 0`, `-1 → 1`, `1 → 2`, `-2 → 3`, `2 → 4`.

Strings and keywords encode a LEB128 byte length followed by UTF-8 bytes.
Keywords encode their portable name: `:foo` is `foo`, and `:foo/bar` is
`foo/bar`; keywords remain distinct from strings because their tags differ.

Vectors encode element count followed by elements in order. Maps encode entry
count followed by each key/value pair. Map entries are ordered by the host
canonical map iteration contract; the frozen vectors and tests require insertion
order to be irrelevant.

Absent keys are not serialized. A present key with nil value is serialized as a
key followed by `0x00`; therefore `{}` and `{:x nil}` differ.

## Roots

The digest is 32 raw SHA-256 bytes. `digest-hex` is lowercase hexadecimal. The
external root representation is `sha256:` followed by `digest-hex`.

The golden vector at `data/fixtures/golden/cancellation-transition-vector.v1.edn`
contains semantic projections, canonical value bytes, complete hash preimages,
digests, and roots for both subject and result.
