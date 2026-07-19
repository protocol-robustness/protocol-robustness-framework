(ns resolver-sim.yield.position
  "Extended position accounting schema with explicit yield buckets, shortfall
   flags, and accrual tracking fields.

   Extends the original `yield/model.clj` position schema with:
   - Yield bucket decomposition (realized, unrealized, deferred, haircut)
   - Principal impairment tracking
   - Shortfall/oracle/partial-fill/capital-event flags
   - Accrual tracking (last-accrual-time, last-accrual-index)
   - Exact sub-unit accrual dust accumulator
   - Status field extensions
   - Projection/report field accessors

   Schema:
   {:position/id          [:yield/position owner-id module-id token]
    :owner/id             owner-id
    :module/id            module-id
    :token                token-kw
    :principal            0           ; deposited underlying amount (base units)
    :shares               0           ; position units (ratio, e.g. 1000)
    :entry-index          1           ; index at deposit (ratio)
    :current-index        nil         ; last mark index (ratio or nil)
    :current-value        nil         ; quantized current value (long)
    :realized-yield       0           ; crystallized yield (base units)
    :unrealized-yield     0           ; current unrealized (base units, signed)
    :deferred-yield       0           ; deferred from partial fill (base units)
    :haircut-yield        0           ; permanently lost (base units)
    :principal-impairment 0           ; impaired principal (base units)
    :accrual-dust-remainder 0         ; exact fractional remainder carried forward (ratio)
    :shortfall-affected?   false
    :oracle-stale-affected? false
    :partial-fill-affected? false
    :capital-event-affected? false
    :original-priority      Long/MAX_VALUE
    :last-accrual-time     nil
    :last-accrual-index    nil
    :status                :active}
  Statuses: :active, :unwinding, :unwound, :withdrawn, :queued, :settled

  :original-priority is the deposit sequence number for FIFO ordering — lower
  values are older positions that get priority in shared withdrawals."
  (:require [resolver-sim.yield.exact-math :as m]))

(def default-position
  {:principal 0
   :shares 0
   :entry-index 1
   :realized-yield 0
   :unrealized-yield 0
   :deferred-yield 0
   :haircut-yield 0
   :principal-impairment 0
   :accrual-dust-remainder 0
   :shortfall-affected? false
   :oracle-stale-affected? false
   :partial-fill-affected? false
    :capital-event-affected? false
    :original-priority      Long/MAX_VALUE
    :status :active})

(defn make-position
  "Construct a position map with all required fields initialized.

   Required: :owner/id, :module/id, :token
   Optional: :principal (0), :shares (0), :entry-index (1), :status (:active),
             plus all extended fields.
   Shares and entry-index are stored as ratios."
  [{:keys [token principal shares entry-index status]
    :or {principal 0 shares 0 entry-index 1 status :active}
    :as m}]
  (let [id (:owner/id m)
        module-id (:module/id m)]
    (merge default-position
           {:position/id [:yield/position id module-id token]
            :owner/id id
            :module/id module-id
            :token token
            :principal (long principal)
            :shares (m/ratio (if (zero? shares) (long principal) shares))
            :entry-index (m/ratio entry-index)}
           (dissoc m :owner/id :module/id :token :principal :shares :entry-index :status))))

(defn normalize-position
  "Ensure a position map (potentially from legacy code) has all extended fields.
   Coerces numeric fields to the correct types."
  [pos]
  (merge default-position
         (dissoc pos :position/id)
         (when-let [pid (:position/id pos)]
           {:position/id pid})
         pos
         {:principal (long (or (:principal pos) 0))
          :shares (m/ratio (or (:shares pos) 0))
          :entry-index (m/ratio (or (:entry-index pos) 1))
          :realized-yield (long (or (:realized-yield pos) 0))
          :unrealized-yield (long (or (:unrealized-yield pos) 0))
          :deferred-yield (long (or (:deferred-yield pos) 0))
          :haircut-yield (long (or (:haircut-yield pos) 0))
           :principal-impairment (long (or (:principal-impairment pos) 0))
           :accrual-dust-remainder (m/ratio (or (:accrual-dust-remainder pos) 0))
           :original-priority (long (or (:original-priority pos) Long/MAX_VALUE))
           :shortfall-affected? (boolean (:shortfall-affected? pos))
          :oracle-stale-affected? (boolean (:oracle-stale-affected? pos))
          :partial-fill-affected? (boolean (:partial-fill-affected? pos))
          :capital-event-affected? (boolean (:capital-event-affected? pos))
          :status (or (:status pos) :active)}))

(defn active?
  "Is the position in an active (accruing) state?"
  [pos]
  (= :active (:status pos)))

(defn unwinding?
  "Is the position in unwinding state?"
  [pos]
  (= :unwinding (:status pos)))

(defn terminal?
  "Is the position in a terminal (non-accruing) state?"
  [pos]
  (contains? #{:withdrawn :unwound :settled} (:status pos)))

(defn position-identity
  "Return the canonical position-id vector."
  [pos]
  (or (:position/id pos)
      [:yield/position (:owner/id pos) (:module/id pos) (:token pos)]))

(defn claimable-principal
  "Principal currently claimable (net of impairment)."
  [pos]
  (max 0 (- (long (:principal pos 0)) (long (:principal-impairment pos 0)))))

(defn claimable-realized-yield
  "Realized yield currently claimable."
  [pos]
  (long (:realized-yield pos 0)))

(defn claimable-unrealized-yield
  "Unrealized yield — only claimable under mark-to-market crystallization."
  [pos]
  (max 0 (long (:unrealized-yield pos 0))))

(defn total-claimable
  "Total claimable amount across all buckets (base units)."
  [pos]
  (+ (claimable-principal pos)
     (claimable-realized-yield pos)
     (claimable-unrealized-yield pos)
     (long (:deferred-yield pos 0))))

(defn total-value
  "Total position value including haircut and impairment (gross economic value)."
  [pos]
  (+ (long (:principal pos 0))
     (long (:realized-yield pos 0))
     (long (:unrealized-yield pos 0))
     (long (:deferred-yield pos 0))
     (long (:haircut-yield pos 0))))

(defn net-equity
  "Net equity after all impairments and haircuts."
  [pos]
  (- (total-value pos)
     (long (:principal-impairment pos 0))
     (long (:haircut-yield pos 0))))

(defn projection-fields
  "Return the projection/report fields for a position as specified in the design.

   Returns a map with keys: workflow_id, module_id, token, settlement_mode,
   accrual_mode, shortfall_affected, partial_fill_affected, oracle_stale_affected,
   capital_event_affected, principal_claimable, principal_deferred, principal_haircut,
   yield_realized, yield_unrealized, yield_deferred, yield_haircut, short_circuits."
  [pos & {:keys [workflow-id settlement-mode accrual-mode short-circuits]}]
  (let [module-id (or (:module/id pos) (get-in pos [:position/id 2]))
        token (or (:token pos) (get-in pos [:position/id 3]))]
    {:workflow_id (or workflow-id (get-in pos [:position/id 1]))
     :module_id module-id
     :token token
     :settlement_mode (or settlement-mode :none)
     :accrual_mode (or accrual-mode :none)
     :shortfall_affected (boolean (:shortfall-affected? pos))
     :partial_fill_affected (boolean (:partial-fill-affected? pos))
     :oracle_stale_affected (boolean (:oracle-stale-affected? pos))
     :capital_event_affected (boolean (:capital-event-affected? pos))
     :principal_claimable (claimable-principal pos)
     :principal_deferred (long (:principal-impairment pos 0))
     :principal_haircut 0
     :yield_realized (claimable-realized-yield pos)
     :yield_unrealized (claimable-unrealized-yield pos)
     :yield_deferred (long (:deferred-yield pos 0))
     :yield_haircut (long (:haircut-yield pos 0))
     :short_circuits (vec (or short-circuits []))}))

