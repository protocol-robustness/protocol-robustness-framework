(ns resolver-sim.protocols.sew.research-models.evidence-spoofing
  "Model information asymmetry and evidence spoofing attacks.
   
   Realistic model:
   - Evidence is not binary (truth/lie) but a production cost
   - Attacker can fabricate plausible-but-false evidence
   - Honest party must spend time/money to rebut
   - Time pressure favors attacker (can't verify everything)
   - Resolvers have limited attention budgets
   
   Based on: Epistemic game theory, information cascades, attention economics."
  (:require [resolver-sim.stochastic.rng :as rng]))

;; ============ Evidence Production ============

(defn evidence-generation-cost
  "Model cost to generate evidence (honest or fake).
   
   Honest evidence: Extract from on-chain/off-chain sources
   Fake evidence: Fabricate documents, modify timestamps, create false narratives
   
   Key insight: Fake evidence is often CHEAPER than honest evidence
   because attacker can omit inconvenient details.
   
   Args:
   - evidence-type: :honest or :fake
   - dispute-difficulty: :easy, :medium, :hard
   - quality-level: [0.0-1.0] How convincing
   
   Returns: Cost in time/effort units"
  [evidence-type dispute-difficulty quality-level]

  (let [; Base cost depends on difficulty
        base-cost (case dispute-difficulty
                    :easy 1.0
                    :medium 5.0
                    :hard 15.0
                    5.0)

        ; Honest evidence requires more work (must be thorough)
        ; Fake evidence is cheaper (can be selective)
        type-multiplier (case evidence-type
                          :honest 1.5
                          :fake 0.6
                          1.0)

        ; Higher quality costs more
        quality-cost (* quality-level 2.0)

        total-cost (* base-cost type-multiplier (+ 1.0 quality-cost))]

    {:type evidence-type
     :difficulty dispute-difficulty
     :quality quality-level
     :base-cost base-cost
     :type-multiplier type-multiplier
     :quality-cost quality-cost
     :total-cost total-cost
     :fake-cheaper? (< (case evidence-type :fake 0.6, 1.5)
                       (case evidence-type :honest 1.5, 0.6))}))

(defn evidence-verification-cost
  "Model cost to verify evidence quality.
   
   Honest party needs to:
   - Review evidence
   - Check sources
   - Gather counter-evidence
   - Prepare rebuttal
   
   Time pressure: Can't fully verify everything in limited time.
   
   Args:
   - evidence-volume: Amount of evidence presented
   - verification-time: Available time in hours
   - resolver-attention: [0.0-1.0] How much attention can dedicate
   
   Returns: Verification confidence and cost"
  [evidence-volume verification-time resolver-attention]

  (let [; Maximum evidence a resolver can fully verify per hour
        verification-rate 10.0  ; 10 units of evidence per hour

        ; Actual verification possible given time
        verifiable (min evidence-volume (* verification-rate verification-time))

        ; Fraction verified
        verification-fraction (if (> evidence-volume 0)
                                (/ verifiable evidence-volume)
                                1.0)

        ; Resolver attention reduces verification depth
        adjusted-verification (* verification-fraction resolver-attention)

        ; Time cost
        time-cost (* (/ verifiable verification-rate) 1.0)

        ; Resource cost (hiring experts, etc.)
        resource-cost (* evidence-volume 0.2)]

    {:evidence-volume evidence-volume
     :time-available verification-time
     :resolver-attention resolver-attention
     :verifiable-amount verifiable
     :verification-fraction verification-fraction
     :adjusted-confidence adjusted-verification
     :time-cost time-cost
     :resource-cost resource-cost
     :total-cost (+ time-cost resource-cost)
     :verdict (cond
                (< adjusted-verification 0.3)
                "UNVERIFIED: High risk of manipulation"

                (< adjusted-verification 0.6)
                "PARTIALLY: Some evidence unverified"

                :else
                "VERIFIED: Evidence adequately reviewed")}))

(defn attacker-evidence-strategy
  "Model attacker's choice: volume attack vs. quality attack.
   
   Two strategies:
   1. Volume attack: Flood with fake evidence
      - Pro: Harder to verify everything
      - Con: Some fake evidence can be caught
   
   2. Quality attack: Few high-quality fakes
      - Pro: Better quality = harder to detect
      - Con: Expensive to generate
   
   Args:
   - attacker-budget: Capital available for evidence generation
   - resolver-time-budget: Hours resolver has to verify
   - evidence-difficulty: :easy, :medium, :hard
   
   Returns: Optimal strategy and expected effectiveness"
  [attacker-budget resolver-time-budget evidence-difficulty]

  (let [; Volume attack: Many low-quality fakes
        fake-cost-per-unit (case evidence-difficulty
                             :easy 0.3
                             :medium 1.0
                             :hard 3.0)
        volume-attack-quantity (/ attacker-budget fake-cost-per-unit)

        ; Fake evidence generation
        {:keys [total-cost]} (evidence-generation-cost :fake evidence-difficulty 0.5)

        ; Volume attack effectiveness: Harder to verify everything
        verification-rate 10.0
        verifiable-units (* verification-rate resolver-time-budget)
        unverified-units (max 0 (- volume-attack-quantity verifiable-units))
        volume-effectiveness (/ unverified-units volume-attack-quantity)

        ; Quality attack: Few very good fakes
        quality-fake-cost (case evidence-difficulty
                            :easy 1.0
                            :medium 3.0
                            :hard 8.0)
        quality-attack-quantity (/ attacker-budget quality-fake-cost)

        ; High-quality fakes are harder to detect
        quality-detection-difficulty 0.8  ; 20% detection rate on good fakes
        quality-effectiveness (- 1.0 quality-detection-difficulty)

        ; Compare strategies
        volume-roi (/ volume-effectiveness (max 0.1 (/ attacker-budget volume-attack-quantity)))
        quality-roi (/ quality-effectiveness (max 0.1 (/ attacker-budget quality-attack-quantity)))]

    {:budget attacker-budget
     :time-budget resolver-time-budget

     :volume-strategy
     {:quantity volume-attack-quantity
      :quality-per-unit 0.5
      :effectiveness volume-effectiveness
      :roi volume-roi
      :description "Flood with quantity, exploit verification limits"}

     :quality-strategy
     {:quantity quality-attack-quantity
      :quality-per-unit 0.8
      :effectiveness quality-effectiveness
      :roi quality-roi
      :description "High-quality fakes, harder to detect"}

     :optimal-strategy (if (> volume-roi quality-roi) :volume :quality)
     :recommendation (if (> volume-roi quality-roi)
                       (str "Use volume attack: " (int volume-attack-quantity) " evidence units")
                       (str "Use quality attack: " (int quality-attack-quantity) " evidence units"))}))

(defn resolver-verification-tradeoff
  "Model resolver's tradeoff: Spend time on verification vs. other decisions.
   
   Resolver has limited attention per epoch.
   
   Args:
   - attention-budget: Total hours per epoch
   - num-disputes: How many cases to decide
   - evidence-complexity: [0.0-1.0] Average difficulty
   - verification-time-per-case: Hours needed for one case
   
   Returns: Quality degradation under load"
  [attention-budget num-disputes evidence-complexity verification-time-per-case]

  (let [; Time available per dispute
        time-per-dispute (/ attention-budget num-disputes)

        ; Time needed for thorough verification
        needed-time verification-time-per-case

        ; Actual verification fraction
        verification-fraction (min 1.0 (/ time-per-dispute needed-time))

        ; Accuracy degradation: Less time = lower quality decisions
        ; Assume linear degradation: Full time = 85% accuracy, no time = 50%
        base-accuracy 0.85
        degraded-accuracy (+ 0.50 (* (- base-accuracy 0.50) verification-fraction))

        ; Effect on detection probability
        detection-ability verification-fraction]

    {:attention-budget attention-budget
     :num-disputes num-disputes
     :time-per-dispute time-per-dispute
     :time-needed needed-time
     :verification-fraction verification-fraction
     :base-accuracy base-accuracy
     :degraded-accuracy degraded-accuracy
     :accuracy-loss (- base-accuracy degraded-accuracy)
     :detection-ability detection-ability
     :quality-verdict (cond
                        (< verification-fraction 0.2) "CRITICAL: Extreme overload"
                        (< verification-fraction 0.5) "POOR: Significant load"
                        (< verification-fraction 0.8) "FAIR: Moderate load"
                        :else "GOOD: Adequate time")}))

(defn epistemic-collapse-risk
  "Model risk of epistemic collapse: When evidence is so ambiguous
   that resolvers give up and rely on heuristics (narrative, bias).
   
   Conditions for collapse:
   - Evidence is contradictory
   - Verification is expensive
   - Time is limited
   - Resolvers resort to priors/heuristics
   
   Args:
   - evidence-clarity: [0.0-1.0] How unambiguous is the case
   - verification-cost: How expensive to be careful
   - time-pressure: [0.5-1.5] Multiplier on standard deadline
   - resolver-baseline-accuracy: Accuracy without evidence review
   
   Returns: Collapse risk and effect on outcomes"
  [evidence-clarity verification-cost time-pressure resolver-baseline-accuracy]

  (let [; Collapse risk increases with:
        ; - Low clarity (evidence is ambiguous)
        ; - High verification cost (easier to be lazy)
        ; - High time pressure (less time to think)
        clarity-factor evidence-clarity
        cost-factor (/ 1.0 (max 0.1 verification-cost))
        time-factor time-pressure

        collapse-risk (* (- 1.0 clarity-factor) cost-factor time-factor)
        clamped-risk (min 1.0 collapse-risk)

        ; Under collapse, resolvers use heuristics
        ; Heuristic accuracy is baseline (no evidence processing)
        heuristic-accuracy resolver-baseline-accuracy

        ; Evidence-based accuracy improves with clarity
        evidence-accuracy (+ 0.5 (* clarity-factor 0.4))

        ; Final accuracy: Mix of heuristic and evidence-based
        final-accuracy (+ (* (- 1.0 clamped-risk) evidence-accuracy)
                          (* clamped-risk heuristic-accuracy))

        accuracy-loss (- evidence-accuracy final-accuracy)]

    {:evidence-clarity evidence-clarity
     :verification-cost verification-cost
     :time-pressure time-pressure
     :collapse-risk clamped-risk
     :heuristic-accuracy heuristic-accuracy
     :evidence-accuracy evidence-accuracy
     :final-accuracy final-accuracy
     :accuracy-loss-from-collapse accuracy-loss
     :verdict (cond
                (< clamped-risk 0.2) "LOW: Evidence-based decision expected"
                (< clamped-risk 0.5) "MODERATE: Some heuristic use"
                (< clamped-risk 0.8) "HIGH: Heavy heuristic reliance"
                :else "CRITICAL: Resolver has given up on evidence")}))
