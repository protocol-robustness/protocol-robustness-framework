(ns resolver-sim.protocols.sew.financial.loss-test
  "Tests for financial loss lifecycle classification.
   Covers all five lifecycle states: :normal, :loss-pending-finality,
   :loss-realized, :loss-irrecoverable, and the haircut path."
  (:require [clojure.test :refer :all]
            [resolver-sim.protocols.sew.financial.loss :as loss]
            [resolver-sim.yield.accounting :as acct]
            [resolver-sim.protocols.sew.types :as t]))

(defn- yield-pos [workflow-id & extra-fields]
  (apply merge {:token :USDC} extra-fields))

(deftest no-shortfall-is-normal
  (let [world (t/empty-world 1000)
        r     (loss/classify-loss world :USDC)]
    (is (= :normal (:loss/status r)))
    (is (false? (:loss/user-realized? r)))
    (is (nil? (:loss/reason r)))))

(deftest shortfall-without-financial-finality-pending
  (let [world (-> (t/empty-world 1000)
                  (assoc-in [:escrow-transfers 0 :escrow-state] :refunded)
                  (assoc-in [:yield/positions (t/escrow-yield-owner-id 0)]
                            (yield-pos 0 {:status :unwinding
                                          :shortfall {:fulfilled-amount 800
                                                      :deferred-amount 200
                                                      :haircut-amount 0}})))
        r     (loss/classify-loss world :USDC)]
    (is (= :loss-pending-finality (:loss/status r))
        "shortfall without financial finality must be pending, not realized")
    (is (false? (:loss/user-realized? r)))))

(deftest shortfall-during-challengeable-phase-is-risk-not-pending
  (let [world (-> (t/empty-world 1000)
                  (assoc-in [:escrow-transfers 0 :escrow-state] :disputed)
                  (assoc-in [:yield/positions (t/escrow-yield-owner-id 0)]
                            {:token :USDC :shortfall {:fulfilled-amount 800
                                                      :deferred-amount 200
                                                      :haircut-amount 0}}))
        r     (loss/classify-loss world :USDC)]
    (is (= :loss-risk (:loss/status r))
        "shortfall while disputed must be risk, not pending (challenge window still open)")
    (is (false? (:loss/user-realized? r)))))

(deftest deferred-shortfall-with-finality-realized
  (let [wf 0
        pos-key (t/escrow-yield-owner-id wf)
        world (-> (t/empty-world 1000)
                  (assoc :total-held {:USDC 500})
                  (assoc :claimable {0 {"dummy" 200}})
                  (assoc-in [:escrow-transfers wf :escrow-state] :refunded)
                  (assoc-in [:yield/positions pos-key]
                            {:token :USDC :shortfall {:fulfilled-amount 800
                                                      :deferred-amount 200
                                                      :haircut-amount 0}}))
        r     (loss/classify-loss world :USDC)]
    (is (= :loss-realized (:loss/status r))
        "deferred shortfall at finality must be realized")
    (is (false? (:loss/user-realized? r))
        "deferred-only shortfall has no haircut → user loss not realized")))

(deftest haircut-only-shortfall-realized
  (let [world (-> (t/empty-world 1000)
                  (assoc :total-held {:USDC 500})
                  (assoc :claimable {0 {"dummy" 200}})
                  (assoc-in [:escrow-transfers 0 :escrow-state] :refunded)
                  (assoc-in [:yield/positions (t/escrow-yield-owner-id 0)]
                            {:token :USDC :workflow-id 0
                             :shortfall {:fulfilled-amount 800
                                         :deferred-amount 0
                                         :haircut-amount 200}}))
        r     (loss/classify-loss world :USDC)]
    (is (= :loss-realized (:loss/status r))
        "haircut-only shortfall must be realized (haircut is a loss)")
    (is (= :haircut-loss (:loss/reason r)))
    (is (= 0.2 (:loss/user-realized? r))
        "haircut 200 / total-oblig 1000 = 0.2 pro-rata user-loss ratio")))

(deftest irrecoverable-when-held-below-threshold
  (let [world (-> (t/empty-world 1000)
                  (assoc :total-held {:USDC 50})
                  (assoc :claimable {0 {"dummy" 10}})
                  (assoc-in [:escrow-transfers 0 :escrow-state] :refunded)
                  (assoc-in [:yield/positions (t/escrow-yield-owner-id 0)]
                            {:token :USDC :workflow-id 0
                             :shortfall {:fulfilled-amount 0
                                         :deferred-amount 1000
                                         :haircut-amount 0}}))
        r     (loss/classify-loss world :USDC {:max-irrecoverable-ratio 0.2})]
    (is (= :loss-irrecoverable (:loss/status r))
        "held+claimable 60 / deferred 1000 = 0.06 < 0.2 threshold")
    (is (= :irrecoverable-shortfall (:loss/reason r)))))

(deftest loss-active-predicate
  (let [normal-world (t/empty-world 1000)
        r1 (loss/classify-loss normal-world :USDC)
        ;; Create a world with haircut shortfall (permanent loss)
        loss-world (-> (t/empty-world 1000)
                       (assoc-in [:escrow-transfers 0 :escrow-state] :refunded)
                       (assoc-in [:yield/positions (t/escrow-yield-owner-id 0)]
                                 {:token :USDC :workflow-id 0
                                  :shortfall {:fulfilled-amount 0
                                              :deferred-amount 0
                                              :haircut-amount 100}}))
        r2 (loss/classify-loss loss-world :USDC)]
    (is (false? (loss/loss-active? r1)))
    (is (true? (loss/loss-active? r2)))
    (is (false? (loss/user-loss-realized? r1)))
    (is (true? (loss/user-loss-realized? r2)))))

(deftest reclaim-deferred-clears-shortfall-on-available
  (testing "claim-deferred clears shortfall and returns reclaimed amount"
    (let [pos {:token :USDC :status :unwinding :principal 10000
               :realized-yield 500
               :shortfall {:fulfilled-amount 8000 :deferred-amount 2000 :haircut-amount 0
                           :reason :liquidity-shortfall}}
          world {:yield/risk {:mod {:USDC {:liquidity-mode :available}}}}
          result (acct/claim-deferred world :mod pos)]
      (is (nil? (:shortfall result)) "shortfall cleared")
      (is (= :withdrawn (:status result)) "status changed to withdrawn")
      (is (= 2000 (:reclaimed-amount result)) "reclaimed-amount matches deferred")
      (is (= 2500 (:realized-yield result)) "realized-yield increased by reclaimed"))))

(deftest reclaim-deferred-ignores-non-available
  (testing "claim-deferred does nothing when liquidity-mode is not available"
    (let [pos {:token :USDC :status :unwinding :principal 10000
               :shortfall {:fulfilled-amount 8000 :deferred-amount 2000 :haircut-amount 0
                           :reason :liquidity-shortfall}}
          world {:yield/risk {:mod {:USDC {:liquidity-mode :shortfall
                                           :shortfall {:available-ratio 0.8}}}}}
          result (acct/claim-deferred world :mod pos)]
      (is (some? (:shortfall result)) "shortfall unchanged")
      (is (= :unwinding (:status result)) "status unchanged"))))

(deftest shortfall-total-distinguishes-yield-vs-principal
  (testing "shortfall-total distinguishes yield-leg from principal shortfalls"
    (let [world {:yield/positions {"y1" {:token :USDC :principal 10000
                                         :shortfall {:fulfilled-amount 8000 :deferred-amount 2000
                                                     :haircut-amount 0 :reason :liquidity-shortfall
                                                     :basis-amount 5000}}
                                   "y2" {:token :USDC :principal 10000
                                         :shortfall {:fulfilled-amount 6000 :deferred-amount 0
                                                     :haircut-amount 4000 :reason :permanent-loss
                                                     :basis-amount 10000}}
                                   "y3" {:token :USDC :principal 10000
                                         :shortfall {:fulfilled-amount 10000 :deferred-amount 0
                                                     :haircut-amount 0 :basis-amount 10000}}}}
          sf (loss/shortfall-total world :USDC)]
      (is (= 3 (:positions-with-shortfall sf)))
      (is (= 1 (:yield-leg-shortfall-count sf)) "y3 fulfilled=10000 >= principal=10000 → yield-leg")
      (is (= 2 (:principal-shortfall-count sf)) "y1 fulfilled=8000 < 10000, y2 fulfilled=6000 < 10000 → principal"))))

(deftest classify-loss-multi-escrow-mixed-finality
  (testing "multi-escrow: terminal + non-terminal escrows"
    (let [world (-> (t/empty-world 1000)
                    (assoc-in [:escrow-transfers 0 :escrow-state] :refunded)
                    (assoc-in [:escrow-transfers 1 :escrow-state] :disputed)
                    (assoc-in [:yield/positions (t/escrow-yield-owner-id 0)]
                              {:token :USDC :workflow-id 0
                               :shortfall {:fulfilled-amount 0 :deferred-amount 1000
                                           :haircut-amount 0}}))
          r (loss/classify-loss world :USDC)]
      (is (= :loss-pending-finality (:loss/status r))
          "one non-terminal escrow blocks loss realization")
      (is (false? (:financially-final? r)))
      (is (false? (:loss/user-realized? r))))))
