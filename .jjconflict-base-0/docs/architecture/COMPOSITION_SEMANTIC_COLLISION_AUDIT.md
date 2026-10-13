# Composition Semantic Collision Audit — DS4

Status: Audit (analysis, no code change)
Scope: meanings, not implementation
Series: Terra checkpoint candidate 2

This audit compares the **meanings** of nine terms across the composition
systems, produces a semantic truth table, re-proves that `bound-sequence` is a
*canonical framing primitive* (and must stay one), and decides whether the
intended future algebra (`combine` / `concatenate` / `consecutive`) already
exists faithfully or needs a Composition V2 / narrower kernel.

## 1. The four distinct composition systems

Before comparing terms it is essential to separate the systems. The codebase
does **not** have one "composition" concept; it has four, deliberately
domain-separated by hash domain tags:

| System | Namespace(s) | Domain tag | Owns |
|--------|-------------|-----------|------|
| A. Capability composition compiler | `composition/combination`, `composition/contract`, `composition/plan`, `composition/compiler`, `composition/execution` | `COMPOSITION_COMBINATION_V1`, `COMPOSITION_CONTRACT_V1`, `COMPOSITION_PLAN_V1` | request → compiled plan → execution |
| B. Semantic composition V1 | `composition/v1`, `composition/semantic` | `SEMANTIC_COMPOSITION_V1` | canonical identity of a run's operational semantics |
| C. CC3 command lineage (frozen) | `composition/command_lineage` | `PRF_COMMAND_LINEAGE_*` | command consecutiveness, combination, concatenation, termination |
| D. Hash framing layer | `hash/sequence`, `hash/canonical`, `hash/framing_view` | `CANONICAL_VALUE_SEQUENCE_V1` | bound-sequence framing; bare concatenation |

The same English word appears with **different, deliberately distinct meanings**
in different systems (notably `combination`, `concatenate`, `consecutive`,
`component`). These systems must NOT be compared directly
(`SEMANTIC_COMPOSITION_V1.md:179`). Any audit that flattens them into one column
reintroduces exactly the collision this audit exists to expose.

## 2. Per-term definitions (meaning only)

### `component`

- **System D (framing)** — `hash/sequence.clj:68-124`: an independently framed
  canonical member value bound by `:purpose`, `:component-count`, and a
  sequential `:components` vector. Membership, arity, and order are preserved by
  the encoding. The framing layer "does not assign semantics to nested
  composition trees" (`CANONICAL_HASH_SPEC_V1.md:98-101`).
- **System B (semantic sequence family)** — `composition/v1.clj:128-137`:
  "Sequence order is material; vector order is semantic." A component here is a
  *semantic identity keyword* in an ordered sequence.
- **System C / D consumers** — e.g. `evidence/confidence.clj:405-417` (bound
  confidence records bound by `:subject-hash`); `benchmark/research_command.clj:454-456`
  (components are command `:command/hash` values).

**Collision:** `component` is a framing element in D, a semantic identity in B.
The binding by identity in consumers is what lifts a framing element into
semantics — this is a *consumer decision*, not something the framing layer does.

### `compactly` / compaction

- **System B** — `composition/v1.clj:237-280`: canonical V1 compact projection.
  `validate-source` is the authoritative boundary gate deciding whether a value
  is a canonical consecutive-composition node *prior to recursive flattening*;
  `composition/v1.clj:151-167` fails closed so a lossy projection cannot
  accidentally become eligible for recursive flattening.

`compactly` is a **normalization/projection** — a canonical-shape reduction with
a semantic-boundary authority, but it is *not itself* an ordering/multiplicity
operation. It is family-sensitive: sequence family preserves order; consecutive
family encodes adjacency.

### `combination`

- **System A** — `composition/combination.clj:1-10`: the requested combination —
  INPUT to the compiler, may be invalid, deliberately distinct from the compiled
  plan. Order of `:combination/nodes` is declared and semantically significant
  (`combination.clj:85-92`).
- **System C** — `composition/command_lineage.clj:13-24`: "built-with-includes
  over a canonical member set; **order-independent**, duplicate refs rejected"
  (`command_lineage.clj:146-149`).

**Collision:** "combination" is *order-sensitive, multiplicity-relevant, a
semantic request* in System A, but *order-independent set identity* in System C.
Same word, opposite order sensitivity.

### `concatenate` / concatenation

- **System C** — `command_lineage.clj:13-24, 391-410`: consecutive concatenation
  "A then B where join-state is DERIVED as resulting-state(A) == input-state(B);
  order matters." Requires a continuity condition; join-state is never
  caller-supplied. The chain root is a *pure cryptographic commitment*; semantic
  authority comes exclusively from `verify-concatenation-chain`
  (`command_lineage.clj:30-40`).
- **System D** — `hash/sequence.clj:45-54, 56-64`: bare byte concatenation
  `encode(v1)||…||encode(vN)` — prefix-free but *unbound*; no purpose, schema,
  or count. Explicitly carries no semantic authority.

**Collision:** "concatenate" is an *order-sensitive semantic operation with a
derived continuity condition* in C, but *bare framing* in D.

### `consecutive`

- **System B** — `composition/v1.clj:139-149, 371-379`: "Consecutive means
  predecessor then successor adjacency, not byte concatenation and not a
  state-transition proof."
- **System D** — `hash/sequence.clj:1-8`; `CANONICAL_HASH_SPEC_V1.md:75-76`: a
  versioned encoding of one ordered sequence of canonical member values.

**Collision:** "consecutive" is *adjacency semantics* in B/C, *mere ordered
member encoding* in D.

### `composition-plan`

- **System A** — `composition/plan.clj:1-11`: a content-addressed artifact
  produced by the compiler; execution consumes ONLY a compiled plan. Binds
  source combination root, capability descriptor roots, contract roots, canonical
  node order and edges, input/output contracts, effect-merge semantics,
  verification contract, compiler identity/version.

`composition-plan` is a **canonical compiled representation** — it commits
semantic order/edges, but it is the *output artifact*, not a framing primitive of
arbitrary content.

### `composition-contract`

- **System A** — `composition/contract.clj:1-14`: a local, versioned declaration
  of how **ONE** capability may participate in a composition. NOT proof that a
  whole multi-capability graph is valid; graph-wide validity is the compiler's
  job. Content-addressed (`:composition-contract-root`).

`composition-contract` is a **per-capability declaration/spec**, neither framing
serialization nor itself order-sensitive (order/associativity live at plan /
compiler level).

### `command lineage`

- **System C** — `command_lineage.clj:1-45`: "Thin, domain-neutral primitive for
  command composition anchored on immutable shared-state identity. CC3 — CLOSED…
  Frozen." Keeps three identities sharp: shared-state, combination (order-
  independent), consecutive concatenation (order matters). Terminal rule:
  cancel-and-terminate after a terminal head → `:predecessor-terminal`;
  identical replay → `:already-terminated`. **Frozen** for all purposes except
  defect repair.

`command lineage` is a **semantic, order-sensitive, purpose-bound operation with
derived continuity and termination semantics**, and it is frozen.

### `bound-sequence`

- **System D** — `hash/sequence.clj:68-124`: the contract-bound value
  `{:encoding-contract :purpose :component-count :components}`, which
  `canonical-sequence-bytes` / `sequence-hash` commit. It exists precisely to
  remove the interpretation ambiguity of bare concatenation by binding purpose,
  count, and explicit component structure. `verify-sequence-commitment`
  (`sequence.clj:141-170`) re-validates the contract so a loaded commitment is a
  genuine fixed point of `bound-sequence`.

## 3. Truth table

Legend: Y = yes / order/multiplicity-sensitive or associative etc.;
`framing` / `semantic` / `decl` (declaration) = semantic relation;
`canonical` = mere canonical framing; `exec` = execution relation.
`-` = not applicable / the concept does not carry that property.

| Term | order-sensitive | multiplicity-sensitive | associative | flattened | purpose-bound | component-count-bound | semantic relation | mere canonical framing | execution relation |
|------|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| `component` (D framing) | Y (vector) | Y (count) | N | N | via bound purpose | Y | N | **Y** | N |
| `component` (B sequence family) | Y (semantic) | Y | N | N | Y | Y | **Y** | N | N |
| `compactly` (B) | Y (family-dependent) | Y | N | Y (flattens) | Y | N | boundary-gate | Y (projection) | N |
| `combination` (A request) | Y (nodes ordered) | Y | N | N | Y | Y | **Y** (request) | N | N |
| `combination` (C lineage) | N (set) | Y (duplicates rejected) | Y (set) | N | Y | Y | **Y** (set identity) | N | N |
| `concatenate` (C) | Y (A then B) | Y | N (pairwise) | N | Y | Y | **Y** (derived join-state) | N | N |
| `concatenate` (D bare) | Y (byte order) | Y | Y (byte concat) | N | N (unbound) | N | N | **Y** | N |
| `consecutive` (B/C) | Y (adjacency) | Y | N | N | Y | Y | **Y** (adjacency) | N | N |
| `consecutive` (D framing) | Y (ordered) | Y | N | N | via purpose | Y | N | **Y** | N |
| `composition-plan` (A) | Y (commits order) | Y | N | N | Y | Y | **Y** (compiled artifact) | Y (serialized) | Y (consumed by exec) |
| `composition-contract` (A) | N (per-capability) | N | N | N | Y | N | **decl** | Y (content-addressed) | N |
| `command lineage` (C) | Y | Y | N | N | Y | Y | **Y** (order+continuity+term) | N | N |
| `bound-sequence` (D) | Y (components vector) | Y | N | N | **Y** | **Y** | N | **Y** | N |

Key observations from the table:

1. **The only term whose "semantic relation" is always NO and whose "mere
   canonical framing" is always YES is `bound-sequence`.** Every other term that
   carries "semantic relation = Y" does so because a *consumer or higher system*
   assigned that meaning, not because framing created it.
2. `component` and `concatenate` and `consecutive` and `combination` each appear
   twice with **different** order-sensitivity. This is the collision. It is
   currently safe only because the systems are domain-separated by tag and
   explicitly forbidden from direct comparison.
3. `associative` is Y only for the *set* combination (C) and *bare byte*
   concatenation (D) — i.e. only for the two pure-framing/set cases. The semantic
   operations (concatenate-C, consecutive, sequence) are all N, correctly: they
   reject associativity as a collapse that would erase order/continuity.
4. `composition-plan` is the only row that is simultaneously semantic,
   canonical-framed, AND an execution input — it is a *compiled artifact*, the
   boundary where framing and execution meet.

## 4. Re-proof: `bound-sequence` = canonical framing primitive

The task asks to prove again, and specifically **not** to pull `bound-sequence`
upward into semantic composition. Proof by four properties, each checkable in
`hash/sequence.clj`:

**(P1) Self-describing but meaning-free.** `bound-sequence` commits exactly four
fields: `:encoding-contract`, `:purpose`, `:component-count`, `:components`
(`sequence.clj:121-124`). `:purpose` is a *label supplied by the caller*; the
framing layer does not interpret it, validate it against a semantic domain, or
derive continuity from it. The value itself — `encode(v1)||…||encode(vN)` with a
purpose/count header — is a serialization shape, not a proposition.

**(P2) No derived-relation authority.** Contrast with C: `build-concatenation`
requires `resulting-state(A) == input-state(B)` and *derives* join-state
(`command_lineage.clj:391-410`). `bound-sequence` performs no such check; it
would frame `[A B]` even if no continuity holds. It cannot, because it has no
access to `input-state`/`resulting-state` — those are command meanings, absent
from the framing layer. Therefore `bound-sequence` cannot be the semantic
operation; the semantic authority must come from a verifier (`verify-
concatenation-chain`, `command_lineage.clj:30-40`) or a consumer
(`concatenate-bound`, `confidence.clj:548-580`).

**(P3) Purpose binding ≠ semantic composition.** The `:purpose` field exists to
*disambiguate byte streams* (the same parseable bytes could mean different
protocol objects — `sequence.clj:8-12`). It removes *interpretation* ambiguity,
not *semantic* ambiguity. Binding a purpose so `[a b]` under `:purpose :x` ≠
`[a b]` under `:purpose :y` is framing hygiene; it does not say what `a then b`
*means*. The spec is explicit: "this low-level sequence contract … does not
assign semantics to nested composition trees" (`CANONICAL_HASH_SPEC_V1.md:98-101`).

**(P4) Fixed-point verification is structural, not semantic.**
`verify-sequence-commitment` (`sequence.clj:141-170`) checks contract shape:
map shape, contract version, keyword purpose, non-negative integer count equal to
`(count :components)`, vector components, no extra keys. Every check is
*structural* — none inspects the semantic relation among components. A
commitment is a "genuine fixed point" of `bound-sequence` iff it satisfies this
structural contract, independent of what the components mean.

**Conclusion.** `bound-sequence` is a *canonical framing primitive*: a
self-describing, purpose-bound, component-counted serialization with structural
(fixed-point) verification and **zero semantic-relation authority**. Its
`associative = N` and `semantic relation = N` rows in the table are exactly the
properties that keep it a primitive.

**Why it must NOT be pulled upward:** the moment `bound-sequence` is given
semantic composition meaning (ordering-as-meaning, continuity, associativity,
flattening), it would (a) collide with B/C/`concatenate-bound` consumers that
already assign semantics, (b) lose its role as the one system-neutral framing
both B and C rely on, and (c) violate the frozen CC3 boundary that names
`command-lineage` the sole owner of "may follow" adjacency semantics
(`CHANGELOG.md`, "CC3 Design Boundary"). Semantic composition is a *consumer
layering on top of* framing, exactly as `confidence/concatenate-bound` does it —
the framing primitive stays below.

## 5. Does the intended future algebra exist faithfully?

The intended future algebra named in the task is `combine` / `concatenate` /
`consecutive`. Findings:

### `combine`
**Does not exist as an operator anywhere.** The only `combine-*` symbol is
`combine-finality` in Sew finality (`protocols_src/.../finality.clj:152`),
unrelated. There is no `combine` in the composition package, no planned
`combine`, and no doc describing a `combine` operation distinct from
`combination`.

### `concatenate` / `consecutive`
These already exist faithfully — **twice**, once per layer:

- **Semantic layer (C):** `build-concatenation` (order-sensitive, derived
  continuity), `consecutive` adjacency, and `combination` (order-independent
  set) are implemented and **frozen** in `command_lineage.clj:13-24`. The three
  identities are "kept sharp."
- **Framing layer (D):** bare concatenation and `bound-sequence` ordered framing
  exist in `hash/sequence.clj`.

But the **intended higher algebra** the task gestures at — a canonical ordered
identity over a whole valid sequence, `CC3Valid([A,B,C,…]) → canonical ordered
identity`, plus the future injectivity layer (`prop-consecutive-injective`:
no-collapse / no-alias / no-reuse) — is **documented as future, not
implemented**:

- `CHANGELOG.md:361`: "Nested/recursive consecutive composition … Not yet
  implemented. CC3 currently supports only flat adjacent pairs."
- `command_lineage.clj:7-11`: frozen pending `prop-consecutive-injective`,
  which "must add a distinct no-collapse / no-alias / no-reuse condition" and
  must be designed *downstream* of these semantics.
- `test/resolver_sim/hash/concat_properties_test.clj:299,310,406`: `prop-
  consecutive-injective` and the `:consecutive-composition` purpose are *defined
  in the property harness* but are not a production algebra.
- `ADR-0005:549-551`: sequential steps are a typed pipeline; a future DAG can be
  added without redefining extension.

### Verdict

- The **pairwise/flat** operators `concatenate` and `consecutive` and the
  **set** `combination` **already exist faithfully** in CC3 (frozen) and in the
  framing layer (D). No new kernel is required for these.
- The **future algebra** — nested/recursive consecutive composition, the whole-
  sequence canonical ordered identity, and the injectivity/no-collapse/no-alias/
  no-reuse layer — **does NOT yet exist** and is explicitly deferred until the
  CC3 boundary is confirmed.
- **No "Composition V2" and no "narrower kernel" exist or are named** anywhere
  in the corpus. The semantic composition schema is explicitly `v1` with no v2
  (`SEMANTIC_COMPOSITION_V1.md:37-38`; `v1.clj:45-47`).

### Recommendation

Do **not** build a "Composition V2 / narrower kernel" reflexively. The faithful
path already laid out in the codebase is:

1. Keep `bound-sequence` as the framing primitive (never pull it up).
2. Keep CC3 frozen as the sole owner of flat adjacency semantics.
3. When there is an actual consumer need, add the deferred *downstream* layer —
   `prop-consecutive-injective` (no-collapse / no-alias / no-reuse) and the
   canonical ordered identity over a whole valid sequence — as a **new domain-
   separated layer**, not a rename/reinterpretation of `bound-sequence` or a
   V2 that breaks the frozen CC3 semantics.

Only a concrete consumer need (per AGENTS.md's frozen-boundary rule) justifies
that layer; symmetry or "completeness" of the algebra is not a sufficient reason.

## 6. Cross-repo corroboration: `~/Code/prf-clean-room`

This audit was re-run against the independent clean-room implementation
(`~/Code/prf-clean-room`), which was deliberately written **without referencing
`composition/v1.clj`** (the main repo's own `v1.clj:81` states "Independently
implemented — does not reference prf-clean-room code"). It therefore acts as a
second witness on the *meanings* of these terms. Findings:

### What the clean room confirms

| Claim from this audit | Clean-room corroboration |
|---|---|
| `compactly` is a canonical framing/projection primitive, not a semantic algebra | `composition.clj:282-294`: "**No global algebraic law is implied.**" Compaction maps identical contexts to a uniform rule and `floor-and-carry → largest-remainder`, but keeps population/cardinality out of the root. |
| `concatenate` is order/multiplicity-sensitive representation, **not** adjacency | `ordered_concatenation.clj:27`: "does not, by itself, establish that those components are consecutive or semantically compatible"; catalogue `:not-confused-with [:consecutive :bare-canonical-byte-concatenation]`. |
| `consecutive` is adjacency, distinct from concatenation and from byte concat | `composition.clj:252-265` (predecessor/successor family); catalogue `:not-confused-with [:concatenate]`; `consecutive_composition.clj:31`. |
| raw byte concatenation is not a framing construction | `research.clj:186-189`; conformance canary `ordered-concatenation-v1.edn:25` (`["ab" "c"]` vs `["a" "bc"]` collide unframed, differ framed). |
| `associative` is false for the semantic ops | clean room asserts no associative/commutative algebra anywhere. |
| `bound-sequence` is a framing primitive (framing disambiguates bytes, not meaning) | clean room has **no** `bound-sequence`, but its equivalent framing is `canonical-bytes` (Canonical ABI V1, element count + ordered elements) + domain-tagged SHA-256 (`composition.clj:57,75`) — the same role, reached independently. |
| No "Composition V2", no "narrower kernel" | absent in clean room; only V1 (`schema-version` always `1`, `composition.clj:88-90`); "narrow" appears only as capability *containment*, unrelated to a composition kernel. |

### Where the clean room is thinner

The clean room does **not** contain the Systems A/D artifacts that the main repo
adds on top of the pure semantics:

- **No `bound-sequence` / `canonical-value-sequence`** by name — its framing is
  the ABI encoder directly. It lacks the main repo's explicit purpose/count
  binding layer (`hash/sequence.clj`) and the fixed-point verifier
  (`verify-sequence-commitment`). So the "bound-sequence as framing primitive"
  proof's *data* (P1–P4) lives only in the main repo; the clean room corroborates
  the *conclusion* (framing is byte-disambiguation, not semantic authority) but
  has no `bound-sequence` to prove against.
- **No `composition-plan` / `composition-contract`** (System A) — the compiler,
  plan, and per-capability contract are absent. These are main-repo-only.
- **No CC3 `command-lineage`** with concatenation-chain/termination — the clean
  room's "lineage" is `capability-lineage` (path-local conservation, a different
  meaning of "lineage"), and `:command-lineage` appears only as a catalogue
  domain-tag label for `:cancel-and-terminate`.

### Net effect

The clean room **independently confirms the semantic boundary** at the heart of
this audit: `concatenate` ≠ `consecutive` ≠ raw byte concatenation; `compactly`
is a projection with no global algebraic law; and framing (whatever its concrete
encoding — `bound-sequence` in the main repo, Canonical ABI in the clean room)
exists to disambiguate bytes, not to carry semantic authority. It offers **no**
support for a "Composition V2" or "narrower kernel": both repos point forward to
*separate future identity boundaries* (clean room: `input-root`, `code-root`,
`result-root`, `evidence-root`; main repo: `prop-consecutive-injective`,
nested/recursive consecutive composition) rather than to a version bump of the
composition schema.

## 7. Files referenced

- `src/resolver_sim/hash/sequence.clj` (bound-sequence framing, P1–P4)
- `src/resolver_sim/composition/command_lineage.clj` (CC3, frozen; three identities)
- `src/resolver_sim/composition/v1.clj` (compactly; sequence / consecutive families)
- `src/resolver_sim/composition/combination.clj` (requested combination)
- `src/resolver_sim/composition/plan.clj` (compiled plan)
- `src/resolver_sim/composition/contract.clj` (per-capability contract)
- `src/resolver_sim/composition/semantic.clj` (authoritative constructor)
- `src/resolver_sim/evidence/confidence.clj` (`concatenate-bound`, upward consumer)
- `src/resolver_sim/hash/canonical.clj` (domain-tag registry)
- `docs/specs/evidence/SEMANTIC_COMPOSITION_V1.md`
- `docs/specs/evidence/CANONICAL_HASH_SPEC_V1.md`
- `test/resolver_sim/hash/concat_properties_test.clj` (`prop-consecutive-injective`)
- `docs/architecture/ADR-0005-framework-extension-packages.md`, `ADR-0006-with-bounty-composition.md`