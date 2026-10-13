;; # Sew Protocol -- Security Validation Notebook
;;
;; **Audience:** Auditors, security researchers, grant reviewers, governance participants.
;;
;; **Purpose:** Structured, falsifiable validation of Sew Protocol security properties.
;; Each section answers: what property is asserted, how could it fail, how was it tested,
;; and what evidence supports the conclusion.
;;
;; **Not a marketing document.** Honest residual risk and open assumptions are documented
;; in every section and consolidated in SEC-10 (Known Limitations).
;;
;; **Reproducibility:** All evidence is derived from deterministic artifacts in
;; `results/test-artifacts/`. Regenerate with `bb run:scenario`.
;;
;; **Status key:**
;; - VERIFIED -- property holds, no open issues, evidence artifact present
;; - PARTIAL -- property holds in tested domains; gaps or open issues exist
;; - ASSUMPTION REQUIRED -- relies on unvalidated invariants or off-chain guarantees
;; - NOT EVALUATED -- claim registered but not yet exercised by any scenario

^{:nextjournal.clerk/toc true
  :nextjournal.clerk/visibility {:code :fold :result :show}
  :nextjournal.clerk/dark-mode true}
(ns notebooks.security-validation
  (:require [nextjournal.clerk :as clerk]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [resolver-sim.notebook-support.nav :as nav]
            [resolver-sim.notebook-support.security :as sec]
            [resolver-sim.protocols.sew.invariant-scenarios :as scenarios]))

;; Hardcoded scenario display entries validated against canonical list at load time.
(def ^:private adversarial-panel-entries
  [["S08" "State machine attack gauntlet"]
   ["S09" "Multi-escrow solvency"]
   ["S10" "Double-finalize rejected"]
   ["S18" "Kleros L0 resolution"]
   ["S19" "Escalation rejected, L0 resolves"]
   ["S20" "Max escalation guard"]
   ["S26" "Forking strategist L1 reversal"]
   ["S37" "Escalation abuse"]
   ["S38" "Resolver cartel inactivity"]])

(def ^:private yield-panel-display-ids
  ["S68 Aave long-horizon 10y accrual"
   "S69 Fixed long-horizon 10y quarterly"
   "S73 Rounding drift, repeated small"
   "S78 Aave partial liquidity release"
   "S79 Aave partial liquidity dispute"
   "S80 Governance disable post-create"
   "S88 Resolver yield accrual"
   "S82 Shortfall recovery cycle"])

(when-not (every? (fn [[sid _]]
                    (some #(str/starts-with? (first %) sid) scenarios/all-scenarios))
                  adversarial-panel-entries)
  (println "WARN: adversarial-panel-entries contains scenario IDs not in all-scenarios"))

;; ---------------------------------------------------------------------------
;; Notebook header
;; ---------------------------------------------------------------------------

^{:nextjournal.clerk/visibility {:code :hide :result :show}
  :nextjournal.clerk/width :full}
(clerk/html (nav/top-nav-bar :security-validation))

^{:nextjournal.clerk/visibility {:code :hide :result :show}
  :nextjournal.clerk/width :full}
(clerk/html
 [:div {:style {:background "#050914" :border-bottom "1px solid #1e293b"
                :padding "24px 32px" :font-family "Inter, sans-serif"}}
  [:div {:style {:display "flex" :justify-content "space-between" :align-items "flex-start"}}
   [:div
    [:h1 {:style {:font-size "22px" :font-weight "900" :color "#f1f5f9" :margin "0 0 4px 0"}}
     "Security Validation Notebook"]
    [:p {:style {:color "#64748b" :font-size "13px" :margin "0"
                 :font-family "JetBrains Mono, monospace"}}
     "Sew Protocol v1 -- Deterministic adversarial evidence register"]]
   [:div {:style {:text-align "right" :font-family "JetBrains Mono, monospace" :font-size "10px"
                  :color "#4b5563"}}
    [:div "38 invariants"]
    [:div "74 scenarios"]
    [:div "5 falsifiable claims"]]]])

;; ---------------------------------------------------------------------------
;; Data loading (all JVM-side, no SCI)
;; ---------------------------------------------------------------------------

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(require '[resolver-sim.notebook-support.manifest.loader :as loader])

^{:nextjournal.clerk/no-cache true
  :nextjournal.clerk/visibility {:code :hide :result :hide}}
(def sec-data
  (let [run (loader/load-focused)
        load-edn  (fn [p] (try (edn/read-string (slurp (io/file p)))
                               (catch Exception _ nil)))]
    {:findings     (loader/load-artifact-by-id run "findings")
     :issues       (loader/load-artifact-by-id run "issues")
     :test-run     (:manifest run)
     :test-summary (:summary run)
     :coverage     (loader/load-artifact-by-id run "coverage")
     :claimable    (loader/load-artifact-by-id run "claimable-classification")
     :signature    (loader/load-artifact-by-id run "signature")
     :claims       (load-edn  "data/claims/sew-claims.edn")}))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def findings-by-severity
  (group-by :severity (:findings sec-data)))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def high-findings (get findings-by-severity "high" []))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def open-issues (:issues sec-data))

;; ---------------------------------------------------------------------------
;; SEC-01: Protocol Security Model
;; ---------------------------------------------------------------------------

^{:nextjournal.clerk/width :full}
(clerk/html (sec/section-header 1 "Protocol Security Model" :assumption-req
  "Trust assumptions, actor privileges, governance constraints, and forward-only upgrade semantics.
   This section establishes the threat model boundary: what the protocol guarantees vs. what it assumes."))

^{:nextjournal.clerk/width :full}
(clerk/html
 [:div {:style {:font-family "Inter, sans-serif" :padding "0 4px"}}

  (sec/content-box
   [:h3 {:style {:color "#93c5fd" :font-size "13px" :margin "0 0 12px 0"}} "Actor Privilege Map"]
   [:div {:style {:display "grid" :grid-template-columns "repeat(3, 1fr)" :gap "12px"}}
    (for [[actor privs color]
          [["Sender" ["create escrow" "cancel (mutual)" "fund escrow"] "#3b82f6"]
           ["Recipient" ["confirm settlement" "cancel (mutual)" "claim after release"] "#22c55e"]
           ["Resolver" ["execute resolution" "propose fraud slash" "register/withdraw stake"] "#f59e0b"]
           ["Governance" ["update fee params (future escrows only)" "pause protocol"
                          "upgrade module registry" "set timelock durations"] "#a78bfa"]
           ["Kleros L1" ["hear appeals" "overturn L0 resolution" "slash on reversal"] "#ef4444"]
           ["Guardian" ["emergency pause" "bounded time window" "cannot alter active escrows"] "#94a3b8"]]]
      [:div {:key actor :style {:background "#0d1117" :border "1px solid #1e293b" :padding "12px"}}
       [:div {:style {:color color :font-family "JetBrains Mono, monospace"
                      :font-size "10px" :font-weight "700" :margin-bottom "8px"
                      :text-transform "uppercase"}}
        actor]
       (for [p privs]
         [:div {:key p :style {:color "#94a3b8" :font-size "11px" :margin-bottom "3px"}}
          (str "> " p)])])])

  (sec/content-box
   [:h3 {:style {:color "#a78bfa" :font-size "13px" :margin "0 0 12px 0"}}
    "Governance Constraint Table"]
   (sec/two-col
    [:div
     [:div {:style {:color "#4ade80" :font-size "12px" :font-weight "700" :margin-bottom "8px"}}
      "Governance CAN"]
     (for [item ["Update resolver fee basis points"
                 "Update timelock durations"
                 "Add/remove resolver module versions"
                 "Pause new escrow creation"
                 "Upgrade module registry"]]
       [:div {:key item :style {:color "#86efac" :font-size "11px" :margin-bottom "4px"}}
        (str "++ " item)])]
    [:div
     [:div {:style {:color "#f87171" :font-size "12px" :font-weight "700" :margin-bottom "8px"}}
      "Governance CANNOT"]
     (for [item ["Alter an active escrow's parameters"
                 "Redirect funds from an existing escrow"
                 "Override a resolver's bonded decision retroactively"
                 "Cancel a dispute already in progress"
                 "Modify settlement amounts post-creation"]]
       [:div {:key item :style {:color "#fca5a5" :font-size "11px" :margin-bottom "4px"}}
        (str "-- " item)])]))

  (sec/warn-box
   "Forward-only upgrade semantics: governance changes apply only to escrows created after
    the upgrade block. Existing and active escrows bind to the module version at their creation
    time. This is a protocol design guarantee -- claim :forward-only-upgrade-safe is registered
    but not yet exercised by a deterministic scenario (see SEC-06 and SEC-10).")])

;; ---------------------------------------------------------------------------
;; SEC-02: State Machine Integrity
;; ---------------------------------------------------------------------------

^{:nextjournal.clerk/width :full}
(clerk/html (sec/section-header 2 "State Machine Integrity" :partial
  "Legal transitions only. Impossible states unreachable. Terminal-state correctness.
   Conservation across transitions. Validated against 41 deterministic in-process scenarios."))

^{:nextjournal.clerk/width :full}
(clerk/html
 [:div {:style {:font-family "Inter, sans-serif" :padding "0 4px"}}

  (sec/content-box
   [:h3 {:style {:color "#93c5fd" :font-size "13px" :margin "0 0 12px 0"}}
    "EscrowState Transition Graph"]
   [:p {:style {:color "#64748b" :font-size "11px" :margin "0 0 12px 0"}}
    "Source: resolver-sim.protocols.sew.state-machine/allowed-transitions. "
    "Terminal states (:released, :refunded) have no outgoing edges. "
    ":resolved is defined in the on-chain enum but is never called by any production code path."]
   (sec/state-machine-svg))

  (sec/content-box
   [:h3 {:style {:color "#93c5fd" :font-size "13px" :margin "0 0 12px 0"}}
    "Transition Coverage (from " [:code {:style {:color "#7dd3fc"}} "results/test-artifacts/coverage.json"] ")"]
   (let [transitions (get (:coverage sec-data) :transition-hit-freq {})
         unhit       (get (:coverage sec-data) :unhit-transitions [])]
     [:div
      [:div {:style {:display "grid" :grid-template-columns "repeat(4, 1fr)" :gap "8px"
                     :margin-bottom "12px"}}
       (for [[tr cnt] (sort-by first transitions)]
         [:div {:key (str tr) :style {:background "#0d1b2e" :border "1px solid #1e3a5f"
                                      :padding "8px 10px"}}
          [:div {:style {:color "#7dd3fc" :font-family "JetBrains Mono, monospace"
                         :font-size "9px" :margin-bottom "4px"}}
           (name tr)]
          [:div {:style {:color "#f1f5f9" :font-weight "700" :font-size "14px"}} cnt]])]
      (when (seq unhit)
        [:div {:style {:background "#1c1007" :border "1px solid #d97706" :padding "8px 12px"}}
         [:span {:style {:color "#fbbf24" :font-family "JetBrains Mono, monospace"
                         :font-size "10px" :font-weight "700"}} "NOT COVERED: "]
         [:span {:style {:color "#d97706" :font-size "11px"}}
          (str/join ", " (map str unhit))
          " -- time-advancing and batch-automation paths not exercised by current scenario suite."]])]))

  (sec/content-box
   [:h3 {:style {:color "#93c5fd" :font-size "13px" :margin "0 0 12px 0"}}
    "Relevant Invariants"]
   (sec/invariant-table nil))])

;; ---------------------------------------------------------------------------
;; SEC-03: Conservation of Funds
;; ---------------------------------------------------------------------------

^{:nextjournal.clerk/width :full}
(clerk/html (sec/section-header 3 "Conservation of Funds" :partial
  "No token creation, no token loss under any tested path. Correct accounting under dispute,
   yield, and appeal. One open high-severity finding under concurrent multi-escrow disputes."))

^{:nextjournal.clerk/width :full}
(clerk/html
 [:div {:style {:font-family "Inter, sans-serif" :padding "0 4px"}}

  (sec/content-box
   [:h3 {:style {:color "#93c5fd" :font-size "13px" :margin "0 0 12px 0"}}
    "Fund Flow Architecture"]
   (sec/fund-flow-svg)
   [:p {:style {:color "#64748b" :font-size "11px" :margin "12px 0 0 0"}}
    "SUM(deposits) = SUM(principal claimable) + SUM(fees) + SUM(bonds) at all protocol states.
     Checked by :conservation-of-funds and :solvency invariants on every scenario transition."])

  (when (seq high-findings)
    (sec/content-box
     [:h3 {:style {:color "#ef4444" :font-size "13px" :margin "0 0 12px 0"}}
      "High-Severity Findings"]
     (for [f high-findings]
       (sec/finding-card f))))

  (sec/content-box
   [:h3 {:style {:color "#93c5fd" :font-size "13px" :margin "0 0 12px 0"}}
    "Conservation Invariants"]
   (sec/invariant-table
    [:solvency :conservation-of-funds :senior-coverage-not-exceeded]))

  (sec/warn-box
   "Multi-escrow solvency under concurrent disputes (FINDING-20260527-153446-744033):
    Adversarial scenario S09 demonstrates that bond/slash accounting across simultaneous
    disputes may produce a :solvency check warning. This is tracked as an open issue.
    Single-escrow conservation is validated across all 74 scenarios.")])

;; ---------------------------------------------------------------------------
;; SEC-04: Withdrawal Safety / Claimable Architecture
;; ---------------------------------------------------------------------------

^{:nextjournal.clerk/width :full}
(clerk/html (sec/section-header 4 "Withdrawal Safety / Claimable Architecture" :verified
  "Pull-based withdrawal enforced. No forced token delivery. Idempotent claiming semantics.
   Withdrawal blocked during active dispute. Claimable classification verified from artifact."))

^{:nextjournal.clerk/width :full}
(clerk/html
 [:div {:style {:font-family "Inter, sans-serif" :padding "0 4px"}}

  (sec/content-box
   [:h3 {:style {:color "#93c5fd" :font-size "13px" :margin "0 0 12px 0"}}
    "Claimable Classification (from " [:code {:style {:color "#7dd3fc"}}
     "results/test-artifacts/claimable-classification.json"] ")"]
   (let [classes (get-in (:claimable sec-data) [:classes] {})]
     [:div {:style {:display "grid" :grid-template-columns "repeat(2, 1fr)" :gap "8px"}}
      (for [[cls-kw attrs] classes]
        [:div {:key (str cls-kw)
               :style {:background "#0d1117" :border "1px solid #1e293b" :padding "10px 12px"}}
         [:div {:style {:color "#7dd3fc" :font-family "JetBrains Mono, monospace"
                        :font-size "10px" :font-weight "700" :margin-bottom "8px"}}
          (name cls-kw)]
         (for [[k v] attrs]
           [:div {:key (str k) :style {:margin-bottom "3px"}}
            (sec/label-val (name k) (str v))])])]))

  (sec/content-box
   [:h3 {:style {:color "#93c5fd" :font-size "13px" :margin "0 0 10px 0"}}
    "Pull Model Guarantees"]
   [:div {:style {:display "grid" :grid-template-columns "1fr 1fr" :gap "12px"}}
    (for [[title items color]
          [["Architecture Properties"
            ["All withdrawals are pull-based (no push delivery)"
             "Settlement transition separated from payout"
             "Entitlement persists until explicitly claimed"
             "Claimable balance computed at execution time"
             "Replay of withdrawal attempt: idempotent"]
            "#22c55e"]
           ["Checked By Invariants"
            [":no-withdrawal-during-dispute"
             ":finalization-accounting-correct"
             ":conservation-of-funds"
             ":terminal-states-unchanged"
             ":no-double-finalize"]
            "#7dd3fc"]]]
      [:div {:key title}
       [:div {:style {:color color :font-size "11px" :font-weight "700" :margin-bottom "8px"}}
        title]
       (for [item items]
         [:div {:key item :style {:color "#94a3b8" :font-size "11px" :margin-bottom "4px"}}
          (str "> " item)])])])])

;; ---------------------------------------------------------------------------
;; SEC-05: Dispute Resolution Robustness
;; ---------------------------------------------------------------------------

^{:nextjournal.clerk/width :full}
(clerk/html (sec/section-header 5 "Dispute Resolution Robustness" :partial
  "Escalation correctness, appeal windows, resolver rotation, timeout behavior.
   Three falsifiable claims not-falsified across scenario suite. Adversarial scenarios
   cover cartelisation, bribery, dispute flooding, and same-block escalation races."))

^{:nextjournal.clerk/width :full}
(clerk/html
 [:div {:style {:font-family "Inter, sans-serif" :padding "0 4px"}}

  (sec/content-box
   [:h3 {:style {:color "#93c5fd" :font-size "13px" :margin "0 0 12px 0"}}
    "Falsifiable Claims"]
   (for [claim (filter #(#{:resolver-collusion-unprofitable
                           :dispute-liveness-bounded
                           :bond-slash-deters-frivolous-appeals}
                          (:claim/id %))
                       (:claims sec-data))]
     (sec/claim-row claim)))

  (sec/content-box
   [:h3 {:style {:color "#93c5fd" :font-size "13px" :margin "0 0 12px 0"}}
    "Adversarial Scenario Coverage"]
   [:div {:style {:display "grid" :grid-template-columns "repeat(3, 1fr)" :gap "8px"}}
     (for [[scenario-id label] adversarial-panel-entries]
      [:div {:key scenario-id
             :style {:background "#0d1117" :border "1px solid #1e293b" :padding "8px 10px"}}
       [:div {:style {:color "#7dd3fc" :font-family "JetBrains Mono, monospace"
                      :font-size "10px" :font-weight "700"}}
        scenario-id]
       [:div {:style {:color "#94a3b8" :font-size "11px" :margin-top "3px"}} label]])])

  (sec/content-box
   [:h3 {:style {:color "#93c5fd" :font-size "13px" :margin "0 0 12px 0"}}
    "Dispute & Resolution Invariants"]
   (sec/invariant-table nil))

  (sec/warn-box
   "Phase AD (adversarial sweep) covers dispute flooding, cartel coordination, and bribery pressure.
    S26 (forking strategist L1 reversal) is currently unclassified -- tracked as an open issue.
    Governance sandwich attacks and same-block escalation races are scenario-covered but not
    exercised against a live on-chain implementation.")])

;; ---------------------------------------------------------------------------
;; SEC-06: Governance Risk Containment
;; ---------------------------------------------------------------------------

^{:nextjournal.clerk/width :full}
(clerk/html (sec/section-header 6 "Governance Risk Containment" :assumption-req
  "Governance cannot alter active escrows. Changes affect future escrows only.
   Emergency pause bounds enforced. Claim :forward-only-upgrade-safe is registered
   but not yet exercised by a deterministic scenario -- this is an explicit gap."))

^{:nextjournal.clerk/width :full}
(clerk/html
 [:div {:style {:font-family "Inter, sans-serif" :padding "0 4px"}}

  (sec/content-box
   [:h3 {:style {:color "#93c5fd" :font-size "13px" :margin "0 0 12px 0"}}
    "Forward-Only Upgrade Mechanism"]
   [:div {:style {:display "grid" :grid-template-columns "1fr 1fr" :gap "16px"}}
    [:div
     [:div {:style {:color "#4ade80" :font-size "12px" :font-weight "700" :margin-bottom "8px"}}
      "Design Guarantee"]
     (for [item ["Escrow records module version at creation time"
                 "Module snapshot is immutable per escrow"
                 "Parameter changes applied only to new escrows"
                 "Active disputes use creation-time parameters"
                 "No retroactive fee/bond/slash mutation"]]
       [:div {:key item :style {:color "#86efac" :font-size "11px" :margin-bottom "4px"}}
        (str "> " item)])]
    [:div
     [:div {:style {:color "#fbbf24" :font-size "12px" :font-weight "700" :margin-bottom "8px"}}
      "Scenario Coverage Gap"]
     (for [item ["S12 covers governance snapshot isolation (basic)"
                 "No scenario tests repeated pause abuse"
                 "Claim :forward-only-upgrade-safe: exercised by S111 + S12/S61/S84"
                 "Mid-dispute fee upgrade replay validates snapshot isolation"]]
       [:div {:key item :style {:color "#fde68a" :font-size "11px" :margin-bottom "4px"}}
        (str "! " item)])]])

  (for [claim (filter #(= :forward-only-upgrade-safe (:claim/id %)) (:claims sec-data))]
    (sec/claim-row claim))

  (sec/warn-box
   "This section is rated ASSUMPTION REQUIRED because the primary claim is :not-evaluated.
    Governance isolation is a strong design guarantee of the protocol, but deterministic
    replay of a mid-dispute governance upgrade scenario is pending. Auditors should treat
    the forward-only guarantee as an implementation assumption until a scenario suite
    explicitly tests upgrade-at-dispute-depth scenarios.")])

;; ---------------------------------------------------------------------------
;; SEC-07: Yield Integration Safety
;; ---------------------------------------------------------------------------

^{:nextjournal.clerk/width :full}
(clerk/html (sec/section-header 7 "Yield Integration Safety" :partial
  "Yield modules cannot redirect funds. Unwind paths safe. Isolation between escrows.
   Liquidity shortfall handling tested. Partial-liquidity claim :not-evaluated."))

^{:nextjournal.clerk/width :full}
(clerk/html
 [:div {:style {:font-family "Inter, sans-serif" :padding "0 4px"}}

  (sec/content-box
   [:h3 {:style {:color "#93c5fd" :font-size "13px" :margin "0 0 12px 0"}}
    "Yield Safety Guarantees"]
   (sec/two-col
    [:div
     [:div {:style {:color "#4ade80" :font-size "11px" :font-weight "700" :margin-bottom "8px"}}
      "Enforced by Invariants"]
     (for [inv [":yield-position-consistency" ":yield-exposure"
                ":senior-coverage-not-exceeded" ":conservation-of-funds"]]
       [:div {:key inv :style {:color "#86efac" :font-family "JetBrains Mono, monospace"
                               :font-size "10px" :margin-bottom "4px"}}
        inv])]
    [:div
     [:div {:style {:color "#fbbf24" :font-size "11px" :font-weight "700" :margin-bottom "8px"}}
      "Yield-Specific Scenarios"]
      (for [s yield-panel-display-ids]
       [:div {:key s :style {:color "#fde68a" :font-size "11px" :margin-bottom "3px"}}
        (str "> " s)])]))

  (sec/content-box
   [:h3 {:style {:color "#93c5fd" :font-size "13px" :margin "0 0 12px 0"}}
    "Shortfall Policy (from claimable classification)"]
   (let [policy (get-in (:claimable sec-data) [:shortfall_policy] {})]
     (if (seq policy)
       [:div {:style {:display "grid" :grid-template-columns "repeat(3, 1fr)" :gap "8px"}}
        (for [[k v] policy]
          [:div {:key (str k) :style {:background "#0d1117" :border "1px solid #1e293b"
                                      :padding "8px 10px"}}
           (sec/label-val (name k) (str v))])]
       [:div {:style {:color "#4b5563" :font-size "11px"}} "Shortfall policy: not loaded"])))

  (for [claim (filter #(= :partial-liquidity-shortfall-bounded (:claim/id %)) (:claims sec-data))]
    (sec/claim-row claim))

  (sec/warn-box
   "Yield module isolation is namespace-scoped by (msg.sender, escrowId) at the smart contract
    level -- this cannot be validated by the Clojure simulation alone. The shortfall bound
    (yield only, never principal) is tested in S78-S82/S81 and claim :partial-liquidity-
    shortfall-bounded is linked in sew-claims.edn.")])

;; ---------------------------------------------------------------------------
;; SEC-08: Adversarial Economic Analysis
;; ---------------------------------------------------------------------------

^{:nextjournal.clerk/width :full}
(clerk/html (sec/section-header 8 "Adversarial Economic Analysis" :partial
  "Attack profitability bounds, slashing effectiveness, griefing resistance.
   84 total findings across 74 scenarios. 1 high-severity, 83 low-severity.
   Monte Carlo Phase AD covers dispute flooding, resolver cartelisation, and bribery."))

^{:nextjournal.clerk/width :full}
(clerk/html
 [:div {:style {:font-family "Inter, sans-serif" :padding "0 4px"}}

  (sec/content-box
   [:h3 {:style {:color "#93c5fd" :font-size "13px" :margin "0 0 12px 0"}}
    "Finding Severity Summary"]
   [:div {:style {:display "flex" :gap "24px" :margin-bottom "16px"}}
    [:div {:style {:background "#1f0707" :border "1px solid #dc2626" :padding "12px 24px"
                   :text-align "center"}}
     [:div {:style {:color "#f87171" :font-size "28px" :font-weight "900"}}
      (count high-findings)]
     [:div {:style {:color "#dc2626" :font-size "10px" :font-family "JetBrains Mono, monospace"}}
      "HIGH SEVERITY"]]
    [:div {:style {:background "#12110a" :border "1px solid #d97706" :padding "12px 24px"
                   :text-align "center"}}
     [:div {:style {:color "#fbbf24" :font-size "28px" :font-weight "900"}}
      (count (get findings-by-severity "low" []))]
     [:div {:style {:color "#d97706" :font-size "10px" :font-family "JetBrains Mono, monospace"}}
      "LOW SEVERITY"]]
    [:div {:style {:background "#052e16" :border "1px solid #16a34a" :padding "12px 24px"
                   :text-align "center"}}
     [:div {:style {:color "#4ade80" :font-size "28px" :font-weight "900"}}
      (count (:findings sec-data))]
     [:div {:style {:color "#16a34a" :font-size "10px" :font-family "JetBrains Mono, monospace"}}
      "TOTAL FINDINGS"]]]
   [:p {:style {:color "#64748b" :font-size "11px" :margin "0"}}
    "All findings are category :protocol_risk. Low-severity findings represent research observations
     about protocol behaviour under adversarial conditions, not necessarily exploitable vulnerabilities."])

  (sec/content-box
   [:h3 {:style {:color "#93c5fd" :font-size "13px" :margin "0 0 12px 0"}}
    "High-Severity Finding Detail"]
   (for [f high-findings]
     (sec/finding-card f))
   (when (empty? high-findings)
     [:div {:style {:color "#4ade80" :font-size "12px"}} "No high-severity findings loaded."]))

  (sec/content-box
   [:h3 {:style {:color "#93c5fd" :font-size "13px" :margin "0 0 12px 0"}}
    "Adversarial Claim Evidence"]
   (for [claim (filter #(= :resolver-collusion-unprofitable (:claim/id %)) (:claims sec-data))]
     (sec/claim-row claim)))

  (sec/content-box
   [:h3 {:style {:color "#93c5fd" :font-size "13px" :margin "0 0 12px 0"}}
    "Threat Coverage (from coverage.json)"]
   (let [threat-freq (get (:coverage sec-data) :threat-tag-freq {})]
     [:div {:style {:display "flex" :flex-wrap "wrap" :gap "6px"}}
      (for [[tag cnt] (sort-by (comp - val) threat-freq)]
        [:div {:key (str tag)
               :style {:background "#0d1117" :border "1px solid #1e3a5f"
                       :padding "4px 10px" :font-family "JetBrains Mono, monospace"}}
         [:span {:style {:color "#7dd3fc" :font-size "10px"}} (name tag)]
         [:span {:style {:color "#4b5563" :font-size "10px" :margin-left "8px"}} (str "x" cnt)]])]))])

;; ---------------------------------------------------------------------------
;; SEC-09: Replay Determinism & Evidence Integrity
;; ---------------------------------------------------------------------------

^{:nextjournal.clerk/width :full}
(clerk/html (sec/section-header 9 "Replay Determinism & Evidence Integrity" :verified
  "Deterministic replay outputs, evidence hashing, manifest provenance, and signed artifact chain.
   Signature produced by Ed25519 test key over canonical deep-sorted manifest hash."))

^{:nextjournal.clerk/width :full}
(clerk/html
 [:div {:style {:font-family "Inter, sans-serif" :padding "0 4px"}}

  (sec/content-box
   [:h3 {:style {:color "#93c5fd" :font-size "13px" :margin "0 0 12px 0"}}
    "Run Manifest Provenance"]
   (let [tr (:test-run sec-data)]
     (if tr
       [:div {:style {:display "grid" :grid-template-columns "1fr 1fr 1fr" :gap "8px"}}
        (for [[lbl val]
              [["Run ID"          (get tr :run_id "—")]
               ["Created at"      (get tr :created_at "—")]
               ["Triggered by"    (get tr :triggered_by "—")]
               ["Duration ms"     (str (get tr :duration_ms "—"))]
               ["OS"              (get-in tr [:environment :os] "—")]
               ["Java"            (subs (get-in tr [:environment :java] "—") 0
                                        (min 30 (count (get-in tr [:environment :java] ""))))]
               ["Python"          (get-in tr [:environment :python] "—")]
               ["Framework"       (get-in tr [:framework :name] "—")]
               ["Scenarios run"   (str (get-in tr [:framework :scenarios_count] "—"))]]]
          [:div {:key lbl :style {:background "#0d1117" :border "1px solid #1e293b"
                                   :padding "8px 10px"}}
           (sec/label-val lbl val)])]
       [:div {:style {:color "#4b5563"}} "test-run.json not loaded"])))

  (sec/content-box
   [:h3 {:style {:color "#93c5fd" :font-size "13px" :margin "0 0 12px 0"}}
    "Evidence Signature"]
   (let [sig (:signature sec-data)]
     (if sig
       [:div
        (sec/two-col
         [:div
          (sec/label-val "Status" "SIGNED")
          (sec/label-val "Key" (str (get sig :private-key-path "—")))
          (sec/label-val "Algorithm" "Ed25519 (OpenSSH)")]
         [:div
          (sec/label-val "Manifest Hash"
            (let [h (str (get sig :hash ""))]
              (if (> (count h) 24) (str (subs h 0 24) "...") h)))
          (sec/label-val "Signature"
            (let [s (str (get sig :signature ""))]
              (if (> (count s) 24) (str (subs s 0 24) "...") s)))])]
       [:div {:style {:color "#fbbf24" :font-size "11px"}}
        "Signature not found -- run 'bb manifest:sign' to sign the current manifest."])))

  (sec/content-box
   [:h3 {:style {:color "#93c5fd" :font-size "13px" :margin "0 0 12px 0"}}
    "Reproducibility Instructions"]
   (for [[step cmd desc]
         [["1" "bb run:scenario" "Run all scenarios and emit test-artifacts/"]
          ["2" "bb manifest:sign" "Sign the run manifest with Ed25519 test key"]
          ["3" "clojure -M:run -- --invariants" "Run deterministic invariant suite S01-S41"]
          ["4" "bb notebook" "Serve notebooks at localhost:7777"]]]
     [:div {:key step :style {:margin-bottom "8px" :display "flex" :gap "12px" :align-items "baseline"}}
      [:span {:style {:color "#4b5563" :font-family "JetBrains Mono, monospace" :font-size "10px"
                      :min-width "16px"}}
       step]
      [:code {:style {:color "#7dd3fc" :font-family "JetBrains Mono, monospace"
                      :font-size "11px" :background "#0c1a2e" :padding "2px 8px"
                      :flex-shrink 0}}
       cmd]
      [:span {:style {:color "#64748b" :font-size "12px"}} desc]]))])

;; ---------------------------------------------------------------------------
;; SEC-10: Known Limitations & Open Assumptions
;; ---------------------------------------------------------------------------

^{:nextjournal.clerk/width :full}
(clerk/html (sec/section-header 10 "Known Limitations & Open Assumptions" :not-evaluated
  "Explicit catalogue of unvalidated assumptions, coverage gaps, pending scenarios,
   and external dependencies. Honest residual risk. Not a minimisation exercise."))

^{:nextjournal.clerk/width :full}
(clerk/html
 [:div {:style {:font-family "Inter, sans-serif" :padding "0 4px"}}

  (sec/content-box
   [:h3 {:style {:color "#ef4444" :font-size "13px" :margin "0 0 12px 0"}}
    "Open Issues (" (str (count open-issues)) " total)"]
   (if (seq open-issues)
     (for [issue open-issues]
       [:div {:key (str (:id issue)) :style {:border-left "3px solid #dc2626"
                                              :background "#1f0707" :padding "10px 14px"
                                              :margin-bottom "8px"}}
        [:div {:style {:display "flex" :justify-content "space-between" :margin-bottom "4px"}}
         [:span {:style {:color "#f87171" :font-family "JetBrains Mono, monospace"
                         :font-size "10px" :font-weight "700"}}
          (:id issue)]
         [:span {:style {:background "#1f0707" :border "1px solid #dc2626"
                         :color "#f87171" :font-family "JetBrains Mono, monospace"
                         :font-size "9px" :padding "2px 7px"}}
          (str/upper-case (str (:status-kind issue "open")))]]
        [:div {:style {:color "#f1f5f9" :font-size "12px" :font-weight "700"}} (:title issue)]
        (when (:summary issue)
          [:div {:style {:color "#94a3b8" :font-size "11px" :margin-top "4px"}} (:summary issue)])])
     [:div {:style {:color "#4b5563" :font-size "11px"}} "No open issues loaded."]))

  (sec/content-box
   [:h3 {:style {:color "#fbbf24" :font-size "13px" :margin "0 0 12px 0"}}
    "Unevaluated Claims"]
   (for [claim (filter #(= :not-evaluated (:status %)) (:claims sec-data))]
     (sec/claim-row claim)))

  (sec/content-box
   [:h3 {:style {:color "#9ca3af" :font-size "13px" :margin "0 0 12px 0"}}
    "Unexercised Transitions"]
   (let [unhit (get (:coverage sec-data) :unhit-transitions [])]
     [:div
      (for [t unhit]
        [:div {:key (str t) :style {:color "#fbbf24" :font-family "JetBrains Mono, monospace"
                                    :font-size "11px" :margin-bottom "4px"}}
         (str (name t))])
      [:p {:style {:color "#64748b" :font-size "11px" :margin "8px 0 0 0"}}
       "automate_timed_actions is not always a first-class event in traces. Simulated time
        advances via each event :time; deadline griefing is partially covered by
        S04, S17, S66, S74, S75 but not always through the batch-automation path."]]))

  (sec/content-box
   [:h3 {:style {:color "#9ca3af" :font-size "13px" :margin "0 0 12px 0"}}
    "Simulation Boundaries"]
   [:div {:style {:display "grid" :grid-template-columns "1fr 1fr" :gap "12px"}}
    [:div
     [:div {:style {:color "#4b5563" :font-size "11px" :font-weight "700"
                    :margin-bottom "8px" :text-transform "uppercase"
                    :letter-spacing "0.08em"}}
      "Out of scope (simulation)"]
     (for [item ["On-chain EVM execution and gas accounting"
                 "Real network partition / MEV reordering"
                 "Oracle price manipulation"
                 "Solidity contract bugs not mirrored in Clojure model"
                 "Cross-chain bridge risks"
                 "Key management / signer compromise"
                 "Frontend / SDK injection vectors"]]
       [:div {:key item :style {:color "#6b7280" :font-size "11px" :margin-bottom "4px"}}
        (str "- " item)])]
    [:div
     [:div {:style {:color "#4b5563" :font-size "11px" :font-weight "700"
                    :margin-bottom "8px" :text-transform "uppercase"
                    :letter-spacing "0.08em"}}
      "Pending scenario coverage"]
     (for [item ["Mid-dispute governance upgrade replay"
                 "Repeated emergency pause abuse"
                 "Yield module provider failure cascade"
                 "Multi-chain escrow coordination"
                 "Adversarial deadline / epoch-racing attacks"
                 "Formal verification / model checking"
                 "Third-party sponsored appeal griefing"]]
       [:div {:key item :style {:color "#6b7280" :font-size "11px" :margin-bottom "4px"}}
        (str "- " item)])]])

  [:div {:style {:background "#080d1a" :border "1px solid #1e293b" :padding "14px 18px"
                 :margin-top "12px"}}
   [:p {:style {:color "#94a3b8" :font-size "12px" :margin "0 0 8px 0"}}
    "This notebook represents the current state of simulation-based validation for
     Sew Protocol v1. It is a point-in-time evidence artifact, not a final security
     clearance. The deterministic scenario suite (S01-S83) and Monte Carlo phases
     (O-AI) provide significant coverage of the economic and protocol-logic attack surface,
     but cannot substitute for formal verification or a third-party security audit."]
   [:p {:style {:color "#4b5563" :font-size "11px" :margin "0"
                :font-family "JetBrains Mono, monospace"}}
    "Deployment status: active testnet / pre-mainnet."]]])
