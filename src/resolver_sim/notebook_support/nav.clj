(ns resolver-sim.notebook-support.nav
  "Shared navigation components for Clerk notebooks.

  All rendering is pure hiccup/HTML — no SCI state required.
  Links use plain <a href='/notebooks/X'> so they work in static exports.

  Primary entrypoints:
    top-nav-bar     — compact category strip for top of each notebook
    notebook-hub    — full hub card grid for index.clj
    status-badge    — live status derived from findings/issues artifacts"
  (:require [clojure.string :as str]
            [resolver-sim.evidence.config :as evcfg]
            [resolver-sim.notebook-support.common :as common]))

;; ── registry loading ──────────────────────────────────────────────────────────

(defn- load-registry []
  (common/read-edn "data/notebooks.edn"))

(defn- notebook-url [path]
  (str "/" (str/replace path #"\.clj$" "")))

;; ── status helpers ────────────────────────────────────────────────────────────

(def ^:private status-cfg
  {:verified       {:label "VERIFIED"       :bg "#dcfce7" :border "#16a34a" :fg "#166534" :icon "✅"}
   :partial        {:label "PARTIAL"        :bg "#fef9c3" :border "#ca8a04" :fg "#713f12" :icon "🟡"}
   :experimental   {:label "EXPERIMENTAL"   :bg "#ede9fe" :border "#7c3aed" :fg "#4c1d95" :icon "🧪"}
   :draft          {:label "DRAFT"          :bg "#f1f5f9" :border "#94a3b8" :fg "#475569" :icon "📝"}
   :stale          {:label "STALE"          :bg "#fff7ed" :border "#ea580c" :fg "#7c2d12" :icon "⚠️"}
   :requires-review {:label "REVIEW NEEDED" :bg "#fef3c7" :border "#d97706" :fg "#78350f" :icon "👁️"}
   :failed         {:label "FAILED"         :bg "#fee2e2" :border "#dc2626" :fg "#7f1d1d" :icon "❌"}})

(defn- status->cfg [status]
  (get status-cfg status (get status-cfg :draft)))

(defn status-badge
  "Render a coloured status chip for a notebook status keyword."
  [status]
  (let [{:keys [label bg border fg icon]} (status->cfg status)]
    [:span {:style {:display "inline-block" :padding "2px 7px"
                    :borderRadius "4px" :fontSize "0.7em" :fontWeight "700"
                    :letterSpacing "0.04em"
                    :background bg :border (str "1px solid " border) :color fg}}
     (str icon " " label)]))

(defn risk-badge
  "Render a risk classification chip."
  [risk]
  (let [[bg border fg label]
        (case risk
          :high          ["#fee2e2" "#ef4444" "#991b1b" "HIGH RISK"]
          :medium        ["#fef3c7" "#f59e0b" "#92400e" "MED RISK"]
          :low           ["#f0fdf4" "#22c55e" "#166534" "LOW RISK"]
          :informational ["#f1f5f9" "#94a3b8" "#475569" "INFO"]
          ["#f1f5f9" "#94a3b8" "#475569" (str/upper-case (name risk))])]
    [:span {:style {:display "inline-block" :padding "2px 6px"
                    :borderRadius "4px" :fontSize "0.65em" :fontWeight "600"
                    :background bg :border (str "1px solid " border) :color fg}}
     label]))

;; ── quick stats from artifacts ────────────────────────────────────────────────

(defn notebook-count []
  (let [registry (load-registry)]
    (count (:notebooks registry))))

(defn- artifact-stats []
  (let [findings     (common/read-json (evcfg/artifact-path :findings))
        issues       (common/read-json (evcfg/artifact-path :issues))
        test-run     (common/read-json (evcfg/artifact-path :test-run))
        registry     (load-registry)
        all-nb       (:notebooks registry)
        verified     (count (filter #(= :verified (:status %)) all-nb))
        total        (count all-nb)]
    {:findings-count (count (:findings findings))
     :issues-count   (count (:issues issues))
     :verified-count verified
     :total-count    total
     :run-id         (:run_id test-run)
     :commit         (get-in test-run [:framework :git_commit])}))

;; ── top nav bar ───────────────────────────────────────────────────────────────

(def ^:private nav-links
  "Ordered quick-nav links shown in every notebook's top bar."
  [{:label "Hub"        :path "notebooks/index.clj"                          :icon "🏠"}
   {:label "Evidence"   :path "notebooks/report.clj"                         :icon "🛡️"}
   {:label "Provenance" :path "notebooks/protocol_provenance.clj"            :icon "📋"}
   {:label "Invariants" :path "notebooks/invariant_failures.clj"             :icon "🔍"}
   {:label "Workbench"  :path "notebooks/workbench_v2.clj"                   :icon "🔧"}
   {:label "Security"   :path "notebooks/security_validation.clj"            :icon "🛡️"}
   {:label "Pro-Rata"   :path "notebooks/pro_rata_allocation_result.clj"     :icon "📐"}
   {:label "Not Admitted":path "notebooks/not_admitted.clj"                  :icon "⛔"}
   {:label "Yield Scenarios":path "notebooks/yield_scenarios_workbench.clj"  :icon "📈"}
   {:label "Yield Shortfall":path "notebooks/yield_shortfall_partial_withdrawal_fills.clj" :icon "💧"}
   {:label "Demos"      :path "notebooks/demo_adversarial_escalation.clj"    :icon "🎬"}])

(defn top-nav-bar
  "Compact navigation strip for the top of any notebook.
  current-path: the notebook's own path string (to highlight active link), or nil."
  ([] (top-nav-bar nil))
  ([current-path]
   [:div {:style {:background "#0f172a" :padding "8px 16px"
                  :display "flex" :alignItems "center" :gap "4px"
                  :flexWrap "wrap" :marginBottom "16px"
                  :borderRadius "6px" :fontFamily "monospace"}}
    [:span {:style {:color "#94a3b8" :fontSize "0.75em" :marginRight "8px" :fontWeight "700"}}
     "PRF RESEARCH"]
    (for [{:keys [label path icon]} nav-links]
      (let [active? (= path current-path)
            href    (notebook-url path)]
        [:a {:key   label
             :href  href
             :style {:padding "4px 10px" :borderRadius "4px" :fontSize "0.78em"
                     :fontWeight (if active? "700" "400")
                     :textDecoration "none"
                     :background (if active? "#334155" "transparent")
                     :color (if active? "#f8fafc" "#94a3b8")
                     :border (if active? "1px solid #475569" "1px solid transparent")}}
         (str icon " " label)]))]))

;; ── notebook card ─────────────────────────────────────────────────────────────

(defn- audience-label [k]
  (case k
    :reviewer "Reviewer" :auditor "Auditor" :researcher "Researcher"
    :grant-reviewer "Grant" :developer "Developer"
    (name k)))

(defn notebook-card
  "Render a notebook card for the hub grid."
  [{:keys [id path title summary categories status risk audience tags threats]}]
  (let [href (notebook-url path)]
    [:div {:style {:border "1px solid #e2e8f0" :borderRadius "8px"
                   :padding "14px 16px" :background "#ffffff"
                   :display "flex" :flexDirection "column" :gap "8px"
                   :transition "box-shadow 0.15s"
                   :cursor "pointer"}
           :onMouseEnter "this.style.boxShadow='0 4px 12px rgba(0,0,0,0.08)'"
           :onMouseLeave "this.style.boxShadow='none'"}
     ;; title + status row
     [:div {:style {:display "flex" :justifyContent "space-between" :alignItems "flex-start" :gap "8px"}}
      [:a {:href  href
           :style {:fontWeight "700" :fontSize "0.92em" :color "#0f172a"
                   :textDecoration "none" :lineHeight "1.3"}}
       title]
      [:div {:style {:display "flex" :gap "4px" :flexShrink "0"}}
       (status-badge status)
       (risk-badge risk)]]
     ;; summary
     [:p {:style {:margin "0" :fontSize "0.78em" :color "#475569" :lineHeight "1.5"}}
      summary]
     ;; meta row: audience + threats
     [:div {:style {:display "flex" :gap "6px" :flexWrap "wrap" :marginTop "2px"}}
      (for [a (take 3 audience)]
        [:span {:key   (name a)
                :style {:background "#f1f5f9" :border "1px solid #cbd5e1"
                        :borderRadius "3px" :padding "1px 6px"
                        :fontSize "0.68em" :color "#475569"}}
         (audience-label a)])
      (for [t (take 2 threats)]
        [:span {:key   (name t)
                :style {:background "#fef2f2" :border "1px solid #fca5a5"
                        :borderRadius "3px" :padding "1px 6px"
                        :fontSize "0.68em" :color "#991b1b"}}
         (name t)])]]))

;; ── category section ──────────────────────────────────────────────────────────

(defn category-section
  "Render one category group with its notebook cards in a responsive grid."
  [cat-key notebooks]
  (let [registry  (load-registry)
        cat-meta  (get-in registry [:categories cat-key])
        label     (or (:label cat-meta) (name cat-key))
        icon      (or (:icon cat-meta) "📁")
        filtered  (filter #(= cat-key (first (:categories %))) notebooks)]
    (when (seq filtered)
      [:div {:style {:marginBottom "28px"}}
       [:h3 {:style {:fontFamily "monospace" :fontSize "0.9em" :fontWeight "700"
                     :color "#334155" :letterSpacing "0.06em"
                     :borderBottom "2px solid #e2e8f0" :paddingBottom "6px"
                     :marginBottom "14px" :textTransform "uppercase"}}
        (str icon " " label " (" (count filtered) ")")]
       [:div {:style {:display "grid"
                      :gridTemplateColumns "repeat(auto-fill, minmax(320px, 1fr))"
                      :gap "12px"}}
        (for [nb filtered]
          [:div {:key (name (:id nb))}
           (notebook-card nb)])]])))

;; ── security concerns bar ─────────────────────────────────────────────────────

(defn security-concerns-bar
  "Render the security concerns quick-nav row.
  Links resolve to notebooks tagged with that threat."
  []
  (let [registry (load-registry)
        threats  (:threats registry)
        nbs      (:notebooks registry)]
    [:div {:style {:background "#0f172a" :padding "12px 16px" :borderRadius "6px"
                   :marginBottom "24px"}}
     [:div {:style {:color "#94a3b8" :fontSize "0.7em" :fontWeight "700"
                    :letterSpacing "0.08em" :marginBottom "8px"}}
      "⚠ SECURITY CONCERNS QUICK-NAV"]
     [:div {:style {:display "flex" :flexWrap "wrap" :gap "6px"}}
      (for [[threat-key {:keys [label severity]}] (sort-by first threats)]
        (let [notebooks-for (filter #(some #{threat-key} (:threats %)) nbs)
              [bg fg border]
              (case severity
                :high   ["#7f1d1d" "#fca5a5" "#ef4444"]
                :medium ["#78350f" "#fcd34d" "#f59e0b"]
                ["#1e3a5f" "#93c5fd" "#3b82f6"])]
          (when (seq notebooks-for)
            [:span {:key   (name threat-key)
                    :title (str (count notebooks-for) " notebook(s): "
                                (str/join ", " (map :title notebooks-for)))
                    :style {:background bg :color fg :border (str "1px solid " border)
                            :borderRadius "4px" :padding "3px 10px"
                            :fontSize "0.72em" :fontWeight "600" :cursor "default"}}
             (str label " (" (count notebooks-for) ")")])))]]))
;; ── quick stats banner ────────────────────────────────────────────────────────

(defn quick-stats-banner
  "Top-of-hub banner showing aggregate run stats."
  []
  (let [{:keys [findings-count issues-count verified-count total-count run-id commit]}
        (artifact-stats)
        stat (fn [n label color]
               [:div {:style {:flex "1" :textAlign "center" :padding "12px"
                              :borderRight "1px solid #e2e8f0"}}
                [:div {:style {:fontFamily "monospace" :fontSize "1.8em"
                               :fontWeight "700" :color color}} n]
                [:div {:style {:fontSize "0.72em" :color "#64748b" :marginTop "2px"}} label]])]
    [:div {:style {:display "flex" :border "1px solid #e2e8f0" :borderRadius "8px"
                   :background "#f8fafc" :marginBottom "24px" :overflow "hidden"}}
     (stat total-count "Notebooks" "#0f172a")
     (stat verified-count "Verified" "#16a34a")
     (stat findings-count "Findings" "#7c3aed")
     (stat issues-count "Open Issues" (if (pos? issues-count) "#dc2626" "#16a34a"))
     [:div {:style {:flex "2" :padding "12px 16px" :fontSize "0.72em" :color "#64748b"}}
      [:div [:strong "Run ID: "] (or run-id "—")]
      [:div {:style {:marginTop "3px"}} [:strong "Commit: "] (or (some-> commit (subs 0 8)) "—")]
      [:div {:style {:marginTop "3px"}} [:strong "Loaded: "] "results/test-artifacts/"]]]))

;; ── full hub ──────────────────────────────────────────────────────────────────

(defn notebook-hub
  "Render the full navigation hub for index.clj.
  Groups all notebooks by their primary category."
  []
  (let [registry   (load-registry)
        nbs        (:notebooks registry)
        cat-order  (->> (:categories registry)
                        (sort-by (fn [[_ v]] (:order v)))
                        (map first))]
    [:div
     (quick-stats-banner)
     (security-concerns-bar)
     (for [cat cat-order]
       [:div {:key (name cat)}
        (category-section cat nbs)])]))
