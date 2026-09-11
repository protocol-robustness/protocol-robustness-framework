(ns resolver-sim.sim.reputation
  "Per-resolver reputation tracking for multi-epoch simulations."
  (:require [resolver-sim.stochastic.rng :as rng]))

(defn initialize-resolvers
  "Create initial cohort of resolvers from strategy mix.

   start-id — first numeric suffix for resolver IDs (default 0).
              Pass a non-zero value when adding new entrants to avoid
              ID collisions with existing resolvers.

   IDs are deterministic: \"<strategy>-<N>\" where N is sequential from start-id.
   No gensym — same arguments always produce the same ID set."
  ([n-resolvers strategy-mix] (initialize-resolvers n-resolvers strategy-mix 0))
  ([n-resolvers strategy-mix start-id]
   (let [honest-count    (int (* n-resolvers (:honest strategy-mix 0)))
         lazy-count      (int (* n-resolvers (:lazy strategy-mix 0)))
         malicious-count (int (* n-resolvers (:malicious strategy-mix 0)))
         collusive-count (- n-resolvers honest-count lazy-count malicious-count)
         groups          [[:honest    honest-count]
                          [:lazy      lazy-count]
                          [:malicious malicious-count]
                          [:collusive collusive-count]]]
     (first
      (reduce
       (fn [[m next-id] [strategy cnt]]
         [(reduce (fn [acc i]
                    (let [id (str (name strategy) "-" (+ next-id i))]
                      (assoc acc id
                             {:resolver-id         id
                              :strategy            strategy
                              :status              :active
                              :total-profit        0.0
                              :total-fees-earned   0.0
                              :total-slashing-loss 0.0
                              :total-trials        0
                              :total-verdicts      0
                              :total-correct       0
                              :total-slashed       0
                              :total-appealed      0
                              :total-escalated     0
                              :exit-probability    0.0
                              :epoch-history       {}})))
                  m (range cnt))
          (+ next-id cnt)])
       [{} start-id]
       groups)))))

(defn update-resolver-history
  "Update resolver state after one epoch's attributed trials.

   profit             — net profit this epoch (sum of attributed trial profits)
   verdicts           — verdicts rendered this epoch
   correct            — correct verdicts this epoch (0 for strategic resolvers)
   slashed?           — true if at least one slash event occurred this epoch
   epoch              — epoch number (1-based)
   trials             — number of trials attributed to this resolver (may be 0)
   appealed           — number of appeal events
   escalated          — number of escalation events

   Returns: Updated resolver record."
  [resolver profit verdicts correct slashed? epoch
   & {:keys [trials appealed escalated] :or {trials 0 appealed 0 escalated 0}}]
  (let [old-history (:epoch-history resolver {})
        epoch-key   (keyword (str "epoch-" epoch))
        new-epoch   {:profit    profit
                     :trials    trials
                     :verdicts  verdicts
                     :correct   correct
                     :slashed?  slashed?
                     :appealed  appealed
                     :escalated escalated
                     :epoch     epoch}]
    (assoc resolver
           :total-profit        (+ (:total-profit resolver 0.0) profit)
           :total-fees-earned   (+ (:total-fees-earned resolver 0.0) (max 0.0 profit))
           :total-slashing-loss (+ (:total-slashing-loss resolver 0.0) (if slashed? (- (min 0.0 profit)) 0.0))
           :total-trials        (+ (:total-trials resolver 0) trials)
           :total-verdicts      (+ (:total-verdicts resolver 0) verdicts)
           :total-correct       (+ (:total-correct resolver 0) correct)
           :total-slashed       (+ (:total-slashed resolver 0) (if slashed? 1 0))
           :total-appealed      (+ (:total-appealed resolver 0) appealed)
           :total-escalated     (+ (:total-escalated resolver 0) escalated)
           :epoch-history       (assoc old-history epoch-key new-epoch))))

(defn calculate-exit-probability
  "Based on cumulative losses and slashing, probability resolver exits.

   Accounts for probationary period: resolvers re-entering after a slashed exit
   carry :probation-until-epoch. If the current epoch is within their probation
   window, a :probation-exit-multiplier (default 1.5) is applied to model the
   elevated scrutiny they face. This makes repeat attackers more likely to be
   detected and exit again quickly.

   Returns: Double between 0 and 1"
  [resolver epoch params]
  (let [total-loss    (:total-slashing-loss resolver 0.0)
        times-slashed (:total-slashed resolver 0)
        total-profit  (:total-profit resolver 0.0)
        strategy      (:strategy resolver)

        ; Base exit probability by strategy
        base-prob (case strategy
                    :honest    0.001   ; Honest almost never exit (profit positive)
                    :lazy      0.01    ; Lazy occasionally exit (lower profits)
                    :malicious 0.05    ; Malicious frequently exit (slashed & unprofitable)
                    :collusive 0.03    ; Collusive sometimes exit (detected)
                    0.01)

        ; Increase probability if slashed
        slashing-penalty    (if (> times-slashed 0) (* 0.02 times-slashed) 0.0)

        ; Decrease probability if profitable
        profitability-bonus (if (> total-profit 0) (- 0.01) 0.0)

        raw-prob (max 0.0 (min 1.0 (+ base-prob slashing-penalty profitability-bonus)))

        ; Probation multiplier: elevated exit pressure during re-entry window
        probation-until     (:probation-until-epoch resolver)
        probation-mult      (if (and probation-until (<= epoch probation-until))
                              (:probation-exit-multiplier params 1.5)
                              1.0)]

    (max 0.0 (min 1.0 (* raw-prob probation-mult)))))

(defn apply-epoch-decay
  "After each epoch, remove exited resolvers, add new ones to maintain population.

   Classifies each exit as either a :slashed-exit (resolver had ≥1 slash event)
   or a :natural-exit (profit-driven / base-rate). This distinction drives two
   behaviour differences for replacements:

   1. Identity cost — replacement resolvers filling slashed-exit slots start with
      a negative initial profit equal to (identity-cost-bps / 10000) * escrow-size.
      This models the on-chain registration stake or cooldown cost that the real
      protocol can impose on re-registering resolvers.

   2. Probationary period — replacements for slashed exits carry
      :probation-until-epoch = (epoch-num + probation-epochs). During this window
      calculate-exit-probability applies :probation-exit-multiplier, making these
      resolvers more likely to exit again (elevated scrutiny).

   New params consumed (all default to 0 / backward-compatible):
     :identity-cost-bps         — registration cost in bps of :escrow-size (default 0)
     :probation-epochs          — epochs of elevated scrutiny after slashed re-entry (default 0)
     :probation-exit-multiplier — exit-prob multiplier during probation (default 1.5)
     :slashed-replacement-mix   — strategy mix for slashed-exit replacements
                                  (defaults to :replacement-strategy-mix or standard mix)

   decay-rng  — seeded RNG for deterministic exit decisions.
   next-id    — starting numeric suffix for new entrant IDs (avoids ID collisions).

   Returns: {:histories updated-map :next-id N :slashed-exits N :natural-exits N}"
  [resolver-histories epoch-num params decay-rng next-id]
  (let [n-total        (count resolver-histories)
        identity-cost  (let [cost-bps   (:identity-cost-bps params 0)
                             escrow-sz  (:escrow-size params 10000)]
                         (* (/ cost-bps 10000.0) escrow-sz))
        probation-end  (+ epoch-num (:probation-epochs params 0))

        ;; Classify each resolver: keep active ones, tag exits as slashed or natural
        {active-resolvers true, exiting-resolvers false}
        (group-by (fn [[_id resolver]]
                    (let [exit-prob (calculate-exit-probability resolver epoch-num params)]
                      (> (rng/next-double decay-rng) exit-prob)))
                  resolver-histories)

        active-map     (into {} active-resolvers)
        slashed-exits  (count (filter (fn [[_id r]] (pos? (:total-slashed r 0))) exiting-resolvers))
        natural-exits  (count (filter (fn [[_id r]] (zero? (:total-slashed r 0))) exiting-resolvers))
        n-new          (- n-total (count active-map))

        ;; Strategy mix for replacements — slashed exits may use a different mix
        ;; (e.g., more honest entrants replacing deterred malicious resolvers)
        standard-mix   (or (:replacement-strategy-mix params)
                           {:honest 0.50 :lazy 0.25 :malicious 0.125 :collusive 0.125})
        slashed-mix    (or (:slashed-replacement-mix params) standard-mix)]

    (if (pos? n-new)
      (let [;; Spawn replacements: slashed-exit slots get identity-cost + probation
            ;; Natural-exit slots get standard treatment
            n-slashed-new (min n-new slashed-exits)
            n-natural-new (- n-new n-slashed-new)

            slashed-replacements
            (when (pos? n-slashed-new)
              (let [base (initialize-resolvers n-slashed-new slashed-mix next-id)]
                ;; Apply identity cost and probation to each slashed replacement
                (reduce-kv (fn [acc id r]
                             (assoc acc id
                                    (cond-> r
                                      (pos? identity-cost)
                                      (assoc :total-profit (- identity-cost))
                                      (pos? (:probation-epochs params 0))
                                      (assoc :probation-until-epoch probation-end))))
                           {} base)))

            natural-replacements
            (when (pos? n-natural-new)
              (initialize-resolvers n-natural-new standard-mix (+ next-id n-slashed-new)))

            all-replacements (merge slashed-replacements natural-replacements)]

        {:histories     (merge active-map all-replacements)
         :next-id       (+ next-id n-new)
         :slashed-exits slashed-exits
         :natural-exits natural-exits})

      {:histories     active-map
       :next-id       next-id
       :slashed-exits 0
       :natural-exits 0})))

(defn win-rate
  "Calculate per-resolver win rate.
   Returns: Double between 0 and 1"
  [resolver]
  (let [total (:total-verdicts resolver 0)
        correct (:total-correct resolver 0)]
    (if (> total 0)
      (double (/ correct total))
      0.0)))

(defn cumulative-profit
  "Get resolver's cumulative profit.
   Returns: Double"
  [resolver]
  (:total-profit resolver 0.0))

(defn resolver-status-summary
  "Get human-readable summary of resolver state.
   Returns: String"
  [resolver]
  (let [strategy (:strategy resolver)
        profit (cumulative-profit resolver)
        slashed (:total-slashed resolver 0)
        win-pct (* 100 (win-rate resolver))]
    (format "%s[%s] profit=%.0f, wins=%.0f%%, slashed=%d"
            (:resolver-id resolver) (name strategy) profit win-pct slashed)))

(defn apply-detection-decay
  "Apply detection decay for governance failure scenarios.
   If :detection-decay-rate is set, multiply detection by (1 - decay-rate) each epoch.
   If :detection-failure-epoch is set, drop detection to 0 at that epoch.
   Decays both detection probabilities and appeal-reversal quality.
   Returns: Updated params with decayed governance quality signals."
  [params epoch]
  (let [decay-rate (:detection-decay-rate params 0.0)
        failure-epoch (:detection-failure-epoch params nil)
        should-fail? (and failure-epoch (>= epoch failure-epoch))
        decay-multiplier (if should-fail?
                           0.0
                           (Math/pow (- 1.0 decay-rate) (dec epoch)))
        decayed-params
        (-> params
            (assoc :slashing-detection-probability
                   (* (:slashing-detection-probability params 0.1) decay-multiplier))
            (assoc :fraud-detection-probability
                   (* (:fraud-detection-probability params 0.25) decay-multiplier))
            (assoc :p-l1-reversal
                   (* (or (:p-l1-reversal params) 0.85) decay-multiplier))
            (assoc :p-l2-reversal
                   (* (or (:p-l2-reversal params) 0.95) decay-multiplier)))]
    decayed-params))