(ns resolver-sim.protocols.sew.research-models.information-cascade
  "Information cascade dynamics in sequential appeal system.
   
   Models: When later reviewers see prior decisions, they may:
   - Follow correct prior decisions (good)
   - Follow incorrect prior decisions (bad)
   - Escalate when uncertain (correct behavior)
   
   This is NOT the herding cascade from Phase P (which doesn't apply to sequential).
   Instead: Models information asymmetry and reputational pressure."
  (:require [resolver-sim.stochastic.rng :as rng]))

;; ============ Information Cascade Theory ============

(defn cascade-following-probability
  "Probability that a later resolver follows a prior decision.
   
   Theory: In information cascades (Banerjee 1992, Welch 1992):
   - If many people believe something, individuals follow despite private signals
   - Reputational pressure makes dissent costly
   - System can lock into wrong outcome
   
   Parameters:
   - prior-alignment: Does prior outcome match your view? [0.0 - 1.0]
     - 1.0 = prior strongly matches your signal
     - 0.0 = prior strongly contradicts your signal
   - reputation-weight: How much do you care about reputation? [0.0 - 1.0]
   - confidence-in-prior: How credible is prior decision? [0.0 - 1.0]
   
   Returns: Probability [0.0 - 1.0] of following prior"
  [prior-alignment reputation-weight confidence-in-prior]

  (let [; Base following probability
        alignment-factor prior-alignment

        ; Reputation pressure increases following probability
        reputation-factor (* reputation-weight 0.5)  ; scales [0 - 0.5]

        ; Confidence in prior increases following
        confidence-factor (* confidence-in-prior 0.3)  ; scales [0 - 0.3]

        ; Combined following probability
        p-follow (min 1.0
                      (+ alignment-factor reputation-factor confidence-factor))]
    p-follow))

(defn detect-cascade-error
  "Probability of detecting when a cascade locks in a wrong decision.
   
   Model: Cascades are sticky but not invulnerable.
   External evidence can break cascade if strong enough.
   
   Args:
   - rounds-in-cascade: How many rounds have same wrong decision?
   - evidence-strength: [0.0 - 1.0] How clear is evidence of error?
   - time-available: [0.5 - 2.0] Time pressure (1.0 = standard)
   
   Returns: Probability [0.0 - 1.0] of breaking cascade"
  [rounds-in-cascade evidence-strength time-available]

  (let [; More rounds = stickier cascade
        cascade-stickiness (/ rounds-in-cascade 3.0)

        ; Evidence can break cascade
        evidence-power (* evidence-strength (/ time-available 1.0))

        ; Break probability = evidence / stickiness
        p-break (/ evidence-power (+ 1.0 cascade-stickiness))]

    (min 1.0 p-break)))

;; ============ Sequential Decision Under Information Asymmetry ============

(defn review-prior-decision
  "Honest resolver reviews a prior decision in sequential appeal.
   
   Typical scenario:
   - Prior decision visible
   - New evidence available
   - Reputational pressure to agree
   - Time pressure to decide quickly
   
   Returns: What decision should be made?
   - :follow-prior: agree with prior
   - :escalate: disagree and escalate
   - :agree-independently: agree for own reasons"
  [rng ground-truth prior-decision prior-was-wrong?
   reputation-weight time-pressure-factor evidence-quality]

  (let [; Personal signal (what does evidence suggest?)
        accuracy-of-personal-signal (case time-pressure-factor
                                      1.0 0.70  ; Normal time
                                      1.5 0.60  ; Rushed
                                      0.5 0.85) ; Abundant time

        personal-believes-prior? (< (rng/next-double rng) accuracy-of-personal-signal)

        ; Cascade following probability
        prior-confidence (if prior-was-wrong? 0.3 0.8)  ; How credible is prior?
        prior-alignment (if (= prior-decision ground-truth) 1.0 0.0)

        p-follow (cascade-following-probability
                  prior-alignment reputation-weight prior-confidence)

        ; Decision logic
        decision (if (< (rng/next-double rng) p-follow)
                   ; Follow cascade/prior
                   prior-decision

                   ; Trust personal signal
                   (if personal-believes-prior?
                     prior-decision
                     (not prior-decision)))]

    {:decision decision
     :followed-prior? (= decision prior-decision)
     :personal-signal personal-believes-prior?
     :cascade-weight p-follow}))

;; ============ Herding Risk in Sequential (Different from Panel Herding) ============

(defn information-cascade-risk
  "Quantify cascade risk when honest resolvers follow wrong prior.
   
   NOT the same as panel herding (no coordination).
   Instead: Individual resolvers independently follow cascade.
   
   Scenario:
   - Round 0 makes wrong decision (but looks plausible)
   - Round 1 reviewer sees Round 0
   - Round 1 reviewer has reputational pressure to agree
   - Round 1 escalates less because it seems reasonable
   - System locks into wrong outcome
   
   Args:
   - num-rounds: How many rounds in system
   - reputation-weight: [0.0 - 1.0] Pressure to agree with prior
   - evidence-quality: [0.0 - 1.0] Clarity of ground truth
   
   Returns: Risk score [0.0 - 1.0]"
  [num-rounds reputation-weight evidence-quality]

  (let [; Base cascade risk
        base-risk (case num-rounds
                    1 0.0    ; Can't cascade with one resolver
                    2 0.30   ; Two resolvers: can lock in
                    3 0.50)  ; Three resolvers: sticky

        ; Reputation pressure increases risk
        reputation-risk (* reputation-weight 0.40)

        ; Good evidence decreases risk
        evidence-protection (* evidence-quality 0.30)

        ; Combined risk
        total-risk (min 1.0
                        (- (+ base-risk reputation-risk)
                           evidence-protection))]

    (max 0.0 total-risk)))

;; ============ Multi-Round Cascade Analysis ============

(defn analyze-cascade-trajectory
  "Analyze how a cascade builds or breaks across appeal rounds.
   
   Returns: {:risk-score, :vulnerable-rounds, :break-point, :recommendation}"
  [ground-truth round-decisions reputation-weight evidence-quality]

  (let [num-rounds (count round-decisions)

        ; Is system locked in wrong outcome?
        all-same? (apply = round-decisions)
        wrong-outcome (and all-same?
                           (not= (first round-decisions) ground-truth))

        ; Where could cascade break?
        break-probability (detect-cascade-error
                           (count round-decisions)
                           evidence-quality
                           1.0)  ; Standard time

        cascade-risk (information-cascade-risk
                      num-rounds reputation-weight evidence-quality)

        vulnerability (if wrong-outcome cascade-risk 0.0)]

    {:cascade-locked? wrong-outcome
     :cascade-risk cascade-risk
     :break-probability break-probability
     :vulnerability vulnerability
     :rounds (count round-decisions)
     :recommendation (cond
                       (> break-probability 0.8) "Cascade will likely break"
                       (< cascade-risk 0.2) "Low cascade risk"
                       (> cascade-risk 0.7) "HIGH CASCADE RISK - Consider external review"
                       :else "Moderate cascade risk")}))

;; ============ Cascade Prevention Strategies ============

(defn reduce-cascade-risk
  "Strategies to reduce cascade risk:
   
   1. Reputation-weighted voting: Weight by past accuracy
   2. Evidence oracles: Provide ground truth for hard cases
   3. Rotation: Prevent same reviewers from creating cascades
   4. Challenge period: Allow challenges before finalization
   
   Returns: Modified reputation-weight for cascade analysis"
  [base-reputation-weight strategy]

  (case strategy
    :no-strategy base-reputation-weight

    :reputation-weighting
    (* base-reputation-weight 0.5)  ; Good actors have more influence

    :evidence-oracles
    (* base-reputation-weight 0.3)  ; External truth breaks cascades

    :juror-rotation
    (* base-reputation-weight 0.4)  ; Prevents same group from cascading

    :challenge-period
    (* base-reputation-weight 0.6)  ; Early challenges reduce stickiness

    :combined
    (* base-reputation-weight 0.2)  ; All strategies together

    base-reputation-weight))

