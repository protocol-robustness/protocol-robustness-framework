(ns resolver-sim.shortfall.provenance-v2-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.shortfall.evm-asset :as asset]
            [resolver-sim.shortfall.context-v2 :as context]
            [resolver-sim.shortfall.reserve-observation-v2 :as observation]
            [resolver-sim.shortfall.reserve-binding-v2 :as binding]))

(def address "0x1111111111111111111111111111111111111111")
(def token "0x2222222222222222222222222222222222222222")
(def allocator "0x3333333333333333333333333333333333333333")
(def op "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
(def genesis-root "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
(def instance-root "0xcccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc")
(def config-root "0xdddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd")
(def prepared-root "0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee")

(defn raw-observation []
  (let [base {:protocol-genesis-root genesis-root
              :chain-instance-genesis-root instance-root :chain-id 31337
              :configuration-root config-root :configuration-epoch 1
              :operation-id op :prepared-operation-root prepared-root
              :reserve address :token-address token :allocator allocator
              :gross-amount 100 :reserved-amount 0 :protected-amount 10
              :available-amount 90 :availability-revision 1
              :snapshot-id 1 :created-at-block 42}
        commitment (observation/economic-state-commitment base)]
    (assoc base :reserve-state-commitment commitment
           :authoritative-observation-root
           (observation/solidity-observation-root prepared-root commitment 1 42))))

(defn shortfall-context [obs]
  {:shortfall/profile :shortfall-pro-rata.v1
   :asset/root (:asset/root (asset/build-asset {:chain-id (:chain-id obs)
                                                :token-address (:token-address obs)
                                                :unit :erc20-base-unit}))
   :allocation-context-root (str "sha256:" (apply str (repeat 64 "1")))
   :candidates [{:claim/id "claim-1" :requested 90 :eligibility :eligible}]
   :availability/source observation/source
   :availability/root (:observation/root obs) :available-amount 90
   :allocation-policy {:mode :pro-rata :rounding-policy :largest-remainder
                       :fail-action-policy {:mode :pro-rata-treatment
                                            :deferred-policy :same-ratio
                                            :haircut-policy :same-ratio}}})

(deftest reserve-v2-recomputes-solidity-commitments-and-instance-bindings
  (let [input (raw-observation)
        obs (observation/build-observation input)
        ctx (shortfall-context obs)
        result (binding/build-binding {:reserve-observation input
                                       :shortfall-context ctx :operation-id op})
        alternate-input (assoc input :configuration-root
                               "0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff")
        alternate-observation (observation/build-observation alternate-input)
        alternate-binding (binding/build-binding
                           {:reserve-observation alternate-input
                            :shortfall-context (shortfall-context alternate-observation)
                            :operation-id op})]
    (is (= observation/source (:source obs)))
    ;; Independently reproduced with viem encodeAbiParameters/keccak256 using
    ;; the deployed Solidity field types and abi.encode word layout.
    (is (= "0x8d7259643668aae1842538fef7f8835c372c007c800c5250f55c799664751b15"
           (:reserve-state-commitment obs)))
    (is (= "0x1273b3fccf30d0cc0294ab275257c1683dc08d643d70df894b78ae85943e465a"
           (:authoritative-observation-root obs)))
    (is (not= (:authoritative-observation-root obs)
              (observation/solidity-observation-root
               "0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
               (:reserve-state-commitment obs) 1 42)))
    (is (re-matches #"^sha256:[0-9a-f]{64}$" (:observation/root obs)))
    (is (= instance-root (:chain-instance-genesis-root result)))
    (is (= config-root (:configuration-root result)))
    (is (not= (:binding/root result) (:binding/root alternate-binding)))
    (is (re-matches #"^sha256:[0-9a-f]{64}$" (:binding/root result)))
    (is (thrown? Exception
                 (observation/build-observation
                  (assoc input :reserve-state-commitment prepared-root))))
    (is (thrown? Exception
                 (observation/build-observation
                  (assoc input :availability-revision (.shiftLeft java.math.BigInteger/ONE 64)))))
    (is (thrown? Exception
                 (binding/build-binding {:reserve-observation input
                                         :shortfall-context (assoc ctx :available-amount 89)
                                         :operation-id op})))))
