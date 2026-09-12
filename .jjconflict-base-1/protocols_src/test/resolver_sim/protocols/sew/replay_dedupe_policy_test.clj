(ns resolver-sim.protocols.sew.replay-dedupe-policy-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.protocol :as proto]
            [resolver-sim.protocols.sew :as sew]))

(deftest replay-sensitive-actions-are-deduped-in-dispatch
  (let [calls (atom 0)
        world {:counter 0}
        context {}
        fake-apply (fn [_ctx w _event]
                     (swap! calls inc)
                     {:ok true :world (update w :counter inc)})]
    (with-redefs [sew/apply-action fake-apply]
      (doseq [action sew/replay-sensitive-actions]
        (let [base-event {:seq 0 :time 1000 :agent "actor" :action action
                          :params {:event-id (str "evt-" action)}}
              r1 (proto/dispatch-action sew/protocol context world base-event)
              r2 (proto/dispatch-action sew/protocol context (:world r1) base-event)]
          (testing (str "dedupe policy for action " action)
            (is (:ok r1))
            (is (:ok r2))
            (is (= :applied-once (get-in r1 [:extra :idempotency])))
            (is (= :no-op-duplicate (get-in r2 [:extra :idempotency])))
            (is (= 1 (get-in r2 [:world :counter]))
                "duplicate event-id should not re-apply")))))
    (is (= (count sew/replay-sensitive-actions) @calls)
        "each replay-sensitive action should apply exactly once under duplicate dispatch")))
