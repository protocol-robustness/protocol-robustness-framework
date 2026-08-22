# RESUBMISSION TRANSITION SPEC V1

Status: Active V1

## 1. Purpose

Define the deterministic state-transition contract for a single resubmission
family chain: the derivation from canonical state-before through a canonical
command to canonical state-after, the pinned projection over which state roots
are computed, and the transaction-ordering evidence that binds the transition.

A conformant implementation MUST reproduce every root and canonical-byte hex
in the companion fixtures under `etc/conformance/fixtures/`:

- `resubmission-transition-v1.edn` — successful genesis admit (chain-state-projection.v1 v1)
- `resubmission-transition-rejection-v1.edn` — rejected duplicate-content submission (state unchanged)
- `resubmission-transition-disposition-v1.edn` — committed `:final` disposition (state-after includes `:chain/disposition-status-by-receipt`)

## 2. Registered Domain Tags

All domain tags are registered in `resolver-sim.hash.canonical/domain-tags`
and validated prefix-free at namespace load time.

| Symbol | Domain Tag String | Used For |
|--------|-------------------|----------|
| `:prf-resubmission-chain-state-v1` | `prf.resubmission-chain-state.v1` | State root projection |
| `:prf-transaction-effects-v1` | `prf.transaction-effects.v1` | Effects root |
| `:prf-transaction-input-v1` | `prf.transaction-input.v1` | Command input root |
| `:prf-transaction-ordering-v1` | `prf.transaction-ordering.v1` | Ordering v1 identity hash |
| `:prf-transaction-ordering-v2` | `prf.transaction-ordering.v2` | Ordering v2 identity hash |
| `:prf-transaction-ordering-change-identity-v1` | `prf.transaction-ordering-change-identity.v1` | Change identity hash |

## 3. Chain-State Projection (chain-state-projection.v1)

The projection is identified by `transition/chain-state-projection-schema` =
`"chain-state-projection.v1"`. It is an internal projection of the transition namespace.
Implementations MAY expose the projection for diagnostic purposes, but the
canonical state root binds only to the projected fields regardless of whether
the projection itself is publicly surfaced.

### 3.1 Required Projected Fields (always present, sorted by encoded-byte ordering)

1. `:chain/family-id`
2. `:chain/version`
3. `:transaction/commit-index`
4. `:chain/head`
5. `:chain/successor-by-parent`
6. `:chain/effective-disposition-by-receipt`
7. `:chain/disposition-head-by-receipt`
8. `:chain/idempotency-index`
9. `:chain/content-index`

### 3.2 Conditional Projected Field

`:chain/disposition-status-by-receipt` is included ONLY when the source state
contains the key (i.e., `(contains? state :chain/disposition-status-by-receipt)`
is true). When present, the field's value is projected verbatim. This field is
absent in states produced by `admit-child` (only `apply-disposition` sets it).

### 3.3 Excluded Source Fields

The following source-state keys MUST NOT appear in the projection, even if
present in the source state:

- `:transaction/last-hash` — the ordering hash itself (including it would
  create a cycle, since the ordering hash is computed over the state-after root)
- `:chain/attempt-receipts` — signed receipt artifacts committed by receipts
  (the receipt commits the ordering hash, not vice-versa)
- `:chain/disposition-public-hex` — trusted chain configuration
- Any other key not listed in §3.1 or §3.2

### 3.4 Absent-vs-Empty Semantics (v1, preserved)

- **Absent** `:chain/disposition-status-by-receipt` (key not in source state)
  → the key is omitted from the projection.
- **Present-but-empty** `{:chain/disposition-status-by-receipt {}}`
  → the key IS included in the projection with value `{}`.

These are intentionally distinct semantic states and produce different state
roots. This behavior is v1-stable. Normalizing absent to empty (or vice-versa)
would alter roots and is deferred to a potential v2 migration.

### 3.5 State Root

```
state-root = "sha256:" + SHA256(
    "prf.resubmission-chain-state.v1" || canonical-bytes(chain-state-projection(state))
)
```

## 4. Derivation Contract

```
derive(
    canonical-state-before,   ;; chain-state-projection(state-before) is NOT included;
                              ;; the full state-before is the input, the projection is the root preimage
    canonical-command,        ;; {:transaction/action, :transaction/input}
    applicable-semantic-context  ;; expected/observed chain version, disposition head
) ->
    { canonical-state-after,
      effects,
      outcome }               ;; :committed | :rejected | :idempotent-replay

root(project-state(canonical-state-after))
    -> state-after-root
```

### 4.1 Verification Equations

```
root(project-state(canonical-state-before))  == claimed-state-before-root
input-root(canonical-command.action, canonical-command.input) == claimed-input-root
derive(...) == expected transition result
root(project-state(canonical-state-after))   == claimed-state-after-root
effects-root(effects)                        == claimed-effects-root
```

### 4.2 Why Change Identity and State-Before Root Are Not Executable Inputs

The change-identity (`prf.transaction-ordering-change-identity.v1`) is derived
from only `{scope, conflict-key, action, input-root}`. It does NOT contain:

- The canonical state-before value or its root (change-identity's basis contains neither)
- The canonical command payload (only the input-root, not the input)
- Any semantic context (chain version, expected head)

Therefore, `derive(change-identity, state-before-root)` cannot produce
`state-after-root`. The change identity and state-before root are **commitments**
that bind a transition to its inputs and result, not executable inputs
themselves.

### 4.3 Why Ordering-Chain Linkage Is Not Transition Verification

The ordering chain (`previous-transaction-hash`, `state-before-root` →
`state-after-root` fixed point) proves **positional continuity**: each
ordering record correctly references the prior ordering and commits the
state-before root it observed. It does NOT prove that the state-after root was
correctly derived from the command — an implausible but valid-looking ordering
could commit roots computed from an incorrect transition. Independent
re-derivation of the transition is required for transition verification.

## 5. Transaction Ordering Evidence

For a v2 ordering record, the unsigned projection includes:
- `:transaction-ordering/schema` — `"transaction-ordering.v2"`
- `:transaction/action` — namespaced action keyword
- `:transaction/scope` — scope keyword
- `:transaction/conflict-key` — vector
- `:transaction/commit-index` — integer (chain position)
- `:transaction/previous-transaction-hash` — `"sha256:..."` or `nil` (chain origin)
- `:transaction/state-before-root` — `"sha256:..."`
- `:transaction/state-after-root` — `"sha256:..."`
- `:transaction/effects-root` — `"sha256:..."`
- `:transaction/expected` — map (concurrency preconditions)
- `:transaction/observed` — map (concurrency preconditions)
- `:transaction/input-root` — `"sha256:..."`
- `:transaction/change-identity` — `"sha256:..."` (derived from input-root + scope + conflict-key + action)

The `:transaction-ordering/hash` is computed as:
```
ordering-root = "sha256:" + SHA256(
    "prf.transaction-ordering.v2" || canonical-bytes(unsigned-ordering-projection-v2)
)
```

The unsigned projection excludes ONLY `:transaction-ordering/hash` itself.

## 6. Input Root

```
input-root = "sha256:" + SHA256(
    "prf.transaction-input.v1" || canonical-bytes(canonical-command.input)
)
```

Concurrency guards (`sequence`, `expected-chain-version`, `expected-disposition-head`)
are excluded from the input projection. Only the substantive command payload
participates in the input-root. The same command applied at different chain
positions or under different observers yields the same input-root.

## 7. Effects Root

```
effects-root = "sha256:" + SHA256(
    "prf.transaction-effects.v1" || canonical-bytes(vec(effects))
)
```

Rejected transitions produce no effects (the effects vector is absent, not empty).
Committed `admit-child` transitions produce 6 effects; committed
`apply-disposition` transitions produce 1 effect. The effects-root is nil for
rejected and idempotent-replay transitions.

## 8. Change Identity

```
change-identity = "sha256:" + SHA256(
    "prf.transaction-ordering-change-identity.v1" || canonical-bytes(
        {scope, conflict-key, action, input-root}
    )
)
```

The change-identity is positional-invariant: it does not depend on `commit-index`,
`previous-transaction-hash`, or any state root. The same logical change retains
the same identity across resequencing.

## 9. Semantic Context

The semantic context is applicable reader-supplied data that does NOT
participate in the command's input-root or change-identity:

- `:transaction/expected` — caller's claimed concurrency preconditions
  (`{:chain-head <root>, :chain-version <n>}` for admit-child;
  `{:disposition-head <root>, :chain-version <n>}` for apply-disposition)
- `:transaction/observed` — the actual preconditions read from state-before

These fields appear in the ordering-v2 projection but are absent from the
change-identity basis. A mismatch between expected and observed triggers
commit contention at the store layer, not at the transition layer.

## 10. Conformance Fixtures

Each fixture is a self-contained EDN map that pins the exact bytes and roots an
independent implementation must reproduce. No field in any fixture is generated
by the same production call that the corresponding test invokes — fixtures are
committed to disk and loaded independently.

### 10.1 Successful Transition (`resubmission-transition-v1.edn`)

The genesis `admit-child` from the empty chain state (family `sha256:FAM`).

> **Note:** Identifiers such as `sha256:FAM`, `sha256:R1`, `sha256:B1`,
> `sha256:L1`, `sha256:I1`, and `sha256:L2` are **synthetic placeholder
> hashes** — short ASCII tokens that exercise the hash-reference type but
> do NOT correspond to real content roots. Only the pinned 64-hex-character
> roots (listed in the tables below) are semantically meaningful for
> conformance.

| Field | Value |
|-------|-------|
| state-before-root | `sha256:53e5ae09087f3733a54110c9a00f4cb227894f18f1384b7a8d88a929e5b66ffb` |
| state-after-root | `sha256:7e117371e6db8c6c4eddc156b5e705f7e3c20a0b26b9cc3a5941275868d6f835` |
| effects-root | `sha256:11d1667ba25d3668ce8ab60df7780e58eba02b608d7da00ed7c5f28d539114bf` |
| input-root | `sha256:bd649f097886f66ec57bd741426e3e2601fe776912985520047ab106eb80ce1f` |
| change-identity | `sha256:e90ed912a81c7df53f95b7e1c8a97f85a184311946126957015c05ae09e35434` |
| ordering-root | `sha256:8403bb1d1abeee127481349767cdcaa103cb3b2ffb51be51628bf98be150f610` |

### 10.2 Rejected Transition (`resubmission-transition-rejection-v1.edn`)

A second `admit-child` with the same content-key (`sha256:B1`) and same parent
(`nil`), submitted against the genesis state. The transition is rejected as
`:duplicate-content-submission` (rejection precedence 3, checked before
stale-head rejection at precedence 6).

| Field | Value |
|-------|-------|
| state-before-root | `sha256:7e117371e6db8c6c4eddc156b5e705f7e3c20a0b26b9cc3a5941275868d6f835` |
| state-after-root | identical (state unchanged) |
| effects-root | nil |
| input-root | nil |
| ordering-root | nil |
| rejection reason | `:duplicate-content-submission` |
| public-result | `{:existing "sha256:R1"}` |

### 10.3 Disposition Transition (`resubmission-transition-disposition-v1.edn`)

A `:final` disposition applied to receipt `sha256:R1`, signed with a deterministic
Ed25519 keypair (seed pinned in `:fixture/disposition-authority/private-key-seed`).

> **Security note:** The deterministic Ed25519 keypair is test-only. Its private
> key seed is committed to the fixture so that the disposition signature is
> reproducible across implementations and CI runs. It MUST NOT be used outside
> conformance fixtures or with any value-bearing chain.

| Field | Value |
|-------|-------|
| state-before-root | `sha256:7e117371e6db8c6c4eddc156b5e705f7e3c20a0b26b9cc3a5941275868d6f835` |
| state-after-root | `sha256:2f33d2a84c48ae66fa0701247d5af170f90cefe4a3c187cb8e9f9399206bad51` |
| effects-root | `sha256:87c107e35be23fcedcabf434d720c847ef84004c5854ba6b302ff30993761cff` |
| input-root | `sha256:29ee2ec01bdf0411c37defd199cae37042570bd04fcf8b911d679558cdaf968f` |
| change-identity | `sha256:216837834ac814edc93060d29a2f344bed17aef3d8fdd82220f642330f88e3c1` |
| ordering-root | `sha256:270753de77c3df854838e0d2ff28d22e5516b6dba43e4436c23f3700deef27e4` |
| disposition public-key-hex | `48f898ce850f83b9e66ed1535b6b197db081f71127786d19fc418ad3b7acbf4d` |

The state-after includes `:chain/disposition-status-by-receipt {"sha256:R1" :final}`,
which is included in the state-after projection via the conditional field rule (§3.2).

## 11. Language-Neutral Transition Input Contract

The transition input schema is defined in EDN and must be parsed by any
language implementation. The following ambiguities are resolved as normative
contract requirements, independent of any specific implementation language:

### 11.1 Keyword vs. String Encoding

In the canonical encoding, a value that appears as a keyword in the fixture
EDN (e.g., `:final`) MUST be encoded with the keyword tag (`0x22`), not the
string tag (`0x20`), even when the host EDN parser is untyped. Every field
value in the fixture maps is either a string (quoted, `"..."`) or a keyword
(unquoted, `:foo`). Implementations MUST preserve this distinction when
projecting canonical bytes.

### 11.2 Qualified vs. Unqualified Keywords

The transition parser MUST resolve field keys by exact match. For a
namespaced map context (`#:ns{...}`), an unqualified key `:key` is resolved
to `Keyword(Some("ns"), "key")`. A standalone `:key` is resolved to
`Keyword(None, "key")`. The parser MUST NOT apply implicit aliases or
fuzzy matching across namespace boundaries.

Clojure EDN supports both `#:namespace{:key value}` (namespaced maps that
auto-qualify unqualified keys) and explicit `:ns/name` (qualified keywords).
These produce different `Value::Keyword` variants:

| EDN syntax | Parsed as |
|---|---|
| `:name` (inside `#:ns{...}`) | `Keyword(Some("ns"), "name")` |
| `:ns/name` (standalone) | `Keyword(Some("ns"), "name")` |
| `:name` (standalone, no `#:`) | `Keyword(None, "name")` |

**Key example:** The signature sub-map inside a disposition artifact uses
unqualified `:signature` as the key name (inside the enclosing
`#:attempt-disposition{...}` context), yielding
`Keyword(Some("attempt-disposition"), "signature")`. It is NOT
`:signature/signature`. Implementations MUST NOT assume the key is namespaced
as `:signature/signature`; a lookup by the bare name `"signature"` within the
sub-map context MUST match, while a lookup by `"signature/signature"` MUST NOT
match.

In the disposition fixture, the signature sub-map uses unqualified `:signature`
as the key name (inside `#:attempt-disposition{...}`), while the algorithm
uses `:signature/algorithm`. Both are qualified with namespace `"signature"`:

- `:signature/algorithm` → `Keyword(Some("signature"), "algorithm")`
- `:signature` → qualified to `Keyword(Some("attempt-disposition"), "signature")` by the enclosing `#:attempt-disposition` context. Its VALUE is a map with `:signature/algorithm` and `:signature` (unqualified).

The `map_get` function matches by the full qualified string `"ns/name"` via
the second rule, OR by bare name via the first rule. Both must be checked
in order.

### 11.3 Absent vs. Empty Disposition Maps

(See §3.4 for the v1-stable semantics.) The disposition fixture's state-before
has `:chain/disposition-public-hex` set to the authority key, while the genesis
state-after has it as `nil`. Because `disposition-public-hex` is EXCLUDED from
the chain-state projection, both states produce the same state root. This
allows the genesis state-after to be reused as the disposition state-before
without altering the root.

### 11.4 Derived Change-Identity Inclusion

The `:transaction/change-identity` field is NOT supplied by the caller. It is
derived from `{scope, conflict-key, action, input-root}` via
`prf.transaction-ordering-change-identity.v1` and injected into the
ordering-v2 projection BEFORE the ordering hash is computed. Independent
implementations must compute this value and include it as
`:transaction/change-identity` in the unsigned projection.

### 11.5 Dependency Chain: Unsigned Disposition → Hash → Input Root → Change Identity → Ordering Root

For `apply-disposition`:

1. `disposition-artifact-hash` = `SHA256("prf.attempt-disposition.v1" || canonical-bytes(artifact \ {signature}))`
2. `input-root` = `SHA256("prf.transaction-input.v1" || canonical-bytes({attempt-receipt-hash, disposition-artifact-hash}))`
3. `change-identity` = `SHA256("prf.transaction-ordering-change-identity.v1" || canonical-bytes({scope, conflict-key, action, input-root}))`
4. `ordering-root` = `SHA256("prf.transaction-ordering.v2" || canonical-bytes(unsigned-ordering-projection-v2))`

The ordering root transitively depends on the disposition-artifact-hash through
the input-root and change-identity. Tampering any upstream component changes
all downstream roots.

### 11.6 Consecutive State/Ordering Linkage

The two committed fixtures (`resubmission-transition-v1.edn` and
`resubmission-transition-disposition-v1.edn`) describe consecutive
transitions on the same chain:

- **Family identity:** Both use `sha256:FAM`.
- **Genesis state-after root = disposition state-before root:** `sha256:7e117371e6db8c6c4eddc156b5e705f7e3c20a0b26b9cc3a5941275868d6f835`.
- **Version progression:** genesis 0→1, disposition 1→2.
- **Commit-index progression:** genesis 0→1, disposition 1→2.
- **Scope and conflict-key:** Both use `:resubmission-family` and
  `[:resubmission-family "sha256:FAM"]`.
- **`previous-transaction-hash`:** Both are `nil` in the fixture ordering
  projections. This is because `:transaction/last-hash` is EXCLUDED from
  the chain-state projection (§3.3), so it does NOT propagate through state
  roots. In a real store, the store would set `last-hash` after each
  committed transition; the ordering record's `previous-transaction-hash`
  is derived from `state[:transaction/last-hash]`, not from the state root.
- **Authority context:** The disposition fixture injects the authority public
  key (`48f898ce...`) into `:chain/disposition-public-hex` on the
  state-before. Since this field is excluded from the projection, it does
  not alter the state root. The signature is verified against this
  state-level key, not against an arbitrary caller-supplied key.

## 12. Concurrency Checks

Two concurrency guards are enforced by `apply-disposition` (and by
`admit-child` for genesis):

1. **`expected-chain-version`:** If present in the command input, the
   state's `:chain/version` must match. A mismatch produces
   `:rejected` with reason `:commit-contention` — an explicit
   rejection, not merely a root mismatch.

2. **`expected-disposition-head`:** If present and non-nil in the command
   input, the state's current disposition head (from
   `:chain/disposition-head-by-receipt`) for the target receipt must
   match. A mismatch produces `:rejected` with reason
   `:disposition-head-mismatch`.

These checks are enforced inside the transition function itself, not at the
store layer. Independent implementations MUST reproduce this rejection
behavior.

## 13. Reproducibility Contract for Independent Implementations

An independent implementation (e.g., Rust, Go, Haskell) must:

1. **Read the fixture EDN files** from `etc/conformance/fixtures/`.
   2. **Reproduce the chain-state projection**: apply the 9 required fields in
      encoded-byte ordering, include the conditional disposition-status field only when
      the source state contains the key, and exclude all other source keys.
3. **Compute state roots**: `SHA256("prf.resubmission-chain-state.v1" || canonical-bytes(projection))`.
4. **Compute input roots**: `SHA256("prf.transaction-input.v1" || canonical-bytes(command.input))`
   with concurrency guards excluded.
5. **Compute effects roots**: `SHA256("prf.transaction-effects.v1" || canonical-bytes(vec(effects)))`.
6. **Compute change-identity**: `SHA256("prf.transaction-ordering-change-identity.v1" || canonical-bytes({scope, conflict-key, action, input-root}))`.
7. **Compute ordering roots**: `SHA256("prf.transaction-ordering.v2" || canonical-bytes(unsigned-v2-projection))`.
8. **Verify dispositions**: Ed25519 verify the signature over
   `canonical-bytes(unsigned-disposition-projection)` against the pinned public key.

All canonical encoding follows the Clojure `pr`-based canonical-bytes algorithm
defined in `resolver-sim.hash.canonical`. Integer and string types are encoded as
UTF-8. Keywords are encoded as their string representation (e.g., `:foo` → `":foo"`).
NIL values are encoded as `0x00`. Maps are sorted by encoded-byte ordering of their canonical key bytes.
