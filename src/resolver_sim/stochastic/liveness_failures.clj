(ns resolver-sim.stochastic.liveness-failures
  "Model participation and liveness failures in decentralized dispute resolution.
   
   Critical insight: System can be economically sound but still fail because
   nobody shows up to resolve disputes (juror fatigue, opportunity cost).
   
   Failure modes:
   1. Juror dropout when boring (natural selection effect)
   2. Adverse selection (only risk-seeking remain)
   3. Latency sensitivity (users leave if decisions take too long)
   4. Participation spiral (fewer resolvers → slower → more leave)
   
   Based on: Market microstructure, participation games, reflexivity."
  (:require [resolver-sim.stochastic.rng :as rng]))

;; ============ Juror Participation Model ============

(defn juror-opportunity-cost
  "Model juror's opportunity cost of resolving disputes.
   
   Juror has outside opportunity:
   - Staking in other DeFi (higher yield?)
   - Day job (explicit opportunity cost)
   - Other leisure (boredom threshold)
   
   Args:
   - base-yield: What they'd earn staking elsewhere
   - dispute-resolution-reward: What they earn resolving
   - effort-cost: Time/energy required per dispute
   - num-disputes-in-period: How many disputes per week
   
   Returns: Participation decision and surplus/deficit"
  [base-yield dispute-reward effort-cost num-disputes-in-period]

  (let [; Time investment per dispute
        hours-per-dispute 5.0  ; 5 hours to review properly
        total-hours (* hours-per-dispute num-disputes-in-period)

        ; Total return from resolving
        resolution-gross dispute-reward

        ; Opportunity cost: What they could have made elsewhere
        opportunity-loss (* base-yield (/ total-hours 24.0))  ; Annualized hourly cost

        ; Effort cost (physical, mental exhaustion)
        effort-loss (* effort-cost num-disputes-in-period)

        ; Net surplus
        net-surplus (- resolution-gross opportunity-loss effort-loss)

        ; Decision: Participate if surplus > 0
        willing-to-participate? (> net-surplus 0)]

    {:base-yield base-yield
     :dispute-reward dispute-reward
     :effort-cost effort-cost
     :num-disputes num-disputes-in-period
     :resolution-gross resolution-gross
     :opportunity-loss opportunity-loss
     :effort-loss effort-loss
     :net-surplus net-surplus
     :willing? willing-to-participate?
     :reason (cond
               (< net-surplus (- 0 (* dispute-reward 0.5)))
               "STRONG_EXIT: Severe opportunity cost"

               (< net-surplus 0)
               "MARGINAL: Barely not worth it"

               (< net-surplus (* dispute-reward 0.2))
               "MARGINAL: Weak incentive"

               :else
               "STRONG_PARTICIPATION: Good incentive")
     :liveness/risk (cond
                      (< net-surplus (- 0 (* dispute-reward 0.5)))
                      :liveness/strong-exit

                      (< net-surplus 0)
                      :liveness/marginal

                      (< net-surplus (* dispute-reward 0.2))
                      :liveness/marginal-incentive

                      :else
                      :liveness/strong-participation)}))

(defn boredom-threshold
  "Model juror dropout when cases are boring/trivial.
   
   Insight: Resolvers are humans. Deciding trivial cases is mentally taxing
   with no sense of meaningful contribution. They drop out.
   
   Args:
   - case-difficulty: [0.0-1.0] complexity (1.0 = interesting, 0.0 = trivial)
   - resolver-cognitive-limit: How many boring cases can they take
   - cases-in-period: How many cases to decide
   - case-interest-distribution: Fraction that are interesting
   - rng: optional SplittableRandom for reproducible sampling
   
   Returns: Dropout risk and final participation"
  ([case-difficulty resolver-cognitive-limit cases-in-period case-interest-distribution]
   (boredom-threshold case-difficulty resolver-cognitive-limit cases-in-period case-interest-distribution nil))
  ([case-difficulty resolver-cognitive-limit cases-in-period case-interest-distribution rng]
   (let [cognitive-cost-per-case (if (> case-difficulty 0.5)
                                   1.0
                                   3.0)
         total-cognitive-load (* cognitive-cost-per-case cases-in-period)
         exceeds-limit? (> total-cognitive-load resolver-cognitive-limit)
         interesting-cases (* cases-in-period case-interest-distribution)
         boring-cases (* cases-in-period (- 1.0 case-interest-distribution))
         interesting-load (* 1.0 interesting-cases)
         boring-load (* 3.0 boring-cases)
         adjusted-load (+ interesting-load boring-load)
         dropout-risk (min 1.0 (/ (max 0 (- adjusted-load resolver-cognitive-limit))
                                  resolver-cognitive-limit))
         will-participate? (< (rng/roll-double rng) (- 1.0 dropout-risk))]
     {:case-difficulty case-difficulty
      :resolver-limit resolver-cognitive-limit
      :total-cases cases-in-period
      :interesting-fraction case-interest-distribution
      :interesting-cases interesting-cases
      :boring-cases boring-cases
      :adjusted-cognitive-load adjusted-load
      :exceeds-limit? exceeds-limit?
      :dropout-risk dropout-risk
      :will-participate? will-participate?
      :verdict (cond
                 (> dropout-risk 0.8) "CRITICAL: Likely exit"
                 (> dropout-risk 0.5) "SERIOUS: Significant exit risk"
                 (> dropout-risk 0.2) "CAUTION: Some dropout expected"
                 :else "STABLE: Low dropout")
      :liveness/risk (cond
                       (> dropout-risk 0.8) :liveness/critical-exit-risk
                       (> dropout-risk 0.5) :liveness/serious-exit-risk
                       (> dropout-risk 0.2) :liveness/caution-dropout
                       :else :liveness/low-dropout)})))

(defn adverse-selection-effect
  "Model adverse selection: Only risk-seeking resolvers remain.
   
   When dropout happens, who leaves and who stays?
   - Risk-averse resolvers exit first (conservative play it safe)
   - Risk-seeking resolvers stay (more aggressive, less careful)
   
   Result: Remaining pool becomes biased toward aggressive decisions.
   
   Args:
   - original-pool-size: Number of available resolvers
   - dropout-rate: [0.0-1.0] fraction who leave
   - risk-aversion-distribution: How risk-averse is pool initially
   
   Returns: Remaining pool characteristics"
  [original-pool-size dropout-rate risk-aversion-distribution]

  (let [; Risk-averse people exit first
        risk-averse-fraction risk-aversion-distribution
        risk-seeking-fraction (- 1.0 risk-aversion-distribution)

        ; Both groups exit, but risk-averse leave faster
        averse-exit-rate (min 1.0 (* dropout-rate 1.5))  ; 1.5x faster
        seeking-exit-rate (max 0.0 (* dropout-rate 0.5))  ; 0.5x slower

        ; Remaining in each group
        remaining-averse (* original-pool-size risk-averse-fraction (- 1.0 averse-exit-rate))
        remaining-seeking (* original-pool-size risk-seeking-fraction (- 1.0 seeking-exit-rate))

        remaining-total (+ remaining-averse remaining-seeking)

        ; New distribution (biased toward risk-seeking)
        new-averse-fraction (if (> remaining-total 0)
                              (/ remaining-averse remaining-total)
                              0.0)
        new-seeking-fraction (if (> remaining-total 0)
                               (/ remaining-seeking remaining-total)
                               1.0)

        ; Effect on decision quality
        ; Risk-averse: More careful, higher accuracy
        ; Risk-seeking: Less careful, lower accuracy, more likely to favor attacker
        accuracy-before 0.75  ; Average
        accuracy-after (- 0.75 (* new-seeking-fraction 0.15))  ; Seeking reduces accuracy

        accuracy-degradation (- accuracy-before accuracy-after)]

    {:original-pool-size original-pool-size
     :dropout-rate dropout-rate
     :original-averse-fraction risk-averse-fraction
     :original-seeking-fraction risk-seeking-fraction
     :remaining-total remaining-total
     :remaining-averse remaining-averse
     :remaining-seeking remaining-seeking
     :new-averse-fraction new-averse-fraction
     :new-seeking-fraction new-seeking-fraction
     :accuracy-before accuracy-before
     :accuracy-after accuracy-after
     :accuracy-degradation accuracy-degradation
     :risk-verdict (cond
                     (< remaining-total 3) "CRITICAL: Pool too small"
                     (> new-seeking-fraction 0.7) "HIGH: Biased toward aggressive"
                     (> new-seeking-fraction 0.5) "MODERATE: Some bias"
                     :else "STABLE: Balanced pool")
     :liveness/risk (cond
                      (< remaining-total 3) :liveness/critical-pool-too-small
                      (> new-seeking-fraction 0.7) :liveness/high-aggressive-bias
                      (> new-seeking-fraction 0.5) :liveness/moderate-bias
                      :else :liveness/stable-balanced-pool)}))

(def ^:const saturation-queue-days
  "Model boundary at which the M/M/c queue approximation is considered
   saturated.  This is a MODEL/policy boundary (the :else branch of the wait
   ladder), not an implementation safety cap.  queue-wait-days is clamped here
   for the bounded display/model value; the uncapped :utilization is retained
   alongside so severity above the boundary is not lost from evidence."
  30)

(defn latency-sensitivity
  "Model user dropout due to slow decision-making.
   
   If disputes take too long, users (litigants) stop using the system.
   - Expected decision time increases with load
   - Users have patience threshold
   - If exceeded, they exit and take disputes elsewhere
   
   Args:
   - dispute-volume: Disputes per week
   - resolvers-available: How many resolvers to handle load
   - time-per-dispute: Hours per resolution
   - user-patience-threshold: Max days acceptable
   
   Returns: User retention and volume impact.

   Structured fields for machine-readable evidence:
     :liveness/risk        — namespaced severity keyword (see check fns).
     :liveness/wait-capped?— true when queue-wait-days hit saturation-queue-days
                              (the saturation boundary), i.e. utilization >= 1.0.
     :utilization          — the UNCAPPED continuous signal; retained so wait
                              clamping never destroys severity information."
  [dispute-volume resolvers-available time-per-dispute user-patience-threshold]

  (let [; Queue model: Average wait time
        resolving-capacity (* resolvers-available 40 7)  ; 40 hours/week per resolver
        resolving-hours-per-week (* dispute-volume time-per-dispute)

        ; Utilization (uncapped — this is the evidence-grade signal)
        utilization (/ resolving-hours-per-week resolving-capacity)

        ; Average wait time (M/M/c queue approximation), clamped at the
        ; saturation boundary.  When ρ >= 1.0 the queue is saturated.
        queue-wait-days (cond
                          (< utilization 0.5) 1.0
                          (< utilization 0.7) 3.0
                          (< utilization 0.9) 7.0
                          (< utilization 1.0) 14.0
                          :else saturation-queue-days)

        wait-capped? (>= utilization 1.0)

        ; User patience: Will they accept this wait?
        user-acceptable-wait user-patience-threshold
        acceptable? (<= queue-wait-days user-acceptable-wait)

        ; If not acceptable, users leave
        user-retention-rate (if acceptable? 1.0
                                (max 0.1 (- 1.0 (/ queue-wait-days user-acceptable-wait))))

        ; Reduced volume
        retained-volume (* dispute-volume user-retention-rate)

        ; Effect: Lower volume → fewer resolvers stay → even slower → spiral
        spiral-effect (if (< user-retention-rate 0.7)
                        "SPIRAL_RISK: May accelerate"
                        "STABLE")]

    {:dispute-volume dispute-volume
     :resolvers-available resolvers-available
     :time-per-dispute time-per-dispute
     :user-patience-threshold user-patience-threshold
     :resolving-capacity resolving-capacity
     :utilization utilization
     :queue-wait-days queue-wait-days
     :acceptable? acceptable?
     :user-retention-rate user-retention-rate
     :retained-volume retained-volume
     :spiral-effect spiral-effect
     :liveness/wait-capped? wait-capped?
     :liveness/risk (cond
                      (>= queue-wait-days saturation-queue-days) :liveness/system-saturated
                      (>= queue-wait-days 14) :liveness/severe-users-leaving
                      (>= queue-wait-days 7) :liveness/serious-latency
                      :else :liveness/within-tolerance)
     :liveness/spiral-risk? (= "SPIRAL_RISK: May accelerate" spiral-effect)
     :verdict (cond
                (>= queue-wait-days saturation-queue-days) "CRITICAL: System broken"
                (>= queue-wait-days 14) "SEVERE: Users leaving"
                (>= queue-wait-days 7) "SERIOUS: Latency problem"
                :else "OK: Within tolerance")}))

(defn participation-spiral
  "Model reflexive participation spiral.
   
   When resolvers drop out:
   1. Fewer resolvers → longer waits
   2. Longer waits → users leave
   3. Fewer users → less demand → fewer resolvers needed
   4. BUT: Fewer resolvers → worse coverage → easier to attack
   5. Attack happens → system breaks
   
   The spiral is asymmetric: Easy to go down, hard to come back up.
   
   Args:
   - initial-resolvers: Start with this many
   - initial-volume: Start with this many disputes/week
   - dropout-trigger: At what utilization do resolvers leave
   - user-sensitivity: How fast do users leave with latency
   - weeks: How many weeks to simulate
   
   Returns: Trajectory of system decay"
  [initial-resolvers initial-volume dropout-trigger user-sensitivity weeks]

  (loop [week 0
         resolvers initial-resolvers
         volume initial-volume
         history []]

    (if (>= week weeks)
      history

      (let [; Calculate utilization
            capacity (* resolvers 40 7)  ; 40h/week per resolver
            hours-needed (* volume 5)    ; 5 hours per dispute
            utilization (if (> capacity 0) (/ hours-needed capacity) 1.0)

            ; Resolver dropout if overloaded
            resolver-dropout-rate (cond
                                    (< utilization dropout-trigger) 0.0
                                    (< utilization 0.9) 0.05  ; 5% dropout
                                    (< utilization 1.0) 0.15  ; 15% dropout
                                    :else 0.3)  ; 30% dropout if saturated

            new-resolvers (int (* resolvers (- 1.0 resolver-dropout-rate)))
            new-resolvers (max 1 new-resolvers)  ; At least 1

            ; Queue wait time
            wait-days (cond
                        (< utilization 0.7) 3
                        (< utilization 0.9) 7
                        (< utilization 1.0) 14
                        :else 30)

            ; User dropout if slow
            user-retention (max 0.3 (- 1.0 (* user-sensitivity (/ wait-days 7.0))))
            new-volume (int (* volume user-retention))
            new-volume (max 1 new-volume)

            ; Record state
            new-entry {:week week
                       :resolvers resolvers
                       :volume volume
                       :utilization utilization
                       :wait-days wait-days
                       :new-resolvers new-resolvers
                       :new-volume new-volume
                       :status (cond
                                 (< new-resolvers 3) "CRITICAL: Pool too small"
                                 (> utilization 1.0) "SATURATED"
                                 (< new-volume (/ initial-volume 2)) "DECLINING"
                                 :else "NORMAL")
                       :liveness/risk (cond
                                        (< new-resolvers 3) :liveness/critical-pool-too-small
                                        (> utilization 1.0) :liveness/saturated
                                        (< new-volume (/ initial-volume 2)) :liveness/declining
                                        :else :liveness/normal)}]

        (recur (inc week)
               new-resolvers
               new-volume
               (conj history new-entry))))))

(defn critical-mass-threshold
  "Model minimum viable participation level.
   
   System needs minimum resolvers to:
   - Cover all dispute types
   - Provide geographic diversity
   - Enable Kleros appeals
   - Resist attacks
   
   Below critical mass, system becomes brittle.
   
   Args:
   - min-resolvers-needed: Absolute minimum
   - geographic-regions: How many regions covered
   - current-resolvers: How many we have now
   
   Returns: Safety margin assessment"
  [min-resolvers-needed geographic-regions current-resolvers]

  (let [; Resolvers per region
        resolvers-per-region (if (> geographic-regions 0)
                               (/ current-resolvers geographic-regions)
                               0)

        ; Safety margin
        safety-margin (- current-resolvers min-resolvers-needed)
        safety-fraction (if (> min-resolvers-needed 0)
                          (/ safety-margin min-resolvers-needed)
                          0)

        ; Can we lose resolvers and still function?
        can-lose (* safety-margin 1.0)

        ; How much attrition before failure?
        attrition-tolerance (/ safety-margin current-resolvers)]

    {:min-resolvers-needed min-resolvers-needed
     :geographic-regions geographic-regions
     :current-resolvers current-resolvers
     :resolvers-per-region resolvers-per-region
     :safety-margin safety-margin
     :safety-fraction safety-fraction
     :can-lose-resolvers can-lose
     :attrition-tolerance attrition-tolerance
     :liveness/risk (cond
                      (< current-resolvers min-resolvers-needed)
                      :liveness/below-minimum-viable

                      (< safety-fraction 0.2)
                      :liveness/danger-low-safety-margin

                      (< safety-fraction 0.5)
                      :liveness/caution-moderate-margin

                      :else
                      :liveness/safe-healthy-margin)
     :status (cond
               (< current-resolvers min-resolvers-needed)
               "CRITICAL: Below minimum viable"

               (< safety-fraction 0.2)
               "DANGER: Low safety margin"

               (< safety-fraction 0.5)
               "CAUTION: Moderate safety margin"

               :else
               "SAFE: Healthy margin")}))

;; ===========================================================================
;; Liveness aggregate checks ("revealing" discipline)
;;
;; Follow the review-aggregate-check pattern: each check returns a structured
;; {:holds? bool :violations [{:kind ns-keyword :<detail> ...} ...]} so every
;; liveness risk is machine-readable, namespaced, and test-covered.  The string
;; verdicts above remain for humans; these checks are what consumers (and
;; reveal-* tests) should rely on.
;; ===========================================================================

(defn check-latency-sensitivity
  "Reveal liveness violations from a latency-sensitivity result.

   Violations (namespaced :kind):
     ::spiral-risk        — user retention below 70%; the reflexive spiral
                             is flagged as may-accelerate.
     ::system-saturated   — the queue hit the saturation boundary
                             (:liveness/wait-capped?, i.e. utilization >= 1.0).
                             Driven off the model's own flag, never a
                             re-hardcoded constant.
     ::severe-user-exits  — queue wait at or above 14 days.
     ::serious-latency    — queue wait at or above 7 days.

   Returns {:holds? bool :violations [...]}."
  [r]
  (let [violations (cond-> []
                     (:liveness/spiral-risk? r)
                     (conj {:kind ::spiral-risk
                            :user-retention-rate (:user-retention-rate r)
                            :queue-wait-days (:queue-wait-days r)})
                     (:liveness/wait-capped? r)
                     (conj {:kind ::system-saturated
                            :queue-wait-days (:queue-wait-days r)
                            :utilization (:utilization r)})
                     (>= (:queue-wait-days r) 14)
                     (conj {:kind ::severe-user-exits
                            :queue-wait-days (:queue-wait-days r)})
                     (>= (:queue-wait-days r) 7)
                     (conj {:kind ::serious-latency
                            :queue-wait-days (:queue-wait-days r)}))]
    {:holds? (empty? violations)
     :violations (vec violations)}))

(defn check-participation-spiral
  "Reveal liveness violations from a participation-spiral trajectory (history).

   Any week in which the resolver pool drops below 3, the system saturates, or
   volume halves is surfaced as a violation:
     ::critical-pool-too-small
     ::saturated-week
     ::declining-volume

   Returns {:holds? bool :violations [...]}."
  [history]
  (let [violations
        (into []
              (keep (fn [entry]
                      (cond
                        (< (:new-resolvers entry) 3)
                        {:kind ::critical-pool-too-small
                         :week (:week entry)
                         :resolvers (:new-resolvers entry)}

                        (> (:utilization entry) 1.0)
                        {:kind ::saturated-week
                         :week (:week entry)
                         :utilization (:utilization entry)}

                        (< (:new-volume entry) (:volume entry))
                        {:kind ::declining-volume
                         :week (:week entry)
                         :volume (:new-volume entry)})))
              history)]
    {:holds? (empty? violations)
     :violations (vec violations)}))

(defn check-critical-mass
  "Reveal critical-mass violations from a critical-mass-threshold result.

     ::below-minimum-viable  — current resolvers below the minimum.
     ::danger-low-margin     — safety fraction below 20%.
     ::caution-moderate      — safety fraction below 50%.

   Returns {:holds? bool :violations [...]}."
  [r]
  (let [violations (cond-> []
                     (= :liveness/below-minimum-viable (:liveness/risk r))
                     (conj {:kind ::below-minimum-viable
                            :current (:current-resolvers r)
                            :minimum (:min-resolvers-needed r)})
                     (= :liveness/danger-low-safety-margin (:liveness/risk r))
                     (conj {:kind ::danger-low-margin
                            :safety-fraction (:safety-fraction r)})
                     (= :liveness/caution-moderate-margin (:liveness/risk r))
                     (conj {:kind ::caution-moderate
                            :safety-fraction (:safety-fraction r)}))]
    {:holds? (empty? violations)
     :violations (vec violations)}))

(defn check-juror-participation
  "Reveal juror-participation violations from a juror-opportunity-cost result.

     ::strong-exit        — severe opportunity cost (net surplus < -50% reward).
     ::marginal           — net surplus negative or weakly incentivised.

   Returns {:holds? bool :violations [...]}."
  [r]
  (let [violations (cond-> []
                     (= :liveness/strong-exit (:liveness/risk r))
                     (conj {:kind ::strong-exit
                            :net-surplus (:net-surplus r)})
                     (or (= :liveness/marginal (:liveness/risk r))
                         (= :liveness/marginal-incentive (:liveness/risk r)))
                     (conj {:kind ::marginal
                            :net-surplus (:net-surplus r)
                            :risk (:liveness/risk r)}))]
    {:holds? (empty? violations)
     :violations (vec violations)}))

(defn check-boredom-exit
  "Reveal boredom-driven dropout from a boredom-threshold result.

     ::critical-exit-risk  — dropout risk > 80%.
     ::serious-exit-risk   — dropout risk > 50%.
     ::caution-dropout     — dropout risk > 20%.

   Returns {:holds? bool :violations [...]}."
  [r]
  (let [violations (cond-> []
                     (= :liveness/critical-exit-risk (:liveness/risk r))
                     (conj {:kind ::critical-exit-risk
                            :dropout-risk (:dropout-risk r)})
                     (= :liveness/serious-exit-risk (:liveness/risk r))
                     (conj {:kind ::serious-exit-risk
                            :dropout-risk (:dropout-risk r)})
                     (= :liveness/caution-dropout (:liveness/risk r))
                     (conj {:kind ::caution-dropout
                            :dropout-risk (:dropout-risk r)}))]
    {:holds? (empty? violations)
     :violations (vec violations)}))

(defn check-adverse-selection
  "Reveal adverse-selection violations from an adverse-selection-effect result.

     ::pool-too-small         — remaining pool below 3 resolvers.
     ::high-aggressive-bias   — new seeking fraction above 70%.
     ::moderate-bias          — new seeking fraction above 50%.

   Returns {:holds? bool :violations [...]}."
  [r]
  (let [violations (cond-> []
                     (= :liveness/critical-pool-too-small (:liveness/risk r))
                     (conj {:kind ::pool-too-small
                            :remaining-total (:remaining-total r)})
                     (= :liveness/high-aggressive-bias (:liveness/risk r))
                     (conj {:kind ::high-aggressive-bias
                            :new-seeking-fraction (:new-seeking-fraction r)
                            :accuracy-degradation (:accuracy-degradation r)})
                     (= :liveness/moderate-bias (:liveness/risk r))
                     (conj {:kind ::moderate-bias
                            :new-seeking-fraction (:new-seeking-fraction r)}))]
    {:holds? (empty? violations)
     :violations (vec violations)}))
