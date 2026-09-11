^{:nextjournal.clerk/visibility {:code :show :result :show}}
(ns resolver-sim.notebook-support.subgame-counterfactual-workbench
  "Workbench for exploring expand-strategic-tree — the dynamic tree expansion
   phase of the subgame perfect equilibrium (SPE) counterfactual evaluator."
  (:require [resolver-sim.io.scenarios :as io-sc]
            [resolver-sim.scenario.normalize :as normalize]
            [resolver-sim.contract-model.replay :as replay]
            [resolver-sim.protocols.protocol :as proto]
            [resolver-sim.observability.available-actions :as available-actions]
            [resolver-sim.hash.canonical :as canonical]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.protocols.registry :as preg]
            [resolver-sim.scenario.subgame-counterfactual :as spe]
            [resolver-sim.notebook-support.nav :as nav]))

(nav/top-nav-bar "notebooks/subgame_counterfactual_workbench.clj")

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
[:div {:class "workbench-container"}
 [:div {:class "wb-hero"}
  [:h1 "expand-strategic-tree Workbench"]
  [:p "Dynamic tree expansion from replay decision nodes. "
   "At each strategic decision point, the workbench queries the protocol "
   "for available actions, forks the simulation for each alternative, "
   "and compares terminal utilities against the chosen action."]]
 [:div {:style {:display "flex" :gap "12px" :alignItems "center"
                :marginBottom "24px" :padding "12px 16px" :background "#0f172a"
                :borderRadius "4px" :border "1px solid #004D59"
                :fontSize "0.85em" :color "#94a3b8"}}
  [:span {:style {:fontWeight "700" :color "#7ADDDC" :marginRight "4px"}} "Phase 2"]
  "expand-strategic-tree · "
  [:span {:style {:color "#64748b"}} "scenario/subgame_counterfactual.clj"]]]

;; ──────────────────────────────────────────────────────────────────────────────
;;  State & initialization
;; ──────────────────────────────────────────────────────────────────────────────

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(defonce state (atom {:scenario nil :result nil :trace nil :protocol nil}))

(def ^:private default-spe-config
  {:enable-tree-expansion? true
   :utility-spec {:type :terminal-realized-v1 :version "v1"}})

(defn init!
  "Load a scenario, replay with full checkpoint retention, and store trace state."
  [path]
  (let [p  (preg/get-protocol "sew-v1")
        sc (normalize/normalize-scenario (io-sc/load-scenario-file path))
        r  (replay/replay-with-protocol p sc
                                        {:flags {:world-checkpoint-policy :retain-all}})]
    (swap! state assoc
           :protocol p
           :scenario sc
           :result r
           :trace (:trace r)
           :world-checkpoints (:world-checkpoints r))
    :loaded))

(defn s
  ([k] (get @state k))
  ([k & ks] (get-in @state (into [k] ks))))

(defn load-and-replay [path] (init! path) (s :result))

;; ──────────────────────────────────────────────────────────────────────────────
;;  Replay summary
;; ──────────────────────────────────────────────────────────────────────────────

(def ^:private strategic-actions
  #{"raise_dispute" "escalate_dispute" "execute_resolution"})

(defn- fork-world-for-node
  "Replay-complete world immediately before `node` executes.
   Matches `fork-world-at-seq` in subgame_counterfactual (checkpoint first,
   lean trace snapshot fallback)."
  [node trace world-checkpoints]
  (let [seq-n (long (:seq node))
        pre-entry (when (pos? seq-n) (nth trace (dec seq-n) nil))]
    (or (get world-checkpoints seq-n)
        (:world pre-entry))))

(defn replay-summary
  ([]
   (let [result (s :result)
         trace  (s :trace)]
     {:scenario-id (:scenario-id result)
      :outcome (:outcome result)
      :events-processed (:events-processed result)
      :trace-length (count trace)
      :checkpoint-count (count (s :world-checkpoints))
      :strategic-nodes (count (filter #(contains? strategic-actions (:action %)) trace))})))

(defn decision-nodes
  "Strategic decision points with fork worlds from :world-checkpoints."
  ([]
   (decision-nodes (s :trace) (s :world-checkpoints)))
  ([trace world-checkpoints]
   (let [checkpoints (or world-checkpoints {})]
     (vec
      (keep-indexed
       (fn [i entry]
         (when (contains? strategic-actions (:action entry))
           (let [seq-n     (:seq entry)
                 pre-entry (when (pos? i) (nth trace (dec i) nil))]
             {:seq seq-n
              :action (:action entry)
              :agent (:agent entry)
              :time (:time entry)
              :result (:result entry)
              :error (:error entry)
              :pre-entry pre-entry
              :fork-world (fork-world-for-node {:seq seq-n} trace checkpoints)
              :chosen-entry entry})))
       trace)))))

;; ──────────────────────────────────────────────────────────────────────────────
;;  Expand tree at a node
;; ──────────────────────────────────────────────────────────────────────────────

(defn expand-at-node
  "Fork from checkpoint world and replay the raw main-line tail.
   Normalization and seq renumbering happen inside expand-strategic-tree."
  ([node] (expand-at-node node default-spe-config))
  ([node spe-config]
   (let [trace       (s :trace)
         checkpoints (or (s :world-checkpoints) {})
         idx         (long (:seq node))
         remaining   (drop (inc idx) trace)
         fork-world  (or (:fork-world node)
                         (fork-world-for-node node trace checkpoints))
         actor       (:agent node)
         scenario    (s :scenario)]
     (cond
       (nil? fork-world)
       (do (println "Cannot expand: no fork world at seq" idx
                    "(checkpoint missing and no trace fallback)")
           nil)

       (empty? remaining)
       (do (println "Cannot expand: no continuation tail after seq" idx)
           nil)

       :else
       (spe/expand-strategic-tree
        (s :protocol)
        (:agents scenario)
        (:protocol-params scenario)
        (:scenario-id scenario)
        fork-world
        actor
        remaining
        (merge default-spe-config spe-config))))))

(defn expand-all-nodes
  ([]
   (expand-all-nodes (decision-nodes)))
  ([nodes]
   (vec (for [node nodes
              :let [branches (expand-at-node node)]
              :when (seq branches)]
          {:node node :branches branches :branch-count (count branches)}))))

;; ──────────────────────────────────────────────────────────────────────────────
;;  Utility comparison
;; ──────────────────────────────────────────────────────────────────────────────

(defn utility-table
  [branches actor & [{:keys [utility-spec] :or {utility-spec {:type :terminal-realized-v1 :version "v1"}}}]]
  (->> branches
       (mapv (fn [branch]
               (let [terminal (:terminal-world branch)
                     stale?   (some :fork/stale-continuation (:trace branch))]
                 {:action (:action (:action branch))
                  :outcome (:outcome branch)
                  :halt-reason (:halt-reason branch)
                  :stale-continuation? stale?
                  :terminal-state (some? terminal)
                  :utility (when terminal (:value (spe/compute-utility terminal actor utility-spec)))})))
       (sort-by #(if (:utility %) (- ##Inf) (or (:utility %) ##Inf)))))

(defn available-actions-at
  [node]
  (let [trace       (s :trace)
        checkpoints (or (s :world-checkpoints) {})
        fork-world  (or (:fork-world node)
                        (fork-world-for-node node trace checkpoints))]
    (when fork-world
      (let [protocol (s :protocol)
            snapshot (proto/world-snapshot protocol fork-world)
            state-root (hash-ref/sha256-ref
                        (canonical/domain-hash :protocol-state snapshot))]
        (available-actions/observe protocol fork-world state-root (:agent node))))))

;; ──────────────────────────────────────────────────────────────────────────────
;;  Use in Clerk
;; ──────────────────────────────────────────────────────────────────────────────

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
[:div {:class "card" :style {:marginTop "32px"}}
 [:div {:class "card-title"} "Caveats"]
 [:ul {:style {:color "#94a3b8" :fontSize "0.85em" :lineHeight "1.7" :margin "0" :paddingLeft "20px"}}
  [:li "Replay uses " [:code {:style {:color "#7ADDDC"}} ":world-checkpoint-policy :retain-all"]
   " so fork worlds match SPE tooling."]
  [:li "Fork world comes from " [:code {:style {:color "#7ADDDC"}} ":world-checkpoints"]
   " at the decision seq (not lean trace snapshots)."]
  [:li "Continuation tail is passed raw; "
   [:code {:style {:color "#7ADDDC"}} "expand-strategic-tree"]
   " normalizes via " [:code {:style {:color "#7ADDDC"}} "continuation-replay-events"]
   " and renumbers seqs."]
  [:li [:strong {:style {:color "#fbbf24"}} "Expense"] " — expanding every node "
   "can take minutes."]
  [:li "Stale continuations are tagged " [:code {:style {:color "#7ADDDC"}} ":fork/stale-continuation"]
   " in branch traces; check " [:code {:style {:color "#7ADDDC"}} ":stale-continuation?"]
   " in " [:code {:style {:color "#7ADDDC"}} "utility-table"]
   " output."]
  [:li "Tree expansion supports: "
   "raise_dispute, escalate_dispute, execute_resolution."]]]
