(ns resolver-sim.scenario.yield-classification
  "Yield-bearing scenario classification axes.

   Every scenario can be classified along four axes:
     :yield/enabled?         — mechanical fact (preset + module)
     :yield/risk-class       — determines invariant interpretation
     :scenario/categories    — set of tags for filtering/grouping
     :invariant/profile      — selects the set of applicable invariants

   See `docs/yield/YIELD_BEARING_INVARIANTS.md` for the full taxonomy.")

(defn yield-risk-class
  "Determine the :yield/risk-class for a loaded scenario.

   Inspects the yield-config module definitions to classify the risk
   profile.  When no yield is configured or no shortfall model is found,
   returns :principal-preserving."
  [scenario]
  (let [yc (:yield-config scenario {})
        modules (:modules yc {})]
    (if (seq modules)
      (let [token-configs (->> modules vals (mapcat :tokens) (map val))
            shortfall-model (some :shortfall-model token-configs)
            liquidity-schedule (some :liquidity-schedule token-configs)
            index-schedule (some :index-schedule token-configs)]
        (cond
          (and shortfall-model
               (= (keyword (:type shortfall-model)) :principal-loss)
               (false? (:recoverable shortfall-model true)))
          :principal-loss

          (and shortfall-model
               (= (keyword (:type shortfall-model)) :principal-loss))
          :recoverable-shortfall

          liquidity-schedule :liquidity-shortfall

          index-schedule :historical-index-replay

          :else :principal-preserving))
      :principal-preserving)))

(defn classify-yield-scenario
  "Return a classification map for a loaded scenario.

   The classification is used to select invariant profiles, interpret
   expected failures, and group evidence/artifacts.  It is pure metadata —
   it does not change protocol behavior."
  [scenario]
  (let [pp      (:protocol-params scenario {})
        preset  (or (get pp :yield-preset)
                    (get-in scenario [:events 0 :params :yield-preset]))
        mod-ref (or (get pp :yield-profile)
                    (get pp :yield-generation-module))
        enabled (and (some? preset)
                     (not= (keyword preset) :off)
                     (some? mod-ref))
        risk-class (yield-risk-class scenario)
        categories (cond-> #{}
                     enabled (conj :yield-bearing)
                     (= risk-class :principal-loss) (conj :principal-loss)
                     (= risk-class :historical-index-replay) (conj :historical-index-replay)
                     (= risk-class :liquidity-shortfall) (conj :liquidity-shortfall))]
    {:yield/enabled? enabled
     :yield/preset   (when enabled (keyword preset))
     :yield/module   (when enabled (keyword mod-ref))
     :yield/risk-class risk-class
     :scenario/categories (if (seq categories) categories #{})
     :invariant/profile (if enabled :sew/yield-bearing :sew/base)}))

(defn expected-invariant-results
  "Return expected invariant results for a classified scenario.

   Used to annotate golden reports with :expected? metadata so that
   invariant failures can be distinguished from protocol bugs."
  [classification]
  (let [risk (:yield/risk-class classification)]
    (case risk
      :principal-loss
      {:solvency          {:status :fail :expected? true  :reason :yield/principal-loss}
       :conservation-of-funds {:status :pass :expected? false}
       :token-tax-reconciliation {:status :pass :expected? false}
       :held-delta-accounted    {:status :pass :expected? false}}

      :historical-index-replay
      {:solvency          {:status :pass :expected? false}
       :conservation-of-funds {:status :pass :expected? false}
       :single-resolution-payout-consistent {:status :pass :expected? false}}

      ;; default: all pass
      {})))
