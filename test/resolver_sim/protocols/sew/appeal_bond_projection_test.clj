(ns resolver-sim.protocols.sew.appeal-bond-projection-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.contract-model.replay.execution :as execution]
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

(deftest appeal-bond-semantic-root-is-deterministic-and-sensitive
  (let [p (disposed-projection)
        same-root (projection/appeal-bond-semantic-root p)
        changed (assoc-in p [:appeal-bond/claims "bond-a" "0xchallenger" :claimed] 1)
        changed (assoc-in changed [:appeal-bond/claims "bond-a" "0xchallenger" :claimable] 89)]
    (is (= same-root (projection/appeal-bond-semantic-root p)))
    (is (not= same-root (projection/appeal-bond-semantic-root changed)))))

(deftest invalid-projection-cannot-produce-a-semantic-root
  (let [invalid (projection/project-appeal-bonds
                 {:appeal-bond/records {"bond-a" (assoc (open-record "bond-a")
                                                            :bond/status :disposed
                                                            :bond/remaining-custody 0)}
                  :appeal-bond/dispositions {}
                  :appeal-bond/claims {}})]
    (is (false? (:valid? invalid)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (projection/appeal-bond-semantic-root invalid)))))

(deftest transition-receipt-binds-operation-cause-and-semantic-roots
  (let [record (open-record "bond-a")
        world {:appeal-bond/records {"bond-a" record}
               :appeal-bond/dispositions {}
               :appeal-bond/claims {}}
        receipt (#'execution/appeal-bond-transition-receipt
                 {:seq 7 :action "execute_resolution" :params {:workflow-id 0}}
                 {:ok false :error :distribution-dependency-failure}
                 "operation-a" world world)]
    (is (= :reverted (:execution/status receipt)))
    (is (= :distribution-dependency-failure (get-in receipt [:execution/cause :type])))
    (is (= :valid (get-in receipt [:appeal-bond :pre-status])))
    (is (= :valid (get-in receipt [:appeal-bond :post-status])))
    (is (= "operation-a" (:operation/id receipt)))
    (is (= (get-in receipt [:appeal-bond :pre-root])
           (get-in receipt [:appeal-bond :post-root])))))

(deftest transition-receipt-records-successful-semantic-mutation
  (let [pre {:appeal-bond/records {"bond-a" (open-record "bond-a")}
             :appeal-bond/dispositions {}
             :appeal-bond/claims {}}
        post {:appeal-bond/records {"bond-a" (assoc (open-record "bond-a")
                                                       :bond/status :disposed
                                                       :bond/remaining-custody 0)}
              :appeal-bond/dispositions {"disp-a" {:disposition/id "disp-a"
                                                    :bond/id "bond-a"
                                                    :kind :refund
                                                    :cause/type :resolution-reversed
                                                    :cause/root "cause-a"
                                                    :allocations {"0xchallenger" 90}
                                                    :total 90}}
              :appeal-bond/claims {"bond-a" {"0xchallenger"
                                              {:allocated 90 :claimable 90 :claimed 0}}}}
        receipt (#'execution/appeal-bond-transition-receipt
                 {:seq 8 :action "execute_resolution" :params {:workflow-id 0}}
                 {:ok true}
                 "operation-b" pre post)]
    (is (= :applied (:execution/status receipt)))
    (is (not= (get-in receipt [:appeal-bond :pre-root])
              (get-in receipt [:appeal-bond :post-root])))))

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

(deftest semantic-root-commits-runtime-float-fields-exactly
  (let [record (assoc (open-record "bond-a") :bond/policy-ratio 1.0)
        p (projection/project-appeal-bonds
           {:appeal-bond/records {"bond-a" record}
            :appeal-bond/dispositions {}
            :appeal-bond/claims {}})]
    (is (:valid? p))
    (is (string? (projection/appeal-bond-semantic-root p)))))

;; ---- claim-consumption semantic boundary --------------------------------
;; Canonical Sew (`ResolverIncentiveModule`) consumes claims aggregate-only per
;; (escrow, workflow, recipient). Per-bond `claimable`/`claimed` attribution is
;; authoritative only when exactly one bond in a workflow carries benefit for a
;; recipient; otherwise consumption is aggregate-only and must not be guessed.

(defn- attribution-projection [records claims]
  {:valid? true
   :appeal-bond/records records
   :appeal-bond/dispositions {}
   :appeal-bond/claims claims})

(deftest claim-attribution-single-bond-is-authoritative
  (let [p (attribution-projection
           {"bond-a" (open-record "bond-a")}
           {"bond-a" {"0xr" {:allocated 90 :claimable 90 :claimed 0}}})
        r (projection/query-bond-claim-attribution p 0 "0xr")]
    (is (= :single-bond (:attribution r)))
    (is (= "bond-a" (:bond/id r)))))

(deftest claim-attribution-aggregate-multi-bond-is-not-bond-attributable
  (let [p (attribution-projection
           {"bond-a" (open-record "bond-a")
            "bond-b" (open-record "bond-b")}
           {"bond-a" {"0xr" {:allocated 90 :claimable 90 :claimed 0}}
            "bond-b" {"0xr" {:allocated 80 :claimable 80 :claimed 0}}})
        r (projection/query-bond-claim-attribution p 0 "0xr")]
    (is (= :aggregate-multi-bond (:attribution r)))
    (is (= ["bond-a" "bond-b"] (:bond/ids r)))))

(deftest claim-attribution-none-when-no-claimable
  (let [p (attribution-projection
           {"bond-a" (open-record "bond-a")}
           {"bond-a" {"0xr" {:allocated 90 :claimable 0 :claimed 90}}})
        r (projection/query-bond-claim-attribution p 0 "0xr")]
    (is (= :none (:attribution r)))
    (is (= [] (:bond/ids r)))))

(deftest claim-attribution-is-scoped-per-workflow
  ;; Solidity aggregates the pool by workflowId; a bond in another workflow
  ;; must not make a workflow's attribution ambiguous.
  (let [other-workflow (assoc (open-record "bond-b") :workflow/id 1)
        p (attribution-projection
           {"bond-a" (open-record "bond-a")
            "bond-b" other-workflow}
           {"bond-a" {"0xr" {:allocated 90 :claimable 90 :claimed 0}}
            "bond-b" {"0xr" {:allocated 80 :claimable 80 :claimed 0}}})
        r (projection/query-bond-claim-attribution p 0 "0xr")]
    (is (= :single-bond (:attribution r)))
    (is (= "bond-a" (:bond/id r)))))
