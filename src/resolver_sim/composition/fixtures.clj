(ns resolver-sim.composition.fixtures
  "Composition capability builders: a capability factory, an extension-map
   seeded with core capabilities, and executable entrypoints.

   Shared by tests and by the served short-circuit demo notebook, so it lives
   on the base (non-test) classpath. These are research/demo scaffolding, not
   production protocol capabilities."
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

(defn address-award
  "Models an add-held custody adjustment: emits a custody-held-adjustment
   effect carrying the add-held kind (:add-held-kind in :spec), the custody
   account, the amount, and the owner/parameter addresses from the invocation
   context — proving addresses and kind flow through plan to invocation."
  [{:keys [addresses spec]}]
  {:amount (:amount spec)
   :effects [{:effect/type :custody/held-adjustment
              :effect/contract :prf.effect/custody-held-adjustment.v2
              :effect/action "add-held"
              :effect/account (or (:account spec) :escrow)
              :effect/amount (:amount spec)
              :held/kind (:add-held-kind spec)
              :owner/address (:owner/address addresses)
              :parameter/address (:parameter/address addresses)}]})

(defn held-adjustment-award
  "Emits a custody-held-adjustment effect with valid parameter attribution
   (context/address pair from :spec) and an add-held kind, so the emitted
   effect can be projected to a canonical held-adjustment record."
  [{:keys [addresses spec]}]
  {:amount (:amount spec)
   :effects [{:effect/type :custody/held-adjustment
              :effect/contract :prf.effect/custody-held-adjustment.v2
              :effect/action "add-held"
              :effect/account (or (:account spec) :escrow)
              :effect/amount (:amount spec)
              :held/kind (:add-held-kind spec)
              :owner/address (:owner/address addresses)
              :parameter/context (:parameter/context spec)
              :parameter/address (:parameter/address spec)}]})

;; ── capability builder ────────────────────────────────────────────────────

(defn cap
  "Build a composition-capable award-amount capability with the given
   composition contract parameters."
  [id & {:keys [input-semantic output-semantic modes terminal? determinism
                effects exclusive merge custody failure-mode entrypoint]
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
                          :composition/custody (or custody
                                                   {:direction :either
                                                    :accounts #{}
                                                    :exclusive-accounts #{}})
                          :composition/control {:terminal? (boolean terminal?)
                                                :may-short-circuit? false
                                                :failure-mode (or failure-mode :abort)}
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
  [id capability-ref & {:keys [version spec basis addresses]
                        :or {version :any}}]
  (cond-> {:node/id id
           :capability/ref capability-ref
           :capability/version version
           :spec spec
           :basis basis}
    addresses (assoc :node/addresses addresses)))
