(ns resolver-sim.sim.shared-batch
  "Shared-world multi-agent batch runner.

   Unlike batch/run-batch-with-attribution which runs two independent sets of
   n-trials (one per strategy), this namespace generates PAIRED dispute outcomes:
   each dispute is run TWICE — once with honest strategy and once with malicious
   strategy — using independent RNG streams split from the same parent.

   This models a shared dispute pool where honest and malicious resolvers compete
   over the same underlying disputes rather than independent draws. The updated
   run-single-epoch with :use-shared-world? true splits the paired trial pool
   between honest and strategic resolver groups so total disputes = n-trials
   (not 2×n-trials as in the independent-batch model).

   Conservation invariant (checked in route-epoch downstream):
     (sum :profit-honest honest-slice) + (sum :profit-malice strategic-slice)
       == total profit attributed across all resolvers"
  (:require [resolver-sim.stochastic.detection :as detection]
            [resolver-sim.stochastic.dispute :as dispute]
            [resolver-sim.stochastic.rng     :as rng]
            [resolver-sim.sim.batch-integration :as integration]
            [resolver-sim.sim.common-kwargs  :refer [common-kwargs]]))

;; ---------------------------------------------------------------------------
;; Statistics helpers (subset of batch.clj; kept local to avoid circular deps)
;; ---------------------------------------------------------------------------

(defn- mean [xs]
  (if (empty? xs) 0.0 (double (/ (reduce + xs) (count xs)))))

(defn- std-dev [xs m]
  (if (<= (count xs) 1)
    0.0
    (Math/sqrt (double (/ (reduce + (map #(Math/pow (- % m) 2) xs))
                          (count xs))))))

(defn- quantile [sorted-xs q]
  (if (empty? sorted-xs)
    0
    (let [n   (count sorted-xs)
          idx (* q (dec n))
          lo  (int (Math/floor idx))
          hi  (min (dec n) (int (Math/ceil idx)))
          f   (- idx lo)]
      (+ (* (- 1.0 f) (nth sorted-xs lo))
         (* f (nth sorted-xs hi))))))

;; ---------------------------------------------------------------------------
;; Paired trial generation
;; ---------------------------------------------------------------------------

(defn run-paired-trial
  "Run one dispute with BOTH honest and malicious strategies on independent
   RNG streams split from the parent rng.

   Returns a merged trial map suitable for route-epoch:
     {:profit-honest     — honest resolver's profit on this dispute
      :profit-malice     — malicious resolver's profit on this dispute
      :slashed?          — whether malicious resolver was slashed (malicious run)
      :slashing-reason   — slashing cause (malicious run)
      :dispute-correct?  — honest resolver always correct (true)
      :appeal-triggered? — whether an appeal occurred (malicious run)
      :escalated?        — whether dispute escalated to L2 (malicious run)
      ...}"
  [rng params]
  (let [[rng-h rng-m] (rng/split-rng rng)
        escrow        (:escrow-size params 10000)
        fee-bps       (:resolver-fee-bps params)
        bond-bps      (:appeal-bond-bps params)
        slash-mult    (:slash-multiplier params)
        apc           (:appeal-probability-if-correct params)
        apw           (:appeal-probability-if-wrong params)
        det-prob      (:slashing-detection-probability params)
        kwargs        (common-kwargs params)

        ;; Honest run: models the honest resolver's outcome for this dispute.
        ;; Returns profit-honest (and a counterfactual profit-malice, unused here).
        h (apply dispute/resolve-dispute
                 rng-h escrow fee-bps bond-bps slash-mult :honest
                 apc apw det-prob kwargs)

        ;; Malicious run: models the malicious resolver's outcome for this dispute.
        ;; Returns the actual profit-malice (with real detection/slashing applied).
        m (apply dispute/resolve-dispute
                 rng-m escrow fee-bps bond-bps slash-mult :malicious
                 apc apw det-prob kwargs)]

    {:profit-honest      (:profit-honest h)
     :profit-malice      (:profit-malice m)
     :bond-loss          (:bond-loss m 0)
     :fraud-upside       (:fraud-upside m 0)
     :slashed?           (:slashed? m)
     :slashing-reason    (:slashing-reason m)
     :dispute-correct?   (:dispute-correct? h true)
     :appeal-triggered?  (:appeal-triggered? m)
     :escalated?         (:escalated? m)
     :l2-detected?       (:l2-detected? m)
     :frozen?            (:frozen? m)
     :escaped?           (:escaped? m)
     :reversal-pending?   (:reversal-pending? m)
     :slashing-pending?   (:slashing-pending? m)
     :slashing-delay-weeks (:slashing-delay-weeks m)}))

;; ---------------------------------------------------------------------------
;; Shared batch runner
;; ---------------------------------------------------------------------------

(defn build-aggregate
  "Compute aggregate statistics from a vector of paired trial results.

   Produces the same key set as batch/run-batch so that callers
   (run-single-epoch, etc.) can consume it without modification."
  [paired-trials n-trials params]
  (let [profits-honest (map :profit-honest paired-trials)
        profits-malice (map :profit-malice paired-trials)
        mean-h         (mean profits-honest)
        mean-m         (mean profits-malice)
        sorted-h       (sort profits-honest)
        sorted-m       (sort profits-malice)

        total-slashed      (count (filter :slashed? paired-trials))
        detected-count     (count (filter :detected? paired-trials))
        appeal-count       (count (filter :appeal-triggered? paired-trials))
        escalation-count   (count (filter :escalated? paired-trials))
        fraud-slashed      (count (filter #(= (:slashing-reason %) :fraud) paired-trials))
        reversal-slashed   (count (filter #(= (:slashing-reason %) :reversal) paired-trials))
        timeout-slashed    (count (filter #(= (:slashing-reason %) :timeout) paired-trials))
        l2-detected-count  (count (filter :l2-detected? paired-trials))
        frozen-count       (count (filter :frozen? paired-trials))
        escaped-count      (count (filter :escaped? paired-trials))]

    {:n-trials          n-trials
     :mode              :shared-world
     :strategy (or (:force-strategy params) (:strategy params :honest))
     :oracle-effective-mode (:mode (:oracle-effective params)
                                   (detection/normalize-oracle-fixture params))
     :oracle-fixture-exhausted? (boolean (some :oracle-fixture/exhausted? paired-trials))
     :oracle-fixture-warnings
     (vec (distinct (mapcat :oracle-fixture/warnings paired-trials)))
     :oracle-fixture-warning-errors
     (count (filter #(= :error (:level %))
                    (mapcat :oracle-fixture/warnings paired-trials)))
     :honest-mean       (double mean-h)
     :honest-std        (double (std-dev profits-honest mean-h))
     :honest-min        (if (seq sorted-h) (first sorted-h) 0)
     :honest-max        (if (seq sorted-h) (last sorted-h) 0)
     :honest-p25        (if (seq sorted-h) (quantile sorted-h 0.25) 0)
     :honest-p50        (if (seq sorted-h) (quantile sorted-h 0.50) 0)
     :honest-p75        (if (seq sorted-h) (quantile sorted-h 0.75) 0)
     :malice-mean       (double mean-m)
     :malice-std        (double (std-dev profits-malice mean-m))
     :malice-min        (if (seq sorted-m) (first sorted-m) 0)
     :malice-max        (if (seq sorted-m) (last sorted-m) 0)
     :malice-p25        (if (seq sorted-m) (quantile sorted-m 0.25) 0)
     :malice-p50        (if (seq sorted-m) (quantile sorted-m 0.50) 0)
     :malice-p75        (if (seq sorted-m) (quantile sorted-m 0.75) 0)
     :honest-wins       (count (filter #(> (:profit-honest %) (:profit-malice %))
                                       paired-trials))
     :dominance-ratio   (if (and (double? (double mean-m)) (pos? mean-m))
                          (double (/ mean-h mean-m))
                          Double/POSITIVE_INFINITY)
     :appeal-rate       (double (/ appeal-count n-trials))
     :escalation-rate   (double (/ escalation-count n-trials))
     :detection-rate    (double (/ detected-count n-trials))
     :l2-detection-rate (double (/ l2-detected-count n-trials))
     :slash-rate        (double (/ total-slashed n-trials))
     :fraud-slash-rate  (double (/ fraud-slashed n-trials))
     :reversal-slash-rate (double (/ reversal-slashed n-trials))
     :timeout-slash-rate  (double (/ timeout-slashed n-trials))
     :fraud-slashed-count    fraud-slashed
     :reversal-slashed-count reversal-slashed
     :timeout-slashed-count  timeout-slashed
     :frozen-rate       (double (/ frozen-count n-trials))
     :escaped-rate      (double (/ escaped-count n-trials))
     :adjusted-strategy (:adjusted-strategy params (or (:force-strategy params) (:strategy params :honest)))
     :bribery-enabled (boolean (and (:bribe-cost-ratio params)
                                    (:fraud-slash-bps params)
                                    (> (:fraud-slash-bps params) 0)))
     :bribery-cost (when (:bribe-cost-ratio params)
                     (integration/calculate-bribery-cost params))}))

(defn run-shared-batch
  "Run n-trials disputes generating PAIRED honest and malicious outcomes.

   Unlike batch/run-batch-with-attribution which runs two independent batches
   of n-trials each, this generates one pool of n-trials disputes where each
   dispute produces both an honest outcome and a malicious outcome via
   independent RNG streams.

   The caller (run-single-epoch with :use-shared-world? true) splits the
   paired-trials vector between honest and strategic resolver groups:
     - honest resolvers handle honest-slice  → profit-honest is used
     - strategic resolvers handle strategic-slice → profit-malice is used
   Total disputes handled == n-trials (not 2n).

   Returns:
     {:paired-trials    — vector of n-trials paired outcome maps
      :aggregate        — aggregate stats (same shape as batch/run-batch)
      :aggregate-malice — same aggregate (both strategies combined in one pool)}"
  [rng n-trials params]
  (let [paired-trials (vec (repeatedly n-trials #(run-paired-trial rng params)))
        aggregate     (build-aggregate paired-trials n-trials params)]
    {:paired-trials    paired-trials
     :aggregate        aggregate
     :aggregate-malice aggregate}))
