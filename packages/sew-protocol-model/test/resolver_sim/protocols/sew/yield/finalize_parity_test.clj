(ns resolver-sim.protocols.sew.yield.finalize-parity-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.protocol :as proto]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.protocols.sew.types :as t]
            [resolver-sim.protocols.sew.lifecycle :as lc]
            [resolver-sim.protocols.sew.resolution :as res]
            [resolver-sim.protocols.sew.snapshot-fixtures :as snap-fix]
            [resolver-sim.yield.registry :as yield-reg]))

(def ^:private partial-liquidity-config
  {:modules {:aave-v3 {:tokens {"USDC" {:initial-index 1.0
                                        :apy 0.08
                                        :liquidity-mode :available
                                        :loss-mode :none
                                        :failure-modes [:partial-liquidity]}}}}})

(defn- settlement-snapshot [world]
  (select-keys world
               [:claimable-v2 :total-held :total-fees :total-yield-generated
                :total-released :total-refunded]))

(defn- base-world [t0]
  (-> (proto/init-world sew/protocol {:initial-block-time t0})
      (yield-reg/apply-yield-config partial-liquidity-config)))

(defn- create-yield-escrow [world]
  (let [snap (snap-fix/escrow-snapshot {:yield-generation-module :aave-v3
                                        :yield-protocol-fee-bps 1000
                                        :appeal-window-duration 0})
        res  (lc/create-escrow world "sender" "USDC" "recipient" 100000
                               (t/make-escrow-settings {:yield-preset :to-recipient})
                               snap)]
    (is (:ok res))
    (:world res)))

(deftest partial-liquidity-release-matches-dispute-resolution
  (testing "Shared finalize-escrow-accounting: direct release vs dispute resolution"
    (let [t0 1000
          t1 315361000
          w0 (base-world t0)
          w-rel (let [w (create-yield-escrow w0)
                      w' (assoc w :block-time t1)
                      res (lc/release w' 0 "sender" (fn [_ _ _] {:allowed? true}))]
                  (is (:ok res))
                  (:world res))
          w-dis (let [w (-> (create-yield-escrow w0)
                            (assoc-in [:escrow-transfers 0 :dispute-resolver] "resolver")
                            (assoc :block-time 2000))
                      w' (:world (lc/raise-dispute w 0 "sender"))
                      w'' (assoc w' :block-time t1)
                      res (res/execute-resolution w'' 0 "resolver" true "0xparity" nil)]
                  (is (:ok res))
                  (:world res))]
      (is (= :released (t/escrow-state w-rel 0)))
      (is (= :released (t/escrow-state w-dis 0)))
      (is (= (settlement-snapshot w-rel)
             (settlement-snapshot w-dis))))))
