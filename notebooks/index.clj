;; # Sew Protocol Research — Navigation Hub
;;
;; **Central landing page for all protocol research notebooks.**
;;
;; Navigate by: security area · threat category · audience role · status
;;
;; Audience: Auditors, Protocol Researchers, Grant Reviewers, Governance Participants

^{:nextjournal.clerk/visibility {:code :fold}
  :nextjournal.clerk/dark-mode true}
(ns notebooks.index
  (:require [nextjournal.clerk :as clerk]
            [resolver-sim.notebook-support.nav :as nav]
            [resolver-sim.notebook-support.common :as common]))

;; ---

(defn- registry-entry
  "Look up a notebook entry from data/notebooks.edn by :id."
  [id]
  (let [registry (common/read-edn "data/notebooks.edn")]
    (first (filter #(= (:id %) id) (:notebooks registry)))))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 [:div
  ;; page header
  [:div {:style {:background "#0f172a" :color "#f8fafc"
                 :padding "24px 28px" :borderRadius "8px" :marginBottom "24px"}}
   [:h1 {:style {:margin "0 0 6px" :fontSize "1.5em" :fontWeight "800"
                  :fontFamily "monospace" :letterSpacing "0.04em"}}
    "Sew Protocol — Research Navigation Hub"]
   [:p {:style {:margin "0" :color "#94a3b8" :fontSize "0.83em" :lineHeight "1.6"}}
    "Structured research workspace for protocol security, adversarial simulation, "
    "economic analysis, and evidence provenance. "
    [:strong {:style {:color "#e2e8f0"}} (str (nav/notebook-count) " notebooks")] " across research domains. "
    "Navigate by category, threat, or audience role."]
   [:div {:style {:display "flex" :gap "16px" :marginTop "14px" :flexWrap "wrap"}}
    (for [[label href icon]
           [            ["Verification Overview"   "/notebooks/verification_overview"           "✅"]
            ["Evidence Workbook"       "/notebooks/report"                          "🛡️"]
            ["Protocol Provenance"     "/notebooks/protocol_provenance"             "📋"]
            ["Clean-Room Corpus"       "/notebooks/clean_room_not_admitted"         "📜"]
            ["Invariant Failures"      "/notebooks/invariant_failures"              "🔍"]
            ["Workbench v2"            "/notebooks/workbench_v2"                    "🔧"]
            ["Security Validation"     "/notebooks/security_validation"             "🛡️"]
            ["Pro-Rata Allocation"     "/notebooks/pro_rata_allocation_result"      "📐"]
            ["Not Admitted"            "/notebooks/not_admitted"                    "⛔"]
            ["Demo: Changed Result"    "/notebooks/demo_not_admitted"               "🔁"]
            ["Demo: Reorder"           "/notebooks/demo_reorder_chain"              "🔀"]
            ["Resubmission Chain"      "/notebooks/resubmission_chain"              "⛓️"]
            ["Allocation Activation"   "/notebooks/allocation_activation"           "✅"]
            ["Authorization Chain"      "/notebooks/authorization_chain"            "🔗"]
            ["Yield Scenarios"         "/notebooks/yield_scenarios_workbench"       "📈"]
            ["Benchmark Report"        "/notebooks/benchmark_protocol_robustness"   "📊"]
            ["Appeal Analysis"         "/notebooks/appeal_analysis"                 "⚖️"]]]
      [:a {:key   label :href href
           :style {:background "#1e293b" :border "1px solid #334155"
                   :borderRadius "5px" :padding "5px 12px"
                   :fontSize "0.78em" :color "#e2e8f0" :textDecoration "none"
                   :fontFamily "monospace"}}
       (str icon " " label)])]]

  ;; recently added section
  (let [recent-ids [:verification-overview :resubmission-chain :canonical-cancellation
                    :not-admitted :demo-not-admitted :demo-reorder-chain
                    :clean-room-not-admitted :canonical-framing
                    :evaluate-pro-rata-allocation :pro-rata-allocation-result
                    :ef-demo-pro-rata-allocation :not-governance
                    :partial-liquidity-recovery :yield-shortfall-partial-withdrawal-fills]
        entries (keep registry-entry recent-ids)]
    (when (seq entries)
      [:div {:style {:marginBottom "24px"}}
       [:h2 {:style {:fontFamily "monospace" :fontSize "1em" :fontWeight "700"
                     :color "#7ADDDC" :letterSpacing "0.04em" :marginBottom "12px"}}
        "📌 Recently Added"]
       [:div {:style {:display "grid" :gridTemplateColumns "repeat(auto-fill, minmax(280px, 1fr))" :gap "12px"}}
        (for [e entries]
          (let [href (str "/" (clojure.string/replace (:path e) #"\.clj$" ""))]
            [:div {:key (name (:id e))
                   :style {:background "#0f172a" :border "1px solid #134e4a"
                           :borderRadius "6px" :padding "12px 14px"}}
             [:a {:href href :style {:fontWeight "700" :fontSize "0.85em" :color "#7ADDDC"
                                     :textDecoration "none" :display "block" :marginBottom "4px"}}
              (:title e)]
             [:p {:style {:margin "0" :fontSize "0.75em" :color "#94a3b8" :lineHeight "1.5"}}
              (:summary e)]]))]]))

  ;; hub grid
  (nav/notebook-hub)])
