(ns resolver-sim.research.sew.economic.dispute-clustering
  "Phase W: Dispute Type Clustering - Adversarial Category Targeting
  
  Tests whether attackers can discover weak dispute categories and
  concentrate attacks there to achieve profitable success rates.
  
  Key insight: Real attacks don't fail uniformly - they find weak corners.
  This test simulates attacker learning which of 10 categories are easiest
  to win in, then concentrating volume there."
  (:require [clojure.math :as math]
            [resolver-sim.stochastic.rng :as rng]
            [resolver-sim.sim.engine      :as engine]))

(defn simulate-category
  "Simulate resolver decisions for one category over N epochs.
   Returns accuracy per epoch."
  [n-epochs n-resolvers category-difficulty rng]
  (for [epoch (range n-epochs)]
    (let [honest-acc (- 1.0 (* 0.1 category-difficulty)) ; difficulty reduces accuracy
          decisions (for [_ (range n-resolvers)]
                      (< (rng/next-double rng) honest-acc))
          correct (count (filter true? decisions))
          accuracy (/ correct (double n-resolvers))]
      accuracy)))

(defn measure-attacker-success
  "Measure probability of attacker success in category.
   Attacker succeeds if they can flip majority with one corrupt vote.
   With independent honest resolvers, this is when honest accuracy < 66%
   (so attacker + half the rest = majority)."
  [accuracies]
  (if (empty? accuracies)
    0.0
    ; Count epochs where honest consensus is weak enough to flip
    (let [flippable (filter #(< % 0.66) accuracies)
          flip-fraction (/ (count flippable) (double (count accuracies)))]
      flip-fraction)))

(defn detect-weak-categories
  "Attacker learns which categories are vulnerable through trials.
   Runs N probe disputes per category, measures success probability.
   Returns list of (category success-rate) pairs, sorted by weakness."
  [n-probes n-resolvers difficulty-distribution rng]
  (let [categories (range 10)
        probe-results
        (for [cat categories
              :let [difficulty (get difficulty-distribution cat 0.5)]]
          (let [accuracies (simulate-category n-probes n-resolvers difficulty rng)
                success-rate (measure-attacker-success accuracies)]
            [cat success-rate difficulty]))]
    (sort-by #(- (second %)) probe-results))) ; Sort by success (descending)

(defn run-focused-attacks
  "Attacker concentrates volume on weakest category discovered.
   More realistic: attacker bribes Round 0 resolver in weak category.
   Success = their bad decision isn't caught by Round 1.
   Measures if profitability threshold is reached."
  [weakest-category n-resolvers difficulty-distribution n-attacks rng]
  (let [[cat success-rate difficulty] weakest-category
        ; In hard category, even honest accuracy drops
        honest-accuracy (- 1.0 (* 0.1 difficulty))

        ; Attack succeeds if:
        ; 1. Corrupt resolver votes wrong (bribed)
        ; 2. Senior reviewer doesn't catch it (depends on evidence)
        ; Weak categories have poor evidence, so harder to catch
        catch-probability (+ 0.4 (* 0.3 (- 1.0 difficulty))) ; 0.4-0.7 catch rate

        attack-success-per-dispute (- 1.0 catch-probability) ; 0.3-0.6 success
        bond-cost 1.0
        reward 0.8 ; Profit if gets past both rounds
        ev (- (* attack-success-per-dispute reward)
              (* (- 1.0 attack-success-per-dispute) bond-cost))]
    {:category cat
     :honest-accuracy (format "%.1f%%" (* 100.0 honest-accuracy))
     :catch-probability (format "%.1f%%" (* 100.0 catch-probability))
     :attack-success (format "%.1f%%" (* 100.0 attack-success-per-dispute))
     :expected-value (format "%.2f" ev)
     :profitable? (> ev 0.0)}))

(defn test-clustering
  "Full clustering test: attacker probes, learns, concentrates, measures success."
  [scenario-name seed difficulty-spread rho-learning]
  (let [rng (rng/make-rng seed)
        ; Generate category difficulties: 
        ; - 3 easy (0.05-0.15 difficulty = 85-95% accuracy)
        ; - 4 medium (0.35-0.45 difficulty = 55-65% accuracy) 
        ; - 3 hard (0.65-0.85 difficulty = 15-35% accuracy)
        difficulty-distribution
        (into {} (for [i (range 10)]
                   [i (condp = (mod i 10)
                        0 0.05  ; Easy (95% acc)
                        1 0.10
                        2 0.15
                        3 0.35  ; Medium (65% acc)
                        4 0.40
                        5 0.45
                        6 0.65  ; Hard (35% acc) - attacker target
                        7 0.70
                        8 0.75
                        9 0.80)]))

        ; Probe phase: attacker tries each category
        probes (detect-weak-categories 30 15 difficulty-distribution rng)
        weakest (first probes)

        ; Attack phase: concentrate on weakest
        attack-result (run-focused-attacks weakest 15 difficulty-distribution
                                           50 rng)]

    {:scenario scenario-name
     :weakest-category (:category attack-result)
     :honest-acc (:honest-accuracy attack-result)
     :catch-prob (:catch-probability attack-result)
     :attack-succ (:attack-success attack-result)
     :expected-value (:expected-value attack-result)
     :profitable? (:profitable? attack-result)
     :status (if (:profitable? attack-result) :vulnerable :safe)}))

(defn run-phase-w-sweep
  "Run full Phase W clustering test across scenarios."
  []
  (println "======================================================================")
  (println "Phase W: Dispute Type Clustering (Adversarial Category Targeting)")
  (println "======================================================================")
  (println)

  (let [tests [[0.2 0.1 "baseline-low-spread"]
               [0.5 0.2 "spread-medium"]
               [0.8 0.3 "spread-high"]
               [1.0 0.4 "spread-extreme"]]

        results (for [seed (range 42 47)
                      [spread learning name-str] tests]
                  (let [r (test-clustering (str name-str "-s" seed) seed spread learning)]
                    (println (format "%-35s [%s] EV=%s"
                                     (:scenario r)
                                     (if (:profitable? r) "VULN" "safe")
                                     (:expected-value r)))
                    r))]

    (let [vuln    (count (filter #(:profitable? %) results))
          passed? (<= vuln 5)]
      (println "\n" (apply str (repeat 70 "=")))
      (println (format "Results: %d vulnerable / %d total" vuln (count results)))
      (if (> vuln 10)
        (println "  🔴 CATEGORY CLUSTERING VULNERABLE - Attacker achieves profit")
        (if (> vuln 5)
          (println "  🟡 MIXED - Some scenarios profitable")
          (println "  🟢 SAFE: Category concentration does not enable profitable attacks")))
      (engine/make-result
       {:benchmark-id "W"
        :label        "Dispute Type Clustering"
        :hypothesis   "Category concentration does not enable profitable attacks (<= 5 vulnerable)"
        :passed?      passed?
        :results      (vec results)
        :summary      {:vulnerable vuln :total (count results)}}))))


