;; Phase M: Governance Response Time Impact Analysis
;; Tests how governance delays (3-7 day response windows) affect system security
;; Extends Phase J multi-epoch simulation to include pending slashes queue

(ns resolver-sim.sim.governance-impact
  (:require [resolver-sim.sim.batch :as batch]
            [resolver-sim.sim.reputation :as rep]
            [resolver-sim.sim.governance-delay :as gov-delay]
            [resolver-sim.stochastic.rng :as rng]
            [clojure.set]))

;; ============================================================================
;; MULTI-EPOCH WITH GOVERNANCE DELAYS

(defn- next-int
  "Seeded [0,n) integer using stochastic RNG."
  [rng n]
  (int (Math/floor (* (rng/next-double rng) n))))

(defn run-single-epoch-with-governance
  "Run one epoch with governance delay mechanics
  
  Flow:
  1. Process pending slashes (execute any ready for execution)
  2. Run batch simulation with frozen resolvers
  3. Mark new slashes as pending (governance queue)
  
  Returns:
  - :epoch-summary: Stats for this epoch
  - :updated-histories: Resolver state after epoch
  - :governance-state: Updated pending/resolved slashes
  - :executed-slashes: Slashes that executed this epoch
  - :next-id: Tracker for new resolver IDs"
  [rng epoch resolver-histories n-trials params governance-state next-id]
  (let [;; Step 1: Execute any pending slashes whose governance window closed
        {:keys [pending-state executable-slashes slashes-still-pending]}
        (gov-delay/process-governance-approvals governance-state epoch)
        [rng-batch rng-events rng-decay] (rng/split-rng rng)

        ;; Get set of frozen resolvers (those with pending slashes)
        frozen-resolver-ids (set (map :resolver-id slashes-still-pending))

        ;; Step 2: Run batch simulation for this epoch (frozen resolvers don't get assignments)
        decayed-params (rep/apply-detection-decay params epoch)
        batch-result (batch/run-batch rng-batch n-trials decayed-params)

        ;; Step 3: Mark newly detected slashes as pending governance approval
        detection-rate (double (get-in batch-result [:aggregate :detection-rate] 0.0))
        detected-slashes-count (long (* n-trials detection-rate))

        ;; Create pending slash entries for detected slashes
        new-pending (reduce (fn [acc _]
                              (let [resolver-id (next-int rng-events (max 1 (count resolver-histories)))
                                    slash-amount (+ 5000 (next-int rng-events 5000))] ; $5-10k
                                (gov-delay/mark-slash-detected
                                 acc
                                 slash-amount
                                 resolver-id
                                 epoch
                                 (:governance-response-days params 3)
                                 :timeout)))
                            pending-state
                            (range detected-slashes-count))

        ;; Summary for this epoch
        epoch-summary
        {:epoch epoch
         :n-trials n-trials
         :honest-mean-profit (:honest-mean batch-result)
         :malice-mean-profit (:malice-mean batch-result)
         :dominance-ratio (:dominance-ratio batch-result)
         :detection-rate detection-rate
         :l1-reversal-rate (:p-l1-reversal decayed-params)
         :l2-reversal-rate (:p-l2-reversal decayed-params)
         :slashes-detected detected-slashes-count
         :slashes-executed (count executable-slashes)
         :slashes-pending (count (:pending-slashes new-pending))
         :frozen-resolvers (count frozen-resolver-ids)}

        ;; Update resolver histories
        updated-histories
        (let [honest-mean (:honest-mean batch-result)
              malice-mean (:malice-mean batch-result)
              honest-count (count (filter #(= :honest (:strategy (val %))) resolver-histories))
              malice-count (count (filter #(#{:malicious :lazy :collusive} (:strategy (val %))) resolver-histories))]

          (reduce-kv (fn [acc id resolver]
                       (let [strategy (:strategy resolver)
                             is-honest? (= strategy :honest)
                             is-frozen? (contains? frozen-resolver-ids id)

                             resolver-profit (if is-frozen?
                                               0.0  ; Frozen = zero profit this epoch
                                               (if is-honest?
                                                 (if (> honest-count 0) (/ honest-mean honest-count) 0)
                                                 (if (> malice-count 0) (/ malice-mean malice-count) 0)))

                             was-slashed? (and (not is-honest?)
                                               (> (:slash-rate batch-result 0) 0)
                                               (< (rng/next-double rng-events) (:slash-rate batch-result 0.1)))

                             updated (rep/update-resolver-history
                                      resolver
                                      resolver-profit
                                      (if is-frozen? 0 1)  ; 0 verdicts if frozen
                                      (if is-honest? (if is-frozen? 0 1) 0)
                                      was-slashed?
                                      epoch)]

                         (assoc acc id updated)))
                     {} resolver-histories))

        ;; Apply population decay
        decay-result (rep/apply-epoch-decay updated-histories epoch params rng-decay next-id)]

    {:epoch-summary epoch-summary
     :histories (:histories decay-result)
     :next-id (:next-id decay-result)
     :governance-state new-pending
     :executed-slashes executable-slashes}))

(defn run-multi-epoch-governance-impact
  "Run N epochs with governance delay mechanics (Phase M)
  
  Args:
    rng: RNG state
    n-epochs: Number of epochs
    n-trials-per-epoch: Trials per epoch
    params: Simulation parameters (including governance-response-days)
  
  Returns: Complete simulation results including governance metrics"
  [rng n-epochs n-trials-per-epoch params]
  (let [n-resolvers (get params :n-resolvers 100)
        strategy-mix (or (:strategy-mix params)
                         {:honest 0.50 :lazy 0.15 :malicious 0.25 :collusive 0.10})
        gov-response-days (get params :governance-response-days 3)

        _ (println (format "\n🏛️  Running Phase M: Governance Response Time Impact"))
        _ (println (format "   Epochs: %d" n-epochs))
        _ (println (format "   Trials per epoch: %d" n-trials-per-epoch))
        _ (println (format "   Governance response window: %d days" gov-response-days))
        _ (println (format "   Initial resolvers: %d" n-resolvers))
        _ (println "")

        ;; Initialize
        initial-histories (rep/initialize-resolvers n-resolvers strategy-mix)
        initial-gov-state (gov-delay/initialize-pending-slashes)

        ;; Run epoch loop
        result-accumulator
        (reduce (fn [acc epoch-num]
                  (let [[rng-1 rng-2] (rng/split-rng (:rng acc))
                        prev-histories (:histories acc initial-histories)
                        prev-gov-state (:gov-state acc initial-gov-state)
                        prev-next-id   (:next-id acc n-resolvers)

                        {:keys [epoch-summary histories next-id governance-state executed-slashes]}
                        (run-single-epoch-with-governance rng-1 epoch-num prev-histories n-trials-per-epoch params prev-gov-state prev-next-id)

                        _ (println (format "   Epoch %d: honest=%.0f, frozen=%d, pending=%d, executed=%d"
                                           epoch-num
                                           (:honest-mean-profit epoch-summary)
                                           (:frozen-resolvers epoch-summary)
                                           (:slashes-pending epoch-summary)
                                           (:slashes-executed epoch-summary)))]

                    (assoc acc
                           :rng rng-2
                           :epochs (cons epoch-summary (:epochs acc []))
                           :histories histories
                           :next-id next-id
                           :gov-state governance-state
                           :all-executed-slashes (concat executed-slashes (:all-executed-slashes acc [])))))
                {:rng rng :epochs [] :histories initial-histories :next-id n-resolvers :gov-state initial-gov-state :all-executed-slashes []}
                (range 1 (inc n-epochs)))

        epoch-results (reverse (:epochs result-accumulator []))
        final-histories (:histories result-accumulator initial-histories)
        final-gov-state (:gov-state result-accumulator initial-gov-state)

        ;; Calculate governance metrics
        gov-metrics (gov-delay/summarize-governance-delay-metrics final-gov-state)

        ;; Calculate final statistics
        final-stats
        (let [honest-resolvers (filter #(= :honest (:strategy (val %))) final-histories)
              malice-resolvers (filter #(#{:malicious :lazy :collusive} (:strategy (val %))) final-histories)
              honest-profits (map #(rep/cumulative-profit (val %)) honest-resolvers)
              malice-profits (map #(rep/cumulative-profit (val %)) malice-resolvers)
              initial-ids (set (keys initial-histories))
              final-ids (set (keys final-histories))
              total-exits (count (clojure.set/difference initial-ids final-ids))]

          {:final-resolver-count (count final-histories)
           :total-resolver-exits total-exits
           :honest-final-count (count honest-resolvers)
           :malice-final-count (count malice-resolvers)
           :honest-cumulative-profit (if (empty? honest-profits) 0 (apply + honest-profits))
           :malice-cumulative-profit (if (empty? malice-profits) 0 (apply + malice-profits))
           :honest-avg-profit (if (empty? honest-profits) 0 (/ (apply + honest-profits) (count honest-resolvers)))
           :malice-avg-profit (if (empty? malice-profits) 0 (/ (apply + malice-profits) (count malice-resolvers)))})]

    ;; Return complete results
    {:scenario-id (:scenario-id params "phase-m-unnamed")
     :governance-response-days gov-response-days
     :n-epochs n-epochs
     :n-trials-per-epoch n-trials-per-epoch
     :initial-resolver-count n-resolvers
     :epoch-results epoch-results
     :resolver-histories final-histories
     :governance-metrics gov-metrics
     :aggregated-stats final-stats}))

;; ============================================================================
;; COMPARISON: Phase J (Instant) vs Phase M (Delayed)
;; ============================================================================

(defn compare-governance-delays
  "Compare system behavior with different governance response delays
  
  Runs same scenario with multiple governance-response-day values:
  - 0 days (instant, no governance delay)
  - 3 days (typical governance)
  - 7 days (slow governance)
  - 14 days (very slow governance / governance failure)
  
  Returns comparison showing impact of delays on:
  - Honest resolver profitability
  - Malicious resolver profitability  
  - Resolver exit rates
  - System stability"
  [rng n-epochs n-trials-per-epoch base-params governance-day-options]
  (let [results (map (fn [gov-days]
                       (let [scenario-params (assoc base-params
                                                    :governance-response-days gov-days
                                                    :scenario-id (str "phase-m-" gov-days "days"))]
                         {:governance-days gov-days
                          :result (run-multi-epoch-governance-impact rng n-epochs n-trials-per-epoch scenario-params)}))
                     governance-day-options)]

    {:comparison-id "governance-delay-sensitivity"
     :test-configurations results
     :summary-table (map (fn [{:keys [governance-days result]}]
                           {:governance-response-days governance-days
                            :honest-final-profit (get-in result [:aggregated-stats :honest-avg-profit])
                            :malice-final-profit (get-in result [:aggregated-stats :malice-avg-profit])
                            :slashes-executed (apply + (map :slashes-executed (:epoch-results result)))
                            :slashes-pending (get-in result [:governance-metrics :pending-slashes-still-waiting])
                            :total-exits (get-in result [:aggregated-stats :total-resolver-exits])})
                         results)}))
