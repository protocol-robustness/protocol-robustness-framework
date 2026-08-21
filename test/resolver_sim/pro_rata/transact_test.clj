(ns resolver-sim.pro-rata.transact-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.pro-rata.canonical-effects :as effects]
            [resolver-sim.pro-rata.transact :as sut]))

(defn- root [n] (str "sha256:" (apply str (repeat 63 "0")) n))
(def q1 (root "1"))
(def q2 (root "2"))
(def policy {:max-fixed-steps 2 :max-steps-per-effect 2})

(deftest bounded-trace-binds-the-atomic-transition
  (let [before {q1 100 q2 0}
        canonical (effects/transition before [(effects/delta q1 -40)
                                              (effects/delta q2 40)])
        transaction (sut/build-transaction
                     {:operations [{:quantity-root q1 :delta -40}
                                   {:quantity-root q2 :delta 40}]
                      :operation-semantics-root (root "3")
                      :trace-policy-root (root "4")})
        trace (sut/execute before transaction canonical policy)
        binding (sut/bind-transition canonical transaction trace
                                     (:binding-semantics/root (sut/build-binding-semantics :effect-exact)))]
    (is (= 2 (:trace/length trace)))
    (is (<= (:trace/length trace) (:trace/max-length trace)))
    (is (= (:state-after/root canonical) (:transition/output-root trace)))
    (is (string? (:transition-binding/root binding)))))

(deftest sequential-trace-failure-does-not-create-a-persistent-after-state
  (let [before {q1 5}
        canonical (effects/transition before [])
        transaction (sut/build-transaction
                     {:operations [{:quantity-root q1 :delta -10}
                                   {:quantity-root q1 :delta 10}]
                      :operation-semantics-root (root "3")
                      :trace-policy-root (root "4")})]
    (is (thrown? clojure.lang.ExceptionInfo
                 (sut/execute before transaction canonical policy)))))

(deftest effect-exact-binding-rejects-transient-quantity-churn
  (let [q3 (root "6")
        before {q1 10 q2 0 q3 0}
        canonical (effects/transition before [(effects/delta q1 -5) (effects/delta q2 5)])
        transaction (sut/build-transaction
                     {:operations [{:quantity-root q1 :delta -5}
                                   {:quantity-root q3 :delta 1}
                                   {:quantity-root q3 :delta -1}
                                   {:quantity-root q2 :delta 5}]
                      :operation-semantics-root (root "3") :trace-policy-root (root "4")})
        trace (sut/execute before transaction canonical policy)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (sut/bind-transition canonical transaction trace
                                      (:binding-semantics/root (sut/build-binding-semantics :effect-exact)))))
    (is (string? (:transition-binding/root
                  (sut/bind-transition canonical transaction trace
                                       (:binding-semantics/root (sut/build-binding-semantics :net-equivalent))
                                       :net-equivalent))))))

(deftest effect-exact-allows-valid-decomposition-within-canonical-footprint
  (let [before {q1 20 q2 0}
        canonical (effects/transition before [(effects/delta q1 -5) (effects/delta q2 5)])
        transaction (sut/build-transaction
                     {:operations [{:quantity-root q1 :delta -10}
                                   {:quantity-root q1 :delta 5}
                                   {:quantity-root q2 :delta 5}]
                      :operation-semantics-root (root "3")
                      :trace-policy-root (root "4")})
        trace (sut/execute before transaction canonical policy)
        binding (sut/bind-transition canonical transaction trace
                                     (:binding-semantics/root
                                      (sut/build-binding-semantics :effect-exact)))]
    (is (= :effect-exact (:binding/mode binding)))
    (is (= :not-exact (get-in (sut/build-binding-semantics :effect-exact)
                              [:sequence-rule])))))

(deftest binding-rejects-a-trace-with-different-net-effects
  (let [before {q1 10 q2 0}
        canonical (effects/transition before [(effects/delta q1 -5) (effects/delta q2 5)])
        transaction (sut/build-transaction
                     {:operations [{:quantity-root q1 :delta -4} {:quantity-root q2 :delta 4}]
                      :operation-semantics-root (root "3") :trace-policy-root (root "4")})
        trace (sut/execute before transaction canonical policy)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (sut/bind-transition canonical transaction trace
                                      (:binding-semantics/root
                                       (sut/build-binding-semantics :effect-exact)))))))
