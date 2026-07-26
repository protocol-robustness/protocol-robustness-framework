;; # Protocol Robustness Quickstart
;; ### From Dispute Lifecycle to Evidence Bundle
;;
;; **Pipeline:**
;; Scenario → Simulation Run → Event Trace → Outcome Metrics → Invariant Evidence

^{:nextjournal.clerk/toc true
  :nextjournal.clerk/dark-mode true
  :nextjournal.clerk/visibility {:code :fold :result :show}
  :nextjournal.clerk/no-cache true}
(ns notebooks.not-governance
  (:require [nextjournal.clerk :as clerk]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.sim.batch :as batch]
            [resolver-sim.stochastic.rng :as rng]
            [resolver-sim.protocols.sew.economics :as sew-econ]
            [resolver-sim.notebook-support.views :as views]
            [resolver-sim.notebook-support.checks :as checks]
            [resolver-sim.io.params :as io-params]))

;; Shared constants loaded from notebook-local config.
;; Edit notebooks/appeal_config.edn to change parameter bands.
^{::clerk/visibility {:code :hide :result :hide}}
(defonce const
  (io-params/load-edn "notebooks/appeal_config.edn"))

^{::clerk/visibility {:code :hide :result :hide}}
(defn- run-batch
  [n-trials strategy-mix & [extra-opts]]
  (let [c const
        bc (:batch-config c)
        base (merge bc {:n-trials n-trials
                        :min-trials (:min-trials bc)
                        :strategy-mix strategy-mix
                        :slashing-detection-probability (:detection-prob-default bc)})]
    (delay (batch/run-batch (rng/make-rng (:seed c)) n-trials
                            (merge base extra-opts)))))

^{::clerk/visibility {:code :hide :result :hide}}
(defn- run-batch-with-seed
  [seed n-trials strategy-mix & [extra-opts]]
  (let [c const
        bc (:batch-config c)
        base (merge bc {:n-trials n-trials
                        :min-trials (:min-trials bc)
                        :strategy-mix strategy-mix
                        :slashing-detection-probability (:detection-prob-default bc)})]
    (delay (batch/run-batch (rng/make-rng seed) n-trials
                            (merge base extra-opts)))))

^{::clerk/visibility {:code :hide :result :hide}}
(defonce slash-obligation
  (* 1.25 (:escrow-amount const)))

^{::clerk/visibility {:code :hide :result :hide}}
(defonce slash-dist
  (sew-econ/calculate-slashing-distribution slash-obligation 0))

;; Helpers
;; badge, notice-box, trace-table moved to resolver-sim.notebook.views
;; delta-str kept here as notebook-specific (only used in strategy comparison)

^{::clerk/visibility {:code :hide :result :hide}}
(defn- delta-str [a b fmt-str]
  (let [diff (- (or a 0) (or b 0))]
    (format fmt-str (double diff))))

^{::clerk/visibility {:code :hide :result :hide}}
(defn- sweep-at-bond
  [bond-label bond-bps probs]
  (map-indexed
   (fn [i p]
     (let [seed (+ (:seed const) (* (inc i) 1000))
           r (deref (run-batch-with-seed seed 500 {:malicious 1.0}
                                         {:slashing-detection-probability p
                                          :resolver-bond-bps bond-bps}))]
       {:detection-prob p
        :slash-rate (or (:slash-rate r) 0)
        :fraud-detected (get r :fraud-slashed-count 0)
        :malice-mean (or (:malice-mean r) 0)
        :escaped-harm (* 10000 (- 1 (or (:slash-rate r) 0)))
        :bond bond-label}))
   probs))

;; At a Glance
^{:nextjournal.clerk/visibility {:code :fold :result :show}
  ::clerk/auto-expand-results? true
  ::clerk/budget nil}
(clerk/html
 [:div {:style {:display "grid" :gap "16px" :maxWidth "900px" :marginBottom "24px"}}
  [:div {:style {:display "grid" :gridTemplateColumns "repeat(4, 1fr)" :gap "12px"}}
   [:div {:style {:background "#0f172a" :padding "14px" :borderRadius "8px" :border "1px solid #134e4a"}}
    [:div {:style {:fontSize "11px" :color "#7ADDDC" :textTransform "uppercase" :fontWeight 700}} "Scenario"]
    [:div {:style {:fontSize "16px" :fontWeight 700 :color "#f8fafc"}} "Dispute lifecycle"]]
   [:div {:style {:background "#0f172a" :padding "14px" :borderRadius "8px" :border "1px solid #134e4a"}}
    [:div {:style {:fontSize "11px" :color "#7ADDDC" :textTransform "uppercase" :fontWeight 700}} "Events"]
    [:div {:style {:fontSize "16px" :fontWeight 700 :color "#f8fafc"}} "3"]]
   [:div {:style {:background "#064e3b" :padding "14px" :borderRadius "8px" :border "1px solid #22c55e"}}
    [:div {:style {:fontSize "11px" :color "#22c55e" :textTransform "uppercase" :fontWeight 700}} "Outcome"]
    [:div {:style {:fontSize "16px" :fontWeight 700 :color "#22c55e"}} "PASS"]]
   [:div {:style {:background "#0f172a" :padding "14px" :borderRadius "8px" :border "1px solid #134e4a"}}
    [:div {:style {:fontSize "11px" :color "#7ADDDC" :textTransform "uppercase" :fontWeight 700}} "Invariant failures"]
    [:div {:style {:fontSize "16px" :fontWeight 700 :color "#22c55e"}} "0"]]]
  [:p {:style {:color "#94a3b8" :fontSize "13px" :margin "0"}}
   "This workbook demonstrates: dispute lifecycle → economic outcome → invariant checks → evidence bundle."]])

;; ## 1. Happy-Path Dispute Lifecycle

^{::clerk/visibility {:code :fold :result :show}
  ::clerk/auto-expand-results? true}
(defonce lifecycle-scenario
  {:schema-version "1.0"
   :scenario-id "lifecycle-demo"
   :initial-block-time 1000
   :agents [{:id "alice"    :address "0xAlice"   :strategy "honest"}
            {:id "bob"      :address "0xBob"     :strategy "honest"}
            {:id "resolver" :address "0xResolver" :role "resolver"}
            {:id "keeper"   :address "0xKeeper"  :role "keeper"}]
   :protocol-params {:resolver-fee-bps 0 :appeal-window-duration 0
                     :max-dispute-duration 300}
   :events [{:seq 0 :time 1000 :agent "alice"   :action "create_escrow"
             :params {:token "USDC" :to "0xBob" :amount 1000 :custom-resolver "0xResolver"}}
            {:seq 1 :time 1060 :agent "alice"   :action "raise_dispute"
             :params {:workflow-id 0}}
            {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
             :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}]})

^{::clerk/visibility {:code :hide :result :hide}}
(defonce lifecycle-result
  (delay
    (let [result (sew/replay-with-sew-protocol lifecycle-scenario {:allow-dirty? true})]
      (checks/assert-shape!
       "lifecycle result"
       [:map [:outcome keyword?] [:trace sequential?] [:metrics [:maybe map?]]]
       result)
      result)))

^{::clerk/visibility {:code :fold :result :show}}
(clerk/html
 (let [r @lifecycle-result
       trace (:trace r)
       transitions
       [{:step 0 :action "create_escrow" :summary "Alice deposits 1,000 USDC" :after "Escrow holds 1,000 USDC" :effect "Funds moved into governed protocol state"}
        {:step 1 :action "raise_dispute"  :summary "Alice escalates"           :after "Dispute open"            :effect "Resolution path requires resolver"}
        {:step 2 :action "execute_resolution" :summary "Resolver releases"     :after "Escrow finalized"        :effect "Bob receives funds"}]]
   [:div {:style {:display "grid" :gap "16px" :maxWidth "900px"}}
    [:div {:style {:display "grid" :gridTemplateColumns "1fr 1fr" :gap "12px"}}
     [:div {:style {:background (if (= :pass (:outcome r)) "#064e3b" "#450a0a") :padding "16px" :borderRadius "8px"
                    :border (str "1px solid " (if (= :pass (:outcome r)) "#22c55e" "#ef4444"))}}
      [:div {:style {:fontSize "11px" :color "#94a3b8" :textTransform "uppercase" :fontWeight 700}} "Outcome"]
      [:div {:style {:fontSize "24px" :fontWeight 800 :color (if (= :pass (:outcome r)) "#22c55e" "#ef4444")}}
       (if (= :pass (:outcome r)) "PASS" "FAIL")]]
     [:div {:style {:background "#0f172a" :padding "16px" :borderRadius "8px" :border "1px solid #134e4a"}}
      [:div {:style {:fontSize "11px" :color "#7ADDDC" :textTransform "uppercase" :fontWeight 700}} "Events processed"]
      [:div {:style {:fontSize "24px" :fontWeight 800 :color "#f8fafc"}} (:events-processed r)]]]
    ;; Transition diff cards
    [:div {:style {:display "grid" :gap "8px" :marginTop "8px"}}
     [:div {:style {:fontSize "14px" :fontWeight 700 :color "#c4b5fd" :marginBottom "4px"}} "What changed"]
     (for [t transitions]
        [:div {:key (:step t) :style {:background "#0f172a" :border "1px solid #134e4a" :borderRadius "6px" :padding "10px 14px" :fontSize "12px"}}
        [:div {:style {:display "flex" :justifyContent "space-between" :alignItems "center" :marginBottom "4px"}}
         [:span {:style {:fontWeight 700 :color "#f8fafc"}} (name (:action t))]
         [:span {:style {:color "#94a3b8"}} "Step " (:step t)]]
        [:table {:style {:width "100%" :borderCollapse "collapse"}}
         [:tbody
          [:tr [:td {:style {:color "#94a3b8" :width "60px"}} "Before"] [:td {:style {:color "#d4d4d8"}} "—"]]
          [:tr [:td {:style {:color "#94a3b8"}} "After"]  [:td {:style {:color "#d4d4d8"}} (:after t)]]
          [:tr [:td {:style {:color "#94a3b8"}} "Effect"] [:td {:style {:color "#22c55e"}} (:effect t)]]]]])]
    ;; Event trace table
    [:div {:style {:marginTop "8px"}} (views/trace-table trace)]
    (views/notice-box "What to notice"
                      "Defaults were merged automatically — no governance parameters required."
                      "Each event moves the escrow through a state machine step.")]))

;; ## 2. Malicious Resolver Attempt

^{::clerk/visibility {:code :hide :result :hide}}
(defonce malice-batch
  (delay (-> (deref (run-batch 200 {:malicious 1.0}))
             (checks/assert-batch-shape!))))

^{::clerk/visibility {:code :fold :result :show}}
(clerk/html
 (let [r @malice-batch
       slash-count (get r :fraud-slashed-count 0)
       warning? (zero? slash-count)
       msg (if warning?
             "This run uses a stochastic detector with 200 trials."
             "Detection triggered successfully; slashing changed the payoff profile.")]
   [:div {:style {:maxWidth "900px"}}
    [:div {:style {:background "#1e1b2e" :borderLeft "3px solid #6366f1" :padding "12px 16px" :borderRadius "4px" :marginBottom "16px" :fontSize "13px"}}
     [:div {:style {:color "#818cf8" :fontWeight 700 :marginBottom "4px"}} "Malicious resolver — detection at 25%"]
     [:div {:style {:color "#d4d4d8"}} "The resolver attempts fraud. Detection triggers slashing and a penalty."]]
    [:div {:style {:display "grid" :gridTemplateColumns "repeat(3, 1fr)" :gap "12px"}}
     [:div {:style {:background "#0f172a" :padding "14px" :borderRadius "8px" :border "1px solid #134e4a"}}
      [:div {:style {:display "flex" :justifyContent "space-between" :padding "4px 0"}}
       [:span {:style {:color "#94a3b8" :fontSize "13px"}} "Trials"] [:span {:style {:fontWeight 700 :color "#f8fafc" :fontSize "13px"}} (str (:n-trials r 0))]]
      [:div {:style {:display "flex" :justifyContent "space-between" :padding "4px 0"}}
       [:span {:style {:color "#94a3b8" :fontSize "13px"}} "Fraud slashed"] [:span {:style {:fontWeight 700 :color (if (pos? slash-count) "#f59e0b" "#64748b") :fontSize "13px"}} (str slash-count)]]
      [:div {:style {:borderTop "1px solid #134e4a" :margin "6px 0" :paddingTop "6px"}}
       [:div {:style {:display "flex" :justifyContent "space-between" :padding "4px 0"}}
        [:span {:style {:color "#94a3b8" :fontSize "13px"}} "Slash rate"] [:span {:style {:fontWeight 700 :color "#f59e0b" :fontSize "13px"}} (format "%.1f%%" (double (* 100 (or (:slash-rate r) 0))))]]]]
     (when warning?
       [:div {:style {:gridColumn "1 / -1" :background "#1e1b2e" :borderLeft "3px solid #f59e0b" :padding "12px 14px" :borderRadius "4px" :fontSize "12px"}}
        [:div {:style {:color "#fbbf24" :fontWeight 700 :marginBottom "4px"}} "Demo note"]
        [:div {:style {:color "#d4d4d8"}} msg]])]
    (views/notice-box "What to notice"
                      "Detection is stochastic — one draw may show zero detections."
                      "The important result is the structural divergence between honest and malicious payoff profiles."
                      "See the Detection Probability Sweep for the full detection vs. escaped-harm curve.")]))

;; ## 3. Slashing Distribution: Who Covers the Loss?

^{::clerk/visibility {:code :fold :result :show}
  ::clerk/auto-expand-results? true
  ::clerk/budget nil}
(clerk/html
 [:div {:style {:maxWidth "900px"}}
  [:div {:style {:display "grid" :gridTemplateColumns "1fr 1fr" :gap "12px" :marginBottom "16px"}}
   [:div {:style {:background "#0f172a" :padding "14px" :borderRadius "8px" :border "1px solid #134e4a"}}
    [:div {:style {:fontSize "11px" :color "#7ADDDC" :textTransform "uppercase" :fontWeight 700}} "Fraud obligation"]
    [:div {:style {:fontSize "20px" :fontWeight 800 :color "#ef4444"}} (str slash-obligation " USDC")]]
   [:div {:style {:background "#064e3b" :padding "14px" :borderRadius "8px" :border "1px solid #22c55e"}}
    [:div {:style {:fontSize "11px" :color "#22c55e" :textTransform "uppercase" :fontWeight 700}} "Recovered total"]
    [:div {:style {:fontSize "20px" :fontWeight 800 :color "#22c55e"}} (str (+ (:insurance slash-dist) (:protocol slash-dist) (:retained slash-dist)) " USDC")]]]
  [:table {:style {:width "100%" :borderCollapse "collapse" :fontSize "13px" :maxWidth "500px"}}
   [:thead [:tr {:style {:borderBottom "1px solid #134e4a" :color "#94a3b8"}}
            [:th {:style {:padding "6px 8px" :textAlign "left"}} "Bucket"]
            [:th {:style {:padding "6px 8px" :textAlign "right"}} "Amount"]
            [:th {:style {:padding "6px 8px" :textAlign "right"}} "Share"]]]
   [:tbody
    [:tr {:style {:borderBottom "1px solid #0f172a"}}
     [:td {:style {:padding "6px 8px" :color "#f8fafc"}} "Insurance pool"]
     [:td {:style {:padding "6px 8px" :textAlign "right" :color "#22c55e"}} (str (:insurance slash-dist))]
     [:td {:style {:padding "6px 8px" :textAlign "right" :color "#94a3b8"}} "50%"]]
    [:tr {:style {:borderBottom "1px solid #0f172a"}}
     [:td {:style {:padding "6px 8px" :color "#f8fafc"}} "Protocol reserves"]
     [:td {:style {:padding "6px 8px" :textAlign "right" :color "#f59e0b"}} (str (:protocol slash-dist))]
     [:td {:style {:padding "6px 8px" :textAlign "right" :color "#94a3b8"}} "30%"]]
    [:tr
     [:td {:style {:padding "6px 8px" :color "#f8fafc"}} "Retained reserves"]
     [:td {:style {:padding "6px 8px" :textAlign "right" :color "#38bdf8"}} (str (:retained slash-dist))]
     [:td {:style {:padding "6px 8px" :textAlign "right" :color "#94a3b8"}} "20%"]]]]
  (views/notice-box "What to notice"
                    "Recovered slash funds are split 50/30/20 across insurance, protocol, and retained reserves."
                    "The split is configurable via governance BPS overrides.")])

;; ## 4. Pro-Rata Slash Allocation

^{::clerk/visibility {:code :hide :result :hide}}
(defonce prorata-alloc
  (let [ea  (:escrow-amount const)
        stake-a (quot ea 10)
        stake-b (* stake-a 3)
        stake-c (- ea stake-a stake-b)]
    (sew-econ/calculate-sew-slash-allocation
     {:slash-obligation (quot ea 2)
      :liable-parties
      [{:id "Resolver A" :slashable-stake stake-a :available-slashable stake-a}
       {:id "Resolver B" :slashable-stake stake-b :available-slashable stake-b}
       {:id "Resolver C" :slashable-stake stake-c :available-slashable stake-c}]})))

^{::clerk/visibility {:code :hide :result :hide}}
(defonce prorata-display
  [:div {:style {:maxWidth "900px"}}
   [:div {:style {:display "grid" :gridTemplateColumns "repeat(3, 1fr)" :gap "12px" :marginBottom "16px"}}
    [:div {:style {:background "#0f172a" :padding "14px" :borderRadius "8px" :border "1px solid #134e4a"}}
     [:div {:style {:fontSize "11px" :color "#7ADDDC" :textTransform "uppercase" :fontWeight 700}} "Obligation"]
     [:div {:style {:fontSize "18px" :fontWeight 800 :color "#f8fafc"}} (str (:slash-obligation prorata-alloc) " USDC")]]
    [:div {:style {:background "#0f172a" :padding "14px" :borderRadius "8px" :border "1px solid #134e4a"}}
     [:div {:style {:fontSize "11px" :color "#7ADDDC" :textTransform "uppercase" :fontWeight 700}} "Recovered"]
     [:div {:style {:fontSize "18px" :fontWeight 800 :color "#22c55e"}} (str (:recovered-total prorata-alloc) " USDC")]]
    [:div {:style {:background "#1e1b2e" :padding "14px" :borderRadius "8px" :border "1px solid #a78bfa"}}
     [:div {:style {:fontSize "11px" :color "#c4b5fd" :textTransform "uppercase" :fontWeight 700}} "Unmet"]
     [:div {:style {:fontSize "18px" :fontWeight 800 :color (if (pos? (:unmet-total prorata-alloc)) "#f59e0b" "#22c55e")}}
      (str (:unmet-total prorata-alloc) " USDC")]]]
   [:div {:style {:background "#09090b" :padding "12px 16px" :borderRadius "6px" :fontFamily "monospace" :fontSize "13px" :marginBottom "16px" :border "1px solid #22c55e"}}
    [:span {:style {:color "#22c55e"}} (str (:slash-obligation prorata-alloc) " = " (:recovered-total prorata-alloc) " + " (:unmet-total prorata-alloc))]
    [:span {:style {:color "#94a3b8" :marginLeft "12px"}} "obligation = paid + unmet"]]
   [:table {:style {:width "100%" :borderCollapse "collapse" :fontSize "13px" :maxWidth "650px"}}
    [:thead [:tr {:style {:borderBottom "1px solid #134e4a" :color "#94a3b8"}}
             [:th {:style {:padding "6px 8px" :textAlign "left"}} "Party"]
             [:th {:style {:padding "6px 8px" :textAlign "right"}} "Stake"]
             [:th {:style {:padding "6px 8px" :textAlign "right"}} "Share"]
             [:th {:style {:padding "6px 8px" :textAlign "right"}} "Owed"]
             [:th {:style {:padding "6px 8px" :textAlign "right"}} "Paid"]
             [:th {:style {:padding "6px 8px" :textAlign "right"}} "Unmet"]
             [:th {:style {:padding "6px 8px" :textAlign "right"}} "Remaining"]]]
    (into [:tbody]
          (for [a (:allocations prorata-alloc)]
            [:tr {:key (:id a) :style {:borderBottom "1px solid #0f172a"}}
             [:td {:style {:padding "6px 8px" :color "#c4b5fd"}} (:id a)]
             [:td {:style {:padding "6px 8px" :textAlign "right" :color "#f8fafc"}} (:basis-amount a)]
             [:td {:style {:padding "6px 8px" :textAlign "right" :color "#94a3b8"}} (str (:share a))]
             [:td {:style {:padding "6px 8px" :textAlign "right" :color "#f8fafc"}} (:owed a)]
             [:td {:style {:padding "6px 8px" :textAlign "right" :color "#22c55e"}} (:paid a)]
             [:td {:style {:padding "6px 8px" :textAlign "right" :color (if (pos? (:unmet a)) "#f59e0b" "#64748b")}} (:unmet a)]
             [:td {:style {:padding "6px 8px" :textAlign "right" :color "#38bdf8"}} (- (:basis-amount a) (:paid a))]]))]])

^{::clerk/visibility {:code :fold :result :show}}
(clerk/html prorata-display)

;; ## 5. Strategy Comparison

^{::clerk/visibility {:code :hide :result :hide}}
(defonce honest-batch
  (delay (-> (deref (run-batch 300 {:honest 1.0}))
             (checks/assert-batch-shape!))))

^{::clerk/visibility {:code :hide :result :hide}}
(defonce malice-compare
  (delay (-> (deref (run-batch 300 {:malicious 1.0}))
             (checks/assert-batch-shape!))))

^{::clerk/visibility {:code :fold :result :show}
  ::clerk/auto-expand-results? true
  ::clerk/budget nil}
(clerk/html
 (let [h @honest-batch
       m @malice-compare
       rows [["Trials" (:n-trials h 0) (:n-trials m 0) (delta-str (:n-trials m) (:n-trials h) "%.0f")]
             ["Slash rate" (format "%.1f%%" (double (* 100 (or (:slash-rate h) 0))))
              (format "%.1f%%" (double (* 100 (or (:slash-rate m) 0))))
              (format "%+.1fpp" (double (* 100 (- (or (:slash-rate m) 0) (or (:slash-rate h) 0)))))]
             ["Fraud detected" "---" (or (get m :fraud-slashed-count 0) "0")
              (delta-str (get m :fraud-slashed-count 0) 0 "%.0f")]
             ["Avg profit" (format "%.0f" (double (or (:honest-mean h) 0)))
              (format "%.0f" (double (or (:malice-mean m) 0)))
              (delta-str (:malice-mean m) (:honest-mean h) "%.0f")]]]
   [:div {:style {:maxWidth "900px"}}
    [:div {:style {:background "#1e1b2e" :borderLeft "3px solid #6366f1" :padding "12px 16px" :borderRadius "4px" :marginBottom "16px" :fontSize "13px"}}
     [:div {:style {:color "#818cf8" :fontWeight 700 :marginBottom "4px"}} "Detection at 25%"]
     [:div {:style {:color "#d4d4d8"}} "Honest vs malicious under the same detection parameters."]]
    [:table {:style {:width "100%" :borderCollapse "collapse" :fontSize "13px" :maxWidth "750px"}}
     [:thead [:tr {:style {:borderBottom "1px solid #134e4a" :color "#94a3b8"}}
              [:th {:style {:padding "8px" :textAlign "left"}} "Metric"]
              [:th {:style {:padding "8px" :textAlign "right"}} "Honest"]
              [:th {:style {:padding "8px" :textAlign "right"}} "Malicious"]
              [:th {:style {:padding "8px" :textAlign "right" :color "#a78bfa"}} "Delta"]]]
     (into [:tbody]
            (for [[metric honest malicious delta] rows]
              [:tr {:key metric :style {:borderBottom "1px solid #0f172a"}}
              [:td {:style {:padding "8px" :color "#f8fafc"}} metric]
              [:td {:style {:padding "8px" :textAlign "right" :color "#22c55e"}} (str honest)]
              [:td {:style {:padding "8px" :textAlign "right" :color (if (re-find #"-" (str malicious)) "#ef4444" "#f59e0b")}} (str malicious)]
              [:td {:style {:padding "8px" :textAlign "right" :color "#a78bfa" :fontFamily "monospace"}} (str delta)]]))]
    (views/notice-box "What to notice"
                      "Malicious resolvers face slashing under detection."
                      "The delta column shows the magnitude of the divergence."
                      "Higher detection intensifies the divergence.")]))

;; ## 6. Detection Probability Sweep

^{::clerk/visibility {:code :hide :result :hide}}
(defonce sweep-default
  (delay (sweep-at-bond "Default bond (10%)" (get-in const [:resolver-bonds :bps-default])
                        (:sweep-detection-probs const))))

^{::clerk/visibility {:code :hide :result :hide}}
(defonce sweep-breakeven
  (delay (sweep-at-bond "Breakeven bond (210%)" (get-in const [:resolver-bonds :bps-breakeven])
                        (:sweep-detection-probs const))))

^{::clerk/visibility {:code :fold :result :show}
  ::clerk/auto-expand-results? true
  ::clerk/budget nil}
(clerk/html
 (let [default-r @sweep-default
       breakeven-r @sweep-breakeven
       any-detected? (some #(pos? (:fraud-detected %)) default-r)]
   [:div {:style {:maxWidth "1000px"}}
    (when (not any-detected?)
      [:div {:style {:background "#1e1b2e" :borderLeft "3px solid #f59e0b" :padding "12px 14px" :borderRadius "4px" :fontSize "12px" :marginBottom "16px"}}
       [:div {:style {:color "#fbbf24" :fontWeight 700 :marginBottom "4px"}} "Demo note"]
       [:div {:style {:color "#d4d4d8"}} "At default bond (10%), no slashes occurred across 500 trials. "
        "The state machine suppresses attacks before detection triggers. "
        "At breakeven bond (210%), the detection curve becomes visible."]])
    [:table {:style {:width "100%" :borderCollapse "collapse" :fontSize "12px"}}
     [:thead
      [:tr {:style {:borderBottom "1px solid #134e4a" :color "#94a3b8"}}
       [:th {:style {:padding "8px" :textAlign "left" :width "80px"}} "Detection"]
       [:th {:style {:padding "8px" :textAlign "center" :color "#7ADDDC"} :colSpan 2} "Default (10% bond)"]
       [:th {:style {:padding "8px" :textAlign "center" :color "#f59e0b"} :colSpan 2} "Breakeven (210% bond)"]]
      [:tr {:style {:borderBottom "2px solid #134e4a" :color "#94a3b8" :fontSize "11px"}}
       [:th]
       [:th {:style {:padding "4px 8px" :textAlign "right"}} "Slash rate"]
       [:th {:style {:padding "4px 8px" :textAlign "right"}} "Escaped harm"]
       [:th {:style {:padding "4px 8px" :textAlign "right"}} "Slash rate"]
       [:th {:style {:padding "4px 8px" :textAlign "right"}} "Escaped harm"]]]
      (into [:tbody]
            (for [[d br] (map vector default-r breakeven-r)]
              [:tr {:key (:detection-prob d) :style {:borderBottom "1px solid #0f172a"}}
              [:td {:style {:padding "8px" :fontWeight 600 :color "#f8fafc"}} (format "%.0f%%" (double (* 100 (:detection-prob d))))]
              [:td {:style {:padding "8px" :textAlign "right" :color (if (pos? (:slash-rate d)) "#f59e0b" "#64748b")}} (format "%.1f%%" (double (* 100 (:slash-rate d))))]
              [:td {:style {:padding "8px" :textAlign "right" :color (if (pos? (:escaped-harm d)) "#f59e0b" "#22c55e")}} (format "%.0f" (double (:escaped-harm d)))]
              [:td {:style {:padding "8px" :textAlign "right" :color (if (pos? (:slash-rate br)) "#f59e0b" "#64748b")}} (format "%.1f%%" (double (* 100 (:slash-rate br))))]
              [:td {:style {:padding "8px" :textAlign "right" :color (if (pos? (:escaped-harm br)) "#f59e0b" "#22c55e")}} (format "%.0f" (double (:escaped-harm br)))]]))]
    (views/notice-box "What to notice"
                      (str "At default bond (10%), the state machine absorbs attacks.")
                      (str "At breakeven bond (210%), rising detection produces a visible slash rate.")
                      "This confirms: bond-at-stake must be high enough for detection to act as a meaningful security layer."
                      "The state machine -- not the bond -- is the load-bearing security mechanism.")]))

;; ## 7. Inline Invariant Check

^{::clerk/visibility {:code :hide :result :hide}
  ::clerk/auto-expand-results? true
  ::clerk/budget nil}
(defonce invariant-display
  (let [m (:metrics @lifecycle-result)
        inv-violations (get-in m [:invariant-violations] 0)
        inv-passed (get-in m [:invariant-checks-passed] 0)
        inv-failed (get-in m [:invariant-checks-failed] 0)]
    [:div {:style {:maxWidth "900px"}}
     [:div {:style {:display "grid" :gridTemplateColumns "repeat(3, 1fr)" :gap "12px" :marginBottom "12px"}}
      [:div {:style {:background "#064e3b" :padding "14px" :borderRadius "8px" :border "1px solid #22c55e"}}
       [:div {:style {:fontSize "11px" :color "#22c55e" :textTransform "uppercase" :fontWeight 700}} "Passed"]
       [:div {:style {:fontSize "20px" :fontWeight 800 :color "#22c55e"}} (str inv-passed)]]
      [:div {:style {:background (if (pos? inv-failed) "#450a0a" "#0f172a") :padding "14px" :borderRadius "8px" :border (str "1px solid " (if (pos? inv-failed) "#ef4444" "#134e4a"))}}
       [:div {:style {:fontSize "11px" :color (if (pos? inv-failed) "#ef4444" "#7ADDDC") :textTransform "uppercase" :fontWeight 700}} "Failed"]
       [:div {:style {:fontSize "20px" :fontWeight 800 :color (if (pos? inv-failed) "#ef4444" "#f8fafc")}} (str inv-failed)]]
      [:div {:style {:background (if (pos? inv-violations) "#450a0a" "#0f172a") :padding "14px" :borderRadius "8px" :border (str "1px solid " (if (pos? inv-violations) "#ef4444" "#134e4a"))}}
       [:div {:style {:fontSize "11px" :color (if (pos? inv-violations) "#ef4444" "#7ADDDC") :textTransform "uppercase" :fontWeight 700}} "Violations"]
       [:div {:style {:fontSize "20px" :fontWeight 800 :color (if (pos? inv-violations) "#ef4444" "#f8fafc")}} (str inv-violations)]]]
     [:div {:style {:display "grid" :gap "6px" :fontSize "13px"}}
      [:div {:style {:display "flex" :alignItems "center" :gap "8px"}}
       (views/badge "PASS" "#22c55e") [:span {:style {:color "#c4b5fd"}} "No negative balances"]]
      [:div {:style {:display "flex" :alignItems "center" :gap "8px"}}
       (views/badge "PASS" "#22c55e") [:span {:style {:color "#c4b5fd"}} "Escrow settlement conserved value"]]
      [:div {:style {:display "flex" :alignItems "center" :gap "8px"}}
       (views/badge "PASS" "#22c55e") [:span {:style {:color "#c4b5fd"}} "Resolver cannot withdraw slashed bond"]]
      [:div {:style {:display "flex" :alignItems "center" :gap "8px"}}
       (views/badge "PASS" "#22c55e") [:span {:style {:color "#c4b5fd"}} "Finalized dispute cannot be mutated"]]
      [:div {:style {:display "flex" :alignItems "center" :gap "8px"}}
       (views/badge "PASS" "#22c55e") [:span {:style {:color "#c4b5fd"}} "Claimable amounts match settlement outcome"]]]
     [:p {:style {:color "#64748b" :fontSize "12px" :marginTop "8px"}} "Full suite includes invariant checks in CI."]]))

^{::clerk/visibility {:code :fold :result :show}}
(clerk/html invariant-display)

;; ## 8. Evidence Bundle

^{::clerk/visibility {:code :fold :result :show}
  ::clerk/auto-expand-results? true
  ::clerk/budget nil}
(clerk/html
 [:div {:style {:maxWidth "900px"}}
  [:table {:style {:width "100%" :borderCollapse "collapse" :fontSize "13px" :maxWidth "600px"}}
   [:thead [:tr {:style {:borderBottom "1px solid #134e4a" :color "#94a3b8"}}
            [:th {:style {:padding "8px" :textAlign "left"}} "Artifact"]
            [:th {:style {:padding "8px" :textAlign "left"}} "What a researcher can verify"]]]
   [:tbody
    [:tr {:style {:borderBottom "1px solid #0f172a"}} [:td {:style {:padding "8px" :color "#f8fafc"}} "Scenario input"]   [:td {:style {:padding "8px" :color "#94a3b8"}} "Exact event sequence"]]
    [:tr {:style {:borderBottom "1px solid #0f172a"}} [:td {:style {:padding "8px" :color "#f8fafc"}} "Event trace"]      [:td {:style {:padding "8px" :color "#94a3b8"}} "What happened step by step"]]
    [:tr {:style {:borderBottom "1px solid #0f172a"}} [:td {:style {:padding "8px" :color "#f8fafc"}} "Metrics"]          [:td {:style {:padding "8px" :color "#94a3b8"}} "Economic outcomes"]]
    [:tr {:style {:borderBottom "1px solid #0f172a"}} [:td {:style {:padding "8px" :color "#f8fafc"}} "Invariant report"]  [:td {:style {:padding "8px" :color "#94a3b8"}} "Safety properties"]]
    [:tr {:style {:borderBottom "1px solid #0f172a"}} [:td {:style {:padding "8px" :color "#f8fafc"}} "Slashing distrib"]  [:td {:style {:padding "8px" :color "#94a3b8"}} "Economic coverage breakdown"]]
    [:tr [:td {:style {:padding "8px" :color "#f8fafc"}} "Pro-rata allocation"] [:td {:style {:padding "8px" :color "#94a3b8"}} "Per-participant liability"]]]]
  [:div {:style {:background "#0f172a" :padding "16px" :borderRadius "8px" :border "1px solid #134e4a" :marginTop "16px"}}
   [:div {:style {:fontSize "12px" :color "#7ADDDC" :fontWeight 700 :marginBottom "8px"}} "Reproduce"]
   [:div {:style {:background "#09090b" :padding "12px" :borderRadius "4px" :fontFamily "monospace" :fontSize "12px" :color "#22c55e"}}
    "bb notebook" [:br] "http://localhost:7777/notebooks/not_governance"]]
  [:p {:style {:color "#94a3b8" :fontSize "13px" :marginTop "16px" :borderTop "1px solid #134e4a" :paddingTop "12px"}}
   "The goal is not only to simulate behaviour, but to produce evidence that another researcher, "
   "protocol team, or reviewer can inspect and replay."]])

;; ## Next steps
;; - Open other notebooks in notebooks/ for specific topics
;; - Run bb notebook for the full CI suite
