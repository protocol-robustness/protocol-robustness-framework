(ns resolver-sim.composition.compiler
  "The composition compiler: a deterministic validator and compiler that
   resolves a requested combination against exact capability descriptors and
   produces either a structured rejection or a canonical compiled composition
   plan.

   v1 supports a strict sequential DAG (an ordered pipeline). Every unsupported
   shape is rejected explicitly with a machine-readable reason rather than
   leaving behaviour undefined. Execution consumes only the compiled plan."
  (:require [clojure.set :as set]
            [resolver-sim.composition.combination :as combo]
            [resolver-sim.composition.contract :as contract]
            [resolver-sim.composition.evidence-contract :as evidence-contract]
            [resolver-sim.composition.plan :as plan]))

(def compiler-id
  :resolver-sim.composition.compiler/sequential-v1)

(def compiler-version
  1)

;; ── graph helpers ─────────────────────────────────────────────────────────

(defn graph-has-cycle?
  "True when the directed edge graph contains a cycle (Kahn's algorithm)."
  [node-ids edges]
  (let [in-degree (reduce (fn [m e] (update m (:to e) (fnil inc 0))) {} edges)
        adj (reduce (fn [m e] (update m (:from e) (fnil conj []) (:to e))) {} edges)]
    (loop [queue (vec (remove #(pos? (get in-degree % 0)) node-ids))
           processed 0
           in in-degree]
      (if (empty? queue)
        (< processed (count node-ids))
        (let [n (peek queue)
              queue (pop queue)
              {:keys [in queue]}
              (reduce (fn [{:keys [in queue]} m]
                        (let [new-in (dec (get in m 0))]
                          {:in (assoc in m new-in)
                           :queue (if (zero? new-in) (conj queue m) queue)}))
                      {:in in :queue queue}
                      (adj n))]
          (recur queue (inc processed) in))))))

(defn unreachable-node-ids
  "Node ids with no incoming edge, excluding the first pipeline node."
  [node-ids edges]
  (let [has-in (into #{} (map :to) edges)]
    (seq (remove #(contains? has-in %) (rest node-ids)))))

(defn- custody-conflict-violations
  "Reject custody-affecting effect conflicts between any two nodes:

   - :exclusive-custody-account — a node claims exclusive custody of an
     account (:exclusive-accounts) that another node also touches (its
     :exclusive-accounts or :accounts);
   - :custody-direction — two nodes touch the same :accounts account with
     opposite concrete directions (:add vs :sub), which is order-sensitive."
  [plan-nodes]
  (let [default {:direction :either :accounts #{} :exclusive-accounts #{}}
        pairs (for [i (range (count plan-nodes))
                    j (range (inc i) (count plan-nodes))]
                [(nth plan-nodes i) (nth plan-nodes j)])]
    (into []
          (mapcat (fn [[a b]]
                    (let [ca (or (:custody a) default)
                          cb (or (:custody b) default)
                          a-id (:node/id a) b-id (:node/id b)
                          a-excl (:exclusive-accounts ca)
                          b-excl (:exclusive-accounts cb)
                          a-acc (:accounts ca) b-acc (:accounts cb)
                          exclusive-a (set/intersection a-excl (set/union b-excl b-acc))
                          exclusive-b (set/intersection b-excl (set/union a-excl a-acc))
                          shared-accounts (set/intersection a-acc b-acc)
                          direction-conflict? (and (seq shared-accounts)
                                                   (not= :either (:direction ca))
                                                   (not= :either (:direction cb))
                                                   (not= (:direction ca) (:direction cb)))]
                      (cond-> []
                        (seq exclusive-a)
                        (conj {:violation/id :violation/custody-effect-conflict
                               :details {:conflict-kind :exclusive-custody-account
                                         :nodes [a-id b-id]
                                         :accounts (vec exclusive-a)}})
                        (seq exclusive-b)
                        (conj {:violation/id :violation/custody-effect-conflict
                               :details {:conflict-kind :exclusive-custody-account
                                         :nodes [b-id a-id]
                                         :accounts (vec exclusive-b)}})
                        direction-conflict?
                        (conj {:violation/id :violation/custody-effect-conflict
                               :details {:conflict-kind :custody-direction
                                         :nodes [a-id b-id]
                                         :accounts (vec shared-accounts)
                                         :directions [(:direction ca) (:direction cb)]}})))))
          pairs)))

;; ── per-node compilation ──────────────────────────────────────────────────

(defn- compile-node
  "Resolve a combination node against the extension-map. Returns
   {:node <plan-node> :violations [...]}."
  [extension-map node]
  (let [ref (:capability/ref node)
        entry (get extension-map ref)
        cap (:capability entry)
        raw-contract (:composition-contract cap)]
    (if (nil? entry)
      {:violations [{:violation/id :violation/unresolved-capability
                     :details {:node/id (:node/id node) :capability/ref ref}}]}
      (let [cap-version (:capability/version cap)
            requested-version (:capability/version node)]
        (cond
          (and (not= :any requested-version)
               (not= requested-version cap-version))
          {:violations [{:violation/id :violation/capability-version-mismatch
                         :details {:node/id (:node/id node)
                                   :capability/ref ref
                                   :requested requested-version
                                   :resolved cap-version}}]}

          (nil? raw-contract)
          {:violations [{:violation/id :violation/missing-composition-contract
                         :details {:node/id (:node/id node)
                                   :capability/ref ref}}]}

          :else
          (let [{:keys [valid? violations]} (contract/validate-composition-contract raw-contract)]
            (if-not valid?
              {:violations [{:violation/id :violation/invalid-composition-contract
                             :details {:node/id (:node/id node)
                                       :contract-violations violations}}]}
              (let [c (contract/normalize-contract raw-contract)
                    in-semantic (get-in c [:composition/input :semantic-type])
                    out-semantic (get-in c [:composition/output :semantic-type])
                    modes (:composition/modes c)
                    determinism (:composition/determinism c)
                    control (:composition/control c)
                    effects (:composition/effects c)
                    verification (:composition/verification c)]
                (cond
                  (not (contains? modes :sequential))
                  {:violations [{:violation/id :violation/unsupported-composition-mode
                                 :details {:node/id (:node/id node)
                                           :modes (vec modes)
                                           :supported [:sequential]}}]}

                  (false? (:required? determinism))
                  {:violations [{:violation/id :violation/nondeterministic-capability-forbidden
                                 :details {:node/id (:node/id node)}}]}

                  :else
                  {:node {:node/id (:node/id node)
                          :capability-key ref
                          :capability-version cap-version
                          :capability-root (:descriptor-root entry)
                          :contract-root (contract/composition-contract-root c)
                          :input-semantic in-semantic
                          :output-semantic out-semantic
                          :terminal? (:terminal? control)
                          :may-short-circuit? (:may-short-circuit? control)
                          :failure-mode (:failure-mode control)
                          :merge-strategy (:merge-strategy effects)
                          :emits (:emits effects)
                          :exclusive-effects (:exclusive-effects effects)
                          :intermediate-output-committed? (:intermediate-output-committed? verification)
                          :spec (:spec node)
                          :basis (:basis node)
                          :addresses (:node/addresses node)
                          :custody (:composition/custody c)}
                   :violations []})))))))))

;; ── compiler ──────────────────────────────────────────────────────────────

(defn compile-combination
  "Compile a requested combination against an extension-map.

   evidence-contracts — an explicit evidence-contract registry (see
   resolver-sim.composition.evidence-contract) used to resolve the
   COMBINATION-LEVEL :combination/verification :evidence-contract-ref. A
   declared combination-level ref that cannot be resolved (missing,
   wrong-kind, or malformed entry) fails compilation; the resolved identity is
   committed into the plan as
   :plan/verification :evidence-contract {:id <ref> :root <committed-root>},
   so a later registry mutation cannot silently change what the plan meant.
   Per-node :composition/verification :evidence-contract-ref values are NOT
   resolved by this mechanism — they remain legacy/unresolved capability
   vocabulary pending the contract-obligation patch.

   Returns {:status :valid, :plan <compiled-plan>}
        or {:status :invalid, :violations [<structured rejections>]}.

   The compiled plan binds exact descriptor roots, exact composition-contract
   roots, canonical node order, canonical edges, graph input/output contracts,
   effect merge semantics, resolved verification (including the committed
   evidence contract), and compiler identity/version."
  ([extension-map combination]
   (compile-combination extension-map combination nil))
  ([extension-map combination evidence-contracts]
  (let [shape (combo/validate-combination combination)]
    (if-not (:valid? shape)
      {:status :invalid :violations (:violations shape)}
      (let [nodes (:combination/nodes combination [])
            node-ids (mapv :node/id nodes)
            edges (combo/effective-edges combination)
            compiled (mapv #(compile-node extension-map %) nodes)
            node-violations (into [] (mapcat :violations) compiled)]
        (if (seq node-violations)
          {:status :invalid :violations node-violations}
          (let [;; per-node addresses: a node's own override, else the
                ;; combination-level default
                plan-nodes (mapv (fn [n]
                                   (update n :addresses
                                           #(or % (:combination/addresses combination))))
                                 (mapv :node compiled))
                by-id (into {} (map (fn [n] [(:node/id n) n])) plan-nodes)
                ;; semantic edge checks over the consecutive chain
                edge-violations (into []
                                      (keep (fn [{:keys [from to]}]
                                              (let [src (by-id from)
                                                    tgt (by-id to)]
                                                (when-not (= (:output-semantic src)
                                                             (:input-semantic tgt))
                                                  {:violation/id :violation/input-output-semantic-mismatch
                                                   :details {:from from :to to
                                                             :source-output (:output-semantic src)
                                                             :target-input (:input-semantic tgt)}})))
                                            edges))
                ;; graph input / output contracts
                input-semantic (get-in combination [:combination/input :semantic-type])
                output-semantic (get-in combination [:combination/expected-output :semantic-type])
                first-node (first plan-nodes)
                last-node (last plan-nodes)
                input-violations (when-not (= input-semantic (:input-semantic first-node))
                                   [{:violation/id :violation/graph-input-not-satisfied
                                     :details {:expected input-semantic
                                               :first-node-input (:input-semantic first-node)}}])
                output-violations (when-not (= output-semantic (:output-semantic last-node))
                                    [{:violation/id :violation/graph-output-not-satisfied
                                      :details {:expected output-semantic
                                                :last-node-output (:output-semantic last-node)}}])
                ;; terminal placement: terminal nodes cannot have successors
                terminal-violations (into []
                                          (keep (fn [n]
                                                  (when (and (:terminal? n)
                                                             (some #(= (:from %) (:node/id n)) edges))
                                                    {:violation/id :violation/illegal-terminal-placement
                                                     :details {:node/id (:node/id n)}})))
                                          (butlast plan-nodes))
                ;; basis dependencies: a step/output basis must reference a prior node
                basis-violations (into []
                                       (keep-indexed (fn [i n]
                                                       (let [basis (:basis n)]
                                                         (when (and (map? basis)
                                                                    (= :step/output (:source basis))
                                                                    (:step-id basis)
                                                                    (not (contains? (set (take i node-ids))
                                                                                    (:step-id basis))))
                                                           {:violation/id :violation/undeclared-dependency
                                                            :details {:node/id (:node/id n)
                                                                      :references (:step-id basis)}})))
                                                     plan-nodes))
                ;; effect conflicts: an exclusive effect emitted by more than one node
                exclusive (reduce set/union #{} (map :exclusive-effects plan-nodes))
                emitted (into {} (map (fn [n] [(:node/id n) (:emits n)])) plan-nodes)
                emitted-set (reduce set/union #{} (vals emitted))
                effect-conflicts (when (seq (set/intersection exclusive emitted-set))
                                   [{:violation/id :violation/effect-conflict
                                     :details {:exclusive (vec exclusive)
                                               :emitted (vec (set/intersection exclusive emitted-set))}}])
                ;; failure-mode consensus: consecutive nodes must agree on how
                ;; the pipeline fails; otherwise one node's :abort stops a
                ;; :continue node before it runs, contradicting its contract
                failure-mode-conflicts (into []
                                             (mapcat (fn [[a b]]
                                                       (when (not= (:failure-mode a) (:failure-mode b))
                                                         [{:violation/id :violation/failure-mode-conflict
                                                           :details {:nodes [(:node/id a) (:node/id b)]
                                                                     :failure-modes
                                                                     [(:failure-mode a) (:failure-mode b)]}}]))
                                                     (partition 2 1 plan-nodes)))
                ;; custody-affecting effect conflicts (exclusive accounts and
                ;; opposite directions on shared custody accounts)
                custody-conflicts (custody-conflict-violations plan-nodes)
                ;; merge strategy: combination-level must agree with every node
                ;; contract; otherwise the plan could bind a strategy a node's
                ;; contract contradicts
                node-strategies (into #{} (map :merge-strategy) plan-nodes)
                declared-strategy (:combination/effect-merge-strategy combination)
                merge-conflicts (cond
                                  (and declared-strategy
                                       (some #(not= declared-strategy %) node-strategies))
                                  [{:violation/id :violation/effect-conflict
                                    :details {:declared-strategy declared-strategy
                                              :node-strategies (vec node-strategies)
                                              :reason "combination merge strategy conflicts with a node contract"}}]

                                  (and (nil? declared-strategy) (> (count node-strategies) 1))
                                  [{:violation/id :violation/effect-conflict
                                    :details {:merge-strategies (vec node-strategies)
                                              :reason "node merge strategies disagree"}}]

                                  :else [])
                ;; adapters: v1 rejects declared adapters explicitly
                adapter-violations (when (seq (:combination/adapters combination))
                                     [{:violation/id :violation/unsupported-adapters
                                       :details {:adapters (vec (:combination/adapters combination))}}])
                ;; verification contract preservation
                requires-intermediate? (some :intermediate-output-committed? plan-nodes)
                declared-verification (:combination/verification combination)
                verification-conflicts (when (and (some? declared-verification)
                                                  (false? (:intermediate-output-committed? declared-verification))
                                                  requires-intermediate?)
                                         [{:violation/id :violation/verification-contract-not-preserved
                                           :details {:combination-verification declared-verification
                                                     :required true}}])
                ;; evidence-contract resolution: a declared ref must resolve
                ;; against the explicit evidence-contract registry, or the
                ;; combination fails to compile (never silently dropped)
                evidence-ref (:evidence-contract-ref declared-verification)
                evidence-resolution (when (some? evidence-ref)
                                      (evidence-contract/resolve-ref evidence-contracts evidence-ref))
                evidence-violations (when (and (some? evidence-ref)
                                               (not (:resolved? evidence-resolution)))
                                      [{:violation/id (:violation/id evidence-resolution)
                                        :details {:evidence-contract-ref evidence-ref
                                                  :kind (:kind evidence-resolution)
                                                  :violations (:violations evidence-resolution)}}])
                ;; graph-wide structural checks (defensive; valid chains pass)
                cycle-violations (when (graph-has-cycle? node-ids edges)
                                   [{:violation/id :violation/cycle-detected
                                     :details {:node-ids node-ids}}])
                unreachable (unreachable-node-ids node-ids edges)
                unreachable-violations (when unreachable
                                         [{:violation/id :violation/unreachable-node
                                           :details {:unreachable (vec unreachable)}}])
                all-violations (into []
                                     (concat edge-violations
                                             input-violations
                                             output-violations
                                             terminal-violations
                                             basis-violations
                                             effect-conflicts
                                             custody-conflicts
                                             failure-mode-conflicts
                                             merge-conflicts
                                             adapter-violations
                                             verification-conflicts
                                             evidence-violations
                                             cycle-violations
                                             unreachable-violations))]
            (if (seq all-violations)
              {:status :invalid :violations all-violations}
              (let [effect-merge-strategy (or declared-strategy
                                              (first node-strategies)
                                              :accumulate)
                    plan-verification (cond-> {:intermediate-output-committed?
                                               (boolean (or requires-intermediate?
                                                            (:intermediate-output-committed? declared-verification)))}
                                       (some? evidence-ref)
                                       (assoc :evidence-contract
                                              (evidence-contract/committed-identity
                                               evidence-ref
                                               (:entry evidence-resolution))))
                    compiled-plan (plan/build-plan
                                   {:combination-root (combo/combination-root combination)
                                    :compiler-id compiler-id
                                    :compiler-version compiler-version
                                    :nodes plan-nodes
                                    :edges edges
                                    :addresses (:combination/addresses combination)
                                    :input-contract (:combination/input combination)
                                    :output-contract (:combination/expected-output combination)
                                    :effect-merge-strategy effect-merge-strategy
                                    :verification plan-verification})]
                {:status :valid :plan compiled-plan})))))))))
