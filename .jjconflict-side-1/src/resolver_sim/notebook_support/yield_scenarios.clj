(ns resolver-sim.notebook-support.yield-scenarios
  "Load, replay, and project yield-provider scenarios for Clerk workbenches."
  (:require [resolver-sim.io.scenarios :as io-sc]
            [resolver-sim.scenario.normalize :as normalize]
            [resolver-sim.scenario.runner :as runner]
            [resolver-sim.scenario.suites :as suites]
            [resolver-sim.contract-model.replay :as replay]
            [resolver-sim.protocols.yield :as yp]))

(def demo-catalog
  "Human-facing labels for live demos (keyed by :scenario-id)."
  {"y01-deposit-accrue-positive"
   {:code "Y01" :headline "Baseline yield"
    :pitch "Deposit → accrue one year → position stays active with unrealized yield."
    :theme :baseline}

   "y02-negative-yield-mtm"
   {:code "Y02" :headline "Negative yield (MTM)"
    :pitch "Mark-to-market loss after enabling :negative-yield stress."
    :theme :stress}

   "y03-partial-liquidity-shortfall-affected"
   {:code "Y03" :headline "Shortfall-affected withdraw"
    :pitch "Partial liquidity: principal now, half of yield deferred; position unwinding."
    :theme :shortfall
    :shortfall-outcome :may-be-partially-deferred}

   "y05-shortfall-affected-recovery"
   {:code "Y05" :headline "Recovery path"
    :pitch "Shortfall on withdraw, restore liquidity, claim deferred — full realized yield."
    :theme :shortfall
    :shortfall-outcome :may-be-partially-deferred}

   "y04-liquidity-shortfall-withdraw"
   {:code "Y04" :headline "Pool shortfall mode"
    :pitch "Flip pool to :shortfall, then withdraw — gross yield largely deferred."
    :theme :shortfall}

   "y06-liquidity-shortage-deposit-blocked"
   {:code "Y06" :headline "Liquidity shortage"
    :pitch "Pool already in shortfall — new deposit rejected at the door."
    :theme :shortage}

   "y07-monthly-accrual-one-year"
   {:code "Y07" :headline "Monthly accrual horizon"
    :pitch "Twelve partitioned accrues — liquidity index climbs stepwise over one year."
    :theme :accrual}})

(def action-labels
  {"yield_deposit"        "Deposit"
   "yield_accrue"         "Accrue"
   "yield_withdraw"       "Withdraw"
   "set-yield-risk"       "Risk update"
   "yield_claim_deferred" "Claim deferred"})

(defn- action-name [event]
  (let [a (:action event)]
    (if (keyword? a) (name a) (str a))))

(defn- format-usd [n]
  (when (number? n)
    (let [v (long n)]
      (str (when (neg? v) "-") (format "%,d" (abs v)) " USDC"))))

(defn- theme-color [theme]
  (case theme
    :baseline "#03DAC6"
    :stress   "#fbbf24"
    :shortfall "#38bdf8"
    :shortage "#fb7185"
    "#7ADDDC"))

(defn load-provider-scenarios
  "Load all JSON scenarios from `:yield-provider-scenarios` suite."
  []
  (let [paths (suites/suite-paths :yield-provider-scenarios)]
    (mapv (fn [path]
            (-> path io-sc/load-scenario-file normalize/normalize-scenario
                (assoc :_path path)))
          paths)))

(defn run-provider-suite!
  "Replay every provider scenario; return runner summary + enriched entries."
  []
  (let [scenarios (load-provider-scenarios)
        entries   (mapv (fn [s]
                          (let [demo (get demo-catalog (:scenario-id s) {:code "?"})]
                            {:name     (str (:code demo) " · " (:scenario-id s))
                             :scenario s}))
                        scenarios)
        summary   (runner/run-collection
                   {:entries   entries
                    :replay-fn #(replay/replay-yield-scenario yp/protocol %)}
                   {:normalize? false :suite-id :yield-provider-scenarios})]
    (update summary :results
            (fn [xs]
              (mapv (fn [e]
                      (let [sid (:scenario-id (:scenario e))
                            demo (get demo-catalog sid {})]
                        (assoc e
                               :demo demo
                               :metrics (get-in e [:replay-result :metrics])
                               :path (:_path (:scenario e)))))
                    xs)))))

(defn flow-steps
  "Per-event demo step maps from replay trace."
  [entry]
  (let [trace (get-in entry [:replay-result :trace] [])]
    (mapv (fn [t]
            {:seq (:seq t)
             :label (get action-labels (name (:action t)) (name (:action t)))
             :result (name (:result t))
             :ok? (= :ok (:result t))})
          trace)))

(defn headline-metrics
  "Pick the 3–5 numbers that matter on a demo card."
  [entry]
  (let [m (:metrics entry {})
        picks [["Principal" :yield/position-principal]
               ["Realized" :yield/position-realized]
               ["Deferred" :yield/position-deferred]
               ["Reclaimed" :yield/position-reclaimed]
               ["Status" :yield/position-status]
               ["Loss reason" :yield/loss-reason]]]
    (into []
          (for [[label k] picks
                :let [v (get m k)]
                :when (and v (not= v 0) (not= v ""))]
            {:label label :value (if (string? v) v (format-usd v))}))))

(defn stress-count
  [summary]
  (->> (:results summary)
       (filter #(#{:shortfall :shortage :stress} (get-in % [:demo :theme])))
       count))

(defn pass-count
  [summary]
  (->> (:results summary) (filter :pass?) count))

(defn sort-entries-demo-order
  [entries]
  (sort-by (fn [e]
             (let [code (get-in e [:demo :code] "Z99")]
               (subs code 1 (count code)))) ; Y01 -> 01
           entries))

(defn scenario-card-hiccup
  [entry]
  (let [{:keys [pass? demo scenario metrics]} entry
        sid (:scenario-id scenario)
        tags (:threat-tags scenario [])
        steps (flow-steps entry)
        chips (headline-metrics entry)
        theme (or (:theme demo) :baseline)
        color (theme-color theme)]
    [:div {:class (str "scenario-card " (if pass? "pass" "fail"))}
     [:div {:style {:display "flex" :justifyContent "space-between" :alignItems "flex-start"
                    :marginBottom "10px"}}
      [:div
       [:div {:style {:fontSize "11px" :color color :fontWeight "800" :letterSpacing "0.08em"}}
        (:code demo)]
       [:div {:style {:fontSize "15px" :fontWeight "800" :color "#e2e8f0" :marginTop "4px"}}
        (:headline demo)]
       [:div {:style {:fontSize "11px" :color "#94a3b8" :marginTop "6px" :lineHeight "1.45"}}
        (or (:pitch demo) (:description scenario))]]
      [:span {:style {:fontSize "10px" :fontWeight "800" :padding "4px 10px" :borderRadius "4px"
                      :background (if pass? "#042f2e" "#3f1d1d")
                      :color (if pass? "#03DAC6" "#fca5a5")
                      :border (str "1px solid " (if pass? "#03DAC6" "#f87171"))}}
       (if pass? "PASS" "FAIL")]]
     [:div {:style {:marginBottom "10px"}}
      (for [t tags]
        [:span.tag-pill {:key (str t)} (if (keyword? t) (name t) (str t))])]
     [:div {:style {:margin "12px 0"}}
      (for [{:keys [label result ok?]} steps]
        [:span {:key label :class (str "flow-pill " (if ok? "ok" "rejected"))}
         (str label " · " result)])]
     (when (seq chips)
       [:div {:style {:marginTop "8px"}}
        (for [{:keys [label value]} chips]
          [:span.metric-chip {:key label}
           [:strong label] value])])]))

(defn detail-panel-hiccup
  [entry]
  (when entry
    (let [{:keys [scenario pass? path]} entry
          trace (get-in entry [:replay-result :trace] [])]
      [:div {:style {:fontSize "12px" :color "#cbd5e1"}}
       [:div {:style {:marginBottom "12px"}}
        [:strong "File: "] [:code {:style {:color "#7ADDDC"}} path]
        [:span {:style {:marginLeft "16px"}}
         [:strong "Outcome: "] (if pass? "pass" "fail")]]
       [:div.trace-block
        (for [t trace]
          [:div {:key (:seq t)
                 :style {:display "grid"
                         :gridTemplateColumns "48px 120px 80px 1fr"
                         :gap "12px"
                         :padding "4px 0"
                         :borderBottom "1px solid #020617"
                         :fontSize "11px"}}
           [:span {:style {:color "#64748b"}} (str (:seq t))]
           [:span {:style {:color "#FF9800"}} (get action-labels (name (:action t)) (name (:action t)))]
           [:span {:style {:color (if (= :ok (:result t)) "#03DAC6" "#fbbf24")}}
            (name (:result t))]
           [:span (pr-str (:params t))]])]])))

(defn gallery-hiccup
  [summary]
  (let [entries (sort-entries-demo-order (:results summary))
        n       (count entries)
        passed  (pass-count summary)
        stress  (stress-count summary)]
    [:div
     [:div.wb-hero
      [:h1 "Yield Provider Scenarios"]
      [:p "Live replay of the "
       [:strong {:style {:color "#e2e8f0"}} "yield-v1"]
       " adapter — deposit, accrue, withdraw, and liquidity stress without Sew escrow. "
       "Each card is one deterministic JSON scenario; numbers come from in-process replay."]]
     [:div.hero-strip
      [:div.metric-panel [:div.label "Scenarios"] [:div.value (str n)]]
      [:div.metric-panel [:div.label "Suite status"]
       [:div.value {:style {:color (if (= passed n) "#03DAC6" "#f87171")}}
        (str passed "/" n " PASS")]]
      [:div.metric-panel [:div.label "Stress cases"] [:div.value (str stress)]]
      [:div.metric-panel [:div.label "Protocol"] [:div.value "yield-v1"]]]
     [:div {:style {:fontSize "11px" :color "#64748b" :marginBottom "12px"}}
      "Reading order: Y01 baseline → Y07 long horizon → Y02–Y06 liquidity & shortfall paths."]
     [:div {:style {:display "flex" :gap "16px" :flexWrap "wrap" :fontSize "10px" :marginBottom "20px"}}
      [:span {:style {:color "#03DAC6"}} "■ Baseline"]
      [:span {:style {:color "#fbbf24"}} "■ Yield stress"]
      [:span {:style {:color "#38bdf8"}} "■ Shortfall-affected"]
      [:span {:style {:color "#fb7185"}} "■ Liquidity shortage"]]
     [:div.grid-layout
      (for [e entries]
        [:div {:key (:scenario-id (:scenario e))
               :style {:grid-column "span 4"}} (scenario-card-hiccup e)])]]))
