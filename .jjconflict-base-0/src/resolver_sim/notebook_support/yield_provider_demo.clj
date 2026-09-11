^{:nextjournal.clerk/visibility {:code :show :result :show}}
(ns resolver-sim.notebook-support.yield-provider-demo
  (:require [nextjournal.clerk :as clerk]
            [resolver-sim.yield.modules.liquid-lending :as liquid]
            [resolver-sim.yield.registry :as yreg]
            [resolver-sim.yield.presets :as presets]
            [resolver-sim.notebook-support.workbench-v2-styles :as wb]
            [resolver-sim.notebook-support.nav :as nav]))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html (nav/top-nav-bar "notebooks/yield_provider_demo.clj"))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 (wb/workbench-shell
  [:div
   [:div.wb-hero
    [:h1 "Yield Provider Demo"]
    [:p "Deposit 1,000 USDC, accrue for 12 months, inspect each preset."]]

   [:div.hero-strip
    (wb/metric-panel "Deposit" "1,000 USDC")
    (wb/metric-panel "Duration" "12 mo")
    (wb/metric-panel "Accruals" "12x")
    (wb/metric-panel "Presets" "5")]

   [:div.grid-layout
    (for [{:keys [preset-id label tag color desc]}
          [{:preset-id :yield.preset/aave-baseline
            :label "Baseline (5% APY)" :tag "NORMAL" :color "#03DAC6"
            :desc "Standard Aave v3. Position grows at 5% per year."}
           {:preset-id :yield.preset/shortfall-partial
            :label "Partial Liquidity" :tag "SHORTFALL" :color "#38bdf8"
            :desc "Only 50% of yield available immediately. Rest deferred."}
           {:preset-id :yield.preset/negative-yield-mild
            :label "Negative Yield" :tag "LOSS" :color "#fbbf24"
            :desc "APY = -1%. Principal shrinks (mark-to-market loss)."}
           {:preset-id :yield.preset/oracle-stale-aave
            :label "Stale Oracle" :tag "STALE" :color "#f87171"
            :desc "Oracle freezes at first accrual. Rate ignores updates."}
           {:preset-id :yield.preset/withdrawal-queue-aave
            :label "Withdrawal Queue" :tag "QUEUED" :color "#a78bfa"
            :desc "Withdrawals enter a queue. Claim later."}]]

      (let [principal 1000
            mid :yield.provider/liquid-lending
            module {:module/id mid :module/type mid
                    :module/capabilities #{:deposit :withdraw :accrue :emergency-unwind :claim-deferred}
                    :accounting/type :shares}
            world0 (-> (yreg/init-yield-modules {:yield/module-aliases {:aave-v3 mid}})
                       (presets/apply-preset preset-id))
            w1 (liquid/deposit world0 module {:owner/id "user1" :amount principal :token :USDC})
            months (reduce (fn [acc i]
                             (let [w (liquid/accrue (get-in (last acc) [:world]) module
                                                    {:token :USDC :dt 2592000})
                                   idx (get-in w [:yield/indices mid :USDC] 1.0)]
                               (conj acc {:month i :index idx :world w})))
                           [{:month 0 :index 1.0 :world w1}]
                           (range 1 13))
            final (last months)
            pos (get-in final [:world :yield/positions "user1"])
            apy (get-in world0 [:yield/rates mid :USDC] 0.0)
            end-idx (double (get-in final [:world :yield/indices mid :USDC] 1.0))
            idx-delta (- end-idx 1.0)]
        [:div.card {:style {:grid-column "span 4"}}
         [:div.card-title [:span {:style {:color color}} (str tag " . " label)]]
         [:div {:style {:color "#94a3b8" :fontSize "0.82em" :lineHeight "1.6"}} desc]
         [:div {:style {:display "flex" :flexWrap "wrap" :gap "8px" :marginTop "16px"}}
          [:div.metric-chip [:strong "APY"] (str (format "%.1f" (* apy 100)) "%")]
          [:div.metric-chip [:strong "Index D"] (format "%+.4f" idx-delta)]
          [:div.metric-chip [:strong "Final Index"] (format "%.4f" end-idx)]]
         [:div {:style {:marginTop "12px" :background "#020617" :padding "12px"
                        :borderRadius "4px" :border "1px solid #004D59"
                        :fontSize "0.78em" :fontFamily "monospace" :lineHeight "1.7"}}
          (str "Status: " (name (:status pos)) " | Unrealized: "
               (format "%+d" (long (:unrealized-yield pos 0))) " USDC")]]))

    [:div {:style {:marginTop "32px" :padding "16px" :borderTop "1px solid #004D59"
                   :fontSize "0.75em" :color "#64748b"}}
     "All presets use liquid-lending with Aave v3 profile. "
     "Simulation: deposit 1,000 USDC, 12 monthly accruals, inspect position."]]]))