(ns resolver-sim.protocols.sew.appeal-bond-projection-integration-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.protocols.sew.replay.appeal-bond :as replay-appeal-bond]))

(def bond-id "appeal-bond:0:alice:0")

(def open-record
  {:bond/id bond-id
   :workflow/id 0
   :dispute/id "dispute:0"
   :appeal/ordinal 0
   :escalation/from-level 0
   :escalation/to-level 1
   :asset :USDC
   :bond/posted-amount 100
   :bond/posting-fee 5
   :bond/custodied-amount 95
   :bond/remaining-custody 95
   :bond/payer "alice"
   :bond/funder "alice"
   :posting-operation/id "post-appeal-bond:0:alice:0"
   :posting-cause/type :appeal-posted
   :posting-cause/root "sha256:appeal-posted"
   :bond/status :open})

(def open-world
  {:appeal-bond/records {bond-id open-record}
   :appeal-bond/dispositions {}
   :appeal-bond/claims {}})

(def disposed-world
  (let [record (assoc open-record :bond/status :disposed :bond/remaining-custody 0)
        disposition {:disposition/id (str "disposition:" bond-id ":refund")
                     :bond/id bond-id
                     :kind :refund
                     :cause/type :appeal-resolution
                     :cause/root "sha256:appeal-resolution"
                     :allocations {"alice" 95}
                     :total 95}]
    {:appeal-bond/records {bond-id record}
     :appeal-bond/dispositions {(:disposition/id disposition) disposition}
     :appeal-bond/claims {bond-id {"alice" {:allocated 95
                                             :claimable 95
                                             :claimed 0
                                             :asset :USDC}}}}))

(deftest reverted-transition-retains-cause-and-valid-semantic-roots
  (let [receipt (replay-appeal-bond/transition-receipt
                 {:seq 7 :action "execute_resolution" :params {:workflow-id 0}}
                 {:ok false :error :distribution-dependency-failure}
                 "operation-a" :scenario.transition/execute_resolution
                 open-world open-world)]
    (is (= :reverted (:execution/status receipt)))
    (is (= :distribution-dependency-failure (get-in receipt [:execution/cause :type])))
    (is (= :valid (get-in receipt [:appeal-bond :pre-status])))
    (is (= :valid (get-in receipt [:appeal-bond :post-status])))
    (is (= (get-in receipt [:appeal-bond :pre-root])
           (get-in receipt [:appeal-bond :post-root])))))

(deftest applied-transition-binds-before-and-after-semantic-roots
  (let [receipt (replay-appeal-bond/transition-receipt
                 {:seq 8 :action "execute_resolution" :params {:workflow-id 0}}
                 {:ok true}
                 "operation-b" :scenario.transition/execute_resolution
                 open-world disposed-world)]
    (is (= :applied (:execution/status receipt)))
    (is (= :valid (get-in receipt [:appeal-bond :pre-status])))
    (is (= :valid (get-in receipt [:appeal-bond :post-status])))
    (is (not= (get-in receipt [:appeal-bond :pre-root])
              (get-in receipt [:appeal-bond :post-root])))))
