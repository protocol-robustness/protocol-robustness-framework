(ns resolver-sim.yield.market-state
  "Unify schedules and policies into a composite yield market state at a specific time."
  (:require [resolver-sim.yield.schedule :as schedule]
            [resolver-sim.logging :as log]))

(def default-shortfall-model
  {:type :liquidity-only
   :recoverable true
   :loss-recognition :defer})

(def default-withdrawal-policy
  {:on-shortfall :partial-fill
   :defer-unfilled true
   :record-claimable-shortfall true})

(defn get-market-state
  "Resolve the composite market state for a module/token at a specific time.
   Reads from world paths populated by apply-yield-config."
  [world module-id token time]
  (let [tok (if (keyword? token) token (keyword (str token)))
        mid (if (keyword? module-id) module-id (when module-id (keyword (str module-id))))
        resolved-mid (if mid
                       (get-in world [:yield/module-aliases mid] mid)
                       nil)
        path (if resolved-mid [:yield/schedules resolved-mid tok] [:yield/schedules nil])
        schedules (get-in world path)
        ;; Dynamic risk overrides from set-yield-risk
        risk (when resolved-mid (get-in world [:yield/risk resolved-mid tok]))
        risk-shortfall (:shortfall risk)

        ;; APY from risk/legacy path (highest priority)
        ;; Try normalized (keyword) key first, fall back to string key for back compat.
        risk-apy (when resolved-mid
                   (or (get-in world [:yield/rates resolved-mid tok])
                       (get-in world [:yield/rates resolved-mid (name tok)])))

        ;; Default APY as fallback for schedule lookup
        default-apy (if (some? risk-apy) risk-apy 0.04)]
    (let [schedules* (or schedules {:rate-schedule nil :index-schedule nil :liquidity-schedule nil :module-state-schedule nil})
          sched-apy (try (schedule/get-value-at-time (:rate-schedule schedules*) time default-apy)
                         (catch Exception e (log/error! "apy-lookup-failed" {:time time}) default-apy))
          sched-ratio (try (schedule/get-value-at-time (:liquidity-schedule schedules*) time 1.0)
                           (catch Exception e (log/error! "liquidity-lookup-failed" {:time time}) 1.0))
          sched-state (try (schedule/get-value-at-time (:module-state-schedule schedules*) time :normal)
                           (catch Exception e (log/error! "module-state-lookup-failed" {:time time}) :normal))]
      (let [risk-lmode (:liquidity-mode risk)
            risk-fm    (:failure-modes risk)
            resolved-state (cond
                             (and risk-lmode (not= risk-lmode :available)) risk-lmode
                             (seq risk-fm) (first (sort risk-fm))
                             :else sched-state)
            ;; If risk explicitly says :shortfall but provided no ratio, 
            ;; we must ensure ratio < 1.0 to trigger the shortfall logic.
            final-ratio (or (:available-ratio risk-shortfall)
                            (if (= resolved-state :shortfall)
                              (min 0.5 (double sched-ratio))
                              sched-ratio))]
        {:apy (if (some? risk-apy) risk-apy sched-apy)
         :index (try (schedule/get-value-at-time (:index-schedule schedules*) time nil)
                     (catch Exception e (log/error! "index-lookup-failed" {:time time}) nil))
         :available-ratio final-ratio
         :module-state resolved-state
         :shortfall-model (if resolved-mid
                            (or (get-in world [:yield/shortfall-models resolved-mid token])
                                default-shortfall-model)
                            default-shortfall-model)
         :withdrawal-policy (if resolved-mid
                              (or (get-in world [:yield/withdrawal-policies resolved-mid token])
                                  default-withdrawal-policy)
                              default-withdrawal-policy)}))))
