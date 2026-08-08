# RISK_PROJECTION_SPEC_V1

Status: Implemented v1 (P0–P4).

Reference implementation:
`src/resolver_sim/notebook_support/speds/risk.clj` (risk-projection model),
`src/resolver_sim/notebook_support/speds/risk_render.clj` (risk card),
`src/resolver_sim/notebook_support/speds/var.clj` (distribution + VaR model),
`src/resolver_sim/notebook_support/speds/var_render.clj` (VaR card),
`scripts/scenarios/generate_risk_projection.clj` (generator, `bb risk:projection`).

This artifact is a [Projection Artifact](PROJECTION_ARTIFACT_SPEC_V1.md) of type
risk projection: a canonical, deterministic, evidence-backed view of ONE observed
risk quantity across the scenario universe of an event-evidence bundle.

---

## 1. Position in the artifact hierarchy

```
event evidence (observed fields)
        │
        ▼
scenario risk observations (rows, :derived from observed fields)
        │
        ▼
risk-projection.v1            ────────────────► risk card (presentation, OUTSIDE root)
        │
        ▼
probability / portfolio model  (P4, deferred)
        │
        ▼
VaR projection                 (P4, deferred — does not exist in v1)
```

The risk projection is the first artifact that aggregates observed exposure. It is
**not** responsible for probabilistic risk. VaR is a separate artifact downstream
of an explicit probability/weighting model.

---

## 2. Boundaries (do not cross)

### 2.1 Corpus ≠ distribution

The projection produces rows and corpus-safe statistics. It never emits VaR
claims. `:distribution-policy/status` stays `:not-measured` until a separate
probability / weighting artifact exists. Without that artifact, values such as
`:scenario-corpus/p95` are corpus statistics, never `VaR p95`.

### 2.2 Evidence provenance ≠ evidence integrity

Every row names the evidence object (`:evidence/hash`) and the exact observed
field (`:evidence/path`) from which its amount was derived. Whether those objects
belong to a valid hash chain / world transition is **not** verified in v1 and
renders `:not-measured`.

### 2.3 Observed exposure ≠ loss

A negative delta is an observed decrease of the held quantity (a release or
refund moving value out of escrow). It is reported as a decrease magnitude, never
as a protocol loss claim.

### 2.4 Scenario-separated aggregation

Rows from different scenarios are never added. Exposure values are only compared
within a scenario. The only cross-scenario value is `:worst-observed-scenario`, a
corpus statistic.

### 2.5 Timestamp ≠ ordering

`:chain/seq` (evidence chain position) is the authoritative ordering coordinate
and is always present. `:event/at` is a clock coordinate joined from a scenario
trace and may be `:not-measured`.

### 2.6 Phase ≠ timestamp

`:protocol/phase` is a semantic state-machine label mapped explicitly from the
evidence type. It is never inferred by reordering `:chain/seq` or derived from
`:event/at`. Unknown evidence types map to `:other`, not a guessed phase.

---

## 3. Quantity

v1 observes exactly one quantity:

```
:escrow/total-held
```

the aggregate value held by the escrow module, read from the post-state of each
event-evidence node. Both observed field paths carry the same quantity:

- `post-state → escrow/after → total-held` (escrow-created)
- `post-state → finalize/after → total-held` (escrow-released / escrow-refunded)

A node contributes a row only when it carries exactly one `total-held` integer
leaf in post-state. Ambiguous or absent nodes contribute no row (never a
fabricated amount).

---

## 4. Canonical row

Every row is canonical-safe (nil, boolean, integer, string, keyword, vector,
map):

```
{:event/at        <int ms> | :not-measured
 :chain/seq       <int>                 ; evidence chain position (always present)
 :scenario/id     <string>
 :scope           :escrow/total-held
 :asset           <string> | :not-measured
 :amount          <int>                 ; derived from the observed field
 :delta           <int> | nil           ; vs previous row in same scenario
 :protocol/phase  <keyword>             ; explicit map from evidence type
 :claim/basis     :derived
 :source          {:evidence/hash       <sha256:...>
                   :evidence/file       <filename>
                   :evidence/path       <path to the observed field>
                   :field               :total-held
                   :observation-basis   :observed}}
```

`:delta` is computed only within a scenario, in `:chain/seq` order. The first row
of a scenario has `:delta nil`.

---

## 5. Artifact structure

```
{:schema                       "risk-projection.v1"
 :projection-id                <16 hex chars of the domain hash>
 :context                      {:bundle-dir ... :trace-dir ... :run-id ...}
 :source                       {:evidence-roots [sha256:...]
                                :scenario-roots [scenario-ids]}
 :projection                   {:quantity "escrow/total-held"
                                :rows [<canonical row> ...]}
 :coverage                     {:scenario-count N
                                :measured-scenario-count M
                                :not-measured-scenario-count K
                                :row-count R
                                :not-measured-scenarios [ids]}
 :aggregation-policy           {:mode "scenario-separated"
                                :cross-scenario-addition? false}
 :distribution-policy          {:status "not-measured"
                                :model nil
                                :var-claims-absent true}
 :metrics                      {:per-scenario [<per-scenario metric> ...]
                                :worst-observed-scenario {...}}
 :evidence                     {:traceability :verified
                                :integrity :not-measured
                                :chain-verification :not-measured
                                :world-transition-verification :not-measured
                                :verification-root nil}
 :risk-projection/root         {:canonical/bytes <hex>
                                :canonical/hash <sha256:...>}}
```

### 5.1 Per-scenario metric

```
{:scenario/id               <string>
 :row-count                 <int>
 :peak-observed-exposure    <int>
 :max-observed-event-loss   <int>   ; largest single-event decrease magnitude
 :peak-drawdown             <int>}
```

All metric leaves are `:derived` from observed amounts and are scenario-local.

### 5.2 NOT MEASURED has two levels

A scenario-level `:not-measured` (evidence present, zero rows for the quantity)
is reported under `:coverage/:not-measured-scenarios` and is **never present in
`:projection/rows`**, so aggregation cannot accidentally sum absence into zero.
An actual amount of `0` is a measured row and stays in `:projection/rows`. The
card always prints the measured/not-measured split so a portfolio number can
never hide unmeasured corpus coverage.

---

## 6. Evidence section

Status vocabulary: `:verified | :failed | :not-measured`.

### 6.1 Established in v1

- `:traceability :verified` — every projected number names the evidence object
  and the observed field it was derived from.
- `:chain-verification :verified` — every scenario chain from which rows derive
  verifies end-to-end via `chain/verify-scenario-chain`: each
  `:evidence/chain-self-hash` equals
  `chain-link-hash(:evidence/hash, :evidence/chain-seq, :evidence/chain-prev-hash)`,
  every predecessor link matches the prior record's chain-self-hash, and the
  sequence is contiguous. Detail: `:chain-verification-detail` (verified /
  invalid scenario counts).
- `:world-hash-fields :verified` — every evidence object the projection uses
  carries well-formed (64-hex) `world/before-hash` and `world/after-hash`
  fields. Scoped to the projection's evidence; nodes producing no rows are
  outside scope.

### 6.2 Not established in v1 (with recorded reasons)

- `:integrity :not-measured` — `:evidence/hash` recomputation is not possible
  from the persisted bundle, which stores a re-serialized projection of each
  evidence record rather than the exact hashed content.
- `:world-transition-verification :not-measured` — recomputing whether a
  `world/after-hash` equals the world state after a transition requires the
  replay engine.

### 6.3 Verification root

`:verification-root` is a `sha256:` commitment to the sorted set of evidence
hashes the projection uses, computed via
`chain/evidence-hash-set-root` (domain-separated
`run-evidence-hash-set-v1`). It commits to the set of objects whose chains
verified, independent of the risk values they carry.

---

## 7. Commitment

`:risk-projection/root` commits the **semantic** artifact only:

```
{:schema, :source, :projection, :coverage,
 :aggregation-policy, :distribution-policy, :metrics}
```

Excluded from the commitment (outside the root):

- `:context` (bundle/trace paths, run id)
- `:evidence` (verification status)
- `:projection-id`
- the rendered risk card

Commitment scheme: `sha256("RISK_PROJECTION_V1" || canonical-bytes(body))`,
produced by `resolver-sim.hash.canonical/domain-hash` with the string domain tag
`RISK_PROJECTION_V1`, returned as a `sha256:` reference via
`resolver-sim.hash.reference/sha256-ref`. Re-verification recomputes the
commitment from the artifact's own semantic fields (`verify-root`); the generator
refuses to write if the root does not re-verify.

---

## 8. Phase plan

| Phase | Scope | Status |
|-------|-------|--------|
| P0 | Semantic contract (schema, coverage, aggregation/distribution policy, source-field provenance, corpus ≠ distribution) | Done (this spec) |
| P1 | Minimal projection — evidence → canonical rows → deterministic root | Done |
| P2 | Rendering — risk card in the narrative design system | Done |
| P3 | Evidence verification — chain-self/prev and world before/after admission | Partially landed: chain verification + world hash-field well-formedness `:verified`; content-hash recomputation (`:integrity`) and world-transition recomputation remain `:not-measured` with recorded reasons |
| P4 | Actual VaR — explicit probability / weighting artifact, then `:var/p95`, `:var/p99`, tail attribution | Done (see §9) |

P3/P4 status is reported honestly by the artifact (`:evidence/* :not-measured`,
`:distribution-policy/status :not-measured`) rather than documented away.

---

## 9. P4 — Scenario distribution and VaR projection

P4 introduces TWO canonical artifacts strictly downstream of risk-projection.v1.
The projection itself never emits VaR numbers; its `:distribution-policy/status`
stays `:not-measured`.

### 9.1 Scenario distribution (`scenario-distribution.v1`)

The explicit probability/weighting boundary. Consumes a risk-projection.v1 and
declares:

- `:model` — `empirical-scenario-distribution.v1`
- `:outcome` — the outcome variable being distributed, one of
  `:per-scenario-peak-exposure` or `:per-scenario-max-event-loss`
- `:scenario-weights` — per-scenario integer weights (uniform, weight 1)
- `:normalization-root` — scenario count / sum of weights / normalization scheme
- `:basis` — where the weights come from
- `:coverage` — weighted vs excluded scenario counts; excluded (unmeasured)
  scenarios receive no weight and are never hidden
- `:distribution/root` — canonical commitment over the above

### 9.2 VaR projection (`var-projection.v1`)

Consumes a risk-projection.v1 and exactly one scenario-distribution.v1. Emits
VaR claims ONLY here:

- `:var/p95`, `:var/p99` — weighted empirical quantiles
  (definition: smallest outcome value whose cumulative weight reaches `c · W_total`)
- `:expected-shortfall/p95`, `:expected-shortfall/p99` — weighted mean of
  outcomes strictly above the corresponding VaR, stored as an exact
  `{:numerator N :denominator D}` pair; `:not-measured` when the tail is empty
- `:tail-attribution/p99` — scenarios in the strict tail above VaR p99
- `:interpretation` — the mandatory corpus-relative statement: these are
  empirical corpus quantiles, NOT a probabilistic forecast of market outcomes,
  and must not be read as probability-weighted Value-at-Risk
- `:var/root` — canonical commitment over `:outcome`, `:distribution` ref,
  `:source`, `:coverage`, `:method`, `:interpretation`, `:metrics`

### 9.3 Data flow

```
risk-projection.v1 ─────────────────────────► risk card
      │
      ▼
scenario-distribution.v1   (empirical, uniform weights)
      │
      ▼
var-projection.v1 ──────────────────────────► VaR card
```

Generator: `scripts/scenarios/generate_risk_projection.clj` (`bb risk:projection`)
emits `distribution.{exposure,loss}.edn` and `var-projection.{exposure,loss}.edn`
plus `var-card.{exposure,loss}.html`. All roots re-verify before write.
