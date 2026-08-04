(ns resolver-sim.composition.execution
  "Execution of compiled composition plans.

   Execution consumes ONLY a compiled plan. A raw requested combination is
   rejected; to execute one, it must first be compiled (execute-combination
   proves this by compiling before executing). Every node's capability is
   re-resolved against the extension-map and its descriptor root must match
   the plan's committed root before invocation.

   v1 value semantics: a running value flows through the sequential pipeline.
   Each node consumes it (value' = value − amount) unless the node is
   terminal, in which case the node's output is the final value. Effects are
   accumulated from each node result."
  (:require [resolver-sim.composition.compiler :as compiler]
            [resolver-sim.extensions.execution :as ext-exec]))

(defn- reject
  [violation-id details]
  {:execution/status :rejected
   :execution/violations [{:violation/id violation-id :details details}]})

(defn execute-compiled-plan
  "Execute a compiled composition plan against an extension-map.

   Args:
     plan          — a compiled plan artifact (must carry :plan/root)
     extension-map — the frozen capability registry
     params        — parameter values passed into each invocation
     input-value   — the pipeline input value

   Returns
     {:execution/status :completed|:short-circuited
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
    (let [nodes (:plan/nodes plan [])
          mismatches (keep (fn [n]
                             (let [entry (get extension-map (:capability-key n))]
                               (when (or (nil? entry)
                                         (not= (:descriptor-root entry)
                                               (:capability-root n)))
                                 {:node/id (:node/id n)
                                  :expected-root (:capability-root n)
                                  :resolved-root (:descriptor-root entry)})))
                           nodes)]
      (if (seq mismatches)
        (reject :violation/descriptor-root-mismatch {:mismatches mismatches})
        (let [initial {:value input-value :effects [] :nodes [] :status :running}
              {:keys [value effects nodes status]}
              (reduce (fn [acc node]
                        (if (= :short-circuited (:status acc))
                          (reduced acc)
                          (let [entry (get extension-map (:capability-key node))
                                result (ext-exec/invoke-capability
                                        entry
                                        {:value (:value acc)
                                         :spec (:spec node)
                                         :params params
                                         :node/id (:node/id node)})
                                amount (:amount result)
                                node-effects (or (:effects result) [])
                                value-out (if (:terminal? node)
                                            (or (:value result) amount (:value acc))
                                            (- (:value acc) (or amount 0)))
                                node-evidence {:node/id (:node/id node)
                                               :input (:value acc)
                                               :amount amount
                                               :output value-out
                                               :effects node-effects}]
                            (-> acc
                                (update :effects into node-effects)
                                (update :nodes conj node-evidence)
                                (assoc :value value-out)
                                (assoc :status (if (:short-circuit? result)
                                                 :short-circuited
                                                 :running))))))
                      initial
                      nodes)
              final-status (if (= :short-circuited status) :short-circuited :completed)]
          {:execution/status final-status
           :execution/plan-root (:plan/root plan)
           :execution/compiler-id (:plan/compiler-id plan)
           :execution/compiler-version (:plan/compiler-version plan)
           :execution/value value
           :execution/effects (vec effects)
           :execution/nodes (vec nodes)})))))

(defn execute-combination
  "Compile a combination, then execute the compiled plan. This is the only
   path that runs a raw combination and it proves an equivalent plan was
   compiled first.

   Returns {:execution/status :rejected ...}
        or {:execution/status :completed|:short-circuited ...}
   and also attaches :execution/compiled-plan on success."
  [extension-map combination params input-value]
  (let [{:keys [status plan violations]}
        (compiler/compile-combination extension-map combination)]
    (if (= :invalid status)
      {:execution/status :rejected
       :execution/violations violations}
      (assoc (execute-compiled-plan plan extension-map params input-value)
             :execution/compiled-plan plan))))
