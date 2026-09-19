(ns resolver-sim.shortfall.allocation-binding-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [resolver-sim.shortfall.allocation-binding :as lineage]
            [resolver-sim.shortfall.context :as shortfall]
            [resolver-sim.shortfall.context-v2 :as context-v2]
            [resolver-sim.shortfall.evm-asset :as asset]
            [resolver-sim.shortfall.reserve-observation :as observation]
            [resolver-sim.shortfall.context-test :as fixture]))

(defn raises? [f]
  (try (f) false (catch Exception _ true)))

(def operation-id "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")

(def reserve-observation
  (let [f (json/read-str (slurp "coprocessor/core/tests/fixtures/onchain-shortfall-reserve-provenance.json") :key-fn keyword)
        e (:expected f)
        numeric #{:chain-id :snapshot-id :created-at-block :gross-amount
                  :reserved-amount-before :protected-amount :available-amount}
        raw (select-keys f [:chain-id :reserve :token-address :snapshot-id :operation-id
                            :allocator :created-at-block :gross-amount :reserved-amount-before
                            :protected-amount :available-amount])
        raw (reduce-kv (fn [m k v] (assoc m k (if (numeric k) (Long/parseLong v) v))) {} raw)]
    (assoc raw :snapshot-hash (:snapshot-hash e))))

(def observation-artifact (observation/build-observation reserve-observation))
(def asset-root (:asset/root (asset/build-asset {:chain-id 31337
                                                 :token-address (:token-address reserve-observation)
                                                 :unit :erc20-base-unit})))

(defn reserve-binding [context]
  {:binding/root "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
   :reserve-observation/root (:availability/root context)
   :shortfall/context-root (:shortfall/context-root context)
   :operation-id operation-id})

(deftest binds-only-the-exact-shortfall-realization
  (let [context (context-v2/build-context
                 (assoc (dissoc (fixture/shortfall-input 49) :asset/id :asset/unit)
                        :asset/root asset-root
                        :availability/source :onchain-shortfall-reserve-observation.v1
                        :availability/root (:observation/root observation-artifact)))
        realized (shortfall/realize context fixture/ctx)
        statement (:realized-allocation-statement realized)
        result (lineage/build-binding {:reserve-binding (reserve-binding context)
                                       :reserve-observation reserve-observation
                                       :shortfall-context context
                                       :allocation-context fixture/ctx
                                       :realized-statement statement
                                       :operation-id operation-id})]
    (is (= (:statement/root statement) (:realized-statement/root result)))
    (is (string? (:binding/root result)))))

(defn binding-fixture [input]
  (let [context (context-v2/build-context
                 (assoc (dissoc (fixture/shortfall-input 49) :asset/id :asset/unit)
                        :asset/root asset-root
                        :availability/source :onchain-shortfall-reserve-observation.v1
                        :availability/root (:observation/root observation-artifact)))
        realized (shortfall/realize context fixture/ctx)]
    {:context context
     :statement (:realized-allocation-statement realized)
     :input input}))

(deftest bound-input-substitutions-are-rejected
  (let [{:keys [context statement]} (binding-fixture nil)
        substitutions (vector
                       {:label :candidate-universe
                        :context (update context :candidates #(conj % {:claim/id "D" :requested 1 :eligibility :eligible}))}
                       {:label :requested-amount
                        :context (assoc context :available-amount 48)}
                       {:label :eligibility
                        :context (assoc-in context [:candidates 1 :eligibility] :eligible)}
                       {:label :allocation-policy
                        :context (assoc context :allocation-policy
                                        nil)}
                       {:label :evm-asset-root
                        :context (assoc context :asset/root
                                        "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")})]
    (doseq [{:keys [label context]} substitutions]
      (is (raises? #(lineage/build-binding
                     {:reserve-binding (reserve-binding context)
                      :reserve-observation reserve-observation
                      :shortfall-context context
                      :allocation-context fixture/ctx
                      :realized-statement statement
                      :operation-id operation-id}))))))

(deftest operation-and-digest-representation-substitutions-are-rejected
  (let [{:keys [context statement]} (binding-fixture nil)
        reserve (reserve-binding context)]
    (is (raises? #(lineage/build-binding
                   {:reserve-binding (assoc reserve :binding/root
                                            (subs (:binding/root reserve) 7))
                    :reserve-observation reserve-observation
                    :shortfall-context context
                    :allocation-context fixture/ctx
                    :realized-statement statement
                    :operation-id "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"})))
    (is (raises? #(lineage/build-binding
                   {:reserve-binding reserve
                    :reserve-observation reserve-observation
                    :shortfall-context context
                    :allocation-context fixture/ctx
                    :realized-statement statement
                    :operation-id "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"})))))

(deftest statement-substitution-is-rejected
  (let [context (context-v2/build-context
                 (assoc (dissoc (fixture/shortfall-input 49) :asset/id :asset/unit)
                        :asset/root asset-root
                        :availability/source :onchain-shortfall-reserve-observation.v1))
        realized (shortfall/realize context fixture/ctx)
        statement (:realized-allocation-statement realized)
        other (shortfall/realize
               (context-v2/build-context
                (assoc (dissoc (fixture/shortfall-input 48) :asset/id :asset/unit)
                       :asset/root asset-root
                       :availability/source :onchain-shortfall-reserve-observation.v1))
               fixture/ctx)]
    (is (raises? #(lineage/build-binding {:reserve-binding (reserve-binding context)
                                          :reserve-observation reserve-observation
                                          :shortfall-context context
                                          :allocation-context fixture/ctx
                                          :realized-statement (:realized-allocation-statement other)
                                          :operation-id operation-id})))
    (is (raises? #(lineage/build-binding {:reserve-binding (reserve-binding context)
                                          :reserve-observation reserve-observation
                                          :shortfall-context (assoc context :available-amount 48)
                                          :allocation-context fixture/ctx
                                          :realized-statement statement
                                          :operation-id operation-id})))))
