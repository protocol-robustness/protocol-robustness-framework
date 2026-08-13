(ns resolver-sim.notebook-support.security
  "Helper functions for the security validation notebook.
   Pure rendering and data utilities -- no I/O here."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Status chips
;; ---------------------------------------------------------------------------

(def status-styles
  {:verified           {:bg "#052e16" :border "#16a34a" :text "#4ade80"   :label "VERIFIED"}
   :partial            {:bg "#1c1007" :border "#d97706" :text "#fbbf24"   :label "PARTIAL"}
   :assumption-req     {:bg "#0f1729" :border "#6366f1" :text "#818cf8"   :label "ASSUMPTION REQUIRED"}
   :failed             {:bg "#1f0707" :border "#dc2626" :text "#f87171"   :label "FAILED"}
   :not-evaluated      {:bg "#111827" :border "#6b7280" :text "#9ca3af"   :label "NOT EVALUATED"}
   :experimental       {:bg "#0c1a2e" :border "#38bdf8" :text "#7dd3fc"   :label "EXPERIMENTAL"}})

(defn status-chip
  ([kw] (status-chip kw nil))
  ([kw label-override]
   (let [{:keys [bg border text label]} (get status-styles kw (get status-styles :not-evaluated))]
     [:span {:style {:background bg :border (str "1px solid " border)
                     :color text :font-family "JetBrains Mono, monospace"
                     :font-size "10px" :font-weight "700" :letter-spacing "0.08em"
                     :padding "3px 9px" :display "inline-block"}}
      (or label-override label)])))

;; ---------------------------------------------------------------------------
;; Section header
;; ---------------------------------------------------------------------------

(defn section-header
  "Renders a dark header strip for a numbered security section."
  [n title status-kw description]
  [:div {:style {:background "#080d1a" :border-left "4px solid #3b82f6"
                 :padding "18px 24px" :margin-bottom "2px"}}
   [:div {:style {:display "flex" :align-items "flex-start" :justify-content "space-between"}}
    [:div
     [:span {:style {:color "#3b82f6" :font-family "JetBrains Mono, monospace"
                     :font-size "11px" :font-weight "700" :letter-spacing "0.15em"
                     :margin-right "12px"}}
      (str "SEC-" (format "%02d" n))]
     [:span {:style {:color "#f1f5f9" :font-size "15px" :font-weight "700"}}
      title]]
    (status-chip status-kw)]
   (when description
     [:p {:style {:color "#94a3b8" :font-size "13px" :margin "8px 0 0 0" :line-height "1.6"}}
      description])])

;; ---------------------------------------------------------------------------
;; State machine SVG
;; ---------------------------------------------------------------------------

(defn state-machine-svg []
  (let [nodes {:none     {:cx 70  :cy 140 :r 28 :fill "#1f2937" :stroke "#6b7280" :dash "4,3"
                          :label ":none"     :sub "pre-creation" :tc "#9ca3af"}
               :pending  {:cx 230 :cy 140 :r 32 :fill "#1e3a5f" :stroke "#3b82f6"
                          :label ":pending"  :sub "live escrow"  :tc "#93c5fd"}
               :disputed {:cx 400 :cy 100 :r 32 :fill "#3b2700" :stroke "#f59e0b"
                          :label ":disputed" :sub "in dispute"   :tc "#fde68a"}
               :released {:cx 570 :cy 50  :r 30 :fill "#052e16" :stroke "#22c55e"
                          :label ":released" :sub "terminal"     :tc "#86efac"}
               :refunded {:cx 570 :cy 150 :r 30 :fill "#052e16" :stroke "#22c55e"
                          :label ":refunded" :sub "terminal"     :tc "#86efac"}
               :resolved {:cx 570 :cy 210 :r 26 :fill "#0f1117" :stroke "#374151" :dash "4,3"
                          :label ":resolved" :sub "unreachable"  :tc "#4b5563"}}
        ;; [from-key to-key label dashed?]
        edges [[:none     :pending  "createEscrow"       false]
               [:pending  :disputed "raiseDispute"       false]
               [:pending  :released "release"            false]
               [:pending  :refunded "cancel/timeout"     false]
               [:disputed :released "executeResolution"  false]
               [:disputed :refunded "executeResolution"  false]
               [:disputed :resolved "(never called)"     true]]
        ;; arrow endpoint offset: move tip back by r so it lands on circle edge
        endpoint (fn [x1 y1 x2 y2 r]
                   (let [dx (- x2 x1) dy (- y2 y1)
                         len (Math/sqrt (+ (* dx dx) (* dy dy)))
                         ux (/ dx len) uy (/ dy len)]
                     [(- x2 (* ux (+ r 2))) (- y2 (* uy (+ r 2)))]))
        startpoint (fn [x1 y1 x2 y2 r]
                     (let [dx (- x2 x1) dy (- y2 y1)
                           len (Math/sqrt (+ (* dx dx) (* dy dy)))
                           ux (/ dx len) uy (/ dy len)]
                       [(+ x1 (* ux (+ r 2))) (+ y1 (* uy (+ r 2)))]))]
    [:svg {:xmlns "http://www.w3.org/2000/svg" :width "660" :height "270"
           :style {:background "#0a0f1e" :border "1px solid #1e293b" :display "block"}}
     ;; arrow marker defs
     [:defs
      [:marker {:id "arrowhead" :markerWidth "8" :markerHeight "6"
                :refX "8" :refY "3" :orient "auto"}
       [:polygon {:points "0 0, 8 3, 0 6" :fill "#475569"}]]
      [:marker {:id "arrowhead-dash" :markerWidth "8" :markerHeight "6"
                :refX "8" :refY "3" :orient "auto"}
       [:polygon {:points "0 0, 8 3, 0 6" :fill "#374151"}]]]
     ;; edges
     (for [[from-k to-k label dashed?] edges]
       (let [{x1 :cx y1 :cy r1 :r} (get nodes from-k)
             {x2 :cx y2 :cy r2 :r} (get nodes to-k)
             [sx sy] (startpoint x1 y1 x2 y2 r1)
             [ex ey] (endpoint  x1 y1 x2 y2 r2)
             mx (/ (+ sx ex) 2) my (- (/ (+ sy ey) 2) 12)]
         [:g {:key (str (name from-k) "-" (name to-k))}
          [:path {:d (str "M " sx " " sy " Q " mx " " my " " ex " " ey)
                  :stroke (if dashed? "#374151" "#475569")
                  :stroke-width "1.5"
                  :stroke-dasharray (when dashed? "5,4")
                  :fill "none"
                  :marker-end (if dashed? "url(#arrowhead-dash)" "url(#arrowhead)")}]
          [:text {:x mx :y (- my 6)
                  :font-family "JetBrains Mono, monospace" :font-size "8"
                  :fill (if dashed? "#4b5563" "#64748b")
                  :text-anchor "middle"}
           label]]))
     ;; nodes
     (for [[k {:keys [cx cy r fill stroke dash label sub tc]}] nodes]
       [:g {:key (name k)}
        [:circle {:cx cx :cy cy :r r :fill fill :stroke stroke :stroke-width "1.5"
                  :stroke-dasharray dash}]
        [:text {:x cx :y (+ cy 4) :text-anchor "middle"
                :font-family "JetBrains Mono, monospace" :font-size "9"
                :font-weight "700" :fill tc}
         label]
        [:text {:x cx :y (+ cy 17) :text-anchor "middle"
                :font-family "JetBrains Mono, monospace" :font-size "7"
                :fill "#475569"}
         sub]])
     ;; legend
     [:g
      [:rect {:x 10 :y 240 :width 10 :height 10 :fill "#1e3a5f" :stroke "#3b82f6"}]
      [:text {:x 25 :y 249 :font-family "JetBrains Mono, monospace" :font-size "9" :fill "#64748b"}
       "active state"]
      [:rect {:x 110 :y 240 :width 10 :height 10 :fill "#3b2700" :stroke "#f59e0b"}]
      [:text {:x 125 :y 249 :font-family "JetBrains Mono, monospace" :font-size "9" :fill "#64748b"}
       "contested"]
      [:rect {:x 210 :y 240 :width 10 :height 10 :fill "#052e16" :stroke "#22c55e"}]
      [:text {:x 225 :y 249 :font-family "JetBrains Mono, monospace" :font-size "9" :fill "#64748b"}
       "terminal"]
      [:rect {:x 300 :y 240 :width 10 :height 10 :fill "#0f1117" :stroke "#374151"
              :stroke-dasharray "3,2"}]
      [:text {:x 315 :y 249 :font-family "JetBrains Mono, monospace" :font-size "9" :fill "#64748b"}
       "enum only / unreachable"]]]))

;; ---------------------------------------------------------------------------
;; Fund flow SVG (conservation of funds diagram)
;; ---------------------------------------------------------------------------

(defn fund-flow-svg []
  [:svg {:xmlns "http://www.w3.org/2000/svg" :width "660" :height "180"
         :style {:background "#0a0f1e" :border "1px solid #1e293b" :display "block"}}
   [:defs
    [:marker {:id "ff-arrow" :markerWidth "8" :markerHeight "6"
              :refX "8" :refY "3" :orient "auto"}
     [:polygon {:points "0 0, 8 3, 0 6" :fill "#475569"}]]
    [:marker {:id "ff-arrow-red" :markerWidth "8" :markerHeight "6"
              :refX "8" :refY "3" :orient "auto"}
     [:polygon {:points "0 0, 8 3, 0 6" :fill "#ef4444"}]]]
   ;; Depositor box
   [:rect {:x 20 :y 65 :width 100 :height 50 :fill "#1e2433" :stroke "#475569" :rx "3"}]
   [:text {:x 70 :y 87 :text-anchor "middle" :font-family "JetBrains Mono, monospace"
           :font-size "9" :fill "#94a3b8"} "DEPOSITOR"]
   [:text {:x 70 :y 100 :text-anchor "middle" :font-family "JetBrains Mono, monospace"
           :font-size "8" :fill "#64748b"} "(sender)"]
   ;; Arrow: depositor -> escrow
   [:line {:x1 120 :y1 90 :x2 178 :y2 90 :stroke "#475569" :stroke-width "1.5"
           :marker-end "url(#ff-arrow)"}]
   [:text {:x 149 :y 85 :text-anchor "middle" :font-family "JetBrains Mono, monospace"
           :font-size "8" :fill "#64748b"} "deposit"]
   ;; Escrow contract box
   [:rect {:x 180 :y 40 :width 120 :height 100 :fill "#0d1b2e" :stroke "#3b82f6" :rx "3"
           :stroke-width "1.5"}]
   [:text {:x 240 :y 62 :text-anchor "middle" :font-family "JetBrains Mono, monospace"
           :font-size "9" :font-weight "700" :fill "#93c5fd"} "ESCROW"]
   [:text {:x 240 :y 75 :text-anchor "middle" :font-family "JetBrains Mono, monospace"
           :font-size "8" :fill "#64748b"} "principal held"]
   [:text {:x 240 :y 88 :text-anchor "middle" :font-family "JetBrains Mono, monospace"
           :font-size "8" :fill "#64748b"} "yield accrues"]
   [:text {:x 240 :y 101 :text-anchor "middle" :font-family "JetBrains Mono, monospace"
           :font-size "8" :fill "#64748b"} "bond locked"]
   [:text {:x 240 :y 114 :text-anchor "middle" :font-family "JetBrains Mono, monospace"
           :font-size "8" :fill "#4ade80"} "pull-only exits"]
   ;; Arrow: escrow -> recipient (release path)
   [:line {:x1 300 :y1 65 :x2 388 :y2 45 :stroke "#22c55e" :stroke-width "1.5"
           :marker-end "url(#ff-arrow)"}]
   [:text {:x 344 :y 49 :text-anchor "middle" :font-family "JetBrains Mono, monospace"
           :font-size "8" :fill "#16a34a"} "release path"]
   ;; Arrow: escrow -> sender (refund path)
   [:line {:x1 300 :y1 105 :x2 388 :y2 135 :stroke "#22c55e" :stroke-width "1.5"
           :marker-end "url(#ff-arrow)"}]
   [:text {:x 344 :y 131 :text-anchor "middle" :font-family "JetBrains Mono, monospace"
           :font-size "8" :fill "#16a34a"} "refund path"]
   ;; Arrow: escrow -> resolver fee (small, dashed)
   [:line {:x1 300 :y1 85 :x2 388 :y2 88 :stroke "#f59e0b" :stroke-width "1"
           :stroke-dasharray "4,3" :marker-end "url(#ff-arrow)"}]
   [:text {:x 342 :y 80 :text-anchor "middle" :font-family "JetBrains Mono, monospace"
           :font-size "8" :fill "#92400e"} "resolver fee"]
   ;; Recipient boxes
   [:rect {:x 390 :y 30 :width 100 :height 30 :fill "#052e16" :stroke "#22c55e" :rx "3"}]
   [:text {:x 440 :y 50 :text-anchor "middle" :font-family "JetBrains Mono, monospace"
           :font-size "9" :fill "#4ade80"} "RECIPIENT"]
   [:rect {:x 390 :y 125 :width 100 :height 30 :fill "#052e16" :stroke "#22c55e" :rx "3"}]
   [:text {:x 440 :y 145 :text-anchor "middle" :font-family "JetBrains Mono, monospace"
           :font-size "9" :fill "#4ade80"} "SENDER"]
   [:rect {:x 390 :y 77 :width 100 :height 30 :fill "#1c1007" :stroke "#d97706" :rx "3"}]
   [:text {:x 440 :y 97 :text-anchor "middle" :font-family "JetBrains Mono, monospace"
           :font-size "9" :fill "#fbbf24"} "RESOLVER"]
   ;; Total balance label
   [:text {:x 570 :y 90 :text-anchor "middle" :font-family "JetBrains Mono, monospace"
           :font-size "9" :fill "#4b5563"} "SUM(in)"]
   [:text {:x 570 :y 103 :text-anchor "middle" :font-family "JetBrains Mono, monospace"
           :font-size "9" :fill "#4b5563"} "= SUM(out)"]
   [:text {:x 570 :y 116 :text-anchor "middle" :font-family "JetBrains Mono, monospace"
           :font-size "8" :fill "#22c55e"} "invariant"]])

;; ---------------------------------------------------------------------------
;; Invariant table rows
;; ---------------------------------------------------------------------------

(def invariant-groups
  [{:name "Conservation & Accounting"
    :ids  [:conservation-of-funds :solvency :fees-non-negative :held-non-negative
           :finalization-accounting-correct :token-tax-reconciliation :fee-payouts-monotonic]}
   {:name "State Machine Integrity"
    :ids  [:terminal-states-unchanged :no-double-finalize :all-status-combinations-valid
           :pending-settlement-consistent :time-non-decreasing :time-no-action-after-finality]}
   {:name "Dispute & Resolution"
    :ids  [:dispute-resolution-path :escalation-level-monotonic :dispute-timestamp-consistent
           :dispute-level-bounded :no-withdrawal-during-dispute :single-resolution-payout-consistent]}
   {:name "Bond & Slash"
    :ids  [:bond-slash-bounded :bond-liquidity :slash-status-consistent
           :slash-distribution-consistent :slash-epoch-cap-respected :reversal-slash-disabled
           :fraud-slash-executions-accounted :no-auto-fraud-execute]}
   {:name "Resolver & Authority"
    :ids  [:resolver-bond-mix-valid :resolver-not-frozen-on-assign :resolver-capacity]}
   {:name "Appeal"
    :ids  [:appeal-bond-conserved :appeal-bond-custody-consistent :cancellation-mutex]}
   {:name "Yield"
    :ids  [:yield-position-consistency :yield-exposure :senior-coverage-not-exceeded]}
   {:name "Time Lock"
    :ids  [:time-lock-integrity]}])

(defn invariant-table [highlighted-ids]
  "Renders a 3-column table of invariant groups with status indicators.
   highlighted-ids: set of IDs with known issues (shown in amber)."
  [:div {:style {:font-family "JetBrains Mono, monospace" :font-size "11px"}}
   (for [{group-name :name ids :ids} invariant-groups]
     [:div {:key group-name :style {:margin-bottom "16px"}}
      [:div {:style {:color "#64748b" :font-size "10px" :font-weight "700"
                     :letter-spacing "0.12em" :margin-bottom "6px"
                     :text-transform "uppercase"}}
       group-name]
      [:div {:style {:display "flex" :flex-wrap "wrap" :gap "4px"}}
       (for [id ids]
         (let [issue? (contains? (set highlighted-ids) id)]
           [:span {:key (str id)
                   :style {:background (if issue? "#1c1007" "#0d1b0d")
                           :border (str "1px solid " (if issue? "#d97706" "#166534"))
                           :color (if issue? "#fbbf24" "#4ade80")
                           :padding "2px 8px" :font-size "10px"}}
            (str ":" (name id))]))]])])

;; ---------------------------------------------------------------------------
;; Claim row
;; ---------------------------------------------------------------------------

(def claim-status-style
  {:not-falsified {:color "#4ade80" :bg "#052e16" :border "#16a34a"
                   :label "NOT FALSIFIED IN THIS REPLAY"}
   :falsified     {:color "#f87171" :bg "#1f0707" :border "#dc2626"
                   :label "FALSIFIED BY THIS REPLAY"}
   :not-evaluated {:color "#9ca3af" :bg "#111827" :border "#4b5563"
                   :label "NOT EVALUATED"}
   :inconclusive  {:color "#fbbf24" :bg "#1c1007" :border "#d97706"
                   :label "INCONCLUSIVE"}})

(defn claim-row [{:claim/keys [id description status confidence assumptions]}]
  (let [{:keys [color bg border label]} (get claim-status-style (keyword status)
                                             (get claim-status-style :not-evaluated))]
    [:div {:style {:border-left (str "3px solid " border) :background bg
                   :padding "10px 14px" :margin-bottom "8px"}}
     [:div {:style {:display "flex" :justify-content "space-between" :align-items "center"
                    :margin-bottom "4px"}}
      [:span {:style {:color "#f1f5f9" :font-weight "700" :font-size "12px"
                      :font-family "JetBrains Mono, monospace"}}
       (str ":" (name id))]
      [:span {:style {:background bg :border (str "1px solid " border)
                      :color color :font-family "JetBrains Mono, monospace"
                      :font-size "9px" :font-weight "700" :padding "2px 7px"}}
       label]]
     [:p {:style {:color "#94a3b8" :font-size "12px" :margin "4px 0"}} description]
     (when (seq assumptions)
       [:div {:style {:color "#64748b" :font-size "10px" :margin-top "6px"}}
        [:span {:style {:color "#4b5563" :font-weight "700"}} "Assumes: "]
        (str/join " | " (take 3 assumptions))])]))

;; ---------------------------------------------------------------------------
;; Finding card (compact)
;; ---------------------------------------------------------------------------

(defn finding-card [{:keys [finding_id title severity summary scenario_id confidence]}]
  (let [high? (= severity "high")
        col   (if high? "#ef4444" "#fbbf24")
        bg    (if high? "#1f0707" "#12110a")]
    [:div {:style {:border-left (str "3px solid " col) :background bg
                   :padding "8px 12px" :margin-bottom "6px"
                   :display "flex" :align-items "flex-start" :gap "12px"}}
     [:span {:style {:color col :font-family "JetBrains Mono, monospace"
                     :font-size "9px" :font-weight "700" :min-width "35px"
                     :padding-top "2px"}}
      (str/upper-case severity)]
     [:div {:style {:flex 1}}
      [:div {:style {:color "#f1f5f9" :font-size "12px" :font-weight "700"}}
       (or title (str "Finding: " (subs (or finding_id "") 0 (min 20 (count (or finding_id ""))))))]
      (when (seq summary)
        [:div {:style {:color "#94a3b8" :font-size "11px" :margin-top "3px"}} summary])
      (when scenario_id
        [:div {:style {:color "#64748b" :font-size "10px" :margin-top "3px"
                       :font-family "JetBrains Mono, monospace"}}
         "scenario: " scenario_id])]
     (when confidence
       [:span {:style {:color "#4b5563" :font-family "JetBrains Mono, monospace"
                       :font-size "9px" :align-self "center"}}
        (str (name confidence))])]))

;; ---------------------------------------------------------------------------
;; Section content wrapper
;; ---------------------------------------------------------------------------

(defn content-box
  "Light-on-dark content card within a section."
  [& children]
  (into [:div {:style {:background "#0d1117" :border "1px solid #1e293b"
                       :padding "16px 20px" :margin-bottom "16px"}}]
        children))

(defn two-col [left right]
  [:div {:style {:display "grid" :grid-template-columns "1fr 1fr" :gap "16px"}}
   left right])

(defn label-val [lbl val]
  [:div {:style {:margin-bottom "6px"}}
   [:span {:style {:color "#4b5563" :font-family "JetBrains Mono, monospace"
                   :font-size "10px" :font-weight "700" :margin-right "8px"
                   :text-transform "uppercase"}}
    lbl]
   [:span {:style {:color "#e2e8f0" :font-size "12px"}} val]])

(defn mono [s]
  [:code {:style {:font-family "JetBrains Mono, monospace" :font-size "11px"
                  :color "#7dd3fc" :background "#0c1a2e" :padding "1px 5px"}}
   s])

(defn warn-box [text]
  [:div {:style {:background "#1c1007" :border "1px solid #d97706" :padding "10px 14px"
                 :margin-bottom "12px"}}
   [:span {:style {:color "#fbbf24" :font-family "JetBrains Mono, monospace"
                   :font-size "10px" :font-weight "700" :margin-right "8px"}}
    "NOTE"]
   [:span {:style {:color "#d97706" :font-size "12px"}} text]])
