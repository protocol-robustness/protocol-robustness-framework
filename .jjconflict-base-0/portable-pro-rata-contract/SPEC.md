# Portable Pro-Rata Contract v1

## 1. Status and Authority

This document and `contract.edn` define `portable-pro-rata-contract.v1`.
They are normative. Source code under `resolver-sim.pro-rata.*`, Solidity code,
proof programs, tests, and conformance tools are realizations or evidence and
MUST NOT redefine this contract.

Authority ordering is:

```text
SPEC.md + contract.edn + canonical encoding + normative vectors
  -> portable normative contract
  -> executable Clojure reference/conformance realization
  -> production resolver-sim.pro-rata realization
```

An executable realization is not normative merely because it is executable. A
future Solidity or Rust realization may identify a defect in either Clojure
realization by reference to the normative package.

The key words MUST, MUST NOT, REQUIRED, SHALL, SHALL NOT, SHOULD, SHOULD NOT,
MAY, and OPTIONAL are to be interpreted as described by RFC 2119.

EDN files are interchange data. EDN text is never hashed. A parsed semantic
value MUST pass the closed structural and arithmetic rules below, be normalized,
projected, encoded using Section 3, and only then hashed.

## 2. Versioning and Immutability

The portable package version is `portable-pro-rata-contract.v1`. EVM statement
versions are independent. `pro-rata-evm-v2` may implement this contract without
changing its version.

A change to an arithmetic rule, field meaning, canonical projection, encoding,
domain tag, ordering rule, rejection condition, or derived value requires a new
portable or artifact version. Historical versions MUST NOT be reinterpreted.

## 3. Canonical Encoding and Hashing

### 3.1 Type algebra

The canonical value algebra contains only null, Boolean, arbitrary-precision
integer, UTF-8 string, keyword, vector, and map. Maps MUST have string or keyword
keys. Floating point, decimal, ratio, byte array, list/sequence, set, record,
timestamp, and host object values MUST be rejected.

Strings and keyword components are encoded as their existing Unicode scalar
sequence in UTF-8. No Unicode normalization is performed. A keyword is encoded
without a leading colon: `:a/b` becomes UTF-8 `a/b`; `:b` becomes `b`.

### 3.2 Tags and lengths

The one-byte tags are: null `00`, false `01`, true `02`, integer `10`, string
`20`, keyword `22`, vector `30`, and map `31`.

Unsigned values and lengths use minimal unsigned LEB128. Signed integers first
use arbitrary-precision ZigZag: non-negative `n -> 2*n`; negative
`n -> -2*n-1`. Integer overflow is impossible; an implementation with bounded
native words MUST use checked multiprecision arithmetic or reject before any
commitment is emitted.

String and keyword encodings are `tag || byte-length-varuint || UTF-8`.
Vector encoding is `30 || element-count-varuint || encoded-elements`.
Map entries are ordered lexicographically by the unsigned bytes of each encoded
key and encoded as `31 || entry-count-varuint || key || value ...`.

### 3.3 Domain hashing

For every portable root:

```text
canonical-bytes = ENCODE(canonical-projection)
preimage        = UTF8(exact-domain-tag) || canonical-bytes
root            = lowercase-hex(SHA-256(preimage))
```

There is no delimiter or length frame around the domain tag. Exact tags are in
`contract.edn`. A `sha256:` wrapper, where used by an integration, is a reference
representation and is not part of the 64-hex digest.

## 4. Allocation

### 4.1 Input and domains

A v1 request MUST contain the fields declared in `contract.edn`. `available`,
`requested`, `weight`, and non-null `cap` MUST be non-negative
arbitrary-precision integers. Row IDs MUST be canonical values and unique.
`obligation/id` is carried as identity evidence and does not merge rows.

Rows MUST be normalized by converting all integer widths to mathematical
integers, setting `effective-cap = min(requested, cap)` when cap exists and
`effective-cap = requested` otherwise, then ordering by the unsigned canonical
bytes of `row/id`.

No semantic default is permitted. Rounding, tie-break, and redistribution policy
MUST be explicit. V1 supports the values in `contract.edn` only.

### 4.2 Quotas and rounding

For active row `i`, available amount `A`, weight `w_i`, and active weight total
`W`, the exact quota is `A*w_i/W`. All products and comparisons MUST use exact
unbounded integers. If `W=0`, every active allocation is zero and available
amount remains residual with reason `no-active-weight`.

`:floor` assigns `floor(A*w_i/W)` and retains the rounding remainder as residual.
`:largest-remainder` first assigns every floor. Remaining whole units are awarded
in descending order of exact fractional remainder, comparing fractions by cross
multiplication. Equal remainders are ordered by canonical row-ID bytes.

### 4.3 Cap and redistribution

`:unallocated` computes quotas over the complete row set once, clamps each to
its effective cap, and does not redistribute cap excess. For a clamped row,
`unmet` is the pre-cap integer award minus `allocated`. This is cap shortfall,
not total unfilled demand.

`:redistribute-cap-excess` repeatedly commits every row whose exact quota is at
least its effective cap, subtracts those caps, removes those rows, and recomputes
over the remaining active set. A committed row is never eligible again. The
active set strictly shrinks on every cap round, so at most the original row count
of cap rounds may occur. Rounding occurs once over the final unconstrained group.
If the active set has zero total weight, remaining availability is terminal
residual with `no-active-weight`. If every active row has been cap-committed,
remaining availability is terminal cap shortfall: it is attributed to committed
rows as `unmet` by the final deterministic largest-remainder weight split and
`unallocated-residual` is zero. A row with equality at its effective cap is cap
constrained. The witness records every active set, exact quota, cap commitment,
and post-commit availability; it is semantic evidence committed by the result
root.

`allocated` is the final amount assigned to a row. `unmet` has only the
policy-specific cap-shortfall meaning above. An implementation MUST NOT infer
`requested = allocated + unmet` for all profiles. `unallocated-residual` is
availability not allocated by the mechanism and is distinct from row `unmet`.

### 4.4 Allocation identity and invariants

The result MUST include its canonical request, deterministic witness rounds,
normalized rows, totals, policies, and residual reason. The allocation root is:

```text
SHA256("PROJECTION_ARTIFACT_V1" ||
       ENCODE(result without :allocation/hash and
                     :allocation/fractional-remainder-total))
```

The fractional remainder total is diagnostic and excluded historically. No other
semantic result field may be omitted. A verifier MUST reject duplicate row IDs,
negative values, unsupported policies, incorrect canonical order, incorrect
quotas/remainders, incorrect remainder awards, impossible residual reasons,
aggregate conservation failure, or root mismatch.

## 5. Canonical Quantity State

A canonical state is a map from canonical quantity-root string to non-negative
arbitrary-precision integer. Native storage locations are not portable state.

The state projection is:

```clojure
{:schema-version "canonical-effect-state.v1"
 :quantities <map ordered by canonical map-key encoding>}
```

Its domain is `CANONICAL_EFFECT_STATE_V1`. State-before and state-after roots
have identical derivation rules.

## 6. Canonical Effects

A raw effect has the exact fields:

```clojure
{:schema-version "canonical-delta-effect.v1"
 :quantity/root <root string>
 :delta <signed arbitrary-precision integer>}
```

Normalization MUST sum all deltas for each target using unbounded signed
arithmetic, remove zero totals, and order by quantity-root string. Emission order
is irrelevant. Duplicate targets compose; they are not an error.

The effect-set projection is:

```clojure
{:schema-version "canonical-delta-effect.v1"
 :effect-semantics/root <fixed v1 semantics root>
 :effects <normalized vector>}
```

Its domain is `CANONICAL_EFFECT_SET_V1`.

## 7. Transition Derivation

The only portable authority direction is:

```text
validated state-before + normalized effects
  -> atomically apply every delta
  -> derived state-after
  -> state-after/root
  -> canonical-effect-transition/root
```

For each effect, missing quantities have value zero. A negative resulting value
is underflow and MUST reject the entire transition. No partial state or root may
be emitted. State-after is never caller-authoritative. If supplied for checking,
both its complete value and root MUST equal independent derivation.

The transition root projection contains exactly:

```clojure
{:schema-version "canonical-effect-transition.v1"
 :effect-semantics/root ...
 :state-before/root ...
 :effects/root ...
 :state-after/root ...}
```

Its domain is `CANONICAL_EFFECT_TRANSITION_V1`. The effects and full derived
state-after are reconstruction evidence but are not duplicated in this root
projection because their roots already commit them.

## 8. Allocation-to-Effect Boundary

V1 standardizes canonical effects and transitions but does not declare every
application mapping portable. A mapping may be portable only through a separately
versioned profile that defines exact subjects, roles, target quantities, and
allocation-to-delta derivation.

The existing SEW aggregate-held-credit profile, native location maps, custody
mirrors, and application policy roots are application concerns and are not part
of this portable release. EVM V2 MUST obtain application mappings through an
exact rooted application binding; it MUST NOT present them as universal pro-rata
semantics.

## 9. Reconstruction and Rejection

A verifier SHALL parse interchange data, validate closed shape/version/domain,
normalize allocation and effects, reconstruct allocation where applicable,
derive state-after, reproduce every intermediate canonical byte sequence and
root, and compare all supplied commitments.

Stable failure categories are listed in `contract.edn`. Malformation,
noncanonical representation, unknown version/policy, ordering disagreement,
arithmetic-domain violation, underflow, effect omission/addition/mutation,
post-state substitution, projection disagreement, and root disagreement MUST
remain distinguishable.

## 10. Semantic Identity and Provenance

Allocation, effects, state-before, state-after, and transition roots are semantic
identities. Contract release root, source revision, implementation revision,
compiler, proof program/VK, fixture package, and signer are forensic provenance.
Implementation provenance MUST NOT alter portable semantic identities.

`manifest.edn` is a closed-world inventory. Its release root is an evidentiary
identity over file paths and SHA-256 file digests plus contract version. Release
signing may bind that root separately; no release key is defined by this semantic
contract.

## 11. Extension and Application Authority

Generic extension machinery may bind capability ID, descriptor root, semantic
contract release root, exact dependencies, scope, and input/output contracts.
Execution MAY instantiate an already declared capability closure and MUST NOT
enlarge it. Dynamic discovery cannot grant authority.

Application validity is reconstructed against exact registry, definition,
descriptor, invocation, sequence, evidence, and portable-contract release roots.
A caller-supplied validity flag has no authority.

## 12. Strategic and Experimental Separation

Game definitions, players, available actions, information, utilities, strategies,
deviations, coalitions, treatments, randomness, trajectories, diagnostics, and
search bounds are outside portable allocation identity. Production strategic
validators require explicit rooted authority. Experimental findings are
non-authoritative unless promoted through an explicit registry/application update.
No empirical or bounded result may claim stronger assurance than its committed
model and evaluation basis establish.

## 13. Conformance

Vectors publish semantic values, normalized forms, intermediate roots, and exact
canonical bytes. Positive agreement alone is insufficient: implementations MUST
also pass mutation/rejection vectors. Clojure, EVM, and clean-room realizations
must consume the same released vectors without invoking one another.
