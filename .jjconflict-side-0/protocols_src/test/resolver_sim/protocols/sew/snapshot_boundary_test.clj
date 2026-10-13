(ns resolver-sim.protocols.sew.snapshot-boundary-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.protocols.protocol :as proto]
            [resolver-sim.time.context :as time-ctx]))

(deftest test-world-snapshot-boundary
  (testing "Snapshot projects temporal context and legacy block-time"
    (let [protocol (sew/->SewProtocol)
          world {:block-time 1000 :context/time {:block-ts 1000 :step 5 :clock-source :scenario}}
          snap (proto/world-snapshot protocol world)]
      (is (= 1000 (:block-time snap)))
      (is (= 5 (:step (:time snap))))
      (is (= :scenario (:clock-source (:time snap)))))))
