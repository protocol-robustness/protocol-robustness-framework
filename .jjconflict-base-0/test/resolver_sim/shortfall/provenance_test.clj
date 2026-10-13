(ns resolver-sim.shortfall.provenance-test
  (:require [clojure.test :refer [deftest is]]

            [resolver-sim.shortfall.evm-asset :as asset]
            [resolver-sim.shortfall.reserve-observation :as observation]
            [resolver-sim.shortfall.context-v2 :as context-v2]
            [resolver-sim.shortfall.reserve-binding :as binding]))

(def addr "0x1111111111111111111111111111111111111111")
(def token "0x2222222222222222222222222222222222222222")
(def allocator "0x3333333333333333333333333333333333333333")
(def op "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")

(defn raises? [f]
  (try (f) false (catch Exception _ true)))

(defn observation-input []
  (let [m {:chain-id 31337 :reserve addr :token-address token :snapshot-id 1
           :operation-id op :allocator allocator :created-at-block 42
           :gross-amount 100 :reserved-amount-before 20 :protected-amount 10
           :available-amount 70}]
    (assoc m :snapshot-hash (observation/solidity-snapshot-hash m))))

(defn context-input [obs]
  {:shortfall/profile :shortfall-pro-rata.v1
   :asset/root (:asset/root (asset/build-asset {:chain-id (:chain-id obs)
                                                :token-address (:token-address obs)
                                                :unit :erc20-base-unit}))
   :allocation-context-root (str "sha256:" (apply str (repeat 64 "1")))
   :candidates [{:claim/id "A" :requested 100 :eligibility :eligible}]
   :availability/source observation/source
   :availability/root (:observation/root obs)
   :available-amount 70
   :allocation-policy {:mode :pro-rata :rounding-policy :largest-remainder
                       :fail-action-policy {:mode :pro-rata-treatment
                                            :deferred-policy :same-ratio
                                            :haircut-policy :same-ratio}}})

(deftest asset-root-is-chain-and-address-not-symbol
  (let [a (asset/build-asset {:chain-id 31337 :token-address token :unit :erc20-base-unit})
        b (asset/build-asset {:chain-id 31337 :token-address allocator :unit :erc20-base-unit})]
    (is (not= (:asset/root a) (:asset/root b)))
    (is (= a (asset/validate-asset a)))))

(deftest reserve-observation-rederives-source-and-amount
  (let [input (observation-input)
        obs (observation/build-observation input)]
    (is (= 70 (:available-amount obs)))
    (is (= (:snapshot-hash input) (:snapshot-hash obs)))
    (is (string? (:observation/root obs)))
    (is (raises? #(observation/build-observation (assoc input :available-amount 71))))
    (is (raises? #(observation/build-observation (assoc input :snapshot-hash (str "0x" (apply str (repeat 64 "b")))))))))

(deftest binding-validates-child-roots-and-operation
  (let [obs (observation/build-observation (observation-input))
        ctx (context-v2/build-context (context-input obs))
        result (binding/build-binding {:reserve-observation (observation-input)
                                       :shortfall-context (context-input obs)
                                       :operation-id op})]
    (is (= (:observation/root obs) (:reserve-observation/root result)))
    (is (= (:shortfall/context-root ctx) (:shortfall/context-root result)))
    (is (raises? #(binding/build-binding {:reserve-observation (observation-input)
                                          :shortfall-context (assoc (context-input obs) :available-amount 69)
                                          :operation-id op})))
    (is (raises? #(binding/build-binding {:reserve-observation (observation-input)
                                          :shortfall-context (context-input obs)
                                          :operation-id (str "0x" (apply str (repeat 64 "b")))})))))
