(ns resolver-sim.stochastic.difficulty
  "Dispute difficulty distribution and effects on accuracy/detection.
   
   Phase P Lite: Adds heterogeneous dispute difficulty to break uniform-case assumptions.
   
   Key insight: Attackers target the hard/ambiguous cases (5% tail) where detection
   is -80% lower than easy cases. Current model averages over all cases, hiding
   tail vulnerability."
  (:require [resolver-sim.stochastic.rng :as rng]))

;; === Difficulty Distribution ===

(defn sample-difficulty
  "Sample dispute difficulty from mixture distribution.
   
   Distribution: 70% easy, 25% medium, 5% hard
   This matches realistic dispute patterns where most are trivial but
   a meaningful tail are ambiguous/impossible without off-chain context."
  [rng]
  (let [r (rng/next-double rng)]
    (cond
      (< r 0.70) :easy
      (< r 0.95) :medium
      :else      :hard)))

(defn difficulty-weight
  "Return numeric weight for difficulty (for validation/aggregation).
   
   easy=1, medium=2, hard=3
   Useful for detecting attacker targeting bias."
  [difficulty]
  (case difficulty
    :easy   1
    :medium 2
    :hard   3))

;; === Accuracy Curves ===

(defn accuracy-by-difficulty
  "Base accuracy for strategy/difficulty combination.
   
   HONEST: High accuracy on easy, degrades gracefully on hard
   LAZY: Low accuracy everywhere, especially hard (gives up)
   MALICIOUS: Low accuracy everywhere (can't exploit ambiguity if they know the truth)
   
   These curves encode the fact that:
   - Hard cases require deep investigation (honest works, lazy shortcuts, malice can't fake)
   - Easy cases are trivial (everyone gets them right)
   - Medium cases are where disagreement occurs"
  [strategy difficulty]
  (case [strategy difficulty]
    [:honest :easy]      0.95
    [:honest :medium]    0.80
    [:honest :hard]      0.50

    [:lazy :easy]        0.60
    [:lazy :medium]      0.40
    [:lazy :hard]        0.20

    [:malicious :easy]   0.40
    [:malicious :medium] 0.30
    [:malicious :hard]   0.15

    [:collusive :easy]   0.80
    [:collusive :medium] 0.70
    [:collusive :hard]   0.40))

;; === Detection Curves ===

(defn detection-probability-by-difficulty
  "Probability of detecting fraud by difficulty.
   
   Hard cases: -80% detection (10% → 2%)
   Medium cases: -40% detection (10% → 6%)
   Easy cases: baseline detection (10%)
   
   This is the CRITICAL vulnerability: attackers can hide fraud in hard cases
   because detection requires understanding the complex context."
  [base-detection-prob difficulty]
  (let [hard-multiplier 0.20      ; 80% reduction: 10% → 2%
        medium-multiplier 0.60]   ; 40% reduction: 10% → 6%
    (case difficulty
      :easy   base-detection-prob
      :medium (* base-detection-prob medium-multiplier)
      :hard   (* base-detection-prob hard-multiplier))))

;; === Effort Costs ===

(defn effort-cost-to-fully-verify
  "Time units required for full verification by strategy/difficulty.
   
   HONEST spends time to verify properly.
   LAZY takes shortcuts.
   MALICIOUS assumes they know the answer already (effort doesn't help them).
   
   Total budget = 100 time units/epoch
   - Easy: 5 (honest) → trivial to verify all 20+ easy cases
   - Medium: 30 (honest) → can verify 3 medium cases
   - Hard: 80 (honest) → can verify 1 hard case fully
   
   Under load (e.g., 100 disputes), honest can't fully verify everything."
  [strategy difficulty]
  (case [strategy difficulty]
    [:honest :easy]      5      ; trivial
    [:honest :medium]    30     ; real investigation
    [:honest :hard]      80     ; deep analysis

    [:lazy :easy]        3      ; heuristic only
    [:lazy :medium]      10     ; skim + guess
    [:lazy :hard]        15     ; give up mostly

    [:malicious :easy]   5      ; they know answer
    [:malicious :medium] 10     ; same as lazy (can't improve with effort)
    [:malicious :hard]   15     ; same as lazy

    [:collusive :easy]   4      ; slightly less than honest
    [:collusive :medium] 20
    [:collusive :hard]   60))

(defn accuracy-with-effort
  "Adjust accuracy based on effort spent vs required.
   
   If resolver spends >= required effort, gets full accuracy.
   If < required, accuracy degrades linearly with effort ratio.
   
   This encodes: full verification takes time; partial effort gives partial knowledge."
  [base-accuracy effort-spent effort-required]
  (if (>= effort-spent effort-required)
    base-accuracy
    (let [effort-ratio (/ effort-spent (double effort-required))]
      (* base-accuracy effort-ratio))))

;; === Attacker Targeting ===

(defn attacker-should-target-dispute?
  "Decide if attacker should attack this dispute.
   
   Attackers target high-value disputes:
   1. Hard cases (detection -80%)
   2. With high stakes (large escrow)
   3. With low honest profit (high slashing risk)
   
   Returns true if dispute is attractive target."
  [difficulty escrow-wei attacker-budget-remaining]
  (and
   (> attacker-budget-remaining 0)
    ; Prefer hard > medium > easy
   (case difficulty
     :hard true
     :medium (> attacker-budget-remaining 10)  ; only if plenty of budget
     :easy false)))

(defn attacker-targeting-strategy
  "Rank disputes by attractiveness for attack.
   
   Returns function that ranks disputes by attack value.
   Higher value = better target for attacker."
  [num-disputes num-attackers]
  (fn [difficulty escrow-wei]
    (let [base-value
          (case difficulty
            :hard   100
            :medium 30
            :easy   0)
          escrow-bonus (/ escrow-wei 1e18)]  ; larger escrow = better
      (* base-value escrow-bonus))))

;; === Validation ===

(defn validate-difficulty-distribution
  "Verify that difficulty distribution sums to 1.0"
  [distribution]
  (let [total (+ (:easy distribution 0)
                 (:medium distribution 0)
                 (:hard distribution 0))]
    (if (< (Math/abs (- total 1.0)) 0.001)
      true
      (throw (ex-info "Invalid difficulty distribution"
                      {:distribution distribution
                       :total total})))))

(defn difficulty-stats
  "Compute statistics from sample of difficulties."
  [difficulties]
  (let [easy (count (filter #{:easy} difficulties))
        medium (count (filter #{:medium} difficulties))
        hard (count (filter #{:hard} difficulties))
        total (count difficulties)]
    {:easy (double (/ easy total))
     :medium (double (/ medium total))
     :hard (double (/ hard total))
     :samples total}))
