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
            [resolver-sim.composition.plan :as plan]
            [resolver-sim.extensions.manifest :as manifest]
            [resolver-sim.extensions.execution :as ext-exec]))

(defn- reject
  [violation-id details]
  {:execution/status :rejected
   :execution/violations [{:violation/id violation-id :details details}]})

(def ^:private plan-keys
  #{:plan/schema-version :plan/combination-root :plan/compiler-id
    :plan/compiler-version :plan/nodes :plan/edges :plan/addresses
    :plan/input-contract :plan/output-contract :plan/effect-merge-strategy
    :plan/verification :plan/root})

(def ^:private node-keys
  #{:node/id :capability-key :capability-version :capability-root
    :contract-root :input-semantic :output-semantic :terminal?
    :may-short-circuit? :failure-mode :merge-strategy :emits
    :exclusive-effects :intermediate-output-committed? :spec :basis
    :addresses :custody})

(def ^:private edge-keys #{:edge/id :from :to :value-semantic})

(defn- closed-map?
  [value expected-keys]
  (and (map? value) (= expected-keys (set (keys value)))))

(defn- valid-root?
  [root]
  (and (string? root) (re-matches #"[0-9a-f]{64}" root)))

(defn- authenticated-plan?
  [candidate]
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
         (= (:plan/root candidate) (plan/plan-root candidate)))
    (catch Throwable _ false)))

(defn execute-compiled-plan
  "Execute an authenticated compiled composition plan against an extension-map.

   The execution-authoritative v1 payload is the closed plan envelope: schema
   version, combination root, compiler identity/version, ordered nodes and
   edges, addresses, input/output contracts, merge strategy, and verification.
   Each node commits its spec, basis, capability/contract identity, addresses,
   and execution/failure controls. The root is recomputed from the fixed plan
   projection before any capability is resolved or invoked. The extension
   registry's descriptor body is separately checked against both its stored
   descriptor root and the node's committed capability root. Other source or
   build identities outside this envelope are advisory and do not authorize
   execution.

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
    (if-not (authenticated-plan? plan)
      (reject :violation/unauthenticated-plan
              {:reason "plan envelope, compiler identity, schema, or commitment is invalid"})
      (let [nodes (:plan/nodes plan [])
            mismatches (keep (fn [n]
                               (let [entry (get extension-map (:capability-key n))]
                                 (when (or (nil? entry)
                                           (not= (:descriptor-root entry)
                                                 (:capability-root n))
                                           (try
                                             (or (not (map? (:capability entry)))
                                                 (not= (:descriptor-root entry)
                                                       (manifest/capability-descriptor-root
                                                        (:capability entry))))
                                             (catch Throwable _ true)))
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
                                           :addresses (:addresses node)
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
             :execution/nodes (vec nodes)}))))))

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
