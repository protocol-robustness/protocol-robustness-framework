(ns resolver-sim.scenario.yield-metrics
  "Yield-specific metrics derived from replay snapshots for scenario expectations."
  (:require [resolver-sim.yield.market-state :as market-state]))

(defn- resolve-owner-id [workflow-id owner-id]
  (or owner-id
      (when workflow-id
        [:sew/escrow (if (number? workflow-id) workflow-id (long workflow-id))])
      [:sew/escrow 0]))

(defn- yield-position [world oid]
  (or (get-in world [:yield/positions oid])
      (get-in world [:yield-positions oid])))

(defn- yield-positions-map [world]
  (or (:yield/positions world)
      (:yield-positions world)
      {}))

(defn compute-yield-metrics
  "Extract numeric yield metrics from the final trace snapshot.

   Supports either :workflow-id (legacy SEW) or :owner-id (general).
   All amounts are integer base units."
  [result & {:keys [workflow-id owner-id]}]
  (let [trace      (:trace result)
        last-world (when (seq trace) (:world (last trace)))
        positions  (when last-world (or (:yield/positions last-world)
                                        (:yield-positions last-world)
                                        {}))
        oid        (or owner-id
                       (get-in result [:scenario :protocol-params :default-owner-id])
                       (when (= 1 (count positions)) (first (keys positions)))
                       (resolve-owner-id workflow-id owner-id))
        pos        (yield-position last-world oid)
        shortfall  (:shortfall pos)
        token      (or (:token pos) :USDC)
        ;; Sew-specific lookups (safe with nil workflow-id)
        wf-id      (or workflow-id (when (= (first oid) :sew/escrow) (second oid)))
        mid        (or (:module/id pos) :aave-v3)
        ms         (when last-world
                     (market-state/get-market-state last-world mid token (:block-time last-world)))
        claimable  (when wf-id (get-in last-world [:claimable wf-id]))
        buyer      (when wf-id (get-in last-world [:escrow-transfers wf-id :from]))
        recipient  (when wf-id (get-in last-world [:escrow-transfers wf-id :to]))]
    (cond-> {:yield/principal     (long (or (:principal pos) 0))
             :yield/unrealized    (long (or (:unrealized-yield pos) 0))
             :yield/current-value (long (or (:current-value pos)
                                            (+ (or (:principal pos) 0)
                                               (or (:unrealized-yield pos) 0))))
             :yield/realized      (long (or (:realized-yield pos) 0))
             :yield/accrual-loss         (long (or (:amount (:yield-loss pos)) 0))
             :yield/deferred      (long (or (:deferred-amount shortfall) 0))
             :yield/haircut       (long (or (:haircut-amount shortfall) 0))
             :yield/reclaimed     (long (or (:reclaimed-amount pos) 0))
             :yield/gross         (long (+ (or (:principal pos) 0)
                                           (or (:unrealized-yield pos) 0)))
             :yield/available-ratio (or (:available-ratio ms) 1.0)}
      ;; Backwards compatibility aliases for :yield/escrow-* and the older
      ;; provider scenario metric names (:yield/position-*).
      true (assoc :yield/escrow-principal (long (or (:principal pos) 0))
                  :yield/escrow-unrealized (long (or (:unrealized-yield pos) 0))
                  :yield/escrow-realized (long (or (:realized-yield pos) 0))
                  :yield/escrow-deferred (long (or (:deferred-amount shortfall) 0))
                  :yield/escrow-haircut (long (or (:haircut-amount shortfall) 0))
                  :yield/escrow-reclaimed (long (or (:reclaimed-amount pos) 0))
                  :yield/escrow-current-value (long (or (:current-value pos)
                                                        (+ (or (:principal pos) 0)
                                                           (or (:unrealized-yield pos) 0))))
                  :yield/escrow-gross (long (+ (or (:principal pos) 0)
                                               (or (:unrealized-yield pos) 0)))
                  :yield/position-principal (long (or (:principal pos) 0))
                  :yield/position-unrealized (long (or (:unrealized-yield pos) 0))
                  :yield/position-realized (long (or (:realized-yield pos) 0))
                  :yield/position-deferred (long (or (:deferred-amount shortfall) 0))
                  :yield/position-haircut (long (or (:haircut-amount shortfall) 0))
                  :yield/position-reclaimed (long (or (:reclaimed-amount pos) 0))
                  :yield/position-current-value (long (or (:current-value pos)
                                                          (+ (or (:principal pos) 0)
                                                             (or (:unrealized-yield pos) 0))))
                  :yield/position-gross (long (+ (or (:principal pos) 0)
                                                 (or (:unrealized-yield pos) 0)))
                  :yield/position-status (when (:status pos) (name (:status pos))))

      (:current-index pos) (assoc :yield/current-index (:current-index pos)
                                  :yield/escrow-current-index (:current-index pos))
      (:entry-index pos) (assoc :yield/entry-index (:entry-index pos)
                                :yield/escrow-entry-index (:entry-index pos))
      (:status pos) (assoc :yield/status (name (:status pos))
                           :yield/escrow-status (name (:status pos)))
      (or (:reason shortfall) (:reason (:yield-loss pos)))
      (assoc :yield/loss-reason (name (or (:reason shortfall) (:reason (:yield-loss pos)))))

      ;; Sew specific metrics
      wf-id
      (assoc :escrow/amount-after-fee    (long (or (get-in last-world [:escrow-amounts wf-id])
                                                   (get-in last-world [:escrow-transfers wf-id :amount-after-fee])
                                                   0))
             :protocol/fees-usdc         (long (or (get-in last-world [:total-fees token]
                                                           (get-in last-world [:total-fees (name token)]))
                                                   0))
             :buyer/claimable            (long (or (when claimable (get claimable buyer))
                                                   (get claimable (keyword buyer))
                                                   0))
             :recipient/claimable        (long (or (when claimable (get claimable recipient))
                                                   (get claimable (keyword recipient))
                                                   0)))

      (and wf-id (:escrow-state (get-in last-world [:escrow-transfers wf-id])))
      (assoc :escrow/state (name (:escrow-state (get-in last-world [:escrow-transfers wf-id]))))
      (and wf-id (get-in last-world [:live-states wf-id]))
      (assoc :escrow/live-state (name (get-in last-world [:live-states wf-id]))))))

(defn merge-yield-metrics
  [result & opts]
  (update result :metrics merge (apply compute-yield-metrics result opts)))

(def ^:private yield-display-keys
  #{:yield/escrow-principal :yield/escrow-unrealized :yield/escrow-realized
    :yield/escrow-deferred :yield/escrow-haircut :yield/escrow-reclaimed
    :yield/escrow-status})

(defn yield-metric-key?
  "True when k names a yield/* metric.

   Accepts namespaced keywords (`:yield/escrow-principal`), plain strings
   (`\"yield/escrow-principal\"`), and JSON-normalized keyword strings
   (`\":yield/escrow-principal\"`)."
  [k]
  (cond
    (and (keyword? k) (= "yield" (namespace k))) true
    (string? k)
    (let [s (if (and (.startsWith ^String k ":") (> (count k) 1))
              (subs k 1)
              k)]
      (.startsWith ^String s "yield/"))
    :else false))

(defn yield-metric-label
  "Stable display label for a yield metric key."
  [k]
  (if (keyword? k)
    (if-let [ns (namespace k)]
      (str ns "/" (name k))
      (name k))
    (str k)))

(defn yield-metrics-for-display
  "Compact yield subset for human reports — no world paths."
  [metrics]
  (select-keys metrics yield-display-keys))
