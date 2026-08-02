# Design: Claim Verification for Benchmark Packs

## Status: Implemented (Levels 1-2), Level 3 Deferred

Level 1 (mechanical) and Level 2 (invariant-backed) claim evaluators have been
implemented in `resolver-sim.benchmark.claims/evaluator-registry`. The benchmark
runner evaluates claims after scenario execution, and results appear in the
evidence bundle as `:claim-results`. See `src/resolver_sim/benchmark/claims.clj`
for the evaluator registry and `src/resolver_sim/benchmark/report.clj` for
report-level classification and maturity labeling.

---

## 1. Claim verification maturity levels

Before writing any evaluator, classify the claim by verification level.
This prevents overbuilding and makes the scope of each evaluator explicit.

| Level | Name | What the evaluator checks | Example | Status |
|-------|------|---------------------------|---------|--------|
| 0 | Declared only | Nothing — claim is metadata | `:benchmark/claims` in the manifest, no evaluator exists | Always present (metadata) |
| 1 | Mechanical | Required artifacts, hashes, evidence roots, or result fields exist and are internally consistent | `:scenario-hash-present`, `:evidence-root-present`, `:replay-result-present` | **Implemented** in `resolver-sim.benchmark.claims` |
| 2 | Invariant-backed | Named post-hoc invariants passed; claim is proxied by invariant results | `:claim/no-unauthorized-release` → checks `conservation-of-funds` + `released-monotonic` | **Implemented** for 9 Sew protocol claims |
| 3 | Semantic | Domain-specific reasoning over scenario results, world state, or evidence nodes | `:malicious-verdict-economically-bounded`, `:pull-first-settlement-safety` | **Deferred** — scenario-level claims declared but not evaluated |

---

## 2. Scope: per-scenario vs per-pack

A claim's evaluator receives either a single scenario result or the
full benchmark aggregate. This must be explicit in the claim definition
so the runner knows how to dispatch.

```clojure
:claim/scope :scenario     ;; evaluator runs once per scenario
:claim/scope :benchmark    ;; evaluator runs once for the full pack
```

Examples:

| Claim | Scope | Why |
|-------|-------|-----|
| `:scenario-hash-present` | `:scenario` | Each scenario produces its own evidence root hash |
| `:evidence-root-present` | `:scenario` | Same — one root per replay |
| `:replay-result-present` | `:scenario` | One outcome per scenario |
| `:no-invariant-errors` | `:scenario` | Invariant check is per-scenario |
| `:all-scenarios-pass` | `:benchmark` | Aggregate of all scenario outcomes |
| `:liveness-under-adversarial-load` | `:scenario` | Tested per scenario in the benchmark pack |
| `:malicious-verdict-economically-bounded` | `:scenario` | Tested per scenario |

The runner iterates over scenario results for `:scope :scenario` claims,
and invokes once for `:scope :benchmark` claims.

---

## 3. First claims to implement

**Start with Level 1 mechanical claims.** These are difficult to
misinterpret and establish the evaluator wiring without domain risk.

### Level 1 — scenario scope

```clojure
;; ── src/resolver_sim/benchmark/claims.clj (new file)

(def evaluator-registry
  {:scenario-hash-present
   {:scope :scenario
    :check (fn [ctx]
             (let [h (get-in ctx [:scenario/result :scenario/evidence-root])]
               {:holds? (boolean (and h (re-matches #"[0-9a-f]{64}" h)))
                :violations (when-not h [{:type :missing-evidence-root}])}))}

   :evidence-root-present
   {:scope :scenario
    :check (fn [ctx]
             (let [r (get-in ctx [:scenario/result :scenario/evidence-root])]
               {:holds? (boolean r)
                :violations (when-not r [{:type :missing-evidence-root}])}))}

   :replay-result-present
   {:scope :scenario
    :check (fn [ctx]
             (let [outcome (get-in ctx [:scenario/result :outcome])]
               {:holds? (boolean outcome)
                :violations (when-not outcome [{:type :missing-outcome}])}))}

   :no-invariant-errors
   {:scope :scenario
    :check (fn [ctx]
             (let [failures (get-in ctx [:scenario/result :invariant-fail-count] 0)]
               {:holds? (zero? failures)
                :violations (when (pos? failures)
                              [{:type :invariant-failures
                                :count failures}])}))}})
```

### Level 1 — benchmark scope

```clojure
   :all-scenarios-pass
   {:scope :benchmark
    :check (fn [{:keys [benchmark/results]}]
             (let [passed (count (filter #(= :pass (:outcome %)) results))
                   total (count results)]
               {:holds? (= passed total)
                :violations (when (< passed total)
                              [{:type :not-all-passed
                                :passed passed :total total}])}))}}
```

### Level 2 — invariant-backed (optional in initial implementation)

```clojure
   :slashable-liability-preserved-holds
   {:scope :scenario
    :check (fn [ctx]
             (let [inv (:slashable-liability-preserved
                        (get-in ctx [:scenario/result :invariant-results]))]
               {:holds? (= :pass (:result inv))
                :violations (when (not= :pass (:result inv))
                              [{:type :invariant-failed
                                :invariant-id :slashable-liability-preserved}])}))}}
```

### Level 3 — deferred for separate review

```clojure
   :malicious-verdict-economically-bounded    ;; deferred
   :liveness-under-adversarial-load           ;; deferred
   :pull-first-settlement-safety              ;; deferred
```

---

## 4. Evaluator interface

Each evaluator entry is a map:

```clojure
{<claim-kw>
 {:scope <:scenario | :benchmark>
  :check (fn [context]) -> {:holds? boolean
                            :violations [<map> ...]}}
```

### Context map

| Key | Available for | Contents |
|-----|---------------|----------|
| `:scenario/result` | `:scenario` | One SewAdapter scenario result map |
| `:scenario/world` | `:scenario` | Terminal world state from replay |
| `:scenario/metrics` | `:scenario` | Replay metrics map |
| `:evidence-nodes` | both | Evidence nodes produced during replay |
| `:benchmark/results` | `:benchmark` | All scenario result vectors |
| `:benchmark/manifest` | both | The benchmark manifest map |

---

## 5. Runner integration

In `run-benchmark`, after collecting results and building `inv-summary`:

```clojure
;; runner.clj (conceptual — not production code)
(let [claim-defs benchmark-claims/evaluator-registry
      manifest-claims (:benchmark/claims manifest)]
  (doseq [claim-id manifest-claims]
    (when-let [{:keys [scope check]} (get claim-defs claim-id)]
      (case scope
        :scenario
        (mapv (fn [result]
                (let [ctx {:scenario/result result
                           :scenario/world (:world result)
                           :evidence-nodes […]}
                      {:keys [holds? violations]} (check ctx)]
                  {:claim/id claim-id
                   :claim/outcome (if holds? :pass :fail)
                   :claim/evidence […]})))

        :benchmark
        (let [ctx {:benchmark/results results
                   :benchmark/manifest manifest
                   :evidence-nodes […]}
              {:keys [holds? violations]} (check ctx)]
          [{:claim/id claim-id
            :claim/outcome (if holds? :pass :fail)
            :claim/evidence […]}])))))
```

The result is a flat vector of `{:claim/id :claim/outcome :claim/severity :claim/evidence}`
added to the evidence bundle as `:claim-results`.

---

## 6. Goal

Turn this (current):

```clojure
{:benchmark {:benchmark/claims [:scenario-hash-present …]}
 :metrics {:total 3 :passed 3}
 :claim/status :declared-not-verified}
```

Into this (after):

```clojure
{:benchmark {:benchmark/claims [:scenario-hash-present …]}
 :metrics {:total 3 :passed 3}
 :claim-results
 [{:claim/id :scenario-hash-present
   :claim/outcome :pass
   :claim/severity :low
   :claim/evidence [:scenario/evidence-root]}
  …]
 :claim/status :verified}
```

---

## 7. What exists now

All levels 1-2 machinery is implemented and operational:

| Component | Location | Status |
|-----------|----------|--------|
| Claim evaluator registry (5 Level 1 + 9 Level 2) | `src/resolver_sim/benchmark/claims.clj` `evaluator-registry` | ✓ — 14 evaluators registered |
| Claim evaluation dispatch | `src/resolver_sim/benchmark/claims.clj` `evaluate-manifest-claims` | ✓ — per-scope (`:scenario`/`:benchmark`) dispatch |
| Runner integration | `src/resolver_sim/benchmark/runner.clj` `run-benchmark` | ✓ — claim evaluation after scenario execution, results in evidence bundle |
| Report classification | `src/resolver_sim/benchmark/report.clj` `classify-result` | ✓ — `:scoring/classification` with `:claim-maturity` level |
| Claim status taxonomy | `src/resolver_sim/benchmark/report.clj` `build-report` | ✓ — `:claim/status` (`:verified`/`:partial`/`:declared-not-verified`/`:none`) + `:claim/maturity` |
| Benchmark result spec | `docs/benchmarks/BENCHMARK_RESULT_SPEC_V1.md` | ✓ — defines `:claim-results` shape |
| Claim registry | `benchmarks/claim-registry.edn` | ✓ — 20 registered claims |
| Level 3 deferred claims | `benchmarks/packs/prf-core/protocol-robustness-v0.edn` | ✓ — `:benchmark/deferred-scenario-claims` set for 3 semantic claims |
| Report maturity labels | `docs/benchmarks/BENCHMARK_REPORT_FIELDS.md` | ✓ — Level 1/2/3 taxonomy documented |

---

## 8. Remaining work (Level 3)

| Item | Priority | Notes |
|------|----------|-------|
| Level 3 semantic evaluators | Low | Scenario-level claims (`:malicious-verdict-economically-bounded`, etc.) are deferred. Requires domain-specific reasoning over world state and evidence. Separate review needed before implementation. |

---

## 9. Risk

| Risk | Likelihood | Mitigation |
|------|-----------|------------|
| Evaluator function logic is wrong | Low (Level 1) | Level 1 checks are mechanical — field exists, hash matches pattern, outcome is non-nil |
| Claim scope confusion | Low | Explicit `:claim/scope` field; runner dispatches on it |
| Evidence-node data not available post-replay | Low | Level 1 checks need only the scenario result map, which the adapter already produces |
| Report builder expects different claim shape | Low | `build-report` passes `:claim-results` through unchanged — shape is defined by the claim evaluators |
| Level 2 invariant-based proxy is wrong | Low | Each Level 2 claim lists explicit invariant IDs. Adding/removing an invariant changes the check. Reviewed per claim. |
| Deferred Level 3 claims not validated | Low | `java -jar prf.jar benchmark validate` checks that deferred scenario claims are explicitly declared via `:benchmark/deferred-scenario-claims`. |
