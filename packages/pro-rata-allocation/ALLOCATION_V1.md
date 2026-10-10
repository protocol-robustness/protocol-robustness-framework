# Pro-rata Allocation V1

This package-local profile is an exact extraction of section 4 from the frozen
[portable-pro-rata-contract.v1 specification](../../portable-pro-rata-contract/SPEC.md).
The aggregate specification and its release root remain unchanged. This file
adds no schema, domain, or semantic identity. The generic canonical value
encoding remains owned by `canonical` and follows
[canonical-hash-scheme.v1](../../docs/specs/evidence/CANONICAL_HASH_SPEC_V1_BINARY_ENCODING_ABI.md).

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
