(ns resolver-sim.stochastic.correlated-failures
  "Model correlated resolver behavior and shared failure modes.
   
   Single-resolver assumption: Each resolver decides independently.
   Reality: Resolvers share...
   - Geographic location → timezone biases
   - Language/culture → narrative interpretation
   - Training/priors → shared blindspots
   - News/social media → correlated information
   - Incentive structures → convergent behavior
   
   This creates phase transitions: System gracefully degrades with
   diversity, but collapses at certain correlation thresholds."
  (:require [resolver-sim.stochastic.rng :as rng]))

;; ============ Correlation Models ============

(defn shared-bias-effect
  "Model shared bias affecting multiple resolvers.
   
   Examples:
   - Bias toward 'established' accounts over new ones
   - Bias toward larger transactions
   - Cultural bias in language/narrative interpretation
   - Timezone bias (decisions made when tired vs. alert)
   
   Args:
   - bias-strength: [0.0-1.0] How much bias affects accuracy
   - ground-truth: What's actually true
   - num-resolvers: How many resolvers affected
   - correlation-coefficient: [0.0-1.0] How much they correlate
   - rng: optional SplittableRandom for reproducible sampling
   
   Returns: Accuracy distribution with shared bias"
  ([bias-strength ground-truth num-resolvers correlation-coefficient]
   (shared-bias-effect bias-strength ground-truth num-resolvers correlation-coefficient nil))
  ([bias-strength ground-truth num-resolvers correlation-coefficient rng]
   (let [roll #(rng/roll-double rng)
         shared-error (if (< (roll) bias-strength)
                        (not ground-truth)
                        ground-truth)
         individual-error (if (< (roll) 0.1)
                            (not ground-truth)
                            ground-truth)
         make-decision (fn [rho]
                         (if (< (roll) rho)
                           shared-error
                           individual-error))
         decisions (map (fn [_] (make-decision correlation-coefficient))
                        (range num-resolvers))
         agreeing (count (filter #(= % shared-error) decisions))
         agreement-fraction (/ agreeing num-resolvers)
         correct-count (count (filter #(= % ground-truth) decisions))
         accuracy (/ correct-count num-resolvers)]
     {:shared-error shared-error
      :ground-truth ground-truth
      :decisions decisions
      :agreement-fraction agreement-fraction
      :accuracy accuracy
      :correlation-coefficient correlation-coefficient
      :biased? (not= shared-error ground-truth)
      :systemic-risk? (and (not= shared-error ground-truth)
                           (> agreement-fraction 0.7))})))

(defn herding-dynamic
  "Model herding: Resolvers converge on same decision through:
   - Information cascade (later resolvers see prior)
   - Reputation pressure (don't want to differ from consensus)
   - Shared priors (natural convergence)
   
   Args:
   - round: Which appeal level (0, 1, 2)
   - prior-decision: What previous level decided
   - accuracy-if-independent: Accuracy without herding
   - reputation-pressure: [0.0-1.0] Cost of disagreeing
   - evidence-quality: [0.0-1.0] How clear is the case
   - rng: optional SplittableRandom for reproducible sampling
   
   Returns: Probability of herding (following prior)"
  ([round prior-decision accuracy-if-independent reputation-pressure evidence-quality]
   (herding-dynamic round prior-decision accuracy-if-independent reputation-pressure evidence-quality nil))
  ([round prior-decision accuracy-if-independent reputation-pressure evidence-quality rng]
   (let [round-factor (/ round 2.0)
         clarity-factor (- 1.0 evidence-quality)
         pressure-factor reputation-pressure
         accuracy-factor (- 1.0 accuracy-if-independent)
         base-herding (* round-factor clarity-factor pressure-factor accuracy-factor)
         herding-prob (min 1.0 (max 0.0 base-herding))]
     {:round round
      :prior-decision prior-decision
      :herding-probability herding-prob
      :reputation-pressure reputation-pressure
      :evidence-quality evidence-quality
      :will-herd? (< (rng/roll-double rng) herding-prob)
      :risk-level (cond
                    (< herding-prob 0.2) "LOW"
                    (< herding-prob 0.5) "MODERATE"
                    (< herding-prob 0.8) "HIGH"
                    :else "CRITICAL")})))

(defn shared-information-source
  "Model resolvers using same external information.
   
   If all resolvers use:
   - Same oracle (single point of failure)
   - Same social media narratives
   - Same trusted expert
   - Same language translator
   
   Then they become correlated in their errors.
   
   Args:
   - num-resolvers: How many use this source
   - source-accuracy: [0.0-1.0] How reliable is source
   - ground-truth: What's actually correct
   - rng: optional SplittableRandom for reproducible sampling
   
   Returns: Correlation created by shared source"
  ([num-resolvers source-accuracy ground-truth]
   (shared-information-source num-resolvers source-accuracy ground-truth nil))
  ([num-resolvers source-accuracy ground-truth rng]
   (let [roll #(rng/roll-double rng)
         source-signal (if (< (roll) source-accuracy)
                         ground-truth
                         (not ground-truth))
         follow-prob 0.8
         decisions (map (fn [_]
                          (if (< (roll) follow-prob)
                            source-signal
                            (if (< (roll) 0.5) ground-truth (not ground-truth))))
                        (range num-resolvers))
         agreeing-with-source (count (filter #(= % source-signal) decisions))
         agreement-fraction (/ agreeing-with-source num-resolvers)
         correct-count (count (filter #(= % ground-truth) decisions))
         accuracy (/ correct-count num-resolvers)]
     {:source-signal source-signal
      :source-accuracy source-accuracy
      :ground-truth ground-truth
      :decisions decisions
      :agreement-with-source agreement-fraction
      :accuracy accuracy
      :correlation-risk (cond
                          (< source-accuracy 0.7) "HIGH: Unreliable source creates errors"
                          (> agreement-fraction 0.9) "HIGH: Over-reliance on source"
                          :else "LOW: Good source with healthy skepticism")})))

(defn diversity-effect
  "Model how resolver diversity (geographic, institutional, training)
   reduces correlated failures.
   
   Args:
   - diversity-level: [0.0-1.0] How diverse are resolvers
   - correlation-base: [0.0-1.0] Natural correlation without diversity
   
   Returns: Effective correlation after diversity adjustment"
  [diversity-level correlation-base]

  (let [; Diversity reduces correlation
        ; At diversity=1.0, correlation approaches 0
        effective-correlation (* correlation-base (- 1.0 diversity-level))

        ; System resilience depends on diversity
        resilience (+ 0.5 (* diversity-level 0.5))]

    {:diversity-level diversity-level
     :correlation-base correlation-base
     :effective-correlation effective-correlation
     :resilience resilience
     :recommendation (cond
                       (< effective-correlation 0.2) "STRONG: Good diversity, low correlation risk"
                       (< effective-correlation 0.5) "ACCEPTABLE: Moderate diversity"
                       (< effective-correlation 0.8) "WEAK: High correlation risk"
                       :else "CRITICAL: Resolvers are essentially clones")}))

(defn correlation-phase-transition
  "Model phase transition: System gracefully handles low correlation,
   but at high correlation (0.6+), behavior inverts and system fails.
   
   Think of it as a bifurcation in dynamical systems:
   - Low ρ: System self-corrects through appeals
   - Medium ρ: Some herding but Kleros breaks it
   - High ρ: Information locks in, cascades can't be broken
   
   Args:
   - correlation-coefficient: [0.0-1.0] Overall resolver correlation
   - system-accuracy-base: Accuracy if independent
   - appeal-effectiveness: [0.0-1.0] How well Kleros breaks cascades
   
   Returns: Phase transition analysis"
  [correlation-coefficient system-accuracy-base appeal-effectiveness]

  (let [; Linear degradation at low correlation
        ; Nonlinear collapse at high correlation
        rho correlation-coefficient

        ; System accuracy under different regimes
        ; Low correlation: Independent decisions, appeals work well
        ; Medium correlation: Some cascade, Kleros still effective
        ; High correlation: Information locked in, system fails

        accuracy-at-rho (cond
                          (< rho 0.3)
                         ; Low regime: Independent decisions
                          (- system-accuracy-base (* rho 0.1))

                          (< rho 0.6)
                         ; Medium regime: Some herding, appeals help
                          (- system-accuracy-base
                             (+ (* rho 0.2) (* rho (- 1.0 appeal-effectiveness) 0.1)))

                          :else
                         ; High regime: Collapse, appeals ineffective
                          (let [collapse-factor (- rho 0.6)
                                collapse-loss (* collapse-factor 0.4)]
                            (- system-accuracy-base collapse-loss)))

        ; Kleros effectiveness at breaking cascades
        ; NOTE: This kleros-effectiveness value is computed independently from the
        ; main model parameters (:l2-detection-prob, :p-l2-reversal, :has-kleros?).
        ; It is not wired into the MC detection pipeline (detection/l2-slashed?) or
        ; the closed-form economics (economics/fraud-survival-probability).  It exists
        ; solely as a diagnostic output for the correlated-failures report.  To make
        ; correlation degrade Kleros effectiveness in the main model, wire this value
        ; back into :l2-detection-prob or :p-l2-reversal during scenario setup.
        kleros-effectiveness (cond
                               (< rho 0.3) appeal-effectiveness
                               (< rho 0.6) (* appeal-effectiveness 0.7)
                               :else (* appeal-effectiveness 0.3))

        ; Regime identification
        regime (cond
                 (< rho 0.3) "LOW_CORRELATION"
                 (< rho 0.6) "MEDIUM_CORRELATION"
                 :else "HIGH_CORRELATION")]

    {:correlation-coefficient correlation-coefficient
     :system-accuracy-base system-accuracy-base
     :appeal-effectiveness appeal-effectiveness
     :accuracy-at-correlation accuracy-at-rho
     :kleros-effectiveness kleros-effectiveness
     :regime regime
     :phase-transition-detected? (> rho 0.6)
     :risk-assessment (case regime
                        "LOW_CORRELATION" "SAFE: System self-corrects"
                        "MEDIUM_CORRELATION" "ACCEPTABLE: Appeals effective"
                        "HIGH_CORRELATION" "CRITICAL: Cascades may lock in")
     :recommendation (cond
                       (< rho 0.3)
                       "No action needed. Natural diversity sufficient."

                       (< rho 0.6)
                       "Monitor cascade rates. Ensure geographic + institutional diversity."

                       :else
                       "EMERGENCY: Resolvers too correlated. Add external oracles or expand panel.")}))

(defn resolver-pool-correlation
  "Estimate correlation given resolver pool composition.
   
   Args:
   - num-resolvers: Total resolvers in system
   - geographic-diversity: [0.0-1.0] How spread out
   - institutional-diversity: [0.0-1.0] How many different organizations
   - training-diversity: [0.0-1.0] Different backgrounds/languages
   
   Returns: Estimated correlation and risk"
  [num-resolvers geographic-diversity institutional-diversity training-diversity]

  (let [; Correlation decreases with diversity
        ; Assume independent effects
        base-correlation 0.8  ; Start high (humans naturally correlated)
        geo-reduction (* geographic-diversity 0.3)
        inst-reduction (* institutional-diversity 0.3)
        train-reduction (* training-diversity 0.2)

        effective-correlation (max 0.0
                                   (- base-correlation
                                      geo-reduction inst-reduction train-reduction))

        ; Larger pools reduce correlation per resolver
        pool-size-reduction (/ 1.0 (max 1.0 (Math/sqrt num-resolvers)))
        adjusted-correlation (* effective-correlation pool-size-reduction)]

    {:num-resolvers num-resolvers
     :geographic-diversity geographic-diversity
     :institutional-diversity institutional-diversity
     :training-diversity training-diversity
     :base-correlation base-correlation
     :reduction-from-diversity (- base-correlation effective-correlation)
     :pool-size-effect pool-size-reduction
     :estimated-correlation adjusted-correlation
     :risk-level (cond
                   (< adjusted-correlation 0.2) "LOW"
                   (< adjusted-correlation 0.4) "MODERATE"
                   (< adjusted-correlation 0.6) "ELEVATED"
                   :else "HIGH")}))
