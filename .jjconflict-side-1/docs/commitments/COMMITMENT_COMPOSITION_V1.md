# PRF commitment composition v1

## Purpose

A commitment composition is a domain-separated canonical commitment whose
members are themselves typed canonical commitments. It binds several
independently meaningful assurance objects into one higher-order assurance
object without erasing their semantic roles.

It is intentionally smaller than a Merkle framework and does not replace
artifact-specific roots. It supplies a reusable parent object only when a
consumer must bind several already-authoritative commitments.

## Layers

```text
artifact commitment
        ↓
commitment composition
        ↓
optional seal
        ↓
optional opening
        ↓
verification
```

- An **artifact commitment** identifies one canonical artifact.
- A **composition** binds typed, named artifact commitments under one semantic
  composition domain.
- A **seal** is a separate protocol layer for delayed disclosure or blinding.
  It can bind a nonce and composition root, but the base composition contains no
  secret and is useful without an opening phase.
- An **opening** reveals the data required by a seal protocol.
- **Verification** first validates the composition fixed point and then, where
  applicable, validates the separate seal/opening protocol.

## Canonical artifact

```clojure
{:artifact/schema "prf/commitment-composition.v1"
 :composition/domain :prf/example-assurance-bundle.v1
 :composition/members
 [{:member/type :source-artifact
   :member/name :generator-source
   :member/commitment "sha256:<64 lowercase hex>"}
  ...]
 :composition/root "sha256:<64 lowercase hex>"}
```

`resolver-sim.commitment.composition/build-composition` accepts the unsigned
input fields (`:composition/domain` and `:composition/members`) and returns
this complete artifact.

### Member rules

Each member has exactly these keys:

```clojure
#{:member/type :member/name :member/commitment}
```

- `:member/type` is a keyword identifying the kind of committed object.
- `:member/name` is a keyword identifying its unique semantic role in this
  composition.
- `:member/commitment` is a strict `sha256:<64 lowercase hex>` reference. Bare
  hexadecimal strings, decoded byte arrays, alternate algorithms, and uppercase
  encodings are rejected.

Members are a non-empty vector sorted ascending by:

```text
[(str :member/type) (str :member/name)]
```

Member names must be unique. The role and type are both committed, so identical
digest bytes cannot be substituted between, for example, an input-set role and
an expected-output-set role.

The builder deterministically sorts valid members. The validator rejects a
materialized artifact whose member vector is not already in this order.

## Root derivation

The unsigned preimage is exactly:

```clojure
{:artifact/schema "prf/commitment-composition.v1"
 :composition/domain <keyword>
 :composition/members <canonical ordered vector>}
```

The derivation has one SHA-256 operation; `sha256-ref` only represents that
already-computed digest as the canonical `sha256:<hex>` string:

```text
digest = SHA-256(
  UTF8("PRF_COMMITMENT_COMPOSITION_V1")
  || canonical-bytes(unsigned-preimage)
)

composition/root = sha256-ref(digest)
```

Equivalently:

```clojure
(hash-ref/sha256-ref
  (hc/domain-hash composition-domain unsigned-preimage))
```

`canonical-bytes` is the repository canonical typed encoding. Maps are
self-delimiting and canonically key-ordered; vectors include their count and
preserve the specified member order. Therefore this construction has no
informal concatenation interpretation such as `A || BC` versus `AB || C`.

The reference string itself is committed as a canonical string. The primitive
does not decode its hexadecimal suffix into 32 raw bytes; there is exactly one
accepted external representation.

## Validation and rejection

`validate-composition` returns `{:valid? ... :errors [...]}` and rejects:

- non-map artifacts or extra/missing composition keys;
- any schema other than V1;
- a non-keyword composition domain;
- a non-vector or empty member collection;
- malformed members or extra/missing member keys;
- non-keyword member type/name;
- non-canonical commitment references;
- duplicate member names;
- noncanonical member order; and
- missing, malformed, or mismatched composition roots.

The root is self-verifying but does not establish that each referenced artifact
is available or valid. Consumers must resolve and verify those artifacts under
their own protocol semantics.

## Clean-room sealing relationship

The realized partial-fill engagement has four semantic commitments:

```text
generator source
projection rules
holdout inputs
expected outputs
```

They are a valid prospective `prf/commitment-composition.v1` member set, and
are covered by the clean-room example vector in
`resolver-sim.commitment.composition-test`.

The existing engagement seal is **not** this V1 composition root. It is an
already-sealed legacy/current construction that hashes its domain, private
nonce, and the four raw commitment values directly. Its bytes, nonce, expected
outputs, blinded seal, and engagement identity remain unchanged. A future
clean-room engagement may seal a composition root under its own delayed-
disclosure protocol; it must not silently reinterpret or reseal the current
engagement.

## Intended future consumers

Potential consumers include proof/admission bundles, pro-rata assurance
packages, SEW adjudication or closure evidence, terminal settlement evidence,
and publication/evidence bundles. Each must define its own composition domain,
member roles, resolver requirements, and admission semantics. This primitive
adds only canonical typed parent commitment semantics; it does not grant
admission, execution, confidentiality, or external assurance claims.
