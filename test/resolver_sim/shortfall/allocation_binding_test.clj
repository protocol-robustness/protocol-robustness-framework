(ns resolver-sim.shortfall.allocation-binding-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [resolver-sim.coprocessor.source :as coprocessor]
            [resolver-sim.shortfall.allocation-binding :as lineage]
            [resolver-sim.shortfall.context :as shortfall]
            [resolver-sim.shortfall.context-v2 :as context-v2]
            [resolver-sim.shortfall.evm-asset :as asset]
            [resolver-sim.shortfall.reserve-observation :as observation]
            [resolver-sim.shortfall.reserve-binding :as reserve-binding]
            [resolver-sim.shortfall.reserve-observation-v2 :as observation-v2]
            [resolver-sim.shortfall.reserve-binding-v2 :as reserve-binding-v2]
            [resolver-sim.shortfall.context-test :as fixture]))

(defn raises? [f]
  (try (f) false (catch Exception _ true)))

(def operation-id "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")

(def reserve-observation
  (let [f (json/read-str (slurp (str (coprocessor/root) "/core/tests/fixtures/onchain-shortfall-reserve-provenance.json")) :key-fn keyword)
        numeric #{:chain-id :snapshot-id :created-at-block :gross-amount
                  :reserved-amount-before :protected-amount :available-amount}
        raw (select-keys f [:chain-id :reserve :token-address :snapshot-id :operation-id
                            :allocator :created-at-block :gross-amount :reserved-amount-before
                            :protected-amount :available-amount])
        raw (reduce-kv (fn [m k v] (assoc m k (if (numeric k) (Long/parseLong v) v))) {} raw)]
    (let [raw (assoc raw :gross-amount 79 :available-amount 49)]
      (assoc raw :snapshot-hash (observation/solidity-snapshot-hash raw)))))

(def observation-artifact (observation/build-observation reserve-observation))
(def asset-root (:asset/root (asset/build-asset {:chain-id 31337
                                                 :token-address (:token-address reserve-observation)
                                                 :unit :erc20-base-unit})))

(defn reserve-binding [context]
  (reserve-binding/build-binding
   {:reserve-observation reserve-observation
    :shortfall-context (select-keys context
                                    [:shortfall/profile :asset/root :allocation-context-root
                                     :candidates :availability/source :availability/root
                                     :available-amount :allocation-policy :temporal-basis-root])
    :operation-id operation-id}))

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
    (doseq [{:keys [_label context]} substitutions]
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

(deftest allocation-lineage-validates-reserve-v2-binding
  (let [raw (let [base {:protocol-genesis-root (str "0x" (apply str (repeat 64 "1")))
                        :chain-instance-genesis-root (str "0x" (apply str (repeat 64 "2")))
                        :chain-id 31337
                        :configuration-root (str "0x" (apply str (repeat 64 "3")))
                        :configuration-epoch 1 :operation-id operation-id
                        :prepared-operation-root (str "0x" (apply str (repeat 64 "4")))
                        :reserve (:reserve reserve-observation)
                        :token-address (:token-address reserve-observation)
                        :allocator (:allocator reserve-observation)
                        :gross-amount 79 :reserved-amount 20 :protected-amount 10
                        :available-amount 49 :availability-revision 1
                        :snapshot-id 1 :created-at-block 42}
                  commitment (observation-v2/economic-state-commitment base)]
              (assoc base :reserve-state-commitment commitment
                     :authoritative-observation-root
                     (observation-v2/solidity-observation-root
                      (:prepared-operation-root base) commitment 1 42)))
        obs (observation-v2/build-observation raw)
        context-input (assoc (dissoc (fixture/shortfall-input 49) :asset/id :asset/unit)
                             :asset/root asset-root
                             :availability/source observation-v2/source
                             :availability/root (:observation/root obs))
        context (context-v2/build-context context-input)
        reserve (reserve-binding-v2/build-binding {:reserve-observation raw
                                                   :shortfall-context context-input
                                                   :operation-id operation-id})
        statement (:realized-allocation-statement (shortfall/realize context fixture/ctx))
        args {:reserve-binding reserve :reserve-observation raw
              :shortfall-context context :allocation-context fixture/ctx
              :realized-statement statement :operation-id operation-id}
        result (lineage/build-binding args)]
    (is (= (:binding/root reserve) (:reserve-binding/root result)))
    (is (= (:statement/root statement) (:realized-statement/root result)))
    (is (raises? #(lineage/build-binding
                   (assoc args :reserve-binding
                          (assoc reserve :binding/root
                                 "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")))))))
