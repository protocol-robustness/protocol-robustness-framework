(ns resolver-sim.protocols.sew.funds-ledger-projection-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.protocol :as proto]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.protocols.sew.types :as t]))

(deftest funds-ledger-view-happy-path
  (testing "ledger view reports expected buckets and zero drift on empty world"
    (let [world (t/empty-world 1000)
          v     (proto/io-projection sew/protocol world :funds-ledger-view)]
      (is (= 1000 (:as-of-block-time v)))
      (is (= 0 (get-in v [:global :claimable-total])))
      (is (= 0 (get-in v [:global :bond-locked-total])))
      (is (= 0 (get-in v [:conservation :drift-total])))
      (is (true? (get-in v [:conservation :holds?]))))))

(deftest funds-ledger-view-detects-drift
  (testing "ledger view surfaces non-zero drift and violations"
    (let [world (-> (t/empty-world 1000)
                    (assoc :total-held {"USDC" 100})
                    (assoc :escrow-transfers {}))
          v     (proto/io-projection sew/protocol world :funds-ledger-view)]
      (is (false? (get-in v [:conservation :holds?])))
      (is (= 100 (get-in v [:conservation :drift-total])))
      (is (= 100 (get-in v [:conservation :drift-by-token "USDC"])))
      (is (seq (get-in v [:conservation :violations]))))))

(deftest funds-ledger-view-includes-bond-distribution-and-withdrawn
  (testing "bond distribution and withdrawn/claimable totals are surfaced"
    (let [world (-> (t/empty-world 1000)
                    (assoc :claimable {0 {"0xAlice" 30 "0xBob" 10}})
                    (assoc :total-withdrawn {"USDC" 55})
                    (assoc :bond-distribution {:insurance 20 :protocol 10 :burned 5})
                    (assoc :retained-slash-reserves 7)
                    (assoc :bond-fees {"USDC" 3})
                    (assoc :bond-balances {0 {"0xResolver" 12}}))
          v     (proto/io-projection sew/protocol world :funds-ledger-view)]
      (is (= 40 (get-in v [:global :claimable-total])))
      (is (= 12 (get-in v [:global :bond-locked-total])))
      (is (= 3  (get-in v [:global :bond-fees-total])))
      (is (= 35 (get-in v [:global :bond-distribution-total])))
      (is (= 7  (get-in v [:global :retained-slash-reserves])))
      (is (= 55 (get-in v [:by-token "USDC" :withdrawn]))))))
