(ns resolver-sim.protocols.sew.replay.appeal-bond-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.protocols.sew.replay.appeal-bond :as sut]))

(deftest transition-receipt-records-unavailable-domain
  (let [event {:seq 3 :action "resolve-appeal" :params {:workflow-id 7}}
        result {:ok true}
        before {:block-time 100}
        after {:block-time 101}
        receipt (sut/transition-receipt event result nil :scenario.transition/resolve_appeal
                                        before after)]
    (is (= :applied (:execution/status receipt)))
    (is (= 3 (:operation/seq receipt)))
    (is (= :scenario.transition/resolve_appeal (:operation/type receipt)))
    (is (some? (:operation/id receipt)))
    (is (= {:pre-status :unavailable :post-status :unavailable}
           (select-keys (:appeal-bond receipt) [:pre-status :post-status])))))

(deftest transition-receipt-retains-reverted-operation-cause
  (let [receipt (sut/transition-receipt {:seq 4 :action "execute-resolution"}
                                        {:ok false :error :distribution-dependency-failure}
                                        "operation-root" :scenario.transition/execute_resolution
                                        {} {})]
    (is (= :reverted (:execution/status receipt)))
    (is (= {:type :distribution-dependency-failure :root "operation-root"}
           (:execution/cause receipt)))))
