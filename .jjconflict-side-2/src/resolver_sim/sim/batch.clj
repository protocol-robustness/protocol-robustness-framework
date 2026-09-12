(ns resolver-sim.sim.batch
  "Batch runner: aggregate N trials into summary statistics."
  (:require [resolver-sim.stochastic.detection :as detection]
            [resolver-sim.stochastic.dispute :as dispute]
            [resolver-sim.stochastic.types :as types]
            [resolver-sim.protocols.sew.research-models.resolver-ring :as ring]
            [resolver-sim.sim.batch-integration :as integration]
            [resolver-sim.sim.common-kwargs :refer [common-kwargs]]))

(defn mean [vals]
  (if (empty? vals) 0 (double (/ (reduce + vals) (count vals)))))

(defn variance [vals mean-val]
  (if (<= (count vals) 1)
    0.0
    (double (/ (reduce + (map #(Math/pow (- % mean-val) 2) vals))
               (dec (count vals))))))

(defn std-dev [vals mean-val]
  (Math/sqrt (variance vals mean-val)))

(defn quantile
  "Linear-interpolation quantile. q in [0,1]; sorted-vals must be pre-sorted."
  [sorted-vals q]
  (if (empty? sorted-vals)
    0
    (let [idx (* q (dec (count sorted-vals)))]
      (if (integer? idx)
        (nth sorted-vals (int idx))
        (let [lo (int (Math/floor idx))
              hi (int (Math/ceil idx))
              frac (- idx lo)]
          (+ (* (- 1 frac) (nth sorted-vals lo))
             (* frac (nth sorted-vals hi))))))))

(defn build-aggregate
  "Compute aggregate stats from a vector of trial results.
   Single source used by both run-batch and run-batch-with-attribution."
  [results n-trials params]
  (let [profits-honest (map :profit-honest results)
        profits-malice (map :profit-malice results)
        mean-honest (mean profits-honest)
        mean-malice (mean profits-malice)
        sorted-honest (sort profits-honest)
        sorted-malice (sort profits-malice)

        l2-detected-count (count (filter :l2-detected? results))

        pending-slashed (count (filter :slashing-pending? results))
        pending-delay-weeks (if (empty? results) 0
                                (double (mean (map :slashing-delay-weeks (filter :slashing-pending? results)))))

        frozen-count (count (filter :frozen? results))
        escaped-count (count (filter :escaped? results))

        total-slashed (count (filter :slashed? results))
        timeout-slashed (count (filter #(= (:slashing-reason %) :timeout) results))
        reversal-slashed (count (filter #(= (:slashing-reason %) :reversal) results))
        fraud-slashed (count (filter #(= (:slashing-reason %) :fraud) results))

        appeal-count (count (filter :appeal-triggered? results))
        escalation-count (count (filter :escalated? results))]

    {:n-trials n-trials
     :strategy (or (:force-strategy params) (:strategy params :honest))
     :oracle-effective-mode (:mode (:oracle-effective params)
                                   (detection/normalize-oracle-fixture params))
     :oracle-fixture-exhausted? (boolean (some :oracle-fixture/exhausted? results))
     :oracle-fixture-warnings
     (vec (distinct (mapcat :oracle-fixture/warnings results)))
     :oracle-fixture-warning-errors
     (count (filter #(= :error (:level %))
                    (mapcat :oracle-fixture/warnings results)))

     :honest-mean (double mean-honest)
     :honest-std (double (std-dev profits-honest mean-honest))
     :honest-min (apply min profits-honest)
     :honest-max (apply max profits-honest)
     :honest-p25 (quantile sorted-honest 0.25)
     :honest-p50 (quantile sorted-honest 0.50)
     :honest-p75 (quantile sorted-honest 0.75)

     :malice-mean (double mean-malice)
     :malice-std (double (std-dev profits-malice mean-malice))
     :malice-min (apply min profits-malice)
     :malice-max (apply max profits-malice)
     :malice-p25 (quantile sorted-malice 0.25)
     :malice-p50 (quantile sorted-malice 0.50)
     :malice-p75 (quantile sorted-malice 0.75)

     :honest-wins (count (filter #(> % 0) (map - profits-honest profits-malice)))
     :dominance-ratio (if (zero? mean-malice) Double/POSITIVE_INFINITY
                          (double (/ mean-honest mean-malice)))

     :appeal-rate (double (/ appeal-count n-trials))
     :escalation-rate (double (/ escalation-count n-trials))

     :l2-detection-rate (double (/ l2-detected-count n-trials))

     :slash-rate (double (/ total-slashed n-trials))
     :timeout-slash-rate (double (/ timeout-slashed n-trials))
     :reversal-slash-rate (double (/ reversal-slashed n-trials))
     :fraud-slash-rate (double (/ fraud-slashed n-trials))

     :fraud-slashed-count fraud-slashed
     :reversal-slashed-count reversal-slashed
     :timeout-slashed-count timeout-slashed

     :frozen-rate (double (/ frozen-count n-trials))
     :escaped-rate (double (/ escaped-count n-trials))

     :adjusted-strategy (:adjusted-strategy params (or (:force-strategy params) (:strategy params :honest)))
     :bribery-enabled (boolean (and (:bribe-cost-ratio params)
                                    (:fraud-slash-bps params)
                                    (> (:fraud-slash-bps params) 0)))
     :bribery-cost (when (:bribe-cost-ratio params)
                     (integration/calculate-bribery-cost params))}))

(defn run-batch
  "Run N trials with given parameters and return aggregated stats with early-stopping."
  [rng n-trials params]
  (let [params        (merge types/default-params params)
        base-strategy (or (:force-strategy params) (:strategy params :honest))
        strategy      (integration/adjust-strategy-for-bribery base-strategy params)
        params        (assoc params :adjusted-strategy strategy)
        min-trials    (:min-trials params)
        pass-threshold 0.8]
    (loop [i 0
           results []
           passes 0]
      (if (or (= i n-trials)
              (and (>= i min-trials)
                   (or (>= (/ passes (max 1 i)) pass-threshold)
                       (<= (/ (- i passes) (max 1 i)) (- 1.0 pass-threshold)))))
        (let [final-results results
              agg (build-aggregate final-results i params)]
          (if (< i n-trials)
            (assoc agg :early-stop? true :trials-run i)
            agg))
        (let [res (apply dispute/resolve-dispute
                         rng (:escrow-size params)
                         (:resolver-fee-bps params)
                         (:appeal-bond-bps params)
                         (:slash-multiplier params)
                         strategy
                         (:appeal-probability-if-correct params)
                         (:appeal-probability-if-wrong params)
                         (:slashing-detection-probability params)
                         (common-kwargs params))]
          (recur (inc i)
                 (conj results res)
                 (if (:ok? res) (inc passes) passes)))))))

(defn run-batch-with-attribution
  "Run N trials and return both aggregate stats and per-trial results.

   Returns {:aggregate <same map as run-batch>
            :trials    [{per-trial result map} ...]}"
  [rng n-trials params]
  (let [params        (merge types/default-params params)
        base-strategy (or (:force-strategy params) (:strategy params :honest))
        strategy      (integration/adjust-strategy-for-bribery base-strategy params)
        params        (assoc params :adjusted-strategy strategy)
        trials
        (vec (repeatedly n-trials
                         #(apply dispute/resolve-dispute
                                 rng (:escrow-size params)
                                 (:resolver-fee-bps params)
                                 (:appeal-bond-bps params)
                                 (:slash-multiplier params)
                                 strategy
                                 (:appeal-probability-if-correct params)
                                 (:appeal-probability-if-wrong params)
                                 (:slashing-detection-probability params)
                                 (common-kwargs params))))]
    {:aggregate (build-aggregate trials n-trials params)
     :trials trials}))

(defn run-ring-batch
  "Run N trials of a resolver ring simulation and return aggregated stats.

   Phase F1: Multi-resolver collusion with waterfall slashing.

   Uses common-kwargs (shared keyword-arg vector) so oracle fixtures,
   :fixed-or, escalation assumptions, and bribery params are forwarded
   to ring/simulate-ring-dispute."
  [rng n-trials params ring-spec]
  (let [params        (merge types/default-params params)
        kw-args       (common-kwargs params)

        ;; Initialize the ring
        initial-ring (ring/create-ring ring-spec)

        ;; Run repeated disputes for the ring
        ring-results
        (reduce
         (fn [ring-state _trial]
           (let [dispute-result
                 (apply ring/simulate-ring-dispute
                        rng ring-state
                        (:escrow-size params)
                        (:resolver-fee-bps params)
                        (:appeal-bond-bps params)
                        (:slash-multiplier params)
                        (:appeal-probability-if-correct params)
                        (:appeal-probability-if-wrong params)
                        (:slashing-detection-probability params)
                        kw-args)]
             (:ring dispute-result)))
         initial-ring
         (range n-trials))

        ;; Extract profitability analysis
        profitability (ring/ring-profitability ring-results)

        ;; Individual resolver states
        member-states (:member-states profitability)
        senior-state (first (filter #(= (:tier %) :senior) member-states))
        junior-states (filter #(= (:tier %) :junior) member-states)]

    {:n-trials n-trials
     :ring-type (str (count junior-states) "-junior-ring")

     ;; Aggregate ring profitability
     :ring-total-profit (double (:total-profit profitability))
     :ring-avg-profit-per-dispute (double (:average-profit-per-dispute profitability))
     :ring-catch-rate (double (:catch-rate profitability))
     :ring-viable? (:viable? profitability)
     :ring-senior-exhausted? (:senior-exhausted? profitability)

     ;; Individual member status (scalars only for CSV compatibility)
     :senior-bond-remaining (double (:bond-remaining senior-state))
     :senior-slashed-amount (double (:slashed-amount senior-state))
     :juniors-count (count junior-states)
     :juniors-avg-bond-remaining (double
                                  (if (empty? junior-states) 0
                                      (/ (reduce + (map :bond-remaining junior-states)) (count junior-states))))
     :juniors-total-slashed (double
                             (reduce + (map :slashed-amount junior-states)))

     ;; Comparative threshold
     :ring-profitable? (:ring-profitable? profitability)
     :ring-solvent? (:ring-solvent? profitability)}))
