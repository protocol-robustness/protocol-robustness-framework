(ns resolver-sim.yield.accrual
  "Data-driven yield accrual decision layer with explicit short circuits.

   Implements a pure decision function `accrual-decision` that inspects world
   state and returns a structured decision map, and an `apply-accrual-decision`
   function that mutates world state from the decision.

   Every short circuit leaves explicit evidence in the decision map.
   Accrual uses exact ratio arithmetic internally; final deltas are
   quantized to integer base units with sub-unit dust carried forward.

   Short circuits (evaluated in order):
   1. Dust threshold
   2. Frozen/emergency zero accrual
   3. Unwinding position suspension
   4. Stale oracle degradation
   5. Max index delta cap
   6. Negative yield floor
   7. Recoverable liquidity cap"
  (:require [resolver-sim.yield.exact-math :as m]
            [resolver-sim.yield.position :as pos]
            [resolver-sim.yield.market-state :as market-state]
            [resolver-sim.yield.token :as tok]
            [resolver-sim.yield.loss :as loss]
            [resolver-sim.yield.risk :as risk-utils]
            [resolver-sim.time.context :as time-ctx]
            [resolver-sim.util.attribution :as attr]
            [resolver-sim.yield.risk-monitor :as risk]
            [resolver-sim.io.event-evidence :as evidence]))

(def ^:private schema-version "accrual-decision.v2")

(def ^:private default-freeze-statuses
  #{:frozen :paused :emergency-shutdown :disabled-for-new-deposits :module-frozen})

(def ^:private default-min-accrual-delta
  "Default dust threshold: accrual deltas below 1 base unit are deferred via
   dust accumulator carry-forward."
  1)

(def ^:private default-max-index-delta-ratio
  "Default max allowed index change as a ratio of current index (5%)."
  1/20)

(def ^:private default-max-index-delta-policy
  :cap)

(def ^:private default-stale-oracle-max-seconds
  "Default max staleness before full degradation (24 hours)."
  time-ctx/seconds-per-day)

(def ^:private default-stale-oracle-floor-bps
  "Default floor APY after stale oracle degradation (0 bps = 0%)."
  0)

(defn- normalize-token [token]
  (tok/normalize token))

(defn- resolve-module-status
  "Resolve the current status of a yield module from world state, including
   schedule awareness with legacy fallback."
  [world module-id token time]
  (let [mid (if (keyword? module-id) module-id (keyword (str module-id)))
        tok (normalize-token token)
        ms  (market-state/get-market-state world mid tok time)
        status (:module-state ms :active)]
    (if (or (nil? status) (= status :normal) (= status :active))
      ;; Fallback to legacy path for backward compatibility and test support
      (let [resolved (get-in world [:yield/module-aliases mid] mid)]
        (or (get-in world [:yield/module-status resolved])
            (get-in world [:yield/module-status mid])
            :active))
      status)))

(defn- resolve-module-freeze-statuses
  "Resolve the set of module statuses that trigger zero accrual."
  [world module-id]
  (let [mid (if (keyword? module-id) module-id (keyword (str module-id)))
        resolved (get-in world [:yield/module-aliases mid] mid)
        configured (get-in world [:yield/accrual-config resolved :freeze-on])]
    (if (set? configured)
      configured
      ;; Include both legacy and common schedule-driven freeze statuses
      (into default-freeze-statuses #{:frozen :paused :emergency :emergency-shutdown}))))

(defn- resolve-position
  "Find a position in world by owner-id, module-id, and token."
  [world owner-id module-id token]
  (let [positions (:yield/positions world {})
        pos (get positions owner-id)
        tok (normalize-token token)]
    (when (and pos
               (= (:module/id pos) module-id)
               (= (normalize-token (:token pos)) tok))
      pos)))

(defn- resolve-index
  "Resolve the current index for a module/token from world state."
  [world module-id token]
  (let [tok (normalize-token token)
        mid (if (keyword? module-id) module-id (keyword (str module-id)))]
    (or (get-in world [:yield/indices mid tok])
        (get-in world [:yield/indices mid (name tok)])
        1)))

(defn- resolve-base-apy-bps
  "Resolve the base APY in basis points for a module/token."
  [world module-id token]
  (let [tok (normalize-token token)
        mid (if (keyword? module-id) module-id (keyword (str module-id)))
        rate (or (get-in world [:yield/rates mid tok])
                 (get-in world [:yield/rates mid (name tok)])
                 0.04)]
    (long (Math/round (* (double rate) m/scaling-factor)))))

(defn- resolve-oracle-staleness
  "Check if oracle is stale and return {:stale? bool :stale-seconds long}.

   Interprets `:oracle-stale-seconds` as a direct duration in seconds.
   Interprets `:oracle-stale-since` as a block timestamp, converting to
   seconds-ago by subtracting from `now`."
  [world module-id token now]
  (let [mid (if (keyword? module-id) module-id (keyword (str module-id)))
        tok (normalize-token token)
        risk (or (get-in world [:yield/risk mid tok])
                 (get-in world [:yield/risk mid (name tok)])
                 {})
        failure-modes (or (:failure-modes risk) #{})
        stale? (contains? failure-modes :oracle-stale)
        stale-since (get-in world [:yield/oracle-stale-since mid tok])
        stale-seconds (if (some? stale-since)
                        (max 0 (- (long (or now 0)) (long stale-since)))
                        (long (or (:oracle-stale-seconds risk) 0)))]
    {:stale? stale?
     :stale-seconds stale-seconds}))

(defn- resolve-recoverable-liquidity
  "Get the recoverable module assets for a token.
   Uses :total-held as the primary proxy, with :yield/held-balances as a fallback."
  [world module-id token]
  (let [tok (normalize-token token)
        tok-name (name tok)
        held-sew (or (get-in world [:total-held tok])
                     (get-in world [:total-held tok-name]))]
    (if (some? held-sew)
      (long held-sew)
      (long (or (get-in world [:yield/held-balances tok-name])
                (get-in world [:yield/held-balances tok])
                0)))))

(defn- module-recoverable-metrics
  "Module-wide solvency metrics for a module/token:
   {:recoverable n :module-liabilities n :net-solvent n :all-positions-yield n}.

   net-solvent = recoverable - (sum of all position principals + realized-yield +
   deferred-yield). all-positions-yield = sum of unrealized-yield across active
   positions. These are shared by the per-position recoverable-liquidity cap and
   the module-level batch coordinator so both evaluate the same headroom."
  [world module-id token]
  (let [tok (normalize-token token)
        positions (:yield/positions world {})
        recoverable (resolve-recoverable-liquidity world module-id token)
        module-liabilities (reduce + 0
                                   (for [[_ p] positions
                                         :when (and (= (:module/id p) module-id)
                                                    (= (normalize-token (:token p)) tok))]
                                     (+ (max 0 (long (:principal p 0)))
                                        (max 0 (long (:realized-yield p 0)))
                                        (max 0 (long (:deferred-yield p 0))))))
        net-solvent (max 0 (- recoverable module-liabilities))
        all-positions-yield (reduce + 0
                                    (for [[_ p] positions
                                          :when (and (= (:module/id p) module-id)
                                                     (= (normalize-token (:token p)) tok)
                                                     (pos/active? p))]
                                      (max 0 (long (:unrealized-yield p 0)))))]
    {:recoverable recoverable
     :module-liabilities module-liabilities
     :net-solvent net-solvent
     :all-positions-yield all-positions-yield}))

(defn- resolve-negative-yield-floor
  "Get the configured negative yield floor for a module/token.
   Default: principal (position value cannot go below principal + floor-margin)."
  [world module-id token]
  (let [mid (if (keyword? module-id) module-id (keyword (str module-id)))
        tok (normalize-token token)
        risk (or (get-in world [:yield/risk mid tok])
                 (get-in world [:yield/risk mid (name tok)])
                 {})
        floor (get risk :negative-yield-floor)]
    (or floor 0)))

(defn- resolve-accrual-config
  "Resolve accrual-specific configuration for a module from world state."
  [world module-id]
  (let [mid (if (keyword? module-id) module-id (keyword (str module-id)))
        resolved (get-in world [:yield/module-aliases mid] mid)]
    (or (get-in world [:yield/accrual-config resolved])
        (get-in world [:yield/accrual-config mid])
        {:min-accrual-delta      default-min-accrual-delta
         :max-index-delta-ratio  default-max-index-delta-ratio
         :max-index-delta-policy default-max-index-delta-policy
         :stale-oracle-max-seconds default-stale-oracle-max-seconds
         :stale-oracle-floor-bps   default-stale-oracle-floor-bps})))

(defn make-decision-base
  "Construct the base decision map with identity fields."
  [world {:keys [module-id token workflow-id position-id now dt]}]
  (let [pos (when position-id
              (resolve-position world position-id module-id token))
        prev-index (resolve-index world module-id token)
        base-apy-bps (resolve-base-apy-bps world module-id token)
        config (resolve-accrual-config world module-id)]
    {:module-id module-id
     :token token
     :workflow-id workflow-id
     :position-id position-id
     :position pos
     :now (or now 0)
     :dt (or dt 0)
     :base-apy-bps base-apy-bps
     :effective-apy-bps base-apy-bps
     :previous-index (m/ratio prev-index)
     :attempted-index nil
     :final-index (m/ratio prev-index)
     :attempted-accrual-delta 0
     :final-accrual-delta 0
     :realized-yield-delta 0
     :unrealized-yield-delta 0
     :deferred-yield-delta 0
     :haircut-yield-delta 0
     :accrual-mode :normal
     :short-circuits []
     :config config
     :evidence {:schema-version schema-version
                :base-apy-bps base-apy-bps
                :previous-index (m/ratio prev-index)
                :dt dt
                :now (or now 0)}}))

(defn- apply-dust-threshold
  "Short circuit 1: If absolute accrual delta is below configured min-accrual-delta,
   do not create a balance update. Emits :dust-threshold.

   Sub-unit accrual is preserved as exact remainder and carried forward via
   the dust accumulator on the position.

   Does NOT override accrual-mode when already set to :module-frozen or :suspended
   by earlier short circuits (the freeze/unwinding checks take priority)."
  [decision]
  (let [min-delta (get-in decision [:config :min-accrual-delta] default-min-accrual-delta)
        attempted (Math/abs (long (:attempted-accrual-delta decision)))
        blocked? (#{:module-frozen :suspended} (:accrual-mode decision))]
    (if (and (not blocked?) (pos? min-delta) (< attempted min-delta))
      (-> decision
          (assoc :final-accrual-delta 0
                 :realized-yield-delta 0
                 :unrealized-yield-delta 0
                 :accrual-mode :dust-threshold)
          (update :short-circuits conj :dust-threshold)
          (update :evidence assoc
                  :dust-threshold-applied true
                  :min-accrual-delta min-delta
                  :attempted-delta attempted))
      decision)))

(defn- apply-module-frozen
  "Short circuit 2: If module status is in the configured freeze-on set,
   effective APY is zero. Emits :module-frozen-zero-accrual."
  [decision world]
  (let [module-id (:module-id decision)
        token (:token decision)
        now (:now decision)
        status (resolve-module-status world module-id token now)
        freeze-set (resolve-module-freeze-statuses world module-id)]
    (if (contains? freeze-set status)
      (-> decision
          (assoc :effective-apy-bps 0
                 :final-index (:previous-index decision)
                 :final-accrual-delta 0
                 :realized-yield-delta 0
                 :unrealized-yield-delta 0
                 :accrual-mode :module-frozen)
          (update :short-circuits conj :module-frozen-zero-accrual)
          (update :evidence assoc
                  :module-frozen true
                  :module-status status
                  :freeze-set (vec freeze-set)))
      decision)))

(defn- apply-position-unwinding
  "Short circuit 3: If position status is :unwinding, suspend accrual.
   Emits :position-unwinding-accrual-suspended."
  [decision]
  (let [pos (:position decision)]
    (if (and pos (= :unwinding (:status pos)))
      (-> decision
          (assoc :final-index (:previous-index decision)
                 :final-accrual-delta 0
                 :realized-yield-delta 0
                 :unrealized-yield-delta 0
                 :accrual-mode :suspended)
          (update :short-circuits conj :position-unwinding-accrual-suspended)
          (update :evidence assoc
                  :position-unwinding true
                  :position-status (:status pos)))
      decision)))

(defn- apply-stale-oracle-degradation
  "Short circuit 4: If oracle is stale, apply APY degradation.
   Positive APY decays toward floor; negative APY remains unchanged.
   Emits :stale-oracle-degraded-apy."
  [decision world]
  (let [module-id (:module-id decision)
        token (:token decision)
        now (:now decision)
        {:keys [stale? stale-seconds]} (resolve-oracle-staleness world module-id token now)]
    (if stale?
      (let [base (:base-apy-bps decision)
            config (:config decision)
            max-stale (get config :stale-oracle-max-seconds default-stale-oracle-max-seconds)
            floor-bps (get config :stale-oracle-floor-bps default-stale-oracle-floor-bps)
            effective (m/apy-degradation base stale-seconds max-stale floor-bps)]
        (-> decision
            (assoc :effective-apy-bps effective)
            (update :short-circuits conj :stale-oracle-degraded-apy)
            (update :evidence assoc
                    :oracle-stale true
                    :stale-seconds stale-seconds
                    :base-apy-bps base
                    :effective-apy-bps-after-degradation effective
                    :max-stale-seconds max-stale
                    :floor-apy-bps floor-bps)))
      decision)))

(defn- apply-max-index-delta-cap
  "Short circuit 5: If attempted index change exceeds configured max delta,
   cap or zero according to policy. Default policy is :cap.
   Emits :max-index-delta-capped or :max-index-delta-zeroed."
  [decision]
  (let [config (:config decision)
        max-ratio (get config :max-index-delta-ratio default-max-index-delta-ratio)
        policy (get config :max-index-delta-policy default-max-index-delta-policy)
        prev-idx (:previous-index decision)
        attempted-idx (:attempted-index decision)
        delta-ratio (if (zero? (double prev-idx))
                      0
                      (/ (Math/abs (double (- attempted-idx prev-idx)))
                         (double prev-idx)))]
    (if (and (> delta-ratio (double max-ratio)) (pos? (double max-ratio)))
      (case policy
        :cap
        (let [direction (if (>= (double attempted-idx) (double prev-idx)) 1 -1)
              capped-idx (* prev-idx (+ 1 (* direction (m/ratio max-ratio))))]
          (-> decision
              (assoc :attempted-index attempted-idx
                     :final-index capped-idx)
              (update :short-circuits conj :max-index-delta-capped)
              (update :evidence assoc
                      :max-index-delta-capped true
                      :max-delta-ratio (m/ratio max-ratio)
                      :attempted-index attempted-idx
                      :capped-index capped-idx
                      :delta-ratio-exceeded (m/ratio delta-ratio))))
        :zero
        (-> decision
            (assoc :attempted-index attempted-idx
                   :final-index prev-idx)
            (update :short-circuits conj :max-index-delta-zeroed)
            (update :evidence assoc
                    :max-index-delta-zeroed true
                    :max-delta-ratio (m/ratio max-ratio)
                    :attempted-index attempted-idx
                    :delta-ratio-exceeded (m/ratio delta-ratio)))
        decision)
      decision)))

(defn- resolve-loss-mode
  "Get the effective loss-mode for the position's risk config.
   Uses risk/effective-loss-mode which upgrades :none→:mark-to-market
   when :negative-yield is an active failure mode."
  [world module-id token]
  (let [mid (if (keyword? module-id) module-id (keyword (str module-id)))
        tok (normalize-token token)
        risk (or (get-in world [:yield/risk mid tok])
                 (get-in world [:yield/risk mid (name tok)])
                 {})]
    (risk-utils/effective-loss-mode risk)))

(defn- apply-negative-yield-floor
  "Short circuit 6: If negative accrual would push position value below
   configured floor, classify as :capital-event rather than ordinary accrual.
   Emits :negative-yield-floor-breached.
   When loss-mode is :none, clamps index to prevent any decrease."
  [decision world]
  (let [pos (:position decision)
        module-id (:module-id decision)
        token (:token decision)
        loss-mode (resolve-loss-mode world module-id token)]
    (if (and pos (:unrealized-yield-delta decision) (neg? (:unrealized-yield-delta decision)))
      (if (= loss-mode :none)
        ;; :none loss-mode: prevent any index decrease, zero out deltas
        (let [prev-idx (:previous-index decision)]
          (-> decision
              (assoc :final-index prev-idx
                     :accrual-mode :capital-event)
              (assoc :unrealized-yield-delta 0
                     :final-accrual-delta 0
                     :deferred-yield-delta 0
                     :haircut-yield-delta 0)
              (update :short-circuits conj :negative-yield-floor-breached)
              (update :evidence assoc
                      :negative-yield-floor-breached true
                      :loss-mode :none
                      :clamped-to-zero true)))
        (let [floor-bps (resolve-negative-yield-floor world module-id token)
              principal (:principal pos 0)
              current-unrealized (:unrealized-yield pos 0)
              projected-value (+ principal current-unrealized (:unrealized-yield-delta decision))
              floor-value (- principal floor-bps)]
          (if (< projected-value floor-value)
            (-> decision
                (assoc :accrual-mode :capital-event
                       :final-accrual-delta (:unrealized-yield-delta decision))
                (update :short-circuits conj :negative-yield-floor-breached)
                (update :evidence assoc
                        :negative-yield-floor-breached true
                        :projected-value projected-value
                        :floor-value floor-value
                        :floor-bps floor-bps
                        :principal principal
                        :current-unrealized current-unrealized))
            decision)))
      decision)))

(defn- apply-recoverable-liquidity-cap
  "Short circuit 7: If accrued yield would exceed recoverable module assets
   / configured cap, classify excess as :unrealized-yield rather than realized.
   Emits :recoverable-liquidity-cap.

   Computes net module solvency: total-held - (sum of all position principals +
   realized-yield + deferred-yield). Only yield accrual beyond net-solvent
   liquidity is capped.

   Optimization: Uses pre-calculated `total-unrealized-yield` if provided in options."
  [decision world opts]
  (let [module-id (:module-id decision)
        token (:token decision)
        pos (:position decision)]
    (if (and pos (:unrealized-yield-delta decision) (pos? (:unrealized-yield-delta decision)))
      (let [{:keys [recoverable module-liabilities net-solvent] :as metrics}
            (module-recoverable-metrics world module-id token)
            ;; Optimized path: use provided module-wide total if available
            all-positions-yield (or (:total-unrealized-yield opts)
                                    (:all-positions-yield metrics))
            ;; Projected module-wide unrealized after this position's accrual.  The
            ;; per-position view is a first-pass check; the module-level coordinator
            ;; (see coordinate-recoverable-liquidity-cap) enforces the shared headroom
            ;; across a batch so positions do not independently spend the same
            ;; net-solvent space.
            projected-total (+ all-positions-yield (:unrealized-yield-delta decision))]
        (if (and (pos? net-solvent) (> projected-total net-solvent))
          (let [unrealized-excess (max 0 (- projected-total net-solvent))
                realizable (max 0 (- (:unrealized-yield-delta decision) unrealized-excess))]
            (-> decision
                (update :unrealized-yield-delta #(min % realizable))
                (update :deferred-yield-delta + unrealized-excess)
                (update :short-circuits conj :recoverable-liquidity-cap)
                (update :evidence assoc
                        :recoverable-liquidity-cap-applied true
                        :recoverable-assets recoverable
                        :module-liabilities module-liabilities
                        :net-solvent net-solvent
                        :all-positions-yield all-positions-yield
                        :available-for-accrual net-solvent
                        :realizable-delta realizable
                        :unrealized-excess unrealized-excess)))
          decision))
      decision)))

(defn coordinate-recoverable-liquidity-cap
  "Enforce the recoverable-liquidity cap at module granularity across a batch of
   decisions that were all computed against the same pre-accrual snapshot.

   The per-position cap in `apply-recoverable-liquidity-cap` evaluates each
   position against the module-wide net-solvent independently. In a batch
   (module `accrue` computes every active position's decision from the same
   world), each position would otherwise claim the full net-solvent headroom and
   the module would over-realize beyond its recoverable assets. This coordinator
   recomputes the module-wide projected unrealized total and, when it exceeds
   net-solvent, scales each position's realized delta down proportionally,
   moving the excess to deferred.

   Returns the batch of decisions with :unrealized-yield-delta /
   :deferred-yield-delta adjusted and :recoverable-liquidity-cap short-circuit
   recorded when the module-wide cap was exceeded."
  [world module-id token decisions]
  (let [{:keys [net-solvent all-positions-yield]}
        (module-recoverable-metrics world module-id token)
        deltas (mapv #(max 0 (long (:unrealized-yield-delta % 0))) decisions)
        total-delta (reduce + 0 deltas)
        projected-total (+ all-positions-yield total-delta)
        excess (max 0 (- projected-total net-solvent))]
    (if (and (pos? net-solvent) (pos? excess) (pos? total-delta))
      (let [scale (/ (max 0 (- total-delta excess)) total-delta)]
        (mapv (fn [decision delta]
                (if (pos? delta)
                  (let [realizable (long (* delta scale))
                        deferred-excess (- delta realizable)]
                    (if (pos? deferred-excess)
                      (-> decision
                          (update :unrealized-yield-delta (constantly realizable))
                          (update :deferred-yield-delta + deferred-excess)
                          (update :short-circuits conj :recoverable-liquidity-cap)
                          (update :evidence assoc
                                  :recoverable-liquidity-cap-batch-applied true
                                  :net-solvent net-solvent
                                  :all-positions-yield all-positions-yield
                                  :projected-total projected-total
                                  :module-excess excess))
                      decision))
                  decision))
              decisions deltas))
      decisions)))

(defn- floor-ratio
  "Floor an exact ratio to the nearest integer toward negative infinity.
   Unlike `long` (which truncates toward zero), floor preserves the full value
   of a negative accrual so fractional losses are never silently dropped."
  [x]
  (let [r (m/ratio x)
        n (if (ratio? r) (.numerator ^clojure.lang.Ratio r) (biginteger r))
        d (if (ratio? r) (.denominator ^clojure.lang.Ratio r) 1)
        q (quot n d)
        rem (rem n d)]
    (if (and (neg? n) (not (zero? rem))) (dec q) q)))

(defn- compute-final-deltas
  "Given the decision with final-index set (after all index-modifying short
   circuits), compute accrual deltas from position state using exact arithmetic
   with dust accumulation. This is the single point where deltas are computed.

   Positive deltas are quantized with dust carry-forward.
   Negative deltas pass through as-is (full loss recognized immediately), floored
   toward negative infinity so sub-unit losses are not truncated away."
  [decision]
  (let [pos (:position decision)]
    (if pos
      (let [shares (m/ratio (:shares pos 0))
            prev-index (:previous-index decision)
            final-index (:final-index decision)
            prev-value-exact (m/current-value-exact shares prev-index)
            final-value-exact (m/current-value-exact shares final-index)
            delta-exact (- final-value-exact prev-value-exact)
            prior-dust (m/ratio (or (:accrual-dust-remainder pos) 0))
            ;; Positive delta: quantize and carry forward sub-unit dust.
            ;; Negative delta: pass through unquantized so losses are recognized.
            {:keys [units carry]}
            (if (pos? delta-exact)
              (m/quantize-with-carry delta-exact prior-dust)
              {:units (floor-ratio delta-exact) :carry 0})]
        (-> decision
            (assoc :attempted-accrual-delta units
                   :final-accrual-delta units
                   :realized-yield-delta 0
                   :unrealized-yield-delta units
                   :deferred-yield-delta 0
                   :haircut-yield-delta 0
                   :accrual-dust-carry carry
                   :exact-accrual-delta delta-exact
                   :exact-prev-value prev-value-exact
                   :exact-final-value final-value-exact)
            (assoc-in [:evidence :shares] (m/ratio->json shares))
            (assoc-in [:evidence :delta-exact] (m/ratio->json delta-exact))
            (assoc-in [:evidence :dust-carry-prior] (m/ratio->json prior-dust))
            (assoc-in [:evidence :final-units] units)
            (assoc-in [:evidence :dust-carry-after] (m/ratio->json (if (pos? delta-exact) carry 0)))))
      (-> decision
          (assoc :attempted-accrual-delta 0
                 :final-accrual-delta 0)))))

(defn accrual-decision
  "Compute a complete yield accrual decision for a position.

   Args:
     world  - the simulation world state
     opts   - {:keys [module-id token workflow-id position-id now dt total-unrealized-yield]}

   Returns a decision map with all fields.

   Pipeline:
   1. Build base decision with identity fields
   2. Short circuit: module frozen → zero APY
   3. Short circuit: position unwinding → suspend
   4. Short circuit: stale oracle → degrade APY
   5. Compute attempted index from effective APY
   6. Short circuit: max index delta → cap/zero
   7. Compute final accrual deltas (single quantized computation)
   8. Short circuit: dust threshold → zero deltas
   9. Short circuit: negative yield floor → capital event
   10. Short circuit: recoverable liquidity cap → split to deferred"
  [world {:keys [module-id token workflow-id position-id now dt] :as opts}]
  (let [base (make-decision-base world {:module-id module-id
                                        :token token
                                        :workflow-id workflow-id
                                        :position-id position-id
                                        :now now
                                        :dt dt})
        d1  (apply-module-frozen base world)
        d2  (apply-position-unwinding d1)
        d3  (apply-stale-oracle-degradation d2 world)
        prev-index (:previous-index d3)
        effective-apy (:effective-apy-bps d3)
        accrual-blocked? (or (= :module-frozen (:accrual-mode d3))
                             (= :suspended (:accrual-mode d3)))
        d4  (if accrual-blocked?
              (assoc d3 :attempted-index prev-index :final-index prev-index)
              (let [attempted-idx (m/next-index prev-index effective-apy dt)]
                (assoc d3 :attempted-index attempted-idx :final-index attempted-idx)))
        d5  (if accrual-blocked?
              d4
              (apply-max-index-delta-cap d4))
        d6  (compute-final-deltas d5)
        d7  (apply-dust-threshold d6)
        d8  (if accrual-blocked?
              d7
              (apply-negative-yield-floor d7 world))
        d9  (if accrual-blocked?
              d8
              (apply-recoverable-liquidity-cap d8 world opts))]
    (assoc d9 :evidence (assoc (:evidence d9)
                               :effective-apy-bps (:effective-apy-bps d9)
                               :previous-index (m/ratio->json (:previous-index d9))
                               :attempted-index (m/ratio->json (:attempted-index d9))
                               :final-index (m/ratio->json (:final-index d9))
                               :short-circuits (:short-circuits d9)
                               :accrual-mode (:accrual-mode d9)))))

(defn apply-accrual-decision
  "Apply a yield accrual decision to the world state.

   Mutates:
   - :yield/indices for the module/token (sets to final-index)
   - The position under :yield/positions (updates current-index, current-value,
     unrealized-yield, accrual-dust-remainder, last-accrual-time/index, flags)
   - :total-yield-generated for the token (cumulative gross yield)

   This generic yield-layer function intentionally does not mutate :total-held.
   A protocol integration must recognize the resulting position delta through
   its canonical custody ledger (Sew uses lifecycle/accrue-yield and
   accounting/add-held or sub-held).

   Returns the updated world. Pure — returns a new map without side effects."
  [world decision]
  (let [module-id (:module-id decision)
        token (:token decision)
        pos (:position decision)
        tok (normalize-token token)
        mid (if (keyword? module-id) module-id (keyword (str module-id)))
        final-index (:final-index decision)
        short-circuits (:short-circuits decision)
        world' (-> world
                   (assoc-in [:yield/indices mid tok] final-index)
                   (assoc-in [:yield/indices mid (name tok)] final-index))]
    (if pos
      (let [owner-id (:owner/id pos)
            yield-delta (:final-accrual-delta decision)
            unrealized-delta (:unrealized-yield-delta decision)
            deferred-delta (:deferred-yield-delta decision 0)
            haircut-delta (:haircut-yield-delta decision 0)
            dust-carry (:accrual-dust-carry decision 0)
            now (:now decision)
            updated-pos (-> pos
                            (pos/normalize-position)
                            (assoc :current-index final-index)
                            (assoc :current-value (long (+ (:principal pos 0) (:unrealized-yield pos 0) yield-delta)))
                            (update :unrealized-yield + unrealized-delta)
                            (update :deferred-yield + deferred-delta)
                            (update :haircut-yield + haircut-delta)
                            (assoc :accrual-dust-remainder dust-carry)
                            (assoc :last-accrual-time now)
                            (assoc :last-accrual-index final-index)
                            (cond-> (contains? (set short-circuits) :oracle-stale-degraded-apy)
                              (assoc :oracle-stale-affected? true))
                            (cond-> (contains? (set short-circuits) :capital-event)
                              (assoc :capital-event-affected? true))
                            (cond-> (contains? (set short-circuits) :recoverable-liquidity-cap)
                              (assoc :shortfall-affected? true)))
            pos-key (or (:position/id pos) owner-id)
            annotated-pos (loss/annotate-accrual-loss world' updated-pos final-index)]
        (-> world'
            (assoc-in [:yield/positions owner-id] annotated-pos)
            (update-in [:total-yield-generated tok] (fnil + 0) yield-delta)))
      world')))

(defn apply-accrual-decision-with-attribution
  "Apply a yield accrual decision to world state, wrapping the mutation in
   `with-attribution` so that downstream logging, invariant checks, and risk
   monitoring can see the decision's evidence without carrying it explicitly
   through every function signature.

   Attribution context keys:
     :accrual/accrual-mode     — the accrual-mode from the decision
     :accrual/short-circuits   — the short-circuits vector
     :accrual/yield-delta      — final-accrual-delta
     :accrual/deferred-delta   — deferred-yield-delta
     :accrual/module-id        — module-id
     :accrual/token            — token
     :accrual/position-id      — position-id
     :accrual/previous-index   — previous index (JSON-safe)
     :accrual/final-index      — final index (JSON-safe)"
  ([world decision] (apply-accrual-decision-with-attribution world decision nil))
  ([world decision attributed-state]
   (let [ctx {:accrual/accrual-mode (:accrual-mode decision)
              :accrual/short-circuits (vec (:short-circuits decision))
              :accrual/yield-delta (:final-accrual-delta decision)
              :accrual/deferred-delta (:deferred-yield-delta decision 0)
              :accrual/module-id (:module-id decision)
              :accrual/token (:token decision)
              :accrual/position-id (:position-id decision)
              :accrual/now (:now decision)
              :accrual/previous-index (m/ratio->json (:previous-index decision))
              :accrual/final-index (m/ratio->json (:final-index decision))}
         explicit-attr (when (instance? resolver_sim.util.attribution.AttributedState attributed-state)
                         (:attribution attributed-state))
         final-ctx (merge explicit-attr ctx)]
     (attr/with-attribution final-ctx
       (let [world' (apply-accrual-decision world decision)]
         (evidence/capture-event-evidence!
          :yield-accrual
          {:accrual/before (select-keys world [:total-held :resolver-stakes :yield-state])}
          {:accrual/after  (select-keys world' [:total-held :resolver-stakes :yield-state])}
          {:accrual/decision (dissoc decision :world)}
          nil
          {:world-before world
           :world-after world'})
         (risk/capture-if-risk-event)
         world')))))
