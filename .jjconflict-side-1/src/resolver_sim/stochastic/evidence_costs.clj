(ns resolver-sim.stochastic.evidence-costs
  "Evidence verification costs and load-dependent strategy selection.
   
   Phase P Lite: Adds attention budget constraint to break infinite-capacity assumption.
   
   Key insight: Under heavy load, honest resolvers can't fully verify everything.
   This makes shortcuts rational → lazy strategy dominates → system breaks.
   
   Also models: evidence forgery cost < verification cost for hard cases.
   This creates an asymmetry where attackers can forge faster than honest can verify.
   
   Pro-rata load allocation:
   When multiple resolvers share a dispute pool, each resolver's rational threshold
   calculation must use its *individual* dispute load, not the global total.
   `prorata-dispute-load` distributes the epoch dispute volume proportionally by
   effort-budget (capacity) using the Hare-quota / largest-remainder method — the
   same conservation-exact primitive used everywhere else in the framework.
   Reference: resolver-sim.yield.exact-math/largest-remainder-alloc"
  (:require [resolver-sim.stochastic.difficulty :as diff]
            [resolver-sim.yield.exact-math :as exact-math]))

;; === Effort Budget Constraints ===

(defn epoch-effort-budget
  "Total time units available per resolver per epoch.
   
   Default: 100 time units
   This is roughly 1 hour of investigation per epoch (week-scale).
   
   Under uniform load (50 disputes):
   - Can fully verify ~3 medium cases, OR
   - ~1 hard case + several easy ones, OR
   - Shortcut everything and triage heuristically"
  ([] 100)
  ([custom-budget] custom-budget))

(defn effort-available-per-dispute
  "Average effort available per dispute given load.

   effort-per-dispute = effort-budget / num-disputes

   At light load (10 disputes): 10 units/dispute → can fully verify all
   At heavy load (100 disputes): 1 unit/dispute → impossible to verify any

   This creates load-dependent strategy switching."
  [effort-budget num-disputes]
  (if (zero? num-disputes)
    effort-budget
    (/ effort-budget (double num-disputes))))

(defn prorata-dispute-load
  "Distribute total-disputes across resolvers proportionally by effort-budget capacity.

   ## Motivation (rational resolver threshold)
   The strategy payoff model in `optimal-strategy-under-load` takes `num-disputes`
   as its load signal. Under a uniform assumption every resolver sees the *global*
   total — but resolvers with different effort budgets receive proportionally
   different slices of the dispute pool.  A resolver with twice the budget can
   handle twice the cases, so the market equilibrium assigns it roughly twice the
   load.  Feeding the global total into per-resolver threshold calculations
   systematically mis-estimates effort-per-dispute and therefore the
   honest/lazy/malicious payoff crossover point.

   ## Method — Hare-quota / largest-remainder (Hamilton's method)
   The same conservation-exact proportional allocation used throughout the
   framework (yield settlement, slash allocation).  Guarantees:
     (1) Conservation: Σ resolver-disputes = total-disputes  (no leakage)
     (2) Quota Rule:   floor(q_i) ≤ alloc_i ≤ ceil(q_i)
                       where q_i = total × (budget_i / Σ budgets)
     (3) Determinism:  tie-breaking is index-stable (no hidden randomness)

   ## Parameters
     resolver-ids   — ordered collection of resolver IDs
     resolver-budgets — {resolver-id → effort-budget (positive number)}
                        Missing IDs default to the global effort-budget default.
                        All-zero budgets fall back to uniform.
     total-disputes — total epoch dispute count (non-negative integer)
     default-budget — fallback per-resolver budget (default: epoch-effort-budget)

   ## Returns
   {resolver-id → integer dispute count}  — every id present, sum = total-disputes.

   ## Evidence
   Hare quota: Balinski & Young (2001) §3.1 — floor allocation + largest-remainder.
   Conservation / quota-rule proofs: see pro_rata_proportional_math_spec.md."
  ([resolver-ids resolver-budgets total-disputes]
   (prorata-dispute-load resolver-ids resolver-budgets total-disputes (epoch-effort-budget)))
  ([resolver-ids resolver-budgets total-disputes default-budget]
   {:pre [(not (neg? total-disputes))
          (sequential? resolver-ids)
          (map? resolver-budgets)]}
   (let [ids-vec (vec resolver-ids)
         n       (count ids-vec)]
     (if (zero? n)
       {}
       (let [;; Step 1: collect per-resolver capacity weights (effort budgets)
             ;; Missing IDs fall back to the global default — same invariant as
             ;; the cfg-level :effort-budget-per-epoch resolution in defection.clj.
             weights (mapv (fn [id]
                             (double (max 0.0 (get resolver-budgets id default-budget))))
                           ids-vec)
             total-weight (reduce + 0.0 weights)
             ;; Step 2: if all weights are zero (e.g. no budget info at all),
             ;; fall back to uniform — each resolver gets an equal share.
             eff-weights (if (pos? total-weight)
                           weights
                           (vec (repeat n 1.0)))

             ;; Step 3: build claims vector for largest-remainder-alloc.
             ;; :amount = capacity weight → drives the proportional share.
             ;; :key    = resolver-id    → preserved in allocations output.
             claims (mapv (fn [id w] {:key id :amount w}) ids-vec eff-weights)

             ;; Step 4: call the canonical pro-rata primitive.
             ;; This provides the Hare-quota guarantee: conservation + quota rule.
             alloc-result (exact-math/largest-remainder-alloc total-disputes claims)

             ;; Step 5: extract and return {resolver-id → dispute-count} map.
             ;; :filled is the integer allocation from largest-remainder-alloc.
             alloc-map (into {} (map (fn [a] [(:key a) (:filled a)])
                                     (:allocations alloc-result)))

             ;; Post-condition: conservation invariant must hold.
             ;; The assertion in largest-remainder-alloc already guarantees this;
             ;; we re-assert here at this abstraction boundary as a belt-and-suspenders check.
             total-allocated (reduce + 0 (vals alloc-map))
             _ (assert (= (long total-disputes) total-allocated)
                       (str "prorata-dispute-load conservation violation: "
                            "total-disputes=" total-disputes
                            " allocated=" total-allocated))]
         alloc-map)))))

(defn effort-required-to-verify
  "Effort required for full verification by strategy/difficulty.
   
   Maps to difficulty module's effort-cost-to-fully-verify for honest,
   lazy, malicious strategies.
   
   Key: 'Full verification' for hard cases costs 80 time units.
   Under heavy load, this is unachievable; shortcuts become rational."
  [strategy difficulty]
  (diff/effort-cost-to-fully-verify strategy difficulty))

(defn effort-actually-spent
  "How much effort does resolver actually spend on a dispute?
   
   Strategy-dependent:
   - HONEST: wants to verify properly, limited by budget
   - LAZY: takes minimum viable effort (heuristic)
   - MALICIOUS: already knows what they want to do, minimal effort
   
   Under normal load, honest can meet targets.
   Under heavy load, honest falls back to heuristics."
  [rng strategy difficulty effort-available effort-required]
  (case strategy
    :honest
    (min effort-available effort-required)  ; Do best effort within budget

    :lazy
    (max 3 (/ effort-available 2.0))  ; Lazy spends ~half effort on anything

    :malicious
    (max 1 (/ effort-available 4.0))  ; Malice barely tries; they know the answer

    :collusive
    (min effort-available (double (/ effort-required 1.3)))))  ; Collusive tries harder

;; === Accuracy with Effort ===

(defn accuracy-given-load
  "Compute realistic accuracy given load and effort available.
   
   1. Get base accuracy for strategy/difficulty
   2. Adjust for effort available vs required
   3. Return degraded accuracy under load
   
   Effect: Under heavy load, honest accuracy drops to lazy levels."
  [rng strategy difficulty effort-available]
  (let [base-accuracy (diff/accuracy-by-difficulty strategy difficulty)
        effort-required (effort-required-to-verify strategy difficulty)
        effort-spent (effort-actually-spent rng strategy difficulty
                                            effort-available effort-required)
        adjusted-accuracy (diff/accuracy-with-effort base-accuracy effort-spent
                                                     effort-required)]
    adjusted-accuracy))

(defn should-shortcut?
  "Does resolver rationally decide to shortcut verification?
   
   Returns true if:
   - Load is heavy (effort-available < effort-required)
   - AND shortcutting is faster than full verification
   - AND resolver's strategy allows it (honest avoids when possible)
   
   This encodes: under load, heuristics become economically dominant."
  [effort-available effort-required strategy]
  (let [needs-shortcut? (< effort-available effort-required)
        shortcut-allowed? (not (= strategy :honest))]
    (and needs-shortcut? shortcut-allowed?)))

;; === Load Analysis ===

(def load-level-thresholds
  "Effort-per-dispute thresholds for load classification.

   :full-verification-effort   — effort units where honest can fully verify a dispute
   :partial-verification-effort — effort units where honest can partially verify
   :minimal-check-effort        — effort units where honest can only minimally check
   
   These are research assumptions about verification cost under the
   difficulty distribution. They depend on the difficulty mix and
   the effort-cost-to-fully-verify model in difficulty.clj."
  {:full-verification-effort   10.0
   :partial-verification-effort 2.0
   :minimal-check-effort        0.5})

(defn load-level
  "Classify load as light/medium/heavy/extreme based on effort per dispute.

   light:   effort-available >= full-verification-effort  (can fully verify all)
   medium:  effort-available >= partial-verification-effort (can verify most)
   heavy:   effort-available >= minimal-check-effort       (must shortcut some)
   extreme: effort-available <  minimal-check-effort        (can't meaningfully verify)

   Each level has different equilibrium strategies.
   This formulation is portable across resolver effort budgets and
   dispute counts — it does not assume a fixed budget or volume."
  [num-disputes effort-budget]
  (let [avg-effort (effort-available-per-dispute effort-budget num-disputes)
        {:keys [full-verification-effort
                partial-verification-effort
                minimal-check-effort]} load-level-thresholds]
    (cond
      (>= avg-effort full-verification-effort)     :light
      (>= avg-effort partial-verification-effort)  :medium
      (>= avg-effort minimal-check-effort)          :heavy
      :else                                         :extreme)))

;; === Detection Probability ===

(defn expected-detection-prob
  "Expected detection probability weighted by difficulty distribution.

   Detection varies by difficulty: easy cases have full detection,
   medium cases attenuate to 60%, hard cases to 20% of base probability.
   This reflects the fact that ambiguous/hard cases are harder to
   adjudicate, creating an attacker advantage in the tail.

   The neutral expectation uses the default difficulty distribution.
   An attacker-targeted distribution (overweighting hard cases) would
   yield lower expected detection, which is the vulnerability the
   adversarial framework is designed to surface."
  [base-detection-prob difficulty-distribution]
  (reduce (fn [acc [difficulty weight]]
            (+ acc (* weight (diff/detection-probability-by-difficulty
                              base-detection-prob difficulty))))
          0.0
          difficulty-distribution))

;; === Evidence Forgery Costs ===

(defn forge-evidence-cost
  "Cost for attacker to create plausible evidence by difficulty.
   
   Easy: 2 units (trivial to fake consensus)
   Medium: 5 units (need selective screenshots/translation tricks)
   Hard: 8 units (need deep fake or narrative construction)
   
   Compare to honest verification:
   - Easy: 5 units honest vs 2 units fake → 2.5x cost disadvantage
   - Medium: 30 units honest vs 5 units fake → 6x cost disadvantage
   - Hard: 80 units honest vs 8 units fake → 10x cost disadvantage
   
   This creates the evidence asymmetry that breaks systems."
  [difficulty]
  (case difficulty
    :easy   2
    :medium 5
    :hard   8))

(defn verify-evidence-cost
  "Cost for honest to verify potentially forged evidence.
   
   Same as effort-required-to-verify (they need full investigation)."
  [difficulty]
  (diff/effort-cost-to-fully-verify :honest difficulty))

(defn evidence-asymmetry-ratio
  "How much cheaper is forgery vs verification?
   
   High ratio = attacker advantage
   easy: 5/2 = 2.5x
   medium: 30/5 = 6x
   hard: 80/8 = 10x
   
   This ratio shows why hard cases are attack targets."
  [difficulty]
  (/ (verify-evidence-cost difficulty)
     (forge-evidence-cost difficulty)))

;; === Difficulty Distribution ===

(defn default-difficulty-distribution
  "Default dispute difficulty distribution.

   Returns a map of difficulty → weight (summing to 1.0).
   These are research assumptions: the protocol's case docket mix.

   Normal docket:       70% easy, 25% medium, 5% hard
   Adversarial docket:  30% easy, 45% medium, 25% hard
   Attack-targeted:     10% easy, 30% medium, 60% hard"
  []
  {:easy 0.70 :medium 0.25 :hard 0.05})

(defn expected-accuracy
  "Expected accuracy for a strategy given a difficulty distribution.

   Weights accuracy-by-difficulty by the distribution weights.
   This is the strategy's base accuracy before load degradation."
  [strategy difficulty-distribution]
  (reduce (fn [acc [difficulty weight]]
            (+ acc (* weight (diff/accuracy-by-difficulty strategy difficulty))))
          0.0
          difficulty-distribution))

;; === Strategy Selection Under Load ===

(defn- canon-round [f] (long (Math/floor f)))

(defn- load-dependent-accuracy
  "Expected accuracy for a strategy under load, weighted by difficulty distribution.

   Honest accuracy degrades under load because full verification becomes
   impossible. Lazy and malicious accuracy are roughly load-insensitive
   because they were already shortcutting — their effort-actually-spent
   is bounded below by a minimum (3 for lazy, 1 for malicious) that
   does not degrade further under extreme load.

   Returns a double in [0, 1]."
  [rng strategy effort-available difficulty-distribution]
  (reduce (fn [acc [difficulty weight]]
            (+ acc (* weight (accuracy-given-load rng strategy difficulty
                                                  effort-available))))
          0.0
          difficulty-distribution))

(defn- validate-params
  "Validate input parameters for optimal-strategy-under-load.
   Throws ex-info on invalid inputs."
  [num-disputes effort-budget detection-prob slashing-multiplier fee-profit]
  (when-not (pos? num-disputes)
    (throw (ex-info "num-disputes must be positive"
                    {:num-disputes num-disputes})))
  (when-not (pos? effort-budget)
    (throw (ex-info "effort-budget must be positive"
                    {:effort-budget effort-budget})))
  (when-not (<= 0.0 detection-prob 1.0)
    (throw (ex-info "detection-prob must be in [0, 1]"
                    {:detection-prob detection-prob})))
  (when-not (pos? slashing-multiplier)
    (throw (ex-info "slashing-multiplier must be positive"
                    {:slashing-multiplier slashing-multiplier})))
  (when-not (pos? fee-profit)
    (throw (ex-info "fee-profit must be positive"
                    {:fee-profit fee-profit})))
  true)

(defn optimal-strategy-under-load
  "Evaluate which strategy maximizes expected profit under given load conditions.

   The core economic mechanism:
   - Under light load, honest verification is accurate and profitable.
   - Under heavy load, honest accuracy degrades (can't verify everything),
     making lazy relatively more attractive.
   - Malicious faces detection/slashing risk that limits its upside.

   Returns a full diagnostic map with per-strategy payoffs, not just the
   winning strategy keyword. This enables inspection of why a particular
   strategy dominates.

   Parameters:
     rng                — seeded RNG (for reproducibility; used by accuracy-given-load)
     num-disputes       — dispute volume this epoch
     effort-budget      — total time-units available per resolver per epoch
     detection-prob     — base probability of detecting malicious behavior [0,1]
     slashing-multiplier — slash penalty multiple on detection
     fee-profit         — expected fee profit per dispute"
  [rng num-disputes effort-budget detection-prob slashing-multiplier fee-profit]
  (validate-params num-disputes effort-budget detection-prob slashing-multiplier fee-profit)
  (let [diff-dist           (default-difficulty-distribution)
        effort-avail        (effort-available-per-dispute effort-budget num-disputes)
        load                (load-level num-disputes effort-budget)

        ;; Strategy payoffs under load
        h-accuracy          (load-dependent-accuracy rng :honest effort-avail diff-dist)
        l-accuracy          (load-dependent-accuracy rng :lazy effort-avail diff-dist)
        m-accuracy          (load-dependent-accuracy rng :malicious effort-avail diff-dist)

        h-profit            (canon-round (* fee-profit h-accuracy))
        l-profit            (canon-round (* fee-profit l-accuracy))
        m-detection-prob    (expected-detection-prob detection-prob diff-dist)
        m-detection-risk    (* m-detection-prob slashing-multiplier)
        m-profit            (canon-round (* fee-profit m-accuracy (max 0.0 (- 1.0 m-detection-risk))))

        optimal (cond
                  (> h-profit l-profit) :honest
                  (> l-profit m-profit) :lazy
                  :else                 :malicious)]

    {:optimal-strategy          optimal
     :load-level                load
     :effort-available-per-dispute effort-avail
     :difficulty-distribution   diff-dist
     :strategy-payoffs
     {:honest    {:expected-profit h-profit
                  :expected-accuracy h-accuracy}
      :lazy      {:expected-profit l-profit
                  :expected-accuracy l-accuracy}
      :malicious {:expected-profit m-profit
                  :expected-accuracy m-accuracy
                  :expected-detection-prob m-detection-prob}}
     :assumptions
     {:base-detection-prob    detection-prob
      :slashing-multiplier    slashing-multiplier
      :detection-model        :difficulty-weighted
      :load-model             :effort-available-per-dispute
      :accuracy-model         :accuracy-given-load}}))

;; === Validation ===

(defn validate-effort-budget
  "Ensure effort budget is positive and reasonable."
  [budget]
  (and (> budget 0) (< budget 1000)))

(defn validate-load
  "Ensure load (num-disputes) is positive."
  [num-disputes]
  (> num-disputes 0))

;; === Metrics ===

(defn effort-utilization
  "Fraction of available budget that's used.
   
   Used / Available.
   - < 1.0: budget is sufficient, slack exists
   - > 1.0: budget is insufficient, overloaded
   
   When > 1.0, shortcuts become rational."
  [total-effort-needed effort-budget]
  (if (zero? effort-budget)
    0.0
    (/ total-effort-needed (double effort-budget))))