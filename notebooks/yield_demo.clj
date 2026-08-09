^{:nextjournal.clerk/toc true
  :nextjournal.clerk/visibility {:code :fold}
  :nextjournal.clerk/dark-mode true}
(ns notebooks.yield-demo
  "Yield Shortfall Handling Demo: Data-Driven General Yield.
   Targeted at non-expert readers evaluating the Protocol Robustness Framework."
  (:require [nextjournal.clerk :as clerk]
            [clojure.string :as str]
            [resolver-sim.notebook-support.yield-scenarios :as ys]
            [resolver-sim.protocols.yield :as yp]
            [resolver-sim.contract-model.replay :as replay]
            [resolver-sim.scenario.normalize :as normalize]))

;; # Yield Shortfall Handling Demo: Data-Driven General Yield
;;
;; This notebook demonstrates how the **Protocol Robustness Framework** handles yield shortfall situations using the **General Yield Module**.
;;
;; Unlike basic simulation models that use hardcoded "if/then" logic, our framework uses a **fully data-driven yield engine**. This means the behavior of the yield source—including its interest rates, liquidity constraints, and withdrawal policies—is injected via external data artifacts (like market schedules or risk monitors) rather than fixed in code.
;;
;; A yield shortfall occurs when a position has accrued yield, but the yield source cannot immediately return all expected funds. This can happen because of market stress, partial liquidity, delayed withdrawals, insolvency, integration failure, or risk controls.
;;
;; The goal of this demonstration is to make the outcome inspectable: **what was expected, what was available, what was paid now, what was deferred, and what evidence was recorded.**

;; ## 1. What to look for
;;
;; Use this guide to understand the key concepts presented in the scenarios below.

(clerk/table
 {"Concept" ["Principal" "Accrued yield" "Available liquidity" "Paid now" "Deferred" "Shortfall affected" "Evidence output"]
  "Meaning" ["Original amount deposited or protected"
             "Yield expected from the data-driven engine"
             "Amount the yield source can actually return (data-driven)"
             "Amount successfully returned or claimable immediately"
             "Amount not currently available, but tracked for later recovery"
             "Position or transfer touched by insufficient liquidity"
             "Structured trace / summary proving the outcome"]})

;; --- Mechanism Diagram ---

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 [:div {:style {:fontFamily "sans-serif" :marginTop "20px" :marginBottom "40px" :color "#1e293b"}}
  [:h3 "The Data-Driven Mechanism"]
  [:div {:style {:display "flex" :flexDirection "column" :alignItems "center" :gap "10px"}}
   [:div {:style {:border "1px solid #cbd5e1" :padding "8px 20px" :borderRadius "4px" :background "#f8fafc"}} "Deposit"]
   [:div "↓"]
   [:div {:style {:border "1px solid #cbd5e1" :padding "8px 20px" :borderRadius "4px" :background "#f8fafc"}} "Yield accrues from data-driven engine"]
   [:div "↓"]
   [:div {:style {:border "1px solid #cbd5e1" :padding "8px 20px" :borderRadius "4px" :background "#f8fafc"}} "Withdrawal / claim requested"]
   [:div "↓"]
   [:div {:style {:border "1px solid #94a3b8" :padding "8px 20px" :borderRadius "4px" :background "#e2e8f0" :fontWeight "bold"}} "Apply data-driven Policy (Liquidity check)"]
   [:div "↓"]
   [:div {:style {:display "grid" :gridTemplateColumns "1fr 1fr 1fr" :gap "15px" :width "100%"}}
    [:div {:style {:border "1px solid #10b981" :padding "10px" :borderRadius "4px" :background "#ecfdf5" :fontSize "12px"}}
     [:strong "Full liquidity"] [:br] "Pay full amount" [:br] "No shortfall flag" [:br] "No deferred balance"]
    [:div {:style {:border "1px solid #f59e0b" :padding "10px" :borderRadius "4px" :background "#fffbeb" :fontSize "12px"}}
     [:strong "Partial liquidity"] [:br] "Pay available amount" [:br] "Mark shortfall" [:br] "Track deferred amount"]
    [:div {:style {:border "1px solid #ef4444" :padding "10px" :borderRadius "4px" :background "#fff1f2" :fontSize "12px"}}
     [:strong "No liquidity"] [:br] "Pay nothing now" [:br] "Mark shortfall" [:br] "Track deferred amount"]]
   [:div "↓"]
   [:div {:style {:border "1px solid #3b82f6" :padding "8px 20px" :borderRadius "4px" :background "#eff6ff" :fontWeight "bold"}} "Evidence bundle records what happened"]]])

;; --- Data Transformation (View Model) ---

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn- get-user-position [world]
  (let [positions (or (get world :yield-positions) (get world :yield/positions))]
    (get positions "user-1")))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn build-view-model [scenario title reader-outcome]
  (let [normalized (normalize/normalize-scenario scenario)
        result (replay/replay-yield-scenario yp/protocol normalized)
        world (:world result)
        pos (get-user-position world)
        principal (double (get pos :principal 0))
        realized (double (get pos :realized-yield 0))
        shortfall (get pos :shortfall)
        deferred (double (get shortfall :deferred-amount 0))
        fulfilled (if shortfall
                    (double (get shortfall :fulfilled-amount 0))
                    (+ principal realized))
        status (get pos :status)
        shortfall? (or (pos? deferred) (pos? (double (get shortfall :haircut-amount 0))))]
    {:demo/title title
     :scenario/id (:scenario-id normalized)
     :yield/model "gen-yield"
     :yield/source "data-driven engine"
     :amount/principal principal
     :amount/expected-yield (+ realized deferred)
     :amount/expected-total (+ principal realized deferred)
     :liquidity/available (if shortfall? (get shortfall :available-ratio 0.0) 1.0)
     :outcome/paid-now fulfilled
     :outcome/deferred deferred
     :outcome/shortfall-affected shortfall?
     :outcome/final-loss (pos? (get shortfall :haircut-amount 0))
     :outcome/recovery-possible (pos? deferred)
     :evidence/trace (get-in result [:trace])
     :reader/outcome reader-outcome
     :raw/result result
     :raw/scenario scenario}))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn- format-usd [n]
  (if (number? n)
    (format "$%,.2f USDC" (double n))
    "n/a"))

;; --- Visualization Components ---

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn outcome-box [vm]
  (let [pass? (= :pass (get-in vm [:raw/result :outcome]))
        shortfall? (:outcome/shortfall-affected vm)
        color (cond (not shortfall?) "#10b981"
                    (not (:outcome/final-loss vm)) "#f59e0b"
                    :else "#ef4444")
        bg (cond (not shortfall?) "#ecfdf5"
                 (not (:outcome/final-loss vm)) "#fffbeb"
                 :else "#fff1f2")
        icon (cond (not shortfall?) "✅ "
                   (not (:outcome/final-loss vm)) "⚠️ "
                   :else "🚨 ")]
    (clerk/html
     [:div {:style {:padding "20px" :borderRadius "8px" :marginBottom "20px"
                    :background bg :border (str "2px solid " color)}}
      [:div {:style {:fontSize "18px" :fontWeight "bold" :color color}}
       (str icon (:demo/title vm))]
      [:div {:style {:fontSize "14px" :marginTop "8px" :color "#4b5563" :fontWeight "500"}}
       (:reader/outcome vm)]])))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn expected-vs-actual [vm]
  (let [expected (:amount/expected-total vm)
        actual (:outcome/paid-now vm)
        deferred (:outcome/deferred vm)]
    (clerk/html
     [:div {:style {:marginTop "20px" :marginBottom "20px" :fontFamily "monospace"}}
      [:div {:style {:display "flex" :alignItems "center" :marginBottom "5px"}}
       [:div {:style {:width "120px" :fontSize "12px"}} "Expected total"]
       [:div {:style {:background "#e2e8f0" :height "20px" :width "300px" :borderRadius "2px"}}
        [:div {:style {:background "#3b82f6" :height "100%" :width "100%" :borderRadius "2px"}}]]
       [:div {:style {:marginLeft "10px" :fontSize "12px" :fontWeight "bold"}} (format-usd expected)]]
      [:div {:style {:display "flex" :alignItems "center" :marginBottom "5px"}}
       [:div {:style {:width "120px" :fontSize "12px"}} "Paid now"]
       [:div {:style {:background "#e2e8f0" :height "20px" :width "300px" :borderRadius "2px"}}
         [:div {:style {:background "#10b981" :height "100%" :width (str (int (* 100 (if (pos? expected) (/ actual expected) 0))) "%") :borderRadius "2px"}}]]
       [:div {:style {:marginLeft "10px" :fontSize "12px" :fontWeight "bold"}} (format-usd actual)]]
      (when (pos? deferred)
        [:div {:style {:display "flex" :alignItems "center"}}
         [:div {:style {:width "120px" :fontSize "12px"}} "Deferred"]
         [:div {:style {:background "#e2e8f0" :height "20px" :width "300px" :borderRadius "2px"}}
           [:div {:style {:background "#f59e0b" :height "100%" :width (str (int (* 100 (if (pos? expected) (/ deferred expected) 0))) "%") :borderRadius "2px"}}]]
         [:div {:style {:marginLeft "10px" :fontSize "12px" :fontWeight "bold"}} (format-usd deferred)]])])))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn outcome-classification [vm]
  (clerk/html
   [:div {:style {:background "#f8fafc" :padding "15px" :borderRadius "6px" :border "1px solid #cbd5e1" :marginTop "20px"}}
    [:h4 {:style {:margin "0 0 10px 0" :fontSize "14px"}} "Outcome Classification"]
    [:div {:style {:display "grid" :gridTemplateColumns "160px 1fr" :gap "8px" :fontSize "13px"}}
     [:span {:style {:color "#64748b"}} "Shortfall affected:"] [:strong (if (:outcome/shortfall-affected vm) "YES" "NO")]
     [:span {:style {:color "#64748b"}} "User paid now:"] [:strong (format-usd (:outcome/paid-now vm))]
     [:span {:style {:color "#64748b"}} "Deferred amount:"] [:strong (format-usd (:outcome/deferred vm))]
     [:span {:style {:color "#64748b"}} "Final loss:"] [:strong (if (:outcome/final-loss vm) "YES" "NO")]
     [:span {:style {:color "#64748b"}} "Recovery path:"] [:strong (if (:outcome/recovery-possible vm) "Available if liquidity is restored" "n/a")]
     [:span {:style {:color "#64748b"}} "Evidence:"] [:span "Structured trace + metrics bundle"]]]))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn scenario-inputs [vm]
  (clerk/table
   (map (fn [[k v]] {"Input" k "Value" v})
        [["Token" "USDC"]
         ["Principal" (format-usd (:amount/principal vm))]
         ["Yield model" (:yield/model vm)]
         ["Source" (:yield/source vm)]
         ["Expected yield" (format-usd (:amount/expected-yield vm))]
          ["Liquidity ratio" (format "%.2f" (double (:liquidity/available vm)))]])))

;; --- 1. Scenario A — Healthy yield path ---
;;
;; **Purpose:** Establish the baseline.
;;
;; In the healthy case, expected yield and available liquidity match. The framework records a clean yield lifecycle using its data-driven engine.

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def vm-a
  (build-view-model
   {:scenario-id "scenario-a-healthy"
    :schema-version "1.0"
    :agents [{:id "user-1" :address "0xUser1"}]
    :yield-config {:modules {"aave-v3" {:tokens {"USDC" {:apy 0.05 :liquidity-mode "available"}}}}}
    :events [{:seq 0 :time 1000 :agent "user-1" :action "yield_deposit" :params {:token "USDC" :amount 10000}}
             {:seq 1 :time 31537000 :agent "user-1" :action "yield_accrue" :params {:token "USDC" :dt 31536000}}
             {:seq 2 :time 31538000 :agent "user-1" :action "yield_withdraw" :params {:token "USDC"}}] }
   "Healthy yield withdrawal"
   "In the healthy case, expected yield and available liquidity match. Full withdrawal succeeded."))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(outcome-box vm-a)
(expected-vs-actual vm-a)
(outcome-classification vm-a)

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 [:details {:style {:background "#f1f5f9" :padding "12px" :borderRadius "6px" :marginTop "10px"}}
  [:summary {:style {:cursor "pointer" :fontWeight "bold" :color "#475569"}} "Scenario Details (EDN & Trace)"]
  [:div {:style {:marginTop "12px"}}
   [:h5 "Scenario Inputs"]
   (scenario-inputs vm-a)
   [:h5 "Raw Scenario EDN"]
   (clerk/code (:raw/scenario vm-a))
   [:h5 "Full Event Trace"]
   (clerk/code (mapv #(select-keys % [:seq :time :action :result :params]) (:evidence/trace vm-a)))]])

(clerk/md "---")

;; --- 2. Scenario B — Yield shortfall ---
;;
;; **Purpose:** Demonstrate how the framework tracks unpaid obligations.
;;
;; A shortfall is a liquidity issue, not automatically a final loss. The framework does not collapse shortfall into a vague failure; it marks the position and tracks the deferred amount based on data-driven policy rules.

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def vm-b
  (build-view-model
   {:scenario-id "scenario-b-shortfall"
    :schema-version "1.0"
    :agents [{:id "user-1" :address "0xUser1"}]
    :yield-config {:modules {"aave-v3" {:tokens {"USDC" {:apy 0.05 :liquidity-mode "shortfall"
                                                        :shortfall {:available-ratio 0.0}}}}}}
    :events [{:seq 0 :time 1000 :agent "user-1" :action "yield_deposit" :params {:token "USDC" :amount 10000}}
             {:seq 1 :time 31537000 :agent "user-1" :action "yield_accrue" :params {:token "USDC" :dt 31536000}}
             {:seq 2 :time 31538000 :agent "user-1" :action "yield_withdraw" :params {:token "USDC"}}] }
   "Yield shortfall"
   "Shortfall: Full yield is deferred due to zero available liquidity. The unpaid amount is preserved as evidence and may be recoverable if liquidity returns."))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(outcome-box vm-b)
(expected-vs-actual vm-b)
(outcome-classification vm-b)

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 [:details {:style {:background "#f1f5f9" :padding "12px" :borderRadius "6px" :marginTop "10px"}}
  [:summary {:style {:cursor "pointer" :fontWeight "bold" :color "#475569"}} "Scenario Details (EDN & Trace)"]
  [:div {:style {:marginTop "12px"}}
   [:h5 "Scenario Inputs"]
   (scenario-inputs vm-b)
   [:h5 "Raw Scenario EDN"]
   (clerk/code (:raw/scenario vm-b))
   [:h5 "Full Event Trace"]
   (clerk/code (mapv #(select-keys % [:seq :time :action :result :params]) (:evidence/trace vm-b)))]])

(clerk/md "---")

;; --- 3. Scenario C — Partial fill ---
;;
;; **Purpose:** Show that the model can handle non-binary outcomes.
;;
;; What happens when only part of the requested amount can be filled? The framework records the immediate fill and preserves the remaining obligation, driven by the module's configurable withdrawal policy.

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def vm-c
  (build-view-model
   {:scenario-id "scenario-c-partial"
    :schema-version "1.0"
    :agents [{:id "user-1" :address "0xUser1"}]
    :yield-config {:modules {"aave-v3" {:tokens {"USDC" {:apy 0.05 :liquidity-mode "available"
                                                        :failure-modes ["partial-liquidity"]
                                                        :shortfall {:available-ratio 0.74}}}}}}
    :events [{:seq 0 :time 1000 :agent "user-1" :action "yield_deposit" :params {:token "USDC" :amount 10000}}
             {:seq 1 :time 31537000 :agent "user-1" :action "yield_accrue" :params {:token "USDC" :dt 31536000}}
             {:seq 2 :time 31538000 :agent "user-1" :action "yield_withdraw" :params {:token "USDC"}}] }
   "Partial-fill withdrawal"
   "Partial liquidity available: user receives available funds now; unpaid amount is tracked as deferred. The framework distinguishes between fully paid, partially paid, and blocked."))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(outcome-box vm-c)
(expected-vs-actual vm-c)
(outcome-classification vm-c)

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 [:details {:style {:background "#f1f5f9" :padding "12px" :borderRadius "6px" :marginTop "10px"}}
  [:summary {:style {:cursor "pointer" :fontWeight "bold" :color "#475569"}} "Scenario Details (EDN & Trace)"]
  [:div {:style {:marginTop "12px"}}
   [:h5 "Scenario Inputs"]
   (scenario-inputs vm-c)
   [:h5 "Raw Scenario EDN"]
   (clerk/code (:raw/scenario vm-c))
   [:h5 "Full Event Trace"]
   (clerk/code (mapv #(select-keys % [:seq :time :action :result :params]) (:evidence/trace vm-c)))]])

;; ## 2. Outcome Comparison
;;
;; The comparison below highlights the framework's high-fidelity tracking of economic states across different market conditions.

(clerk/table
 (map (fn [vm]
        {"Scenario" (:demo/title vm)
         "Expected total" (format-usd (:amount/expected-total vm))
         "Paid now" (format-usd (:outcome/paid-now vm))
         "Deferred" (format-usd (:outcome/deferred vm))
         "Shortfall affected" (if (:outcome/shortfall-affected vm) "Yes" "No")
         "Final loss" (if (:outcome/final-loss vm) "Yes" "No")})
      [vm-a vm-b vm-c]))

;; ## 3. Conclusion
;;
;; The framework generates structured evidence for how yield-bearing protocol mechanisms behave when expected liquidity is unavailable.
;;
;; **The core demo value:** The framework does not collapse shortfall into a vague failure. It classifies the outcome: paid now, deferred, shortfall-affected, recoverable, or final loss depending on the modeled rules.
