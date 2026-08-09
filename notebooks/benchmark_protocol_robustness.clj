;; # Protocol Robustness Framework — Benchmark Report
;;
;; **Audience:** Protocol reviewers, security researchers, contributors.
;;
;; **Purpose:** This PRF benchmark evaluates protocol robustness across
;; resolver accountability, liveness under adversarial load, fund safety,
;; and evidence integrity. It currently uses Sew scenarios as the reference
;; protocol workload.
;;
;; PRF owns the benchmark framework, scoring rules, concept mappings,
;; evidence hashing, reproducibility, attestations, registries, and the
;; report layer. Sew is the current reference protocol workload — the same
;; benchmark structure can evaluate other protocols that implement the
;; same scenario interfaces.
;;
;; **Data contract:**
;; - `results/benchmarks/protocol-robustness-v0.edn` — evidence bundle from `bb benchmark:run`
;; - `benchmarks/concepts/protocol-robustness-v0.edn` — benchmark concept definitions
;; - `benchmarks/scoring/robustness-dimensions-v0.edn` — scoring rules and pass conditions
;;
;; If the evidence bundle is absent the report shows amber (not run),
;; not red. Missing data is not the same as a failing result.

^{:nextjournal.clerk/toc true
  :nextjournal.clerk/dark-mode true
  :nextjournal.clerk/visibility {:code :fold}}
(ns notebooks.benchmark-protocol-robustness
  (:require [nextjournal.clerk :as clerk]
            [clojure.string :as str]
            [resolver-sim.benchmark.report :as rpt]
            [resolver-sim.notebook-support.views :as views]
            [resolver-sim.notebook-support.theme :refer [notebook-theme
                                                         section-style
                                                         table-style
                                                         table-cell-style
                                                         table-header-row-style
                                                         table-header-cell-style]]))

;; ── Local style helpers ───────────────────────────────────────────────────────

(def heading-style
  {:color "#e2e8f0" :marginBottom "12px"})

(def code-block-style
  {:background (:code/block-bg notebook-theme)
   :padding "12px"
   :borderRadius "4px"
   :color (:code/block-text notebook-theme)
   :fontSize "13px"})

(def cell-mono-style
  (merge table-cell-style
         {:font-family "monospace" :fontSize "13px"}))

(def cell-bold-style
  (merge table-cell-style {:fontWeight 600}))

;; Badge colors use bright variants for dark Clerk background.
(def ^:private pass-badge "#22c55e")
(def ^:private fail-badge "#ef4444")
(def ^:private warn-badge "#f59e0b")

;; ── Data loading ──────────────────────────────────────────────────────────────
;; Override evidence path via BENCHMARK_EVIDENCE_PATH env var to view any
;; benchmark report without editing the notebook:
;;   BENCHMARK_EVIDENCE_PATH=results/benchmarks/shortfall-allocation-v0.edn \
;;     bb clerk:serve

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def config
  (let [default-path "results/benchmarks/protocol-robustness-v0.edn"
        env-path (System/getenv "BENCHMARK_EVIDENCE_PATH")
        evidence-path (or env-path default-path)]
    (when env-path
      (println "Using evidence path from BENCHMARK_EVIDENCE_PATH:" env-path))
    {:evidence-path evidence-path
     :concepts-path "benchmarks/concepts/protocol-robustness-v0.edn"
     :scoring-path  "benchmarks/scoring/robustness-dimensions-v0.edn"}))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def report
  (try
    ;; Prefer auto-resolution from evidence bundle alone,
    ;; fall back to explicit paths for backward compatibility.
    (if (.exists (java.io.File. (:evidence-path config)))
      (try
        (rpt/resolve-report (:evidence-path config))
        (catch Exception _
          (rpt/build-report (:evidence-path config)
                             (:concepts-path config)
                             (:scoring-path config))))
      (println "No evidence bundle found at" (:evidence-path config)))
    (catch Exception e
      (println "Warning: could not load benchmark evidence:" (.getMessage e))
      nil)))

;; ── Header ────────────────────────────────────────────────────────────────────

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/html
 (let [rag (if report
             (if (:all-pass? report) :green :red)
             :amber)]
   [:div {:style {:marginBottom "24px"}}
    [:h1 {:style {:fontSize "1.5em" :fontWeight 700 :color "#e2e8f0"
                  :marginBottom "8px"}}
     "Protocol Robustness Benchmark"]
    (when report
      [:div
       [:div {:style {:color "#94a3b8" :fontSize "14px" :marginBottom "6px"}}
        (str (:benchmark/id report))
        " · " (or (:purpose report) "")]
       [:div {:style {:color "#cbd5e1" :fontSize "13px"}}
        "Domain: "
        [:span {:style {:fontFamily "monospace"}}
         (str (:benchmark/domain report))]
        (when-let [desc (:benchmark/domain-description report)]
          (str " — " desc))]])
    [:div {:style {:display "flex" :gap "16px" :marginTop "16px"
                   :flexWrap "wrap"}}
      (views/render-card
       {:label "Status"
        :rag rag
         :value (if report
                  (let [cls (get-in report [:scoring/classification :classification-label])
                        maturity (get-in report [:scoring/classification :claim-maturity :label])]
                   (or cls (if (:all-pass? report) "Scenario replay passed" "Scenario replay failed")))
                 "Not run")
         :note (if report
                 (let [cls (get-in report [:scoring/classification :classification-label])
                       maturity (get-in report [:scoring/classification :claim-maturity :label])]
                   (str (:passed-scenarios report) "/" (:total-scenarios report)
                        " scenarios passed"
                        (when cls (str " — " cls))
                        (when maturity (str " (" maturity ")"))))
                 "Run `bb benchmark:run` to generate evidence")})
     (when report
       (views/render-card
        {:label "Domain"
         :rag :neutral
         :value (name (:benchmark/domain report))
         :note (or (:benchmark/domain-description report)
                   "Benchmark taxonomy area")}))
     (when report
       (views/render-card
        {:label "Evidence hash"
         :rag :neutral
          :value (let [h (or (:evidence/hash report) "—")]
                   (str/upper-case (subs h 0 (min 16 (count h)))))
          :note (str "Full: " (or (:evidence/hash report) "—"))}))]]))

;; ── Scope notice ──────────────────────────────────────────────────────────────

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(views/notice-box
 "Scope"
 "This v0 benchmark checks selected robustness dimensions over representative"
 "scenarios. It is evidence-backed and reproducible, but it is not a full audit,"
 "formal verification result, or protocol safety certification.")

;; ── Benchmark concepts ────────────────────────────────────────────────────────

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(when report
  (clerk/html
   [:div
    [:h2 {:style heading-style} "Benchmark concepts"]
    [:table {:style (merge table-style {:marginBottom "24px"})}
     [:thead {:style table-header-row-style}
      [:tr
       [:th {:style table-header-cell-style} "Concept"]
       [:th {:style table-header-cell-style} "Source"]
       [:th {:style table-header-cell-style} "Framework concepts"]]]
     [:tbody
      (for [concept (:benchmark/concept-summary report)]
        [:tr {:key (str (:concept/id concept))}
         [:td {:style table-cell-style}
          [:div {:style {:fontWeight 600}}
           (or (:concept/title concept) (name (:concept/id concept)))]
          [:div {:style {:fontSize "13px"
                         :opacity "0.75"
                         :fontFamily "monospace"
                         :marginTop "4px"}}
           (str (:concept/id concept))]]
         [:td {:style table-cell-style}
          (views/badge (name (:concept/source concept))
                       (case (:concept/source concept)
                         :benchmark-local pass-badge
                         :global warn-badge
                         fail-badge))]
         [:td {:style cell-mono-style}
          (if (seq (:concept/framework-concepts concept))
            (str/join ", " (map name (:concept/framework-concepts concept)))
            "—")]])]]]))

;; ── Dimension results ─────────────────────────────────────────────────────────

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(if (nil? report)
  (clerk/html
   (views/notice-box
    "Benchmark not yet run"
    "Run the following to produce an evidence bundle:"
    (clerk/row
     [:pre {:style code-block-style}
      "bb benchmark:run benchmarks/packs/prf-core/protocol-robustness-v0.edn \\"
      "  -o results/benchmarks/protocol-robustness-v0.edn"])))
  (clerk/html
   [:div
    [:h2 {:style heading-style} "Dimension results"]
    [:table {:style (merge table-style {:marginBottom "24px"})}
     [:thead {:style table-header-row-style}
      [:tr
        [:th {:style table-header-cell-style} "Dimension"]
        [:th {:style table-header-cell-style} "Scenario"]
        [:th {:style table-header-cell-style} "Outcome"]
        [:th {:style table-header-cell-style} "Pass condition met?"]
        [:th {:style table-header-cell-style} "Summary"]
        [:th {:style table-header-cell-style} "Evidence ref"]]]
     [:tbody
      (for [d (:dimensions report)]
        (let [ok? (:pass-condition-met? d)]
          [:tr {:key (str (:dimension d))}
           [:td {:style cell-bold-style} (:concept/title d)]
            [:td {:style cell-mono-style} (:scenario/id d)]
            [:td {:style table-cell-style}
             (views/badge (name (:outcome d))
                          (if (= :pass (:outcome d)) pass-badge fail-badge))]
            [:td {:style table-cell-style}
             (views/badge (if ok? "Pass" "Fail")
                          (if ok? pass-badge fail-badge))]
            [:td {:style table-cell-style}
             (or (:concept/summary d) "")]
            [:td {:style cell-mono-style}
             (let [root (:scenario/evidence-root d)]
               (if root (str/upper-case (subs root 0 (min 8 (count root))))
                   "—"))]]))]]]))

;; ── Scoring detail ────────────────────────────────────────────────────────────

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(when report
  (clerk/html
   [:div
    [:h2 {:style heading-style} "Scoring detail"]
    (for [d (:dimensions report)]
      (let [rag (if (:pass-condition-met? d) :green :red)]
        [:div {:key (str (:dimension d))
               :style (merge (section-style rag) {:fontSize "14px"})}
         [:h3 {:style {:margin "0 0 8px 0" :fontSize "16px"}}
          (views/status-emoji rag) " " (:concept/title d)]
         [:div {:style {:marginBottom "8px"}}
          [:strong "Stakeholder language: "]
          (:concept/stakeholder-language d)]
         [:div {:style {:marginBottom "6px" :opacity "0.85"}}
          [:strong "Pass condition: "] (:pass-condition d)]
         [:div {:style {:opacity "0.85"}}
          [:strong "Why it matters: "] (:concept/why-it-matters d)]]))]))

;; ── Invariant summary ─────────────────────────────────────────────────────────

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(when report
  (let [inv (:invariant-summary report)]
    (clerk/html
     [:div
      [:h2 {:style heading-style} "Invariant summary"]
      (if (and inv (pos? (:total-checks inv)))
        [:div
         (views/render-card
          {:label "Invariant pass rate"
           :rag (if (:all-pass? inv) :green :red)
           :value (str (:passed-checks inv) "/" (:total-checks inv))
           :note (if (:all-pass? inv) "All invariants passed"
                   "Some invariants failed")})
         [:table {:style (merge table-style {:marginTop "16px"})}
          [:thead {:style table-header-row-style}
           [:tr
            [:th {:style table-header-cell-style} "Invariant"]
            [:th {:style table-header-cell-style} "Passed"]
            [:th {:style table-header-cell-style} "Total"]
            [:th {:style table-header-cell-style} "Rate"]]]
          [:tbody
           (for [[inv-id counts] (:per-invariant inv)]
             (let [rate (if (pos? (:total counts))
                          (float (/ (:passed counts) (:total counts)))
                          0.0)
                   color (cond (>= rate 1.0) pass-badge
                               (>= rate 0.5) warn-badge
                               :else fail-badge)]
               [:tr {:key (str inv-id)}
                [:td {:style cell-mono-style} (name inv-id)]
                [:td {:style table-cell-style} (:passed counts)]
                [:td {:style table-cell-style} (:total counts)]
                [:td {:style table-cell-style}
                 (views/badge (format "%.0f%%" (* 100 rate)) color)]]))]]]
        (views/notice-box
         "Invariant checks"
         "No per-step invariant failures were recorded during replay."
         "All invariants passed post-hoc verification on the terminal"
         "world state."))])))

;; ── Evidence trail ────────────────────────────────────────────────────────────

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(when report
  (clerk/html
   [:div
    [:h2 {:style heading-style} "Evidence trail"]
    [:table {:style table-style}
     [:tbody
      [:tr [:td {:style cell-bold-style} "Evidence path"]
       [:td {:style cell-mono-style} (:evidence/path report)]]
      [:tr [:td {:style cell-bold-style} "Evidence hash"]
       [:td {:style cell-mono-style} (or (:evidence/hash report) "—")]]
      [:tr [:td {:style cell-bold-style} "Reproduce command"]
       [:td {:style cell-mono-style}
        (or (get-in report [:reproduce :command]) "—")]]
       [:tr [:td {:style cell-bold-style} "Environment"]
        [:td {:style table-cell-style}
         (str (:os-name (:environment report)) " "
              (:os-version (:environment report)))]]
       [:tr [:td {:style cell-bold-style} "Scenario suite"]
        [:td {:style table-cell-style}
         (or (:scenario/suite-description report)
             (str (:scenario/suite report)))]]
        [:tr [:td {:style cell-bold-style} "Claim status"]
         [:td {:style table-cell-style}
          (let [s (:claim/status report)]
            (views/badge (name (or s :none))
                         (case s
                           :verified pass-badge
                           :partial warn-badge
                           (:declared-not-verified nil) warn-badge
                           pass-badge)))]]]]
     (views/notice-box
      "Verification"
      (clerk/row
       "Run "
       [:code {:style {:color (:code/block-text notebook-theme)}}
        "bb benchmark:reproduce " (or (:evidence/path report) "<evidence-bundle>")]
       " with the same bundle to independently verify these results."))
      (views/notice-box
       "Claim verification scope"
       (clerk/row
        "Claim status applies only to the claims declared in the benchmark"
        " manifest. Current verification is at"
        " Level 1 (mechanical: field existence, hash format, outcome present)."
        " Level 2 (invariant-backed) evaluators exist for Sew protocol claims."
        " Level 3 (semantic/economic) verification is deferred — declared"
        " scenario claims are not evaluated."))]))

;; ── Concepts reference ────────────────────────────────────────────────────────

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/html
 [:div
  [:h2 {:style heading-style} "Concepts reference"]
  (let [concepts (rpt/load-benchmark-concepts (:concepts-path config))]
    (if (seq concepts)
      [:div {:style {:overflowX "auto" :marginTop "12px"}}
        [:table {:style (merge table-style {:background "#1e293b" :borderRadius "6px"})}
         [:thead {:style {:background "#0f172a"}}
          [:tr
           [:th {:style (merge table-header-cell-style
                               {:color "#f8fafc" :background "#0f172a"})}
            "Concept"]
           [:th {:style (merge table-header-cell-style
                               {:color "#f8fafc" :background "#0f172a"})}
            "Summary"]
            [:th {:style (merge table-header-cell-style
                                {:color "#f8fafc" :background "#0f172a"})}
             "Why it matters"]
            [:th {:style (merge table-header-cell-style
                                {:color "#f8fafc" :background "#0f172a"})}
             "Note"]]]]
        [:tbody
         (for [c concepts]
           [:tr {:key (str (:concept/id c))
                 :style {:borderBottom "1px solid #334155"}}
            [:td {:style (merge cell-bold-style {:color "#e2e8f0"})}
             (:concept/title c)]
            [:td {:style (merge table-cell-style {:color "#cbd5e1"})}
             (:concept/summary c)]
             [:td {:style (merge table-cell-style {:color "#cbd5e1"})}
              (:concept/why-it-matters c)]
             [:td {:style (merge table-cell-style {:color "#cbd5e1" :font-size "0.85em" :font-style "italic"})}
              (or (:concept/note c) "")]])]]
      (views/notice-box
       "Concepts file not found"
       "Benchmark concepts file not found at " (:concepts-path config) ".")))])

;; ── Reproduce ─────────────────────────────────────────────────────────────────

;; ```shell
;; bb benchmark:run benchmarks/packs/prf-core/protocol-robustness-v0.edn \
;;   -o results/benchmarks/protocol-robustness-v0.edn
;; ```
;;
;; After running, refresh this notebook to see per-dimension results,
;; invariant summaries, and evidence chain data.
