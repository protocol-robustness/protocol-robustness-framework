(ns resolver-sim.stochastic.economics
  "Payoff and economic functions.
   
   All functions are pure: no side effects, deterministic given inputs."
  (:require [resolver-sim.stochastic.rng :as rng]
            [resolver-sim.time.context :as time-ctx]))

(defn calculate-fee
  "Calculate resolver fee based on escrow and fee rate (bps).
   Uses integer division (quot) to match contract_model/types compute-fee.

   Example:
   (calculate-fee 1000 150) ; 1000 wei escrow, 1.5% = 15 wei"
  [escrow-wei fee-bps]
  (quot (* escrow-wei fee-bps) 10000))

(defn calculate-bond
  "Calculate appeal bond based on escrow and bond rate (bps).
   Uses integer division (quot) to match contract_model/types compute-fee.

   Example:
   (calculate-bond 1000 700) ; 1000 wei escrow, 7% = 70 wei"
  [escrow-wei bond-bps]
  (quot (* escrow-wei bond-bps) 10000))

(defn calculate-slashing-loss
  "Calculate slashing loss if resolver is caught and slashed.
   
   Loss = bond * slash_multiplier"
  [bond-wei slash-multiplier]
  (* bond-wei slash-multiplier))

(defn honest-expected-value
  "Expected value for honest resolver.
   
   EV = fee * (1 - appeal_rate)
        + fee * appeal_rate * (1 - appeal_won_rate)
   
   Simplified: assume honest loses appeals rarely."
  [fee-wei appeal-prob-if-correct]
  (let [appeal-loss-prob (- 1 appeal-prob-if-correct)
        ev (* fee-wei appeal-loss-prob)]
    (max 0 ev)))

(defn malicious-expected-value
  "Expected value for malicious resolver.

   EV = fee + fraud-upside * fraud-success-rate - slashing-loss * detection-probability

   fraud-upside is the escrow-diversion gain: escrow × (1 − fee-rate).
   When fraud-success-rate=0.0 (the default), this reduces to the original
   EV = fee - slashing-loss * detection-probability, keeping backward compatibility.

   IMPORTANT: This is protocol income only. It does not model the full economic
   gain to a colluding *party* who receives the misdirected escrow."
  ([fee-wei slashing-loss detection-prob]
   (malicious-expected-value fee-wei slashing-loss detection-prob 0 0.0))
  ([fee-wei slashing-loss detection-prob fraud-upside fraud-success-rate]
   (let [expected-fraud-gain (* fraud-upside fraud-success-rate)
         net-profit (+ fee-wei expected-fraud-gain (- (* slashing-loss detection-prob)))]
     net-profit)))

(defn lazy-expected-value
  "Expected value for lazy resolver.
   
   EV = fee * (correct_probability * (1 - appeal_prob_correct)
               + wrong_probability * (1 - appeal_prob_wrong))"
  [fee-wei correct-prob appeal-prob-correct appeal-prob-wrong]
  (let [correct-ev (* correct-prob (- 1 appeal-prob-correct))
        wrong-ev (* (- 1 correct-prob) (- 1 appeal-prob-wrong))
        total-survival-prob (+ correct-ev wrong-ev)
        ev (* fee-wei total-survival-prob)]
    (max 0 ev)))

(defn collusive-expected-value
  "Expected value for collusive resolver.

   EV = fee * colluder-gain-rate - fee * effective-detection

   colluder-gain-rate drives extra profit from coordinated wrong verdicts.
   Default 1.2 reproduces the original model's coordination bonus at coalition=1.
   Calibrated value from ring-attack trace: use ~1.15 (Phase AI escalation-trap).

   To use the original hard-coded formula: omit colluder-gain-rate (or pass nil).
   To calibrate from trace data: pass the measured gain multiplier directly."
  ([fee-wei coalition-size detection-prob-increased]
   (collusive-expected-value fee-wei coalition-size detection-prob-increased nil))
  ([fee-wei coalition-size detection-prob-increased colluder-gain-rate]
   (let [; colluder-gain-rate nil → fall back to the original log-based formula
         gain-rate (or colluder-gain-rate
                       (/ 1.2 (Math/log (+ 2 coalition-size))))
         effective-detection (min 0.5 detection-prob-increased)
         ev (- (* fee-wei gain-rate)
               (* fee-wei effective-detection))]
     (max 0 ev))))

(defn strategy-dominance-score
  "How much better is honest than malicious?

   score = ev_honest / ev_malicious

   score > 2.0 means honest is 2× better."
  [ev-honest ev-malicious]
  (if (zero? ev-malicious)
    (if (zero? ev-honest) 1.0 Double/POSITIVE_INFINITY)
    (/ ev-honest ev-malicious)))

(defn worst-case-fraud-success-rate
  "Worst-case fraud-success-rate: every undetected malicious resolver diverts funds.
   fraud-success-rate = 1 - detection-prob.

   This is the correct default for economic security analysis. It means:
   'if not caught, the malicious resolver captures the escrow.'
   Setting fraud-success-rate=0.0 (the original default) only models protocol income."
  [detection-prob]
  (max 0.0 (- 1.0 detection-prob)))

(def default-escalation-assumptions
  "Named parameter bands for escalation-model sensitivity analysis.

   These assumptions are used to model how likely an incorrect lower-layer
   dispute outcome is to be appealed, corrected, escalated, and ultimately
   reversed.

   Bands:

   :base
   Realistic mid-range estimates.

   :optimistic
   Strong deterrence and strong correction assumptions. Appeals are more
   likely, L1 reversal is more reliable, and Kleros escalation is highly
   effective.

   :pessimistic
   Weak deterrence and weak correction assumptions. Appeals are less likely,
   L1 reversal is less reliable, and escalation is less likely to fully
   correct fraud. Use this band for conservative or worst-case economic
   security analysis.

   :two-layer
   Disables the Kleros backstop. This represents the same L0/L1 protocol
   without L2 escalation. The L2 probability keys remain present so downstream
   stochastic code can consume the same shape across all bands.

   Probability semantics:

   :p-appeal-wrong
   Probability that an incorrect lower-layer outcome is appealed.

   :p-l1-reversal
   Probability that L1 reverses an incorrect lower-layer outcome,
   conditional on an appeal occurring.

   :p-l2-escalation
   Probability that an unresolved or still-contested L1 outcome escalates
   to L2, conditional on the Kleros backstop being enabled.

   :p-l2-reversal
   Probability that L2 reverses an incorrect lower-layer outcome,
   conditional on escalation occurring.

   :has-kleros?
   Whether the L2 Kleros fallback is available for this parameter band."
  {:base        {:p-appeal-wrong 0.40  :p-l1-reversal 0.85  :has-kleros? true  :p-l2-escalation 0.70  :p-l2-reversal 0.95}
   :optimistic  {:p-appeal-wrong 0.60  :p-l1-reversal 0.95  :has-kleros? true  :p-l2-escalation 0.90  :p-l2-reversal 0.99}
   :pessimistic {:p-appeal-wrong 0.20  :p-l1-reversal 0.60  :has-kleros? true  :p-l2-escalation 0.40  :p-l2-reversal 0.80}
   :two-layer   {:p-appeal-wrong 0.40  :p-l1-reversal 0.85  :has-kleros? false :p-l2-escalation 0.0   :p-l2-reversal 0.0}})

(defn effective-correction-probability
  "Returns the probability that an incorrect lower-layer outcome is corrected
   through appeal and, if enabled, L2 escalation.

   DEPRECATED: Use `correction-probability` (derived from canonical
   `escalation-survival-probability`) instead.

   Formula:
   appeal * (L1 reversal + L1 failure * L2 escalation * L2 reversal)"
  [{:keys [p-appeal-wrong
           p-l1-reversal
           has-kleros?
           p-l2-escalation
           p-l2-reversal]}]
  (let [l2-correction (if has-kleros?
                        (* (- 1.0 p-l1-reversal)
                           p-l2-escalation
                           p-l2-reversal)
                        0.0)]
    (* p-appeal-wrong
       (+ p-l1-reversal l2-correction))))

(defn fraud-survival-probability
  "Probability that a malicious L0 verdict survives all active escalation tiers.

   DEPRECATED: Use `escalation-survival-probability` (canonical recursive
   formulation) instead. This function is preserved for backward compatibility.

   Three-layer path (has-kleros? true):
     P = P(no appeal)
       + P(appeal) × P(L1 fails to reverse)
         × [P(no L2 escalation) + P(L2 escalation) × P(L2 fails to reverse)]

   Two-layer path (has-kleros? false, no Kleros backstop):
     P = P(no appeal) + P(appeal) × P(L1 fails to reverse)

   Parameters (map):
     :p-appeal-wrong    P(aggrieved party appeals) — defaults from ~{:base} band
     :p-l1-reversal     P(L1 overturns corrupt verdict | appealed)
     :has-kleros?       whether L2/Kleros backstop is active
     :p-l2-escalation   P(party escalates to L2 | L1 upholds corrupt)
     :p-l2-reversal     P(L2 overturns | escalated)

   Missing keys default to ~default-escalation-assumptions :base band.
   Inputs are clamped to [0,1]."
  [params]
  (let [base          (:base default-escalation-assumptions)
        p-appeal-wrong  (get params :p-appeal-wrong  (:p-appeal-wrong base))
        p-l1-reversal   (get params :p-l1-reversal   (:p-l1-reversal base))
        has-kleros?     (get params :has-kleros?     (:has-kleros? base))
        p-l2-escalation (get params :p-l2-escalation (:p-l2-escalation base))
        p-l2-reversal   (get params :p-l2-reversal   (:p-l2-reversal base))
        clamp         (fn [x] (-> x double (max 0.0) (min 1.0)))
        p-appeal      (clamp p-appeal-wrong)
        p-l1-fail     (- 1.0 (clamp p-l1-reversal))
        p-l2-escalate (clamp p-l2-escalation)
        p-l2-fail     (- 1.0 (clamp p-l2-reversal))
        after-appeal  (* p-appeal p-l1-fail)]
    (if has-kleros?
      (+ (- 1.0 p-appeal)
         (* after-appeal (+ (- 1.0 p-l2-escalate) (* p-l2-escalate p-l2-fail))))
      (+ (- 1.0 p-appeal) after-appeal))))

(defn sequential-fraud-success-prob
  "Analytical probability that a corrupt outcome survives all escalation tiers.

   Use this to understand parameter sensitivity without running MC trials.
   The simulation uses stochastic draws (in resolve-dispute); this function
   computes the closed-form expectation for comparison and documentation.

   Three-layer path (with Kleros):
     P = P(no appeal)
       + P(appeal) × P(L1 upholds)
         × [P(no L2 escalation) + P(L2 escalation) × P(L2 upholds)]

   Two-layer path (no Kleros, has-kleros?=false):
     P = P(no appeal) + P(appeal) × P(L1 upholds)

   Parameters (map):
     :appeal-prob-wrong    P(aggrieved party appeals at all) — defaults from ~{:base} band
     :p-l1-reversal        P(senior resolver overturns corrupt verdict | appealed)
     :has-kleros?          whether L2/Kleros backstop exists
     :p-l2-escalation      P(party escalates to L2 | L1 upheld corrupt)
     :p-l2-reversal        P(Kleros overturns | escalated to L2)

   Missing keys default to ~default-escalation-assumptions :base band.
   Returns the probability [0,1] that fraud reaches final settlement as-is."
  [params]
  (let [base             (:base default-escalation-assumptions)
        appeal-prob-wrong (get params :appeal-prob-wrong (:p-appeal-wrong base))
        p-l1-reversal     (get params :p-l1-reversal    (:p-l1-reversal base))
        has-kleros?       (get params :has-kleros?      (:has-kleros? base))
        p-l2-escalation   (get params :p-l2-escalation  (:p-l2-escalation base))
        p-l2-reversal     (get params :p-l2-reversal    (:p-l2-reversal base))
        p-no-appeal  (- 1.0 appeal-prob-wrong)
        p-l1-upholds (- 1.0 p-l1-reversal)
        p-after-l1   (* appeal-prob-wrong p-l1-upholds)]
    (if has-kleros?
      (let [p-no-l2     (- 1.0 p-l2-escalation)
            p-l2-upholds (- 1.0 p-l2-reversal)]
        (+ p-no-appeal (* p-after-l1 (+ p-no-l2 (* p-l2-escalation p-l2-upholds)))))
      (+ p-no-appeal p-after-l1))))

;; ---------------------------------------------------------------------------
;; Unified Escalation Probability Tree
;; ---------------------------------------------------------------------------

(defn- clamp-prob
  "Clamp a value to [0.0, 1.0]."
  [x]
  (-> x double (max 0.0) (min 1.0)))

(defn escalation-survival-probability
  "Canonical recursive escalation survival probability.

   Models the probability that an incorrect lower-layer outcome survives
   through all active escalation tiers. The recursion is:

     survival(tier k) =
       P(no challenge at k)
       + P(challenge at k)
         × P(tier k fails to correct)
         × survival(tier k+1)

   For the standard three-tier model (L0 → L1 → Kleros/L2):

     survival(L0) =
       (1 - p-appeal-wrong)
       + p-appeal-wrong
         × (1 - p-l1-reversal)
         × survival(L1)

     survival(L1) =
       (1 - p-l2-escalation)
       + p-l2-escalation
         × (1 - p-l2-reversal)
         × 1.0    (terminal tier — fraud survives if L2 fails)

   Parameters (map):
     :p-appeal-wrong    P(aggrieved party appeals an incorrect outcome)
     :p-l1-reversal     P(L1 reverses the incorrect outcome | appealed)
     :has-kleros?       whether L2/Kleros backstop is active
     :p-l2-escalation   P(party escalates to L2 | L1 failed to correct)
     :p-l2-reversal     P(Kleros/L2 reverses | escalated)

   Missing keys default to the :base band of default-escalation-assumptions.
   All probability inputs are clamped to [0.0, 1.0]."
  [params]
  (let [base (:base default-escalation-assumptions)
        p-appeal   (clamp-prob (get params :p-appeal-wrong  (:p-appeal-wrong base)))
        p-l1-rev   (clamp-prob (get params :p-l1-reversal   (:p-l1-reversal base)))
        has-l2?    (boolean (get params :has-kleros?        (:has-kleros? base)))
        p-l2-esc   (clamp-prob (get params :p-l2-escalation (:p-l2-escalation base)))
        p-l2-rev   (clamp-prob (get params :p-l2-reversal   (:p-l2-reversal base)))
        ;; Terminal tier survival
        survive-l2 (if has-l2?
                     (+ (- 1.0 p-l2-esc)
                        (* p-l2-esc (- 1.0 p-l2-rev)))
                     1.0)
        ;; L1 survival
        survive-l1 (+ (- 1.0 p-appeal)
                      (* p-appeal (- 1.0 p-l1-rev) survive-l2))]
    survive-l1))

(defn correction-probability
  "Probability that an incorrect lower-layer outcome IS corrected.
   Complementary to escalation-survival-probability: correction = 1 - survival."
  [params]
  (- 1.0 (escalation-survival-probability params)))

(defn verify-escalation-identities
  "Verify that the survival and correction identities hold for a set of
   parameter vectors. Returns a vector of result maps with :pass? and :delta."
  [& param-sets]
  (mapv (fn [params]
          (let [survival  (escalation-survival-probability params)
                correction (correction-probability params)
                sum (+ survival correction)
                pass? (< (Math/abs (- sum 1.0)) 1e-10)]
            {:params    (select-keys params [:p-appeal-wrong :p-l1-reversal])
             :survival  survival
             :correction correction
             :sum       sum
             :pass?     pass?
             :delta     (- sum 1.0)}))
        (or (seq param-sets) [{}])))

;; ---------------------------------------------------------------------------
;; Coalition payoff aggregation
;; ---------------------------------------------------------------------------

(defn coalition-aggregate-payoff
  "Compute total coalition utility and marginal contribution for a coalition
   within a resolver population.

   `resolver-payoffs` — sequence of {:resolver-id kw, :strategy kw, :net-payoff
                        long, :coalition-id kw | nil}
   `coalition-id` — the coalition to evaluate
   `outside-option` — per-resolver outside-option utility (default 0)
   `coordination-cost-fn` — function of coalition-size returning total
                            coordination cost (default (constantly 0))
   `definition-root` — optional content root binding the exact
   outside-option definition used, for re-verification.

   Returns {:coalition-id kw
            :member-count n
            :total-payoff long
            :per-member-average double
            :outside-option-total long
            :coalition-surplus long
            :marginal-contributions [{:resolver-id kw :contribution long} ...]
            :side-payment-feasible? bool
            :feasible-side-payments {:min long :max long}}"
  [resolver-payoffs coalition-id
   & {:keys [outside-option coordination-cost-fn definition-root]
      :or {outside-option 0
           coordination-cost-fn (constantly 0)}}]
  (let [members     (filter #(= (:coalition-id %) coalition-id) resolver-payoffs)
        member-ids  (set (map :resolver-id members))
        non-members (remove #(member-ids (:resolver-id %)) resolver-payoffs)
        member-payoffs    (map :net-payoff members)
        total-payoff      (reduce + 0 member-payoffs)
        member-count      (count members)
        coord-cost        (coordination-cost-fn member-count)
        coalition-net     (- total-payoff coord-cost)
        outside-total     (* member-count outside-option)
        surplus           (- coalition-net outside-total)
        ;; Aggregate stats for non-members (total comparison baseline)
        non-member-payoffs (map :net-payoff non-members)
        total-non-member   (reduce + 0 non-member-payoffs)
        total-all          (+ total-payoff total-non-member)
        ;; Marginal contribution = what the coalition adds to total
        ;; compared to the next-best alternative (members at outside option)
        marginal-all       (- total-all (+ (* member-count outside-option) total-non-member))
        ;; Per-resolver marginal contributions (approximate — assumes
        ;; average surplus if individual contributions are not tracked)
        per-member-marginal (if (pos? member-count)
                              (mapv (fn [m]
                                      {:resolver-id (:resolver-id m)
                                       :contribution (long (/ marginal-all member-count))})
                                    members)
                              [])
        ;; Side-payment feasibility: if total surplus > 0, side payments
        ;; can redistribute to make every member at least as well off as
        ;; outside option
        side-pay-feasible? (pos? coalition-net)]
    {:coalition-id           coalition-id
     :member-count           member-count
     :total-payoff           total-payoff
     :coordination-cost      coord-cost
     :coalition-net          coalition-net
     :per-member-average     (if (pos? member-count)
                               (double (/ coalition-net member-count))
                               0.0)
     :outside-option-total   outside-total
     :coalition-surplus      surplus
     :marginal-contributions per-member-marginal
     :side-payment-feasible? side-pay-feasible?
     :feasible-side-payments (if side-pay-feasible?
                               (let [min-pay 0
                                     max-pay coalition-net]
                                 {:min min-pay :max max-pay})
                               {:min 0 :max 0})
     :definition-root        definition-root}))

;; ---------------------------------------------------------------------------
;; Endogenous appeal participation
;; ---------------------------------------------------------------------------

(defn appeal-participation-constraint
  "Determine whether a rational agent appeals an adverse outcome.

   An agent appeals when the expected benefit exceeds the cost:
     P(reversal) × recovery-amount > appeal-cost

   Parameters:
     `recovery-amount` — value the agent can recover if the appeal succeeds
     `p-reversal` — probability the appeal reverses the outcome [0..1]
     `appeal-cost` — total cost of filing and pursuing the appeal
     `epsilon` — minimum surplus required to trigger appeal (default 0,
                 use positive values for risk-aversion or opportunity cost)

   Returns {:should-appeal? bool
            :expected-benefit double
            :net-benefit double
            :breakdown {:recovery-amount long, :p-reversal double,
                        :appeal-cost long}}"
  [recovery-amount p-reversal appeal-cost & {:keys [epsilon] :or {epsilon 0}}]
  (let [expected-benefit (* (double recovery-amount) (double p-reversal))
        net-benefit (- expected-benefit (double appeal-cost))]
    {:should-appeal? (> net-benefit (double epsilon))
     :expected-benefit expected-benefit
     :net-benefit net-benefit
     :breakdown {:recovery-amount (long recovery-amount)
                 :p-reversal (double p-reversal)
                 :appeal-cost (long appeal-cost)}}))

(defn derive-appeal-probability
  "Derive the probability `p-appeal-wrong` from economic parameters,
   assuming agents appeal when the participation constraint is met.

   When the expected benefit of appeal exceeds the cost for the
   declared agent population, `p-appeal-wrong` approaches 1.0 (all
   affected agents appeal).  When it does not, it approaches 0.0.

   Parameters:
     `recovery-amount` — typical value at stake
     `p-l1-reversal` — probability L1 reverses an incorrect outcome
     `appeal-cost` — typical appeal cost
     `agent-rational-fraction` — fraction of agents that are economically
                                  rational (default 1.0)

   Returns {:p-appeal-wrong double
            :rational? bool
            :participation-constraint map}
   where `:p-appeal-wrong` is either 1.0 (if constraint met) or the
   configured base rate (if not met)."
  [recovery-amount p-l1-reversal appeal-cost
   & {:keys [agent-rational-fraction base-rate]
      :or {agent-rational-fraction 1.0
           base-rate (:p-appeal-wrong (:base default-escalation-assumptions))}}]
  (let [constraint (appeal-participation-constraint
                    recovery-amount p-l1-reversal appeal-cost)
        rational? (:should-appeal? constraint)]
    {:p-appeal-wrong (if rational?
                       (min 1.0 (* agent-rational-fraction 1.0))
                       (* (- 1.0 agent-rational-fraction) base-rate))
     :rational? rational?
     :participation-constraint constraint}))

;; ---------------------------------------------------------------------------
;; Backward-compatible wrappers (deprecated — use escalation-survival-probability)
;; ---------------------------------------------------------------------------

(defn breakeven-detection
  "Minimum detection probability for honest EV to exceed full malicious EV.

   Derivation (worst-case model: fraud-success-rate = 1 - detection-prob):
     malice-EV = fee + (1-d) × (escrow - fee) - d × bond-loss
     honest-EV ≈ fee

   Setting honest-EV = malice-EV and solving for d:
     d = (escrow - fee) / (bond-loss + escrow - fee)

   If current detection-prob < breakeven-detection, bond deterrence alone is insufficient.
   The protocol's economic security relies on the state machine constraining
   fraud-success-rate (via invariants: funds-conservation, no-double-release)."
  [escrow-wei fee-wei bond-loss]
  (let [escrow-net (- escrow-wei fee-wei)]
    (double (/ escrow-net (+ bond-loss escrow-net)))))

;; ---------------------------------------------------------------------------
;; Yield Modeling
;; ---------------------------------------------------------------------------

(defn calculate-accrued-yield
  "Calculate accrued yield based on principal, annual rate (bps), and duration (seconds).
   
   yield = (principal * rate-bps * duration) / (10000 * seconds-per-year)"
  [principal-wei rate-bps duration-seconds]
  (let [num (* (bigint principal-wei) (bigint rate-bps) (bigint duration-seconds))
        den (* 10000 (bigint time-ctx/seconds-per-year))]
    (long (quot num den))))

(defn random-aave-rate
  "Simulate a volatile Aave-style yield rate (bps) for a token.
   Base rate with random walk noise."
  [rng base-rate-bps volatility]
  (let [noise (* volatility (- (rng/next-double rng) 0.5))]
    (max 0 (int (+ base-rate-bps noise)))))
