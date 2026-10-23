(ns resolver-sim.composition.execution
  "Execution of compiled composition plans.

   Execution consumes ONLY a compiled plan. A raw requested combination is
   rejected; to execute one, it must first be compiled (execute-combination
   proves this by compiling before executing). Every node's capability is
   re-resolved against the extension-map and its descriptor root must match
   the plan's committed root before invocation.

   v1 value semantics: a running value flows through the sequential pipeline.
   Each node consumes it (value' = value − amount) unless the node is
   terminal, in which case the node's output is the final value. Effects and
   results are accepted only if every node succeeds. Entry points are required
   to be deterministic calculations explicitly declared :pure-calculation
   in their content-addressed capability descriptor. Returned effects are
   staged data. v1 has no rollback or atomic commit facility, so irreversible
   consumers are rejected."
  (:require [resolver-sim.composition.compiler :as compiler]
            [resolver-sim.composition.contract :as composition-contract]
            [resolver-sim.composition.plan :as plan]
            [resolver-sim.extensions.manifest :as manifest]
            [resolver-sim.extensions.execution :as ext-exec]))

(defn- reject
  [violation-id details]
  {:execution/status :rejected
   :execution/violations [{:violation/id violation-id :details details}]})

(defn- failed
  [plan node observations failure-type details]
  {:execution/status :failed
   :execution/plan-root (:plan/root plan)
   :execution/compiler-id (:plan/compiler-id plan)
   :execution/compiler-version (:plan/compiler-version plan)
   :execution/failure {:failure/type failure-type
                       :failure/node-id (:node/id node)
                       :failure/details details}
   ;; Observations are diagnostic only; they are never an accepted result or
   ;; effect set. In particular the candidate value/effects are discarded.
   :execution/observations (vec observations)
   :execution/value nil
   :execution/effects []
   :execution/nodes []})

(defn- result-failure
  [node result]
  (cond
    (nil? result)
    {:failure/type :execution-failure/invalid-result :failure/details {:reason :nil-result}}

    (not (map? result))
    {:failure/type :execution-failure/invalid-result
     :failure/details {:reason :result-not-map :result-type (.getName (class result))}}

    (contains? result :failure)
    {:failure/type :execution-failure/precondition-failed
     :failure/details {:evidence (:failure result)}}

    (not (number? (:amount result)))
    {:failure/type :execution-failure/invalid-result
     :failure/details {:reason :missing-or-nonnumeric-amount :amount (:amount result)}}

    (and (:terminal? node) (contains? result :value) (not (number? (:value result))))
    {:failure/type :execution-failure/invalid-result
     :failure/details {:reason :terminal-value-not-numeric :value (:value result)}}

    (not (vector? (or (:effects result) [])))
    {:failure/type :execution-failure/invalid-result
     :failure/details {:reason :effects-not-vector :effects (:effects result)}}

    (some (fn [effect]
            (or (not (map? effect))
                (not (contains? (:emits node) (:effect/contract effect)))))
          (or (:effects result) []))
    {:failure/type :execution-failure/undeclared-effect
     :failure/details {:declared (:emits node) :effects (:effects result)}}

    :else nil))

(def ^:private plan-keys
  #{:plan/schema-version :plan/combination-root :plan/compiler-id
    :plan/compiler-version :plan/nodes :plan/edges :plan/addresses
    :plan/input-contract :plan/output-contract :plan/effect-merge-strategy
    :plan/verification :plan/root})

(def ^:private node-keys
  #{:node/id :capability-key :capability-version :capability-root :entrypoint-safety
    :contract-root :input-semantic :output-semantic :terminal?
    :may-short-circuit? :failure-mode :merge-strategy :emits
    :exclusive-effects :intermediate-output-committed? :spec :basis
    :addresses :custody})

(def ^:private edge-keys #{:edge/id :from :to :value-semantic})

(defn- closed-map? [value expected-keys]
  (and (map? value) (= expected-keys (set (keys value)))))

(defn- valid-root? [root]
  (and (string? root) (re-matches #"[0-9a-f]{64}" root)))

(defn- authenticated-plan? [candidate]
  (try
    (and (closed-map? candidate plan-keys)
         (= plan/plan-schema-version (:plan/schema-version candidate))
         (= compiler/compiler-id (:plan/compiler-id candidate))
         (= compiler/compiler-version (:plan/compiler-version candidate))
         (valid-root? (:plan/root candidate))
         (string? (:plan/combination-root candidate))
         (vector? (:plan/nodes candidate))
         (every? #(closed-map? % node-keys) (:plan/nodes candidate))
         (vector? (:plan/edges candidate))
         (every? #(closed-map? % edge-keys) (:plan/edges candidate))
         (map? (:plan/addresses candidate))
         (map? (:plan/input-contract candidate))
         (map? (:plan/output-contract candidate))
         (keyword? (:plan/effect-merge-strategy candidate))
         (map? (:plan/verification candidate))
         (string? (:plan/root candidate))
         (= (:plan/root candidate) (plan/plan-root candidate)))
    (catch Throwable _ false)))

(defn- merge-plan-violation [compiled-plan]
  (let [strategy (:plan/effect-merge-strategy compiled-plan)
        node-strategies (set (map :merge-strategy (:plan/nodes compiled-plan)))]
    (cond
      (not (contains? composition-contract/supported-merge-strategies strategy))
      {:violation/id :violation/unsupported-effect-merge-strategy
       :details {:merge-strategy strategy}}
      (not= node-strategies #{strategy})
      {:violation/id :violation/effect-merge-strategy-mismatch
       :details {:plan-strategy strategy :node-strategies node-strategies}}
      :else nil)))

(defn- merge-node-effects [strategy accumulated node-effects node-id]
  (case strategy
    :accumulate {:effects (into accumulated node-effects)}
    :replace {:effects (vec node-effects)}
    :partition
    (loop [remaining (seq node-effects)
           current accumulated
           seen (set (keep :effect/partition-key accumulated))]
      (if-let [effect (first remaining)]
        (let [key (when (map? effect) (:effect/partition-key effect))]
          (cond
            (nil? key) {:violation/id :violation/missing-effect-partition-key
                        :details {:node/id node-id :effect effect}}
            (contains? seen key)
            {:violation/id :violation/duplicate-effect-partition-key
             :details {:node/id node-id :effect/partition-key key}}
            :else (recur (next remaining) (conj current effect) (conj seen key))))
        {:effects current}))
    {:violation/id :violation/unsupported-effect-merge-strategy
     :details {:merge-strategy strategy}}))

(defn execute-compiled-plan
  "Execute a compiled composition plan against an extension-map.

   Args:
     plan          — a compiled plan artifact (must carry :plan/root)
     extension-map — the frozen capability registry
     params        — parameter values passed into each invocation
     input-value   — the pipeline input value

   Returns
     {:execution/status :completed|:short-circuited|:failed
      :execution/plan-root ...
      :execution/compiler-id ...
      :execution/compiler-version ...
      :execution/value ...
      :execution/effects [...]
      :execution/nodes [...]}
   or {:execution/status :rejected :execution/violations [...]}."
  [plan extension-map params input-value]
  (if (not (and (map? plan) (some? (:plan/root plan))))
    (reject :violation/uncompiled-combination
            {:reason "execution requires a compiled plan, not a raw combination"})
    (if-not (authenticated-plan? plan)
      (reject :violation/unauthenticated-plan
              {:reason "plan envelope, compiler identity, schema, or commitment is invalid"})
      (let [nodes (:plan/nodes plan [])
            unsafe (filter #(not= :pure-calculation (:entrypoint-safety %)) nodes)
            unsupported-modes (filter #(not= :abort (:failure-mode %)) nodes)
          mismatches (keep (fn [n]
                             (let [entry (get extension-map (:capability-key n))]
                               (when (or (nil? entry)
                                         (not= (:descriptor-root entry)
                                               (:capability-root n))
                                         (not= :pure-calculation
                                               (composition-contract/execution-safety
                                                (:capability entry) entry))
                                         (not= (:entrypoint-safety n)
                                               (composition-contract/execution-safety
                                                (:capability entry) entry))
                                         (try
                                           (let [cap (:capability entry)
                                                 raw-contract (:composition-contract cap)
                                                 validation (composition-contract/validate-composition-contract
                                                             raw-contract)
                                                 safety (composition-contract/execution-safety cap entry)
                                                 committed-contract
                                                 (cond-> (composition-contract/normalize-contract
                                                          raw-contract)
                                                   safety (assoc :composition/execution-safety safety))]
                                             (or (not (map? cap))
                                                 (not= (:descriptor-root entry)
                                                       (manifest/capability-descriptor-root cap))
                                                 (not (:valid? validation))
                                                 (not= (:contract-root n)
                                                       (composition-contract/composition-contract-root
                                                        committed-contract))))
                                           (catch Throwable _ true)))
                                 {:node/id (:node/id n)
                                  :expected-root (:capability-root n)
                                  :resolved-root (:descriptor-root entry)})))
                           nodes)
            merge-violation (merge-plan-violation plan)]
      (if (or merge-violation (seq unsafe))
        (if merge-violation
          {:execution/status :rejected :execution/violations [merge-violation]}
          (reject :violation/unsafe-entrypoint
                  {:nodes (mapv :node/id unsafe) :required :pure-calculation}))
      (if (seq unsupported-modes)
        (reject :violation/unsupported-failure-mode
                {:nodes (mapv :node/id unsupported-modes) :supported #{:abort}})
      (if (seq mismatches)
        (reject :violation/descriptor-root-mismatch {:mismatches mismatches})
        (if-not (number? input-value)
          (failed plan (first nodes) [] :execution-failure/invalid-input
                  {:reason :pipeline-input-not-numeric
                   :input-type (some-> input-value class .getName)})
        (let [initial {:value input-value :effects [] :nodes [] :status :running}
              outcome
              (reduce (fn [acc node]
                        (if (or (:failure acc) (:rejection acc) (:short-circuited? acc))
                          (reduced acc)
                          (let [entry (get extension-map (:capability-key node))]
                            (try
                              (let [f (try (ext-exec/resolve-entrypoint entry)
                                           (catch Throwable t t))]
                                (cond
                                  (nil? f)
                                  (assoc acc :failed-node node
                                         :failure {:failure/type :execution-failure/missing-entrypoint
                                                   :failure/details {:entrypoint (get-in entry [:capability :entrypoint])}})

                                  (instance? Throwable f)
                                  (assoc acc :failed-node node
                                         :failure {:failure/type :execution-failure/invocation-exception
                                                   :failure/details {:exception-class (.getName (class f))}})

                                  :else
                                  (let [result (try
                                                 (f {:value (:value acc)
                                                     :spec (:spec node)
                                                     :params params
                                                     :addresses (:addresses node)
                                                     :node/id (:node/id node)})
                                                 (catch Throwable t t))]
                                    (cond
                                      (instance? Throwable result)
                                      (assoc acc :failed-node node
                                             :failure {:failure/type :execution-failure/invocation-exception
                                                       :failure/details {:exception-class (.getName (class result))}})

                                      (result-failure node result)
                                      (assoc acc :failed-node node
                                             :failure (result-failure node result))

                                      :else
                                      (let [amount (:amount result)
                                            node-effects (or (:effects result) [])
                                            merged-effects (merge-node-effects
                                                            (:plan/effect-merge-strategy plan)
                                                            (:effects acc) node-effects (:node/id node))
                                            value-out (if (:terminal? node)
                                                        (if (contains? result :value) (:value result) amount)
                                                        (- (:value acc) amount))
                                            evidence {:node/id (:node/id node)
                                                      :input (:value acc)
                                                      :amount amount
                                                      :output value-out
                                                      :effects node-effects}
                                            acc' (-> acc
                                                     (assoc :effects (:effects merged-effects))
                                                     (update :nodes conj evidence)
                                                     (update :observations conj evidence)
                                                     (assoc :value value-out))]
                                        (if (:violation/id merged-effects)
                                          (assoc acc :failed-node node
                                                 :failure {:failure/type :execution-failure/invalid-effects
                                                           :failure/details merged-effects})
                                        (if (and (:short-circuit? result)
                                                 (:may-short-circuit? node))
                                          (assoc acc' :short-circuited? true)
                                          (if (:short-circuit? result)
                                            (assoc acc' :rejection
                                                   {:violation/id :violation/unauthorized-short-circuit
                                                    :details {:node/id (:node/id node)}})
                                            acc'))))))))
                              (catch Throwable t
                                (assoc acc :failed-node node
                                       :failure {:failure/type :execution-failure/evaluation-exception
                                                 :failure/details {:exception-class (.getName (class t))}}))))))
                      (assoc initial :observations [])
                      nodes)]
          (cond
            (:rejection outcome)
            (reject (get-in outcome [:rejection :violation/id])
                    (get-in outcome [:rejection :details]))

            (:failure outcome)
            (let [failure (:failure outcome)]
              (assoc (failed plan (:failed-node outcome) (:observations outcome)
                             (:failure/type failure) (:failure/details failure))
                     :execution/failure-node-id (:node/id (:failed-node outcome))))

            :else
            {:execution/status (if (:short-circuited? outcome) :short-circuited :completed)
             :execution/plan-root (:plan/root plan)
             :execution/compiler-id (:plan/compiler-id plan)
             :execution/compiler-version (:plan/compiler-version plan)
             :execution/value (:value outcome)
             :execution/effects (vec (:effects outcome))
             :execution/nodes (vec (:nodes outcome))}))))))))))

(defn execute-combination
  "Compile a combination against a compilation context, then execute the
   compiled plan. This is the only path that runs a raw combination and it
   proves an equivalent plan was compiled first.

   context — {:extensions <extension-map>
              :evidence-contracts <optional evidence-contract registry>
              :obligations <optional obligation definitions registry>}

   Returns {:execution/status :rejected ...}
        or {:execution/status :completed|:short-circuited ...}
   and also attaches :execution/compiled-plan on success."
  [context combination params input-value]
  (let [{:keys [status plan violations]}
        (compiler/compile-combination context combination)]
    (if (= :invalid status)
      {:execution/status :rejected
       :execution/violations violations}
      (assoc (execute-compiled-plan plan (:extensions context) params input-value)
             :execution/compiled-plan plan))))
