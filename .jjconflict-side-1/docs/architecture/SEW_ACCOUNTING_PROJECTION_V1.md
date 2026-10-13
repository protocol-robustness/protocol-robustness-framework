# Sew Accounting Projection V1

## Status and scope

This record defines the canonical, deterministic projection that future Sew
accounting invariants, accounting evidence, and proof-oriented consumers must
share. It is a projection specification, not a proof program and not an
external solvency attestation.

Runtime projection construction and validation are implemented. Scenario worlds
remain fail-closed by default; callers that have the required committed inputs
must explicitly attach them before requesting derivation.

## Projection identity

The projection is identified as:

```text
sew/accounting-projection.v1
```

Its canonical root is a domain-separated SHA-256 commitment over the semantic
projection body, excluding the root field itself. The body is closed: unknown
fields, unknown bucket names, duplicate rows, malformed roots, and ambiguous
asset mappings are invalid.

Conceptual shape:

```clojure
{:accounting-projection/schema "sew/accounting-projection.v1"
 :accounting-projection/scope :sew
 :accounting-projection/cutpoint {...}
 :accounting-projection/source-roots {...}
 :accounting-projection/completeness {...}
 :accounting-projection/assets [...]
 :accounting-projection/summary {...}
 :accounting-projection/root "sha256:..."}
```

The exact hash intent and field registry must be added with the runtime
implementation. The root must be computed from canonicalized fields and must
not depend on map iteration order.

## Cutpoint

The projection must bind all accounting facts to one explicit state boundary.
The cutpoint is a closed record, not a free-form label:

```clojure
{:chain-id 8453
 :block-number 123
 :block-hash "0x..."
 :world-root "sha256:..."
 :execution-root "sha256:..."}
```

Fields are environment-dependent, but the selected cutpoint semantics must be
declared and committed. A projection must not combine assets, liabilities,
claimables, or history from different state boundaries without an explicit
composed observation contract.

At minimum, the implementation must bind the projection to a deterministic
world/state root or an equivalent exact execution cutpoint. If a field is not
available in a simulation environment, the projection must use an explicit
environment-specific cutpoint mode rather than silently omit the boundary.

## Source roots

Source roots identify the authoritative inputs from which the projection was
derived. The initial source-root vocabulary is:

| Field | Meaning |
|---|---|
| `:accounting/ledger-root` | Canonical held-adjustment history covered by the projection. |
| `:accounting/claimable-root` | Canonical claimable and withdrawal source state. |
| `:accounting/fee-root` | Fee accrual and fee-withdrawal source state. |
| `:accounting/yield-root` | Yield and deferred-yield source state, where in scope. |
| `:accounting/bond-root` | Bond custody and terminal-disposition source state, where in scope. |
| `:accounting/asset-registry-root` | Mapping from legacy token inputs to canonical asset identities. |
| `:accounting/transition-root` | Authoritative transition/effect history, if used by the implementation. |

Each included source must be either a canonical SHA-256 reference or an
explicitly declared empty source. A missing source is not equivalent to an
empty source. Sources outside the declared accounting scope must not be
silently ignored; they must be excluded by a committed scope definition.

## Completeness

Completeness is part of the projection body:

```clojure
{:status :complete | :incomplete
 :from "sha256:..."
 :through "sha256:..."
 :covered-sources #{:ledger :claimable :fees}
 :missing-sources []
 :reason nil}
```

Rules:

- `:complete` requires every source declared in the projection scope and a
  continuous covered range from `:from` through `:through`.
- `:incomplete` must identify missing sources or gaps in `:reason` and
  `:missing-sources`.
- Missing completeness metadata is invalid, not complete.
- An incomplete projection may be reported for diagnostics but cannot produce
  a positive conservation result.
- Completeness does not mean the identity holds; it only establishes that the
  required evidence boundary was covered.

## Scenario integration

The runtime helper `with-accounting-metadata` attaches the required metadata to
a Sew world. It requires:

```clojure
(projection/with-accounting-metadata
 world
 {:assets {legacy-token asset-identity}
  :source-roots source-roots
  :world-root world-root
  :from from-root
  :through through-root
  :complete? true})
```

The `:assets` mapping is explicit and keyed by the exact legacy token keys used
by the world. No token symbol, address, chain, or spelling is inferred. A
missing mapping, source root, world root, or completeness declaration leaves
the public `accounting-projection-v1` target non-evaluable rather than
producing a guessed projection. The ordinary `empty-world` constructor does
not add accounting commitments automatically.

## Per-asset rows

Each asset row is keyed by the canonical asset identity from
`SEW_ASSET_IDENTITY_V1.md`, preferably by its `:asset/root`:

```clojure
{:asset/root "sha256:..."
 :asset/identity {...}
 :inflows {:external-funding 1000}
 :outflows {:release-withdrawals 300
            :refund-withdrawals 0
            :fee-withdrawals 5}
 :held {:active-principal 500
        :release-claimable 195
        :refund-claimable 0
        :accrued-protocol-fees 0
        :bond-obligations 0
        :yield-custody 0
        :explicit-reserves 0
        :other-modeled-custody {} }
 :adjustments {}
 :conservation {:inflows 1000
                :outflows 305
                :held 695
                :difference 0
                :holds? true}}
```

The row is a derived accounting view. It must not accept caller-supplied
summary totals as authoritative. The implementation recomputes totals from
the bucket fields and rejects mismatches.

## Conservation result

For every asset row:

```text
sum(inflows)
  = sum(outflows)
  + sum(current held buckets)
```

The projection must evaluate this identity independently for every asset. It
must never aggregate across asset roots, symbols, addresses, or chains.

The result is a diagnostic field derived from the row. The canonical projection
root commits the row and result, but a root alone does not imply a pass: callers
must also verify schema, completeness, source roots, cutpoint, and the
recomputed conservation result.

## Summary

The summary may contain derived totals and status, but it must remain
recomputable from the asset rows:

```clojure
{:asset-count 1
 :complete? true
 :conservation-status :pass | :fail | :not-evaluated
 :difference-by-asset {"sha256:..." 0}}
```

No system-wide numeric total may be used to hide a per-asset mismatch.

## Separation from solvency projections

This projection is not a giant solvency state. Future solvency work should
produce independently committed liability and asset/custody projections that
reference the same cutpoint and asset roots. A later solvency assessment may
bind:

```text
cutpoint
liability-root
asset/custody-root
policy-root
result
```

The accounting projection establishes conservation and represented custody
state; it does not establish recoverability, valuation, or sufficiency.

## Determinism and validation requirements

Runtime implementation tests:

- equivalent map orderings produce the same projection root;
- equivalent legacy token spellings resolve to the same mapped asset root;
- different chains or addresses never share an asset bucket;
- missing or malformed source roots fail closed;
- missing completeness metadata cannot produce `:complete`;
- incomplete history cannot produce `:pass`;
- derived summaries and conservation results reject tampering;
- a conserving projection can coexist with a solvency failure;
- a non-conserving projection cannot be promoted by a solvency pass.

## Explicitly deferred

This specification does not add SP1 circuits, Merkle reserve trees, RPC
attestations, oracle valuation, native-asset encoding, or proof generation.
Those mechanisms may consume this projection after its runtime derivation and
validation are implemented.
