(ns resolver-sim.research.sew.governance.capture-drift
  "Phase T: Governance Capture via Rule Drift.

   Tests whether an attacker with partial governance control can drive
   safety-critical parameters (slash multiplier, appeal bond) below
   viability thresholds via repeated small incremental votes.

   Three falsifiable hypotheses:

   H1 — Stake threshold:
     An attacker with ≤ 35% governance stake cannot breach safety floors
     in 100 epochs at abstention ≤ 30%, with or without timing optimisation.

   H2 — Timing attack:
     An attacker with 40% stake exploiting low-salience governance periods
     (abstention spike +20pp every 5th epoch) accumulates meaningfully more
     drift than the same stake without timing optimisation.  At 45% stake the
     threat is more acute: breach is expected within 50 epochs.

   H3 — Detect-and-revert safeguard:
     Auto-reverting one drift step whenever a 10-epoch window shows > 0.10
     accumulated drop keeps the 40% + timing scenario safe for 100 epochs.

   Safety floors:
     slash-multiplier ≥ 1.5   (below: fraud becomes profitable)
     appeal-bond-bps  ≥ 200   (below: spam appeals become cheap)

   Attacker strategy:
     Each epoch: propose to reduce slash-multiplier by 0.05 (then appeal-bond
     by 25 bps once the slash floor is threatened).  Changes take effect after
     governance-delay-epochs (default 5) — mimicking on-chain timelock.

   Vote model:
     Attacker controls `attacker-stake` fraction of votes.
     Defenders control `1 - attacker-stake`, but a fraction `abstention` do
     not vote.  The proposal passes when attacker votes > defender votes:
       attacker-stake > (1 - attacker-stake) × (1 - abstention)
     Abstention is drawn per epoch with ±5% noise around the base rate.
     Timing-attack epochs (every 5th) apply an additional +20pp spike."
  (:require [resolver-sim.stochastic.rng :as rng]
            [resolver-sim.sim.engine      :as proto]))

;; ── Constants ─────────────────────────────────────────────────────────────────

(def ^:private baseline-rules
  {:slash-multiplier 2.5
   :appeal-bond-bps  500
   :resolver-fee-bps 100})

(def ^:private safety-floors
  {:slash-multiplier 1.5
   :appeal-bond-bps  200})

;; ── Rule helpers ──────────────────────────────────────────────────────────────

(defn- safe?
  "True when all safety-critical parameters are above their floors."
  [rules]
  (and (>= (:slash-multiplier rules) (:slash-multiplier safety-floors))
       (>= (:appeal-bond-bps  rules) (:appeal-bond-bps  safety-floors))))

(defn- proposed-change
  "Attacker targets slash-multiplier first; switches to appeal-bond once
   slash reaches its floor."
  [rules]
  (cond
    (> (:slash-multiplier rules) (:slash-multiplier safety-floors))
    {:param :slash-multiplier :delta -0.05}

    (> (:appeal-bond-bps rules) (:appeal-bond-bps safety-floors))
    {:param :appeal-bond-bps :delta -25}

    :else nil))

(defn- apply-change [rules {:keys [param delta]}]
  (update rules param + delta))

;; ── Vote model ────────────────────────────────────────────────────────────────

(defn- vote-passes?
  "Returns true if the governance proposal passes.
   Draws abstention with ±5pp noise; timing-spike adds another +20pp."
  [^java.util.SplittableRandom d-rng attacker-stake base-abstention timing-spike?]
  (let [spike          (if timing-spike? (+ base-abstention 0.20) base-abstention)
        noise          (* 0.05 (- (* 2.0 (rng/next-double d-rng)) 1.0))
        eff-abstention (-> (+ spike noise) (min 0.95) (max 0.0))
        defender-vote  (* (- 1.0 attacker-stake) (- 1.0 eff-abstention))]
    (> attacker-stake defender-vote)))

;; ── Detect-and-revert ─────────────────────────────────────────────────────────

(defn- should-revert?
  "True when the last `window` entries of rule-history show > 0.10 cumulative
   drop in slash-multiplier.  Requires at least `window` history entries."
  [rule-history window]
  (when (>= (count rule-history) window)
    (let [recent (take-last window rule-history)
          oldest (:slash-multiplier (first  recent))
          newest (:slash-multiplier (last   recent))]
      (> (- oldest newest) 0.10))))

;; ── Single-epoch simulation ───────────────────────────────────────────────────

(defn- simulate-epoch
  "Advance one governance epoch.

   Returns:
     :rules-after    — effective rules at end of epoch
     :vote-passed?   — whether the attacker's proposal passed
     :reverted?      — whether the detect-and-revert safeguard fired
     :safe?          — whether rules are still above safety floors
     :pending        — proposals queued but not yet effective"
  [^java.util.SplittableRandom d-rng
   rules rule-history
   attacker-stake base-abstention
   timing-attack? detect-and-revert?
   epoch governance-delay pending-changes]
  (let [timing-spike? (and timing-attack? (zero? (mod epoch 5)))
        passed?       (vote-passes? d-rng attacker-stake base-abstention timing-spike?)
        change        (proposed-change rules)

        ;; Enqueue new proposal (if vote passed and a change is possible)
        new-pending   (if (and passed? change)
                        (conj pending-changes
                              {:effective-epoch (+ epoch governance-delay)
                               :change change})
                        pending-changes)

        ;; Apply proposals whose timelock has elapsed
        [apply-now still-pending] (split-with #(<= (:effective-epoch %) epoch)
                                              (sort-by :effective-epoch new-pending))
        rules-after-q (reduce apply-change rules (map :change apply-now))

        ;; Detect-and-revert: if drift window > 0.10 accumulated, undo one step
        reverted?     (and detect-and-revert?
                           (should-revert? (conj rule-history rules-after-q) 10))
        rules-final   (if reverted?
                        (update rules-after-q :slash-multiplier + 0.05)
                        rules-after-q)]

    {:rules-after  rules-final
     :vote-passed? passed?
     :reverted?    reverted?
     :safe?        (safe? rules-final)
     :pending      (vec still-pending)}))

;; ── Scenario runner ───────────────────────────────────────────────────────────

(defn run-drift-scenario
  "Run up to `n-epochs` of governance rule drift.

   Returns:
     :survived?           — true if safety floors were never breached
     :epochs-until-breach — epoch at which breach occurred (or n-epochs if none)
     :final-rules         — rule state at end of run
     :votes-passed        — total proposals that passed the vote
     :final-drift         — absolute drop in slash-multiplier from baseline"
  [attacker-stake base-abstention n-epochs governance-delay
   timing-attack? detect-and-revert? seed]
  (loop [epoch      1
         rules      baseline-rules
         rule-hist  []
         pending    []
         votes-won  0
         d-rng      (rng/make-rng seed)]
    (if (> epoch n-epochs)
      {:survived?           true
       :epochs-until-breach n-epochs
       :final-rules         rules
       :votes-passed        votes-won
       :final-drift         (- (:slash-multiplier baseline-rules)
                               (:slash-multiplier rules))}
      (let [{:keys [rules-after vote-passed? safe? pending]}
            (simulate-epoch d-rng rules rule-hist
                            attacker-stake base-abstention
                            timing-attack? detect-and-revert?
                            epoch governance-delay pending)]
        (if (not safe?)
          {:survived?           false
           :epochs-until-breach epoch
           :final-rules         rules-after
           :votes-passed        (+ votes-won (if vote-passed? 1 0))
           :final-drift         (- (:slash-multiplier baseline-rules)
                                   (:slash-multiplier rules-after))}
          (recur (inc epoch)
                 rules-after
                 (conj rule-hist rules-after)
                 pending
                 (+ votes-won (if vote-passed? 1 0))
                 d-rng))))))

;; ── Multi-seed aggregate ──────────────────────────────────────────────────────

(defn- run-multi-seed
  "Run a scenario across multiple seeds and aggregate."
  [attacker-stake base-abstention n-epochs governance-delay
   timing-attack? detect-and-revert? seeds]
  (let [results       (mapv #(run-drift-scenario attacker-stake base-abstention
                                                 n-epochs governance-delay
                                                 timing-attack? detect-and-revert? %)
                            seeds)
        survived      (count (filter :survived? results))
        breached      (remove :survived? results)
        breach-epochs (mapv :epochs-until-breach breached)
        mean-drift    (/ (double (reduce + (map :final-drift results)))
                         (count results))]
    {:attacker-stake     attacker-stake
     :base-abstention    base-abstention
     :timing-attack?     timing-attack?
     :detect-and-revert? detect-and-revert?
     :survival-rate      (/ (double survived) (count results))
     :survived-count     survived
     :total-seeds        (count results)
     :mean-breach-epoch  (when (seq breach-epochs)
                           (/ (double (reduce + breach-epochs))
                              (count breach-epochs)))
     :mean-drift         mean-drift
     :passed?            (>= (/ (double survived) (count results)) 0.80)}))

;; ── Phase T sweep ─────────────────────────────────────────────────────────────

(defn run-phase-t-sweep
  "Run Phase T: Governance Capture via Rule Drift.

   Sweeps attacker stake × abstention rate for H1, then compares
   timing-attack and detect-and-revert scenarios for H2/H3."
  [params]
  (let [base-seed        (:rng-seed params 42)
        seeds            (mapv #(rng/seed-from-index base-seed %) (range 5))
        n-epochs         (:n-epochs params 100)
        gov-delay        (:governance-delay-epochs params 5)
        surv-threshold   (double (:survival-threshold params 0.80))]

    (println "\n📊 PHASE T: GOVERNANCE CAPTURE VIA RULE DRIFT")
    (println "   H1: ≤35% stake cannot breach safety floors (100 epochs, abst ≤ 30%)")
    (println "   H2: 40% stake + timing attack accumulates materially more drift")
    (println "   H3: Detect-and-revert safeguard keeps 40%+timing safe for 100 epochs")
    (println (format "   Seeds: %d  Epochs: %d  Gov-delay: %d epochs"
                     (count seeds) n-epochs gov-delay))
    (println "")

    ;; ── H1: Stake threshold sweep ──────────────────────────────────────────
    (println "── H1: Stake Threshold (no timing attack, no safeguard) ─────────────")
    (let [h1-results
          (for [stake [0.20 0.30 0.35 0.40 0.45 0.49]
                abst  [0.10 0.20 0.30]]
            (run-multi-seed stake abst n-epochs gov-delay false false seeds))

          _ (doseq [r h1-results]
              (println (format "   stake=%.2f  abst=%.2f  survival=%.0f%%  drift=%.2f  %s"
                               (:attacker-stake r)
                               (:base-abstention r)
                               (* 100.0 (:survival-rate r))
                               (:mean-drift r)
                               (if (:passed? r) "✅" "❌"))))

          safe-low-stake (filter #(and (<= (:attacker-stake %) 0.35)
                                       (>= (:survival-rate %) surv-threshold))

                                 h1-results)
          total-low      (count (filter #(<= (:attacker-stake %) 0.35) h1-results))
          h1-holds?      (= (count safe-low-stake) total-low)]

      (println (format "\n   H1: %d/%d configs safe at stake ≤ 35%% → %s\n"
                       (count safe-low-stake) total-low
                       (if h1-holds? "✅ HOLDS" "❌ FAILS")))

      ;; ── H2: Timing attack ────────────────────────────────────────────────
      (println "── H2: Timing Attack Comparison ─────────────────────────────────────")
      (let [h2-pairs
            (for [stake [0.35 0.40 0.45]
                  abst  [0.20 0.30]]
              (let [without (run-multi-seed stake abst n-epochs gov-delay false false seeds)
                    with    (run-multi-seed stake abst n-epochs gov-delay true  false seeds)]
                {:stake         stake
                 :abst          abst
                 :drift-without (:mean-drift without)
                 :drift-with    (:mean-drift with)
                 :surv-without  (:survival-rate without)
                 :surv-with     (:survival-rate with)
                 :breach-with   (:mean-breach-epoch with)}))

            _ (doseq [r h2-pairs]
                (println (format "   stake=%.2f  abst=%.2f  drift: no-timing=%.2f  timing=%.2f  surv-timing=%.0f%%  %s"
                                 (:stake r) (:abst r)
                                 (:drift-without r) (:drift-with r)
                                 (* 100.0 (:surv-with r))
                                 (cond
                                   (nil? (:breach-with r)) "no breach"
                                   (< (:breach-with r) 50) (format "breach@~%.0f" (:breach-with r))
                                   :else (format "breach@~%.0f" (:breach-with r))))))

            ;; H2: timing is a confirmed vector if it either:
            ;;   (a) causes breach in a scenario that survived without timing, OR
            ;;   (b) materially increases drift (>0.20 more) in any 40%+ scenario
            h2-high-stake (filter #(>= (:stake %) 0.40) h2-pairs)
            h2-confirmed? (some (fn [r]
                                  (or (and (< (:surv-without r) 1.0)   ; breach without timing
                                           (some? (:breach-without r))) ; already failing — skip
                                      (and (>= (:surv-without r) surv-threshold) ; survived without timing
                                           (< (:surv-with r) surv-threshold))    ; failed with timing
                                      (> (- (:drift-with r) (:drift-without r)) 0.20)))  ; large drift delta
                                h2-high-stake)]

        (println (format "\n   H2: timing attack amplifies drift at 40%%+ stake → %s\n"
                         (if h2-confirmed?
                           "✅ CONFIRMED (timing is a real attack vector)"
                           "⚠️  NOT CONFIRMED (noise-dominated, timing is weak)")))

        ;; ── H3: Detect-and-revert safeguard ──────────────────────────────
        (println "── H3: Detect-and-Revert Safeguard ──────────────────────────────────")
        (let [h3-results
              (for [stake [0.40 0.45 0.49]
                    abst  [0.20 0.30]]
                (let [without (run-multi-seed stake abst n-epochs gov-delay true false seeds)
                      with    (run-multi-seed stake abst n-epochs gov-delay true true  seeds)]
                  {:stake         stake
                   :abst          abst
                   :surv-without  (:survival-rate without)
                   :surv-with     (:survival-rate with)
                   :breach-without (:mean-breach-epoch without)
                   :breach-with   (:mean-breach-epoch with)
                   :improvement   (- (:survival-rate with) (:survival-rate without))}))

              _ (doseq [r h3-results]
                  (println (format "   stake=%.2f  abst=%.2f  no-safeguard=%.0f%%  with-safeguard=%.0f%%  Δ=+%.0f%%"
                                   (:stake r) (:abst r)
                                   (* 100.0 (:surv-without r))
                                   (* 100.0 (:surv-with r))
                                   (* 100.0 (:improvement r)))))

              safe-40  (filter #(and (= (:stake %) 0.40)
                                     (>= (:surv-with %) surv-threshold))

                               h3-results)
              total-40 (count (filter #(= (:stake %) 0.40) h3-results))
              h3-holds? (= (count safe-40) total-40)]

          (println (format "\n   H3: safeguard keeps 40%% stake safe? %d/%d → %s\n"
                           (count safe-40) total-40
                           (if h3-holds? "✅ HOLDS" "❌ FAILS")))

          ;; ── Summary ──────────────────────────────────────────────────────
          (println "═══════════════════════════════════════════════════")
          (println "📋 PHASE T SUMMARY")
          (println "═══════════════════════════════════════════════════")
          (println (format "   H1 (≤35%% stake is safe):      %s"
                           (if h1-holds? "✅ HOLDS" "❌ FAILS")))
          (println (format "   H2 (timing amplifies drift):   %s"
                           (if h2-confirmed? "✅ CONFIRMED" "⚠️  INCONCLUSIVE")))
          (println (format "   H3 (detect-and-revert works):  %s"
                           (if h3-holds? "✅ HOLDS" "❌ FAILS")))
          (println "")
          (let [governance-safe? (and h1-holds? h3-holds?)
                confidence-delta (cond
                                   (and h1-holds? h3-holds?)
                                   "+5% (governance capture requires ≥40% stake; safeguard effective)"
                                   h1-holds?
                                   "+2% (stake threshold safe; safeguard improvement limited)"
                                   :else
                                   "0% (governance capture is a real production risk)")]
            (println (format "   Governance capture risk:  %s"
                             (if governance-safe? "LOW (with safeguard)" "MEDIUM-HIGH")))
            (println (format "   Confidence delta:         %s" confidence-delta))
            (println "")
            (if governance-safe?
              (do
                (println "   Recommendation: Deploy detect-and-revert safeguard.")
                (println "   Safe boundary: ≤35% stake without safeguard; ≤40% with safeguard.")
                (println "   For safety-critical param changes, enforce supermajority (>60%)."))
              (do
                (println "   Recommendation: Require supermajority (>60%) for all safety-critical")
                (println "   parameter changes (slash-multiplier, appeal-bond-bps).")))
            (println "")
            (proto/make-result
             {:benchmark-id "T"
              :label        "Governance Capture via Rule Drift"
              :hypothesis   "Detect-and-revert safeguard prevents capture at <= 40% attacker stake"
              :passed?      governance-safe?
              :results      {:h1 h1-results :h2 h2-pairs :h3 h3-results}
              :summary      {:h1-holds? h1-holds?
                             :h2-timing-confirmed? h2-confirmed?
                             :h3-holds? h3-holds?
                             :governance-safe? governance-safe?}})))))))
