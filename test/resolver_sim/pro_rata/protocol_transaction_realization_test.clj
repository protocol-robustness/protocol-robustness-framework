(ns resolver-sim.pro-rata.protocol-transaction-realization-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.pro-rata.canonical-effects :as effects]
            [resolver-sim.pro-rata.protocol-realization :as protocol]
            [resolver-sim.pro-rata.protocol-transaction-realization :as sut]
            [resolver-sim.pro-rata.transact :as transact]))

(defn- root [n] (str "sha256:" (apply str (repeat 63 "0")) n))
(def q (root "1"))
(def before {:held-credit 10 :owner :alice})
(def canonical (effects/transition {q 10} [(effects/delta q -5)]))
(def adapter (protocol/build-adapter {:protocol-id :sew :protocol-state-schema-root (root "2")
                                      :projection-semantics-root (root "3") :quantity-mapping-root (root "4")
                                      :reconstruction-semantics-root (root "5") :write-set-semantics-root (root "6")}))
(def protocol-realization
  (protocol/build-realization {:adapter adapter :canonical-transition canonical :protocol-before before
                               :write-set [[:held-credit]] :project #(hash-map q (:held-credit %))
                               :reconstruct (fn [state after _] (assoc state :held-credit (get after q)))
                               :protocol-state-root #(hc/domain-hash :world-state %)
                               :realization-semantics-root (root "7")}))
(def transaction (transact/build-transaction {:operations [{:quantity-root q :delta -5}]
                                               :operation-semantics-root (root "8") :trace-policy-root (root "9")}))
(def trace (transact/execute {q 10} transaction canonical {:max-fixed-steps 1 :max-steps-per-effect 1}))
(def transition-binding (transact/bind-transition canonical transaction trace
                                      (:binding-semantics/root (transact/build-binding-semantics :effect-exact))))

(deftest joins-two-realizations-of-one-canonical-transition
  (let [joined (sut/build {:canonical-transition-root (:canonical-effect-transition/root canonical)
                           :transition-binding transition-binding
                           :protocol-effect-realization protocol-realization})]
    (is (sut/valid? joined transition-binding protocol-realization))
    (is (= :effect-exact (:binding/mode joined)))
    (is (= (:canonical-effect-transition/root canonical) (:canonical-transition/root joined)))))

(deftest rejects-children-that-reference-different-transitions
  (is (thrown? clojure.lang.ExceptionInfo
               (sut/build {:canonical-transition-root (:canonical-effect-transition/root canonical)
                           :transition-binding (assoc transition-binding :canonical-transition/root (root "a"))
                           :protocol-effect-realization protocol-realization}))))
