# Fair end-to-end allocation provability — architectural contract

**Status:** architectural contract for `:claim/pro-rata-fairness-end-to-end`.

This is the contract that governs moving fair pro-rata allocation from
evidence-scoped validation toward cryptographic provability. It supersedes the
earlier demo-repo determination and incorporates five architectural changes
before the contract is treated as binding.

The source of truth for the allocation coprocessor now lives in this repo
(see [Migration decision](#migration-decision)). The former
`iee-prf-allocation-coprocessor-demo` repository is reduced to an external
integration/demo consumer.

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
activation decision / receipt
      ↓
economic effect
```

The activation receipt binds at least:

```clojure
:proof-root
:result-root
:rejection/classification
:activation/status
:activation-policy-root
```

and enforces **`rejected proof ⇒ activation prohibited`**. This mirrors the
existing distinction enforced elsewhere in the codebase between a *decision
being computed* and a *decision authorizing an irreversible effect*. The
Solidity coordinator (`contracts/allocation/AllocationCoordinator.sol`) remains
the activation gate and is out of scope until this receipt exists.

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
3. Introduce `realized-allocation-statement.v1` (Change 4) as the canonical
   cross-runtime statement.
4. Implement realized partial-fill in the independent Rust core (Change 5).
5. Add rounding/fail-action policy assertions (Changes 1–2) to the Rust core.
6. Make the SP1 guest execute that core as a thin wrapper.
7. Bind proof → activation receipt (Change 3), enforcing
   `rejected proof ⇒ activation prohibited`.
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

Until steps 3–7 land, the claim remains a strong, deterministic,
evidence-scoped validation — not a zero-knowledge proof — and its
`:claim/interpretation` is written to say exactly that.
