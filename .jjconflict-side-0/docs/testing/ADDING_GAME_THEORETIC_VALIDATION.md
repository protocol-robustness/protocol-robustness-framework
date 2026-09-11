# Adding Your Own Game-Theoretic Validation

This guide shows how to add **new game-theoretic checks** to this repository without breaking architecture boundaries.

It covers both lanes:

1. **Single-trace theory checks** (scenario-level, deterministic replay)
2. **Multi-epoch population checks** (stochastic simulation output)

---

## 1) Choose the correct validation lane

Use this decision rule first:

- Use **single-trace** (`scenario/*`) when your claim can be evaluated from one replay result.
  - Example: “no profitable unilateral deviation was observed in this trace”.
- Use **multi-epoch** (`sim/*`) when your claim needs population dynamics over time.
  - Example: “malicious cohort survival rate stays below honest cohort survival rate across epochs”.

If uncertain, start as single-trace `:inconclusive` with `:basis :multi-trace-required` and add a multi-epoch counterpart.

---

## 2) Single-trace extension workflow (CDRS theory block)

### Files involved

- `src/resolver_sim/scenario/equilibrium.clj`
- `src/resolver_sim/protocols/sew/equilibrium.clj` (protocol-specific validators)
- `test/resolver_sim/scenario/equilibrium_test.clj`
- `docs/CDRS-v1.1-THEORY-SCHEMA.md`

### Steps

1. **Add validator function** in the appropriate namespace.
   - Generic concept/property → `scenario/equilibrium.clj`
   - Sew-specific semantics → `protocols/sew/equilibrium.clj`

2. **Return normalized result shape**:
   - `:status` one of `:pass | :fail | :inconclusive | :not-applicable`
   - `:basis` should reflect evidence strength (for example `:single-trace-metric-proxy`)
   - include meaningful `:observed`, `:expected`, and `:offending` where applicable

3. **Register validator** in the concept/property map.

4. **Add/extend tests** in `equilibrium_test.clj`:
   - one `:pass`
   - one `:fail`
   - one `:inconclusive` (or `:not-applicable` if relevant)

5. **Document the field semantics** in `docs/CDRS-v1.1-THEORY-SCHEMA.md`.

---

## 3) Multi-epoch extension workflow (stochastic equilibrium)

### Files involved

- `src/resolver_sim/sim/multi_epoch.clj`
- `src/resolver_sim/sim/stochastic_equilibrium.clj`
- `test/resolver_sim/sim/multi_epoch_test.clj`
- `test/resolver_sim/sim/audit_test.clj`

### Steps

1. **Emit required evidence fields** from `run-multi-epoch`.
   - Prefer explicit fields over inferred approximations.
   - Example used now: `:initial-composition` (`honest/malice counts + shares`).

2. **Add claim evaluator** to `stochastic_equilibrium.clj`.
   - Use `pass/fail/inconclusive` helpers.
   - Keep evidence and detail strings explicit.

3. **(Optional) Add mechanism-proxy evaluator** if mapping to mechanism vocabulary.

4. **Register evaluator** in evaluator registry.

5. **Add tests**:
   - structural output test (new fields emitted)
   - synthetic evaluator test (known pass/fail case)

---

## 4) Evidence-strength and safety rules

- Do **not** over-claim. If proof needs more data, return `:inconclusive`.
- Prefer explicit `:basis` labels matching actual evidence quality.
- Keep deterministic replay checks and stochastic checks separate.
- Preserve layering:
  - `scenario/*` must not depend on `sim/*`
  - `sim/*` can depend on `sim/*` only

### Provenance metadata (valid-time + local self-signed trust)

When emitting game-theoretic outputs from `scenario/equilibrium.clj`, include
provenance so reviewers can distinguish claim quality from trust quality.

- `:provenance/:temporal`
  - `:query-mode` (e.g. `:as-of` vs `:latest`/unknown)
  - `:confidence` (pass-through temporal confidence map when present)
  - `:explicit-valid-time?` boolean derived from `:time-basis :valid-time`
- `:provenance/:attestation`
  - `:status` (e.g. `:verified`, `:failed`, `:missing`, `:unknown`)
  - `:source` (current local mode: `:local-self-signed`)
  - `:details` (raw attestation map when present)

Slice-1 policy is metadata-only (no pass/fail gating). Future strict modes may
require explicit valid-time snapshots and verified attestations before allowing
final `:pass` for selected claims.

Current strict mode:

- `:equilibrium-trust-mode :strict-valid-time`
  - Applied in `scenario/equilibrium.clj` for equilibrium-concept evaluation.
  - If explicit valid-time provenance is missing (`:time-basis != :valid-time`),
    concepts are returned as `:inconclusive` with basis `:absent-evidence`.
  - Mechanism/equilibrium core logic remains unchanged when provenance is present.

- `:equilibrium-trust-mode :strict-attestation`
  - Applied in `scenario/equilibrium.clj` for equilibrium-concept evaluation.
  - Requires `:provenance/:attestation/:status` to be `:verified`.
  - Non-verified statuses (e.g. `:missing`, `:failed`, `:unknown`) produce
    `:inconclusive` with basis `:absent-evidence`.

---

## 5) Minimum PR checklist

- [ ] Validator implemented
- [ ] Registered in dispatcher map
- [ ] Tests added (pass/fail/inconclusive)
- [ ] Docs updated
- [ ] `CHANGELOG.md` updated
- [ ] Relevant test commands run successfully

Canonical validation command:

```bash
./scripts/test.sh suites
```

For targeted sim tests:

```bash
clojure -M:test -e "(require 'clojure.test 'resolver-sim.sim.multi-epoch-test 'resolver-sim.sim.audit-test) (clojure.test/run-tests 'resolver-sim.sim.multi-epoch-test) (clojure.test/run-tests 'resolver-sim.sim.audit-test)"
```
