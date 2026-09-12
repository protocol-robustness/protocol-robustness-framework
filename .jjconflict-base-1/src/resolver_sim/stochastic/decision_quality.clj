(ns resolver-sim.stochastic.decision-quality
  "Per-round decision quality under time pressure and evidence constraints.
   
   Models the sequential appeal system where each round has different:
   - Time pressure (hours to decide)
   - Evidence availability (what information is known)
   - Verification thoroughness (how carefully resolver reviews)
   
   This replaces the invalid parallel-panel herding model with realistic
   sequential appeal dynamics."
  (:require [resolver-sim.stochastic.rng :as rng]))

;; ============ Round Configurations ============

(def ROUND_CONFIGS
  "Configuration for each appeal round.
   
   Round 0: Initial resolver (24-hour deadline, limited evidence)
   Round 1: Senior resolver (48-hour deadline, full evidence review)
   Round 2: External resolver (unlimited time, Kleros arbitration)"
  {0 {:name "Initial Resolver"
      :time-hours 24
      :description "Fast decision under time pressure"
      :evidence-available? false  ; limited - must decide quickly
      :escalation-window-hours 48}

   1 {:name "Senior Resolver"
      :time-hours 48
      :description "Thorough review with full evidence"
      :evidence-available? true   ; can review prior decision + new evidence
      :escalation-window-hours 72}

   2 {:name "External Resolver (Kleros)"
      :time-hours 168  ; one week
      :description "Final arbitration with unlimited resources"
      :evidence-available? true   ; full case history
      :escalation-window-hours 0} ; final, no appeal
   })

;; ============ Time Pressure Impact ============

(defn accuracy-loss-from-time-pressure
  "As time pressure increases, decision accuracy decreases.
   
   Models realistic constraint: must decide quickly or escalate.
   
   Returns: accuracy multiplier [0.0 - 1.0]
   - 1.0 = unlimited time (best accuracy)
   - 0.5 = high time pressure (50% accuracy loss)"
  [hours-available max-hours]
  (let [pressure-ratio (/ hours-available max-hours)]
    ; Quadratic accuracy loss under time pressure
    ; More time = better accuracy, but with diminishing returns
    (* pressure-ratio
       (+ 0.5 (/ 0.5 (Math/sqrt pressure-ratio))))))

(defn decision-accuracy-by-round
  "Accuracy of decision-maker in each round, given time constraints.
   
   Args:
   - round: 0 (initial), 1 (senior), 2 (external)
   - time-pressure-factor: [0.0 - 2.0] (1.0 = standard time)
   
   Returns: accuracy [0.5 - 1.0]"
  [round time-pressure-factor]
  (let [config (get ROUND_CONFIGS round)
        base-hours (:time-hours config)
        available-hours (* base-hours (/ 1.0 time-pressure-factor))
        max-hours base-hours

        ; Base accuracy increases with round (better reviewers)
        base-accuracy (case round
                        0 0.70  ; Initial: 70% baseline
                        1 0.85  ; Senior: 85% baseline (review advantage)
                        2 0.95) ; External: 95% baseline (Kleros standard)

        ; Apply time pressure penalty
        time-penalty (accuracy-loss-from-time-pressure available-hours max-hours)

        ; Combined accuracy
        final-accuracy (* base-accuracy time-penalty)]

    (max 0.5 (min 1.0 final-accuracy))))

;; ============ Evidence Availability ============

(defn evidence-bonus-for-review
  "Bonus accuracy when reviewing prior decisions.
   
   Later rounds have access to:
   - Prior decision and reasoning
   - Appeal arguments
   - New evidence submitted
   
   This is an INFORMATION ADVANTAGE, not a communication advantage.
   Enables detection of errors but not real-time coordination."
  [round]
  (case round
    0 0.0      ; Initial - no prior to review
    1 0.10     ; Senior - can see Round 0, gets 10% accuracy bonus
    2 0.15))   ; External - full history, gets 15% accuracy bonus

(defn detect-error-in-prior-decision
  "Can a later resolver detect an error made by earlier round?
   
   Models: how obvious is an incorrect decision?
   - Easy cases: error is obvious
   - Medium cases: error requires careful review
   - Hard cases: error requires external expertise
   
   Args:
   - round: current round (1 or 2, not 0)
   - prior-round: previous round (0 or 1)
   - prior-was-wrong?: whether prior decision was incorrect
   - difficulty: 'easy', 'medium', 'hard'
   - evidence-quality: [0.0 - 1.0] how clear is evidence
   
   Returns: probability [0.0 - 1.0] of detecting error"
  [round prior-round prior-was-wrong? difficulty evidence-quality]
  (if (not prior-was-wrong?)
    0.0  ; No error to detect

    (let [; Base detection rate by difficulty
          base-rate (case difficulty
                      "easy" 0.95
                      "medium" 0.60
                      "hard" 0.20)

          ; Evidence quality improves detection
          evidence-factor (* 0.5 (+ 1.0 evidence-quality))

          ; Later rounds (with more time) detect better
          round-factor (case [prior-round round]
                         [0 1] 1.0    ; Senior reviewing initial
                         [1 2] 1.1    ; External reviewing senior
                         [0 2] 1.2)   ; External reviewing initial

          ; Combined detection probability
          detection (min 1.0
                         (* base-rate evidence-factor round-factor))]
      detection)))

;; ============ Honest Behavior ============

(defn honest-resolver-decision
  "Honest resolver makes decision based on evidence and accuracy.
   
   Args:
   - rng: random number generator
   - round: 0, 1, or 2
   - ground-truth: correct decision
   - time-pressure-factor: [0.0 - 2.0]
   - prior-decision: prior round's decision (nil for round 0)
   - prior-was-wrong?: whether prior decision was incorrect
   - difficulty: 'easy', 'medium', 'hard'
   - evidence-quality: [0.0 - 1.0]
   
   Returns: decision (same as ground-truth or opposite)"
  [rng round ground-truth time-pressure-factor prior-decision prior-was-wrong?
   difficulty evidence-quality]

  (let [; Get accuracy for this round
        accuracy (decision-accuracy-by-round round time-pressure-factor)

        ; Can this round detect prior error?
        detect-error (if (and prior-decision (> round 0))
                       (detect-error-in-prior-decision
                        round (dec round) prior-was-wrong?
                        difficulty evidence-quality)
                       0.0)

        ; Decision logic:
        ; 1. If round 0: use accuracy against ground truth
        ; 2. If round > 0 and detect prior error: correct it
        ; 3. If round > 0 and don't detect: follow prior

        decision (if (= round 0)
                   ; Initial round: decide based on accuracy vs truth
                   (if (< (rng/next-double rng) accuracy)
                     ground-truth
                     (not ground-truth))

                   ; Appeal rounds: review prior
                   (if (and prior-decision prior-was-wrong?)
                     ; If prior was wrong and we detect it, correct
                     (if (< (rng/next-double rng) detect-error)
                       ground-truth  ; Detected error, correct it
                       prior-decision) ; Didn't detect, follow prior

                     ; Prior decision matches or was correct
                     prior-decision))]

    decision))

;; ============ Attacker Behavior ============

(defn attacker-resolver-decision
  "Attacker resolves disputes in favor of their interests.
   
   Model: Attacker controls resolver and makes favorable decision.
   
   Args:
   - honest-path: what would honest resolver decide
   - attack-favors-outcome: what outcome benefits attacker (true/false)
   - corruption-probability: [0.0 - 1.0] reliability of bribe
   
   Returns: decision"
  [honest-path attack-favors-outcome corruption-probability]

  (if (>= corruption-probability 1.0)
    ; Direct corruption: decision is always favorable
    attack-favors-outcome

    ; Probabilistic corruption
    attack-favors-outcome))

;; ============ Round Simulation ============

(defn simulate-round
  "Simulate a single round of dispute resolution.
   
   Args:
   - rng: random number generator
   - round: 0, 1, or 2
   - ground-truth: correct decision
   - resolver-honest?: is resolver honest or corrupted
   - corruption-level: [0.0 - 1.0] (ignored if honest)
   - time-pressure-factor: [0.5 - 2.0] (1.0 = standard)
   - prior-decision: decision from prior round (nil for round 0)
   - difficulty: 'easy', 'medium', 'hard'
   - evidence-quality: [0.0 - 1.0]
   
   Returns: {:decision true/false, :was-error boolean}"
  [rng round ground-truth resolver-honest? corruption-level
   time-pressure-factor prior-decision difficulty evidence-quality]

  (let [decision (if resolver-honest?
                   ; Honest decision with time pressure effects
                   (honest-resolver-decision
                    rng round ground-truth time-pressure-factor
                    prior-decision (not= prior-decision ground-truth)
                    difficulty evidence-quality)

                   ; Corrupted decision (opposite of truth)
                   (not ground-truth))

        was-error (not= decision ground-truth)]

    {:decision decision
     :was-error was-error
     :round round
     :resolver-honest resolver-honest?}))

;; ============ Sequential Appeal Simulation ============

(defn simulate-full-appeal
  "Simulate complete appeal process through all rounds.
   
   Matches contract behavior:
   - Round 0: Initial resolver (24 hours)
   - If appealed: Round 1 senior (48 hours)
   - If appealed: Round 2 external (unlimited)
   
   Args:
   - rng: random number generator
   - ground-truth: correct decision
   - resolver-corruption: [honest, round-1-corrupt?, round-2-corrupt?]
   - time-pressures: [pressure-r0, pressure-r1, pressure-r2]
   - difficulty: 'easy', 'medium', 'hard'
   - evidence-quality: [0.0 - 1.0]
   - appeal-threshold: accuracy needed to appeal (default 0.6)
   
   Returns: {:final-decision, :rounds [r0, r1, r2], :escalation-count}"
  [rng ground-truth resolver-corruption time-pressures difficulty evidence-quality
   & {:keys [appeal-threshold] :or {appeal-threshold 0.6}}]

  (let [[r0-honest? r1-corrupt? r2-corrupt?] resolver-corruption
        [tp0 tp1 tp2] time-pressures

        ; Round 0: Initial decision
        r0 (simulate-round rng 0 ground-truth r0-honest? 0.0
                           tp0 nil difficulty evidence-quality)

        ; Decision to appeal: if error detected or accuracy low
        should-appeal-r0 (or (:was-error r0)
                             (< (rng/next-double rng) (- 1.0 appeal-threshold)))

        ; Round 1: Senior review (if appealed)
        [r1 should-appeal-r1] (if should-appeal-r0
                                (let [r1 (simulate-round rng 1 ground-truth
                                                         (not r1-corrupt?)
                                                         (if r1-corrupt? 1.0 0.0)
                                                         tp1 (:decision r0)
                                                         difficulty evidence-quality)]
                                  [r1 (or (:was-error r1)
                                          (< (rng/next-double rng) (- 1.0 appeal-threshold)))])
                                [nil false])

        ; Round 2: External review (if appealed again)
        r2 (if (and r1 should-appeal-r1)
             (let [; External resolver (Kleros) is always honest
                   r2 (simulate-round rng 2 ground-truth true 0.0
                                      tp2 (:decision r1)
                                      difficulty evidence-quality)]
               r2)
             nil)

        ; Final decision is from highest round reached
        final-decision (cond
                         r2 (:decision r2)
                         r1 (:decision r1)
                         :else (:decision r0))

        escalation-count (cond-> 0
                           should-appeal-r0 (+ 1)
                           should-appeal-r1 (+ 1))]

    {:final-decision final-decision
     :was-error (not= final-decision ground-truth)
     :round-0 r0
     :round-1 r1
     :round-2 r2
     :escalation-count escalation-count
     :escalated? (> escalation-count 0)}))

;; ============ Utility ============

(defn format-decision [decision]
  (if decision "YES" "NO"))

