(ns resolver-sim.protocols.sew.research-models.escalation-economics
  "Economics of sequential appeal escalation.
   
   Models:
   - Appeal bond costs per round
   - Resolver stakes and slashing
   - Attacker cost to corrupt each level
   - ROI analysis for attackers
   
   Key insight: Sequential system makes attacker pay per level,
   not as one-shot like panel voting."
  (:require [resolver-sim.protocols.sew.config :as sew-config]
            [resolver-sim.stochastic.rng :as rng]))

;; ============ Bond and Stake Configuration ============

(def DEFAULT_ESCALATION_CONFIG
  "Default configuration for appeal bonds and resolver stakes.
   Centralized in resolver-sim.protocols.sew.config."
  sew-config/DEFAULT_ESCALATION_CONFIG)

;; ============ Cost Calculations ============

(defn resolver-stake-at-round
  "Calculate resolver's stake at each round.
   
   Later rounds have higher stakes (more scrutiny, more to lose).
   
   Args:
   - round: 0, 1, or 2
   - config: escalation configuration
   
   Returns: Stake amount in wei"
  [round config]

  (let [base-stake (:resolver-stake-base config)
        round-cfg (get-in config [:round-configs round])
        multiplier (:stake-multiplier round-cfg)]
    (* base-stake multiplier)))

(defn appeal-bond-at-round
  "Calculate appeal bond cost to escalate to next round.
   
   Later appeals are more expensive (stronger scrutiny).
   
   Args:
   - from-round: which round to appeal from (0 or 1)
   - config: escalation configuration
   
   Returns: Bond amount in wei"
  [from-round config]

  (let [base-bond (:appeal-bond-base config)
        round-cfg (get-in config [:round-configs from-round])
        multiplier (:bond-multiplier round-cfg)
        bond-escalation (:bond-multiplier config)]

    (* base-bond multiplier bond-escalation)))

(defn total-appeal-cost-to-round
  "Total cost to appeal all the way to a given round.
   
   Example: Cost to reach round 2 = bond(0→1) + bond(1→2)
   
   Args:
   - target-round: which round to reach (1 or 2)
   - config: escalation configuration
   
   Returns: Total cost in wei"
  [target-round config]

  (let [bonds (for [round (range target-round)]
                (appeal-bond-at-round round config))]
    (reduce + 0 bonds)))

;; ============ Slashing Calculations ============

(defn slashing-loss-if-wrong
  "How much resolver loses if they get slashed for wrong decision.
   
   Args:
   - round: which round
   - config: escalation configuration
   
   Returns: Loss amount in wei"
  [round config]

  (let [stake (resolver-stake-at-round round config)
        slashing-rate (:slashing-rate config)]
    (* stake slashing-rate)))

(defn net-incentive-to-decide-wrong
  "Calculate attacker's profit vs honest resolver's loss.
   
   Honest resolver loses if wrong: stake * slashing-rate
   Attacker gains: dispute-value (what they attack for)
   
   Args:
   - round: which round
   - dispute-value: what is the dispute worth to attacker?
   - config: escalation configuration
   
   Returns: net profit for attacker"
  [round dispute-value config]

  (let [slashing-loss (slashing-loss-if-wrong round config)

        ; Attacker's profit if attack succeeds
        attack-profit (min dispute-value
                           (* 10 slashing-loss))  ; Max reasonable profit

        ; Honest resolver's loss if corrupted into wrong decision
        resolver-loss slashing-loss

        ; Net gain for attacker paying bribes
        net-gain (- attack-profit resolver-loss)]

    (max 0 net-gain)))

;; ============ Corruption Cost ============

(defn bribe-cost-to-corrupt-resolver
  "How much does it cost to bribe a resolver into wrong decision?
   
   Model: Attacker must pay resolver enough to make attack profitable
   despite slashing risk.
   
   Cost = resolver-loss-if-caught + (probability-of-appeal × bond-cost)
   
   Args:
   - round: which round
   - appeal-probability: [0.0 - 1.0] Likelihood of escalation
   - config: escalation configuration
   
   Returns: Bribe amount needed in wei"
  [round appeal-probability config]

  (let [slashing-loss (slashing-loss-if-wrong round config)
        next-bond (if (< round 2)
                    (appeal-bond-at-round round config)
                    0)  ; No further appeal from round 2

        ; Bribe must cover:
        ; 1. Slashing loss (probability 1.0 if detected)
        ; 2. Appeal bond if escalated

        expected-cost (+ slashing-loss
                         (* appeal-probability next-bond))

        ; Add 20% margin for risk
        bribe-needed (* expected-cost 1.2)]

    bribe-needed))

;; ============ Attacker ROI Analysis ============

(defn attacker-roi-per-corruption
  "Calculate ROI for attacking through corrupting a single resolver.
   
   Args:
   - round: which round to corrupt
   - dispute-value: What is the dispute worth?
   - appeal-probability: [0.0 - 1.0] Likelihood of escalation
   - corruption-success-rate: [0.0 - 1.0] Does bribe stick?
   - config: escalation configuration
   
   Returns: {:bribe-cost, :expected-profit, :roi-percentage}"
  [round dispute-value appeal-probability corruption-success-rate config]

  (let [bribe-cost (bribe-cost-to-corrupt-resolver round appeal-probability config)

        ; If attack succeeds (bribe sticks AND decision matters)
        success-profit (net-incentive-to-decide-wrong round dispute-value config)

        ; Expected value
        expected-profit (* success-profit corruption-success-rate)

        ; ROI
        roi (if (> bribe-cost 0)
              (/ (- expected-profit bribe-cost) bribe-cost)
              0.0)]

    {:bribe-cost bribe-cost
     :expected-profit expected-profit
     :roi-percentage (* roi 100.0)
     :profitable? (> expected-profit bribe-cost)}))

;; ============ Sequential Attack Analysis ============

(defn sequential-escalation-attack-cost
  "Cost for attacker to corrupt multiple levels sequentially.
   
   Model: Attacker bribes Round 0 → if escalated, bribes Round 1
   
   Each level is independent bribe with its own success probability.
   
   Args:
   - dispute-value: What is the dispute worth?
   - corruption-prob-r0: [0.0 - 1.0] Can corrupt Round 0?
   - corruption-prob-r1: [0.0 - 1.0] Can corrupt Round 1 if appealed?
   - appeal-probability: [0.0 - 1.0] Likelihood of escalation
   - config: escalation configuration
   
   Returns: Total cost for multi-level attack"
  [dispute-value corruption-prob-r0 corruption-prob-r1 appeal-probability config]

  (let [; Cost to corrupt Round 0
        r0-bribe (bribe-cost-to-corrupt-resolver 0 appeal-probability config)

        ; Only pay R1 bribe if R0 is appealed
        r1-bribe (* appeal-probability
                    (bribe-cost-to-corrupt-resolver 1 0 config))

        ; Total cost
        total-cost (+ (* r0-bribe corruption-prob-r0)
                      (* r1-bribe corruption-prob-r1))]

    total-cost))

(defn escalation-provides-security
  "Analyze how escalation rounds improve security.
   
   Each round adds:
   - Higher resolver stake (more to lose)
   - Appeal bond cost (attacker pays to corrupt next level)
   - Review by higher-quality resolver (less likely to make errors)
   
   Returns: Security improvement metrics"
  [config]

  (let [r0-stake (resolver-stake-at-round 0 config)
        r1-stake (resolver-stake-at-round 1 config)
        r2-stake (resolver-stake-at-round 2 config)

        r0-bond (appeal-bond-at-round 0 config)
        r1-bond (appeal-bond-at-round 1 config)

        ; Security factors
        stake-improvement-r1 (/ (- r1-stake r0-stake) r0-stake)
        stake-improvement-r2 (/ (- r2-stake r0-stake) r0-stake)

        cost-improvement-r1 r0-bond
        cost-improvement-r2 (+ r0-bond r1-bond)]

    {:stake-increase-r1 stake-improvement-r1
     :stake-increase-r2 stake-improvement-r2
     :cumulative-appeal-cost-r1 cost-improvement-r1
     :cumulative-appeal-cost-r2 cost-improvement-r2
     :protection-improvement-r1 (+ stake-improvement-r1
                                   (/ cost-improvement-r1 r0-stake))
     :protection-improvement-r2 (+ stake-improvement-r2
                                   (/ cost-improvement-r2 r0-stake))}))

;; ============ Comparative Analysis ============

(defn compare-attack-costs
  "Compare cost to attack at different rounds.
   
   Shows: Is it cheaper to corrupt Round 0, or pay to escalate and corrupt Round 1?
   
   Returns: Cost comparison and recommendation"
  [dispute-value config]

  (let [; Attack Round 0 only (don't let it escalate)
        r0-only-cost (bribe-cost-to-corrupt-resolver 0 0 config)

        ; Attack Round 0 + escalate + attack Round 1
        r0-then-r1-cost (sequential-escalation-attack-cost
                         dispute-value 1.0 1.0 1.0 config)

        ; Just attack Round 1 (wait for appeal)
        r1-only-cost (bribe-cost-to-corrupt-resolver 1 0 config)

        cheapest (min r0-only-cost r0-then-r1-cost r1-only-cost)]

    {:attack-r0-only r0-only-cost
     :attack-r0-then-r1 r0-then-r1-cost
     :attack-r1-only r1-only-cost
     :cheapest-route cheapest
     :attacker-prefers (cond
                         (= cheapest r0-only-cost) "Corrupt R0, pray it doesn't escalate"
                         (= cheapest r0-then-r1-cost) "Corrupt R0, then R1 if appealed"
                         :else "Wait and corrupt R1")}))

