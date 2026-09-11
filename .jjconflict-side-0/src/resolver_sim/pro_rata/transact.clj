(ns resolver-sim.pro-rata.transact
  "Optional ordered execution witnesses for an atomic canonical transition.

   Transient states exist only inside the trace. A failing operation throws and
   yields no trace/persistent post-state; the canonical transition remains the
   durable closed-form semantic authority."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.pro-rata.canonical-effects :as effects]))

(def transaction-schema "transact.v1")
(def trace-schema "trace-bounded-transition.v1")
(def binding-schema "transition-binding.v1")
(def binding-semantics-schema "transition-binding-semantics.v1")

(defn operation-root [operation]
  (hc/domain-hash :transact-operation
                  (select-keys operation [:schema-version :operation/type
                                          :quantity/root :delta])))

(defn operation [{:keys [quantity-root delta]}]
  (let [effect (effects/delta quantity-root delta)
        base {:schema-version "transact-operation.v1"
              :operation/type :delta
              :quantity/root (:quantity/root effect)
              :delta delta}]
    (assoc base :operation/root (operation-root base))))

(defn transaction-root [transaction]
  (hc/domain-hash :transact
                  (select-keys transaction [:schema-version :state-before/root
                                            :operations/root :operation-semantics/root
                                            :trace-policy/root])))

(defn build-transaction
  "Commit an intended ordered procedure, independent of the state it later
   executes against. The trace, not this object, commits execution endpoints."
  [{:keys [operations operation-semantics-root trace-policy-root]}]
  (let [ops (mapv operation operations)
        base {:schema-version transaction-schema
              :operations/root (hc/domain-hash :transact-operations ops)
              :operation-semantics/root operation-semantics-root
              :trace-policy/root trace-policy-root
              :operations ops}]
    (assoc base :transact/root (transaction-root base))))

(defn derive-bound
  "The bound is semantic: fixed overhead plus operations per canonical effect."
  [trace-policy canonical-transition]
  (+ (:max-fixed-steps trace-policy)
     (* (:max-steps-per-effect trace-policy)
        (count (:effects canonical-transition)))))

(defn trace-root [trace]
  (hc/domain-hash :trace-bounded-transition
                  (select-keys trace [:schema-version :transact/root
                                      :transition/input-root :transition/output-root :trace/root
                                      :trace/length :trace/max-length
                                      :operation-semantics/root :trace-policy/root])))

(defn execute
  "Execute ordered operations over canonical state and return transient witness
   steps. Operations are sequential here; unlike canonical effects, an
   intermediate underflow rejects the transaction even if a later op repairs it."
  [state-before transaction canonical-transition trace-policy]
  (let [max-length (derive-bound trace-policy canonical-transition)]
    (when-not (= (effects/state-root state-before) (:state-before/root canonical-transition))
      (throw (ex-info "transaction state does not match canonical transition before-state" {})))
    (when (> (count (:operations transaction)) max-length)
      (throw (ex-info "transaction exceeds derived trace bound" {})))
    (let [{:keys [state steps]}
          (reduce (fn [{:keys [state steps]} op]
                    (let [after (effects/apply-effects state
                                                       [(effects/delta (:quantity/root op) (:delta op))])]
                      {:state after
                       :steps (conj steps {:step/index (count steps)
                                           :operation/root (:operation/root op)
                                           :state-after/root (effects/state-root after)})}))
                  {:state state-before :steps []}
                  (:operations transaction))
          trace-body {:schema-version trace-schema
                      :transact/root (:transact/root transaction)
                      :transition/input-root (effects/state-root state-before)
                      :transition/output-root (effects/state-root state)
                      :trace/steps steps}
          trace-witness-root (hc/domain-hash :transact-trace trace-body)
          base (assoc trace-body
                      :trace/root trace-witness-root
                      :trace/length (count steps)
                      :trace/max-length max-length
                      :operation-semantics/root (:operation-semantics/root transaction)
                      :trace-policy/root (:trace-policy/root transaction))]
      (assoc base :trace-bounded-transition/root (trace-root base)))))

(defn operation-footprint-root [operations]
  (hc/domain-hash :transact-operation-footprint
                  {:schema-version "transact-operation-footprint.v1"
                   :quantities (vec (sort (distinct (map :quantity/root operations))))}))

(defn binding-semantics-root [semantics]
  (hc/domain-hash :transition-binding-semantics
                  (select-keys semantics [:schema-version :binding/mode
                                          :endpoint-equality :normalized-effect-equality
                                          :footprint-rule :sequence-rule])))

(defn build-binding-semantics
  "Freeze the assertion strength rather than relying on an unstructured
   caller-supplied semantics root. `:effect-exact` is target/delta exact but
   deliberately permits valid per-target operation decomposition."
  [mode]
  (let [base {:schema-version binding-semantics-schema
              :binding/mode mode
              :endpoint-equality :required
              :normalized-effect-equality :required
              :footprint-rule (if (= mode :effect-exact) :equal :unconstrained)
              :sequence-rule :not-exact}]
    (assoc base :binding-semantics/root (binding-semantics-root base))))

(defn binding-root [binding]
  (hc/domain-hash :transition-binding
                  (select-keys binding [:schema-version :binding/mode
                                        :canonical-transition/root
                                        :trace-bounded-transition/root
                                        :operation-footprint/root
                                        :projected-trace-before/root
                                        :projected-trace-after/root
                                        :binding-semantics/root])))

(defn bind-transition
  "Bind a successful trace to a canonical transition. `:effect-exact` (the
   default) forbids touched quantities outside the canonical effect footprint;
   `:net-equivalent` permits transient quantitative excursions deliberately."
  ([canonical-transition transaction trace binding-semantics-root]
   (bind-transition canonical-transition transaction trace binding-semantics-root :effect-exact))
  ([canonical-transition transaction trace binding-semantics-root mode]
   (let [semantics (build-binding-semantics mode)
         _ (when-not (= binding-semantics-root (:binding-semantics/root semantics))
             (throw (ex-info "binding semantics root does not match binding mode"
                             {:binding/mode mode})))
         normalized (effects/normalize-effects
                     (mapv #(effects/delta (:quantity/root %) (:delta %))
                           (:operations transaction)))
         operation-targets (set (map :quantity/root (:operations transaction)))
         canonical-targets (set (map :quantity/root (:effects canonical-transition)))
         exact? (= operation-targets canonical-targets)]
     (when-not (and (contains? #{:net-equivalent :effect-exact} mode)
                    (= (:state-before/root canonical-transition) (:transition/input-root trace))
                    (= (:state-after/root canonical-transition) (:transition/output-root trace))
                    (= (:effects canonical-transition) normalized)
                    (or (= mode :net-equivalent) exact?))
       (throw (ex-info "transaction trace does not realize canonical transition"
                       {:binding/mode mode :operation-targets operation-targets
                        :canonical-targets canonical-targets})))
     (let [base {:schema-version binding-schema
                 :binding/mode mode
                 :canonical-transition/root (:canonical-effect-transition/root canonical-transition)
                 :trace-bounded-transition/root (:trace-bounded-transition/root trace)
                 :operation-footprint/root (operation-footprint-root (:operations transaction))
                 :projected-trace-before/root (:transition/input-root trace)
                 :projected-trace-after/root (:transition/output-root trace)
                 :binding-semantics/root (:binding-semantics/root semantics)}]
       (assoc base :transition-binding/root (binding-root base))))))
