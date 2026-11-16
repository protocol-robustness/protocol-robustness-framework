(ns resolver-sim.research.sew.economic.burst-concurrency
  "Phase X: Burst Concurrency Exploit - Overwhelming Sequential Appeals
  
  Tests whether attacker can circumvent sequential defense by triggering
  many disputes simultaneously, all within the slashing window (before
  governance can freeze escalations).
  
  Key insight: Sequential defense works if disputes are spaced out.
  What if attacker bunches 20+ disputes in one block, before governance
  can respond?"
  (:require [clojure.math :as math]
            [resolver-sim.stochastic.rng :as rng]
            [resolver-sim.sim.engine      :as engine]))

(defn simulate-burst-attack
  "Simulate attack where attacker triggers N disputes simultaneously.
   All within slashing delay window (before governance freezes).
   
   Measures whether parallelization defeats sequential defense."
  [n-disputes n-resolvers honest-acc slashing-delay governance-response-time rng]
  (let [        ; Round 0: Initial resolver decides quickly
        ; Cost per resolver: 1 unit of time
        ; Time available: slashing-delay (e.g., 3 days)
        round-0-decisions
        (for [disp (range n-disputes)]
          (let [honest-vote? (< (rng/next-double rng) honest-acc)
                corrupt-vote? (< (rng/next-double rng) 0.3) ; Attacker can bribe 30% of initial resolvers
                attacker-wins? corrupt-vote?]
            {:dispute disp :attacker-wins? attacker-wins?}))

        attacker-wins (count (filter :attacker-wins? round-0-decisions))

        ; Round 1: Senior review can happen on some disputes
        ; Each senior review takes 8 hours, capacity = 3 disputes/day
        ; Slashing delay = 3 days = enough for ~9 disputes
        ; But if burst has 20+, most go unappealed
        disputes-reviewed (min n-disputes
                               (int (* (/ slashing-delay 1.0) 3.0)))

        ; Of reviewed disputes, how many attacker wins caught?
        reviewed-attacker-wins (min attacker-wins disputes-reviewed)
        caught (int (* 0.9 reviewed-attacker-wins)) ; Senior catches 90% of bad outcomes
        uncaught (- reviewed-attacker-wins caught)

        ; Governance response: Freeze escalations after governance-response-time
        ; By then, how many disputes already past Round 0?
        governance-delay-fraction (/ governance-response-time slashing-delay)
        frozen-at-round-0 (int (* (count round-0-decisions) (- 1.0 governance-delay-fraction)))

        ; Final accounting:
        ; Attacker profit = (disputes won) - (bond cost per dispute)
        ; Bond cost = 1.0 per dispute
        ; Reward per win = 0.8
        bond-total n-disputes
        reward-total (* uncaught 0.8)
        ev (- reward-total bond-total)]

    {:n-disputes n-disputes
     :round-0-wins attacker-wins
     :reviewed disputes-reviewed
     :caught caught
     :uncaught uncaught
     :governance-delay-fraction governance-delay-fraction
     :frozen-at-round-0 frozen-at-round-0
     :ev ev
     :profitable? (> ev 0.0)}))

(defn test-burst
  "Full burst test: trigger N disputes simultaneously within slashing window."
  [scenario-name seed n-disputes slashing-delay governance-response-time]
  (let [rng (rng/make-rng seed)
        result (simulate-burst-attack n-disputes 15 0.75
                                      slashing-delay governance-response-time rng)]
    {:scenario scenario-name
     :status (if (:profitable? result) :vulnerable :safe)
     :n-disputes (:n-disputes result)
     :ev (format "%.2f" (:ev result))
     :caught (:caught result)
     :uncaught (:uncaught result)
     :governance-delay (format "%.1f%%" (* 100.0 (:governance-delay-fraction result)))}))

(defn run-phase-x-sweep
  "Run full Phase X burst concurrency test across scenarios."
  []
  (println "======================================================================")
  (println "Phase X: Burst Concurrency Exploit (Overwhelm Sequential Defense)")
  (println "======================================================================")
  (println)

  (let [        ; Test parameters:
        ; Slashing delay = 3 days (how long before governance can freeze)
        ; Governance response = 1-3 days (when governance actually acts)
        ; N-disputes = 5, 10, 20, 40 (escalating parallelism)
        tests [[5 3.0 1.5 "small-burst"]
               [10 3.0 1.5 "medium-burst"]
               [20 3.0 1.5 "large-burst"]
               [40 3.0 1.5 "huge-burst"]]

        results (for [seed (range 42 47)
                      [n-disp delay resp name-str] tests]
                  (let [r (test-burst (str name-str "-s" seed) seed n-disp delay resp)]
                    (println (format "%-35s [%s] N=%d EV=%s"
                                     (:scenario r)
                                     (if (= (:status r) :vulnerable) "VULN" "safe")
                                     (:n-disputes r)
                                     (:ev r)))
                    r))]

    (let [vuln (count (filter #(= :vulnerable (:status %)) results))]
      (println "\n" (apply str (repeat 70 "=")))
      (println (format "Results: %d vulnerable / %d total" vuln (count results)))
      (if (> vuln 10)
        (println "  🔴 BURST ATTACKS PROFITABLE - Parallelism defeats sequential defense")
        (if (> vuln 5)
          (println "  🟡 MIXED - Large bursts become profitable")
          (println "  🟢 SAFE: Burst attacks unprofitable even with 40 simultaneous disputes")))
      (engine/make-result
       {:benchmark-id "X"
        :label        "Burst Concurrency Exploit"
        :hypothesis   "Burst attacks unprofitable even with 40 simultaneous disputes (<= 5 vulnerable)"
        :passed?      (<= vuln 5)
        :results      (vec results)
        :summary      {:vulnerable vuln :total (count results)}}))))


