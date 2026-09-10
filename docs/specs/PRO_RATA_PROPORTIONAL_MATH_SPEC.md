# Pro-Rata / Proportional Allocation Mathematical Specification and Verification

This document presents the formal mathematical specification, proofs of key invariants, and implementation verification steps for the canonical pro-rata / proportional allocation function (`largest-remainder-alloc`) in the simulation and settlement engine.

## 1. The Pro-Rata Allocation Problem

In decentralized finance (DeFi) and dispute-resolution systems, we frequently need to distribute a discrete integer amount of resources $A \in \mathbb{N}$ (e.g., token base units/wei) across a set of $n$ claimants. Each claimant $i$ has a weight $w_i \in \mathbb{R}^+$ (e.g., stake basis, withdrawal request amount).

Let:
- $W = \sum_{j=1}^{n} w_j$ be the total weight.
- $Ideal_i = A \times \frac{w_i}{W}$ be the exact real-valued proportional share of claimant $i$.

Because the allocated resource must be distributed in indivisible base units (integers), we cannot simply award $Ideal_i$. We must compute an integer allocation vector $\mathbf{a} = [a_1, a_2, \ldots, a_n] \in \mathbb{N}^n$ such that:
1. **Conservation (Zero Leakage)**: $\sum_{i=1}^n a_i = A$
2. **Fairness / Proportionality**: Each $a_i$ is as close as possible to $Ideal_i$.

## 2. The Hare-Quota (Largest Remainder) Method

The simulator uses the **Hare-Quota (Largest Remainder) Method** to solve this allocation problem. The algorithm consists of two distinct stages:

### Stage 1: Lower-Quota Floor Allocation
First, we allocate the integer floor of the ideal share to each claimant:
$$a_i^{\text{floor}} = \lfloor Ideal_i \rfloor$$

Let $S^{\text{floor}} = \sum_{i=1}^n a_i^{\text{floor}}$ be the total units allocated in Stage 1.

The remaining unallocated units (the "shortage") is:
$$H = A - S^{\text{floor}}$$

By definition of the floor function, $Ideal_i - 1 < a_i^{\text{floor}} \le Ideal_i$.
Summing this inequality over all $i$:
$$A - n < S^{\text{floor}} \le A$$
Which implies:
$$0 \le H < n$$

Thus, the shortage $H$ is a non-negative integer strictly less than the number of claimants $n$.

### Stage 2: Fractional Remainder Distribution
To satisfy conservation, we must distribute exactly $H$ additional units, at most one per claimant.
For each claimant, we compute the fractional remainder:
$$r_i = Ideal_i - a_i^{\text{floor}}$$
Note that $0 \le r_i < 1$.

We sort the claimants by their remainder $r_i$ in descending order. The top $H$ claimants in this sorted list receive an additional unit:
$$a_i = \begin{cases} a_i^{\text{floor}} + 1 & \text{if } i \in \text{Top } H \text{ by remainder} \\ a_i^{\text{floor}} & \text{otherwise} \end{cases}$$

#### Tie-Breaking (Determinism)
If there is a tie ($r_j = r_k$), the tie is broken deterministically by the original input sequence index (lower index gets priority). This guarantees identical execution traces across simulations and replays.

---

## 3. Mathematical Proof of Invariants

We prove three core properties for the Hare-quota allocation method:

### Theorem 1: Conservation of Value
The sum of final allocations equals the total available amount $A$.
$$\sum_{i=1}^n a_i = A$$

**Proof:**
By construction, the final allocation is:
$$\sum_{i=1}^n a_i = \sum_{i=1}^n \left( a_i^{\text{floor}} + \mathbb{I}(i \in \text{Top } H) \right)$$
where $\mathbb{I}$ is the indicator function.
$$\sum_{i=1}^n a_i = \sum_{i=1}^n a_i^{\text{floor}} + \sum_{i=1}^n \mathbb{I}(i \in \text{Top } H)$$
$$\sum_{i=1}^n a_i = S^{\text{floor}} + H$$
Since $H = A - S^{\text{floor}}$:
$$\sum_{i=1}^n a_i = S^{\text{floor}} + (A - S^{\text{floor}}) = A$$
Thus, no dust is generated, and no extra tokens are minted or leaked. $\blacksquare$

### Theorem 2: Quota Rule Compliance
Every claimant's allocation satisfies the upper and lower quota bounds:
$$\lfloor Ideal_i \rfloor \le a_i \le \lceil Ideal_i \rceil$$

**Proof:**
Since $a_i$ is either $a_i^{\text{floor}}$ or $a_i^{\text{floor}} + 1$:
1. If $a_i = a_i^{\text{floor}} = \lfloor Ideal_i \rfloor$:
   The lower bound holds trivially. The upper bound holds because $\lfloor Ideal_i \rfloor \le \lceil Ideal_i \rceil$.
2. If $a_i = a_i^{\text{floor}} + 1 = \lfloor Ideal_i \rfloor + 1$:
   - If $Ideal_i$ is an integer, then the remainder $r_i = 0$. Since we only distribute units to positive remainders (or up to $H$ elements), if all remainders are 0, $H=0$, so no addition occurs. Thus, $Ideal_i$ must be non-integer.
   - For non-integers, $\lfloor Ideal_i \rfloor + 1 = \lceil Ideal_i \rceil$.
   - Thus, $a_i = \lceil Ideal_i \rceil$.

In all cases, $\lfloor Ideal_i \rfloor \le a_i \le \lceil Ideal_i \rceil$. $\blacksquare$

---

## 4. Implementation Flow & Evidence Validation

In `resolver-sim.yield.exact-math/largest-remainder-alloc`, exact ratios (`clojure.lang.Ratio`) are used for all intermediate calculations, avoiding any floating-point drift or precision loss.

### Step-by-Step Runtime Verification

On every call to `largest-remainder-alloc`, the system executes the following checks:

```clojure
;; Step 1: Pre-conditions check
{:pre [(let [tot (ratio total-available)]
         (and (not (nil? tot)) (>= tot 0)))
       (sequential? claims)]}

;; Step 2: Intermediate state checks (inside let)
_ (assert (>= shortage 0) "Shortage must be non-negative")
_ (assert (< shortage n) "Shortage must be strictly less than the number of claims")

;; Step 3: Post-condition verification of conservation
_ (assert (= total-units total-allocated) "Conservation violation")

;; Step 4: Post-condition verification of Quota Rule compliance
(dotimes [i n]
  (let [idl (nth ideal i)
        [flr rem] (quantize-base-units idl)
        cel (if (zero? rem) flr (inc flr))
        alloc (nth final-units i)]
    (assert (and (>= alloc flr) (<= alloc cel)) "Quota rule violation")))
```

This strict checking guarantees that any violation of the pro-rata mathematical contract immediately halts execution and prevents corrupted states from being written or committed.

---

## 5. Evidence and Artifacts

Every pro-rata allocation in the system produces a stack of verifiable evidence
artifacts. These range from lightweight inline evidence (yield partial-fill) to
full DAG-linked execution nodes (fraud slash allocation) to committed JSON test
vectors for Solidity parity testing.

### 5.1 Evidence Stack

| Layer | Artifact | Produced By | Content |
|---|---|---|---|
| Projection | Projection artifact | `build-sew-slash-projection-artifact` | Ex-ante allocation frame: liable parties, total-basis, slash-obligation, policy snapshot, world-before hash |
| Re-projection | Projection artifact (second build) | Same function (called again) | Identical to first; hash comparison proves determinism |
| Allocation | Allocation result | `allocate-pro-rata` | Per-item allocations with `:allocated`, `:unmet`, `:weight`, `:cap-hit?`; aggregate totals |
| Result artifact | Pro-rata allocation result artifact | `build-pro-rata-allocation-result-artifact` | Canonical ex-post record binding projection, allocation, world before/after, action hash, claims, invariant links |
| Claims | Claim evaluation content | `build-claim-evaluation-node` | Input context, direct result, projection artifacts — consumed by claim evaluators |
| Claim evaluation node | Execution evidence node | `emit-claim-eval-execution-node!` | Persisted node with evaluation results for all 7 claims |
| Claims result | Claim result entries | `claims-engine/evaluate-claims` | Per-claim `:holds?` with violation details |
| Evidence | Evidence aggregate | `build-evidence-aggregate` | Typed, versioned, hashed evidence record with subject, inputs, result, dependencies, attribution |
| Execution node | Execution evidence node | `emit-pro-rata-execution-node!` | DAG node linking projection, re-projection, allocation, claim evaluation, and evidence hashes |
| Held-custody | Held-adjustment artifact | `adjust-held` (accounting.clj) | Custody movement record for pro-rata shortfall/haircut amounts |

### 5.2 Pro-Rata Allocation Result Artifact

Built by `build-pro-rata-allocation-result-artifact` in
`resolver-sim.pro-rata.evaluation`. This canonical ex-post artifact
captures what was actually allocated, complementing the ex-ante projection:

```clojure
{:schema-version 1
 :artifact-kind :pro-rata/allocation-result
 :allocation-result-id       ;; "allocation-pro-rata-<short-hash>"
 :allocation-result-type     :pro-rata-allocation
 :allocation-result-version  1
 :projection-artifact-hash   ;; link to ex-ante projection
 :projection-definition-id
 :projection-definition-hash
 :projection-concept-hash
 :source                     ;; projection source metadata
 :provenance                 ;; world-before/world-after/action hashes + attribution
 :allocation-input           ;; raw input (obligation, parties, basis, cap-field)
 :allocation-result          ;; actual allocations from allocate-pro-rata
 :shortfall-outcome          ;; shortfall breakdown (when applicable)
 :claims                     ;; claim result links
 :invariant-links            ;; invariant result links
 :metadata                   ;; e.g. {:slash-policy sp}
 :external-refs              ;; evidence-record-hash, evidence-group-id
 :allocation-result-hash}    ;; canonical hash of the artifact
```

Two-phase construction avoids circular dependency between artifact hash and
evidence envelope hash:
1. **Phase 1** — built without `:external-refs`; evidence aggregate computed
   from this provisional artifact.
2. **Phase 2** — rebuilt with `:evidence-record-hash` and `:evidence-group-id`
   in `:external-refs`. The `:external-refs` map is excluded from the hash
   calculation, so the artifact hash is identical across both phases.

### 5.3 Slash Pro-Rata Evidence

Built by `build-prorata-slash-evidence` in
`protocols_src/.../evidence/slashing.clj` (line 174). Produces both the
evidence record and the allocation result artifact in a single call.

Evidence linking model (fraud slash chain):

```
  proposal evidence ──────────→ allocation evidence
    (:fraud-slash-proposed)       (:evidence/dependencies)

  stake evidence ────────────→ allocation evidence
    (:slashing,                  (:evidence/dependencies)
     slash-resolver-stake)

                                allocation evidence ──→ result artifact
                                  (:pro-rata              (:external-refs
                                   :allocation-            :evidence-record-hash,
                                   result-hash             :evidence-group-id)
```

The evidence record carries:
- **`:evidence-type`**: `:slash/prorata-allocation`
- **`:schema-version`**: `"slash-prorata-allocation.v2"`
- **`:subject`**: `{:slash-id, :workflow-id, :resolver, :epoch, :trigger}`
- **`:inputs`**: allocation input, strategy context, slash context
- **`:result`**: projection summary, pro-rata section (intent, projection-hash,
  allocation-hash, allocation-result-hash, claims, summary), allocation result
- **`:dependencies`**: linked evidence from the fraud slash chain plus the
  pro-rata execution node

### 5.4 Pro-Rata Execution Node

Emitted by `emit-pro-rata-execution-node!` (same file, line 134). A
DAG-verifiable execution evidence node that links all computation hashes:

```clojure
{:execution-id :execution/pro-rata-allocation
 :status :pass
 :parent-hashes [claim-eval-node-hash scenario-replay-node-hash]
 :inputs {:projection-artifact {:projection-hash :projection-definition-hash}
          :allocation-input {:slash-obligation :liable-parties :basis :cap-field}}
 :outputs {:evidence-hash <hash>}
 :extensions {:pro-rata/type :pro-rata-allocation
              :pro-rata/projection-hash <hash>
              :pro-rata/re-projection-hash <hash>
              :pro-rata/allocation-result-hash <hash>
              :pro-rata/artifact-hash <hash>
              :pro-rata/claim-eval-node-hash <hash>
              :pro-rata/slash-id <id>
              :pro-rata/workflow-id <id>}
 :execution-kind :pro-rata-allocation
 :runner :protocol-layer}
```

`parent-hashes` link backward to the claim evaluation node and optionally to a
containing scenario-replay node for full provenance tracing.

### 5.5 Pro-Rata Claims (7 Registered Evaluators)

Located in `resolver-sim.yield.pro-rata-claims`. Each evaluator checks a
specific pro-rata property against the evidence-node content:

| # | Claim ID | Property | What It Detects |
|---|---|---|---|
| 1 | `shadow-equivalence` | Projection determinism | Non-deterministic projection hashes or allocation mismatch between direct and projection paths |
| 2 | `non-negative` | All values non-negative | Negative paid, unmet, owed, basis-amount, share, allocated, weight, cap |
| 3 | `allocation-complete` | Participant completeness | Missing liable parties in allocations or extra parties not in input |
| 4 | `conservation` | Value conservation (Theorem 1) | Per-allocation owed ≠ paid + unmet, or aggregate total ≠ sum of allocated + unmet + remainder |
| 5 | `rounding-bounded` | Quota rule compliance (Theorem 2) | Allocations deviating more than 1 unit from their ideal proportional share |
| 6 | `ordering-independent` | Determinism under re-processing | Different allocations when same input is processed in a different order |
| 7 | `cap-enforced` | Per-party cap enforcement | Any allocated amount exceeding its configured cap |

Claims 1–6 evaluate the reference allocation result. Claim 7 evaluates cap
enforcement on the actual ledger allocation (with redistribution). All 7
claims are evaluated as a batch via `claims-engine/evaluate-claims`, producing
a unified `:pro-rata` section in the evidence result.

### 5.6 Partial-Fill Evidence (Yield Settlement)

For yield settlement pro-rata, `calculate-fulfillment-pro-rata` in
`resolver-sim.yield.partial-fill` (line 117) returns an `:evidence` map inline:

```clojure
{:settlement-mode :partial-fill
 :evidence {:mode :pro-rata
            :available <liquidity>
            :total-requested <sum>
            :shortage <difference>
            :allocation-detail {:total-allocated :total-unmet :remainder}
            :allocation-rows
            [{:bucket-key :owed :effective-weight :effective-cap
              :pro-rata-share :fill-ratio :allocated :deferred :cap-hit?} ...]
            :redistribution {:round :excess :recipients ...}}}
```

When using decoupled weight/cap rows, the evidence includes the full
redistribution trace so each bucket's allocation, cap-hit, and deferred amount
can be verified independently.

### 5.7 Held-Custody Artifacts

Pro-rata allocations that change custody balances produce held-adjustment
artifacts via `adjust-held`. Each adjustment emits a
`held-custody-adjustment.artifact.v1` artifact recording amount, direction,
token, reason, and before/after balances. Pro-rata shortfall events produce
the `:partial-fill-principal-loss` held reason (exceptional held adjustment
requiring authorization provenance).

### 5.8 Test Vector Artifacts

Canonical test vectors are emitted by `resolver-sim.test-vectors.pro-rata`:

| Emitter | Schema | Domain |
|---|---|---|
| `emit-liquidity-fulfillment-vector` | `liquidity-fulfillment-vector.v1` | Yield partial-fill settlement |
| `emit-slash-allocation-vector` | `slash-allocation-vector.v1` | Sew slash allocation |

Each vector includes `schema-version`, `vector-id`, `domain`, `description`,
`input`, `expected-output`, `invariants` (machine-readable), `source-function`,
`source-metadata`, `policy-metadata`, `units`, `snapshot-metadata`,
`trust-boundary`, `edge-case-tags`, and three canonical hashes.

Liquidity vectors verify: conservation, bounded fulfillment, no
over-fulfillment, no negative amounts, deterministic ordering. Slash vectors
verify: conservation, cap enforcement, no negative debits, zero-weight
handling, deterministic ordering.

### 5.9 Evidence Registration

All pro-rata evidence artifacts are registered in the evidence registry
(`resolver-sim.evidence.registry`) and indexed by:
- **Event** — the replay event index that triggered the allocation
- **Group** — shared `:ctx/evidence-group-id` linking all evidence from the
  same replay event
- **Subject** — slash-id and workflow-id
- **Type** — `:slash/prorata-allocation` and `:execution/pro-rata-allocation`
- **Layer** — `:protocol-layer` for execution nodes, `:economic` for results

### 5.10 Artifact Dependency Graph

```
                    ┌──────────────────┐
                    │  Slash Proposal  │
                    │  Evidence        │
                    └────────┬─────────┘
                             │ dependency
                             ▼
                    ┌──────────────────┐
                    │  Projection      │
                    │  Artifact        │
                    └────────┬─────────┘
                             │
                    ┌────────▼─────────┐
                    │  Re-Projection   │── hash comparison → determinism
                    │  Artifact        │
                    └────────┬─────────┘
                             │
                             ▼
                    ┌──────────────────┐
                    │  Allocation      │
                    │  Result          │
                    └────────┬─────────┘
                             │
                    ┌────────▼─────────┐
                    │  Claim Eval Node │── 7 evaluators → claim results
                    └────────┬─────────┘
                             │ parent-hash
                             ▼
                    ┌──────────────────┐
                    │  Evidence        │
                    │  Aggregate       │── dependencies: [proposal, stake]
                    └────────┬─────────┘
                             │ external-refs
                             ▼
                    ┌──────────────────┐
                    │  Allocation      │
                    │  Result Artifact │
                    └────────┬─────────┘
                             │ parent-hash
                             ▼
                    ┌──────────────────┐
                    │  Execution Node  │── parent-hashes: [claim-eval, replay]
                    └──────────────────┘
```

---

## 6. Application: Pro-Rata Rational Resolver Threshold

### 6.1 Problem

The strategy payoff model (`optimal-strategy-under-load` in `evidence_costs.clj`) takes
`num-disputes` as its primary load signal and derives:

$$\text{effort-per-dispute}_i = \frac{B_i}{D_i}$$

where $B_i$ is resolver $i$'s effort budget and $D_i$ its dispute count.
This determines the **load level** (`:light` / `:medium` / `:heavy` / `:extreme`) and
hence the honest/lazy/malicious payoff crossover — the *rational threshold* at which a
resolver switches strategy.

**Bug (pre-fix):** `D_i = D_{\text{total}}$ for every resolver, i.e. the global epoch
dispute count was used regardless of how many disputes each resolver actually handles.
This systematically mis-estimates `effort-per-dispute` and therefore the strategy
crossover point, especially for heterogeneous resolver populations.

### 6.2 Correct Model

In a pool of $m$ resolvers with budgets $\{B_1, \ldots, B_m\}$ and total budget
$\mathcal{B} = \sum_{j=1}^{m} B_j$, the pro-rata dispute assignment is:

$$D_i = D_{\text{total}} \times \frac{B_i}{\mathcal{B}} \quad (\text{in general, rounded by Hare-quota})$$

The resulting effort-per-dispute:

$$\text{effort-per-dispute}_i = \frac{B_i}{D_i} \approx \frac{B_i}{D_{\text{total}} \cdot B_i / \mathcal{B}} = \frac{\mathcal{B}}{D_{\text{total}}}$$

which is **constant across resolvers** (up to integer rounding). This confirms the
invariant that under pro-rata assignment the effort-per-dispute signal is uniform, and
load classifications are consistent across the resolver pool.

### 6.3 Implementation: `prorata-dispute-load`

Located in `resolver-sim.stochastic.evidence-costs`. Delegates to
`largest-remainder-alloc` for the integer allocation, inheriting all guarantees
from Sections 2–4:

| Guarantee | Mapping |
|-----------|---------|
| Conservation | $\sum_i D_i = D_{\text{total}}$ — no dispute is lost or double-counted |
| Quota Rule | $\lfloor q_i \rfloor \le D_i \le \lceil q_i \rceil$ where $q_i = D_{\text{total}} \cdot B_i / \mathcal{B}$ |
| Non-negativity | $D_i \ge 0$ for all $i$ |
| Completeness | Every resolver ID appears in result |
| Determinism | Tie-breaking by input index — no hidden RNG |

A belt-and-suspenders conservation assertion is also placed at the `prorata-dispute-load`
boundary (independent of the inner assertion in `largest-remainder-alloc`).

### 6.4 Usage in `apply-load-optimal`

In `resolver-sim.sim.defection/apply-load-optimal`:

```clojure
;; 1. Build per-resolver budget map (falls back to global cfg budget)
resolver-budgets {id → effort-budget-per-epoch}

;; 2. Hare-quota allocation of total disputes
per-resolver-disputes (ec/prorata-dispute-load
                        (keys resolver-histories)
                        resolver-budgets
                        total-disputes
                        effort-budget-per-epoch)

;; 3. Per-resolver threshold evaluation
;; Each resolver gets its own :_epoch-trials → its own load snap
resolver-params (assoc params :_epoch-trials (get per-resolver-disputes id 0))
load-snap       (when (pos? resolver-disputes)
                  (load-optimal-snapshot resolver-params cfg rng))
```

Zero-dispute guard: if `total-disputes = 0` (epoch with no cases), `load-snap` is `nil`
and `decision` short-circuits to `{:to from :skip? true}` — no strategy switch occurs.

### 6.5 Test Coverage

New test namespace: `resolver-sim.stochastic.evidence-costs-prorata-test`
- 17 tests, 24 assertions covering G1–G5 individually
- Proportionality under exact-ratio, 2x, and 3-way budgets
- All edge cases: zero total, single resolver, empty, all-zero weights, missing budgets
- Rational threshold integration smoke test (confirms effort-per-dispute equalises)
