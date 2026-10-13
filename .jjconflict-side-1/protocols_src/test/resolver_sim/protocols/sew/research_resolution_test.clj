(ns resolver-sim.protocols.sew.research-resolution-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew.snapshot-fixtures :as snap-fix]
            [resolver-sim.protocols.sew.types      :as t]
            [resolver-sim.protocols.sew.lifecycle  :as lc]
            [resolver-sim.protocols.sew.resolution :as res]))

(def alice    "0xAlice")
(def bob      "0xBob")
(def carol    "0xCarol")
(def resolver "0xResolver")
(def usdc     "0xUSDC")

(defn- base-world
  "World with one :disputed escrow."
  []
  (let [snap (snap-fix/escrow-snapshot {:escrow-fee-bps 50
                                        :max-dispute-duration 3600
                                        :appeal-window-duration 1800})
        r    (lc/create-escrow (t/empty-world 1000) alice usdc bob 1000
                               (t/make-escrow-settings {}) snap)
        w    (:world r)]
    (-> w
        (assoc-in [:escrow-transfers 0 :escrow-state]     :disputed)
        (assoc-in [:escrow-transfers 0 :dispute-resolver] resolver)
        ;; Add pending settlement
        (assoc-in [:pending-settlements 0]
                  (t/make-pending-settlement {:exists true :is-release true
                                              :appeal-deadline 2800 ;; 1000 + 1800
                                              :resolution-hash "0xhash"}))
        (assoc-in [:dispute-timestamps 0] 1000))))

(defn- make-escalation-fn
  [new-resolver]
  (fn [_world _wf _caller _level]
    {:ok true :new-resolver new-resolver}))

(deftest test-simultaneous-escalation-and-challenge
  "Researching if a participant and a third-party can both escalate/challenge."
  (let [w (base-world)
        esc-fn (make-escalation-fn "0xSenior")
        ;; Participant escalates
        r-esc (res/escalate-dispute w 0 alice esc-fn)
        ;; Challenge concurrently
        r-chal (res/challenge-resolution w 0 carol esc-fn)]

    (testing "Participant escalation succeeds"
      (is (:ok r-esc)))

    (testing "Third-party challenge fails because pending settlement is cleared by escalation"
      (let [w1 (:world r-esc)
            r-chal (res/challenge-resolution w1 0 carol esc-fn)]
        (is (false? (:ok r-chal)))
        (is (= :no-resolution-to-challenge (:error r-chal)))))))

(deftest test-resolver-rotation-mid-dispute
  "Researching if a resolver rotation can occur while a pending settlement exists."
  (let [w (base-world)
        new-resolver "0xNewResolver"
        ;; Resolver rotation should fail because a pending settlement exists
        r (res/rotate-dispute-resolver w 0 new-resolver)]

    (testing "Resolver rotation fails when a pending settlement exists"
      (is (false? (:ok r)))
      (is (= :resolution-already-pending (:error r))))))
