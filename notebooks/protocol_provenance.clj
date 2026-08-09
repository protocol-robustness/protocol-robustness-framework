^{:nextjournal.clerk/toc true
  :nextjournal.clerk/visibility {:code :fold}
  :nextjournal.clerk/dark-mode true}
(ns notebooks.protocol-provenance
  "Protocol Provenance — Manifest Management Cockpit.

  Single operational surface for evidence bundles, replay provenance,
  benchmark runs, scenario lineage, and publication state.

  Answers: what exactly was tested, under which assumptions, against which
  code, producing which evidence — and can it be independently reproduced?"
  (:require [nextjournal.clerk :as clerk]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [resolver-sim.notebook-support.common :as common]
            [resolver-sim.notebook-support.ui :as ui]
            [resolver-sim.notebook-support.manifest.loader :as loader]
            [resolver-sim.notebook-support.manifest.hash :as mhash]
            [resolver-sim.notebook-support.manifest.dag :as dag]
            [resolver-sim.notebook-support.manifest.claims :as claims]
            [resolver-sim.notebook-support.manifest.diff :as diff]
            [resolver-sim.notebook-support.manifest.publication :as pub]))

;; # Protocol Provenance
;; ## Manifest Management Cockpit

^{:nextjournal.clerk/visibility {:code :hide :result :show}
  :nextjournal.clerk/width :full}
(clerk/html
 [:style "
  @import url('https://fonts.googleapis.com/css2?family=JetBrains+Mono:wght@400;700;800&family=Inter:wght@400;600;900&display=swap');
  .prov-root { background: #020617; color: #7ADDDC; padding: 40px; font-family: 'JetBrains Mono', monospace; min-height: 100vh; }
  .prov-root h2, .prov-root h3 { color: #e2e8f0; margin-top: 32px; }
  .hero-strip { display: grid; grid-template-columns: repeat(3, 1fr); gap: 20px; margin-bottom: 32px; }
  .hero-card  { background: #0f172a; border: 1px solid #004D59; padding: 20px 24px; border-radius: 4px; }
  .hero-label { font-size: 10px; font-weight: 800; letter-spacing: 0.15em; color: #7ADDDC; opacity: 0.6; margin-bottom: 6px; text-transform: uppercase; }
  .hero-value { font-size: 14px; font-weight: 700; color: #e2e8f0; word-break: break-all; }
  .status-pill { display: inline-block; padding: 4px 14px; border-radius: 999px; font-size: 12px; font-weight: 800; letter-spacing: 0.12em; }
  .status-VERIFIED  { background: rgba(34,197,94,0.12); border: 1px solid #22c55e; color: #22c55e; }
  .status-STALE     { background: rgba(245,158,11,0.12); border: 1px solid #f59e0b; color: #f59e0b; }
  .status-DIVERGENT { background: rgba(239,68,68,0.12);  border: 1px solid #ef4444; color: #ef4444; }
  .status-UNSIGNED  { background: rgba(148,163,184,0.12);border: 1px solid #94a3b8; color: #94a3b8; }
  .status-UNKNOWN   { background: rgba(100,116,139,0.12);border: 1px solid #64748b; color: #64748b; }
  .section-divider { border: none; border-top: 1px solid #1e293b; margin: 32px 0; }
  .run-table { width: 100%; border-collapse: collapse; font-size: 12px; }
  .run-table th { text-align: left; padding: 6px 10px; border-bottom: 1px solid #1e293b; color: #94a3b8; font-weight: 600; }
  .run-table td { padding: 6px 10px; border-bottom: 1px solid #0f172a; color: #cbd5e1; }
  .run-table tr:hover td { background: #0f172a; }
  .check-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; }
  .check-item { background: #0f172a; border: 1px solid #1e293b; padding: 12px 14px; border-radius: 4px; }
  .check-label { font-size: 10px; font-weight: 700; color: #7ADDDC; opacity: 0.7; text-transform: uppercase; letter-spacing: 0.1em; margin-bottom: 4px; }
  .check-pass { color: #22c55e; font-size: 18px; }
  .check-fail { color: #ef4444; font-size: 18px; }
  .check-warn { color: #f59e0b; font-size: 18px; }
  .check-note { font-size: 10px; color: #64748b; margin-top: 4px; }
  .artifact-tabs { display: flex; gap: 2px; margin-bottom: 0; }
  .tab-btn { padding: 6px 16px; font-family: 'JetBrains Mono', monospace; font-size: 11px; font-weight: 700; background: #0f172a; border: 1px solid #004D59; border-bottom: none; color: #7ADDDC; cursor: pointer; }
  .tab-btn.active { background: #004D59; color: #fff; }
  .artifact-pane { background: #0f172a; border: 1px solid #004D59; padding: 20px; font-size: 11px; color: #cbd5e1; white-space: pre-wrap; max-height: 420px; overflow-y: auto; border-radius: 0 4px 4px 4px; }
  .dag-container { background: #0f172a; border: 1px solid #004D59; padding: 32px; border-radius: 4px; overflow-x: auto; }
  .claim-row { display: grid; grid-template-columns: 200px 1fr 90px 120px; gap: 12px; padding: 10px 0; border-bottom: 1px solid #1e293b; align-items: start; font-size: 12px; }
  .claim-id { font-weight: 800; color: #7ADDDC; }
  .claim-desc { color: #94a3b8; line-height: 1.4; }
  .pub-card { background: #0f172a; border: 1px solid #004D59; padding: 24px; border-radius: 4px; }
  .hash-display { font-family: monospace; font-size: 11px; color: #7ADDDC; background: #020617; padding: 10px 14px; border-radius: 2px; word-break: break-all; margin-top: 8px; }
 "])

;; ─────────────────────────────────────────────────────────────────────────────
;; Data loading — once at notebook eval time
;; ─────────────────────────────────────────────────────────────────────────────

^{:nextjournal.clerk/visibility {:code :hide :result :hide}
  :nextjournal.clerk/no-cache true}
(def latest-run   (loader/load-focused))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}
  :nextjournal.clerk/no-cache true}
(def all-runs     (loader/list-runs))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def status-kw    (loader/run->status-indicator latest-run))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def all-claims   (claims/load-claims))

;; ─────────────────────────────────────────────────────────────────────────────
;; Section 1 — Executive Summary Header
;; ─────────────────────────────────────────────────────────────────────────────

^{:nextjournal.clerk/visibility {:code :hide :result :show}
  :nextjournal.clerk/width :full}
(clerk/html
 (common/safe-render
  "executive-summary"
  (fn []
    (let [manifest   (:manifest latest-run)
          status-str (str/upper-case (name status-kw))
          run-id     (get manifest :run_id "—")
          git        (get-in manifest [:framework :git_commit] "—")
          git-s      (if (and git (> (count git) 8)) (subs git 0 8) git)
          suite-id   (get-in manifest [:suite :id] "—")
          scenario   (or (get-in manifest [:suite :scenario])
                         (get-in manifest [:suite :selector]) "—")
          scenario-s (last (str/split (str scenario) #"/"))
          dur-ms     (get manifest :duration_ms 0)
          created-at (get manifest :created_at "—")
          created-s  (subs (str created-at) 0 (min 19 (count (str created-at))))
          eng-ver    (get-in manifest [:framework :version] "—")]
      [:div.prov-root
       [:h1 {:style {:fontSize "1.6rem" :fontWeight 900 :color "#ffffff" :marginBottom "4px"}} "PROTOCOL PROVENANCE"]
       [:div {:style {:fontSize "12px" :color "#7ADDDC" :marginBottom "24px" :opacity "0.7"}}
        "Evidence cockpit — Sew Protocol · Manifest Management v1"]

       [:div.hero-strip
        [:div.hero-card
         [:div.hero-label "Status"]
         [:span {:class (str "status-pill status-" status-str)} status-str]]
        [:div.hero-card
         [:div.hero-label "Run ID"]
         [:div.hero-value run-id]]
        [:div.hero-card
         [:div.hero-label "Git Commit"]
         [:div.hero-value git-s]]]

       [:div.hero-strip {:style {:marginTop "0"}}
        [:div.hero-card
         [:div.hero-label "Suite"]
         [:div.hero-value suite-id]]
        [:div.hero-card
         [:div.hero-label "Scenario"]
         [:div.hero-value scenario-s]]
        [:div.hero-card
         [:div.hero-label "Duration"]
         [:div.hero-value (str dur-ms "ms")]]]

       [:div {:style {:fontSize "11px" :color "#64748b" :marginTop "-8px"}}
        "Generated: " created-s
        "  ·  Framework: " eng-ver
        "  ·  Runs on disk: " (count all-runs)
        "  ·  Claims: " (count all-claims)]]))))

;; ─────────────────────────────────────────────────────────────────────────────
;; Section 2 — Run Index
;; ─────────────────────────────────────────────────────────────────────────────

^{:nextjournal.clerk/visibility {:code :hide :result :show}
  :nextjournal.clerk/width :full}
(clerk/html
 (common/safe-render
  "run-index"
  (fn []
    [:div {:style {:background "#020617" :padding "32px" :fontFamily "'JetBrains Mono', monospace"}}
     [:h2 {:style {:color "#e2e8f0" :marginTop "0"}} "Run Index"]
     [:div {:style {:fontSize "11px" :color "#64748b" :marginBottom "16px"}}
      (count all-runs) " run(s) on disk in results/runs/"]
     (if (empty? all-runs)
       [:div {:style {:color "#f59e0b" :padding "12px" :border "1px solid #f59e0b" :borderRadius "4px"}}
        "⚠ No runs found in results/runs/ — run bb run:scenario <scenario> first"]
       [:table.run-table
        [:thead
         [:tr
          [:th "Run ID"] [:th "Slug"] [:th "Suite"] [:th "Scenario"]
          [:th "Status"] [:th "Duration"] [:th "Commit"] [:th "Created"]]]
        [:tbody
         (map (fn [{:keys [run-id slug suite-id scenario status duration-ms git-commit created-at]}]
                (let [status-str  (str/upper-case (or status "unknown"))
                      scenario-s  (when scenario (last (str/split scenario #"/")))
                      git-s       (when git-commit (subs git-commit 0 (min 8 (count git-commit))))]
                  [:tr
                   [:td {:style {:fontFamily "monospace"}} run-id]
                   [:td {:style {:color "#7ADDDC"}} slug]
                   [:td suite-id]
                   [:td {:style {:color "#94a3b8"}} (or scenario-s "—")]
                   [:td [:span {:class (str "status-pill status-" status-str)
                                :style {:fontSize "10px"}} status-str]]
                   [:td {:style {:color "#64748b"}} (str (or duration-ms "—") "ms")]
                   [:td {:style {:fontFamily "monospace" :color "#64748b"}} (or git-s "—")]
                   [:td {:style {:color "#64748b"}} (subs (str created-at) 0 (min 16 (count (str created-at))))]]))
              all-runs)]])])))

;; ─────────────────────────────────────────────────────────────────────────────
;; Section 3 — Reproducibility Status Panel
;; ─────────────────────────────────────────────────────────────────────────────

^{:nextjournal.clerk/visibility {:code :hide :result :show}
  :nextjournal.clerk/width :full}
(clerk/html
 (common/safe-render
  "reproducibility-panel"
  (fn []
    (let [manifest   (:manifest latest-run)
          summary    (:summary latest-run)
          registry   (:registry latest-run)
          checks
          [{:label "Manifest present"
            :status (if manifest :pass :fail)
            :note   (if manifest "test-run.json loaded" "missing test-run.json")}
           {:label "Schema version"
            :status (if (= "test-run.v1" (get manifest :schema_version)) :pass :warn)
            :note   (str "schema: " (get manifest :schema_version "—"))}
           {:label "Overall status"
            :status (if (= "pass" (get summary :overall_status)) :pass :fail)
            :note   (str "status: " (get summary :overall_status "—"))}
           {:label "Artifact hashes present"
            :status (if (seq (get registry :artifacts [])) :pass :fail)
            :note   (str (count (get registry :artifacts [])) " artifact(s)")}
           {:label "Git commit recorded"
            :status (if (get-in manifest [:framework :git_commit]) :pass :warn)
            :note   (or (get-in manifest [:framework :git_commit] "not recorded"))}
           {:label "Environment recorded"
            :status (if (get-in manifest [:environment :os]) :pass :warn)
            :note   (or (get-in manifest [:environment :python]) "not recorded")}
           {:label "Duration recorded"
            :status (if (pos? (get manifest :duration_ms 0)) :pass :warn)
            :note   (str (get manifest :duration_ms 0) "ms")}
           {:label "Triggered-by tag"
            :status (if (get manifest :triggered_by) :pass :warn)
            :note   (or (get manifest :triggered_by) "not set")}]
          render-check (fn [{:keys [label status note]}]
                         (let [[icon cls] (case status
                                            :pass ["✅" "check-pass"]
                                            :fail ["❌" "check-fail"]
                                            :warn ["⚠️"  "check-warn"]
                                            ["?" "check-warn"])]
                           [:div.check-item
                            [:div.check-label label]
                            [:div {:class cls} icon]
                            [:div.check-note note]]))]
      [:div {:style {:background "#020617" :padding "32px" :fontFamily "'JetBrains Mono', monospace"}}
       [:h2 {:style {:color "#e2e8f0" :marginTop "0"}} "Reproducibility Status"]
       [:div {:style {:fontSize "11px" :color "#64748b" :marginBottom "16px"}}
        "8 checks against latest run in results/test-artifacts/"]
       [:div.check-grid (map render-check checks)]]))))

;; ─────────────────────────────────────────────────────────────────────────────
;; Section 4 — Artifact Explorer (interactive: clerk/with-viewer + reagent atom)
;; ─────────────────────────────────────────────────────────────────────────────

;; Serialize artifact bodies on JVM (json/write-str unavailable in SCI).
;; Use keyword keys (:manifest :summary :registry :classification) to survive
;; transit round-trip cleanly — string-keyed maps can lose identity in SCI get.
^{:nextjournal.clerk/visibility {:code :hide :result :hide}
  :nextjournal.clerk/no-cache true}
(def artifact-explorer-data
  (let [serialize (fn [v]
                    (if v
                      (try (json/write-str v {:indent true})
                           (catch Exception _ (pr-str v)))
                      nil))
        h (when (:manifest latest-run)
            (mhash/canonical-hash (:manifest latest-run)))]
    {:manifest       (serialize (:manifest latest-run))
     :summary        (serialize (:summary latest-run))
     :registry       (serialize (:registry latest-run))
     :classification (serialize (:classification latest-run))
     :hash           (or h "—")}))

^{:nextjournal.clerk/visibility {:code :hide :result :show}
  :nextjournal.clerk/width :full}
(clerk/with-viewer
  {:render-fn
   ;; Form-2 Reagent component: outer fn runs once (creates atom),
   ;; inner fn runs on every render. Both use varargs so Clerk can
   ;; pass 1, 2, or 3 arguments without triggering "Invalid arity".
   '(fn [& args]
      (let [data  (first args)
            tabs  [[:manifest       "test-run.json"]
                   [:summary        "test-summary.json"]
                   [:registry       "test-artifacts.json"]
                   [:classification "claimable-classification.json"]]
            !ui   (reagent.core/atom {:active :manifest})]
        (fn [& args]
          (let [data   (first args)
                active (:active @!ui)]
            [:div {:style {:background "#020617" :padding "32px"
                           :font-family "'JetBrains Mono', monospace"}}
             [:h2 {:style {:color "#e2e8f0" :margin-top "0"}} "Artifact Explorer"]
             [:div {:style {:font-size "11px" :color "#64748b" :margin-bottom "16px"}}
              "Inline view of all 4 manifest artifacts from results/test-artifacts/"]
             [:div {:style {:display "flex" :gap "2px" :flex-wrap "wrap"}}
              (for [[k label] tabs]
                ^{:key (name k)}
                [:button {:on-click (fn [_] (swap! !ui assoc :active k))
                          :style    {:padding      "6px 16px"
                                     :font-family  "'JetBrains Mono', monospace"
                                     :font-size    "11px"
                                     :font-weight  "700"
                                     :background   (if (= k active) "#004D59" "#0f172a")
                                     :border       "1px solid #004D59"
                                     :border-bottom "none"
                                     :color        (if (= k active) "#fff" "#7ADDDC")
                                     :cursor       "pointer"}}
                 label])]
             [:pre {:style {:background    "#0f172a"
                            :border        "1px solid #004D59"
                            :padding       "20px"
                            :font-size     "11px"
                            :color         "#cbd5e1"
                            :white-space   "pre-wrap"
                            :max-height    "420px"
                            :overflow-y    "auto"
                            :border-radius "0 4px 4px 4px"
                            :margin        "0"}}
               (or (get data active) "(not found — run bb run:scenario first)")]
              [:div {:style {:font-size "10px" :color "#64748b" :margin-top "12px"}}
               "SHA-256: " (or (:hash data) "\u2014")]]))))}
  artifact-explorer-data)

;; ─────────────────────────────────────────────────────────────────────────────
;; Section 5 — Manifest Dependency DAG
;; ─────────────────────────────────────────────────────────────────────────────

^{:nextjournal.clerk/visibility {:code :hide :result :show}
  :nextjournal.clerk/width :full}
(clerk/html
 (common/safe-render
  "manifest-dag"
  (fn []
    (let [{:keys [nodes]} (dag/build latest-run)
          node-w 160 node-h 54 h-gap 36 v-pad 20
          total-w (+ (* (count nodes) node-w) (* (dec (count nodes)) h-gap) (* 2 v-pad))
          svg-h   140
          cx      (fn [i] (+ v-pad (* i (+ node-w h-gap)) (/ node-w 2)))
          render-node (fn [i {:keys [label note color]}]
                        (let [x  (+ v-pad (* i (+ node-w h-gap)))
                              y  32
                              cx (+ x (/ node-w 2))]
                          [[:rect {:x x :y y :width node-w :height node-h
                                   :fill (or color "#004D59") :rx 3
                                   :stroke "#7ADDDC" :stroke-width "0.5"}]
                           [:text {:x cx :y (+ y 22) :text-anchor "middle"
                                   :fill "#ffffff" :font-size "11"
                                   :font-family "JetBrains Mono, monospace"
                                   :font-weight "700"}
                            label]
                           [:text {:x cx :y (+ y 39) :text-anchor "middle"
                                   :fill "#7ADDDC" :font-size "8.5"
                                   :font-family "JetBrains Mono, monospace"}
                            (or note "")]]))
          render-arrow (fn [i]
                         (let [x1 (+ v-pad (* i (+ node-w h-gap)) node-w)
                               x2 (+ v-pad (* (inc i) (+ node-w h-gap)))
                               xm (/ (+ x1 x2) 2)
                               y  (+ 32 (/ node-h 2))]
                           [[:line {:x1 x1 :y1 y :x2 (- x2 8) :y2 y
                                    :stroke "#004D59" :stroke-width "1.5"}]
                            [:polygon {:points (str (- x2 8) "," (- y 4) " "
                                                    x2 "," y " "
                                                    (- x2 8) "," (+ y 4))
                                       :fill "#004D59"}]]))]
      [:div {:style {:background "#020617" :padding "32px" :fontFamily "'JetBrains Mono', monospace"}}
       [:h2 {:style {:color "#e2e8f0" :marginTop "0"}} "Manifest Dependency Graph"]
       [:div {:style {:fontSize "11px" :color "#64748b" :marginBottom "16px"}}
        "Provenance chain from benchmark to published bundle"]
       [:div.dag-container {:style {:overflowX "auto"}}
        [:svg {:width total-w :height svg-h :style {:display "block"}}
         (concat
          (mapcat (fn [i n] (render-node i n)) (range) nodes)
          (mapcat render-arrow (range (dec (count nodes)))))]]]))))

;; ─────────────────────────────────────────────────────────────────────────────
;; Section 6 — Claim Registry
;; ─────────────────────────────────────────────────────────────────────────────

^{:nextjournal.clerk/visibility {:code :hide :result :show}
  :nextjournal.clerk/width :full}
(clerk/html
 (common/safe-render
  "claim-registry"
  (fn []
    (let [cs-summary (claims/status-summary all-claims)
          status->pill
          (fn [s]
            (let [[lbl cls]
                  (case s
                    :not-falsified ["NOT FALSIFIED" "status-VERIFIED"]
                    :falsified     ["FALSIFIED"     "status-DIVERGENT"]
                    :not-evaluated ["NOT EVALUATED" "status-UNSIGNED"]
                    :inconclusive  ["INCONCLUSIVE"  "status-STALE"]
                    ["—"           "status-UNKNOWN"])]
              [:span {:class (str "status-pill " cls) :style {:fontSize "10px"}} lbl]))
          conf->badge
          (fn [c]
            (let [color (case c :high "#22c55e" :medium "#f59e0b" :bounded "#7ADDDC" "#94a3b8")]
              [:span {:style {:fontSize "10px" :color color :fontWeight "800"}}
               (str/upper-case (name (or c :—)))]))]
      [:div {:style {:background "#020617" :padding "32px" :fontFamily "'JetBrains Mono', monospace"}}
       [:h2 {:style {:color "#e2e8f0" :marginTop "0"}} "Claim Registry"]
       [:div {:style {:display "flex" :gap "24px" :marginBottom "20px" :fontSize "11px"}}
        [:span {:style {:color "#22c55e"}} "✅ " (:not-falsified cs-summary) " not-falsified"]
        [:span {:style {:color "#ef4444"}} "❌ " (:falsified cs-summary) " falsified"]
        [:span {:style {:color "#94a3b8"}} "○ " (:not-evaluated cs-summary) " not-evaluated"]
        [:span {:style {:color "#f59e0b"}} "⚠ " (:inconclusive cs-summary) " inconclusive"]
        [:span {:style {:color "#64748b"}} "total: " (:total cs-summary)]]
       [:div {:style {:borderTop "1px solid #1e293b" :paddingTop "8px"}}
        [:div.claim-row {:style {:color "#94a3b8" :fontWeight "800" :fontSize "10px"}}
         [:span "CLAIM"] [:span "DESCRIPTION / FALSIFIED-IF"] [:span "STATUS"] [:span "CONFIDENCE"]]
        (map (fn [{:keys [claim/id description falsified-if status confidence validated-by]}]
               [:div.claim-row
                [:div
                 [:div.claim-id (name id)]
                 [:div {:style {:fontSize "10px" :color "#64748b" :marginTop "4px"}}
                  (count validated-by) " scenario(s)"]]
                [:div
                 [:div.claim-desc (subs description 0 (min 120 (count description)))]
                 [:div {:style {:fontSize "10px" :color "#64748b" :marginTop "4px"}}
                  "Falsified if: " falsified-if]]
                [:div (status->pill status)]
                [:div (conf->badge confidence)]])
             all-claims)]]))))

;; ─────────────────────────────────────────────────────────────────────────────
;; Section 7 — Manifest Diff Engine
;; ─────────────────────────────────────────────────────────────────────────────

^{:nextjournal.clerk/visibility {:code :hide :result :show}
  :nextjournal.clerk/width :full}
(clerk/html
 (common/safe-render
  "manifest-diff"
  (fn []
    (let [runs     all-runs
          a-data   (when (>= (count runs) 2) (loader/load-run (:run-id (second runs))))
          b-data   (when (>= (count runs) 1) (loader/load-run (:run-id (first runs))))
          diff-res (when (and a-data b-data) (diff/diff-runs a-data b-data))
          summary  (when diff-res (diff/diff-summary diff-res))]
      [:div {:style {:background "#020617" :padding "32px" :fontFamily "'JetBrains Mono', monospace"}}
       [:h2 {:style {:color "#e2e8f0" :marginTop "0"}} "Manifest Diff Engine"]
       [:div {:style {:fontSize "11px" :color "#64748b" :marginBottom "16px"}}
        "Comparing last two runs (newest vs second-newest). "
        "Edit this notebook cell to select specific run-ids."]
       (cond
         (< (count runs) 2)
         [:div {:style {:color "#f59e0b" :padding "12px" :border "1px solid #f59e0b" :borderRadius "4px"}}
          "⚠ Fewer than 2 runs available — run bb run:scenario at least twice to compare"]

         (nil? diff-res)
         [:div {:style {:color "#ef4444"}} "Could not load run data for diff"]

         :else
         [:div
          [:div {:style {:marginBottom "12px" :fontSize "12px" :color "#7ADDDC"}}
           summary]
          (diff/render-diff diff-res)])]))))

;; ─────────────────────────────────────────────────────────────────────────────
;; Section 8 — Signed Publication
;; ─────────────────────────────────────────────────────────────────────────────

^{:nextjournal.clerk/visibility {:code :hide :result :show}
  :nextjournal.clerk/width :full}
(clerk/html (ui/provenance-footer latest-run))
