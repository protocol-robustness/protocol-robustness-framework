(ns resolver-sim.research.sew.adversarial.fair-slashing
  "Phase AE: Fair Slashing Hypothesis Sweep.

   Tests whether the DR3 fair-slashing corrections (PENDING fraud window,
   24h TIMEOUT_RESOLVE contest window, appeal bond enforcement) preserve
   resolver capital in false-positive slash scenarios.

   Hypothesis:
     With a PENDING fraud window and 24h timeout contest, a resolver
     experiencing a false-positive slash can recover capital in ≥95% of
     scenarios where governance makes the correct appeal decision.

   Parameters swept:
     :appeal-window         — seconds [86400, 259200, 604800] (1d, 3d, 7d)
     :appeal-bond-usd       — USD equivalent [0, 50, 100, 200]
     :p-correct-appeal      — probability governance decides correctly [0.7 0.8 0.9 0.99]
     :timeout-contest-window — seconds [43200, 86400, 172800] (12h, 24h, 48h)

   Pass threshold: ≥80% of resolver capital correctly preserved across
   false-positive scenarios."
  (:require [resolver-sim.stochastic.rng :as rng]
            [resolver-sim.sim.engine      :as proto]))

;; ---------------------------------------------------------------------------
;; Slash outcome model
;; ---------------------------------------------------------------------------

(defn simulate-false-positive-slash
  "Simulate a single false-positive slash scenario.

   A false-positive slash occurs when a resolver is incorrectly accused.
   The resolver is innocent; the correct governance outcome is to REVERSE.

   Returns :capital-preserved if the resolver successfully recovered capital,
   :capital-lost otherwise."
  [{:keys [appeal-window appeal-bond-usd p-correct-appeal resolver-stake-usd]} d-rng]
  (let [;; Can the resolver afford the appeal bond?
        can-afford-bond?  (>= resolver-stake-usd appeal-bond-usd)
        ;; Did the resolver notice the slash in time to appeal?
        ;; Assumes resolvers with monitoring see the slash with p=0.95;
        ;; resolvers without monitoring have p=0.5 within the appeal window.
        noticed-in-time?  (< (rng/next-double d-rng) 0.90)
        ;; Did governance make the correct decision (uphold appeal / reverse slash)?
        correct-decision? (< (rng/next-double d-rng) p-correct-appeal)]
    (cond
      ;; Resolver couldn't afford the bond — cannot appeal.
      (not can-afford-bond?) :capital-lost
      ;; Resolver didn't notice in time — missed the appeal window.
      (not noticed-in-time?) :capital-lost
      ;; Governance made the correct decision — slash reversed.
      correct-decision?       :capital-preserved
      ;; Governance made the wrong decision — capital lost.
      :else                   :capital-lost)))

(defn simulate-timeout-contest
  "Simulate a TIMEOUT_RESOLVE contest scenario.

   A resolver experiences a false timeout slash (e.g. due to RPC failure).
   They have a contest-window to provide evidence.

   Returns :capital-preserved or :capital-lost."
  [{:keys [timeout-contest-window p-correct-appeal appeal-bond-usd resolver-stake-usd]} d-rng]
  (let [;; Resolver files a contest before the deadline
        ;; Assumes faster contest window = lower chance of noticing (linear interpolation)
        base-notice-p     (/ timeout-contest-window (* 48 3600.0)) ; normalize to 48h = 1.0
        capped-notice-p   (min 0.95 base-notice-p)
        noticed?          (< (rng/next-double d-rng) capped-notice-p)
        can-afford-bond?  (>= resolver-stake-usd appeal-bond-usd)
        correct-decision? (< (rng/next-double d-rng) p-correct-appeal)]
    (cond
      (not noticed?)        :capital-lost
      (not can-afford-bond?) :capital-lost
      correct-decision?      :capital-preserved
      :else                  :capital-lost)))

;; ---------------------------------------------------------------------------
;; Sweep runner
;; ---------------------------------------------------------------------------

(defn run-scenario
  "Run n-trials for a single parameter combination.
   Returns {:params params :preservation-rate float :pass? bool}"
  [params n-trials seed preservation-floor]
  (let [d-rng          (rng/make-rng seed)
        fraud-outcomes (for [_ (range n-trials)]
                         (simulate-false-positive-slash params d-rng))
        timeout-outcomes (for [_ (range n-trials)]
                           (simulate-timeout-contest params d-rng))
        all-outcomes   (concat fraud-outcomes timeout-outcomes)
        preserved      (count (filter #{:capital-preserved} all-outcomes))
        total          (count all-outcomes)
        rate           (double (/ preserved total))]
    {:params            params
     :preservation-rate rate
     :pass?             (>= rate preservation-floor)}))

(defn run-sweep
  "Run the full Phase AE parameter sweep.

   Returns a seq of scenario results."
  [{:keys [n-trials base-seed resolver-stake-usd pass-threshold expected-preservation-floor]
    :or   {n-trials 1000 base-seed 42 resolver-stake-usd 10000
           pass-threshold 0.80 expected-preservation-floor 0.80}}]
  (let [appeal-windows    [86400 259200 604800]
        appeal-bonds      [0 50 100 200]
        p-correct-vals    [0.70 0.80 0.90 0.99]
        contest-windows   [43200 86400 172800]]
    (for [appeal-window    appeal-windows
          appeal-bond-usd  appeal-bonds
          p-correct-appeal p-correct-vals
          timeout-contest  contest-windows
          :let [seed   (+ base-seed
                          (* appeal-window 7)
                          (* appeal-bond-usd 13)
                          (int (* p-correct-appeal 1000)))
                params {:appeal-window          appeal-window
                        :appeal-bond-usd        appeal-bond-usd
                        :p-correct-appeal       p-correct-appeal
                        :timeout-contest-window timeout-contest
                        :resolver-stake-usd     resolver-stake-usd}]]
      (run-scenario params n-trials seed expected-preservation-floor))))

;; ---------------------------------------------------------------------------
;; Report
;; ---------------------------------------------------------------------------

(defn summarize-sweep
  "Summarize sweep results into a high-level report."
  [results pass-threshold]
  (let [total       (count results)
        passing     (count (filter :pass? results))
        pass-rate   (double (/ passing total))
        worst-case  (apply min-key :preservation-rate results)
        best-case   (apply max-key :preservation-rate results)]
    {:total-scenarios    total
     :passing-scenarios  passing
     :overall-pass-rate  pass-rate
     :pass-threshold     pass-threshold
     :hypothesis-holds?  (>= pass-rate pass-threshold)
     :worst-preservation (:preservation-rate worst-case)
     :worst-params       (:params worst-case)
     :best-preservation  (:preservation-rate best-case)
     :best-params        (:params best-case)}))

(defn run-phase-ae
  "Entry point: run the full Phase AE fair-slashing sweep and return a summary."
  ([] (run-phase-ae {}))
  ([opts]
   (let [pass-threshold (double (or (:pass-threshold opts) 0.80))
         preservation-floor (double (or (:expected-preservation-floor opts) 0.80))]
     (proto/print-phase-header
      {:benchmark-id "AE"
       :label        "Fair Slashing — Capital Preservation"
       :hypothesis   (format "Resolver capital preserved in >=%.0f%% of false-positive slash scenarios"
                             (* 100 preservation-floor))
       :pass-threshold pass-threshold
       :preservation-floor preservation-floor})
     (let [results (run-sweep (assoc opts
                                     :pass-threshold pass-threshold
                                     :expected-preservation-floor preservation-floor))
           summary (summarize-sweep results pass-threshold)]
       (proto/print-phase-footer
        {:benchmark-id  "AE"
         :passed?       (:hypothesis-holds? summary)
         :summary-lines [(format "Pass rate: %.0f%%  (%d / %d scenarios)  (threshold: %.0f%%)"
                                 (* 100 (:overall-pass-rate summary))
                                 (:passing-scenarios summary)
                                 (:total-scenarios summary)
                                 (* 100 pass-threshold))]})
       (proto/make-result
        {:benchmark-id "AE"
         :label        "Fair Slashing — Capital Preservation"
         :hypothesis   (format "Resolver capital preserved in >=%.0f%% of false-positive slash scenarios"
                               (* 100 preservation-floor))
         :passed?      (:hypothesis-holds? summary)
         :results      results
         :summary      summary})))))
