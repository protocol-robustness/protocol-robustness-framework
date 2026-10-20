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

(defn invalid-code [world]
  (-> (projection/project-appeal-bonds world) :errors first :errors first :code))

(deftest ap-rejects-non-map-world
  (let [p (projection/project-appeal-bonds :malformed-world)]
    (is (false? (:valid? p)))
    (is (= :invalid-bond-world (invalid-code :malformed-world)))))

(deftest ap-rejects-malformed-identities-and-lineage-types
  (let [malformed-record (assoc open-record :bond/funder false)
        disposed (assoc open-record :bond/status :disposed :bond/remaining-custody 0)
        malformed-dispositions
        (map (fn [changes] (merge refund-disposition changes))
             [{:cause/type ""}
              {:cause/root false}
              {:cause/root 17}])]
    (is (= :invalid-bond-identity
           (invalid-code {:appeal-bond/records {bond-id malformed-record}})))
    (doseq [disposition malformed-dispositions]
      (let [world {:appeal-bond/records {bond-id disposed}
                   :appeal-bond/dispositions {(:disposition/id disposition) disposition}
                   :appeal-bond/claims refund-claims}]
        (is (= :missing-disposition-lineage (invalid-code world)))))))

(deftest ap-preserves-symbol-account-identities-used-by-the-model
  (let [record (-> open-record
                   (assoc :bond/id "appeal-bond:0:alice:0"
                          :bond/payer 'alice :bond/funder 'alice))
        disposition (-> refund-disposition
                        (assoc :disposition/id "disposition:appeal-bond:0:alice:0:refund"
                               :bond/id (:bond/id record)
                               :allocations {'alice 95}))
        claims {(:bond/id record) {'alice {:allocated 95 :claimable 95 :claimed 0 :asset :USDC}}}
        world {:appeal-bond/records {(:bond/id record) (assoc record :bond/status :disposed
                                                               :bond/remaining-custody 0)}
               :appeal-bond/dispositions {(:disposition/id disposition) disposition}
               :appeal-bond/claims claims}]
    (is (:valid? (projection/project-appeal-bonds world)))))

(deftest ap-preserves-supported-keyword-workflow-identities
  (let [workflow-id :0
        bond-id "appeal-bond::0:0xappealer:0"
        record (assoc open-record :bond/id bond-id :workflow/id workflow-id
                      :dispute/id "dispute::0")]
    (is (:valid? (projection/project-appeal-bonds
                  {:appeal-bond/records {bond-id record}})))))

(deftest ap-missing-remaining-custody-is-typed-invalid
  (let [p (projection/project-appeal-bonds
           {:appeal-bond/records {bond-id (dissoc open-record :bond/remaining-custody)}})]
    (is (false? (:valid? p)))
    (is (= :missing-bond-fields (invalid-code
                                 {:appeal-bond/records {bond-id (dissoc open-record :bond/remaining-custody)}})))))

(deftest ap-rejects-orphan-claims-and-dispositions
  (let [claim-world {:appeal-bond/records {bond-id open-record}
                     :appeal-bond/claims {"orphan-bond" {"0xappealer"
                                                          {:allocated 1 :claimable 1 :claimed 0}}}}
        disposition {:disposition/id "disposition:orphan:refund" :bond/id "orphan-bond"
                     :kind :refund :allocations {"0xappealer" 1} :total 1
                     :cause/type :resolution-reversed :cause/root "root"}
        disposition-world {:appeal-bond/records {bond-id open-record}
                           :appeal-bond/dispositions {(:disposition/id disposition) disposition}}]
    (is (= :orphan-claims (invalid-code claim-world)))
    (is (= :orphan-disposition (invalid-code disposition-world)))))

(deftest ap-malformed-disposition-value-returns-typed-invalid
  (let [disposed (assoc open-record :bond/status :disposed :bond/remaining-custody 0)
        p (projection/project-appeal-bonds
           {:appeal-bond/records {bond-id disposed}
            :appeal-bond/dispositions {"malformed-disposition" "not-a-map"}
            :appeal-bond/claims {}})]
    (is (false? (:valid? p)))
    (is (some #{:invalid-disposition-record}
              (map :code (get-in p [:errors 0 :errors]))))))

(deftest ap-rejects-altered-record-and-disposition-identities
  (let [wrong-record (assoc open-record :bond/id "another-bond")
        wrong-disposition (assoc refund-disposition :workflow/id 99)
        wrong-disposition-case (assoc refund-disposition :dispute/id "dispute:1")
        wrong-claim (assoc-in refund-claims [bond-id "0xappealer" :workflow/id] 99)
        wrong-claim-case (assoc-in refund-claims [bond-id "0xappealer" :dispute/id] "dispute:1")
        wrong-claim-round (assoc-in refund-claims [bond-id "0xappealer" :appeal/ordinal] 1)
        wrong-case-record (assoc open-record :dispute/id "dispute:1")
        disposed (assoc open-record :bond/status :disposed :bond/remaining-custody 0)]
    (is (= :bond-record-id-mismatch
           (invalid-code {:appeal-bond/records {bond-id wrong-record}})))
    (is (= :disposition-identity-mismatch
           (invalid-code {:appeal-bond/records {bond-id disposed}
                          :appeal-bond/dispositions {(:disposition/id wrong-disposition) wrong-disposition}
                          :appeal-bond/claims refund-claims})))
    (is (= :claim-identity-mismatch
           (invalid-code {:appeal-bond/records {bond-id disposed}
                          :appeal-bond/dispositions {(:disposition/id refund-disposition) refund-disposition}
                          :appeal-bond/claims wrong-claim})))
    (is (= :claim-identity-mismatch
           (invalid-code {:appeal-bond/records {bond-id disposed}
                          :appeal-bond/dispositions {(:disposition/id refund-disposition) refund-disposition}
                          :appeal-bond/claims wrong-claim-round})))
    (is (= :claim-identity-mismatch
           (invalid-code {:appeal-bond/records {bond-id disposed}
                          :appeal-bond/dispositions {(:disposition/id refund-disposition) refund-disposition}
                          :appeal-bond/claims wrong-claim-case})))
    (is (= :disposition-identity-mismatch
           (invalid-code {:appeal-bond/records {bond-id disposed}
                          :appeal-bond/dispositions {(:disposition/id wrong-disposition-case) wrong-disposition-case}
                          :appeal-bond/claims refund-claims})))
    (is (= :bond-case-or-round-mismatch
           (invalid-code {:appeal-bond/records {bond-id wrong-case-record}})))))

(deftest ap-rejects-cross-bond-and-cross-round-disposition-substitution
  (let [bond-id-2 "appeal-bond:0:0xappealer:1"
        record-2 (-> open-record (assoc :bond/id bond-id-2 :appeal/ordinal 1
                                        :bond/status :disposed :bond/remaining-custody 0))
        cross-bond (assoc refund-disposition :bond/id bond-id-2)
        cross-round (assoc refund-disposition :appeal/ordinal 1)
        records {bond-id (assoc open-record :bond/status :disposed :bond/remaining-custody 0)
                 bond-id-2 record-2}]
    (let [p (projection/project-appeal-bonds
             {:appeal-bond/records records
              :appeal-bond/dispositions {(:disposition/id cross-bond) cross-bond}
              :appeal-bond/claims refund-claims})]
      (is (false? (:valid? p)))
      (is (some #{:disposition-id-identity-mismatch}
                (map :code (get-in p [:errors 0 :errors])))))
    (is (= :disposition-identity-mismatch
           (invalid-code {:appeal-bond/records {bond-id (assoc open-record :bond/status :disposed
                                                              :bond/remaining-custody 0)}
                          :appeal-bond/dispositions {(:disposition/id cross-round) cross-round}
                          :appeal-bond/claims refund-claims})))))

(deftest ap-rejects-overconsumed-claims-and-malformed-numbers
  (let [disposed (assoc open-record :bond/status :disposed :bond/remaining-custody 0)
        overclaimed {bond-id {"0xappealer" {:allocated 95 :claimable 50 :claimed 46 :asset :USDC}}}
        bad-number (assoc open-record :bond/posting-fee "5")]
    (is (= :claim-entitlement-conservation-failure
           (invalid-code {:appeal-bond/records {bond-id disposed}
                          :appeal-bond/dispositions {(:disposition/id refund-disposition) refund-disposition}
                          :appeal-bond/claims overclaimed})))
    (is (= :invalid-bond-amounts
           (invalid-code {:appeal-bond/records {bond-id bad-number}})))
    (is (= :invalid-bond-amounts
           (invalid-code {:appeal-bond/records
                          {bond-id (assoc open-record :bond/posting-fee (+ Long/MAX_VALUE 1N))}})))
    (is (= :posted-fee-custody-mismatch
           (invalid-code {:appeal-bond/records
                          {bond-id (assoc open-record :bond/posted-amount Long/MAX_VALUE
                                          :bond/posting-fee Long/MAX_VALUE
                                          :bond/custodied-amount 1
                                          :bond/remaining-custody 1)}})))))

(deftest ap-round-arithmetic-overflow-is-typed-invalid
  (is (= :bond-case-or-round-mismatch
         (invalid-code {:appeal-bond/records
                        {bond-id (assoc open-record :escalation/from-level Long/MAX_VALUE
                                        :escalation/to-level 1)}}))))

(deftest ap-overflowing-entitlements-and-allocations-return-invalid
  (let [disposed (assoc open-record :bond/status :disposed :bond/remaining-custody 0)
        overflowing-claims {bond-id {"0xappealer" {:allocated 95 :claimable Long/MAX_VALUE
                                                    :claimed 1 :asset :USDC}}}
        overflowing-disposition (assoc refund-disposition
                                       :allocations {"recipient-a" Long/MAX_VALUE
                                                     "recipient-b" 1}
                                       :total 95)
        world {:appeal-bond/records {bond-id disposed}
               :appeal-bond/dispositions
               {(:disposition/id refund-disposition) overflowing-disposition}
               :appeal-bond/claims overflowing-claims}]
    (is (= :allocation-total-mismatch (invalid-code world)))
    (let [claim-world {:appeal-bond/records {bond-id disposed}
                       :appeal-bond/dispositions {(:disposition/id refund-disposition)
                                                  refund-disposition}
                       :appeal-bond/claims overflowing-claims}]
      (is (= :claim-entitlement-conservation-failure (invalid-code claim-world))))))

(deftest ap-rejects-inconsistent-conservation-and-invalid-root
  (let [disposed (assoc open-record :bond/status :disposed :bond/remaining-custody 0)
        bad-disposition (assoc refund-disposition :total 94)
        world {:appeal-bond/records {bond-id disposed}
               :appeal-bond/dispositions {(:disposition/id bad-disposition) bad-disposition}
               :appeal-bond/claims refund-claims}
        p (projection/project-appeal-bonds world)]
    (is (false? (:valid? p)))
    (is (thrown? clojure.lang.ExceptionInfo (projection/appeal-bond-semantic-root p)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (projection/appeal-bond-semantic-root
                  {:valid? true :appeal-bond/records {bond-id (dissoc open-record :bond/remaining-custody)}
                   :appeal-bond/dispositions {} :appeal-bond/claims {}})))))

(deftest ap-multibond-recipient-consumption-remains-aggregate-only
  (let [bond-id-2 "appeal-bond:0:0xappealer:1"
        record-2 (-> open-record (assoc :bond/id bond-id-2 :appeal/ordinal 1))
        disposition-2 (-> refund-disposition
                          (assoc :disposition/id (str "disposition:" bond-id-2 ":refund")
                                 :bond/id bond-id-2))
        claims {bond-id {"0xappealer" {:allocated 95 :claimable 95 :claimed 0 :asset :USDC}}
                bond-id-2 {"0xappealer" {:allocated 95 :claimable 95 :claimed 0 :asset :USDC}}}
        world {:appeal-bond/records {bond-id (assoc open-record :bond/status :disposed
                                                    :bond/remaining-custody 0)
                                     bond-id-2 (assoc record-2 :bond/status :disposed
                                                      :bond/remaining-custody 0)}
               :appeal-bond/dispositions {(:disposition/id refund-disposition) refund-disposition
                                          (:disposition/id disposition-2) disposition-2}
               :appeal-bond/claims claims}
        p (projection/project-appeal-bonds world)]
    (is (:valid? p))
    (is (= {:valid? true :attribution :aggregate-multi-bond
            :bond/ids [bond-id bond-id-2]}
           (projection/query-bond-claim-attribution p 0 "0xappealer")))))

(deftest ap-single-bond-recipient-claim-attribution-is-explicit
  (let [disposed (assoc open-record :bond/status :disposed :bond/remaining-custody 0)
        claims {bond-id {"0xappealer" {:allocated 95 :claimable 35 :claimed 60 :asset :USDC}}}
        p (projection/project-appeal-bonds
           {:appeal-bond/records {bond-id disposed}
            :appeal-bond/dispositions {(:disposition/id refund-disposition) refund-disposition}
            :appeal-bond/claims claims})]
    (is (:valid? p))
    (is (= {:valid? true :attribution :single-bond :bond/id bond-id}
           (projection/query-bond-claim-attribution p 0 "0xappealer")))))

(deftest ap-invalid-errors-are-stable-across-map-orderings
  (let [record-a (assoc open-record :bond/id "appeal-bond:0:a:0"
                        :bond/funder "a" :bond/payer "a" :bond/posting-fee "bad")
        record-b (assoc open-record :bond/id "appeal-bond:0:b:0"
                        :bond/funder "b" :bond/payer "b" :bond/custodied-amount -1)
        records-forward (array-map (:bond/id record-a) record-a (:bond/id record-b) record-b)
        records-reverse (array-map (:bond/id record-b) record-b (:bond/id record-a) record-a)
        errors (fn [records]
                 (:errors (projection/project-appeal-bonds {:appeal-bond/records records})))]
    (is (= (errors records-forward) (errors records-reverse)))))

(deftest ap-ambiguous-open-bond-selector-fails-closed-deterministically
  (let [bond-id-2 "appeal-bond:0:0xappealer:1"
        record-2 (assoc open-record :bond/id bond-id-2 :appeal/ordinal 1)
        records (array-map bond-id-2 record-2 bond-id open-record)
        p (projection/project-appeal-bonds {:appeal-bond/records records})
        result (projection/query-bond-conservation
                p {:workflow/id 0 :bond/funder "0xappealer"})]
    (is (:valid? p))
    (is (= :ambiguous-bond-selector (:error result)))
    (is (= [bond-id bond-id-2] (:bond-ids result)))))
