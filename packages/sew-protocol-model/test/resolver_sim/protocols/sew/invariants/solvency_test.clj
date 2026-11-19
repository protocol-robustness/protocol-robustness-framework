(ns resolver-sim.protocols.sew.invariants.solvency-test
  (:require [clojure.test :refer :all]
            [resolver-sim.protocols.sew.types :as t]
            [resolver-sim.protocols.sew.invariants.solvency :as sut]))

(defn- make-position
  [& {:keys [token status principal realized-yield unrealized-yield shortfall module-id]
      :or {token :USDC
           status :active
           principal 1000
           realized-yield 50
           unrealized-yield 30
           shortfall nil
           module-id :aave-v3}}]
  (merge {:token token
          :status status
          :principal principal
          :realized-yield realized-yield
          :unrealized-yield unrealized-yield
          :module/id module-id}
         (when shortfall {:shortfall shortfall})))

(defn- make-escrow-transfer
  [& {:keys [token escrow-state amount-after-fee]
      :or {token :USDC
           escrow-state :pending
           amount-after-fee 500}}]
  {:token token
   :escrow-state escrow-state
   :amount-after-fee amount-after-fee})

(defn- make-world
  [& {:keys [positions escrow-transfers]
      :or {positions {}
           escrow-transfers {}}}]
  (merge {:yield/positions positions
          :escrow-transfers escrow-transfers}))

(deftest test-active-escrow
  (let [escrow-id :wf-1
        oid [:sew/escrow escrow-id]
        pos (make-position :token :USDC :status :active :realized-yield 50 :unrealized-yield 30)
        et (make-escrow-transfer :token :USDC :escrow-state :pending)
        world (make-world :positions {oid pos}
                          :escrow-transfers {escrow-id et})]
    (is (= 80 (sut/get-yield-held-sum world :USDC t/live-states)))))

(deftest test-active-resolver
  (let [oid "resolver:owner-1"
        pos (make-position :token :USDC :status :active :realized-yield 20 :unrealized-yield 10)
        world (make-world :positions {oid pos})]
    (is (= 30 (sut/get-yield-held-sum world :USDC t/live-states)))))

(deftest test-active-not-live
  (let [escrow-id :wf-1
        oid [:sew/escrow escrow-id]
        pos (make-position :token :USDC :status :active :realized-yield 50 :unrealized-yield 30)
        et (make-escrow-transfer :token :USDC :escrow-state :resolved) ;; not live
        world (make-world :positions {oid pos}
                          :escrow-transfers {escrow-id et})]
    (is (= 0 (sut/get-yield-held-sum world :USDC t/live-states)))))

(deftest test-unwinding-escrow
  (let [escrow-id :wf-1
        oid [:sew/escrow escrow-id]
        shortfall {:deferred-amount 200}
        pos (make-position :token :USDC :status :unwinding :shortfall shortfall)
        et (make-escrow-transfer :token :USDC :escrow-state :pending)
        world (make-world :positions {oid pos}
                          :escrow-transfers {escrow-id et})]
    (is (= 200 (sut/get-yield-held-sum world :USDC t/live-states)))))

(deftest test-unwinding-resolver
  (let [oid "resolver:owner-1"
        shortfall {:deferred-amount 200}
        pos (make-position :token :USDC :status :unwinding :shortfall shortfall)
        world (make-world :positions {oid pos})]
    (is (= 0 (sut/get-yield-held-sum world :USDC t/live-states)))))

(deftest test-withdrawn
  (let [oid :some-owner
        pos (make-position :token :USDC :status :withdrawn)
        world (make-world :positions {oid pos})]
    (is (= 0 (sut/get-yield-held-sum world :USDC t/live-states)))))

(deftest test-multiple-positions
  (let [escrow-id1 :wf-1
        escrow-id2 :wf-2
        oid1 [:sew/escrow escrow-id1]
        oid2 [:sew/escrow escrow-id2]
        pos1 (make-position :token :USDC :status :active :realized-yield 10 :unrealized-yield 5)
        pos2 (make-position :token :USDC :status :active :realized-yield 20 :unrealized-yield 15)
        et1 (make-escrow-transfer :token :USDC :escrow-state :pending)
        et2 (make-escrow-transfer :token :USDC :escrow-state :pending)
        world (make-world :positions {oid1 pos1 oid2 pos2}
                          :escrow-transfers {escrow-id1 et1 escrow-id2 et2})]
    (is (= 50 (sut/get-yield-held-sum world :USDC t/live-states)))))

(deftest test-different-token
  (let [escrow-id :wf-1
        oid [:sew/escrow escrow-id]
        pos (make-position :token :DAI :status :active :realized-yield 30 :unrealized-yield 20)
        et (make-escrow-transfer :token :DAI :escrow-state :pending)
        world (make-world :positions {oid pos}
                          :escrow-transfers {escrow-id et})]
    (is (= 0 (sut/get-yield-held-sum world :USDC t/live-states)))
    (is (= 50 (sut/get-yield-held-sum world :DAI t/live-states)))))

(deftest test-missing-keys
  (let [world (make-world)]
    (is (= 0 (sut/get-yield-held-sum world :USDC t/live-states)))))

(deftest test-solvency-holds
  (let [escrow-id :wf-1
        oid [:sew/escrow escrow-id]
        pos (make-position :token :USDC :status :active :realized-yield 50 :unrealized-yield 30)
        et (make-escrow-transfer :token :USDC :escrow-state :pending :amount-after-fee 500)
        world (make-world :positions {oid pos}
                          :escrow-transfers {escrow-id et})
        world (assoc world :total-held {:USDC 580} :resolver-stakes {})]
    (is (:holds? (sut/solvency-holds? world nil)))))
