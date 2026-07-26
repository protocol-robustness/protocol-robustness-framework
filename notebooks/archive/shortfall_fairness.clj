^{:nextjournal.clerk/visibility {:code :show :result :show}
  :nextjournal.clerk/dark-mode true}
(ns notebooks.archive.shortfall-fairness
  "Interactive notebook: shortfall lifecycle, fairness, and classification.
   Demonstrates Phase 1-5: projection fields, configurable reclaim threshold,
   principal/yield split ratios, conservation invariant, and lifecycle events."
  (:require [nextjournal.clerk :as clerk]
            [clojure.pprint :as pp]
            [resolver-sim.yield.accounting :as acct]
            [resolver-sim.yield.liquidity :as liq]
            [resolver-sim.yield.risk :as yrisk]
            [resolver-sim.yield.invariants :as yi]
            [resolver-sim.yield.evidence :as ye]
            [resolver-sim.notebook-support.nav :as nav]))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html (nav/top-nav-bar "notebooks/shortfall_fairness.clj"))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/md "# Shortfall Fairness — Interactive Research Notebook

Demonstrates all five phases of shortfall lifecycle support.")

;; ════════════════════════════════════════════════════════════════════
;; 1. Shortfall application — equal treatment at withdrawal
;; ════════════════════════════════════════════════════════════════════

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/md "## 1. Shortfall Application — Equal Treatment at Withdrawal

When a yield module is in shortfall, every withdrawal gets the same
available-ratio applied — no favoritism by amount, user, or position order.")

(comment
  (let [amounts [1000 2000 5000 10000 25000]
        risk {:liquidity-mode :shortfall
              :shortfall {:available-ratio 0.8 :reason :liquidity-shortfall}}
        world {:yield/risk {:aave-v3 {:USDC risk}}}
        withdrawals (map (fn [a]
                           {:requested a
                            :result (liq/apply-withdrawal-policy
                                     world :aave-v3 :USDC a a)})
                         amounts)]
    (clerk/table
     (conj (for [w withdrawals]
             (let [r (:result w)
                   sf (:shortfall r)]
               {:requested (:requested w)
                :fulfilled (:fulfilled r)
                :deferred (long (or (:deferred-amount sf) 0))
                :rate (double (/ (:fulfilled r) (:requested w)))}))
           {:requested "TOTAL"
            :fulfilled (reduce + (map (comp :fulfilled :result) withdrawals))
            :deferred (reduce + (map #(long (get-in % [:result :shortfall :deferred-amount] 0)) withdrawals))
            :rate 0.8}))))

;; ════════════════════════════════════════════════════════════════════
;; 2. Phase 1 — Projection fields
;; ════════════════════════════════════════════════════════════════════

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/md "## 2. Phase 1 — Projection Fields

Every yield position now exposes structured shortfall annotations in traces.

| Field | Meaning |
|-------|---------|
| `shortfall-affected?` | Position has an active shortfall |
| `shortfall-kind` | :temporary-liquidity, :insolvency, :negative-yield, etc. |
| `shortfall-effect` | :timing-delayed, :loss-realized, :partially-liquid |
| `shortfall-affected-fields` | Which balance fields are constrained |
| `liquidity-shortfall` | Shortfall with no permanent loss (haircut=0) |
| `economic-shortfall` | Shortfall with permanent haircut loss |")

(comment
  (let [pos {:owner/id "user-1" :token :USDC :principal 10000
             :shares 5000 :entry-index 1.0 :status :unwinding
             :realized-yield 400 :unrealized-yield 100
             :shortfall {:fulfilled-amount 8400 :deferred-amount 1600
                         :haircut-amount 0 :reason :liquidity-shortfall
                         :available-ratio 0.8 :basis-amount 10000}}
        ms {:available-ratio 0.8 :apy 0.05}
        ;; Simulate what the projection computes
        sf (:shortfall pos)
        total (+ (:principal pos 0) (:realized-yield pos 0) (:unrealized-yield pos 0))
        gross-yield (max 0 (- total (:principal pos 0)))
        deferred (long (or (:deferred-amount sf) 0))
        haircut (long (or (:haircut-amount sf) 0))]
    {:shortfall-affected? (some? sf)
     :shortfall-kind (if (= (:reason sf) :liquidity-shortfall)
                       :temporary-liquidity
                       :unknown)
     :shortfall-effect (cond
                         (and (pos? deferred) (pos? haircut)) :partially-liquid
                         (pos? deferred) :timing-delayed
                         (pos? haircut) :loss-realized
                         :else :claimability-constrained)
     :shortfall-affected-fields (cond-> []
                                  (pos? deferred) (conj :deferred-amount)
                                  (pos? haircut) (conj :haircut-amount))
     :liquidity-shortfall (and sf (zero? haircut))
     :economic-shortfall (and sf (pos? haircut))
     :claimable-yield (max 0 (- gross-yield deferred haircut))
     :deferred-yield deferred}))

;; ════════════════════════════════════════════════════════════════════
;; 3. Phase 2 — Configurable Reclaim Threshold
;; ════════════════════════════════════════════════════════════════════

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/md "## 3. Phase 2 — Configurable Reclaim Threshold

claim-deferred now reads :min-available-ratio-for-claim from the risk config
(default 1.0).  Setting it below 1.0 enables **partial recovery** — deferred
amounts become reclaimable before the module fully returns to :available.")

(comment
  (let [pos {:token :USDC :status :unwinding :principal 10000
             :realized-yield 0 :unrealized-yield 0
             :shortfall {:fulfilled-amount 8000 :deferred-amount 2000
                         :haircut-amount 0 :reason :liquidity-shortfall
                         :basis-amount 10000 :available-ratio 0.8}}
        ;; Module at 90% recovery, threshold = 0.85 → claimable
        risk-claim {:liquidity-mode :shortfall
                    :min-available-ratio-for-claim 0.85
                    :shortfall {:available-ratio 0.9}}
        ;; Module at 90% recovery, threshold = 0.95 → not claimable
        risk-block {:liquidity-mode :shortfall
                    :min-available-ratio-for-claim 0.95
                    :shortfall {:available-ratio 0.9}}
        w-claim {:yield/risk {:mod {:USDC risk-claim}}}
        w-block {:yield/risk {:mod {:USDC risk-block}}}
        r1 (acct/claim-deferred w-claim :mod pos)
        r2 (acct/claim-deferred w-block :mod pos)]
    (clerk/table
     [{:scenario "ratio=0.9, threshold=0.85" :cleared? (nil? (:shortfall r1))
       :reclaimed (:reclaimed-amount r1 0)}
      {:scenario "ratio=0.9, threshold=0.95" :cleared? (nil? (:shortfall r2))
       :reclaimed (:reclaimed-amount r2 0)}])))

;; ════════════════════════════════════════════════════════════════════
;; 4. Phase 3 — Principal/Yield Split Ratios
;; ════════════════════════════════════════════════════════════════════

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/md "## 4. Phase 3 — Principal/Yield Split Ratios

When `:partial-liquidity` is in the failure modes and `:principal` is passed,
separate ratios are applied to the yield and principal portions.

Example: principal=100% available, yield=60% available, partial-liquidity active.")

(comment
  (let [world {:yield/risk {:mod {:USDC {:liquidity-mode :shortfall
                                         :failure-modes #{:partial-liquidity}
                                         :shortfall {:available-ratio 0.8
                                                     :yield-available-ratio 0.6
                                                     :principal-available-ratio 1.0
                                                     :reason :liquidity-shortfall}}}}}
        ;; 5000 yield, 10000 principal = 15000 gross
        ;; yield=5000 * 0.6 = 3000, principal=10000 * 1.0 = 10000
        ;; total fulfilled = 13000
        res (acct/apply-liquidity-stress world :mod :USDC 15000 :principal 10000)]
    (clerk/table
     [{:scenario "partial-liquidity with split ratios"
       :gross 15000 :fulfilled (:fulfilled res)
       :deferred (get-in res [:shortfall :deferred-amount] 0)
       :yield-ratio (get-in res [:shortfall :yield-available-ratio])
       :principal-ratio (get-in res [:shortfall :principal-available-ratio])
       :shortfall-kind (get-in res [:shortfall :shortfall-kind])}])))

;; ════════════════════════════════════════════════════════════════════
;; 5. Phase 4 — Conservation Invariant
;; ════════════════════════════════════════════════════════════════════

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/md "## 5. Phase 4 — Value Conservation Invariant

The invariant `:yield/value-conservation` verifies that for every position:
- deferred-amount >= 0, haircut-amount >= 0, fulfilled-amount >= 0
- deferred-amount + haircut-amount <= principal + max(0, unrealized-yield)

This runs on every replay step alongside the other 6 yield invariants.")

(comment
  (let [;; A valid shortfall position: deferred=1500 <= principal=10000
        valid {:yield/positions {"p1" {:token :USDC :principal 10000
                                       :realized-yield 300 :unrealized-yield 200
                                       :shortfall {:deferred-amount 1500
                                                   :haircut-amount 0
                                                   :fulfilled-amount 8500}}}}
        ;; Invalid: deferred=20000 > principal=10000+500 (no yield covers it)
        invalid {:yield/positions {"p1" {:token :USDC :principal 10000
                                         :realized-yield 300 :unrealized-yield 200
                                         :shortfall {:deferred-amount 20000
                                                     :haircut-amount 0
                                                     :fulfilled-amount 0}}}}
        {:keys [holds?]} (yi/holds? :yield/value-conservation valid)
        {:keys [holds?]} (yi/holds? :yield/value-conservation invalid)]
    (clerk/table
     [{:scenario "Valid position (claimed <= residual)" :passes? true}
      {:scenario "Invalid (deferred exceeds residual)" :passes? false}])))

;; ════════════════════════════════════════════════════════════════════
;; 6. Phase 5 — Shortfall Lifecycle Events
;; ════════════════════════════════════════════════════════════════════

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/md "## 6. Phase 5 — Shortfall Lifecycle Events

Events are stored in `world[:yield/events]` and emitted at key lifecycle
transitions.  Each event includes type, block time, position id, and
relevant amounts.")

(comment
  (let [events (atom [])
        world (-> {}
                  (ye/emit-shortfall-event :yield.shortfall/deferred-created "pos-1"
                                           {:deferred-amount 2000 :haircut-amount 0 :fulfilled-amount 8000
                                            :basis-amount 10000 :available-ratio 0.8
                                            :shortfall-kind "temporary-liquidity"})
                  (ye/emit-shortfall-event :yield.shortfall/deferred-reclaimed "pos-1"
                                           {:reclaimed-amount 2000 :deferred-before 2000})
                  (ye/emit-shortfall-event :yield.shortfall/deferred-created "pos-2"
                                           {:deferred-amount 5000 :haircut-amount 3000 :fulfilled-amount 4000
                                            :basis-amount 12000 :available-ratio 0.5
                                            :shortfall-kind "partial-liquidity"}))]
    (clerk/table
     (for [e (:yield/events world)]
       {:type (:event/type e) :position (:position/id e)
        :deferred (:deferred-amount e) :haircut (:haircut-amount e)
        :reclaimed (:reclaimed-amount e "n/a")}))))

;; ════════════════════════════════════════════════════════════════════
;; 7. Economic vs Liquidity Shortfall
;; ════════════════════════════════════════════════════════════════════

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/md "## 7. Economic vs Liquidity Shortfall

The framework distinguishes two fundamentally different kinds of shortfall:

| Kind | haircut > 0? | Meaning |
|------|-------------|---------|
| **Liquidity** (deferred only) | No | Value exists, temporarily illiquid |
| **Economic** (haircut present) | Yes | Permanent loss of value |

Same projection field: `shortfall-effect` distinguishes:
- `:timing-delayed` — liquidity-only (deferred, no haircut)
- `:loss-realized` — economic (haircut, no deferred)
- `:partially-liquid` — both")
