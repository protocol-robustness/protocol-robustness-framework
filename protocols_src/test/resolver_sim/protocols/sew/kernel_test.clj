(ns resolver-sim.protocols.sew.kernel-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew.kernel :as kernel]))

(deftest kernel-builds-a-world-with-a-transitionable-escrow
  (testing "the public constructors establish the state needed by transitions"
    (let [world (kernel/world-with-escrow
                 0
                 {:token "0xUSDC"
                  :from "0xAlice"
                  :to "0xBob"
                  :amount-after-fee 950})
          result (kernel/transition-to-disputed world 0 "0xAlice")]
      (is (= :pending (kernel/escrow-state world 0)))
      (is (true? (:ok result)))
      (is (= :disputed (kernel/escrow-state (:world result) 0)))
      (is (= 1 (:next-workflow-id world))))))

(deftest kernel-exposes-the-underlying-transfer-constructor
  (let [transfer (kernel/make-escrow-transfer
                  {:token "0xUSDC"
                   :from "0xAlice"
                   :to "0xBob"
                   :amount-after-fee 950})]
    (is (= :pending (:escrow-state transfer)))
    (is (= 950 (:amount-after-fee transfer)))))
