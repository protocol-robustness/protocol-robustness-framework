(ns resolver-sim.protocols.sew.appeal-bond-projection-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.protocols.sew.appeal-bond-projection :as projection]))

(defn open-record [id]
  {:bond/id id
   :workflow/id 0
   :appeal/ordinal 1
   :asset :USDC
   :bond/posted-amount 100
   :bond/posting-fee 10
   :bond/custodied-amount 90
   :bond/remaining-custody 90
   :bond/payer "0xchallenger"
   :bond/funder "0xchallenger"
   :posting-operation/id (str "post-" id)
   :bond/status :open})

(deftest ambiguous-unscoped-bond-query-fails-closed
  (let [projection {:valid? true
                    :appeal-bond/records
                    {"bond-a" (open-record "bond-a")
                     "bond-b" (open-record "bond-b")}
                    :appeal-bond/dispositions {}
                    :appeal-bond/claims {}}
        result (projection/query-bond-conservation
                projection
                {:workflow/id 0
                 :bond/funder "0xchallenger"
                 :appeal/ordinal 1})]
    (is (false? (:valid? result)))
    (is (= :ambiguous-bond-selector (:error result)))
    (is (= ["bond-a" "bond-b"] (:bond-ids result)))))

(defn disposed-projection []
  (let [record (assoc (open-record "bond-a")
                      :bond/status :disposed
                      :bond/remaining-custody 0)
        disposition {:disposition/id "disp-a"
                     :bond/id "bond-a"
                     :kind :refund
                     :cause/type :appeal-resolution
                     :cause/root "cause-a"
                     :allocations {"0xchallenger" 90}
                     :total 90}]
    (projection/project-appeal-bonds
     {:appeal-bond/records {"bond-a" record}
      :appeal-bond/dispositions {"disp-a" disposition}
      :appeal-bond/claims {"bond-a" {"0xchallenger"
                                      {:allocated 90 :claimable 90 :claimed 0}}}})))

(deftest scoped-query-exposes-disposition-and-entitlement
  (let [p (disposed-projection)
        result (projection/query-bond-conservation p
                                                    {:bond/id "bond-a"
                                                     :workflow/id 0
                                                     :bond/funder "0xchallenger"
                                                     :appeal/ordinal 1})]
    (is (:valid? result))
    (is (= 1 (:disposition-count result)))
    (is (= :refund (:disposition/kind result)))
    (is (= {"0xchallenger" 90} (:disposition/allocations result)))
    (is (= {:allocated 90 :claimable 90 :claimed 0}
           (get (:entitlements result) "0xchallenger")))))

(deftest selector-components-must-identify-the-same-bond
  (let [p (disposed-projection)
        result (projection/query-bond-conservation p
                                                    {:bond/id "bond-a"
                                                     :workflow/id 99})]
    (is (false? (:valid? result)))
    (is (= :bond-not-found (:error result)))))

(deftest workflow-funder-query-preserves-round-distinct-identities
  (let [a (open-record "bond-a")
        b (assoc (open-record "bond-b") :appeal/ordinal 2)
        p {:valid? true
           :appeal-bond/records {"bond-a" a "bond-b" b}
           :appeal-bond/dispositions {}
           :appeal-bond/claims {}}
        result (projection/query-bonds-by-workflow-funder p 0 "0xchallenger")]
    (is (= ["bond-a" "bond-b"] (mapv :bond/id result)))
    (is (= [1 2] (mapv :appeal/ordinal result)))))
