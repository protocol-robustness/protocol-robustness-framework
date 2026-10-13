# Realized partial-fill projections v1

This document defines the exact semantic bodies and root preimages required by
the external `largest-remainder-deferred-pro-rata.v1` realized partial-fill
exercise. It is scoped to `realized-allocation-statement.v1` and is read with
`external-cleanroom-realized-partial-fill-v1.md`.

## Canonical value and root rule

Every body below is a canonical value under the portable canonical encoding:
null, booleans, exact integers, strings, keywords, vectors, and maps only.
Maps sort by encoded key bytes. Roots are:

```text
lowercase-hex(SHA-256(UTF8(domain-tag) || canonical-bytes(body)))
```

The exact binary encoding and map-order rule are defined by
`portable-pro-rata-contract/SPEC.md` §3. JSON source text is not hashed.

## Input normalization

- Claim IDs are non-empty strings.
- Amounts are exact non-negative integers.
- Requested entries are ordered by ascending claim-ID string order before
  realization and before constructing any vector body.
- Unsupported policy values reject; they do not default into this profile.
- The supported fill policy is `{ :mode :pro-rata,
  :rounding-policy :largest-remainder }`.
- The supported fail-action policy is `{ :mode :pro-rata-treatment,
  :deferred-policy :same-ratio, :haircut-policy :same-ratio }`.

For a shortfall, exact quota for claim `i` is:

```text
available * requested[i] / sum(requested)
```

The initial fill is its mathematical floor. Remaining units go to descending
fractional remainders; equal remainders use ascending canonical claim-ID order.
`deferred = requested - filled`; `haircut = 0`; `unrealized = 0`.

## Committed bodies

### Allocation context

`allocation-context-root` is the `ALLOCATION_CONTEXT_V1` root of the canonical
allocation context body supplied in `allocation-context`. Its exact context
schema is the existing allocation-kernel context schema; implementations must
reconstruct it from raw input and must not accept a supplied root.

### Request set

`request-set-root` is the `REALIZED_REQUEST_SET_V1` root of:

```clojure
{"claim-id-a" requested-a
 "claim-id-b" requested-b}
```

The map is canonical-map ordered by encoded string key bytes.

### Allocation policy

`allocation-policy-root` is the `ALLOCATION_POLICY_V1` root of the effective
fill-policy map. `:fail-action-policy` is excluded even if present in an
implementation-local policy container.

### Fail-action policy

`fail-action-policy-root` is the `FAIL_ACTION_POLICY_V1` root of the declared
fail-action policy map. If absent, the canonical default is exactly:

```clojure
{:mode :pro-rata-treatment
 :deferred-policy :same-ratio
 :haircut-policy :same-ratio}
```

### Realized results

`realized-results-root` is the `REALIZED_RESULTS_V1` root of a vector sorted by
ascending claim ID. Each requested claim appears exactly once:

```clojure
[{:claim/id "claim-id-a"
  :requested 60
  :filled 45
  :deferred 15
  :haircut 0
  :unrealized 0
  :disposition :deferred}]
```

Disposition precedence is exact:

1. positive deferred and haircut: `:deferred-and-haircut`;
2. positive deferred: `:deferred`;
3. positive haircut: `:haircut`;
4. requested equals filled: `:full-fill`;
5. filled is zero: `:zero-filled`;
6. otherwise: `:partial-fill`.

### Round lifecycle

`round-lifecycle-root` is the `ROUND_LIFECYCLE_V1` root of the complete derived
round-lifecycle projection. The projection must include exactly the lifecycle
fields currently used by the statement producer: assurance,
cancellation-blocking-reasons, cancellation-possible, cancellation-window,
cancellation-window-schema, derived-state, evidence-status,
lifecycle-assertion-status, lifecycle-profile-id, lifecycle-profile-version,
and round-state. A non-passing lifecycle assertion rejects the input.

### Statement

The exact `REALIZED_ALLOCATION_STATEMENT_V1` preimage is:

```clojure
{:schema-version "realized-allocation-statement.v1"
 :allocation-context-root <64 lowercase hex>
 :request-set-root <64 lowercase hex>
 :allocation-policy-root <64 lowercase hex>
 :realized-results-root <64 lowercase hex>
 :fail-action-policy-root <64 lowercase hex>
 :round-lifecycle-root <64 lowercase hex>}
```

No `:statement/root`, `:statement/all-active?`, diagnostic equality, scenario
evidence, native realization, proof, or admission field is included in this
preimage.

## Witness boundary

`realized-allocation-statement.v1` commits realized rows but does **not** define
or root a standalone largest-remainder witness body. The quota, floor,
remainder, and tie-break outcome are independently reconstructible from the
request set and available amount; they are not an additional committed artifact
in this statement version.

Therefore the current external package must call these values a *derived audit
trace*, not a committed witness. A future published witness requires a new,
explicit schema, projection, domain tag, root, and statement version or
additive external-conformance profile. Implementers MUST NOT invent a witness
schema and claim it is committed by `realized-allocation-statement.v1`.

## Detailed conformance projection

The native kernel accepts `--detailed` as an additive conformance/diagnostic
mode. It preserves the compact statement fields and adds
`realized-partial-fill-details.v1` containing sorted realized rows, claim order,
conservation totals, supported-profile identification, and a repeated
committed-root inventory. These details are observations and are not additional
statement-root inputs. The default invocation remains compact and byte-stable.

The detailed mode does not claim to expose a committed rounding witness; the
witness boundary above remains authoritative.

## Conformance comparison

The compact public expected-output files compare the six roots, statement root,
schema version, status, and all-active flag. A detailed conformance projection,
when added, must expose the committed body for each root above and may expose a
non-committed derived audit trace labelled as such.
