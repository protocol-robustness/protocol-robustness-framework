(ns resolver-sim.governance.rules
  "Governance rules and dispute resolution parameters.

   Governance is responsible for:
   1. Defining resolution rules (appeal constraints, slashing parameters)
   2. Applying governance delays (time to change rules)
   3. Enforcing rule constraints (bounds on parameters)

   Phases:
   - Phase H: Fixed rules, no changes (governance-delay = infinity)
   - Phase T (planned): Rule drift, timing attacks, governance capture")

(defprotocol GovernanceRules
  "Dispute resolution rules and governance mechanics."

  (can-rule-change?
    [this epoch current-rules params]
    "Determine if rules can be changed at this epoch.
     Returns: true | false")

  (apply-rule-change
    [this old-rules new-rules params]
    "Apply governance rule change with appropriate delay.
     Returns: {:rules new-rules :delay-epochs int}")

  (governance-delay
    [this params]
    "Get governance response delay in epochs.
     Returns: int (0 = immediate, infinity = never)"))

;; ============ FIXED RULES (Phase H baseline) ============

(defn fixed-rules
  "Create a FixedRules instance — no rule changes allowed.

   Used in Phase H (baseline) and throughout Phases P-R."
  []
  (reify GovernanceRules
    (can-rule-change? [_ epoch rules params]
      false)
    (apply-rule-change [_ old-rules new-rules params]
      {:rules old-rules :delay-epochs 0})
    (governance-delay [_ params]
      Integer/MAX_VALUE)))

;; ============ ADAPTIVE RULES (Phase T - future) ============

;; ============ HELPER FUNCTIONS ============

(defn default-rules
  "Create default governance rules for baseline.

   Parameters:
   - escrow-size: wei amount in dispute

   Returns:
   {:appeal-bond-bps int
    :slash-multiplier float
    :resolver-fee-bps int
    :panel-size int
    :majority-ratio float
    :appeal-threshold float
    :appeal-probability-if-correct float
    :appeal-probability-if-wrong float}"
  [escrow-size]

  {:escrow-size escrow-size
   :appeal-bond-bps 500       ;; 5% of escrow
   :slash-multiplier 2.5      ;; 2.5× slash for detected fraud
   :resolver-fee-bps 100      ;; 1% of escrow as base reward
   :panel-size 3              ;; jurors per dispute panel
   :majority-ratio (/ 2.0 3.0) ;; 2/3 majority needed
   :appeal-threshold 0.6      ;; accuracy threshold for appeal
   :appeal-probability-if-correct 0.20
   :appeal-probability-if-wrong 0.40
   :slashing-detection-probability 0.10})

(defn apply-governance-delay
  "Apply delay before rule change takes effect.

   In real governance, rule changes:
   1. Are proposed in epoch N
   2. Go through governance delay (e.g., N+30)
   3. Take effect starting epoch N+30+1

   Parameters:
   - epoch-proposed: when change was proposed
   - delay-epochs: how long to wait
   - current-epoch: what epoch we're in now

   Returns: true if change is active, false if still pending"
  [epoch-proposed delay-epochs current-epoch]

  (>= current-epoch (+ epoch-proposed delay-epochs)))

(defn validate-rule-change
  "Validate proposed rule change is within bounds.

   Prevents extreme parameter changes that might break system:
   - slash-multiplier: [1.0, 5.0]
   - appeal-bond-bps: [100, 2000] (1%-20%)
   - fee-bps: [50, 500] (0.5%-5%)

   Returns: {:valid? bool :errors [str]}
   "
  [old-rules new-rules]

  (let [errors (atom [])]

    ;; Check slash multiplier bounds
    (let [slash-mult (:slash-multiplier new-rules)]
      (when (or (< slash-mult 1.0) (> slash-mult 5.0))
        (swap! errors conj "Slash multiplier must be in [1.0, 5.0]")))

    ;; Check appeal bond bounds
    (let [bond-bps (:appeal-bond-bps new-rules)]
      (when (or (< bond-bps 100) (> bond-bps 2000))
        (swap! errors conj "Appeal bond must be in [100, 2000] bps")))

    ;; Check fee bounds
    (let [fee-bps (:resolver-fee-bps new-rules)]
      (when (or (< fee-bps 50) (> fee-bps 500))
        (swap! errors conj "Resolver fee must be in [50, 500] bps")))

    ;; Check panel size bounds
    (let [panel-size (:panel-size new-rules)]
      (when (or (< panel-size 1) (> panel-size 21))
        (swap! errors conj "Panel size must be in [1, 21]")))

    ;; Check majority ratio bounds
    (let [maj-ratio (:majority-ratio new-rules)]
      (when (or (< maj-ratio 0.5) (> maj-ratio 1.0))
        (swap! errors conj "Majority ratio must be in [0.5, 1.0]")))

    ;; Check appeal threshold bounds
    (let [appeal-thresh (:appeal-threshold new-rules)]
      (when (or (< appeal-thresh 0.0) (> appeal-thresh 1.0))
        (swap! errors conj "Appeal threshold must be in [0.0, 1.0]")))

    {:valid? (empty? @errors)
     :errors @errors}))
