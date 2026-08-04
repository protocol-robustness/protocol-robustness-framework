(ns resolver-sim.composition.fixtures
  "Composition framework test fixtures: capability builders, an extension-map
   seeded with core capabilities, and executable fixture entrypoints."
  (:require [resolver-sim.economics.slash-distribution :as sd]
            [resolver-sim.extensions.registry :as reg]))

;; ── executable fixture entrypoints ────────────────────────────────────────

(defn identity-award
  "Deterministic amount from a :spec {:rate <bps>}: value * rate / 10000."
  [{:keys [value spec]}]
  {:amount (quot (* value (:rate spec)) 10000)
   :effects []})

(defn effect-award
  "Returns a fixed amount and the effects declared in :spec."
  [{:keys [spec]}]
  {:amount (:amount spec)
   :effects (:effects spec)})

(defn short-circuit-award
  "Returns an amount and signals short-circuit."
  [{:keys [spec]}]
  {:amount (:amount spec)
   :short-circuit? true})

;; ── capability builder ────────────────────────────────────────────────────

(defn cap
  "Build a composition-capable award-amount capability with the given
   composition contract parameters."
  [id & {:keys [input-semantic output-semantic modes terminal? determinism
                effects exclusive merge entrypoint]
         :or {input-semantic :amount output-semantic :amount
              modes #{:sequential} entrypoint 'resolver-sim.composition.fixtures/identity-award}}]
  {:capability/kind :economics/award-amount
   :capability/id id
   :capability/version 1
   :capability/contract-version 1
   :entrypoint entrypoint
   :input-schema :prf/award-amount-context.v1
   :output-schema :prf/calculation-result.v1
   :composition-contract {:composition-contract/version 1
                          :composition/input {:schema-ref :prf/award-amount-context.v1
                                              :semantic-type input-semantic
                                              :cardinality :one}
                          :composition/output {:schema-ref :prf/calculation-result.v1
                                               :semantic-type output-semantic
                                               :cardinality :one}
                          :composition/roles #{:step}
                          :composition/modes modes
                          :composition/effects {:emits (or effects #{})
                                                :merge-strategy (or merge :accumulate)
                                                :exclusive-effects (or exclusive #{})}
                          :composition/control {:terminal? (boolean terminal?)
                                                :may-short-circuit? false
                                                :failure-mode :abort}
                          :composition/determinism {:required? (if (nil? determinism)
                                                                 true determinism)
                                                    :context-reads #{}
                                                    :external-reads #{}}
                          :composition/adapters {:accepted #{}
                                                 :implicit? false}
                          :composition/verification {:intermediate-output-committed? true
                                                     :evidence-contract-ref nil}}})

(def test-pack
  {:extension/id :fixture/composition-pack
   :extension/version "1.0.0"
   :extension/api-version 1
   :extension/manifest-version 1
   :extension/capabilities []})

(defn ext-map-with
  "Core capabilities plus the given fixture capabilities."
  [& caps]
  (reduce (fn [m c] (reg/register-capability m test-pack c))
          (sd/core-extension-map)
          caps))

(defn seq-combination
  "A two-stage sequential combination over the given node specs."
  [n1 n2 & {:keys [input output]
            :or {input :amount output :amount}}]
  {:combination/id :test.combination/seq
   :combination/version 1
   :combination/nodes [n1 n2]
   :combination/input {:schema-ref :prf/award-amount-context.v1 :semantic-type input}
   :combination/expected-output {:schema-ref :prf/calculation-result.v1 :semantic-type output}})

(defn node
  [id capability-ref & {:keys [version spec basis]
                        :or {version :any}}]
  {:node/id id
   :capability/ref capability-ref
   :capability/version version
   :spec spec
   :basis basis})
