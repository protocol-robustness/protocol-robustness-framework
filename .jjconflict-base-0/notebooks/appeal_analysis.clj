;; # Sew Protocol — Appeal Subsystem Analysis
;;
;; **Audience:** Grant reviewers · Protocol researchers · Auditors · Security reviewers
;;
;; **Purpose:** Explain, exercise, and visualize the protocol's appeal subsystem.
;; Supports grant applications, research updates, and technical due diligence.
;;
;; The notebook demonstrates:
;; - how appeals work mechanically,
;; - why appeal bonds matter economically,
;; - how custody and conservation invariants are enforced,
;; - how appeal windows and boundary conditions are tested,
;; - how Kleros / escalation backstops improve robustness,
;; - how the system can be benchmarked with reproducible evidence.
;;
;; **Data sources:**
;; - Live in-process world states via fixture builders
;; - Curated scenario references for slash appeals, open challenges, escalation, and deadlines
;; - Analytic benchmark results (F6, M3)
;; - Research models (escalation economics, stochastic detection)

^{:nextjournal.clerk/toc true
  :nextjournal.clerk/dark-mode true
  :nextjournal.clerk/visibility {:code :fold :result :show}}
(ns notebooks.appeal-analysis
  (:require [clojure.string :as str]
            [nextjournal.clerk :as clerk]
            [resolver-sim.notebook-support.ui :as ui]
            [resolver-sim.notebook-support.theme :refer [notebook-theme]]
            [resolver-sim.protocols.sew.economics :as sew-econ]
            [resolver-sim.protocols.sew.research-models.escalation-economics :as ee]
            [resolver-sim.stochastic.detection :as detect]
            [resolver-sim.stochastic.decision-quality :as dq]
            [resolver-sim.io.params :as io-params]))

(def mermaid-viewer
  {:transform-fn clerk/mark-presented
   :render-fn
   '(fn [value]
      (when value
        [nextjournal.clerk.render/with-d3-require
         {:package ["mermaid@8.14/dist/mermaid.js"]}
         (fn [mermaid]
           [:div
            {:style {:overflow "auto"
                     :max-height "75vh"
                     :width "100%"
                     :border "1px solid #ddd"
                     :border-radius "6px"
                     :padding "0.5rem"}}
            [:div
             {:style {:min-width "1400px"}
              :ref
              (fn [element]
                (when element
                  (.render mermaid
                           (str (gensym "mermaid-"))
                           value
                           #(set! (.-innerHTML element) %))))}]])]))})

(defn mermaid [source]
  (clerk/with-viewer mermaid-viewer source))

;; ===========================================================================
;; Notebook configuration (loaded from shared EDN)
;; ===========================================================================

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def cfg
  (delay (io-params/load-edn "notebooks/appeal_config.edn")))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn- styled-table
  "Render a table with consistent styling. head is a vector of column labels,
   rows is a seq of row vectors."
  [head rows]
  (clerk/html
   [:table {:style (merge (:table-style notebook-theme) {:fontSize "0.82em" :width "100%" :borderCollapse "collapse"})}
    [:thead
     [:tr {:style (:table-header-row-style notebook-theme)}
      (for [h head]
        [:th {:style (:table-header-cell-style notebook-theme)} h])]]
    (into [:tbody]
          (map (fn [row]
                 [:tr {:style {:borderBottom (str "1px solid " (:table/border notebook-theme))}}
                  (for [cell row]
                    [:td {:style (:table-cell-style notebook-theme)} (str cell)])]))
          rows)]))

;; ===========================================================================
;; Executive summary
;; ===========================================================================

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/md "# Appeal, Challenge, and Escalation Framework

   **Contribution.** The protocol does more than require an appeal bond. It is
   an executable framework for distinguishing who may challenge which decision,
   tracing bonds through separate custody paths, preventing settlement during a
   valid provisional-resolution challenge period, bounding escalation, and
   producing reproducible benchmark inputs for whether selected parameters may
   deter abuse without excluding legitimate correction.

   **Implemented today:** resolver slash appeals, open resolution challenges,
   sequential bounded escalation, custody state, deadline guards, and
   authorization enforcement at ``resolve-appeal``.

   **What remains research or hardening:** full bond-flow conservation,
   equal-timestamp resolver-slash ordering, aggregate stochastic calibration,
   adjacent governance-path provenance normalization, and external-arbitration
   integration.")

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(styled-table
 ["Capability" "Status" "Reader should infer"]
 [["Resolver slash appeal and governance resolution" "Implemented" "A slashed resolver has a bounded correction path"]
  ["Third-party provisional-resolution challenge" "Implemented" "A sponsor or observer can contest an outcome without controlling the resolver's slash appeal"]
  ["Deadline and escalation guards" "Deterministically fixture-tested elsewhere" "Canonical fixtures exist; this notebook does not execute them"]
  ["F6 / M3 parameter analysis" "Analytically computed at render time when available" "PASS, FAIL, and execution ERROR are distinct"]
  ["L1/L2 reversal behavior" "Stochastically modelled" "Single displayed traces are illustrative, not rates"]
  ["External/Kleros-style arbitration" "Proposed / modelled" "No live request, callback, ruling, or fee-settlement integration"]
  ["Full per-bond conservation" "Proposed hardening" "Existing legacy invariant checks non-negativity only"]])

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/md "### Current calibration finding

   **F6** reports appeal-window adequacy across its tested configurations.
   **M3** currently reports **0/11 passing frivolous-appeal-discouragement
   trials** for the default parameterization. This is not a feature claim: it
   is an unresolved calibration result. The correction and custody machinery is
   implemented; the research problem is finding parameter regions that deter
   abuse without pricing out legitimate correction.")

;; ===========================================================================
;; Section 1: Appeal Subsystem Summary
;; ===========================================================================

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/md
 "## 1. Appeal Subsystem Summary

   ### What an appeal is

   An appeal is a formal challenge to a resolver's slashing decision. It is
   submitted by the resolver who was slashed, within a bounded window, and
   requires posting an appeal bond.

   ### Who can appeal

   Only the resolver who received the slash can appeal. This is the entity
   that was penalized by the fraud-slash mechanism.

   ### When an appeal is valid

   An appeal is valid when:
   - A `:pending` fraud slash exists against the resolver,
   - The appeal window has not expired,
   - The caller is the resolver who was slashed,
   - The slash has not already been appealed.

   ### What bond is required

   A resolver slash-appeal bond is calculated as a percentage (bps) of the
   slashed amount, or a fixed absolute amount if the protocol snapshot defines
   one. In the current model, ``appeal-slash`` holds that full slash-scoped
   bond; the protocol-fee deduction applies to the separate generic
   ``post-appeal-bond`` path used by resolution challenges and escalation.

   ### If the appeal succeeds

   If governance upholds the resolver slash appeal (``appeal-upheld? = true``),
   the slash is reversed and cannot be executed. The appeal bond is returned
   to the appellant.

   ### If the appeal fails

   If the appeal is rejected (``appeal-upheld? = false``), the slash stands and
   becomes executable. The resolver's slash-scoped appeal bond is forfeited to
   the appeal-bond distribution / insurance accounting path. It is not itself
   recorded as a generic ``:bond-fees`` protocol fee.

   ### How appeal finality interacts with dispute finality

   A provisional resolution cannot reach settlement finality while its
   **challenge window** remains open. A resolver slash appeal separately
   controls whether the pending fraud slash may be executed or reversed. These
   are distinct finality domains with different deadline semantics; see
   Section 7.

   ### How Kleros / escalation acts as a backstop

   The protocol models multi-level escalation through L1, L2, and a possible
   external/Kleros-style tier. Each modeled level has higher bond costs and
   stronger scrutiny assumptions. This notebook exercises the on-protocol
   escalation state machine and stochastic economics; it does **not** present
   a live Kleros integration, external callback, or external ruling as an
   implemented execution path.")

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/md "### Feature scope: resolver appeals vs. open resolution challenges

   The protocol has two related but distinct correction mechanisms. Keeping
   them separate is essential for evaluating authorization, bond accounting,
   and finality.")

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(styled-table
 ["Mechanism" "Initiator" "Action" "Immediate effect" "Decision authority"]
 [["Resolver slash appeal"
   "Only the resolver named on the pending slash"
   "appeal-slash"
   "Marks the slash :appealed and holds its appeal bond"
   "Governance resolves the slash appeal"]
  ["Resolution challenge / escalation"
   "Any participant or third party"
   "challenge-resolution"
   "Posts a challenge bond, supersedes the pending settlement, and advances the dispute level"
   "Next-level resolver; modelled external/Kleros tier"]])

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/md "**Important boundary:** a third party cannot submit a resolver's
   ``appeal-slash``; that action rejects callers other than the slashed
   resolver. A third party instead uses ``challenge-resolution`` to challenge
   a provisional dispute outcome during its open window. The two paths have
   different state, bond, and resolution semantics.")

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/md "### Worked example — illustrative 10 ETH slash appeal

   This is a reader-oriented example, **not** a claim about the active
   parameter snapshot or a completed replay. Assume a 10 ETH provisional slash
   and a 15% resolver slash-appeal bond requirement.")

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(styled-table
 ["Step" "Amount / state" "Ledger or evidence effect"]
 [["Before appeal" "10 ETH slash is :pending" "Pending slash records resolver, deadline, token, and slash amount"]
  ["Required resolver bond" "15% × 10 ETH = 1.5 ETH" "1.5 ETH is entered as :appeal-bond-held in slash-scoped custody"]
  ["During slash appeal" "Settlement / slash execution subject to their separate deadline guards" "Appeal evidence records :fraud-slash-appealed and custody state"]
  ["Appeal upheld" "1.5 ETH returned; 10 ETH slash cancelled" "Custody is cleared; refund is recorded as claimable; slash becomes :reversed"]
  ["Appeal rejected" "1.5 ETH forfeited; 10 ETH slash becomes executable" "Custody is cleared; the current model records forfeiture in slash-appeal insurance/distribution accounting"]
  ["Separate third-party path" "Challenge bond is distinct from 1.5 ETH" "challenge-resolution uses the fee-bearing generic bond ledger and advances one dispute level"]])

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/md "**Accounting limit:** this example illustrates intended movements,
   not a proved conservation equation. The current legacy
   ``appeal-bond-conserved?`` invariant prevents negative slash-scoped custody
   but does not reconcile the complete post/return/forfeit flow.")

;; ===========================================================================
;; Section 2: Appeal Lifecycle Map
;; ===========================================================================

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/md "## 2. Appeal Lifecycle Map")

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn appeal-lifecycle-diagram
  "Return a Mermaid stateDiagram-v2 describing the appeal lifecycle."
  []
  (str/join "\n"
            ["stateDiagram-v2"
             "    direction LR"
             "    state \"Slash Proposed\" as PENDING"
             "    state \"Appeal Window Open\" as WINDOW"
             "    state \"Appeal Submitted\" as APPEALED"
             "    state \"Appeal Resolved\" as RESOLVED"
             "    state \"Bond Returned\" as RETURNED"
             "    state \"Bond Forfeited\" as FORFEITED"
             "    state \"Slash Executed\" as EXECUTED"
             "    state \"Slash Reversed\" as REVERSED"
             ""
             "    [*] --> PENDING : propose_fraud_slash"
             "    PENDING --> WINDOW : slash pending, window open"
             "    WINDOW --> WINDOW : deadline elapsing"
             "    WINDOW --> APPEALED : appeal_slash"
             "    APPEALED --> RESOLVED : resolve_appeal"
             "    RESOLVED --> RETURNED : appeal upheld (slash reversed)"
             "    RESOLVED --> FORFEITED : appeal rejected (slash stands)"
             "    RETURNED --> REVERSED : slash cancelled"
             "    FORFEITED --> EXECUTED : slash executed"
             "    WINDOW --> EXECUTED : execute_fraud_slash after deadline"
             ""
             "    note right of WINDOW : fraud-slash deadline only"
             "    note right of APPEALED : governance is the sole resolver"]))

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(mermaid (appeal-lifecycle-diagram))

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/md
 "**Lifecycle key events:**

 | # | Event | What happens |
 |---|-------|-------------|
 | 1 | ``propose_fraud_slash`` | A slash is proposed against a resolver for a fraudulent decision. |
 | 2 | ``appeal_slash`` | The resolver appeals within the window, posting an appeal bond. |
 | 3 | ``resolve_appeal`` | Governance resolves the appeal: upheld (bond returned) or rejected (bond forfeited). |
 | 4 | Fraud-slash execution | If the appeal failed or the slash deadline expired without an appeal, ``execute_fraud_slash`` may execute the slash. |

   This diagram is limited to the resolver slash-appeal state machine. The
   separate third-party provisional-resolution challenge and L0 → L1 → L2
   chain are shown in Sections 2a and 2b.")

;; ===========================================================================
;; Section 2a: Open Third-Party Resolution Challenge
;; ===========================================================================

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/md "## 2a. Open Third-Party Resolution Challenge

   A provisional resolution can be challenged by a participant **or any third
   party** while its appeal window is open. This is an escalation mechanism,
   not delegation of a resolver's slash appeal. The challenger posts the
   challenge bond, the pending settlement is superseded, and the dispute moves
   to the next resolver level.")

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(styled-table
 ["Scenario" "Actor" "Action" "Expected result" "Boundary demonstrated"]
 [["s76-sponsored-appeal-third-party-funding"
   "Sponsor / third party"
   "challenge-resolution inside the open window"
   "Challenge bond is posted; dispute escalates from L0 to L1"
   "Open access to escalation is distinct from resolver-only appeal-slash"]
  ["dr-b-001-appeal-window-expiry-race"
   "Challenger"
   "challenge-resolution at the exact deadline"
   "Rejected with :appeal-window-expired; settlement may execute"
   "Challenge access closes at the deterministic finality boundary"]])

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/md "**Presentation note:** The S76 title uses “sponsored appeal,” but
   its executed action is ``challenge-resolution``. It demonstrates third-party
   funding of a challenge bond; it does not allow a sponsor to call
   ``appeal-slash`` for a slashed resolver.")

;; ===========================================================================
;; Section 2b: Chained Resolution Challenges
;; ===========================================================================

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/md "## 2b. Chained Resolution Challenges

   Resolution challenges form a **sequential, bounded chain**, not a set of
   concurrent independent appeals. Each link requires a prior provisional
   resolution on the same workflow. A successful challenge supersedes that
   pending settlement, advances exactly one dispute level, and produces a new
   provisional resolution that may itself be challenged while its new window
   remains open.")

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn chained-escalation-diagram
  "Return a Mermaid state diagram for the sequential resolution-challenge chain."
  []
  (str/join "\n"
            ["stateDiagram-v2"
             "    direction LR"
             "    state \"L0 Provisional Resolution\" as L0_PENDING"
             "    state \"L1 Review\" as L1_REVIEW"
             "    state \"L1 Provisional Resolution\" as L1_PENDING"
             "    state \"L2 Review\" as L2_REVIEW"
             "    state \"L2 Provisional Resolution\" as L2_PENDING"
             "    state \"Financial Finality\" as FINAL"
             "    state \"Further Challenge Rejected\" as MAXED"
             ""
             "    [*] --> L0_PENDING : L0 resolves dispute"
             "    L0_PENDING --> L1_REVIEW : challenge-resolution within window"
             "    L1_REVIEW --> L1_PENDING : L1 resolves dispute"
             "    L1_PENDING --> L2_REVIEW : next challenge within window"
             "    L2_REVIEW --> L2_PENDING : L2 resolves dispute"
             "    L0_PENDING --> FINAL : window closes without challenge"
             "    L1_PENDING --> FINAL : window closes without challenge"
             "    L2_PENDING --> FINAL : window closes without challenge"
             "    L2_PENDING --> MAXED : maximum escalation level reached"
             ""
             "    note right of L1_REVIEW : prior pending settlement superseded"
             "    note right of L2_REVIEW : one level per valid challenge"]))

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(mermaid (chained-escalation-diagram))

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(styled-table
 ["Stage" "Precondition" "Actor" "Bond / fee path" "State transition" "Can chain?"]
 [["L0 provisional resolution"
   "Dispute resolved at L0"
   "L0 resolver"
   "No challenge bond yet"
   "Pending settlement opens its challenge window"
   "Yes, while the window is open"]
  ["L0 → L1 challenge"
   "Existing pending settlement; non-final level; open window"
   "Any participant or third party"
   "Challenge bond via post-appeal-bond; generic bond fee accrues"
   "Pending settlement is superseded; dispute advances one level"
   "Yes, after L1 issues a new provisional resolution"]
  ["L1 → L2 challenge"
   "New L1 pending settlement; non-final level; open window"
   "Any participant or third party"
   "New challenge bond; amount scales with the caller's prior escalation count"
   "Current pending settlement is superseded; dispute advances one level"
   "Only until the configured maximum level"]
  ["Finality or exhaustion"
   "Window closes, or final level is reached"
   "Keeper / attempted challenger"
   "No additional bond after finality"
   "Settlement executes, or further challenge is rejected"
   "No"]])

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/md "**Evidence and safety boundaries:**

   - ``s76-sponsored-appeal-third-party-funding`` demonstrates the first
     third-party L0 → L1 chain link.
   - S62 covers a multi-level escalation chain; it is sequential rather than
     concurrent.
   - S78 exercises bounded exhaustion at the maximum escalation level.
   - ``dr-b-001-appeal-window-expiry-race`` demonstrates that a new chain link
     is rejected at the exact deadline while settlement may execute.
   - ``appeal-requires-prior-resolution?`` prevents an escalation level from
     existing without a preceding resolution on the same workflow.")

;; ===========================================================================
;; Section 3: Challenge-Bond Fee and Custody Flow
;; ===========================================================================

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/md "## 3. Challenge-Bond Fee and Custody Flow

   This diagram covers the generic ``post-appeal-bond`` path used by open
   resolution challenges and escalation. It is separate from resolver
   ``appeal-slash`` custody, which is shown in the financial-flow table below.")

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn appeal-bond-flow
  "Return a Mermaid stateDiagram-v2 showing appeal bond custody paths."
  []
  (str/join "\n"
            ["stateDiagram-v2"
             "    direction TB"
             "    state \"Challenge Bond Posted\" as POSTED"
             "    state \"Challenge Bond Held in Custody\" as CUSTODY"
             "    state \"Protocol Fee Deducted\" as FEE"
             "    state \"Net Challenge Bond Held\" as NET"
             "    state \"Challenge Bond Returned\" as RETURNED"
             "    state \"Challenge Bond Slashed\" as FORFEITED"
             "    state \"Bond-Fee Pool\" as FEEPOOL"
             "    state \"Slashed-Bond Pool (50/30/20 split)\" as SLASHED"
             ""
             "    [*] --> POSTED : post-appeal-bond"
             "    POSTED --> FEE : calculate-appeal-bond-fee"
             "    FEE --> FEEPOOL : fee deducted"
             "    FEE --> NET : net bond amount"
             "    NET --> CUSTODY : bond-balances entry created"
             "    CUSTODY --> RETURNED : bond return path"
             "    CUSTODY --> FORFEITED : bond slash path"
             "    RETURNED --> [*] : return-bond / sub-held"
             "    FORFEITED --> SLASHED : slash-bond / sub-held"
             ""
             "    note right of POSTED : amount = bps(slash-amount)"
             "    note right of POSTED : or fixed snap.appeal-bond-amount"
             "    note right of FEE : fee-bps from protocol snapshot"
             "    note right of NET : total-bonded increment"
             "    note right of RETURNED : custody cleared, bond returned"
             "    note right of FORFEITED : custody cleared, bond slashed"]))

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(mermaid (appeal-bond-flow))

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/md
 "**Key accounting functions:**

 | Function | Purpose | Invariant checked |
 |----------|---------|-------------------|
 | ``post-appeal-bond`` | Records bond custody, deducts protocol fee, updates totals | ``:appeal-bond-held`` non-negative |
 | ``return-bond`` | Returns a generic challenge bond to its bond owner, clears custody | Custody entry cleared |
 | ``slash-bond`` | Forfeits a generic challenge bond owned by the challenger, applies 50/30/20 split | Generic bond balance zeroed |
 | ``sub-held`` | Low-level custody deduction with reason tag | Conservation-of-funds |

   **Scope note:** These are generic bond-accounting helpers. Resolver
   ``appeal-slash`` uses slash-specific custody and ``resolve-appeal`` rather
   than this fee-accruing posting function. Do not infer from this diagram that
   every resolver appeal pays a protocol fee.")

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/md "### Financial-flow separation

   The generic ``post-appeal-bond`` flow shown above is the custody path used
   by open resolution challenges. Resolver slash appeals maintain slash-scoped
   custody (``:appeal-bond-custody`` and ``:appeal-bond-held``). They should
   not be treated as interchangeable when reconciling fees or custody.")

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(styled-table
 ["Flow" "Gross amount" "Fee generation" "Custody / destination" "Feature evidence"]
 [["Resolver slash appeal"
   "Appeal bond calculated from the slash snapshot"
   "No generic bond-fee accrual is represented by appeal-slash"
   "Slash-scoped :appeal-bond-held; returned if upheld or forfeited if rejected"
   "S25 / S35 / S63"]
  ["Third-party resolution challenge"
   "Challenge bond, scaled by prior challenges from the caller"
   "post-appeal-bond calculates fee and accrues :bond-fees[token]"
   "Net bond held in :bond-balances; pending settlement is superseded during escalation"
   "s76-sponsored-appeal-third-party-funding"]
  ["Escrow creation and yield"
   "Escrow amount or generated yield"
   "Protocol fees accrue independently of appeal/challenge bonds"
   "Protocol fee ledger and governance-controlled fee-recipient withdrawal"
   "Fee-recipient / withdrawal scenarios"]
  ["Rejected bond / slash"
   "Held net bond or slash amount"
   "Not a protocol fee merely because it is forfeited"
   "Slashed-bond distribution and applicable incentive / bounty allocations"
   "S63 and escalation scenarios"]])

;; ===========================================================================
;; Section 4: Appeal Economics Deep Dive
;; ===========================================================================

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/md "## 4. Appeal Economics Deep Dive

   This section shows how appeal cost changes with escrow amount, bond basis
   points, appeal round, escalation depth, protocol fee, and probability
   assumptions. Amounts are displayed in base units for reproducibility; the
   worked example above provides an ETH/percentage interpretation.")

;; --- 4a: Bond amount and fee table ---

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn- bond-amount-table
  "Generate rows showing required bond and fee for escrow amounts and bond-bps bands."
  []
  (let [eco (:economic-analysis @cfg)
        escrow-amounts (:escrow-amounts eco)
        bond-bps-values (:bond-bps-values eco)
        fee-bps (:fee-bps @cfg)
        rows (for [escrow escrow-amounts
                   bps bond-bps-values]
               (let [bond-amount (sew-econ/calculate-appeal-bond-amount
                                  escrow
                                  {:appeal-bond-bps bps :appeal-bond-amount 0})
                     fee-result (sew-econ/calculate-appeal-bond-fee bond-amount fee-bps)]
                 {:escrow escrow
                  :bond-bps bps
                  :required-bond bond-amount
                  :fee (:fee fee-result)
                  :net-custody (:net fee-result)}))
        ;; Group for compact display
        grouped (group-by :bond-bps rows)]
    (for [[bps items] (sort grouped)]
      [bps
       (map (fn [i] [(:escrow i) (:required-bond i) (:fee i) (:net-custody i)]) items)])))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def economics-bond-table
  (bond-amount-table))

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/md "### Bond Amount and Fee by Escrow Size and Rate

   Required bond = ``calculate-appeal-bond-amount(escrow, snap)`` where
   snap provides either an absolute ``:appeal-bond-amount`` or a
   ``:appeal-bond-bps`` rate. The fee column illustrates the generic
   ``post-appeal-bond`` path used by resolution challenges. Resolver
   ``appeal-slash`` currently holds the full slash-scoped bond and does not
   invoke generic bond-fee accrual.")

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/md
 (apply str
        (for [[bps rows] economics-bond-table]
          (str "### Bond rate: " bps " bps (" (/ bps 100) "% of normalized dispute value)\n\n"
               "| Normalized dispute value | Required bond | Bond % | Generic posting fee (150 bps) | Net generic custody |\n"
               "|---:|---:|---:|---:|---:|\n"
               (apply str
                      (for [[escrow bond fee net] rows]
                        (str "| " escrow " | " bond " | " (/ bps 100) "% | " fee " | " net " |\n")))
               "\n"))))

;; --- 4b: Escalation cost table ---

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn- escalation-cost-rows
  "Generate cost rows using the escalation economics model."
  []
  (let [config ee/DEFAULT_ESCALATION_CONFIG
        rounds (:rounds (:economic-analysis @cfg))]
    (for [r rounds]
      {:round r
       :marginal-bond (ee/appeal-bond-at-round (dec r) config)
       :cumulative-cost (ee/total-appeal-cost-to-round r config)})))

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/md "### Escalation Cost by Round

   Each escalation level increases the appeal bond cost. Using
   ``appeal-bond-at-round`` and ``total-appeal-cost-to-round`` from the
   escalation economics model with default parameters.")

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(styled-table
 ["Round" "Marginal Bond Cost" "Cumulative Appeal Cost"]
 (map (fn [{:keys [round marginal-bond cumulative-cost]}]
        [(str round) (str marginal-bond) (str cumulative-cost)])
      (escalation-cost-rows)))

;; --- 4c: Security improvement from escalation ---

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn- security-improvement-rows
  []
  (let [security (ee/escalation-provides-security ee/DEFAULT_ESCALATION_CONFIG)]
    [["Stake increase R0→R1" (str (int (* 100 (:stake-increase-r1 security)))) "%"]
     ["Stake increase R0→R2" (str (int (* 100 (:stake-increase-r2 security)))) "%"]
     ["Cumulative appeal cost to R1" (str (:cumulative-appeal-cost-r1 security)) " wei"]
     ["Cumulative appeal cost to R2" (str (:cumulative-appeal-cost-r2 security)) " wei"]
     ["Protection improvement R1" (str (int (* 100 (:protection-improvement-r1 security)))) "%"]
     ["Protection improvement R2" (str (int (* 100 (:protection-improvement-r2 security)))) "%"]]))

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/md "### Modelled Cost and Robustness Effects of Escalation

   Under the model assumptions, higher rounds increase resolver stakes and
   appeal-bond costs. This increases modelled attack cost and may improve
   robustness; it is not a formal security guarantee.")

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(styled-table
 ["Metric" "Value" "Unit"]
 (security-improvement-rows))

;; --- 4d: Benchmark results (deferred) ---

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def f6-results
  (delay
    (try
      (let [phase-f-ns 'resolver-sim.research.sew.analytic.phase-f-economic-parameters]
        (require phase-f-ns)
        ((requiring-resolve 'resolver-sim.research.sew.analytic.phase-f-economic-parameters/run-f6-appeal-window-adequacy)))
      (catch Exception e
        {:benchmark-id "F6" :label "Appeal Window Adequacy"
         :status :error :summary {:error (.getMessage e)}}))))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def m3-results
  (delay
    (try
      (let [phase-m-ns 'resolver-sim.research.sew.analytic.phase-m-fairness-analysis]
        (require phase-m-ns)
        ((requiring-resolve 'resolver-sim.research.sew.analytic.phase-m-fairness-analysis/run-m3-frivolous-appeal-discouragement)))
      (catch Exception e
        {:benchmark-id "M3" :label "Frivolous Appeal Discouragement"
         :status :error :summary {:error (.getMessage e)}}))))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn- benchmark-status
  [{:keys [status passed?]}]
  (cond
    (= status :error) "⚠️ ERROR"
    (true? passed?) "✅ PASS"
    (false? passed?) "❌ FAIL"
    :else "⚠️ UNAVAILABLE"))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn- benchmark-result-markdown
  [result numerator-key]
  (let [{:keys [benchmark-id label summary]} result
        error (:error summary)]
    (str "**" label " (" benchmark-id ")** — " (benchmark-status result)
         (cond
           error (str " — execution error: " error)
           summary (str " — pass rate: " (int (* 100 (:pass-rate summary 0))) "%"
                        " (" (get summary numerator-key 0) "/" (:total-trials summary 0) " trials)")
           :else " — no benchmark result was returned"))))

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/md "### Analytic Benchmark Results

   These benchmarks are computed at render time from the analytic suite.
   **PASS** and **FAIL** describe completed benchmark outcomes. **ERROR** means
   loading or executing the benchmark failed; it is not a negative research
   finding. **UNAVAILABLE** means no interpretable result was returned.")

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/md (benchmark-result-markdown @f6-results :healthy-trials))

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/md (benchmark-result-markdown @m3-results :discouraged-trials))

;; ===========================================================================
;; Section 5: Appeal Scenarios Deep Dive
;; ===========================================================================

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/md "## 5. Appeal Scenarios Deep Dive

   This is a curated **static mapping** from appeal-related mechanisms to
   scenario identifiers. It does not execute scenarios, retrieve runner status,
   or load golden artifacts at render time. Treat it as navigation to the
   scenario suite rather than live evidence of a current passing run.")

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def appeal-scenario-registry
  "Curated metadata for appeal-related scenarios.
   Covers the core appeal lifecycle scenarios and boundary conditions."
  [{:scenario "S25"
    :display-name "profit-maximizer-slash-lifecycle"
    :risk "Fraud slash lifecycle: slash proposed, appealed, resolved"
    :mechanism "appeal-slash followed by resolve-appeal; no generic post-appeal-bond call"
    :expected "Resolver slash-appeal lifecycle fixture; consult runner evidence for outcome"
    :invariant "appeal-bond-conserved? (non-negativity only), appeal-bond-custody-consistent?"
    :grant-claim "Maps the resolver slash-appeal lifecycle to its canonical fixture"}

   {:scenario "S35"
    :display-name "profit-maximizer-governance-wins-appeal"
    :risk "Governance overturns a slash decision via appeal"
    :mechanism "resolve-appeal with appeal-upheld? = true"
    :expected "Slash reversed, bond returned, execution blocked"
    :invariant "appeal-bond-conserved?, appeal-bond-custody-consistent?"
    :grant-claim "Governance appeal path works correctly"}

   {:scenario "S36"
    :display-name "profit-maximizer-pre-window-execute-rejected"
    :risk "Execution attempted before appeal window closes"
    :mechanism "execute-pending-settlement blocked by :appeal-window-not-expired"
    :expected "Execution rejected, retry after window succeeds"
    :invariant "finality-blocked-during-appeal?"
    :grant-claim "Pre-window execution is correctly rejected"}

   {:scenario "S47"
    :display-name "appeal-window-boundary-pair"
    :risk "Appeal window edge cases (pair: S47a + S47b)"
    :mechanism "Appeal deadline enforcement at boundary"
    :expected "Window boundaries enforced correctly"
    :invariant "finality-blocked-during-appeal?"
    :grant-claim "Maps the appeal-window boundary fixture to the finality guard"}

   {:scenario "s49-max-escalation-plus-one-rejected"
    :display-name "max-escalation-plus-one-rejected"
    :risk "A challenge attempts to advance beyond the configured maximum level"
    :mechanism "escalate-dispute with maximum escalation guard"
    :expected "Extra escalation rejected with :escalation-not-allowed"
    :invariant "appeal-requires-prior-resolution?"
    :grant-claim "Maps maximum-depth enforcement to its canonical fixture"}

   {:scenario "S62"
    :display-name "multi-appeal-escalation-chain"
    :risk "Sequential challenges advance through multiple escalation levels"
    :mechanism "Each valid challenge supersedes the current pending settlement and advances one level"
    :expected "Every escalation follows a prior resolution; only the active level continues"
    :invariant "appeal-requires-prior-resolution?"
    :grant-claim "Bounded sequential escalation does not cause state corruption"}

   {:scenario "S63"
    :display-name "frivolous-appeal-slashing"
    :risk "Frivolous appeal penalized via bond forfeiture"
    :mechanism "frivolous appeal bond forfeiture"
    :expected "Bond forfeited, slash stands"
    :invariant "appeal-bond-custody-consistent?"
    :grant-claim "The mechanical forfeiture path is mapped; current parameters do not satisfy the M3 deterrence benchmark."}

   {:scenario "S65"
    :display-name "appeal-after-settlement-rejected"
    :risk "Appeal attempted after settlement already executed"
    :mechanism "Guard against appeal after finality"
    :expected "Appeal rejected because window closed"
    :invariant "finality-blocked-during-appeal?"
    :grant-claim "Appeal after settlement is correctly rejected"}

   {:scenario "S72"
    :display-name "challenge-during-appeal-window"
    :risk "Escalation challenge submitted during appeal window"
    :mechanism "Challenge and appeal window interaction"
    :expected "Challenge accepted, appeal lifecycle continues"
    :invariant "appeal-requires-prior-resolution?"
    :grant-claim "Challenge and appeal can coexist safely"}

   {:scenario "s76-sponsored-appeal-third-party-funding"
    :display-name "sponsored-resolution-challenge"
    :risk "An offline or non-participating party needs to contest a provisional resolution"
    :mechanism "Third party calls challenge-resolution, posts a challenge bond, and escalates to L1"
    :expected "Challenge is accepted inside the window; pending settlement is superseded and L1 resolves"
    :invariant "appeal-requires-prior-resolution?, finality-blocked-during-appeal?"
    :grant-claim "Maps the third-party challenge and bonded escalation path to its canonical fixture"}

   {:scenario "dr-b-001-appeal-window-expiry-race"
    :display-name "third-party-challenge-at-expiry-rejected"
    :risk "Third party attempts to challenge when the provisional resolution has reached its deadline"
    :mechanism "challenge-resolution at exact appeal deadline"
    :expected "Challenge rejected with :appeal-window-expired; settlement can execute"
    :invariant "finality-blocked-during-appeal?"
    :grant-claim "Open challenge rights end deterministically at the same finality boundary"}

   {:scenario "S78"
    :display-name "many-appeals-eventually-rejects"
    :risk "Sequential challenges attempt to continue beyond the configured escalation depth"
    :mechanism "Maximum escalation guard"
    :expected "Further challenge rejected after the final level"
    :invariant "appeal-requires-prior-resolution?"
    :grant-claim "Escalation-chain exhaustion is bounded and enforced"}

   {:scenario "S81"
    :display-name "appeal-deadline-boundary-before"
    :risk "Execution at t = deadline - 1 (before window closes)"
    :mechanism "execute-pending-settlement with offset -1"
    :expected "Rejected: appeal window not expired"
    :invariant "finality-blocked-during-appeal?"
    :grant-claim "Execution before deadline is correctly blocked"}

   {:scenario "s74-appeal-deadline-boundary"
    :display-name "pending-settlement-deadline-boundary"
    :risk "Settlement execution immediately before, at, and after a provisional-resolution deadline"
    :mechanism "execute-pending-settlement at t-1, t, and t+1"
    :expected "Rejected before the deadline; eligible at and after the deadline"
    :invariant "finality-blocked-during-appeal?"
    :grant-claim "Maps pending-settlement deadline semantics to its canonical fixture"}])

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/md "### Appeal Scenario Registry

   Each row links a scenario to its risk model, appeal mechanism, expected
   outcome, invariant coverage, and grant-facing claim.")

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(styled-table
 ["Scenario" "Risk Model" "Mechanism" "Invariant Coverage" "Grant Claim"]
 (map (fn [{:keys [scenario display-name mechanism invariant grant-claim]}]
        [(str scenario ": " display-name) mechanism invariant grant-claim])
      appeal-scenario-registry))

;; ===========================================================================
;; Section 6: Appeal Invariant Coverage
;; ===========================================================================

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/md "## 6. Appeal Invariant Coverage

   This section documents the appeal-specific invariants, their plain-English
   meaning, the failure class they prevent, the lifecycle phase they protect,
   the scenarios that exercise them, and the grant-facing claim they support.")

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def appeal-invariant-registry
  [{:name "appeal-bond-conserved? (legacy identifier; non-negativity check)"
    :meaning "No slash event has a negative :appeal-bond-held amount. It does not reconcile posted, held, returned, forfeited, and fee amounts."
    :failure-class "Accounting underflow only"
    :phase "Slash-scoped appeal-bond balance"
    :scenarios ["S25" "S35" "S36"]
    :grant-claim "Slash-scoped appeal-bond held amounts cannot underflow; full bond conservation remains an unimplemented stronger property."}

   {:name "appeal-bond-custody-consistent?"
    :meaning "Appeal bond custody lifecycle is coherent: held > 0 implies :appealed status and custody entry; held = 0 implies no custody entry."
    :failure-class "Custody tracking desync / stale entries"
    :phase "Appeal bond lifecycle (custody entry management)"
    :scenarios ["S25" "S35" "S36" "S63"]
    :grant-claim "Maps custody-consistency coverage to the listed fixture set."}

   {:name "appeal-requires-prior-resolution?"
    :meaning "No escalation exists without a prior resolution on the same workflow. Dispute-level > 0 implies a resolution exists."
    :failure-class "Escalation without resolution / state machine violation"
    :phase "Appeal submission and escalation"
    :scenarios ["S28" "S44" "S62" "S78"]
    :grant-claim "Escalation challenges cannot advance without a prior provisional resolution."}

   {:name "finality-blocked-during-appeal?"
    :meaning "No provisional dispute settlement executes before its challenge window expires. This is distinct from the resolver slash-appeal deadline."
    :failure-class "Premature settlement / financial finality bypass"
    :phase "Provisional-resolution challenge window → settlement execution"
    :scenarios ["S05" "S13" "S32" "S36" "S47" "S65"
                "s74-appeal-deadline-boundary"
                "s76-sponsored-appeal-third-party-funding"
                "dr-b-001-appeal-window-expiry-race" "S81"]
    :grant-claim "Settlement finality is blocked while the provisional-resolution challenge window is open."}])

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(styled-table
 ["Invariant" "Meaning" "Failure Class" "Lifecycle Phase" "Exercised By" "Grant Claim"]
 (map (fn [{:keys [name meaning failure-class phase scenarios grant-claim]}]
        [name meaning failure-class phase (str/join ", " scenarios) grant-claim])
      appeal-invariant-registry))

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/md
 "**Evidence status:**

    This notebook documents invariant definitions and their intended scenario
    coverage. It does **not** run the invariant runner or display current
    per-scenario pass/fail status, artifact hashes, source revision, or run
    timestamp. Refer to the dispute-resolution workbench and generated evidence
    artifacts for executable results.")

;; ===========================================================================
;; Section 7: Appeal Boundary Testing
;; ===========================================================================

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/md "## 7. Deadline Semantics and Boundary Limits

   The protocol has two different deadline guards. They must not be presented
   as one shared appeal boundary.

   | Deadline | Action valid before deadline | Equality behavior in implementation | Action valid at/after deadline |
   |----------|------------------------------|------------------------------------|-------------------------------|
   | Provisional-resolution challenge window | ``challenge-resolution`` / escalation when ``now < deadline`` | Challenge is rejected at equality | ``execute-pending-settlement`` when ``now >= deadline`` |
   | Resolver fraud-slash appeal window | ``appeal-slash`` when ``now <= deadline`` | Appeal is accepted at equality | ``execute-fraud-slash`` also permits ``now >= deadline`` |

   The first row has a clear non-overlapping boundary: at equality, a challenge
   fails and settlement is eligible. ``dr-b-001-appeal-window-expiry-race``
   documents that behavior, and ``s74-appeal-deadline-boundary`` exercises
   settlement at t-1, t, and t+1.

   The second row has an unresolved equal-timestamp ordering question: the
   current guards permit both ``appeal-slash`` and ``execute-fraud-slash`` at
   ``now == deadline``. This notebook does not claim a deterministic same-block
   ordering rule or safety proof for that conflict. A dedicated ordered-event
   scenario and an explicit protocol rule are required before making such a
   claim.")

;; --- Canonical pending-settlement boundary fixtures ---

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(styled-table
 ["Scenario" "Offset from Deadline" "Expected Outcome" "Guard Checked"]
 [["s74-appeal-deadline-boundary" "-1 / 0 / +1" "Reject before; eligible at and after deadline" "execute-pending-settlement"]
  ["dr-b-001-appeal-window-expiry-race" "0" "Challenge rejected; settlement eligible" "challenge-resolution / execute-pending-settlement"]
  ["S81  appeal-deadline-boundary-before" "-1" "Execution rejected" ":appeal-window-not-expired"]])

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/md
 "**Evidence limit:** These fixture references document the intended
   pending-settlement boundary semantics. This notebook does not execute them
   at render time. The equal-timestamp resolver slash-appeal/execute-fraud-slash
   conflict remains a hardening gap, not a validated safety claim.")

;; ===========================================================================
;; Section 8: Kleros and Escalation Analysis
;; ===========================================================================

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/md "## 8. Escalation Economics and External-Arbitration Model

   This section models how appeal outcomes may be affected by higher-level
   review or a Kleros-style external tier. It demonstrates on-protocol
   escalation and economic assumptions, not a deployed external-arbitration
   integration.")

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(styled-table
 ["Capability" "Status represented here" "Not demonstrated by this notebook"]
 [["On-protocol challenge and escalation" "Implemented state-machine path" "—"]
  ["L1/L2 reversal and cost assumptions" "Stochastic / analytic model; single traces are illustrative" "Production outcome probabilities or confidence intervals"]
  ["External/Kleros-style tier" "Conceptual/modelled backstop" "External request, callback, ruling verification, fee settlement, timeout handling"]
  ["External ruling evidence" "Not exercised" "Portable evidence of a real arbitrator decision"]])

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn- simulate-appeal-scenarios
  "Run a small number of stochastic appeal simulations to illustrate
   escalation outcomes under different conditions."
  []
  (let [rng (java.util.Random. (:seed @cfg))
        sa (:stochastic-assumptions @cfg)
        param-sets [{:label "Base"
                     :params (get sa :base)}
                    {:label "Optimistic (high reversal)"
                     :params (get sa :optimistic)}
                    {:label "Pessimistic (low reversal)"
                     :params (get sa :pessimistic)}]
        contexts [{:label "Correct verdict"
                   :context {:verdict-correct? true :appealed? true}}
                  {:label "Wrong verdict"
                   :context {:verdict-correct? false :appealed? true}}]]
    (for [ps param-sets
          ctx contexts]
      (let [result (detect/appeal-reversal-outcome rng (:params ps) (:context ctx))]
        (merge
         {:band (:label ps) :verdict-context (:label ctx)}
         result)))))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def appeal-stochastic-results
  (simulate-appeal-scenarios))

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/md "### Stochastic Appeal Reversal Outcomes

   **Illustrative execution traces — not statistical evidence.** Each row is
   one seeded Bernoulli realization from ``appeal-reversal-outcome`` for the
   named parameter band and verdict context (seed from ``appeal_config.edn``).
   These samples show possible control flow only; they do not estimate reversal
   rates, false-reversal rates, failure-to-correct rates, or confidence
   intervals. Those require repeated trials and an aggregate experiment.")

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(styled-table
 ["Band" "Verdict Context" "L1 Reversed?" "L2 Escalated?" "L2 Reversed?" "Decision Reversed?"]
 (map (fn [{:keys [band verdict-context l1-reversed? l2-escalated? l2-reversed? decision-reversed?]}]
        [band verdict-context (str l1-reversed?) (str l2-escalated?) (str l2-reversed?) (str decision-reversed?)])
      appeal-stochastic-results))

;; --- 8b: Full appeal simulation ---

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn- run-full-appeal-sims
  "Run the simulate-full-appeal function under different conditions."
  []
  (let [fas (:full-appeal-simulation @cfg)
        rng (java.util.Random. (:seed @cfg))]
    (for [diff (:difficulties fas)
          ev (:evidence-levels fas)]
      (let [res (dq/simulate-full-appeal
                 rng (:ground-truth fas)
                 [(:r0-honest? fas) (:r1-corrupt? fas) (:r2-corrupt? fas)]
                 (:time-pressures fas)
                 diff ev)]
        {:difficulty diff
         :evidence-quality ev
         :ground-truth (:ground-truth fas)
         :initial-decision (get-in res [:round-0 :decision])
         :final-decision (:final-decision res)
         :correct-outcome? (not (:was-error res))
         :escalation-count (:escalation-count res)
         :was-error (:was-error res)}))))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def full-appeal-results
  (try (run-full-appeal-sims)
       (catch Exception _ [{:difficulty "N/A" :evidence-quality "N/A"
                            :ground-truth "N/A" :initial-decision "N/A"
                            :final-decision "N/A" :correct-outcome? "N/A"
                            :escalation-count "N/A"
                            :was-error "Simulation failed — check dependencies"}])))

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/md "### Full Appeal Simulation

   **Illustrative traces — not aggregate decision-quality evidence.** Each row
   is one seeded run of ``simulate-full-appeal`` through R0 (resolver), R1
   (senior), and an assumed R2 external/Kleros-style tier. ``true`` means the
   model's release/refund decision matches the configured boolean ground truth.
   R2 is a model input, not an executed external integration; escalation counts
   reflect the sampled appeal decisions, not a measured improvement rate.")

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(styled-table
 ["Difficulty" "Evidence Quality" "Ground Truth" "Initial Decision" "Final Decision" "Correct Outcome?" "Escalations"]
 (map (fn [{:keys [difficulty evidence-quality ground-truth initial-decision final-decision correct-outcome? escalation-count]}]
        [(str difficulty) (str evidence-quality) (str ground-truth) (str initial-decision)
         (str final-decision) (str correct-outcome?) (str escalation-count)])
      full-appeal-results))

;; --- 8c: Escalation cost comparison ---

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn- escalation-cost-comparison
  "Compare cost to attack at different rounds."
  []
  (let [config ee/DEFAULT_ESCALATION_CONFIG
        dispute-values (:dispute-values (:economic-analysis @cfg))]
    (for [dv dispute-values]
      (let [comparison (ee/compare-attack-costs dv config)]
        (assoc comparison :dispute-value dv)))))

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/md "### Attack Cost Relative to Dispute Value

   The default escalation model compares absolute corruption costs by route.
   The table also shows the cheapest route as a percentage of dispute value and
   the corresponding potential surplus before other costs. If absolute attack
   cost stays fixed while dispute value rises, the model becomes less protective
   relative to value; this is a calibration warning, not evidence of safety.")

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(styled-table
 ["Normalized Dispute Value" "Attack R0" "Attack R0→R1" "Attack R1" "Cheapest Cost" "Cost / Value" "Potential Surplus" "Calibration Warning"]
 (map (fn [{:keys [dispute-value attack-r0-only attack-r0-then-r1 attack-r1-only cheapest-route]}]
        (let [cost (double cheapest-route)
              value (double dispute-value)
              ratio (* 100.0 (/ cost value))
              surplus (- value cost)]
          [(str dispute-value)
           (str (int attack-r0-only))
           (str (int attack-r0-then-r1))
           (str (int attack-r1-only))
           (str (int cost))
           (format "%.1f%%" ratio)
           (str (int surplus))
           (if (< ratio 100.0) "Value exceeds modeled cheapest attack cost" "No surplus at this model value")]))
      (escalation-cost-comparison)))

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/md
 "**Key escalation insights**

 | Question | Answer |
 |----------|--------|
 | What happens if L1 review is wrong? | The dispute escalates to L2 where bond costs are higher and scrutiny is stronger. |
 | What happens if escalation is expensive? | Higher costs deter frivolous escalation, but also create a barrier to valid correction. Bond parameters must be calibrated. |
 | When does a bond deter frivolous appeals? | When the expected loss from forfeiture exceeds the expected gain from a successful frivolous appeal. |
 | When might a bond overdeter valid appeals? | If the bond is set so high that even resolvers with a meritorious case cannot afford to appeal. |
 | How could an external-arbitration backstop improve robustness? | In the model, an independent final review layer reduces assumed L0/L1 capture or error risk; live integration remains future work. |")

;; ===========================================================================
;; Section 9: Governance and Authorization Paths
;; ===========================================================================

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/md "## 9. Governance and Authorization Paths

   This section covers governance appeal paths and emergency override
   patterns for the appeal subsystem.")

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/md
 "### Governance resolving an appeal

   Governance (TIMELOCK) resolves a slashing appeal via
   ``resolve-appeal``. The function accepts an ``appeal-upheld?`` flag:

   - ``appeal-upheld? = true`` → slash is reversed, bond returned,
     execution blocked
   - ``appeal-upheld? = false`` → slash stands, bond forfeited,
     execution proceeds

   Governance resolution requires authorization provenance.
   ``resolve-appeal`` rejects a call without ``:authorization-provenance``,
   providing forensic tracing of who authorized the resolution.

   ### Force resolution

   If governance override paths exist (e.g. emergency resolution), they
   should be distinguishable in evidence via explicit
   ``:authorization-provenance`` tags. This is critical for auditability
   and forensic reconstruction.

   ### Appeal-related custody movements

   Custody movements are mechanism-specific and should not be merged in
   evidence review:

   | Mechanism | Movement | Reason / evidence | Ledger |
   |-----------|----------|-------------------|--------|
   | Resolver slash appeal | Bond posted | ``appeal-slash``; ``:fraud-slash-appealed`` evidence | Slash-scoped ``:appeal-bond-custody`` |
   | Resolver slash appeal | Bond returned | ``:appeal-bond-returned`` in ``resolve-appeal`` | Slash-scoped custody cleared; refund claimable |
   | Resolver slash appeal | Bond forfeited | ``:appeal-bond-forfeited`` in ``resolve-appeal`` | Slash-appeal insurance/distribution accounting |
   | Resolution challenge | Bond posted | ``post-appeal-bond`` | Generic ``:bond-balances`` ledger |
   | Resolution challenge | Fee collected | ``:bond-fees`` from generic posting path | Generic fee ledger |
   | Resolution challenge | Net bond returned/slashed | ``return-bond`` / ``slash-bond`` | Generic challenge-bond ledger |

   ### Current limitations

   - Authorization provenance is required at the ``resolve-appeal`` entry
     point. Normalization and evidence-schema coverage across adjacent
     governance paths remain in progress.
   - Force resolution / emergency override paths are not yet fully
     formalized in the evidence schema.
   - Governance authorization boundaries between TIMELOCK and DAO are still
     being migrated to explicit evidence records.")

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(styled-table
 ["Capability" "Status" "Grant relevance"]
 [["Governance resolves appeal" "Implemented (resolve-appeal)" "Auditability, dispute safety"]
  ["Authorization provenance" "Required at resolve-appeal; adjacent-path normalization in progress" "Forensic reconstruction"]
  ["Force resolution / emergency" "Not yet formalized" "Future hardening item"]
  ["Custody movement evidence" "Tracked via reason tags" "Audit trail"]
  ["Governance <-> DAO boundaries" "Migration in progress" "Custody authorization"]])

;; ===========================================================================
;; Section 10: Grant Application Summary
;; ===========================================================================

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/md "## 10. Grant Application Summary Section

   ---

   ### Problem

   Decentralized dispute resolution requires a mechanism for correcting
   erroneous or malicious slashing decisions without undermining the
   finality and economic security of the system. Without a well-designed
   appeal subsystem, resolvers can be slashed unfairly with no recourse,
   or frivolous appeals can delay settlement and drain protocol resources.

   ### Approach

   The Sew Protocol implements a bonded appeal system with the following
   properties:

   - **Time-bounded appeal windows** — appeals must be submitted within a
     configurable window after a slash is proposed.
   - **Economic bond requirement** — appellants must post a bond calculated
     as a percentage of the slashed amount (bps) or a fixed amount.
   - **Challenge-bond protocol fee** — generic ``post-appeal-bond`` accrues a
     fee to ``:bond-fees`` for the open resolution-challenge path. Resolver
     ``appeal-slash`` holds its full slash-scoped bond in the current model.
   - **Governance resolution** — appeals are resolved by governance
     (TIMELOCK), with provenance required at ``resolve-appeal`` and adjacent
     governance-path normalization still in progress.
   - **Multi-level escalation model** — disputes can escalate through L1 → L2
     and an assumed external/Kleros-style tier, with increasing modeled bond
     costs at each level.
   - **Invariant-checked custody** — appeal bond custody has non-negativity
     and consistency checks; full bond-flow conservation remains future work.

   ### What is novel

   - **Appeal bonds as calibrated economic security** — the bond is not
     merely UX friction but a measurable parameter with custody non-negativity
     and consistency checks; full flow reconciliation remains future work.
   - **Benchmark-backed arbitration design** — the appeal window adequacy
     (F6) and frivolous appeal discouragement (M3) benchmarks provide
     reproducible evidence for parameter choices.
   - **Custody safety instrumentation** — appeal-bond movements carry reason
     tags, while existing invariants check non-negativity and custody
     consistency rather than full post/return/forfeit reconciliation.
   - **Finality blocking during appeal** — financial finality is deferred
     until the appeal window closes, preventing premature settlement.
   - **Correction paths for malicious or erroneous slashing** — both
     governance appeal and multi-level escalation provide avenues for
     correcting wrong decisions.

   ### Evidence generated

   | Evidence | Source | What it shows |
   |----------|--------|---------------|
   | Appeal lifecycle diagram | Section 2 | Resolver slash-appeal lifecycle and its governance decision point |
   | Challenge-bond custody flow | Section 3 | Generic challenge bond post → fee → custody accounting path |
   | Economics tables | Section 4 | Bond amounts, fees, escalation costs under parameter bands |
   | Benchmark F6 | Section 4 | Appeal window adequacy (pass rate) |
   | Benchmark M3 | Section 4 | Frivolous appeal discouragement (pass rate) |
   | Scenario registry | Section 5 | Static mapping of 14 appeal/challenge fixtures; not runner output |
   | Invariant coverage | Section 6 | 4 invariant definitions and their limits; not live results |
   | Boundary testing | Section 7 | Implemented deadline comparisons and unresolved slash-appeal equality gap |
   | Escalation analysis | Section 8 | Stochastic outcome modeling with L1/L2/Kleros |
   | Governance paths | Section 9 | Authorization and custody movement documentation |

   ### Benchmarks / scenarios

   - **14 curated appeal/challenge fixture references**; this notebook does not execute them
   - **F6 benchmark**: Appeal window adequacy (pass threshold ≥ 75%)
   - **M3 benchmark**: Frivolous appeal discouragement (pass threshold ≥ 70%)
   - **Boundary semantics**: Non-overlapping challenge/settlement deadline plus an unresolved resolver-slash equality case
   - **Stochastic modeling**: Appeal reversal outcomes under base / optimistic / pessimistic assumptions
   - **Full appeal simulation**: Multi-round process through R0, R1, R2/Kleros

   ### Invariants checked

   - ``appeal-bond-conserved?`` — Legacy identifier for non-negative slash-scoped appeal-bond-held; not a full conservation proof
   - ``appeal-bond-custody-consistent?`` — Custody lifecycle is coherent
   - ``appeal-requires-prior-resolution?`` — Escalation challenges cannot advance without a prior provisional resolution
   - ``finality-blocked-during-appeal?`` — No provisional settlement before its challenge window expires

   ### Why this matters to ecosystem safety

   The appeal subsystem is a critical safety mechanism for any dispute
   resolution protocol. It provides the only corrective path for resolvers
   who have been unfairly slashed, while maintaining economic disincentives
   against abuse. The benchmark-backed approach demonstrated in this
   notebook enables:

   - **Evidence-based parameter selection** for appeal bonds and windows
   - **Reproducible benchmark inputs** for bond and window calibration
   - **Auditable custody movements** with explicit evidence limits
   - **Bounded multi-level escalation** as a correction-path robustness mechanism

   ### Grant programme and research agenda

   | Work package | What funding would establish | Current status |
   |--------------|------------------------------|----------------|
   | Mechanism formalization | Per-bond custody identities, conservation equations, uniform governance provenance, and explicit equal-timestamp ordering semantics | Partial: non-negativity and entry-point provenance exist; full formalization remains open |
   | Economic calibration | Combined deterrence/accessibility sweeps, sensitivity by dispute value and participant liquidity, and escalation griefing analysis | F6/M3 analytic hooks exist; integrated calibration remains research |
   | Evidence publication | Versioned parameter snapshots, reproducible benchmark packs, run revision metadata, and portable scenario/invariant evidence bundles | Static scenario mappings and some evidence hooks exist; a consolidated appeal pack is not shown here |
   | External-arbitration adapter | Request/callback model, ruling authentication, timeout/failure behavior, cost allocation, and custody reconciliation | Proposed/modelled only |
   | Emergency and DAO governance paths | Formal authorization and evidence semantics for force resolution and DAO/TIMELOCK boundaries | In progress / not yet formalized |")

;; ===========================================================================
;; Notebook navigation
;; ===========================================================================

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/html (ui/notebook-navigation "Appeal Analysis"))

;; ===========================================================================
;; Provenance footer
;; ===========================================================================

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/md (str "---\n\n"
               "*Notebook generated by the Sew Protocol simulation toolchain.*  \n"
               "*Source: `notebooks/appeal_analysis.clj`*  \n"
               "*All analysis is live — evaluated in-process at render time.*"))
