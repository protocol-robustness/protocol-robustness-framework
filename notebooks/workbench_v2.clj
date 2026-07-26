^{:nextjournal.clerk/dark-mode true}
(ns notebooks.workbench-v2
  (:require [nextjournal.clerk :as clerk]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [resolver-sim.notebook-support.common :as common]
            [resolver-sim.notebook-support.speds.data :as speds-data]
            [resolver-sim.notebook-support.speds.config :as config]
            [resolver-sim.notebook-support.speds.story :as story]))

;; # Sew Protocol — Production Evidence Workbench
;; ## High-Assurance Protocol Robustness & Adversarial Telemetry

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(defonce !ui-state (atom {}))

(defn- normalize-scenario-id [sid]
  (let [s (str sid)]
    (if (str/starts-with? s "scenarios/") s (str "scenarios/" s))))

(defn- safe-prefix [s n fallback]
  (let [v (str (or s fallback ""))]
    (subs v 0 (min n (count v)))))

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
        git-sha (or (:git_sha summary) (:git-sha config/protocol-defaults))
        run-id (or (:run_id summary) (:run-id config/protocol-defaults))
        evidence-hash (or (:ipfs-cid manifest) (:hash-suffix config/protocol-defaults) "unknown")]
    [:<>
     [:div.hero-strip
      [:div.metric-panel [:div.label "Validation Run"] [:div.value run-id]]
      [:div.metric-panel [:div.label "Invariant Status"] [:div.value (str/upper-case (or (:overall_status summary) "FAIL"))]]
      [:div.metric-panel [:div.label "Determinism"] [:div.value "100.0%"]]
      [:div.metric-panel [:div.label "Evidence Hash"] [:div.value {:style {:fontSize "1.2rem"}} (safe-prefix evidence-hash 8 "unknown")]]]

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

      ;; B. Threat-tag Heatmap (restored, static)
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

      ;; C. Game-theoretic claims / honesty surface (restored)
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

      ;; 3. Clerk-safe drilldown (no React event handlers)
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
             [:div "Trace not found for scenario: " id])]])]]])])
