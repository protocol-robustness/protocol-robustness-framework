(ns resolver-sim.sim.batch-integration
  "Integration layer between Adversary protocol and batch runner.
   
   This module adapts Phase P-R adversary models to the standard batch simulation.
   It calculates bribery costs and adjusts attack success probabilities based on
   economic feasibility of different attack types.")

;; ============ BRIBERY COST INTEGRATION ============

(defn calculate-bribery-cost
  "Calculate realistic bribery cost given simulation parameters.
   
   This implements Phase P: attacks only succeed if bribery is affordable
   and beneficial. Returns cost in wei.
   
   Parameters:
   - escrow-size: Total dispute value (wei)
   - detection-prob: P(fraud detected) ∈ [0, 1]
   - bribe-cost-ratio: Margin multiplier (1.3 = 30% markup, default from Kleros)
   - panel-size: Number of jurors to control (default 3, need 2/3 majority)
   
   Model:
   - Attacker benefit ≈ escrow-size if all jurors corrupted
   - Per-juror cost = detection-prob × escrow-size × margin
   - Total cost = per-juror cost × (ceil(panel-size * 2/3))"

  [{:keys [escrow-size detection-prob bribe-cost-ratio panel-size majority-ratio]
    :or {panel-size 3 majority-ratio (/ 2.0 3.0) bribe-cost-ratio 1.3 detection-prob 0.0}}]

  (let [;; Ensure detection-prob is a valid number
        det-prob (double (or detection-prob 0.0))

        ;; Attacker's potential benefit if attack succeeds
        attack-benefit (long escrow-size)

        ;; Jurors needed to control outcome (majority-ratio threshold)
        juror-majority (double (or majority-ratio (/ 2.0 3.0)))
        jurors-needed (long (Math/ceil (* panel-size juror-majority)))

        ;; Cost per juror (from contingent bribery model)
        ;; Formula: cost = benefit × (margin - 1.0) × detection-prob
        cost-per-juror (long (Math/ceil
                              (* attack-benefit
                                 (- bribe-cost-ratio 1.0)
                                 det-prob)))

        ;; Total bribery cost
        total-cost (* cost-per-juror jurors-needed)]

    total-cost))

(defn should-bribe?
  "Decide whether attacker should attempt bribery attack.
   
   Returns true if:
   1. Bribery is economically rational (cost < benefit)
   2. Attacker has sufficient budget
   3. Detection probability is low enough to make it worthwhile
   
   Parameters:
   - params: dispatch parameters with bribery config
   - escrow-size: Dispute value (wei)
   
   This is the Phase P decision rule."

  [{:keys [escrow-size detection-prob attacker-budget bribe-cost-ratio
           fraud-detection-probability] :as params}]

  ;; Use fraud-detection-probability if available (Phase I), else default
  (let [det-prob (or fraud-detection-probability detection-prob 0.0)
        bribe-cost (calculate-bribery-cost
                    (assoc params :detection-prob det-prob))]

    (and
      ;; 1. Cost is less than expected benefit (bribery is profitable)
     (< bribe-cost escrow-size)

      ;; 2. Attacker has budget (if tracking budgets)
     (or (nil? attacker-budget)
         (>= attacker-budget bribe-cost))

      ;; 3. Not too expensive relative to budget
     (< det-prob 0.8))))  ; If detection > 80%, don't bother

(defn adjust-strategy-for-bribery
  "Adjust attack success probability based on bribery cost.
   
   In Phase H (current baseline): Strategy determines outcome.
   In Phase P: If bribery is affordable, apply it. If not, attack fails.
   
   Returns:
   - :malicious if bribery succeeds (attacker can afford it)
   - :honest if bribery fails (too expensive or unprofitable)
   
   This preserves backward compatibility: if no bribery config, returns
   the original strategy unchanged."

  [original-strategy {:keys [escrow-size detection-prob bribe-cost-ratio
                             fraud-detection-probability fraud-slash-bps] :as params}]

  ;; Only apply Phase P logic if:
  ;; 1. We're in a malicious strategy scenario
  ;; 2. Bribery is configured (has fraud detection + slash multiplier)
  ;; 3. We have bribe-cost-ratio parameter

  (if (and (= original-strategy :malicious)
           (and fraud-slash-bps (> fraud-slash-bps 0))
           bribe-cost-ratio)

    ;; Phase P: Check if bribery is feasible
    (if (should-bribe? params)
      :malicious  ; Keep as malicious (bribery succeeds)
      :honest)    ; Fall back to honest (bribery too expensive)

    ;; Not in Phase P: return original
    original-strategy))

;; ============ BATCH INTEGRATION HELPERS ============

(defn wrap-bribery-cost-analysis
  "Wrap batch results with Phase P bribery cost metrics.
   
   Adds to the batch result map:
   - :bribery-cost: Wei required for successful attack
   - :bribery-affordable?: Whether attacker can pay it
   - :bribery-profitable?: Whether attack still profitable after cost
   
   This allows post-hoc analysis of Phase P effectiveness."

  [batch-result params]

  (if (and (:bribe-cost-ratio params) (:fraud-slash-bps params))
    (let [cost (calculate-bribery-cost (assoc params :detection-prob
                                              (or (:fraud-detection-probability params) 0.0)))
          escrow (:escrow-size params 10000)]

      (assoc batch-result
             :bribery-cost cost
             :bribery-cost-ratio (/ (double cost) (double escrow))
             :bribery-affordable? (if-let [budget (:attacker-budget params)]
                                    (>= budget cost)
                                    true)))

    ;; Not a Phase P scenario
    batch-result))

(defn format-bribery-params
  "Format Phase P parameters for display/logging.
   
   Useful for debugging parameter sweeps."

  [{:keys [bribe-cost-ratio fraud-detection-probability fraud-slash-bps escrow-size]}]

  (when (and bribe-cost-ratio fraud-detection-probability)
    (let [cost (calculate-bribery-cost
                {:escrow-size escrow-size
                 :detection-prob fraud-detection-probability
                 :bribe-cost-ratio bribe-cost-ratio})]

      (format "Phase P: bribe-ratio=%.2f det=%.1f%% cost=%d wei (%.1f%% of escrow)"
              bribe-cost-ratio
              (* fraud-detection-probability 100)
              cost
              (* 100 (/ (double cost) (double escrow-size)))))))