(ns resolver-sim.research.sew.governance.adversary
  "Phase AA: Governance as Adversary — Selective Enforcement Gaming.

   Tests whether attackers can achieve >20% win rate by gaming governance
   response patterns, specifically:
   - Governance bandwidth limit: only 3 disputes reviewed per epoch
   - Governance bias: high-value disputes reviewed preferentially
   - Low-value dispute flooding: attacker exploits the invisible window
   - Attacker learning: attacker adapts to governance thresholds over time

   Hypothesis to falsify:
     'Attackers cannot exceed 20% win rate via governance gaming, even when
      governance capacity is limited and biased toward high-value disputes.'

   Also covers the governance capture gap (rule drift) not tested in Phases M/J."
  (:require [resolver-sim.stochastic.rng :as rng]
            [resolver-sim.sim.engine :as proto]
            [resolver-sim.research.sew.governance.bandwidth-floor :as floor]))

;; ============ Governance Models (Pure) ============

(defn governance-review-probability
  "Probability that governance reviews a dispute given its value."
  [dispute-value]
  (cond
    (>= dispute-value 100000) 0.95
    (>= dispute-value 10000)  0.60
    :else                     0.20))

(defn select-reviewed-disputes
  "Governance reviews up to capacity, prioritizing by value and probability."
  [disputes capacity d-rng]
  (let [candidates (filter (fn [d] (> (rng/next-double d-rng)
                                      (- 1.0 (governance-review-probability (:value d)))))
                           disputes)
        sorted (sort-by :value > candidates)]
    (take capacity sorted)))

(defn simulate-dispute-outcome
  "Determine if an attacker wins a dispute.

   base-win-prob:     win probability when unreviewed
                      Default 0.22 — calibrated from the deterministic invariant suite:
                      9 of 41 adversarial scenarios produce a successful attacker outcome
                      (9/41 ≈ 0.22). This replaces the previous unjustified 0.35 assumption.
   reviewed-win-prob: win probability when governance reviews the dispute
                      Default 0.03 — maintains ~7× governance-catch ratio (0.22/0.03 ≈ 7.3),
                      matching the deterrence multiplier implicit in the original model."
  ([dispute reviewed-ids d-rng]
   (simulate-dispute-outcome dispute reviewed-ids d-rng 0.22 0.03))
  ([dispute reviewed-ids d-rng base-win-prob reviewed-win-prob]
   (let [reviewed? (contains? reviewed-ids (:id dispute))
         win-prob  (if reviewed? reviewed-win-prob base-win-prob)]
     (< (rng/next-double d-rng) win-prob))))

(defn infer-grey-zone
  "Attacker analyzes history to find the highest value with <20% review rate."
  [history]
  (let [by-value (group-by (fn [d] (cond (< (:value d) 10000) :low
                                         (< (:value d) 100000) :med
                                         :else :high)) history)
        stats (for [[k v] by-value]
                [k (/ (double (count (filter :reviewed v))) (count v))])]
    (if-let [best (first (sort-by second < (filter (fn [[_ prob]] (< prob 0.3)) stats)))]
      (first best)
      :low)))

;; ============ Engine Adapters ============

(defn biased-review-probability
  "Governance attention probability, with optional bias override per value tier.

   bias-high: review probability for disputes valued ≥ 100,000 (default 0.95)
   bias-med:  review probability for disputes valued ≥ 10,000  (default 0.60)
   bias-low:  review probability for disputes below 10,000     (default 0.20)"
  [dispute-value bias]
  (cond
    (>= dispute-value 100000) (get bias :bias-high 0.95)
    (>= dispute-value 10000)  (get bias :bias-med  0.60)
    :else                     (get bias :bias-low  0.20)))

(defn select-reviewed-disputes-biased
  "Like select-reviewed-disputes but applies the bias map to review probabilities."
  [disputes capacity bias d-rng]
  (let [candidates (filter (fn [d] (> (rng/next-double d-rng)
                                      (- 1.0 (biased-review-probability (:value d) bias))))
                           disputes)
        sorted (sort-by :value > candidates)]
    (take capacity sorted)))

(defn- reviewed-id-set
  "Normalise any review-selection result to a set of dispute ids.
   The floor selector and the value/biased selectors return different shapes
   (set of ids vs seq of dispute maps); this makes them uniform for
   `contains?` checks in dispute-outcome simulation."
  [selection]
  (if (set? selection)
    selection
    (set (map :id selection))))

(defn select-reviewed-disputes-for-epoch
  "Choose which disputes governance reviews for an epoch.

   Selection is driven entirely by params — nothing is hardcoded:
   - `:bias` map present -> biased value-tier attendance (e.g. TEST 3)
   - `:floor-reviews` > 0 -> mandatory low-value floor + value-tier review (Phase AD-style)
   - otherwise -> plain value-prioritised probabilistic review (unmitigated baseline)

   Returns a set of reviewed dispute ids."
  [disputes capacity {:keys [bias floor-reviews floor-threshold]} d-rng]
  (let [floor-reviews   (or floor-reviews 0)
        floor-threshold (or floor-threshold 10000)]
    (reviewed-id-set
     (cond
       (seq bias) (select-reviewed-disputes-biased disputes capacity bias d-rng)
       (pos? floor-reviews) (floor/select-reviewed-with-floor
                              disputes capacity floor-reviews floor-threshold d-rng)
       :else (select-reviewed-disputes disputes capacity d-rng)))))

(defn simulate-epoch-aa
  [epoch state params d-rng]
  (let [{:keys [capacity learning?]
         :or   {capacity 5 learning? false}} params
        bwp           (double (get params :base-win-prob 0.22))
        rwp           (double (get params :reviewed-win-prob 0.03))
        disputes-per-epoch (int (get params :disputes-per-epoch 5))
        history       (:history state [])
        attacker-strategy (if (and learning? (> epoch 20))
                            (infer-grey-zone history)
                            :random)

        epoch-disputes (for [i (range disputes-per-epoch)]
                         (let [val (case attacker-strategy
                                :low    (rng/next-int d-rng 9999)
                                :med    (+ 10000 (rng/next-int d-rng 89999))
                                :high   (+ 100000 (rng/next-int d-rng 100000))
                                :random (+ 1000 (rng/next-int d-rng 150000)))]
                           {:id (str epoch "-" i) :value val}))

        reviewed-ids (select-reviewed-disputes-for-epoch epoch-disputes capacity params d-rng)

        outcomes (for [d epoch-disputes]
                   (let [won? (simulate-dispute-outcome d reviewed-ids d-rng bwp rwp)]
                     (assoc d :won won? :reviewed (contains? reviewed-ids (:id d)))))

        new-wins (count (filter :won outcomes))]

    {:epoch epoch
     :history (concat history outcomes)
     :total-wins (+ (:total-wins state 0) new-wins)
     :total-attempts (+ (:total-attempts state 0) (count epoch-disputes))}))

(defn summarize-aa-history
  [history params]
  (let [final (last history)
        threshold (double (get params :max-op-win-rate-threshold 0.20))
        total-wins (:total-wins final)
        total-attempts (:total-attempts final)
        win-rate (if (pos? total-attempts)
                   (/ (double total-wins) total-attempts)
                   0.0)
        passed? (< win-rate threshold)]
    {:status (if passed? "✅ SAFE" "❌ VULNERABLE")
     :win-rate win-rate
     :class (if passed? "A" "C")
     :passed? passed?
     :threshold threshold}))

(defn- derive-prescriptive-thresholds
  "Compute actionable thresholds from observed AA outcomes.

   Model approximation:
     win-rate ≈ base-win - review-rate × (base-win - reviewed-win)

   base-win, reviewed-win, target-win-rate and disputes-per-epoch are read from
   params (defaults match the calibrated values: 0.22/0.03, 0.20, 5).
   Returns guidance for minimum review effectiveness and rough capacity floor."
  ([results base-win reviewed-win]
   (derive-prescriptive-thresholds results base-win reviewed-win 0.20 5.0))
  ([results base-win reviewed-win target-win-rate disputes-per-epoch]
   (let [review-delta (- base-win reviewed-win)
         required-review-rate (-> (/ (- base-win target-win-rate) review-delta)
                                  (max 0.0)
                                  (min 1.0))
         worst-win-rate (apply max (map :win-rate results))
         observed-review-gain (-> (/ (- worst-win-rate reviewed-win) review-delta)
                                  (max 0.0)
                                  (min 1.0))
         required-capacity-floor (Math/ceil (* disputes-per-epoch required-review-rate))
         envelope
         (cond
           (< worst-win-rate target-win-rate) :green
           (< worst-win-rate (+ target-win-rate 0.05)) :yellow
           :else :red)]
     {:target-win-rate target-win-rate
      :required-review-rate required-review-rate
      :required-capacity-floor (long required-capacity-floor)
      :worst-win-rate worst-win-rate
      :implied-review-rate-worst-case (- 1.0 observed-review-gain)
      :envelope envelope})))

;; ============ Scenario Definitions ============

(defn make-scenarios [seed]
  [{:label "TEST 1: Baseline (High capacity, naive attacker)"
    :initial-state {:history [] :total-wins 0 :total-attempts 0}
    :update-fn simulate-epoch-aa
    :summary-fn summarize-aa-history
    :epochs 50
    :seed seed
    :params {:capacity 5 :learning? false}}

   {:label "TEST 2: Limited Capacity (Cap=3, learning attacker)"
    :initial-state {:history [] :total-wins 0 :total-attempts 0}
    :update-fn simulate-epoch-aa
    :summary-fn summarize-aa-history
    :epochs 50
    :seed (+ seed 1)
    :params {:capacity 3 :learning? true}}

   {:label "TEST 3: Biased Governance (Focus on high-value; low-value bias-low=0.05)"
    :initial-state {:history [] :total-wins 0 :total-attempts 0}
    :update-fn simulate-epoch-aa
    :summary-fn summarize-aa-history
    :epochs 50
    :seed (+ seed 2)
    ;; Governance reviews high-value disputes almost always, low-value almost never.
    ;; Attacker learns to stay in the low-value blind spot.
    :params {:capacity 3
             :bias {:bias-high 0.95 :bias-med 0.30 :bias-low 0.05}
             :learning? true}}

   {:label "TEST 4: Low-Value Flooding (cap=2, learning attacker)"
    :initial-state {:history [] :total-wins 0 :total-attempts 0}
    :update-fn simulate-epoch-aa
    :summary-fn summarize-aa-history
    :epochs 50
    :seed (+ seed 3)
    :params {:capacity 2 :learning? true}}

   {:label "TEST 5: [STRESS] Below-Minimum Capacity (cap=1, learning attacker)"
    :initial-state {:history [] :total-wins 0 :total-attempts 0}
    :update-fn simulate-epoch-aa
    :summary-fn summarize-aa-history
    :epochs 50
    :seed (+ seed 4)
    ;; cap=1 is below the minimum viable configuration (floor requires ≥1 of 5 = 20% coverage).
    ;; Expected to fail — included as a stress test to show the hard lower bound.
    :stress-test? true
    :params {:capacity 1 :learning? true}}])

;; ============ Full Phase AA Run ============

(defn run-phase-aa-sweep
  "Run all Phase AA governance gaming tests."
  [params]
  (let [seed          (:rng-seed params 42)
        base-win-prob (double (get params :base-win-prob 0.22))
        rev-win-prob  (double (get params :reviewed-win-prob 0.03))
        disputes-per-epoch (double (get params :disputes-per-epoch 5))
        max-win-rate-threshold (double (get params :max-op-win-rate-threshold 0.20))
        _  (proto/print-phase-header
            {:benchmark-id "AA"
             :label        "Governance as Adversary"
             :hypothesis   (format "Attackers cannot exceed %.0f%% win rate via governance gaming"
                                   (* 100 max-win-rate-threshold))})

        scenarios (make-scenarios seed)
        results (proto/run-sweep "PHASE AA SWEEP" scenarios params)

        ;; Separate operational scenarios from stress tests
        op-results     (remove :stress-test? scenarios)
        op-results     (filter (fn [r] (some #(= (:label %) (:label r)) op-results)) results)
        stress-results (filter :stress-test? scenarios)
        stress-results (filter (fn [r] (some #(= (:label %) (:label r)) stress-results)) results)

        class-a (count (filter #(= "A" (:class %)) op-results))
        class-c (count (filter #(= "C" (:class %)) op-results))
        max-op-win-rate (apply max (map :win-rate op-results))
        ;; Hypothesis applies only to operational scenarios (cap ≥ 2, above minimum viable)
        hypothesis-holds? (< max-op-win-rate max-win-rate-threshold)
        guidance (derive-prescriptive-thresholds results base-win-prob rev-win-prob
                                                 max-win-rate-threshold disputes-per-epoch)
        envelope-msg (case (:envelope guidance)
                       :green "SAFE ENVELOPE: current governance profile meets target"
                       :yellow "WARNING ENVELOPE: near threshold; harden governance capacity"
                       :red "RED ENVELOPE: redesign/strong safeguards required before mainnet")]

    (when (seq stress-results)
      (println "\n⚠️  STRESS TESTS (below minimum viable capacity — expected to fail):")
      (doseq [r stress-results]
        (println (format "   %s → %.1f%% win rate" (:label r) (* 100 (:win-rate r))))))

    (proto/print-phase-footer
     {:benchmark-id  "AA"
      :passed?       hypothesis-holds?
      :summary-lines [(format "Win-prob calibration: base=%.2f (9/41 invariant suite), reviewed=%.2f (~7x catch ratio)"
                              base-win-prob rev-win-prob)
                      (format "Robust (A): %d  Fragile (C): %d (operational scenarios only)" class-a class-c)
                      (format "Max attacker win rate (operational): %.1f%%" (* 100 max-op-win-rate))
                      (format "Required reviewed-share to keep attacker ≤ %.0f%%: %.1f%%"
                              (* 100 (:target-win-rate guidance))
                              (* 100 (:required-review-rate guidance)))
                      (format "Approx capacity floor (5 disputes/epoch model): %d reviews/epoch"
                              (:required-capacity-floor guidance))
                      (str "Policy envelope: " envelope-msg)
                      "→ Remediation: Phase AD below shows floor ≥ 2/epoch closes the no-floor gap"]})

    (proto/make-result
     {:benchmark-id "AA"
      :label        "Governance as Adversary"
      :hypothesis   "Attackers cannot exceed 20% win rate under viable governance capacity (cap ≥ 2)"
      :passed?      hypothesis-holds?
      :results      results
      :summary      {:class-a class-a
                     :class-c class-c
                     :max-win-rate max-op-win-rate
                     :policy-guidance guidance}})))
