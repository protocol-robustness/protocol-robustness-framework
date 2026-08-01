^{:nextjournal.clerk/dark-mode true}
(ns notebooks.workbench-v2
  (:require [nextjournal.clerk :as clerk]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [resolver-sim.notebook-support.common :as common]
            [resolver-sim.notebook-support.speds.data :as speds-data]
            [resolver-sim.notebook-support.speds.config :as config]
            [resolver-sim.notebook-support.speds.story :as story]
            [resolver-sim.grounded-amount :as ga]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.protocols.sew.accounting :as acct]
            [resolver-sim.protocols.sew.types :as sew-types]
            [resolver-sim.protocols.sew.projection :as sew-proj]))

;; # Sew Protocol — Production Evidence Workbench
;; ## High-Assurance Protocol Robustness & Adversarial Telemetry

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(defonce !ui-state (atom {}))

(defonce !world-cache (atom {}))

(defn- world-for-trace
  "Replay a scenario trace and cache the terminal world keyed by scenario-id."
  [trace-path cache]
  (or (get @cache trace-path)
      (when-let [trace (try (common/read-json trace-path) (catch Exception _ nil))]
        (try
          (let [result (sew/replay-with-sew-protocol trace)
                world (sew-proj/terminal-world-from-result result)]
            (swap! cache assoc trace-path world)
            world)
          (catch Exception _ nil)))))

(defn- workflow-integer-state
  "Hiccup for integer-keyed world state visible for a given workflow ID.
   Only shows keys present in the world — absent maps are omitted."
  [world wf-id]
  (let [escrow   (get-in world [:escrow-transfers wf-id])
        from     (:from escrow)
        to       (:to escrow)
        level    (get-in world [:dispute-levels wf-id])
        bonds    (get-in world [:bond-balances wf-id])
        claim    (get-in world [:claimable wf-id])
        settle   (get-in world [:pending-settlements wf-id])
        live     (get-in world [:live-states wf-id])
        released (get-in world [:amount-released wf-id])
        prev-dec (get-in world [:previous-decisions wf-id])]
    [:div {:style {:display "grid" :gridTemplateColumns "1fr 1fr" :gap "8px"
                   :fontSize "11px" :color "#cbd5e1" :padding "6px 12px 6px 24px"}}
     [:div
      (when escrow
        [:div {:style {:marginBottom "6px"}}
         [:div {:style {:color "#7ADDDC" :fontWeight 700 :marginBottom "2px"}} "escrow-transfers"]
         (when from
           [:div {:style {:display "flex" :gap "6px" :fontSize "12px" :marginBottom "2px"}}
            [:span {:style {:color "#FF9800" :fontWeight 700 :minWidth "60px"}} "from"]
            [:span {:style {:color "#e2e8f0"}} (pr-str from)]])
         (when to
           [:div {:style {:display "flex" :gap "6px" :fontSize "12px" :marginBottom "4px"}}
            [:span {:style {:color "#03DAC6" :fontWeight 700 :minWidth "60px"}} "to"]
            [:span {:style {:color "#e2e8f0"}} (pr-str to)]])
         (for [[k v] (apply dissoc escrow :from :to)]
           [:div {:style {:display "flex" :gap "6px" :fontSize "10px"}}
            [:span {:style {:color "#64748b" :minWidth "60px"}} (name k)]
            [:span (pr-str v)]])])
      (when level
        [:div {:style {:marginBottom "4px"}}
         [:span {:style {:color "#FF9800" :fontWeight 700}} "dispute-levels "]
         [:span (str level)]])
      (when live
        [:div {:style {:marginBottom "4px"}}
         [:span {:style {:color "#7ADDDC" :fontWeight 700}} "live-states "]
         [:span (str live)]])
      (when released
        [:div {:style {:marginBottom "4px"}}
         [:span {:style {:color "#03DAC6" :fontWeight 700}} "amount-released "]
         [:span (str released)]])]
     [:div
      (when (seq bonds)
        [:div {:style {:marginBottom "6px"}}
         [:div {:style {:color "#7ADDDC" :fontWeight 700 :marginBottom "2px"}} "bond-balances"]
         (for [[addr amt] (sort bonds)]
           [:div {:style {:display "flex" :gap "6px"}}
            [:span {:style {:color "#64748b" :minWidth "60px"}} (pr-str addr)]
            [:span amt]])])
      (when (seq claim)
        [:div {:style {:marginBottom "6px"}}
         [:div {:style {:color "#03DAC6" :fontWeight 700 :marginBottom "2px"}} "claimable"]
         (for [[addr amt] (sort claim)]
           [:div {:style {:display "flex" :gap "6px"}}
            [:span {:style {:color "#64748b" :minWidth "60px"}} (pr-str addr)]
            [:span amt]])])
      (when settle
        [:div {:style {:marginBottom "4px"}}
         [:span {:style {:color "#fbbf24" :fontWeight 700}} "pending-settlements "]
         [:span (pr-str settle)]])
      (when prev-dec
        [:div
         [:div {:style {:color "#cbd5e1" :fontWeight 700 :marginBottom "2px"}} "previous-decisions"]
         (for [[level decision] (sort prev-dec)]
           [:div {:style {:display "flex" :gap "6px"}}
            [:span {:style {:color "#64748b" :minWidth "30px"}} (str "L" level)]
            [:span (pr-str decision)]])])]]))

(defn- normalize-scenario-id [sid]
  (let [s (str sid)]
    (if (str/starts-with? s "scenarios/") s (str "scenarios/" s))))

(defn- safe-prefix [s n fallback]
  (let [v (str (or s fallback ""))]
    (subs v 0 (min n (count v)))))

(defn- extract-workflow-events
  "Scan trace events and extract per-workflow-id event sequences.
   Returns map of workflow-id -> {:scenarios #{scenario-id}
                                   :events [{:action :time :seq :scenario}]
                                   :terminal-state keyword}."
  [traces]
  (let [workflow-actions (fn [events scenario-id]
                           (loop [i 0, events' events, wf-counter 0, acc {}]
                             (if (empty? events')
                               acc
                               (let [e (first events')
                                     action (:action e)
                                     params (:params e)
                                     wf-id (if (= action "create_escrow")
                                             wf-counter
                                             (get params :workflow-id))
                                     next-wf (if (= action "create_escrow") (inc wf-counter) wf-counter)]
                                 (recur (inc i) (rest events') next-wf
                                        (update-in acc [wf-id :events]
                                                   (fnil conj [])
                                                   (assoc e :scenario scenario-id)))))))]
    (reduce (fn [acc trace]
              (let [sid (:scenario-id trace)
                    wf-map (workflow-actions (:events trace) sid)]
                (reduce-kv (fn [m wf-id wf-data]
                             (update-in m [wf-id :scenarios] (fnil conj #{}) sid)
                             (update-in m [wf-id :events] into (:events wf-data)))
                           acc wf-map)))
            {} traces)))

(defn- workflow-terminal-state
  "Infer the terminal state of a workflow from its events."
  [events]
  (let [actions (set (map :action events))]
    (cond
      (actions "refund") :settled-refund
      (actions "execute_pending_settlement") :settled
      (actions "sender_cancel") :cancelled-sender
      (actions "recipient_cancel") :cancelled-recipient
      (actions "auto_cancel_disputed_escrow") :auto-cancelled
      (actions "raise_dispute") :disputed
      (actions "release") :released
      :else :pending)))

(defn- scenario->trace-path [{:keys [path file id]}]
  (or path
      (when (seq file) (str "data/fixtures/traces/" file))
      (let [sid (-> (normalize-scenario-id id)
                    (str/replace #"^scenarios/" ""))]
        (str "data/fixtures/traces/" sid ".trace.json"))))

^{:nextjournal.clerk/visibility {:code :hide :result :show}
  :nextjournal.clerk/width :full}

(clerk/html
 [:div.workbench-container
  [:style "
    /* Global Layout Overrides for Full-Width Immersive Experience */
    .clerk-view, .viewer-notebook, .prose, .max-w-prose, .max-w-5xl, .mx-auto {
      max-width: none !important;
      width: 100% !important;
      margin-left: 0 !important;
      margin-right: 0 !important;
    }
    .workbench-container {
      font-family: 'JetBrains Mono', 'Inter', sans-serif;
      background: #020617;
      color: #7ADDDC;
      padding: 40px;
    }

    /* Mission Control Panel Styles */
    .hero-strip {
      display: grid;
      grid-template-columns: repeat(4, 1fr);
      gap: 20px;
      margin-bottom: 30px;
    }
    .metric-panel {
      background: #0f172a;
      border: 1px solid #004D59;
      padding: 20px;
      border-radius: 4px;
    }

    /* Layout Primitives */
    .grid-layout {
      display: grid;
      grid-template-columns: repeat(12, 1fr);
      gap: 24px;
    }
    .card {
      background: #0f172a;
      border: 1px solid #004D59;
      padding: 24px;
      border-radius: 4px;
      grid-column: span 6;
    }
    .card-title {
      font-weight: 900;
      font-size: 0.8rem;
      text-transform: uppercase;
      letter-spacing: 0.1em;
      color: #7ADDDC;
      margin-bottom: 20px;
      display: flex;
      align-items: center;
      gap: 10px;
    }
    .card-title::before { content: ''; width: 4px; height: 16px; background: #7ADDDC; }
    .trace-block { margin-top: 12px; max-height: 320px; overflow-y: auto; background: #020617; padding: 14px; border: 1px solid #004D59; border-radius: 4px; }
    .scenario-details { border: 1px solid #004D59; border-radius: 4px; padding: 10px 12px; margin-bottom: 10px; background: #0b1220; }
    .scenario-summary { cursor: pointer; color: #e2e8f0; font-size: 12px; }
  "]

  ;; 1. Hero Validation Summary (Artifact-Driven)
   (let [artifacts (speds-data/load-run-artifacts)
        {:keys [summary coverage manifest]} artifacts
        scenarios (sort-by :id (:scenarios coverage))
        options (mapv (fn [s]
                        {:id (normalize-scenario-id (:id s))
                         :trace-path (scenario->trace-path s)})
                      scenarios)
        {:keys [scenario-count replay-match-label]} (speds-data/narrative-metrics artifacts)
        run-id (or (:run_id summary) (:run-id config/protocol-defaults))]
    [:<>
     [:div.hero-strip
      [:div.metric-panel [:div.label "Validation Run"] [:div.value run-id]]
      [:div.metric-panel [:div.label "Invariant Status"] [:div.value (str/upper-case (or (:overall_status summary) "FAIL"))]]
      [:div.metric-panel [:div.label "Replay Match"] [:div.value (or replay-match-label "—")]]
      [:div.metric-panel [:div.label "Scenario Count"] [:div.value (str (or scenario-count 0))]]]

     ;; 2. Observable Sections
     [:div.grid-layout

      ;; A0. Dispute-forking family focus (research workflow)
      [:div.card {:style {:grid-column "span 12"}}
       [:div.card-title "Dispute Forking Family Focus (S26–S33)"]
       (let [forking-scenarios (->> scenarios
                                    (filter (fn [s]
                                              (str/includes? (str/lower-case (or (:id s) ""))
                                                             "forking-strategist")))
                                    (sort-by :id)
                                    vec)]
         [:div
          [:div {:style {:fontSize "12px" :marginBottom "10px" :color "#cbd5e1"}}
           "Search with `bb run:scenario:search forking-strategist` to find related scenarios. This panel shows those scenarios from the latest loaded artifacts."]
          (if (seq forking-scenarios)
            [:table {:style {:width "100%" :borderCollapse "collapse" :fontSize "12px"}}
             [:thead
              [:tr
               [:th {:style {:textAlign "left" :padding "8px" :borderBottom "1px solid #134e4a"}} "Scenario"]
               [:th {:style {:textAlign "left" :padding "8px" :borderBottom "1px solid #134e4a"}} "Purpose"]
               [:th {:style {:textAlign "left" :padding "8px" :borderBottom "1px solid #134e4a"}} "Threat tags"]]]
             [:tbody
              (for [s forking-scenarios]
                [:tr
                 [:td {:style {:padding "8px" :borderBottom "1px solid #0b1220"}}
                  [:code (:id s)]]
                 [:td {:style {:padding "8px" :borderBottom "1px solid #0b1220" :color "#cbd5e1"}}
                  (or (:purpose s) "n/a")]
                 [:td {:style {:padding "8px" :borderBottom "1px solid #0b1220" :color "#cbd5e1"}}
                  (->> (or (:threat-tags s) [])
                       (map name)
                       (str/join ", "))]])]]
            [:div {:style {:fontSize "12px" :color "#fbbf24"}}
             "No forking-family scenarios were found in the currently loaded coverage artifact."])])]

      ;; A. Protocol Atlas (restored)
      [:div.card {:style {:grid-column "span 12"}}
       [:div.card-title "Protocol Atlas"]
       (story/generate-atlas-view artifacts)]

      ;; B. Workflow-ID Explorer with integer-keyed world state drilldown
      [:div.card {:style {:grid-column "span 12"}}
       [:div.card-title "Workflow Explorer"]
       (let [traces (remove nil? (for [{:keys [trace-path]} options
                                       :let [trace (try (common/read-json trace-path) (catch Exception _ nil))]
                                       :when trace] (assoc trace :trace-path trace-path)))
             wf-map (extract-workflow-events traces)
             sorted-wfs (sort-by (comp str key) (seq wf-map))
             scenario-worlds (into {}
                                   (keep (fn [trace]
                                           (when-let [w (world-for-trace (:trace-path trace)
                                                                         !world-cache)]
                                             [(:scenario-id trace) w])))
                                   traces)]
         (if (seq sorted-wfs)
           [:div
            [:div {:style {:fontSize "11px" :opacity 0.85 :marginBottom "10px" :color "#cbd5e1"}}
             "Expand any row to inspect integer-keyed protocol state for that workflow."]
            (for [[wf-id {:keys [scenarios events]}] sorted-wfs
                  :let [state (workflow-terminal-state events)
                        action-summary (->> events
                                            (map :action)
                                            frequencies
                                            (sort-by val >)
                                            (map (fn [[a c]] (str (str/upper-case a) " x" c)))
                                            (str/join ", "))
                        world (some scenario-worlds scenarios)]]
              [:details.scenario-details
               [:summary.scenario-summary
                [:span {:style {:color "#FF9800" :fontWeight 700}} (str wf-id)]
                "  "
                (str/join ", " (sort scenarios))
                "  "
                [:span {:style {:fontSize "10px" :color "#64748b"}} action-summary]
                "  "
                [:span {:style {:color (case state
                                        :released "#03DAC6"
                                        :settled "#03DAC6"
                                        :disputed "#FF9800"
                                        :pending "#7ADDDC"
                                        "#fbbf24")}}
                 (name state)]]
               (if world
                 (workflow-integer-state world wf-id)
                 [:div {:style {:padding "6px 12px" :fontSize "10px" :color "#64748b"}}
                  "Replay world not available for this workflow."])])]
           [:div {:style {:fontSize "12px" :color "#fbbf24"}}
            "No workflow events could be extracted from the loaded traces."]))]

      ;; C. Yield Position Metrics (from golden reports)
      [:div.card {:style {:grid-column "span 12"}}
       [:div.card-title "Yield Position Metrics"]
       (let [golden-reports (:golden-reports artifacts)
             yield-rows (keep (fn [[sid report]]
                                (let [m (:metrics report)]
                                  (when (some #(pos? (long (get m % 0)))
                                              [:yield/position-principal :yield/position-realized
                                               :yield/position-unrealized :yield/position-deferred
                                               :yield/position-haircut :yield/principal])
                                    {:scenario sid
                                     :status (or (:yield/status m) (:yield/position-status m) "n/a")
                                     :principal (or (:yield/position-principal m) (:yield/principal m) 0)
                                     :realized (or (:yield/position-realized m) (:yield/realized m) 0)
                                     :unrealized (or (:yield/position-unrealized m) (:yield/unrealized m) 0)
                                     :deferred (or (:yield/position-deferred m) (:yield/deferred m) 0)
                                     :haircut (or (:yield/position-haircut m) (:yield/haircut m) 0)
                                     :current-value (or (:yield/position-current-value m) (:yield/current-value m) 0)
                                     :available-ratio (:yield/available-ratio m)})))
                              golden-reports)
             sorted-rows (sort-by :principal > yield-rows)]
         (if (seq sorted-rows)
           [:div
            [:div {:style {:fontSize "11px" :opacity 0.85 :marginBottom "14px" :color "#cbd5e1"}}
             "Yield position data from golden replay reports. Scenarios without yield activity are omitted."]
            [:table {:style {:width "100%" :borderCollapse "collapse" :fontSize "11px"}}
             [:thead
              [:tr
               [:th {:style {:textAlign "left" :padding "6px" :borderBottom "1px solid #134e4a"}} "Scenario"]
               [:th {:style {:textAlign "left" :padding "6px" :borderBottom "1px solid #134e4a"}} "Status"]
               [:th {:style {:textAlign "right" :padding "6px" :borderBottom "1px solid #134e4a"}} "Principal"]
               [:th {:style {:textAlign "right" :padding "6px" :borderBottom "1px solid #134e4a"}} "Realized"]
               [:th {:style {:textAlign "right" :padding "6px" :borderBottom "1px solid #134e4a"}} "Unrealized"]
               [:th {:style {:textAlign "right" :padding "6px" :borderBottom "1px solid #134e4a"}} "Deferred"]
               [:th {:style {:textAlign "right" :padding "6px" :borderBottom "1px solid #134e4a"}} "Haircut"]
               [:th {:style {:textAlign "right" :padding "6px" :borderBottom "1px solid #134e4a"}} "Value"]
               [:th {:style {:textAlign "right" :padding "6px" :borderBottom "1px solid #134e4a"}} "Ratio"]]]
             [:tbody
              (for [r sorted-rows]
                [:tr
                 [:td {:style {:padding "6px" :borderBottom "1px solid #0b1220"}}
                  [:code {:style {:fontSize "10px"}} (:scenario r)]]
                 [:td {:style {:padding "6px" :borderBottom "1px solid #0b1220"}
                       :color (if (= (:status r) "active") "#03DAC6" "#cbd5e1")}
                  (name (:status r))]
                 [:td {:style {:padding "6px" :borderBottom "1px solid #0b1220" :textAlign "right" :color "#e2e8f0"}} (str (:principal r))]
                 [:td {:style {:padding "6px" :borderBottom "1px solid #0b1220" :textAlign "right" :color (if (pos? (:realized r)) "#03DAC6" "#64748b")}} (str (:realized r))]
                 [:td {:style {:padding "6px" :borderBottom "1px solid #0b1220" :textAlign "right" :color (if (pos? (:unrealized r)) "#7ADDDC" "#64748b")}} (str (:unrealized r))]
                 [:td {:style {:padding "6px" :borderBottom "1px solid #0b1220" :textAlign "right" :color (if (pos? (:deferred r)) "#fbbf24" "#64748b")}} (str (:deferred r))]
                 [:td {:style {:padding "6px" :borderBottom "1px solid #0b1220" :textAlign "right" :color (if (pos? (:haircut r)) "#ef4444" "#64748b")}} (str (:haircut r))]
                 [:td {:style {:padding "6px" :borderBottom "1px solid #0b1220" :textAlign "right" :color "#e2e8f0"}} (str (:current-value r))]
                 [:td {:style {:padding "6px" :borderBottom "1px solid #0b1220" :textAlign "right" :color "#cbd5e1"}} (if (:available-ratio r) (str (:available-ratio r)) "—")]])]]
            [:div {:style {:fontSize "10px" :marginTop "10px" :color "#64748b"}}
             (str (count sorted-rows) " scenarios with yield position data")]]
           [:div {:style {:fontSize "12px" :color "#fbbf24"}}
            "No yield position data found in golden reports. Yield-specific scenarios may need to be run first."]))]

      ;; D. Threat-tag Heatmap (restored, static)
      [:div.card {:style {:grid-column "span 6"}}
       [:div.card-title "Threat-tag Heatmap"]
       (let [rows (->> (or (:threat-tag-freq coverage) {})
                       (map (fn [[k v]] {:tag (name k) :count v}))
                       (sort-by :count >)
                       (take 20))
             max-count (apply max 1 (map :count rows))]
         [:div
          (for [{:keys [tag count]} rows]
            [:div {:style {:display "grid"
                           :gridTemplateColumns "220px 1fr 42px"
                           :gap "10px"
                           :alignItems "center"
                           :marginBottom "6px"
                           :fontSize "11px"}}
             [:code {:style {:color "#cbd5e1"}} tag]
             [:div {:style {:height "10px"
                            :background "#0b1220"
                            :border "1px solid #134e4a"
                            :borderRadius "999px"
                            :overflow "hidden"}}
              [:div {:style {:width (str (int (* 100.0 (/ count max-count))) "%")
                             :height "100%"
                             :background "#03DAC6"}}]]
             [:span {:style {:color "#7ADDDC" :textAlign "right"}} (str count)]])])]

      ;; E. Game-theoretic claims / honesty surface (restored)
      [:div.card {:style {:grid-column "span 6"}}
       [:div.card-title "Game-Theoretic Claims"]
       (let [tf (take 8 (filter #(= (:purpose %) "theory-falsification") scenarios))]
         [:div
          [:div {:style {:fontSize "12px" :marginBottom "8px" :color "#fbbf24"}}
           "Theory-falsification scenarios (research findings; not necessarily regressions):"]
          [:ul {:style {:fontSize "12px" :color "#cbd5e1" :paddingLeft "18px"}}
           (for [s tf]
             [:li [:code (:id s)] " — " (or (:title s) "(untitled)")])]
          [:div {:style {:fontSize "12px" :marginTop "14px" :color "#fbbf24"}}
           "Open assumptions: cross-chain finality modeling, dynamic liquidity, resolver bond market dynamics."]])]

      ;; F. Clerk-safe drilldown (no React event handlers)
      [:div.card {:style {:grid-column "span 12" :marginTop "30px"}}
       [:div.card-title "Evidence Explorer"]
       [:div {:style {:fontSize "11px" :opacity 0.85 :marginBottom "14px"}}
        "Clerk-safe mode: expand a scenario row to inspect its trace (avoids browser-extension React event interception)."]
       (for [{:keys [id trace-path]} (take 40 options)
             :let [trace (common/read-json trace-path)]]
         [:details.scenario-details
          [:summary.scenario-summary id]
          [:div {:style {:fontSize "10px" :opacity 0.8 :marginTop "8px"}}
           "Trace path: " trace-path]
          [:div.trace-block
           (if (:events trace)
             (for [e (take 80 (:events trace))]
               [:div {:style {:display "flex" :gap "20px" :fontSize "11px" :padding "3px 0" :borderBottom "1px solid #020617" :color "#cbd5e1"}}
                [:span {:style {:color "#004D59" :minWidth "80px"}} (str (:time e) "ms")]
                [:span {:style {:color "#FF9800" :minWidth "150px"}} (str/upper-case (:action e))]
                [:span (pr-str (walk/stringify-keys (:params e)))]])
              [:div "Trace not found for scenario: " id])]])]

      ;; G. Funds Custody Ledger (from live replay via projection layer)
      [:div.card {:style {:grid-column "span 12"}}
       [:div.card-title "Funds Custody Ledger"]
       (let [traces (take 5 (remove nil? (for [{:keys [trace-path]} options
                                               :let [trace (try (common/read-json trace-path) (catch Exception _ nil))]
                                               :when trace] trace)))
             custody-rows (keep (fn [trace]
                                  (try
                                    (let [result (sew/replay-with-sew-protocol trace)
                                          world (sew-proj/terminal-world-from-result result)
                                          fv (sew-proj/funds-ledger-view world)]
                                      (assoc fv :scenario-id (:scenario-id trace)
                                             :world-hash (some-> world hash str)))
                                    (catch Exception _ nil)))
                                traces)]
         (if (seq custody-rows)
           [:div
            [:div {:style {:fontSize "11px" :opacity 0.85 :marginBottom "14px" :color "#cbd5e1"}}
             "Custody ledger from in-process live replay (first 5 scenarios)."]
            (for [row custody-rows
                  :let [by-token (:by-token row)
                        total-held (reduce + 0 (map (comp :held val) by-token))
                        conservation (get-in row [:conservation :holds?])
                        drift-total (get-in row [:conservation :drift-total])
                        reconciled? (and conservation (zero? drift-total))]]
              [:details.scenario-details
               [:summary.scenario-summary
                [:span {:style {:color "#7ADDDC" :fontWeight 700}} (:scenario-id row)]
                "  —  "
                (str (count by-token) " tokens, " total-held " total held")
                "  "
                [:span {:style {:color (if conservation "#03DAC6" "#ef4444") :fontWeight 700}}
                 (if conservation "✓" "✗")]]
               [:div {:style {:fontSize "11px" :color "#cbd5e1" :padding "8px"}}

                ;; ── Custody overview strip ─────────────────────────────────
                [:div {:style {:display "grid" :gridTemplateColumns "repeat(4, 1fr)" :gap "8px" :marginBottom "14px" :fontSize "10px"}}
                 [:div {:style {:padding "8px" :background "#0b1220" :border "1px solid #134e4a" :borderRadius "4px"}}
                  [:span {:style {:color "#64748b"}} "Total held"]
                  [:div {:style {:fontSize "14px" :color "#e2e8f0" :fontWeight 700}} total-held]]
                 [:div {:style {:padding "8px" :background "#0b1220" :border "1px solid #134e4a" :borderRadius "4px"}}
                  [:span {:style {:color "#64748b"}} "Active tokens"]
                  [:div {:style {:fontSize "14px" :color "#e2e8f0" :fontWeight 700}} (count by-token)]]
                 [:div {:style {:padding "8px" :background "#0b1220" :border (str "1px solid " (if conservation "#03DAC6" "#ef4444")) :borderRadius "4px"}}
                  [:span {:style {:color "#64748b"}} "Conservation"]
                  [:div {:style {:fontSize "14px" :color (if conservation "#03DAC6" "#ef4444") :fontWeight 700}}
                   (if conservation "✓ HOLDS" "✗ VIOLATED")]]
                 [:div {:style {:padding "8px" :background "#0b1220" :border "1px solid #134e4a" :borderRadius "4px"}}
                  [:span {:style {:color "#64748b"}} "Drift"]
                  [:div {:style {:fontSize "14px" :color (if (zero? drift-total) "#03DAC6" "#fbbf24") :fontWeight 700}} drift-total]]]

                ;; ── Per-token custody ──────────────────────────────────────
                [:div {:style {:marginBottom "12px"}}
                 [:strong {:style {:color "#7ADDDC"}} "Per-token custody"]
                 [:table {:style {:width "100%" :borderCollapse "collapse" :fontSize "10px" :marginTop "6px"}}
                  [:thead
                   [:tr
                    [:th {:style {:textAlign "left" :padding "4px" :borderBottom "1px solid #134e4a"}} "Token"]
                    [:th {:style {:textAlign "right" :padding "4px" :borderBottom "1px solid #134e4a"}} "Held"]
                    [:th {:style {:textAlign "right" :padding "4px" :borderBottom "1px solid #134e4a"}} "Released"]
                    [:th {:style {:textAlign "right" :padding "4px" :borderBottom "1px solid #134e4a"}} "Refunded"]
                    [:th {:style {:textAlign "right" :padding "4px" :borderBottom "1px solid #134e4a"}} "Withdrawn"]
                    [:th {:style {:textAlign "right" :padding "4px" :borderBottom "1px solid #134e4a"}} "Bond Posted"]
                    [:th {:style {:textAlign "right" :padding "4px" :borderBottom "1px solid #134e4a"}} "Bond Slashed"]
                    [:th {:style {:textAlign "center" :padding "4px" :borderBottom "1px solid #134e4a"}} "Status"]]]
                  [:tbody
                   (for [[token buckets] (sort by-token)
                         :let [all-zero? (every? #(zero? (long (get buckets % 0)))
                                                  [:held :released :refunded :withdrawn :bond-posted :bond-slashed])]]
                     [:tr {:style {:opacity (if all-zero? "0.5" "1.0")}}
                      [:td {:style {:padding "4px" :borderBottom "1px solid #0b1220"}} token]
                      [:td {:style {:padding "4px" :borderBottom "1px solid #0b1220" :textAlign "right" :color "#03DAC6"}} (str (:held buckets))]
                      [:td {:style {:padding "4px" :borderBottom "1px solid #0b1220" :textAlign "right"}} (str (:released buckets))]
                      [:td {:style {:padding "4px" :borderBottom "1px solid #0b1220" :textAlign "right"}} (str (:refunded buckets))]
                      [:td {:style {:padding "4px" :borderBottom "1px solid #0b1220" :textAlign "right"}} (str (:withdrawn buckets))]
                      [:td {:style {:padding "4px" :borderBottom "1px solid #0b1220" :textAlign "right"}} (str (:bond-posted buckets))]
                      [:td {:style {:padding "4px" :borderBottom "1px solid #0b1220" :textAlign "right"}} (str (:bond-slashed buckets))]
                      [:td {:style {:padding "4px" :borderBottom "1px solid #0b1220" :textAlign "center"}}
                       [:span {:style {:color (if all-zero? "#64748b" "#03DAC6") :fontSize "9px"}}
                        (if all-zero? "empty" "active")]]])]]]

                ;; ── Global custody buckets ─────────────────────────────────
                [:div {:style {:marginBottom "12px"}}
                 [:strong {:style {:color "#7ADDDC"}} "Global custody buckets"]
                 [:div {:style {:display "grid" :gridTemplateColumns "repeat(5, 1fr)" :gap "6px" :marginTop "6px" :fontSize "10px"}}
                  (for [[k v] (:global row)]
                    [:div {:style {:padding "6px" :background "#0b1220" :border "1px solid #134e4a" :borderRadius "4px" :textAlign "center"}}
                     [:div {:style {:color "#64748b" :fontSize "9px"}} (name k)]
                     [:div {:style {:color "#e2e8f0" :fontWeight 700 :fontSize "12px" :marginTop "2px"}} (str v)]])]]

                ;; ── Integrity checks ───────────────────────────────────────
                [:div
                 [:strong {:style {:color "#7ADDDC"}} "Integrity checks"]
                 [:div {:style {:marginTop "6px" :fontSize "10px"}}
                  (let [checks [{:label "Conservation of funds"
                                 :pass? conservation
                                 :detail (str "drift=" drift-total)}
                                {:label "All token entries ≤ available"
                                 :pass? (every? #(>= (long %) 0)
                                                (mapcat (fn [[_ b]] [(:held b) (:released b) (:refunded b)
                                                                    (:withdrawn b) (:bond-posted b) (:bond-slashed b)])
                                                        by-token))
                                 :detail "non-negative"}
                                {:label "Drift within tolerance"
                                 :pass? (zero? drift-total)
                                 :detail (str "drift=" drift-total)}]]
                    [:div {:style {:display "grid" :gap "4px"}}
                     (for [c checks]
                       [:div {:style {:display "flex" :alignItems "center" :gap "8px"
                                      :padding "4px 8px" :background "#0b1220" :border "1px solid #134e4a" :borderRadius "4px"}}
                        [:span {:style {:color (if (:pass? c) "#03DAC6" "#ef4444") :fontWeight 700}}
                         (if (:pass? c) "✓" "✗")]
                        [:span {:style {:color "#cbd5e1"}} (:label c)]
                        [:span {:style {:color "#64748b" :marginLeft "auto"}} (:detail c)]])])]]]])]
           [:div {:style {:fontSize "12px" :color "#fbbf24"}}
             "No custody data could be computed from live replay."]))]]])])

;; # Primitive Inspectors — grounded-amount & add-held
;; Thin viewers over production surfaces: the grounded-amount projection contract
;; and the add-held custody primitive, exercised through the same public APIs the
;; accounting tests use (no reimplemented business rules).

^{:nextjournal.clerk/visibility {:code :hide :result :show}
  :nextjournal.clerk/width :full}

(clerk/html
 [:div.workbench-container
  [:div.grid-layout
   [:div.card {:style {:grid-column "span 12"}}
    [:div.card-title "Primitive Inspectors — grounded-amount & add-held"]
    (let [ga-proj (ga/grounded-amount 250 :0xUSDC :escrow-principal "terminal-world-root"
                                      :as-of-root "terminal-world-root")
          w0 (sew-types/empty-world 1000)
          w1 (acct/add-held w0 :0xUSDC 500 {:action "demo-add-held" :reason :escrow-created
                                             :extra {:held/workflow-id 0 :held/actor "0xBuyer"}})
          w2 (acct/add-held w1 :0xUSDC 250 {:action "demo-add-held" :reason :escrow-created
                                             :extra {:held/workflow-id 0 :held/actor "0xBuyer"}})
          total-held (get-in w2 [:total-held :0xUSDC] 0)
          adj-count (count (:held-adjustments w2))]
      [:div {:style {:display "grid" :gridTemplateColumns "repeat(3,1fr)" :gap "8px" :fontSize "10px"}}
       [:div {:style {:padding "8px" :background "#0b1220" :border "1px solid #134e4a" :borderRadius "4px"}}
        [:div {:style {:color "#7ADDDC" :fontWeight 700 :marginBottom "6px"}} "grounded-amount (projection)"]
        (for [[k v] ga-proj]
          [:div {:style {:display "flex" :gap "6px" :padding "1px 0"}}
           [:span {:style {:color "#64748b" :minWidth "90px"}} (name k)]
           [:span {:style {:color "#cbd5e1"}} (pr-str v)]])]
       [:div {:style {:padding "8px" :background "#0b1220" :border "1px solid #134e4a" :borderRadius "4px"}}
        [:div {:style {:color "#7ADDDC" :fontWeight 700 :marginBottom "6px"}} "add-held (custody mutation)"]
        [:div {:style {:display "flex" :gap "6px" :padding "1px 0"}}
         [:span {:style {:color "#64748b" :minWidth "90px"}} "after +500"]
         [:span {:style {:color "#e2e8f0"}} (get-in w1 [:total-held :0xUSDC] 0)]]
        [:div {:style {:display "flex" :gap "6px" :padding "1px 0"}}
         [:span {:style {:color "#64748b" :minWidth "90px"}} "after +250"]
         [:span {:style {:color "#03DAC6" :fontWeight 700}} total-held]]
        [:div {:style {:display "flex" :gap "6px" :padding "1px 0"}}
         [:span {:style {:color "#64748b" :minWidth "90px"}} "adjustments"]
         [:span {:style {:color "#cbd5e1"}} adj-count]]]
       [:div {:style {:padding "8px" :background "#0b1220" :border "1px solid #134e4a" :borderRadius "4px"}}
        [:div {:style {:color "#7ADDDC" :fontWeight 700 :marginBottom "6px"}} "Why"]
        [:div {:style {:color "#cbd5e1" :fontSize "10px"}}
         "grounded-amount grounds a bare number with token, basis, and source/as-of roots. add-held is the production custody primitive exercised through the exact public API the accounting tests use. Both are thin viewers over production surfaces."]]])]]])
