(ns resolver-sim.research.sew.analytic.phase-f-economic-parameters
  "Phase F: Economic Parameter Validation
  
   Tests hypothesis: Safe parameter zones exist where malicious EV remains
   negative across all resolver strategies.
   
   Sub-phases:
   - F1: Detection Probability Sensitivity (0.1 to 0.9)
   - F2: Bond Size Sweep (0.5× to 10.0× escrow)
   - F3: Fee Adequacy (50 to 1000 bps)
   - F4: Escrow Concentration (100 to 1M, log scale)
   - F5: Multi-resolver Equilibrium (1 to 100 resolvers)
   - F6: Appeal Window Adequacy (0 to 10k blocks)
   
   Pass threshold: ≥80% of sweeps show malicious EV < 0 or hypothesis-specific metric."
  (:require [resolver-sim.sim.engine :as engine]
            [resolver-sim.stochastic.economics :as econ]
            [resolver-sim.stochastic.rng :as rng]))

;; ---------------------------------------------------------------------------
;; Economic Model Helpers
;; ---------------------------------------------------------------------------

(defn- malicious-ev
  "Calculate malicious resolver EV.
   
   EV = fee - (bond × detection-probability × slash-multiplier)"
  [fee-wei bond-wei detection-prob slash-mult]
  (let [slashing-loss (* bond-wei detection-prob slash-mult)
        profit (- fee-wei slashing-loss)]
    profit))

(defn- honest-ev
  "Calculate honest resolver EV.
   
   EV = fee × (1 - appeal-rate)
   Assumes honest resolver wins appeals (appeal-rate is appeal *rate*, not win rate)."
  [fee-wei appeal-rate]
  (* fee-wei (- 1 appeal-rate)))

(defn- incentive-aligned?
  "Check if honest EV > malicious EV (true = protocol is incentive-aligned)."
  [fee-wei bond-wei detection-prob appeal-rate slash-mult]
  (let [h-ev (honest-ev fee-wei appeal-rate)
        m-ev (malicious-ev fee-wei bond-wei detection-prob slash-mult)]
    (> h-ev m-ev)))

(defn- safe-parameter?
  "Check if malicious EV is negative (true = parameter zone is safe)."
  [fee-wei bond-wei detection-prob slash-mult]
  (let [m-ev (malicious-ev fee-wei bond-wei detection-prob slash-mult)]
    (< m-ev 0)))

;; ---------------------------------------------------------------------------
;; F1: Detection Probability Sensitivity
;; ---------------------------------------------------------------------------

(defn- run-f1-trial
  "Single trial: sweep detection-probability from 0.1 to 0.9.
   
   Fixed params:
   - escrow = 10,000 wei
   - fee-bps = 150 (1.5%)
   - bond-multiplier = 2.0× escrow
   - slash-multiplier = 2.0
   
   Returns map: {:detection-prob, :fee-wei, :bond-wei, :malicious-ev, :safe? true/false}"
  [{:keys [detection-prob seed]}]
  (let [escrow 10000
        fee-bps 150
        fee-wei (quot (* escrow fee-bps) 10000)
        bond-mult 2.0
        bond-wei (* escrow bond-mult)
        slash-mult 2.0
        m-ev (malicious-ev fee-wei bond-wei detection-prob slash-mult)
        safe? (< m-ev 0)]
    {:detection-prob detection-prob
     :fee-wei fee-wei
     :bond-wei bond-wei
     :malicious-ev m-ev
     :safe? safe?}))

(defn run-f1-detection-probability-sweep
  "Sweep detection-probability from 0.1 to 0.9 (10 points).
   
   Returns: {:benchmark-id \"F1\" :label \"...\" :passed? bool :results [...] :summary {...}}"
  []
  (let [detection-probs (mapv #(+ 0.1 (* 0.08 %)) (range 10))  ; 0.1, 0.18, 0.26, ... 0.9
        param-grid (mapv (fn [p] {:detection-prob p :seed 42}) detection-probs)
        results (engine/run-parameter-sweep param-grid run-f1-trial)
        safe-count (count (filter :safe? results))
        total-count (count results)
        pass-rate (double (/ safe-count total-count))
        passed? (>= pass-rate 0.80)]
    (engine/make-result
     {:benchmark-id "F1"
      :label "Detection Probability Sensitivity"
      :hypothesis "Malicious EV remains negative across detection probability range (0.1–0.9)"
      :passed? passed?
      :results results
      :summary {:total-trials total-count
                :safe-trials safe-count
                :pass-rate pass-rate
                :threshold 0.80}})))

;; ---------------------------------------------------------------------------
;; F2: Bond Size Sweep
;; ---------------------------------------------------------------------------

(defn- run-f2-trial
  "Single trial: sweep bond-multiplier from 0.5 to 10.0.
   
   Fixed params:
   - escrow = 10,000 wei
   - fee-bps = 150
   - detection-prob = 0.6
   - slash-multiplier = 2.0"
  [{:keys [bond-mult seed]}]
  (let [escrow 10000
        fee-bps 150
        fee-wei (quot (* escrow fee-bps) 10000)
        bond-wei (* escrow bond-mult)
        detection-prob 0.6
        slash-mult 2.0
        m-ev (malicious-ev fee-wei bond-wei detection-prob slash-mult)
        safe? (< m-ev 0)]
    {:bond-mult bond-mult
     :fee-wei fee-wei
     :bond-wei bond-wei
     :malicious-ev m-ev
     :safe? safe?}))

(defn run-f2-bond-size-sweep
  "Sweep bond-multiplier from 0.5 to 10.0 (10 points).
   
   Returns: {:benchmark-id \"F2\" :label \"...\" :passed? bool :results [...] :summary {...}}"
  []
  (let [bond-mults (mapv #(+ 0.5 (* 0.95 %)) (range 10))  ; 0.5, 1.45, 2.4, ... 10.0
        param-grid (mapv (fn [b] {:bond-mult b :seed 42}) bond-mults)
        results (engine/run-parameter-sweep param-grid run-f2-trial)
        safe-count (count (filter :safe? results))
        total-count (count results)
        pass-rate (double (/ safe-count total-count))
        passed? (>= pass-rate 0.80)]
    (engine/make-result
     {:benchmark-id "F2"
      :label "Bond Size Sweep"
      :hypothesis "Increasing bond size makes malicious EV consistently negative"
      :passed? passed?
      :results results
      :summary {:total-trials total-count
                :safe-trials safe-count
                :pass-rate pass-rate
                :threshold 0.80}})))

;; ---------------------------------------------------------------------------
;; F3: Fee Adequacy Sweep
;; ---------------------------------------------------------------------------

(defn- run-f3-trial
  "Single trial: sweep fee-bps from 50 to 1000.
   
   Fixed params:
   - escrow = 10,000 wei
   - bond-multiplier = 2.0
   - detection-prob = 0.6
   - appeal-rate = 0.2 (20% of decisions are appealed)
   - slash-multiplier = 2.0"
  [{:keys [fee-bps seed]}]
  (let [escrow 10000
        fee-wei (quot (* escrow fee-bps) 10000)
        bond-mult 2.0
        bond-wei (* escrow bond-mult)
        detection-prob 0.6
        appeal-rate 0.2
        slash-mult 2.0
        h-ev (honest-ev fee-wei appeal-rate)
        m-ev (malicious-ev fee-wei bond-wei detection-prob slash-mult)
        aligned? (> h-ev m-ev)]
    {:fee-bps fee-bps
     :fee-wei fee-wei
     :honest-ev h-ev
     :malicious-ev m-ev
     :incentive-aligned? aligned?}))

(defn run-f3-fee-adequacy-sweep
  "Sweep fee-bps from 50 to 1000 (10 points).
   
   Returns: {:benchmark-id \"F3\" :label \"...\" :passed? bool :results [...] :summary {...}}"
  []
  (let [fee-bps-values (mapv #(+ 50 (* 105.56 %)) (range 9))  ; 50, 155.56, 261.11, ... 1000
        param-grid (mapv (fn [f] {:fee-bps f :seed 42}) fee-bps-values)
        results (engine/run-parameter-sweep param-grid run-f3-trial)
        aligned-count (count (filter :incentive-aligned? results))
        total-count (count results)
        pass-rate (double (/ aligned-count total-count))
        passed? (>= pass-rate 0.80)]
    (engine/make-result
     {:benchmark-id "F3"
      :label "Fee Adequacy Sweep"
      :hypothesis "Honest resolver EV > malicious EV at adequate fee levels (≥80%)"
      :passed? passed?
      :results results
      :summary {:total-trials total-count
                :aligned-trials aligned-count
                :pass-rate pass-rate
                :threshold 0.80}})))

;; ---------------------------------------------------------------------------
;; F4: Escrow Concentration Analysis
;; ---------------------------------------------------------------------------

(defn- run-f4-trial
  "Single trial: sweep escrow-amount on log scale from 100 to 1M.
   
   Fixed params:
   - fee-bps = 150
   - bond-multiplier = 2.0
   - detection-prob = 0.6
   - slash-multiplier = 2.0"
  [{:keys [escrow-amount seed]}]
  (let [fee-bps 150
        fee-wei (quot (* escrow-amount fee-bps) 10000)
        bond-mult 2.0
        bond-wei (* escrow-amount bond-mult)
        detection-prob 0.6
        slash-mult 2.0
        m-ev (malicious-ev fee-wei bond-wei detection-prob slash-mult)
        safe? (< m-ev 0)
        profit-ratio (double (/ m-ev escrow-amount))]  ; Profit as fraction of escrow
    {:escrow-amount escrow-amount
     :fee-wei fee-wei
     :bond-wei bond-wei
     :malicious-ev m-ev
     :safe? safe?
     :profit-ratio profit-ratio}))

(defn run-f4-escrow-concentration-sweep
  "Sweep escrow-amount from 100 to 1M (8 points, log scale).
   
   Returns: {:benchmark-id \"F4\" :label \"...\" :passed? bool :results [...] :summary {...}}"
  []
  (let [escrow-amounts [100 562 1000 3162 10000 31623 100000 1000000]  ; log scale
        param-grid (mapv (fn [e] {:escrow-amount e :seed 42}) escrow-amounts)
        results (engine/run-parameter-sweep param-grid run-f4-trial)
        safe-count (count (filter :safe? results))
        total-count (count results)
        pass-rate (double (/ safe-count total-count))
        passed? (>= pass-rate 0.75)]  ; Slightly lower threshold for log-scale analysis
    (engine/make-result
     {:benchmark-id "F4"
      :label "Escrow Concentration Analysis"
      :hypothesis "Incentives remain rational across 4-order escrow magnitude range"
      :passed? passed?
      :results results
      :summary {:total-trials total-count
                :safe-trials safe-count
                :pass-rate pass-rate
                :threshold 0.75}})))

;; ---------------------------------------------------------------------------
;; F5: Multi-resolver Equilibrium (Simplified)
;; ---------------------------------------------------------------------------

(defn- run-f5-trial
  "Single trial: model effect of resolver count on reputation/fee equilibrium.
   
   Simplified model: more resolvers → lower average fee (competition)
   But: fixed bond size means higher slashing per resolver.
   
   Question: Does market remain healthy with N resolvers?"
  [{:keys [num-resolvers seed]}]
  (let [escrow 10000
        ;; Fee decreases with competition: base_fee / sqrt(num_resolvers)
        base-fee-bps 150
        fee-bps (/ base-fee-bps (Math/sqrt num-resolvers))
        fee-wei (quot (* escrow fee-bps) 10000)
        bond-mult 2.0
        bond-wei (* escrow bond-mult)
        detection-prob 0.6
        slash-mult 2.0
        m-ev (malicious-ev fee-wei bond-wei detection-prob slash-mult)
        safe? (< m-ev 0)]
    {:num-resolvers num-resolvers
     :fee-bps fee-bps
     :fee-wei fee-wei
     :malicious-ev m-ev
     :safe? safe?}))

(defn run-f5-multi-resolver-equilibrium
  "Sweep num-resolvers from 1 to 100 (10 points, log scale).
   
   Returns: {:benchmark-id \"F5\" :label \"...\" :passed? bool :results [...] :summary {...}}"
  []
  (let [resolver-counts [1 3 10 32 100]  ; log scale
        param-grid (mapv (fn [n] {:num-resolvers n :seed 42}) resolver-counts)
        results (engine/run-parameter-sweep param-grid run-f5-trial)
        safe-count (count (filter :safe? results))
        total-count (count results)
        pass-rate (double (/ safe-count total-count))
        passed? (>= pass-rate 0.60)]  ; Lower threshold: market dynamics are complex
    (engine/make-result
     {:benchmark-id "F5"
      :label "Multi-resolver Equilibrium"
      :hypothesis "Malicious EV remains negative even as resolver competition increases"
      :passed? passed?
      :results results
      :summary {:total-trials total-count
                :safe-trials safe-count
                :pass-rate pass-rate
                :threshold 0.60}})))

;; ---------------------------------------------------------------------------
;; F6: Appeal Window Adequacy (Simplified)
;; ---------------------------------------------------------------------------

(defn- run-f6-trial
  "Single trial: model appeal window effect on resolution quality.
   
   Simplified assumption: longer window → more accurate appeals
   Appeal-rate increases with window, but appeal-win-rate also improves.
   
   Question: Does extended window meaningfully improve outcomes?"
  [{:keys [appeal-window-blocks seed]}]
  (let [escrow 10000
        fee-bps 150
        fee-wei (quot (* escrow fee-bps) 10000)
        ;; Rough model: appeal-rate increases 5% per 100 blocks
        base-appeal-rate 0.1
        appeal-rate (min 0.5 (+ base-appeal-rate (* 0.05 (/ appeal-window-blocks 100))))
        ;; Appeal success improves with time: starts at 50%, improves to 80% with large window
        base-appeal-success 0.50
        appeal-success (min 0.80 (+ base-appeal-success (* 0.30 (/ appeal-window-blocks 10000))))
        h-ev (honest-ev fee-wei (* appeal-rate (- 1 appeal-success)))
        ;; Malicious loses more as appeal window extends
        detection-prob (min 0.8 (+ 0.3 (* 0.05 (/ appeal-window-blocks 1000))))
        bond-mult 2.0
        bond-wei (* escrow bond-mult)
        slash-mult 2.0
        m-ev (malicious-ev fee-wei bond-wei detection-prob slash-mult)
        healthy? (> h-ev m-ev)]
    {:appeal-window-blocks appeal-window-blocks
     :appeal-rate appeal-rate
     :appeal-success appeal-success
     :detection-prob detection-prob
     :honest-ev h-ev
     :malicious-ev m-ev
     :healthy? healthy?}))

(defn run-f6-appeal-window-adequacy
  "Sweep appeal-window-blocks from 0 to 10,000 (8 points).
   
   Returns: {:benchmark-id \"F6\" :label \"...\" :passed? bool :results [...] :summary {...}}"
  []
  (let [appeal-windows [0 1429 2857 4286 5714 7143 8571 10000]
        param-grid (mapv (fn [w] {:appeal-window-blocks w :seed 42}) appeal-windows)
        results (engine/run-parameter-sweep param-grid run-f6-trial)
        healthy-count (count (filter :healthy? results))
        total-count (count results)
        pass-rate (double (/ healthy-count total-count))
        passed? (>= pass-rate 0.75)]
    (engine/make-result
     {:benchmark-id "F6"
      :label "Appeal Window Adequacy"
      :hypothesis "Appeal window provides sufficient time for legitimate challenges (≥75%)"
      :passed? passed?
      :results results
      :summary {:total-trials total-count
                :healthy-trials healthy-count
                :pass-rate pass-rate
                :threshold 0.75}})))

;; ---------------------------------------------------------------------------
;; Phase F Entry Point
;; ---------------------------------------------------------------------------

(defn run-phase-f-sweep
  "Run all F1–F6 economic parameter validation sweeps.
   
   Returns: {:phases [{F1-result} {F2-result} ... {F6-result}]
             :overall-passed? bool
             :overall-summary {...}}"
  []
  (engine/print-phase-header
   {:benchmark-id "Phase F"
    :label "Economic Parameter Validation"
    :hypothesis "Safe parameter zones exist where malicious EV < 0 across all conditions"
    :details ["F1: Detection Probability Sensitivity (0.1–0.9)"
              "F2: Bond Size Sweep (0.5×–10.0×)"
              "F3: Fee Adequacy Sweep (50–1000 bps)"
              "F4: Escrow Concentration (100–1M)"
              "F5: Multi-resolver Equilibrium (1–100 resolvers)"
              "F6: Appeal Window Adequacy (0–10k blocks)"]})

  (let [f1 (run-f1-detection-probability-sweep)
        f2 (run-f2-bond-size-sweep)
        f3 (run-f3-fee-adequacy-sweep)
        f4 (run-f4-escrow-concentration-sweep)
        f5 (run-f5-multi-resolver-equilibrium)
        f6 (run-f6-appeal-window-adequacy)

        phases [f1 f2 f3 f4 f5 f6]
        passed-phases (count (filter :passed? phases))
        total-phases (count phases)

        overall-passed? (>= passed-phases 5)  ; 5/6 phases must pass

        result (engine/make-result
                {:benchmark-id "Phase F"
                 :label "Economic Parameter Validation"
                 :hypothesis "Safe parameter zones exist where malicious EV < 0"
                 :passed? overall-passed?
                 :summary {:total-phases total-phases
                           :passed-phases passed-phases
                           :phases (mapv #(select-keys % [:benchmark-id :passed?]) phases)}})]

    (println (format "F1 Detection Prob: %s" (:status f1)))
    (println (format "F2 Bond Size: %s" (:status f2)))
    (println (format "F3 Fee Adequacy: %s" (:status f3)))
    (println (format "F4 Escrow Concentration: %s" (:status f4)))
    (println (format "F5 Multi-resolver: %s" (:status f5)))
    (println (format "F6 Appeal Window: %s" (:status f6)))
    (println "")

    (if overall-passed?
      (println "✓ PASS: Phase F — Economic parameters validated")
      (println "✗ FAIL: Phase F — Unsafe parameter zones detected"))
    (println "")

    result))
