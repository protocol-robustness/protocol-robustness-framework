# a-vs-b-plus-c scenario

Fixed allocation scenario for the PRF + native Rust coprocessor.

## Semantics

- Capacity: 50
- Total eligible weight: 100
- Claim A: amount 50, weight 50 (owner-A)
- Claim B: amount 30, weight 30 (owner-B)
- Claim C: amount 20, weight 20 (owner-C)

Feasible outcomes (all-or-nothing, exact-capacity):

| Outcome | A | B | C |
|---------|---|---|---|
| O1      | 50|  0| 0 |
| O2      |  0| 30|20 |

Proposed rates: O1 = 1/2, O2 = 1/2.

Expected allocations (exact pro-rata):

- A = 50 × 50 / 100 = 25
- B = 50 × 30 / 100 = 15
- C = 50 × 20 / 100 = 10

Exact pro-rata representation: numerator = capacity × claimant weight,
denominator = total eligible weight.

## Files

- `scenario.json` — round-level identity (capacity, randomness, algorithm).
- `claimants.json` — claimant set.
- `outcomes.json` — feasible outcome set.
- `proposed-rates.json` — proposed rates in outcome canonical order.
- `policy.json` — policy reference.
- `expected-public-values.json` — golden public-value projection generated
  through the pinned PRF JAR (`scripts/conformance/generate-expected.sh`). Do not edit by
  hand.

## Reproduction

Generate expected values from PRF:

```bash
scripts/conformance/generate-expected.sh
```

Run the native Rust kernel over the scenario input and compare:

```bash
scripts/conformance/conformance.sh
```
