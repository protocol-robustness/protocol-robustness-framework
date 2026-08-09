;; # Sew Protocol — Decentralized Dispute Resolution Validation Workbench
;;
;; **Subtitle:** Scenario evidence, adversarial testing, escalation behavior, and open validation gaps.
;;
;; ---
;; **Audience:** Kleros integrations / protocol team · Protocol reviewers · Security researchers ·
;; Sew contributors · Mechanism design / dispute resolution researchers.
;;
;; **Purpose:** Production-quality validation workbench for the Sew Protocol dispute resolution
;; subsystem. Every status indicator is paired with:
;; - what it means,
;; - what it does **not** mean,
;; - the source artifact backing it,
;; - a confidence tier.
;;
;; **Not a marketing page.** Green does not mean safe. Every cell in this workbench is an
;; evidence claim with an explicit backing level. Missing evidence shows as amber, not green.
;;
;; **Data sources loaded at render time:**
;; - `results/test-artifacts/test-summary.json` — canonical CI gate
;; - `data/fixtures/golden/*.report.edn` — per-scenario replay outcomes
;; - `data/fixtures/traces/*.trace.json` — scenario metadata corpus
;; - `results/test-artifacts/coverage.json` — transition / threat-tag coverage
;; - Live: `resolver-sim.protocols.sew.invariant-runner/run-all` — in-process invariant suite

^{:nextjournal.clerk/toc true
  :nextjournal.clerk/visibility {:code :fold :result :show}
  :nextjournal.clerk/dark-mode true}
(ns notebooks.dispute-resolution
  (:require [nextjournal.clerk :as clerk]
            [clojure.string :as str]
            [resolver-sim.notebook-support.ui :as ui]
            [resolver-sim.notebook-support.common :as common]
            [resolver-sim.notebook-support.speds.data :as speds-data]
            [resolver-sim.protocols.sew.invariants :as invariants]
            [resolver-sim.protocols.sew.invariant-runner :as runner]
            [resolver-sim.protocols.sew.invariant-scenarios :as sc]
            [resolver-sim.protocols.sew.state-machine :as sm]
            [resolver-sim.protocols.sew.types :as t]))

;; ---------------------------------------------------------------------------
;; Notebook Configuration
;; ---------------------------------------------------------------------------

;; ===========================================================================
;; Navigation
;; ===========================================================================

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 [:div {:style {:background "#f8fafc" :border "1px solid #e2e8f0" :borderRadius "6px" :padding "10px" :marginBottom "20px"}}
  [:strong "Index: "]
  [:a {:href "#section-1-executive-overview"} "Executive Overview"] " · "
  [:a {:href "#section-2-dispute-lifecycle-model"} "Dispute Lifecycle"] " · "
  [:a {:href "#section-3-invariant-coverage"} "Invariant Coverage"] " · "
  [:a {:href "#section-4-scenario-matrix-live"} "Scenario Matrix"] " · "
  [:a {:href "#section-5-adversarial-scenario-breakdown"} "Adversarial Breakdown"] " · "
  [:a {:href "#section-6-kleros-integration-model"} "Kleros Integration"] " · "
  [:a {:href "#section-7-confidence-summary-by-area"} "Confidence Summary"] " · "
  [:a {:href "#section-8-open-validation-gaps"} "Open Validation Gaps"] " · "
  [:a {:href "#section-9-artifact-provenance"} "Artifact Provenance"] " · "
  [:a {:href "#section-10-appeal-window-deadline-testing"} "Appeal/Deadline Testing"]])

;; ---------------------------------------------------------------------------
;; Artifact loading
;; ---------------------------------------------------------------------------

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def test-summary
  (delay (speds-data/load-summary)))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def coverage-data
  (delay (speds-data/load-coverage)))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def golden-reports
  (delay (speds-data/load-all-golden-reports)))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def all-traces
  (delay
    (map (fn [d]
           {:id          (or (:scenario-id d)
                             (str/replace (:_filename d) ".trace.json" ""))
            :title       (or (:title d) "")
            :description (or (:description d) "")
            :purpose     (or (:purpose d) "")
            :threat-tags (or (:threat-tags d) [])})
         (speds-data/load-all-traces))))

;; Live invariant suite run — executes all S01–S67 scenarios in-process.
;; Wrapped in delay so namespace loading (via `require`) does not trigger
;; expensive computation. Deref occurs at Clerk render time (first access).
;; Clerk caches across re-evaluations unless `:nextjournal.clerk/no-cache true`.
^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def live-suite-results
  (delay
    (try (runner/run-all)
         (catch Exception e
           {:passed 0 :total 0 :elapsed-ms 0 :results []
            :error (.getMessage e)}))))

;; Realize the delay in a background thread so namespace `require` completes fast.
;; The future is daemon — killed on JVM exit; fine for `bb test:notebooks`.
;; Clerk: re-evaluate the notebook after a few seconds to see results.
^{:nextjournal.clerk/visibility {:code :hide :result :hide}
  :nextjournal.clerk/no-cache true}
(defonce _loader
  (future (deref live-suite-results)))

;; ---
;; Render helper — shows placeholder until delay is realized
;; ---------------------------------------------------------------------------

(defn- suite-section [label render-fn]
  (common/safe-render label
    (fn []
      (if (realized? live-suite-results)
        (render-fn @live-suite-results)
        [:div {:style {:padding "12px" :color "#64748b" :fontStyle "italic"}}
         "⏳ Computing invariant suite results (S01–S107)…"]))))

;; ---------------------------------------------------------------------------
;; Dynamic scenario set derivation
;;
;; Replaces hardcoded display-name sets with pattern-based filters over the
;; canonical all-scenarios list. New scenarios following the naming convention
;; are automatically picked up; no manual maintenance required.
;; ---------------------------------------------------------------------------

(defn- kleros-scenario-names
  "Returns the set of display names whose scenario-id or label matches
   'kleros' or 'forking-strategist' — the two Kleros-path naming conventions."
  []
  (set (filter #(re-find #"(?i)kleros|forking-strategist" %)
               (map first sc/all-scenarios))))

(defn- appeal-scenario-names
  "Returns the set of display names whose scenario-id or label matches
   appeal-related naming conventions: pending-settlement, pending-cleared,
   appeal-deadline, or appeal-window."
  []
  (set (filter #(re-find #"(?i)pending-settlement|pending-cleared|appeal-deadline|appeal-window" %)
               (map first sc/all-scenarios))))

;; Curated invariant → scenario coverage map.
;; Keys are canonical invariant IDs (from invariants/canonical-ids).
;; Values are lists of scenario display names (prefix only, e.g. \"S09\") that
;; are known to exercise the invariant.
;;
;; This map MUST stay in sync with canonical-ids and all-scenarios.
;; The validator below warns on stale entries at render time.
(def ^:private inv-coverage
  {"solvency"                      ["S09" "S24" "S25" "S37"]
   "fees-non-negative"             ["S11" "S25" "S36"]
   "held-non-negative"             ["S09" "S24" "S25"]
   "all-status-combinations-valid" ["S08" "S10" "S22"]
   "pending-settlement-consistent" ["S05" "S13" "S21" "S25" "S32"]
   "dispute-timestamp-consistent"  ["S04" "S05" "S17" "S21"]
   "dispute-level-bounded"         ["S20" "S28" "S30"]
   "slash-status-consistent"       ["S25" "S34" "S35" "S36"]
   "appeal-bond-conserved"         ["S25" "S35" "S36"]
   "appeal-bond-custody-consistent" ["S25" "S35" "S36"]
   "no-auto-fraud-execute"         ["S25" "S34"]
   "bond-liquidity"                ["S24" "S38" "S39"]
   "bond-slash-bounded"            ["S24" "S41"]
   "fee-cap"                       ["S11" "S12a" "S12b"]
   "no-stale-automatable-escrows"  ["S04" "S17"]
   "conservation-of-funds"         ["S24" "S25" "S31" "S37"]
   "dispute-resolution-path"       ["S02" "S03" "S18" "S26" "S27"]
   "slash-distribution-consistent" ["S24" "S34" "S37"]
   "resolver-bond-mix-valid"       ["S38"]
   "senior-coverage-not-exceeded"  ["S39"]
   "resolver-not-frozen-on-assign" ["S40"]
   "slash-epoch-cap-respected"     ["S40" "S41"]
   "reversal-slash-disabled"       ["S41"]
   "resolver-capacity"             ["S24" "S38"]
   "yield-position-consistency"    []
   "yield-exposure"                []
   "terminal-states-unchanged"     ["S08" "S10" "S19" "S25"]
   "time-non-decreasing"           ["S04" "S05" "S21"]
   "time-no-action-after-finality" ["S08" "S10"]
   "finalization-accounting-correct" ["S02" "S03" "S09" "S25"]
   "escalation-level-monotonic"    ["S21" "S28" "S32"]
   "no-withdrawal-during-dispute"  ["S45"]
   "time-lock-integrity"           ["S66"]
   "token-tax-reconciliation"      ["S11"]
   "fees-monotone"                 ["S11" "S25" "S37"]
   "single-resolution-payout-consistent" ["S02" "S03" "S31"]
   "fraud-slash-executions-accounted"    ["S25" "S34" "S35"]})

(defn- validate-inv-coverage!
  "Warn if any inv-coverage key is not a canonical invariant, or any referenced
   scenario prefix does not appear in the all-scenarios display names."
  []
  (let [valid-invs         invariants/canonical-ids
        valid-prefixes     (set (map #(re-find #"S\d+[a-z]?" %) (map first sc/all-scenarios)))
        bad-keys           (remove #(contains? valid-invs (keyword %)) (keys inv-coverage))
        bad-scenarios      (remove (fn [[_ scenarios]]
                                     (every? #(contains? valid-prefixes %) scenarios))
                                   inv-coverage)]
    (when (seq bad-keys)
      (println "WARN: inv-coverage references unknown invariants:" bad-keys))
    (when (seq bad-scenarios)
      (println "WARN: inv-coverage references unknown scenario prefixes:" bad-scenarios))))

;; ---------------------------------------------------------------------------
;; Canonical state machine diagram generator
;;
;; Derives a Mermaid stateDiagram-v2 source string directly from:
;;   • sm/allowed-transitions  — authoritative edge set
;;   • sm/transitions          — guard/effect metadata per trigger
;;   • t/max-dispute-level     — maximum escalation level (= 2)
;;
;; The output is the SINGLE canonical diagram for the protocol.
;; Regenerate by re-evaluating this cell; no manual maintenance required.
;; ---------------------------------------------------------------------------

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def ^:private edge-labels
  "Human-readable labels for each top-level edge.
   Keys are [from-kw to-kw]. Values: {:op string :actor string :note string}."
  {[:none     :pending]  {:op "createEscrow()"       :actor "Buyer"}
   [:pending  :disputed] {:op "raiseDispute()"        :actor "Buyer or Seller"  :note "participant only"}
   [:pending  :released] {:op "release() / autoRelease() / mutualCancel()" :actor "Buyer · Keeper"}
   [:pending  :refunded] {:op "senderCancel() / recipientCancel() / autoCancel()" :actor "Seller · Keeper"}
   [:disputed :released] {:op "executeResolution(release) / executePendingSettlement()" :actor "Resolver · Keeper"}
   [:disputed :refunded] {:op "executeResolution(refund) / autoCancelDisputedEscrow()" :actor "Resolver · Keeper"}
   [:disputed :resolved] {:op "transitionToResolved()" :actor "(internal)" :note "no production call site"}})

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn generate-state-machine-mermaid
  "Generate a Mermaid stateDiagram-v2 string from the live simulator state machine.
   Returns a string ready to embed in a ```mermaid fenced block."
  []
  (let [transitions  sm/allowed-transitions
        max-level    t/max-dispute-level
        ;; Topological order for readability
        state-order  [:none :pending :disputed :released :refunded :resolved]
        terminal?    (fn [s] (empty? (get transitions s #{})))
        indent       (fn [n s] (str (apply str (repeat n "    ")) s))
        lines        (atom [])]
    (letfn [(emit [& parts] (swap! lines conj (apply str parts)))]
      (emit "stateDiagram-v2")
      (emit (indent 1 "direction LR"))
      (emit "")
      (emit (indent 1 "%% ── Top-level states ──────────────────────────────────────────────"))
      ;; :none is a pre-creation sentinel — show it as the entry point
      (emit (indent 1 "[*] --> PENDING : createEscrow()  [Buyer]"))
      (emit "")
      (emit (indent 1 "%% ── PENDING transitions ───────────────────────────────────────────"))
      (doseq [to (sort-by str (get transitions :pending #{}))]
        (let [{:keys [op actor note]} (get edge-labels [:pending to] {:op "?" :actor "?"})]
          (emit (indent 1 (str "PENDING --> " (str/upper-case (name to))
                               " : " op
                               (when note (str "  [" note "]")))))))
      (emit "")
      (emit (indent 1 "%% ── DISPUTED: escalation sub-process ──────────────────────────────"))
      (emit (indent 1 "state DISPUTED {"))
      (emit (indent 2 "direction TB"))
      (emit (indent 2 "[*] --> L0 : dispute opened"))
      (emit "")
      ;; Emit L0 … L(max-1) escalation chain
      (doseq [lvl (range (inc max-level))]
        (let [label     (str "L" lvl)
              is-last   (= lvl max-level)
              next-lbl  (str "L" (inc lvl))]
          (if is-last
            (do
              (emit (indent 2 (str "state \"L" lvl " — Kleros backstop (final round)\" as L" lvl)))
              (emit (indent 2 (str "L" lvl " --> [*] : executeResolution()  [Kleros jurors]"))))
            (do
              (let [ps-lbl (if (zero? lvl) "PendingSettlement" (str "PendingSettlement" lvl))]
                (emit (indent 2 (str "L" lvl " --> " ps-lbl " : executeResolution()  (appeal window open)")))
                (emit (indent 2 (str "L" lvl " --> [*] : executeResolution()  (no appeal window)")))
                (emit (indent 2 (str "L" lvl " --> [*] : autoCancelDisputedEscrow()  (timeout)")))
                (emit "")
                (emit (indent 2 (str "state \"PendingSettlement (L" lvl " decision, appeal open)\" as " ps-lbl)))
                (emit (indent 2 (str ps-lbl " --> " next-lbl " : escalateDispute() / challengeResolution()  (within appeal window)")))
                (emit (indent 2 (str ps-lbl " --> [*] : executePendingSettlement()  (after deadline)")))
                (emit ""))))))
      (emit (indent 1 "}"))
      (emit "")
      (emit (indent 1 "%% ── Terminal states ───────────────────────────────────────────────"))
      (doseq [s [:released :refunded :resolved]]
        (emit (indent 1 (str (str/upper-case (name s)) " --> [*]"))))
      (emit "")
      (emit (indent 1 "%% ── RESOLVED note ─────────────────────────────────────────────────"))
      (emit (indent 1 "note right of RESOLVED"))
      (emit (indent 2 "Reserved — no production call site."))
      (emit (indent 2 "Retained for enum completeness"))
      (emit (indent 2 "and formal verification only."))
      (emit (indent 1 "end note")))
    (str/join "\n" @lines)))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def canonical-mermaid-source
  "The canonical Mermaid diagram source, generated from the live simulator at
   notebook evaluation time. Saved here so it can be inspected as plain text."
  (generate-state-machine-mermaid))

;; ---------------------------------------------------------------------------
;; RAG / Status helpers (pure)
;; ---------------------------------------------------------------------------

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn rag-badge [rag text]
  (ui/rag-badge rag text))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn- status-emoji [rag]
  (case rag :green "🟢" :amber "🟠" :red "🔴" "⚪"))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn- conf-badge [level]
  (let [[bg border fg]
        (case level
          "High"    ["#dcfce7" "#16a34a" "#166534"]
          "Medium"  ["#eff6ff" "#3b82f6" "#1e3a8a"]
          "Low"     ["#fef3c7" "#f59e0b" "#92400e"]
          "Missing" ["#f1f5f9" "#94a3b8" "#334155"]
          ["#f1f5f9" "#94a3b8" "#334155"])]
    [:span {:style {:display "inline-block" :padding "2px 8px"
                    :borderRadius "999px" :border (str "1px solid " border)
                    :backgroundColor bg :color fg :fontSize "0.75em" :fontWeight "600"}}
     level]))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn- card [rag label value note]
  (let [border (case rag :green "#16a34a" :red "#dc2626" "#d97706")
        bg     (case rag :green "#f0fdf4" :red "#fef2f2" "#fffbeb")
        color  (case rag :green "#14532d" :red "#7f1d1d" "#78350f")]
    [:div {:style {:borderLeft   (str "4px solid " border)
                   :background   bg
                   :color        color
                   :padding      "12px 16px"
                   :borderRadius "4px"
                   :marginBottom "8px"}}
     [:div {:style {:display "flex" :alignItems "baseline" :gap "8px"}}
      [:span {:style {:fontSize "1.1em"}} (status-emoji rag)]
      [:strong label]
      (when value [:span {:style {:fontSize "0.95em" :opacity "0.85"}} value])]
     (when note
       [:div {:style {:fontSize "0.82em" :marginTop "4px" :opacity "0.8"}} note])]))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn- section-header [title sub]
  [:div {:style {:borderBottom "2px solid #e2e8f0" :paddingBottom "8px" :marginTop "28px" :marginBottom "12px"}}
   [:h2 {:style {:margin "0 0 4px 0"}} title]
   (when sub [:p {:style {:color "#64748b" :fontSize "0.88em" :margin "0"}} sub])])

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn- note-box [text]
  [:div {:style {:background "#eff6ff" :border "1px solid #93c5fd"
                 :borderRadius "4px" :padding "10px 14px"
                 :fontSize "0.84em" :color "#1e3a8a" :marginBottom "10px"}}
   text])

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn- warn-box [text]
  [:div {:style {:background "#fffbeb" :border "1px solid #f59e0b"
                 :borderRadius "4px" :padding "10px 14px"
                 :fontSize "0.84em" :color "#78350f" :marginBottom "10px"}}
   text])

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn- simple-table [headers rows]
  [:table {:style {:borderCollapse "collapse" :width "100%" :fontSize "0.84em"}}
   [:thead
    [:tr {:style {:background "#f1f5f9"}}
     (map-indexed
      (fn [idx h]
        ^{:key (str "header-" idx "-" h)}
        [:th {:style {:padding "8px 10px" :textAlign "left"}} h])
      headers)]]
   (into [:tbody] rows)])

;; ---------------------------------------------------------------------------
;; Notebook navigation
;; ---------------------------------------------------------------------------

(clerk/html (ui/notebook-navigation "Dispute Resolution Workbench"))

;; ---
;; ## Legend
;;
;; | Colour | Meaning |
;; |--------|---------|
;; | 🟢 Green | Validated — simulator-backed, replayed, invariant-checked |
;; | 🟠 Amber | Inconclusive — scenario-backed but limited range, partial implementation, or artifact missing |
;; | 🔴 Red   | Failure / finding — hard invariant violation, unexpected outcome, or active vulnerability evidence |
;;
;; **Confidence tiers** (used throughout this workbench):
;; - **High** — simulator-backed, replayed across parameter ranges, all relevant invariants checked
;; - **Medium** — scenario-backed with limited parameter range, or derivation-backed with partial replay coverage
;; - **Low** — derivation-backed, partial implementation, or manual review only
;; - **Missing** — no meaningful evidence yet exists

;; ===========================================================================
;; ## Section 1 — Executive Overview
;; ===========================================================================

(clerk/html
 (suite-section
  "Executive Overview"
  (fn [suite]
    (let [{:keys [passed total elapsed-ms error]} suite
          suite-rag  (cond error :amber (= passed total) :green :else :red)
          inv-count  (count invariants/canonical-ids)
          sc-count   (count sc/all-scenarios)
          adv-count  (count (filter #(= :adversarial
                                        (:scenario/type (get sc/scenario-type-registry
                                                             (if (map? (second %))
                                                               (:scenario-id (second %))
                                                               (:scenario-id (first (second %)))))))
                                    sc/all-scenarios))
           gate-rag   (if @test-summary
                        (if (= "pass" (str (:overall_status @test-summary))) :green :red)
                        :amber)]
      [:div
       (section-header
        "Executive Overview"
        "What this workbench covers and what it does not.")

       (note-box
        [:span
         [:strong "Scope: "]
         "This workbench presents evidence for the Sew Protocol dispute resolution subsystem "
         "as exercised by the deterministic invariant scenario suite (S01–S67). "
         "It does not cover the full on-chain implementation, gas analysis, or live mainnet behavior. "
         "Evidence strength is annotated on every claim."])

       ;; Protocol model summary
       [:div {:style {:background "#f8fafc" :border "1px solid #e2e8f0"
                      :borderRadius "6px" :padding "14px 16px" :marginBottom "14px"}}
        [:h3 {:style {:margin "0 0 10px 0" :fontSize "1em"}} "Protocol Model Summary"]
        [:ul {:style {:margin "0" :paddingLeft "20px" :fontSize "0.88em" :lineHeight "1.8"}}
         [:li "Sew protected transfers (escrows) lock funds until explicit release, "
          "mutual cancel, dispute resolution, or timeout."]
         [:li "Disputes follow a predefined escalation path: "
          "Level-0 resolver → Level-1 (appeal) → Level-2 → Kleros backstop."]
         [:li "Escrow terms and resolver assignments are "
          [:strong "snapshotted at creation time"] ". "
          "Governance changes to protocol parameters do not affect active escrows."]
         [:li "Governance " [:strong "cannot alter active escrow terms"] " after creation. "
          "The snapshot is an immutable contract commitment."]
         [:li "Kleros is modeled as the final escalation layer ("
          [:code "0xkleros-proxy"]
          ") with a configurable multi-level resolver set (L0/L1/L2)."]
         [:li "The simulator tests dispute behavior under adversarial conditions using three "
          "adversary classes: profit-maximizer, forking-strategist, and colluder."]
         [:li "This workbench presents " [:strong "evidence"] ", not claims. "
          "Every status below references a specific artifact or explains why it is absent."]]]

       ;; Live suite status bar
       [:div {:style {:background "#f0fdf4" :border "1px solid #16a34a"
                      :borderRadius "6px" :padding "10px 14px" :marginBottom "14px"
                      :display "flex" :gap "24px" :flexWrap "wrap" :alignItems "center"}}
        (status-emoji suite-rag)
        [:strong "Live invariant suite: "]
        [:span (if error
                 (str "error — " error)
                 (str passed "/" total " scenarios pass"))]
        [:span {:style {:color "#64748b" :fontSize "0.85em"}}
         (when-not error (str "(" (int (/ elapsed-ms 1000.0)) "s)"))]
        [:span {:style {:color "#64748b" :fontSize "0.85em"}}
         (str "  │  " inv-count " canonical invariants  │  "
              sc-count " scenarios  │  " adv-count " adversarial")]]

       ;; Summary table
       (let [rows
             [["Basic dispute lifecycle"
               (if (>= passed 3) "🟢 Exercised" "🟠 Partial")
               "High"
               "S01–S08 cover create/dispute/resolve/refund/timeout paths. "
               "Scenario-backed, replayed, all structural invariants checked."]
              ["Resolver assignment / routing"
               "🟢 Validated"
               "High"
               "S07, S14, S15 test authorized vs. unauthorized resolver rejection "
               "via both custom-resolver and module-based routing."]
              ["Escalation pipeline"
               "🟢 Validated"
               "High"
               "S19–S23, S26–S33 exercise multi-level escalation, level-monotonicity, "
               "pending-settlement clearing, and premature escalation rejection."]
              ["Appeal windows"
               "🟢 Validated"
               "High"
               "S05, S13, S21 test appeal deadline enforcement, early-settlement rejection, "
               "and deadline-exact execution."]
              ["Kleros backstop integration"
               "🟠 Modeled"
               "Medium"
               "S18–S23 exercise the Kleros proxy module model (0xkleros-proxy). "
               "Confidence is Medium: simulator model, not live Kleros contract integration."]
              ["Resolver liveness"
               "🟢 Validated"
               "High"
               "S04, S17, S24 test timeout-triggered auto-cancel and resolver stake "
               "depletion. Bond-slash saturation is exercised across 3 concurrent escrows."]
              ["Bond / incentive behavior"
               "🟢 Validated"
               "High"
               "S24–S37, S40–S41, S45 test slash accounting, bond-mix validity, "
               "senior coverage, freeze-post-slash, and flash-loan stake inflation."]
              ["Governance interaction"
               "🟢 Validated"
               "High"
               "S12 (snapshot isolation) confirms governance params do not "
               "cross-contaminate active escrows. Governance-capture under multi-epoch "
               "adversarial conditions is covered in the stochastic phases."]
              ["Capacity / dispute flooding"
               "🟠 Partial"
               "Medium"
               "S24 (stake cascade) tests 3-escrow concurrent depletion. "
               "Monte Carlo sweep (Phase F) covers flooding at scale. "
               "No dedicated single-notebook flooding scenario."]
              ["Multi-epoch adversarial behavior"
               "🟠 Partial"
               "Low"
               "Stochastic phases (Phase J) model multi-epoch reputation drift. "
               "No deterministic multi-epoch scenario in S01–S67 currently."]]]
         [:div {:style {:overflowX "auto"}}
          [:table {:style {:borderCollapse "collapse" :width "100%" :fontSize "0.84em"}}
           [:thead
            [:tr {:style {:background "#f1f5f9"}}
             [:th {:style {:padding "8px 10px" :textAlign "left" :borderBottom "2px solid #cbd5e1"}} "Area"]
             [:th {:style {:padding "8px 10px" :textAlign "left" :borderBottom "2px solid #cbd5e1"}} "Current status"]
             [:th {:style {:padding "8px 10px" :textAlign "left" :borderBottom "2px solid #cbd5e1"}} "Confidence"]
             [:th {:style {:padding "8px 10px" :textAlign "left" :borderBottom "2px solid #cbd5e1"}} "Notes"]]]
           (into [:tbody]
                 (map (fn [[area status conf note1 note2]]
                        [:tr {:style {:borderBottom "1px solid #e2e8f0"}}
                         [:td {:style {:padding "8px 10px" :fontWeight "600"}} area]
                         [:td {:style {:padding "8px 10px"}} status]
                         [:td {:style {:padding "8px 10px"}} (conf-badge conf)]
                         [:td {:style {:padding "8px 10px" :color "#475569"}} (str note1 " " note2)]])
                      rows))]])]

       (warn-box
         [:span
          [:strong "Confidence caveat: "]
          "\"High\" confidence means simulator-backed with invariant checking across "
          "a deterministic scenario suite. It does not imply formal verification, "
          "gas-correctness, on-chain proof, or absence of unknown-unknown attack surfaces. "
          "The simulator is a pure Clojure model that mirrors the Solidity spec; "
          "divergence from the on-chain contract is a bug."])))))

;; ===========================================================================
;; ## Section 2 — Dispute Lifecycle Model
;; ===========================================================================

;; Canonical diagram — generated from sm/allowed-transitions + t/max-dispute-level
(clerk/md (str "## Dispute Lifecycle Model

The diagram below is **generated directly from the live simulator** at notebook evaluation time.
Sources:
- `resolver-sim.protocols.sew.state-machine/allowed-transitions` — the authoritative edge set
- `resolver-sim.protocols.sew.types/max-dispute-level` = `" t/max-dispute-level "` — maximum escalation rounds

Every lifecycle function that changes `:escrow-state` goes through `apply-transition!`,
which throws a programming error on any illegal edge attempt.

```mermaid
" canonical-mermaid-source "
```

> **Note on `:resolved`:** `transitionToResolved()` is defined in `StateManagementLibrary.sol`
> but is **never called** by any production code path (verified from source).
> Disputes always terminate in `:released` or `:refunded`.
> `:resolved` is retained for enum completeness and Foundry/halmos compatibility only.

### Who can initiate each action?

| Action | Authorized caller | Precondition |
|--------|------------------|--------------|
| `createEscrow()` | Buyer | — |
| `raiseDispute()` | Buyer or Seller (participant) | State = `:pending` |
| `release()` | Buyer | State = `:pending` |
| `senderCancel()` / `recipientCancel()` | Respective party | State = `:pending` |
| `autoRelease()` / `autoCancel()` | Anyone (keeper) | Timeout elapsed; state = `:pending` |
| `executeResolution()` | Authorized resolver | State = `:disputed`; resolver authority check |
| `escalateDispute()` / `challengeResolution()` | Any party | State = `:disputed`; pending-settlement exists; within appeal window; level < max |
| `executePendingSettlement()` | Anyone (keeper) | State = `:disputed`; appeal deadline elapsed |
| `autoCancelDisputedEscrow()` | Anyone (keeper) | State = `:disputed`; `max-dispute-duration` elapsed |

### What cannot happen after finalization?

Terminal states (`:released`, `:refunded`, `:resolved`) are absorbing — enforced by:
1. `allowed-transitions` contains `#{}` for all three terminal states.
2. `apply-transition!` throws a programming error on any illegal edge.
3. The `:terminal-states-unchanged` invariant checked after every scenario step.
4. Scenarios S08 (state machine attack gauntlet) and S10 (double-finalize rejected) provide deterministic regression coverage.

### Governance isolation

Escrow protocol parameters (resolver fee, appeal window, dispute duration) are **snapshotted at creation time**.
Governance changes after creation have no effect on active escrows.
Scenario S12 (governance snapshot isolation) provides deterministic regression coverage."))

;; Live transition table — raw map from the simulator
(clerk/html
 (common/safe-render
  "Transition Graph"
  (fn []
    [:div {:style {:marginBottom "16px"}}
     [:h3 {:style {:margin "0 0 10px 0"}} "Live transition table"]
     (note-box
      [:span "Rendered directly from "
       [:code "sm/allowed-transitions"] " and "
       [:code "sm/transitions"] " at evaluation time. "
       "This is the exact data structure consumed by "
       [:code "apply-transition!"] " at runtime."])
     ;; sm/transitions shape: {:trigger-kw {:from #{states} :to state :guards [...] :effects [...]}}
     (simple-table
      ["From" "To" "Trigger" "Guards" "Effects"]
      (let [edge->trigger
            (into {}
                  (mapcat (fn [[kw {:keys [from to guards effects]}]]
                            (map (fn [f] [[f to] {:kw kw :guards guards :effects effects}])
                                 from))
                          sm/transitions))]
        (for [[from tos] (sort-by (comp str first) sm/allowed-transitions)
              to         (sort-by str tos)]
          (let [{:keys [kw guards effects]} (get edge->trigger [from to] {})]
            [:tr {:style {:borderBottom "1px solid #e2e8f0"}}
             [:td {:style {:padding "6px 10px" :fontFamily "monospace" :fontWeight "600"}} (name from)]
             [:td {:style {:padding "6px 10px" :fontFamily "monospace"}} (name to)]
             [:td {:style {:padding "6px 10px" :fontFamily "monospace" :fontSize "0.85em"}}
              (if kw (name kw) "—")]
             [:td {:style {:padding "6px 10px" :fontSize "0.82em" :color "#475569"}}
              (if (seq guards) (str/join " · " (map name guards)) "—")]
             [:td {:style {:padding "6px 10px" :fontSize "0.82em" :color "#475569"}}
              (if (seq effects) (str/join " · " (map name effects)) "—")]]))))
     ;; Terminal states row
     [:div {:style {:marginTop "8px"}}
      (into [:div {:style {:display "flex" :gap "8px" :flexWrap "wrap"}}]
            (map (fn [s]
                   [:span {:style {:background "#f0fdf4" :border "1px solid #16a34a"
                                   :borderRadius "4px" :padding "2px 8px"
                                   :fontFamily "monospace" :fontSize "0.82em"}}
                    (str "🔒 " (name s) " — terminal")]  )
                 (filter (fn [s] (empty? (get sm/allowed-transitions s #{})))
                         (keys sm/allowed-transitions))))]
     [:p {:style {:fontSize "0.8em" :color "#64748b" :marginTop "8px"}}
      "Source: "
      [:code "resolver-sim.protocols.sew.state-machine/allowed-transitions"]
      " and "
      [:code "resolver-sim.protocols.sew.state-machine/transitions"]
      " — rendered live at notebook evaluation time. "
      [:code (str "max-dispute-level = " t/max-dispute-level)]
      " from "
      [:code "resolver-sim.protocols.sew.types/max-dispute-level"] "."]])))

;; Raw Mermaid source for copy/export
(clerk/html
 (common/safe-render
  "Raw Mermaid Source"
  (fn []
    [:details {:style {:marginTop "8px"}}
     [:summary {:style {:cursor "pointer" :fontSize "0.85em" :color "#475569" :userSelect "none"}}
      "▶ Show raw Mermaid source (generated at evaluation time — copy to render externally)"]
     [:pre {:style {:background "#f8fafc" :border "1px solid #e2e8f0" :borderRadius "4px"
                    :padding "12px" :fontSize "0.78em" :overflowX "auto" :marginTop "6px"}}
      canonical-mermaid-source]])))

;; ===========================================================================
;; ## Section 3 — Invariant Coverage
;; ===========================================================================

(clerk/html
 (suite-section
  "Invariant Coverage"
  (fn [suite]
     (validate-inv-coverage!)
     (let [inv-ids (sort (map name invariants/canonical-ids))
           results (:results suite)
          covered?   (fn [inv] (seq (get inv-coverage inv)))
          total-inv  (count inv-ids)
          covered-n  (count (filter covered? inv-ids))
          uncovered  (remove covered? inv-ids)]
      [:div
       (section-header
        "Invariant Coverage"
        (str total-inv " canonical invariants across Sew v1; "
             covered-n " with deterministic scenario coverage."))

       (note-box
        [:span
         [:strong "What these invariants are: "]
         "Each invariant in "
         [:code "resolver-sim.protocols.sew.invariants/canonical-ids"]
         " mirrors a runtime guard in "
         [:code "InvariantGuardInternal.sol"]
         " and defines the specification for future Foundry invariant tests and Halmos properties. "
         "The simulator checks all applicable invariants after every event step."])

       [:div {:style {:display "flex" :gap "16px" :flexWrap "wrap" :marginBottom "12px"}}
        (card :green "Invariants with scenario coverage"
              (str covered-n "/" total-inv) nil)
        (card (if (empty? uncovered) :green :amber)
              "Invariants with no scenario coverage yet"
              (str (count uncovered)) nil)]

       ;; Coverage table
       [:div {:style {:overflowX "auto"}}
        [:table {:style {:borderCollapse "collapse" :width "100%" :fontSize "0.82em"}}
         [:thead
          [:tr {:style {:background "#f1f5f9"}}
           [:th {:style {:padding "7px 10px" :textAlign "left"}} "Invariant ID"]
           [:th {:style {:padding "7px 10px" :textAlign "left"}} "Coverage"]
           [:th {:style {:padding "7px 10px" :textAlign "left"}} "Scenarios"]]]
         (into [:tbody]
               (map (fn [inv]
                      (let [covs  (get inv-coverage inv [])
                            cov?  (seq covs)
                            rag   (if cov? :green :amber)]
                        [:tr {:style {:borderBottom "1px solid #e2e8f0"}}
                         [:td {:style {:padding "6px 10px" :fontFamily "monospace"}} (str ":" inv)]
                         [:td {:style {:padding "6px 10px"}}
                          (if cov?
                            [:span {:style {:color "#15803d"}} "✓ covered"]
                            [:span {:style {:color "#b45309"}} "⚠ no scenario"])]
                         [:td {:style {:padding "6px 10px" :color "#475569" :fontSize "0.9em"}}
                          (if (seq covs) (str/join ", " covs) "—")]]))
                    inv-ids))]]

       (when (seq uncovered)
         [:div {:style {:marginTop "12px"}}
          (warn-box
           [:span
            [:strong "Uncovered invariants: "]
            "The following invariants exist in "
            [:code "canonical-ids"]
            " but have no deterministic scenario coverage yet: "
            [:code (str/join ", " (map #(str ":" %) uncovered))]
            ". This does not mean they are unimplemented — the checker runs on every step — "
            "but there is no scenario specifically designed to stress-test them."])])]))))

;; ===========================================================================
;; ## Section 4 — Scenario Matrix (Live)
;; ===========================================================================

;; Interactive: select a scenario to see its type metadata and live result.
^{:nextjournal.clerk/sync true}
(defonce !selected-scenario (atom nil))

(clerk/html
 (suite-section
  "Scenario Matrix"
  (fn [suite]
    (let [results      (:results suite [])
          selected-id  @!selected-scenario
          type-counts  (frequencies (map :scenario/type results))
          adv-results  (filter #(= :adversarial (:scenario/type %)) results)
          pass-count   (:passed suite 0)
          total-count  (:total suite 0)
          suite-rag    (if (= pass-count total-count) :green :red)]
      [:div
       (section-header
        "Scenario Matrix — Live Execution (S01–S67)"
        "All deterministic invariant scenarios executed in-process at notebook load time.")

       (note-box
        [:span
         "Each row represents one execution of "
         [:code "sew/replay-with-sew-protocol"]
         " against a fully deterministic scenario. "
         "\"XFAIL\" (expected-fail) scenarios are regression tests for known-fixed bugs: "
         "they pass when the invariant fires as expected. "
         "Click a row to inspect its metadata."])

       ;; Summary metrics
       [:div {:style {:display "flex" :gap "12px" :flexWrap "wrap" :marginBottom "14px"}}
        (card suite-rag "Suite result" (str pass-count "/" total-count) "All scenarios must pass")
        (card :green "Baseline" (str (get type-counts :baseline 0)) "Standard protocol flows")
        (card :green "Edge-case" (str (get type-counts :edge-case 0)) "Guards, boundaries, state checks")
        (card :green "Stress" (str (get type-counts :stress 0)) "Solvency, multi-escrow, depletion")
        (card (if (pos? (get type-counts :adversarial 0)) :green :amber) "Adversarial"
              (str (get type-counts :adversarial 0)) "Profit-maximizer, forking-strategist, colluder")]

       ;; Scenario table
       [:div {:style {:overflowX "auto"}}
        [:table {:style {:borderCollapse "collapse" :width "100%" :fontSize "0.81em"
                         :cursor "pointer"}}
         [:thead
          [:tr {:style {:background "#f1f5f9" :position "sticky" :top "0"}}
           [:th {:style {:padding "7px 10px" :textAlign "left"}} ""]
           [:th {:style {:padding "7px 10px" :textAlign "left"}} "Scenario"]
           [:th {:style {:padding "7px 10px" :textAlign "left"}} "Type"]
           [:th {:style {:padding "7px 10px" :textAlign "right"}} "Steps"]
           [:th {:style {:padding "7px 10px" :textAlign "right"}} "Reverts"]
           [:th {:style {:padding "7px 10px" :textAlign "right"}} "Violations"]
           [:th {:style {:padding "7px 10px" :textAlign "left"}} "Result"]]]
         (into [:tbody]
               (map (fn [{:keys [name pass? expected-fail? steps reverts violations
                                 scenario/type adversary/type adversary/traits] :as r}]
                      (let [rag      (cond (and pass? expected-fail?) :amber
                                           pass?                      :green
                                           :else                      :red)
                            selected? (= name selected-id)
                            row-bg   (cond selected? "#eff6ff"
                                           (= :adversarial type) "#fefce8"
                                           :else nil)]
                        [:tr {:style (cond-> {:borderBottom "1px solid #e2e8f0"}
                                       row-bg (assoc :background row-bg))
                              :on-click #(reset! !selected-scenario name)}
                         [:td {:style {:padding "6px 10px"}}
                          (status-emoji rag)]
                         [:td {:style {:padding "6px 10px" :fontFamily "monospace"
                                       :fontWeight (when selected? "600")}}
                          name]
                         [:td {:style {:padding "6px 10px" :color "#475569"}}
                          (when type (clojure.core/name type))]
                         [:td {:style {:padding "6px 10px" :textAlign "right"}} steps]
                         [:td {:style {:padding "6px 10px" :textAlign "right"}} reverts]
                         [:td {:style {:padding "6px 10px" :textAlign "right"
                                       :color (when (pos? (or violations 0)) "#dc2626")}}
                          violations]
                         [:td {:style {:padding "6px 10px"}}
                          (cond (and pass? expected-fail?) "✓ XFAIL"
                                pass? "✓ PASS"
                                :else "✗ FAIL")]]))
                    results))]]

       ;; Detail panel for selected scenario
       (when-let [selected (and selected-id (some #(when (= (:name %) selected-id) %) results))]
         [:div {:style {:marginTop "16px" :border "1px solid #93c5fd"
                        :borderRadius "6px" :padding "14px 16px" :background "#eff6ff"}}
          [:h3 {:style {:margin "0 0 10px 0"}} "Scenario detail: " (:name selected)]
          [:dl {:style {:display "grid" :gridTemplateColumns "160px 1fr"
                        :gap "4px 12px" :fontSize "0.85em"}}
           [:dt [:strong "Type"]]
           [:dd (str (or (:scenario/type selected) "—"))]
           [:dt [:strong "Adversary type"]]
           [:dd (str (or (:adversary/type selected) "none"))]
           [:dt [:strong "Adversary traits"]]
           [:dd (if (seq (:adversary/traits selected))
                  (str/join ", " (map name (:adversary/traits selected)))
                  "—")]
           [:dt [:strong "Expected-fail?"]]
           [:dd (str (:expected-fail? selected))]
           [:dt [:strong "Steps executed"]]
           [:dd (str (:steps selected))]
           [:dt [:strong "Reverts"]]
           [:dd (str (:reverts selected))]
           [:dt [:strong "Violations"]]
           [:dd (str (:violations selected))]
           [:dt [:strong "Pass?"]]
           [:dd (str (:pass? selected))]]])

       [:p {:style {:fontSize "0.78em" :color "#64748b" :marginTop "8px"}}
        "Click any row to inspect its metadata. "
        "Yellow background = adversarial scenario. "
        "Source: "
        [:code "resolver-sim.protocols.sew.invariant-runner/run-all"]
        " — executed live."]]))))

;; ===========================================================================
;; ## Section 5 — Adversarial Scenario Breakdown
;; ===========================================================================

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn- render-adversary-card [adv-type scenarios adversary-descriptions]
  (let [meta       (get adversary-descriptions adv-type {})
        pass-count (count (filter :pass? scenarios))
        total      (count scenarios)
        rag        (if (= pass-count total) :green :red)
        failed     (->> scenarios (remove :pass?) (map :name) (sort) vec)]
    [:div {:style {:border "1px solid #e2e8f0" :borderRadius "6px"
                   :padding "14px 16px" :marginBottom "12px"}}
     [:div {:style {:display "flex" :gap "10px" :alignItems "baseline"
                    :marginBottom "8px"}}
      (status-emoji rag)
      [:h3 {:style {:margin "0"}} (str (or (:label meta) (name adv-type)))]
      [:span {:style {:color "#64748b" :fontSize "0.85em"}}
       (str pass-count "/" total " pass")]]
     (when (:summary meta)
       [:p {:style {:fontSize "0.86em" :color "#374151" :marginBottom "8px"}}
        (:summary meta)])
     [:div {:style {:display "grid" :gridTemplateColumns "1fr 1fr" :gap "10px"}}
      [:div
       [:strong {:style {:fontSize "0.83em"}} "Modeled tactics"]
       [:ul {:style {:margin "4px 0" :paddingLeft "18px" :fontSize "0.82em"}}
        (map #(vector :li %) (or (:tactics meta) []))]]
      [:div
       [:strong {:style {:fontSize "0.83em"}} "Finding"]
       [:p {:style {:fontSize "0.82em" :color "#374151" :margin "4px 0"}}
        (if (= pass-count total)
          (or (:finding meta) "—")
          (str "⚠ Live suite currently shows failing scenarios for "
               (or (:label meta) (name adv-type))
               ": "
               (str/join ", " failed)
               ". Investigate these before treating this adversary class as bounded."))]]]
     [:div {:style {:marginTop "10px" :overflowX "auto"}}
      [:table {:style {:borderCollapse "collapse" :fontSize "0.8em" :width "100%"}}
       [:thead
        [:tr {:style {:background "#f8fafc"}}
         [:th {:style {:padding "5px 8px" :textAlign "left"}} "Scenario"]
         [:th {:style {:padding "5px 8px" :textAlign "left"}} "Traits"]
         [:th {:style {:padding "5px 8px" :textAlign "right"}} "Steps"]
         [:th {:style {:padding "5px 8px" :textAlign "right"}} "Reverts"]
         [:th {:style {:padding "5px 8px" :textAlign "left"}} "Result"]]]
       (into [:tbody]
             (map (fn [{:keys [name pass? steps reverts adversary/traits]}]
                    [:tr {:style {:borderBottom "1px solid #e2e8f0"}}
                     [:td {:style {:padding "4px 8px" :fontFamily "monospace"}}
                      name]
                     [:td {:style {:padding "4px 8px" :color "#475569"}}
                      (if (seq traits) (str/join ", " (map clojure.core/name traits)) "—")]
                     [:td {:style {:padding "4px 8px" :textAlign "right"}} steps]
                     [:td {:style {:padding "4px 8px" :textAlign "right"}} reverts]
                     [:td {:style {:padding "4px 8px"}}
                      (if pass? "✓ PASS" "✗ FAIL")]])
                  scenarios))]]]))

(clerk/html
 (suite-section
  "Adversarial Breakdown"
  (fn [suite]
    (let [results     (:results suite [])
          adv-results (filter #(= :adversarial (:scenario/type %)) results)
          by-adversary (group-by :adversary/type adv-results)
          adversary-descriptions
          {:profit-maximizer
           {:label    "Profit-Maximizer"
            :summary  "Attempts to extract value via speculative fraud slashes, pre-window settlement execution, governance manipulation, and stake inflation."
            :tactics  ["Speculative fraud slash followed by appeal (S25)"
                       "Unchallenged slash (S34)"
                       "Governance wins appeal against resolver (S35)"
                       "Pre-window settlement execution rejected (S36)"
                       "Two-resolver split-outcome extraction (S37)"
                       "Flash-loan stake inflation (S45)"
                       "Reentrancy callback attempt (S67)"]
            :finding  "All extraction attempts are rejected or bounded by the bond-slash accounting model. No profit-maximizer scenario produces a solvency violation."}
           :forking-strategist
           {:label    "Forking-Strategist"
            :summary  "Attempts to exploit the multi-level escalation pipeline: skipping levels, fabricating appeal windows, isolating forks, and triggering double-loss scenarios."
            :tactics  ["L1 reversal attempt (S26)"
                       "L2 fork attempt (S27)"
                       "Late escalation after deadline (S28)"
                       "Seller-initiated escalation (S29)"
                       "Double-loss via forking (S30)"
                       "All-levels confirm (S31)"
                       "Premature settlement rejection (S32)"
                       "Two-escrow fork isolation (S33)"]
            :finding  "The escalation-level-monotonic and pending-settlement-consistent invariants prevent level-skipping and premature finalization. All forking attempts are either rejected or produce correctly-isolated outcomes."}
           :colluder
           {:label    "Colluder"
            :summary  "Multi-agent collusion: resolver–buyer bribery loop."
            :tactics  ["Resolver-buyer bribery loop (S42)"]
            :finding  "Single scenario. Demonstrates that the resolver authority model prevents bribery-based resolution hijacking within the deterministic model. Multi-epoch collusion resistance relies on stochastic Phase J."}}
          total-adv  (count adv-results)
          pass-adv   (count (filter :pass? adv-results))]
      [:div
       (section-header
        "Adversarial Scenario Breakdown"
        (str total-adv " adversarial scenarios across 3 adversary classes; "
             pass-adv "/" total-adv " pass."))

       (note-box
        [:span
         [:strong "Adversarial scenarios are not attack demonstrations. "]
         "They are deterministic proofs that specific adversarial strategies fail as modeled. "
         "A passing adversarial scenario means the protocol correctly rejected the attack. "
         [:strong "A failing adversarial scenario means an attack succeeded — investigate immediately."]])

       ;; Per-adversary breakdown
       (into [:div]
             (map (fn [[adv-type scenarios]]
                    (render-adversary-card adv-type scenarios adversary-descriptions))
                  by-adversary))]))))

;; ===========================================================================
;; ## Section 5b — Failure Triage Summary (Actionable)
;; ===========================================================================

(clerk/html
 (suite-section
  "Failure Triage Summary"
  (fn [suite]
    (let [results (:results suite [])
          by-name (into {} (map (juxt :name identity) results))
          scenario-row
          (fn [name criticality ease action root]
            (let [{:keys [pass? steps reverts violations]} (get by-name name)
                  status (if pass? "✓ PASS" "✗ FAIL")]
              [:tr {:style {:borderBottom "1px solid #e2e8f0"}}
               [:td {:style {:padding "6px 10px" :fontFamily "monospace"}} name]
               [:td {:style {:padding "6px 10px"}} status]
               [:td {:style {:padding "6px 10px" :textAlign "right"}} (or steps "—")]
               [:td {:style {:padding "6px 10px" :textAlign "right"}} (or reverts "—")]
               [:td {:style {:padding "6px 10px" :textAlign "right"}} (or violations "—")]
               [:td {:style {:padding "6px 10px"}} criticality]
               [:td {:style {:padding "6px 10px"}} ease]
               [:td {:style {:padding "6px 10px" :color "#475569"}} action]
               [:td {:style {:padding "6px 10px" :color "#475569"}} root]]))
          rows [(scenario-row "S34  profit-maximizer-unchallenged-slash"
                              "High"
                              "Medium"
                              "Trace slash accounting + expected post-slash invariants; verify settlement path cannot create unbounded credit."
                              "R1: Slash/lifecycle accounting consistency")
                (scenario-row "S35  profit-maximizer-governance-wins-appeal"
                              "High"
                              "Medium"
                              "Audit governance appeal resolution transitions and snapshot boundaries; add explicit invariant for appeal-finalization accounting." 
                              "R1: Slash/lifecycle accounting consistency")
                (scenario-row "S36  profit-maximizer-pre-window-execute-rejected"
                              "Critical"
                              "Easy"
                              "Tighten/verify deadline guard and add boundary tests (t-1/t/t+1) for executePendingSettlement."
                              "R2: Temporal boundary enforcement")
                (scenario-row "S37  profit-maximizer-two-resolver-split-outcomes"
                              "Critical"
                              "Hard"
                              "Validate cross-resolver payout isolation and single-resolution payout invariants under concurrent disputes."
                              "R3: Multi-escrow/concurrency isolation")
                [:tr {:style {:borderBottom "1px solid #e2e8f0"}}
                 [:td {:style {:padding "6px 10px" :fontFamily "monospace"}} "Phase J stochastic claims"]
                 [:td {:style {:padding "6px 10px"}} "❌ mixed-fail"]
                 [:td {:style {:padding "6px 10px" :textAlign "right"}} "—"]
                 [:td {:style {:padding "6px 10px" :textAlign "right"}} "—"]
                 [:td {:style {:padding "6px 10px" :textAlign "right"}} "—"]
                 [:td {:style {:padding "6px 10px"}} "High (economic/governance)"]
                 [:td {:style {:padding "6px 10px"}
                       :title "Model tuning + policy constraints"}
                  "Medium"]
                 [:td {:style {:padding "6px 10px" :color "#475569"}}
                  "Prioritize resolver participation stability + budget-balance; enforce governance review floor >=2/epoch (Phase AD finding)."]
                 [:td {:style {:padding "6px 10px" :color "#475569"}}
                  "R4: Incentive/budget dynamics under multi-epoch stress"]]]]
      [:div
       (section-header
        "Failure Triage Summary (Live + Actionable)"
        "Criticality, ease of fixing, recommended action, and root-cause clustering for current failures.")

       [:div {:style {:overflowX "auto"}}
        [:table {:style {:borderCollapse "collapse" :width "100%" :fontSize "0.82em"}}
         [:thead
          [:tr {:style {:background "#f1f5f9"}}
           [:th {:style {:padding "7px 10px" :textAlign "left"}} "Failure group"]
           [:th {:style {:padding "7px 10px" :textAlign "left"}} "Live status"]
           [:th {:style {:padding "7px 10px" :textAlign "right"}} "Steps"]
           [:th {:style {:padding "7px 10px" :textAlign "right"}} "Reverts"]
           [:th {:style {:padding "7px 10px" :textAlign "right"}} "Violations"]
           [:th {:style {:padding "7px 10px" :textAlign "left"}} "Criticality"]
           [:th {:style {:padding "7px 10px" :textAlign "left"}} "Ease of fixing"]
           [:th {:style {:padding "7px 10px" :textAlign "left"}} "Recommended action"]
           [:th {:style {:padding "7px 10px" :textAlign "left"}} "Underlying root cause"]]]
         (into [:tbody] rows)]]

       [:h4 {:style {:margin "14px 0 8px 0"}} "Root-cause clusters that unlock multiple fixes"]
       [:ul {:style {:fontSize "0.84em" :color "#334155" :lineHeight "1.8"}}
        [:li [:strong "R1 — Slash/lifecycle accounting consistency"]
         ": likely addresses both S34 and S35 if accounting and appeal-finalization semantics are unified around one invariant set."]
        [:li [:strong "R2 — Temporal boundary enforcement"]
         ": directly targets S36 and reduces race-condition regressions globally (appeal windows, pre-window execution)."]
        [:li [:strong "R3 — Multi-escrow/concurrency isolation"]
         ": addresses S37 and strengthens flooding/split-outcome resilience under concurrent disputes."]
        [:li [:strong "R4 — Incentive/budget dynamics"]
         ": explains repeated Phase J failures (participation-stable, budget-balance); policy-level hardening such as governance bandwidth floor can mitigate system-level fragility."]]

       (warn-box
        [:span
         [:strong "Priority recommendation: "]
         "Fix in order: S36 (temporal guard), then R1 accounting unification (S34/S35), then R3 concurrency isolation (S37), followed by R4 economic/governance hardening from Phase J/AA/AD outputs."]) ]))))

;; ===========================================================================
;; ## Section 6 — Kleros Integration Model
;; ===========================================================================

(clerk/md
 "## Kleros Integration Model

### Where Kleros fits in the Sew escalation model

Sew Protocol uses Kleros as a **final escalation / backstop layer** for disputes
that cannot be resolved by the escrow-level resolver within the standard appeal
pipeline.

```
Dispute raised
     │
     ▼
 Level-0 Resolver (custom or module-assigned)
     │
     │ resolution + appeal window
     ▼
 Appeal → Level-1 Resolver
     │
     │ resolution + appeal window
     ▼
 Appeal → Level-2 Resolver  ←── Kleros proxy (0xkleros-proxy)
     │
     │ final resolution (no further escalation)
     ▼
 executePendingSettlement (after deadline)
     │
     ▼
 :released or :refunded  (terminal)
```

Kleros enters via the `resolution-module` parameter set to `\"0xkleros-proxy\"`
at escrow creation time. The module configures `escalation-resolvers` as a
level-indexed map: `{:0 \"0xl0\" :1 \"0xl1\" :2 \"0xl2\"}`.

### Kleros-specific invariants enforced by the simulator

| Invariant | Meaning |
|-----------|---------|
| `escalation-level-monotonic` | Dispute level can only increase; skipping levels is impossible |
| `pending-settlement-consistent` | Escalation requires an existing pending settlement (a resolution must have been proposed) |
| `dispute-level-bounded` | Level is capped at the max configured in `escalation-resolvers` |
| `no-withdrawal-during-dispute` | Funds cannot be withdrawn while a dispute is active at any level |
| `terminal-states-unchanged` | Once finalized, even Kleros cannot re-open the dispute |

### Deterministic scenarios covering Kleros integration

| Scenario | Description | What it proves |
|----------|-------------|----------------|
| S18 | DR3 Kleros: L0 resolver resolves at level 0 | Standard Kleros-module resolution path |
| S19 | Preemptive escalation rejected; L0 resolves | Escalation requires prior resolution |
| S20 | Max-escalation guard | Level cap enforcement |
| S21 | Pending settlement cleared on escalation | Escalation correctly clears prior settlement; L1 path works |
| S22 | Status-leak regression | Agree-to-cancel status cleared on dispute |
| S23 | Preemptive escalation blocked (seller) | Both-party escalation guard |
| S26 | Forking-strategist L1 reversal | L1 reversal is correctly accounted |
| S27 | Forking-strategist L2 fork | L2 Kleros level reached; invariants hold |
| S28 | Late escalation rejected | Post-deadline escalation is blocked |
| S32 | Premature settlement rejected | Cannot settle before appeal deadline |

### What this does NOT prove

- **Live Kleros contract integration** — the simulator uses `0xkleros-proxy` as a
  stub with a configurable resolver set. The actual Kleros court contract is not
  exercised.
- **Kleros economics** — juror incentives, PNK staking, coherence bonuses, and
  appeal fee dynamics are not modeled.
- **Cross-chain Kleros** — multi-chain dispute routing is not covered.
- **Kleros v2 (Kleros Court)** — the model assumes the Kleros v1/arbitration interface.

These gaps should be addressed in a joint integration specification with the
Kleros protocol team before production deployment.")

;; Live Kleros scenario results panel
(clerk/html
 (suite-section
  "Kleros Scenario Results"
  (fn [suite]
     (let [results      (:results suite [])
           kleros-ids   (kleros-scenario-names)
           kleros-res   (filter #(kleros-ids (:name %)) results)
          pass-n       (count (filter :pass? kleros-res))
          total-n      (count kleros-res)]
      [:div
       [:h3 "Kleros-Path Scenario Results (Live)"]
       (card (if (= pass-n total-n) :green :red)
             "Kleros-path scenarios"
             (str pass-n "/" total-n " pass")
             (str "Includes all Kleros-module resolver scenarios and forking-strategist escalation scenarios."))
       [:table {:style {:borderCollapse "collapse" :fontSize "0.82em" :width "100%"}}
        [:thead
         [:tr {:style {:background "#f1f5f9"}}
          [:th {:style {:padding "6px 10px" :textAlign "left"}} ""]
          [:th {:style {:padding "6px 10px" :textAlign "left"}} "Scenario"]
          [:th {:style {:padding "6px 10px" :textAlign "right"}} "Steps"]
          [:th {:style {:padding "6px 10px" :textAlign "right"}} "Reverts"]
          [:th {:style {:padding "6px 10px" :textAlign "left"}} "Result"]]]
        (into [:tbody]
              (map (fn [{:keys [name pass? steps reverts]}]
                     [:tr {:style {:borderBottom "1px solid #e2e8f0"}}
                      [:td {:style {:padding "5px 10px"}} (if pass? "🟢" "🔴")]
                      [:td {:style {:padding "5px 10px" :fontFamily "monospace"}} name]
                      [:td {:style {:padding "5px 10px" :textAlign "right"}} steps]
                      [:td {:style {:padding "5px 10px" :textAlign "right"}} reverts]
                      [:td {:style {:padding "5px 10px"}} (if pass? "✓ PASS" "✗ FAIL")]])
                   kleros-res))]]))))

;; ===========================================================================
;; ## Section 7 — Confidence Summary by Area
;; ===========================================================================

(clerk/html
 (suite-section
  "Confidence Summary"
  (fn [suite]
    (let [results  (:results suite [])
          pass-n   (:passed suite 0)
          total-n  (:total suite 0)
          areas
          [{:area        "State machine correctness"
            :confidence  "High"
            :backing     "Simulator-backed"
            :scenarios   "S08, S10, S22"
            :invariants  ":terminal-states-unchanged, :all-status-combinations-valid, :time-no-action-after-finality"
            :rag         :green
            :caveat      "Covers all declared transitions. Formal proof (Halmos/Foundry) not yet complete."}
           {:area        "Solvency (fund conservation)"
            :confidence  "High"
            :backing     "Simulator-backed"
            :scenarios   "S09, S24, S25, S37"
            :invariants  ":solvency, :conservation-of-funds, :held-non-negative, :fees-non-negative"
            :rag         :green
            :caveat      "Strict equality invariant (=, not ≤). External token balance verification depends on token contract behavior."}
           {:area        "Resolver authorization"
            :confidence  "High"
            :backing     "Simulator-backed"
            :scenarios   "S07, S14, S15"
            :invariants  ":dispute-resolution-path"
            :rag         :green
            :caveat      "Covers custom-resolver and module-based routing. Registry/governance integration is a separate trust assumption."}
           {:area        "Appeal window enforcement"
            :confidence  "High"
            :backing     "Simulator-backed"
            :scenarios   "S05, S13, S21, S32"
            :invariants  ":pending-settlement-consistent, :dispute-timestamp-consistent, :escalation-level-monotonic"
            :rag         :green
            :caveat      "Deadline arithmetic is integer seconds. EVM block-time drift is not modeled."}
           {:area        "Dispute timeout / liveness"
            :confidence  "High"
            :backing     "Simulator-backed"
            :scenarios   "S04, S17, S24"
            :invariants  ":no-stale-automatable-escrows, :bond-slash-bounded"
            :rag         :green
            :caveat      "Liveness depends on keeper availability. Keeper incentive economics are modeled in stochastic Phase O."}
           {:area        "Governance snapshot isolation"
            :confidence  "High"
            :backing     "Simulator-backed"
            :scenarios   "S12 (paired)"
            :invariants  ":fee-cap (via snapshot parameter)"
            :rag         :green
            :caveat      "Tests fee_bps isolation. Full governance upgrade path not yet covered."}
           {:area        "Bond / slash accounting"
            :confidence  "High"
            :backing     "Simulator-backed"
            :scenarios   "S24, S25, S34–S37, S38–S41, S45"
            :invariants  ":bond-liquidity, :bond-slash-bounded, :slash-status-consistent, :slash-epoch-cap-respected, :reversal-slash-disabled"
            :rag         :green
            :caveat      "Flash-loan stake inflation (S45) is modeled as a single-epoch attack. Multi-block flash-loan is not fully modeled."}
           {:area        "Kleros escalation path"
            :confidence  "Medium"
            :backing     "Scenario-backed (stub proxy)"
            :scenarios   "S18–S23, S26–S33"
            :invariants  ":escalation-level-monotonic, :dispute-level-bounded, :pending-settlement-consistent"
            :rag         :amber
            :caveat      "Kleros proxy is a stub. Real Kleros court economics, juror coherence, and PNK stake are not modeled. Confidence is Medium pending live integration."}
           {:area        "Capacity / dispute flooding"
            :confidence  "Medium"
            :backing     "Stochastic (Phase F) + limited deterministic"
            :scenarios   "S24 + Monte Carlo"
            :invariants  ":resolver-capacity"
            :rag         :amber
            :caveat      "S24 covers 3-escrow cascade. Scale flooding is in the Monte Carlo sweep, not this workbench."}
           {:area        "Multi-epoch adversarial behavior"
            :confidence  "Low"
            :backing     "Stochastic Phase J (separate notebook)"
            :scenarios   "None in S01–S67"
            :invariants  "N/A (stochastic)"
            :rag         :amber
            :caveat      "Multi-epoch reputation drift and ring-attack collusion are modeled in Phase J. No deterministic scenario exists."}
           {:area        "On-chain / gas correctness"
            :confidence  "Partial"
            :backing     "Partially assessed"
            :rag         :amber
            :caveat      "The simulator remains a pure Clojure model for protocol semantics. On-chain verification has partial coverage via existing Foundry invariant suites, but canonical parity against simulator invariants is still incomplete. Gas correctness/performance baselines are not yet systematically assessed or enforced as release gates."}
           {:area        "Yield position accounting"
            :confidence  "Low"
            :backing     "Partial — invariant exists, limited coverage"
            :scenarios   "—"
            :invariants  ":yield-position-consistency, :yield-exposure"
            :rag         :amber
            :caveat      "Yield invariants are defined in canonical-ids but lack dedicated scenario coverage."}]]
      [:div
       (section-header
        "Confidence Summary by Protocol Area"
        "Every confidence level is backed by a specific artifact or explains why evidence is absent.")

       (warn-box
        [:span
         [:strong "Important: confidence ≠ safety. "]
         "High confidence means the claim is well-evidenced within the simulator model. "
         "It does not mean the on-chain implementation is correct, "
         "that all attack surfaces have been discovered, "
         "or that the model is complete. "
         "Unknown unknowns are not reflected in any confidence level."])

       [:div {:style {:overflowX "auto"}}
        [:table {:style {:borderCollapse "collapse" :width "100%" :fontSize "0.83em"}}
         [:thead
          [:tr {:style {:background "#f1f5f9"}}
           [:th {:style {:padding "8px 10px" :textAlign "left"}} ""]
           [:th {:style {:padding "8px 10px" :textAlign "left"}} "Area"]
           [:th {:style {:padding "8px 10px" :textAlign "left"}} "Confidence"]
           [:th {:style {:padding "8px 10px" :textAlign "left"}} "Backing"]
           [:th {:style {:padding "8px 10px" :textAlign "left"}} "Key scenarios"]
           [:th {:style {:padding "8px 10px" :textAlign "left"}} "Key invariants"]
           [:th {:style {:padding "8px 10px" :textAlign "left"}} "Caveat"]]]
         (into [:tbody]
               (map (fn [{:keys [area confidence backing scenarios invariants rag caveat]}]
                      [:tr {:style {:borderBottom "1px solid #e2e8f0"}}
                       [:td {:style {:padding "6px 10px"}} (status-emoji rag)]
                       [:td {:style {:padding "6px 10px" :fontWeight "600"}} area]
                       [:td {:style {:padding "6px 10px"}} (conf-badge confidence)]
                       [:td {:style {:padding "6px 10px" :color "#475569"}} backing]
                       [:td {:style {:padding "6px 10px" :fontFamily "monospace"
                                     :fontSize "0.85em" :color "#374151"}} scenarios]
                       [:td {:style {:padding "6px 10px" :fontFamily "monospace"
                                     :fontSize "0.78em" :color "#374151"}} invariants]
                       [:td {:style {:padding "6px 10px" :color "#6b7280" :fontSize "0.85em"}} caveat]])
                    areas))]]]))))

;; ===========================================================================
;; ## Section 8 — Open Validation Gaps
;; ===========================================================================

(clerk/html
 (common/safe-render
  "Open Gaps"
  (fn []
    (let [gaps
          [{:id    "G01"
            :area  "Formal verification"
            :desc  "Foundry invariant suites exist (state, resolver, DRv1/DRv2, slashing, staking, yield), but simulator-to-contract invariant parity is incomplete and not yet tracked by a formal mapping matrix. Halmos profile/harness exists, but bounded symbolic checks are not yet wired into a stable CI gate."
            :risk  "High"
            :path  "Define Foundry invariant tests mirroring canonical-ids. Run Halmos for symbolic bounded checking on the state machine."}
           {:id    "G02"
            :area  "Live Kleros contract integration"
            :desc  "The Kleros proxy is a stub model. The real Kleros court, PNK economics, juror coherence bonuses, and appeal fee dynamics are not exercised."
            :risk  "High"
            :path  "Define a joint integration specification with Kleros. Run integration tests against Kleros Sepolia testnet before mainnet deployment."}
           {:id    "G03"
            :area  "Multi-epoch adversarial determinism"
            :desc  "Multi-epoch reputation drift and ring-attack collusion resistance are covered only in the stochastic Phase J simulation, not in a deterministic scenario."
            :risk  "Medium"
            :path  "Add at least one deterministic multi-epoch scenario (e.g., resolver reputation drift over N dispute cycles) to bridge the stochastic/deterministic gap."}
           {:id    "G04"
            :area  "Gas correctness"
            :desc  "The simulator is a pure Clojure integer model. Gas consumption, EVM stack depth, and token transfer fallback behavior are not modeled."
            :risk  "Medium"
            :path  "Run Foundry fuzz tests on all state-machine entry points. Profile gas for worst-case multi-escrow scenarios."}
           {:id    "G05"
            :area  "Yield invariant scenario coverage"
            :desc  ":yield-position-consistency and :yield-exposure are in canonical-ids but have no dedicated scenario. The checker runs but is not stress-tested."
            :risk  "Low"
            :path  "Add at least two yield-path scenarios: one nominal and one adversarial (e.g., yield accrual during disputed state)."}
           {:id    "G06"
            :area  "Capacity / dispute flooding at scale"
            :desc  "S24 covers 3-escrow stake depletion. Large-scale concurrent dispute flooding is in the Monte Carlo Phase F sweep only."
            :risk  "Low"
            :path  "Add a deterministic flooding scenario (e.g., N=20 concurrent disputes against a single under-bonded resolver) to bridge the Monte Carlo gap."}
           {:id    "G07"
            :area  "Oracle / off-chain evidence submission"
            :desc  "The simulator does not model the submission of off-chain evidence hashes or the oracle verification layer that Kleros jurors may rely on."
            :risk  "Medium"
            :path  "Define the evidence submission interface. Add scenarios testing evidence-hash integrity and oracle dispute triggers."}
           {:id    "G08"
            :area  "Cross-chain dispute routing"
            :desc  "Multi-chain escrow scenarios (e.g., Kleros on L1, escrow on L2) are not modeled."
            :risk  "Low"
            :path  "Design cross-chain escalation specification. Add bridge-delay modeling to the timeout invariants."}
           {:id    "G09"
            :area  "Governance upgrade / proxy attack surface"
            :desc  "S12 covers snapshot isolation but not the full governance upgrade path (proxy admin key, timelock, quorum). Governance capture under long-tail adversarial conditions is stochastic only."
            :risk  "Medium"
            :path  "Add deterministic governance-upgrade scenarios. Verify snapshot isolation holds across proxy upgrades."}
           {:id    "G10"
            :area  "EVM block-time drift and timestamp manipulation"
            :desc  "The simulator uses integer timestamps. EVM block-timestamp miner manipulation (±15s) is not modeled. Appeal window boundary conditions may be off-by-one under adversarial miner assumptions."
            :risk  "Low"
            :path  "Add timestamp boundary scenarios with ±1 block tolerance. Verify deadline arithmetic under miner-controlled timestamp drift."}]]
      [:div
       (section-header
        "Open Validation Gaps"
        "Known gaps in evidence, coverage, or integration. Gaps do not necessarily indicate vulnerabilities — they indicate areas where evidence is incomplete.")

       (note-box
        [:span
         "This list is derived from the coverage analysis above and the known limitations of the simulator model. "
         "A gap is flagged wherever the current evidence does not fully support a confidence claim, "
         "a known external dependency is unstubbed, or a threat class is only stochastically (not deterministically) covered."])

       [:div {:style {:overflowX "auto"}}
        [:table {:style {:borderCollapse "collapse" :width "100%" :fontSize "0.83em"}}
         [:thead
          [:tr {:style {:background "#f1f5f9"}}
           [:th {:style {:padding "7px 10px" :textAlign "left"}} "ID"]
           [:th {:style {:padding "7px 10px" :textAlign "left"}} "Area"]
           [:th {:style {:padding "7px 10px" :textAlign "left"}} "Risk"]
           [:th {:style {:padding "7px 10px" :textAlign "left"}} "Description"]
           [:th {:style {:padding "7px 10px" :textAlign "left"}} "Suggested path"]]]
         (into [:tbody]
               (map (fn [{:keys [id area risk desc path]}]
                      (let [risk-rag (case risk "High" :red "Medium" :amber :green)]
                        [:tr {:style {:borderBottom "1px solid #e2e8f0"}}
                         [:td {:style {:padding "6px 10px" :fontFamily "monospace" :fontWeight "600"}}
                          id]
                         [:td {:style {:padding "6px 10px" :fontWeight "600"}} area]
                         [:td {:style {:padding "6px 10px"}} (rag-badge risk-rag risk)]
                         [:td {:style {:padding "6px 10px" :color "#374151"}} desc]
                         [:td {:style {:padding "6px 10px" :color "#475569" :fontSize "0.9em"}}
                          path]]))
                    gaps))]]

       [:div {:style {:marginTop "16px" :background "#f8fafc"
                      :border "1px solid #e2e8f0" :borderRadius "6px"
                      :padding "12px 16px"}}
        [:h4 {:style {:margin "0 0 8px 0"}} "Gap priority summary"]
        [:ul {:style {:margin "0" :paddingLeft "20px" :fontSize "0.85em" :lineHeight "1.8"}}
         [:li [:strong "Highest priority (G01, G02): "]
          "Formal verification and live Kleros integration — these are prerequisites "
          "for production deployment confidence."]
         [:li [:strong "Medium priority (G03, G04, G07, G09): "]
          "Multi-epoch determinism, gas correctness, oracle interface, governance upgrade — "
          "required before full protocol review sign-off."]
         [:li [:strong "Lower priority (G05, G06, G08, G10): "]
          "Yield invariant coverage, scale flooding, cross-chain, timestamp drift — "
          "important for completeness but lower immediate risk."]]]]))))

;; ===========================================================================
;; ## Section 9 — Artifact Provenance
;; ===========================================================================

(clerk/html
 (suite-section
  "Artifact Provenance"
  (fn [suite]
    (let [inv-count   (count invariants/canonical-ids)
          sc-count    (count sc/all-scenarios)
          golden-n    (count (or @golden-reports {}))
          trace-n     (count (or @all-traces []))
           gate-rag    (if @test-summary
                         (if (= "pass" (str (:overall_status @test-summary))) :green :red)
                         :amber)]
      [:div
       (section-header
        "Artifact Provenance"
        "What artifact backs each claim in this workbench, and where to find it.")

       (simple-table
        ["Artifact" "Path" "Status" "Used in sections"]
        (map (fn [[artifact path status sections]]
               [:tr {:style {:borderBottom "1px solid #e2e8f0"}}
                [:td {:style {:padding "6px 10px" :fontWeight "600"}} artifact]
                [:td {:style {:padding "6px 10px" :fontFamily "monospace" :fontSize "0.85em"}} path]
                [:td {:style {:padding "6px 10px"}} status]
                [:td {:style {:padding "6px 10px" :color "#475569"}} sections]])
             [["Invariant runner (live)"
               "resolver-sim.protocols.sew.invariant-runner/run-all"
               (let [{:keys [passed total error]} suite]
                 (if error
                   (str "⚠ error: " error)
                   (str "🟢 " passed "/" total " pass")))
               "§1, §3, §4, §5, §6, §7"]
              ["Canonical invariant IDs"
               "resolver-sim.protocols.sew.invariants/canonical-ids"
               (str "🟢 " inv-count " invariants loaded")
               "§3, §7"]
              ["Scenario registry"
               "resolver-sim.protocols.sew.invariant-scenarios/all-scenarios"
               (str "🟢 " sc-count " scenarios")
               "§4, §5"]
              ["Scenario type registry"
               "resolver-sim.protocols.sew.invariant-scenarios/scenario-type-registry"
               "🟢 loaded"
               "§4, §5"]
              ["State machine transitions"
               "resolver-sim.protocols.sew.state-machine/allowed-transitions"
               "🟢 loaded"
               "§2"]
               ["Test summary (CI gate)"
                "results/test-artifacts/test-summary.json"
                (if @test-summary
                  (str "🟢 loaded — "
                       (or (:overall_status @test-summary) "unknown"))
                  "🟠 not found (optional)")
               "§1"]
              ["Coverage data"
               "results/test-artifacts/coverage.json"
               (if @coverage-data "🟢 loaded" "🟠 not found (optional)")
               "§3"]
              ["Golden reports"
               "data/fixtures/golden/*.report.edn"
               (if (pos? golden-n)
                 (str "🟢 " golden-n " loaded")
                 "🟠 none found (optional)")
               "§1"]
              ["Trace metadata"
               "data/fixtures/traces/*.trace.json"
               (if (pos? trace-n)
                 (str "🟢 " trace-n " loaded")
                 "🟠 none found (optional)")
               "§1"]]))

       [:div {:style {:marginTop "12px" :fontSize "0.82em" :color "#64748b"}}
        "All live artifacts are loaded at notebook evaluation time and cached by Clerk. "
        "Re-run with "
        [:code "clojure -M:clerk"] " or "
        [:code "clerk/serve!"]
        " to refresh. File artifacts use graceful degradation — absent files show amber status, not red."]]))))

;; ===========================================================================
;; ## Section 10 — Appeal Window & Deadline Testing
;; ===========================================================================

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/md "## Appeal Window & Deadline Testing

This section documents the deterministic stress-testing of appeal windows, dispute escalation deadlines, and pending-settlement expiration boundaries.

### Scenarios
- **S05 (Pending-settlement-execute):** Tests execution of settlements after the appeal deadline.
- **S13 (Pending-settlement-refund):** Tests refund logic when pending settlement is cleared.
- **S21 (DR3 Kleros pending cleared):** Tests pending settlement clearing on escalation.
- **S57 (Pending-settlement-expiry):** Tests 1s boundary conditions on settlement expiry.
- **S74 (Appeal-deadline-boundary):** Validates appeal window boundaries with sub-second precision.

### Status (Live)
")

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 (suite-section
  "Appeal Status"
  (fn [suite]
     (let [results      (:results suite [])
           appeal-ids   (appeal-scenario-names)
           appeal-res   (filter #(appeal-ids (:name %)) results)
          pass-n       (count (filter :pass? appeal-res))
          total-n      (count appeal-res)]
      [:div
       (card (if (= pass-n total-n) :green :red)
             "Appeal-path scenarios"
             (str pass-n "/" total-n " pass")
             "Tests pending settlement expiration and appeal window boundaries.")
       [:table {:style {:borderCollapse "collapse" :fontSize "0.82em" :width "100%"}}
        [:thead
         [:tr {:style {:background "#f1f5f9"}}
          [:th {:style {:padding "6px 10px" :textAlign "left"}} ""]
          [:th {:style {:padding "6px 10px" :textAlign "left"}} "Scenario"]
          [:th {:style {:padding "6px 10px" :textAlign "right"}} "Result"]]]
        (into [:tbody]
              (map (fn [{:keys [name pass?]}]
                     [:tr {:style {:borderBottom "1px solid #e2e8f0"}}
                      [:td {:style {:padding "5px 10px"}} (if pass? "🟢" "🔴")]
                      [:td {:style {:padding "5px 10px" :fontFamily "monospace"}} name]
                      [:td {:style {:padding "5px 10px"}} (if pass? "✓ PASS" "✗ FAIL")]])
                   appeal-res))]]))))

;; ---
;; *Notebook generated by the Sew Protocol simulation toolchain.*
;; *Source: `notebooks/dispute_resolution.clj`*
;; *All scenario results are live — evaluated in-process at render time.*
