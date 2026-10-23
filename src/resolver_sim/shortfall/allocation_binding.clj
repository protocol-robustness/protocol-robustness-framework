(ns resolver-sim.shortfall.allocation-binding
  "Application-level lineage binding from an authoritative Shortfall reserve
   binding to one realized-allocation statement. The child artifacts retain
   their own schemas and roots; this artifact adds no allocation semantics."
  (:require [clojure.set :as set]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.shortfall.context-v2 :as context-v2]
            [resolver-sim.shortfall.reserve-observation :as observation]
            [resolver-sim.shortfall.reserve-binding :as reserve-binding-v1]
            [resolver-sim.shortfall.reserve-observation-v2 :as observation-v2]
            [resolver-sim.shortfall.reserve-binding-v2 :as reserve-binding-v2]
            [resolver-sim.shortfall.context :as shortfall]))

(def ^:const schema-version "prf/onchain-shortfall-allocation-binding.v1")
(def ^:private domain "prf.onchain-shortfall-allocation-binding.v1")

(defn- root! [field value]
  (when-not (and (string? value)
                 (or (re-matches #"sha256:[0-9a-f]{64}" value)
                     (re-matches #"[0-9a-f]{64}" value)))
    (throw (ex-info "invalid lineage root" {:field field :value value})))
  value)

(defn- sha-root! [field value]
  (when-not (and (string? value) (re-matches #"sha256:[0-9a-f]{64}" value))
    (throw (ex-info "invalid sha256 lineage root" {:field field :value value})))
  value)

(defn binding-preimage [binding]
  {:schema-version schema-version
   :reserve-binding/root (:reserve-binding/root binding)
   :realized-statement/root (:realized-statement/root binding)
   :operation-id (:operation-id binding)})

(defn build-binding
  "Validate the reserve binding, reconstruct the Shortfall realization against
   the supplied allocation context, and bind its exact realized statement root.

   The returned canonical identity contains only the two child roots and the
   operation ID. Child bodies are validation inputs, never duplicated fields."
  [{:keys [reserve-binding reserve-observation shortfall-context allocation-context realized-statement operation-id]
    :as input}]
  (let [allowed #{:reserve-binding :reserve-observation :shortfall-context
                  :allocation-context :realized-statement :operation-id}]
    (when (seq (set/difference (set (keys input)) allowed))
      (throw (ex-info "unknown Shortfall allocation binding field" {})))
    (sha-root! :reserve-binding/root (:binding/root reserve-binding))
    (let [validated-binding
          (case (:schema-version reserve-binding)
            "prf/onchain-shortfall-reserve-binding.v1"
            (reserve-binding-v1/build-binding {:reserve-observation reserve-observation
                                               :shortfall-context (select-keys shortfall-context
                                                                               [:shortfall/profile :asset/root :allocation-context-root
                                                                                :candidates :availability/source :availability/root
                                                                                :available-amount :allocation-policy :temporal-basis-root])
                                               :operation-id operation-id})
            "prf/onchain-shortfall-reserve-binding.v2"
            (reserve-binding-v2/build-binding {:reserve-observation reserve-observation
                                               :shortfall-context (select-keys shortfall-context
                                                                               [:shortfall/profile :asset/root :allocation-context-root
                                                                                :candidates :availability/source :availability/root
                                                                                :available-amount :allocation-policy :temporal-basis-root])
                                               :operation-id operation-id})
            (throw (ex-info "unsupported reserve binding schema"
                            {:schema-version (:schema-version reserve-binding)})))
          validated-observation
          (case (:schema-version reserve-binding)
            "prf/onchain-shortfall-reserve-binding.v1"
            (observation/build-observation reserve-observation)
            "prf/onchain-shortfall-reserve-binding.v2"
            (observation-v2/build-observation reserve-observation))]
      (when-not (= (:binding/root reserve-binding) (:binding/root validated-binding))
        (throw (ex-info "reserve binding root does not match its validated inputs"
                        {:expected (:binding/root validated-binding)
                         :actual (:binding/root reserve-binding)})))
      (when-not (= (:reserve-observation/root reserve-binding)
                   (:observation/root validated-observation))
        (throw (ex-info "reserve binding observation witness mismatch" {})))
      (when-not (= (:asset/root shortfall-context) (:asset/root validated-observation))
        (throw (ex-info "Shortfall asset root does not match reserve observation" {})))
      (when-not (= (:operation-id reserve-binding) (:operation-id validated-observation))
        (throw (ex-info "reserve binding operation mismatch" {}))))
    (root! :realized-statement/root (:statement/root realized-statement))
    (when-not (and (string? operation-id)
                   (re-matches #"0x[0-9a-f]{64}" operation-id))
      (throw (ex-info "invalid operation ID" {:operation-id operation-id})))
    (let [context (context-v2/build-context
                   (select-keys shortfall-context
                                [:shortfall/profile :asset/root :allocation-context-root
                                 :candidates :availability/source :availability/root
                                 :available-amount :allocation-policy :temporal-basis-root]))]
      (when-not (= (:shortfall/profile context) :shortfall-pro-rata.v1)
        (throw (ex-info "unsupported Shortfall profile" {})))
      (when-not (= (:shortfall/context-root reserve-binding)
                   (:shortfall/context-root context))
        (throw (ex-info "reserve binding Shortfall context mismatch" {})))
      (when-not (= (:availability/root context)
                   (:reserve-observation/root reserve-binding))
        (throw (ex-info "Shortfall context is not the bound reserve observation" {})))
      (when-not (= operation-id (:operation-id reserve-binding))
        (throw (ex-info "operation ID does not match reserve binding" {})))
      (let [realized (shortfall/realize context allocation-context)
            expected (:realized-allocation-statement realized)]
        (when-not (= (:statement/root expected) (:statement/root realized-statement))
          (throw (ex-info "realized statement is not the Shortfall realization"
                          {:expected (:statement/root expected)
                           :actual (:statement/root realized-statement)})))
        (let [canonical {:schema-version schema-version
                         :reserve-binding/root (:binding/root reserve-binding)
                         :realized-statement/root (:statement/root realized-statement)
                         :operation-id operation-id}]
          (assoc canonical :binding/root
                 (str "sha256:" (hc/domain-hash domain (binding-preimage canonical)))))))))
