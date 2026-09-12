(ns resolver-sim.contract-model.replay.checkpoints-test
  (:require [clojure.test :refer :all]
            [resolver-sim.contract-model.replay.checkpoints :as checkpoints]))

(deftest secure-checkpoint-update-normal-path
  (let [event {:seq 0 :event/id "evt-1"}
        data {:world-key "value"}
        acc {:states {}
             :world-checkpoints {}
             :checkpoint-log []}
        result (checkpoints/secure-checkpoint-update acc :states event data)]
    (is (= {0 {:world-key "value"}} (:states result)))
    (is (= 1 (count (:checkpoint-log result))))
    (is (= 0 (:checkpoint/index (first (:checkpoint-log result)))))
    (is (= 0 (:event/seq (first (:checkpoint-log result)))))
    (is (= "evt-1" (:event/id (first (:checkpoint-log result)))))
    (is (= :pre-event (:checkpoint/type (first (:checkpoint-log result)))))
    (is (string? (:world/hash (first (:checkpoint-log result)))))))

(deftest secure-checkpoint-update-overwrite-detection
  (let [event {:seq 0 :event/id "evt-1"}
        data1 {:version 1}
        data2 {:version 2}
        acc {:states {0 data1}
             :world-checkpoints {}
             :checkpoint-log [{:checkpoint/index 0 :event/seq 0 :checkpoint/type :post-event}]
             :diagnostics {}}
        result (checkpoints/secure-checkpoint-update acc :states event data2)]
    (is (= {0 {:version 2}} (:states result)))
    (is (some? (:diagnostics result)))
    (is (= 1 (count (get-in result [:diagnostics :checkpoint-collisions]))))
    (is (= 0 (-> result :diagnostics :checkpoint-collisions first :seq)))
    (is (= :states (-> result :diagnostics :checkpoint-collisions first :target)))))

(deftest secure-checkpoint-update-append-log
  (let [event1 {:seq 0 :event/id "evt-1"}
        event2 {:seq 1 :event/id "evt-2"}
        data1 {:idx 0}
        data2 {:idx 1}
        acc {:states {}
             :world-checkpoints {}
             :checkpoint-log []}
        after-1 (checkpoints/secure-checkpoint-update acc :states event1 data1)
        after-2 (checkpoints/secure-checkpoint-update after-1 :states event2 data2)]
    (is (= 2 (count (:checkpoint-log after-2))))
    (is (= 0 (:checkpoint/index (first (:checkpoint-log after-2)))))
    (is (= 1 (:checkpoint/index (second (:checkpoint-log after-2)))))))

(deftest secure-checkpoint-update-world-checkpoints
  (let [event {:seq 0 :event/id "evt-1"}
        world {:block-time 1000}
        acc {:states {}
             :world-checkpoints {}
             :checkpoint-log []}
        result (checkpoints/secure-checkpoint-update acc :world-checkpoints event world)]
    (is (= {0 {:block-time 1000}} (:world-checkpoints result)))
    (is (= 1 (count (:checkpoint-log result))))))

(deftest secure-checkpoint-update-fnil-checkpoint-log
  (let [event {:seq 0 :event/id "evt-1"}
        data {:val 1}
        acc {:states {}}
        result (checkpoints/secure-checkpoint-update acc :states event data)]
    (is (= {0 {:val 1}} (:states result)))
    (is (= 1 (count (:checkpoint-log result))))
    (is (= 0 (:event/seq (first (:checkpoint-log result)))))))
