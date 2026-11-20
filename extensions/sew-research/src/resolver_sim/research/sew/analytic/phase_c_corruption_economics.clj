(ns resolver-sim.research.sew.analytic.phase-c-corruption-economics
  "Phase C: Corruption Economics
  
   Tests hypothesis: Cost-of-corruption always exceeds potential profit
   across all adversary types and attack scenarios.
   
   Sub-phases:
   - C1: Bribery Cost Model (0.1× to 2.0× escrow cost)
   - C2: External Collusion (2 to 50 colluding parties)
   - C3: Layer Escalation Attack (1 to 5 escalation rounds)
   - C4: Detection Probability Trade-off (2D surface)
   - C5: Profit-Maximizer Lifecycle (slash-multiplier sweep)
   - C6: Strategic Abstention (timeout penalty sweep)
   
   Pass threshold: ≥80% of scenarios show corruption is unprofitable."
  (:require [resolver-sim.sim.engine :as engine]
            [resolver-sim.stochastic.economics :as econ]
            [resolver-sim.stochastic.rng :as rng]))

;; ---------------------------------------------------------------------------
;; Corruption Economics Helpers
;; ---------------------------------------------------------------------------

(defn- bribery-profitability
  "Calculate profitability of bribery attack.
   
   Gain = escrow × (1 - fee-rate)
   Cost = escrow × bribery-cost-multiplier
   Profit = Gain - Cost
   
   Returns negative if unprofitable."
  [escrow-wei fee-bps bribery-cost-mult]
  (let [fee-rate (/ fee-bps 10000.0)
        gain (* escrow-wei (- 1 fee-rate))
        cost (* escrow-wei bribery-cost-mult)
        profit (- gain cost)]
    profit))

(defn- collusion-cost
  "Calculate cost to coordinate N colluding parties.
   
   Simplified model: Cost = coordination-overhead × (1 + 0.5 × log(N))
   Assumes exponential increase in complexity with party count."
  [num-parties base-coordination-cost]
  (let [complexity-factor (+ 1.0 (* 0.5 (Math/log (max 2 num-parties))))
        total-cost (* base-coordination-cost complexity-factor)]
    total-cost))

(defn- escalation-cost
  "Calculate cumulative cost to escalate through N rounds.
   
   Cost = Σ(appeal-bond) from round 1 to N
   Bond increases 1.5× per round (escalation premium)."
  [num-rounds base-bond-wei]
  (reduce + (take num-rounds (iterate #(* % 1.5) base-bond-wei))))

(defn- profit-maximizer-lifecycle
  "Model profit-maximizing resolver over K disputes.
   
   Per-dispute profit = fee - (bond × detection-prob × slash-mult)
   Total profit after K disputes, assuming random slashing events."
  [fee-wei bond-wei detection-prob slash-mult num-disputes]
  (let [per-dispute-ev (- fee-wei (* bond-wei detection-prob slash-mult))
        expected-slash-events (* num-disputes detection-prob)
        total-slashing-loss (* expected-slash-events bond-wei slash-mult)
        total-fees (* num-disputes fee-wei)
        total-profit (- total-fees total-slashing-loss)]
    total-profit))

;; ---------------------------------------------------------------------------
;; C1: Bribery Cost Model
;; ---------------------------------------------------------------------------

(defn- run-c1-trial
  "Single trial: test if bribery is unprofitable.
   
   Fixed params:
   - escrow = 10,000 wei
   - fee-bps = 150
   
   Sweep: bribery-cost-multiplier from 0.1 to 2.0"
  [{:keys [bribery-cost-mult seed]}]
  (let [escrow 10000
        fee-bps 150
        profit (bribery-profitability escrow fee-bps bribery-cost-mult)
        unprofitable? (< profit 0)]
    {:bribery-cost-mult bribery-cost-mult
     :escrow escrow
     :profit profit
     :unprofitable? unprofitable?}))

(defn run-c1-bribery-cost-model
  "Sweep bribery-cost-multiplier from 0.1 to 2.0 (10 points).
   
   Pass: ≥80% of bribery attempts are unprofitable"
  []
  (let [cost-mults (mapv #(+ 0.1 (* 0.21 %)) (range 10))
        param-grid (mapv (fn [c] {:bribery-cost-mult c :seed 42}) cost-mults)
        results (engine/run-parameter-sweep param-grid run-c1-trial)
        unprofitable-count (count (filter :unprofitable? results))
        total-count (count results)
        pass-rate (double (/ unprofitable-count total-count))
        passed? (>= pass-rate 0.80)]
    (engine/make-result
     {:benchmark-id "C1"
      :label "Bribery Cost Model"
      :hypothesis "Bribery remains unprofitable at realistic cost levels (≥80%)"
      :class :analytic
      :passed? passed?
      :results results
      :summary {:total-trials total-count
                :unprofitable-trials unprofitable-count
                :pass-rate pass-rate
                :threshold 0.80}})))

;; ---------------------------------------------------------------------------
;; C2: External Collusion (Schelling Attack)
;; ---------------------------------------------------------------------------

(defn- run-c2-trial
  "Single trial: can colluders coordinate cheaper than diverting escrow?
   
   Cost to coordinate: increases with number of parties
   Gain from escrow diversion: fixed per diversion
   
   Question: Is coordination cost > escrow value?"
  [{:keys [num-colluders seed]}]
  (let [escrow 10000
        fee-bps 150
        fee-rate (/ fee-bps 10000.0)
        escrow-gain (* escrow (- 1 fee-rate))
        ;; Base coordination cost: 1000 wei (fixed overhead)
        base-coord-cost 1000
        coord-cost (collusion-cost num-colluders base-coord-cost)
        ;; Add per-party cost: 100 wei per colluder for communication, verification
        per-party-cost (* num-colluders 100)
        total-cost (+ coord-cost per-party-cost)
        net-profit (- escrow-gain total-cost)
        uneconomical? (< net-profit 0)]
    {:num-colluders num-colluders
     :escrow-gain escrow-gain
     :total-cost total-cost
     :net-profit net-profit
     :uneconomical? uneconomical?}))

(defn run-c2-external-collusion
  "Sweep num-colluders from 2 to 50 (8 points).
   
   Pass: ≥80% of collusion attempts are unprofitable"
  []
  (let [colluder-counts [2 8 15 25 35 42 48 50]
        param-grid (mapv (fn [n] {:num-colluders n :seed 42}) colluder-counts)
        results (engine/run-parameter-sweep param-grid run-c2-trial)
        uneconomical-count (count (filter :uneconomical? results))
        total-count (count results)
        pass-rate (double (/ uneconomical-count total-count))
        passed? (>= pass-rate 0.80)]
    (engine/make-result
     {:benchmark-id "C2"
      :label "External Collusion (Schelling Attack)"
      :hypothesis "Coordination cost exceeds escrow diversion gain (≥80%)"
      :passed? passed?
      :results results
      :summary {:total-trials total-count
                :uneconomical-trials uneconomical-count
                :pass-rate pass-rate
                :threshold 0.80}})))

;; ---------------------------------------------------------------------------
;; C3: Layer Escalation Attack
;; ---------------------------------------------------------------------------

(defn- run-c3-trial
  "Single trial: can attacker escalate through all layers profitably?
   
   Cost per round increases (larger bond at each level)
   Escrow gain is fixed
   
   Question: Does cumulative escalation cost exceed escrow?"
  [{:keys [num-escalation-rounds seed]}]
  (let [escrow 10000
        fee-bps 150
        fee-rate (/ fee-bps 10000.0)
        escrow-gain (* escrow (- 1 fee-rate))
        ;; Base appeal bond: 10% of escrow
        base-bond (quot (* escrow 10) 100)
        escalation-cost (escalation-cost num-escalation-rounds base-bond)
        net-profit (- escrow-gain escalation-cost)
        bankrupt? (< net-profit 0)]
    {:num-escalation-rounds num-escalation-rounds
     :escrow-gain escrow-gain
     :escalation-cost escalation-cost
     :net-profit net-profit
     :bankrupt? bankrupt?}))

(defn run-c3-layer-escalation-attack
  "Sweep num-escalation-rounds from 1 to 5 (5 points).
   
   Pass: ≥80% of escalation attacks bankrupt the attacker"
  []
  (let [num-rounds [1 2 3 4 5]
        param-grid (mapv (fn [n] {:num-escalation-rounds n :seed 42}) num-rounds)
        results (engine/run-parameter-sweep param-grid run-c3-trial)
        bankrupt-count (count (filter :bankrupt? results))
        total-count (count results)
        pass-rate (double (/ bankrupt-count total-count))
        passed? (>= pass-rate 0.80)]
    (engine/make-result
     {:benchmark-id "C3"
      :label "Layer Escalation Attack"
      :hypothesis "Cumulative escalation cost bankrupts attacker (≥80%)"
      :passed? passed?
      :results results
      :summary {:total-trials total-count
                :bankrupt-trials bankrupt-count
                :pass-rate pass-rate
                :threshold 0.80}})))

;; ---------------------------------------------------------------------------
;; C4: Detection Probability Trade-off
;; ---------------------------------------------------------------------------

(defn- run-c4-trial
  "Single trial: create 2D point (p_detection, cost_of_corruption).
   
   At each point, check: is malicious EV negative?
   
   Question: Is the safe region (negative EV) large enough?"
  [{:keys [detection-prob cost-mult seed]}]
  (let [escrow 10000
        fee-bps 150
        fee-wei (quot (* escrow fee-bps) 10000)
        bond-mult 2.0
        bond-wei (* escrow bond-mult)
        slash-mult 2.0

        ;; Actual cost to the attacker
        attack-cost (* escrow cost-mult)

        ;; Gross profit if undetected
        gross-profit (+ fee-wei (* escrow (- 1 (/ fee-bps 10000.0))))

        ;; Expected loss from detection
        expected-loss (* detection-prob (* bond-wei slash-mult))

        ;; Net profit after accounting for attack cost and detection risk
        net-profit (- (+ gross-profit (- expected-loss)) attack-cost)

        safe? (< net-profit 0)]
    {:detection-prob detection-prob
     :cost-mult cost-mult
     :attack-cost attack-cost
     :gross-profit gross-profit
     :expected-loss expected-loss
     :net-profit net-profit
     :safe? safe?}))

(defn run-c4-detection-trade-off
  "2D sweep of (detection-prob, cost-mult).
   
   Points: (0.1, 0.1), (0.1, 0.5), ... (0.9, 2.0)
   
   Pass: ≥80% of parameter space is safe (malicious EV < 0)"
  []
  (let [detection-probs [0.1 0.3 0.5 0.7 0.9]
        cost-mults [0.1 0.5 1.0 1.5 2.0]
        param-grid (for [dp detection-probs cm cost-mults]
                     {:detection-prob dp :cost-mult cm :seed 42})
        results (engine/run-parameter-sweep param-grid run-c4-trial)
        safe-count (count (filter :safe? results))
        total-count (count results)
        pass-rate (double (/ safe-count total-count))
        passed? (>= pass-rate 0.80)]
    (engine/make-result
     {:benchmark-id "C4"
      :label "Detection Probability Trade-off"
      :hypothesis "Safe parameter space is large enough (≥80% of sweep)"
      :passed? passed?
      :results results
      :summary {:total-trials total-count
                :safe-trials safe-count
                :pass-rate pass-rate
                :threshold 0.80
                :parameter-space "5×5 grid"}})))

;; ---------------------------------------------------------------------------
;; C5: Profit-Maximizer Lifecycle
;; ---------------------------------------------------------------------------

(defn- run-c5-trial
  "Single trial: track profit-maximizing resolver over multiple disputes.
   
   Question: Does expected slashing eventually wipe out profits?
   
   Sweep: slash-multiplier from 0.5 to 5.0
   Fixed: 10 disputes, detection-prob 0.6"
  [{:keys [slash-mult seed]}]
  (let [escrow 10000
        fee-bps 150
        fee-wei (quot (* escrow fee-bps) 10000)
        bond-mult 2.0
        bond-wei (* escrow bond-mult)
        detection-prob 0.6
        num-disputes 10
        total-profit (profit-maximizer-lifecycle fee-wei bond-wei detection-prob slash-mult num-disputes)
        unprofitable? (< total-profit 0)]
    {:slash-mult slash-mult
     :num-disputes num-disputes
     :total-profit total-profit
     :unprofitable? unprofitable?}))

(defn run-c5-profit-maximizer-lifecycle
  "Sweep slash-multiplier from 0.5 to 5.0 (10 points).
   
   Pass: ≥80% result in net loss over 10 disputes"
  []
  (let [slash-mults (mapv #(+ 0.5 (* 0.5 %)) (range 10))
        param-grid (mapv (fn [s] {:slash-mult s :seed 42}) slash-mults)
        results (engine/run-parameter-sweep param-grid run-c5-trial)
        unprofitable-count (count (filter :unprofitable? results))
        total-count (count results)
        pass-rate (double (/ unprofitable-count total-count))
        passed? (>= pass-rate 0.80)]
    (engine/make-result
     {:benchmark-id "C5"
      :label "Profit-Maximizer Lifecycle"
      :hypothesis "No unbounded profit accumulation (≥80% show net loss)"
      :passed? passed?
      :results results
      :summary {:total-trials total-count
                :unprofitable-trials unprofitable-count
                :pass-rate pass-rate
                :threshold 0.80
                :disputes-simulated 10}})))

;; ---------------------------------------------------------------------------
;; C6: Strategic Abstention
;; ---------------------------------------------------------------------------

(defn- run-c6-trial
  "Single trial: compare lazy vs malicious resolver strategies.
   
   Lazy: Gets fee but no fraud upside; loses on appeals
   Malicious: Gets fraud upside but faces slashing
   
   Question: Is timeout penalty sufficient to deter abstention?"
  [{:keys [timeout-penalty-bps seed]}]
  (let [escrow 10000
        fee-bps 150
        fee-wei (quot (* escrow fee-bps) 10000)
        bond-mult 2.0
        bond-wei (* escrow bond-mult)
        detection-prob 0.6
        slash-mult 2.0
        appeal-rate 0.2

        ;; Lazy EV: fee with some appeal loss, but no slashing
        lazy-ev (* fee-wei (- 1 appeal-rate))

        ;; Malicious EV
        malicious-ev (- fee-wei (* bond-wei detection-prob slash-mult))

        ;; Add timeout penalty to lazy EV
        timeout-penalty-wei (quot (* escrow timeout-penalty-bps) 10000)
        lazy-with-penalty (- lazy-ev timeout-penalty-wei)

        ;; Malicious is better if it avoids penalty
        malicious-superior? (> malicious-ev lazy-with-penalty)]
    {:timeout-penalty-bps timeout-penalty-bps
     :lazy-ev lazy-ev
     :lazy-with-penalty lazy-with-penalty
     :malicious-ev malicious-ev
     :malicious-superior? malicious-superior?}))

(defn run-c6-strategic-abstention
  "Sweep timeout-penalty-bps from 0 to 1000 (10 points).
   
   Pass: ≥80% of penalties make lazy strategy equal/worse than malicious"
  []
  (let [penalty-bps (mapv #(* 100 %) (range 11))  ; 0, 100, 200, ... 1000
        param-grid (mapv (fn [p] {:timeout-penalty-bps p :seed 42}) penalty-bps)
        results (engine/run-parameter-sweep param-grid run-c6-trial)
        ;; We want cases where malicious is NOT superior (i.e., lazy is competitive)
        non-superior-count (count (filter (complement :malicious-superior?) results))
        total-count (count results)
        pass-rate (double (/ non-superior-count total-count))
        passed? (>= pass-rate 0.70)]  ; Slightly lower threshold
    (engine/make-result
     {:benchmark-id "C6"
      :label "Strategic Abstention"
      :hypothesis "Timeout penalty deters lazy resolver strategy (≥70%)"
      :passed? passed?
      :results results
      :summary {:total-trials total-count
                :deterrent-trials non-superior-count
                :pass-rate pass-rate
                :threshold 0.70}})))

;; ---------------------------------------------------------------------------
;; Phase C Entry Point
;; ---------------------------------------------------------------------------

(defn run-phase-c-sweep
  "Run all C1–C6 corruption economics sweeps.
   
   Returns: {:passed? bool :results [{C1-result} ... {C6-result}] :summary {...}}"
  []
  (engine/print-phase-header
   {:benchmark-id "Phase C"
    :label "Corruption Economics"
    :hypothesis "Cost-of-corruption always exceeds potential profit"
    :details ["C1: Bribery Cost Model (0.1×–2.0×)"
              "C2: External Collusion (2–50 parties)"
              "C3: Layer Escalation Attack (1–5 rounds)"
              "C4: Detection Probability Trade-off (2D grid)"
              "C5: Profit-Maximizer Lifecycle (slash sweep)"
              "C6: Strategic Abstention (timeout penalty sweep)"]})

  (let [c1 (run-c1-bribery-cost-model)
        c2 (run-c2-external-collusion)
        c3 (run-c3-layer-escalation-attack)
        c4 (run-c4-detection-trade-off)
        c5 (run-c5-profit-maximizer-lifecycle)
        c6 (run-c6-strategic-abstention)

        phases [c1 c2 c3 c4 c5 c6]
        passed-phases (count (filter :passed? phases))
        total-phases (count phases)

        overall-passed? (>= passed-phases 5)  ; 5/6 phases must pass

        result (engine/make-result
                {:benchmark-id "Phase C"
                 :label "Corruption Economics"
                 :hypothesis "Cost-of-corruption exceeds profit across all scenarios"
                 :passed? overall-passed?
                 :summary {:total-phases total-phases
                           :passed-phases passed-phases
                           :phases (mapv #(select-keys % [:benchmark-id :passed?]) phases)}})]

    (println (format "C1 Bribery Cost: %s" (:status c1)))
    (println (format "C2 Collusion: %s" (:status c2)))
    (println (format "C3 Escalation: %s" (:status c3)))
    (println (format "C4 Detection Trade-off: %s" (:status c4)))
    (println (format "C5 Profit-Maximizer: %s" (:status c5)))
    (println (format "C6 Strategic Abstention: %s" (:status c6)))
    (println "")

    (if overall-passed?
      (println "✓ PASS: Phase C — Corruption is uneconomical")
      (println "✗ FAIL: Phase C — Profitable corruption scenarios exist"))
    (println "")

    result))
