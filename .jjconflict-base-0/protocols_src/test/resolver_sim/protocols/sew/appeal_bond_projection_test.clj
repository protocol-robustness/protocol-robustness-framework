(ns resolver-sim.protocols.sew.appeal-bond-projection-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew.appeal-bond-projection :as projection]))

(def bond-id "appeal-bond:0:0xappealer:0")

(def open-record
  {:bond/id bond-id
   :workflow/id 0
   :dispute/id "dispute:0"
   :appeal/ordinal 0
   :escalation/from-level 0
   :escalation/to-level 1
   :posting-operation/id "post-appeal-bond:0:0xappealer:0"
   :asset :USDC
   :bond/posted-amount 100
   :bond/posting-fee 5
   :bond/custodied-amount 95
   :bond/payer "0xappealer"
   :bond/funder "0xappealer"
   :posting-cause/type :appeal-posted
   :posting-cause/root "sew:appeal-bond-posted:0:0"
   :bond/status :open
   :bond/remaining-custody 95})

(def refund-disposition
  {:disposition/id "disposition:appeal-bond:0:0xappealer:0:refund"
   :bond/id bond-id
   :operation/id "return-bond:0:0xappealer"
   :kind :refund
   :allocations {"0xappealer" 95}
   :total 95
   :cause/type :resolution-reversed
   :cause/root "sew:bond-refunded:0:0xappealer"})


(def refund-claims
  {bond-id {"0xappealer" {:allocated 95 :claimable 95 :claimed 0 :asset :USDC}}})

(deftest ap-id-open-position-is-addressable-and-unsettled
  (let [p (projection/project-appeal-bonds
           {:appeal-bond/records {bond-id open-record}})]
    (is (:valid? p))
    (is (= open-record (get-in p [:appeal-bond/records bond-id])))
    (is (= 95 (get-in p [:appeal-bond/conservation :remaining])))))

(deftest ap-disp-and-ap-cons-disposed-position-conserves-custody
  (let [world {:appeal-bond/records {bond-id (assoc open-record :bond/status :disposed :bond/remaining-custody 0)}
               :appeal-bond/dispositions {(:disposition/id refund-disposition) refund-disposition}
               :appeal-bond/claims refund-claims}
        p (projection/project-appeal-bonds world)]
    (is (:valid? p))
    (is (= 95 (get-in p [:appeal-bond/dispositions (:disposition/id refund-disposition) :total])))
    (is (= 100 (get-in p [:appeal-bond/conservation :posted])))
    (is (= 5 (get-in p [:appeal-bond/conservation :fees])))
    (is (= 95 (get-in p [:appeal-bond/conservation :custodied])))
    (is (= 0 (get-in p [:appeal-bond/conservation :remaining])))))

(deftest ap-disp-rejects-duplicate-terminal-disposition
  (let [second (assoc refund-disposition :disposition/id "disposition:duplicate")
        p (projection/project-appeal-bonds
           {:appeal-bond/records {bond-id (assoc open-record :bond/status :disposed :bond/remaining-custody 0)}
            :appeal-bond/dispositions {(:disposition/id refund-disposition) refund-disposition
                                        (:disposition/id second) second}})]
    (is (false? (:valid? p)))
    (is (= :disposed-bond-disposition-count
           (-> p :errors first :errors first :code)))))

(deftest ap-cause-rejects-missing-authoritative-root
  (let [invalid (assoc refund-disposition :cause/root "")
        p (projection/project-appeal-bonds
           {:appeal-bond/records {bond-id (assoc open-record :bond/status :disposed :bond/remaining-custody 0)}
            :appeal-bond/dispositions {(:disposition/id invalid) invalid}
            :appeal-bond/claims refund-claims})]
    (is (false? (:valid? p)))
    (is (= :missing-disposition-lineage
           (-> p :errors first :errors first :code)))))


(deftest ap-claim-rejects-missing-entitlement-record
  (let [p (projection/project-appeal-bonds
           {:appeal-bond/records {bond-id (assoc open-record :bond/status :disposed :bond/remaining-custody 0)}
            :appeal-bond/dispositions {(:disposition/id refund-disposition) refund-disposition}})]
    (is (false? (:valid? p)))
    (is (= :missing-bond-claims
           (-> p :errors first :errors first :code)))))

(deftest ap-claim-conserves-allocated-entitlement-through-claiming
  (let [claims {bond-id {"0xappealer" {:allocated 95 :claimable 35 :claimed 60 :asset :USDC}}}
        p (projection/project-appeal-bonds
           {:appeal-bond/records {bond-id (assoc open-record :bond/status :disposed :bond/remaining-custody 0)}
            :appeal-bond/dispositions {(:disposition/id refund-disposition) refund-disposition}
            :appeal-bond/claims claims})]
    (is (:valid? p))))
