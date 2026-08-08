;; # Short-Circuit Execution — Composition Demo
;;
;; **What this demonstrates:** a compiled composition plan is a sequential
;; pipeline. When a node signals `:short-circuit?` at runtime, execution halts
;; immediately — every later node is skipped, so no further amounts are
;; deducted, no effects are emitted, and no node evidence is recorded for them.
;;
;; The scenario below runs the **same three-node plan** twice. The only
;; difference is what node 2 does:
;;
;;   • **Scenario A** — node 2 completes normally. All three nodes run.
;;   • **Scenario B** — node 2 returns `:short-circuit? true`. Node 3 never runs.
;;
;; The outcome is inspectable: status, which nodes executed, the value that
;; flowed, and the effects that were emitted.

^{:nextjournal.clerk/dark-mode true}
(ns notebooks.demo-short-circuit
  (:require [nextjournal.clerk :as clerk]
            [resolver-sim.composition.execution :as exec]
            [resolver-sim.composition.fixtures :as fx]))

;; ## 1. What to look for
;;
;; Use this guide to interpret the two scenarios below.

(clerk/table
 {"Concept" ["Sequential pipeline" "Short-circuit signal" "Halt" "Later nodes" "Node evidence"]
  "Meaning" ["Nodes run in order: n1 → n2 → n3"
             "A node returns :short-circuit? true at runtime"
             "Execution stops; the result reports :execution/status :short-circuited"
             "Every node after the signal is skipped — no amount, no effects, no evidence"
             "Executed nodes are still recorded in :execution/nodes"]})

;; --- Mechanism Diagram ---

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 [:div {:style {:fontFamily "sans-serif" :marginTop "20px" :marginBottom "40px" :color "#1e293b"}}
  [:h3 "The Short-Circuit Mechanism"]
  [:div {:style {:display "flex" :flexDirection "column" :alignItems "center" :gap "8px"}}
   [:div {:style {:border "1px solid #cbd5e1" :padding "8px 20px" :borderRadius "4px" :background "#f8fafc"}} "Node n1 runs"]
   [:div "↓"]
   [:div {:style {:border "1px solid #f59e0b" :padding "8px 20px" :borderRadius "4px" :background "#fffbeb"}}
    "Node n2 runs — returns :short-circuit? true"]
   [:div {:style {:border "2px solid #ef4444" :padding "8px 20px" :borderRadius "4px" :background "#fff1f2" :fontWeight "bold"}}
    "⛔ Pipeline halts — n3, n4, … are never executed"]
   [:div {:style {:border "1px solid #3b82f6" :padding "8px 20px" :borderRadius "4px" :background "#eff6ff" :fontWeight "bold"}}
    "Result: :execution/status :short-circuited"]]])

;; --- View Model Helpers ---

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def emap
  (fx/ext-map-with
   (fx/cap :fixture/rate :entrypoint 'resolver-sim.composition.fixtures/identity-award)
   (fx/cap :fixture/stop :entrypoint 'resolver-sim.composition.fixtures/short-circuit-award)
   (fx/cap :fixture/effects :entrypoint 'resolver-sim.composition.fixtures/effect-award)))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn combo-of
  "A three-node sequential combination over a shared input of 1000.
   Node 1 derives an amount by rate; node 2 and node 3 are supplied by
   the caller so the two scenarios share one pipeline shape."
  [n2 n3]
  {:combination/id :test.combination/seq
   :combination/version 1
   :combination/nodes [(fx/node :n1 [:economics/award-amount :fixture/rate] :spec {:rate 500})
                       n2 n3]
   :combination/input {:schema-ref :prf/award-amount-context.v1 :semantic-type :amount}
   :combination/expected-output {:schema-ref :prf/calculation-result.v1 :semantic-type :amount}
   :combination/effect-merge-strategy :accumulate})

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn run
  [combination]
  (exec/execute-combination {:extensions emap} combination {} 1000))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn build-vm
  [title reader-outcome n2 n3]
  (let [result (run (combo-of n2 n3))
        executed (:execution/nodes result)
        status (:execution/status result)]
    {:demo/title title
     :reader/outcome reader-outcome
     :planned/nodes [:n1 :n2 :n3]
     :short-circuit/node (when (= :short-circuited status)
                           (:node/id (last executed)))
     :execution/status status
     :execution/value (:execution/value result)
     :execution/nodes executed
     :execution/effects (:execution/effects result)
     :raw/combination (combo-of n2 n3)
     :raw/result result}))

;; --- Visualization Components ---

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn- fmt-usd [n]
  (if (number? n)
    (format "$%,.2f USDC" (double n))
    "n/a"))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn outcome-box [vm]
  (let [short-circuited? (= :short-circuited (:execution/status vm))
        color (if short-circuited? "#ef4444" "#10b981")
        bg (if short-circuited? "#fff1f2" "#ecfdf5")
        icon (if short-circuited? "⛔ " "✅ ")]
    (clerk/html
     [:div {:style {:padding "20px" :borderRadius "8px" :marginBottom "20px"
                    :background bg :border (str "2px solid " color)}}
      [:div {:style {:fontSize "18px" :fontWeight "bold" :color color}}
       (str icon (:demo/title vm))]
      [:div {:style {:fontSize "14px" :marginTop "8px" :color "#4b5563" :fontWeight "500"}}
       (:reader/outcome vm)]])))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn node-card [vm node]
  (let [executed-ids (into #{} (map :node/id) (:execution/nodes vm))
        id (:node/id node)
        ran? (contains? executed-ids id)
        fired? (= id (:short-circuit/node vm))
        ev (first (filter #(= (:node/id %) id) (:execution/nodes vm)))
        card-style (cond
                     fired? {:border "2px solid #ef4444" :background "#fff1f2"}
                     ran?  {:border "1px solid #10b981" :background "#ecfdf5"}
                     :else {:border "1px dashed #94a3b8" :background "#f1f5f9"})]
    (clerk/html
     [:div {:style (merge {:borderRadius "6px" :padding "12px" :fontSize "13px" :flex "1"} card-style)}
      [:div {:style {:display "flex" :justifyContent "space-between" :alignItems "center" :marginBottom "6px"}}
       [:span {:style {:fontWeight "700" :color "#0f172a" :fontFamily "monospace"}}
        (name id)]
       (cond
         fired? [:span {:style {:color "#ef4444" :fontWeight "700" :fontSize "11px"}}
                 "⚠ SHORT-CIRCUIT"]
         ran?  [:span {:style {:color "#166534" :fontWeight "700" :fontSize "11px"}}
                "✓ RAN"]
         :else [:span {:style {:color "#475569" :fontWeight "700" :fontSize "11px"}}
                "⛔ SKIPPED"])]
      (cond
        fired? [:div {:style {:color "#b91c1c"}}
                "Returned :short-circuit? true. Pipeline halts here — every later node is skipped."]
        ran?  [:div {:style {:display "grid" :gridTemplateColumns "70px 1fr" :gap "2px" :fontSize "12px"}}
               [:span {:style {:color "#64748b"}} "input:"] [:span {:style {:color "#0f172a"}} (:input ev)]
               [:span {:style {:color "#64748b"}} "amount:"] [:span {:style {:color "#0f172a"}} (:amount ev)]
               [:span {:style {:color "#64748b"}} "output:"] [:span {:style {:color "#0f172a"}} (:output ev)]
               [:span {:style {:color "#64748b"}} "effects:"] [:span {:style {:color "#0f172a"}} (count (:effects ev))]]
        :else  [:div {:style {:color "#475569" :fontSize "12px"}}
                "Never invoked. No amount, no effects, no node evidence recorded."])])))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn pipeline-viz [vm]
  (let [nodes-by-id {:n1 {:node/id :n1} :n2 {:node/id :n2} :n3 {:node/id :n3}}]
    (clerk/html
     [:div {:style {:display "flex" :gap "10px" :alignItems "stretch" :marginBottom "16px"}}
      (for [id (:planned/nodes vm)]
        [:div {:key (name id) :style {:display "flex" :gap "10px" :alignItems "stretch"}}
         (node-card vm (get nodes-by-id id))
         (when-not (= id :n3)
           [:div {:style {:display "flex" :alignItems "center" :color "#94a3b8" :fontSize "18px"}} "→"])])])))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn outcome-summary [vm]
  (let [short-circuited? (= :short-circuited (:execution/status vm))]
    (clerk/html
     [:div {:style {:background "#f8fafc" :padding "15px" :borderRadius "6px"
                    :border "1px solid #cbd5e1" :marginTop "8px"}}
      [:h4 {:style {:margin "0 0 10px 0" :fontSize "14px"}} "Execution Summary"]
      [:div {:style {:display "grid" :gridTemplateColumns "170px 1fr" :gap "8px" :fontSize "13px"}}
       [:span {:style {:color "#64748b"}} "Status:"] [:strong (pr-str (:execution/status vm))]
       [:span {:style {:color "#64748b"}} "Nodes executed:"] [:strong (str (count (:execution/nodes vm)) " / 3")]
       [:span {:style {:color "#64748b"}} "Final value:"] [:strong (fmt-usd (:execution/value vm))]
       [:span {:style {:color "#64748b"}} "Effects emitted:"] [:strong (str (count (:execution/effects vm)) " — " (pr-str (mapv :effect/contract (:execution/effects vm))))]]
      [:div {:style {:marginTop "10px" :fontSize "12px" :color "#475569"}}
       (if short-circuited?
         "The short-circuit halts the pipeline before node 3: its 5-unit award is never applied and its effect is never emitted."
         "All three nodes run to completion: amounts flow through the pipeline and every declared effect is emitted.")]])))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn scenario-details [vm]
  (clerk/html
   [:details {:style {:background "#f1f5f9" :padding "12px" :borderRadius "6px" :marginTop "10px"}}
    [:summary {:style {:cursor "pointer" :fontWeight "bold" :color "#475569"}} "Scenario Details (EDN & Result)"]
    [:div {:style {:marginTop "12px"}}
     [:h5 {:style {:margin "0 0 6px 0"}} "Combination (compiled plan input)"]
     (clerk/code (:raw/combination vm))
     [:h5 {:style {:margin "12px 0 6px 0"}} "Execution Result"]
     (clerk/code (:raw/result vm))]]))

;; --- Scenario A — Normal completion ---
;;
;; **Purpose:** Establish the baseline. All three nodes run, so the pipeline
;; ends with status `:completed`, a fully flowed value, and both effects.

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def vm-a
  (build-vm
   "Normal pipeline — all nodes run"
   "Node 2 completes normally, so node 3 also runs: the full 3-node pipeline executes and both effects are emitted."
   (fx/node :n2 [:economics/award-amount :fixture/effects]
            :spec {:amount 5 :effects [{:effect/contract :prf.effect/x.v1}]})
   (fx/node :n3 [:economics/award-amount :fixture/effects]
            :spec {:amount 5 :effects [{:effect/contract :prf.effect/y.v1}]})))

(outcome-box vm-a)
(pipeline-viz vm-a)
(outcome-summary vm-a)
(scenario-details vm-a)

(clerk/md "---")

;; --- Scenario B — Short-circuit at node 2 ---
;;
;; **Purpose:** Demonstrate the halt. Node 2 returns `:short-circuit? true`.
;; Node 3 never runs — its award is never applied and its effect is never
;; emitted — and the result reports `:execution/status :short-circuited`.

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def vm-b
  (build-vm
   "Short-circuit at node 2 — later nodes skipped"
   "Node 2 returns :short-circuit? true. Execution halts immediately: node 3 never runs, so the pipeline finishes with only 2 executed nodes and no effects."
   (fx/node :n2 [:economics/award-amount :fixture/stop] :spec {:amount 0})
   (fx/node :n3 [:economics/award-amount :fixture/effects]
            :spec {:amount 5 :effects [{:effect/contract :prf.effect/y.v1}]})))

(outcome-box vm-b)
(pipeline-viz vm-b)
(outcome-summary vm-b)
(scenario-details vm-b)

;; ## 2. Outcome Comparison
;;
;; The same plan, two behaviours. The short-circuit changes the observable
;; result in four places at once: status, executed node count, final value,
;; and emitted effects.

(clerk/table
 {:head ["Metric" "A — Normal" "B — Short-circuit"]
  :rows [["Status" (pr-str (:execution/status vm-a)) (pr-str (:execution/status vm-b))]
         ["Nodes executed" (str (count (:execution/nodes vm-a)) " / 3") (str (count (:execution/nodes vm-b)) " / 3")]
         ["Final value" (fmt-usd (:execution/value vm-a)) (fmt-usd (:execution/value vm-b))]
         ["Effects emitted" (pr-str (mapv :effect/contract (:execution/effects vm-a))) (pr-str (mapv :effect/contract (:execution/effects vm-b)))]]})

;; ## 3. Key Design Properties
;;
;; The halt is implemented in `execute-compiled-plan` (`src/resolver_sim/
;; composition/execution.clj`): each node is invoked through the frozen
;; extension-map, and when a node's result carries `:short-circuit?`, the
;; reduce short-circuits (`reduced`) so no later node is invoked.
;;
;;   • **Fail-fast, not rollback.** The node that fires the signal has already
;;     run — its amount and effects stand. Only the nodes *after* it are
;;     skipped.
;;
;;   • **Observable.** The status transitions to `:short-circuited` and the
;;     executed nodes stay recorded in `:execution/nodes`, so a short-circuit
;;     is distinguishable from a normal completion or a rejection.
;;
;;   • **Deterministic.** The pipeline order and node specs are fixed by the
;;     compiled plan; the signal is a runtime property of one node's result.
;;
;;   • **Tested.** `test/resolver_sim/composition/execution_test.clj`
;;     (`short-circuit-stops-pipeline`) asserts that later nodes do not
;;     execute after a short-circuit.
