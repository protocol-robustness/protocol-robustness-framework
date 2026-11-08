(ns resolver-sim.protocols.sew.research-models.bribery-markets
  "Model contingent and advanced bribery strategies.
   
   Extends simple 'bribe with fixed cost' to more realistic models:
   - p+ε bribes: Pay only if attack fails (zero-cost-on-success)
   - Selective targeting: Corrupt marginal swing resolvers not majority
   - Budget recycling: Reuse failed bribe capital
   
   Based on: Kleros yellow paper, game theory of bribery."
  (:require [resolver-sim.stochastic.rng :as rng]))

;; ============ Bribery Models ============

(defn simple-bribery-cost
  "Traditional model: Fixed cost to corrupt a resolver.
   
   Args:
   - resolver-stake: Amount at risk if slashed
   - detection-probability: [0.0-1.0] chance of detection
   - corruption-value: Bribe amount (typically < stake)
   
   Returns: Expected cost to attack
   
   Cost = bribe-amount if successful
   Cost = bribe-amount + detection-loss if caught"
  [resolver-stake detection-probability corruption-value]

  (let [detection-loss (* resolver-stake detection-probability)]
    (+ corruption-value detection-loss)))

(defn contingent-bribery-cost
  "Advanced model: p+ε bribe (pay only on failure).
   
   Structure:
   - Promise: 'Pay X if your decision favors us'
   - But: 'Pay X+ε if your decision is overturned on appeal'
   - Effect: Resolver has incentive to decide now, faces cost only if wrong
   
   This is asymmetric: attacker's downside is conditional, resolver's is not.
   
   Args:
   - resolver-stake: Amount at risk
   - base-accuracy: Honest accuracy at this level
   - appeal-probability: [0.0-1.0] chance decision is appealed
   - contingent-premium: Extra cost if appeal fails (ε factor)
   
   Returns: Expected cost to attacker"
  [resolver-stake base-accuracy appeal-probability contingent-premium]

  (let [; Resolver is incentivized to decide now (gets paid immediately)
        ; Then faces contingent penalty only if overturned on appeal
        resolver-incentive-to-attack (- 1.0 base-accuracy)

        ; Expected cost to attacker:
        ; - Base bribe (pay if attack succeeds)
        ; - Premium if appeal fails (contingency payment)
        base-bribe resolver-stake
        contingency-loss (* resolver-stake contingent-premium (- 1.0 base-accuracy))
        total-expected (+ base-bribe
                          (* contingency-loss appeal-probability))]

    {:base-cost base-bribe
     :contingency-cost contingency-loss
     :appeal-probability appeal-probability
     :total-expected-cost total-expected
     :attacker-favorable? (< total-expected (* resolver-stake 2.0))}))

(defn selective-targeting-cost
  "Model: Corrupt the marginal resolver, not the majority.
   
   Theory: Attacker doesn't need to flip the outcome at a panel.
   Instead, corrupt the deciding resolver in a sequential system.
   
   But in sequential single-resolver model:
   - Each round has only ONE resolver
   - Attacking requires corrupting that specific resolver
   - No marginal swing set—corruption is all-or-nothing per round
   
   Args:
   - round: Which round (0, 1, 2)
   - base-stake: Stake at this round
   - detection-probability: Likelihood of detection
   - resolver-reputation: How much does resolver care about being caught?
   
   Returns: Cost to corrupt this round's resolver"
  [round base-stake detection-probability resolver-reputation]

  (let [; Higher rounds have higher stakes
        round-multiplier (case round 0 1.0, 1 2.0, 2 3.0, 1.0)
        stake-at-round (* base-stake round-multiplier)

        ; Higher reputation = higher cost to damage it
        ; (resolver more reluctant to accept bribe if reputation is strong)
        reputation-discount (/ 1.0 (max 0.5 resolver-reputation))

        ; Detection loss
        detection-loss (* stake-at-round detection-probability reputation-discount)

        ; Base bribe (must exceed what resolver gains)
        base-bribe (* stake-at-round 0.7)  ; Bribe typically 70% of stake

        total-cost (+ base-bribe detection-loss)]

    {:round round
     :stake-at-round stake-at-round
     :base-bribe base-bribe
     :detection-cost detection-loss
     :total-cost total-cost
     :cost-scale (/ total-cost stake-at-round)  ; Cost as multiple of stake
     :attacker-profitable? (< total-cost (* stake-at-round 3.0))}))

(defn budget-recycling
  "Model: Attacker recycles bribe capital across multiple attempts.
   
   Idea: If first bribery fails (detected), attacker can reuse that capital
   for subsequent attacks. This reduces amortized cost.
   
   Args:
   - initial-budget: Attacker's capital
   - bribe-per-attempt: Cost per corrupted resolver
   - detection-rate: Probability attempt is detected
   - recovery-rate: Fraction of detected bribes recovered
   - num-attempts: How many times attacker can try
   
   Returns: How many attacks affordable with budget recycling"
  [initial-budget bribe-per-attempt detection-rate recovery-rate num-attempts
   & {:keys [rng] :or {rng nil}}]

  (loop [attempts 0
         remaining-budget initial-budget
         successful-attacks 0]

    (if (or (>= attempts num-attempts) (< remaining-budget bribe-per-attempt))
      {:total-attempts attempts
       :successful-attacks successful-attacks
       :remaining-budget remaining-budget
       :recycling-advantage (/ (* bribe-per-attempt successful-attacks) initial-budget)}

      (let [detected? (if rng (< (.nextDouble rng) detection-rate)
                          (< (rand) detection-rate))
            recovered (if detected? (* bribe-per-attempt recovery-rate) 0)
            new-budget (- remaining-budget bribe-per-attempt)
            final-budget (+ new-budget recovered)]

        (recur (inc attempts)
               final-budget
               (if (not detected?) (inc successful-attacks) successful-attacks))))))

(defn multi-round-attack-cost
  "Model: Sequential attack across multiple appeal rounds.
   
   Attacker must corrupt at EACH LEVEL to maintain wrong outcome.
   
   Structure:
   R0: Corrupt resolver 0 (stake 10K)
   R1: Corrupt resolver 1 (stake 20K) - must, if R0 appealed
   R2: Corrupt resolver 2 (stake 30K) - must, if R1 appealed
   
   Problem: Cost escalates. Earlier rounds are cheaper but not guaranteed.
   
   Args:
   - base-stakes: [10000 20000 30000] per round
   - detection-probs: [0.1 0.15 0.2] per round (harder to hide at higher levels)
   - appeal-probs: [0.3 0.2 0.0] probability of appeal at each level
   
   Returns: Cost structure for multi-round attack"
  [base-stakes detection-probs appeal-probs]

  (let [num-rounds (count base-stakes)

        ; For each round, calculate cost to corrupt
        round-costs (mapv (fn [round stake det-prob appeal-prob]
                            (let [; Cost to successfully corrupt this round
                                  base-bribe stake
                                 ; Detection loss at this level (higher = harder)
                                  detection-loss (* stake det-prob)
                                 ; Only pays off if attack isn't appealed
                                  expected-payoff (* (- 1.0 appeal-prob) stake)
                                 ; Total expected cost
                                  total-cost (+ base-bribe detection-loss)]

                              {:round round
                               :stake stake
                               :base-bribe base-bribe
                               :detection-loss detection-loss
                               :detection-probability det-prob
                               :appeal-probability appeal-prob
                               :expected-payoff expected-payoff
                               :expected-cost total-cost
                               :profitable? (> expected-payoff total-cost)}))
                          (range num-rounds) base-stakes detection-probs appeal-probs)

        ; Total cost if attacker must corrupt all rounds (worst case)
        total-cost-all (apply + (map :expected-cost round-costs))

        ; Expected cost considering appeal probability
        expected-cost-with-appeals
        (apply + (map (fn [{:keys [expected-cost appeal-probability]}]
                        (* expected-cost (- 1.0 appeal-probability)))
                      round-costs))

        ; Cost per round successful corruption
        avg-cost-per-round (/ total-cost-all num-rounds)]

    {:round-costs round-costs
     :total-cost-all-rounds total-cost-all
     :expected-cost-with-appeals expected-cost-with-appeals
     :avg-cost-per-round avg-cost-per-round
     :attack-strategy (cond
                        (< expected-cost-with-appeals (* (apply + base-stakes) 0.5))
                        "Cheap: Low cost relative to total stakes"

                        (< expected-cost-with-appeals (* (apply + base-stakes) 1.5))
                        "Moderate: Costs ~1.5x base stakes"

                        :else "Expensive: High cost, unlikely ROI")}))

(defn attack-feasibility
  "Assess whether multi-round attack is feasible for attacker.
   
   Args:
   - attacker-budget: Attacker's available capital
   - attack-cost: From multi-round-attack-cost
   - dispute-value: What attacker gains if successful
   - win-probability: Likelihood attack succeeds despite detection
   
   Returns: Feasibility assessment"
  [attacker-budget attack-cost dispute-value win-probability]

  (let [expected-gain (* dispute-value win-probability)
        expected-loss (* attack-cost (- 1.0 win-probability))
        roi (if (> attack-cost 0) (/ expected-gain attack-cost) 0)
        net-expected (- expected-gain expected-loss)
        budget-sufficient? (>= attacker-budget attack-cost)]

    {:attack-cost attack-cost
     :dispute-value dispute-value
     :win-probability win-probability
     :expected-gain expected-gain
     :expected-loss expected-loss
     :net-expected-value net-expected
     :roi roi
     :budget-sufficient? budget-sufficient?
     :feasible? (and budget-sufficient? (> net-expected 0))
     :recommendation (cond
                       (not budget-sufficient?)
                       "BLOCK: Attacker lacks capital"

                       (< net-expected 0)
                       "BLOCK: Negative expected value"

                       (< roi 1.0)
                       "CAUTION: ROI < 1.0 (capital loss)"

                       :else
                       "RISK: Feasible and profitable attack")}))
