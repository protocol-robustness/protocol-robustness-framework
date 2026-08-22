# Fair end-to-end allocation provability — architectural contract

**Status:** architectural contract for `:claim/pro-rata-fairness-end-to-end`.

This is the contract that governs moving fair pro-rata allocation from
evidence-scoped validation toward cryptographic provability. It supersedes the
earlier demo-repo determination and incorporates five architectural changes
before the contract is treated as binding.

The source of truth for the allocation coprocessor now lives in this repo
(see [Migration decision](#migration-decision)). The former standalone
coprocessor repository is reduced to an external integration/demo consumer.

---

## 1. Scope and claim

`:claim/pro-rata-fairness-end-to-end` asserts: every claimant receives the same
fill ratio within rounding tolerance, validated via evidence-root
verifiability, invariant compliance, and complete allocation reporting.

The claim is declared in
`src/resolver_sim/benchmark/strategic_claim_validation.clj` and evaluated by
closed-form checks in `src/resolver_sim/yield/partial_fill.clj`. Until the
provability path (Change 5, below) is green, a claim `:pass` remains
evidence-scoped post-hoc validation — its
`:claim/interpretation` says exactly that and must keep saying it.

---

## 2. Change 1 — Three-check fairness taxonomy (done)

The single cross-product check is replaced by three distinct theorems, so that
`:not-applicable` on the exact theorem can never be mistaken for "fairness
passed":

| Check | Theorem | Applicability |
|-------|---------|---------------|
| `:partial-fill/exact-pro-rata` | exact cross-product: `filled[i]×requested[j] = filled[j]×requested[i]` | strict pro-rata only; `:not-applicable` under rounding/cap-constrained |
| `:partial-fill/rounding-fairness` | deterministic dust rule: floor proportional; residual units by declared remainder ordering; no claimant beyond permitted rounding advantage; dust reconciles exactly | pro-rata + supported rounding policy; `:pass`/`:fail` (never silent) |
| `:partial-fill/fail-action-fairness` | execution conforms to its declared pro-rata fail-action policy | shortfall (deferred and/or haircut) present |

**Aggregator correctness gap — fixed.** The claim aggregator previously
converted any required `:not-applicable` check into `:not-exercised`, making
the whole level `:uncovered`. Under largest-remainder dust that would fail the
end-to-end claim even when `rounding-fairness` holds; conversely, a claim with
no applicable theorem at all could not be distinguished. The aggregator now
supports **alternative theorem groups**:

```clojure
:closed-form-check-ids #{:partial-fill/exact-pro-rata
                         :partial-fill/rounding-fairness
                         :partial-fill/fail-action-fairness}
:closed-form-alternative-sets #{#{:partial-fill/exact-pro-rata
                                  :partial-fill/rounding-fairness}}
```

Semantics, verified by tests:

- A `:not-applicable` check is acceptable only when another member of its
  alternative set is applicable-and-passing on the same decision.
- If no applicable theorem covers the regime, the level is `:uncovered` with
  reason `:no-applicable-theorem` — **fail-closed**, never a silent pass.
- `fail-action-fairness` is a required (non-alternative) theorem: a shortfall
  must have a declared-and-satisfied fail-action policy.

Verification: largest-remainder dust decision now yields verdict `:pass`,
`VALID true`; a waterfall (no pro-rata theorem applicable) decision yields
`:uncovered`, `:no-applicable-theorem`, `VALID false`.

---

## 3. Change 2 — Fail-action fairness is policy-bound (done)

Deferred-versus-haircut proportionality is **not** an unconditional definition
of fairness. It is the execution's conformance to a declared
`:fail-action-policy`. The check reads the policy from the decision and
commits its root:

```clojure
:fail-action-policy {:mode :pro-rata-treatment
                     :deferred-policy :same-ratio   ; or :contractual, :priority, ...
                     :haircut-policy :same-ratio}
```

- `:same-ratio` buckets must give every claimant the same shortfall ratio
  within the permitted rounding advantage.
- Non-`:same-ratio` policies (e.g. contractual treatment) are honored, not
  redefined as fairness violations.
- The effective policy and `:fail-action-policy-root` (new hash intent
  `:fail-action-policy`, domain tag `FAIL_ACTION_POLICY_V1`) are included in
  the check details, so the theorem is replayable and later provable.

The theorem is therefore: *the execution conforms to its declared pro-rata
fail-action policy* — stronger and more honest than freezing one treatment
policy as fairness forever.

---

## 4. Change 3 — Rejection activation is outside the proof (contract)

SP1 cannot prove "a rejected result was never activated" by itself. It can
prove:

```
this computation produced: rejection = X, allocation-result = Y
```

Activation is a separate authenticated protocol step. The end-to-end
fail-action theorem requires an explicit object chain:

```
allocation proof
      ↓
allocation-activation.v1 receipt
      ↓
economic effect
```

### `allocation-activation.v1` (implemented — producer + fail-closed verifier)

The activation receipt is versioned as **`allocation-activation.v1`** and binds
at least:

```clojure
:activation/schema-version  "allocation-activation.v1"
:proof-root
:result-root
:rejection/classification
:activation/status
:activation-policy-root
```

and enforces **`rejected proof ⇒ activation prohibited`**.

**All-active no-churn property.** Mirroring `conclusion-collective-hash`
(all-active input ⇒ root byte-identical to the unfiltered hash, filter is a
no-op): when the allocation is **all-active** — no rejection, and no deferred/
haircut fail action — the committed `:result-root` in the receipt is
**byte-identical to the unfiltered result-root**. The rejection/fail-action
filter is a no-op, so activating an all-active allocation introduces no hash
churn.

**Status: implemented with producer and fail-closed verifier.** The schema is
now registered (domain tags `ALLOCATION_ACTIVATION_V1` +
`ALLOCATION_ACTIVATION_POLICY_V1`) together with its producer and verifier:

- **`src/resolver_sim/allocation/activation.clj`** — `build-receipt` produces
  `:activated` receipts for passing proofs and `:prohibited` receipts (binding
  the rejection classification) for rejected proofs;
  `valid-activated-receipt?` is the authorization boundary: a receipt is valid
  only when activated, the proof was passing, and the root recomputes.
- **Acceptance test** (`test/resolver_sim/allocation/activation_test.clj`):
  mutates a genuinely produced kernel proof so verification rejects it, then
  demonstrates the activation path cannot emit/accept a valid receipt — and a
  forged `:activated` status on a rejected proof's receipt is still invalid
  because the rejection classification is bound.

This follows the `realised-parameter-set-root` lesson in reverse: the receipt
entered the production contract *with* its producer and verifier, not before.
The Solidity coordinator (`contracts/allocation/AllocationCoordinator.sol`)
remains the on-chain activation gate; wiring it to this receipt is out of scope
until proof verification is green.

This mirrors the existing distinction enforced elsewhere in the codebase
between a *decision being computed* and a *decision authorizing an
irreversible effect*. The Solidity coordinator
(`contracts/allocation/AllocationCoordinator.sol`) remains the activation gate
and is out of scope until this receipt exists.

---

## 5. Change 4 — Canonical cross-runtime statement (contract)

Do not teach the coprocessor about agent-c's internal `:scenario/evidence-root`.
Introduce a canonical cross-runtime statement instead:

```
realized-allocation-statement.v1
{:schema "realized-allocation-statement.v1"
 :allocation-context-root   ...
 :request-set-root          ...
 :allocation-policy-root    ...
 :realized-results-root     ...
 :fail-action-policy-root   ...
 :round-lifecycle-root      ...}
```

The data flow becomes:

```
agent-c          Rust/SP1
   │  computes       ↑ proves
   ↓                 │
realized-allocation-statement-root
```

agent-c separately binds that statement root into its richer
`:scenario/evidence-root`. SP1 proves the canonical statement; it never needs
to understand the simulator's full evidence model. This keeps the proof system
usable by any future client besides agent-c.

### Implementation status (partial)

The statement is **implemented and reachable on the producer side**:

- `src/resolver_sim/allocation/realized_statement.clj` — the
  `realized-allocation-statement.v1` builder: computes the six roots
  (allocation-context, request-set, allocation-policy, realized-results,
  fail-action-policy, round-lifecycle), commits the statement root under
  `REALIZED_ALLOCATION_STATEMENT_V1`, exposes the all-active no-churn
  property, and binds the statement into scenario evidence via
  `SCENARIO_EVIDENCE_BINDING_V1`.
- **`realized-results-root` is an explicit per-participant disposition
  vector.** It commits one sorted row per participant over the union of
  requested/filled/deferred/haircut keys:

  ```
  {:claim/id k :requested r :filled f :deferred d :haircut h :unrealized u
   :disposition :full-fill | :partial-fill | :deferred | :haircut
                | :zero-filled | :deferred-and-haircut}
  ```

  This is the participant/request → realized-disposition model: no participant
  is silently dropped. `:zero-filled` (row present) is distinguishable from
  "producer omitted" (row absent), which changes the root.
- **Mutation-locality tests pin the six-root decomposition.** Each mutation is
  asserted to change only its dimension (plus the statement root) and leave the
  other five roots unchanged: fail-action policy mutation → only
  `fail-action-policy-root`; realized-fill mutation → only
  `realized-results-root`; lifecycle mutation → only `round-lifecycle-root`;
  context mutation → only `allocation-context-root`; request membership
  mutation → `request-set-root` + `realized-results-root` (the new participant
  gains an explicit disposition), other four unchanged.
- Domain tags added to `hash/canonical.clj`:
  `REALIZED_ALLOCATION_STATEMENT_V1`, `REALIZED_REQUEST_SET_V1`,
  `ALLOCATION_POLICY_V1`, `REALIZED_RESULTS_V1`, `ROUND_LIFECYCLE_V1`,
  `SCENARIO_EVIDENCE_BINDING_V1`.
- Producer wired into the benchmark runner
  (`runner.clj::realized-allocation-statements`): when a scenario's world
  carries both an allocation context/round-lifecycle and partial-fill
  decisions, the statement root is bound into the scenario evidence-root and
  exposed on the result as
  `:scenario/realized-allocation-statements-root`.
- **Fail-closed producer** (`packs/partial_fill/evidence.clj`): a statement is
  produced only when context + lifecycle + decisions coexist; a missing context
  returns nil, so absence can never be mistaken for a proven statement.

**Superseded status note.** The Rust mirror, realized largest-remainder/deferred
partial-fill core, and thin SP1 guest are now implemented (see the current
Step-4 and Step-6 sections below). What remains is verifier-backed proof
admission, coordinator enforcement, and effect provenance—not another generic
fairness-obligation kernel.

---

## 6. Change 5 — Realized partial-fill lives in Rust core (contract)

The partial-fill realization must be implemented in the **independent Rust
kernel**, not ad-hoc in the SP1 guest. The SP1 guest is a thin proving wrapper
around deterministic Rust domain logic:

```
PRF/Clojure reference implementation
              ↕ conformance
independent Rust partial-fill kernel
              ↓
         SP1 guest (thin wrapper)
```

Target equality:

```
PRF result == independent Rust result == SP1 execution of the same Rust core
```

This avoids three synchronized semantic implementations (Clojure, native Rust,
SP1-specific). The current kernel (`coprocessor/core`) already covers the
all-or-nothing outcome-lottery model byte-for-byte; realized partial-fill
(`calculate-fulfillment*`, rounding policies, residual handling, fail-action
policy) is the next implementation increment, in Rust core.

### Step-4 acceptance conditions

**A. Rust must reconstruct all six roots.** Rust must not accept five roots as
inputs and merely hash the envelope. The valuable independent-verification
path is:

```
raw/canonical inputs
        ↓
Rust semantics
        ↓
allocation-context-root
request-set-root
allocation-policy-root
realized-results-root
fail-action-policy-root
round-lifecycle-root
        ↓
REALIZED_ALLOCATION_STATEMENT_V1
        ↓
statement-root
```

Rust may consume an already-canonical protocol artifact only when that artifact
is explicitly outside the computation being proved. The acceptance test must
document exactly which roots are recomputed versus merely bound.

**B. Define "realized partial-fill" independently of the statement encoder.**
Keep separate Rust layers so semantic errors are testable independently from
serialization errors, and the Rust implementation does not drift into "code
written specifically to reproduce Clojure hashes":

```
partial_fill(...)              → RealizedAllocation (semantics)
statement_projection(...)      → RealizedAllocationStatement (projection)
canonical_encode(...)          → statement root (serialization)
```

**C. Golden vectors include semantic mutations.** The conformance corpus must
include: all-active/no shortfall; all-active/partial-fill shortfall; a
participant becoming inactive; fail-action change; fail-action policy change
with identical allocation; request order/membership mutation; lifecycle
mutation; allocation-context mutation; rounding/dust case; zero-filled
participant; residual/unallocated amount. Each mutation must change the
expected semantic dimension, not merely "some hash changed". Locality is
asserted per root (see the Clojure mutation-locality tests, which are the
template).

**D. Clojure/Rust equality is conformance, not correctness.** The independent
Rust implementation derives its realization algorithm from the written
contract and canonical primitives; Clojure vectors are the oracle for
conformance, not a translation source. Assurance claim:

```
contract
 ├── Clojure implementation
 └── Rust implementation
then: Clojure output == Rust output
```

**Step-4 acceptance bar.** The Rust/SP1 part is complete only when:

> Given identical canonical allocation context, requests, allocation policy,
> fail-action policy, lifecycle state, and partial-fill decision inputs, the
> independent Rust core derives the same realized allocation and byte-identical
> realized-allocation-statement.v1 commitment as the Clojure producer.

proving: (1) semantic result equality; (2) each of the six component roots
equal; (3) final statement root equal; (4) all-active no-churn equality;
(5) negative/mutation vectors fail or diverge in the expected dimension;
(6) malformed/non-canonical input fails closed. SP1 then proves/reveals the
canonical statement root (and whatever minimal public inputs are intentionally
exposed), not the simulator evidence binding.

### Step-4 implementation status (core done)

The independent Rust realization and statement projection are implemented and
conformance-green against the Clojure producer:

- **`coprocessor/core/src/realized_fill.rs`** — semantics layer: `partial_fill`
  (largest-remainder pro-rata) → `RealizedAllocation` with explicit per-
  participant dispositions (`disposition_of`); malformed input fails closed.
- **`coprocessor/core/src/realized_statement.rs`** — projection + canonical-
  encode layer: all six roots recomputed from raw inputs (condition A; the
  context root is derived via `kernel::context_preimage`, not bound), statement
  root committed under `REALIZED_ALLOCATION_STATEMENT_V1`, `reveal` helper for
  the minimal public-input exposure SP1 should prove.
- **`coprocessor/core/src/realized_statement_io.rs`** — canonical input document
  → statement JSON projection (single shared path for the native CLI and the
  SP1 guest).
- **`coprocessor/core/tests/realized_statement_conformance.rs`** — golden
  conformance (condition D): six roots + statement root byte-equal to Clojure
  oracle values for the all-active, shortfall, and mutation-locality cases;
  negative vectors (malformed input) fail closed; a participant becoming
  inactive remains distinguishable.
- `cargo fmt` / `cargo clippy -D warnings` / `cargo test` all clean.

### Proof-admission profile and assurance stages

The active path supersedes earlier generic `fairness-proof-obligation.v1`
planning. The proof subject remains the narrower
`realized-allocation-statement.v1`; Clojure continues to evaluate the fairness
theorem for that exact subject.

The currently supportable cryptographic computation profile is
`largest-remainder-deferred-pro-rata.v1`: pro-rata mode, largest-remainder
rounding, deferred-only shortfall, no effective row caps, and no redistribution.
Haircut, mixed fail-action, cap-constrained, cap-redistribution, and other
policy regimes are explicitly **uncovered** for cryptographic admission until
Rust/SP1 independently implements them. They are not simplified into a
passing proof input.

### Cryptographic computation verification boundary

`realized-allocation-proof.v1` is an unsigned, self-addressed artifact for
**one SP1 proof of one realized statement**. Its identity commits the semantic
proof profile, statement schema/root, program id and ELF digest, verification
key, exact UTF-8 JSON public-value bytes and digest, proof encoding/file reference,
and proof digest. For the current off-chain Core profile, the proof file is the
SP1 SDK `SP1ProofWithPublicValues` bincode envelope (`sp1-bincode.v1`), stored
beside the artifact and content-addressed by `:proof/sha256`; Clojure verifies
that exact file before verifier admission. This is intentionally distinct from
`proof.bytes()`, which SP1 reserves for later Groth16/Plonk on-chain encodings.
The artifact is evidence only: it cannot nominate a trusted key or mark itself
verified.

`realized-statement-verify` independently loads the persisted Core envelope,
resolves the single supported profile to its compiled-in ELF and locally derived
VK, recomputes proof/public-value hashes, compares the exact public-value bytes,
and runs SDK verification. It emits an **unsigned** verification decision; this
is deliberately not yet a verification authority.

A future independently operated signer must wrap that decision in a signed
`realized-allocation-proof-verification.v1` receipt binding the artifact hash,
profile, statement root, program/ELF/VK identity, public-values/proof digests,
verifier id/version, and a `:verified` or `:rejected` verdict. Clojure will
verify that receipt under an externally supplied trust policy requiring an
active `:allocation-proof-verifier` key, and also independently parses the
exact public-value bytes and recomputes the statement. Thus a self-consistent
unsigned decision or receipt, caller-supplied `:verified?`, or caller-nominated
VK cannot elevate assurance.

The public SP1 subject remains the scenario-independent canonical statement
projection. Scenario/round separation remains outside the guest: the runner
constructs `scenario-realized-statement-binding.v1` only after both the generic
evidence-content root and the statement-collection root exist. This is acyclic:
the binding never feeds the evidence-content root from which it is derived.

A scenario may contain multiple realized statements. Proof admission is per
statement root; collection coverage is complete only when every member has
exactly one trusted, admitted proof tuple. A proof for one statement never
implies proof coverage of the collection.

The reproducible `make allocation-realized-proof` target uses ignored,
project-local writable SP1/Cargo/Go caches (or `SP1_CARGO_HOME`) and invokes
Cargo with `--locked`. Cache paths are not proof identity. The emitted artifact
records `rustc --version` and the SHA-256 of `coprocessor/Cargo.lock` as build
provenance, alongside the semantic profile, ELF digest, and VK.

Future profile/program/VK changes require a new profile or a separately
allowlisted registry entry. Historical artifacts retain the exact schema,
profile, statement version, public-values schema, ELF digest, and VK they were
issued under; newer code must not reinterpret them as a different profile.

Assurance is staged and machine-readable: `:assurance/evidence`,
`:assurance/cryptographic-computation`,
`:assurance/cryptographic-activation`, and `:assurance/effect-bound`.
The current strategic claim uses the first level. A future higher-level claim
must independently verify the exact statement/evidence binding, pinned
program/VK/profile proof, theorem result for the same statement, one-time
activation, and effect provenance.

`:full-fill` is not a shortcut: it is a recomputed disposition requiring every
request to be filled with zero deferred and haircut. `fulfilled` is only an
immediately applied amount and can be partial. Full-fill uses the same
statement → proof → theorem → activation → effect chain.

### Step-6 implementation status (SP1 thin wrapper done; on-chain proof pending)

The SP1 guest is now a thin wrapper over the realized-fill core, per acceptance
condition B:

- **`coprocessor/realized-statement-sp1-program/`** — the SP1 guest: reads the
  canonical realized-statement input, delegates all semantics + encoding to
  `realized_statement_io`, and commits the statement JSON projection as public
  values. The guest does not reimplement realization or encoding.
- **`coprocessor/sp1-script/src/bin/realized_statement_prove.rs`** — the host
  script: executes/proves the realized guest and asserts
  `guest public values == native public values` byte-for-byte.
- **`coprocessor/core/src/bin/realized_statement_kernel.rs`** — native CLI for
  the statement; `scripts/conformance/realized-statement-conformance.sh` runs
  it on the canonical fixture and asserts the six roots + statement root match
  the Clojure oracle, plus a malformed-input fail-closed check.
- `make allocation-realized-conformance` passes.

Still **not implemented**: trusted signed verifier-receipt issuance and
persisted-scenario statement recomputation for proof admission; on-chain/SP1
proof verification of the realized statement (the generated EVM verifier +
coordinator acceptance). The
`allocation-activation.v1` receipt (Change 3) is now **implemented** on the
producer + fail-closed-verifier side (see Change 3); its on-chain enforcement
in the Solidity coordinator awaits proof verification. The statement conformance
gate enforces `Clojure == Rust` today; `== SP1 guest` is wired at the host
level and becomes a CI-enforced equality once proof generation is green in the
environment (the gnark/Go `NAME_MAX` workaround is documented in
`sp1-script/README.md`).

---

## 7. Migration decision (done)

The substantive coprocessor implementation has been brought into this repo.
The Rust kernel/SP1 implementation is now part of the maturity path for a named
canonical claim, with shared roots, assertion semantics, lifecycle semantics,
conformance vectors, and eventually proof verification. Keeping the source of
truth in a separate demo repo creates drift risk with no compensating
assurance gain.

### What moved

```
agent-c/
├── coprocessor/                    # Rust core + SP1 guest + host/prover
│   ├── Cargo.toml
│   ├── core/                       # independent allocation kernel
│   ├── sp1-program/                # SP1 guest (thin wrapper)
│   └── sp1-script/                 # host: execute/prove/verify/EVM fixture
├── contracts/
│   └── allocation/                 # Solidity verifier + coordinator skeleton
│       ├── *.sol
│       ├── test/
│       └── foundry.toml
├── scenarios/
│   └── allocation/a-vs-b-plus-c/   # fixed conformance scenario + golden values
├── prf-runner/                     # pinned PRF JAR wrapper + artifact-lock.json
├── scripts/
│   └── conformance/                # conformance.sh, generate-expected.sh, vector-set-root.sh
└── docs/
    └── allocation-proofs/          # this contract + boundary docs
        ├── fair-end-to-end-provability-determination.md
        ├── sp1-proof-boundary.md
        ├── lifecycle-boundary.md
        ├── exact-replication-contract.md
        └── baseline-sew-property-test-failures.md
```

The pinned PRF JAR already lives in this repo's `target/` and matches the
`artifact-lock.json` SHA-256, so the wrapper (`prf-runner/run-prf.sh`, override
via `PRF_JAR`) and conformance gate work unchanged. The conformance gate
passes from the new location: **23 vectors, PRF result == native Rust result**.

### Ownership rule

The main repo owns the canonical contract, the Clojure reference, the
independent Rust implementation, the SP1 guest, the conformance corpus, and the
proof-boundary documentation.

**Independence is architectural, not a repository boundary.** The Rust crate
must not import or generate-share implementation logic from the Clojure
reference path. Its input is only the published contract — schemas, canonical
encoding, test vectors, declared constants/vocabulary. "Independent" means:
independent implementation, language/toolchain, execution, cross-checked
outputs. A monorepo makes the independence test stronger: one PR changing a
canonical rule can be required to update both implementations and the
conformance vectors before merging.

### What stays in the demo repo

The former demo repo is reduced to a thin external consumer: demo deployment,
example EVM contract integration, walk-through UI, sample external-client
wiring, Docker/dev environment, customer-facing example — consuming a released/
versioned coprocessor artifact from this repo. The repo proves an external
consumer can integrate; this repo proves the primitive works.

### Future independent verifier

For genuine third-party verification assurance, a *separate repository /
organization* reimplementation would be added later. The present Rust
implementation is valuable as an independent implementation, but merely living
in a separate repo owned and evolved by the same project provides little
additional assurance.

---

## 8. Recommended next increments (ordered)

1. ✅ Move Rust core + SP1 + contracts/conformance into this repo.
2. Freeze the current 14-assertion conformance gate (23 vectors) as the
   baseline.
3. ✅ Introduce `realized-allocation-statement.v1` (Change 4) as the canonical
   cross-runtime statement — Clojure builder + fail-closed producer wired into
   the benchmark runner; explicit per-participant dispositions; Rust mirror
   complete.
4. ✅ Implement realized partial-fill in the independent Rust core (Change 5) —
   semantics layer (`realized_fill.rs`) + projection/encode
   (`realized_statement.rs`) + golden conformance vs the Clojure producer
   (all six roots + statement root byte-equal; mutation locality; fail-closed).
5. Add rounding/fail-action policy assertions (Changes 1–2) to the Rust core.
6. ✅ Make the SP1 guest execute that core as a thin wrapper —
   `realized-statement-sp1-program` delegates to `realized_statement_io`; host
   script asserts guest public values == native; conformance gate enforces
   `Clojure == Rust` on the statement (SP1 equality wired at host, CI-enforced
   once proof generation is environment-green).
7. ✅ Bind proof → `allocation-activation.v1` receipt (Change 3), enforcing
   `rejected proof ⇒ activation prohibited` — producer + fail-closed verifier
   implemented (`activation.clj`), acceptance test proves a mutated rejected
   proof can never emit/accept a valid receipt; on-chain coordinator
   enforcement pending proof verification.
8. Only then graduate `:claim/pro-rata-fairness-end-to-end` from evidence
   validation to cryptographic provability.

---

## 9. Current verification status

- `partial-fill-test`: 94 tests / 245 assertions, 0 failures.
- `pro-rata-claims-test`: 24 tests / 56 assertions, 0 failures.
- `game-theory-validation-test`: 12 tests / 126 assertions, 0 failures
  (includes the aggregator alternative-group and fail-closed checks).
- `hash/canonical-test`: 169 tests / 1658 assertions, 0 failures
  (includes the new `:fail-action-policy` intent).
- clj-kondo: no new findings from this work.
- Rust conformance gate: 23 vectors pass (`make allocation-conformance`).

The claim currently remains deterministic, evidence-scoped validation. Its
machine-readable assurance level is `:assurance/evidence`; a cryptographic
computation level is fail-closed until a registered verifier-backed statement
proof, exact scenario binding, and supported proof profile are available. A
computation proof alone will still not authorize an economic effect until
activation and effect provenance are enforced. A future
`allocation-activation.v2` must bind the statement root, proof artifact and
trusted verification receipt identities, immutable round/decision and
coordinator identity, a single-use consumption key, and the applied effect-set
root. It must not reinterpret structurally valid `allocation-activation.v1`
receipts as this stronger authority.
