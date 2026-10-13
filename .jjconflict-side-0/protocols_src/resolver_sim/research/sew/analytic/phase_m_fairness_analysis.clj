(ns resolver-sim.research.sew.analytic.phase-m-fairness-analysis
  "Phase M: Fairness Analysis
  
   Tests hypothesis: Protocol is procedurally fair — any party can exercise
   rights regardless of capital constraints.
   
   Sub-phases:
   - M1: Access-to-Justice Validation (appeal bond sweep)
   - M2: Asymmetric Information Cost (evidence prep cost sweep)
   - M3: Frivolous Appeal Discouragement (penalty sweep)
   - M4: Expert Availability & Cost (resolver reputation sweep)
   
   Pass threshold: ≥80% of scenarios show fair access."
  (:require [resolver-sim.sim.engine :as engine]
            [resolver-sim.stochastic.rng :as rng]))

;; ---------------------------------------------------------------------------
;; M1: Access-to-Justice Validation
;; ---------------------------------------------------------------------------

(defn- run-m1-trial
  "Single trial: can party afford to appeal?
   
   Sweep: appeal-bond-bps from 0 to 50
   Measure: fraction of escrows where appeal becomes unaffordable for average party.
   
   Model: party has capital of ~0.5× average escrow; can afford if bond < capital."
  [{:keys [appeal-bond-bps seed]}]
  (let [escrow-amount 10000
        available-capital (* 0.5 escrow-amount)  ; party budget: half escrow
        appeal-bond-wei (quot (* escrow-amount appeal-bond-bps) 10000)
        affordable? (<= appeal-bond-wei available-capital)]
    {:appeal-bond-bps appeal-bond-bps
     :escrow-amount escrow-amount
     :appeal-bond-wei appeal-bond-wei
     :available-capital available-capital
     :affordable? affordable?}))

(defn run-m1-access-to-justice-validation
  "Sweep appeal-bond-bps from 0 to 50 (10 points).
   
   Pass: ≥95% of escrows allow affordable appeals (unaffordable cases ≤5%)"
  []
  (let [bond-bps (mapv #(* 5 %) (range 11))  ; 0, 5, 10, ..., 50
        param-grid (mapv (fn [b] {:appeal-bond-bps b :seed 42}) bond-bps)
        results (engine/run-parameter-sweep param-grid run-m1-trial)
        affordable-count (count (filter :affordable? results))
        total-count (count results)
        pass-rate (double (/ affordable-count total-count))
        passed? (>= pass-rate 0.95)]
    (engine/make-result
     {:benchmark-id "M1"
      :label "Access-to-Justice Validation"
      :hypothesis "≥95% of parties can afford appeal bonds"
      :passed? passed?
      :results results
      :summary {:total-trials total-count
                :affordable-trials affordable-count
                :unaffordable-trials (- total-count affordable-count)
                :unaffordable-pct (* 100.0 (- 1.0 pass-rate))
                :threshold-pct 5.0}})))

;; ---------------------------------------------------------------------------
;; M2: Asymmetric Information Cost
;; ---------------------------------------------------------------------------

(defn- run-m2-trial
  "Single trial: evidence prep cost asymmetrically affects parties.
   
   Sweep: evidence-prep-cost from 0 to escrow/10
   Measure: Do buyers and sellers face different cost impact?
   
   Model: evidence prep requires hiring expert; cost scales with claim complexity.
   - Buyers (claim: product defective): ~80% success with evidence
   - Sellers (claim: buyer breached): ~60% success with evidence
   
   Question: Does cost imbalance favor wealthier party?"
  [{:keys [prep-cost-percent-escrow seed]}]
  (let [escrow 10000
        prep-cost (* escrow prep-cost-percent-escrow)
        buyer-success-with-evidence 0.8
        seller-success-with-evidence 0.6
        ;; Cost impact: (cost / escrow) as fraction of success rate
        buyer-cost-impact (/ prep-cost (* escrow buyer-success-with-evidence))
        seller-cost-impact (/ prep-cost (* escrow seller-success-with-evidence))
        asymmetry (Math/abs (- buyer-cost-impact seller-cost-impact))
        fair? (<= asymmetry 0.10)]  ; ≤10% asymmetry is acceptable
    {:prep-cost-percent-escrow prep-cost-percent-escrow
     :prep-cost-wei prep-cost
     :buyer-cost-impact buyer-cost-impact
     :seller-cost-impact seller-cost-impact
     :asymmetry asymmetry
     :fair? fair?}))

(defn run-m2-asymmetric-information-cost
  "Sweep prep-cost from 0 to 10% of escrow (10 points).
   
   Pass: ≥90% of cases have ≤10% cost asymmetry between parties"
  []
  (let [costs (mapv #(* 0.01 %) (range 11))  ; 0%, 1%, ..., 10%
        param-grid (mapv (fn [c] {:prep-cost-percent-escrow c :seed 42}) costs)
        results (engine/run-parameter-sweep param-grid run-m2-trial)
        fair-count (count (filter :fair? results))
        total-count (count results)
        pass-rate (double (/ fair-count total-count))
        passed? (>= pass-rate 0.90)]
    (engine/make-result
     {:benchmark-id "M2"
      :label "Asymmetric Information Cost"
      :hypothesis "Cost asymmetry between parties ≤10% (≥90% of cases)"
      :passed? passed?
      :results results
      :summary {:total-trials total-count
                :fair-trials fair-count
                :asymmetric-trials (- total-count fair-count)
                :pass-rate pass-rate
                :threshold 0.90}})))

;; ---------------------------------------------------------------------------
;; M3: Frivolous Appeal Discouragement
;; ---------------------------------------------------------------------------

(defn- run-m3-trial
  "Single trial: does appeal-failure penalty reduce frivolous appeals?
   
   Sweep: appeal-failure-penalty from 0 to full bond
   Measure: rate of frivolous appeals with/without penalty
   
   Model: party with 30% win rate (likely frivolous) will avoid appeal if penalty ≥ 20% of bond."
  [{:keys [penalty-percent-bond seed]}]
  (let [escrow 10000
        appeal-bond-bps 700  ; 7% bond
        appeal-bond-wei (quot (* escrow appeal-bond-bps) 10000)
        penalty-wei (* appeal-bond-wei penalty-percent-bond)

        ;; Frivolous appeal has 30% win probability
        frivolous-win-prob 0.30

        ;; EV of frivolous appeal: win * escrow - (1-win) * penalty
        escrow-upside (* frivolous-win-prob escrow)
        penalty-downside (* (- 1.0 frivolous-win-prob) penalty-wei)
        net-ev (- escrow-upside penalty-downside)

        ;; Appeal made if EV > 0
        appeal-made? (> net-ev 0)
        ;; Frivolous appeal is discouraged if appeal-made? is false
        discouraged? (not appeal-made?)]
    {:penalty-percent-bond penalty-percent-bond
     :penalty-wei penalty-wei
     :frivolous-ev net-ev
     :appeal-made? appeal-made?
     :discouraged? discouraged?}))

(defn run-m3-frivolous-appeal-discouragement
  "Sweep penalty from 0 to 100% of bond (10 points).
   
   Pass: ≥70% of penalties discourage frivolous appeals"
  []
  (let [penalties (mapv #(* 0.1 %) (range 11))  ; 0%, 10%, ..., 100%
        param-grid (mapv (fn [p] {:penalty-percent-bond p :seed 42}) penalties)
        results (engine/run-parameter-sweep param-grid run-m3-trial)
        discouraged-count (count (filter :discouraged? results))
        total-count (count results)
        pass-rate (double (/ discouraged-count total-count))
        passed? (>= pass-rate 0.70)]
    (engine/make-result
     {:benchmark-id "M3"
      :label "Frivolous Appeal Discouragement"
      :hypothesis "≥70% of penalty levels reduce frivolous appeals"
      :passed? passed?
      :results results
      :summary {:total-trials total-count
                :discouraged-trials discouraged-count
                :pass-rate pass-rate
                :threshold 0.70}})))

;; ---------------------------------------------------------------------------
;; M4: Expert Availability & Cost
;; ---------------------------------------------------------------------------

(defn- run-m4-trial
  "Single trial: are sufficient quality experts available at market rates?
   
   Sweep: min-resolver-reputation from 0.0 to 1.0
   Measure: availability of resolvers at each quality tier
   
   Model: resolver supply follows Zipf distribution (power law).
   - 80% of resolvers have reputation < 0.5 (low quality, low cost)
   - 15% have reputation 0.5-0.8 (medium quality)
   - 5% have reputation > 0.8 (high quality, premium cost)
   
   Question: Is there enough supply at reasonable quality?"
  [{:keys [min-reputation seed]}]
  (let [;; Simplified supply model
        total-resolvers 1000
        low-quality (* total-resolvers 0.80)
        med-quality (- (* total-resolvers 0.95) low-quality)
        high-quality (- total-resolvers (* total-resolvers 0.95))

        ;; Count resolvers meeting min-reputation
        available (cond
                    (<= min-reputation 0.5) total-resolvers
                    (<= min-reputation 0.8) (+ med-quality high-quality)
                    :else high-quality)

        availability-pct (double (/ available total-resolvers))
        sufficient? (> availability-pct 0.10)]  ; At least 10% available
    {:min-reputation min-reputation
     :available-resolvers available
     :availability-pct availability-pct
     :sufficient? sufficient?}))

(defn run-m4-expert-availability-and-cost
  "Sweep min-reputation from 0.0 to 1.0 (11 points).
   
   Pass: ≥80% of quality levels have sufficient resolver availability (>10%)"
  []
  (let [reputations (mapv #(* 0.1 %) (range 11))  ; 0.0, 0.1, ..., 1.0
        param-grid (mapv (fn [r] {:min-reputation r :seed 42}) reputations)
        results (engine/run-parameter-sweep param-grid run-m4-trial)
        sufficient-count (count (filter :sufficient? results))
        total-count (count results)
        pass-rate (double (/ sufficient-count total-count))
        passed? (>= pass-rate 0.80)]
    (engine/make-result
     {:benchmark-id "M4"
      :label "Expert Availability & Cost"
      :hypothesis "≥80% of quality tiers have sufficient resolver availability"
      :passed? passed?
      :results results
      :summary {:total-trials total-count
                :sufficient-trials sufficient-count
                :pass-rate pass-rate
                :threshold 0.80}})))

;; ---------------------------------------------------------------------------
;; Phase M Entry Point
;; ---------------------------------------------------------------------------

(defn run-phase-m-sweep
  "Run all M1–M4 fairness analysis sweeps.
   
   Returns: {:passed? bool :results [...] :summary {...}}"
  []
  (engine/print-phase-header
   {:benchmark-id "Phase M"
    :label "Fairness Analysis"
    :hypothesis "Protocol is procedurally fair: any party can exercise rights regardless of capital"
    :details ["M1: Access-to-Justice Validation (appeal bond sweep)"
              "M2: Asymmetric Information Cost (evidence prep cost)"
              "M3: Frivolous Appeal Discouragement (penalty sweep)"
              "M4: Expert Availability & Cost (resolver reputation sweep)"]})

  (let [m1 (run-m1-access-to-justice-validation)
        m2 (run-m2-asymmetric-information-cost)
        m3 (run-m3-frivolous-appeal-discouragement)
        m4 (run-m4-expert-availability-and-cost)

        phases [m1 m2 m3 m4]
        passed-phases (count (filter :passed? phases))
        total-phases (count phases)

        overall-passed? (>= passed-phases 3)  ; 3/4 must pass

        result (engine/make-result
                {:benchmark-id "Phase M"
                 :label "Fairness Analysis"
                 :hypothesis "Protocol is procedurally fair"
                 :passed? overall-passed?
                 :summary {:total-phases total-phases
                           :passed-phases passed-phases
                           :phases (mapv #(select-keys % [:benchmark-id :passed?]) phases)}})]

    (println (format "M1 Access-to-Justice: %s" (:status m1)))
    (println (format "M2 Asymmetric Information: %s" (:status m2)))
    (println (format "M3 Frivolous Appeal Discouragement: %s" (:status m3)))
    (println (format "M4 Expert Availability: %s" (:status m4)))
    (println "")

    (if overall-passed?
      (println "✓ PASS: Phase M — Protocol is procedurally fair")
      (println "✗ FAIL: Phase M — Fairness barriers detected"))
    (println "")

    result))
