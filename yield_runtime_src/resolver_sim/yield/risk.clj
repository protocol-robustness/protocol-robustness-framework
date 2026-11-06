(ns resolver-sim.yield.risk
  "Composable yield risk configuration and market-shock application.

   Pure functions over world maps — no I/O.")

(defn normalize-failure-modes
  "Coerce failure mode collection to a keyword set."
  [failure-modes]
  (let [modes (or failure-modes #{})]
    (into #{} (map (fn [m] (if (keyword? m) m (keyword (name m)))) modes))))

(defn effective-liquidity-mode
  "Resolve withdrawal liquidity semantics.

   The :partial-liquidity failure mode implies :shortfall liquidity even when
   :liquidity-mode remains :available in config (ergonomic scenario DSL)."
  [risk]
  (let [mode (let [m (:liquidity-mode risk :available)]
               (if (keyword? m) m (keyword (name m))))
        failure-modes (normalize-failure-modes (:failure-modes risk))]
    (if (contains? failure-modes :partial-liquidity)
      :shortfall
      mode)))

(defn effective-loss-mode
  "Resolve accounting loss semantics for a token risk map.

   When :negative-yield is active and loss-mode was not explicitly set beyond
   :none, default to :mark-to-market so stress scenarios model principal drawdown."
  [risk]
  (let [loss-mode (let [m (:loss-mode risk :none)]
                    (if (keyword? m) m (keyword (name m))))
        failure-modes (normalize-failure-modes (:failure-modes risk))]
    (cond
      (= loss-mode :mark-to-market) :mark-to-market
      (and (= loss-mode :none)
           (contains? failure-modes :negative-yield)) :mark-to-market
      :else loss-mode)))

(defn- risk-path [module-id token]
  [:yield/risk module-id token])

(defn merge-failure-modes
  [world module-id token modes]
  (update-in world (conj (risk-path module-id token) :failure-modes)
             (fn [existing]
               (into (normalize-failure-modes existing) (normalize-failure-modes modes)))))

(defn apply-shock
  "Apply one market-shock descriptor to yield risk state.

   Supported :type values:
     :apy              — {:value -0.2}
     :liquidity-mode   — {:mode :shortfall}
     :loss-mode        — {:mode :mark-to-market}
     :failure-mode     — {:mode :negative-yield}  (adds to failure-modes set)
     :failure-modes    — {:modes [:negative-yield :deposit-fails]}
     :shortfall        — {:available-ratio 0.8 :reason :liquidity-shortfall}
     :haircut          — {:loss-ratio 0.1}"
  [world module-id token {:keys [type] :as shock}]
  (let [tok (if (keyword? token) token (keyword token))
        path (risk-path module-id tok)]
    (case (keyword type)
      :apy
      (assoc-in world [:yield/rates module-id tok] (double (:value shock)))

      :liquidity-mode
      (assoc-in world (conj path :liquidity-mode)
                (keyword (:mode shock)))

      :loss-mode
      (assoc-in world (conj path :loss-mode)
                (keyword (:mode shock)))

      :failure-mode
      (merge-failure-modes world module-id tok [(:mode shock)])

      :failure-modes
      (merge-failure-modes world module-id tok (:modes shock))

      :shortfall
      (update-in world path
                 (fn [risk]
                   (assoc risk :shortfall
                          (merge (:shortfall risk {})
                                 (select-keys shock [:available-ratio :reason])))))

      :haircut
      (update-in world path
                 (fn [risk]
                   (assoc risk :shortfall
                          (merge (:shortfall risk {})
                                 {:loss-ratio (double (:loss-ratio shock))}))))

      world)))

(defn apply-shocks
  "Apply an ordered vector of shock descriptors."
  [world module-id token shocks]
  (reduce (fn [w shock] (apply-shock w module-id token shock))
          world
          (or shocks [])))

(defn apply-market-shock
  "Entry point for scenario actions: shocks vector and/or legacy flat fields."
  [world module-id token params]
  (let [tok (if (keyword? token) token (keyword token))
        shocks (:shocks params)
        world' (if (seq shocks)
                 (apply-shocks world module-id tok shocks)
                 world)
        {:keys [liquidity-mode loss-mode failure-modes apy shortfall]} params]
    (-> world'
        (cond-> apy (assoc-in [:yield/rates module-id tok] (double apy))
                liquidity-mode (assoc-in [:yield/risk module-id tok :liquidity-mode] (keyword liquidity-mode))
                loss-mode (assoc-in [:yield/risk module-id tok :loss-mode] (keyword loss-mode))
                failure-modes (assoc-in [:yield/risk module-id tok :failure-modes] (normalize-failure-modes failure-modes))
                shortfall (assoc-in [:yield/risk module-id tok :shortfall] shortfall))
        (as-> w
              (if (and failure-modes (nil? loss-mode))
                (let [risk (get-in w [:yield/risk module-id tok] {})]
                  (assoc-in w [:yield/risk module-id tok :loss-mode]
                            (effective-loss-mode (assoc risk :failure-modes
                                                        (normalize-failure-modes failure-modes)))))
                w)))))
