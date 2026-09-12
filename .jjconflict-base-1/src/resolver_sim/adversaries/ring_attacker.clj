(ns resolver-sim.adversaries.ring-attacker
  "RingAttack adversary for multi-epoch equity trajectory analysis (Phase AH / AI).

   Models a coordinated ring of resolvers that rotate disputes among members to
   keep per-member verdict counts below the detection threshold.

   Multi-epoch sweep orchestration: resolver-sim.sim.adversarial.ring-attacker-sweep

   Layering: adversaries/* may import stochastic/* only. No db/*, io/*, sim/* imports."
  (:require [resolver-sim.adversaries.strategy :as strategy]))

;; ---------------------------------------------------------------------------
;; RingAttacker — implements the Adversary protocol
;; ---------------------------------------------------------------------------

(deftype RingAttacker [ring-size rotation-period detection-evasion-bps]
  strategy/Adversary

  (should-attack? [_ _params]
    true)

  (attack-type [_ _params]
    :ring-rotation)

  (budget-allocation [_ _params]
    {:ring-rotation 1.0 :bribery 0.0 :evidence 0.0 :collusion 0.0})

  (expected-profit [_ _attack-type params]
    (let [escrow       (:escrow-size params 10000)
          fee-bps      (:resolver-fee-bps params 150)
          base-det     (:fraud-detection-probability params 0.05)
          eff-det      (/ base-det ring-size)
          slash-mult   (:slash-multiplier params 2.5)
          fee          (* escrow (/ fee-bps 10000.0))
          slash-loss   (* escrow slash-mult eff-det)]
      (- fee slash-loss)))

  (observe-outcome! [_ _outcome]
    nil))
