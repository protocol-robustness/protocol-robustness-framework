(ns resolver-sim.yield.accounting
  "Standardized accounting substrate for yield-bearing assets.

   Provides reusable arithmetic and reconciliation mechanics for yield-related
   balances and accrual transformations.

   This substrate is designed to be portable across different protocol simulations."
  (:require [resolver-sim.yield.risk :as risk]
            [resolver-sim.yield.exact-math :as math]))

(def ^:private default-asset-decimals 18)

(def liquidity-modes
  "Liquidity-mode keywords that block new deposits."
  #{:shortfall :frozen :paused})

(defn- normalize-token [token]
  (cond
    (keyword? token) token
    (string? token)  (keyword token)
    :else            token))

(defn- risk-map [world module-id token]
  (let [tok (normalize-token token)]
    (or (get-in world [:yield/risk module-id tok])
        (get-in world [:yield/risk module-id (name tok)])
        {})))

(defn token-decimals
  "Resolve token decimals from world metadata.
   Falls back to 18 when unspecified."
  [world token]
  (let [tok (normalize-token token)]
    (long (or (get-in world [:token/decimals tok])
              (get-in world [:token/decimals (name tok)])
              (get-in world [:yield/token-decimals tok])
              (get-in world [:yield/token-decimals (name tok)])
              default-asset-decimals))))

(defn floor-to-asset-decimals
  "Floor numeric amount to token precision (base units)."
  [amount _decimals]
  (first (math/quantize-base-units amount)))

(defn floor-to-asset-decimals-signed
  "Floor numeric amount to token precision while preserving sign."
  [amount _decimals]
  (if (neg? amount)
    (- (first (math/quantize-base-units (abs amount))))
    (first (math/quantize-base-units amount))))

(defn position-current-value
  "Redeemable position value in underlying token units.

   `current-share-price` is the generic scalar multiplier stored in world paths
   as `:yield/indices` — it may represent an Aave liquidity index, an ERC-4626
   share price, or another module exchange rate. Semantics are module-specific;
   the math is always `shares × price`.

   Shares are minted at deposit as `principal / entry-index` (see liquid-lending
   deposit); `entry-index` is not re-applied here."
  [position current-share-price]
  (* (:shares position 0) (double current-share-price)))

(defn- require-world-for-yield-update!
  [world position current-index]
  (when (nil? world)
    (throw (ex-info "world is required for yield risk/loss-mode evaluation"
                    {:fn 'update-position-yield
                     :position position
                     :current-index current-index}))))

(defn update-position-yield
  "Update unrealized yield from shares and the current share price / index.

   Invariant (share-price model, Model A):
     shares            = principal / entry-index   (at deposit)
     current-value     = shares × current-share-price
     unrealized-yield  = current-value − principal (floored; signed under :mark-to-market)

   The `current-index` argument is the current share price / exchange rate /
   liquidity index for this module — one scalar multiplier, module-specific name.

   `world` is required so loss-mode and token decimals resolve correctly."
  ([position current-index]
   (throw (ex-info "world is required"
                   {:fn 'update-position-yield
                    :position position
                    :current-index current-index})))
  ([world position current-index]
   (require-world-for-yield-update! world position current-index)
   (let [principal           (:principal position 0)
         token               (:token position)
         module-id           (:module/id position)
         decimals            (token-decimals world token)
         risk                (risk-map world module-id token)
         loss-mode           (risk/effective-loss-mode risk)
         current-share-price (double current-index)
         current-value       (long (Math/floor (position-current-value position current-share-price)))
         pnl                 (- current-value principal)
         unrealized          (if (= loss-mode :mark-to-market)
                               (floor-to-asset-decimals-signed pnl decimals)
                               (floor-to-asset-decimals pnl decimals))]
     (assoc position
            :current-index current-index
            :current-value current-value
            :unrealized-yield unrealized))))

(defn realize-yield
  "Move unrealized yield to realized-yield. Usually called during crystallization."
  ([position]
   (realize-yield nil position))
  ([_world position]
   (let [unrealized (:unrealized-yield position 0)]
     (-> position
         (update :realized-yield + unrealized)
         (assoc :unrealized-yield 0)))))

(defn- resolve-available-ratios
  "Return [yield-ratio principal-ratio] from risk config.
   Falls back to :available-ratio, then to 1.0."
  [risk]
  (let [sf (:shortfall risk)
        avail (double (or (:available-ratio sf) 1.0))
        yield-r (double (or (:yield-available-ratio sf) avail))
        princ-r (double (or (:principal-available-ratio sf) avail))]
    [yield-r princ-r]))

(defn apply-liquidity-stress
  "Calculates potential shortfall and haircuts based on world risk parameters.

   When :principal is provided and failure-modes include :partial-liquidity,
   separate :yield-available-ratio and :principal-available-ratio from the
   shortfall config are used.  Falls back to :available-ratio when split
   ratios are not set."
  [world module-id token amount & {:keys [principal]}]
  (let [risk (risk-map world module-id token)
        mode (risk/effective-liquidity-mode risk)
        failure-modes (risk/normalize-failure-modes (:failure-modes risk))
        [yield-r princ-r] (resolve-available-ratios risk)]
    (case mode
      :available {:fulfilled amount :shortfall nil}
      :shortfall (if (and principal (contains? failure-modes :partial-liquidity))
                   (let [yield-portion (max 0 (- amount principal))
                         princ-portion (min amount principal)
                         yf (long (Math/floor (* yield-portion yield-r)))
                         pf (long (Math/floor (* princ-portion princ-r)))]
                     {:fulfilled (+ yf pf)
                      :shortfall {:reason :liquidity-shortfall
                                  :basis-amount amount
                                  :available-ratio yield-r
                                  :yield-available-ratio yield-r
                                  :principal-available-ratio princ-r
                                  :fulfilled-amount (+ yf pf)
                                  :deferred-amount (- amount (+ yf pf))
                                  :haircut-amount 0
                                  :shortfall-kind :partial-liquidity}})
                   (let [fulfilled (long (Math/floor (* amount yield-r)))
                         deferred (- amount fulfilled)]
                     {:fulfilled fulfilled
                      :shortfall {:reason :liquidity-shortfall
                                  :basis-amount amount
                                  :available-ratio yield-r
                                  :fulfilled-amount fulfilled
                                  :deferred-amount deferred
                                  :haircut-amount 0}}))
      :haircut   (let [ratio (double (get-in risk [:haircut :loss-ratio] 0.1))
                       loss  (long (Math/floor (* amount ratio)))
                       fulfilled (- amount loss)]
                   {:fulfilled fulfilled
                    :shortfall {:reason :permanent-loss
                                :basis-amount amount
                                :fulfilled-amount fulfilled
                                :deferred-amount 0
                                :haircut-amount loss}})
      {:fulfilled 0 :shortfall {:reason mode
                                :basis-amount amount
                                :fulfilled-amount 0
                                :deferred-amount amount
                                :haircut-amount 0}})))

(defn partial-yield-shortfall?
  "True when shortfall stress applied only to the yield leg (partial-liquidity).

   Identified by basis-amount strictly below position principal."
  [position shortfall]
  (and position shortfall
       (let [basis (long (or (:basis-amount shortfall) 0))
             principal (long (or (:principal position) 0))]
         (and (pos? principal) (< basis principal)))))

(defn apply-liquidity-stress-for-withdraw
  "Apply liquidity stress at withdrawal.

   Under :partial-liquidity, stress applies to the yield leg only; principal
   remains immediately available. Returns total :fulfilled (principal + liquid yield)."
  [world module-id token gross-amount principal]
  (let [risk (risk-map world module-id token)
        failure-modes (risk/normalize-failure-modes (:failure-modes risk))]
    (if (contains? failure-modes :partial-liquidity)
      (let [yield-portion (max 0 (- gross-amount principal))
            {:keys [fulfilled shortfall]}
            (apply-liquidity-stress world module-id token yield-portion :principal principal)]
        {:fulfilled (+ principal fulfilled)
         :shortfall shortfall})
      (apply-liquidity-stress world module-id token gross-amount :principal principal))))

(defn claim-deferred
  "Attempts to reclaim deferred funds from a position in :unwinding status.
   Uses :min-available-ratio-for-claim from risk config (default 1.0) instead of
   a hardcoded (= mode :available) check.  This enables partial recovery."
  [world module-id position]
  (let [token (:token position)
        risk  (risk-map world module-id token)
        available-ratio (double (get-in risk [:shortfall :available-ratio]
                                        (if (:shortfall risk) 0.0 1.0)))
        min-ratio (double (get-in risk [:min-available-ratio-for-claim] 1.0))
        shortfall (:shortfall position)]
    (if (and shortfall (>= available-ratio min-ratio))
      (let [reclaimed (:deferred-amount shortfall 0)]
        (-> position
            (assoc :status :withdrawn)
            (assoc :shortfall nil)
            (update :realized-yield + reclaimed)
            (assoc :reclaimed-amount reclaimed)))
      position)))